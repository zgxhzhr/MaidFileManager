package com.example.maid_file_manager.network;

import com.example.maid_file_manager.Constants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

/**
 * 服务端向客户端发送 S2C 包的辅助类。
 * 通过 Forge 的 {@link com.example.maid_file_manager.MaidFileModForge#CHANNEL} 发送。
 */
public final class ServerNetworkBridge {
    private ServerNetworkBridge() {
    }

    public static void sendToPlayer(ServerPlayer player, ResourceLocation packetId, FriendlyByteBuf data) {
        S2CPacket packet = new S2CPacket(packetId, data);
        com.example.maid_file_manager.MaidFileModForge.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
}
