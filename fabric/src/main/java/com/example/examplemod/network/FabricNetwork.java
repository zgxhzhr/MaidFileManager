package com.example.examplemod.network;

import com.example.examplemod.data.MaidFileData;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * Fabric 客户端网络实现。
 * 实现 {@link IMaidFileNetwork}，通过 Fabric Networking API 发送 C2S 包。
 */
public final class FabricNetwork implements IMaidFileNetwork {
    @Override
    public void sendRequestMaidList() {
        sendC2S(MaidFilePackets.ID_REQUEST_MAID_LIST, PacketByteBufs.create());
    }

    @Override
    public void sendExportMaid(int entityId) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeInt(entityId);
        sendC2S(MaidFilePackets.ID_EXPORT_MAID, buf);
    }

    @Override
    public void sendImportFile(MaidFileData data) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        MaidFilePackets.writeMaidFileData(buf, data);
        sendC2S(MaidFilePackets.ID_IMPORT_FILE, buf);
    }

    private static void sendC2S(ResourceLocation id, FriendlyByteBuf buf) {
        ClientPlayNetworking.send(id, buf);
    }
}
