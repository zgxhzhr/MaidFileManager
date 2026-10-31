package io.github.zgxhzhr.maidfm.data;

import io.github.zgxhzhr.maidfm.Constants;
import io.github.zgxhzhr.maidfm.spi.MaidBaubleCarrierRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * 跨 Minecraft 版本的女仆 NBT 迁移器。
 *
 * <p>契约（调用方可依赖的保证）：
 * <ul>
 *   <li>{@link #migrate} 不修改入参，始终返回一个清理后的副本</li>
 *   <li>运行时状态/物品容器标签对所有来源（含版本未知的旧文件）一律清理，
 *       避免 Health&lt;=0 / DeathTime&gt;0 导致导入即死亡、Attributes modifier
 *       UUID 跨版本冲突、物品组件化导致的解析异常</li>
 *   <li>版本相关的结构改名仅在来源/目标版本都明确且确实跨版本时执行</li>
 *   <li>{@code MaidBaubleInventory} 绝不在删除列表中（导入端饰品恢复依赖它）</li>
 * </ul>
 */
public final class NbtMigration {

    /**
     * 运行时污染标签：与版本无关的卫生清理，任何来源文件都执行。
     * UUID 恒删 —— 导入实体一律由构造器生成新 UUID（硬约束：避免跨世界冲突），
     * 位置/朝向由导入主流程按玩家附近安全点重建。
     * 注意 1.9+ 实体根 UUID 键为 int 数组 "UUID"；UUIDLeast/UUIDMost 是 1.8 旧格式，两者都删。
     * 此处仅作用于实体根标签，不影响 OwnerUUID 等独立业务键。
     */
    private static final String[] RUNTIME_STATE_TAGS_TO_REMOVE = {
            "Health", "HurtTime", "DeathTime", "HurtByTimestamp",
            "Fire", "Air", "LifeTicks", "PortalCooldown", "FallDistance",
            "FallFlying", "NoGravity", "Glowing", "Invulnerable",
            "HasVisualFire", "TicksFrozen", "FrozenTicks",
            // 1.21 的实体存档把属性修饰符写在**小写** attributes 下；只删大写 Attributes 会让 1.21 导入
            // 保留源属性修饰符，与 1.20 的「属性回白板」硬约束不等价。
            "Attributes", "attributes", "ActiveEffects", "active_effects", "Effects", "AbsorptionAmount",
            "SleepingX", "SleepingY", "SleepingZ", "Brain", "Saddle",
            "Bukkit.updateLevel", "Bukkit.values",  // Paper/Arclight 残留（防御）
            "Motion", "Rotation", "FallHurtDistance",
            "Leash", "UUID", "UUIDLeast", "UUIDMost",
            "Pos",
            "CanPickUpLoot",
            "Passengers",
    };

    /** 易变物品容器：导出端已清空，跨版本直接删除让目标端重建合法空容器 */
    private static final String[] INVENTORY_TAGS_TO_REMOVE = {
            "MaidInventory", "MaidHideInventory", "MaidTaskInventory",
            "BackpackData", "MaidBackpackData",
            "HandItems", "ArmorItems", "HandDropChances", "ArmorDropChances",
    };

    /**
     * 日程/家园位置：**一律删除**（不是只在跨版本时）。
     *
     * <p>这些键指向源存档的坐标；导入后女仆位置由主流程按玩家前方重新计算
     * （findSafeSpawnPos + setPos），旧坐标在新世界里没有意义——保留反而会让
     * 工作点/睡觉点/家位置指向错误地点。原注释写的「跨版本」与下面无条件执行的
     * 实现不符，已更正。
     */
    private static final String[] SCHEDULE_TAGS_TO_REMOVE = {
            "MaidSchedulePos", "MaidWorkPos", "MaidIdlePos", "MaidSleepPos",
            "MaidRestrictPos", "MaidRestrictCenter", "MaidRestrictRadius",
            "BedPosition", "HomePos",
    };

    private NbtMigration() {}
    public static CompoundTag migrate(CompoundTag data, int sourceVersion, int targetVersion,
                                      CuriosBaubleFilter.Stats curiosStatsOut,
                                      CuriosItemPolicy curiosPolicy, boolean stripAttributes,
                                      boolean carryCurios) {
        if (data == null) {
            return null;
        }
        // 不修改入参：所有清理都在副本上进行
        CompoundTag out = data.copy();

        // ---------- 运行时卫生清理：同版本/跨版本/版本未知，一律执行 ----------
        for (String tag : RUNTIME_STATE_TAGS_TO_REMOVE) {
            out.remove(tag);
        }
        for (String tag : INVENTORY_TAGS_TO_REMOVE) {
            out.remove(tag);
        }
        for (String tag : SCHEDULE_TAGS_TO_REMOVE) {
            out.remove(tag);
        }

        // Curios 饰品：导入侧按同一规则再裁剪一次（防御纵深）。
        // 旧版本导出的 .maid 文件可能仍带着 Curios 载荷，未声明的命名空间不得借文件绕过本地注册表。
        CuriosBaubleFilter.Result curiosResult = CuriosBaubleFilter.strip(out,
                carryCurios,
                curiosPolicy, stripAttributes);
        if (curiosStatsOut != null) {
            curiosStatsOut.add(curiosResult);
        }

        // ---------- 仅在版本明确且真正跨版本时做结构改名/定向迁移 ----------
        boolean versionsKnown = sourceVersion != NbtVersion.UNKNOWN && targetVersion != NbtVersion.UNKNOWN;
        if (versionsKnown && sourceVersion != targetVersion) {
            renameModelId(out, sourceVersion, targetVersion);
            // YSM 相关键必须**整组**处理：只删 YsmModelName 会留下 IsYsmModel/YsmModelId，
            // 目标端据此走 YSM 分支但名字为空，比全删或全留都糟。跨版本统一整组清空，
            // 让目标端退回按 model_id 加载（YsmRoamingVars 属漫游行为数据，与此无关，保留）。
            for (String ysmKey : new String[]{
                    "IsYsmModel", "YsmModelId", "YsmModelName", "YsmModelTexture"}) {
                if (out.contains(ysmKey)) {
                    out.remove(ysmKey);
                }
            }
            Constants.LOG.info("[maid_file_manager] NBT 跨版本迁移完成: {} -> {}", sourceVersion, targetVersion);
        }
        return out;
    }

    /** ModelId 在 1.20.x 为 "ModelId"，1.21 起 TLM 改名为 "model_id"，双向兼容 */
    private static void renameModelId(CompoundTag data, int sourceVersion, int targetVersion) {
        if (sourceVersion <= NbtVersion.MC_1_20_1 && targetVersion >= NbtVersion.MC_1_21) {
            if (data.contains("ModelId", Tag.TAG_STRING) && !data.contains("model_id", Tag.TAG_STRING)) {
                String modelId = data.getString("ModelId");
                data.putString("model_id", modelId);
                data.remove("ModelId");
            }
        }
        if (sourceVersion >= NbtVersion.MC_1_21 && targetVersion <= NbtVersion.MC_1_20_1) {
            if (data.contains("model_id", Tag.TAG_STRING) && !data.contains("ModelId", Tag.TAG_STRING)) {
                String modelId = data.getString("model_id");
                data.putString("ModelId", modelId);
                data.remove("model_id");
            }
        }
    }
}
