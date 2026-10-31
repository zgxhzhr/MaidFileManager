package io.github.zgxhzhr.maidfm.service;

import io.github.zgxhzhr.maidfm.Constants;
import io.github.zgxhzhr.maidfm.data.MaidFileData;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * 女仆实体 UUID 的解析与派生。
 *
 * <p>从 {@code MaidTransferService} 抽出的纯逻辑部分：只做 UUID 计算与查询，
 * 不涉及导入编排、不写任何持久化数据。
 */
public final class UuidAllocator {

    private UuidAllocator() {
    }

    public static UUID resolveSourceMaidUuid(MaidFileData data, CompoundTag originalNbt) {
        String topLevel = data.getSourceMaidUuid();
        if (topLevel != null) {
            try {
                return UUID.fromString(topLevel);
            } catch (IllegalArgumentException ignored) {
                // 顶层字段非法，落回实体 NBT 兜底
            }
        }
        if (originalNbt != null) {
            try {
                // 26.1：CompoundTag 不再提供 hasUUID/getUUID，UUID 以 int[4] 存储
                int[] uuidInts = originalNbt.getIntArray("UUID").orElse(null);
                if (uuidInts != null && uuidInts.length == 4) {
                    return new UUID(((long) uuidInts[0] << 32) | (uuidInts[1] & 0xFFFFFFFFL),
                            ((long) uuidInts[2] << 32) | (uuidInts[3] & 0xFFFFFFFFL));
                }
                if (originalNbt.contains("UUIDMost")
                        && originalNbt.contains("UUIDLeast")) {
                    return new UUID(originalNbt.getLongOr("UUIDMost", 0L), originalNbt.getLongOr("UUIDLeast", 0L));
                }
            } catch (Throwable t) {
                Constants.LOG.warn("[maid_file_manager] 从实体 NBT 解析源女仆 UUID 失败: {}", t.toString());
            }
        }
        return null;
    }

    public static boolean isUuidOccupied(MinecraftServer server, UUID uuid) {
        if (server == null || uuid == null) {
            return false;
        }
        for (ServerLevel serverLevel : server.getAllLevels()) {
            if (serverLevel.getEntity(uuid) != null) {
                return true;
            }
        }
        return false;
    }

    public static UUID mapImportUuid(UUID sourceMaidUuid, UUID importerPlayerUuid) {
        String name = "maid_file_manager|import-v1|" + sourceMaidUuid + "|" + importerPlayerUuid;
        return UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static boolean isLoadedMaidWithUuid(MinecraftServer server, UUID entityId) {
        if (server == null) {
            return false;
        }
        for (ServerLevel serverLevel : server.getAllLevels()) {
            if (serverLevel.getEntity(entityId) instanceof EntityMaid) {
                return true;
            }
        }
        return false;
    }

    /**
     * 导入 UUID 决策（只读，不落库、不搬备份）。
     *
     * @param targetUuid   该用的实体 UUID；{@code null} 表示源文件没有可用源 UUID
     * @param commitNeeded 是否需要把结果写入幂等记录（已有记录时为 false，无需重写）
     */
    public record ImportUuidDecision(UUID targetUuid, boolean commitNeeded) {
    }

    /**
     * 决定导入实体该用哪个 UUID——<b>纯查询，不产生任何副作用</b>。
     *
     * <p>刻意把「查/算」与「写」分开：调用方要先 {@code addFreshEntity}，实体真的入世界
     * 之后才写入幂等记录并迁移 TLM 备份。否则导入失败时会留下孤儿记录，而备份迁移是
     * 删除原目录的不可逆操作，更不能在实体没建成时执行。
     *
     * <p>判定顺序（与原实现一致）：幂等记录 -> 原 UUID 未被占用则沿用 -> 否则派生。
     */
    public static ImportUuidDecision decideImportUuid(ServerPlayer player, UUID sourceMaidUuid) {
        if (sourceMaidUuid == null) {
            return new ImportUuidDecision(null, false);
        }
        MinecraftServer server = player.level().getServer();
        UUID recorded = ArchiveImportMap.lookup(server, sourceMaidUuid, player.getUUID());
        if (recorded != null) {
            return new ImportUuidDecision(recorded, false);
        }
        if (!isUuidOccupied(server, sourceMaidUuid)) {
            return new ImportUuidDecision(sourceMaidUuid, true);
        }
        return new ImportUuidDecision(mapImportUuid(sourceMaidUuid, player.getUUID()), true);
    }
}
