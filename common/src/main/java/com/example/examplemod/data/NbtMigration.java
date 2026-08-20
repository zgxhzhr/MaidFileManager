package com.example.examplemod.data;

import com.example.examplemod.Constants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * 跨 Minecraft 版本的女仆 NBT 迁移器。
 * <p>
 * 最终版策略：仅保留 TLM 专有字段（ModelId/Favorability/Tame/OwnerUUID/CustomName/
 * Sitting/Task/Pose/BackpackType 等），LivingEntity/Mob/Entity 层的所有运行时状态
 * 一律删除；导入端再由 setter 强制重填为合法值。
 * <p>
 * 已通过日志验证的根因：
 * <ul>
 *   <li>1.21 {@code SchedulePos.save()} 对 workPos/idlePos/sleepPos 不判 null，
 *       读到 Optional.empty().orElse(null) → 存档阶段 NPE → EntityStorage 明确：
 *       "It WILL NOT PERSIST" → 重进消失。解决方案见 {@code ensureSchedulePosNonNull}。</li>
 * </ul>
 */
public final class NbtMigration {

    /** Entity/LivingEntity/Mob 层所有运行时污染标签。只保留 PersistenceRequired=true。 */
    private static final String[] RUNTIME_STATE_TAGS_TO_REMOVE = {
        "Health", "HurtTime", "DeathTime", "HurtByTimestamp",
        "Fire", "Air", "LifeTicks", "PortalCooldown", "FallDistance",
        "FallFlying", "NoGravity", "Glowing", "Invulnerable",
        "HasVisualFire", "TicksFrozen", "FrozenTicks",
        "Attributes", "ActiveEffects", "Effects", "AbsorptionAmount",
        "SleepingX", "SleepingY", "SleepingZ", "Brain", "Saddle",
        "Bukkit.updateLevel", "Bukkit.values",
        "Motion", "Rotation", "FallHurtDistance", "Pos",
        "UUIDLeast", "UUIDMost",
        "Leash",
        "CanPickUpLoot",
        "Passengers",
    };

    private NbtMigration() {}

    public static CompoundTag migrate(CompoundTag data, int sourceVersion, int targetVersion) {
        if (data == null) return null;
        if (sourceVersion == targetVersion
                || sourceVersion == NbtVersion.UNKNOWN
                || targetVersion == NbtVersion.UNKNOWN) {
            return data;
        }

        for (String tag : new String[] {
            "MaidInventory", "MaidBaubleInventory", "MaidHideInventory", "MaidTaskInventory",
            "BackpackData", "MaidBackpackData",
        }) {
            data.remove(tag);
        }

        data.remove("HandItems");
        data.remove("ArmorItems");
        data.remove("HandDropChances");
        data.remove("ArmorDropChances");
        // PersistenceRequired：坚决不删！

        for (String tag : RUNTIME_STATE_TAGS_TO_REMOVE) {
            data.remove(tag);
        }
        // ---------- 日程/位置容器：跨版本一律删除，在 MaidTransferService 再填安全值 ----------
        data.remove("MaidSchedulePos");
        data.remove("MaidWorkPos");
        data.remove("MaidIdlePos");
        data.remove("MaidSleepPos");
        data.remove("MaidRestrictPos");
        data.remove("MaidRestrictCenter");
        data.remove("MaidRestrictRadius");
        data.remove("BedPosition");
        data.remove("HomePos");

        renameCommonTags(data, sourceVersion, targetVersion);

        if (sourceVersion <= NbtVersion.MC_1_20_1 && targetVersion >= NbtVersion.MC_1_21) {
            migrate1201To121(data);
        }
        if (sourceVersion >= NbtVersion.MC_1_21 && targetVersion <= NbtVersion.MC_1_20_1) {
            migrate121To1201(data);
        }
        return data;
    }

    private static void migrate1201To121(CompoundTag data) {
        if (data.contains("YsmModelName", Tag.TAG_STRING)) {
            data.remove("YsmModelName");
        }
        Constants.LOG.info("[maid_file_manager] NBT migrated: 1.20.x -> 1.21.x (full purge)");
    }

    private static void migrate121To1201(CompoundTag data) {
        if (data.contains("YsmModelName", Tag.TAG_STRING)) {
            data.remove("YsmModelName");
        }
        Constants.LOG.info("[maid_file_manager] NBT migrated: 1.21.x -> 1.20.x (full purge)");
    }

    private static void renameCommonTags(CompoundTag data, int sourceVersion, int targetVersion) {
        if (sourceVersion <= NbtVersion.MC_1_20_1 && targetVersion >= NbtVersion.MC_1_21) {
            if (data.contains("ModelId", Tag.TAG_STRING) && !data.contains("model_id", Tag.TAG_STRING)) {
                String modelId = data.getString("ModelId");
                data.putString("model_id", modelId);
                data.remove("ModelId");
                Constants.LOG.info("[maid_file_manager] NBT renamed: ModelId -> model_id (value={})", modelId);
            }
        }
        if (sourceVersion >= NbtVersion.MC_1_21 && targetVersion <= NbtVersion.MC_1_20_1) {
            if (data.contains("model_id", Tag.TAG_STRING) && !data.contains("ModelId", Tag.TAG_STRING)) {
                String modelId = data.getString("model_id");
                data.putString("ModelId", modelId);
                data.remove("model_id");
                Constants.LOG.info("[maid_file_manager] NBT renamed: model_id -> ModelId (value={})", modelId);
            }
        }
    }
}
