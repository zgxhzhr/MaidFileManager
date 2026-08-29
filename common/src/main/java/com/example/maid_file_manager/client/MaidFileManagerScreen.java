package com.example.maid_file_manager.client;

import com.example.maid_file_manager.Constants;
import com.example.maid_file_manager.data.MaidFileData;
import com.example.maid_file_manager.data.MaidFileIo;
import com.example.maid_file_manager.data.MaidInfo;
import com.example.maid_file_manager.network.IMaidFileNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.awt.Desktop;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    /** 导入 Tab：保留饰品开关（仅车万本体/万法皆通，全新无附魔） */
    private Button keepBaublesBtn;
    /** 标题栏设置入口（进入 MaidConfigScreen） */
    private Button settingsBtn;
    /** 导出 Tab 全选状态 */
    private boolean selectAllState;
    /** 导入 Tab 全选状态 */
    private boolean selectAllImportState;
    /** OP 统一导出入口按钮（仅 OP 可见；面板左上角小按钮 → 独立确认界面） */
    private Button serverExportTriggerBtn;
    /** 统一导出浏览模式：true = 列表显示所有在线玩家（以各玩家为中心）的女仆 */
    private boolean serverExportMode;
    /** 统一导出异步结果正在等待：此期间网络回调 onFeedbackReceived 仅显示聊天栏，不覆盖主界面底部黄字（Fix 截图3「共成功0个」多余黄字） */
    private long serverExportSuppressFeedbackUntil = 0L;
    /** 统一导出：服务端返回的玩家分组女仆列表 */
    private List<IMaidFileNetwork.PlayerMaidGroup> serverGroups = new ArrayList<>();
    /** 统一导出：收起的玩家（点击玩家行折叠/展开） */
    private final Set<String> collapsedPlayers = new HashSet<>();
    /** 统一导出：entityId → 主人玩家名 */
    private final Map<Integer, String> maidOwnerByEntityId = new HashMap<>();
    /** 导出后是否保留原女仆在世界：true=保留（默认开启，友好），false=不保留 */
    private boolean keepMaidsInWorldState;
    /** 导入时是否保留饰品：true=恢复（默认开启），false=不恢复 */
    private boolean keepBaublesState;
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
        // 【v4-3Fix 截图3：Tab 行左侧大按钮（🌐统一导出 / ↩返回单独导出）与 Tab 按钮同高对齐，视觉一体】
        //   避让策略：Tab 组的最小左边界 = serverBtnX + serverBtnW + tabGap，如原来的居中 TabStartX 太小则整体右移，保持按钮不被挤压
        final int SERVER_EXPORT_BTN_W = 122;
        int serverExportBtnX = panelX + 10;
        int tabMinX = serverExportBtnX + SERVER_EXPORT_BTN_W + tabGap;
        int tabStartX = panelX + (panelW - totalTabW) / 2;
        if (tabStartX < tabMinX) tabStartX = tabMinX;
        // 避让：Tab 组贴太右就左移一点，避免和刷新按钮水平挤字
        int tabEndX = tabStartX + totalTabW;
        if (tabEndX > refreshX - 10) {
            tabStartX -= (tabEndX - (refreshX - 10));
            // 避让后不能又挤到左侧统一导出按钮
            if (tabStartX < tabMinX) tabStartX = tabMinX;
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
        // 设置入口（关闭按钮左侧）
        settingsBtn = Button.builder(Component.translatable("maid_file_manager.gui.button.settings"),
                        b -> this.minecraft.setScreen(new MaidConfigScreen(this)))
                .bounds(panelX + panelW - 34 - 8 - 56, panelY + 4, 56, 14).build();
        addRenderableWidget(exportTabBtn);
        addRenderableWidget(importTabBtn);
        addRenderableWidget(refreshBtn);
        addRenderableWidget(settingsBtn);
        addRenderableWidget(closeBtn);

        // ============= 左侧工具列（竖排 3 行：全选 / 保留 / 文件夹。UI无问题版模型=工具按钮透明叠在列表左上，最大化列表高度——核心解决"列表看不见了"） =============
        int sideX = panelX + 6;                            // UI无问题版：工具列左侧内边距
        int sideBtnW = SIDEBAR_W - 12;                     // UI无问题版：工具按钮统一宽（156-12=144）
        int toolBtnH = 20;                                  // UI无问题版：工具按钮高 20
        int rowGap = 8;                                     // UI无问题版：行距 8
        int sideY1 = panelY + HEADER_TOTAL_H + 2;          // UI无问题版：第 1 行（全选）起始
        int sideY2 = sideY1 + toolBtnH + rowGap;           // 第 2 行：保留女仆 / 保留饰品
        int sideY3 = sideY2 + toolBtnH + rowGap;           // 第 3 行：打开导出/导入文件夹

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

        // 行 2（导出 Tab）：保留女仆开关
        int keepBtnW = sideBtnW;
        keepMaidsInWorldState = true;
        keepMaidsBtn = Button.builder(keepMaidsLabel(keepMaidsInWorldState),
                        b -> { keepMaidsInWorldState = !keepMaidsInWorldState; refreshKeepMaidsLabel(); })
                .bounds(sideX, sideY2, keepBtnW, toolBtnH).build();

        // 行 2（导入 Tab）：保留饰品开关（联机新增）
        keepBaublesState = true;
        keepBaublesBtn = Button.builder(keepBaublesLabel(keepBaublesState),
                        b -> { keepBaublesState = !keepBaublesState; refreshKeepBaublesLabel(); })
                .bounds(sideX, sideY2, keepBtnW, toolBtnH).build();
        keepBaublesBtn.visible = false;
        keepBaublesBtn.active = false;

        // 行 3：打开导出/导入文件夹（共用左侧第 3 行位置，Tab 切换 visible）
        openExportDirBtn = Button.builder(Component.literal("📁 打开导出文件夹"),
                        b -> onOpenExportDir())
                .bounds(sideX, sideY3, sideBtnW, toolBtnH).build();
        openImportDirBtn = Button.builder(
                        Component.translatable("maid_file_manager.gui.button.open_import_dir"),
                        b -> onOpenImportDir())
                .bounds(sideX, sideY3, sideBtnW, toolBtnH).build();

        // 【v4-3Fix 截图3：Tab行左侧大按钮 → 模式动态切换：单独导出→开独立确认界面；统一导出→直接返回单独导出】
        //   与导出/导入 Tab 同高(tabH=20)同Y(tabY)，视觉一体对齐；宽 SERVER_EXPORT_BTN_W=122，左边界 panelX+10
        serverExportTriggerBtn = Button.builder(Component.literal("🌐 统一导出"),
                        b -> onServerExportButtonClick())
                .bounds(serverExportBtnX, tabY, SERVER_EXPORT_BTN_W, tabH).build();

        // ============= 右侧列表区（UI无问题版公式：最大化高度=核心解决"列表看不见了"。工具按钮透明叠在列表左上，不再避让！） =============
        int listAreaX = sideX + SIDEBAR_W + INNER_GAP;     // UI无问题版：列表 X = 工具列右 + 内间距
        int listAreaW = panelX + panelW - 8 - listAreaX;   // UI无问题版：列表宽 = 面板右端点（贴内右边距 8）- 列表左
        int listTop = panelY + HEADER_TOTAL_H;             // UI无问题版：列表顶 = Tab 下沿
        int listBottom = panelY + panelH - 56;             // UI无问题版：列表底 = 面板底 - 56
        if (listBottom - listTop < 80) listBottom = listTop + 80; // UI无问题版：高度兜底 ≥ 80
        // 【Fix B 最小硬上限】：与 updateBottomButtonLayout btnY-4 同公式，防极矮屏兜底冲出按钮区
        int btnYFloor = panelY + panelH - 20 - 22 - 4;
        if (listBottom > btnYFloor) listBottom = btnYFloor;
        if (listBottom <= listTop) listBottom = listTop + 80; // 保底正高
        maidListWidget = new MaidListWidget(listAreaX, listAreaW, listTop, listBottom);
        fileListWidget = new FileListWidget(listAreaX, listAreaW, listTop, listBottom);
        updateListVisibility();

        // ===== 工具列 7 个按钮注册（联机新增 3 个：selectAllImportBtn / keepBaublesBtn / serverExportTriggerBtn） =====
        addRenderableWidget(selectAllBtn);
        addRenderableWidget(keepMaidsBtn);
        addRenderableWidget(selectAllImportBtn);
        addRenderableWidget(keepBaublesBtn);
        addRenderableWidget(openExportDirBtn);
        addRenderableWidget(openImportDirBtn);
        addRenderableWidget(serverExportTriggerBtn);

        // ============= 底部主动作按钮（宽 min(280, panelW-32)，高 20，永远贴 panel 内不下溢出） =============
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

    /** 切换统一导出浏览模式（仅 OP 可见入口）：进入后按玩家分组展示所有在线玩家（以各玩家为中心）的女仆 */
    private void toggleServerExportMode() {
        serverExportMode = !serverExportMode;
        // 进入/退出都重置统一导出状态与勾选（两份 maidList 数据源不同，混用会导错主人）
        serverGroups = new ArrayList<>();
        maidOwnerByEntityId.clear();
        selectedMaidIds.clear();
        collapsedPlayers.clear();
        selectAllState = false;
        // 切换模式时重置"等待服务端异步"的反馈抑制（避免返回单独导出模式后 feedback 仍被静默吞掉）
        serverExportSuppressFeedbackUntil = 0L;
        refreshSelectAllLabel();
        updateActionButtonText();
        // 统一导出模式：隐藏保留女仆开关（永远强制保留所有玩家女仆在世界，不允许 OP 移除他人女仆）
        updateTabSpecificControlsVisibility();
        // 【v4-3Fix 双重保险：直接 setMessage 防止 updateTabSpecificControlsVisibility 因 isOp/时序被跳过】
        if (serverExportTriggerBtn != null && serverExportTriggerBtn.visible) {
            serverExportTriggerBtn.setMessage(Component.literal(
                    serverExportMode ? "↩ 返回单独导出" : "🌐 统一导出"));
        }
        refreshCurrentTab();
    }

    /** 简化：底部主动作按钮（高 20）永远在 panel 内不溢出。
     * 玩家直观反馈：「把导出选中女仆往下移一移就好」—— 所以 bottomPad 从 24 缩为 4，按钮贴 panel 下沿（仅留 2px 边框安全边）。
     * 公式必须与 init 阶段【单一真相源】的 actionBtnY 计算 100% 一致，禁止各算各的导致 UI 链错位。 */
    private void updateBottomButtonLayout() {
        final int ACTION_H = 20;
        final int BOTTOM_PAD_MIN = 4;
        int actionW = 280;
        if (actionW > panelW - 44) actionW = panelW - 44;       // 极窄屏横向压窄到不溢出
        int btnY = panelY + panelH - BOTTOM_PAD_MIN - ACTION_H;
        // 保险裁剪：btnY + actionH ≤ panelY + panelH - 2（边框内侧留 2 px）
        if (btnY + ACTION_H > panelY + panelH - 2) {
            btnY = panelY + panelH - 2 - ACTION_H;
        }
        int centerX = panelX + Math.max(0, (panelW - actionW) / 2);
        actionBtn.setX(centerX);
        actionBtn.setY(btnY);
        actionBtn.setWidth(actionW);
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
    /** 保留饰品按钮：默认开启=导入时恢复饰品（仅车万本体/万法皆通，全新无附魔）。 */
    private static Component keepBaublesLabel(boolean keep) {
        String prefix = keep ? "☑ " : "☐ ";
        String txt = keep ? "保留饰品（默认开启）" : "保留饰品";
        return Component.literal(prefix + txt).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
    }
    private void refreshKeepBaublesLabel() {
        if (keepBaublesBtn != null) keepBaublesBtn.setMessage(keepBaublesLabel(keepBaublesState));
    }

    /** 根据当前 Tab 显示/隐藏 Tab 专属控件：
     *  导出 Tab = 全选 + 保留女仆 + 打开导出文件夹
     *  导入 Tab = 全选 + 保留饰品 + 打开导入文件夹
     */
    private void updateTabSpecificControlsVisibility() {
        boolean showExport = (currentTab == Tab.EXPORT);
        boolean showImport = (currentTab == Tab.IMPORT);
        // 工具栏①
        selectAllBtn.visible = showExport;
        selectAllBtn.active = showExport;
        // 保留女仆按钮：仅「我的导出」可见（统一导出模式下强制永远保留原女仆在世界，隐藏开关避免玩家误会）
        keepMaidsBtn.visible = showExport && !serverExportMode;
        keepMaidsBtn.active = showExport && !serverExportMode;
        selectAllImportBtn.visible = showImport;
        selectAllImportBtn.active = showImport;
        keepBaublesBtn.visible = showImport;
        keepBaublesBtn.active = showImport;
        // 工具栏②（打开文件夹按钮）
        openExportDirBtn.visible = showExport;
        openExportDirBtn.active = showExport;
        openImportDirBtn.visible = showImport;
        openImportDirBtn.active = showImport;
        // 统一导出入口：仅导出 Tab 且 OP 可见
        boolean isOp = this.minecraft != null && this.minecraft.player != null
                && this.minecraft.player.hasPermissions(2);
        serverExportTriggerBtn.visible = showExport && isOp;
        serverExportTriggerBtn.active = showExport && isOp;
        // 模式动态文字：单独导出→统一导出入口；统一导出→返回单独导出
        if (showExport && isOp) {
            if (serverExportMode) {
                serverExportTriggerBtn.setMessage(Component.literal("↩ 返回单独导出"));
            } else {
                serverExportTriggerBtn.setMessage(Component.literal("🌐 统一导出"));
            }
        }
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

    /** 打开导出文件夹（跟 onOpenImportDir 对称实现）；直接打开当前玩家名子目录 maid_exports/<玩家名>/ */
    private void onOpenExportDir() {
        try {
            String playerName = this.minecraft.player != null ? this.minecraft.player.getName().getString() : "";
            File dir = new File(this.minecraft.gameDirectory,
                    Constants.MAID_EXPORTS_DIR + File.separatorChar + MaidFileIo.sanitizePlayerName(playerName));
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
            if (serverExportMode) {
                actionBtn.setMessage(Component.literal("通过服务端导出选中女仆（存到服务端）"));
            } else {
                actionBtn.setMessage(Component.translatable("maid_file_manager.gui.button.export_selected"));
            }
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
            if (serverExportMode) {
                // 统一导出浏览：服务端收集所有在线玩家（以各玩家为中心）的女仆列表
                setFeedback(Component.literal("正在从服务端获取所有玩家的女仆列表…"));
                net.sendRequestServerExportList();
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
        if (msg != null && System.currentTimeMillis() < serverExportSuppressFeedbackUntil
                && msg.getString().contains("共成功")) {
            Constants.LOG.info("[maid_file_manager] setFeedback suppressed: {}", msg.getString());
            return;
        }
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
            if (serverExportMode) {
                // 统一导出：按主人分组提交，文件保存到服务端磁盘 maid_exports/<玩家名>/
                Map<String, List<Integer>> byOwner = new LinkedHashMap<>();
                for (int id : selectedMaidIds) {
                    String owner = maidOwnerByEntityId.getOrDefault(id, "");
                    byOwner.computeIfAbsent(owner, k -> new ArrayList<>()).add(id);
                }
                List<IMaidFileNetwork.PlayerExportRequest> reqs = new ArrayList<>();
                for (Map.Entry<String, List<Integer>> e : byOwner.entrySet()) {
                    reqs.add(new IMaidFileNetwork.PlayerExportRequest(e.getKey(), e.getValue()));
                }
                setFeedback(Component.literal("正在通过服务端导出 " + selectedMaidIds.size() + " 个女仆…"));
                net.sendServerExportBatch(reqs);
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
            Constants.LOG.info("[maid_file_manager] BATCH IMPORT START: count={}, skipped={}, keepBaubles={}",
                    dataList.size(), skipped, keepBaublesState);
            net.sendImportFiles(dataList, keepBaublesState);
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
    // 工程基于官方映射编译，而 TLM 源码使用 Parchment 映射仍保留 isPauseScreen；
    // 两个名字都写（不加 @Override），运行时只要命中其一即可阻止 pause menu 的 gui_blur shader。
    // （但为了彻底杜绝 blur，render() 末尾再盖全屏不透明色 + 重绘内容，双保险兜底）
    @SuppressWarnings("unused")
    public boolean returnsPauseScreen() {
        return false;
    }
    @SuppressWarnings("unused")
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // ① 先画全屏不透明深色底（盖死世界帧 → blur shader 即便后续叠也先把世界层盖没）
        graphics.fill(0, 0, this.width, this.height, SCREEN_BG);
        // ② 再画主面板 + 标题 + Tab 下划线
        drawPanel(graphics, panelX, panelY, panelW, panelH);
        drawCenteredStringNoShadow(graphics, this.font,
                Component.translatable("maid_file_manager.gui.title"),
                this.width / 2, panelY + 10, HEADER_COLOR);
        Button activeTab = currentTab == Tab.EXPORT ? exportTabBtn : importTabBtn;
        graphics.fill(activeTab.getX(), activeTab.getY() + activeTab.getHeight(),
                activeTab.getX() + activeTab.getWidth(), activeTab.getY() + activeTab.getHeight() + 2,
                0xFFFFD700);
        // ③ 子组件（Tab / 工具列 / 列表 / action 按钮等）。
        //    注意：Screen.super.render() 内部在 1.21 会先调用 4 参 renderBackground →
        //    虽然我们 4 参版本已经是空实现，但 MC 还有可能在 render 尾部叠加 blur post-process；
        //    因此 super.render 之后我们再盖一次全屏不透明色并重新画面板/文字/widget。
        super.render(graphics, mouseX, mouseY, partialTick);
        // ④ 再盖一次全屏不透明色（彻底覆盖任何 gui_blur shader 后处理叠加的半透明层）
        graphics.fill(0, 0, this.width, this.height, SCREEN_BG);
        // ⑤ 重画主面板 + 标题 + Tab 下划线（否则会被第 ④ 步盖掉）
        drawPanel(graphics, panelX, panelY, panelW, panelH);
        drawCenteredStringNoShadow(graphics, this.font,
                Component.translatable("maid_file_manager.gui.title"),
                this.width / 2, panelY + 10, HEADER_COLOR);
        graphics.fill(activeTab.getX(), activeTab.getY() + activeTab.getHeight(),
                activeTab.getX() + activeTab.getWidth(), activeTab.getY() + activeTab.getHeight() + 2,
                0xFFFFD700);
        // ⑥ 重画所有 widget（保留当前状态/悬停/高亮）。
        //    1.21 Screen.renderables 是 private；用反射读（失败返回空 List → 最多轻微 blur 绝不 CCE 崩溃世界）。
        List<Renderable> widgets = ScreenWidgetAccess.getRenderables(this);
        for (Renderable w : widgets) {
            w.render(graphics, mouseX, mouseY, partialTick);
        }
        // ⑦ 反馈消息（UI无问题版公式 fbY + Fix A fbX=面板中心。drawCenteredStringNoShadow 保留 1.21 锐利无投影；ScreenWidgetAccess 重绘段保留）
        if (!feedbackMsg.getString().isEmpty() && System.currentTimeMillis() < feedbackExpireAt) {
            int fbX = panelX + panelW / 2;                           // Fix A：面板木框中心（不是窄列表中心，长文不溢出，直观居中）
            int fbY = panelY + panelH - LIST_BOTTOM_PAD + 6;         // UI无问题版：fbY=面板底-62（黄字 overlay 列表底上方 6px，与 action 按钮 btnY=panel底-42 留 11px 间距零纵叠）
            // 最小 fallback：GUI 极矮屏时，若 fbY 冲进列表内容顶部（< listTop+12）就落列表顶+6（正常 GUI 完全不触发）
            int listTopV = maidListWidget.listTop;
            if (fbY < listTopV + 12) fbY = listTopV + 6;
            drawCenteredStringNoShadow(graphics, this.font, feedbackMsg, fbX, fbY, 0xFFFFAA00);
        }
        // ⑧ Hover tooltip（最后画，不被任何层覆盖）
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
            MaidListWidget.ListRow hovered = maidListWidget.getHovered(mouseX, mouseY);
            if (hovered instanceof MaidListWidget.Entry sel) {
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
    public void onServerExportListReceived(List<IMaidFileNetwork.PlayerMaidGroup> groups) {
        if (!serverExportMode) {
            return; // 已退出统一导出模式，丢弃迟到的响应
        }
        this.serverGroups = groups == null ? new ArrayList<>() : new ArrayList<>(groups);
        // 展平到 maidList（复用全选逻辑）+ entityId → 主人映射
        this.maidList = new ArrayList<>();
        this.maidOwnerByEntityId.clear();
        for (IMaidFileNetwork.PlayerMaidGroup g : this.serverGroups) {
            for (MaidInfo info : g.maids()) {
                this.maidList.add(info);
                this.maidOwnerByEntityId.put(info.entityId(), g.playerName());
            }
        }
        selectedMaidIds.removeIf(id -> !maidOwnerByEntityId.containsKey(id));
        boolean allSelected = !maidList.isEmpty() && selectedMaidIds.size() >= maidList.size();
        if (selectAllState != allSelected) {
            selectAllState = allSelected;
            refreshSelectAllLabel();
        }
        maidListWidget.refresh();
        int denied = 0;
        for (IMaidFileNetwork.PlayerMaidGroup g : this.serverGroups) {
            if (!g.consented()) denied++;
        }
        setFeedback(Component.literal(String.format(Locale.ROOT,
                "统一导出列表已加载：%d 名玩家、共 %d 只女仆（%d 人未同意导出，选中其女仆将被服务端拒绝）。点击玩家行展开/收起。",
                this.serverGroups.size(), maidList.size(), denied)));
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
            // 目录结构：maid_exports/<玩家名>/<女仆文件>
            String playerName = this.minecraft.player != null ? this.minecraft.player.getName().getString() : "";
            Path dir = MaidFileIo.ensureExportsDir(gameDir).resolve(MaidFileIo.sanitizePlayerName(playerName));
            Files.createDirectories(dir);
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
            String playerDirName = MaidFileIo.sanitizePlayerName(
                    this.minecraft.player != null ? this.minecraft.player.getName().getString() : "");
            String msg = String.format(Locale.ROOT,
                    "成功导出 %d 个女仆！文件已保存到 maid_exports/%s/%s",
                    success, playerDirName, (success == 1 ? (lastFileName != null ? lastFileName : "") : "…"));
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
        // 【v4-3Fix 截图1/3：统一导出异步期间（15s）只写聊天栏不写底部黄字，避免"共成功0个"覆盖主界面"请求已发送"】
        //   服务端异步结果本身就会通过聊天栏 displayClientMessage 显示（下方 L880），主界面底部黄字仅显示同步即时状态。
        if (System.currentTimeMillis() < serverExportSuppressFeedbackUntil) {
            if (this.minecraft != null && this.minecraft.player != null) {
                this.minecraft.player.displayClientMessage(message, false);
            }
            Constants.LOG.info("[maid_file_manager] onFeedbackReceived suppressed(serverExport async): {}", message.getString());
            return;
        }
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

    /** v3 Fix【根治 enableScissor 剪刀错 = 列表只剩右下角小块 / 点不到】：Widget 公开层几何赋值（跨 Forge/NeoForge 1.20~1.21.1 稳定）。
     *  ListWidget.enableScissor() 用 Widget.getX()/getWidth() 作为剪刀边界；先用 setX/setY/setWidth/setHeight 公开方法（MC 1.19.3+），
     *  失败兜底写 x/y/width/height 公开字段。本层赋值 100% 命中 enableScissor 基准，不随 mapping 变名失效。 */
    private static void applyWidgetGeometry(Object widget, int x, int y, int width, int height) {
        try { widget.getClass().getMethod("setX", int.class).invoke(widget, x); } catch (Throwable ignore) {}
        try { widget.getClass().getMethod("setY", int.class).invoke(widget, y); } catch (Throwable ignore) {}
        try { widget.getClass().getMethod("setWidth", int.class).invoke(widget, width); } catch (Throwable ignore) {}
        try { widget.getClass().getMethod("setHeight", int.class).invoke(widget, height); } catch (Throwable ignore) {}
        trySetField(widget, "x", x);
        trySetField(widget, "y", y);
        trySetField(widget, "width", width);
        trySetField(widget, "height", height);
    }

    // ---------- MaidListWidget ----------

    private final class MaidListWidget extends ObjectSelectionList<MaidListWidget.ListRow> {
        private final int listX, listW, listTop, listBottom;

        /** 列表行基类：让女仆行 Entry 与玩家标题行 HeaderEntry 可共存于同一个列表 */
        abstract static class ListRow extends ObjectSelectionList.Entry<ListRow> {
        }

        MaidListWidget(int listX, int listW, int listTop, int listBottom) {
            super(MaidFileManagerScreen.this.minecraft, listW,
                    listBottom - listTop, listTop, ROW_HEIGHT);
            this.listX = listX;
            this.listW = listW;
            this.listTop = listTop;
            this.listBottom = listBottom;
            // ====== v3 Fix【列表=右下角小块 / 点不到 根治】：Widget 公开层几何赋值 100% 命中 enableScissor 剪刀边界 ======
            applyWidgetGeometry(this, listX, listTop, listW, listBottom - listTop);
            // SRG 反射兜底（UI无问题版严格顺序：top→bottom→height→width→right→left → 锚定两遍 width+right，防内部派生覆盖 right/x1）
            trySetFieldMulti(this, SRG_TOP, listTop);
            trySetFieldMulti(this, SRG_BOTTOM, listBottom);
            trySetFieldMulti(this, SRG_HEIGHT, listBottom - listTop);
            trySetFieldMulti(this, SRG_WIDTH, listW);
            trySetFieldMulti(this, SRG_RIGHT, listX + listW);
            trySetFieldMulti(this, SRG_LEFT, listX);
            trySetFieldMulti(this, SRG_WIDTH, listW);
            trySetFieldMulti(this, SRG_RIGHT, listX + listW);
            trySetFieldMulti(this, SRG_WIDTH, listW);
            trySetFieldMulti(this, SRG_RIGHT, listX + listW);
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

        public ListRow getHovered(double mouseX, double mouseY) {
            return this.getEntryAtPosition(mouseX, mouseY);
        }

        void refresh() {
            clearEntries();
            if (serverExportMode) {
                // 统一导出浏览：玩家标题行（点击展开/收起）+ 展开玩家的女仆行
                for (IMaidFileNetwork.PlayerMaidGroup g : serverGroups) {
                    addEntry(new HeaderEntry(g));
                    if (!collapsedPlayers.contains(g.playerName())) {
                        for (MaidInfo info : g.maids()) {
                            addEntry(new Entry(info));
                        }
                    }
                }
                if (serverGroups.isEmpty()) {
                    addEntry(new HeaderEntry(null));
                }
            } else {
                for (MaidInfo info : maidList) {
                    addEntry(new Entry(info));
                }
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
            for (ListRow entry : this.children()) {
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
            for (ListRow entry : this.children()) {
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

        /** 统一导出浏览：玩家标题行（可点击展开/收起该玩家的女仆列表） */
        final class HeaderEntry extends ListRow {
            final IMaidFileNetwork.PlayerMaidGroup group;
            int lastTop = -1;
            int lastHeight = -1;

            HeaderEntry(IMaidFileNetwork.PlayerMaidGroup group) {
                this.group = group;
            }

            @Override
            public void render(GuiGraphics graphics, int index, int top, int left,
                               int width, int height, int mouseX, int mouseY, boolean hovering,
                               float partialTick) {
                this.lastTop = top;
                this.lastHeight = height;
                int rowX = MaidListWidget.this.listX;
                int rowW = MaidListWidget.this.listW;
                if (hovering) {
                    graphics.fill(rowX, top - 1, rowX + rowW, top + height + 1, ROW_HOVER);
                }
                net.minecraft.client.gui.Font font = MaidFileManagerScreen.this.font;
                if (group == null) {
                    graphics.drawString(font, Component.literal("（服务端没有在线玩家或没有可显示的女仆）"),
                            rowX + 28, top + 6, SUBTEXT_COLOR, false);
                    return;
                }
                boolean collapsed = collapsedPlayers.contains(group.playerName());
                String arrow = collapsed ? "▶" : "▼";
                String consentTag = group.consented() ? "" : " [未同意导出]";
                String line = String.format(Locale.ROOT, "%s %s（%d 只女仆）%s",
                        arrow, group.playerName(), group.maids().size(), consentTag);
                int color = group.consented() ? ACCENT : SUBTEXT_COLOR;
                graphics.fill(rowX, top, rowX + rowW, top + height, 0x30FFFFFF);
                graphics.drawString(font, Component.literal(line), rowX + 28, top + 6, color, false);
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
                if (group == null) {
                    return true;
                }
                String name = group.playerName();
                if (!collapsedPlayers.remove(name)) {
                    collapsedPlayers.add(name);
                }
                MaidListWidget.this.refresh();
                return true;
            }

            @Override
            public Component getNarration() {
                return group == null ? Component.literal("空")
                        : Component.literal(group.playerName() + " " + group.maids().size());
            }
        }

        final class Entry extends ListRow {
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
                if (hovering) {
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
            // ====== v3 Fix【列表=右下角小块 / 点不到 根治】：Widget 公开层几何赋值 100% 命中 enableScissor 剪刀边界 ======
            applyWidgetGeometry(this, listX, listTop, listW, listBottom - listTop);
            // SRG 反射兜底（UI无问题版严格顺序：top→bottom→height→width→right→left → 锚定两遍 width+right，防内部派生覆盖 right/x1）
            trySetFieldMulti(this, SRG_TOP, listTop);
            trySetFieldMulti(this, SRG_BOTTOM, listBottom);
            trySetFieldMulti(this, SRG_HEIGHT, listBottom - listTop);
            trySetFieldMulti(this, SRG_WIDTH, listW);
            trySetFieldMulti(this, SRG_RIGHT, listX + listW);
            trySetFieldMulti(this, SRG_LEFT, listX);
            trySetFieldMulti(this, SRG_WIDTH, listW);
            trySetFieldMulti(this, SRG_RIGHT, listX + listW);
            trySetFieldMulti(this, SRG_WIDTH, listW);
            trySetFieldMulti(this, SRG_RIGHT, listX + listW);
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
                if (hovering) {
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

    // ============= 左上角「🌐 统一导出」入口 → 独立确认界面 / 返回单独导出（模式动态切换，用户指示新增） =============

    /** 左上角按钮点击：根据当前模式切换行为（单独导出→开确认界面；统一导出→直接返回） */
    private void onServerExportButtonClick() {
        if (serverExportMode) {
            // 当前是统一导出模式 → 点击直接返回单独导出模式（不需要确认）
            toggleServerExportMode();
        } else {
            // 当前是单独导出模式 → 打开独立确认界面
            openServerExportConfirm();
        }
    }

    /** 打开统一导出独立确认界面（仅 OP 可见入口，点击时再检查权限） */
    private void openServerExportConfirm() {
        if (this.minecraft == null) return;
        this.minecraft.setScreen(new MaidServerExportScreen(this));
    }

    /** 统一导出全员独立确认界面（用户指示新增独立界面；确认后回到主界面，切换到统一导出模式并自动执行导出） */
    public static class MaidServerExportScreen extends Screen {
        private final MaidFileManagerScreen parent;
        private final boolean hasPermission;

        public MaidServerExportScreen(MaidFileManagerScreen parent) {
            super(Component.literal("统一导出全员确认"));
            this.parent = parent;
            this.hasPermission = parent.minecraft != null && parent.minecraft.player != null
                    && parent.minecraft.player.hasPermissions(2);
        }

        @Override
        protected void init() {
            int btnW = 160;
            int btnH = 20;
            // 【v4-3Fix 截图2：按钮从 height/2+30 下移到 height/2+96】
            //   说明文字渲染：标题 y=height/2-70，+26 标题底 + 9行×14 = h/2+82 最后一行文字底，再加 14px 间隙到 h/2+96。
            int centerY = this.height / 2 + 96;
            int gap = 20;
            int baseX = this.width / 2 - btnW - gap / 2;
            // 确认按钮（仅 OP 可用）
            Button confirmBtn = Button.builder(Component.literal("✅ 确认导出"), b -> onConfirm())
                    .bounds(baseX, centerY, btnW, btnH).build();
            confirmBtn.active = hasPermission;
            addRenderableWidget(confirmBtn);
            // 取消按钮（所有人都可用）
            addRenderableWidget(Button.builder(Component.literal("❌ 取消返回"), b -> {
                if (this.minecraft != null) this.minecraft.setScreen(parent);
            }).bounds(baseX + btnW + gap, centerY, btnW, btnH).build());
        }

        /** 确认导出：回到主界面 → 切换到统一导出模式 → 主动执行 action 导出逻辑 */
        private void onConfirm() {
            if (!hasPermission || this.minecraft == null) return;
            this.minecraft.setScreen(parent);
            // 强制切换到统一导出模式（确保切换数据源为在线玩家分组）
            if (!parent.serverExportMode) parent.toggleServerExportMode();
            // 全选（默认全选所有在线玩家的所有女仆）
            parent.selectAllState = true;
            parent.onSelectAllToggled(true);
            // 【v4-3Fix：开启 15s 反馈抑制 → 服务端异步 onFeedbackReceived 只写聊天栏，不覆盖"请求已发送"黄字】
            parent.serverExportSuppressFeedbackUntil = System.currentTimeMillis() + 15000L;
            // 直接调用主动作按钮（= 导出选中女仆 → 统一导出模式即为全员）
            parent.onActionButtonClick(parent.actionBtn);
            // 【Fix：消去多余"共成功 0 个"提示】统一导出为服务端异步，结果走聊天栏显示，主界面黄字强制覆盖为"请求已发送"提示
            parent.setFeedback(Component.literal("📤 统一导出请求已发送（服务端异步处理），结果请查看聊天栏 / 服务器日志。"));
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            renderBackground(graphics, mouseX, mouseY, partialTick);
            super.render(graphics, mouseX, mouseY, partialTick);
            int cx = this.width / 2;
            int y = this.height / 2 - 70;
            // 标题
            graphics.drawCenteredString(this.font, "【统一导出全员 确认】", cx, y, 0xFFFFD700);
            y += 26;
            // 说明文字
            String[] lines;
            if (!hasPermission) {
                lines = new String[] {
                    "⚠ 权限不足：仅 OP（权限等级 2+）可使用此功能",
                    "",
                    "你当前不是服务器 OP，无法统一导出所有玩家的女仆。",
                    "",
                    "请点击「取消返回」回到主界面，或联系服务器管理员。"
                };
            } else {
                lines = new String[] {
                    "将导出「所有在线玩家」的女仆（以各玩家为单位分组）。",
                    "",
                    "导出规则：是否保留原女仆在世界 = 主界面「保留原女仆在世界」开关",
                    "导入规则：是否保留饰品 = 主界面「保留饰品」开关（导入时生效）",
                    "",
                    "※ 每个女仆会单独生成 .maid 文件，文件名包含玩家名和女仆名。",
                    "※ OP 无法删除其他玩家的女仆（统一导出模式强制保留原女仆）。",
                    "",
                    "点击「确认导出」开始处理，结果将显示在聊天栏 / 服务器日志中。"
                };
            }
            for (String line : lines) {
                graphics.drawCenteredString(this.font, line, cx, y, 0xFFFFFFFF);
                y += 14;
            }
        }

        @Override
        public boolean shouldCloseOnEsc() { return true; }

        @Override
        public void onClose() {
            if (this.minecraft != null) this.minecraft.setScreen(parent);
        }
    }
}
