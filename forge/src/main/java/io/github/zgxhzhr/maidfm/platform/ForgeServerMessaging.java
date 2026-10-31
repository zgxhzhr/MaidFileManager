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
}
