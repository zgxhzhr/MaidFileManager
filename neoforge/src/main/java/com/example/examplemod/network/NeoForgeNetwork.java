package com.example.examplemod.network;

import com.example.examplemod.Constants;
import com.example.examplemod.data.MaidFileData;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.util.List;

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
    public void sendImportFile(MaidFileData data) {
        LOG.info("[maid_file_manager] C->S: IMPORT_FILE");
        byte[] bytes = MaidFilePackets.serializeMaidFileData(data);
        if (bytes == null) return;
        PacketDistributor.sendToServer(new MaidFilePayloads.ImportFilePayload(bytes));
    }

    @Override
    public void sendImportFiles(List<MaidFileData> dataList) {
        LOG.info("[maid_file_manager] C->S: IMPORT_BATCH count={}", dataList.size());
        PacketDistributor.sendToServer(new MaidFilePayloads.ImportBatchPayload(dataList));
    }
}
