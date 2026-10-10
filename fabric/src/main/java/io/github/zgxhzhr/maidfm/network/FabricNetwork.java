package io.github.zgxhzhr.maidfm.network;

import io.github.zgxhzhr.maidfm.data.MaidFileData;
import io.github.zgxhzhr.maidfm.data.MaidProfile;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Fabric 客户端网络实现。
 * 实现 {@link IMaidFileNetwork}，通过 Fabric Networking API 发送 C2S 包。
 */
public final class FabricNetwork implements IMaidFileNetwork {
    @Override
    public void sendRequestMaidList() {
        sendC2S(MaidFilePackets.ID_REQUEST_MAID_LIST, PacketByteBufs.create());
    }

    @Override
    public void sendExportMaids(List<Integer> entityIds, boolean removeAfterExport) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        MaidFilePackets.writeIntList(buf, entityIds);
        buf.writeBoolean(removeAfterExport);
        sendC2S(MaidFilePackets.ID_EXPORT_BATCH, buf);
    }

    @Override
    public void sendImportFiles(List<MaidFileData> dataList, boolean keepBaubles, boolean deleteAfterImport) {
        // 饰品导入策略由服务端配置决定，客户端不上传（杜绝非 OP 绕过）
        FriendlyByteBuf buf = PacketByteBufs.create();
        MaidFilePackets.writeMaidFileDataList(buf, dataList);
        buf.writeBoolean(keepBaubles);
        buf.writeBoolean(deleteAfterImport);
        sendC2S(MaidFilePackets.ID_IMPORT_BATCH, buf);
    }

    @Override
    public void sendClientConsent(boolean allow) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeBoolean(allow);
        sendC2S(MaidFilePackets.ID_CLIENT_CONSENT, buf);
    }

    @Override
    public void sendSetServerConfig(String key, boolean value) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeUtf(key, MaidFilePackets.MAX_TEXT_LEN);
        buf.writeBoolean(value);
        sendC2S(MaidFilePackets.ID_SET_SERVER_CONFIG, buf);
    }

    @Override
    public void sendSetServerBaubleBlockedList(List<String> ids) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        MaidFilePackets.writeStringList(buf, ids);
        sendC2S(MaidFilePackets.ID_SET_SERVER_BAUBLE_BLOCKED_LIST, buf);
    }

    @Override
    public void sendRequestServerExportList() {
        sendC2S(MaidFilePackets.ID_REQUEST_SERVER_EXPORT_LIST, PacketByteBufs.create());
    }

    @Override
    public void sendServerExportBatch(List<PlayerExportRequest> groups) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        MaidFilePackets.writePlayerExportRequests(buf, groups);
        sendC2S(MaidFilePackets.ID_SERVER_EXPORT_BATCH, buf);
    }

    @Override
    public void sendRequestMaidProfile(int entityId) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(entityId);
        sendC2S(MaidFilePackets.ID_REQUEST_MAID_PROFILE, buf);
    }

    @Override
    public void sendSaveMaidProfile(int entityId, MaidProfile profile) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(entityId);
        MaidFilePackets.writeMaidProfile(buf, profile);
        sendC2S(MaidFilePackets.ID_SAVE_MAID_PROFILE, buf);
    }

    @Override
    public void sendRequestBackupList() {
        sendC2S(MaidFilePackets.ID_REQUEST_BACKUP_LIST, PacketByteBufs.create());
    }

    @Override
    public void sendRequestBackupExport(String ownerUuid, String maidUuid, String fileName) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeUtf(ownerUuid == null ? "" : ownerUuid, MaidFilePackets.MAX_TEXT_LEN);
        buf.writeUtf(maidUuid == null ? "" : maidUuid, MaidFilePackets.MAX_TEXT_LEN);
        buf.writeUtf(fileName == null ? "" : fileName, MaidFilePackets.MAX_TEXT_LEN);
        sendC2S(MaidFilePackets.ID_REQUEST_BACKUP_EXPORT, buf);
    }

    private static void sendC2S(ResourceLocation id, FriendlyByteBuf buf) {
        ClientPlayNetworking.send(id, buf);
    }
}
