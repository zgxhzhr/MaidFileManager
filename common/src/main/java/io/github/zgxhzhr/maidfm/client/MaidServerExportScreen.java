package io.github.zgxhzhr.maidfm.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 统一导出全员独立确认界面。
 *
 * <p>从 {@code MaidFileManagerScreen} 移出的独立 Screen。它原本写在那 2000+ 行类的末尾，
 * 但既不依赖外部实例（{@code static}），生命周期也完全独立（{@code removed} / {@code tick} /
 * {@code init} / {@code render} 全部自成一套），放在里面只是让那个类更长。
 *
 * <p>它需要主界面上的三个状态：是否处于统一导出模式、反馈抑制窗口、以及反馈文案入口。
 * 这三项在同包内可见（package-private），不对外暴露。
 */

/** 统一导出全员独立确认界面（按需求新增的独立界面；确认后回到主界面，切换到统一导出模式并自动执行导出） */
public class MaidServerExportScreen extends Screen {
    private final MaidFileManagerScreen parent;
    /** 确认屏面板的理想高度（内容够用时用它；屏幕不够高时会被 clamp 到 height-16） */
    private static final int CONFIRM_PANEL_H = 240;

    private final boolean hasPermission;

    public MaidServerExportScreen(MaidFileManagerScreen parent) {
        super(Component.translatable("maid_file_manager.gui.server_export.confirm_title"));
        this.parent = parent;
        this.hasPermission = parent.getMc() != null && parent.getMc().player != null
                && parent.getMc().player.hasPermissions(2);
    }

    @Override
    public void removed() {
        // 界面背景音乐（试验性功能）：离开界面时释放
        UiMusicManager.leave(this);
        super.removed();
    }

    @Override
    public void tick() {
        super.tick();
        UiMusicManager.tick();
    }

    @Override
    protected void init() {
        UiMusicManager.enter(this);
        // 两按钮总宽必须装进面板：341 宽时面板只有 325，160×2+20=340 会溢出
        int btnW = Math.min(160, Math.max(60, (Math.min(380, this.width - 16) - 20 - 24) / 2));
        int btnH = 20;
        // 【v4-3Fix 截图2：按钮从 height/2+30 下移到 height/2+96】
        //   说明文字渲染：标题 y=height/2-70，+26 标题底 + 9行×14 = h/2+82 最后一行文字底，再加 14px 间隙到 h/2+96。
        // 原为固定加性偏移 height/2+96（按 1080p+GUI scale 2 的说明文字行数推出）。
        // 1366×768 + GUI scale 4 时逻辑分辨率仅 341×192，height/2+96 = 192 已贴屏幕底边，按钮整体出屏。
        // 改为按「自适应后的面板底部」定位，与 render() 用同一公式。
        int panelHForButtons = Math.min(CONFIRM_PANEL_H, this.height - 16);
        int panelTopForButtons = (this.height - panelHForButtons) / 2;
        int centerY = panelTopForButtons + panelHForButtons - btnH - 12;
        int gap = 20;
        int baseX = this.width / 2 - btnW - gap / 2;
        // 确认按钮（仅 OP 可用）
        Button confirmBtn = Button.builder(Component.translatable("maid_file_manager.gui.export.confirm_ok"), b -> onConfirm())
                .bounds(baseX, centerY, btnW, btnH).build();
        confirmBtn.active = hasPermission;
        addRenderableWidget(confirmBtn);
        // 取消按钮（所有人都可用）
        addRenderableWidget(Button.builder(Component.translatable("maid_file_manager.gui.button.cancel_back"), b -> {
            if (this.minecraft != null) this.minecraft.setScreen(parent);
        }).bounds(baseX + btnW + gap, centerY, btnW, btnH).build());
    }

