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

import java.util.Collection;
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
        Collection<Advancement> all = server.getAdvancements().getAllAdvancements();
        var playerAdv = owner.getAdvancements();
        for (Advancement adv : all) {
            ResourceLocation id = adv.getId();
            if (!id.getNamespace().equals(TLM_NS)) {
                continue;
            }
            AdvancementProgress progress = playerAdv.getOrStartProgress(adv);
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
            // 跨版本安全跳过：目标服务端不认识的成就
            Advancement adv = server.getAdvancements().getAdvancement(id);
            if (adv == null) {
                continue;
            }
            AdvancementProgress targetProgress = playerAdv.getOrStartProgress(adv);
            if (targetProgress.isDone()) {
                continue; // 已完成，跳过
            }
            // 取出源数据中完成的 criteria 列表
            CompoundTag entry = advancementsData.getCompound(idStr);
            ListTag criteriaList = entry.getList("criteria", Tag.TAG_STRING);
            for (Tag t : criteriaList) {
                String criterion = ((StringTag) t).getAsString();
                if (!targetProgress.getCriterion(criterion).isDone()) {
                    try {
                        playerAdv.award(adv, criterion);
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
