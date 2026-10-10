package io.github.zgxhzhr.maidfm.client;

import io.github.zgxhzhr.maidfm.Constants;
import io.github.zgxhzhr.maidfm.config.MaidConfigManager;
import io.github.zgxhzhr.maidfm.network.IMaidFileNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 饰品导入设置次级界面（从设置界面「饰品导入设置」按钮进入）。
 *
 * <p>包含两项<b>服务端配置</b>（均由 OP 管控，写入服务端 {@code config/maid_file_manager-server.properties}，
 * 玩家进服时自动同步；非 OP 只读，无法修改）：
 * <ul>
 *   <li><b>丢弃饰品属性</b>：导入时是否丢弃附魔/耐久/无法破坏/属性修饰符等，恢复为全新物品（默认保留）；</li>
 *   <li><b>禁用携带列表</b>：枚举车万本体（touhou_little_maid）与万法皆通（touhou_little_maid_spell）
 *       两命名空间内真正可佩戴的饰品（以 TLM 饰品注册表判定，与游戏内槽位校验一致），
 *       支持按名称/ID 搜索并滚动多选；勾选的饰品在导入时白名单校验之前直接丢弃。</li>
 * </ul>
 *
 * <p>视觉与设置界面一致：全屏不透明深色背景 + 深棕主面板；物品名 + 图标列表自绘
 * （不使用第三方 GUI 库），带搜索框、滚动条与逐行手绘复选框。
 */
public class MaidBaubleImportScreen extends Screen {
    // ===== 配色（与设置界面保持一致） =====
    private static final int SCREEN_BG = 0xFF101010;
    private static final int PANEL_BG = 0xFF282018;
    private static final int PANEL_BORDER = 0xFF8B5A2B;
    private static final int HEADER_COLOR = 0xFFFFE082;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int SUBTEXT_COLOR = 0xFFB0B0B0;
    private static final int LIST_BG = 0xFF181008;
    private static final int ROW_SELECTED = 0xFF2E3820;
    /** 木色按钮配色（与主面板同色系，替代原版灰底按钮） */
    private static final int BUTTON_BG = 0xFF3A2C1C;
    private static final int BUTTON_BG_HOVER = 0xFF4A3826;
    /** 滑动开关配色 */
    private static final int TRACK_OFF = 0xFF4A4038;
    private static final int TRACK_ON = 0xFF5A8A3A;
    private static final int KNOB = 0xFFE8E0D0;
    private static final int LABEL_ALLOW = 0xFFB8E08A;
    private static final int LABEL_DENY = 0xFFB0B0B0;
    /** 手绘复选框（与主管理界面同款） */
    private static final int CHECK_SIZE = 9;
    private static final int CHECK_BORDER = 0xFFA89070;
    private static final int CHECK_INNER = 0xFF18120C;
    private static final int CHECK_MARK = 0xFFFFD700;
    private static final int[][] CHECK_PIXELS = {
            {5, 0},
            {4, 1}, {5, 1},
            {3, 2}, {4, 2},
            {1, 3}, {2, 3}, {3, 3}, {4, 3},
            {0, 4}, {1, 4}, {2, 4}, {3, 4},
            {1, 5}, {2, 5},
            {2, 6}
    };

    private static final int PANEL_W = 360;
    private static final int SWITCH_W = 64;
    private static final int SWITCH_H = 16;
    private static final int SEARCH_H = 14;
    private static final int DONE_H = 22;
    private static final int ROW_H = 22;
    private static final int LIST_H = 190;
    private static final int SCROLL_W = 3;

