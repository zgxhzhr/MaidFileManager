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
}
