package com.example.maid_file_manager.network;

import com.example.maid_file_manager.Constants;
import com.example.maid_file_manager.data.MaidFileData;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.util.List;

/**
 * NeoForge 网络实现：客户端发送 C2S 包。
 */
public class NeoForgeNetwork implements IMaidFileNetwork {
    private static final Logger LOG = Constants.LOG;

    @Override
    public void sendRequestMaidList() {
        LOG.info("[maid_file_manager] C->S: REQUEST_MAID_LIST");
        PacketDistributor.sendToServer(new MaidFilePayloads.RequestMaidListPayload());
    }

    @Override
    public void sendExportMaids(List<Integer> entityIds, boolean removeAfterExport) {
        LOG.info("[maid_file_manager] C->S: EXPORT_BATCH count={} removeAfter={}", entityIds.size(), removeAfterExport);
        PacketDistributor.sendToServer(new MaidFilePayloads.ExportBatchPayload(entityIds, removeAfterExport));
    }

    @Override
    public void sendImportFile(MaidFileData data, boolean keepBaubles) {
        LOG.info("[maid_file_manager] C->S: IMPORT_FILE keepBaubles={}", keepBaubles);
        byte[] bytes = MaidFilePackets.serializeMaidFileData(data);
        if (bytes == null) return;
        PacketDistributor.sendToServer(new MaidFilePayloads.ImportFilePayload(bytes, keepBaubles));
    }

    @Override
    public void sendImportFiles(List<MaidFileData> dataList, boolean keepBaubles) {
        LOG.info("[maid_file_manager] C->S: IMPORT_BATCH count={} keepBaubles={}", dataList.size(), keepBaubles);
        PacketDistributor.sendToServer(new MaidFilePayloads.ImportBatchPayload(dataList, keepBaubles));
    }

    @Override
    public void sendClientConsent(boolean allow) {
        LOG.info("[maid_file_manager] C->S: CLIENT_CONSENT allow={}", allow);
        PacketDistributor.sendToServer(new MaidFilePayloads.ClientConsentPayload(allow));
    }

    @Override
    public void sendSetServerConfig(String key, boolean value) {
        LOG.info("[maid_file_manager] C->S: SET_SERVER_CONFIG key={} value={}", key, value);
        PacketDistributor.sendToServer(new MaidFilePayloads.SetServerConfigPayload(key, value));
    }

    @Override
    public void sendRequestServerExportList() {
        LOG.info("[maid_file_manager] C->S: REQUEST_SERVER_EXPORT_LIST");
        PacketDistributor.sendToServer(new MaidFilePayloads.RequestServerExportListPayload());
    }

    @Override
    public void sendServerExportBatch(List<IMaidFileNetwork.PlayerExportRequest> groups) {
        LOG.info("[maid_file_manager] C->S: SERVER_EXPORT_BATCH groups={}", groups.size());
        PacketDistributor.sendToServer(new MaidFilePayloads.ServerExportBatchPayload(groups));
    }
}
