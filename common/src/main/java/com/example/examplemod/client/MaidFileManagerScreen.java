package com.example.examplemod.client;

import com.example.examplemod.Constants;
import com.example.examplemod.data.MaidFileData;
import com.example.examplemod.data.MaidFileIo;
import com.example.examplemod.data.MaidInfo;
import com.example.examplemod.network.IMaidFileNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.awt.Desktop;
import java.io.File;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 女仆文件管理主界面。
 *
 * <p>交互流程：用户在列表里点击行 → 底部【导出/导入选中】按钮执行动作。
 */
public class MaidFileManagerScreen extends Screen implements IMaidFileNetwork.ClientHandler {
    private enum Tab {EXPORT, IMPORT}

    /** 全屏深色背景（替代 Minecraft 默认泥土背景） */
    private static final int SCREEN_BG = 0xFF101010;
    /** 主面板颜色（不透明深棕） */
    private static final int PANEL_BG = 0xFF282018;
    private static final int PANEL_BORDER = 0xFF8B5A2B;
    private static final int LIST_BG = 0xFF1A1510;
    private static final int ROW_HOVER = 0x40FFFFFF;
    private static final int ROW_SELECTED = 0x80FFD700;
    private static final int HEADER_COLOR = 0xFFFFE082;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int SUBTEXT_COLOR = 0xFFB0B0B0;
    private static final int ACCENT = 0xFFD0A060;

    private static final int MARGIN = 30;
    private static final int LIST_TOP_OFFSET = 55;
    private static final int LIST_BOTTOM_OFFSET = 70;
    private static final int ROW_HEIGHT = 22;

    private Tab currentTab = Tab.EXPORT;
    private List<MaidInfo> maidList = new ArrayList<>();
    private List<String> fileList = new ArrayList<>();
    private Component feedbackMsg = Component.empty();
    private long feedbackExpireAt = 0L;
    private MaidListWidget maidListWidget;
    private FileListWidget fileListWidget;
    private Button exportTabBtn;
    private Button importTabBtn;
    private Button refreshBtn;
    private Button closeBtn;
    private Button actionBtn;
    private Button openImportDirBtn;
    private int panelX, panelY, panelW, panelH;

    public MaidFileManagerScreen() {
        super(Component.translatable("maid_file_manager.gui.title"));
    }

    @Override
    protected void init() {
        super.init();
        panelW = Math.min(580, this.width - MARGIN * 2);
        panelH = this.height - MARGIN * 2;
        panelX = (this.width - panelW) / 2;
        panelY = MARGIN;

        // Tab 按钮：动态调整宽度
        int tabY = panelY + 28;
        int tabW = Math.min(100, panelW / 6);
        int tabGap = Math.min(8, panelW / 40);
        int totalTabW = tabW * 2 + tabGap;
        int rightSpace = 100;
        int tabStartX = panelX + Math.max(0, (panelW - totalTabW - rightSpace) / 2);
        exportTabBtn = Button.builder(Component.translatable("maid_file_manager.gui.tab.export"),
                        b -> switchTab(Tab.EXPORT))
                .bounds(tabStartX, tabY, tabW, 18).build();
        importTabBtn = Button.builder(Component.translatable("maid_file_manager.gui.tab.import"),
                        b -> switchTab(Tab.IMPORT))
                .bounds(tabStartX + tabW + tabGap, tabY, tabW, 18).build();
        // 刷新和关闭按钮
        int rightBtnX = panelX + panelW - 90;
        refreshBtn = Button.builder(Component.translatable("maid_file_manager.gui.button.refresh"),
                        b -> refreshCurrentTab())
                .bounds(rightBtnX, tabY, 50, 18).build();
        closeBtn = Button.builder(Component.translatable("maid_file_manager.gui.button.close"),
                        b -> onClose())
                .bounds(panelX + panelW - 40, panelY + 4, 35, 14).build();
        addRenderableWidget(exportTabBtn);
        addRenderableWidget(importTabBtn);
        addRenderableWidget(refreshBtn);
        addRenderableWidget(closeBtn);

        // 列表控件
        int listX = panelX + 6;
        int listW = panelW - 12;
        int listTop = panelY + LIST_TOP_OFFSET;
        int listBottom = panelY + panelH - LIST_BOTTOM_OFFSET;
        maidListWidget = new MaidListWidget(listX, listW, listTop, listBottom);
        fileListWidget = new FileListWidget(listX, listW, listTop, listBottom);
        updateListVisibility();

        // 底部按钮组
        openImportDirBtn = Button.builder(
                        Component.translatable("maid_file_manager.gui.button.open_import_dir"),
                        b -> onOpenImportDir()).build();

        actionBtn = Button.builder(Component.empty(), this::onActionButtonClick).build();
        updateActionButtonText();
        addRenderableWidget(openImportDirBtn);
        addRenderableWidget(actionBtn);
        updateBottomButtonLayout();
        updateImportDirBtnVisibility();

        IMaidFileNetwork.ClientHandlerHolder.set(this);
        refreshCurrentTab();
    }

