package com.example.examplemod.network;

import com.example.examplemod.Constants;
import com.example.examplemod.data.MaidFileData;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

public class NeoForgeNetwork implements IMaidFileNetwork {
    private static final Logger LOG = Constants.LOG;

    @Override
    public void sendRequestMaidList() {
        LOG.info("[maid_file_manager] C->S: REQUEST_MAID_LIST");
        PacketDistributor.sendToServer(new MaidFilePayloads.RequestMaidListPayload());
    }

    @Override
    public void sendExportMaid(int entityId) {
        LOG.info("[maid_file_manager] C->S: EXPORT_MAID entityId={}", entityId);
        PacketDistributor.sendToServer(new MaidFilePayloads.ExportMaidPayload(entityId));
    }

    @Override
    public void sendImportFile(MaidFileData data) {
        LOG.info("[maid_file_manager] C->S: IMPORT_FILE");
        byte[] bytes = MaidFilePackets.serializeMaidFileData(data);
        if (bytes == null) return;
        PacketDistributor.sendToServer(new MaidFilePayloads.ImportFilePayload(bytes));
    }
}
