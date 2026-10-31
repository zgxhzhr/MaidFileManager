package io.github.zgxhzhr.maidfm.platform;

import io.github.zgxhzhr.maidfm.network.MaidFilePackets;
import io.github.zgxhzhr.maidfm.network.ServerNetworkBridge;
import io.github.zgxhzhr.maidfm.platform.services.ServerMessaging;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Forge 的 {@link ServerMessaging} 实现。
 *
 * <p>编码用 1.20.x 的 {@code FriendlyByteBuf.writeComponent}（1.20.5+ 才移除），
 * 发送走 {@link ServerNetworkBridge#sendToPlayer}——它内部负责 release 自建堆缓冲，
 * 调用方<b>不得</b>再 release（重复释放会触发 {@code IllegalReferenceCountException}）。
 *
 * <p>注意本类<b>不</b>检查网络通道是否就绪：{@code CHANNEL} 在模组初始化时注册，
 * 而本方法只在处理 C2S 包时被调用——那时通道必然已就绪。
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
}
