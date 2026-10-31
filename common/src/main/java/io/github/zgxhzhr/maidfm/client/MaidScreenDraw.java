package io.github.zgxhzhr.maidfm.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * 界面绘制的公共小工具：纯函数、无状态，供各 Screen 共用。
 *
 * <p>抽出的原因：drawCheckBox / drawButtonCheckBox / drawCenteredStringNoShadow / inside
 * 与 CHECK_* 常量原本在 MaidFileManagerScreen 与 MaidBaubleImportScreen 各存一份相同实现。
 *
 * <p>26.1.2 的渲染 API 是 GuiGraphicsExtractor（原 GuiGraphics.drawString 本版本叫 text），
 * 与 1.20.x / 1.21.x 不同——本文件是这一端的适配版。
 */
public final class MaidScreenDraw {

    private MaidScreenDraw() {
    }

    /** 点是否落在矩形内（左下开区间，与 fill 的语义一致）。 */
    public static boolean inside(int x, int y, int w, int h, double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    // ============================ 复选框 ============================

    public static final int CHECK_SIZE = 9;
    private static final int CHECK_BORDER = 0xFFA89070;
    private static final int CHECK_INNER = 0xFF18120C;
    private static final int CHECK_MARK = 0xFFFFD700;

    /** 对勾图案（7x7 网格内的像素坐标）。 */
    private static final int[][] CHECK_PIXELS = {
            {5, 0},
            {4, 1}, {5, 1},
            {3, 2}, {4, 2},
            {1, 3}, {2, 3}, {3, 3}, {4, 3},
            {0, 4}, {1, 4}, {2, 4}, {3, 4},
            {1, 5}, {2, 5},
            {2, 6}
    };

    /** 手绘复选框：MC 默认字体不含 ☐/☑ 字形，勾选状态一律像素绘制。 */
    public static void drawCheckBox(GuiGraphicsExtractor graphics, int x, int y, boolean checked) {
        graphics.fill(x, y, x + CHECK_SIZE, y + CHECK_SIZE, CHECK_BORDER);
        graphics.fill(x + 1, y + 1, x + CHECK_SIZE - 1, y + CHECK_SIZE - 1, CHECK_INNER);
        if (checked) {
            for (int[] p : CHECK_PIXELS) {
                graphics.fill(x + 1 + p[0], y + 1 + p[1], x + 2 + p[0], y + 2 + p[1], CHECK_MARK);
            }
        }
    }

    /** 在按钮左侧绘制复选框（按钮不可见时不画）。 */
    public static void drawButtonCheckBox(GuiGraphicsExtractor graphics, Button button, boolean checked) {
        if (button != null && button.visible) {
            drawCheckBox(graphics, button.getX() + 5,
                    button.getY() + (button.getHeight() - CHECK_SIZE) / 2, checked);
        }
    }

    // ============================ 文字 ============================

    /** 手动居中 + 无阴影：drawCenteredString 默认 dropShadow 会把彩色字糊成一团。 */
    public static void drawCenteredStringNoShadow(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font,
                                                  Component text, int centerX, int y, int color) {
        int w = font.width(text);
        g.text(font, text, centerX - w / 2, y, color, false);
    }
}
