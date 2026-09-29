package io.github.zgxhzhr.maidfm.service;

import io.github.zgxhzhr.maidfm.Constants;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;

/**
 * 成就转移工具：导出时收集源玩家 TLM 成就并按女仆属性过滤；导入时合并到原主人。
 *
 * <h3>设计原则</h3>
 * <ul>
 *   <li>仅转移 {@code touhou_little_maid} 命名空间下的成就</li>
 *   <li>导出时对"属性绑定型"成就按女仆实际属性过滤：仅当当前女仆达标才包含该成就，
 *       避免导入 B 女仆时带出 A 女仆的满好感度成就</li>
 *   <li>导入时采用"合并只补未完成"策略：目标玩家已完成的成就不动，仅补全未完成的</li>
 *   <li>跨版本时目标服务端不认识的成就 ID 安全跳过</li>
 * </ul>
 */
public final class AdvancementTransfer {

    /** TLM 命名空间 */
    private static final String TLM_NS = "touhou_little_maid";

    /** TLM 好感度满级阈值（Level 3 = 384 点） */
    private static final int FAVORABILITY_MAX = 384;
    /** 满血成就阈值 */
    private static final float MAID_100_HEALTHY_THRESHOLD = 100f;

    /**
     * 不可转移的 TLM 进度路径：开局赠送型进度。
     *
     * <p>这两条进度的奖励就是物品本身（女仆魂符 / 帕秋莉手册），正常途径只能由玩家登录事件
     * 触发，且触发条件受 TLM 配置 {@code MiscConfig.GIVE_SMART_SLAB} /
     * {@code MiscConfig.GIVE_PATCHOULI_BOOK} 守卫——玩家在配置中关闭开局赠送后，
     * 进度永远不会完成。
     *
     * <p>直接对玩家 {@code Advancement#award} 会绕过 trigger 的配置 predicate 强行完成进度、
     * 发放奖励物品，表现为「导入女仆时被强行补发手册和魂符」。因此收集与合并两端都必须排除；
     * 合并端排除同时兼容已包含这些条目的旧 .maid 文件。
     *
     * <p>两个进度在不同 MC 版本中的路径（ID 末段）：
     * <ul>
     *   <li>女仆魂符：1.20.x 与 1.21.x 均为 {@code give_smart_slab}</li>
     *   <li>帕秋莉手册：1.20.x 为 {@code grant_book_on_first_join}，
     *       1.21.x 为 {@code grant_patchouli_book}</li>
     * </ul>
     * 因此按路径（而非完整 ID）过滤，同一 common 实现覆盖全部版本；
     * 跨版本 .maid 中出现对方版本路径时，目标服务端本就无此进度，会被安全跳过。
     */
    private static final Set<String> NON_TRANSFERABLE_PATHS = Set.of(
            "give_smart_slab",
            "grant_book_on_first_join",
            "grant_patchouli_book");

    /**
     * 属性绑定型成就：只有当女仆属性达标时才导出。
     * 非绑定型成就（事件触发类）一律导出，无法按女仆属性过滤。
     */
    private static boolean isStatMatched(ResourceLocation id, EntityMaid maid) {
        String path = id.getPath();
        // 满好感度成就：仅当女仆好感度 >= 384
        if (path.equals("favorability/favorability_increased_max")) {
            return maid.getFavorability() >= FAVORABILITY_MAX;
        }
        // 满血成就：仅当女仆 max health >= 100
        if (path.equals("challenge/maid_100_healthy")) {
            return maid.getMaxHealth() >= MAID_100_HEALTHY_THRESHOLD;
        }
        // 雷击成就：仅当女仆有渡劫标记
        if (path.equals("challenge/lightning_bolt")) {
            return maid.isStruckByLightning();
        }
        // 其余 TLM 成就为事件触发型，无法按属性过滤，一律包含
        return true;
    }

