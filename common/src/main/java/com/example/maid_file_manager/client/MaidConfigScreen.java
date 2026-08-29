package com.example.maid_file_manager.client;

import com.example.maid_file_manager.config.MaidConfigManager;
import com.example.maid_file_manager.network.IMaidFileNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置界面（从管理界面右上角「设置」按钮进入）。
 *
 * <p>视觉与导入/导出管理界面一致：全屏不透明深色背景 + 深棕主面板（完全遮住世界画面）。
 *
 * <p>开关为左右滑动样式：滑块滑到右侧=允许（绿色轨道），滑到左侧=不允许（灰色轨道），
 * 状态由开关组件自身持有，可反复切换。
 *
 * <p>分为两个区块：
 * <ul>
 *   <li><b>服务端设置</b>（仅 OP/联机宿主可修改，修改后发包到服务端并广播同步）：
 *     允许客户端导入女仆（默认开）/ 导入时允许携带饰品（默认开）</li>
 *   <li><b>客户端设置</b>（本人随时可改，写本地配置）：
 *     允许服务端统一导出你的女仆（默认关）</li>
 * </ul>
 */
public class MaidConfigScreen extends Screen {
    // ===== 配色（与 MaidFileManagerScreen 保持一致） =====
    /** 全屏深色背景（不透明，遮住世界画面） */
    private static final int SCREEN_BG = 0xFF101010;
    /** 主面板颜色（不透明深棕） */
    private static final int PANEL_BG = 0xFF282018;
    private static final int PANEL_BORDER = 0xFF8B5A2B;
    private static final int HEADER_COLOR = 0xFFFFE082;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int SUBTEXT_COLOR = 0xFFB0B0B0;

    /** 滑动开关：轨道/滑块/状态字配色 */
    private static final int TRACK_OFF = 0xFF4A4038;
    private static final int TRACK_ON = 0xFF5A8A3A;
    private static final int KNOB = 0xFFE8E0D0;
    private static final int LABEL_ALLOW = 0xFFB8E08A;
    private static final int LABEL_DENY = 0xFFB0B0B0;

    private static final int PANEL_W = 340;
    private static final int ROW_H = 22;
    private static final int SWITCH_W = 64;
    private static final int SWITCH_H = 16;

    private final Screen parent;
    private int panelX;
    private int panelY;
    private int panelH;
    /** 非 widget 文字（区块标题/行标签），render 阶段统一绘制 */
    private final List<RowLabel> rowLabels = new ArrayList<>();

