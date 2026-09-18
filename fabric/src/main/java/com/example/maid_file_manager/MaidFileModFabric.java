package com.example.maid_file_manager;

import com.example.maid_file_manager.config.MaidConfigManager;
import com.example.maid_file_manager.data.ImportResult;
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
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
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
            if (data != null && !sendExportResults(player, List.of(data))) {
                return; // 超负载上限时已发送失败反馈
            }
            if (data == null) {
                sendFeedback(player, Component.translatable("maid_file_manager.export.fail.unknown"));
            }
        });

        // 批量导出：数据发回客户端写文件；removeAfter=true 时移除已导出的女仆
        registerC2SReceiver(MaidFilePackets.ID_EXPORT_BATCH, (server, player, buf) -> {
            List<Integer> ids = MaidFilePackets.readIntList(buf);
            boolean removeAfter = buf.readBoolean();
            // 两阶段处理：先完成全部序列化并通过体积预检、确认结果包已发送，之后才允许删除实体。
            // 若边导出边删除，预检失败时女仆已被 discard 而文件未送达，构成不可逆数据丢失
            List<MaidFileData> results = new ArrayList<>(ids.size());
            List<Integer> exportedIds = new ArrayList<>(ids.size());
            for (Integer entityId : ids) {
                if (entityId == null) {
                    continue;
                }
                MaidFileData data = MaidTransferService.exportMaidToData(player, entityId);
                if (data != null) {
                    results.add(data);
                    exportedIds.add(entityId);
                }
            }
            if (!sendExportResults(player, results)) {
                return; // 数据体积超 1 MiB 负载上限，已回执明确失败原因（非静默），实体一律未删除
            }
            int removedCount = 0;
            if (removeAfter) {
                for (Integer entityId : exportedIds) {
                    Entity e = player.level().getEntity(entityId);
                    if (e != null) {
                        e.discard();
                        removedCount++;
                    }
                }
            }
            Constants.LOG.debug("[maid_file_manager] EXPORT_BATCH: player={} sent={} removed={}",
                    player.getName().getString(), results.size(), removedCount);
        });

        registerC2SReceiver(MaidFilePackets.ID_IMPORT_FILE, (server, player, buf) -> {
            MaidFileData maidData = MaidFilePackets.readMaidFileData(buf);
            boolean keepBaubles = buf.readBoolean();
            // 单文件通道与批量通道线格式保持一致（尾部追加 deleteAfterImport）；
            // 单文件通道无对应 UI 删除流程，读掉但不触发回传
            buf.readBoolean();
            if (maidData == null) {
                sendFeedback(player, Component.translatable("maid_file_manager.import.fail.invalid"));
                return;
            }
            ImportResult result = MaidTransferService.importMaidFromData(player, maidData, keepBaubles);
            sendFeedback(player, result.message());
        });

        // 批量导入：成败统计只认 ImportResult.State 枚举，严禁反解中文展示文案
        registerC2SReceiver(MaidFilePackets.ID_IMPORT_BATCH, (server, player, buf) -> {
            List<MaidFileData> list = MaidFilePackets.readMaidFileDataList(buf);
            boolean keepBaubles = buf.readBoolean();
            boolean deleteAfterImport = buf.readBoolean();
            List<ImportResult> results = new ArrayList<>(list.size());
            for (MaidFileData d : list) {
                if (d == null) {
                    results.add(ImportResult.failed(Component.translatable(
                            "maid_file_manager.import.fail.invalid")));
                    continue;
                }
                results.add(MaidTransferService.importMaidFromData(player, d, keepBaubles));
            }
            Component summary = MaidTransferService.buildBatchSummary(results);
            if (deleteAfterImport) {
                // 请求删除源文件：回传逐项 spawned 标志（与请求顺序对齐），客户端只删成功的文件
                List<Boolean> spawned = new ArrayList<>(results.size());
                for (ImportResult r : results) {
                    spawned.add(r.spawned());
                }
                sendImportBatchResult(player, summary, spawned);
            } else {
                sendFeedback(player, summary);
            }
        });

        registerC2SReceiver(MaidFilePackets.ID_CLIENT_CONSENT, (server, player, buf) -> {
            boolean allow = buf.readBoolean();
            MaidConfigManager.handleClientConsent(player.getUUID(), allow);
        });

        registerC2SReceiver(MaidFilePackets.ID_SET_SERVER_CONFIG, (server, player, buf) -> {
            String key = buf.readUtf(MaidFilePackets.MAX_TEXT_LEN);
            boolean value = buf.readBoolean();
            if (!player.hasPermissions(2)) {
                sendFeedback(player, Component.translatable("maid_file_manager.config.fail.no_permission"));
                return;
            }
            // 未知键直接回执失败，不得把旧配置向全服重新广播
            if (!MaidConfigManager.setServerConfig(key, value)) {
                sendFeedback(player, Component.literal("[女仆文件管理] 未知配置项，修改已拒绝"));
                return;
            }
            broadcastServerConfig(server);
        });

        // OP 统一导出：返回所有在线玩家（以各玩家为中心）的女仆分组列表
        registerC2SReceiver(MaidFilePackets.ID_REQUEST_SERVER_EXPORT_LIST, (server, player, buf) -> {
            if (!player.hasPermissions(2)) {
                sendFeedback(player, Component.literal("[女仆文件管理] 统一导出仅 OP 可用"));
                return;
            }
            List<IMaidFileNetwork.PlayerMaidGroup> groups = MaidServerCommands.collectOnlinePlayerMaids(server);
            // 组数维度预检：接收端 readPlayerMaidGroups 硬上限 128，空列表时整包极小，
            // 只查字节数会在数百在线时放行、于客户端解码端炸包断连，必须先按组数拒绝
            if (groups.size() > MaidFilePackets.MAX_PLAYER_GROUPS) {
                sendFeedback(player, Component.literal(String.format(java.util.Locale.ROOT,
                        "在线玩家数超过统一导出上限（%d > %d），无法生成列表，请缩小范围后重试",
                        groups.size(), MaidFilePackets.MAX_PLAYER_GROUPS)));
                return;
            }
            FriendlyByteBuf out = MaidPayload.buffer();
            MaidFilePackets.writePlayerMaidGroups(out, groups);
            // 发送前按真实线格式预检总字节：全服女仆聚合可能远超单包上限，
            // 超限必须回执明确提示而非发出坏包把 OP 客户端断连
            int size = out.readableBytes();
            if (size > MaidFilePackets.MAX_PACKET_BYTES) {
                Constants.LOG.warn("[maid_file_manager] 统一导出列表 {} 字节超过单包上限 {}，拒绝发送",
                        size, MaidFilePackets.MAX_PACKET_BYTES);
                sendFeedback(player, Component.literal(String.format(java.util.Locale.ROOT,
                        "全服女仆列表数据过大（%d KB > %d KB），请缩小查询范围后重试",
                        size / 1024, MaidFilePackets.MAX_PACKET_BYTES / 1024)));
                out.release();
                return;
            }
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

        // 玩家登录 → 仅向该玩家单播服务端配置（不再全服广播）；退出 → 清理同意状态
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                sendServerConfig(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                MaidConfigManager.removeClientConsent(handler.player.getUUID()));

        // /maidfile exportall 命令（仅 OP，权限校验在命令类内）
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                MaidServerCommands.register(dispatcher));

        Constants.LOG.info("Maid File Manager network registered");
    }

    /**
     * 发送导出数据回客户端写文件。
     * 发送前按客户端解码端的全部契约维度（512 条目/单文件 512 KiB/整包 1 MiB 自律红线）预检，
     * 序列化字节在预检与实际发包间复用；任一维度不过只回执明确失败并返回 false，
     * 调用方（尤其 removeAfter 路径）据此保证实体绝不被 discard。
     *
     * @return true=已发送；false=序列化失败或契约预检被拒（调用方不得删除实体）
     */
    private static boolean sendExportResults(ServerPlayer player, List<MaidFileData> results) {
        List<byte[]> blobs = MaidFilePackets.serializeMaidDataBatch(results);
        if (blobs == null) {
            Constants.LOG.error("[maid_file_manager] 导出结果序列化失败，拒绝发送: player={}",
                    player.getName().getString());
            sendFeedback(player, Component.literal("[女仆文件管理] 导出数据序列化失败，请重试；如反复失败请联系服主查看日志"));
            return false;
        }
        String reject = MaidFilePackets.checkMaidDataBatchForWire(blobs, MaidFilePackets.MAX_EXPORT_IDS);
        if (reject != null) {
            Constants.LOG.warn("[maid_file_manager] 导出结果预检被拒绝: player={}, count={}, reason={}",
                    player.getName().getString(), blobs.size(), reject);
            sendFeedback(player, Component.literal("[女仆文件管理] " + reject));
            return false;
        }
        FriendlyByteBuf out = MaidPayload.buffer();
        MaidFilePackets.writeMaidDataBlobs(out, blobs);
        ServerPlayNetworking.send(player, MaidPayload.of(MaidFilePackets.ID_EXPORT_BATCH_RESULT, out));
        return true;
    }

    /** 把服务端配置单播给指定玩家（登录同步用） */
    private static void sendServerConfig(ServerPlayer player) {
        if (player == null) {
            return;
        }
        FriendlyByteBuf out = MaidPayload.buffer();
        out.writeBoolean(MaidConfigManager.isClientImportAllowed());
        out.writeBoolean(MaidConfigManager.isBaublesAllowed());
        out.writeBoolean(MaidConfigManager.isAdvancementsAllowed());
        out.writeBoolean(MaidConfigManager.isEffectsAllowed());
        ServerPlayNetworking.send(player, MaidPayload.of(MaidFilePackets.ID_SERVER_CONFIG_SYNC, out));
    }

    /** 把服务端配置同步给所有在线玩家（OP 改配置后用） */
    private static void broadcastServerConfig(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            sendServerConfig(p);
        }
    }

    private static void sendFeedback(ServerPlayer player, Component message) {
        FriendlyByteBuf out = MaidPayload.buffer();
        // 与 NeoForge 版 FeedbackPayload 相同的线格式：Component 序列化为 JSON 后 writeUtf(32767)
        out.writeUtf(Component.Serializer.toJson(message, RegistryAccess.EMPTY), 32767);
        ServerPlayNetworking.send(player, MaidPayload.of(MaidFilePackets.ID_FEEDBACK, out));
    }

    /** 批量导入 + 删除源文件：汇总文案 + 逐项 spawned 标志（顺序与请求对齐） */
    private static void sendImportBatchResult(ServerPlayer player, Component summary, List<Boolean> spawned) {
        FriendlyByteBuf out = MaidPayload.buffer();
        out.writeUtf(Component.Serializer.toJson(summary, RegistryAccess.EMPTY), 32767);
        MaidFilePackets.writeBooleanList(out, spawned);
        ServerPlayNetworking.send(player, MaidPayload.of(MaidFilePackets.ID_IMPORT_BATCH_RESULT, out));
    }

    /**
     * 统一注册 C2S 处理器：1.21+ 新 API 处理器已在服务端线程回调，
     * 仍保留 server.execute + try/catch 兜底（崩溃转 FEEDBACK 回执，非静默失败，与 Forge 版 C2SPacket 行为对齐）
     */
    private static void registerC2SReceiver(ResourceLocation id, C2SHandler handler) {
        ServerPlayNetworking.registerGlobalReceiver(MaidPayload.typeOf(id), (payload, context) -> {
            MinecraftServer server = context.server();
            ServerPlayer player = context.player();
            Constants.LOG.debug("[maid_file_manager] S<-C RECV: id={}, player={}", id,
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
                        // 异常原文可能含服务端路径/类名，仅保留在日志，回执只给通用提示
                        try {
                            sendFeedback(p, Component.literal("[女仆文件管理] 服务端处理失败，请联系服主查看日志（错误位置："
                                    + id + "）"));
                        } catch (Throwable feedbackError) {
                            Constants.LOG.debug("[maid_file_manager] 失败回执发送异常", feedbackError);
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
