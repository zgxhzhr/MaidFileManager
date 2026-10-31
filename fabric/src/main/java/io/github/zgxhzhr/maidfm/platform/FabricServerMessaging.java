package io.github.zgxhzhr.maidfm.platform;

import io.github.zgxhzhr.maidfm.network.MaidFilePayloads;
import io.github.zgxhzhr.maidfm.platform.services.ServerMessaging;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Fabric 26.1.2 的 {@link ServerMessaging} 实现。
 *
 * <p>用 payload record + {@code ServerPlayNetworking.send}。26.1.2 的 NBT / 渲染 API
 * 与本类无关，所以实现与 21.1 版不同之处仅在发送入口。
 */
public final class FabricServerMessaging implements ServerMessaging {

    @Override
    public void sendFeedback(ServerPlayer player, Component message) {
        ServerPlayNetworking.send(player, new MaidFilePayloads.FeedbackPayload(message));
    }

    @Override
    public void sendImportBatchResult(ServerPlayer player, Component summary, List<Boolean> spawned) {
        ServerPlayNetworking.send(player,
                new MaidFilePayloads.ImportBatchResultPayload(summary, spawned));
    }

    @Override
    public void sendMaidList(ServerPlayer player, java.util.List<io.github.zgxhzhr.maidfm.data.MaidInfo> list) {
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(
                player, new io.github.zgxhzhr.maidfm.network.MaidFilePayloads.MaidListPayload(list));
    }

    @Override
    public void sendMaidProfile(ServerPlayer player, io.github.zgxhzhr.maidfm.data.MaidProfileView view) {
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(
                player, new io.github.zgxhzhr.maidfm.network.MaidFilePayloads.MaidProfilePayload(view));
    }

    @Override
    public void sendServerConfig(ServerPlayer player) {
        io.github.zgxhzhr.maidfm.network.MaidFilePayloads.sendServerConfig(player);
    }

    @Override
    public boolean hasOpPermission(ServerPlayer player) {
        return player != null && player.permissions()
                .hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR);
    }

    @Override
    public net.minecraft.server.MinecraftServer serverOf(ServerPlayer player) {
        return player == null ? null
                : ((net.minecraft.server.level.ServerLevel) player.level()).getServer();
    }

    @Override
    public void sendExportBatchResult(ServerPlayer player, byte[] encodedPayload) {
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                new io.github.zgxhzhr.maidfm.network.MaidFilePayloads.ExportBatchResultPayload(encodedPayload));
    }

    @Override
    public boolean sendChunkedDownload(ServerPlayer player, String kind, byte[] payload) {
        return io.github.zgxhzhr.maidfm.network.MaidFilePayloads.ChunkedDownloadPayload.sendChunks(player, kind, payload);
    }

    @Override
    public void sendServerExportList(ServerPlayer player,
                                     java.util.List<io.github.zgxhzhr.maidfm.network.IMaidFileNetwork.PlayerMaidGroup> groups) {
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                new io.github.zgxhzhr.maidfm.network.MaidFilePayloads.ServerExportListPayload(groups));
    }

    @Override
    public void sendBackupList(ServerPlayer player,
                               java.util.List<io.github.zgxhzhr.maidfm.network.IMaidFileNetwork.BackupOwner> owners) {
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                new io.github.zgxhzhr.maidfm.network.MaidFilePayloads.BackupListPayload(owners));
    }
}
