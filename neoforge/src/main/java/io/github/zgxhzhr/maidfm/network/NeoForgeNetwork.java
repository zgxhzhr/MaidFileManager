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
        // 编码成与 ImportBatchPayload 相同的字节：writeMaidFileDataList + 2 个布尔。
        // 超过单片上限就改走分片上传——1.20.x 真联机的 32767 单包上限会直接断连，
        // 1.21 的 1 MiB 也可能被多文件批量撑破，两条路都靠分片兜底。
        net.minecraft.network.FriendlyByteBuf probe =
                new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        MaidFilePackets.writeMaidFileDataList(probe, dataList);
        probe.writeBoolean(keepBaubles);
        probe.writeBoolean(deleteAfterImport);
        byte[] encodedImport = new byte[probe.readableBytes()];
        probe.getBytes(0, encodedImport);
        if (encodedImport.length > io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.CHUNK_BYTES) {
            sendChunkedUpload(io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.KIND_IMPORT_BATCH, encodedImport);
            return;
        }        PacketDistributor.sendToServer(new MaidFilePayloads.ImportBatchPayload(
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
    public void sendSetServerListConfig(String key, List<String> ids) {
        PacketDistributor.sendToServer(new MaidFilePayloads.SetServerBaubleBlockedListPayload(
                key == null ? "" : key, ids == null ? java.util.List.of() : ids));
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
        // 先把负载编码成「VarInt entityId + 档案 NBT」字节：分片路径与普通包共用这一格式。
        net.minecraft.network.FriendlyByteBuf probe = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        probe.writeVarInt(entityId);
        MaidFilePackets.writeMaidProfile(probe, profile);
        byte[] encoded = new byte[probe.readableBytes()];
        probe.getBytes(0, encoded);
        if (MaidFilePackets.isLegacyPayloadLimit()
                && encoded.length > io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.CHUNK_BYTES) {
            // 1.20.x 真联机：单包 32767 上限，带照片的档案必然超限 —— 改走分片上传。
            // 单人/局域网主机不走这里（isLegacyPayloadLimit 会返回 false）。
            sendChunkedUpload(io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.KIND_SAVE_PROFILE, encoded);
            return;
        }
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

    @Override
    public void sendChunkedUpload(String kind, byte[] payload) {
        if (payload == null || payload.length == 0) {
            return;
        }
        int chunkBytes = io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.CHUNK_BYTES;
        int total = (payload.length + chunkBytes - 1) / chunkBytes;
        int sessionId = java.util.concurrent.ThreadLocalRandom.current().nextInt();
        for (int i = 0; i < total; i++) {
            int off = i * chunkBytes;
            int len = Math.min(chunkBytes, payload.length - off);
            byte[] chunk = java.util.Arrays.copyOfRange(payload, off, off + len);
            sendChunkedPacket(kind, sessionId, total, i, chunk);
        }
    }
    /** 发送一片分片上传包（NeoForge：走专用 CustomPacketPayload） */
    private static void sendChunkedPacket(String kind, int sessionId, int total, int index, byte[] chunk) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new MaidFilePayloads.ChunkedUploadPayload(kind, sessionId, total, index, chunk));
    }
}
