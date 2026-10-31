package io.github.zgxhzhr.maidfm.client;

import io.github.zgxhzhr.maidfm.Constants;
import io.github.zgxhzhr.maidfm.data.MaidFileData;
import io.github.zgxhzhr.maidfm.data.MaidFileIo;
import io.github.zgxhzhr.maidfm.data.MaidInfo;
import io.github.zgxhzhr.maidfm.data.MaidProfileView;
import io.github.zgxhzhr.maidfm.network.IMaidFileNetwork;
import io.github.zgxhzhr.maidfm.service.MaidBackupService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.Util;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 女仆备份管理（备份浏览器）。
 *
 * <p>用途：浏览车万女仆（TLM）自动备份产出的 .dat 数据，并按需导出为本模组规范的
 * .maid 文件（命名规则与普通导出一致）。
 *
 * <p>数据来源分两种：
 * <ul>
 *   <li>单人世界与客户端发起的局域网联机：客户端即集成服务端，备份就在本机，直接读取；</li>
 *   <li>专业服务端：备份在服务器磁盘，向服务端请求——OP 可见全部玩家的备份，
 *       非 OP 仅可见自己名下的备份；导出后的 .maid 始终落在客户端本机。</li>
 * </ul>
 *
 * <p>列表按「存档 → 玩家（主人 UUID）→ 女仆（女仆 UUID）→ 备份文件」四层组织：
 * 游戏主菜单没有「当前存档」上下文，故顶层先按存档分组；专业服务端只持有当前存档的数据，
 * 顶层退化为单个「当前服务器」节点。
 * 玩家名解析见 {@link PlayerNameResolver}：正版玩家走 Mojang 接口并缓存；
 * 离线玩家名由界面输入并按其离线 UUID 匹配，匹配不上则继续显示 UUID。
 */
public class MaidBackupBrowserScreen extends Screen implements IMaidFileNetwork.ClientHandler {
    // ===== 配色（与主管理界面一致） =====
    private static final int SCREEN_BG = 0xFF101010;
    private static final int PANEL_BG = 0xFF282018;
    private static final int PANEL_BORDER = 0xFF8B5A2B;
    private static final int HEADER_COLOR = 0xFFFFE082;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int SUBTEXT_COLOR = 0xFFB0B0B0;
    /** 提醒色：用于「结果不完整」这类需要玩家注意但不阻断操作的提示 */
    private static final int WARN_COLOR = 0xFFFFA726;
    private static final int ACCENT = 0xFFD0A060;
    private static final int LIST_BG = 0xFF181008;
    private static final int ROW_SELECTED = 0xFF2E3820;
    private static final int ROW_HOVER = 0x30FFFFFF;
    private static final int BUTTON_BG = 0xFF3A2C1C;
    private static final int BUTTON_BG_HOVER = 0xFF4A3826;
    private static final int ERROR_COLOR = 0xFFFF8080;

    private static final int PANEL_MAX_W = 480;
    private static final int HEADER_H = 24;
    private static final int ROW_H = 14;
    private static final int SCROLL_W = 3;
    private static final int TOP_ROW_H = 15;
    private static final int BTN_H = 18;

    private final Screen parent;
    /** 打开本界面前活跃的网络回调持有者，关闭时还原，避免抢占主管理界面的回调 */
    private final IMaidFileNetwork.ClientHandler prevHandler;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int listX;
    private int listY;
    private int listW;
    private int listH;

    private boolean available;
    /** 专业服务端：已发出列表请求、正在等待服务端回传 */
    private boolean loading;
    /** loading 置真的时刻：服务端不回包时用它超时兜底（否则界面永久「加载中」） */
    private long loadingSince;
    /** 等服务端回包的最长时间（毫秒） */
    private static final long LOADING_TIMEOUT_MS = 15_000L;
    /** 是否已完成首次扫描（init 会因 resize 被重复调用，避免反复全量扫描） */
    private boolean initialScanDone;
    /** 超时后是否已经自动重试过一次（避免无限重试） */
    private boolean scanRetriedAfterTimeout;

    /** 网络实现暂不可用时的自动重试（第六轮 A4）：最多 3 次、每次间隔 1s */
    private boolean netRetryPending;
    private long netRetryAt;
    private int netRetryCount;
    private int scrollOffset;

    private final List<WorldNode> worlds = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    /** 已发起过名字解析的玩家 UUID，避免重复入队 */
    private final Set<String> nameRequested = new HashSet<>();

    private EditBox offlineField;
    /** 搜索框：按存档名 / 玩家名 / 女仆名 / 文件名过滤列表（纯本地） */
    private EditBox searchField;
    /** 搜索行标签的 Y 坐标（init 里算好，render 里画名字用） */
    private int searchLabelY = -1;
    /** 是否显示「名字匹配与查询」说明页 */
    private boolean helpVisible;
    /** 「导出为 .maid」按钮：仅在有选中且功能可用时激活 */
    private WoodButton exportBtn;
    /** 「打开导出文件夹」按钮：功能可用时激活（扫描完成后刷新，避免常黑） */
    private WoodButton openDirBtn;
    /** 当前选中的备份文件（owner/maid/file），未选中为 null */
    private String selOwner;
    private String selMaid;
    private String selFile;

    private Component feedback = Component.empty();
    private int feedbackColor = SUBTEXT_COLOR;

    public MaidBackupBrowserScreen(Screen parent) {
        super(Component.translatable("gui.maid_file_manager.backup.title"));
        this.parent = parent;
        this.prevHandler = IMaidFileNetwork.ClientHandlerHolder.get();
    }

    // ===== 数据模型 =====

