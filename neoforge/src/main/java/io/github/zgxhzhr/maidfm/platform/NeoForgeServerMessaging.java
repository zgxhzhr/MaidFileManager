package io.github.zgxhzhr.maidfm.platform;

import io.github.zgxhzhr.maidfm.network.MaidFilePayloads;
import io.github.zgxhzhr.maidfm.platform.services.ServerMessaging;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * NeoForge 的 {@link ServerMessaging} 实现。
 *
 * <p>本端用 payload record 而非手写 buf：{@code FeedbackPayload} / {@code ImportBatchResultPayload}
 * 的编解码由各自的 {@code StreamCodec} 负责，发送走 {@code PacketDistributor}。
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
}