    private final Screen parent;
    private int panelX;
    private int panelY;
    private int panelH;
    private int listX;
    private int listY;
    private int listW;
    private int listH;
    private int scrollOffset;
    /** 搜索关键字（小写，匹配物品 ID 或名称） */
    private String searchQuery = "";
    /** 丢弃饰品属性开关状态（内存态，点完成时提交服务端） */
    private boolean stripAttributes;
    /** 已勾选的禁用物品 ID（保持勾选顺序；点完成时提交服务端） */
    private final Set<String> blockedIds = new LinkedHashSet<>();
    /** 当前玩家是否为 OP：服务端配置仅 OP 可改，非 OP 只读（开关/勾选禁用） */
    private boolean canEdit;
    /** 黑/白名单是否由整合包配置（bauble_import.json）托管：托管时即使 OP 也只读展示 */
    private boolean managed;
    /** 是否允许勾选禁用列表：需 OP 且未被整合包托管 */
    private boolean canEditList;
    /** 两命名空间全部饰品（id → 物品栈，供图标/名称显示） */
    private final List<ItemEntry> entries = new ArrayList<>();
    /** 当前搜索结果（entries 按搜索关键字过滤后的子集，列表只渲染它） */
    private final List<ItemEntry> filtered = new ArrayList<>();
    /** 非 widget 文字（区块标题/行标签），render 阶段统一绘制 */
    private final List<RowLabel> rowLabels = new ArrayList<>();
    /** 丢弃属性滑动开关 */
    private StripToggle stripToggle;
    /** 饰品搜索框 */
    private EditBox searchField;

    public MaidBaubleImportScreen(Screen parent) {
        super(Component.translatable("gui.maid_file_manager.config.bauble_import_settings"));
        this.parent = parent;
        // 读取登录时同步下来的服务端配置缓存（非 OP 只读展示）
        this.stripAttributes = MaidConfigManager.cachedBaubleStripAttributes();
        this.blockedIds.addAll(MaidConfigManager.cachedBaubleBlockedList());
        this.managed = MaidConfigManager.cachedBaubleConfigManaged();
    }

    @Override
    protected void init() {
        super.init();
        rowLabels.clear();
        // 服务端配置仅 OP 可改：局域网联机=宿主默认 OP
        this.canEdit = this.minecraft != null && this.minecraft.player != null
                && this.minecraft.player.hasPermissions(2);
        // 黑/白名单托管（整合包 bauble_import.json 存在）时，列表只读：OP 也不能在游戏内增删
        this.canEditList = this.canEdit && !this.managed;
        // 枚举两命名空间里真正可佩戴的饰品：判定以 TLM 的饰品注册表 BaubleManager 为准。
        // 饰品物品本体（如 ItemDamageableBauble）并未实现 IMaidBauble——饰品行为对象
        // 是由 BaubleManager 以「物品 → 饰品行为」映射登记的（与游戏内饰品槽位校验同源），
        // 故不能对 Item 做 instanceof 判定，否则列表恒为空；工具/材料/书籍等非饰品物品不进禁用列表。
        entries.clear();
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
            if (key == null) {
                continue;
            }
            String ns = key.getNamespace();
            if (!"touhou_little_maid".equals(ns) && !"touhou_little_maid_spell".equals(ns)) {
                continue;
            }
            ItemStack stack = item.getDefaultInstance();
            if (!isBaubleItem(stack)) {
                continue;
            }
            entries.add(new ItemEntry(key.toString(), stack));
        }
        entries.sort(Comparator.comparing(e -> e.id));
        rebuildFiltered();

        panelX = (this.width - PANEL_W) / 2;
        int labelX = panelX + 20;
        int switchX = panelX + PANEL_W - 20 - SWITCH_W;

        // 「丢弃饰品属性」说明文字较长，按开关左侧的可用宽度折行（最多 2 行），
        // 行高随行数增长，避免文字尾部被右侧滑动开关盖住
        List<String> stripLines = wrapLines(
                Component.translatable("gui.maid_file_manager.config.bauble_import_strip").getString(),
                switchX - labelX - 8, 2);
        int stripRowH = Math.max(SWITCH_H, stripLines.size() * 9) + 6;
        // 非 OP 时在丢弃属性开关下方补一行"无权限"提示
        int hintRowH = canEdit ? 0 : 12;

