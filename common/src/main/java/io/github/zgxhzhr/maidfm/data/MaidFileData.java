package io.github.zgxhzhr.maidfm.data;

import io.github.zgxhzhr.maidfm.Constants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.UUID;

/**
 * .maid 文件的数据结构。
 *
 * <p>文件格式：
 * <ul>
 *   <li>{@code format_version}：整数，文件格式版本号，用于跨版本兼容时迁移</li>
 *   <li>{@code exported_at}：长整数，导出时间戳（毫秒）</li>
 *   <li>{@code source_mc_version}：字符串，导出时的 Minecraft 版本</li>
 *   <li>{@code source_tlm_version}：字符串，导出时的车万女仆模组版本</li>
 *   <li>{@code mod_id}：字符串，导出工具的 modId（maid_file_manager）</li>
 *   <li>{@code data}：复合标签，包含车万女仆原始 NBT 数据（与 EntityMaid.addAdditionalSaveData 一致）</li>
 *   <li>{@code owner_uuid}：字符串（可空），主人 UUID，便于跨版本匹配</li>
 *   <li>{@code owner_name}：字符串（可空），主人名称，UUID 找不到时按名字匹配</li>
 *   <li>{@code tamed}：字节，是否已驯服</li>
 * </ul>
 *
 * <p>这样设计的好处：
 * <ul>
 *   <li>同版本导入：直接使用 {@code data} 字段调用 {@code entity.load}，导入的女仆是完整 TLM 女仆，
 *       卸载本模组后不影响已导入的女仆</li>
 *   <li>跨版本导入：通过离散字段（model_id 等）做数据迁移，未来 1.21 兼容时只需新增迁移逻辑</li>
 * </ul>
 */
public class MaidFileData {
    private int formatVersion = Constants.MAID_FILE_FORMAT_VERSION;
    private long exportedAt;
    private String sourceMcVersion = "";
    private String sourceTlmVersion = "";
    private String modId = Constants.MOD_ID;

    /** 主人 UUID 字符串，可空（未驯服） */
    private String ownerUuid;
    /**
     * 源女仆实体 UUID 字符串，可空（极早期文件可能无此字段）。
     * 导入生成的新实体一律使用新 UUID，本字段仅用于导入前查重，防止同一位女仆被重复导入。
     */
    private String sourceMaidUuid;
    /** 主人名称，可空 */
    private String ownerName;
    /** 是否已驯服 */
    private boolean tamed;

    /** 车万女仆原始 NBT 数据（EntityMaid.addAdditionalSaveData 写入的字段） */
    private CompoundTag data;

    /** NBT 数据版本（对应 NbtVersion 常量），用于跨版本迁移 */
    private int dataVersion;

    /** 模型 ID，便于文件命名与跨版本展示 */
    private String modelId;
    /** 模型显示名（中文名），用于导出文件命名 */
    private String displayName;
    /** 女仆自定义名（命名牌所取），可空；非空时拼到文件名模型名前 */
    private String customName;
    /** 源玩家 TLM 成就数据（仅命名空间 touhou_little_maid 的已完成成就），可空 */
    private CompoundTag advancements;
    /** 药水效果 NBT（从 ActiveEffects 提取，始终序列化，不受配置影响），可空 */
    private CompoundTag effects;
    /**
     * 附属模组通过 {@link io.github.zgxhzhr.maidfm.spi.MaidMigrationProvider}
     * 导出的扩展数据，key 为 provider 的 Identifier 字符串，
     * value 为该 provider 的 export 返回值。可空（旧版文件无此字段）。
     */
    private CompoundTag extras;
    /**
     * v7 新增：女仆档案（照片/职业/个人资料/偏好/背景故事快照等）。
     * 旧 v6 文件无此字段，读取端容错为 null。
     */
    private MaidProfile profile;

    public MaidFileData() {
    }

