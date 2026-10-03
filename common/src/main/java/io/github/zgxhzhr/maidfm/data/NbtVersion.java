package io.github.zgxhzhr.maidfm.data;

import io.github.zgxhzhr.maidfm.Constants;
import io.github.zgxhzhr.maidfm.platform.Services;

public final class NbtVersion {
    public static final int MC_1_20_1 = 1_20_01_00;
    public static final int MC_1_20_6 = 1_20_06_00;
    public static final int MC_1_21   = 1_21_00_00;
    public static final int MC_1_21_1 = 1_21_01_00;
    public static final int UNKNOWN = -1;

    /** 1.20.1 的 Mojang 官方 DataVersion：1.20 平台据此判断源文件是否为更新的组件格式 */
    public static final int DATA_VERSION_1_20_1 = 3465;

    private NbtVersion() {}

    public static int fromMcVersion(String mcVersion) {
        if (mcVersion == null || mcVersion.isEmpty()) return UNKNOWN;
        if (mcVersion.startsWith("1.21.1")) return MC_1_21_1;
        if (mcVersion.startsWith("1.21"))   return MC_1_21;
        if (mcVersion.startsWith("1.20.5") || mcVersion.startsWith("1.20.6")) return MC_1_20_6;
        if (mcVersion.startsWith("1.20"))   return MC_1_20_1;
        return UNKNOWN;
    }

    /**
     * 本模组版本标识对应的 Mojang 官方 DataVersion（供饰品属性跨版本升级使用）。
     *
     * <p>DataFixerUpper 需要"源版本"与"目标版本"两个 Mojang 官方数据版本号才能执行格式升级，
     * 而 .maid 文件里记录的是本模组自定义的 {@code data_version}/{@code source_mc_version}，
     * 因此需要此映射表换算。未知版本返回 -1，调用方应跳过 DFU 升级路径。
     *
     * <p>参考值（Mojang 官方 data_version）：1.20.1=3465，1.20.6=3837，1.21=3953，1.21.1=3955。
     */
    public static int mojangDataVersion(int nbtVersion) {
        switch (nbtVersion) {
            case MC_1_20_1:
                return 3465;
            case MC_1_20_6:
                return 3837;
            case MC_1_21:
                return 3953;
            case MC_1_21_1:
                return 3955;
            default:
                return -1;
        }
    }

    /**
     * 当前运行时版本。由平台层提供 MC 版本字符串（各加载器启动早期即可用），
     * 这样 common 层本文件在全部八个工程中保持同一份代码，无需按工程改常量。
     * 平台服务异常时返回 {@link #UNKNOWN}（迁移器会只做与版本无关的卫生清理）。
     */
    public static int currentRuntime() {
        try {
            return fromMcVersion(Services.PLATFORM.get().getMcVersion());
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 无法获取当前 MC 运行时版本: {}", t.toString());
            return UNKNOWN;
        }
    }
}