    /** 顶层：一个存档（专业服务端场景为单个「当前服务器」节点） */
    private static final class WorldNode {
        final String name;
        // 默认折叠：备份树初始只列世界节点，点开再看玩家/女仆/文件，避免一进界面就全部摊开。
        boolean expanded = false;
        final List<OwnerNode> owners = new ArrayList<>();

        WorldNode(String name) {
            this.name = name;
        }
    }

    private static final class OwnerNode {
        final String uuid;
        String displayName;
        final List<MaidNode> maids = new ArrayList<>();

        OwnerNode(String uuid) {
            this.uuid = uuid;
        }
    }

    private static final class MaidNode {
        final String uuid;
        final String name;
        /** 原始 TLM 模型 ID：客户端据此自行解析显示名，避免服务端缺资源时退化成「小女仆」 */
        final String modelId;
        /** 是否 YSM 换模：为 true 时服务端给的 name 才是权威名字，不能再拿 modelId 去解析 */
        final boolean ysmModel;
        final List<String> files;
        boolean expanded;

        MaidNode(String uuid, String name, String modelId, boolean ysmModel, List<String> files) {
            this.uuid = uuid;
            this.name = name;
            this.modelId = modelId;
            this.ysmModel = ysmModel;
            this.files = files;
        }
    }

    /** 展平后的一行：类型 0=存档 1=玩家 2=女仆 3=备份文件 */
    private static final class Row {
        final int type;
        final WorldNode world;
        final OwnerNode owner;
        final MaidNode maid;
        final String file;

        Row(int type, WorldNode world, OwnerNode owner, MaidNode maid, String file) {
            this.type = type;
            this.world = world;
            this.owner = owner;
            this.maid = maid;
            this.file = file;
        }
    }

    @Override
    public void removed() {
        // 界面背景音乐（试验性功能）：离开界面时释放，全部界面关闭后延迟停止
        UiMusicManager.leave(this);
        // 界面被 setScreen 切走时 onClose 不会被调用：若不在这里还原网络回调持有者，
        // 会残留指向已关闭界面的引用，后续服务端回调打到死界面。
        if (IMaidFileNetwork.ClientHandlerHolder.get() == this) {
            IMaidFileNetwork.ClientHandlerHolder.set(prevHandler);
        }
        super.removed();
    }

    @Override
    public void tick() {
        super.tick();
        // 界面期间每帧压住原版背景音乐（其 MusicManager 会自动重播，单次停止无效）
        UiMusicManager.tick();
        // 网络实现暂不可用的自动重试（第六轮 A4）
        if (netRetryPending && System.currentTimeMillis() >= netRetryAt) {
            netRetryPending = false;
            netRetryCount++;
            scanBackups();
        }
        // 服务端不回包时不能让「加载中」永久挂着：超时后放开并给出可读提示。
        if (loading && loadingSince > 0L
                && System.currentTimeMillis() - loadingSince > LOADING_TIMEOUT_MS) {
            loading = false;
            loadingSince = 0L;
            // 超时后先自动重试一次：initialScanDone 会挡住 init 重扫，
            // 原先玩家只能关屏重开才能再拉一次列表。
            if (!scanRetriedAfterTimeout) {
                scanRetriedAfterTimeout = true;
                loading = false;
                scanBackups();
                return;
            }
            setFeedback(Component.translatable("maid_file_manager.gui.error.network_unavailable"), ERROR_COLOR);
            Constants.LOG.warn("[maid_file_manager] 备份列表请求超时（{} ms 未回包）", LOADING_TIMEOUT_MS);
        }
    }

    @Override
    protected void init() {
        UiMusicManager.enter(this);
        super.init();
        // 游戏运行中可能刚下载了新模型包：重扫一次，否则新包的女仆名永远显示英文
        try {
            ModelNames.reload();
        } catch (Throwable ignored) {
        }
        IMaidFileNetwork.ClientHandlerHolder.set(this);
        panelW = Math.min(PANEL_MAX_W, this.width - 16);
        panelH = Math.min(this.height - 20, 252);
        panelX = (this.width - panelW) / 2;
        panelY = Math.max(6, (this.height - panelH) / 2);

        int topY = panelY + HEADER_H;
        int labelW = this.font.width(Component.translatable("gui.maid_file_manager.backup.offline_label")) + 4;
        int matchW = 44;
        int fieldW = panelW - 24 - labelW - matchW - 8;
        offlineField = new EditBox(this.font, panelX + 12 + labelW, topY, fieldW, TOP_ROW_H,
                Component.translatable("gui.maid_file_manager.backup.offline_label"));
        offlineField.setMaxLength(16);
        offlineField.setValue("");
        offlineField.setTextColor(TEXT_COLOR);
        offlineField.setFocused(false);
        addRenderableWidget(offlineField);
        addRenderableWidget(new WoodButton(panelX + 12 + labelW + fieldW + 4, topY, matchW, TOP_ROW_H,
                Component.translatable("gui.maid_file_manager.backup.match"), this::onOfflineMatch));

        // 搜索行：纯本地过滤，不发网络请求
        int searchY = topY + TOP_ROW_H + 2;
        searchLabelY = searchY;
        int searchLabelW = this.font.width(Component.translatable("gui.maid_file_manager.backup.search")) + 4;
        // 按钮宽度按「说明」两个字算，原来 18px 的「?」太小、玩家看不出是干什么的
        int helpW = Math.max(36, this.font.width(Component.translatable("gui.maid_file_manager.backup.help.btn")) + 8);
        int searchW = panelW - 24 - searchLabelW - helpW - 8;
        searchField = new EditBox(this.font, panelX + 12 + searchLabelW, searchY, searchW, TOP_ROW_H,
                Component.translatable("gui.maid_file_manager.backup.search_hint"));
        searchField.setMaxLength(64);
        searchField.setValue("");
        searchField.setTextColor(TEXT_COLOR);
        searchField.setResponder(s -> {
            scrollOffset = 0;
            rebuildRows();
        });
        searchField.setFocused(false);
        addRenderableWidget(searchField);
        addRenderableWidget(new WoodButton(panelX + 12 + searchLabelW + searchW + 4, searchY, helpW, TOP_ROW_H,
                Component.translatable("gui.maid_file_manager.backup.help.btn"), () -> helpVisible = !helpVisible));

        int bottomY = panelY + panelH - BTN_H - 6;
        listX = panelX + 10;
        listY = searchY + TOP_ROW_H + 6;
        listW = panelW - 20 - SCROLL_W;
        listH = bottomY - listY - 4;

        boolean hasSelection = selFile != null;
        int btnW = 110;
        int bx = panelX + panelW - 12 - btnW;
        exportBtn = new WoodButton(bx, bottomY, btnW, BTN_H,
                Component.translatable("gui.maid_file_manager.backup.export"), this::onExport);
        exportBtn.active = hasSelection && available;
        addRenderableWidget(exportBtn);
        bx -= btnW + 6;
        openDirBtn = new WoodButton(bx, bottomY, btnW, BTN_H,
                Component.translatable("gui.maid_file_manager.backup.open_dir"), this::openExportDir);
        openDirBtn.active = available;
        addRenderableWidget(openDirBtn);
        addRenderableWidget(new WoodButton(panelX + 12, bottomY, 80, BTN_H,
                Component.translatable("gui.maid_file_manager.backup.back"), this::onClose));

        PlayerNameResolver.loadCache(this.minecraft.gameDirectory.toPath());
        // init() 在窗口 resize 时也会被调用，而扫描要遍历全部存档并逐个读 index.dat。
        // 已扫描过就不再重复扫（列表内容不随窗口尺寸变化），避免拉窗口时反复卡主线程。
        if (!initialScanDone) {
            initialScanDone = true;
            scanBackups();
        }
        rebuildRows();
        // 扫描可能已把 available 置为 true，此处刷新一次按钮状态，避免「打开导出文件夹」按钮停在初始的禁用态
        refreshButtons();
    }

