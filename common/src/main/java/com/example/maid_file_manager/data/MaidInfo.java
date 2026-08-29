package com.example.maid_file_manager.data;

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

    public MaidInfo(int entityId, String modelId, String displayName, int favorability,
                    float health, float maxHealth, boolean tamed,
                    UUID ownerUuid, String ownerName, String customName) {
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
}
