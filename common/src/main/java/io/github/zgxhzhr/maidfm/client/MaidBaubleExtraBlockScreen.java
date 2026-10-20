package io.github.zgxhzhr.maidfm.client;

import io.github.zgxhzhr.maidfm.config.MaidConfigManager;
import io.github.zgxhzhr.maidfm.network.IMaidFileNetwork;
import io.github.zgxhzhr.maidfm.platform.Services;
import io.github.zgxhzhr.maidfm.spi.MaidBaubleCarrierRegistry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 饰品禁用设置（按模组 / 按 Curios 槽位）。
 *
 * <p>与本模组其它设置界面的操作方式一致：<b>顶部标签切换 → 搜索 → 逐行点击勾选/取消 → 完成提交</b>。
 *
 * <ul>
 *   <li><b>按模组</b>：列出当前整合包里<b>所有已加载模组</b>（不依赖对方是否接入声明接口），
 *       每行标注该模组状态：<i>已声明可携带</i>（接入过 {@code MaidBaubleCarrier}）或
 *       <i>未适配·默认丢弃</i>。勾选＝禁用该模组的饰品，对女仆自带饰品栏与 Curios 槽位同时生效。</li>
 *   <li><b>按 Curios 槽位</b>：硬禁槽位（背包类 / 已核实的数据容器）显示为「永久禁止」且不可取消；
 *       其余槽位可在输入框里输入名字后点「添加」加入禁用，点击已加入的行可移除。</li>
 * </ul>
 *
 * <p>两个清单分别写入服务端 {@code bauble_blocked_namespaces} 与 {@code bauble_blocked_curios_slots}，
 * 服务端做权限校验与字符集收窄后广播同步。
 *
 * <p>翻页用按钮而非滚轮：1.20 与 1.21 的 {@code mouseScrolled} 签名不同，同一份源码无法两代通用。
 */
public class MaidBaubleExtraBlockScreen extends Screen {

    // ===== 配色（与设置界面一致） =====
    private static final int SCREEN_BG = 0xFF101010;
    private static final int PANEL_BG = 0xFF282018;
    private static final int PANEL_BORDER = 0xFF8B5A2B;
    private static final int HEADER_COLOR = 0xFFFFE082;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int SUBTEXT_COLOR = 0xFFB0B0B0;
    private static final int HINT_COLOR = 0xFFC8B890;
    private static final int LIST_BG = 0xFF181008;
    private static final int ROW_HOVER = 0xFF33281C;
    private static final int ROW_CHECKED = 0xFF2E3820;
    private static final int ROW_READONLY = 0xFF3A2424;
    private static final int CHECK_ON = 0xFF5A8A3A;
    private static final int CHECK_OFF = 0xFF4A4038;
    private static final int OK_COLOR = 0xFF9FD07A;
    private static final int WARN_COLOR = 0xFFD8A070;

    private static final int PANEL_W = 400;
    private static final int ROW_H = 14;
    private static final int ROWS_PER_PAGE = 9;
    private static final int LIST_H = ROWS_PER_PAGE * ROW_H;
    /** 面板内固定纵向间距（避免任何重叠） */
    private static final int TITLE_Y = 8;
    private static final int HINT_Y = 22;
    private static final int TAB_Y = 46;
    private static final int SEARCH_Y = 64;
    private static final int LIST_Y = 82;
    private static final int PAGER_Y = LIST_Y + LIST_H + 8;
    private static final int PANEL_H = PAGER_Y + 16 + 6 + 24;

    /** 硬禁槽位精确名单（与 MaidBaubleCarrierRegistry 保持一致；只读展示） */
    private static final List<String> HARD_BLOCKED_SLOTS = List.of(
            "back", "curio_back", "backpack", "backpacks", "ionocraft_backpack", "arrow_bag",
            "klein_star", "deep_learner", "soul", "soul_stone", "soultotem");

    private final Screen parent;
    private boolean canEdit;