    private void switchTab(Tab tab) {
        if (currentTab == tab) return;
        currentTab = tab;
        updateListVisibility();
        updateActionButtonText();
        updateImportDirBtnVisibility();
        updateBottomButtonLayout();
        refreshCurrentTab();
    }

    private void updateBottomButtonLayout() {
        int actionW = Math.min(180, panelW / 3);
        int actionH = 22;
        int openDirW = Math.min(180, panelW / 3);
        int gap = Math.min(10, panelW / 30);
        int btnY = panelY + panelH - actionH - 28;

        if (currentTab == Tab.IMPORT) {
            int totalW = openDirW + gap + actionW;
            int groupStartX = panelX + Math.max(0, (panelW - totalW) / 2);
            openImportDirBtn.setX(groupStartX);
            openImportDirBtn.setY(btnY);
            actionBtn.setX(groupStartX + openDirW + gap);
            actionBtn.setY(btnY);
        } else {
            int centerX = panelX + Math.max(0, (panelW - actionW) / 2);
            openImportDirBtn.setX(-9999);
            openImportDirBtn.setY(-9999);
            actionBtn.setX(centerX);
            actionBtn.setY(btnY);
        }
    }

    private void updateImportDirBtnVisibility() {
        if (currentTab == Tab.IMPORT) {
            openImportDirBtn.visible = true;
            openImportDirBtn.active = true;
        } else {
            openImportDirBtn.visible = false;
            openImportDirBtn.active = false;
        }
    }