    // ===== 扫描备份 =====

    /**
     * 刷新备份列表。
     *
     * <p>单人世界 / 局域网主机：集成服务端即本机，直接读取本机存档的备份目录；
     * 专业服务端：备份在服务器磁盘，向服务端请求列表（服务端按权限过滤后回传）。
     */
    private void scanBackups() {
        worlds.clear();
        nameRequested.clear();
        loading = false;
        if (isRemoteServer()) {
            // 专业服务端：备份在服务器磁盘，请求服务端按权限过滤后回传
            available = true;
            loading = true;
            loadingSince = System.currentTimeMillis();
            IMaidFileNetwork net = IMaidFileNetwork.Holder.get();
            if (net == null) {
                // 不再永久置「备份功能不可用」：网络实现在界面刚打开时可能尚未就绪，
                // 原先只能关屏重开（第六轮 A4）。改为保可用状态 + 1s 后自动重试，最多 3 次。
                if (netRetryCount >= 3) {
                    loading = false;
                    loadingSince = 0L;
                    setFeedback(Component.translatable("maid_file_manager.gui.error.network_unavailable"), ERROR_COLOR);
                } else {
                    netRetryPending = true;
                    // 排期重试期间不能挂着 loading：否则 tick 的「加载超时」会提前跳到失败分支
                    loading = false;
                    loadingSince = 0L;
                    netRetryAt = System.currentTimeMillis() + 1000L;
                }
                return;
            }
            net.sendRequestBackupList();
            return;
        }
        available = true;
        // 本机：单人 / 局域网主机读当前存档；游戏主菜单无存档上下文，合并扫描本机全部存档
        final IntegratedServer server = this.minecraft.getSingleplayerServer();
        final java.nio.file.Path scanGameDir = this.minecraft.gameDirectory.toPath();
        final java.util.UUID scanRequester = requesterUuid();
        // 扫描会遍历存档目录里的所有 .maid 备份并解析 NBT；主菜单分支 scanAllWorlds 可能扫到
        // 几十上百个文件，原先在渲染线程同步执行会明显卡顿。改为后台线程 + 回主线程应用。
        loading = true;
        loadingSince = System.currentTimeMillis();
        Thread scanWorker = new Thread(() -> {
            try {
                if (server != null) {
                    final MaidBackupService.ScanResult result =
                            MaidBackupService.scan(server, scanRequester, true);
                    if (this.minecraft == null) {
                        return;
                    }
                    this.minecraft.execute(() -> {
                        loading = false;
                        loadingSince = 0L;
                        applyServerOwners(result.owners());
                        // 截断提示必须排在 applyXxx 之后：后者的「列表为空」提示会覆盖它，
                        // 而「列表不全」比「列表为空」更应该被玩家看到
                        if (result.truncated()) {
                            setFeedback(Component.translatable(
                                    "gui.maid_file_manager.backup.truncated"), WARN_COLOR);
                        }
                    });
                } else {
                    final MaidBackupService.WorldScanResult result =
                            MaidBackupService.scanAllWorlds(scanGameDir);
                    if (this.minecraft == null) {
                        return;
                    }
                    this.minecraft.execute(() -> {
                        loading = false;
                        loadingSince = 0L;
                        applyWorlds(result.worlds());
                        if (result.truncated()) {
                            setFeedback(Component.translatable(
                                    "gui.maid_file_manager.backup.truncated"), WARN_COLOR);
                        }
                    });
                }
            } catch (Throwable t) {
                Constants.LOG.warn("[maid_file_manager] 备份扫描失败: {}", t.toString());
                if (this.minecraft != null) {
                    this.minecraft.execute(() -> {
                        loading = false;
                        loadingSince = 0L;
                        setFeedback(Component.translatable(
                                "maid_file_manager.gui.error.network_unavailable"), ERROR_COLOR);
                    });
                }
            }
        }, "maidfm-backup-scan");
        scanWorker.setDaemon(true);
        scanWorker.start();
    }