    /** 0 = 按物品（只读展示，实际勾选在主列表），1 = 按模组，2 = 按 Curios 槽位 */
    private int tab;

    private final List<String> itemEntries = new ArrayList<>();
    private final Set<String> blockedItems = new LinkedHashSet<>();
    private final List<String> modIds = new ArrayList<>();
    private final Map<String, String> modNames = new HashMap<>();
    private final Set<String> declared = new LinkedHashSet<>();
    private final List<String> slotEntries = new ArrayList<>();

    private final Set<String> blockedNamespaces = new LinkedHashSet<>();
    /** 整合包强制禁用的模组 / 槽位（只读，游戏内不可取消） */
    private final Set<String> managedNamespaces = new LinkedHashSet<>();
    private final Set<String> managedSlots = new LinkedHashSet<>();
    private final Set<String> blockedSlots = new LinkedHashSet<>();

    private final List<String> shown = new ArrayList<>();
    private EditBox filterField;
    private Button addButton;
    private int page;
    private int panelX;
    private int panelY;
    private int listX;
    private int listW;

    public MaidBaubleExtraBlockScreen(Screen parent) {
        super(Component.translatable("gui.maid_file_manager.config.bauble_extra_block"));
        this.parent = parent;
    }

    @Override
    public void removed() {
        // 界面背景音乐（试验性功能）：离开界面时释放，全部界面关闭后延迟停止
        UiMusicManager.leave(this);
        super.removed();
    }

    @Override
    public void tick() {
        super.tick();
        // 界面期间每帧压住原版背景音乐（其 MusicManager 会自动重播，单次停止无效）
        UiMusicManager.tick();
    }

