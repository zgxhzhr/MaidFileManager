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
 * <p>交互流程：玩家在列表里点击行 → 底部【导出/导入选中】按钮执行动作。
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
    /** 导入 Tab：保留饰品开关（默认开启；仅车万本体/万法皆通饰品支持，附魔耐久重置为全新） */
    private Button keepBaublesBtn;
    /** 设置入口（右上角，进设置界面） */
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
    /** 导入时是否保留饰品：true=保留（默认开启），false=丢弃 */
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

        // ============= 左侧工具列（竖排 4 行：全选 / 保留 / 文件夹 / 统一导出。UI无问题版模型=工具按钮透明叠在列表左上，最大化列表高度——核心解决"列表看不见了"） =============
        int sideX = panelX + 6;                            // UI无问题版：工具列左侧内边距
        int sideBtnW = SIDEBAR_W - 12;                     // UI无问题版：工具按钮统一宽（156-12=144）
        int toolBtnH = 20;                                  // UI无问题版：工具按钮高 20（之前缩 18 没必要）
        int rowGap = 8;                                     // UI无问题版：行距 8（之前缩 4 太挤）
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

        // 行 2（导入 Tab）：保留饰品开关（联机新增，UI无问题版无）
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
        int listTop = panelY + HEADER_TOTAL_H;             // UI无问题版：列表顶 = Tab 下沿（工具按钮透明叠列表左上，最大化高度）
        int listBottom = panelY + panelH - 56;             // UI无问题版：列表底 = 面板底 - 56（底部动作 20 + 间距 10 + feedback 6 + 余量 20 = 56）
        if (listBottom - listTop < 80) listBottom = listTop + 80; // UI无问题版：高度兜底 ≥ 80，GUI 超窄时不压缩到 0
        // 【Fix B 最小硬上限】：与 updateBottomButtonLayout btnY-4 完全同公式，防止极矮屏兜底冲出按钮区（btnY=panel底-42，间距至少 4px）
        int btnYFloor = panelY + panelH - 20 - 22 - 4;
        if (listBottom > btnYFloor) listBottom = btnYFloor;
        if (listBottom <= listTop) listBottom = listTop + 80; // 保底正高（极端情况）
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
    /** 保留饰品按钮：默认开启=导入时恢复饰品（仅车万本体/万法皆通，全新无附魔）。 */
    private static Component keepBaublesLabel(boolean keep) {
        String prefix = keep ? "☑ " : "☐ ";
        String txt = keep ? "保留饰品（默认开启）" : "保留饰品";
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
            Constants.LOG.info("[maid_file_manager] BATCH IMPORT START: count={}, skipped={}, keepBaubles={}",
                    dataList.size(), skipped, keepBaublesState);
            net.sendImportFiles(dataList, keepBaublesState);
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
        // 反馈消息（UI无问题版公式：fbY=面板底-LIST_BOTTOM_PAD+6；Fix A：fbX=面板中心=木框中心，超长中文不溢出裁剪且对齐玩家"在中间"直觉）
        if (!feedbackMsg.getString().isEmpty() && System.currentTimeMillis() < feedbackExpireAt) {
            int fbX = panelX + panelW / 2;                           // Fix A：面板木框中心（不是窄列表中心！387px 长文 100% 装下）
            int fbY = panelY + panelH - LIST_BOTTOM_PAD + 6;         // UI无问题版：fbY=面板底-62（listBottom=panel底-56 → 黄字在列表底上方 6px 处 overlay 列表，和按钮 btnY=panel底-42 留 11px 间距零纵叠）
            // 最小 fallback：GUI 极矮屏（GUI scale 大）时，若 fbY 冲进列表内容顶部（< listTop+12）就落列表顶+6（正常 GUI 完全不触发）
            int listTopV = maidListWidget.listTop;
            if (fbY < listTopV + 12) fbY = listTopV + 6;
            graphics.drawCenteredString(this.font, feedbackMsg, fbX, fbY, 0xFFFFAA00);
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
        //   服务端异步结果本身就会通过聊天栏 displayClientMessage 显示（代码 L853），主界面底部黄字仅显示同步即时状态。
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

    /** v3 Fix【根治 enableScissor 剪刀错 = 列表只剩右下角小块 / 点不到】：Widget 公开层几何赋值（跨 Forge/NeoForge 1.20~1.21.1 稳定）。
     *  ObjectSelectionList 父类 ListWidget.enableScissor() 用的是 Widget.getX()/getWidth() 作为剪刀边界，而不是 AbstractObjectSelectionList 内部 left/right。
     *  先用 setX/setY/setWidth/setHeight 公开方法（MC 1.19.3+ 稳定方法），失败再兜底写 x/y/width/height 公开字段。
     *  这一层赋值比 SRG f_93388_ 反射可靠 100%，不随 Forge SRG mapping 变名而失效。 */
    private static void applyWidgetGeometry(Object widget, int x, int y, int width, int height) {
        // 第一层：MC 1.19.3+ Widget 公开方法（最优先）
        try { widget.getClass().getMethod("setX", int.class).invoke(widget, x); } catch (Throwable ignore) {}
        try { widget.getClass().getMethod("setY", int.class).invoke(widget, y); } catch (Throwable ignore) {}
        try { widget.getClass().getMethod("setWidth", int.class).invoke(widget, width); } catch (Throwable ignore) {}
        try { widget.getClass().getMethod("setHeight", int.class).invoke(widget, height); } catch (Throwable ignore) {}
        // 第二层兜底：直接写 public 字段（某些 mapping 下方法名被重命名，但 x/y/width/height 字段名永远 public）
        trySetField(widget, "x", x);
        trySetField(widget, "y", y);
        trySetField(widget, "width", width);
        trySetField(widget, "height", height);
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

    private final class MaidListWidget extends ObjectSelectionList<MaidListWidget.ListRow> {
        private final int listX, listW, listTop, listBottom;

        /** 列表行基类：让女仆行 Entry 与玩家标题行 HeaderEntry 可共存于同一个列表 */
        abstract static class ListRow extends ObjectSelectionList.Entry<ListRow> {
        }

        MaidListWidget(int listX, int listW, int listTop, int listBottom) {
            super(MaidFileManagerScreen.this.minecraft, listW,
                    listBottom - listTop, listTop, listBottom, ROW_HEIGHT);
            this.listX = listX;
            this.listW = listW;
            this.listTop = listTop;
            this.listBottom = listBottom;
            // ====== v3 Fix【列表=右下角小块 / 点不到 根治】：Widget 公开层几何赋值（Forge/Neo 1.20~1.21.1 稳定） ======
            //   ListWidget.enableScissor() 用 Widget.getX()/getWidth() 作为剪刀边界；先改这一层（100% 命中），
            //   再用 SRG 反射锚定 AbstractObjectSelectionList 内部 left/right 作兜底。
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

        public ListRow getHovered(double mouseX, double mouseY) {
            return this.hitRow(mouseX, mouseY);
        }

        /** v14b FabricFix：父类 getEntryAtPosition 是 final 不能覆写 → 命中逻辑独立成 hitRow（自有 listX/listTop 直判，滚动量仍复用 getScrollAmount()） */
        private ListRow hitRow(double mouseX, double mouseY) {
            if (mouseX < (double) this.listX || mouseX > (double) (this.listX + this.listW)) {
                return null;
            }
            if (mouseY < (double) this.listTop || mouseY > (double) this.listBottom) {
                return null;
            }
            int index = (int) ((mouseY - this.listTop - 4 + this.getScrollAmount()) / ROW_HEIGHT);
            java.util.List<ListRow> rows = this.children();
            if (index < 0 || index >= rows.size()) {
                return null;
            }
            return rows.get(index);
        }

        /** v13：父类内部 scissor 已靠 SRG 反射 6 字段 100% 命中拉正 → 删除之前「二次画 Entry」的兜底，只保留 Widget 层 isMouseOver 最后一道防线。
         *  Fix「只有点最左端才能选中」：override mouseClicked 自己遍历 Entry.isMouseOver（我们 LIST_BG 判的，与 Entry.render 同一基准），完全绕开父类 getEntryAtPosition 的 rowLeft 错值计算。 */
        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (!this.isMouseOver(mouseX, mouseY)) return false;
            for (ListRow e : this.children()) {
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
            for (ListRow e : this.children()) {
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

        /** v14 FabricFix【Fabric 生产环境列表行不可见根治】：Fabric 运行时的字段/方法全是 intermediary 名，
         *  SRG(f_933xx_)与官方名(x0/y0/setX)的字符串反射全部静默失败 → 父类 scissor/行坐标/滚动条全部错位（行画在看不见的位置）。
         *  改为完全自绘：不调 super.render，用本类 listX/listTop/listW/listBottom 裁剪+摆行+画滚动条；
         *  滚动量复用父类 getScrollAmount()（y0/y1 构造期已正确；编译期调用会被 loom 重映射，跨加载器稳定）。 */
        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            // 1.20.x AbstractSelectionList 不继承 AbstractWidget、无 visible 字段；显隐由外层 removeWidget/addRenderableWidget 控制
            this.renderBackground(graphics);
            java.util.List<ListRow> rows = this.children();
            int viewH = this.listBottom - this.listTop;
            int scroll = (int) this.getScrollAmount();
            graphics.enableScissor(this.listX, this.listTop, this.listX + this.listW, this.listBottom);
            for (int i = 0; i < rows.size(); i++) {
                int top = this.listTop + 4 - scroll + i * ROW_HEIGHT;
                if (top >= this.listBottom || top + ROW_HEIGHT <= this.listTop) {
                    continue;
                }
                boolean hovering = mouseX >= this.listX && mouseX < this.listX + this.listW
                        && mouseY >= top && mouseY < top + ROW_HEIGHT;
                rows.get(i).render(graphics, i, top, this.listX + 2,
                        this.getRowWidth(), ROW_HEIGHT, mouseX, mouseY, hovering, partialTick);
            }
            graphics.disableScissor();
            // 滚动条：内容溢出时贴列表右缘画（位置基于我们自己的 listX/listW，不再依赖父类 x1）
            int contentH = rows.size() * ROW_HEIGHT + 8;
            if (contentH > viewH) {
                int trackH = viewH - 4;
                int thumbH = Math.max(20, trackH * viewH / contentH);
                int maxScroll = contentH - viewH;
                int progress = Math.min(trackH - thumbH,
                        (int) ((long) scroll * (trackH - thumbH) / Math.max(1, maxScroll)));
                int barX = this.listX + this.listW - 3;
                graphics.fill(barX, this.listTop + 2 + progress, barX + 2,
                        this.listTop + 2 + progress + thumbH, 0x66FFFFFF);
            }
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
                    graphics.drawString(font, "（服务端没有在线玩家或没有可显示的女仆）",
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
                graphics.drawString(font, line, rowX + 28, top + 6, color, false);
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
                if (hovering) {
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
                // 第二行（detail）：超长自动左右循环跑马灯（需求「左右自动滚动」）
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
            return this.hitRow(mouseX, mouseY);
        }

        /** v14b FabricFix：父类 getEntryAtPosition 是 final 不能覆写 → 命中逻辑独立成 hitRow（自有 listX/listTop 直判，滚动量仍复用 getScrollAmount()） */
        private Entry hitRow(double mouseX, double mouseY) {
            if (mouseX < (double) this.listX || mouseX > (double) (this.listX + this.listW)) {
                return null;
            }
            if (mouseY < (double) this.listTop || mouseY > (double) this.listBottom) {
                return null;
            }
            int index = (int) ((mouseY - this.listTop - 4 + this.getScrollAmount()) / ROW_HEIGHT);
            java.util.List<Entry> rows = this.children();
            if (index < 0 || index >= rows.size()) {
                return null;
            }
            return rows.get(index);
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

        /** v14 FabricFix：与 MaidListWidget 相同的自绘渲染（Fabric intermediary 环境下父类几何反射失效） */
        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            // 1.20.x AbstractSelectionList 不继承 AbstractWidget、无 visible 字段；显隐由外层 removeWidget/addRenderableWidget 控制
            this.renderBackground(graphics);
            java.util.List<Entry> rows = this.children();
            int viewH = this.listBottom - this.listTop;
            int scroll = (int) this.getScrollAmount();
            graphics.enableScissor(this.listX, this.listTop, this.listX + this.listW, this.listBottom);
            for (int i = 0; i < rows.size(); i++) {
                int top = this.listTop + 4 - scroll + i * ROW_HEIGHT;
                if (top >= this.listBottom || top + ROW_HEIGHT <= this.listTop) {
                    continue;
                }
                boolean hovering = mouseX >= this.listX && mouseX < this.listX + this.listW
                        && mouseY >= top && mouseY < top + ROW_HEIGHT;
                rows.get(i).render(graphics, i, top, this.listX + 2,
                        this.getRowWidth(), ROW_HEIGHT, mouseX, mouseY, hovering, partialTick);
            }
            graphics.disableScissor();
            int contentH = rows.size() * ROW_HEIGHT + 8;
            if (contentH > viewH) {
                int trackH = viewH - 4;
                int thumbH = Math.max(20, trackH * viewH / contentH);
                int maxScroll = contentH - viewH;
                int progress = Math.min(trackH - thumbH,
                        (int) ((long) scroll * (trackH - thumbH) / Math.max(1, maxScroll)));
                int barX = this.listX + this.listW - 3;
                graphics.fill(barX, this.listTop + 2 + progress, barX + 2,
                        this.listTop + 2 + progress + thumbH, 0x66FFFFFF);
            }
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
                if (hovering) {
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

                // 第一行：文件名（超长 → 自动左右循环跑马灯，需求「左右自动滚动」）
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
        public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            renderBackground(graphics);
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
