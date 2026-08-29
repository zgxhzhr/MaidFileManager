package com.example.maid_file_manager.data;

/**
 * Minecraft/NBT 数据版本常量。
 * <p>
 * 用于 .maid 文件跨版本迁移时判断源 NBT 的格式，
 * 并决定是否调用 {@link NbtMigration} 升级到目标版本。
 */
public final class NbtVersion {
    /** MC 1.20.1 / Forge-47.x / 旧物品 NBT 格式（tag 子标签） */
    public static final int MC_1_20_1 = 1_20_01_00;
    /** MC 1.20.5 - 1.20.6：引入 Data Components（物品格式变更） */
    public static final int MC_1_20_6 = 1_20_06_00;
    /** MC 1.21.x：正式 Data Components + RegistryAccess 需要 */
    public static final int MC_1_21   = 1_21_00_00;
    /** MC 1.21.1：基本同 1.21，但部分注册表/资源加载变更 */
    public static final int MC_1_21_1 = 1_21_01_00;
    /** 未知版本 */
    public static final int UNKNOWN = -1;

    private NbtVersion() {}

    /** 通过 sourceMcVersion 字符串推断数据版本号 */
    public static int fromMcVersion(String mcVersion) {
        if (mcVersion == null || mcVersion.isEmpty()) return UNKNOWN;
        if (mcVersion.startsWith("1.21.1")) return MC_1_21_1;
        if (mcVersion.startsWith("1.21"))   return MC_1_21;
        if (mcVersion.startsWith("1.20.5") || mcVersion.startsWith("1.20.6")) return MC_1_20_6;
        if (mcVersion.startsWith("1.20"))   return MC_1_20_1;
        return UNKNOWN;
    }

    /** 返回当前运行时的数据版本 */
    public static int currentRuntime() {
        return MC_1_20_1;
    }
}
