package io.github.zgxhzhr.maidfm.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * 丢弃饰品属性滑动开关：点击切换，滑块带平滑动画。
 *
 * <p>从 {@link MaidBaubleImportScreen} 移出。状态不再直连宿主字段，改成「初始值 + 变更回调」：
 * 这个开关只是个纯 UI 控件，宿主拿到回调自己决定往哪存（本屏是 stripAttributes，
 * 点「完成」时统一提交服务端）。这样它不依赖任何宿主类型，将来别处也能用。
 *
 * <p>26.1.2 的按钮基类改成了 {@code extractContents}（原 renderWidget），
 * 点击回调也带 {@code InputWithModifiers} 参数，与其它端不同。
 */
class StripToggle extends AbstractButton {

    private boolean state;
    /** 滑块动画进度：0=左（不丢弃=保留），1=右（丢弃） */
    private float slide;
    private final Consumer<Boolean> onChange;

    StripToggle(int x, int y, boolean initial, Consumer<Boolean> onChange) {
        super(x, y, MaidBaubleImportScreen.SWITCH_W, MaidBaubleImportScreen.SWITCH_H, Component.empty());
        this.state = initial;
        this.slide = initial ? 1.0F : 0.0F;
        this.onChange = onChange;
    }

    @Override
    public void onPress(net.minecraft.client.input.InputWithModifiers input) {
        this.state = !this.state;
        this.onChange.accept(this.state);
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        float target = this.state ? 1.0F : 0.0F;
        this.slide += (target - this.slide) * 0.35F;
        if (Math.abs(target - this.slide) < 0.02F) {
            this.slide = target;
        }
        int track = this.active
                ? MaidBaubleImportScreen.blendColor(MaidBaubleImportScreen.TRACK_OFF,
                        MaidBaubleImportScreen.TRACK_ON, this.slide)
                : MaidBaubleImportScreen.blendColor(0xFF332E28, 0xFF3A4433, this.slide);
        graphics.fill(getX(), getY(), getX() + width, getY() + height, track);
        graphics.outline(getX(), getY(), width, height, MaidBaubleImportScreen.PANEL_BORDER);

        net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
        Component text = Component.translatable(this.state
                ? "gui.maid_file_manager.config.on_label"
                : "gui.maid_file_manager.config.off_label");
        int textColor = this.active
                ? (this.state ? MaidBaubleImportScreen.LABEL_ALLOW : MaidBaubleImportScreen.LABEL_DENY)
                : MaidBaubleImportScreen.LABEL_DENY;
        int textX = this.state
                ? getX() + 5
                : getX() + width - 5 - font.width(text);
        graphics.text(font, text, textX, getY() + (height - 8) / 2, textColor, false);

        int knobW = 22;
        int travel = width - 4 - knobW;
        int knobX = getX() + 2 + (int) (travel * this.slide);
        graphics.fill(knobX, getY() + 2, knobX + knobW, getY() + height - 2,
                this.active ? MaidBaubleImportScreen.KNOB : 0xFF9A9284);
        graphics.outline(knobX, getY() + 2, knobW, height - 4, 0xFF000000);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
