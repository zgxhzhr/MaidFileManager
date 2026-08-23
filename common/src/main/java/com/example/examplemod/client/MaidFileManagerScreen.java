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
    /** 主面板宽度砍一砍（用户反馈太宽太乱） */
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
    /** 导出 Tab 工具栏②：打开导出文件夹（用户新增） */
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
        // 【用户要求】主界面宽度砍一砍
        //   L1 标题+关闭:      panelY+8 ~ panelY+30     （关闭 14×14 贴右上）
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

        // 行 1：全选（导出/导入 各一，初始共用 X/Y，Tab 切换控制 visible）
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

        // 行 2：保留女仆按钮（仅 EXPORT Tab 显示；长文案"保留原女仆在世界（默认开启）"让按钮变两行——其实一行 20 高够用，宽 144+刚好）
        int keepBtnW = sideBtnW;
        keepMaidsInWorldState = true;
        keepMaidsBtn = Button.builder(keepMaidsLabel(keepMaidsInWorldState),
                        b -> { keepMaidsInWorldState = !keepMaidsInWorldState; refreshKeepMaidsLabel(); })
                .bounds(sideX, sideY2, keepBtnW, toolBtnH).build();

        // 行 3：打开导出/导入文件夹（共用左侧第 3 行位置，Tab 切换控制 visible）
        openExportDirBtn = Button.builder(Component.literal("📁 打开导出文件夹"),
                        b -> onOpenExportDir())
                .bounds(sideX, sideY3, sideBtnW, toolBtnH).build();
        openImportDirBtn = Button.builder(
                        Component.translatable("maid_file_manager.gui.button.open_import_dir"),
                        b -> onOpenImportDir())
                .bounds(sideX, sideY3, sideBtnW, toolBtnH).build();

        // ============= 右侧列表区（最大化高度，核心解决"列表看不到了"） =============
        int listAreaX = sideX + SIDEBAR_W + INNER_GAP;     // 列表 X = 工具列右 + 内间距
        int listAreaW = panelX + panelW - 8 - listAreaX;   // 列表宽 = 面板右端点（贴内右边距 8）- 列表左
        int listTop = panelY + HEADER_TOTAL_H;             // 列表顶 = Tab 底对齐（工具按钮允许透明叠在列表左上，最大化高度）
        int listBottom = panelY + panelH - 56;             // 列表底 = 面板底 - 56（底部动作 20 + 间距 10 + feedback 6 + 余量 20 = 56）
        if (listBottom - listTop < 80) listBottom = listTop + 80; // 高度兜底 ≥ 80，GUI 超窄时不压缩到 0
        maidListWidget = new MaidListWidget(listAreaX, listAreaW, listTop, listBottom);
        fileListWidget = new FileListWidget(listAreaX, listAreaW, listTop, listBottom);
        updateListVisibility();

        // ===== 工具列 6 个按钮注册 =====
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

    /** 简化：底部永远居中一个「宽 280」主动作按钮（高 20，Forge 1.20 不调 setHeight） */
    private void updateBottomButtonLayout() {
        int actionW = 280;
        int actionH = 20;
        if (actionW > panelW - 44) actionW = panelW - 44;   // 极窄屏兜底
        int btnY = panelY + panelH - actionH - 22;
        int centerX = panelX + Math.max(0, (panelW - actionW) / 2);
        actionBtn.setX(centerX);
        actionBtn.setY(btnY);
        actionBtn.setWidth(actionW);
        // Forge 1.20 Button API 无 setHeight —— 用 bounds 构造默认高度 20
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

    /** 打开导出文件夹（用户新增，跟 onOpenImportDir 对称实现） */
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
            // keep=true → 保留（不移除）；keep=false → 导出后移除
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
                this.width / 2, panelY + 10, HEADER_COLOR);
        // 当前 Tab 下划线
        Button activeTab = currentTab == Tab.EXPORT ? exportTabBtn : importTabBtn;
        graphics.fill(activeTab.getX(), activeTab.getY() + activeTab.getHeight(),
                activeTab.getX() + activeTab.getWidth(), activeTab.getY() + activeTab.getHeight() + 2,
                0xFFFFD700);
        // ---------- "是否保留女仆"按钮醒目：仅加粗描边（两层阴影叠加视觉变形 → 彻底删除自定义金黄色外框） ----------
        // 子组件
        super.render(graphics, mouseX, mouseY, partialTick);
        // 反馈消息（主动作按钮上方 16，列表区下方）
        if (!feedbackMsg.getString().isEmpty() && System.currentTimeMillis() < feedbackExpireAt) {
            int fbY = panelY + panelH - LIST_BOTTOM_PAD + 6;
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

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---------- 跨版本滚动条修复：反射强制设置父类字段，彻底屏蔽 super() 参数顺序差异 ----------
    /** Forge 1.20.x 6 个核心布局字段的 100% 精准 SRG 名（由 v12 截图遍历结果值匹配铁证定位）：
     *   f_93393_ = left(x0) |  f_93392_ = right(x1)=389=200+189  |  f_93388_ = width=189
     *   f_93389_ = height=80  |  f_93390_ = top(y0)=94  |  f_93391_ = bottom(y1)=174
     *   加旧名/新名双兜底，跨加载器通用。 */
    private static final String[] SRG_WIDTH   = { "f_93388_", "width" };
    private static final String[] SRG_HEIGHT  = { "f_93389_", "height" };
    private static final String[] SRG_TOP     = { "f_93390_", "top", "y0" };
    private static final String[] SRG_BOTTOM  = { "f_93391_", "bottom", "y1" };
    private static final String[] SRG_LEFT    = { "f_93393_", "left", "x0" };
    private static final String[] SRG_RIGHT   = { "f_93392_", "right", "x1" };

    private static void trySetFieldMulti(Object obj, String[] names, Object value) {
        for (String n : names) trySetField(obj, n, value);
    }

    private static void trySetField(Object obj, String fieldName, Object value) {
        Class<?> c = obj.getClass();
        for (int i = 0; i < 8; i++) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(obj, value);
                return;
            } catch (Exception ignore) { /* continue */ }
            c = c.getSuperclass();
            if (c == null || c == Object.class) return;
        }
    }

    /** 对称读字段，用于屏幕左上角可视化调试（找不到返回 -99999） */
    private static int tryGetFieldInt(Object obj, String fieldName) {
        Class<?> c = obj.getClass();
        for (int i = 0; i < 6; i++) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v instanceof Number) return ((Number) v).intValue();
                return -99998;
            } catch (Exception ignore) { /* continue */ }
            c = c.getSuperclass();
            if (c == null || c == Object.class) return -99999;
        }
        return -99997;
    }

    /** 遍历父类 8 层所有 int/float 字段名+值（返回前 14 条画图用，直接定位真实 SRG 字段名） */
    private static String[] tryListAllIntFields(Object obj, int maxLines, String skipPrefix) {
        java.util.List<String> out = new java.util.ArrayList<>();
        Class<?> c = obj.getClass();
        int depth = 0;
        while (c != null && c != Object.class && depth < 8) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    String name = f.getName();
                    if (skipPrefix != null && name.startsWith(skipPrefix)) continue;
                    Class<?> t = f.getType();
                    if (t == int.class || t == Integer.class || t == float.class || t == Float.class) {
                        Object v = f.get(obj);
                        if (v instanceof Float) {
                            float fv = (Float) v;
                            if (fv == Math.floor(fv)) out.add(name + "=" + (int)fv);
                            else out.add(name + "=" + String.format("%.1f", fv));
                        } else {
                            out.add(name + "=" + v);
                        }
                        if (out.size() >= maxLines) return out.toArray(new String[0]);
                    }
                } catch (Exception ignore) {}
            }
            c = c.getSuperclass();
            depth++;
        }
        return out.toArray(new String[0]);
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
            // ====== Fix 根因：删掉 setLeftPos（只改 x0 不改 x1）。反射严格顺序 + 3 重兜底（旧名+新名+真实 SRG f_93388_...） ======
            // 顺序：top/bottom/height → width → right/x1 → left/x0 → 锚定 width+right/x1 两遍
            trySetFieldMulti(this, SRG_TOP, listTop);
            trySetFieldMulti(this, SRG_BOTTOM, listBottom);
            trySetFieldMulti(this, SRG_HEIGHT, listBottom - listTop);
            trySetFieldMulti(this, SRG_WIDTH, listW);
            trySetFieldMulti(this, SRG_RIGHT, listX + listW);
            trySetFieldMulti(this, SRG_LEFT, listX);
            // 最终锚定两遍（防写 left 时内部派生值覆盖 x1/width）
            trySetFieldMulti(this, SRG_WIDTH, listW);
            trySetFieldMulti(this, SRG_RIGHT, listX + listW);
            trySetFieldMulti(this, SRG_WIDTH, listW);
            trySetFieldMulti(this, SRG_RIGHT, listX + listW);
            setRenderBackground(false);
            setRenderTopAndBottom(false);
            refresh();
        }

        /** v12 Fix「点击位置错位=父类 ListWidget.isMouseOver 判 X∈[0,189]（正好是左侧 Tag 区）」：强制按我们自己 LIST_BG ± 滚动条余量判 Widget 层命中，绕开父类 x0/x1 错值 */
        @Override
        public boolean isMouseOver(double mouseX, double mouseY) {
            return mouseX >= this.listX - 12 && mouseX <= this.listX + this.listW + 12
                    && mouseY >= this.listTop - 2 && mouseY <= this.listBottom + 2;
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

        /** v13：父类内部 scissor 已靠 SRG 反射 6 字段 100% 命中拉正 → 删除之前「二次画 Entry」的兜底，只保留 Widget 层 isMouseOver 最后一道防线。
         *  Fix「只有点最左端才能选中」：override mouseClicked 自己遍历 Entry.isMouseOver（我们 LIST_BG 判的，与 Entry.render 同一基准），完全绕开父类 getEntryAtPosition 的 rowLeft 错值计算。 */
        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (!this.isMouseOver(mouseX, mouseY)) return false;
            for (Entry e : this.children()) {
                if (e.isMouseOver(mouseX, mouseY)) {
                    boolean ret = e.mouseClicked(mouseX, mouseY, button);
                    if (ret) this.setSelected(e);
                    return ret || true;
                }
            }
            return false;
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            if (!this.isMouseOver(mouseX, mouseY)) return false;
            for (Entry e : this.children()) {
                if (e.isMouseOver(mouseX, mouseY)) return e.mouseReleased(mouseX, mouseY, button);
            }
            return false;
        }

        @Override
        public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
            return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double vertical) {
            if (!this.isMouseOver(mouseX, mouseY)) return false;
            return super.mouseScrolled(mouseX, mouseY, vertical);
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            // v13：SRG 6 字段反射 100% 命中，父类内部 enableScissor 剪刀完全正确（x0=200 x1=389），直接 super.render 不再二次画
            super.render(graphics, mouseX, mouseY, partialTick);
        }

        void refresh() {
            clearEntries();
            for (MaidInfo info : maidList) {
                addEntry(new Entry(info));
            }
        }

        final class Entry extends ObjectSelectionList.Entry<Entry> {
            final MaidInfo info;
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
                // 高亮背景 & 勾选框 & 文字：永远对齐外层 LIST_BG 的 listX，不依赖父类传入的 left
                int rowX = MaidListWidget.this.listX;
                int rowW = MaidListWidget.this.listW;
                if (selected) {
                    graphics.fill(rowX, top - 1, rowX + rowW, top + height + 1, ROW_SELECTED);
                } else if (hovering) {
                    graphics.fill(rowX, top - 1, rowX + rowW, top + height + 1, ROW_HOVER);
                }
                // ---------- 左侧自绘勾选标记（☐/☑）：父类滚动条在 LIST_BG 左边（宽≈8），这里整体右移 28px 避免与滚动条重叠 ----------
                String mark = selected ? "☑" : "☐";
                int markColor = selected ? 0xFFFFD700 : TEXT_COLOR;
                graphics.drawString(MaidFileManagerScreen.this.font,
                        mark, rowX + 28, top + 5, markColor, false);

                // ---------- 女仆信息（从勾选标记右侧 21 像素开始绘制） ----------
                int textLeft = rowX + 49;
                int contentRight = rowX + rowW - 10;   // 右侧留 10px padding，文字超过 contentRight 就跑马灯
                int contentW = contentRight - textLeft;
                net.minecraft.client.gui.Font font = MaidFileManagerScreen.this.font;

                String name = info.displayName();
                // 第一行（女仆名）：超长截断 + 省略号…，不跑马灯（避免勾选框右侧视觉晃动）
                if (font.width(name) > contentW) {
                    String trim = font.plainSubstrByWidth(name, contentW - font.width("…")) + "…";
                    graphics.drawString(font, trim, textLeft, top + 2, TEXT_COLOR, false);
                } else {
                    graphics.drawString(font, name, textLeft, top + 2, TEXT_COLOR, false);
                }

                String detail = String.format(Locale.ROOT, "%s  |  好感:%d  HP:%.0f/%.0f  %s",
                        info.modelId(), info.favorability(), info.health(), info.maxHealth(),
                        info.tamed() ? "已驯服" : "未驯服");
                // 第二行（detail）：超长自动左右循环跑马灯（用户要求「左右自动滚动」）
                int detailW = font.width(detail);
                if (detailW <= contentW) {
                    graphics.drawString(font, detail, textLeft, top + 12, SUBTEXT_COLOR, false);
                } else {
                    // 循环周期：停 1200ms → 向左滚动到末尾 → 停 1200ms → 立即回卷从头（≈ MC 成就/长名标准行为）
                    int pauseMs = 1200;
                    int pxPerSec = 60;              // 每秒 60 像素
                    int scrollMs = (int) Math.ceil((double) (detailW - contentW) * 1000.0 / pxPerSec);
                    int cycleMs = pauseMs + scrollMs + pauseMs + 400;
                    long t = System.currentTimeMillis() % cycleMs;
                    int offset;
                    if (t < pauseMs) {
                        offset = 0; // 开头停 1.2s
                    } else if (t < pauseMs + scrollMs) {
                        long st = t - pauseMs;
                        offset = (int) ((long) (detailW - contentW) * st / scrollMs); // 向左滚
                    } else if (t < pauseMs + scrollMs + pauseMs) {
                        offset = detailW - contentW; // 末尾停 1.2s
                    } else {
                        offset = 0; // 最后 400ms 快速回到开头
                    }
                    // 用 scissor 把 detail 区域严格限制在 content 区内（跑马灯的文字超出部分会被裁掉）
                    graphics.enableScissor(textLeft, top + 10, contentRight, top + 22);
                    graphics.drawString(font, detail, textLeft - offset, top + 12, SUBTEXT_COLOR, false);
                    graphics.disableScissor();
                }
            }

            /** 交互命中强制按我们自己的 LIST_BG 矩形判定（不依赖父类 rowLeft/width 错值） */
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
                // 点击行任意位置 → 切换勾选
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
                    listBottom - listTop, listTop, listBottom, ROW_HEIGHT);
            this.listX = listX;
            this.listW = listW;
            this.listTop = listTop;
            this.listBottom = listBottom;
            // ====== Fix 根因：删 setLeftPos + 严格反射顺序 + 3 重兜底（旧名+新名+真实 SRG） ======
            trySetFieldMulti(this, SRG_TOP, listTop);
            trySetFieldMulti(this, SRG_BOTTOM, listBottom);
            trySetFieldMulti(this, SRG_HEIGHT, listBottom - listTop);
            trySetFieldMulti(this, SRG_WIDTH, listW);
            trySetFieldMulti(this, SRG_RIGHT, listX + listW);
            trySetFieldMulti(this, SRG_LEFT, listX);
            // 锚定两遍
            trySetFieldMulti(this, SRG_WIDTH, listW);
            trySetFieldMulti(this, SRG_RIGHT, listX + listW);
            trySetFieldMulti(this, SRG_WIDTH, listW);
            trySetFieldMulti(this, SRG_RIGHT, listX + listW);
            setRenderBackground(false);
            setRenderTopAndBottom(false);
            refresh();
        }

        /** v12 Widget 层命中兜底：按我们自己 LIST_BG ± 滚动条余量判，绕开父类 x0/x1 错值点击错位 */
        @Override
        public boolean isMouseOver(double mouseX, double mouseY) {
            return mouseX >= this.listX - 12 && mouseX <= this.listX + this.listW + 12
                    && mouseY >= this.listTop - 2 && mouseY <= this.listBottom + 2;
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

        /** v13 同 MaidListWidget：mouseClicked 自遍历 Entry.isMouseOver（绕开父类 getEntryAtPosition rowLeft 错值 → 只有最左端能选中）+ 不再二次重绘 Entry */
        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (!this.isMouseOver(mouseX, mouseY)) return false;
            for (Entry e : this.children()) {
                if (e.isMouseOver(mouseX, mouseY)) {
                    boolean ret = e.mouseClicked(mouseX, mouseY, button);
                    if (ret) this.setSelected(e);
                    return ret || true;
                }
            }
            return false;
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            if (!this.isMouseOver(mouseX, mouseY)) return false;
            for (Entry e : this.children()) {
                if (e.isMouseOver(mouseX, mouseY)) return e.mouseReleased(mouseX, mouseY, button);
            }
            return false;
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double vertical) {
            if (!this.isMouseOver(mouseX, mouseY)) return false;
            return super.mouseScrolled(mouseX, mouseY, vertical);
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            super.render(graphics, mouseX, mouseY, partialTick);
        }

        void refresh() {
            clearEntries();
            for (String fileName : fileList) {
                addEntry(new Entry(fileName));
            }
        }

        final class Entry extends ObjectSelectionList.Entry<Entry> {
            final String fileName;
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
                // 高亮/勾选框/文字：永远对齐外层 LIST_BG 左边缘 listX，彻底摆脱父类 left 参数错误
                int rowX = FileListWidget.this.listX;
                int rowW = FileListWidget.this.listW;
                if (selected) {
                    graphics.fill(rowX, top - 1, rowX + rowW, top + height + 1, ROW_SELECTED);
                } else if (hovering) {
                    graphics.fill(rowX, top - 1, rowX + rowW, top + height + 1, ROW_HOVER);
                }
                // ---------- 左侧自绘勾选标记（☐/☑）：父类滚动条在 LIST_BG 左边，整体右移 28px 避免与滚动条重叠 ----------
                String mark = selected ? "☑" : "☐";
                int markColor = selected ? 0xFFFFD700 : TEXT_COLOR;
                graphics.drawString(MaidFileManagerScreen.this.font,
                        mark, rowX + 28, top + 5, markColor, false);

                // ---------- 文件信息（从勾选标记右侧 21 像素开始绘制） ----------
                int textLeft = rowX + 49;
                int contentRight = rowX + rowW - 10;
                int contentW = contentRight - textLeft;
                net.minecraft.client.gui.Font ffont = MaidFileManagerScreen.this.font;

                // 第一行：文件名（超长 → 自动左右循环跑马灯，用户要求「左右自动滚动」）
                int nameW = ffont.width(fileName);
                if (nameW <= contentW) {
                    graphics.drawString(ffont, fileName, textLeft, top + 2, TEXT_COLOR, false);
                } else {
                    int pauseMs = 1200;
                    int pxPerSec = 60;
                    int scrollMs = (int) Math.ceil((double) (nameW - contentW) * 1000.0 / pxPerSec);
                    int cycleMs = pauseMs + scrollMs + pauseMs + 400;
                    long t = System.currentTimeMillis() % cycleMs;
                    int off;
                    if (t < pauseMs) off = 0;
                    else if (t < pauseMs + scrollMs) off = (int) ((long) (nameW - contentW) * (t - pauseMs) / scrollMs);
                    else if (t < pauseMs + scrollMs + pauseMs) off = nameW - contentW;
                    else off = 0;
                    graphics.enableScissor(textLeft, top, contentRight, top + 12);
                    graphics.drawString(ffont, fileName, textLeft - off, top + 2, TEXT_COLOR, false);
                    graphics.disableScissor();
                }
                // 第二行：时间戳（过长截断+省略号，时间格式一般不超）
                long ts = MaidFileIo.parseTimestampFromFileName(fileName);
                String time = ts > 0 ? new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(ts)) : "?";
                String timeLine = "时间: " + time;
                if (ffont.width(timeLine) > contentW) {
                    String trim = ffont.plainSubstrByWidth(timeLine, contentW - ffont.width("…")) + "…";
                    graphics.drawString(ffont, trim, textLeft, top + 12, SUBTEXT_COLOR, false);
                } else {
                    graphics.drawString(ffont, timeLine, textLeft, top + 12, SUBTEXT_COLOR, false);
                }
            }

            /** 交互命中强制按我们自己的 LIST_BG 矩形判定（不依赖父类 rowLeft/width 错值） */
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
                // 行任意位置点击：切换勾选
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
