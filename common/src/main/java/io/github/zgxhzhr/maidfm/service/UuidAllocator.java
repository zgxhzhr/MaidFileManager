package io.github.zgxhzhr.maidfm.service;

import io.github.zgxhzhr.maidfm.Constants;
import io.github.zgxhzhr.maidfm.data.MaidFileData;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * 女仆实体 UUID 的解析与派生。
 *
 * <p>从 {@code MaidTransferService} 抽出的纯逻辑部分。这个类<b>只做 UUID 计算与查询</b>，
 * 不涉及导入编排、不写任何持久化数据——需要落盘的动作（幂等记录、TLM 备份迁移）仍留在
 * 调用方。
 *
 * <p>抽出的动机：{@code MaidTransferService} 已经 2000 行，而这几段逻辑彼此内聚、与导入
 * 主流程只有「输入 UUID 得输出 UUID」的关系，单独成类后可以被 {@code SelfTest} 直接覆盖。
 */
public final class UuidAllocator {

    private UuidAllocator() {
    }

    /**
     * 解析源女仆实体 UUID：优先读 .maid 顶层 {@code source_maid_uuid}（v6 起写入）；
     * 旧文件无此字段时从实体根 NBT 兜底（1.9+ int 数组 "UUID"，或 1.8 的 UUIDMost/UUIDLeast）。
     *
     * <p><b>注意必须在 {@code NbtMigration#migrate} 之前调用</b>——迁移会恒删实体根 UUID 键。
     *
     * @return 源女仆 UUID；无法解析（极早期文件且 NBT 缺键）返回 null，调用方退回随机实体 UUID
     */
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
                if (originalNbt.hasUUID("UUID")) {
                    return originalNbt.getUUID("UUID");
                }
                if (originalNbt.contains("UUIDMost", Tag.TAG_LONG)
                        && originalNbt.contains("UUIDLeast", Tag.TAG_LONG)) {
                    return new UUID(originalNbt.getLong("UUIDMost"), originalNbt.getLong("UUIDLeast"));
                }
            } catch (Throwable t) {
                Constants.LOG.warn("[maid_file_manager] 从实体 NBT 解析源女仆 UUID 失败: {}", t.toString());
            }
        }
        return null;
    }

    /**
     * 判定 UUID 是否已被占用。只查已加载实体（任意维度）：未加载区块里的实体查不到，
     * 但那种情况下 {@code addFreshEntity} 本身不会因 UUID 冲突失败，按「可用」处理更贴近原生行为。
     */
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

    /**
     * 确定性导入 UUID 映射：同一玩家导入同一源女仆，永远得到同一目标 UUID；
     * 不同玩家（多人服分享文件）因玩家 UUID 参与哈希而得到不同 UUID，互不冲突。
     *
     * <p>使用 JDK 内置的 v3(MD5) 名字 UUID：固定命名前缀隔离用途，输出带版本/变体位，
     * 与原版随机 v4 实体 UUID 不会发生有意义的碰撞。这个函数是<b>纯函数、无存储</b>，
     * 所以不存在映射累积问题。
     */
    public static UUID mapImportUuid(UUID sourceMaidUuid, UUID importerPlayerUuid) {
        String name = "maid_file_manager|import-v1|" + sourceMaidUuid + "|" + importerPlayerUuid;
        return UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * 判定指定 UUID 在已加载世界（任意维度）中是否已有女仆实体。
     *
     * <p>仅供 {@code addFreshEntity} 失败后的原因细分：同 UUID 实体只有处在已加载区块时
     * 才会导致原生拒绝，未加载区块 / 魂符中的实体查不到也不应影响判定（那些场景导入本就成功）。
     * {@code server} 为 null 时返回 false，使调用方落回通用失败文案。
     */
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
