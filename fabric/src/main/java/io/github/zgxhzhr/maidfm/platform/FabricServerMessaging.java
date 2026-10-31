package io.github.zgxhzhr.maidfm.platform;

import io.github.zgxhzhr.maidfm.network.MaidFilePackets;
import io.github.zgxhzhr.maidfm.platform.services.ServerMessaging;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Fabric 1.20.1 的 {@link ServerMessaging} 实现。
 *
 * <p>用 1.20.x 的旧网络 API：{@code PacketByteBufs.create()} 建缓冲，
 * {@code FriendlyByteBuf.writeComponent} 编码（1.20.5+ 移除），
 * {@code ServerPlayNetworking.send(player, id, buf)} 发送。
 */
public final class FabricServerMessaging implements ServerMessaging {

    @Override
    public void sendFeedback(ServerPlayer player, Component message) {
        FriendlyByteBuf out = PacketByteBufs.create();
        out.writeComponent(message);
        ServerPlayNetworking.send(player, MaidFilePackets.ID_FEEDBACK, out);
    }

    @Override
    public void sendImportBatchResult(ServerPlayer player, Component summary, List<Boolean> spawned) {
        FriendlyByteBuf out = PacketByteBufs.create();
        out.writeComponent(summary);
        MaidFilePackets.writeBooleanList(out, spawned);
        ServerPlayNetworking.send(player, MaidFilePackets.ID_IMPORT_BATCH_RESULT, out);
    }
}
