package io.github.zgxhzhr.maidfm.service;

import io.github.zgxhzhr.maidfm.Constants;
import io.github.zgxhzhr.maidfm.data.MaidFileIo;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 派生 UUID 时把车万女仆的自动备份从旧 UUID 目录迁到新 UUID 目录。
 *
 * <p>备份 {@code .dat} 是完整实体存档、里面存着自己的 UUID，直接复制会让新目录下的
 * 备份仍自称旧 UUID，所以必须逐份改写 UUID 后再写盘，并同步 {@code index.dat}。
 */
public final class TlmBackupMigrator {

    private TlmBackupMigrator() {
    }

    public static void migrate(ServerPlayer player, UUID oldUuid, UUID newUuid) {
        try {
            MinecraftServer server = player.level().getServer();
            if (server == null) {
                return;
            }
            Path root = MaidBackupService.backupRoot(server);
            String owner = player.getUUID().toString();
            Path ownerDir = root.resolve(owner);
            Path oldDir = ownerDir.resolve(oldUuid.toString());
            if (!Files.isDirectory(oldDir)) {
                return;
            }
            Path newDir = ownerDir.resolve(newUuid.toString());
            Files.createDirectories(newDir);
            int[] newUuidArray = uuidToIntArray(newUuid);
            int copied = 0;
            try (var stream = Files.list(oldDir)) {
                for (Path p : stream.toList()) {
                    if (!Files.isRegularFile(p)) {
                        continue;
                    }
                    CompoundTag dat = MaidFileIo.readCompressedNbt(p);
                    if (dat == null) {
                        continue;
                    }
                    dat.putIntArray("UUID", newUuidArray);
                    net.minecraft.nbt.NbtIo.writeCompressed(dat, newDir.resolve(p.getFileName().toString()));
                    copied++;
                }
            }
            rewriteBackupIndex(ownerDir.resolve("index.dat"), oldUuid, newUuid);
            deleteRecursively(oldDir);
            Constants.LOG.info("[maid_file_manager] 已把 TLM 备份从 {} 迁到 {}（{} 个文件，已改写 UUID 并删除原目录）",
                    oldUuid, newUuid, copied);
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 迁移 TLM 备份失败（不影响导入）: {}", t.toString());
        }
    }

    public static void rewriteBackupIndex(Path indexFile, UUID oldUuid, UUID newUuid) {
        try {
            if (!Files.isRegularFile(indexFile)) {
                return;
            }
            CompoundTag index = MaidFileIo.readCompressedNbt(indexFile);
            if (index == null || !index.contains(oldUuid.toString())) {
                return;
            }
            CompoundTag entry = index.getCompoundOrEmpty(oldUuid.toString()).copy();
            index.remove(oldUuid.toString());
            index.put(newUuid.toString(), entry);
            net.minecraft.nbt.NbtIo.writeCompressed(index, indexFile);
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 更新备份索引失败（残留不影响功能）: {}", t.toString());
        }
    }    /** 递归删除目录（失败只告警：保留残留比删错文件安全） */

    public static int[] uuidToIntArray(UUID uuid) {
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        return new int[]{(int) (msb >> 32), (int) msb, (int) (lsb >> 32), (int) lsb};
    }

    public static void deleteRecursively(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            for (Path p : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 清理旧备份目录失败（残留不影响功能）: {}", t.toString());
        }
    }
}
