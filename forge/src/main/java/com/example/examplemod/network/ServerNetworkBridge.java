package com.example.examplemod.network;

import com.example.examplemod.Constants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

/**
 * 服务端向客户端发送 S2C 包的辅助类。
 * 通过 Forge 的 {@link com.example.examplemod.MaidFileModForge#CHANNEL} 发送。
 */
public final class ServerNetworkBridge {
    private ServerNetworkBridge() {
    }

    public static void sendToPlayer(ServerPlayer player, ResourceLocation packetId, FriendlyByteBuf data) {
        S2CPacket packet = new S2CPacket(packetId, data);
        com.example.examplemod.MaidFileModForge.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
}
