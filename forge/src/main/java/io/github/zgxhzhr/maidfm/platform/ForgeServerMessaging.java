package io.github.zgxhzhr.maidfm.platform;

import io.github.zgxhzhr.maidfm.network.MaidFilePackets;
import io.github.zgxhzhr.maidfm.network.ServerNetworkBridge;
import io.github.zgxhzhr.maidfm.platform.services.ServerMessaging;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Forge 1.20.1 的 {@link ServerMessaging} 实现。
 *
 * <p>编码用 1.20.x 的 {@code FriendlyByteBuf.writeComponent}（1.20.5+ 才移除），
 * 发送走 {@link ServerNetworkBridge#sendToPlayer}——它内部 release 自建堆缓冲，
 * 调用方不得再 release。
 */
public final class ForgeServerMessaging implements ServerMessaging {

    @Override
    public void sendFeedback(ServerPlayer player, Component message) {
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeComponent(message);
        ServerNetworkBridge.sendToPlayer(player, MaidFilePackets.ID_FEEDBACK, buf);
    }

    @Override
    public void sendImportBatchResult(ServerPlayer player, Component summary, List<Boolean> spawned) {
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeComponent(summary);
        MaidFilePackets.writeBooleanList(buf, spawned);
        ServerNetworkBridge.sendToPlayer(player, MaidFilePackets.ID_IMPORT_BATCH_RESULT, buf);
    }

    @Override
    public void sendMaidList(ServerPlayer player, java.util.List<io.github.zgxhzhr.maidfm.data.MaidInfo> list) {
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        io.github.zgxhzhr.maidfm.network.MaidInfoCodec.writeMaidInfoList(buf, list);
        ServerNetworkBridge.sendToPlayer(player, MaidFilePackets.ID_MAID_LIST, buf);
    }

    @Override
    public void sendMaidProfile(ServerPlayer player, io.github.zgxhzhr.maidfm.data.MaidProfileView view) {
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        io.github.zgxhzhr.maidfm.network.MaidProfileCodec.writeMaidProfileView(buf, view);
        ServerNetworkBridge.sendToPlayer(player, MaidFilePackets.ID_MAID_PROFILE, buf);
    }

    @Override
    public void sendServerConfig(ServerPlayer player) {
        if (player == null) {
            return;
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeBoolean(io.github.zgxhzhr.maidfm.config.MaidConfigManager.isClientImportAllowed());
        buf.writeBoolean(io.github.zgxhzhr.maidfm.config.MaidConfigManager.isBaublesAllowed());
        buf.writeBoolean(io.github.zgxhzhr.maidfm.config.MaidConfigManager.isAdvancementsAllowed());
        buf.writeBoolean(io.github.zgxhzhr.maidfm.config.MaidConfigManager.isEffectsAllowed());
        buf.writeBoolean(io.github.zgxhzhr.maidfm.config.MaidConfigManager.isInvulnerableAllowed());
        buf.writeBoolean(io.github.zgxhzhr.maidfm.config.MaidConfigManager.isCarryCurios());
        buf.writeBoolean(io.github.zgxhzhr.maidfm.config.MaidConfigManager.isBaubleStripAttributes());
        MaidFilePackets.writeStringList(buf,
                io.github.zgxhzhr.maidfm.config.MaidConfigManager.getOpBaubleBlockedList());
        MaidFilePackets.writeStringList(buf,
                io.github.zgxhzhr.maidfm.config.MaidConfigManager.getBaubleBlacklist());
        MaidFilePackets.writeStringList(buf,
                io.github.zgxhzhr.maidfm.config.MaidConfigManager.getBaubleWhitelist());
        buf.writeBoolean(io.github.zgxhzhr.maidfm.config.MaidConfigManager.isBaubleConfigManaged());
        MaidFilePackets.writeStringList(buf,
                io.github.zgxhzhr.maidfm.config.MaidConfigManager.getOpBaubleBlockedNamespaces());
        MaidFilePackets.writeStringList(buf,
                io.github.zgxhzhr.maidfm.config.MaidConfigManager.getOpBaubleBlockedCuriosSlots());
        MaidFilePackets.writeStringList(buf,
                io.github.zgxhzhr.maidfm.config.MaidConfigManager.getBaubleImportBlockedNamespaces());
        MaidFilePackets.writeStringList(buf,
                io.github.zgxhzhr.maidfm.config.MaidConfigManager.getBaubleImportBlockedCuriosSlots());
        buf.writeBoolean(io.github.zgxhzhr.maidfm.config.MaidConfigManager.isBaubleWhitelistEnforced());
        ServerNetworkBridge.sendToPlayer(player, MaidFilePackets.ID_SERVER_CONFIG_SYNC, buf);
    }

    @Override
    public boolean hasOpPermission(ServerPlayer player) {
        return player != null && player.hasPermissions(2);
    }

    @Override
    public net.minecraft.server.MinecraftServer serverOf(ServerPlayer player) {
        return player == null ? null : player.server;
    }

    @Override
    public void sendExportBatchResult(ServerPlayer player, byte[] encodedPayload) {
        // 已编码好的字节：包成 buf 后走 ID_EXPORT_BATCH_RESULT
        net.minecraft.network.FriendlyByteBuf buf = new FriendlyByteBuf(
                io.netty.buffer.Unpooled.wrappedBuffer(encodedPayload));
        ServerNetworkBridge.sendToPlayer(player, MaidFilePackets.ID_EXPORT_BATCH_RESULT, buf);
    }

    @Override
    public boolean sendChunkedDownload(ServerPlayer player, String kind, byte[] payload) {
        if (payload == null || payload.length == 0) {
            return true;
        }
        // 总量预检：接收端 ChunkedUploadAssembler 在首片就按 declared > MAX_TOTAL_BYTES 拒收，
        // 发送端照样发满几百片纯属浪费。超限直接回执失败并返回 false ——
        // 调用方据此不登记待删，避免「文件没发出去却删了女仆」。
        if (payload.length > io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.MAX_TOTAL_BYTES) {
            io.github.zgxhzhr.maidfm.Constants.LOG.warn(
                    "[maid_file_manager] 分片下发超总量上限，拒绝发送: player={} bytes={} limit={}",
                    player.getName().getString(), payload.length,
                    io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.MAX_TOTAL_BYTES);
            sendFeedback(player, net.minecraft.network.chat.Component.translatable(
                    "maid_file_manager.server.export_too_large",
                    payload.length / 1024,
                    io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.MAX_TOTAL_BYTES / 1024));
            return false;
        }
        int chunkBytes = io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.CHUNK_BYTES;
        int total = (payload.length + chunkBytes - 1) / chunkBytes;
        int sessionId = java.util.concurrent.ThreadLocalRandom.current().nextInt();
        for (int i = 0; i < total; i++) {
            int off = i * chunkBytes;
            int len = Math.min(chunkBytes, payload.length - off);
            byte[] chunk = java.util.Arrays.copyOfRange(payload, off, off + len);
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            buf.writeUtf(kind == null ? "" : kind, MaidFilePackets.MAX_TEXT_LEN);
            buf.writeVarInt(sessionId);
            buf.writeVarInt(total);
            buf.writeVarInt(i);
            buf.writeByteArray(chunk);
            ServerNetworkBridge.sendToPlayer(player, MaidFilePackets.ID_CHUNKED_DOWNLOAD, buf);
        }
        io.github.zgxhzhr.maidfm.Constants.LOG.info(
                "[maid_file_manager] 导出结果分片下发: player={} bytes={} chunks={}",
                player.getName().getString(), payload.length, total);
        return true;
    }

    @Override
    public void sendServerExportList(ServerPlayer player,
                                     java.util.List<io.github.zgxhzhr.maidfm.network.IMaidFileNetwork.PlayerMaidGroup> groups) {
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        io.github.zgxhzhr.maidfm.network.MaidInfoCodec.writePlayerMaidGroups(buf, groups);
        ServerNetworkBridge.sendToPlayer(player, MaidFilePackets.ID_SERVER_EXPORT_LIST, buf);
    }

    @Override
    public void sendBackupList(ServerPlayer player,
                               java.util.List<io.github.zgxhzhr.maidfm.network.IMaidFileNetwork.BackupOwner> owners) {
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        io.github.zgxhzhr.maidfm.network.MaidBackupCodec.writeBackupOwners(buf, owners);
        ServerNetworkBridge.sendToPlayer(player, MaidFilePackets.ID_BACKUP_LIST, buf);
    }
}