    /**
     * 收集源玩家（女仆主人）已完成的 TLM 成就，按女仆属性过滤后序列化为 NBT。
     *
     * @param owner 女仆主人（必须是在线 ServerPlayer）
     * @param maid  正在导出的女仆实体
     * @return 过滤后的成就 CompoundTag；无成就时返回空 CompoundTag
     */
    public static CompoundTag collectForMaid(ServerPlayer owner, EntityMaid maid) {
        CompoundTag result = new CompoundTag();
        MinecraftServer server = owner.getServer();
        if (server == null) {
            return result;
        }
        Iterable<Advancement> all = server.getAdvancements().getAllAdvancements();
        var playerAdv = owner.getAdvancements();
        for (Advancement holder : all) {
            ResourceLocation id = holder.getId();
            if (!id.getNamespace().equals(TLM_NS)) {
                continue;
            }
            // 开局赠送型进度不随女仆转移（其奖励受 TLM 配置开关守卫）
            if (NON_TRANSFERABLE_PATHS.contains(id.getPath())) {
                continue;
            }
            AdvancementProgress progress = playerAdv.getOrStartProgress(holder);
            if (!progress.isDone()) {
                continue;
            }
            // 属性绑定型成就按女仆属性过滤
            if (!isStatMatched(id, maid)) {
                continue;
            }
            // 序列化完成的 criteria 列表
            ListTag criteriaList = new ListTag();
            for (String criterion : progress.getCompletedCriteria()) {
                criteriaList.add(StringTag.valueOf(criterion));
            }
            CompoundTag entry = new CompoundTag();
            entry.put("criteria", criteriaList);
            result.put(id.toString(), entry);
        }
        Constants.LOG.debug("[maid_file_manager] 成就收集: maid={} 共 {} 项 TLM 成就",
                maid.getId(), result.getAllKeys().size());
        return result;
    }

    /**
     * 把导出的成就数据合并应用到目标玩家（原主人）。
     * 策略：目标玩家已完成的成就跳过；未完成的按 criterion 逐一 award。
     * 跨版本不认识的成就 ID 安全跳过。
     *
     * @param target          目标玩家（原主人，必须在线）
     * @param advancementsData 导出的成就 NBT（由 {@link #collectForMaid} 生成）
     */
    public static void applyToPlayer(ServerPlayer target, CompoundTag advancementsData) {
        if (advancementsData == null || advancementsData.isEmpty()) {
            return;
        }
        MinecraftServer server = target.getServer();
        if (server == null) {
            return;
        }
        var playerAdv = target.getAdvancements();
        int applied = 0;
        for (String idStr : advancementsData.getAllKeys()) {
            ResourceLocation id;
            try {
                id = ResourceLocation.tryParse(idStr);
            } catch (Exception e) {
                Constants.LOG.warn("[maid_file_manager] 成就 ID 解析失败: {}", idStr);
                continue;
            }
            if (id == null) {
                continue;
            }
            // 开局赠送型进度不补：award 会绕过 TLM 配置开关强发手册/魂符（旧 .maid 同样拦截）
            if (NON_TRANSFERABLE_PATHS.contains(id.getPath())) {
                continue;
            }
            // 跨版本安全跳过：目标服务端不认识的成就
            Advancement holder = server.getAdvancements().getAdvancement(id);
            if (holder == null) {
                continue;
            }
            AdvancementProgress targetProgress = playerAdv.getOrStartProgress(holder);
            if (targetProgress.isDone()) {
                continue; // 已完成，跳过
            }
            // 取出源数据中完成的 criteria 列表
            CompoundTag entry = advancementsData.getCompound(idStr);
            ListTag criteriaList = entry.getList("criteria", Tag.TAG_STRING);
            for (Tag t : criteriaList) {
                String criterion = ((StringTag) t).getAsString();
                var cp = targetProgress.getCriterion(criterion);
                if (cp != null && !cp.isDone()) {
                    try {
                        playerAdv.award(holder, criterion);
                        applied++;
                    } catch (Throwable ex) {
                        Constants.LOG.warn("[maid_file_manager] award 成就失败: {} criterion={}", idStr, criterion);
                    }
                }
            }
        }
        if (applied > 0) {
            Constants.LOG.info("[maid_file_manager] 成就合并完成: 玩家={} 补全 {} 项 criterion",
                    target.getName().getString(), applied);
        }
    }

    private AdvancementTransfer() {
    }
}
