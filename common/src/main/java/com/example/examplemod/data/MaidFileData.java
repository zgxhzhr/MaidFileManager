package com.example.examplemod.data;

import com.example.examplemod.Constants;
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
        return root;
    }

    public static MaidFileData readFromNbt(CompoundTag root) {
        MaidFileData data = new MaidFileData();
        data.formatVersion = root.contains("format_version", Tag.TAG_INT)
                ? root.getInt("format_version")
                : Constants.MAID_FILE_FORMAT_VERSION;
        data.exportedAt = root.getLong("exported_at");
        data.sourceMcVersion = root.getString("source_mc_version");
        data.sourceTlmVersion = root.getString("source_tlm_version");
        data.modId = root.contains("mod_id", Tag.TAG_STRING)
                ? root.getString("mod_id")
                : Constants.MOD_ID;
        data.dataVersion = root.contains("data_version", Tag.TAG_INT)
                ? root.getInt("data_version")
                : NbtVersion.fromMcVersion(root.getString("source_mc_version"));
        data.tamed = root.getBoolean("tamed");
        if (root.contains("owner_uuid", Tag.TAG_STRING)) {
            data.ownerUuid = root.getString("owner_uuid");
            try {
                UUID.fromString(data.ownerUuid);
            } catch (IllegalArgumentException e) {
                data.ownerUuid = null;
            }
        }
        if (root.contains("owner_name", Tag.TAG_STRING)) {
            data.ownerName = root.getString("owner_name");
        }
        if (root.contains("data", Tag.TAG_COMPOUND)) {
            data.data = root.getCompound("data");
        }
        if (root.contains("model_id", Tag.TAG_STRING)) {
            data.modelId = root.getString("model_id");
        }
        if (root.contains("display_name", Tag.TAG_STRING)) {
            data.displayName = root.getString("display_name");
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

    public int getDataVersion() {
        return dataVersion;
    }

    public void setDataVersion(int dataVersion) {
        this.dataVersion = dataVersion;
    }
}
