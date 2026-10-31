package io.github.zgxhzhr.maidfm.network;

import io.github.zgxhzhr.maidfm.data.MaidFileData;
import io.github.zgxhzhr.maidfm.data.MaidProfile;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.util.List;

/**
 * NeoForge 网络实现：客户端发送 C2S 包。
 */
public class NeoForgeNetwork implements IMaidFileNetwork {
    @Override
    public void sendRequestMaidList() {
        ClientPacketDistributor.sendToServer(new MaidFilePayloads.RequestMaidListPayload());
    }

    @Override
    public void sendExportMaids(List<Integer> entityIds, boolean removeAfterExport) {
        ClientPacketDistributor.sendToServer(new MaidFilePayloads.ExportBatchPayload(entityIds, removeAfterExport));
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
        // 阈值与导出侧（MaidFilePayloads 的 CHUNK_BYTES 判定）对齐到 1.21 的真实单包上限，
        // 而不是 24 KiB 的分片尺寸：24 KiB 会让任何带头像的 .maid 都改走分片，
        // 白白多绕一圈网络与一次完整缓冲。
        int wireLimit = MaidFilePackets.MAX_PACKET_BYTES - 1024;
        if (encodedImport.length > wireLimit) {
            sendChunkedUpload(io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.KIND_IMPORT_BATCH, encodedImport);
            return;
        }        ClientPacketDistributor.sendToServer(new MaidFilePayloads.ImportBatchPayload(
                dataList, keepBaubles, deleteAfterImport));
    }

    @Override
    public void sendClientConsent(boolean allow) {
        ClientPacketDistributor.sendToServer(new MaidFilePayloads.ClientConsentPayload(allow));
    }

    @Override
    public void sendSetServerConfig(String key, boolean value) {
        ClientPacketDistributor.sendToServer(new MaidFilePayloads.SetServerConfigPayload(key, value));
    }

    @Override
    public void sendSetServerListConfig(String key, List<String> ids) {
        ClientPacketDistributor.sendToServer(new MaidFilePayloads.SetServerBaubleBlockedListPayload(
                key == null ? "" : key, ids == null ? java.util.List.of() : ids));
    }

    @Override
    public void sendRequestServerExportList() {
        ClientPacketDistributor.sendToServer(new MaidFilePayloads.RequestServerExportListPayload());
    }

    @Override
    public void sendServerExportBatch(List<IMaidFileNetwork.PlayerExportRequest> groups) {
        ClientPacketDistributor.sendToServer(new MaidFilePayloads.ServerExportBatchPayload(groups));
    }

    @Override
    public void sendRequestMaidProfile(int entityId) {
        ClientPacketDistributor.sendToServer(new MaidFilePayloads.RequestMaidProfilePayload(entityId));
    }

    @Override
    public void sendSaveMaidProfile(int entityId, MaidProfile profile) {
        // 先把负载编码成「VarInt entityId + 档案 NBT」字节：分片路径与普通包共用这一格式。
        net.minecraft.network.FriendlyByteBuf probe = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        probe.writeVarInt(entityId);
        MaidProfileCodec.writeMaidProfile(probe, profile);
        byte[] encoded = new byte[probe.readableBytes()];
        probe.getBytes(0, encoded);
        // 不用 isLegacyPayloadLimit()：那个判定只对 1.20.x 有意义（32767 单包上限），
        // 在 1.21 上恒为 false，写在这里是死守卫 —— 与其他三端的分片条件不一致，
        // 也让人误以为 1.21 不会走分片。1.21 的真实单包上限是 1 MiB，
        // 超过 24 KiB 就走分片反而更省 peak 内存。
        if (encoded.length > io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.CHUNK_BYTES) {
            sendChunkedUpload(io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.KIND_SAVE_PROFILE, encoded);
            return;
        }
        ClientPacketDistributor.sendToServer(new MaidFilePayloads.SaveMaidProfilePayload(entityId, profile));
    }

    @Override
    public void sendRequestBackupList() {
        ClientPacketDistributor.sendToServer(new MaidFilePayloads.RequestBackupListPayload());
    }

    @Override
    public void sendModelNames(java.util.List<String> pairs) {
        if (pairs == null || pairs.isEmpty()) {
            return;
        }
        ClientPacketDistributor.sendToServer(new MaidFilePayloads.ModelNamesPayload(pairs));
    }

    @Override
    public void sendRequestBackupExport(String ownerUuid, String maidUuid, String fileName) {
        ClientPacketDistributor.sendToServer(
                new MaidFilePayloads.RequestBackupExportPayload(ownerUuid, maidUuid, fileName));
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
            // 只记日志玩家看不到任何反馈（导出会「什么都没发生」）：补一条聊天栏提示
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.player != null) {
                mc.gui.setOverlayMessage(net.minecraft.network.chat.Component.translatable(
                        "maid_file_manager.gui.upload.too_large",
                        payload.length / 1024, io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.MAX_TOTAL_BYTES / 1024), false);
            }
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
        net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(
                new MaidFilePayloads.ChunkedUploadPayload(kind, sessionId, total, index, chunk));
    }

    @Override
    public void sendExportWriteAck(boolean success) {
        net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(
                new MaidFilePayloads.ExportWriteAckPayload(success));
    }
}
