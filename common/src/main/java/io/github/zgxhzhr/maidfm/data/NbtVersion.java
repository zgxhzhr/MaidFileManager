package io.github.zgxhzhr.maidfm.data;

import io.github.zgxhzhr.maidfm.Constants;
import io.github.zgxhzhr.maidfm.platform.Services;

public final class NbtVersion {
    public static final int MC_1_20_1 = 1_20_01_00;
    public static final int MC_1_20_6 = 1_20_06_00;
    public static final int MC_1_21   = 1_21_00_00;
    public static final int MC_1_21_1 = 1_21_01_00;
    public static final int UNKNOWN = -1;

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
