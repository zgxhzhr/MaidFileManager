package io.github.zgxhzhr.maidfm.platform;

import io.github.zgxhzhr.maidfm.network.MaidFilePackets;
import io.github.zgxhzhr.maidfm.network.MaidPayload;
import io.github.zgxhzhr.maidfm.platform.services.ServerMessaging;

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
}
