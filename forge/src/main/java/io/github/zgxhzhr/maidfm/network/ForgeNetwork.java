package io.github.zgxhzhr.maidfm.network;

import io.github.zgxhzhr.maidfm.Constants;
import io.github.zgxhzhr.maidfm.MaidFileModForge;
import io.github.zgxhzhr.maidfm.data.MaidFileData;
import io.github.zgxhzhr.maidfm.data.MaidProfile;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.util.List;

/**
 * Forge 客户端网络实现。
 * 实现 {@link IMaidFileNetwork}，通过 Forge SimpleChannel 发送 C2S 包。
 * SimpleChannel 发送时同步完成香草包编码，发送返回后即可释放自建堆缓冲。
 */
public final class ForgeNetwork implements IMaidFileNetwork {
    private static final Logger LOG = Constants.LOG;

    @Override
    public void sendRequestMaidList() {
        sendC2S(MaidFilePackets.ID_REQUEST_MAID_LIST, new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer()));
    }

    @Override
    public void sendExportMaids(List<Integer> entityIds, boolean removeAfterExport) {
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
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
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        MaidFilePackets.writeMaidFileDataList(buf, dataList);
        buf.writeBoolean(keepBaubles);
        buf.writeBoolean(deleteAfterImport);
        sendC2S(MaidFilePackets.ID_IMPORT_BATCH, buf);
    }

    @Override
    public void sendClientConsent(boolean allow) {
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeBoolean(allow);
        sendC2S(MaidFilePackets.ID_CLIENT_CONSENT, buf);
    }

    @Override
    public void sendSetServerConfig(String key, boolean value) {
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeUtf(key, MaidFilePackets.MAX_TEXT_LEN);
        buf.writeBoolean(value);
        sendC2S(MaidFilePackets.ID_SET_SERVER_CONFIG, buf);
    }

    @Override
    public void sendSetServerListConfig(String key, List<String> ids) {
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeUtf(key == null ? "" : key, MaidFilePackets.MAX_TEXT_LEN);
        MaidFilePackets.writeStringList(buf, ids);
        sendC2S(MaidFilePackets.ID_SET_SERVER_BAUBLE_BLOCKED_LIST, buf);
    }

    @Override
    public void sendRequestServerExportList() {
        sendC2S(MaidFilePackets.ID_REQUEST_SERVER_EXPORT_LIST,
                new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer()));
    }

    @Override
    public void sendServerExportBatch(List<IMaidFileNetwork.PlayerExportRequest> groups) {
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        MaidFilePackets.writePlayerExportRequests(buf, groups);
        sendC2S(MaidFilePackets.ID_SERVER_EXPORT_BATCH, buf);
    }

    @Override
    public void sendRequestMaidProfile(int entityId) {
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeVarInt(entityId);
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
        sendC2S(MaidFilePackets.ID_REQUEST_BACKUP_LIST, new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer()));
    }

    @Override
    public void sendRequestBackupExport(String ownerUuid, String maidUuid, String fileName) {
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeUtf(ownerUuid == null ? "" : ownerUuid, MaidFilePackets.MAX_TEXT_LEN);
        buf.writeUtf(maidUuid == null ? "" : maidUuid, MaidFilePackets.MAX_TEXT_LEN);
        buf.writeUtf(fileName == null ? "" : fileName, MaidFilePackets.MAX_TEXT_LEN);
        sendC2S(MaidFilePackets.ID_REQUEST_BACKUP_EXPORT, buf);
    }

    private static void sendC2S(ResourceLocation id, FriendlyByteBuf data) {
        try {
            C2SPacket packet = new C2SPacket(id, data);
            MaidFileModForge.CHANNEL.sendToServer(packet);
            // 编码已在发送调用内同步完成
            LOG.debug("[maid_file_manager] C->S packet sent: id={}", id);
        } catch (Throwable t) {
            // 连接关闭/编码失败时不得向上抛出（调用点多在 UI 事件里），缓冲仍在下面统一释放
            LOG.warn("[maid_file_manager] C->S 发送失败: id={}", id, t);
        } finally {
            data.release();
        }
    }

    @Override
    public void sendChunkedUpload(String kind, byte[] payload) {
        if (payload == null || payload.length == 0) {
            return;
        }
        // 发送前预检整包上限：接收端本来就会在收齐后丢弃超限负载，
        // 但那时已经发了 342 片（8 MiB），白占带宽与接收端内存，客户端还拿不到错误。
        if (io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.exceedsTotalLimit(payload)) {
            io.github.zgxhzhr.maidfm.Constants.LOG.warn(
                    "[maid_file_manager] 分片上传负载超上限，已拒绝发送: kind={} bytes={} limit={}",
                    kind, payload.length,
                    io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.MAX_TOTAL_BYTES);
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
    /** 发送一片分片上传包（Forge：走 SimpleChannel C2S） */
    private static void sendChunkedPacket(String kind, int sessionId, int total, int index, byte[] chunk) {
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeUtf(kind == null ? "" : kind, MaidFilePackets.MAX_TEXT_LEN);
        buf.writeVarInt(sessionId);
        buf.writeVarInt(total);
        buf.writeVarInt(index);
        buf.writeByteArray(chunk);
        sendC2S(MaidFilePackets.ID_CHUNKED_UPLOAD, buf);
    }

    @Override
    public void sendExportWriteAck(boolean success) {
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeBoolean(success);
        sendC2S(MaidFilePackets.ID_EXPORT_WRITE_ACK, buf);
    }
}