    /** 是否连接的是专业服务端（非本机集成服务端）：此时备份需由服务端代读回传 */
    private boolean isRemoteServer() {
        return this.minecraft.getSingleplayerServer() == null && this.minecraft.getConnection() != null;
    }

    private UUID requesterUuid() {
        return this.minecraft.player != null ? this.minecraft.player.getUUID() : null;
    }

    /** 本机：按存档分组重建界面模型，并触发玩家名解析 */
    private void applyWorlds(List<IMaidFileNetwork.BackupWorld> list) {
        worlds.clear();
        nameRequested.clear();
        List<IMaidFileNetwork.BackupWorld> src = list == null ? List.of() : list;
        for (IMaidFileNetwork.BackupWorld w : src) {
            WorldNode world = new WorldNode(w.worldName());
            List<IMaidFileNetwork.BackupOwner> owners = w.owners() == null ? List.of() : w.owners();
            for (IMaidFileNetwork.BackupOwner o : owners) {
                world.owners.add(buildOwner(o));
            }
            world.owners.sort(Comparator.comparing(o -> o.uuid));
            worlds.add(world);
        }
        resolveAllNames();
        // 本机扫描结果回来后必须重建列表：applyWorlds 只填了 worlds，
        // 而唯一的 rebuildRows() 挂在「名字异步解析回调」里；
        // 名字全部命中缓存时该回调不会触发 → 界面永远停在「未找到任何女仆备份」
        //（服务端模式不受影响：其回调 onBackupListReceived 自己就 rebuildRows）。
        rebuildRows();
        refreshButtons();
    }

    /** 专业服务端：只有当前存档的数据，包成单个「当前服务器」节点展示 */
    private void applyServerOwners(List<IMaidFileNetwork.BackupOwner> list) {
        // 把服务端解析好的「modelId → 名字」学进本地缓存：
        // 之后退到主界面走本地扫描时，注册表已空，就靠这份缓存保住名字
        try {
            ClientModelNameCache.learn(list);
            ClientModelNameCache.flush();
        } catch (Throwable ignored) {
        }
        worlds.clear();
        nameRequested.clear();
        WorldNode world = new WorldNode(
                Component.translatable("gui.maid_file_manager.backup.world.server").getString());
        List<IMaidFileNetwork.BackupOwner> src = list == null ? List.of() : list;
        for (IMaidFileNetwork.BackupOwner o : src) {
            world.owners.add(buildOwner(o));
        }
        world.owners.sort(Comparator.comparing(o -> o.uuid));
        worlds.add(world);
        resolveAllNames();
    }

    private OwnerNode buildOwner(IMaidFileNetwork.BackupOwner o) {
        OwnerNode owner = new OwnerNode(o.ownerUuid());
        // 服务端已从 usernamecache.json 反查出玩家名时直接用它：客户端自己的 usernamecache 里
        // 往往没有服务端离线 UUID 的条目，只能显示 UUID 前缀。
        String serverName = o.ownerName();
        if (serverName != null && !serverName.isEmpty()) {
            owner.displayName = serverName;
        }
        List<IMaidFileNetwork.BackupMaid> maids = o.maids() == null ? List.of() : o.maids();
        for (IMaidFileNetwork.BackupMaid m : maids) {
            owner.maids.add(new MaidNode(m.maidUuid(), m.maidName(), m.modelId(), m.ysmModel(),
                    m.files() == null ? new ArrayList<>() : new ArrayList<>(m.files())));
        }
        owner.maids.sort(Comparator.comparing(m -> m.uuid));
        return owner;
    }

    private void resolveAllNames() {
        for (WorldNode world : worlds) {
            for (OwnerNode owner : world.owners) {
                if (owner.displayName == null) {
                    owner.displayName = resolveDisplayName(owner.uuid);
                }
            }
        }
    }

    /** 解析玩家显示名：优先缓存，未命中触发异步查询并先用 UUID 前 8 位兜底 */
    private String resolveDisplayName(String uuidStr) {
        UUID uuid;
        try {
            uuid = UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            return shortId(uuidStr);
        }
        String cached = PlayerNameResolver.getCached(uuid);
        if (cached != null) {
            return cached;
        }
        if (nameRequested.add(uuidStr)) {
            PlayerNameResolver.resolve(uuid, (resolvedUuid, name) -> {
                // 该回调在解析工作线程上执行：界面状态（displayName / rows）必须回主线程改，
                // 否则既可能踩并发，也可能改了却没重绘。
                if (this.minecraft == null) {
                    return;
                }
                this.minecraft.execute(() -> {
                    for (WorldNode world : worlds) {
                        for (OwnerNode owner : world.owners) {
                            // 回填条件必须是「当前仍显示短 UUID 占位」：
                            // 原先写 owner.displayName == null 恒假（resolveDisplayName 末尾已用 shortId 填过），
                            // 于是整条异步链路是死代码，非缓存玩家永远停在 8 位短 UUID（第六轮 P1-3）。
                            if (owner.uuid.equals(resolvedUuid.toString())
                                    && (owner.displayName == null
                                        || owner.displayName.equals(shortId(owner.uuid)))) {
                                owner.displayName = name;
                            }
                        }
                    }
                    rebuildRows();
                });
            });
        }
        return shortId(uuidStr);
    }


