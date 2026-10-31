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
 * 派生 UUID 时把 TLM 自动备份从旧 UUID 目录<b>复制</b>到新 UUID 目录。
 *
 * <p>从 {@code MaidTransferService} 抽出的独立职责：这条路径只在「源 UUID 被占用、
 * 导入时派生了新 UUID」时触发，绝大多数导入不会走到，但它涉及逐份改写备份 NBT，
 * 混在 2000 行的导入流程里既难读也难单独验证。
 *
 * <p><b>为什么必须改写而不是直接复制文件：</b>备份 {@code .dat} 是完整的实体存档，
 * 里面存着自己的 UUID（IntArray 4 个 int）。直接复制会让「新 UUID 目录下的备份」
 * 仍然自称旧 UUID——备份列表看着对，但导出 {@code .maid} 时 {@code source_maid_uuid}
 * 会取到旧值，再次导入又对不上。因此逐份改写 UUID 后再写盘，并同步 {@code index.dat}
 * （以女仆 UUID 为键的索引表）。
 *
 * <p><b>为什么是复制而不是移动（1.7.3 修正）：</b>触发本方法的条件是
 * {@code isUuidOccupied(源 UUID) == true}，而该方法只查<b>已加载实体</b>——也就是说
 * 源 UUID 被占用，几乎必然意味着「原女仆还活着且就在本世界」。此时把旧目录
 * <b>整体移走并删除</b>，等于把一只仍然活着的女仆的全部历史备份搬给新导入的副本，
 * 原女仆从此没有备份可用。这是不可逆的数据损失，且玩家完全无法察觉。
 *
 * <p>改为复制后两边各自独立：原女仆的备份完整保留，新导入的副本也拿到一份截至当前的
 * 历史快照。之后两者各自积累，互不影响。
 */
public final class TlmBackupCopier {

    private TlmBackupCopier() {
    }

    /**
     * 把 {@code <备份根>/<玩家UUID>/<旧女仆UUID>/} 里的备份复制到新 UUID 目录（改写 UUID），
     * 并同步 {@code index.dat}。<b>不删除原目录。</b>
     *
     * <p>任何失败都只告警、不抛出——备份复制是导入流程里的附带优化，
     * 失败时新副本只是没有历史备份，不影响导入本身。
     */
    public static void copyBackups(ServerPlayer player, UUID oldUuid, UUID newUuid) {
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
                    net.minecraft.nbt.NbtIo.writeCompressed(dat,
                            newDir.resolve(p.getFileName().toString()));
                    copied++;
                }
            }
            copyBackupIndex(ownerDir.resolve("index.dat"), oldUuid, newUuid);
            if (copied > 0) {
                Constants.LOG.info("[maid_file_manager] 已把 TLM 备份从 {} 复制到 {}"
                                + "（{} 个文件，已改写 UUID；原目录保留）",
                        oldUuid, newUuid, copied);
            }
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 复制 TLM 备份失败（不影响导入）: {}", t.toString());
        }
    }

    /** UUID → Minecraft 实体 NBT 里的 IntArray 形态（高/低 64 位各拆成两个 int）。 */
    public static int[] uuidToIntArray(UUID uuid) {
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        return new int[]{(int) (msb >> 32), (int) msb, (int) (lsb >> 32), (int) lsb};
    }

    /**
     * 把 {@code index.dat} 里以旧 UUID 为键的条目<b>复制</b>一份到新 UUID 键下。
     *
     * <p>旧键保留——索引表同样跟着原女仆走，不能因为派生就把它抹掉。
     */
    private static void copyBackupIndex(Path indexFile, UUID oldUuid, UUID newUuid) {
        try {
            if (!Files.isRegularFile(indexFile)) {
                return;
            }
            CompoundTag index = MaidFileIo.readCompressedNbt(indexFile);
            if (index == null || !index.contains(oldUuid.toString())) {
                return;
            }
            CompoundTag entry = index.getCompound(oldUuid.toString()).copy();
            index.put(newUuid.toString(), entry);
            net.minecraft.nbt.NbtIo.writeCompressed(index, indexFile);
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 复制备份索引条目失败（残留不影响功能）: {}", t.toString());
        }
    }
}