    public MaidConfigScreen(Screen parent) {
        super(Component.translatable("gui.maid_file_manager.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        rowLabels.clear();
        boolean hasServerPerm = this.minecraft != null && this.minecraft.player != null
                && this.minecraft.player.hasPermissions(2);

        // 先按内容结构算出面板高度，再垂直居中
        panelH = 8 + 16 + 4   // 标题
                + 14 + ROW_H + ROW_H   // 服务端区块标题 + 2 行
                + (hasServerPerm ? 0 : 14)   // 无权限提示行（仅无权限时）
                + 16 + ROW_H   // 客户端区块标题 + 1 行
                + 12 + 20 + 10;   // 完成按钮 + 底部边距
        panelX = (this.width - PANEL_W) / 2;
        panelY = Math.max(10, (this.height - panelH) / 2);

        int labelX = panelX + 20;
        int switchX = panelX + PANEL_W - 20 - SWITCH_W;
        int y = panelY + 8 + 16 + 4;

        // ===== 服务端设置区块 =====
        rowLabels.add(new RowLabel(Component.translatable("gui.maid_file_manager.config.server_section"),
                labelX, y, HEADER_COLOR));
        y += 14;
        y = addRow(labelX, switchX, y, Component.translatable("gui.maid_file_manager.config.allow_import"),
                MaidConfigManager.cachedClientImportAllowed(), true,
                MaidConfigManager.KEY_ALLOW_CLIENT_IMPORT, hasServerPerm);
        y = addRow(labelX, switchX, y, Component.translatable("gui.maid_file_manager.config.allow_baubles"),
                MaidConfigManager.cachedBaublesAllowed(), true,
                MaidConfigManager.KEY_ALLOW_BAUBLES, hasServerPerm);
        if (!hasServerPerm) {
            rowLabels.add(new RowLabel(Component.translatable("gui.maid_file_manager.config.no_permission"),
                    labelX, y + 2, SUBTEXT_COLOR));
            y += 14;
        }

        // ===== 客户端设置区块 =====
        y += 2;
        rowLabels.add(new RowLabel(Component.translatable("gui.maid_file_manager.config.client_section"),
                labelX, y, HEADER_COLOR));
        y += 14;
        addRow(labelX, switchX, y, Component.translatable("gui.maid_file_manager.config.allow_server_export"),
                MaidConfigManager.isClientAllowServerExport(), false, null, true);
        y += ROW_H;

        // ===== 完成（返回管理界面） =====
        y += 12;
        addRenderableWidget(Button.builder(Component.translatable("gui.maid_file_manager.config.done"),
                        b -> this.onClose())
                .bounds(this.width / 2 - 60, y, 120, 20)
                .build());
    }

    /** 加一行：左侧标签文字 + 右侧滑动开关，返回下一行的 y */
    private int addRow(int labelX, int switchX, int y, Component label, boolean initialState,
                       boolean serverConfig, String configKey, boolean enabled) {
        rowLabels.add(new RowLabel(label, labelX, y + (SWITCH_H - 8) / 2,
                enabled ? TEXT_COLOR : SUBTEXT_COLOR));
        ToggleSwitch sw = new ToggleSwitch(switchX, y, initialState, serverConfig, configKey);
        sw.active = enabled;
        addRenderableWidget(sw);
        return y + ROW_H;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // ① 先画全屏不透明深色底（把 gui_blur shader 的世界层完全盖死）
        graphics.fill(0, 0, this.width, this.height, SCREEN_BG);
        // ② 再画主面板 + 边框
        graphics.fill(panelX, panelY, panelX + PANEL_W, panelY + panelH, PANEL_BG);
        graphics.renderOutline(panelX, panelY, PANEL_W, panelH, PANEL_BORDER);
        // ③ 标题（禁用阴影，清晰显示）
        graphics.drawString(this.font, this.title,
                this.width / 2 - this.font.width(this.title) / 2, panelY + 8, HEADER_COLOR, false);
        // ④ 区块标题与行标签
        for (RowLabel row : rowLabels) {
            graphics.drawString(this.font, row.text(), row.x(), row.y(), row.color(), false);
        }
        // ⑤ 子组件（Switch / 完成按钮等）
        super.render(graphics, mouseX, mouseY, partialTick);
        // ⑥ 再盖一次全屏不透明底（兜底：防止 super.render 内部通过 4 参 renderBackground 重新叠加 blur shader 盖过我们已画内容）
        graphics.fill(0, 0, this.width, this.height, SCREEN_BG);
        // ⑦ 再次画主面板、标题、行标签（否则会被第 ⑥ 步盖掉）
        graphics.fill(panelX, panelY, panelX + PANEL_W, panelY + panelH, PANEL_BG);
        graphics.renderOutline(panelX, panelY, PANEL_W, panelH, PANEL_BORDER);
        graphics.drawString(this.font, this.title,
                this.width / 2 - this.font.width(this.title) / 2, panelY + 8, HEADER_COLOR, false);
        for (RowLabel row : rowLabels) {
            graphics.drawString(this.font, row.text(), row.x(), row.y(), row.color(), false);
        }
        // ⑧ 把所有 widget 再画一遍（Switch 的状态/完成按钮）。
        //    1.21 Screen.renderables 是 private；用反射读（ScreenWidgetAccess 失败时返回空 List → 不重画只轻微 blur，绝不崩）。
        List<Renderable> widgets = ScreenWidgetAccess.getRenderables(this);
        for (Renderable w : widgets) {
            w.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    /** 打断 Screen 默认的 4 参 renderBackground 调用链（它会 apply gui_blur shader + 画泥土背景） */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 什么都不做 —— 全屏底色 + 面板统一在 render() 开头绘制
    }

    /** 打断 Screen 默认的单参 renderBackground 调用链 */
    public void renderBackground(GuiGraphics graphics) {
        // 什么都不做
    }

    /** 打断 Screen 默认的泥土背景绘制链路 */
    public void renderDirtBackground(GuiGraphics graphics) {
        // 什么都不做
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    // 1.21 Screen 有两个映射名：Mojang 官方映射=returnsPauseScreen；Parchment=isPauseScreen。
    // 两个名字都写一遍（不加 @Override，防止某映射版本编译不过；运行时 MC 只关心其中一个名命中对应方法）。
    // 只要有一个被 JVM 识别为覆写，MC 就不会对本界面套用 pause menu 的 gui_blur shader。
    @SuppressWarnings("unused")
    public boolean returnsPauseScreen() {
        return false;
    }

    @SuppressWarnings("unused")
    public boolean isPauseScreen() {
        return false;
    }

    /** 行标签（非交互文字） */
    private record RowLabel(Component text, int x, int y, int color) {
    }

    /**
     * 左右滑动开关：点击切换，滑块带平滑动画。
     * 状态由组件自身持有（state 字段），修复了旧实现闭包捕获旧值导致只能切换一次的问题。
     */
    private class ToggleSwitch extends AbstractButton {
        private final boolean serverConfig;
        /** 服务端配置键；null 表示客户端本地配置 */
        private final String configKey;
        private boolean state;
        /** 滑块动画进度：0=左（不允许），1=右（允许） */
        private float slide;

        ToggleSwitch(int x, int y, boolean initialState, boolean serverConfig, String configKey) {
            super(x, y, SWITCH_W, SWITCH_H, Component.empty());
            this.state = initialState;
            this.slide = initialState ? 1.0F : 0.0F;
            this.serverConfig = serverConfig;
            this.configKey = configKey;
        }

        @Override
        public void onPress() {
            this.state = !this.state;
            if (this.serverConfig) {
                // 服务端配置：发包（服务端校验 OP 权限后写文件并广播同步）
                IMaidFileNetwork net = IMaidFileNetwork.Holder.get();
                if (net != null) {
                    net.sendSetServerConfig(this.configKey, this.state);
                }
            } else {
                // 客户端本地配置：写本地文件并上报同意状态
                MaidConfigManager.setClientAllowServerExport(this.state);
            }
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            // 滑块向目标位置平滑靠拢（左右滑动效果）
            float target = this.state ? 1.0F : 0.0F;
            this.slide += (target - this.slide) * 0.35F;
            if (Math.abs(target - this.slide) < 0.02F) {
                this.slide = target;
            }

            // 轨道：关=深灰，开=绿；不可用时整体压暗
            int track = this.active
                    ? blendColor(TRACK_OFF, TRACK_ON, this.slide)
                    : blendColor(0xFF332E28, 0xFF3A4433, this.slide);
            graphics.fill(getX(), getY(), getX() + width, getY() + height, track);
            graphics.renderOutline(getX(), getY(), width, height, PANEL_BORDER);

            // 状态字显示在滑块未盖住的一侧（允许=左，不允许=右）
            Component text = Component.translatable(this.state
                    ? "gui.maid_file_manager.config.on_label"
                    : "gui.maid_file_manager.config.off_label");
            int textColor = this.active ? (this.state ? LABEL_ALLOW : LABEL_DENY) : SUBTEXT_COLOR;
            int textX = this.state
                    ? getX() + 5
                    : getX() + width - 5 - MaidConfigScreen.this.font.width(text);
            graphics.drawString(MaidConfigScreen.this.font, text, textX, getY() + (height - 8) / 2, textColor, false);

            // 滑块
            int knobW = 22;
            int travel = width - 4 - knobW;
            int knobX = getX() + 2 + (int) (travel * this.slide);
            graphics.fill(knobX, getY() + 2, knobX + knobW, getY() + height - 2,
                    this.active ? KNOB : 0xFF9A9284);
            graphics.renderOutline(knobX, getY() + 2, knobW, height - 4, 0xFF000000);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            this.defaultButtonNarrationText(output);
        }
    }

    /** 颜色线性插值（不透明） */
    private static int blendColor(int from, int to, float t) {
        int fr = (from >> 16) & 0xFF;
        int fg = (from >> 8) & 0xFF;
        int fb = from & 0xFF;
        int tr = (to >> 16) & 0xFF;
        int tg = (to >> 8) & 0xFF;
        int tb = to & 0xFF;
        int r = (int) (fr + (tr - fr) * t);
        int g = (int) (fg + (tg - fg) * t);
        int b = (int) (fb + (tb - fb) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