    private void onOpenImportDir() {
        try {
            File dir = new File(this.minecraft.gameDirectory, Constants.MAID_IMPORTS_DIR);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(dir);
            } else {
                String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
                ProcessBuilder pb;
                if (os.contains("win")) {
                    pb = new ProcessBuilder("explorer", dir.getAbsolutePath());
                } else if (os.contains("mac")) {
                    pb = new ProcessBuilder("open", dir.getAbsolutePath());
                } else {
                    pb = new ProcessBuilder("xdg-open", dir.getAbsolutePath());
                }
                pb.start();
            }
            Constants.LOG.info("[maid_file_manager] Opened import dir: {}", dir.getAbsolutePath());
        } catch (Exception e) {
            Constants.LOG.error("[maid_file_manager] Failed to open import dir", e);
            setFeedback(Component.translatable("maid_file_manager.gui.error.open_dir_failed"));
        }
    }

    private void updateListVisibility() {
        removeWidget(maidListWidget);
        removeWidget(fileListWidget);
        if (currentTab == Tab.EXPORT) {
            addRenderableWidget(maidListWidget);
        } else {
            addRenderableWidget(fileListWidget);
        }
    }

    private void updateActionButtonText() {
        if (actionBtn == null) return;
        if (currentTab == Tab.EXPORT) {
            actionBtn.setMessage(Component.translatable("maid_file_manager.gui.button.export_selected"));
        } else {
            actionBtn.setMessage(Component.translatable("maid_file_manager.gui.button.import_selected"));
        }
    }

    private void refreshCurrentTab() {
        if (currentTab == Tab.EXPORT) {
            IMaidFileNetwork net = IMaidFileNetwork.Holder.get();
            if (net == null) {
                setFeedback(Component.translatable("maid_file_manager.gui.error.network_unavailable"));
                return;
            }
            setFeedback(Component.translatable("maid_file_manager.gui.export.loading"));
            net.sendRequestMaidList();
        } else {
            // 导入 Tab：从本地 maid_imports 目录读取文件列表
            setFeedback(Component.translatable("maid_file_manager.gui.import.loading"));
            try {
                Path gameDir = this.minecraft.gameDirectory.toPath().toAbsolutePath();
                Path dir = MaidFileIo.ensureImportsDir(gameDir);
                List<String> files = MaidFileIo.listMaidFiles(dir);
                this.fileList = new ArrayList<>(files);
                fileListWidget.refresh();
                if (files.isEmpty()) {
                    setFeedback(Component.translatable("maid_file_manager.gui.import.empty"));
                } else {
                    setFeedback(Component.translatable("maid_file_manager.gui.import.loaded", files.size()));
                }
            } catch (Exception e) {
                Constants.LOG.error("[maid_file_manager] Failed to list import files", e);
                setFeedback(Component.translatable("maid_file_manager.gui.error.network_unavailable"));
            }
        }
    }

    private void setFeedback(Component msg) {
        this.feedbackMsg = msg;
        this.feedbackExpireAt = System.currentTimeMillis() + 6000;
    }

    private void onActionButtonClick(Button b) {
        IMaidFileNetwork net = IMaidFileNetwork.Holder.get();
        if (net == null) {
            Constants.LOG.warn("[maid_file_manager] Action button: net is null");
            setFeedback(Component.translatable("maid_file_manager.gui.error.network_unavailable"));
            return;
        }
        if (currentTab == Tab.EXPORT) {
            MaidListWidget.Entry sel = maidListWidget.getSelected();
            if (sel == null) {
                setFeedback(Component.translatable("maid_file_manager.gui.export.no_selection"));
                return;
            }
            MaidInfo info = sel.info;
            setFeedback(Component.translatable("maid_file_manager.gui.export.exporting"));
            net.sendExportMaid(info.entityId());
        } else {
            FileListWidget.Entry sel = fileListWidget.getSelected();
            if (sel == null) {
                setFeedback(Component.translatable("maid_file_manager.gui.import.no_selection"));
                return;
            }
            String fileName = sel.fileName;
            try {
                Path gameDir = this.minecraft.gameDirectory.toPath().toAbsolutePath();
                Path dir = MaidFileIo.ensureImportsDir(gameDir);
                Path file = dir.resolve(fileName).normalize();
                if (!file.startsWith(dir)) {
                    setFeedback(Component.translatable("maid_file_manager.import.fail.exception", "非法路径"));
                    return;
                }
                MaidFileData data = MaidFileIo.readMaidFile(file);
                if (data == null || data.getData() == null) {
                    setFeedback(Component.translatable("maid_file_manager.import.fail.exception", "无效的女仆文件"));
                    return;
                }
                setFeedback(Component.translatable("maid_file_manager.gui.import.importing"));
                net.sendImportFile(data);
            } catch (Exception e) {
                Constants.LOG.error("[maid_file_manager] Failed to read import file", e);
                setFeedback(Component.translatable("maid_file_manager.import.fail.exception", e.getMessage()));
            }
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        // 彻底覆盖：不绘制 Minecraft 默认背景（渐变/泥土）
    }

    @Override
    public void renderDirtBackground(GuiGraphics graphics) {
        // 彻底覆盖：不绘制泥土背景
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 全屏不透明深色背景（双重保险覆盖默认背景）
        graphics.fill(0, 0, this.width, this.height, SCREEN_BG);
        // 主面板
        drawPanel(graphics, panelX, panelY, panelW, panelH);
        // 标题
        graphics.drawCenteredString(this.font,
                Component.translatable("maid_file_manager.gui.title"),
                this.width / 2, panelY + 8, HEADER_COLOR);
        // 当前 Tab 下划线
        Button activeTab = currentTab == Tab.EXPORT ? exportTabBtn : importTabBtn;
        graphics.fill(activeTab.getX(), activeTab.getY() + activeTab.getHeight(),
                activeTab.getX() + activeTab.getWidth(), activeTab.getY() + activeTab.getHeight() + 2,
                0xFFFFD700);
        // 子组件
        super.render(graphics, mouseX, mouseY, partialTick);
        // 反馈消息（在按钮上方，留出足够空间）
        if (!feedbackMsg.getString().isEmpty() && System.currentTimeMillis() < feedbackExpireAt) {
            int fbY = panelY + panelH - 72;
            graphics.drawCenteredString(this.font, feedbackMsg, this.width / 2, fbY, 0xFFFFAA00);
        }
        // Hover tooltip
        drawHoverTooltip(graphics, mouseX, mouseY);
    }

    private void drawPanel(GuiGraphics g, int x, int y, int w, int h) {
        // 主背景（不透明深棕）
        g.fill(x, y, x + w, y + h, PANEL_BG);
        // 边框（木质感）
        g.fill(x, y, x + w, y + 1, PANEL_BORDER);
        g.fill(x, y + h - 1, x + w, y + h, PANEL_BORDER);
        g.fill(x, y, x + 1, y + h, PANEL_BORDER);
        g.fill(x + w - 1, y, x + w, y + h, PANEL_BORDER);
        // 内层亮线
        g.fill(x + 1, y + 1, x + w - 1, y + 2, ACCENT);
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, ACCENT);
    }

    private void drawHoverTooltip(GuiGraphics g, int mouseX, int mouseY) {
        if (currentTab == Tab.EXPORT) {
            MaidListWidget.Entry sel = maidListWidget.getHovered(mouseX, mouseY);
            if (sel != null) {
                MaidInfo info = sel.info;
                List<Component> lines = new ArrayList<>();
                lines.add(Component.literal(info.displayName())
                        .withStyle(ChatFormatting.GOLD));
                lines.add(Component.literal("Model ID: " + info.modelId())
                        .withStyle(ChatFormatting.GRAY));
                lines.add(Component.literal(String.format(Locale.ROOT,
                        "好感度: %d   血量: %.0f / %.0f",
                        info.favorability(), info.health(), info.maxHealth()))
                        .withStyle(ChatFormatting.WHITE));
                if (info.ownerName() != null) {
                    lines.add(Component.literal("主人: " + info.ownerName())
                            .withStyle(ChatFormatting.AQUA));
                }
                g.renderTooltip(this.font, lines, Optional.empty(), mouseX, mouseY);
            }
        } else {
            FileListWidget.Entry sel = fileListWidget.getHovered(mouseX, mouseY);
            if (sel != null) {
                String fileName = sel.fileName;
                List<Component> lines = new ArrayList<>();
                lines.add(Component.literal(fileName).withStyle(ChatFormatting.GOLD));
                long ts = MaidFileIo.parseTimestampFromFileName(fileName);
                if (ts > 0) {
                    lines.add(Component.literal("时间: "
                                    + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(ts)))
                            .withStyle(ChatFormatting.GRAY));
                }
                lines.add(Component.literal("位于 maid_imports/ 目录")
                        .withStyle(ChatFormatting.DARK_GRAY));
                g.renderTooltip(this.font, lines, Optional.empty(), mouseX, mouseY);
            }
        }
    }

    @Override
    public void onMaidListReceived(List<MaidInfo> list) {
        this.maidList = new ArrayList<>(list);
        maidListWidget.refresh();
        if (currentTab == Tab.EXPORT) {
            if (list.isEmpty()) {
                setFeedback(Component.translatable("maid_file_manager.gui.export.empty"));
            } else {
                setFeedback(Component.translatable("maid_file_manager.gui.export.loaded", list.size()));
            }
        }
    }

    @Override
    public void onExportResultReceived(MaidFileData data) {
        if (data == null) {
            setFeedback(Component.translatable("maid_file_manager.export.fail", "导出数据为空"));
            return;
        }
        try {
            Path gameDir = this.minecraft.gameDirectory.toPath().toAbsolutePath();
            Path dir = MaidFileIo.ensureExportsDir(gameDir);
            String displayName = data.getDisplayName() != null
                    ? data.getDisplayName()
                    : (data.getModelId() != null ? data.getModelId() : "unknown");
            String ownerUuid = data.getOwnerUuid();
            String fileName = MaidFileIo.writeMaidFile(dir, displayName, data.getModelId(),
                    ownerUuid, data, LocalDateTime.now());
            setFeedback(Component.literal("导出成功！文件已保存到 maid_exports/" + fileName));
            Constants.LOG.info("[maid_file_manager] Export saved locally: {}", dir.resolve(fileName));
            if (this.minecraft.player != null) {
                this.minecraft.player.displayClientMessage(
                        Component.literal("导出成功！文件已保存到 maid_exports/" + fileName), false);
            }
        } catch (Exception e) {
            Constants.LOG.error("[maid_file_manager] Failed to save export file locally", e);
            setFeedback(Component.translatable("maid_file_manager.export.fail", e.getMessage()));
        }
    }

    @Override
    public void onFeedbackReceived(Component message) {
        setFeedback(message);
        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.displayClientMessage(message, false);
        }
    }

    @Override
    public void onClose() {
        IMaidFileNetwork.ClientHandlerHolder.set(null);
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---------- MaidListWidget ----------

    private final class MaidListWidget extends ObjectSelectionList<MaidListWidget.Entry> {
        private final int listX, listW, listTop, listBottom;

        MaidListWidget(int listX, int listW, int listTop, int listBottom) {
            super(MaidFileManagerScreen.this.minecraft, listW,
                    listBottom - listTop, listTop, listBottom, ROW_HEIGHT);
            this.listX = listX;
            this.listW = listW;
            this.listTop = listTop;
            this.listBottom = listBottom;
            this.setLeftPos(listX);
            setRenderBackground(false);
            setRenderTopAndBottom(false);
            refresh();
        }

        @Override
        public int getRowWidth() {
            return this.listW - 8;
        }

        @Override
        protected void renderBackground(GuiGraphics graphics) {
            graphics.fill(this.listX, this.listTop,
                    this.listX + this.listW, this.listBottom, LIST_BG);
        }

        public Entry getHovered(double mouseX, double mouseY) {
            return this.getEntryAtPosition(mouseX, mouseY);
        }

        void refresh() {
            clearEntries();
            for (MaidInfo info : maidList) {
                addEntry(new Entry(info));
            }
        }

        final class Entry extends ObjectSelectionList.Entry<Entry> {
            final MaidInfo info;

            Entry(MaidInfo info) {
                this.info = info;
            }

            @Override
            public void render(GuiGraphics graphics, int index, int top, int left,
                               int width, int height, int mouseX, int mouseY, boolean hovering,
                               float partialTick) {
                boolean selected = MaidListWidget.this.getSelected() == this;
                if (selected) {
                    graphics.fill(left, top - 1, left + width, top + height + 1, ROW_SELECTED);
                } else if (hovering) {
                    graphics.fill(left, top - 1, left + width, top + height + 1, ROW_HOVER);
                }
                String name = info.displayName();
                graphics.drawString(MaidFileManagerScreen.this.font,
                        name, left + 4, top + 2, TEXT_COLOR);
                String detail = String.format(Locale.ROOT, "%s  |  好感:%d  HP:%.0f/%.0f  %s",
                        info.modelId(), info.favorability(), info.health(), info.maxHealth(),
                        info.tamed() ? "已驯服" : "未驯服");
                graphics.drawString(MaidFileManagerScreen.this.font,
                        detail, left + 4, top + 12, SUBTEXT_COLOR);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                MaidListWidget.this.setSelected(this);
                return true;
            }

            @Override
            public Component getNarration() {
                return Component.literal(info.displayName());
            }
        }
    }

    // ---------- FileListWidget ----------

    private final class FileListWidget extends ObjectSelectionList<FileListWidget.Entry> {
        private final int listX, listW, listTop, listBottom;

        FileListWidget(int listX, int listW, int listTop, int listBottom) {
            super(MaidFileManagerScreen.this.minecraft, listW,
                    listBottom - listTop, listTop, listBottom, ROW_HEIGHT);
            this.listX = listX;
            this.listW = listW;
            this.listTop = listTop;
            this.listBottom = listBottom;
            this.setLeftPos(listX);
            setRenderBackground(false);
            setRenderTopAndBottom(false);
            refresh();
        }

        @Override
        public int getRowWidth() {
            return this.listW - 8;
        }

        @Override
        protected void renderBackground(GuiGraphics graphics) {
            graphics.fill(this.listX, this.listTop,
                    this.listX + this.listW, this.listBottom, LIST_BG);
        }

        public Entry getHovered(double mouseX, double mouseY) {
            return this.getEntryAtPosition(mouseX, mouseY);
        }

        void refresh() {
            clearEntries();
            for (String fileName : fileList) {
                addEntry(new Entry(fileName));
            }
        }

        final class Entry extends ObjectSelectionList.Entry<Entry> {
            final String fileName;

            Entry(String fileName) {
                this.fileName = fileName;
            }

            @Override
            public void render(GuiGraphics graphics, int index, int top, int left,
                               int width, int height, int mouseX, int mouseY, boolean hovering,
                               float partialTick) {
                boolean selected = FileListWidget.this.getSelected() == this;
                if (selected) {
                    graphics.fill(left, top - 1, left + width, top + height + 1, ROW_SELECTED);
                } else if (hovering) {
                    graphics.fill(left, top - 1, left + width, top + height + 1, ROW_HOVER);
                }
                graphics.drawString(MaidFileManagerScreen.this.font,
                        fileName, left + 4, top + 2, TEXT_COLOR);
                long ts = MaidFileIo.parseTimestampFromFileName(fileName);
                String time = ts > 0 ? new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(ts)) : "?";
                graphics.drawString(MaidFileManagerScreen.this.font,
                        "时间: " + time, left + 4, top + 12, SUBTEXT_COLOR);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                FileListWidget.this.setSelected(this);
                return true;
            }

            @Override
            public Component getNarration() {
                return Component.literal(fileName);
            }
        }
    }
}
