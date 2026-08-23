package com.example.examplemod.network;

import com.example.examplemod.Constants;
import com.example.examplemod.MaidFileModForge;
import com.example.examplemod.data.MaidFileData;
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
    public void sendImportFile(MaidFileData data) {
        LOG.info("[maid_file_manager] C->S: IMPORT_FILE");
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        MaidFilePackets.writeMaidFileData(buf, data);
        sendC2S(MaidFilePackets.ID_IMPORT_FILE, buf);
    }

    @Override
    public void sendImportFiles(List<MaidFileData> dataList) {
        LOG.info("[maid_file_manager] C->S: IMPORT_BATCH count={}", dataList.size());
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        MaidFilePackets.writeMaidFileDataList(buf, dataList);
        sendC2S(MaidFilePackets.ID_IMPORT_BATCH, buf);
    }

    private static void sendC2S(ResourceLocation id, FriendlyByteBuf data) {
        C2SPacket packet = new C2SPacket(id, data);
        MaidFileModForge.CHANNEL.sendToServer(packet);
        LOG.info("[maid_file_manager] C->S packet sent: id={}", id);
    }
}
