package com.example.maid_file_manager.client;

import com.example.maid_file_manager.Constants;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;

/**
 * 模组按键绑定。
 *
 * <p>默认 Alt+U 唤出女仆文件管理界面，玩家可在原版 MC 控制设置中重新绑定。
 */
public final class MaidFileKeyMappings {
    /** 主分类（与原版按键一致），便于玩家在控制设置中查找 */
    public static final String CATEGORY = "key.categories." + Constants.MOD_ID;

    /** 打开女仆文件管理界面 */
    public static final KeyMapping OPEN_MANAGER = new KeyMapping(
            "key." + Constants.MOD_ID + ".open_manager",
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_U,
            CATEGORY
    );

    private MaidFileKeyMappings() {
    }
}
