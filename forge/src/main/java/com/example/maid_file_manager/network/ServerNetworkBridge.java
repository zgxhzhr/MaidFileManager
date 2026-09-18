package com.example.maid_file_manager.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

/**
 * 服务端向客户端发送 S2C 包的辅助类。
 * 通过 {@link com.example.maid_file_manager.MaidFileModForge#CHANNEL} 发送。
 * SimpleChannel 发送时同步完成香草包编码，发送返回后即可释放自建堆缓冲。
 */
public final class ServerNetworkBridge {
    private ServerNetworkBridge() {
    }

    public static void sendToPlayer(ServerPlayer player, ResourceLocation packetId, FriendlyByteBuf data) {
        try {
            S2CPacket packet = new S2CPacket(packetId, data);
            com.example.maid_file_manager.MaidFileModForge.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player), packet);
        } finally {
            // SimpleChannel 同步编码：无论发送成功还是抛异常，自建堆缓冲都由本方法负责释放，
            // 调用方不得再 release（重复释放会触发 IllegalReferenceCountException）
            data.release();
        }
    }
}
