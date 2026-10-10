package io.github.zgxhzhr.maidfm.service;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import io.github.zgxhzhr.maidfm.Constants;
import io.github.zgxhzhr.maidfm.data.MaidProfile;
import io.github.zgxhzhr.maidfm.data.MaidProfileView;
import io.github.zgxhzhr.maidfm.platform.Services;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 女仆档案业务逻辑（仅服务端调用）。
 *
 * <p>档案的可编辑字段存放于女仆实体的自定义持久化标签中（经
 * {@link io.github.zgxhzhr.maidfm.platform.services.IPlatformHelper} 下沉到各加载器）；
 * 背景故事不单独存 NBT，而是<b>双向读写</b> TLM 的 AI 人设
 * {@code MaidAIChat.CustomSetting}，保证与 TLM 内存态同步——直接改 NBT 会造成内存与存档不一致。
 *
 * <p>只读数值（档案编号/所属势力/基础数值/饰品图标）在组装视图时实时读取，不落盘。
 */
public final class MaidProfileService {
    /** 饰品图标最多随包发送的件数（防止饰品栏被恶意扩张后打爆数据包） */
    private static final int MAX_BAUBLE_ICONS = 64;

    private MaidProfileService() {
    }

    // ---------- 读 ----------

    /**
     * 从女仆实体读取档案。档案 NBT 不存在时返回空档案；背景故事快照始终以
     * TLM AI 人设的当前值为准（无实体来源时保持原快照）。
     */
    public static MaidProfile readFromMaid(EntityMaid maid) {
        CompoundTag tag = safeReadTag(maid);
        MaidProfile profile = MaidProfile.readFromNbt(tag);
        if (profile == null) {
            profile = new MaidProfile();
        }
        String story = readStory(maid);
        if (story != null) {
            profile.setStorySnapshot(story);
        }
        return profile;
    }

    /** 读取 TLM AI 人设（背景故事）；读取失败返回 null（保持原值不覆盖） */
    public static String readStory(EntityMaid maid) {
        try {
            return maid.getAiChatManager().customSetting;
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 读取女仆 AI 人设失败: {}", t.toString());
            return null;
        }
    }

    // ---------- 写 ----------

    /**
     * 将档案写回女仆实体：可编辑字段落到实体持久化标签，背景故事同步写入 TLM AI 人设。
     *
     * @param syncStory 是否把档案中的背景故事同步到 TLM AI 人设。
     *                  档案界面保存时传 true；导入时若档案只是快照冗余，传 false 以免覆盖
     *                  {@code data} 内已恢复的权威 AI 数据。
     */
    public static void writeToMaid(EntityMaid maid, MaidProfile profile, boolean syncStory) {
        if (profile == null) {
            return;
        }
        if (syncStory) {
            writeStory(maid, profile.getStorySnapshot());
        }
        try {
            Services.PLATFORM.get().writeMaidProfile(maid, profile.writeToNbt());
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 写入女仆档案失败: {}", t.toString());
        }
    }

    /** 写入 TLM AI 人设（背景故事）；直接改 public 字段，与 TLM 官方保存路径等价、内存态同步 */
    public static void writeStory(EntityMaid maid, String story) {
        if (story == null) {
            return;
        }
        try {
            maid.getAiChatManager().customSetting = story;
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 写入女仆 AI 人设失败: {}", t.toString());
        }
    }

    // ---------- 视图组装 ----------

    /**
     * 组装档案界面所需的数据快照。
     *
     * <p><b>仅限女仆的主人本人调用</b>：女仆为 null、请求者为 null 或请求者并非主人时一律返回 null，
     * 调用方据此回执「无权限」，不向非归属玩家泄露任何档案内容。
     * 普通模式下列表本就只返回请求者自己的女仆，服务端这一层是兜底防线。
     */
    public static MaidProfileView buildView(Player requester, EntityMaid maid) {
        if (maid == null || requester == null || !maid.isOwnedBy(requester)) {
            return null;
        }
        String ownerName = null;
        net.minecraft.world.entity.LivingEntity owner = maid.getOwner();
        if (owner != null) {
            ownerName = owner.getName().getString();
        }
        // YSM 换模时展示名与模型 ID 一律改为 YSM 模型信息，与导出列表保持一致
        String modelId = maid.getModelId();
        String displayName = MaidTransferService.getDisplayName(modelId);
        String ysmName = MaidTransferService.getYsmDisplayName(maid);
        if (ysmName != null) {
            displayName = ysmName;
            String ysmId = MaidTransferService.getYsmDisplayId(maid);
            if (ysmId != null) {
                modelId = ysmId;
            }
        }
        String customName = maid.hasCustomName() ? maid.getCustomName().getString() : null;
        float maxHealth = (float) maid.getMaxHealth();
        float attackDamage = 0f;
        try {
            attackDamage = (float) maid.getAttributeValue(Attributes.ATTACK_DAMAGE);
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 读取女仆攻击力失败: {}", t.toString());
        }
        return new MaidProfileView(
                maid.getId(),
                maid.getUUID().toString(),
                ownerName,
                modelId,
                displayName,
                customName,
                maid.getHealth(),
                maxHealth,
                attackDamage,
                maid.getFavorability(),
                maid.isStruckByLightning(),
                readFromMaid(maid),
                collectBaubleIcons(maid));
    }

    /**
     * 采集饰品图标：取饰品栏中非空物品，序列化为同版本 NBT 供客户端渲染。
     * 读取失败或饰品栏不可用时返回空列表（不影响档案其他字段展示）。
     */
    private static List<CompoundTag> collectBaubleIcons(EntityMaid maid) {
        List<CompoundTag> result = new ArrayList<>();
        try {
            int slots = Services.PLATFORM.get().baubleGetSlots(maid);
            if (slots <= 0) {
                return result;
            }
            RegistryAccess registries = maid.level().registryAccess();
            int limit = Math.min(slots, MAX_BAUBLE_ICONS);
            for (int i = 0; i < limit; i++) {
                ItemStack stack = Services.PLATFORM.get().baubleGetStack(maid, i);
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                CompoundTag tag = Services.PLATFORM.get().serializeItemStack(registries, stack);
                if (tag != null && !tag.isEmpty()) {
                    result.add(tag);
                }
            }
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 采集女仆饰品图标失败: {}", t.toString());
        }
        return result;
    }

    private static CompoundTag safeReadTag(EntityMaid maid) {
        try {
            return Services.PLATFORM.get().readMaidProfile(maid);
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 读取女仆档案 NBT 失败: {}", t.toString());
            return null;
        }
    }
}
