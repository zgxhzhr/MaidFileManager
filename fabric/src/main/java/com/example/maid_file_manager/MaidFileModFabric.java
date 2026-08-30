package com.example.maid_file_manager;

import com.example.maid_file_manager.config.MaidConfigManager;
import com.example.maid_file_manager.data.MaidFileData;
import com.example.maid_file_manager.data.MaidInfo;
import com.example.maid_file_manager.network.IMaidFileNetwork;
import com.example.maid_file_manager.network.MaidFilePackets;
import com.example.maid_file_manager.network.MaidPayload;
import com.example.maid_file_manager.service.MaidServerCommands;
import com.example.maid_file_manager.service.MaidTransferService;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;

public class MaidFileModFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        Constants.LOG.info("Maid File Manager (Fabric) loading...");

        // 1.21+ Fabric 新 API：先注册全部通道编解码器（必须在任何收发之前）
        MaidPayload.registerAll();

        // 配置系统初始化（服务端/客户端 properties 文件）
        MaidConfigManager.init(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir());

        registerC2SReceiver(MaidFilePackets.ID_REQUEST_MAID_LIST, (server, player, buf) -> {
            List<MaidInfo> list = MaidTransferService.listOwnMaids(player);
            FriendlyByteBuf out = MaidPayload.buffer();
            MaidFilePackets.writeMaidInfoList(out, list);
            ServerPlayNetworking.send(player, MaidPayload.of(MaidFilePackets.ID_MAID_LIST, out));
        });

        registerC2SReceiver(MaidFilePackets.ID_EXPORT_MAID, (server, player, buf) -> {
            int entityId = buf.readInt();
            MaidFileData data = MaidTransferService.exportMaidToData(player, entityId);
            if (data != null) {
                // 发送序列化数据给客户端，由客户端写文件
                FriendlyByteBuf out = MaidPayload.buffer();
                MaidFilePackets.writeMaidFileData(out, data);
                ServerPlayNetworking.send(player, MaidPayload.of(MaidFilePackets.ID_EXPORT_RESULT, out));
            } else {
                sendFeedback(player, Component.translatable("maid_file_manager.export.fail", "无法导出（详见日志）"));
            }
        });

        // 批量导出：数据发回客户端写文件；removeAfter=true 时移除已导出的女仆
        registerC2SReceiver(MaidFilePackets.ID_EXPORT_BATCH, (server, player, buf) -> {
            List<Integer> ids = MaidFilePackets.readIntList(buf);
            boolean removeAfter = buf.readBoolean();
            List<MaidFileData> results = new ArrayList<>(ids.size());
            int removedCount = 0;
            for (Integer entityId : ids) {
                if (entityId == null) {
                    continue;
                }
                MaidFileData data = MaidTransferService.exportMaidToData(player, entityId);
                if (data != null) {
                    results.add(data);
                    if (removeAfter) {
                        Entity e = player.level().getEntity(entityId);
                        if (e != null) {
                            e.discard();
                            removedCount++;
                        }
                    }
                }
            }
            FriendlyByteBuf out = MaidPayload.buffer();
            MaidFilePackets.writeMaidFileDataList(out, results);
            ServerPlayNetworking.send(player, MaidPayload.of(MaidFilePackets.ID_EXPORT_BATCH_RESULT, out));
            Constants.LOG.info("[maid_file_manager] EXPORT_BATCH: player={} sent={} removed={}",
                    player.getName().getString(), results.size(), removedCount);
        });

        registerC2SReceiver(MaidFilePackets.ID_IMPORT_FILE, (server, player, buf) -> {
            MaidFileData maidData = MaidFilePackets.readMaidFileData(buf);
            boolean keepBaubles = buf.readBoolean();
            if (maidData == null) {
                sendFeedback(player, Component.translatable("maid_file_manager.import.fail.exception", "无效的女仆数据"));
                return;
            }
            sendFeedback(player, MaidTransferService.importMaidFromData(player, maidData, keepBaubles));
        });

        // 批量导入：统计成功/失败并明确说明被服务端策略拦截的原因（非静默失败）
        registerC2SReceiver(MaidFilePackets.ID_IMPORT_BATCH, (server, player, buf) -> {
            List<MaidFileData> list = MaidFilePackets.readMaidFileDataList(buf);
            boolean keepBaubles = buf.readBoolean();
            int ok = 0, fail = 0;
            boolean serverBlocked = false;
            for (MaidFileData d : list) {
                if (d == null) {
                    fail++;
                    continue;
                }
                Component fb = MaidTransferService.importMaidFromData(player, d, keepBaubles);
                String s = fb == null ? "" : fb.getString();
                if (s.contains("成功")) {
                    ok++;
                } else {
                    fail++;
                    if (s.contains("禁止导入")) {
                        serverBlocked = true;
                    }
                }
            }
            StringBuilder summary = new StringBuilder(String.format(java.util.Locale.ROOT,
                    "批量导入完成：成功 %d 个，失败 %d 个", ok, fail));
            if (serverBlocked) {
                summary.append("。失败原因：服务器已禁止导入女仆（管理员在服务端设置中关闭了「允许客户端导入女仆」）");
            }
            sendFeedback(player, Component.literal(summary.toString()));
        });

        registerC2SReceiver(MaidFilePackets.ID_CLIENT_CONSENT, (server, player, buf) -> {
            boolean allow = buf.readBoolean();
            MaidConfigManager.handleClientConsent(player.getUUID(), allow);
        });

        registerC2SReceiver(MaidFilePackets.ID_SET_SERVER_CONFIG, (server, player, buf) -> {
            String key = buf.readUtf(128);
            boolean value = buf.readBoolean();
            if (!player.hasPermissions(2)) {
                sendFeedback(player, Component.translatable("maid_file_manager.config.fail.no_permission"));
                return;
            }
            MaidConfigManager.setServerConfig(key, value);
            broadcastServerConfig(server);
        });

        // OP 统一导出：返回所有在线玩家（以各玩家为中心）的女仆分组列表
        registerC2SReceiver(MaidFilePackets.ID_REQUEST_SERVER_EXPORT_LIST, (server, player, buf) -> {
            if (!player.hasPermissions(2)) {
                sendFeedback(player, Component.literal("[女仆文件管理] 统一导出仅 OP 可用"));
                return;
            }
            List<IMaidFileNetwork.PlayerMaidGroup> groups = MaidServerCommands.collectOnlinePlayerMaids(server);
            FriendlyByteBuf out = MaidPayload.buffer();
            MaidFilePackets.writePlayerMaidGroups(out, groups);
            ServerPlayNetworking.send(player, MaidPayload.of(MaidFilePackets.ID_SERVER_EXPORT_LIST, out));
        });

        // OP 统一导出提交：代各玩家导出女仆到服务端磁盘 maid_exports/<玩家名>/
        registerC2SReceiver(MaidFilePackets.ID_SERVER_EXPORT_BATCH, (server, player, buf) -> {
            if (!player.hasPermissions(2)) {
                sendFeedback(player, Component.literal("[女仆文件管理] 统一导出仅 OP 可用"));
                return;
            }
            List<IMaidFileNetwork.PlayerExportRequest> requests = MaidFilePackets.readPlayerExportRequests(buf);
            sendFeedback(player, MaidServerCommands.exportForPlayers(server, requests));
        });

        // 玩家登录 → 推送服务端配置同步；退出 → 清理同意状态
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> broadcastServerConfig(server));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                MaidConfigManager.removeClientConsent(handler.player.getUUID()));

        // /maidfile exportall 命令（仅 OP，权限校验在命令类内）
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                MaidServerCommands.register(dispatcher));

        Constants.LOG.info("Maid File Manager network registered");
    }

    /** 把服务端配置同步给所有在线玩家（登录时也走这个方法） */
    private static void broadcastServerConfig(MinecraftServer server) {
        if (server == null) {
            return;
        }
        boolean allowImport = MaidConfigManager.isClientImportAllowed();
        boolean allowBaubles = MaidConfigManager.isBaublesAllowed();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            FriendlyByteBuf out = MaidPayload.buffer();
            out.writeBoolean(allowImport);
            out.writeBoolean(allowBaubles);
            ServerPlayNetworking.send(p, MaidPayload.of(MaidFilePackets.ID_SERVER_CONFIG_SYNC, out));
        }
    }

    private static void sendFeedback(ServerPlayer player, Component message) {
        FriendlyByteBuf out = MaidPayload.buffer();
        // 与 NeoForge 版 FeedbackPayload 相同的线格式：Component 序列化为 JSON 后 writeUtf(32767)
        out.writeUtf(Component.Serializer.toJson(message, RegistryAccess.EMPTY), 32767);
        ServerPlayNetworking.send(player, MaidPayload.of(MaidFilePackets.ID_FEEDBACK, out));
    }

    /**
     * 统一注册 C2S 处理器：1.21+ 新 API 处理器已在服务端线程回调，
     * 仍保留 server.execute + try/catch 兜底（崩溃转 FEEDBACK 回执，非静默失败，与 Forge 版 C2SPacket 行为对齐）
     */
    private static void registerC2SReceiver(ResourceLocation id, C2SHandler handler) {
        ServerPlayNetworking.registerGlobalReceiver(MaidPayload.typeOf(id), (payload, context) -> {
            // 1.21 的 fabric-api Context 无 server()，用 player().getServer() 等价获取
            MinecraftServer server = context.player().getServer();
            ServerPlayer player = context.player();
            Constants.LOG.info("[maid_file_manager] S<-C RECV: id={}, player={}", id,
                    player == null ? "<null>" : player.getName().getString());
            try {
                if (player == null) {
                    Constants.LOG.warn("[maid_file_manager] C2S 包 {} 被丢弃：player 为 null", id);
                    return;
                }
                ServerPlayer p = player;
                FriendlyByteBuf buf = payload.body();
                server.execute(() -> {
                    try {
                        handler.handle(server, p, buf);
                    } catch (Throwable t) {
                        Constants.LOG.error("[maid_file_manager] C2S handler 崩溃: id={}, player={}, cause={}",
                                id, p.getName().getString(), t.toString(), t);
                        try {
                            sendFeedback(p, Component.literal("[女仆文件管理] 服务端处理失败: " + t));
                        } catch (Throwable ignored) {
                        }
                    }
                });
            } catch (Throwable t) {
                Constants.LOG.error("[maid_file_manager] C2S 读取阶段崩溃: id={}, cause={}", id, t.toString(), t);
            }
        });
    }

    @FunctionalInterface
    private interface C2SHandler {
        void handle(MinecraftServer server, ServerPlayer player, FriendlyByteBuf buf);
    }
}
