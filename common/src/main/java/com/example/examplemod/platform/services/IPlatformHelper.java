package com.example.examplemod.platform.services;

import java.nio.file.Path;

public interface IPlatformHelper {

    /**
     * Gets the name of the current platform
     *
     * @return The name of the current platform.
     */
    String getPlatformName();

    /**
     * Checks if a mod with the given id is loaded.
     *
     * @param modId The mod to check if it is loaded.
     * @return True if the mod is loaded, false otherwise.
     */
    boolean isModLoaded(String modId);

    /**
     * Check if the game is currently in a development environment.
     *
     * @return True if in a development environment, false otherwise.
     */
    boolean isDevelopmentEnvironment();

    /**
     * Gets the name of the environment type as a string.
     *
     * @return The name of the environment type.
     */
    default String getEnvironmentName() {
        return isDevelopmentEnvironment() ? "development" : "production";
    }

    /**
     * 获取指定 modId 的版本字符串，未加载返回空字符串。
     *
     * @param modId 模组 ID
     * @return 版本字符串
     */
    String getModVersion(String modId);

    /**
     * 获取当前 Minecraft 版本字符串。
     *
     * @return MC 版本号
     */
    String getMcVersion();

    /**
     * 获取 Minecraft 运行根目录（即 .minecraft 目录），maid_files 文件夹应放在此处，
     * 方便用户在切换整合包时直接拷贝。
     *
     * @return 游戏根目录的绝对路径
     */
    Path getGameDir();
}