    @Override
    protected void init() {
        UiMusicManager.enter(this);
        this.canEdit = this.minecraft != null && this.minecraft.player != null
                && this.minecraft.player.hasPermissions(2);

        blockedNamespaces.clear();
        blockedNamespaces.addAll(MaidConfigManager.cachedBaubleBlockedNamespaces());
        blockedSlots.clear();
        blockedSlots.addAll(MaidConfigManager.cachedBaubleBlockedCuriosSlots());
        managedNamespaces.clear();
        managedNamespaces.addAll(MaidConfigManager.cachedBaubleImportBlockedNamespaces());
        managedSlots.clear();
        managedSlots.addAll(MaidConfigManager.cachedBaubleImportBlockedCuriosSlots());

        declared.clear();
        declared.addAll(MaidBaubleCarrierRegistry.declaredNamespaces());

        blockedItems.clear();
        blockedItems.addAll(MaidConfigManager.cachedBaubleBlockedList());

        // 按物品：遍历全部物品并用 TLM 饰品注册表判定"真正可佩戴的女仆饰品"——
        // 不限命名空间，任何模组的饰品都会出现（与游戏内饰品槽位校验同源）
        itemEntries.clear();
        Set<String> itemIds = new LinkedHashSet<>(cachedAllBaubleIds());
        itemIds.addAll(blockedItems);
        itemIds.addAll(MaidConfigManager.cachedBaubleWhitelist());
        itemIds.addAll(MaidConfigManager.cachedBaubleBlacklist());
        itemEntries.addAll(itemIds);
        itemEntries.sort(String::compareTo);

        modIds.clear();
        modNames.clear();
        try {
            Map<String, String> all = Services.PLATFORM.get().getAllMods();
            if (all != null) {
                for (Map.Entry<String, String> e : all.entrySet()) {
                    if (e.getKey() == null || e.getKey().isEmpty()) {
                        continue;
                    }
                    modIds.add(e.getKey());
                    modNames.put(e.getKey(), e.getValue() == null || e.getValue().isEmpty()
                            ? e.getKey() : e.getValue());
                }
            }
        } catch (Throwable t) {
            // 平台层已容错，这里再兜一层：界面绝不因取模组列表失败而崩
        }
        modIds.sort((a, b) -> {
            boolean da = declared.contains(a);
            boolean db = declared.contains(b);
            if (da != db) {
                return da ? -1 : 1;
            }
            return modNames.getOrDefault(a, a).compareToIgnoreCase(modNames.getOrDefault(b, b));
        });

        slotEntries.clear();
        slotEntries.addAll(blockedSlots);
        for (String hard : HARD_BLOCKED_SLOTS) {
            if (!slotEntries.contains(hard)) {
                slotEntries.add(hard);
            }
        }
        slotEntries.sort((a, b) -> {
            boolean ha = HARD_BLOCKED_SLOTS.contains(a);
            boolean hb = HARD_BLOCKED_SLOTS.contains(b);
            if (ha != hb) {
                return ha ? -1 : 1;
            }
            return a.compareTo(b);
        });

        panelX = (this.width - PANEL_W) / 2;
        panelY = Math.max(8, (this.height - PANEL_H) / 2);
        listX = panelX + 20;
        listW = PANEL_W - 40;

        // 三个标签：按物品 / 按模组 / 按 Curios 槽位
        int tabW = (listW - 12) / 3;
        addRenderableWidget(Button.builder(Component.translatable("gui.maid_file_manager.config.bauble_extra_by_item"),
                        b -> switchTab(0))
                .bounds(listX, panelY + TAB_Y, tabW, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.maid_file_manager.config.bauble_extra_by_mod"),
                        b -> switchTab(1))
                .bounds(listX + tabW + 6, panelY + TAB_Y, tabW, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.maid_file_manager.config.bauble_extra_by_slot"),
                        b -> switchTab(2))
                .bounds(listX + (tabW + 6) * 2, panelY + TAB_Y, tabW, 16).build());

        // 搜索框（两个标签都用它过滤：模组按 id/显示名，槽位按名字）
        filterField = new EditBox(this.font, listX, panelY + SEARCH_Y, listW - 60, 14,
                Component.translatable("gui.maid_file_manager.config.bauble_extra_search"));
        filterField.setMaxLength(64);
        filterField.setTextColor(TEXT_COLOR);
        filterField.setResponder(s -> {
            page = 0;
            rebuildShown();
        });
        addRenderableWidget(filterField);
        addButton = Button.builder(Component.translatable("gui.maid_file_manager.config.bauble_extra_add"),
                        b -> addSlotFromFilter())
                .bounds(listX + listW - 56, panelY + SEARCH_Y, 56, 14).build();
        addButton.active = canEdit && tab == 2;
        addRenderableWidget(addButton);

        // 翻页
        int btnW = 56;
        addRenderableWidget(Button.builder(Component.translatable("gui.maid_file_manager.config.bauble_extra_prev"),
                        b -> {
                            if (page > 0) {
                                page--;
                                rebuildShown();
                            }
                        })
                .bounds(listX, panelY + PAGER_Y, btnW, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.maid_file_manager.config.bauble_extra_next"),
                        b -> {
                            int max = Math.max(0, (shown.size() - 1) / ROWS_PER_PAGE);
                            if (page < max) {
                                page++;
                                rebuildShown();
                            }
                        })
                .bounds(listX + listW - btnW, panelY + PAGER_Y, btnW, 16).build());

        // 完成 / 返回
        int doneW = 120;
        addRenderableWidget(Button.builder(Component.translatable("gui.maid_file_manager.config.done"),
                        b -> onSaveAndClose())
                .bounds(panelX + PANEL_W / 2 - doneW - 4, panelY + PANEL_H - 22, doneW, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.maid_file_manager.config.bauble_extra_back"),
                        b -> onClose())
                .bounds(panelX + PANEL_W / 2 + 4, panelY + PANEL_H - 22, doneW, 16).build());

        rebuildShown();
    }

