package io.github.zgxhzhr.maidfm.data;

import net.minecraft.nbt.CompoundTag;

import java.util.List;

/**
 * 档案界面所需的一次性数据快照，由服务端组装后发往客户端。
 *
 * <p>只读数值（档案编号、所属势力、基础数值、饰品图标）由服务端从女仆实体实时读取；
 * 可编辑字段来自 {@link MaidProfile}。客户端编辑后仅回传档案部分，
 * 只读部分不回传，避免伪造。
 */
public final class MaidProfileView {
    private final int entityId;
    /** 档案编号：女仆实体 UUID */
    private final String maidUuid;
    /** 所属势力：主人玩家名（可能为空） */
    private final String ownerName;
    private final String modelId;
    private final String displayName;
    private final String customName;
    private final float health;
    private final float maxHealth;
    private final float attackDamage;
    private final int favorability;
    /** 是否已渡劫（TLM 雷击标记），在线档案与导入预览均展示 */
    private final boolean struckByLightning;
    private final MaidProfile profile;
    /** 饰品图标：每件饰品 ItemStack 的同版本 NBT，客户端用平台层反序列化后渲染 */
    private final List<CompoundTag> baubleItems;

    public MaidProfileView(int entityId, String maidUuid, String ownerName,
                           String modelId, String displayName, String customName,
                           float health, float maxHealth, float attackDamage, int favorability,
                           boolean struckByLightning,
                           MaidProfile profile, List<CompoundTag> baubleItems) {
        this.entityId = entityId;
        this.maidUuid = maidUuid;
        this.ownerName = ownerName;
        this.modelId = modelId;
        this.displayName = displayName;
        this.customName = customName;
        this.health = health;
        this.maxHealth = maxHealth;
        this.attackDamage = attackDamage;
        this.favorability = favorability;
        this.struckByLightning = struckByLightning;
        this.profile = profile;
        this.baubleItems = baubleItems;
    }

    public int entityId() {
        return entityId;
    }

    public String maidUuid() {
        return maidUuid;
    }

    public String ownerName() {
        return ownerName;
    }

    public String modelId() {
        return modelId;
    }

    public String displayName() {
        return displayName;
    }

    public String customName() {
        return customName;
    }

    public float health() {
        return health;
    }

    public float maxHealth() {
        return maxHealth;
    }

    public float attackDamage() {
        return attackDamage;
    }

    public int favorability() {
        return favorability;
    }

    public boolean struckByLightning() {
        return struckByLightning;
    }

    public MaidProfile profile() {
        return profile;
    }

    public List<CompoundTag> baubleItems() {
        return baubleItems;
    }
}
