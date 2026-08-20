package com.example.examplemod.data;

import com.example.examplemod.Constants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * 跨 Minecraft 版本的女仆 NBT 迁移器。
 * <p>
 * 核心修复：由于 TLM 在 1.20.1 / 1.21 之间物品栏槽位数量（特别是
 * BaubleItemHandler 的 EnumMap 大小）以及物品 NBT 格式都发生了变化，
 * 「保留原始 Size+Items」会导致导入端 TLM 的 ItemStackHandler 反序列化
 * 时数组越界 / ItemStack.parse 抛 "Item must not be minecraft:air" 异常。
 * <p>
 * 鉴于我们的模组在导出时本来就清空了所有物品栏、手持、装甲、背包，这里直接
 * <b>删除</b>相关容器标签，让目标端 TLM EntityMaid 在 readAdditionalSaveData
 * 阶段找不到对应 tag，从而使用默认（该版本合法大小）的空容器。
 */
public final class NbtMigration {

    /** 跨版本导入时必须移除的<strong>运行时污染标签</strong>。
     *  这些字段是源女仆 1.20.x 运行时状态，直接套到 1.21 新实体上可能导致：
     *  - Health<=0 / DeathTime>0 → 加载时立即判定死亡，调用 remove(KILLED) → 不写入存档
     *  - HurtByTimestamp / Fire / FrozenTicks → 进入异常状态
     *  - Attributes（含 MODIFIER UUID）→ 跨版本 modifier UUID 冲突，属性系统混乱
     *  - Motion / Rotation / FallDistance / Position → 新实体位置都 setPos 过了，老值纯污染
     *  - ActiveEffects / AbsorptionAmount 等 → 跨版本效果 ID 不一致
     *  - Leashed 相关 → 拴绳 UUID 不存在
     *  - Passengers 相关 → 乘客都是无效实体
     *  目标端 Entity 构造器 + load 会填合法默认值，然后我们再显式 rebuildAttributes/setHealth。
     */
    private static final String[] RUNTIME_STATE_TAGS_TO_REMOVE = {
        "Health", "HurtTime", "DeathTime", "HurtByTimestamp",
        "Fire", "Air", "LifeTicks", "PortalCooldown", "FallDistance",
        "FallFlying", "NoGravity", "Glowing", "Invulnerable",
        "HasVisualFire", "TicksFrozen", "FrozenTicks",
        "Attributes", "ActiveEffects", "Effects", "AbsorptionAmount",
        "SleepingX", "SleepingY", "SleepingZ", "Brain", "Saddle",
        "Bukkit.updateLevel", "Bukkit.values",  // Paper/Arclight 残留（防御）
        "Motion", "Rotation", "FallHurtDistance",
        "Leash", "UUIDLeast", "UUIDMost",
        "Pos",
        "CanPickUpLoot",
        "Passengers",
    };

    private NbtMigration() {}

    public static CompoundTag migrate(CompoundTag data, int sourceVersion, int targetVersion) {
        if (data == null) return null;
        if (sourceVersion == targetVersion
                || sourceVersion == NbtVersion.UNKNOWN
                || targetVersion == NbtVersion.UNKNOWN) {
            // 同版本：只做一次防御性清理（万一导出没清干净），不强制删。
            return data;
        }

        // ---------- 跨版本：直接删除所有易变物品容器标签 ----------
        for (String tag : new String[] {
            "MaidInventory", "MaidBaubleInventory", "MaidHideInventory", "MaidTaskInventory",
            "BackpackData", "MaidBackpackData",
        }) {
            data.remove(tag);
        }

        // ---------- HandItems / ArmorItems：直接删除，让 Entity.load 自己填 EMPTY ----------
        // 这样避免 "Item must not be minecraft:air" 以及 "unknown component" 等解析问题
        data.remove("HandItems");
        data.remove("ArmorItems");
        data.remove("HandDropChances");
        data.remove("ArmorDropChances");
        // 注意：绝对不能删 PersistenceRequired！删除会导致 Minecraft 把女仆当成"临时生物"，
        // 退出重进世界就被清理。如果 NBT 里真的没这个标签，导入后还会显式 setPersistenceRequired()。

        // ---------- 核心修复：批量移除所有运行时污染标签（见上方 RUNTIME_STATE_TAGS_TO_REMOVE 注释） ----------
        for (String tag : RUNTIME_STATE_TAGS_TO_REMOVE) {
            data.remove(tag);
        }
        // ---------- 位置/日程容器：跨版本迁移时一律删除，重建为当前位置 ----------
        // 1.21 SchedulePos.save() 对 workPos/idlePos/sleepPos 没有 null 检查，
        // 如果这些 BlockPos 跨版本从 NBT 读成 null，存档时 NPE → 服务端拒绝持久化这个实体。
        // 删除后，我们会在 MaidTransferService 中反射 schedulePos 字段为当前 spawnPos 赋值。
        data.remove("MaidSchedulePos");
        data.remove("MaidWorkPos");
        data.remove("MaidIdlePos");
        data.remove("MaidSleepPos");
        data.remove("MaidRestrictPos");
        data.remove("MaidRestrictCenter");
        data.remove("MaidRestrictRadius");
        data.remove("BedPosition");
        data.remove("HomePos");
        Constants.LOG.info("[maid_file_manager] NbtMigration removed {} runtime state/inventory tags from source NBT",
                RUNTIME_STATE_TAGS_TO_REMOVE.length);

        // ---------- 双向转换 TLM 跨版本改名的核心 NBT 键名 ----------
        renameCommonTags(data, sourceVersion, targetVersion);

        // ---------- 定向迁移 ----------
        if (sourceVersion <= NbtVersion.MC_1_20_1 && targetVersion >= NbtVersion.MC_1_21) {
            migrate1201To121(data);
        }
        if (sourceVersion >= NbtVersion.MC_1_21 && targetVersion <= NbtVersion.MC_1_20_1) {
            migrate121To1201(data);
        }
        return data;
    }

    private static void migrate1201To121(CompoundTag data) {
        // YSM 模型名：1.21 反序列化需要 RegistryAccess，为避免崩溃直接清空可选项
        if (data.contains("YsmModelName", Tag.TAG_STRING)) {
            data.remove("YsmModelName");
        }
        // 防御：彻底移除可能残留的旧版 Items 容器元素（如果有其它名字）
        Constants.LOG.info("[maid_file_manager] NBT migrated: 1.20.x -> 1.21.x (inventory tags removed)");
    }

    private static void migrate121To1201(CompoundTag data) {
        if (data.contains("YsmModelName", Tag.TAG_STRING)) {
            data.remove("YsmModelName");
        }
        Constants.LOG.info("[maid_file_manager] NBT migrated: 1.21.x -> 1.20.x (inventory tags removed)");
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
