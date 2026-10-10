package io.github.zgxhzhr.maidfm.network;

import io.github.zgxhzhr.maidfm.data.MaidFileData;
import io.github.zgxhzhr.maidfm.data.MaidProfile;
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
        PacketDistributor.sendToServer(new MaidFilePayloads.ImportBatchPayload(
                dataList, keepBaubles, deleteAfterImport));
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
    public void sendSetServerBaubleBlockedList(List<String> ids) {
        PacketDistributor.sendToServer(new MaidFilePayloads.SetServerBaubleBlockedListPayload(ids));
    }

    @Override
    public void sendRequestServerExportList() {
        PacketDistributor.sendToServer(new MaidFilePayloads.RequestServerExportListPayload());
    }

    @Override
    public void sendServerExportBatch(List<IMaidFileNetwork.PlayerExportRequest> groups) {
        PacketDistributor.sendToServer(new MaidFilePayloads.ServerExportBatchPayload(groups));
    }

    @Override
    public void sendRequestMaidProfile(int entityId) {
        PacketDistributor.sendToServer(new MaidFilePayloads.RequestMaidProfilePayload(entityId));
    }

    @Override
    public void sendSaveMaidProfile(int entityId, MaidProfile profile) {
        PacketDistributor.sendToServer(new MaidFilePayloads.SaveMaidProfilePayload(entityId, profile));
    }

    @Override
    public void sendRequestBackupList() {
        PacketDistributor.sendToServer(new MaidFilePayloads.RequestBackupListPayload());
    }

    @Override
    public void sendRequestBackupExport(String ownerUuid, String maidUuid, String fileName) {
        PacketDistributor.sendToServer(
                new MaidFilePayloads.RequestBackupExportPayload(ownerUuid, maidUuid, fileName));
    }
}