    public CompoundTag writeToNbt() {
        CompoundTag root = new CompoundTag();
        root.putInt("format_version", formatVersion);
        root.putLong("exported_at", exportedAt);
        root.putString("source_mc_version", sourceMcVersion);
        root.putString("source_tlm_version", sourceTlmVersion);
        root.putString("mod_id", modId);
        root.putInt("data_version", dataVersion);
        root.putBoolean("tamed", tamed);
        if (ownerUuid != null) {
            root.putString("owner_uuid", ownerUuid);
        }
        if (sourceMaidUuid != null) {
            root.putString("source_maid_uuid", sourceMaidUuid);
        }
        if (ownerName != null) {
            root.putString("owner_name", ownerName);
        }
        if (data != null) {
            root.put("data", data);
        }
        if (modelId != null) {
            root.putString("model_id", modelId);
        }
        if (displayName != null) {
            root.putString("display_name", displayName);
        }
        if (customName != null) {
            root.putString("custom_name", customName);
        }
        if (advancements != null) {
            root.put("advancements", advancements);
        }
        if (effects != null) {
            root.put("effects", effects);
        }
        if (extras != null) {
            root.put("extras", extras);
        }
        if (profile != null) {
            root.put("profile", profile.writeToNbt());
        }
        return root;
    }

    public static MaidFileData readFromNbt(CompoundTag root) {
        MaidFileData data = new MaidFileData();
        data.formatVersion = root.contains("format_version")
                ? root.getIntOr("format_version", 0)
                : Constants.MAID_FILE_FORMAT_VERSION;
        if (data.formatVersion > Constants.MAID_FILE_FORMAT_VERSION) {
            // 来自更新版本的文件：按当前版本尽力解析，但必须明示，避免未来 v8 被当 v7 静默读坏数据
            Constants.LOG.warn("[maid_file_manager] .maid 的 format_version={} 高于本模组支持的 {}，"
                            + "将按当前版本尽力解析，部分字段可能被忽略",
                    data.formatVersion, Constants.MAID_FILE_FORMAT_VERSION);
        }
        data.exportedAt = root.getLongOr("exported_at", 0L);
        data.sourceMcVersion = root.getStringOr("source_mc_version", "");
        data.sourceTlmVersion = root.getStringOr("source_tlm_version", "");
        data.modId = root.contains("mod_id")
                ? root.getStringOr("mod_id", "")
                : Constants.MOD_ID;
        data.dataVersion = root.contains("data_version")
                ? root.getIntOr("data_version", 0)
                : NbtVersion.fromMcVersion(root.getStringOr("source_mc_version", ""));
        data.tamed = root.getBooleanOr("tamed", false);
        if (root.contains("owner_uuid")) {
            data.ownerUuid = root.getStringOr("owner_uuid", "");
            try {
                UUID.fromString(data.ownerUuid);
            } catch (IllegalArgumentException e) {
                data.ownerUuid = null;
            }
        }
        // 源女仆实体 UUID（v6 起写入；旧文件无此字段，导入端会从实体根 NBT 的 UUID 键兜底解析）
        if (root.contains("source_maid_uuid")) {
            data.sourceMaidUuid = root.getStringOr("source_maid_uuid", "");
            try {
                UUID.fromString(data.sourceMaidUuid);
            } catch (IllegalArgumentException e) {
                data.sourceMaidUuid = null;
            }
        }
        if (root.contains("owner_name")) {
            data.ownerName = root.getStringOr("owner_name", "");
        }
        if (root.contains("data")) {
            data.data = root.getCompoundOrEmpty("data");
        }
        if (root.contains("model_id")) {
            data.modelId = root.getStringOr("model_id", "");
        }
        if (root.contains("display_name")) {
            data.displayName = root.getStringOr("display_name", "");
        }
        if (root.contains("custom_name")) {
            data.customName = root.getStringOr("custom_name", "");
        }
        // v3 新增：成就数据（旧 v2 文件无此字段，容错为 null）
        if (root.contains("advancements")) {
            data.advancements = root.getCompoundOrEmpty("advancements");
        }
        // v4 药水效果字段（旧 v3 文件无此字段，容错处理）
        if (root.contains("effects")) {
            data.effects = root.getCompoundOrEmpty("effects");
        }
        // v5 新增：附属模组扩展数据（旧 v4 文件无此字段，容错为 null）
        if (root.contains("extras")) {
            data.extras = root.getCompoundOrEmpty("extras");
        }
        // v7 新增：女仆档案（旧 v6 文件无此字段，容错为 null；子键缺省同样容错）
        if (root.contains("profile")) {
            data.profile = MaidProfile.readFromNbt(root.getCompoundOrEmpty("profile"));
        }
        // v6 短暂存在过的 source_health_base/source_attack_base 字段已废弃：
        // 属性 base 导入时无条件回到 TLM 白板值，旧文件含这两个键时直接忽略。
        // 旧 v4 文件可能含 spell_maid 键，同样忽略（向后兼容）。
        // 注意：**不能**用根键 DataVersion（Minecraft 世界数据版本，如 3465）兜底 data_version。
        // dataVersion 是本模组**内部格式版本**（当前 7），两者量纲完全不同：
        // 把 3465 写进去会让 NbtMigration 判定为「远高于当前版本」，走错升级/降级分支。
        // 缺 data_version 时保持默认值（当前格式版本）即可 —— 旧文件本就按最新格式解析。
        if (!root.contains("data_version") && root.contains("DataVersion")) {
            Constants.LOG.debug("[maid_file_manager] .maid 缺 data_version，忽略根键 DataVersion={}（与内部格式版本无关）",
                    root.getIntOr("DataVersion", 0));
        }
        // model_id 缺失时从实体 NBT 回退（1.21+ 为 model_id，1.20.x 为 ModelId），
        // 仅用于展示与命名兜底；导入实体模型仍由 data 内的模型键经 maid.load 恢复
        if (!root.contains("model_id") && data.data != null) {
            CompoundTag entityTag = data.data;
            String mid = entityTag.contains("model_id")
                    ? entityTag.getStringOr("model_id", "")
                    : entityTag.getStringOr("ModelId", "");
            if (!mid.isEmpty()) {
                data.modelId = mid;
            }
        }
        return data;
    }

