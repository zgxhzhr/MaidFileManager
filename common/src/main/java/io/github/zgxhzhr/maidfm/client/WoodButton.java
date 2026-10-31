package io.github.zgxhzhr.maidfm.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * 木色按钮：深棕底 + 木色描边 + 白字（无阴影），替代原版灰色按钮以贴合木色面板。
 *
 * <p>从 {@link MaidBaubleImportScreen} 移出：它是个通用的「一句话 + 一个动作」按钮，
 * 唯一的外部需求是字体（从 {@link Minecraft} 取），没有任何宿主状态依赖。
 */
class WoodButton extends AbstractButton {

    private final Runnable action;

    WoodButton(int x, int y, int width, int height, Component message, Runnable action) {
        super(x, y, width, height, message);
        this.action = action;
    }

    @Override
    public void onPress() {
        this.action.run();
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean hovered = isHoveredOrFocused();
        graphics.fill(getX(), getY(), getX() + width, getY() + height,
                hovered ? MaidBaubleImportScreen.BUTTON_BG_HOVER : MaidBaubleImportScreen.BUTTON_BG);
        graphics.renderOutline(getX(), getY(), width, height, MaidBaubleImportScreen.PANEL_BORDER);
        net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
        int tx = getX() + (width - font.width(getMessage())) / 2;
        int ty = getY() + (height - 8) / 2;
        graphics.drawString(font, getMessage(), tx, ty, MaidBaubleImportScreen.TEXT_COLOR, false);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
