package io.github.zgxhzhr.maidfm.client;

import io.github.zgxhzhr.maidfm.Constants;
import io.github.zgxhzhr.maidfm.config.MaidConfigManager;
import io.github.zgxhzhr.maidfm.data.MaidFileData;
import io.github.zgxhzhr.maidfm.data.MaidFileIo;
import io.github.zgxhzhr.maidfm.data.MaidInfo;
import io.github.zgxhzhr.maidfm.data.MaidProfileView;
import io.github.zgxhzhr.maidfm.network.IMaidFileNetwork;
import io.github.zgxhzhr.maidfm.network.MaidFilePackets;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
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
 * 女仆档案管理主界面。
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
    /** 运行期实际工具列宽：窄屏（panelW 小）时收窄，把宽度让给列表 */
    private int sidebarW = SIDEBAR_W;
    /** 列表行内「档案」按钮尺寸 */
    private static final int PROFILE_BTN_W = 34;
    private static final int PROFILE_BTN_H = 12;
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
    /** 导入 Tab：导入成功后删除源文件开关（默认关闭，危险操作） */
    private Button deleteImportFileBtn;
    /** 标题栏设置入口（进入 MaidConfigScreen） */
    private Button settingsBtn;
    /** 标题栏备份管理入口（进入 MaidBackupBrowserScreen，浏览车万女仆自动备份并导出为 .maid） */
    private Button backupBtn;
    /** 等待服务端返回档案的女仆 entityId（收到不匹配的档案视图时忽略） */
    private int pendingProfileEntityId = Integer.MIN_VALUE;
    /** 档案请求发出时刻：服务端限频会静默丢弃重复请求，用它超时复位 */
    private long pendingProfileSince;
    /** 等档案回包的最长时间 */
    private static final long PROFILE_REQUEST_TIMEOUT_MS = 5_000L;
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
    /** 统一导出确认后：等服务端列表回包再全选并提交（否则 owner 映射为空，导出 0 条） */
    private boolean pendingServerExport;
    /** 导出批次在途：从发请求到「写盘回执已发」期间为真，用于串行化导出 */
    private boolean exportInFlight;
    /** 置位时刻：服务端不回导出结果时用它超时自愈（否则导出按钮永久 busy） */
    private long exportInFlightSince;

    /** 导入提交在途：读盘期间连点两次会重复提交 → 服务端重复生成女仆（第六轮 M3） */
    private boolean importSubmitInFlight;
    private long importSubmitSince;
    /** 导入在途的最长等待 */
    private static final long IMPORT_SUBMIT_TIMEOUT_MS = 60_000L;
    /** 等在途导出结束的最长时间 */
    private static final long EXPORT_IN_FLIGHT_TIMEOUT_MS = 60_000L;
    /** 统一导出：服务端返回的玩家分组女仆列表 */
    private List<IMaidFileNetwork.PlayerMaidGroup> serverGroups = new ArrayList<>();
    /** 统一导出：收起的玩家（点击玩家行折叠/展开） */
    private final Set<String> collapsedPlayers = new HashSet<>();
    /** 统一导出：entityId → 主人玩家名 */
    private final Map<Integer, String> maidOwnerByEntityId = new HashMap<>();
    /** 导出后是否保留原女仆在世界：true=保留（默认开启，友好），false=不保留 */
    /** init() 会因 resize 重跑，用它避免把玩家已选的选项冲回默认 */
    private boolean optionsInitialized;
    private boolean deleteImportFileStateSet;

    private boolean keepMaidsInWorldState;
    /** 「不保留并导出」二次确认小弹窗是否正在显示 */
    private boolean removeConfirmVisible;
    /** 弹窗打开瞬间暂存的待导出女仆 entityId（点确认时使用，弹窗期间勾选变化不影响本次提交） */
    private final List<Integer> pendingRemoveIds = new ArrayList<>();
    /** 弹窗内「不再提示」本次勾选状态（仅在点「确认导出」时才写入客户端配置） */
    private boolean removeConfirmDontAsk;
    // ===== 二次确认弹窗几何（每次 render 重算，鼠标判定复用同一组坐标） =====
    private int dlgX, dlgY, dlgW, dlgH;
    private int dlgCheckX, dlgCheckY, dlgCheckH;
    private int dlgBtnY, dlgBtnW, dlgBtnH, dlgCancelX, dlgConfirmX;
    /** 导入成功后是否删除源文件：true=删除（默认关闭，危险操作） */
    private boolean deleteImportFileState;
    /**
     * 最近一次批量导入实际发送的源文件名（与 dataList/服务端回传 spawned 严格同序）。
     * 仅在 deleteImportFileState=true 时用于删除本地文件；无效文件在发送前已被跳过，不在此列表中。
     */
    private final List<String> pendingImportFileNames = new ArrayList<>();
    /** 当前导出 Tab 被勾选的女仆 entityId（Set 方便 O(1) 查询、去重） */
    private final Set<Integer> selectedMaidIds = new HashSet<>();
    /** 当前导入 Tab 被勾选的文件名（Set 方便 O(1) 查询、去重） */
    private final Set<String> selectedImportFileNames = new HashSet<>();
    private int panelX, panelY, panelW, panelH;

    public MaidFileManagerScreen() {
        super(Component.translatable("maid_file_manager.gui.title"));
    }

    @Override
    public void removed() {
        // 界面背景音乐（试验性功能）：离开界面时释放，全部界面关闭后延迟停止
        UiMusicManager.leave(this);
        // 界面被 setScreen 切走时 onClose 不会被调用：若不在这里还原网络回调持有者，
        // 会残留指向已关闭界面的引用，后续服务端回调打到死界面。
        if (IMaidFileNetwork.ClientHandlerHolder.get() == this) {
            IMaidFileNetwork.ClientHandlerHolder.set(null);
        }
        super.removed();
    }
    @Override
    public void tick() {
        super.tick();
        // 界面期间每帧压住原版背景音乐（其 MusicManager 会自动重播，单次停止无效）
        UiMusicManager.tick();
        // 档案请求的超时复位：服务端 RequestThrottle 会**静默丢弃**同一玩家 1 秒内的重复
        // 「档案」请求，客户端若照旧等待，pendingProfileEntityId 会永久指向一个永远不来的 id，
        // 此后所有「档案」点击都被 onMaidProfileReceived 的守卫拒掉 → 界面再也打不开。
        if (pendingProfileEntityId != Integer.MIN_VALUE && pendingProfileSince > 0L
                && System.currentTimeMillis() - pendingProfileSince > PROFILE_REQUEST_TIMEOUT_MS) {
            pendingProfileEntityId = Integer.MIN_VALUE;
            pendingProfileSince = 0L;
            setFeedback(Component.translatable("maid_file_manager.gui.profile.timeout"));
        }
        // 在途导出的超时自愈：服务端预检拒绝 / handler 抛异常 / 结果被别的界面吃掉时，
        // 客户端收不到任何结果，exportInFlight 会一直为真 → 导出按钮永久 busy。
        // 超过 EXPORT_IN_FLIGHT_TIMEOUT_MS 就放开，让玩家能重试。
        if (this.exportInFlight && this.exportInFlightSince > 0L
                && System.currentTimeMillis() - this.exportInFlightSince > EXPORT_IN_FLIGHT_TIMEOUT_MS) {
            this.exportInFlight = false;
            this.exportInFlightSince = 0L;
            setFeedback(Component.translatable("maid_file_manager.gui.export.timeout"));
            Constants.LOG.warn("[maid_file_manager] 导出在途超时（60s 未收到结果），已放开重试");
        }

        // 导入提交在途的超时自愈：后台读盘线程若因界面关闭被丢弃，标志会一直为真（第六轮 M3）。
        if (this.importSubmitInFlight && this.importSubmitSince > 0L
                && System.currentTimeMillis() - this.importSubmitSince > IMPORT_SUBMIT_TIMEOUT_MS) {
            this.importSubmitInFlight = false;
            this.importSubmitSince = 0L;
        }
    }

    @Override
    protected void init() {
        UiMusicManager.enter(this);
        super.init();
        panelW = Math.min(PANEL_MAX_W, this.width - MARGIN * 2);
        panelH = this.height - MARGIN * 2;
        panelX = (this.width - panelW) / 2;
        // 工具列按面板宽收窄（1024×768 + scale 3 → 逻辑宽 341、panelW=281）：
        // 固定 156 会把列表区挤到 ~103px，行内文字区只剩几像素、名字全变「…」。
        sidebarW = Math.min(SIDEBAR_W, Math.max(64, panelW / 3));
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
        int tabGap = 10;
        int refreshW = 58;
        int refreshX = panelX + panelW - refreshW - 8;
        // 【v4-3Fix 截图3：Tab 行左侧大按钮（🌐统一导出 / ↩返回单独导出）与 Tab 按钮同高对齐，视觉一体】
        //   避让策略：Tab 组的最小左边界 = serverBtnX + serverBtnW + tabGap，如原来的居中 TabStartX 太小则整体右移，保持按钮不被挤压
        final int SERVER_EXPORT_BTN_W = 122;
        int serverExportBtnX = panelX + 10;
        // 非 OP 时「统一导出」按钮 visible=false，不该再为它预留 122+10=132px：
        // 否则 341 宽下 Tab 组被推到 tabMinX=172，「导入」Tab 与刷新按钮仍重叠 17px
        // （P2-25 只把总重叠从 49 缩到 17，根因就是这个预留宽度）。
        boolean opUser = this.minecraft != null && this.minecraft.player != null
                && this.minecraft.player.hasPermissions(2);
        int tabMinX = opUser ? (serverExportBtnX + SERVER_EXPORT_BTN_W + tabGap) : (panelX + 10);
        // 一次性算定可用区间并允许收窄 Tab 宽度。原先是「居中 → clamp 到 tabMinX → 右移避让 →
        // 再 clamp 回 tabMinX」，最后那次 clamp 把避让整体撤销且不再复查右边界：
        // 341×192（1366×768 + scale 4）下 tabW=56、totalTabW=122、tabMinX=172，
        // Tab 组占 172..294，其中「导入」页 238..294 与刷新按钮 245..303 重叠 49px。
        int availW = (refreshX - 10) - tabMinX;
        int tabW2 = Math.min(86, Math.max(40, (availW - tabGap) / 2));
        int totalTabW2 = tabW2 * 2 + tabGap;
        int tabStartX = tabMinX + Math.max(0, (availW - totalTabW2) / 2);
        int tabW = tabW2;
        int totalTabW = totalTabW2;
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
        // 备份管理入口（设置按钮左侧）：浏览各存档中车万女仆的自动备份并导出为 .maid
        int backupW = 40;
        backupBtn = Button.builder(Component.translatable("maid_file_manager.gui.button.backup"),
                        b -> openBackupBrowser())
                .bounds(panelX + panelW - 34 - 8 - 56 - 8 - backupW, panelY + 4, backupW, 14)
                .tooltip(Tooltip.create(Component.translatable("maid_file_manager.gui.button.backup.tooltip")))
                .build();
        addRenderableWidget(exportTabBtn);
        addRenderableWidget(importTabBtn);
        addRenderableWidget(refreshBtn);
        addRenderableWidget(settingsBtn);
        addRenderableWidget(backupBtn);
        addRenderableWidget(closeBtn);

        // ============= 左侧工具列（竖排 3 行：全选 / 保留 / 文件夹。UI无问题版模型=工具按钮透明叠在列表左上，最大化列表高度——核心解决"列表看不见了"） =============
        int sideX = panelX + 6;                            // UI无问题版：工具列左侧内边距
        int sideBtnW = sidebarW - 12;                     // UI无问题版：工具按钮统一宽（156-12=144）
        int toolBtnH = 20;                                  // UI无问题版：工具按钮高 20
        int rowGap = 8;                                     // UI无问题版：行距 8
        // 三行工具按钮改为「从按钮区上方倒排」：原来 sideY1 恒为 panelY+66、第 3 行恒在
        // panelY+122..142，与 this.height 完全无关 —— height=192（1366×768 + scale 4）时
        // 面板只有 30..162，第 3 行按钮 152..172 越出面板底；height=170 时直接出屏。
        // 倒排保证三行永远落在列表可视区上方，且不会压住底部动作按钮。
        int sideY3 = panelY + panelH - 20 - 22 - 4 - toolBtnH - 4;   // 第 3 行下沿对齐 btnYFloor-4
        int sideY2 = sideY3 - toolBtnH - rowGap;
        int sideY1 = sideY2 - toolBtnH - rowGap;
        // 极端矮屏兜底：第 1 行不能被挤到 Tab 栏上面
        int sideMinY = panelY + HEADER_TOTAL_H + 2;
        if (sideY1 < sideMinY) {
            sideY1 = sideMinY;
            sideY2 = sideY1 + toolBtnH + rowGap;
            sideY3 = sideY2 + toolBtnH + rowGap;
        }

        // 行 1：全选（导出/导入 各一，共用 X/Y，Tab 切换 visible）
        // 勾选状态在 init 之间保留：从统一导出确认弹窗返回时不应丢掉用户已选的项
        selectAllBtn = Button.builder(selectAllLabel(selectAllState),
                        b -> { selectAllState = !selectAllState; onSelectAllToggled(selectAllState); refreshSelectAllLabel(); })
                .bounds(sideX, sideY1, sideBtnW, toolBtnH).build();
        // 同上：导入页全选状态保留
        selectAllImportBtn = Button.builder(selectAllLabel(selectAllImportState),
                        b -> { selectAllImportState = !selectAllImportState; onSelectAllImportToggled(selectAllImportState); refreshSelectAllImportLabel(); })
                .bounds(sideX, sideY1, sideBtnW, toolBtnH).build();
        selectAllImportBtn.visible = false;
        selectAllImportBtn.active = false;

        // 行 2（导出 Tab）：保留女仆开关
        int keepBtnW = sideBtnW;
        // 只在首次进入时给默认值：init() 在窗口 resize 时也会重跑，
        // 无条件重置会把玩家已经点选的「保留女仆 / 导入后删文件」冲回默认。
        if (!optionsInitialized) {
            optionsInitialized = true;
            keepMaidsInWorldState = true;
        }
        keepMaidsBtn = Button.builder(keepMaidsLabel(keepMaidsInWorldState),
                        b -> { keepMaidsInWorldState = !keepMaidsInWorldState; refreshKeepMaidsLabel(); })
                .bounds(sideX, sideY2, keepBtnW, toolBtnH).build();

        // 行 3（仅导入 Tab）：导入成功后删除源文件（默认关闭，危险操作；仅删除服务端确认生成成功的文件）
        if (!deleteImportFileStateSet) {
            deleteImportFileStateSet = true;
            deleteImportFileState = false;
        }
        deleteImportFileBtn = Button.builder(deleteImportFileLabel(deleteImportFileState),
                        b -> { deleteImportFileState = !deleteImportFileState; refreshDeleteImportFileLabel(); })
                .bounds(sideX, sideY3, keepBtnW, toolBtnH).build();
        deleteImportFileBtn.visible = false;
        deleteImportFileBtn.active = false;

        // 行 2/3：打开导出文件夹 → 行 3；打开导入文件夹 → 行 2（与导出 Tab 的保留女仆同槽，Tab 切换 visible）
        openExportDirBtn = Button.builder(Component.translatable("maid_file_manager.gui.button.open_export_dir"),
                        b -> onOpenExportDir())
                .bounds(sideX, sideY3, sideBtnW, toolBtnH).build();
        openImportDirBtn = Button.builder(
                        Component.translatable("maid_file_manager.gui.button.open_import_dir"),
                        b -> onOpenImportDir())
                .bounds(sideX, sideY2, sideBtnW, toolBtnH).build();

        // 【v4-3Fix 截图3：Tab行左侧大按钮 → 模式动态切换：单独导出→开独立确认界面；统一导出→直接返回单独导出】
        //   与导出/导入 Tab 同高(tabH=20)同Y(tabY)，视觉一体对齐；宽 SERVER_EXPORT_BTN_W=122，左边界 panelX+10
        serverExportTriggerBtn = Button.builder(Component.translatable("maid_file_manager.gui.button.server_export"),
                        b -> onServerExportButtonClick())
                .bounds(serverExportBtnX, tabY, SERVER_EXPORT_BTN_W, tabH).build();

        // ============= 右侧列表区（UI无问题版公式：最大化高度=核心解决"列表看不见了"。工具按钮透明叠在列表左上，不再避让！） =============
        int listAreaX = sideX + sidebarW + INNER_GAP;     // UI无问题版：列表 X = 工具列右 + 内间距
        int listAreaW = panelX + panelW - 8 - listAreaX;   // UI无问题版：列表宽 = 面板右端点（贴内右边距 8）- 列表左
        int listTop = panelY + HEADER_TOTAL_H;             // UI无问题版：列表顶 = Tab 下沿
        // 与反馈起点（面板底 - LIST_BOTTOM_PAD + 6）留 6px 间隔，避免反馈首行压住列表底
        int desiredBottom = panelY + panelH - LIST_BOTTOM_PAD;
        // 【Fix B 最小硬上限】：与 updateBottomButtonLayout btnY-4 同公式，防极矮屏兜底冲出按钮区
        int btnYFloor = panelY + panelH - 20 - 22 - 4;
        // 三段兜底必须合成一个**不可能互相覆盖**的表达式。原先的顺序是：
        //   if (listBottom - listTop < 80) listBottom = listTop + 80;   // 先顶到 listTop+80
        //   if (listBottom > btnYFloor) listBottom = btnYFloor;         // 再压回按钮上沿
        //   if (listBottom <= listTop) listBottom = listTop + 80;       // ← 又顶回，把上一步整条覆盖
        // 当 btnYFloor <= listTop（panelH <= 110，即 height <= 170）时，列表会画出面板并压住
        // 主动作按钮（按钮整条落在列表矩形内，点击先被列表接走 → 按钮失去点击能力）。
        int minH = 40;   // 矮屏下宁可变矮，也别压按钮
        int maxBottom = Math.min(btnYFloor, panelY + panelH - 20);
        int listBottom = Math.max(listTop + minH, Math.min(maxBottom, desiredBottom));
        if (listBottom > maxBottom) {
            listBottom = maxBottom;   // 空间真不够时允许低于 minH，但绝不越过按钮
        }
        maidListWidget = new MaidListWidget(listAreaX, listAreaW, listTop, listBottom);
        fileListWidget = new FileListWidget(listAreaX, listAreaW, listTop, listBottom);
        updateListVisibility();

        // ===== 工具列 6 个按钮注册（导出/导入两 Tab 靠 visible 切换共用槽位） =====
        addRenderableWidget(selectAllBtn);
        addRenderableWidget(keepMaidsBtn);
        addRenderableWidget(selectAllImportBtn);
        addRenderableWidget(deleteImportFileBtn);
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

    /** 打开备份管理界面：浏览各存档中车万女仆的自动备份并导出为 .maid */
    private void openBackupBrowser() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(new MaidBackupBrowserScreen(this));
        }
    }

    /**
     * 打开「不保留原女仆并导出」二次确认小弹窗：
     * 暂存当前勾选的女仆 id，弹窗期间背景界面的一切交互被吞掉。
     */
    private void openRemoveConfirm(List<Integer> ids) {
        pendingRemoveIds.clear();
        pendingRemoveIds.addAll(ids);
        removeConfirmDontAsk = false;
        removeConfirmVisible = true;
    }

    /** 关闭确认弹窗（取消 / ESC / 确认后调用），不产生任何导出动作（确认除外） */
    private void closeRemoveConfirm() {
        removeConfirmVisible = false;
        pendingRemoveIds.clear();
        removeConfirmDontAsk = false;
    }

    /** 弹窗内点「确认导出」：勾选了不再提示则写入客户端配置（仅当前存档），然后按暂存 id 执行移除导出 */
    private void confirmRemoveExport() {
        List<Integer> ids = new ArrayList<>(pendingRemoveIds);
        if (removeConfirmDontAsk) {
            MaidConfigManager.setSkipRemoveConfirm(currentWorldKey(this.minecraft), true);
        }
        closeRemoveConfirm();
        doExportMaids(ids, true);
    }

    /**
     * 当前所在存档/服务器的稳定标识（仅客户端可用）：
     * 多人游戏=服务器地址；单人（含对局域网开放）=存档文件夹名；分别加 {@code mp_}/{@code sp_} 前缀区分。
     * 该标识只用于本地客户端配置按存档分别记录，不会上报服务端。
     */
    public static String currentWorldKey(Minecraft mc) {
        ServerData server = mc.getCurrentServer();
        if (server != null && server.ip != null && !server.ip.isEmpty()) {
            return "mp_" + sanitizeWorldKey(server.ip);
        }
        if (mc.getSingleplayerServer() != null && mc.getSingleplayerServer().getWorldData() != null) {
            return "sp_" + sanitizeWorldKey(mc.getSingleplayerServer().getWorldData().getLevelName());
        }
        return "unknown";
    }

    /**
     * 把服务器地址/存档名规整为配置键，必须保证不同原名绝不撞键：
     * 全由字母数字、下划线、连字符组成时原样保留；一旦包含其它字符（中文存档名、
     * IP 里的点/冒号等），整体改用 UTF-8 字节的十六进制编码（前缀 h）。
     * 注意：不能把非安全字符一律替换成下划线——两个等长中文存档名（如「世界一」「世界二」）
     * 会压成同一个键，导致 A 存档勾选的「不再提示」泄漏到 B 存档，存档隔离失效。
     */
    private static String sanitizeWorldKey(String raw) {
        boolean allSafe = true;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-')) {
                allSafe = false;
                break;
            }
        }
        if (allSafe) {
            return raw.isEmpty() ? "unknown" : raw;
        }
        byte[] bytes = raw.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length * 2 + 1);
        sb.append('h');
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * 真正发送导出请求（保留导出与确认弹窗确认后共用同一入口）。
     *
     * @param ids         要导出的女仆 entityId
     * @param removeAfter true=导出后从世界移除（背包物品不返还）
     */
    private void doExportMaids(List<Integer> ids, boolean removeAfter) {
        IMaidFileNetwork net = IMaidFileNetwork.Holder.get();
        if (net == null) {
            Constants.LOG.warn("[maid_file_manager] doExportMaids: net is null");
            setFeedback(Component.translatable("maid_file_manager.gui.error.network_unavailable"));
            return;
        }
        Constants.LOG.info("[maid_file_manager] EXPORT START: count={}, removeAfter={}", ids.size(), removeAfter);
        setFeedback(Component.translatable("maid_file_manager.gui.export.exporting_multi", ids.size()));
        // 串行化：上一批导出还没把写盘回执发回去时不许再发下一批，
        // 否则服务端的待删登记与回执无法一一对应（P2-8 残余窗口）。
        if (this.exportInFlight) {
            setFeedback(Component.translatable("maid_file_manager.gui.export.busy"));
            return;
        }
        this.exportInFlight = true;
        this.exportInFlightSince = System.currentTimeMillis();
        net.sendExportMaids(ids, removeAfter);
    }

    private void switchTab(Tab tab) {
        if (currentTab == tab) return;
        // 切 Tab 必须取消「等回包再全选导出」这条待办：消费点会走 onActionButtonClick，
        // 而那个入口按**当前 Tab** 分派 —— 等待窗口内切到「导入」且已勾选文件时，
        // 回包到达会把用户确认的「导出」执行成「批量导入」（凭空生成女仆，不可撤销）。
        if (pendingServerExport) {
            pendingServerExport = false;
            serverExportSuppressFeedbackUntil = 0L;
            setFeedback(Component.translatable("maid_file_manager.gui.server_export.preparing"));
        }
        currentTab = tab;
        closeRemoveConfirm();
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
        // 统一导出强制保留女仆，确认弹窗一并关闭
        closeRemoveConfirm();
        // P2-6：进入/退出统一导出都要清掉「等待服务端列表」标记。此前只在收到回包时复位，
        // 切换模式时重置"等待服务端异步"的反馈抑制（避免返回单独导出模式后 feedback 仍被静默吞掉）
        // 回包失败或中途退出后该标记会残留，下次进统一导出时任何一次列表回包都会触发自动全选+提交。
        serverExportSuppressFeedbackUntil = 0L;
        pendingServerExport = false;
        refreshSelectAllLabel();
        updateActionButtonText();
        // 统一导出模式：隐藏保留女仆开关（永远强制保留所有玩家女仆在世界，不允许 OP 移除他人女仆）
        updateTabSpecificControlsVisibility();
        // 【v4-3Fix 双重保险：直接 setMessage 防止 updateTabSpecificControlsVisibility 因 isOp/时序被跳过】
        if (serverExportTriggerBtn != null && serverExportTriggerBtn.visible) {
            serverExportTriggerBtn.setMessage(serverExportMode
                    ? Component.translatable("maid_file_manager.gui.button.back_to_single_export")
                    : Component.translatable("maid_file_manager.gui.button.server_export"));
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
    // 注意：MC 默认字体不含 ☐/☑ 字形（渲染成空方框），复选框由 drawCheckBox 手绘，消息文本不带前缀
    private static Component selectAllLabel(boolean selected) {
        return Component.translatable("maid_file_manager.gui.button.select_all");
    }
    /** 保留女仆按钮：默认开启=保留原女仆在世界（友好默认）。 */
    private static Component keepMaidsLabel(boolean keep) {
        // 金色系：与 UI 木色/标题金色协调，醒目但不刺眼
        return Component.translatable("maid_file_manager.gui.label.keep_maids")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
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
    /** 导入后删除源文件按钮：默认关闭；开启时红色提示危险 */
    private static Component deleteImportFileLabel(boolean delete) {
        return delete
                ? Component.translatable("maid_file_manager.gui.label.delete_import_file.on")
                        .withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
                : Component.translatable("maid_file_manager.gui.label.delete_import_file.off")
                        .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
    }
    private void refreshDeleteImportFileLabel() {
        if (deleteImportFileBtn != null) deleteImportFileBtn.setMessage(deleteImportFileLabel(deleteImportFileState));
    }
    /** 根据当前 Tab 显示/隐藏 Tab 专属控件：
     *  导出 Tab = 全选 + 保留女仆 + 打开导出文件夹
     *  导入 Tab = 全选 + 打开导入文件夹 + 导入后删除文件
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
        deleteImportFileBtn.visible = showImport;
        deleteImportFileBtn.active = showImport;
        // 工具栏②（打开文件夹按钮）
        // 统一导出模式：文件写在**服务端**的 maid_exports/<玩家名>/，客户端这个目录是空的，
        // 打开它只会让玩家以为「导出没生效」。该模式下隐藏此按钮。
        openExportDirBtn.visible = showExport && !serverExportMode;
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
                serverExportTriggerBtn.setMessage(Component.translatable("maid_file_manager.gui.button.back_to_single_export"));
            } else {
                serverExportTriggerBtn.setMessage(Component.translatable("maid_file_manager.gui.button.server_export"));
            }
        }
    }

    /** 一键全选被点击时，同步 selectedMaidIds 和每行的选中状态 */
    private void onSelectAllToggled(boolean selected) {
        selectedMaidIds.clear();
        if (selected) {
            for (MaidInfo info : maidList) {
                // 折叠玩家下的女仆不在屏幕上：全选不应把它们加回选中集。
                // 否则「已选 N」与可见行数不符，移除模式下还会删掉玩家看不见的对象
                // （P2-31 只在折叠瞬间移除过，折叠之后再点全选又会被加回来）。
                String owner = maidOwnerByEntityId.get(info.entityId());
                if (owner != null && collapsedPlayers.contains(owner)) {
                    continue;
                }
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
        File dir = new File(this.minecraft.gameDirectory, Constants.MAID_IMPORTS_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            Constants.LOG.warn("[maid_file_manager] 无法创建导入目录: {}", dir.getAbsolutePath());
            setFeedback(Component.translatable("maid_file_manager.gui.error.open_dir_failed"));
            return;
        }
        openSystemDirInBackground(dir);
    }

    /** 打开导出文件夹（跟 onOpenImportDir 对称实现）；直接打开当前玩家名子目录 maid_exports/<玩家名>/ */
    private void onOpenExportDir() {
        String playerName = this.minecraft.player != null ? this.minecraft.player.getName().getString() : "";
        // 与写文件路径使用同一个 resolvePlayerDir（normalize+startsWith 断言），不自行字符串拼接目录
        Path exportRoot = this.minecraft.gameDirectory.toPath()
                .resolve(Constants.MAID_EXPORTS_DIR);
        File dir = MaidFileIo.resolvePlayerDir(exportRoot, playerName).toFile();
        if (!dir.exists() && !dir.mkdirs()) {
            Constants.LOG.warn("[maid_file_manager] 无法创建导出目录: {}", dir.getAbsolutePath());
            setFeedback(Component.translatable("maid_file_manager.gui.error.open_dir_failed"));
            return;
        }
        openSystemDirInBackground(dir);
    }

    /**
     * 在后台守护线程中调起系统文件管理器。
     * Desktop.open（部分平台实现）与 xdg-open 都可能阻塞数秒，
     * 在渲染线程直接执行会让点击瞬间整客户端卡死，故统一异步；打开失败只记日志，
     * 不跨线程触碰界面状态。
     */
    private static void openSystemDirInBackground(File dir) {
        Thread t = new Thread(() -> {
            try {
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
                Constants.LOG.info("[maid_file_manager] Opened dir: {}", dir.getAbsolutePath());
            } catch (Exception e) {
                Constants.LOG.error("[maid_file_manager] Failed to open dir: {}", dir.getAbsolutePath(), e);
            }
        }, "maid_file_manager-open-dir");
        t.setDaemon(true);
        t.start();
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
                actionBtn.setMessage(Component.translatable("maid_file_manager.gui.button.export_selected_via_server"));
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
                setFeedback(Component.translatable("maid_file_manager.gui.server_export.fetching"));
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
        if (msg == null) {
            msg = Component.empty();
        }
        // 注意：统一导出异步窗口内的服务端回执已在 onFeedbackReceived 中整体改道聊天栏，
        // 此处不得再靠 contains("共成功") 之类的中文字面量反解服务端文案（契约禁止，且措辞变更即失效）
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
                setFeedback(Component.translatable("maid_file_manager.gui.server_export.exporting", selectedMaidIds.size()));
                net.sendServerExportBatch(reqs);
                return;
            }
            List<Integer> ids = new ArrayList<>(selectedMaidIds);
            // keep=true → 保留原女仆（不移除）；keep=false → 导出后移除
            boolean removeAfter = !keepMaidsInWorldState;
            // 危险操作二次确认：取消保留且当前存档未配置「不再提示」时，先弹小窗确认，确认后由弹窗回调真正发送
            if (removeAfter && !MaidConfigManager.isSkipRemoveConfirm(currentWorldKey(this.minecraft))) {
                openRemoveConfirm(ids);
                return;
            }
            doExportMaids(ids, removeAfter);
        } else {
            // 导入 Tab：批量导入（取 selectedImportFileNames）
            if (selectedImportFileNames.isEmpty()) {
                setFeedback(Component.translatable("maid_file_manager.gui.import.no_selection"));
                return;
            }
            // 数量上限必须在**读盘之前**挡：原先是在全部 readMaidFile 之后才校验，
            // 「全选」会先在主线程同步读并解析目录内全部 .maid（每份都要解压 + NBT 解析），
            // 超标时这些工作全白做，还会让客户端卡住数秒。
            if (selectedImportFileNames.size() > MaidFilePackets.MAX_IMPORT_BATCH_FILES) {
                setFeedback(Component.translatable("maid_file_manager.import.fail.too_many",
                        MaidFilePackets.MAX_IMPORT_BATCH_FILES, selectedImportFileNames.size()));
                return;
            }
            // 在途守卫：读盘 + 上传期间再点一次会重复提交，服务端会重复生成女仆（第六轮 M3）。
            if (importSubmitInFlight) {
                setFeedback(Component.translatable("maid_file_manager.gui.import.batch_reading",
                        selectedImportFileNames.size()));
                return;
            }
            importSubmitInFlight = true;
            importSubmitSince = System.currentTimeMillis();
            final List<String> readFileNames = new ArrayList<>(selectedImportFileNames);
            final boolean deleteAfterImport = deleteImportFileState;
            pendingImportFileNames.clear();
            // 读盘 + NBT 解析 + 序列化全是 CPU/IO 密集操作（每份 .maid 都要解压 + 解析），
            // 原先进口路径在渲染线程同步做完再发送，几十份就会明显卡帧。改到后台线程，
            // 回到主线程再发包与更新界面。
            setFeedback(Component.translatable("maid_file_manager.gui.import.batch_reading", readFileNames.size()));
            // 路径必须先在主线程取好：worker 内读 this.minecraft 属跨线程访问客户端对象，
            // 且界面关闭后它可能已被置空（第六轮 M2）。
            final java.nio.file.Path importGameDir = this.minecraft != null
                    ? this.minecraft.gameDirectory.toPath().toAbsolutePath() : null;
            Thread readWorker = new Thread(() -> {
                List<MaidFileData> dataList = new ArrayList<>();
                List<String> okNames = new ArrayList<>();
                int skippedLocal = 0;
                String errorKey = null;
                String rejectMsg = null;
                try {
                    if (importGameDir == null) {
                        errorKey = "maid_file_manager.import.fail.local_read";
                    } else {
                    Path gameDir = importGameDir;
                    Path dir = MaidFileIo.ensureImportsDir(gameDir);
                    for (String fileName : readFileNames) {
                        Path file = dir.resolve(fileName).normalize();
                        if (!file.startsWith(dir)) {
                            skippedLocal++;
                            continue;
                        }
                        MaidFileData data = MaidFileIo.readMaidFile(file);
                        if (data == null || data.getData() == null) {
                            skippedLocal++;
                            continue;
                        }
                        dataList.add(data);
                        okNames.add(fileName);
                    }
                    }
                } catch (Exception ex) {
                    Constants.LOG.error("[maid_file_manager] Failed to batch read import files", ex);
                    errorKey = "maid_file_manager.import.fail.local_read";
                }
                if (errorKey == null && dataList.isEmpty()) {
                    errorKey = "maid_file_manager.import.fail.empty";
                }
                if (errorKey == null) {
                    List<byte[]> importBlobs = MaidFilePackets.serializeMaidDataBatch(dataList);
                    if (importBlobs == null) {
                        errorKey = "maid_file_manager.import.fail.unserializable";
                    } else {
                        // 允许分片兜底：超瞬时上限时不本地拒绝，改由 sendImportFiles 走分片上传
                        rejectMsg = MaidFilePackets.checkMaidDataBatchForWire(
                                importBlobs, MaidFilePackets.MAX_IMPORT_BATCH_FILES, true);
                    }
                }
                final List<MaidFileData> outData = dataList;
                final List<String> outNames = okNames;
                final int outSkipped = skippedLocal;
                final String outError = errorKey;
                final String outReject = rejectMsg;
                if (this.minecraft == null) {
                    return;
                }
                this.minecraft.execute(() -> {
                    importSubmitInFlight = false;
                    if (outError != null) {
                        setFeedback(Component.translatable(outError));
                        return;
                    }
                    if (outReject != null) {
                        setFeedback(Component.literal(outReject));
                        return;
                    }
                    pendingImportFileNames.clear();
                    pendingImportFileNames.addAll(outNames);
                    setFeedback(Component.translatable("maid_file_manager.gui.import.batch_start",
                            outData.size(), outSkipped));
                    // 饰品是否随行统一由「设置 → 服务端设置 → 导入时允许携带饰品」控制：
                    // 此处恒上传 true，实际是否恢复由服务端双闸门决定，杜绝非 OP 绕过
                    Constants.LOG.info("[maid_file_manager] BATCH IMPORT START: count={}, skipped={}, deleteAfter={}",
                            outData.size(), outSkipped, deleteAfterImport);
                    net.sendImportFiles(outData, true, deleteAfterImport);
                });
            }, "maidfm-import-read");
            readWorker.setDaemon(true);
            readWorker.start();
        }
    }

    /**
     * 1.20.x 官方映射（javap 核实）只有单参 renderBackground，由 Screen.render 在画组件前调用，
     * 默认实现会应用背景模糊 + 画泥土底。留空打断调用链；全屏不透明底色统一在 render() 里绘制。
     */
    @Override
    public void renderBackground(GuiGraphics graphics) {
        // 不调用 super：本界面自带不透明背景
    }

    /** 非暂停界面：阻止暂停菜单模糊效果（1.20.x 官方映射方法名为 isPauseScreen） */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // ① 全屏不透明深色底（renderBackground 已空实现，不会有泥土底/模糊层叠上来）
        graphics.fill(0, 0, this.width, this.height, SCREEN_BG);
        // ② 主面板 + 标题 + Tab 下划线
        drawPanel(graphics, panelX, panelY, panelW, panelH);
        // 标题居中于「面板左内边距 ~ 备份按钮左侧」这段可用宽度，窄屏先截断再加省略号：
        // 否则 341 宽（1024×768 + GUI scale 3）下标题会与右边三个小按钮重叠 32px。
        Component titleComp = Component.translatable("maid_file_manager.gui.title");
        String titleStr = titleComp.getString();
        int titleLeft = panelX + 10;
        int titleRight = (backupBtn != null ? backupBtn.getX() : panelX + panelW - 10) - 6;
        int titleAvail = Math.max(20, titleRight - titleLeft);
        if (this.font.width(titleStr) > titleAvail) {
            titleStr = this.font.plainSubstrByWidth(titleStr, Math.max(1, titleAvail - this.font.width("…"))) + "…";
        }
        drawCenteredStringNoShadow(graphics, this.font, Component.literal(titleStr),
                (titleLeft + titleRight) / 2, panelY + 10, HEADER_COLOR);
        Button activeTab = currentTab == Tab.EXPORT ? exportTabBtn : importTabBtn;
        graphics.fill(activeTab.getX(), activeTab.getY() + activeTab.getHeight(),
                activeTab.getX() + activeTab.getWidth(), activeTab.getY() + activeTab.getHeight() + 2,
                0xFFFFD700);
        // ③ 子组件（Tab / 工具列 / 列表 / action 按钮等）在面板之上正常绘制一次即可。
        //    禁止再全屏盖色+反射重绘：Forge 生产环境反射 Screen 私有字段会被模块限制拒绝，
        //    盖色后按钮/开关全部隐形（只剩文字），是 1.20/1.20.1 界面缺件的根因。
        super.render(graphics, mouseX, mouseY, partialTick);
        // ④ 工具按钮左侧的手绘复选框（按钮文本不含 ☐/☑ 缺字字符，框体统一在这里画）
        drawButtonCheckBox(graphics, selectAllBtn, selectAllState);
        drawButtonCheckBox(graphics, selectAllImportBtn, selectAllImportState);
        drawButtonCheckBox(graphics, keepMaidsBtn, keepMaidsInWorldState);
        drawButtonCheckBox(graphics, deleteImportFileBtn, deleteImportFileState);
        // ⑤ 反馈消息（fbX=面板中心；无阴影文字保持锐利；超长自动按面板宽度换行，
        //    行数受"底部按钮上沿"限制，超出部分截断加省略号——完整文本仍会进聊天栏）
        if (!feedbackMsg.getString().isEmpty() && System.currentTimeMillis() < feedbackExpireAt) {
            int fbX = panelX + panelW / 2;
            int startY = panelY + panelH - LIST_BOTTOM_PAD + 6;
            // 反馈起点必须落在列表**下方**。原先的 fallback 是「若冲进列表顶部就落到 listTop+6」，
            // 那是「更靠列表内」—— 矮屏（341×256）下列表被 clamp 得更矮时，这行黄字会被画进
            // 列表矩形里，压住唯一可见的那几行。
            int listBottomV = maidListWidget.listBottom;
            if (startY < listBottomV + 2) startY = listBottomV + 2;
            int btnTop = (actionBtn != null && actionBtn.getY() > 0) ? actionBtn.getY() : startY + 4 * 9;
            int maxLines = Math.max(1, (btnTop - startY) / 9);
            List<String> lines = wrapFeedback(feedbackMsg.getString(), Math.max(80, panelW - 16), maxLines);
            for (int i = 0; i < lines.size(); i++) {
                drawCenteredStringNoShadow(graphics, this.font, Component.literal(lines.get(i)),
                        fbX, startY + i * 9, 0xFFFFAA00);
            }
        }
        // ⑥ Hover tooltip（最后画，不被任何层覆盖）
        drawHoverTooltip(graphics, mouseX, mouseY);
        // ⑦ 「不保留并导出」二次确认弹窗（最顶层，盖住包括 tooltip 在内的一切）
        if (removeConfirmVisible) {
            renderRemoveConfirmDialog(graphics, mouseX, mouseY);
        }
    }

    // ================== 「不保留并导出」二次确认小弹窗 ==================

    private static final int DLG_DIM = 0xB0000000;
    private static final int DLG_WARN_COLOR = 0xFFE8E8E8;
    private static final int DLG_CANCEL_BG = 0xFF221B13;
    private static final int DLG_CANCEL_BG_HOVER = 0xFF3D3024;
    private static final int DLG_DANGER_BG = 0xFF3A1D18;
    private static final int DLG_DANGER_BG_HOVER = 0xFF552820;
    private static final int DLG_BTN_TEXT = 0xFFE8E8E8;
    private static final int DLG_BTN_TEXT_DANGER = 0xFFFF7766;

    /**
     * 弹窗整体所在的 GUI 深度层。背景控件（按钮贴图/文字）都绘制在 z=0 平面，
     * 而 GUI 各渲染层（gui/text）均开启 LEQUAL 深度测试：弹窗若也画在 z=0，
     * 同深度的背景按钮文字会"浮"在遮罩与面板之上。原版悬浮提示（tooltip）
     * 同样是抬到 z=400 才压住全部控件，这里沿用同一约定。
     */
    private static final int DLG_Z_LAYER = 400;

    /** 绘制居中模态小弹窗：半透明遮罩 + 木框小面板 + 警告文案 + 「不再提示」+ 取消/确认两个按钮 */
    private void renderRemoveConfirmDialog(GuiGraphics graphics, int mouseX, int mouseY) {
        Component title = Component.translatable("maid_file_manager.gui.export.confirm_title")
                .withStyle(ChatFormatting.BOLD);
        Component warn = Component.translatable("maid_file_manager.gui.export.remove_warning");
        Component dontAsk = Component.translatable("maid_file_manager.gui.export.dont_ask_again");
        Component cancel = Component.translatable("maid_file_manager.gui.export.confirm_cancel");
        Component ok = Component.translatable("maid_file_manager.gui.export.confirm_ok");

        // —— 几何（内边距 16，宽 320，高度随行数自适应） ——
        dlgW = Math.min(320, Math.max(260, this.width - 80));
        int textMaxW = dlgW - 32;
        List<net.minecraft.util.FormattedCharSequence> lines = this.font.split(warn, textMaxW);
        int padTop = 14, titleH = 12, gapTitle = 6, lineH = 10, gapTextCheck = 10;
        int checkH = 9, gapCheckBtn = 12, btnH = 20, padBottom = 12;
        dlgH = padTop + titleH + gapTitle + lines.size() * lineH + gapTextCheck
                + checkH + gapCheckBtn + btnH + padBottom;
        dlgX = (this.width - dlgW) / 2;
        dlgY = (this.height - dlgH) / 2;

        // —— 整体抬升到高深度层：遮罩/面板/文字/控件全部在该层内按先后顺序绘制，
        //    既稳稳压住 z=0 的背景按钮文字，弹窗内部文字又仍在面板之上 ——
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, (float) DLG_Z_LAYER);

        // —— 遮罩 + 面板 ——
        graphics.fill(0, 0, this.width, this.height, DLG_DIM);
        graphics.fill(dlgX, dlgY, dlgX + dlgW, dlgY + dlgH, PANEL_BG);
        graphics.renderOutline(dlgX, dlgY, dlgW, dlgH, PANEL_BORDER);

        // —— 标题（居中金色加粗） + 标题下分隔细线 ——
        int titleX = dlgX + (dlgW - this.font.width(title)) / 2;
        int titleY = dlgY + padTop;
        graphics.drawString(this.font, title, titleX, titleY, HEADER_COLOR, false);
        int sepY = titleY + titleH + 2;
        graphics.fill(dlgX + 16, sepY, dlgX + dlgW - 16, sepY + 1, 0x55D0A060);

        // —— 警告正文（自动换行、左对齐） ——
        int textY = sepY + 1 + gapTitle - 2;
        int lineX = dlgX + 16;
        for (net.minecraft.util.FormattedCharSequence line : lines) {
            graphics.drawString(this.font, line, lineX, textY, DLG_WARN_COLOR, false);
            textY += lineH;
        }

        // —— 「不再提示」复选框 + 文案 ——
        dlgCheckX = lineX;
        dlgCheckY = textY + gapTextCheck;
        dlgCheckH = checkH;
        drawCheckBox(graphics, dlgCheckX, dlgCheckY, removeConfirmDontAsk);
        graphics.drawString(this.font, dontAsk, dlgCheckX + CHECK_SIZE + 6,
                dlgCheckY, removeConfirmDontAsk ? ACCENT : SUBTEXT_COLOR, false);

        // —— 取消 / 确认导出 两个手绘按钮 ——
        dlgBtnY = dlgCheckY + checkH + gapCheckBtn;
        dlgBtnH = btnH;
        dlgBtnW = (dlgW - 32 - 8) / 2;
        dlgCancelX = dlgX + 16;
        dlgConfirmX = dlgX + dlgW - 16 - dlgBtnW;
        drawDialogButton(graphics, dlgCancelX, dlgBtnY, dlgBtnW, btnH, cancel,
                inside(dlgCancelX, dlgBtnY, dlgBtnW, btnH, mouseX, mouseY), false);
        drawDialogButton(graphics, dlgConfirmX, dlgBtnY, dlgBtnW, btnH, ok,
                inside(dlgConfirmX, dlgBtnY, dlgBtnW, btnH, mouseX, mouseY), true);

        graphics.pose().popPose();
    }

    /** 弹窗内的手绘按钮：深棕底 + 木色描边；危险操作（确认移除）用暗红底/红字 */
    private void drawDialogButton(GuiGraphics graphics, int x, int y, int w, int h,
                                  Component text, boolean hovered, boolean danger) {
        int bg;
        int border;
        int color;
        if (danger) {
            bg = hovered ? DLG_DANGER_BG_HOVER : DLG_DANGER_BG;
            border = hovered ? 0xFFFF8877 : 0xFF8B5A2B;
            color = hovered ? 0xFFFFB0A6 : DLG_BTN_TEXT_DANGER;
        } else {
            bg = hovered ? DLG_CANCEL_BG_HOVER : DLG_CANCEL_BG;
            border = hovered ? ACCENT : PANEL_BORDER;
            color = hovered ? HEADER_COLOR : DLG_BTN_TEXT;
        }
        graphics.fill(x, y, x + w, y + h, bg);
        graphics.renderOutline(x, y, w, h, border);
        graphics.drawString(this.font, text,
                x + (w - this.font.width(text)) / 2, y + (h - 8) / 2, color, false);
    }

    private static boolean inside(int x, int y, int w, int h, double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    /** 弹窗显示时吞掉所有鼠标点击：背景界面（列表/按钮）一律不响应，仅弹窗控件可点 */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!removeConfirmVisible) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (button == 0) {
            // 复选框热区包含右侧文案，整条都可点
            if (mouseX >= dlgCheckX && mouseX < dlgX + dlgW - 16
                    && mouseY >= dlgCheckY - 3 && mouseY < dlgCheckY + dlgCheckH + 3) {
                removeConfirmDontAsk = !removeConfirmDontAsk;
            } else if (inside(dlgCancelX, dlgBtnY, dlgBtnW, dlgBtnH, mouseX, mouseY)) {
                closeRemoveConfirm();
            } else if (inside(dlgConfirmX, dlgBtnY, dlgBtnW, dlgBtnH, mouseX, mouseY)) {
                confirmRemoveExport();
            } else {
                // 契约（记忆 F/J-11）：点击弹窗外部视为取消，不能像以前那样「点了没反应」。
                closeRemoveConfirm();
            }
        }
        return true;
    }

    /** 弹窗显示时吞掉拖动，避免背景列表被拖出滚动/误触 */
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (removeConfirmVisible) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    /** 弹窗显示时吞掉字符输入，避免背景控件收到输入 */
    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (removeConfirmVisible) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    /** 弹窗显示时 ESC 只关弹窗，不关闭整个管理界面 */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (removeConfirmVisible) {
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                closeRemoveConfirm();
            }
            // 弹窗是模态的：其它按键也不得传给背景控件（此前只有 ESC 被吞）
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** 弹窗显示时屏蔽背景列表滚动（1.20.x 为 3 参签名） */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (removeConfirmVisible) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    /** 在工具按钮左侧绘制手绘复选框（不可见时跳过） */
    private static void drawButtonCheckBox(GuiGraphics graphics, Button button, boolean checked) {
        if (button != null && button.visible) {
            drawCheckBox(graphics, button.getX() + 5,
                    button.getY() + (button.getHeight() - CHECK_SIZE) / 2, checked);
        }
    }

    /**
     * 手绘复选框：MC 默认字体不含 U+2610/U+2611（☐/☑）字形，直接写字符只会得到空方框（豆腐块），
     * 因此所有勾选状态一律用像素绘制：1px 暗色边框 + 深色内底 + 选中时金色对勾。
     */
    private static final int CHECK_SIZE = 9;
    private static final int CHECK_BORDER = 0xFFA89070;
    private static final int CHECK_INNER = 0xFF18120C;
    private static final int CHECK_MARK = 0xFFFFD700;
    /** 对勾图案（7×7 网格内 1=填充像素）：左下短笔 + 向右上挑的长笔 */
    private static final int[][] CHECK_PIXELS = {
            {5, 0},
            {4, 1}, {5, 1},
            {3, 2}, {4, 2},
            {1, 3}, {2, 3}, {3, 3}, {4, 3},
            {0, 4}, {1, 4}, {2, 4}, {3, 4},
            {1, 5}, {2, 5},
            {2, 6}
    };

    /** 在 (x,y) 绘制 CHECK_SIZE×CHECK_SIZE 复选框；checked=true 时填充金色对勾 */
    private static void drawCheckBox(GuiGraphics graphics, int x, int y, boolean checked) {
        graphics.fill(x, y, x + CHECK_SIZE, y + CHECK_SIZE, CHECK_BORDER);
        graphics.fill(x + 1, y + 1, x + CHECK_SIZE - 1, y + CHECK_SIZE - 1, CHECK_INNER);
        if (checked) {
            for (int[] p : CHECK_PIXELS) {
                // 内底区域为 7×7（x+1..x+7），像素逐格填充
                graphics.fill(x + 1 + p[0], y + 1 + p[1], x + 2 + p[0], y + 2 + p[1], CHECK_MARK);
            }
        }
    }

    /** GuiGraphics.drawCenteredString 默认 dropShadow=true，阴影会把彩色字糊成一团。
     *  这里手动计算居中 + drawString(Component, ..., shadow=false) 得到干净的文字。 */
    private static void drawCenteredStringNoShadow(GuiGraphics g, net.minecraft.client.gui.Font font,
                                                   Component text, int centerX, int y, int color) {
        int w = font.width(text);
        g.drawString(font, text, centerX - w / 2, y, color, false);
    }

    /**
     * 按像素宽度把反馈文本折成多行（最多 maxLines 行，超出部分以省略号标注截断；
     * 完整文本始终会经聊天栏送达，不依赖界面）。
     */
    /** 只保留最近一次的折行结果：该方法在渲染路径上每帧调用，而反馈文本通常连续多帧不变。
     *  用单条缓存代替原先的无上限 HashMap，避免长会话里把每条历史反馈文本（含文件名/计数）都留在内存。 */
    private static String feedbackWrapCacheKey;
    private static List<String> feedbackWrapCacheLines;

    private static void cacheWrapResult(String key, List<String> lines) {
        feedbackWrapCacheKey = key;
        feedbackWrapCacheLines = lines;
    }

    private static List<String> wrapFeedback(String text, int maxWidth, int maxLines) {
        String cacheKey = maxWidth + "|" + maxLines + "|" + text;
        if (cacheKey.equals(feedbackWrapCacheKey) && feedbackWrapCacheLines != null) {
            return feedbackWrapCacheLines;
        }
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int curWidth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int w = net.minecraft.client.Minecraft.getInstance().font.width(String.valueOf(c));
            if (cur.length() > 0 && curWidth + w > maxWidth) {
                lines.add(cur.toString());
                cur = new StringBuilder();
                curWidth = 0;
                if (lines.size() >= maxLines) {
                    int last = lines.size() - 1;
                    lines.set(last, lines.get(last) + "…");
                    cacheWrapResult(cacheKey, lines);
                    return lines;
                }
            }
            cur.append(c);
            curWidth += w;
        }
        if (cur.length() > 0 && lines.size() < maxLines) {
            lines.add(cur.toString());
        }
        return lines;
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
                lines.add(Component.literal(
                        info.customName() != null
                                ? info.customName() + " · " + ModelNames.displayModelId(info.modelId())
                                : ModelNames.displayModelId(info.modelId()))
                        .withStyle(ChatFormatting.GOLD));
                lines.add(Component.literal("Model ID: " + ModelNames.display(info.modelId()))
                        .withStyle(ChatFormatting.GRAY));
                lines.add(Component.translatable("maid_file_manager.gui.tooltip.favorability_health",
                        info.favorability(),
                        String.format(Locale.ROOT, "%.0f", info.health()),
                        String.format(Locale.ROOT, "%.0f", info.maxHealth()))
                        .withStyle(ChatFormatting.WHITE));
                if (info.ownerName() != null) {
                    lines.add(Component.translatable("maid_file_manager.gui.label.owner", info.ownerName())
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
                    lines.add(Component.translatable("maid_file_manager.gui.label.time",
                                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(ts)))
                            .withStyle(ChatFormatting.GRAY));
                }
                lines.add(Component.translatable("maid_file_manager.gui.import.located_in")
                        .withStyle(ChatFormatting.DARK_GRAY));
                g.renderTooltip(this.font, lines, Optional.empty(), mouseX, mouseY);
            }
        }
    }

    @Override
    public void onMaidListReceived(List<MaidInfo> list) {
        if (serverExportMode) {
            // 迟到防护（与 onServerExportListReceived 对称）：统一导出模式下 maidList 承载的是
            // 服务端回包的全员列表；切模式前发出的"本人女仆列表"响应若在此刻到达就会覆盖它，
            // 造成列表与 maidOwnerByEntityId 不一致 → 全选导出时部分条目玩家名为空被跳过。
            return;
        }
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
        setFeedback(Component.translatable("maid_file_manager.gui.server_export.list_loaded",
                this.serverGroups.size(), maidList.size(), denied));
        // 消费点必须校验 15s 抑制窗口：确认屏的列表请求在「非 OP / 组数 >128 / 整包 >1 MiB」
        // 三种情况下只回 ID_FEEDBACK 不回列表 → 标志残留；窗口到期后任何一次列表成功回包
        //（例如点「刷新」）都会触发「全选 + 直接提交导出」，违背玩家意图。
        // 统一导出确认流程：列表已就绪（maidOwnerByEntityId 已填）→ 现在全选并提交才有意义
        if (pendingServerExport && System.currentTimeMillis() < serverExportSuppressFeedbackUntil) {
            pendingServerExport = false;
            selectAllState = true;
            refreshSelectAllLabel();
            onSelectAllToggled(true);
            if (selectedMaidIds.isEmpty()) {
                setFeedback(Component.translatable("maid_file_manager.gui.export.no_selection"));
            } else {
                onActionButtonClick(this.actionBtn);
            }
        }
        else if (pendingServerExport) {
            // 窗口已过：视为列表获取失败，丢弃待办并提示重试（不要静默全量导出）
            pendingServerExport = false;
            serverExportSuppressFeedbackUntil = 0L;
            setFeedback(Component.translatable("maid_file_manager.gui.server_export.list_failed"));
        }
    }

    @Override
    public void onExportResultReceived(List<MaidFileData> dataList) {
        if (dataList == null || dataList.isEmpty()) {
            this.exportInFlight = false;   // 空批次未登记待删，直接放开
            setFeedback(Component.translatable("maid_file_manager.export.fail.empty"));
            return;
        }
        // 写盘投递到后台线程：这个方法在网络回调（已回主线程）里逐条做同步磁盘 IO，
        // 批量导出 64 只女仆 = 64 次 NBT 序列化 + 64 次文件写（每次还要探测重名），
        // 单人世界里红石/刷怪塔在跑时主线程会明显卡顿。
        // 注意：
        //  - gameDir / dir / 显示名在主线程先算好（显示名要读语言表，不能跨线程）；
        //  - MaidFileData 只读，跨线程安全；MaidFileIo 全是静态纯 IO；
        //  - 回执与 UI 更新必须回主线程。
        final Path gameDir = this.minecraft.gameDirectory.toPath().toAbsolutePath();
        final String playerName = this.minecraft.player != null ? this.minecraft.player.getName().getString() : "";
        final Path dir;
        try {
            dir = MaidFileIo.resolvePlayerDir(MaidFileIo.ensureExportsDir(gameDir), playerName);
            Files.createDirectories(dir);
        } catch (Exception e) {
            Constants.LOG.error("[maid_file_manager] Failed to prepare export dir", e);
            IMaidFileNetwork net = IMaidFileNetwork.Holder.get();
            if (net != null) {
                net.sendExportWriteAck(false);
                this.exportInFlight = false;   // 已回执失败，允许重试
            }
            setFeedback(Component.translatable("maid_file_manager.export.fail.local_save"));
            return;
        }
        // 显示名必须在主线程算好（ModelNames 会读客户端语言表）
        final java.util.List<String> displayNames = new java.util.ArrayList<>(dataList.size());
        for (MaidFileData data : dataList) {
            if (data == null) {
                displayNames.add(null);
                continue;
            }
            String dn = data.getDisplayName() != null
                    ? ModelNames.displayModelId(data.getModelId())
                    : (data.getModelId() != null ? ModelNames.displayModelId(data.getModelId()) : "unknown");
            displayNames.add(MaidFileIo.composeDisplayName(data.getCustomName(), dn));
        }
        final Minecraft mc = this.minecraft;
        Thread worker = new Thread(() -> {
            int success = 0;
            int failed = 0;
            String lastFileName = null;
            boolean cataclysm = false;
            for (int i = 0; i < dataList.size(); i++) {
                MaidFileData data = dataList.get(i);
                String displayName = displayNames.get(i);
                if (data == null || displayName == null) {
                    failed++;
                    continue;
                }
                try {
                    String fileName = MaidFileIo.writeMaidFile(dir, displayName, data.getModelId(),
                            data.getOwnerUuid(), data, LocalDateTime.now());
                    lastFileName = fileName;
                    success++;
                    Constants.LOG.info("[maid_file_manager] Export saved locally: {}", dir.resolve(fileName));
                } catch (Throwable t) {
                    failed++;
                    cataclysm = true;
                    Constants.LOG.error("[maid_file_manager] Failed to save export file locally", t);
                }
            }
            final int fSuccess = success;
            final int fFailed = failed;
            final String fLast = lastFileName;
            final boolean fCatastrophic = cataclysm;
            mc.execute(() -> {
                IMaidFileNetwork ackNet = IMaidFileNetwork.Holder.get();
                if (ackNet != null) {
                    ackNet.sendExportWriteAck(fFailed == 0);
                    this.exportInFlight = false;   // 回执已发，允许下一批导出
                }
                if (fCatastrophic && fSuccess == 0) {
                    setFeedback(Component.translatable("maid_file_manager.export.fail.local_save"));
                    return;
                }
                Component fb;
                if (fFailed == 0) {
                    String playerDirName = MaidFileIo.sanitizePlayerName(playerName);
                    fb = Component.translatable("maid_file_manager.gui.export.success_files",
                            fSuccess, playerDirName, (fSuccess == 1 ? (fLast != null ? fLast : "") : "…"));
                    Constants.LOG.info("[maid_file_manager] Batch export SUCCESS: count={}, keep={}", fSuccess, keepMaidsInWorldState);
                } else {
                    fb = Component.translatable("maid_file_manager.gui.export.partial", fSuccess, fFailed);
                    Constants.LOG.warn("[maid_file_manager] Batch export PARTIAL: ok={} fail={}", fSuccess, fFailed);
                }
                setFeedback(fb);
                if (this.minecraft != null && this.minecraft.player != null) {
                    this.minecraft.player.displayClientMessage(fb, false);
                }
            });
        }, "maidfm-export-writer");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void onFeedbackReceived(Component message) {
        // 【v4-3Fix 截图1/3：统一导出异步期间（15s）只写聊天栏不写底部黄字，避免"共成功0个"覆盖主界面"请求已发送"】
        //   服务端异步结果本身就会通过聊天栏 displayClientMessage 显示（下方），主界面底部黄字仅显示同步即时状态。
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
    public void onImportBatchResultReceived(Component summary, List<Boolean> spawned) {
        setFeedback(summary);
        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.displayClientMessage(summary, false);
        }
        if (pendingImportFileNames.isEmpty() || spawned == null || spawned.isEmpty()) {
            pendingImportFileNames.clear();
            return;
        }
        int deleted = 0;
        int failed = 0;
        try {
            Path gameDir = this.minecraft.gameDirectory.toPath().toAbsolutePath();
            Path dir = MaidFileIo.ensureImportsDir(gameDir);
            int n = Math.min(pendingImportFileNames.size(), spawned.size());
            for (int i = 0; i < n; i++) {
                if (!spawned.get(i)) {
                    failed++;
                    continue;
                }
                String fileName = pendingImportFileNames.get(i);
                Path file = dir.resolve(fileName).normalize();
                if (!file.startsWith(dir)) {
                    Constants.LOG.warn("[maid_file_manager] delete-after-import: path traversal blocked: {}", fileName);
                    failed++;
                    continue;
                }
                try {
                    Files.deleteIfExists(file);
                    selectedImportFileNames.remove(fileName);
                    fileList.remove(fileName);
                    deleted++;
                } catch (Exception e) {
                    Constants.LOG.warn("[maid_file_manager] delete-after-import: failed to delete: {}", fileName, e);
                    failed++;
                }
            }
        } catch (Exception e) {
            Constants.LOG.error("[maid_file_manager] delete-after-import: unexpected error", e);
        } finally {
            pendingImportFileNames.clear();
        }
        Constants.LOG.info("[maid_file_manager] delete-after-import: deleted={}, failed={}", deleted, failed);
        fileListWidget.refresh();
    }

    /** 请求某女仆的档案：服务端读取后回发，由 {@link #onMaidProfileReceived} 打开档案界面 */
    private void requestMaidProfile(int entityId) {
        IMaidFileNetwork net = IMaidFileNetwork.Holder.get();
        if (net == null) {
            Constants.LOG.warn("[maid_file_manager] 网络实现缺失，无法请求女仆档案");
            return;
        }
        pendingProfileEntityId = entityId;
        pendingProfileSince = System.currentTimeMillis();
        net.sendRequestMaidProfile(entityId);
    }

    @Override
    public void onMaidProfileReceived(MaidProfileView view) {
        if (view == null || this.minecraft == null) {
            return;
        }
        // 只处理本次请求的返回，避免过期请求把玩家正在看的界面顶掉
        // 条件必须是「没有待处理请求 **或** entityId 不匹配」都拒绝：pending == MIN_VALUE 表示
        // 当前没有请求，此时任何回包都是迟到的（上次请求超时重发、服务端延迟送达），
        // 不该 setScreen 把玩家正在看的界面（备份屏 / 正在输入）顶掉。
        // 原写成 pending != MIN_VALUE && id != pending，等于「没有请求时接受一切」。
        if (pendingProfileEntityId == Integer.MIN_VALUE || view.entityId() != pendingProfileEntityId) {
            return;
        }
        pendingProfileEntityId = Integer.MIN_VALUE;
        pendingProfileSince = 0L;
        this.minecraft.setScreen(new MaidProfileScreen(this, view));
    }

    @Override
    public void onClose() {
        IMaidFileNetwork.ClientHandlerHolder.set(null);
        super.onClose();
    }

    // ---------- MaidListWidget ----------

    private final class MaidListWidget extends ObjectSelectionList<MaidListWidget.ListRow> {
        private final int listX, listW, listTop, listBottom;

        /** 列表行基类：让女仆行 Entry 与玩家标题行 HeaderEntry 可共存于同一个列表 */
        abstract static class ListRow extends ObjectSelectionList.Entry<ListRow> {
            /** 清掉上一帧记录的命中区：只有本帧真正渲染过的行才允许被点中 */
            void resetHitArea() {
            }
        }

        MaidListWidget(int listX, int listW, int listTop, int listBottom) {
            // 1.20.x 列表构造器为 6 参（mc, 宽, 高, top, bottom, 行高）
            super(MaidFileManagerScreen.this.minecraft, listW,
                    listBottom - listTop, listTop, listBottom, ROW_HEIGHT);
            this.listX = listX;
            this.listW = listW;
            this.listTop = listTop;
            this.listBottom = listBottom;
            // 1.20.x 的 AbstractSelectionList 不继承 AbstractWidget，没有 setX/setY/setWidth，
            // 几何直接赋 protected 字段；自绘 render 的 scissor、行定位与滚动量计算都以这四个值为唯一基准
            this.x0 = listX;
            this.x1 = listX + listW;
            this.y0 = listTop;
            this.y1 = listBottom;
            this.width = listW;
            this.height = listBottom - listTop;
            refresh();
        }

        @Override
        public int getRowWidth() {
            return this.listW - 8;
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

        void refresh() {
clearEntries();
// 父类 clearEntries() 不重置滚动量：残留 scrollAmount 会让列表白屏、hitRow 算错行
setScrollAmount(0);
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
            // 容错从 ±12 收窄到 ±2：原先可以把鼠标放到面板外一整条区域仍命中，容易点穿到相邻控件
            return mouseX >= this.listX - 2 && mouseX <= this.listX + this.listW + 2
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

        // 1.20.x mouseScrolled 为 3 参（1.21 起才增加 horizontal 参数）
        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
            if (!this.isMouseOver(mouseX, mouseY)) return false;
            return super.mouseScrolled(mouseX, mouseY, delta);
        }

        /** v14 FabricFix【Fabric 生产环境列表行不可见根治】：Fabric 运行时的字段/方法全是 intermediary 名，
         *  官方名(top/y0 等)的字符串反射全部静默失败 → 父类 scissor/行坐标/滚动条全部错位（行画在看不见的位置）。
         *  改为完全自绘：不调 super.render，用本类 listX/listTop/listW/listBottom 裁剪+摆行+画滚动条；
         *  滚动量复用父类 getScrollAmount()（编译期调用会被 loom 重映射，跨加载器稳定）。
         *  1.20.x 列表不继承 AbstractWidget，覆写的是 public render 而非 renderWidget，
         *  且没有 visible 字段（显隐由 Screen 的 removeWidget/addRenderableWidget 控制）。 */
        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(this.listX, this.listTop,
                    this.listX + this.listW, this.listBottom, LIST_BG);
            java.util.List<ListRow> rows = this.children();
            // 每帧在**画行之前**清命中区：只有本帧真正画过的行才可命中。
            // 注意不能放 hitRow()：它被 drawHoverTooltip 每帧调用，放在那里会在帧末把所有行清成 -1，
            // 导致行内「档案」按钮与 isMouseOver 永久拿不到 lastTop（第六轮 P1-1）。
            for (ListRow row : rows) {
                row.resetHitArea();
            }
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
            // 滚动条：内容溢出时贴列表右缘画（位置基于我们自己的 listX/listW，不再依赖父类内部几何）
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

        /** 统一导出浏览：玩家标题行（可点击展开/收起该玩家的女仆列表） */
        final class HeaderEntry extends ListRow {
            final IMaidFileNetwork.PlayerMaidGroup group;
            int lastTop = -1;
            int lastHeight = -1;

            @Override
            void resetHitArea() {
                this.lastTop = -1;
                this.lastHeight = -1;
            }

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
                // 右侧留 5px 给自绘滚动条：滚动条画在 listX+listW-3..+2，完全落在行矩形内，
                // 不剔除的话点滚动条会被判成点行（切换勾选），且命中即 return true，
                // 父类 updateScrollingState() 永远拿不到点击 → 滚动条无法拖动。
                int rowW = MaidListWidget.this.listW - 5;
                if (hovering) {
                    graphics.fill(rowX, top - 1, rowX + rowW, top + height + 1, ROW_HOVER);
                }
                net.minecraft.client.gui.Font font = MaidFileManagerScreen.this.font;
                if (group == null) {
                    graphics.drawString(font, Component.translatable("maid_file_manager.gui.server_export.empty"),
                            rowX + 28, top + 6, SUBTEXT_COLOR, false);
                    return;
                }
                boolean collapsed = collapsedPlayers.contains(group.playerName());
                String arrow = collapsed ? "▶" : "▼";
                String consentTag = group.consented() ? "" : Component.translatable("maid_file_manager.gui.server_export.no_consent_tag").getString();
                String line = Component.translatable("maid_file_manager.gui.server_export.player_row",
                        arrow, group.playerName(), group.maids().size(), consentTag).getString();
                int color = group.consented() ? ACCENT : SUBTEXT_COLOR;
                graphics.fill(rowX, top, rowX + rowW, top + height, 0x30FFFFFF);
                graphics.drawString(font, Component.literal(line), rowX + 28, top + 6, color, false);
            }

            @Override
            public boolean isMouseOver(double mouseX, double mouseY) {
                int rowX = MaidListWidget.this.listX;
                int rowW = MaidListWidget.this.listW;
                return mouseX >= rowX && mouseX <= rowX + rowW - 5
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
                    // 折叠时把该组女仆从选中集移除：否则「全选」勾中的是展平全集（含不可见行），
                    // 界面显示的勾选数比屏幕上能看到的行多很多，且 allSelected 判断永远算不出
                    // 「已全选」，导出时那些看不见的条目仍会被提交。
                    for (IMaidFileNetwork.PlayerMaidGroup g : MaidFileManagerScreen.this.serverGroups) {
                        if (g.playerName().equals(name)) {
                            for (MaidInfo info : g.maids()) {
                                MaidFileManagerScreen.this.selectedMaidIds.remove(info.entityId());
                            }
                            break;
                        }
                    }
                    MaidFileManagerScreen.this.selectAllState = false;
                    MaidFileManagerScreen.this.refreshSelectAllLabel();
                }
                MaidListWidget.this.refresh();
                return true;
            }

            @Override
            public Component getNarration() {
                return group == null ? Component.translatable("maid_file_manager.gui.server_export.empty_group")
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

            @Override
            void resetHitArea() {
                this.lastTop = -1;
                this.lastHeight = -1;
            }

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
                // 勾选框 + 文字：rowX 对齐 LIST_BG 左边缘（手绘复选框，不用缺字字符）
                drawCheckBox(graphics, rowX + (rowW < 190 ? 4 : 28), top + 4, selected);
                int textLeft = rowX + (rowW < 190 ? 18 : 49);
                // 右侧为「档案」按钮预留空间，文字区主动避让，避免被按钮压住
                int contentRight = rowW < 190 ? rowX + rowW - 4 : rowX + rowW - 10 - PROFILE_BTN_W - 6;
                int contentW = contentRight - textLeft;
                net.minecraft.client.gui.Font font = MaidFileManagerScreen.this.font;
                // 第一行：name 超长 → 截断+省略号…（不滚避免勾选框右晃动）
                String name = info.customName() != null
                        ? info.customName() + " · " + ModelNames.displayModelId(info.modelId())
                        : ModelNames.displayModelId(info.modelId());
                String drawName;
                if (font.width(name) > contentW) {
                    drawName = font.plainSubstrByWidth(name, contentW - font.width("…")) + "…";
                } else {
                    drawName = name;
                }
                graphics.drawString(font, Component.literal(drawName), textLeft, top + 2, TEXT_COLOR, false);
                // 第二行：detail 超长 → 左右自动跑马灯（1.2s头停→向左滚60px/s→1.2s尾停→400ms快速回卷）
                String detail = Component.translatable("maid_file_manager.gui.maid_row.detail",
                        info.modelId(), info.favorability(),
                        String.format(Locale.ROOT, "%.0f", info.health()),
                        String.format(Locale.ROOT, "%.0f", info.maxHealth()),
                        info.tamed()
                                ? Component.translatable("maid_file_manager.gui.label.tamed")
                                : Component.translatable("maid_file_manager.gui.label.untamed")).getString();
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
                drawProfileButton(graphics, rowX + rowW, top, height, mouseX, mouseY);
            }

            /** 绘制行内「档案」按钮（右边缘对齐 rowRight） */
            private void drawProfileButton(GuiGraphics graphics, int rowRight, int top, int height,
                                           int mouseX, int mouseY) {
                // 统一导出模式下列表含其他玩家的女仆，不提供「档案」入口（仅本人女仆可查看档案）
                if (MaidFileManagerScreen.this.serverExportMode) {
                    return;
                }
                // 窄屏（列表区放不下按钮+文字）时不画/不命中「档案」按钮
                if (listW < 190) {
                    return;
                }
                int bx = rowRight - PROFILE_BTN_W - 4;
                int by = top + (height - PROFILE_BTN_H) / 2;
                boolean hover = mouseX >= bx && mouseX <= bx + PROFILE_BTN_W
                        && mouseY >= by && mouseY <= by + PROFILE_BTN_H;
                graphics.fill(bx, by, bx + PROFILE_BTN_W, by + PROFILE_BTN_H, hover ? 0xFF4A3826 : 0xFF3A2C1C);
                graphics.renderOutline(bx, by, PROFILE_BTN_W, PROFILE_BTN_H, PANEL_BORDER);
                Component label = Component.translatable("maid_file_manager.gui.button.profile");
                int tx = bx + (PROFILE_BTN_W - MaidFileManagerScreen.this.font.width(label)) / 2;
                graphics.drawString(MaidFileManagerScreen.this.font, label, tx,
                        by + (PROFILE_BTN_H - 8) / 2, TEXT_COLOR, false);
            }

            /** 是否命中行内「档案」按钮 */
            private boolean isProfileButtonHit(double mouseX, double mouseY) {
                if (MaidFileManagerScreen.this.serverExportMode || lastTop < 0) {
                    return false;
                }
                int rowRight = MaidListWidget.this.listX + MaidListWidget.this.listW;
                // 窄屏（列表区放不下按钮+文字）时不画/不命中「档案」按钮
                if (listW < 190) {
                    return false;
                }
                int bx = rowRight - PROFILE_BTN_W - 4;
                int by = lastTop + (lastHeight - PROFILE_BTN_H) / 2;
                return mouseX >= bx && mouseX <= bx + PROFILE_BTN_W
                        && mouseY >= by && mouseY <= by + PROFILE_BTN_H;
            }

            @Override
            public boolean isMouseOver(double mouseX, double mouseY) {
                int rowX = MaidListWidget.this.listX;
                int rowW = MaidListWidget.this.listW;
                return mouseX >= rowX && mouseX <= rowX + rowW - 5
                        && this.lastTop >= 0
                        && mouseY >= this.lastTop - 2
                        && mouseY <= this.lastTop + this.lastHeight + 2;
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                // 命中「档案」按钮 → 请求该女仆档案（不再触发勾选）
                if (button == 0 && isProfileButtonHit(mouseX, mouseY)) {
                    requestMaidProfile(info.entityId());
                    return true;
                }
                boolean newVal = !selectedMaidIds.contains(info.entityId());
                toggleRowSelection(info, newVal);
                return true;
            }

            @Override
            public Component getNarration() {
                boolean sel = selectedMaidIds.contains(info.entityId());
                String name = info.customName() != null
                        ? info.customName() + " · " + ModelNames.displayModelId(info.modelId())
                        : ModelNames.displayModelId(info.modelId());
                return Component.translatable(sel
                        ? "maid_file_manager.gui.label.selected"
                        : "maid_file_manager.gui.label.not_selected").append(name);
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
            // 几何直接赋 protected 字段（与 MaidListWidget 同一套唯一基准）
            this.x0 = listX;
            this.x1 = listX + listW;
            this.y0 = listTop;
            this.y1 = listBottom;
            this.width = listW;
            this.height = listBottom - listTop;
            refresh();
        }

        @Override
        public int getRowWidth() {
            return this.listW - 8;
        }

        void refresh() {
clearEntries();
// 父类 clearEntries() 不重置滚动量：残留 scrollAmount 会让列表白屏、hitRow 算错行
setScrollAmount(0);
            for (String fileName : fileList) {
                addEntry(new Entry(fileName));
            }
        }

        // ====== v13 交互兜底：同 MaidListWidget（绕开父类 getEntryAtPosition） ======
        @Override
        public boolean isMouseOver(double mouseX, double mouseY) {
            // 容错从 ±12 收窄到 ±2：原先可以把鼠标放到面板外一整条区域仍命中，容易点穿到相邻控件
            return mouseX >= this.listX - 2 && mouseX <= this.listX + this.listW + 2
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
        public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
            if (!this.isMouseOver(mouseX, mouseY)) return false;
            return super.mouseScrolled(mouseX, mouseY, delta);
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

        /** v14 FabricFix：与 MaidListWidget 相同的自绘渲染（Fabric intermediary 环境下父类几何反射失效） */
        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(this.listX, this.listTop,
                    this.listX + this.listW, this.listBottom, LIST_BG);
            java.util.List<Entry> rows = this.children();
            // 导入列表同样每帧清命中区（第五轮 P1-5 只修了导出列表，导入列表漏了 → 第六轮 P1-2）：
            // 不清的话滚出视口的行保留旧 lastTop，点某文件会勾中看不见的另一个文件，
            // 配合「导入后删除源文件」会删错文件。
            for (Entry row : rows) {
                row.resetHitArea();
            }
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

        final class Entry extends ObjectSelectionList.Entry<Entry> {
            final String fileName;
            // 修复 v1.1.2 UI bug：与 MaidListWidget.Entry 同款问题，isMouseOver 必须用 lastTop/lastHeight 限定 mouseY 范围
            int lastTop = -1;
            int lastHeight = -1;

            /** 清掉上一帧记录的命中区（本类不继承 ListRow，故无 @Override） */
            void resetHitArea() {
                this.lastTop = -1;
                this.lastHeight = -1;
            }

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
                // 勾选框：rowX 对齐 LIST_BG 左边缘（手绘复选框，不用缺字字符）
                drawCheckBox(graphics, rowX + (rowW < 190 ? 4 : 28), top + 4, selected);
                int textLeft = rowX + (rowW < 190 ? 18 : 49);
                // 右侧为「档案」按钮预留空间，文字区主动避让，避免被按钮压住
                int contentRight = rowW < 190 ? rowX + rowW - 4 : rowX + rowW - 10 - PROFILE_BTN_W - 6;
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
                String time = MaidFileIo.formatDisplayTime(fileName);
                String timeLine = Component.translatable("maid_file_manager.gui.label.time", time).getString();
                String drawTime;
                if (font.width(timeLine) > contentW) {
                    drawTime = font.plainSubstrByWidth(timeLine, contentW - font.width("…")) + "…";
                } else {
                    drawTime = timeLine;
                }
                graphics.drawString(font, Component.literal(drawTime), textLeft, top + 12, SUBTEXT_COLOR, false);
                drawProfileButton(graphics, rowX + rowW, top, height, mouseX, mouseY);
            }

            /** 绘制行内「档案」按钮（导入列表用文件内档案快照预览） */
            private void drawProfileButton(GuiGraphics graphics, int rowRight, int top, int height,
                                           int mouseX, int mouseY) {
                // 窄屏（列表区放不下按钮+文字）时不画/不命中「档案」按钮
                if (listW < 190) {
                    return;
                }
                int bx = rowRight - PROFILE_BTN_W - 4;
                int by = top + (height - PROFILE_BTN_H) / 2;
                boolean hover = mouseX >= bx && mouseX <= bx + PROFILE_BTN_W
                        && mouseY >= by && mouseY <= by + PROFILE_BTN_H;
                graphics.fill(bx, by, bx + PROFILE_BTN_W, by + PROFILE_BTN_H, hover ? 0xFF4A3826 : 0xFF3A2C1C);
                graphics.renderOutline(bx, by, PROFILE_BTN_W, PROFILE_BTN_H, PANEL_BORDER);
                Component label = Component.translatable("maid_file_manager.gui.button.profile");
                int tx = bx + (PROFILE_BTN_W - MaidFileManagerScreen.this.font.width(label)) / 2;
                graphics.drawString(MaidFileManagerScreen.this.font, label, tx,
                        by + (PROFILE_BTN_H - 8) / 2, TEXT_COLOR, false);
            }

            /** 是否命中行内「档案」按钮 */
            private boolean isProfileButtonHit(double mouseX, double mouseY) {
                if (lastTop < 0) {
                    return false;
                }
                int rowRight = FileListWidget.this.listX + FileListWidget.this.listW;
                // 窄屏（列表区放不下按钮+文字）时不画/不命中「档案」按钮
                if (listW < 190) {
                    return false;
                }
                int bx = rowRight - PROFILE_BTN_W - 4;
                int by = lastTop + (lastHeight - PROFILE_BTN_H) / 2;
                return mouseX >= bx && mouseX <= bx + PROFILE_BTN_W
                        && mouseY >= by && mouseY <= by + PROFILE_BTN_H;
            }

            /** 打开该导入文件内的档案快照（只读预览；文件无档案数据时提示） */
            /** 打开该导入文件内的档案快照（只读预览；文件无档案数据时提示） */
            private void openOfflineProfile() {
                // readMaidFile 要解 GZIP + 解析 NBT，必须离开渲染线程（第六轮 M8）。
                final Path snapGameDir = MaidFileManagerScreen.this.minecraft != null
                        ? MaidFileManagerScreen.this.minecraft.gameDirectory.toPath().toAbsolutePath() : null;
                final String snapFileName = fileName;
                if (snapGameDir == null) {
                    return;
                }
                Thread snapWorker = new Thread(() -> {
                    MaidProfileView view = null;
                    boolean noProfile = false;
                    try {
                        Path dir = MaidFileIo.ensureImportsDir(snapGameDir);
                        Path file = dir.resolve(snapFileName).normalize();
                        if (!file.startsWith(dir)) {
                            return;
                        }
                        MaidFileData data = MaidFileIo.readMaidFile(file);
                        if (data == null) {
                            return;
                        }
                        if (data.getProfile() == null) {
                            noProfile = true;
                        } else {
                            view = MaidProfileScreen.buildOfflineView(data);
                        }
                    } catch (Throwable t) {
                        Constants.LOG.warn("[maid_file_manager] 打开导入文件档案失败: {}", t.toString());
                        return;
                    }
                    if (MaidFileManagerScreen.this.minecraft == null) {
                        return;
                    }
                    final MaidProfileView outView = view;
                    final boolean outNoProfile = noProfile;
                    MaidFileManagerScreen.this.minecraft.execute(() -> {
                        if (outNoProfile) {
                            MaidFileManagerScreen.this.setFeedback(
                                    Component.translatable("maid_file_manager.profile.not_in_file"));
                            return;
                        }
                        if (outView == null || MaidFileManagerScreen.this.minecraft == null) {
                            return;
                        }
                        MaidFileManagerScreen.this.minecraft.setScreen(
                                new MaidProfileScreen(MaidFileManagerScreen.this, outView));
                    });
                }, "maidfm-snapshot-read");
                snapWorker.setDaemon(true);
                snapWorker.start();
            }

            @Override
            public boolean isMouseOver(double mouseX, double mouseY) {
                int rowX = FileListWidget.this.listX;
                int rowW = FileListWidget.this.listW;
                return mouseX >= rowX && mouseX <= rowX + rowW - 5
                        && this.lastTop >= 0
                        && mouseY >= this.lastTop - 2
                        && mouseY <= this.lastTop + this.lastHeight + 2;
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                // 命中「档案」按钮 → 打开该文件内的档案快照（只读），不再触发勾选
                if (button == 0 && isProfileButtonHit(mouseX, mouseY)) {
                    openOfflineProfile();
                    return true;
                }
                boolean newVal = !selectedImportFileNames.contains(fileName);
                toggleFileRowSelection(fileName, newVal);
                return true;
            }

            @Override
            public Component getNarration() {
                boolean sel = selectedImportFileNames.contains(fileName);
                return Component.translatable(sel
                        ? "maid_file_manager.gui.label.selected"
                        : "maid_file_manager.gui.label.not_selected").append(fileName);
            }
        }
    }

    // ============= 左上角「🌐 统一导出」入口 → 独立确认界面 / 返回单独导出（模式动态切换，按需求新增） =============

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

    /** 统一导出全员独立确认界面（按需求新增的独立界面；确认后回到主界面，切换到统一导出模式并自动执行导出） */
    public static class MaidServerExportScreen extends Screen {
        private final MaidFileManagerScreen parent;
        /** 确认屏面板的理想高度（内容够用时用它；屏幕不够高时会被 clamp 到 height-16） */
        private static final int CONFIRM_PANEL_H = 240;

        private final boolean hasPermission;

        public MaidServerExportScreen(MaidFileManagerScreen parent) {
            super(Component.translatable("maid_file_manager.gui.server_export.confirm_title"));
            this.parent = parent;
            this.hasPermission = parent.minecraft != null && parent.minecraft.player != null
                    && parent.minecraft.player.hasPermissions(2);
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
            parent.pendingServerExport = true;
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
            graphics.fill(0, 0, this.width, this.height, SCREEN_BG);
            int panelW = Math.min(380, this.width - 16);
            // 面板高度自适应：与 init() 的按钮定位共用同一公式，避免两处不一致
            int panelH = Math.min(CONFIRM_PANEL_H, this.height - 16);
            int px = (this.width - panelW) / 2;
            int py = (this.height - panelH) / 2;
            graphics.fill(px, py, px + panelW, py + panelH, PANEL_BG);
            graphics.renderOutline(px, py, panelW, panelH, PANEL_BORDER);
            drawTextBlock(graphics, cx, py);
            super.render(graphics, mouseX, mouseY, partialTick);
        }

        /** 绘制标题与说明文字（纯文本，无副作用） */
        private void drawTextBlock(GuiGraphics graphics, int cx, int py) {
            int y = py + 20;
            drawCenteredNoShadow(graphics,
                    Component.translatable("maid_file_manager.gui.server_export.confirm_header").getString(),
                    cx, y, HEADER_COLOR);
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
                    graphics.drawString(this.font, seq, cx - this.font.width(seq) / 2, y, TEXT_COLOR, false);
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

}