    /**
     * 根据 UUID 的版本号标注账号来源。
     *
     * <ul>
     *   <li>版本 4（第 3 段首位 {@code 4}）→ **正版** UUID</li>
     *   <li>版本 3（第 3 段首位 {@code 3}）→ **离线** UUID（由名字 MD5 得来）</li>
     * </ul>
     *
     * <p>这个区分很实用：正版服与离线服对同一玩家给的 UUID 不同，备份目录也按 UUID 分，
     * 一眼看出某个目录属于哪种身份，能避免「为什么我的备份在另一台服上看不到」的困惑。
     */
    private static String uuidOrigin(String uuid) {
        try {
            if (uuid == null) {
                return "";
            }
            String[] seg = uuid.split("-");
            if (seg.length < 3 || seg[2].isEmpty()) {
                return "";
            }
            // 返回翻译键而非硬编码文案：英文客户端也能正确显示
            return switch (seg[2].charAt(0)) {
                case '3' -> "maid_file_manager.gui.backup.uuid_offline";
                case '4' -> "maid_file_manager.gui.backup.uuid_premium";
                default -> "";
            };
        } catch (Throwable t) {
            return "";
        }
    }
    private static String shortId(String uuid) {
        String s = uuid.replace("-", "");
        return s.length() > 8 ? s.substring(0, 8) : s;
    }

    // ===== 行构建 =====

