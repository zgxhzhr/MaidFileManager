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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 女仆文件管理主界面。
 *
 * <p>交互流程：玩家在列表里勾选条目 → 底部【批量导出/批量导入】按钮执行动作。
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
    /** 主面板宽度砍一砍（反馈太宽太乱） */
    private static final int PANEL_MAX_W = 430;
    /** 左侧工具列宽（竖排堆：全选 / 保留女仆 / 打开文件夹）—— 按钮统一宽 = SIDEBAR_W - 12 */
    private static final int SIDEBAR_W = 156;
    /** 列表与左侧工具列之间内间距 */
    private static final int INNER_GAP = 8;
    /** 列表底部距离主动作按钮上方留多少（给 feedback 居中文字） */
    private static final int LIST_BOTTOM_PAD = 68;
    /** 标题/Tab 占多少高度（列表 startY 从 Tab 底 + 8 开始） */
    private static final int HEADER_TOTAL_H = 64;
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
    /** 导出 Tab 工具栏②：打开导出文件夹（新增） */
    private Button openExportDirBtn;
    /** 导出 Tab：一键全选按钮（用 Button 模拟复选框，文字前缀 ☐/☑） */
    private Button selectAllBtn;
    /** 导入 Tab：一键全选按钮（用 Button 模拟复选框） */
    private Button selectAllImportBtn;
    /** 导出 Tab：醒目"是否保留原来的女仆在世界中"开关（Button 模拟复选框，默认开启=保留） */
    private Button keepMaidsBtn;
    /** 导出 Tab 全选状态 */
    private boolean selectAllState;
    /** 导入 Tab 全选状态 */
    private boolean selectAllImportState;
    /** 导出后是否保留原女仆在世界：true=保留（默认开启，友好），false=不保留 */
    private boolean keepMaidsInWorldState;
    /** 当前导出 Tab 被勾选的女仆 entityId（Set 方便 O(1) 查询、去重） */
    private final Set<Integer> selectedMaidIds = new HashSet<>();
    /** 当前导入 Tab 被勾选的文件名（Set 方便 O(1) 查询、去重） */
    private final Set<String> selectedImportFileNames = new HashSet<>();
    private int panelX, panelY, panelW, panelH;

    public MaidFileManagerScreen() {
        super(Component.translatable("maid_file_manager.gui.title"));
    }

    @Override
    protected void init() {
        super.init();
        panelW = Math.min(PANEL_MAX_W, this.width - MARGIN * 2);
        panelH = this.height - MARGIN * 2;
        panelX = (this.width - panelW) / 2;
        panelY = MARGIN;

        // =============================================================
        // 【需求】主界面宽度砍一砍
        //   L1 标题+关闭:      panelY+10 ~ panelY+30
        //   L2 Tab 栏（原位）:  panelY+36 ~ panelY+58    导出/导入 Tab 居中 + 刷新贴右
        //   L3 主体分两栏:      panelY+66 开始
        //        左 SIDEBAR_W:  竖排工具按钮（全选 / 保留女仆 / 打开文件夹），高 20，gap=8
        //        右 列表区:      直插到底部动作上方 —— 最大化列表高度（核心解决列表看不见）
        //   L6 底部动作 + FB:   panelY+panelH-46 ~ panelY+panelH-20 （action 宽 280 居中）
        // =============================================================
        int tabY = panelY + 36;
        int tabH = 20;
        int tabW = Math.min(86, panelW / 5);
        int tabGap = 10;
        int totalTabW = tabW * 2 + tabGap;
        int refreshW = 58;
        int refreshX = panelX + panelW - refreshW - 8;
        int tabStartX = panelX + (panelW - totalTabW) / 2;
        // 避让：Tab 组贴太右就左移一点，避免和刷新按钮水平挤字
        int tabEndX = tabStartX + totalTabW;
        if (tabEndX > refreshX - 10) {
            tabStartX -= (tabEndX - (refreshX - 10));
        }
        exportTabBtn = Button.builder(Component.translatable("maid_file_manager.gui.tab.export"),
                        b -> switchTab(Tab.EXPORT))
                .bounds(tabStartX, tabY, tabW, tabH).build();
        importTabBtn = Button.builder(Component.translatable("maid_file_manager.gui.tab.import"),
                        b -> switchTab(Tab.IMPORT))
                .bounds(tabStartX + tabW + tabGap, tabY, tabW, tabH).build();
        refreshBtn = Button.builder(Component.translatable("maid_file_manager.gui.button.refresh"),
                        b -> refreshCurrentTab())
                .bounds(refreshX, tabY, refreshW, tabH).build();
        closeBtn = Button.builder(Component.translatable("maid_file_manager.gui.button.close"),
                        b -> onClose())
                .bounds(panelX + panelW - 34, panelY + 4, 30, 14).build();
        addRenderableWidget(exportTabBtn);
        addRenderableWidget(importTabBtn);
        addRenderableWidget(refreshBtn);
        addRenderableWidget(closeBtn);

        // ============= 左侧工具列（竖排堆 4 个按钮，export 3 / import 2 共用左侧 X/W） =============
        int sideX = panelX + 6;                           // 工具列左侧内边距
        int sideBtnW = SIDEBAR_W - 12;                    // 工具按钮统一宽（比列宽窄一点美观）
        int toolBtnH = 20;
        int rowGap = 8;
        int sideY1 = panelY + HEADER_TOTAL_H + 2;         // 第 1 行：全选
        int sideY2 = sideY1 + toolBtnH + rowGap;          // 第 2 行：保留女仆（仅 EXPORT） / 空
        int sideY3 = sideY2 + toolBtnH + rowGap;          // 第 3 行：打开导出/导入文件夹

        // 行 1：全选（导出/导入 各一，共用 X/Y，Tab 切换 visible）
        selectAllState = false;
        selectAllBtn = Button.builder(selectAllLabel(selectAllState),
                        b -> { selectAllState = !selectAllState; onSelectAllToggled(selectAllState); refreshSelectAllLabel(); })
                .bounds(sideX, sideY1, sideBtnW, toolBtnH).build();
        selectAllImportState = false;
        selectAllImportBtn = Button.builder(selectAllLabel(selectAllImportState),
                        b -> { selectAllImportState = !selectAllImportState; onSelectAllImportToggled(selectAllImportState); refreshSelectAllImportLabel(); })
                .bounds(sideX, sideY1, sideBtnW, toolBtnH).build();
        selectAllImportBtn.visible = false;
        selectAllImportBtn.active = false;

        // 行 2：保留女仆按钮（仅 EXPORT Tab）
        int keepBtnW = sideBtnW;
        keepMaidsInWorldState = true;
        keepMaidsBtn = Button.builder(keepMaidsLabel(keepMaidsInWorldState),
                        b -> { keepMaidsInWorldState = !keepMaidsInWorldState; refreshKeepMaidsLabel(); })
                .bounds(sideX, sideY2, keepBtnW, toolBtnH).build();

        // 行 3：打开导出/导入文件夹
        openExportDirBtn = Button.builder(Component.literal("📁 打开导出文件夹"),
                        b -> onOpenExportDir())
                .bounds(sideX, sideY3, sideBtnW, toolBtnH).build();
        openImportDirBtn = Button.builder(
                        Component.translatable("maid_file_manager.gui.button.open_import_dir"),
                        b -> onOpenImportDir())
                .bounds(sideX, sideY3, sideBtnW, toolBtnH).build();

        // ============= 右侧列表区（最大化高度，核心解决"列表看不到了"） =============
        int listAreaX = sideX + SIDEBAR_W + INNER_GAP;
        int listAreaW = panelX + panelW - 8 - listAreaX;
        int listTop = panelY + HEADER_TOTAL_H;
        int listBottom = panelY + panelH - 56;
        // 兜底：极低分辨率 GUI scale=3 1280×732 时 panelH 只有 184px，列表高度必须 ≥80 才能塞 3 行女仆
        if (listBottom - listTop < 80) listBottom = listTop + 80;
        maidListWidget = new MaidListWidget(listAreaX, listAreaW, listTop, listBottom);
        fileListWidget = new FileListWidget(listAreaX, listAreaW, listTop, listBottom);
        updateListVisibility();

        addRenderableWidget(selectAllBtn);
        addRenderableWidget(keepMaidsBtn);
        addRenderableWidget(selectAllImportBtn);
        addRenderableWidget(openExportDirBtn);
        addRenderableWidget(openImportDirBtn);

        // ============= 底部主动作按钮（宽 280，高 20） =============
        actionBtn = Button.builder(Component.empty(), this::onActionButtonClick).build();
        updateActionButtonText();
        addRenderableWidget(actionBtn);
        updateBottomButtonLayout();
        updateTabSpecificControlsVisibility();

        IMaidFileNetwork.ClientHandlerHolder.set(this);
        refreshCurrentTab();
    }

    private void switchTab(Tab tab) {
        if (currentTab == tab) return;
        currentTab = tab;
        updateListVisibility();
        updateActionButtonText();
        updateTabSpecificControlsVisibility();
        updateBottomButtonLayout();
        refreshCurrentTab();
    }

    /** 简化：底部永远居中一个「宽 280」主动作按钮（跨版本不用 setHeight） */
    private void updateBottomButtonLayout() {
        int actionW = 280;
        int actionH = 20;
        if (actionW > panelW - 44) actionW = panelW - 44;   // 极窄屏兜底
        int btnY = panelY + panelH - actionH - 22;
        int centerX = panelX + Math.max(0, (panelW - actionW) / 2);
        actionBtn.setX(centerX);
        actionBtn.setY(btnY);
        actionBtn.setWidth(actionW);
        // 高度维持默认，不调用 setHeight
    }

    // ---------- Button 模拟复选框的 label 生成与刷新 ----------
    private static Component selectAllLabel(boolean selected) {
        String prefix = selected ? "☑ " : "☐ ";
        return Component.literal(prefix + "全选");
    }
    /** 保留女仆按钮：默认开启=保留原女仆在世界（友好默认）。 */
    private static Component keepMaidsLabel(boolean keep) {
        String prefix = keep ? "☑ " : "☐ ";
        String txt = keep ? "保留原女仆在世界（默认开启）" : "保留原女仆在世界";
        // 金色系：与 UI 木色/标题金色协调，醒目但不刺眼
        return Component.literal(prefix + txt).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
    }
    private void refreshSelectAllLabel() {
        if (selectAllBtn != null) selectAllBtn.setMessage(selectAllLabel(selectAllState));
    }
    private void refreshSelectAllImportLabel() {
        if (selectAllImportBtn != null) selectAllImportBtn.setMessage(selectAllLabel(selectAllImportState));
    }
    private void refreshKeepMaidsLabel() {
        if (keepMaidsBtn != null) keepMaidsBtn.setMessage(keepMaidsLabel(keepMaidsInWorldState));
    }

    /** 根据当前 Tab 显示/隐藏 Tab 专属控件：
     *  导出 Tab = 全选 + 保留女仆 + 打开导出文件夹
     *  导入 Tab = 全选 + 打开导入文件夹
     */
    private void updateTabSpecificControlsVisibility() {
        boolean showExport = (currentTab == Tab.EXPORT);
        boolean showImport = (currentTab == Tab.IMPORT);
        // 工具栏①
        selectAllBtn.visible = showExport;
        selectAllBtn.active = showExport;
        keepMaidsBtn.visible = showExport;
        keepMaidsBtn.active = showExport;
        selectAllImportBtn.visible = showImport;
        selectAllImportBtn.active = showImport;
        // 工具栏②（打开文件夹按钮）
        openExportDirBtn.visible = showExport;
        openExportDirBtn.active = showExport;
        openImportDirBtn.visible = showImport;
        openImportDirBtn.active = showImport;
    }

    /** 一键全选被点击时，同步 selectedMaidIds 和每行的选中状态 */
    private void onSelectAllToggled(boolean selected) {
        selectedMaidIds.clear();
        if (selected) {
            for (MaidInfo info : maidList) {
                selectedMaidIds.add(info.entityId());
            }
        }
        maidListWidget.refresh();
        Constants.LOG.info("[maid_file_manager] selectAll toggled: {} selected={}", selected ? "ON" : "OFF", selectedMaidIds.size());
    }

    /** 切换某一行的复选框状态（被 MaidListWidget.Entry 调用） */
    private void toggleRowSelection(MaidInfo info, boolean selected) {
        if (selected) {
            selectedMaidIds.add(info.entityId());
        } else {
            selectedMaidIds.remove(info.entityId());
        }
        // 同步全选按钮状态（若全选就打勾，反之取消）
        boolean allSelected = !maidList.isEmpty() && selectedMaidIds.size() >= maidList.size();
        if (selectAllState != allSelected) {
            selectAllState = allSelected;
            refreshSelectAllLabel();
        }
    }

    /** 导入 Tab 一键全选被点击时，同步 selectedImportFileNames 和每行选中状态 */
    private void onSelectAllImportToggled(boolean selected) {
        selectedImportFileNames.clear();
        if (selected) {
            selectedImportFileNames.addAll(fileList);
        }
        fileListWidget.refresh();
        Constants.LOG.info("[maid_file_manager] import selectAll toggled: {} selected={}",
                selected ? "ON" : "OFF", selectedImportFileNames.size());
    }

    /** 切换导入文件某一行的复选框状态（被 FileListWidget.Entry 调用） */
    private void toggleFileRowSelection(String fileName, boolean selected) {
        if (selected) {
            selectedImportFileNames.add(fileName);
        } else {
            selectedImportFileNames.remove(fileName);
        }
        // 同步导入 Tab 全选按钮状态
        boolean allSelected = !fileList.isEmpty() && selectedImportFileNames.size() >= fileList.size();
        if (selectAllImportState != allSelected) {
            selectAllImportState = allSelected;
            refreshSelectAllImportLabel();
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

    /** 打开导出文件夹（新增，跟 onOpenImportDir 对称实现） */
    private void onOpenExportDir() {
        try {
            File dir = new File(this.minecraft.gameDirectory, Constants.MAID_EXPORTS_DIR);
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
            Constants.LOG.info("[maid_file_manager] Opened export dir: {}", dir.getAbsolutePath());
        } catch (Exception e) {
            Constants.LOG.error("[maid_file_manager] Failed to open export dir", e);
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
                // 清理已失效的勾选（文件名不在新列表里的移除）
                selectedImportFileNames.removeIf(fn -> !files.contains(fn));
                // 同步导入全选状态
                boolean allSelected = !fileList.isEmpty() && selectedImportFileNames.size() >= fileList.size();
                if (selectAllImportState != allSelected) {
                    selectAllImportState = allSelected;
                    refreshSelectAllImportLabel();
                }
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
            if (selectedMaidIds.isEmpty()) {
                setFeedback(Component.translatable("maid_file_manager.gui.export.no_selection"));
                return;
            }
            List<Integer> ids = new ArrayList<>(selectedMaidIds);
            // keep=true → 保留原女仆（不移除）；keep=false → 导出后移除
            boolean removeAfter = !keepMaidsInWorldState;
            Constants.LOG.info("[maid_file_manager] EXPORT START: count={}, removeAfter={}", ids.size(), removeAfter);
            setFeedback(Component.translatable("maid_file_manager.gui.export.exporting_multi", ids.size()));
            net.sendExportMaids(ids, removeAfter);
        } else {
            // 导入 Tab：批量导入（取 selectedImportFileNames）
            if (selectedImportFileNames.isEmpty()) {
                setFeedback(Component.translatable("maid_file_manager.gui.import.no_selection"));
                return;
            }
            List<MaidFileData> dataList = new ArrayList<>();
            int skipped = 0;
            try {
                Path gameDir = this.minecraft.gameDirectory.toPath().toAbsolutePath();
                Path dir = MaidFileIo.ensureImportsDir(gameDir);
                for (String fileName : selectedImportFileNames) {
                    Path file = dir.resolve(fileName).normalize();
                    if (!file.startsWith(dir)) {
                        skipped++;
                        continue;
                    }
                    MaidFileData data = MaidFileIo.readMaidFile(file);
                    if (data == null || data.getData() == null) {
                        skipped++;
                        continue;
                    }
                    dataList.add(data);
                }
            } catch (Exception e) {
                Constants.LOG.error("[maid_file_manager] Failed to batch read import files", e);
                setFeedback(Component.translatable("maid_file_manager.import.fail.exception", e.getMessage()));
                return;
            }
            if (dataList.isEmpty()) {
                setFeedback(Component.translatable("maid_file_manager.import.fail.exception", "没有可导入的有效文件"));
                return;
            }
            setFeedback(Component.literal(String.format(Locale.ROOT,
                    "正在批量导入 %d 个女仆（已跳过 %d 个无效文件）...", dataList.size(), skipped)));
            Constants.LOG.info("[maid_file_manager] BATCH IMPORT START: count={}, skipped={}", dataList.size(), skipped);
            net.sendImportFiles(dataList);
        }
    }

    public void renderBackground(GuiGraphics graphics) {
        // 1.21 Screen 有两套 renderBackground：单参（空）和 4 参。super.render() 走 4 参版本，
        // 4 参版本默认会调用 renderDirtBackground + 叠加 GUI blur shader。
        // 我们在 4 参版本里直接画不透明深色底，彻底打断 blur/泥土背景绘制链路。
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 注意：Screen.super.render() 内部会再次调用这个 4 参 renderBackground，
        // 如果这里再 fill 全屏底色，会把我们 render() 开头已画好的面板/标题覆盖掉。
        // 因此 4 参版本什么都不做，全屏底 + 面板统一在 render() 开头一次性绘制，
        // 同时不调用 super.renderBackground → 打断默认泥土背景 + GUI blur shader。
    }

    public void renderDirtBackground(GuiGraphics graphics) {
        // 彻底覆盖：不绘制泥土背景
    }

    // 1.21 Mojang 官方映射里暂停判断方法从 isPauseScreen 改名为 returnsPauseScreen。
    // 我们工程基于官方映射编译，而 TLM 源码使用 Parchment 映射仍保留 isPauseScreen，
    // 所以这里直接覆写 returnsPauseScreen，不加 @Override 兜底防止映射版本差异报错
    @SuppressWarnings("unused")
    public boolean returnsPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // ① 先画全屏不透明深色底（把游戏世界盖死 → blur shader 没有世界背景可"糊"，相当于打断 GUI blur 视觉）
        graphics.fill(0, 0, this.width, this.height, SCREEN_BG);
        // ② 再画主面板（在全屏底之上，子组件之下 → 不会被 super.render 里的 renderBackground 4 参覆盖）
        drawPanel(graphics, panelX, panelY, panelW, panelH);
        // ③ 标题（强制 shadow=false，避免金色字被阴影糊成一团）
        drawCenteredStringNoShadow(graphics, this.font,
                Component.translatable("maid_file_manager.gui.title"),
                this.width / 2, panelY + 10, HEADER_COLOR);
        // ④ 当前 Tab 下划线
        Button activeTab = currentTab == Tab.EXPORT ? exportTabBtn : importTabBtn;
        graphics.fill(activeTab.getX(), activeTab.getY() + activeTab.getHeight(),
                activeTab.getX() + activeTab.getWidth(), activeTab.getY() + activeTab.getHeight() + 2,
                0xFFFFD700);
        // ---------- "是否保留女仆"按钮：仅 Tab 下划线 + 原生 MC 阴影（自定义金黄外框叠阴影变形 → 彻底删除） ----------
        // 子组件
        super.render(graphics, mouseX, mouseY, partialTick);
        // ⑥ 反馈消息
        if (!feedbackMsg.getString().isEmpty() && System.currentTimeMillis() < feedbackExpireAt) {
            int fbY = panelY + panelH - 56 + 6;
            drawCenteredStringNoShadow(graphics, this.font, feedbackMsg, this.width / 2, fbY, 0xFFFFAA00);
        }
        // ⑦ Hover tooltip
        drawHoverTooltip(graphics, mouseX, mouseY);
    }

    /** 1.21 GuiGraphics.drawCenteredString 默认 dropShadow=true，阴影会把彩色字糊成一团。
     *  这里手动计算居中 + drawString(Component, ..., shadow=false) 得到干净的文字。 */
    private static void drawCenteredStringNoShadow(GuiGraphics g, net.minecraft.client.gui.Font font,
                                                   Component text, int centerX, int y, int color) {
        int w = font.width(text);
        g.drawString(font, text, centerX - w / 2, y, color, false);
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
        // 清理已失效的勾选（entityId 不在新列表里的移除）
        selectedMaidIds.removeIf(id -> list.stream().noneMatch(info -> info.entityId() == id));
        // 同步全选状态
        boolean allSelected = !maidList.isEmpty() && selectedMaidIds.size() >= maidList.size();
        if (selectAllState != allSelected) {
            selectAllState = allSelected;
            refreshSelectAllLabel();
        }
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
    public void onExportResultReceived(List<MaidFileData> dataList) {
        if (dataList == null || dataList.isEmpty()) {
            setFeedback(Component.translatable("maid_file_manager.export.fail", "导出数据为空"));
            return;
        }
        int success = 0;
        int failed = 0;
        String lastFileName = null;
        try {
            Path gameDir = this.minecraft.gameDirectory.toPath().toAbsolutePath();
            Path dir = MaidFileIo.ensureExportsDir(gameDir);
            for (MaidFileData data : dataList) {
                if (data == null) {
                    failed++;
                    continue;
                }
                String displayName = data.getDisplayName() != null
                        ? data.getDisplayName()
                        : (data.getModelId() != null ? data.getModelId() : "unknown");
                String ownerUuid = data.getOwnerUuid();
                String fileName = MaidFileIo.writeMaidFile(dir, displayName, data.getModelId(),
                        ownerUuid, data, LocalDateTime.now());
                lastFileName = fileName;
                success++;
                Constants.LOG.info("[maid_file_manager] Export saved locally: {}", dir.resolve(fileName));
            }
        } catch (Exception e) {
            Constants.LOG.error("[maid_file_manager] Failed to save export file locally", e);
            setFeedback(Component.translatable("maid_file_manager.export.fail", e.getMessage()));
            return;
        }

        Component fb;
        if (failed == 0) {
            String msg = String.format(Locale.ROOT,
                    "成功导出 %d 个女仆！文件已保存到 maid_exports/%s",
                    success, (success == 1 ? (lastFileName != null ? lastFileName : "") : "…"));
            fb = Component.literal(msg);
            Constants.LOG.info("[maid_file_manager] Batch export SUCCESS: count={}, keep={}", success, keepMaidsInWorldState);
        } else {
            fb = Component.literal(String.format(Locale.ROOT,
                    "导出完成：成功 %d，失败 %d。详见 maid_exports/", success, failed));
            Constants.LOG.warn("[maid_file_manager] Batch export PARTIAL: ok={} fail={}", success, failed);
        }
        setFeedback(fb);
        if (this.minecraft.player != null) {
            this.minecraft.player.displayClientMessage(fb, false);
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

    // ---------- 跨版本滚动条修复：反射强制设置父类字段，彻底屏蔽 super() 参数顺序差异 ----------
    // NeoForge 1.21.x 使用 Mojang 官方映射，字段名是 width/left/right/top/bottom/height/y0/y1/x0/x1
    private static final String[] SRG_WIDTH   = { "width" };
    private static final String[] SRG_HEIGHT  = { "height" };
    private static final String[] SRG_TOP     = { "top", "y0" };
    private static final String[] SRG_BOTTOM  = { "bottom", "y1" };
    private static final String[] SRG_RIGHT   = { "right", "x1" };
    private static final String[] SRG_LEFT    = { "left", "x0" };

    private static void trySetFieldMulti(Object obj, String[] names, Object value) {
        for (String n : names) trySetField(obj, n, value);
    }

    private static void trySetField(Object obj, String fieldName, Object value) {
        Class<?> c = obj.getClass();
        for (int i = 0; i < 6; i++) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(obj, value);
                return;
            } catch (Exception ignore) { /* 继续往父类找 */ }
            c = c.getSuperclass();
            if (c == null || c == Object.class) return;
        }
    }

    // ---------- MaidListWidget ----------

    private final class MaidListWidget extends ObjectSelectionList<MaidListWidget.Entry> {
        private final int listX, listW, listTop, listBottom;

        MaidListWidget(int listX, int listW, int listTop, int listBottom) {
            super(MaidFileManagerScreen.this.minecraft, listW,
                    listBottom - listTop, listTop, ROW_HEIGHT);
            this.listX = listX;
            this.listW = listW;
            this.listTop = listTop;
            this.listBottom = listBottom;
            // ====== Neo 独有 AbstractWidget API：先设尺寸，再设位置（避免设位置时父类依赖 width/height 把 right 算错） ======
            this.setHeight(listBottom - listTop);
            this.setY(listTop);
            this.setWidth(listW);
            this.setX(listX);
            // ====== 反射兜底（严格顺序：top/bottom → height → width → right → left → 锚定 width+right 两遍） ======
            trySetFieldMulti(this, SRG_TOP, listTop);
            trySetFieldMulti(this, SRG_BOTTOM, listBottom);
            trySetFieldMulti(this, SRG_HEIGHT, listBottom - listTop);
            trySetFieldMulti(this, SRG_WIDTH, listW);
            trySetFieldMulti(this, SRG_RIGHT, listX + listW);
            trySetFieldMulti(this, SRG_LEFT, listX);
            trySetFieldMulti(this, SRG_WIDTH, listW); trySetFieldMulti(this, SRG_RIGHT, listX + listW); // 锚定两遍
            refresh();
        }

        @Override
        public int getRowWidth() {
            return this.listW - 8;
        }

        @Override
        protected void renderListBackground(GuiGraphics graphics) {
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

        // ====== v13 交互兜底：完全绕开父类 getEntryAtPosition 的 rowLeft 旧公式 ======
        // 第一层 Widget 范围命中：LIST_BG ± 12 像素（父类 scissor 放宽 12px）
        @Override
        public boolean isMouseOver(double mouseX, double mouseY) {
            return mouseX >= this.listX - 12 && mouseX <= this.listX + this.listW + 12
                    && mouseY >= this.listTop - 2 && mouseY <= this.listBottom + 2;
        }

        // 第二层 Entry 命中：自遍历 Entry.isMouseOver（我们 Entry 已经判 LIST_BG 矩形）
        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (!this.isMouseOver(mouseX, mouseY)) return false;
            for (Entry entry : this.children()) {
                if (entry.isMouseOver(mouseX, mouseY)) {
                    if (entry.mouseClicked(mouseX, mouseY, button)) {
                        this.setSelected(entry);
                        return true;
                    }
                }
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            for (Entry entry : this.children()) {
                if (entry.isMouseOver(mouseX, mouseY)) {
                    entry.mouseReleased(mouseX, mouseY, button);
                }
            }
            return super.mouseReleased(mouseX, mouseY, button);
        }

        // NeoForge 1.21.x mouseScrolled 4 参：horizontal + vertical
        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
            if (!this.isMouseOver(mouseX, mouseY)) return false;
            return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
        }

        final class Entry extends ObjectSelectionList.Entry<Entry> {
            final MaidInfo info;
            // 修复 v1.1.2 UI bug：反馈「点哪个女仆都只能选中第一个」
            // 根因：isMouseOver 只检查 mouseX 不检查 mouseY，导致所有 entry 都返回 true → mouseClicked 遍历 children() 时第一个命中
            // 修复：移植 1.20.1 的 lastTop/lastHeight 机制，render 时记录自己的行 top/height，isMouseOver 用它限定 mouseY 范围
            int lastTop = -1;
            int lastHeight = -1;

            Entry(MaidInfo info) {
                this.info = info;
            }

            @Override
            public void render(GuiGraphics graphics, int index, int top, int left,
                               int width, int height, int mouseX, int mouseY, boolean hovering,
                               float partialTick) {
                this.lastTop = top;
                this.lastHeight = height;
                boolean selected = selectedMaidIds.contains(info.entityId());
                int rowX = MaidListWidget.this.listX;
                int rowW = MaidListWidget.this.listW;
                if (selected) {
                    graphics.fill(rowX, top - 1, rowX + rowW, top + height + 1, ROW_SELECTED);
                } else if (hovering) {
                    graphics.fill(rowX, top - 1, rowX + rowW, top + height + 1, ROW_HOVER);
                }
                // 勾选框 + 文字：rowX 对齐 LIST_BG 左边缘
                String mark = selected ? "☑" : "☐";
                int markColor = selected ? 0xFFFFD700 : TEXT_COLOR;
                graphics.drawString(MaidFileManagerScreen.this.font,
                        Component.literal(mark), rowX + 28, top + 5, markColor, false);
                int textLeft = rowX + 49;
                int contentRight = rowX + rowW - 10;
                int contentW = contentRight - textLeft;
                net.minecraft.client.gui.Font font = MaidFileManagerScreen.this.font;
                // 第一行：name 超长 → 截断+省略号…（不滚避免勾选框右晃动）
                String name = info.displayName();
                String drawName;
                if (font.width(name) > contentW) {
                    drawName = font.plainSubstrByWidth(name, contentW - font.width("…")) + "…";
                } else {
                    drawName = name;
                }
                graphics.drawString(font, Component.literal(drawName), textLeft, top + 2, TEXT_COLOR, false);
                // 第二行：detail 超长 → 左右自动跑马灯（1.2s头停→向左滚60px/s→1.2s尾停→400ms快速回卷）
                String detail = String.format(Locale.ROOT, "%s  |  好感:%d  HP:%.0f/%.0f  %s",
                        info.modelId(), info.favorability(), info.health(), info.maxHealth(),
                        info.tamed() ? "已驯服" : "未驯服");
                int detailW = font.width(detail);
                if (detailW <= contentW) {
                    graphics.drawString(font, Component.literal(detail), textLeft, top + 12, SUBTEXT_COLOR, false);
                } else {
                    final int pauseMs = 1200;
                    final int scrollMs = (int)((detailW - contentW) * 1000L / 60L); // 60px/s
                    final int rewindMs = 400;
                    final int cycleMs = pauseMs + scrollMs + pauseMs + rewindMs;
                    long t = System.currentTimeMillis() % cycleMs;
                    int offset;
                    if (t < pauseMs) {
                        offset = 0;
                    } else if (t < pauseMs + scrollMs) {
                        long tt = t - pauseMs;
                        offset = (int)(tt * 60L / 1000L);
                    } else if (t < pauseMs + scrollMs + pauseMs) {
                        offset = detailW - contentW;
                    } else {
                        long tt = t - (pauseMs + scrollMs + pauseMs);
                        offset = (int)((detailW - contentW) * (1L - tt * 1000L / (rewindMs * 1000L)));
                        if (offset < 0) offset = 0;
                    }
                    graphics.enableScissor(textLeft, top + 10, contentRight, top + 22);
                    graphics.drawString(font, Component.literal(detail), textLeft - offset, top + 12, SUBTEXT_COLOR, false);
                    graphics.disableScissor();
                }
            }

            @Override
            public boolean isMouseOver(double mouseX, double mouseY) {
                int rowX = MaidListWidget.this.listX;
                int rowW = MaidListWidget.this.listW;
                return mouseX >= rowX && mouseX <= rowX + rowW
                        && this.lastTop >= 0
                        && mouseY >= this.lastTop - 2
                        && mouseY <= this.lastTop + this.lastHeight + 2;
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                boolean newVal = !selectedMaidIds.contains(info.entityId());
                toggleRowSelection(info, newVal);
                return true;
            }

            @Override
            public Component getNarration() {
                boolean sel = selectedMaidIds.contains(info.entityId());
                return Component.literal((sel ? "☑ " : "☐ ") + info.displayName());
            }
        }
    }

    // ---------- FileListWidget ----------

    private final class FileListWidget extends ObjectSelectionList<FileListWidget.Entry> {
        private final int listX, listW, listTop, listBottom;

        FileListWidget(int listX, int listW, int listTop, int listBottom) {
            super(MaidFileManagerScreen.this.minecraft, listW,
                    listBottom - listTop, listTop, ROW_HEIGHT);
            this.listX = listX;
            this.listW = listW;
            this.listTop = listTop;
            this.listBottom = listBottom;
            this.setHeight(listBottom - listTop);
            this.setY(listTop);
            this.setWidth(listW);
            this.setX(listX);
            trySetFieldMulti(this, SRG_TOP, listTop);
            trySetFieldMulti(this, SRG_BOTTOM, listBottom);
            trySetFieldMulti(this, SRG_HEIGHT, listBottom - listTop);
            trySetFieldMulti(this, SRG_WIDTH, listW);
            trySetFieldMulti(this, SRG_RIGHT, listX + listW);
            trySetFieldMulti(this, SRG_LEFT, listX);
            trySetFieldMulti(this, SRG_WIDTH, listW); trySetFieldMulti(this, SRG_RIGHT, listX + listW);
            refresh();
        }

        @Override
        public int getRowWidth() {
            return this.listW - 8;
        }

        void refresh() {
            clearEntries();
            for (String fileName : fileList) {
                addEntry(new Entry(fileName));
            }
        }

        @Override
        protected void renderListBackground(GuiGraphics graphics) {
            graphics.fill(this.listX, this.listTop,
                    this.listX + this.listW, this.listBottom, LIST_BG);
        }

        // ====== v13 交互兜底：同 MaidListWidget（绕开父类 getEntryAtPosition） ======
        @Override
        public boolean isMouseOver(double mouseX, double mouseY) {
            return mouseX >= this.listX - 12 && mouseX <= this.listX + this.listW + 12
                    && mouseY >= this.listTop - 2 && mouseY <= this.listBottom + 2;
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (!this.isMouseOver(mouseX, mouseY)) return false;
            for (Entry entry : this.children()) {
                if (entry.isMouseOver(mouseX, mouseY)) {
                    if (entry.mouseClicked(mouseX, mouseY, button)) {
                        this.setSelected(entry);
                        return true;
                    }
                }
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            for (Entry entry : this.children()) {
                if (entry.isMouseOver(mouseX, mouseY)) {
                    entry.mouseReleased(mouseX, mouseY, button);
                }
            }
            return super.mouseReleased(mouseX, mouseY, button);
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
            if (!this.isMouseOver(mouseX, mouseY)) return false;
            return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
        }

        public Entry getHovered(double mouseX, double mouseY) {
            return this.getEntryAtPosition(mouseX, mouseY);
        }

        final class Entry extends ObjectSelectionList.Entry<Entry> {
            final String fileName;
            // 修复 v1.1.2 UI bug：与 MaidListWidget.Entry 同款问题，isMouseOver 必须用 lastTop/lastHeight 限定 mouseY 范围
            int lastTop = -1;
            int lastHeight = -1;

            Entry(String fileName) {
                this.fileName = fileName;
            }

            @Override
            public void render(GuiGraphics graphics, int index, int top, int left,
                               int width, int height, int mouseX, int mouseY, boolean hovering,
                               float partialTick) {
                this.lastTop = top;
                this.lastHeight = height;
                boolean selected = selectedImportFileNames.contains(fileName);
                int rowX = FileListWidget.this.listX;
                int rowW = FileListWidget.this.listW;
                if (selected) {
                    graphics.fill(rowX, top - 1, rowX + rowW, top + height + 1, ROW_SELECTED);
                } else if (hovering) {
                    graphics.fill(rowX, top - 1, rowX + rowW, top + height + 1, ROW_HOVER);
                }
                // 勾选框：rowX 对齐 LIST_BG 左边缘
                String mark = selected ? "☑" : "☐";
                int markColor = selected ? 0xFFFFD700 : TEXT_COLOR;
                graphics.drawString(MaidFileManagerScreen.this.font,
                        Component.literal(mark), rowX + 28, top + 5, markColor, false);
                int textLeft = rowX + 49;
                int contentRight = rowX + rowW - 10;
                int contentW = contentRight - textLeft;
                net.minecraft.client.gui.Font font = MaidFileManagerScreen.this.font;
                // 第一行：fileName 超长 → 左右跑马灯（文件名很长必须全显示）
                int nameW = font.width(fileName);
                if (nameW <= contentW) {
                    graphics.drawString(font, Component.literal(fileName), textLeft, top + 2, TEXT_COLOR, false);
                } else {
                    final int pauseMs = 1200;
                    final int scrollMs = (int)((nameW - contentW) * 1000L / 60L);
                    final int rewindMs = 400;
                    final int cycleMs = pauseMs + scrollMs + pauseMs + rewindMs;
                    long t = System.currentTimeMillis() % cycleMs;
                    int offset;
                    if (t < pauseMs) {
                        offset = 0;
                    } else if (t < pauseMs + scrollMs) {
                        long tt = t - pauseMs;
                        offset = (int)(tt * 60L / 1000L);
                    } else if (t < pauseMs + scrollMs + pauseMs) {
                        offset = nameW - contentW;
                    } else {
                        long tt = t - (pauseMs + scrollMs + pauseMs);
                        float p = (float)tt / (float)rewindMs;
                        if (p > 1.0f) p = 1.0f;
                        offset = (int)((nameW - contentW) * (1.0f - p));
                        if (offset < 0) offset = 0;
                    }
                    graphics.enableScissor(textLeft, top, contentRight, top + 12);
                    graphics.drawString(font, Component.literal(fileName), textLeft - offset, top + 2, TEXT_COLOR, false);
                    graphics.disableScissor();
                }
                // 第二行：时间超长 → 截断+省略号…（时间格式固定，不滚）
                long ts = MaidFileIo.parseTimestampFromFileName(fileName);
                String time = ts > 0 ? new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(ts)) : "?";
                String timeLine = "时间: " + time;
                String drawTime;
                if (font.width(timeLine) > contentW) {
                    drawTime = font.plainSubstrByWidth(timeLine, contentW - font.width("…")) + "…";
                } else {
                    drawTime = timeLine;
                }
                graphics.drawString(font, Component.literal(drawTime), textLeft, top + 12, SUBTEXT_COLOR, false);
            }

            @Override
            public boolean isMouseOver(double mouseX, double mouseY) {
                int rowX = FileListWidget.this.listX;
                int rowW = FileListWidget.this.listW;
                return mouseX >= rowX && mouseX <= rowX + rowW
                        && this.lastTop >= 0
                        && mouseY >= this.lastTop - 2
                        && mouseY <= this.lastTop + this.lastHeight + 2;
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                boolean newVal = !selectedImportFileNames.contains(fileName);
                toggleFileRowSelection(fileName, newVal);
                return true;
            }

            @Override
            public Component getNarration() {
                boolean sel = selectedImportFileNames.contains(fileName);
                return Component.literal((sel ? "☑ " : "☐ ") + fileName);
            }
        }
    }
}