    /** 确认导出：回到主界面 → 切换到统一导出模式 → 主动执行 action 导出逻辑 */
    private void onConfirm() {
        if (!hasPermission || this.minecraft == null) return;
        this.minecraft.setScreen(parent);
        // 强制切换到统一导出模式（确保切换数据源为在线玩家分组）
        if (!parent.serverExportMode) parent.toggleServerExportMode();
        // 关键：此时 maidOwnerByEntityId 还是空的（列表要等服务端回包才有），
        // 立刻全选+导出会全部落到空玩家名 → 服务端查不到玩家 → 导出 0 条。
        // 因此只置标志并重新拉列表，真正的全选+导出由 onServerExportListReceived 触发。
        // 不再置 pendingServerExport：确认只负责进入统一导出面板，
        // 真正的导出由用户在列表里勾选后点「通过服务端导出选中女仆」触发。
        // 接上反馈抑制窗口（P2-26）：该字段原先只在声明与 toggle 两处置 0、从未赋正值，
        // 于是 onFeedbackReceived 里「异步期间只写聊天栏、不覆盖底部黄字」的分支是死的，
        // 服务端异步回执仍会盖掉主界面刚显示的「请求已发送」。15 秒后自动失效。
        parent.serverExportSuppressFeedbackUntil = System.currentTimeMillis() + 15_000L;
        // 不再调 refreshCurrentTab()：上面 toggleServerExportMode() 内部末尾已经发过一次
        // 统一导出列表请求，这里再发一次会让同一份内容发两个包；后到的回包把底部反馈
        // 覆盖成「已加载 N 个玩家」，玩家看到「点了确认却显示加载成功」而非「正在导出」。
        parent.setFeedback(Component.translatable("maid_file_manager.gui.server_export.preparing"));
    }

    /** 留空打断默认泥土背景 + 背景模糊（与主界面同一硬约束：不透明、无模糊）；1.20.x 为单参签名 */
    @Override
    public void renderBackground(GuiGraphics graphics) {
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 无阴影居中文字（drawCenteredString 默认带阴影会发虚） */
    private void drawCenteredNoShadow(GuiGraphics graphics, String text, int centerX, int y, int color) {
        graphics.drawString(this.font, Component.literal(text),
                centerX - this.font.width(text) / 2, y, color, false);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int cx = this.width / 2;
        // 全屏不透明深色底 + 居中面板，super 只画按钮（renderBackground 已空实现，单次绘制即可）
        graphics.fill(0, 0, this.width, this.height, MaidFileManagerScreen.SCREEN_BG);
        int panelW = Math.min(380, this.width - 16);
        // 面板高度自适应：与 init() 的按钮定位共用同一公式，避免两处不一致
        int panelH = Math.min(CONFIRM_PANEL_H, this.height - 16);
        int px = (this.width - panelW) / 2;
        int py = (this.height - panelH) / 2;
        graphics.fill(px, py, px + panelW, py + panelH, MaidFileManagerScreen.PANEL_BG);
        graphics.renderOutline(px, py, panelW, panelH, MaidFileManagerScreen.PANEL_BORDER);
        drawTextBlock(graphics, cx, py);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /** 绘制标题与说明文字（纯文本，无副作用） */
    private void drawTextBlock(GuiGraphics graphics, int cx, int py) {
        int y = py + 20;
        drawCenteredNoShadow(graphics,
                Component.translatable("maid_file_manager.gui.server_export.confirm_header").getString(),
                cx, y, MaidFileManagerScreen.HEADER_COLOR);
        y += 26;
        String[] lines;
        if (!hasPermission) {
            lines = new String[] {
                Component.translatable("maid_file_manager.gui.server_export.no_perm_line1").getString(),
                "",
                Component.translatable("maid_file_manager.gui.server_export.no_perm_line2").getString(),
                "",
                Component.translatable("maid_file_manager.gui.server_export.no_perm_line3").getString()
            };
        } else {
            lines = new String[] {
                Component.translatable("maid_file_manager.gui.server_export.rule_intro").getString(),
                "",
                Component.translatable("maid_file_manager.gui.server_export.rule1").getString(),
                Component.translatable("maid_file_manager.gui.server_export.rule2").getString(),
                Component.translatable("maid_file_manager.gui.server_export.rule3").getString(),
                "",
                Component.translatable("maid_file_manager.gui.server_export.rule_note").getString(),
                "",
                Component.translatable("maid_file_manager.gui.server_export.rule_footer").getString()
            };
        }
        int panelWText = Math.min(380, this.width - 16);
        int textMaxW = panelWText - 32;
        // 说明文字必须按面板宽度换行：最长中文行 531px、英文约 870px，
        // 341 宽屏下不换行会大段出屏两侧（原实现直接 drawCenteredNoShadow 整行）。
        for (String line : lines) {
            if (line == null || line.isEmpty()) {
                y += 14;
                continue;
            }
            for (net.minecraft.util.FormattedCharSequence seq
                    : this.font.split(Component.literal(line), textMaxW)) {
                graphics.drawString(this.font, seq, cx - this.font.width(seq) / 2, y, MaidFileManagerScreen.TEXT_COLOR, false);
                y += 10;
            }
        }
    }

    @Override
    public boolean shouldCloseOnEsc() { return true; }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }
}