    /** 把搜索框里输入的名字作为 Curios 槽位加入禁用清单（仅槽位标签可用） */
    private void addSlotFromFilter() {
        if (filterField == null) {
            return;
        }
        String text = filterField.getValue() == null ? "" : filterField.getValue().trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return;
        }
        if (!slotEntries.contains(text)) {
            slotEntries.add(text);
        }
        blockedSlots.add(text);
        filterField.setValue("");
        page = 0;
        rebuildShown();
    }

    private void switchTab(int newTab) {
        if (newTab != tab) {
            tab = newTab;
            page = 0;
            if (filterField != null) {
                filterField.setValue("");
            }
            if (addButton != null) {
                addButton.active = canEdit && tab == 2;
            }
            rebuildShown();
        }
    }

    /** 依据当前标签与搜索内容重建可见列表 */
    private void rebuildShown() {
        shown.clear();
        String q = filterField == null ? "" : filterField.getValue().trim().toLowerCase(Locale.ROOT);
        if (tab == 0) {
            for (String id : itemEntries) {
                if (q.isEmpty() || id.toLowerCase(Locale.ROOT).contains(q)) {
                    shown.add(id);
                }
            }
        } else if (tab == 1) {
            for (String modId : modIds) {
                String name = modNames.getOrDefault(modId, modId);
                if (q.isEmpty() || modId.toLowerCase(Locale.ROOT).contains(q)
                        || name.toLowerCase(Locale.ROOT).contains(q)) {
                    shown.add(modId);
                }
            }
            shown.sort((a, b) -> Boolean.compare(blockedNamespaces.contains(b), blockedNamespaces.contains(a)));
        } else {
            for (String slot : slotEntries) {
                if (q.isEmpty() || slot.toLowerCase(Locale.ROOT).contains(q)) {
                    shown.add(slot);
                }
            }
        }
        int max = Math.max(0, (shown.size() - 1) / ROWS_PER_PAGE);
        if (page > max) {
            page = max;
        }
    }

    /** 按宽度折行绘制（最多两行，末行超出用省略号），避免提示文字越界 */
    private void drawWrapped(GuiGraphics graphics, String text, int x, int y, int maxWidth, int color) {
        if (this.font.width(text) <= maxWidth) {
            graphics.drawString(this.font, text, x, y, color, false);
            return;
        }
        String rest = text;
        for (int line = 0; line < 2 && !rest.isEmpty(); line++) {
            int cut = rest.length();
            while (cut > 0 && this.font.width(rest.substring(0, cut)) > maxWidth) {
                cut--;
            }
            if (cut <= 0) {
                cut = 1;
            }
            boolean truncated = cut < rest.length();
            String piece = rest.substring(0, cut);
            if (line == 1 && truncated) {
                int c2 = piece.length();
                while (c2 > 0 && this.font.width(piece.substring(0, c2) + "...") > maxWidth) {
                    c2--;
                }
                piece = piece.substring(0, Math.max(1, c2)) + "...";
                truncated = false;
            }
            graphics.drawString(this.font, piece, x, y + line * 10, color, false);
            if (!truncated) {
                break;
            }
            rest = rest.substring(cut);
        }
    }

    private static List<String> allBaubleIdsCache;

    /** 全部"可佩戴女仆饰品"的物品 id（含所有模组；反射判定同主列表，静态缓存避免每次重算） */
    private static List<String> cachedAllBaubleIds() {
        if (allBaubleIdsCache == null) {
            List<String> out = new ArrayList<>();
            try {
                for (net.minecraft.world.item.Item item
                        : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
                    net.minecraft.resources.ResourceLocation key =
                            net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
                    if (key != null && isBaubleItem(item.getDefaultInstance())) {
                        out.add(key.toString());
                    }
                }
            } catch (Throwable t) {
                // 注册表异常时退化为空列表，不影响其它标签
            }
            allBaubleIdsCache = out;
        }
        return allBaubleIdsCache;
    }

    private static java.lang.reflect.Method baubleLookup;
    private static boolean baubleLookupFailed;

    /** 是否为 TLM 可佩戴饰品（反射查 BaubleManager.getBauble，与游戏内饰品槽位校验同源） */
    private static boolean isBaubleItem(net.minecraft.world.item.ItemStack stack) {
        if (baubleLookup == null && !baubleLookupFailed) {
            try {
                Class<?> c = Class.forName(
                        "com.github.tartaricacid.touhoulittlemaid.item.bauble.BaubleManager");
                baubleLookup = c.getMethod("getBauble", net.minecraft.world.item.ItemStack.class);
            } catch (Throwable t) {
                baubleLookupFailed = true;
            }
        }
        if (baubleLookup == null) {
            return true;
        }
        try {
            return baubleLookup.invoke(null, stack) != null;
        } catch (Throwable t) {
            return true;
        }
    }

    /** 物品显示名（取不到就退化为 id） */
    private String itemLabel(String id) {
        try {
            net.minecraft.resources.ResourceLocation key = net.minecraft.resources.ResourceLocation.tryParse(id);
            if (key != null && net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(key)) {
                return new net.minecraft.world.item.ItemStack(
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.get(key)).getHoverName().getString();
            }
        } catch (Throwable t) {
            // 忽略：显示 id 即可
        }
        return id;
    }

    private void toggleMod(String modId) {
        if (!blockedNamespaces.remove(modId)) {
            blockedNamespaces.add(modId);
        }
        rebuildShown();
    }

    private void toggleSlot(String slot) {
        if (HARD_BLOCKED_SLOTS.contains(slot)) {
            return;
        }
        if (!blockedSlots.remove(slot)) {
            blockedSlots.add(slot);
        }
        rebuildShown();
    }

    private void onSaveAndClose() {
        if (canEdit) {
            IMaidFileNetwork net = IMaidFileNetwork.Holder.get();
            if (net != null) {
                net.sendSetServerListConfig(MaidConfigManager.KEY_BAUBLE_BLOCKED_NAMESPACES,
                        new ArrayList<>(blockedNamespaces));
                net.sendSetServerListConfig(MaidConfigManager.KEY_BAUBLE_BLOCKED_CURIOS_SLOTS,
                        new ArrayList<>(blockedSlots));
            }
        }
        onClose();
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseX >= listX && mouseX <= listX + listW) {
            int top = panelY + LIST_Y;
            if (mouseY >= top && mouseY <= top + LIST_H) {
                int row = (int) ((mouseY - top) / ROW_H);
                int index = page * ROWS_PER_PAGE + row;
                if (index >= 0 && index < shown.size()) {
                    if (tab == 1) {
                        if (!managedNamespaces.contains(shown.get(index))) {
                            toggleMod(shown.get(index));
                        }
                    } else if (tab == 2) {
                        if (!managedSlots.contains(shown.get(index))) {
                            toggleSlot(shown.get(index));
                        }
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, SCREEN_BG);
        graphics.fill(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, PANEL_BG);
        graphics.renderOutline(panelX, panelY, PANEL_W, PANEL_H, PANEL_BORDER);

        graphics.drawString(this.font, this.title, listX, panelY + TITLE_Y, HEADER_COLOR, false);
        String hint = Component.translatable(tab == 0
                ? "gui.maid_file_manager.config.bauble_extra_hint_item"
                : (tab == 1
                        ? "gui.maid_file_manager.config.bauble_extra_hint_mod"
                        : "gui.maid_file_manager.config.bauble_extra_hint_slot")).getString();
        drawWrapped(graphics, hint, listX, panelY + HINT_Y, listW, HINT_COLOR);

        // 列表底
        int top = panelY + LIST_Y;
        graphics.fill(listX - 1, top - 1, listX + listW + 1, top + LIST_H + 1, LIST_BG);

        int start = page * ROWS_PER_PAGE;
        for (int i = 0; i < ROWS_PER_PAGE; i++) {
            int index = start + i;
            if (index >= shown.size()) {
                break;
            }
            String value = shown.get(index);
            int rowY = top + i * ROW_H;
            boolean hardBlocked = tab == 2 && HARD_BLOCKED_SLOTS.contains(value);
            boolean managed = hardBlocked
                    || (tab == 1 ? managedNamespaces.contains(value)
                            : (tab == 2 && managedSlots.contains(value)));
            boolean checked = managed
                    || (tab == 0 ? blockedItems.contains(value)
                            : (tab == 1 ? blockedNamespaces.contains(value) : blockedSlots.contains(value)));
            boolean hovered = !managed && mouseX >= listX && mouseX <= listX + listW
                    && mouseY >= rowY && mouseY < rowY + ROW_H;
            if (checked || hovered) {
                graphics.fill(listX, rowY, listX + listW, rowY + ROW_H - 1,
                        managed ? ROW_READONLY : (checked ? ROW_CHECKED : ROW_HOVER));
            }
            int boxY = rowY + 3;
            graphics.fill(listX + 3, boxY, listX + 11, boxY + 8, CHECK_OFF);
            if (checked) {
                graphics.fill(listX + 4, boxY + 1, listX + 10, boxY + 7, CHECK_ON);
            }

            if (tab == 0) {
                String label = itemLabel(value);
                graphics.drawString(this.font, label, listX + 16, rowY + 3, TEXT_COLOR, false);
                int idW = this.font.width(value);
                if (listX + listW - 12 - idW > listX + 16 + this.font.width(label) + 8) {
                    graphics.drawString(this.font, value, listX + listW - idW - 4, rowY + 3, SUBTEXT_COLOR, false);
                }
            } else if (tab == 1) {
                String name = modNames.getOrDefault(value, value);
                graphics.drawString(this.font, name, listX + 16, rowY + 3, TEXT_COLOR, false);
                String status = declared.contains(value)
                        ? Component.translatable("gui.maid_file_manager.config.bauble_extra_declared").getString()
                        : Component.translatable("gui.maid_file_manager.config.bauble_extra_undeclared").getString();
                int color = declared.contains(value) ? OK_COLOR : WARN_COLOR;
                int statusW = this.font.width(status);
                graphics.drawString(this.font, status, listX + listW - statusW - 4, rowY + 3, color, false);
                String idText = value;
                int idW = this.font.width(idText);
                if (listX + listW - statusW - 12 - idW > listX + 16) {
                    graphics.drawString(this.font, idText,
                            listX + listW - statusW - 12 - idW, rowY + 3, SUBTEXT_COLOR, false);
                }
            } else {
                graphics.drawString(this.font, value, listX + 16, rowY + 3,
                        hardBlocked ? SUBTEXT_COLOR : TEXT_COLOR, false);
                if (hardBlocked || managed) {
                    String mark = Component.translatable(hardBlocked
                            ? "gui.maid_file_manager.config.bauble_extra_hard"
                            : "gui.maid_file_manager.config.bauble_extra_managed").getString();
                    graphics.drawString(this.font, mark,
                            listX + listW - this.font.width(mark) - 4, rowY + 3, SUBTEXT_COLOR, false);
                }
            }
        }

        String pageText = (shown.isEmpty() ? 0 : page + 1) + " / "
                + Math.max(1, (shown.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE)
                + "   （" + shown.size() + "）";
        graphics.drawString(this.font, pageText, listX + listW / 2 - this.font.width(pageText) / 2,
                panelY + PAGER_Y + 4, SUBTEXT_COLOR, false);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /**
     * 打断 Screen 默认背景绘制。
     *
     * <p>本屏在 {@code render()} 开头自绘全屏底色与面板，且 {@code super.render()} 在**最后**才调用；
     * 若不覆写本方法，{@code Screen.render} 首行会把默认背景（暗色 + 背景模糊）画在自绘内容**之上**，
     * 导致列表 / 勾选 / 文字全被糊掉，只剩按钮清晰。与同目录其它屏保持一致地空实现。
     */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 什么都不做 —— 全屏底色 + 面板统一在 render() 开头绘制
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
