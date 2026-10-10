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
    private int scrollOffset;

    private final List<WorldNode> worlds = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    /** 已发起过名字解析的玩家 UUID，避免重复入队 */
    private final Set<String> nameRequested = new HashSet<>();

    private EditBox offlineField;
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
        boolean expanded = true;
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
        final List<String> files;
        boolean expanded;

        MaidNode(String uuid, String name, List<String> files) {
            this.uuid = uuid;
            this.name = name;
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
    protected void init() {
        super.init();
        IMaidFileNetwork.ClientHandlerHolder.set(this);
        panelW = Math.min(PANEL_MAX_W, this.width - 16);
        panelH = Math.min(this.height - 20, 230);
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
        addRenderableWidget(offlineField);
        addRenderableWidget(new WoodButton(panelX + 12 + labelW + fieldW + 4, topY, matchW, TOP_ROW_H,
                Component.translatable("gui.maid_file_manager.backup.match"), this::onOfflineMatch));

        int bottomY = panelY + panelH - BTN_H - 6;
        listX = panelX + 10;
        listY = topY + TOP_ROW_H + 6;
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
        scanBackups();
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
            IMaidFileNetwork net = IMaidFileNetwork.Holder.get();
            if (net == null) {
                available = false;
                loading = false;
                setFeedback(Component.translatable("maid_file_manager.gui.error.network_unavailable"), ERROR_COLOR);
                return;
            }
            net.sendRequestBackupList();
            return;
        }
        available = true;
        // 本机：单人 / 局域网主机读当前存档；游戏主菜单无存档上下文，合并扫描本机全部存档
        IntegratedServer server = this.minecraft.getSingleplayerServer();
        if (server != null) {
            applyServerOwners(MaidBackupService.scan(server, requesterUuid(), true));
        } else {
            // 主菜单/单人：按存档分组（存档 → 玩家 → 女仆 → 备份文件）
            applyWorlds(MaidBackupService.scanAllWorlds(this.minecraft.gameDirectory.toPath()));
        }
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
    }

    /** 专业服务端：只有当前存档的数据，包成单个「当前服务器」节点展示 */
    private void applyServerOwners(List<IMaidFileNetwork.BackupOwner> list) {
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
        List<IMaidFileNetwork.BackupMaid> maids = o.maids() == null ? List.of() : o.maids();
        for (IMaidFileNetwork.BackupMaid m : maids) {
            owner.maids.add(new MaidNode(m.maidUuid(), m.maidName(),
                    m.files() == null ? new ArrayList<>() : new ArrayList<>(m.files())));
        }
        owner.maids.sort(Comparator.comparing(m -> m.uuid));
        return owner;
    }

    private void resolveAllNames() {
        for (WorldNode world : worlds) {
            for (OwnerNode owner : world.owners) {
                owner.displayName = resolveDisplayName(owner.uuid);
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
                for (WorldNode world : worlds) {
                    for (OwnerNode owner : world.owners) {
                        if (owner.uuid.equals(resolvedUuid.toString())) {
                            owner.displayName = name;
                        }
                    }
                }
                rebuildRows();
            });
        }
        return shortId(uuidStr);
    }

    private static String shortId(String uuid) {
        String s = uuid.replace("-", "");
        return s.length() > 8 ? s.substring(0, 8) : s;
    }

    // ===== 行构建 =====

    private void rebuildRows() {
        rows.clear();
        for (WorldNode world : worlds) {
            rows.add(new Row(0, world, null, null, null));
            if (!world.expanded) {
                continue;
            }
            for (OwnerNode owner : world.owners) {
                rows.add(new Row(1, world, owner, null, null));
                for (MaidNode maid : owner.maids) {
                    rows.add(new Row(2, world, owner, maid, null));
                    if (maid.expanded) {
                        for (String file : maid.files) {
                            rows.add(new Row(3, world, owner, maid, file));
                        }
                    }
                }
            }
        }
        int maxScroll = Math.max(0, rows.size() - visibleRows());
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset));
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
        UUID offline = PlayerNameResolver.offlineUuid(name);
        boolean matched = false;
        for (WorldNode world : worlds) {
            for (OwnerNode owner : world.owners) {
                if (owner.uuid.equalsIgnoreCase(offline.toString())) {
                    owner.displayName = name;
                    matched = true;
                }
            }
        }
        if (matched) {
            PlayerNameResolver.putCached(offline, name);
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
        // 本机：在游戏目录的全部存档中查找该备份并写入本地导出目录
        MaidFileData data = MaidBackupService.readLocal(
                this.minecraft.gameDirectory.toPath(), selOwner, selMaid, selFile);
        if (data == null) {
            setFeedback(Component.translatable("gui.maid_file_manager.backup.export_failed"), ERROR_COLOR);
            return;
        }
        writeLocal(data);
    }

    /** 把一份备份数据写入客户端本地导出目录（文件名规则与普通导出一致） */
    private void writeLocal(MaidFileData data) {
        try {
            String ownerName = findOwnerDisplayName(data.getOwnerUuid());
            data.setOwnerName(ownerName);
            Path exportRoot = MaidFileIo.ensureExportsDir(this.minecraft.gameDirectory.toPath());
            Path playerDir = MaidFileIo.resolvePlayerDir(exportRoot, ownerName);
            String written = MaidFileIo.writeMaidFile(playerDir, data.getDisplayName(), data.getModelId(),
                    data.getOwnerUuid(), data, LocalDateTime.now());
            setFeedback(Component.translatable("gui.maid_file_manager.backup.exported", written), ACCENT);
        } catch (Throwable t) {
            Constants.LOG.error("[maid_file_manager] 导出备份为 .maid 失败", t);
            setFeedback(Component.translatable("gui.maid_file_manager.backup.export_failed"), ERROR_COLOR);
        }
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
        applyServerOwners(list);
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

    private void renderList(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.fill(listX, listY, listX + listW, listY + listH, LIST_BG);
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
            String text = arrow + row.world.name + "  (" + row.world.owners.size() + " 位玩家)";
            graphics.drawString(this.font, Component.literal(text), listX + 4, top + 3, HEADER_COLOR, false);
        } else if (row.type == 1) {
            String name = row.owner.displayName != null ? row.owner.displayName : shortId(row.owner.uuid);
            String text = name + "  (" + row.owner.maids.size() + ")";
            graphics.drawString(this.font, Component.literal(text), listX + 14, top + 3, HEADER_COLOR, false);
        } else if (row.type == 2) {
            String arrow = row.maid.expanded ? "▼ " : "▶ ";
            String name = row.maid.name != null ? row.maid.name : shortId(row.maid.uuid);
            graphics.drawString(this.font, Component.literal(arrow + name),
                    listX + 24, top + 3, ACCENT, false);
        } else {
            String prefix = row.maid.files.indexOf(row.file) == 0 ? "● " : "○ ";
            graphics.drawString(this.font, Component.literal("    " + prefix + row.file),
                    listX + 34, top + 3, TEXT_COLOR, false);
        }
    }

    /** 打断 Screen 默认的背景绘制链（1.20.1 会画泥土/渐变背景），底色与面板已在 render() 中绘制 */
    @Override
    public void renderBackground(GuiGraphics graphics) {
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
