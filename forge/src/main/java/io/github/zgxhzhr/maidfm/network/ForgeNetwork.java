package io.github.zgxhzhr.maidfm.network;

import io.github.zgxhzhr.maidfm.Constants;
import io.github.zgxhzhr.maidfm.MaidFileModForge;
import io.github.zgxhzhr.maidfm.data.MaidFileData;
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
}
