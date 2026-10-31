package io.github.zgxhzhr.maidfm.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 备份浏览列表的线格式编解码（owner -> maids -> files 三层）。
 *
 * <p>从 {@code MaidFilePackets} 拆出。三层数量各有独立上限，读端超限一律拒绝
 * 而不是截断——这一路是 S2C 响应，坏包走异常断连/回执比静默丢数据更安全。
 */
public final class MaidBackupCodec {

    private MaidBackupCodec() {
    }

    public static void writeBackupOwners(FriendlyByteBuf buf, List<IMaidFileNetwork.BackupOwner> owners) {
        List<IMaidFileNetwork.BackupOwner> safe = owners == null ? List.of() : owners;
        buf.writeVarInt(Math.min(safe.size(), MaidFilePackets.MAX_BACKUP_OWNERS));
        int ownerCount = Math.min(safe.size(), MaidFilePackets.MAX_BACKUP_OWNERS);
        for (int i = 0; i < ownerCount; i++) {
            IMaidFileNetwork.BackupOwner owner = safe.get(i);
            buf.writeUtf(owner.ownerUuid() == null ? "" : owner.ownerUuid(), MaidFilePackets.MAX_TEXT_LEN);
            buf.writeUtf(owner.ownerName() == null ? "" : owner.ownerName(), MaidFilePackets.MAX_TEXT_LEN);
            List<IMaidFileNetwork.BackupMaid> maids = owner.maids() == null ? List.of() : owner.maids();
            int maidCount = Math.min(maids.size(), MaidFilePackets.MAX_BACKUP_MAIDS);
            buf.writeVarInt(maidCount);
            for (int j = 0; j < maidCount; j++) {
                IMaidFileNetwork.BackupMaid maid = maids.get(j);
                buf.writeUtf(maid.maidUuid() == null ? "" : maid.maidUuid(), MaidFilePackets.MAX_TEXT_LEN);
                buf.writeUtf(maid.maidName() == null ? "" : maid.maidName(), MaidFilePackets.MAX_TEXT_LEN);
                buf.writeUtf(maid.modelId() == null ? "" : maid.modelId(), MaidFilePackets.MAX_TEXT_LEN);
                buf.writeBoolean(maid.ysmModel());
                List<String> files = maid.files() == null ? List.of() : maid.files();
                int fileCount = Math.min(files.size(), MaidFilePackets.MAX_BACKUP_FILES);
                buf.writeVarInt(fileCount);
                for (int k = 0; k < fileCount; k++) {
                    buf.writeUtf(files.get(k) == null ? "" : files.get(k), MaidFilePackets.MAX_TEXT_LEN);
                }
            }
        }
    }

    public static List<IMaidFileNetwork.BackupOwner> readBackupOwners(FriendlyByteBuf buf) {
        int ownerCount = MaidFilePackets.checkSize(buf.readVarInt(), MaidFilePackets.MAX_BACKUP_OWNERS, "backupOwners");
        List<IMaidFileNetwork.BackupOwner> owners = new ArrayList<>(ownerCount);
        for (int i = 0; i < ownerCount; i++) {
            String ownerUuid = buf.readUtf(MaidFilePackets.MAX_TEXT_LEN);
            String ownerName = buf.readUtf(MaidFilePackets.MAX_TEXT_LEN);
            int maidCount = MaidFilePackets.checkSize(buf.readVarInt(), MaidFilePackets.MAX_BACKUP_MAIDS, "backupMaids");
            List<IMaidFileNetwork.BackupMaid> maids = new ArrayList<>(maidCount);
            for (int j = 0; j < maidCount; j++) {
                String maidUuid = buf.readUtf(MaidFilePackets.MAX_TEXT_LEN);
                String maidName = buf.readUtf(MaidFilePackets.MAX_TEXT_LEN);
                String modelId = buf.readUtf(MaidFilePackets.MAX_TEXT_LEN);
                boolean ysmModel = buf.readBoolean();
                int fileCount = MaidFilePackets.checkSize(buf.readVarInt(), MaidFilePackets.MAX_BACKUP_FILES, "backupFiles");
                List<String> files = new ArrayList<>(fileCount);
                for (int k = 0; k < fileCount; k++) {
                    files.add(buf.readUtf(MaidFilePackets.MAX_TEXT_LEN));
                }
                maids.add(new IMaidFileNetwork.BackupMaid(maidUuid, maidName, modelId, ysmModel, files));
            }
            owners.add(new IMaidFileNetwork.BackupOwner(ownerUuid, ownerName.isEmpty() ? null : ownerName, maids));
        }
        return owners;
    }
}
