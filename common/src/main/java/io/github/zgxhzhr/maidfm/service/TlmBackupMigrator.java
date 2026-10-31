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
 * <p>从 {@code MaidTransferService} 抽出的独立职责：这条路径只在「原 UUID 被占用、
 * 导入时派生了新 UUID」时触发，绝大多数导入不会走到，但它涉及<b>逐份改写备份 NBT
 * 并删除原目录</b>，混在 2000 行的导入流程里既难读也难单独验证。
 *
 * <p><b>为什么必须改写而不是复制：</b>备份 {@code .dat} 是完整的实体存档，里面存着
 * 自己的 UUID（IntArray 4 个 int）。直接复制会让「新 UUID 目录下的备份」仍然自称旧
 * UUID——备份列表看着对，但导出 {@code .maid} 时 {@code source_maid_uuid} 会取到旧值，
 * 再次导入又对不上。因此逐份改写 UUID 后再写盘，并同步 {@code index.dat}
 * （以女仆 UUID 为键的索引表），最后删除原目录。
 */
public final class TlmBackupMigrator {

    private TlmBackupMigrator() {
    }

    /**
     * 把 {@code <备份根>/<玩家UUID>/<旧女仆UUID>/} 整个目录迁移到新 UUID 目录。
     *
     * <p>任何失败都只告警、不抛出——备份迁移是导入流程里的兜底优化，
     * 失败时残留旧目录不影响功能，不能因此让导入失败。
     */
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
                    // 1.21+ 的 NbtIo.writeCompressed 直接接收 Path（1.20 才是 File）
                    net.minecraft.nbt.NbtIo.writeCompressed(dat,
                            newDir.resolve(p.getFileName().toString()));
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

    /** UUID → Minecraft 实体 NBT 里的 IntArray 形态（高/低 64 位各拆成两个 int）。 */
    static int[] uuidToIntArray(UUID uuid) {
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        return new int[]{(int) (msb >> 32), (int) msb, (int) (lsb >> 32), (int) lsb};
    }

    /** 把 {@code index.dat} 里以旧 UUID 为键的条目改名到新 UUID（TLM 备份列表的索引表）。 */
    private static void rewriteBackupIndex(Path indexFile, UUID oldUuid, UUID newUuid) {
        try {
            if (!Files.isRegularFile(indexFile)) {
                return;
            }
            CompoundTag index = MaidFileIo.readCompressedNbt(indexFile);
            if (index == null || !index.contains(oldUuid.toString())) {
                return;
            }
            CompoundTag entry = index.getCompound(oldUuid.toString()).copy();
            index.remove(oldUuid.toString());
            index.put(newUuid.toString(), entry);
            net.minecraft.nbt.NbtIo.writeCompressed(index, indexFile);
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 更新备份索引失败（残留不影响功能）: {}", t.toString());
        }
    }

    /** 递归删除目录（失败只告警：保留残留比删错文件安全）。 */
    private static void deleteRecursively(Path dir) {
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
