package io.github.zgxhzhr.maidfm.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * 多行文本输入框（原版 {@code EditBox} 的纵向扩展版，跨加载器通用）。
 *
 * <p>背景故事、个人资料等长文本用单行输入框不好用，本控件把文本按宽度软换行并逐行绘制，
 * 支持回车硬换行、光标插入/删除、方向键移动、鼠标点击定位与滚轮滚动。
 * 样式为半透明深色底 + 细暗色描边，融入深棕面板而非突兀的纯黑框。
 *
 * <p>特意继承原版 {@link EditBox} 而非 {@code AbstractWidget}：第三方输入法模组
 * （如 IMBlocker）通过识别原版输入框类型来决定是否激活输入法，只有继承 {@code EditBox}
 * 才能让本控件在获得焦点时正常切换并输入中文。对外行为均已被本类覆盖。
 */
public class MultiLineEditBox extends EditBox {
    private static final int BG = 0x66000000;
    private static final int BORDER = 0xFF4A4034;
    private static final int BORDER_FOCUS = 0xFFC9A96A;
    private static final int HINT_COLOR = 0xFF7A7264;
    private static final int PAD = 4;
    private static final int SCROLL_W = 2;

    private final Font font;
    private final int maxLength;

    private String value = "";
    private boolean editable = true;
    private boolean singleLine;
    private int textColor = 0xFFFFFFFF;
    private Component hint;

    /** 光标在 {@link #value} 中的字符索引 */
    private int cursor;
    /** 顶部显示的显示行索引（内容超高时滚动） */
    private int scrollLine;

    public MultiLineEditBox(Font font, int x, int y, int width, int height, int maxLength, Component narration) {
        super(font, x, y, width, height, narration);
        this.font = font;
        this.maxLength = maxLength;
    }

