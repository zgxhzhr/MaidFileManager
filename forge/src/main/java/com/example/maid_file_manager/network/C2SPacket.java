package com.example.maid_file_manager.network;

import com.example.maid_file_manager.Constants;
import com.example.maid_file_manager.data.MaidFileData;
import com.example.maid_file_manager.data.MaidInfo;
import com.example.maid_file_manager.service.MaidTransferService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 客户端到服务端的通用网络包。
 * 通过 {@link #packetId} 区分具体业务类型，{@link #data} 携带数据。
 *
 * <p>使用 Forge SimpleChannel 传输，避免为每种业务定义独立 packet 类。
 */
public record C2SPacket(ResourceLocation packetId, FriendlyByteBuf data) {

    public static void encode(C2SPacket packet, FriendlyByteBuf buf) {
        buf.writeResourceLocation(packet.packetId);
        buf.writeVarInt(packet.data.readableBytes());
        buf.writeBytes(packet.data);
    }

    public static C2SPacket decode(FriendlyByteBuf buf) {
        ResourceLocation id = buf.readResourceLocation();
        int len = buf.readVarInt();
        FriendlyByteBuf data = new FriendlyByteBuf(buf.readBytes(len));
        return new C2SPacket(id, data);
    }

    public static void handle(C2SPacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            ResourceLocation id = packet.packetId;
            Constants.LOG.info("[maid_file_manager] S<-C RECV: id={}, player={}", id,
                    player == null ? "<null>" : player.getName().getString());
            try {
                if (player == null) {
                    Constants.LOG.warn("[maid_file_manager] C2S 包 {} 被丢弃：ctx.getSender() 返回 null", id);
                    return;
                }
                FriendlyByteBuf data = packet.data;
                if (MaidFilePackets.ID_REQUEST_MAID_LIST.equals(id)) {
                    handleRequestMaidList(player);
                } else if (MaidFilePackets.ID_EXPORT_MAID.equals(id)) {
                    handleExportMaid(player, data.readInt());
                } else if (MaidFilePackets.ID_EXPORT_BATCH.equals(id)) {
                    List<Integer> ids = MaidFilePackets.readIntList(data);
                    boolean removeAfter = data.readBoolean();
                    handleExportBatch(player, ids, removeAfter);
                } else if (MaidFilePackets.ID_REQUEST_FILE_LIST.equals(id)) {
                    handleRequestFileList(player);
                } else if (MaidFilePackets.ID_IMPORT_FILE.equals(id)) {
                    handleImportFile(player, data);
                } else if (MaidFilePackets.ID_IMPORT_BATCH.equals(id)) {
                    handleImportBatch(player, data);
                } else if (MaidFilePackets.ID_CLIENT_CONSENT.equals(id)) {
                    boolean allow = data.readBoolean();
                    com.example.maid_file_manager.config.MaidConfigManager.handleClientConsent(player.getUUID(), allow);
                    Constants.LOG.info("[maid_file_manager] CLIENT_CONSENT: player={} allow={}",
                            player.getName().getString(), allow);
                } else if (MaidFilePackets.ID_SET_SERVER_CONFIG.equals(id)) {
                    String key = data.readUtf(128);
                    boolean value = data.readBoolean();
                    handleSetServerConfig(player, key, value);
                } else if (MaidFilePackets.ID_REQUEST_SERVER_EXPORT_LIST.equals(id)) {
                    handleRequestServerExportList(player);
                } else if (MaidFilePackets.ID_SERVER_EXPORT_BATCH.equals(id)) {
                    handleServerExportBatch(player, data);
                } else {
                    Constants.LOG.warn("[maid_file_manager] 未知的 C2S 包: {}", id);
                }
            } catch (Throwable t) {
                Constants.LOG.error("[maid_file_manager] C2S handler 崩溃: id={}, player={}, cause={}",
                        id, player == null ? "<null>" : player.getName().getString(), t.toString(), t);
                if (player != null) {
                    try {
                        Component feedback = Component.literal("[女仆文件管理] 服务端处理失败: " + t);
                        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                        buf.writeComponent(feedback);
                        ServerNetworkBridge.sendToPlayer(player, MaidFilePackets.ID_FEEDBACK, buf);
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
        ctx.setPacketHandled(true);
    }

    private static void handleRequestMaidList(ServerPlayer player) {
        Constants.LOG.info("[maid_file_manager] handleRequestMaidList: player={}", player.getName().getString());
        List<MaidInfo> list = MaidTransferService.listOwnMaids(player);
        Constants.LOG.info("[maid_file_manager] handleRequestMaidList: got {} maids", list.size());
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        MaidFilePackets.writeMaidInfoList(buf, list);
        ServerNetworkBridge.sendToPlayer(player, MaidFilePackets.ID_MAID_LIST, buf);
        Constants.LOG.info("[maid_file_manager] handleRequestMaidList: S->C MAID_LIST sent");
    }

    private static void handleExportMaid(ServerPlayer player, int entityId) {
        Constants.LOG.info("[maid_file_manager] handleExportMaid: player={} entityId={}",
                player.getName().getString(), entityId);
        MaidFileData data = MaidTransferService.exportMaidToData(player, entityId);
        if (data != null) {
            // 发送序列化数据给客户端，由客户端写文件
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            MaidFilePackets.writeMaidFileData(buf, data);
            ServerNetworkBridge.sendToPlayer(player, MaidFilePackets.ID_EXPORT_RESULT, buf);
            Constants.LOG.info("[maid_file_manager] handleExportMaid: SUCCESS data sent to client");
        } else {
            sendFeedback(player, Component.translatable("maid_file_manager.export.fail", "无法导出（详见日志）"));
            Constants.LOG.warn("[maid_file_manager] handleExportMaid: FAILED data=null");
        }
    }

    private static void handleImportFile(ServerPlayer player, FriendlyByteBuf data) {
        Constants.LOG.info("[maid_file_manager] handleImportFile: player={}", player.getName().getString());
        MaidFileData maidData = MaidFilePackets.readMaidFileData(data);
        boolean keepBaubles = data.readBoolean();
        if (maidData == null) {
            sendFeedback(player, Component.translatable("maid_file_manager.import.fail.exception", "无效的女仆数据"));
            return;
        }
        Component feedback = MaidTransferService.importMaidFromData(player, maidData, keepBaubles);
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeComponent(feedback);
        ServerNetworkBridge.sendToPlayer(player, MaidFilePackets.ID_FEEDBACK, buf);
        Constants.LOG.info("[maid_file_manager] handleImportFile: result sent to client");
    }

    private static void handleExportBatch(ServerPlayer player, List<Integer> ids, boolean removeAfter) {
        Constants.LOG.info("[maid_file_manager] handleExportBatch: player={} count={} removeAfter={}",
                player.getName().getString(), ids.size(), removeAfter);
        List<MaidFileData> results = new ArrayList<>(ids.size());
        int removedCount = 0;
        for (Integer entityId : ids) {
            if (entityId == null) continue;
            MaidFileData data = MaidTransferService.exportMaidToData(player, entityId);
            if (data != null) {
                results.add(data);
                if (removeAfter) {
                    Entity e = player.level().getEntity(entityId);
                    if (e != null) {
                        e.discard();
                        removedCount++;
                        Constants.LOG.info("[maid_file_manager] handleExportBatch: discarded entityId={}", entityId);
                    }
                }
            }
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        MaidFilePackets.writeMaidFileDataList(buf, results);
        ServerNetworkBridge.sendToPlayer(player, MaidFilePackets.ID_EXPORT_BATCH_RESULT, buf);
        Constants.LOG.info("[maid_file_manager] handleExportBatch: DONE sent={} removed={}", results.size(), removedCount);
    }

    private static void handleImportBatch(ServerPlayer player, FriendlyByteBuf data) {
        List<MaidFileData> list = MaidFilePackets.readMaidFileDataList(data);
        boolean keepBaubles = data.readBoolean();
        Constants.LOG.info("[maid_file_manager] handleImportBatch: player={} count={} keepBaubles={}",
                player.getName().getString(), list.size(), keepBaubles);
        int ok = 0, fail = 0;
        boolean serverBlocked = false;
        for (MaidFileData d : list) {
            if (d == null) { fail++; continue; }
            Component fb = MaidTransferService.importMaidFromData(player, d, keepBaubles);
            // importMaidFromData 返回的 feedback 如果成功消息则 ok++，否则 fail++
            String s = fb == null ? "" : fb.getString();
            if (s.contains("成功")) {
                ok++;
            } else {
                fail++;
                // 明确写明被服务端策略拦截的原因（非静默失败）
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
        Constants.LOG.info("[maid_file_manager] handleImportBatch: DONE ok={} fail={}", ok, fail);
    }

    private static void sendFeedback(ServerPlayer player, Component message) {
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeComponent(message);
        ServerNetworkBridge.sendToPlayer(player, MaidFilePackets.ID_FEEDBACK, buf);
    }

    /** 客户端请求修改服务端配置：先校验 OP 权限，再写文件并广播同步给所有在线客户端 */
    private static void handleSetServerConfig(ServerPlayer player, String key, boolean value) {
        if (!player.hasPermissions(2)) {
            Constants.LOG.warn("[maid_file_manager] SET_SERVER_CONFIG rejected (no OP): player={} key={}",
                    player.getName().getString(), key);
            sendFeedback(player, Component.translatable("maid_file_manager.config.fail.no_permission"));
            return;
        }
        com.example.maid_file_manager.config.MaidConfigManager.setServerConfig(key, value);
        Constants.LOG.info("[maid_file_manager] SET_SERVER_CONFIG: player={} key={} value={}",
                player.getName().getString(), key, value);
        broadcastServerConfig(player.server);
    }

    /** OP 统一导出：返回所有在线玩家（以各玩家为中心）的女仆分组列表 */
    private static void handleRequestServerExportList(ServerPlayer player) {
        if (!player.hasPermissions(2)) {
            sendFeedback(player, Component.literal("[女仆文件管理] 统一导出仅 OP 可用"));
            return;
        }
        List<IMaidFileNetwork.PlayerMaidGroup> groups =
                com.example.maid_file_manager.service.MaidServerCommands.collectOnlinePlayerMaids(player.server);
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        MaidFilePackets.writePlayerMaidGroups(buf, groups);
        ServerNetworkBridge.sendToPlayer(player, MaidFilePackets.ID_SERVER_EXPORT_LIST, buf);
        Constants.LOG.info("[maid_file_manager] SERVER_EXPORT_LIST sent to OP {}: {} players",
                player.getName().getString(), groups.size());
    }

    /** OP 统一导出提交：代各玩家导出女仆到服务端磁盘 maid_exports/<玩家名>/ */
    private static void handleServerExportBatch(ServerPlayer player, FriendlyByteBuf data) {
        if (!player.hasPermissions(2)) {
            sendFeedback(player, Component.literal("[女仆文件管理] 统一导出仅 OP 可用"));
            return;
        }
        List<IMaidFileNetwork.PlayerExportRequest> requests = MaidFilePackets.readPlayerExportRequests(data);
        Component result = com.example.maid_file_manager.service.MaidServerCommands
                .exportForPlayers(player.server, requests);
        sendFeedback(player, result);
        Constants.LOG.info("[maid_file_manager] SERVER_EXPORT_BATCH by OP {}: {}",
                player.getName().getString(), result.getString());
    }

    /** 把服务端配置同步给所有在线玩家（登录时也走这个方法） */
    public static void broadcastServerConfig(net.minecraft.server.MinecraftServer server) {
        if (server == null) {
            return;
        }
        boolean allowImport = com.example.maid_file_manager.config.MaidConfigManager.isClientImportAllowed();
        boolean allowBaubles = com.example.maid_file_manager.config.MaidConfigManager.isBaublesAllowed();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            buf.writeBoolean(allowImport);
            buf.writeBoolean(allowBaubles);
            ServerNetworkBridge.sendToPlayer(p, MaidFilePackets.ID_SERVER_CONFIG_SYNC, buf);
        }
    }

    private static void handleRequestFileList(ServerPlayer player) {
        // 文件列表现在由客户端直接读取本地 maid_imports 目录，无需服务端处理
        // 保留此方法以兼容
        Constants.LOG.info("[maid_file_manager] handleRequestFileList: deprecated, client reads locally");
    }
}
