package io.github.zgxhzhr.maidfm.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * 界面绘制的公共小工具：纯函数、无状态，供各 Screen 共用。
 *
 * <p>抽出来的原因：{@code drawCheckBox} 与 {@code CHECK_*} 常量原本在
 * {@code MaidFileManagerScreen} 与 {@code MaidBaubleImportScreen} 各存了一份完全相同的实现。
 */
public final class MaidScreenDraw {

    private MaidScreenDraw() {
    }

    /** 点是否落在矩形内（左下开区间，与 {@code fill} 的语义一致）。 */
    public static boolean inside(int x, int y, int w, int h, double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    // ============================ 复选框 ============================

    public static final int CHECK_SIZE = 9;
    private static final int CHECK_BORDER = 0xFFA89070;
    private static final int CHECK_INNER = 0xFF18120C;
    private static final int CHECK_MARK = 0xFFFFD700;

    /** 对勾图案（7×7 网格内的像素坐标）：左下短笔 + 向右上挑的长笔。 */
    private static final int[][] CHECK_PIXELS = {
            {5, 0},
            {4, 1}, {5, 1},
            {3, 2}, {4, 2},
            {1, 3}, {2, 3}, {3, 3}, {4, 3},
            {0, 4}, {1, 4}, {2, 4}, {3, 4},
            {1, 5}, {2, 5},
            {2, 6}
    };

    /**
     * 手绘复选框：MC 默认字体不含 U+2610/U+2611（☐/☑）字形，直接写字符只会得到空方框（豆腐块），
     * 因此所有勾选状态一律用像素绘制：1px 暗色边框 + 深色内底 + 选中时金色对勾。
     */
    public static void drawCheckBox(GuiGraphics graphics, int x, int y, boolean checked) {
        graphics.fill(x, y, x + CHECK_SIZE, y + CHECK_SIZE, CHECK_BORDER);
        graphics.fill(x + 1, y + 1, x + CHECK_SIZE - 1, y + CHECK_SIZE - 1, CHECK_INNER);
        if (checked) {
            for (int[] p : CHECK_PIXELS) {
                // 内底区域为 7×7（x+1..x+7），像素逐格填充
                graphics.fill(x + 1 + p[0], y + 1 + p[1], x + 2 + p[0], y + 2 + p[1], CHECK_MARK);
            }
        }
    }

    /** 在按钮左侧绘制复选框（按钮不可见时不画）。 */
    public static void drawButtonCheckBox(GuiGraphics graphics, Button button, boolean checked) {
        if (button != null && button.visible) {
            drawCheckBox(graphics, button.getX() + 5,
                    button.getY() + (button.getHeight() - CHECK_SIZE) / 2, checked);
        }
    }

    // ============================ 文字 ============================

    /**
     * {@code GuiGraphics.drawCenteredString} 默认 dropShadow=true，阴影会把彩色字糊成一团。
     * 这里手动计算居中 + {@code drawString(Component, ..., shadow=false)} 得到干净的文字。
     */
    public static void drawCenteredStringNoShadow(GuiGraphics g, net.minecraft.client.gui.Font font,
                                                  Component text, int centerX, int y, int color) {
        int w = font.width(text);
        g.drawString(font, text, centerX - w / 2, y, color, false);
    }
}