    // ===== 值 =====

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value == null ? "" : value;
        this.cursor = this.value.length();
        this.scrollLine = 0;
        ensureCursorVisible();
    }

    public void setTextColor(int color) {
        this.textColor = color;
    }

    public void setEditable(boolean editable) {
        this.editable = editable;
        this.active = editable;
    }

    public void setHint(Component hint) {
        this.hint = hint;
    }

    /** 单行模式：忽略回车换行，只显示一行（用于职业/生日等短文本） */
    public void setSingleLine(boolean singleLine) {
        this.singleLine = singleLine;
    }

    // ===== 编辑 =====

    private boolean canEdit() {
        return this.editable && this.active;
    }

    @Override
    public void insertText(String text) {
        if (!canEdit() || text == null || text.isEmpty()) {
            return;
        }
        String filtered = text.replace("\r", "");
        if (this.singleLine) {
            filtered = filtered.replace("\n", "");
        }
        int room = this.maxLength - this.value.length();
        if (room <= 0) {
            return;
        }
        if (filtered.length() > room) {
            filtered = filtered.substring(0, room);
        }
        this.value = this.value.substring(0, this.cursor) + filtered + this.value.substring(this.cursor);
        this.cursor += filtered.length();
        ensureCursorVisible();
    }

    @Override
    public void deleteChars(int dir) {
        if (!canEdit()) {
            return;
        }
        if (dir < 0 && this.cursor > 0) {
            this.value = this.value.substring(0, this.cursor - 1) + this.value.substring(this.cursor);
            this.cursor--;
        } else if (dir > 0 && this.cursor < this.value.length()) {
            this.value = this.value.substring(0, this.cursor) + this.value.substring(this.cursor + 1);
        }
        ensureCursorVisible();
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (!canEdit() || !this.isFocused()) {
            return false;
        }
        if (!isAllowedChar(codePoint)) {
            return false;
        }
        insertText(String.valueOf(codePoint));
        return true;
    }

    /** 是否可输入：排除 §（格式化符）与控制字符（与各版本 {@code Screen} 的可输入判定一致） */
    private static boolean isAllowedChar(char c) {
        return c != '\u00a7' && c >= ' ' && c != '\u007f';
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!canEdit() || !this.isFocused()) {
            return false;
        }
        switch (keyCode) {
            case 259: // Backspace
                deleteChars(-1);
                return true;
            case 261: // Delete
                deleteChars(1);
                return true;
            case 257: // Enter
            case 335: // Numpad Enter
                if (this.singleLine) {
                    return false;
                }
                insertText("\n");
                return true;
            case 263: // Left
                this.cursor = Mth.clamp(this.cursor - 1, 0, this.value.length());
                ensureCursorVisible();
                return true;
            case 262: // Right
                this.cursor = Mth.clamp(this.cursor + 1, 0, this.value.length());
                ensureCursorVisible();
                return true;
            case 268: // Home
                this.cursor = displayLineStart(this.cursor);
                ensureCursorVisible();
                return true;
            case 269: // End
                this.cursor = displayLineEnd(this.cursor);
                ensureCursorVisible();
                return true;
            case 265: // Up
                moveVertical(-1);
                return true;
            case 266: // Down
                moveVertical(1);
                return true;
            case 65: // A
            case 67: // C
            case 86: // V
            case 88: // X
                // 剪贴板组合键
                return handleClipboard(keyCode);
            default:
                // 其余按键交给界面处理（ESC 关闭等）
                return false;
        }
    }

    /**
     * 处理 Ctrl+A/C/V/X。
     *
     * <p>本控件没有选区概念，因此复制/剪切针对全文，全选等价于把光标移到末尾；
     * 粘贴直接读系统剪贴板，中英文均可插入（不经过 {@link #isAllowedChar}，只有换行在单行模式下会被过滤）。
     */
    private boolean handleClipboard(int keyCode) {
        if (!Screen.hasControlDown()) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        switch (keyCode) {
            case 65: // Ctrl+A：全选（光标移到末尾）
                this.cursor = this.value.length();
                ensureCursorVisible();
                return true;
            case 67: // Ctrl+C：复制全文
                minecraft.keyboardHandler.setClipboard(this.value);
                return true;
            case 88: // Ctrl+X：剪切全文
                minecraft.keyboardHandler.setClipboard(this.value);
                this.value = "";
                this.cursor = 0;
                this.scrollLine = 0;
                ensureCursorVisible();
                return true;
            case 86: // Ctrl+V：粘贴
                insertText(minecraft.keyboardHandler.getClipboard());
                return true;
            default:
                return false;
        }
    }

    private void moveVertical(int dir) {
        List<DLine> lines = buildDisplayLines();
        int idx = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (this.cursor >= lines.get(i).start && this.cursor <= lines.get(i).start + lines.get(i).text.length()) {
                idx = i;
                break;
            }
        }
        int col = this.cursor - lines.get(idx).start;
        int target = Mth.clamp(idx + dir, 0, lines.size() - 1);
        DLine t = lines.get(target);
        this.cursor = t.start + Mth.clamp(col, 0, t.text.length());
        ensureCursorVisible();
    }

    private int displayLineStart(int index) {
        List<DLine> lines = buildDisplayLines();
        DLine best = lines.get(0);
        for (DLine l : lines) {
            if (l.start <= index) {
                best = l;
            }
        }
        return best.start;
    }

    private int displayLineEnd(int index) {
        List<DLine> lines = buildDisplayLines();
        for (DLine l : lines) {
            if (index >= l.start && index <= l.start + l.text.length()) {
                return l.start + l.text.length();
            }
        }
        return this.value.length();
    }

    // ===== 交互 =====

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || !this.active || !this.visible) {
            return false;
        }
        if (mouseX < getX() || mouseX >= getX() + this.width || mouseY < getY() || mouseY >= getY() + this.height) {
            return false;
        }
        List<DLine> lines = buildDisplayLines();
        int lineIndex = Mth.clamp(this.scrollLine + (int) ((mouseY - (getY() + PAD)) / lineHeight()),
                0, Math.max(0, lines.size() - 1));
        DLine line = lines.get(lineIndex);
        int col = 0;
        int rel = (int) (mouseX - (getX() + PAD));
        int acc = 0;
        while (col < line.text.length() && acc + this.font.width(String.valueOf(line.text.charAt(col))) / 2 < rel) {
            acc += this.font.width(String.valueOf(line.text.charAt(col)));
            col++;
        }
        this.setFocused(true);
        this.cursor = line.start + col;
        ensureCursorVisible();
        return true;
    }

    // ===== 布局辅助 =====

    private int lineHeight() {
        return this.font.lineHeight + 1;
    }

    private int visibleLines() {
        if (this.singleLine) {
            return 1;
        }
        return Math.max(1, (this.height - PAD * 2) / lineHeight());
    }

    private int innerWidth() {
        return Math.max(1, this.width - PAD * 2 - (this.singleLine ? 0 : SCROLL_W));
    }

    private void ensureCursorVisible() {
        List<DLine> lines = buildDisplayLines();
        int idx = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (this.cursor >= lines.get(i).start && this.cursor <= lines.get(i).start + lines.get(i).text.length()) {
                idx = i;
                break;
            }
        }
        this.scrollLine = Mth.clamp(this.scrollLine, Math.max(0, idx - visibleLines() + 1), idx);
        int maxScroll = Math.max(0, lines.size() - visibleLines());
        this.scrollLine = Mth.clamp(this.scrollLine, 0, maxScroll);
    }

    /** 按宽度软换行：每个显示行记录其在 {@link #value} 中的起始索引 */
    private List<DLine> buildDisplayLines() {
        List<DLine> out = new ArrayList<>();
        int innerW = innerWidth();
        int n = this.value.length();
        int i = 0;
        while (true) {
            int start = i;
            StringBuilder sb = new StringBuilder();
            while (i < n && this.value.charAt(i) != '\n') {
                char c = this.value.charAt(i);
                if (sb.length() > 0 && this.font.width(sb.toString() + c) > innerW) {
                    break;
                }
                sb.append(c);
                i++;
            }
            out.add(new DLine(start, sb.toString()));
            if (i >= n) {
                break;
            }
            if (this.value.charAt(i) == '\n') {
                i++;
                if (i >= n) {
                    out.add(new DLine(i, ""));
                    break;
                }
            }
        }
        return out;
    }

    // ===== 渲染 =====

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(getX(), getY(), getX() + this.width, getY() + this.height, BG);
        graphics.renderOutline(getX(), getY(), this.width, this.height,
                this.isFocused() ? BORDER_FOCUS : BORDER);

        List<DLine> lines = buildDisplayLines();
        int innerW = innerWidth();
        int lh = lineHeight();
        int textTop = getY() + PAD;

        if (this.value.isEmpty() && this.hint != null) {
            graphics.drawString(this.font, this.hint, getX() + PAD, textTop, HINT_COLOR, false);
            return;
        }

        graphics.enableScissor(getX() + 1, getY() + 1, getX() + this.width - 1 - SCROLL_W, getY() + this.height - 1);
        int visible = visibleLines();
        for (int i = 0; i < visible; i++) {
            int idx = this.scrollLine + i;
            if (idx >= lines.size()) {
                break;
            }
            graphics.drawString(this.font, lines.get(idx).text,
                    getX() + PAD, textTop + i * lh, this.textColor, false);
        }
        // 光标
        if (this.isFocused() && this.editable && (System.currentTimeMillis() / 500L) % 2L == 0L) {
            DLine cl = lines.get(Math.min(this.scrollLine + visibleLines() - 1, lines.size() - 1));
            for (DLine l : lines) {
                if (this.cursor >= l.start && this.cursor <= l.start + l.text.length()) {
                    cl = l;
                    break;
                }
            }
            int lineIdx = lines.indexOf(cl);
            int row = lineIdx - this.scrollLine;
            if (row >= 0 && row < visible) {
                int col = this.cursor - cl.start;
                int cx = getX() + PAD + this.font.width(cl.text.substring(0, Mth.clamp(col, 0, cl.text.length())));
                int cy = textTop + row * lh;
                graphics.fill(cx, cy - 1, cx + 1, cy + this.font.lineHeight, 0xFFFFFFFF);
            }
        }
        graphics.disableScissor();

        // 滚动条
        if (!this.singleLine && lines.size() > visible) {
            int barX = getX() + this.width - 1 - SCROLL_W;
            int trackH = this.height - 2;
            int barH = Math.max(8, (int) ((double) trackH * visible / lines.size()));
            int maxScroll = lines.size() - visible;
            int barY = getY() + 1 + (int) ((double) (trackH - barH) * this.scrollLine / Math.max(1, maxScroll));
            graphics.fill(barX, barY, barX + SCROLL_W, barY + barH, BORDER_FOCUS);
        }
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }

    /** 一个显示行：{@code start} 为其在原始文本中的起始索引 */
    private static final class DLine {
        final int start;
        final String text;

        DLine(int start, String text) {
            this.start = start;
            this.text = text;
        }
    }
}
