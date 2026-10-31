package io.github.zgxhzhr.maidfm.platform;

import io.github.zgxhzhr.maidfm.network.MaidFilePayloads;
import io.github.zgxhzhr.maidfm.platform.services.ServerMessaging;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * NeoForge 26.1.2 的 {@link ServerMessaging} 实现。
 *
 * <p>用 payload record，发送走 {@code PacketDistributor}。26.1.2 的 NBT / 渲染 API 与本类无关，
 * 所以实现与 21.1 版相同。
 */
public final class NeoForgeServerMessaging implements ServerMessaging {

    @Override
    public void sendFeedback(ServerPlayer player, Component message) {
        PacketDistributor.sendToPlayer(player, new MaidFilePayloads.FeedbackPayload(message));
    }

    @Override
    public void sendImportBatchResult(ServerPlayer player, Component summary, List<Boolean> spawned) {
        PacketDistributor.sendToPlayer(player,
                new MaidFilePayloads.ImportBatchResultPayload(summary, spawned));
    }

    @Override
    public void sendMaidList(ServerPlayer player, java.util.List<io.github.zgxhzhr.maidfm.data.MaidInfo> list) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                player, new io.github.zgxhzhr.maidfm.network.MaidFilePayloads.MaidListPayload(list));
    }

    @Override
    public void sendMaidProfile(ServerPlayer player, io.github.zgxhzhr.maidfm.data.MaidProfileView view) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
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
}
