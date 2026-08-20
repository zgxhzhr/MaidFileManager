package com.example.examplemod.data;

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

    /** 当前运行时版本：1.21 */
    public static int currentRuntime() {
        return MC_1_21;
    }
}
