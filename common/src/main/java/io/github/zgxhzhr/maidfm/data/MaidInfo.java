package io.github.zgxhzhr.maidfm.data;

import java.util.UUID;

/**
 * 列表中的女仆信息，用于客户端 GUI 展示与选择。
 * 通过网络包从服务端发送到客户端。
 */
public final class MaidInfo {
    private final int entityId;
    private final String modelId;
    /** 模型显示名（中文名）；服务端通过 TLM 的 ServerCustomPackLoader 查询得到 */
    private final String displayName;
    private final int favorability;
    private final float health;
    private final float maxHealth;
    private final boolean tamed;
    private final UUID ownerUuid;
    private final String ownerName;
    private final String customName;
    /**
     * 是否由 YSM（Yes Steve Model）换模。
     *
     * <p>YSM 女仆的 {@link #modelId} 是 YSM 的模型路径 ID（形如 {@code ysm:Wine Fox/02 New Year}），
     * 无法通过 TLM 模型表解析出名字；真正的展示名在 {@link #displayName} 里。
     * 所以这类女仆显示时必须优先用 displayName，不能走 displayModelId(modelId)。
     */
    private final boolean ysmModel;

    public MaidInfo(int entityId, String modelId, String displayName, int favorability,
                    float health, float maxHealth, boolean tamed,
                    UUID ownerUuid, String ownerName, String customName,
                    boolean ysmModel) {
        this.entityId = entityId;
        this.modelId = modelId;
        this.displayName = displayName == null ? modelId : displayName;
        this.favorability = favorability;
        this.health = health;
        this.maxHealth = maxHealth;
        this.tamed = tamed;
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.customName = customName;
        this.ysmModel = ysmModel;
    }

    public int entityId() {
        return entityId;
    }

    public String modelId() {
        return modelId;
    }

    public String displayName() {
        return displayName;
    }

    public int favorability() {
        return favorability;
    }

    public float health() {
        return health;
    }

    public float maxHealth() {
        return maxHealth;
    }

    public boolean tamed() {
        return tamed;
    }

    public UUID ownerUuid() {
        return ownerUuid;
    }

    public String ownerName() {
        return ownerName;
    }

    public String customName() {
        return customName;
    }

    /** 是否 YSM 换模；为 true 时 displayName 才是权威名字 */
    public boolean ysmModel() {
        return ysmModel;
    }

}
