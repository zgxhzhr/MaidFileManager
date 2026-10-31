package io.github.zgxhzhr.maidfm.platform;

import io.github.zgxhzhr.maidfm.MaidFileModFabric;
import io.github.zgxhzhr.maidfm.network.MaidFilePackets;
import io.github.zgxhzhr.maidfm.network.MaidPayload;
import io.github.zgxhzhr.maidfm.service.ServerMessaging;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Fabric 1.21.1 的 {@link ServerMessaging} 实现。
 *
 * <p>本端没有 payload record，用统一的 {@code MaidPayload} 包一个裸缓冲。
 * 文本组件序列化为 JSON 后 {@code writeUtf(32767)} —— 与 NeoForge 版
 * {@code FeedbackPayload} 的线格式一致（两端协议必须逐字节相同）。
 */
public final class FabricServerMessaging implements ServerMessaging {

    @Override
    public void sendFeedback(ServerPlayer player, Component message) {
        FriendlyByteBuf out = MaidPayload.buffer();
        out.writeUtf(Component.Serializer.toJson(message, RegistryAccess.EMPTY), 32767);
        ServerPlayNetworking.send(player, MaidPayload.of(MaidFilePackets.ID_FEEDBACK, out));
    }

    @Override
    public void sendImportBatchResult(ServerPlayer player, Component summary, List<Boolean> spawned) {
        FriendlyByteBuf out = MaidPayload.buffer();
        out.writeUtf(Component.Serializer.toJson(summary, RegistryAccess.EMPTY), 32767);
        MaidFilePackets.writeBooleanList(out, spawned);
        ServerPlayNetworking.send(player, MaidPayload.of(MaidFilePackets.ID_IMPORT_BATCH_RESULT, out));
    }

    @Override
    public void sendMaidList(ServerPlayer player, java.util.List<io.github.zgxhzhr.maidfm.data.MaidInfo> list) {
        net.minecraft.network.FriendlyByteBuf out = io.github.zgxhzhr.maidfm.network.MaidPayload.buffer();
        io.github.zgxhzhr.maidfm.network.MaidInfoCodec.writeMaidInfoList(out, list);
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(
                player, io.github.zgxhzhr.maidfm.network.MaidPayload.of(
                        io.github.zgxhzhr.maidfm.network.MaidFilePackets.ID_MAID_LIST, out));
    }

    @Override
    public void sendMaidProfile(ServerPlayer player, io.github.zgxhzhr.maidfm.data.MaidProfileView view) {
        net.minecraft.network.FriendlyByteBuf out = io.github.zgxhzhr.maidfm.network.MaidPayload.buffer();
        io.github.zgxhzhr.maidfm.network.MaidProfileCodec.writeMaidProfileView(out, view);
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(
                player, io.github.zgxhzhr.maidfm.network.MaidPayload.of(
                        io.github.zgxhzhr.maidfm.network.MaidFilePackets.ID_MAID_PROFILE, out));
    }

    @Override
    public void sendServerConfig(ServerPlayer player) {
        io.github.zgxhzhr.maidfm.MaidFileModFabric.sendServerConfig(player);
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
        net.minecraft.network.FriendlyByteBuf out = io.github.zgxhzhr.maidfm.network.MaidPayload.buffer();
        out.writeBytes(encodedPayload);
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                io.github.zgxhzhr.maidfm.network.MaidPayload.of(
                        io.github.zgxhzhr.maidfm.network.MaidFilePackets.ID_EXPORT_BATCH_RESULT, out));
    }

    @Override
    public boolean sendChunkedDownload(ServerPlayer player, String kind, byte[] payload) {
        return MaidFileModFabric.sendChunkedDownload(player, kind, payload);
    }

    @Override
    public void sendServerExportList(ServerPlayer player,
                                     java.util.List<io.github.zgxhzhr.maidfm.network.IMaidFileNetwork.PlayerMaidGroup> groups) {
        net.minecraft.network.FriendlyByteBuf out1 = io.github.zgxhzhr.maidfm.network.MaidPayload.buffer();
        io.github.zgxhzhr.maidfm.network.MaidInfoCodec.writePlayerMaidGroups(out1, groups);
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                io.github.zgxhzhr.maidfm.network.MaidPayload.of(
                        io.github.zgxhzhr.maidfm.network.MaidFilePackets.ID_SERVER_EXPORT_LIST, out1));
    }

    @Override
    public void sendBackupList(ServerPlayer player,
                               java.util.List<io.github.zgxhzhr.maidfm.network.IMaidFileNetwork.BackupOwner> owners) {
        net.minecraft.network.FriendlyByteBuf out2 = io.github.zgxhzhr.maidfm.network.MaidPayload.buffer();
        io.github.zgxhzhr.maidfm.network.MaidBackupCodec.writeBackupOwners(out2, owners);
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                io.github.zgxhzhr.maidfm.network.MaidPayload.of(
                        io.github.zgxhzhr.maidfm.network.MaidFilePackets.ID_BACKUP_LIST, out2));
    }
}
