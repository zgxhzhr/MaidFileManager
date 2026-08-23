package com.example.examplemod.network;

import com.example.examplemod.Constants;
import com.example.examplemod.data.MaidFileData;
import com.example.examplemod.data.MaidInfo;
import com.example.examplemod.service.MaidTransferService;
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
        if (maidData == null) {
            sendFeedback(player, Component.translatable("maid_file_manager.import.fail.exception", "无效的女仆数据"));
            return;
        }
        Component feedback = MaidTransferService.importMaidFromData(player, maidData);
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
        Constants.LOG.info("[maid_file_manager] handleImportBatch: player={} count={}",
                player.getName().getString(), list.size());
        int ok = 0, fail = 0;
        for (MaidFileData d : list) {
            if (d == null) { fail++; continue; }
            Component fb = MaidTransferService.importMaidFromData(player, d);
            // importMaidFromData 返回的 feedback 如果成功消息则 ok++，否则 fail++
            if (fb != null && fb.getString().contains("成功")) {
                ok++;
            } else {
                fail++;
            }
        }
        Component summary = Component.literal(String.format(java.util.Locale.ROOT,
                "批量导入完成：成功 %d 个，失败 %d 个", ok, fail));
        sendFeedback(player, summary);
        Constants.LOG.info("[maid_file_manager] handleImportBatch: DONE ok={} fail={}", ok, fail);
    }

    private static void sendFeedback(ServerPlayer player, Component message) {
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeComponent(message);
        ServerNetworkBridge.sendToPlayer(player, MaidFilePackets.ID_FEEDBACK, buf);
    }

    private static void handleRequestFileList(ServerPlayer player) {
        // 文件列表现在由客户端直接读取本地 maid_imports 目录，无需服务端处理
        // 保留此方法以兼容
        Constants.LOG.info("[maid_file_manager] handleRequestFileList: deprecated, client reads locally");
    }
}
