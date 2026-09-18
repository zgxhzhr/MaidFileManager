package com.example.maid_file_manager.network;

import com.example.maid_file_manager.data.MaidFileData;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * NeoForge 网络实现：客户端发送 C2S 包。
 */
public class NeoForgeNetwork implements IMaidFileNetwork {
    @Override
    public void sendRequestMaidList() {
        PacketDistributor.sendToServer(new MaidFilePayloads.RequestMaidListPayload());
    }

    @Override
    public void sendExportMaids(List<Integer> entityIds, boolean removeAfterExport) {
        PacketDistributor.sendToServer(new MaidFilePayloads.ExportBatchPayload(entityIds, removeAfterExport));
    }

    @Override
    public void sendImportFiles(List<MaidFileData> dataList, boolean keepBaubles, boolean deleteAfterImport) {
        PacketDistributor.sendToServer(new MaidFilePayloads.ImportBatchPayload(dataList, keepBaubles, deleteAfterImport));
    }

    @Override
    public void sendClientConsent(boolean allow) {
        PacketDistributor.sendToServer(new MaidFilePayloads.ClientConsentPayload(allow));
    }

    @Override
    public void sendSetServerConfig(String key, boolean value) {
        PacketDistributor.sendToServer(new MaidFilePayloads.SetServerConfigPayload(key, value));
    }

    @Override
    public void sendRequestServerExportList() {
        PacketDistributor.sendToServer(new MaidFilePayloads.RequestServerExportListPayload());
    }

    @Override
    public void sendServerExportBatch(List<IMaidFileNetwork.PlayerExportRequest> groups) {
        PacketDistributor.sendToServer(new MaidFilePayloads.ServerExportBatchPayload(groups));
    }
}
