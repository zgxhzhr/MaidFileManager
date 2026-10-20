package io.github.zgxhzhr.maidfm.network;

import io.github.zgxhzhr.maidfm.data.MaidFileData;
import io.github.zgxhzhr.maidfm.data.MaidProfile;
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
        }        // 饰品导入策略由服务端配置决定，客户端不上传（杜绝非 OP 绕过）
        FriendlyByteBuf buf = MaidPayload.buffer();
        MaidFilePackets.writeMaidFileDataList(buf, dataList);
        buf.writeBoolean(keepBaubles);
        buf.writeBoolean(deleteAfterImport);
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
    public void sendSetServerListConfig(String key, List<String> ids) {
        FriendlyByteBuf buf = MaidPayload.buffer();
        buf.writeUtf(key == null ? "" : key, MaidFilePackets.MAX_TEXT_LEN);
        MaidFilePackets.writeStringList(buf, ids);
        sendC2S(MaidFilePackets.ID_SET_SERVER_BAUBLE_BLOCKED_LIST, buf);
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

    @Override
    public void sendRequestMaidProfile(int entityId) {
        FriendlyByteBuf buf = MaidPayload.buffer();
        buf.writeInt(entityId);
        sendC2S(MaidFilePackets.ID_REQUEST_MAID_PROFILE, buf);
    }

    @Override
    public void sendSaveMaidProfile(int entityId, MaidProfile profile) {
        // 先把负载编码成「VarInt entityId + 档案 NBT」字节：分片路径与普通包共用这一格式。
        FriendlyByteBuf probe = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
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
        }        sendC2S(MaidFilePackets.ID_SAVE_MAID_PROFILE, probe);
    }

    @Override
    public void sendRequestBackupList() {
        sendC2S(MaidFilePackets.ID_REQUEST_BACKUP_LIST, MaidPayload.buffer());
    }

    @Override
    public void sendRequestBackupExport(String ownerUuid, String maidUuid, String fileName) {
        FriendlyByteBuf buf = MaidPayload.buffer();
        buf.writeUtf(ownerUuid == null ? "" : ownerUuid, MaidFilePackets.MAX_TEXT_LEN);
        buf.writeUtf(maidUuid == null ? "" : maidUuid, MaidFilePackets.MAX_TEXT_LEN);
        buf.writeUtf(fileName == null ? "" : fileName, MaidFilePackets.MAX_TEXT_LEN);
        sendC2S(MaidFilePackets.ID_REQUEST_BACKUP_EXPORT, buf);
    }

    private static void sendC2S(ResourceLocation id, FriendlyByteBuf buf) {
        try {
            ClientPlayNetworking.send(MaidPayload.of(id, buf));
        } finally {
            // Fabric API 不会替调用方释放 FriendlyByteBuf：发送完成后必须手动释放，否则每次发包泄漏一块池化内存
            buf.release();
        }
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
    /** 发送一片分片上传包（Fabric：走 sendC2S，缓冲由本端 API 创建） */
    private static void sendChunkedPacket(String kind, int sessionId, int total, int index, byte[] chunk) {
        FriendlyByteBuf buf = io.github.zgxhzhr.maidfm.network.MaidPayload.buffer();
        buf.writeUtf(kind == null ? "" : kind, MaidFilePackets.MAX_TEXT_LEN);
        buf.writeVarInt(sessionId);
        buf.writeVarInt(total);
        buf.writeVarInt(index);
        buf.writeByteArray(chunk);
        sendC2S(MaidFilePackets.ID_CHUNKED_UPLOAD, buf);
    }

    @Override
    public void sendExportWriteAck(boolean success) {
        FriendlyByteBuf buf = io.github.zgxhzhr.maidfm.network.MaidPayload.buffer();
        buf.writeBoolean(success);
        sendC2S(MaidFilePackets.ID_EXPORT_WRITE_ACK, buf);
    }
}