        // 整合包托管提示（1 行）+ 白名单摘要（白名单非空时 1 行，只读展示）
        List<String> managedHints = new ArrayList<>();
        if (managed) {
            managedHints.add(Component.translatable(
                    "gui.maid_file_manager.config.bauble_import_managed_hint").getString());
            List<String> wl = MaidConfigManager.cachedBaubleWhitelist();
            if (!wl.isEmpty()) {
                managedHints.add(Component.translatable(
                        "gui.maid_file_manager.config.bauble_import_whitelist_summary",
                        wl.size(), String.join("、", wl)).getString());
            }
        }
        int managedRowH = managedHints.size() * 11;

        // 先按内容结构算出面板高度，再垂直居中
        panelH = 8 + 16 + 4     // 标题
                + stripRowH     // 丢弃属性区块：说明文字行 + 开关
                + hintRowH      // 无权限提示行（仅非 OP）
                + 14            // 禁用列表区块标题
                + managedRowH   // 整合包托管提示 / 白名单摘要（仅托管时）
                + SEARCH_H + 6  // 搜索框行
                + LIST_H        // 列表区
                + 12 + DONE_H + 12;   // 完成按钮 + 底部边距
        panelY = Math.max(10, (this.height - panelH) / 2);

        int y = panelY + 8 + 16 + 4;

        // ===== 丢弃饰品属性区块 =====
        for (int i = 0; i < stripLines.size(); i++) {
            rowLabels.add(new RowLabel(Component.literal(stripLines.get(i)),
                    labelX, y + i * 9, TEXT_COLOR));
        }
        stripToggle = new StripToggle(switchX, y + (stripRowH - 6 - SWITCH_H) / 2);
        stripToggle.active = canEdit;
        addRenderableWidget(stripToggle);
        y += stripRowH;
        if (!canEdit) {
            rowLabels.add(new RowLabel(Component.translatable("gui.maid_file_manager.config.no_permission"),
                    labelX, y + 2, SUBTEXT_COLOR));
            y += hintRowH;
        }

        // ===== 禁用携带列表区块 =====
        rowLabels.add(new RowLabel(Component.translatable("gui.maid_file_manager.config.bauble_import_blocked_list"),
                labelX, y, HEADER_COLOR));
        y += 14;
        // 整合包托管提示 / 白名单摘要（只读）：贴在区块标题下方，明确"游戏内不可改"的原因与当前白名单
        for (String hint : managedHints) {
            rowLabels.add(new RowLabel(Component.literal(hint), labelX, y, SUBTEXT_COLOR));
            y += 11;
        }
        // 搜索框：按饰品名称/ID 实时过滤列表
        searchField = new EditBox(this.font, labelX, y, PANEL_W - 40 - SCROLL_W - 4, SEARCH_H,
                Component.translatable("gui.maid_file_manager.config.bauble_import_search"));
        searchField.setMaxLength(64);
        searchField.setTextColor(TEXT_COLOR);
        searchField.setResponder(this::onSearchChanged);
        addRenderableWidget(searchField);
        y += SEARCH_H + 6;
        listX = labelX;
        listW = PANEL_W - 40 - SCROLL_W - 4;
        listH = LIST_H;
        listY = y;
        scrollOffset = 0;