    // ---- getters / setters ----

    public int getFormatVersion() {
        return formatVersion;
    }

    public void setFormatVersion(int formatVersion) {
        this.formatVersion = formatVersion;
    }

    public long getExportedAt() {
        return exportedAt;
    }

    public void setExportedAt(long exportedAt) {
        this.exportedAt = exportedAt;
    }

    public String getSourceMcVersion() {
        return sourceMcVersion;
    }

    public void setSourceMcVersion(String sourceMcVersion) {
        this.sourceMcVersion = sourceMcVersion;
    }

    public String getSourceTlmVersion() {
        return sourceTlmVersion;
    }

    public void setSourceTlmVersion(String sourceTlmVersion) {
        this.sourceTlmVersion = sourceTlmVersion;
    }

    public String getModId() {
        return modId;
    }

    public void setModId(String modId) {
        this.modId = modId;
    }

    public String getOwnerUuid() {
        return ownerUuid;
    }

    public void setOwnerUuid(String ownerUuid) {
        this.ownerUuid = ownerUuid;
    }

    public String getSourceMaidUuid() {
        return sourceMaidUuid;
    }

    public void setSourceMaidUuid(String sourceMaidUuid) {
        this.sourceMaidUuid = sourceMaidUuid;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public boolean isTamed() {
        return tamed;
    }

    public void setTamed(boolean tamed) {
        this.tamed = tamed;
    }

    public CompoundTag getData() {
        return data;
    }

    public void setData(CompoundTag data) {
        this.data = data;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getCustomName() {
        return customName;
    }

    public void setCustomName(String customName) {
        this.customName = customName;
    }

    public int getDataVersion() {
        return dataVersion;
    }

    public void setDataVersion(int dataVersion) {
        this.dataVersion = dataVersion;
    }

    public CompoundTag getAdvancements() {
        return advancements;
    }

    public void setAdvancements(CompoundTag advancements) {
        this.advancements = advancements;
    }

    public CompoundTag getEffects() {
        return effects;
    }

    public void setEffects(CompoundTag effects) {
        this.effects = effects;
    }

    public CompoundTag getExtras() {
        return extras;
    }

    public void setExtras(CompoundTag extras) {
        this.extras = extras;
    }

    public MaidProfile getProfile() {
        return profile;
    }

    public void setProfile(MaidProfile profile) {
        this.profile = profile;
    }

    /**
     * 这个 .maid 里的女仆是否使用 YSM（Yes Steve Model）换模。
     *
     * <p>YSM 信息随实体 NBT 一起保存（{@code IsYsmModel} / {@code YsmModelName} /
     * {@code YsmModelId}），不单独占 {@code .maid} 顶层字段——顶层加字段等于改线格式，
     * 需要 bump 协议。所以这里直接读实体 NBT。
     *
     * <p>调用方用它决定「展示名该信谁」：YSM 下 TLM 的 modelId 只是底层 geckolib ID，
     * 客户端反查会得到换模前的旧名，只能信服务端算好的 displayName。
     */
    public boolean isYsmModel() {
        if (data == null) {
            return false;
        }
        try {
            return data.getBooleanOr("IsYsmModel", false)
                    || !data.getStringOr("YsmModelName", "").isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }
}