    private void rebuildRows() {
        rows.clear();
        String q = searchField == null ? "" : searchField.getValue().trim().toLowerCase(java.util.Locale.ROOT);
        boolean filtering = !q.isEmpty();
        for (WorldNode world : worlds) {
            boolean worldHit = hit(world.name, q);
            if (filtering && !worldHit) {
                boolean anyChild = false;
                for (OwnerNode owner : world.owners) {
                    if (hit(owner.displayName, q) || hit(owner.uuid, q)) {
                        anyChild = true;
                        break;
                    }
                    for (MaidNode m : owner.maids) {
                        if (hit(m.name, q) || hit(m.uuid, q) || hit(m.modelId, q)) {
                            anyChild = true;
                            break;
                        }
                    }
                    if (anyChild) {
                        break;
                    }
                }
                if (!anyChild) {
                    continue;
                }
            }
            rows.add(new Row(0, world, null, null, null));
            if (!filtering && !world.expanded) {
                continue;
            }
            for (OwnerNode owner : world.owners) {
                boolean ownerHit = worldHit || hit(owner.displayName, q) || hit(owner.uuid, q);
                boolean maidAnyHit = false;
                for (MaidNode m : owner.maids) {
                    if (hit(m.name, q) || hit(m.uuid, q) || hit(m.modelId, q)) {
                        maidAnyHit = true;
                        break;
                    }
                }
                if (filtering && !ownerHit && !maidAnyHit) {
                    continue;
                }
                rows.add(new Row(1, world, owner, null, null));
                for (MaidNode maid : owner.maids) {
                    boolean maidHit = ownerHit || hit(maid.name, q) || hit(maid.uuid, q) || hit(maid.modelId, q);
                    if (filtering && !maidHit) {
                        continue;
                    }
                    rows.add(new Row(2, world, owner, maid, null));
                    if (!filtering && !maid.expanded) {
                        continue;
                    }
                    for (String file : maid.files) {
                        if (filtering && !maidHit && !hit(file, q)) {
                            continue;
                        }
                        rows.add(new Row(3, world, owner, maid, file));
                    }
                }
            }
        }
        int maxScroll = Math.max(0, rows.size() - visibleRows());
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset));
    }

    /** 过滤用的子串匹配（忽略大小写，q 已小写化） */
    private static boolean hit(String s, String q) {
        return s != null && !q.isEmpty() && s.toLowerCase(java.util.Locale.ROOT).contains(q);
    }

    private int visibleRows() {
        return Math.max(1, listH / ROW_H);
    }

    // ===== 交互 =====

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && available && isInsideList(mouseX, mouseY)) {
            int index = scrollOffset + (int) ((mouseY - listY) / ROW_H);
            if (index >= 0 && index < rows.size()) {
                onRowClicked(rows.get(index));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void onRowClicked(Row row) {
        if (row.type == 0) {
            row.world.expanded = !row.world.expanded;
        } else if (row.type == 1) {
            boolean anyCollapsed = false;
            for (MaidNode maid : row.owner.maids) {
                if (!maid.expanded) {
                    anyCollapsed = true;
                    break;
                }
            }
            for (MaidNode maid : row.owner.maids) {
                maid.expanded = anyCollapsed;
            }
        } else if (row.type == 2) {
            row.maid.expanded = !row.maid.expanded;
        } else {
            selOwner = row.owner.uuid;
            selMaid = row.maid.uuid;
            selFile = row.file;
        }
        rebuildRows();
        refreshButtons();
    }

    @SuppressWarnings("unused")
    public boolean mouseScrolled(double mouseX, double mouseY, double amount, double delta) {
        return handleScroll(mouseX, mouseY, delta);
    }

    @SuppressWarnings("unused")
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        return handleScroll(mouseX, mouseY, amount);
    }

    private boolean handleScroll(double mouseX, double mouseY, double amount) {
        if (!available || !isInsideList(mouseX, mouseY)) {
            return false;
        }
        int maxScroll = Math.max(0, rows.size() - visibleRows());
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset + (amount > 0 ? -2 : 2)));
        return true;
    }

    private boolean isInsideList(double mouseX, double mouseY) {
        return mouseX >= listX && mouseX <= listX + listW && mouseY >= listY && mouseY <= listY + listH;
    }

    /** 选择变化后刷新导出按钮可用状态（不重建控件，避免清空离线名输入框内容） */
    private void refreshButtons() {
        if (exportBtn != null) {
            exportBtn.active = selFile != null && available;
        }
        if (openDirBtn != null) {
            openDirBtn.active = available;
        }
    }

    private void onOfflineMatch() {
        String name = offlineField.getValue() == null ? "" : offlineField.getValue().trim();
        if (name.isEmpty()) {
            setFeedback(Component.translatable("gui.maid_file_manager.backup.need_name"), ERROR_COLOR);
            return;
        }
        // 先在名字对照表（含 usernamecache.json）里反查 UUID，因此正版玩家也能匹配；
        // 对照表未命中才回退到离线 UUID 规则
        UUID matchedUuid = PlayerNameResolver.resolveUuidByName(name);
        boolean matched = false;
        for (WorldNode world : worlds) {
            for (OwnerNode owner : world.owners) {
                if (owner.uuid.equalsIgnoreCase(matchedUuid.toString())) {
                    owner.displayName = name;
                    matched = true;
                }
            }
        }
        if (matched) {
            PlayerNameResolver.putCached(matchedUuid, name);
            setFeedback(Component.translatable("gui.maid_file_manager.backup.matched", name), ACCENT);
        } else {
            setFeedback(Component.translatable("gui.maid_file_manager.backup.not_matched", name), ERROR_COLOR);
        }
        rebuildRows();
    }

    // ===== 导出 =====

    private void onExport() {
        if (selOwner == null || selMaid == null || selFile == null) {
            setFeedback(Component.translatable("gui.maid_file_manager.backup.no_selection"), ERROR_COLOR);
            return;
        }
        if (isRemoteServer()) {
            // 专业服务端：请服务端读取并回传，客户端再写本地文件
            IMaidFileNetwork net = IMaidFileNetwork.Holder.get();
            if (net == null) {
                setFeedback(Component.translatable("maid_file_manager.gui.error.network_unavailable"), ERROR_COLOR);
                return;
            }
            setFeedback(Component.translatable("gui.maid_file_manager.backup.exporting"), SUBTEXT_COLOR);
            net.sendRequestBackupExport(selOwner, selMaid, selFile);
            return;
        }
        // 本机：readLocal 要遍历全部存档目录、解 GZIP 并解析 NBT，必须离开渲染线程（第六轮 A1）。
        final java.nio.file.Path backupGameDir = this.minecraft.gameDirectory.toPath();
        final String backupSelOwner = selOwner;
        final String backupSelMaid = selMaid;
        final String backupSelFile = selFile;
        setFeedback(Component.translatable("gui.maid_file_manager.backup.exporting"), SUBTEXT_COLOR);
        Thread backupReadWorker = new Thread(() -> {
            MaidFileData data = MaidBackupService.readLocal(backupGameDir, backupSelOwner, backupSelMaid, backupSelFile);
            if (this.minecraft == null) {
                return;
            }
            this.minecraft.execute(() -> {
                if (data == null) {
                    setFeedback(Component.translatable("gui.maid_file_manager.backup.export_failed"), ERROR_COLOR);
                    return;
                }
                writeLocal(data);
            });
        }, "maidfm-backup-read");
        backupReadWorker.setDaemon(true);
        backupReadWorker.start();
    }

    /** 把一份备份数据写入客户端本地导出目录（文件名规则与普通导出一致） */
    private void writeLocal(MaidFileData data) {
        // 写盘含 NBT 序列化 + 压缩 + 目录创建 + 重名探测：放后台线程，完成后回主线程给反馈。
        // （原先直接在渲染线程同步写，主菜单批量导出备份时逐条累积成明显卡顿。）
        if (this.minecraft == null) {
            return;
        }
        final String ownerName = findOwnerDisplayName(data.getOwnerUuid());
        data.setOwnerName(ownerName);
        final java.nio.file.Path exportGameDir = this.minecraft.gameDirectory.toPath();
        Thread writeWorker = new Thread(() -> {
            String written = null;
            Throwable failure = null;
            try {
                Path exportRoot = MaidFileIo.ensureExportsDir(exportGameDir);
                Path playerDir = MaidFileIo.resolvePlayerDir(exportRoot, ownerName);
                // 文件名必须与普通导出一致：普通导出会拼自定义名（命名牌所取），
                // 见 MaidFileManagerScreen / MaidServerCommands 都调 composeDisplayName。
                String baseName = ModelNames.displayModelId(data.getModelId());
                String displayName = MaidFileIo.composeDisplayName(data.getCustomName(), baseName);
                written = MaidFileIo.writeMaidFile(playerDir, displayName, data.getModelId(),
                        data.getOwnerUuid(), data, LocalDateTime.now());
            } catch (Throwable t) {
                failure = t;
            }
            final String outWritten = written;
            final Throwable outFailure = failure;
            if (this.minecraft == null) {
                return;
            }
            this.minecraft.execute(() -> {
                if (outFailure != null) {
                    Constants.LOG.error("[maid_file_manager] 导出备份为 .maid 失败", outFailure);
                    setFeedback(Component.translatable(
                            "gui.maid_file_manager.backup.export_failed"), ERROR_COLOR);
                } else {
                    setFeedback(Component.translatable(
                            "gui.maid_file_manager.backup.exported", outWritten), ACCENT);
                }
            });
        }, "maidfm-backup-export");
        writeWorker.setDaemon(true);
        writeWorker.start();
    }

    private String findOwnerDisplayName(String ownerUuid) {
        for (WorldNode world : worlds) {
            for (OwnerNode owner : world.owners) {
                if (owner.uuid.equals(ownerUuid)) {
                    return owner.displayName != null ? owner.displayName : shortId(ownerUuid);
                }
            }
        }
        return shortId(ownerUuid);
    }

    private void openExportDir() {
        try {
            Path exportRoot = MaidFileIo.ensureExportsDir(this.minecraft.gameDirectory.toPath());
            // 用游戏原生的平台打开方式（各系统分支已内建），比直接调 AWT Desktop 更可靠
            Util.getPlatform().openFile(exportRoot.toFile());
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 打开导出目录失败: {}", t.toString());
            setFeedback(Component.translatable("gui.maid_file_manager.backup.export_failed"), ERROR_COLOR);
        }
    }

    // ===== 网络回调 =====

    @Override
    public void onBackupListReceived(List<IMaidFileNetwork.BackupOwner> list) {
        if (this.minecraft == null) {
            return;
        }
        loading = false;
        available = true;
        // 空结果不要塞「当前服务器」节点：applyServerOwners 无条件 worlds.add(world)，
        // rebuildRows 至少产出 1 行（存档行），于是下面 rows.isEmpty() 那条「没有备份」
        // 的提示永远不执行 —— 非 OP / 被权限过滤为空时界面只剩一个「当前服务器 (0)」，
        // 玩家看不出是「权限不足」还是「确实没有备份」。
        if (list == null || list.isEmpty()) {
            worlds.clear();
            nameRequested.clear();
            setFeedback(Component.translatable("gui.maid_file_manager.backup.empty"), SUBTEXT_COLOR);
        } else {
            applyServerOwners(list);
        }
        rebuildRows();
        refreshButtons();
        if (rows.isEmpty()) {
            setFeedback(Component.translatable("gui.maid_file_manager.backup.empty"), SUBTEXT_COLOR);
        }
    }

    @Override
    public void onBackupExportReceived(MaidFileData data) {
        if (data == null) {
            setFeedback(Component.translatable("gui.maid_file_manager.backup.export_failed"), ERROR_COLOR);
            return;
        }
        writeLocal(data);
    }

    /** 服务端回执（如权限不足、列表过大）直接显示在界面底部 */
    @Override
    public void onFeedbackReceived(Component message) {
        if (message != null && !message.getString().isEmpty()) {
            setFeedback(message, ERROR_COLOR);
        }
    }

    // 本界面不关心的其它回调

    @Override
    public void onMaidListReceived(List<MaidInfo> list) {
    }

    @Override
    public void onExportResultReceived(List<MaidFileData> dataList) {
    }

    @Override
    public void onImportBatchResultReceived(Component summary, List<Boolean> spawned) {
    }

    @Override
    public void onServerExportListReceived(List<IMaidFileNetwork.PlayerMaidGroup> groups) {
    }

    @Override
    public void onMaidProfileReceived(MaidProfileView view) {
    }

    // ===== 渲染 =====

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, SCREEN_BG);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, PANEL_BG);
        graphics.renderOutline(panelX, panelY, panelW, panelH, PANEL_BORDER);
        graphics.drawString(this.font, this.title,
                this.width / 2 - this.font.width(this.title) / 2, panelY + 6, HEADER_COLOR, false);

        // 离线玩家名标签
        graphics.drawString(this.font, Component.translatable("gui.maid_file_manager.backup.offline_label"),
                panelX + 12, panelY + HEADER_H + 4, SUBTEXT_COLOR, false);

        renderList(graphics, mouseX, mouseY);
        super.render(graphics, mouseX, mouseY, partialTick);

        if (!feedback.getString().isEmpty()) {
            graphics.drawString(this.font, feedback, panelX + 12, panelY + panelH + 4, feedbackColor, false);
        }
    }

    /** 「?」说明页：解释名字是怎么来的、为什么人多要等、离线玩家为什么要手动匹配 */
    private void renderHelp(GuiGraphics graphics) {
        int x = listX + 8;
        int y = listY + 6;
        graphics.drawString(this.font, Component.translatable("gui.maid_file_manager.backup.help.title"),
                x, y, HEADER_COLOR, false);
        y += 14;
        for (int i = 1; i <= 10; i++) {
            Component line = Component.translatable("gui.maid_file_manager.backup.help.line" + i);
            graphics.drawString(this.font, line, x, y, SUBTEXT_COLOR, false);
            y += 12;
        }
    }

    private void renderList(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.fill(listX, listY, listX + listW, listY + listH, LIST_BG);
        if (helpVisible) {
            renderHelp(graphics);
            return;
        }
        if (!available) {
            graphics.drawString(this.font,
                    Component.translatable("gui.maid_file_manager.backup.unavailable"),
                    listX + 6, listY + 6, SUBTEXT_COLOR, false);
            return;
        }
        if (loading) {
            graphics.drawString(this.font,
                    Component.translatable("gui.maid_file_manager.backup.loading"),
                    listX + 6, listY + 6, SUBTEXT_COLOR, false);
            return;
        }
        if (rows.isEmpty()) {
            graphics.drawString(this.font,
                    Component.translatable("gui.maid_file_manager.backup.empty"),
                    listX + 6, listY + 6, SUBTEXT_COLOR, false);
            return;
        }
        graphics.enableScissor(listX, listY, listX + listW, listY + listH);
        int visible = visibleRows();
        for (int i = 0; i < visible; i++) {
            int index = scrollOffset + i;
            if (index >= rows.size()) {
                break;
            }
            Row row = rows.get(index);
            int top = listY + i * ROW_H;
            boolean selected = row.type == 3 && selFile != null && selFile.equals(row.file)
                    && selOwner != null && selOwner.equals(row.owner.uuid)
                    && selMaid != null && selMaid.equals(row.maid.uuid);
            boolean hover = mouseX >= listX && mouseX <= listX + listW && mouseY >= top && mouseY < top + ROW_H;
            if (selected) {
                graphics.fill(listX, top, listX + listW, top + ROW_H, ROW_SELECTED);
            } else if (hover) {
                graphics.fill(listX, top, listX + listW, top + ROW_H, ROW_HOVER);
            }
            renderRow(graphics, row, top);
        }
        graphics.disableScissor();
        if (rows.size() > visible) {
            int barH = Math.max(12, (int) (listH * ((double) visible / rows.size())));
            int maxScroll = rows.size() - visible;
            int barY = listY + (int) ((listH - barH) * ((double) scrollOffset / maxScroll));
            graphics.fill(listX + listW + 2, barY, listX + listW + 2 + SCROLL_W, barY + barH, PANEL_BORDER);
        }
    }

    private void renderRow(GuiGraphics graphics, Row row, int top) {
        if (row.type == 0) {
            String arrow = row.world.expanded ? "▼ " : "▶ ";
            String text = arrow + Component.translatable("maid_file_manager.gui.backup.world_row", row.world.name, row.world.owners.size()).getString();
            graphics.drawString(this.font, Component.literal(text), listX + 4, top + 3, HEADER_COLOR, false);
        } else if (row.type == 1) {
            String name = row.owner.displayName != null ? row.owner.displayName : shortId(row.owner.uuid);
            // 主人行末尾标注 UUID 来源（正版 / 离线）：两者 UUID 不同源，排查「导入被判非本人」时是第一现场
            String originKey = uuidOrigin(row.owner.uuid);
            String origin = originKey.isEmpty() ? "" : Component.translatable(originKey).getString();
            String text = name + origin + "  (" + row.owner.maids.size() + ")";
            graphics.drawString(this.font, Component.literal(text), listX + 14, top + 3, HEADER_COLOR, false);
        } else if (row.type == 2) {
            String arrow = row.maid.expanded ? "▼ " : "▶ ";
            // 备份 index.dat 里存的是翻译键，显示前统一转换（服务端不会翻译）
            // 优先用原始 modelId 做客户端侧解析：服务端可能没装模型资源包，
            // 它传来的 maidName 已是「小女仆」占位名，再解析也变不回来。
            // YSM 换模的女仆：modelId 是 TLM 底层 ID，用它解析会得到 TLM 名字而不是 YSM 名字，
            // 所以直接采用服务端给的 maidName（那里已是 YSM 真名）
            if (row.maid.ysmModel && row.maid.name != null && !row.maid.name.isEmpty()) {
                // arrow 在下面才定义，这里自己算一份，避免作用域问题
                String ysmArrow = row.maid.expanded ? "▼ " : "▶ ";
                graphics.drawString(this.font, Component.literal(ysmArrow + row.maid.name),
                        listX + 24, top + 3, ACCENT, false);
                return;
            }
            // 名字解析要分两层，缺一不可：
            //   ① 客户端本地解析 modelId —— 只有装了那个模型包的客户端才成功（能拿中文名）
            //   ② 服务端传来的 maidName —— 服务端汇总了各客户端上报的映射（B 功能），
            //      即使本机没装该模型包也能拿到正确名字
            // 之前无条件优先 ①，本机解析失败时会把 ② 里的正确名字覆盖成 path 美化名（如「001」）。
            String name = null;
            String rawModelId = row.maid.modelId;
            if (rawModelId != null && !rawModelId.isEmpty()) {
                String local = ModelNames.displayModelId(rawModelId);
                // displayModelId 解析失败时返回的就是 prettifyModelPath(path)，据此判断是否真的解析成功
                String pathOnly = rawModelId;
                int c = rawModelId.indexOf(':');
                if (c >= 0 && c + 1 < rawModelId.length()) {
                    pathOnly = rawModelId.substring(c + 1);
                }
                String fallback = ModelNames.prettifyModelPath(pathOnly);
                if (local != null && !local.isEmpty() && !local.equals(fallback)) {
                    name = local;
                }
            }
            if ((name == null || name.isEmpty())
                    && row.maid.name != null && !row.maid.name.isEmpty()) {
                name = ModelNames.display(row.maid.name);
            }
            if (name == null || name.isEmpty()) {
                name = rawModelId != null && !rawModelId.isEmpty()
                        ? ModelNames.displayModelId(rawModelId)
                        : shortId(row.maid.uuid);
            }
            graphics.drawString(this.font, Component.literal(arrow + name),
                    listX + 24, top + 3, ACCENT, false);
        } else {
            String prefix = row.maid.files.indexOf(row.file) == 0 ? "● " : "○ ";
            graphics.drawString(this.font, Component.literal("    " + prefix + row.file),
                    listX + 34, top + 3, TEXT_COLOR, false);
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 全屏底色与面板已在 render() 中绘制
    }

    @SuppressWarnings("unused")
    public boolean returnsPauseScreen() {
        return false;
    }

    @SuppressWarnings("unused")
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        // 还原网络回调持有者，避免本界面关闭后仍拦截主管理界面的回包
        IMaidFileNetwork.ClientHandlerHolder.set(prevHandler);
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    private void setFeedback(Component message, int color) {
        this.feedback = message == null ? Component.empty() : message;
        this.feedbackColor = color;
    }

    /** 木色按钮：深棕底 + 木色描边 + 白字（无阴影） */
    private class WoodButton extends AbstractButton {
        private final Runnable action;

        WoodButton(int x, int y, int width, int height, Component message, Runnable action) {
            super(x, y, width, height, message);
            this.action = action;
        }

        @Override
        public void onPress() {
            this.action.run();
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            boolean hovered = isHoveredOrFocused();
            int bg = this.active ? (hovered ? BUTTON_BG_HOVER : BUTTON_BG) : 0xFF2A2018;
            graphics.fill(getX(), getY(), getX() + width, getY() + height, bg);
            graphics.renderOutline(getX(), getY(), width, height, PANEL_BORDER);
            int tx = getX() + (width - MaidBackupBrowserScreen.this.font.width(getMessage())) / 2;
            int ty = getY() + (height - 8) / 2;
            graphics.drawString(MaidBackupBrowserScreen.this.font, getMessage(), tx, ty,
                    this.active ? TEXT_COLOR : SUBTEXT_COLOR, false);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            this.defaultButtonNarrationText(output);
        }
    }
}
