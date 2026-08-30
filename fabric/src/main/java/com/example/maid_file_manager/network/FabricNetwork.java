package com.example.maid_file_manager.network;

import com.example.maid_file_manager.data.MaidFileData;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Fabric 客户端网络实现（1.21+ 新 API：CustomPacketPayload）。
 * 实现 {@link IMaidFileNetwork}，通过 Fabric Networking API 发送 C2S 包。
 * 包体格式与 Forge/NeoForge 版完全一致（由 {@link MaidFilePackets} 定义）。
 */
public final class FabricNetwork implements IMaidFileNetwork {
    @Override
    public void sendRequestMaidList() {
        sendC2S(MaidFilePackets.ID_REQUEST_MAID_LIST, MaidPayload.buffer());
    }

    @Override
    public void sendExportMaids(List<Integer> entityIds, boolean removeAfterExport) {
        FriendlyByteBuf buf = MaidPayload.buffer();
        MaidFilePackets.writeIntList(buf, entityIds);
        buf.writeBoolean(removeAfterExport);
        sendC2S(MaidFilePackets.ID_EXPORT_BATCH, buf);
    }

    @Override
    public void sendImportFile(MaidFileData data, boolean keepBaubles) {
        FriendlyByteBuf buf = MaidPayload.buffer();
        MaidFilePackets.writeMaidFileData(buf, data);
        buf.writeBoolean(keepBaubles);
        sendC2S(MaidFilePackets.ID_IMPORT_FILE, buf);
    }

    @Override
    public void sendImportFiles(List<MaidFileData> dataList, boolean keepBaubles) {
        FriendlyByteBuf buf = MaidPayload.buffer();
        MaidFilePackets.writeMaidFileDataList(buf, dataList);
        buf.writeBoolean(keepBaubles);
        sendC2S(MaidFilePackets.ID_IMPORT_BATCH, buf);
    }

    @Override
    public void sendClientConsent(boolean allow) {
        FriendlyByteBuf buf = MaidPayload.buffer();
        buf.writeBoolean(allow);
        sendC2S(MaidFilePackets.ID_CLIENT_CONSENT, buf);
    }

    @Override
    public void sendSetServerConfig(String key, boolean value) {
        FriendlyByteBuf buf = MaidPayload.buffer();
        buf.writeUtf(key, 128);
        buf.writeBoolean(value);
        sendC2S(MaidFilePackets.ID_SET_SERVER_CONFIG, buf);
    }

    @Override
    public void sendRequestServerExportList() {
        sendC2S(MaidFilePackets.ID_REQUEST_SERVER_EXPORT_LIST, MaidPayload.buffer());
    }

    @Override
    public void sendServerExportBatch(List<PlayerExportRequest> groups) {
        FriendlyByteBuf buf = MaidPayload.buffer();
        MaidFilePackets.writePlayerExportRequests(buf, groups);
        sendC2S(MaidFilePackets.ID_SERVER_EXPORT_BATCH, buf);
    }

    private static void sendC2S(ResourceLocation id, FriendlyByteBuf buf) {
        ClientPlayNetworking.send(MaidPayload.of(id, buf));
    }
}
