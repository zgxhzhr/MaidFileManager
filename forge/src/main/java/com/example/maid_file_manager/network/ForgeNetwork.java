package com.example.maid_file_manager.network;

import com.example.maid_file_manager.Constants;
import com.example.maid_file_manager.MaidFileModForge;
import com.example.maid_file_manager.data.MaidFileData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.util.List;

/**
 * Forge 客户端网络实现。
 * 实现 {@link IMaidFileNetwork}，通过 Forge SimpleChannel 发送 C2S 包。
 */
public final class ForgeNetwork implements IMaidFileNetwork {
    private static final Logger LOG = Constants.LOG;

    @Override
    public void sendRequestMaidList() {
        LOG.info("[maid_file_manager] C->S: REQUEST_MAID_LIST");
        sendC2S(MaidFilePackets.ID_REQUEST_MAID_LIST, new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer()));
    }

    @Override
    public void sendExportMaids(List<Integer> entityIds, boolean removeAfterExport) {
        LOG.info("[maid_file_manager] C->S: EXPORT_BATCH count={} removeAfter={}", entityIds.size(), removeAfterExport);
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        MaidFilePackets.writeIntList(buf, entityIds);
        buf.writeBoolean(removeAfterExport);
        sendC2S(MaidFilePackets.ID_EXPORT_BATCH, buf);
    }

    @Override
    public void sendImportFile(MaidFileData data, boolean keepBaubles) {
        LOG.info("[maid_file_manager] C->S: IMPORT_FILE keepBaubles={}", keepBaubles);
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        MaidFilePackets.writeMaidFileData(buf, data);
        buf.writeBoolean(keepBaubles);
        sendC2S(MaidFilePackets.ID_IMPORT_FILE, buf);
    }

    @Override
    public void sendImportFiles(List<MaidFileData> dataList, boolean keepBaubles) {
        LOG.info("[maid_file_manager] C->S: IMPORT_BATCH count={} keepBaubles={}", dataList.size(), keepBaubles);
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        MaidFilePackets.writeMaidFileDataList(buf, dataList);
        buf.writeBoolean(keepBaubles);
        sendC2S(MaidFilePackets.ID_IMPORT_BATCH, buf);
    }

    @Override
    public void sendClientConsent(boolean allow) {
        LOG.info("[maid_file_manager] C->S: CLIENT_CONSENT allow={}", allow);
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeBoolean(allow);
        sendC2S(MaidFilePackets.ID_CLIENT_CONSENT, buf);
    }

    @Override
    public void sendSetServerConfig(String key, boolean value) {
        LOG.info("[maid_file_manager] C->S: SET_SERVER_CONFIG key={} value={}", key, value);
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeUtf(key, 128);
        buf.writeBoolean(value);
        sendC2S(MaidFilePackets.ID_SET_SERVER_CONFIG, buf);
    }

    @Override
    public void sendRequestServerExportList() {
        LOG.info("[maid_file_manager] C->S: REQUEST_SERVER_EXPORT_LIST");
        sendC2S(MaidFilePackets.ID_REQUEST_SERVER_EXPORT_LIST,
                new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer()));
    }

    @Override
    public void sendServerExportBatch(List<IMaidFileNetwork.PlayerExportRequest> groups) {
        LOG.info("[maid_file_manager] C->S: SERVER_EXPORT_BATCH groups={}", groups.size());
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        MaidFilePackets.writePlayerExportRequests(buf, groups);
        sendC2S(MaidFilePackets.ID_SERVER_EXPORT_BATCH, buf);
    }

    private static void sendC2S(ResourceLocation id, FriendlyByteBuf data) {
        C2SPacket packet = new C2SPacket(id, data);
        MaidFileModForge.CHANNEL.sendToServer(packet);
        LOG.info("[maid_file_manager] C->S packet sent: id={}", id);
    }
}