        // ===== 完成（保存并返回设置界面） =====
        y += listH + 12;
        int doneW = 140;
        addRenderableWidget(new WoodButton(panelX + (PANEL_W - doneW) / 2, y, doneW, DONE_H,
                Component.translatable("gui.maid_file_manager.config.done"), this::onSaveAndClose));
    }

    /** 完成：把丢弃属性开关与禁用列表提交服务端（服务端校验 OP 后写文件并广播同步），再返回设置界面 */
    private void onSaveAndClose() {
        if (canEdit) {
            IMaidFileNetwork net = IMaidFileNetwork.Holder.get();
            if (net != null) {
                // 「丢弃饰品属性」始终可改；禁用列表在整合包托管时只读，不提交（服务端亦会拒绝）
                net.sendSetServerConfig(MaidConfigManager.KEY_BAUBLE_STRIP_ATTRIBUTES, stripAttributes);
                if (!managed) {
                    net.sendSetServerBaubleBlockedList(new ArrayList<>(blockedIds));
                }
            } else {
                Constants.LOG.warn("[maid_file_manager] 网络实现缺失，饰品导入设置未提交服务端");
            }
        }
        onClose();
    }

    // ===== 搜索与列表数据 =====

    /** 搜索关键字变化：重建过滤列表并回到顶部 */
    private void onSearchChanged(String query) {
        this.searchQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        rebuildFiltered();
        scrollOffset = 0;
    }

    private void rebuildFiltered() {
        filtered.clear();
        if (searchQuery.isEmpty()) {
            filtered.addAll(entries);
            return;
        }
        for (ItemEntry e : entries) {
            String name = e.stack.getHoverName().getString().toLowerCase(Locale.ROOT);
            if (e.id.toLowerCase(Locale.ROOT).contains(searchQuery) || name.contains(searchQuery)) {
                filtered.add(e);
            }
        }
    }

    /**
     * 按像素宽度把文本折成多行（最多 maxLines 行，超出部分直接截断）。
     * 用于长说明文字在右侧控件（如滑动开关）左侧的可用宽度内折行，避免被盖住。
     */
    private List<String> wrapLines(String text, int maxWidth, int maxLines) {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int curWidth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int w = this.font.width(String.valueOf(c));
            if (cur.length() > 0 && curWidth + w > maxWidth) {
                lines.add(cur.toString());
                cur = new StringBuilder();
                curWidth = 0;
                if (lines.size() >= maxLines) {
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

    /**
     * TLM 饰品注册表查询方法（{@code BaubleManager.getBauble(ItemStack)}），首次使用时解析并缓存。
     * <p>不能用编译期直接引用：common 模块的编译类路径里没有加载器类型，而该方法所在类还存在
     * 以 {@code RegistryObject}（Forge）/ {@code DeferredHolder}（NeoForge）为参数的同类重载，
     * javac 解析重载时必须加载这些类型，会直接编译失败。
     */
    private static Method baubleLookup;
    private static boolean baubleLookupFailed;

    /**
     * 判断物品是否为 TLM 可佩戴饰品。
     * <p>饰品物品本体（如 {@code ItemDamageableBauble}）并不实现 {@code IMaidBauble}，
     * 饰品行为对象由 TLM 内部注册表以「物品 → 饰品行为」映射登记，与游戏内饰品槽位校验同源，
     * 故只能查该注册表，不能对 {@code Item} 做 instanceof 判定（否则列表恒为空）。
     * <p>反射解析失败时降级为「按命名空间全收」，并输出一次警告，避免列表静默为空。
     */
    private static boolean isBaubleItem(ItemStack stack) {
        if (baubleLookup == null && !baubleLookupFailed) {
            try {
                Class<?> c = Class.forName(
                        "com.github.tartaricacid.touhoulittlemaid.item.bauble.BaubleManager");
                baubleLookup = c.getMethod("getBauble", ItemStack.class);
            } catch (Throwable t) {
                baubleLookupFailed = true;
                Constants.LOG.warn("[maid_file_manager] 无法解析饰品注册表，禁用列表将降级为按命名空间列出全部物品: {}",
                        t.toString());
            }
        }
        if (baubleLookup == null) {
            return true;
        }
        try {
            return baubleLookup.invoke(null, stack) != null;
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 饰品注册表查询失败，该物品按饰品处理: {}", t.toString());
            return true;
        }
    }

    // ===== 列表交互 =====

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 非 OP 只读：列表只展示，不允许勾选；整合包托管时即使 OP 也只读
        if (button == 0 && canEditList && isInsideList(mouseX, mouseY)) {
            int index = scrollOffset + (int) ((mouseY - listY) / ROW_H);
            if (index >= 0 && index < filtered.size()) {
                toggleBlocked(index);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 处理滚轮：仅当光标位于列表区时滚动（1.20.x 官方映射仅三参签名） */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (!isInsideList(mouseX, mouseY)) {
            return false;
        }
        int visibleRows = listH / ROW_H;
        int maxScroll = Math.max(0, filtered.size() - visibleRows);
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset + (amount > 0 ? -3 : 3)));
        return true;
    }

    private boolean isInsideList(double mouseX, double mouseY) {
        return mouseX >= listX && mouseX <= listX + listW
                && mouseY >= listY && mouseY <= listY + listH;
    }

    private void toggleBlocked(int index) {
        String id = filtered.get(index).id;
        if (!blockedIds.remove(id)) {
            blockedIds.add(id);
        }
    }

    // ===== 渲染 =====

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // ① 全屏不透明深色底（renderBackground 已空实现，不会有泥土底/blur 层叠上来）
        graphics.fill(0, 0, this.width, this.height, SCREEN_BG);
        // ② 主面板 + 边框
        graphics.fill(panelX, panelY, panelX + PANEL_W, panelY + panelH, PANEL_BG);
        graphics.renderOutline(panelX, panelY, PANEL_W, panelH, PANEL_BORDER);
        // ③ 标题（禁用阴影，清晰显示）
        graphics.drawString(this.font, this.title,
                this.width / 2 - this.font.width(this.title) / 2, panelY + 8, HEADER_COLOR, false);
        // ④ 区块标题与行标签
        for (RowLabel row : rowLabels) {
            graphics.drawString(this.font, row.text(), row.x(), row.y(), row.color(), false);
        }
        // ⑤ 禁用携带列表（自绘滚动区）
        renderList(graphics, mouseX, mouseY);
        // ⑥ 子组件（滑动开关 / 搜索框 / 完成按钮）在面板之上正常绘制一次即可
        super.render(graphics, mouseX, mouseY, partialTick);
        // ⑦ 悬停行 tooltip：显示完整物品 ID（名称可能重复，ID 唯一）
        ItemEntry hovered = getHoveredRow(mouseX, mouseY);
        if (hovered != null) {
            graphics.renderTooltip(this.font, Component.literal(hovered.id), mouseX, mouseY);
        }
    }

    /** 自绘滚动列表：逐行复选框 + 图标 + 名称，右侧滚动条；无匹配时给出提示 */
    private void renderList(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.enableScissor(listX, listY, listX + listW, listY + listH);
        graphics.fill(listX, listY, listX + listW, listY + listH, LIST_BG);
        if (filtered.isEmpty()) {
            graphics.drawString(this.font,
                    Component.translatable("gui.maid_file_manager.config.bauble_import_no_match"),
                    listX + 6, listY + 6, SUBTEXT_COLOR, false);
            graphics.disableScissor();
            return;
        }
        int visibleRows = listH / ROW_H;
        for (int i = 0; i < visibleRows; i++) {
            int index = scrollOffset + i;
            if (index >= filtered.size()) {
                break;
            }
            ItemEntry e = filtered.get(index);
            int top = listY + i * ROW_H;
            boolean selected = blockedIds.contains(e.id);
            if (selected) {
                graphics.fill(listX, top, listX + listW, top + ROW_H, ROW_SELECTED);
            }
            drawCheckBox(graphics, listX + 4, top + (ROW_H - CHECK_SIZE) / 2, selected);
            graphics.renderItem(e.stack, listX + 4 + CHECK_SIZE + 4, top + 2);
            graphics.drawString(this.font, e.stack.getHoverName(),
                    listX + 4 + CHECK_SIZE + 4 + 18, top + 6, TEXT_COLOR, false);
        }
        graphics.disableScissor();
        // 滚动条（仅在内容超出可视区时绘制）
        if (filtered.size() > visibleRows) {
            int barX = listX + listW + 2;
            int barH = Math.max(12, (int) (listH * ((double) visibleRows / filtered.size())));
            int maxScroll = filtered.size() - visibleRows;
            int barY = listY + (int) ((listH - barH) * ((double) scrollOffset / maxScroll));
            graphics.fill(barX, barY, barX + SCROLL_W, barY + barH, PANEL_BORDER);
        }
    }

    private ItemEntry getHoveredRow(double mouseX, double mouseY) {
        if (!isInsideList(mouseX, mouseY)) {
            return null;
        }
        int index = scrollOffset + (int) ((mouseY - listY) / ROW_H);
        if (index >= 0 && index < filtered.size()) {
            return filtered.get(index);
        }
        return null;
    }

    /** 手绘复选框（与主管理界面同款） */
    private void drawCheckBox(GuiGraphics graphics, int x, int y, boolean checked) {
        graphics.fill(x, y, x + CHECK_SIZE, y + CHECK_SIZE, CHECK_BORDER);
        graphics.fill(x + 1, y + 1, x + CHECK_SIZE - 1, y + CHECK_SIZE - 1, CHECK_INNER);
        if (checked) {
            for (int[] p : CHECK_PIXELS) {
                graphics.fill(x + 1 + p[0], y + 1 + p[1], x + 2 + p[0], y + 2 + p[1], CHECK_MARK);
            }
        }
    }

    /** 打断 Screen 默认的单参 renderBackground 调用链（1.20.x 会画泥土背景并触发背景模糊） */
    @Override
    public void renderBackground(GuiGraphics graphics) {
        // 什么都不做 —— 全屏底色 + 面板统一在 render() 开头绘制
    }

    /** 非暂停界面（1.20.x 官方映射方法名为 isPauseScreen，已用 javap 核实） */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    /** 行标签（非交互文字） */
    private record RowLabel(Component text, int x, int y, int color) {
    }

    /** 列表条目：完整物品 ID + 默认物品栈（图标/名称显示用） */
    private record ItemEntry(String id, ItemStack stack) {
    }

    /**
     * 丢弃饰品属性滑动开关：点击切换，滑块带平滑动画。
     * 状态写入本屏字段 stripAttributes，点「完成」时统一提交服务端（非 OP 时禁用）。
     */
    private class StripToggle extends AbstractButton {
        private boolean state;
        /** 滑块动画进度：0=左（不丢弃=保留），1=右（丢弃） */
        private float slide;

        StripToggle(int x, int y) {
            super(x, y, SWITCH_W, SWITCH_H, Component.empty());
            this.state = MaidBaubleImportScreen.this.stripAttributes;
            this.slide = this.state ? 1.0F : 0.0F;
        }

        @Override
        public void onPress() {
            this.state = !this.state;
            MaidBaubleImportScreen.this.stripAttributes = this.state;
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            float target = this.state ? 1.0F : 0.0F;
            this.slide += (target - this.slide) * 0.35F;
            if (Math.abs(target - this.slide) < 0.02F) {
                this.slide = target;
            }
            int track = this.active
                    ? blendColor(TRACK_OFF, TRACK_ON, this.slide)
                    : blendColor(0xFF332E28, 0xFF3A4433, this.slide);
            graphics.fill(getX(), getY(), getX() + width, getY() + height, track);
            graphics.renderOutline(getX(), getY(), width, height, PANEL_BORDER);

            Component text = Component.translatable(this.state
                    ? "gui.maid_file_manager.config.on_label"
                    : "gui.maid_file_manager.config.off_label");
            int textColor = this.active ? (this.state ? LABEL_ALLOW : LABEL_DENY) : LABEL_DENY;
            int textX = this.state
                    ? getX() + 5
                    : getX() + width - 5 - MaidBaubleImportScreen.this.font.width(text);
            graphics.drawString(MaidBaubleImportScreen.this.font, text, textX, getY() + (height - 8) / 2, textColor, false);

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

    /**
     * 木色完成按钮：与主面板同色系的深棕底 + 木色描边 + 白字（无阴影），
     * 替代原版灰色按钮，避免与木色面板风格冲突。
     */
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
            graphics.fill(getX(), getY(), getX() + width, getY() + height,
                    hovered ? BUTTON_BG_HOVER : BUTTON_BG);
            graphics.renderOutline(getX(), getY(), width, height, PANEL_BORDER);
            int tx = getX() + (width - MaidBaubleImportScreen.this.font.width(getMessage())) / 2;
            int ty = getY() + (height - 8) / 2;
            graphics.drawString(MaidBaubleImportScreen.this.font, getMessage(), tx, ty, TEXT_COLOR, false);
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
