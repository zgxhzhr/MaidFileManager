package io.github.zgxhzhr.maidfm.network;

import io.github.zgxhzhr.maidfm.data.MaidInfo;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 女仆列表与统一导出分组的线格式编解码。
 *
 * <p>从 {@code MaidFilePackets} 拆出。所有数量字段都走
 * {@code MaidFilePackets.checkSize} 做读端自律——线格式里的 VarInt 长度不可信，
 * 直接 {@code new ArrayList<>(len)} 会被畸形包打爆内存。
 *
 * <p>写端截断上限必须与读端 {@code checkSize} 的上限完全一致，否则超过上限时
 * 整包错位（历史上 {@code writeStringList} 出过这个 bug）。
 */
public final class MaidInfoCodec {

    private MaidInfoCodec() {
    }

    public static void writeMaidInfoList(FriendlyByteBuf buf, List<MaidInfo> list) {
        // 与读端 MaidFilePackets.checkSize(MaidFilePackets.MAX_MAID_LIST_ENTRIES) 对齐：超限截断，否则读端拒包直接断连
        int size = Math.min(list.size(), MaidFilePackets.MAX_MAID_LIST_ENTRIES);
        buf.writeVarInt(size);
        for (int i = 0; i < size; i++) {
            writeMaidInfo(buf, list.get(i));
        }
    }

    public static List<MaidInfo> readMaidInfoList(FriendlyByteBuf buf) {
        int size = MaidFilePackets.checkSize(buf.readVarInt(), MaidFilePackets.MAX_MAID_LIST_ENTRIES, "maidInfoList");
        List<MaidInfo> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(readMaidInfo(buf));
        }
        return list;
    }

    public static void writeMaidInfo(FriendlyByteBuf buf, MaidInfo info) {
        buf.writeInt(info.entityId());
        buf.writeUtf(info.modelId(), MaidFilePackets.MAX_TEXT_LEN);
        buf.writeUtf(info.displayName(), MaidFilePackets.MAX_TEXT_LEN);
        buf.writeVarInt(info.favorability());
        buf.writeFloat(info.health());
        buf.writeFloat(info.maxHealth());
        buf.writeBoolean(info.tamed());
        buf.writeBoolean(info.ownerUuid() != null);
        if (info.ownerUuid() != null) {
            buf.writeUUID(info.ownerUuid());
        }
        buf.writeBoolean(info.ownerName() != null);
        if (info.ownerName() != null) {
            buf.writeUtf(info.ownerName(), MaidFilePackets.MAX_TEXT_LEN);
        }
        buf.writeBoolean(info.customName() != null);
        if (info.customName() != null) {
            buf.writeUtf(info.customName(), MaidFilePackets.MAX_TEXT_LEN);
        }
        buf.writeBoolean(info.ysmModel());
    }

    public static MaidInfo readMaidInfo(FriendlyByteBuf buf) {
        int entityId = buf.readInt();
        String modelId = buf.readUtf(MaidFilePackets.MAX_TEXT_LEN);
        String displayName = buf.readUtf(MaidFilePackets.MAX_TEXT_LEN);
        int favorability = buf.readVarInt();
        float health = buf.readFloat();
        float maxHealth = buf.readFloat();
        boolean tamed = buf.readBoolean();
        UUID ownerUuid = buf.readBoolean() ? buf.readUUID() : null;
        String ownerName = buf.readBoolean() ? buf.readUtf(MaidFilePackets.MAX_TEXT_LEN) : null;
        String customName = buf.readBoolean() ? buf.readUtf(MaidFilePackets.MAX_TEXT_LEN) : null;
        boolean ysmModel = buf.readBoolean();
        return new MaidInfo(entityId, modelId, displayName, favorability, health, maxHealth, tamed,
                ownerUuid, ownerName, customName, ysmModel);
    }

    public static void writePlayerMaidGroups(FriendlyByteBuf buf, List<IMaidFileNetwork.PlayerMaidGroup> groups) {
        int size = Math.min(groups.size(), MaidFilePackets.MAX_PLAYER_GROUPS);
        buf.writeVarInt(size);
        for (int i = 0; i < size; i++) {
            IMaidFileNetwork.PlayerMaidGroup g = groups.get(i);
            buf.writeUtf(g.playerName(), MaidFilePackets.MAX_TEXT_LEN);
            buf.writeBoolean(g.consented());
            writeMaidInfoList(buf, g.maids());
        }
    }

    public static List<IMaidFileNetwork.PlayerMaidGroup> readPlayerMaidGroups(FriendlyByteBuf buf) {
        int size = MaidFilePackets.checkSize(buf.readVarInt(), MaidFilePackets.MAX_PLAYER_GROUPS, "playerGroups");
        List<IMaidFileNetwork.PlayerMaidGroup> groups = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            String name = buf.readUtf(MaidFilePackets.MAX_TEXT_LEN);
            boolean consented = buf.readBoolean();
            groups.add(new IMaidFileNetwork.PlayerMaidGroup(name, consented, readMaidInfoList(buf)));
        }
        return groups;
    }

    public static void writePlayerExportRequests(FriendlyByteBuf buf, List<IMaidFileNetwork.PlayerExportRequest> groups) {
        buf.writeVarInt(groups.size());
        for (IMaidFileNetwork.PlayerExportRequest g : groups) {
            buf.writeUtf(g.playerName(), MaidFilePackets.MAX_TEXT_LEN);
            MaidFilePackets.writeIntList(buf, g.entityIds());
        }
    }

    public static List<IMaidFileNetwork.PlayerExportRequest> readPlayerExportRequests(FriendlyByteBuf buf) {
        int size = MaidFilePackets.checkSize(buf.readVarInt(), MaidFilePackets.MAX_PLAYER_GROUPS, "playerExportRequests");
        List<IMaidFileNetwork.PlayerExportRequest> groups = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            String name = buf.readUtf(MaidFilePackets.MAX_TEXT_LEN);
            groups.add(new IMaidFileNetwork.PlayerExportRequest(name, MaidFilePackets.readIntList(buf)));
        }
        return groups;
    }
}
