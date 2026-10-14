package io.github.zgxhzhr.maidfm.client;

import io.github.zgxhzhr.maidfm.config.MaidConfigManager;
import io.github.zgxhzhr.maidfm.network.IMaidFileNetwork;
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
 * 饰品禁用设置（按模组 / 按 Curios 槽位）次级界面。
 *
 * <p>从「饰品导入设置」进入。与"按物品逐条勾选"互补，这里提供两个**批量维度**（均为服务端配置，仅 OP 可改）：
 * <ul>
 *   <li><b>按模组（命名空间）</b>：一键禁掉某个模组的所有饰品 —— 同时作用于女仆自带饰品栏与 Curios 槽位；</li>
 *   <li><b>按 Curios 槽位</b>：禁掉某个槽位里的饰品。硬禁槽位（背包类 / 已核实的数据容器）
 *       由本模组永久禁止，此处只读展示、不可取消。</li>
 * </ul>
 *
 * <p>两个清单分别写入服务端 {@code bauble_blocked_namespaces} 与
 * {@code bauble_blocked_curios_slots}，服务端按键分类收窄校验后广播同步。
 *
 * <p>翻页用按钮而非滚轮：1.20 与 1.21 的 {@code mouseScrolled} 签名不同，同一份源码无法两代通用。
 */
public class MaidBaubleExtraBlockScreen extends Screen {

    private static final int SCREEN_BG = 0xFF101010;
    private static final int PANEL_BG = 0xFF282018;
    private static final int PANEL_BORDER = 0xFF8B5A2B;
    private static final int HEADER_COLOR = 0xFFFFE082;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int SUBTEXT_COLOR = 0xFFB0B0B0;
    private static final int LIST_BG = 0xFF181008;
    private static final int ROW_CHECKED = 0xFF2E3820;
    private static final int ROW_READONLY = 0xFF2A1A1A;
    private static final int CHECK_ON = 0xFF5A8A3A;
    private static final int CHECK_OFF = 0xFF4A4038;

    private static final int ROW_H = 12;
    private static final int ROWS_PER_PAGE = 10;

    /** 硬禁槽位精确名单（与 MaidBaubleCarrierRegistry 保持一致；只读展示） */
    private static final List<String> HARD_BLOCKED_SLOTS = List.of(
            "back", "curio_back", "backpack", "backpacks", "ionocraft_backpack", "arrow_bag",
            "klein_star", "deep_learner", "soul", "soul_stone", "soultotem");

    /** 硬禁槽位词根（只读展示，说明"名字里带这些词的槽位也会被禁止"） */
    private static final List<String> HARD_BLOCKED_WORDS = List.of(
            "back", "backpack", "pack", "bag", "pouch", "satchel", "chest", "container",
            "storage", "inventory", "quiver", "pocket");

    private final Screen parent;
    private boolean canEdit;

    private final List<String> modCandidates = new ArrayList<>();
    private final Map<String, String> modDisplay = new HashMap<>();
    private final Set<String> blockedNamespaces = new LinkedHashSet<>();
    private final Set<String> blockedSlots = new LinkedHashSet<>();
    private final List<String> extraSlotCandidates = new ArrayList<>();

    private EditBox slotInput;
    private int modPage;
    private int slotPage;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int leftX;
    private int rightX;
    private int columnW;
    private int listTop;
    private int listH;

    public MaidBaubleExtraBlockScreen(Screen parent) {
        super(Component.translatable("gui.maid_file_manager.config.bauble_extra_block"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.canEdit = this.minecraft != null && this.minecraft.player != null
                && this.minecraft.player.hasPermissions(2);

        // 已选：来自服务端同步缓存（只读展示 + 可取消勾选后提交）
        blockedNamespaces.clear();
        blockedNamespaces.addAll(MaidConfigManager.cachedBaubleBlockedNamespaces());
        blockedSlots.clear();
        blockedSlots.addAll(MaidConfigManager.cachedBaubleBlockedCuriosSlots());

        collectModCandidates();
        extraSlotCandidates.clear();
        extraSlotCandidates.addAll(blockedSlots);
        for (String hard : HARD_BLOCKED_SLOTS) {
            if (!extraSlotCandidates.contains(hard)) {
                extraSlotCandidates.add(hard);
            }
        }

        panelW = Math.min(420, this.width - 40);
        panelH = Math.min(240, this.height - 40);
        panelX = (this.width - panelW) / 2;
        panelY = Math.max(10, (this.height - panelH) / 2);
        int gap = 12;
        columnW = (panelW - 40 - gap) / 2;
        leftX = panelX + 20;
        rightX = leftX + columnW + gap;
        listTop = panelY + 40;
        listH = ROWS_PER_PAGE * ROW_H;

        // 槽位输入框 + 添加（硬禁之外的槽位可手动添加）
        slotInput = new EditBox(this.font, rightX, listTop + listH + 4, columnW - 56, 14,
                Component.translatable("gui.maid_file_manager.config.bauble_extra_slot_hint"));
        slotInput.setMaxLength(64);
        slotInput.setTextColor(TEXT_COLOR);
        addRenderableWidget(slotInput);
        addRenderableWidget(Button.builder(Component.translatable("gui.maid_file_manager.config.bauble_extra_add"),
                        b -> addSlotFromInput())
                .bounds(rightX + columnW - 52, listTop + listH + 4, 52, 14).build());

        int navY = panelY + panelH - 22;
        addRenderableWidget(Button.builder(Component.literal("<"), b -> {
            if (modPage > 0) {
                modPage--;
            }
        }).bounds(leftX, navY, 18, 16).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> {
            if ((modPage + 1) * ROWS_PER_PAGE < modCandidates.size()) {
                modPage++;
            }
        }).bounds(leftX + columnW - 18, navY, 18, 16).build());
        addRenderableWidget(Button.builder(Component.literal("<"), b -> {
            if (slotPage > 0) {
                slotPage--;
            }
        }).bounds(rightX, navY, 18, 16).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> {
            if ((slotPage + 1) * ROWS_PER_PAGE < extraSlotCandidates.size()) {
                slotPage++;
            }
        }).bounds(rightX + columnW - 18, navY, 18, 16).build());

        int doneW = 120;
        addRenderableWidget(Button.builder(Component.translatable("gui.maid_file_manager.config.done"),
                        b -> onSaveAndClose())
                .bounds(panelX + (panelW - doneW) / 2, panelY + panelH - 22, doneW, 16).build());
    }

    /** 候选模组：从已知饰品清单里推导命名空间，并补上已禁用的命名空间 */
    private void collectModCandidates() {
        modCandidates.clear();
        modDisplay.clear();
        Set<String> namespaces = new LinkedHashSet<>();
        for (String id : MaidConfigManager.cachedBaubleBlockedList()) {
            addNamespaceOf(namespaces, id);
        }
        for (String id : MaidConfigManager.cachedBaubleWhitelist()) {
            addNamespaceOf(namespaces, id);
        }
        for (String id : MaidConfigManager.cachedBaubleBlacklist()) {
            addNamespaceOf(namespaces, id);
        }
        namespaces.addAll(blockedNamespaces);
        modCandidates.addAll(namespaces);
        modCandidates.sort(String::compareTo);
        for (String ns : modCandidates) {
            modDisplay.put(ns, io.github.zgxhzhr.maidfm.platform.Services.PLATFORM.get().getModDisplayName(ns));
        }
    }

    private static void addNamespaceOf(Set<String> out, String itemId) {
        if (itemId == null) {
            return;
        }
        int colon = itemId.indexOf(':');
        if (colon > 0) {
            out.add(itemId.substring(0, colon));
        }
    }

    private void addSlotFromInput() {
        if (slotInput == null) {
            return;
        }
        String text = slotInput.getValue() == null ? "" : slotInput.getValue().trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return;
        }
        if (!blockedSlots.contains(text)) {
            blockedSlots.add(text);
            if (!extraSlotCandidates.contains(text)) {
                extraSlotCandidates.add(text);
            }
        }
        slotInput.setValue("");
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
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int modIndex = rowIndexAt(mouseX, mouseY, leftX, listTop, modPage, modCandidates.size());
            if (modIndex >= 0) {
                toggle(blockedNamespaces, modCandidates.get(modIndex));
                return true;
            }
            int slotIndex = rowIndexAt(mouseX, mouseY, rightX, listTop, slotPage, extraSlotCandidates.size());
            if (slotIndex >= 0) {
                String slot = extraSlotCandidates.get(slotIndex);
                if (HARD_BLOCKED_SLOTS.contains(slot)) {
                    return true;
                }
                toggle(blockedSlots, slot);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private static void toggle(Set<String> set, String value) {
        if (!set.remove(value)) {
            set.add(value);
        }
    }

    /** 命中测试：返回该坐标对应的行下标（不在列表内返回 -1） */
    private int rowIndexAt(double mouseX, double mouseY, int x, int top, int page, int total) {
        if (mouseX < x || mouseX > x + columnW || mouseY < top || mouseY > top + listH) {
            return -1;
        }
        int row = (int) ((mouseY - top) / ROW_H);
        int index = page * ROWS_PER_PAGE + row;
        return index >= 0 && index < total ? index : -1;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, SCREEN_BG);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, PANEL_BG);
        graphics.renderOutline(panelX, panelY, panelW, panelH, PANEL_BORDER);

        graphics.drawString(this.font, this.title, panelX + 12, panelY + 10, HEADER_COLOR, false);
        graphics.drawString(this.font,
                Component.translatable("gui.maid_file_manager.config.bauble_extra_hint"),
                panelX + 12, panelY + 24, SUBTEXT_COLOR, false);

        drawColumn(graphics, leftX, "gui.maid_file_manager.config.bauble_extra_by_mod",
                modCandidates, modPage, blockedNamespaces, true);
        drawColumn(graphics, rightX, "gui.maid_file_manager.config.bauble_extra_by_slot",
                extraSlotCandidates, slotPage, blockedSlots, false);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawColumn(GuiGraphics graphics, int x, String titleKey,
                            List<String> values, int page, Set<String> selected, boolean isModColumn) {
        graphics.drawString(this.font, Component.translatable(titleKey), x, listTop - 14, HEADER_COLOR, false);
        graphics.fill(x - 1, listTop - 1, x + columnW + 1, listTop + listH + 1, LIST_BG);

        int start = page * ROWS_PER_PAGE;
        for (int i = 0; i < ROWS_PER_PAGE; i++) {
            int index = start + i;
            if (index >= values.size()) {
                break;
            }
            String value = values.get(index);
            int rowY = listTop + i * ROW_H;
            boolean hardBlocked = !isModColumn && HARD_BLOCKED_SLOTS.contains(value);
            boolean checked = hardBlocked || selected.contains(value);
            if (checked) {
                graphics.fill(x, rowY, x + columnW, rowY + ROW_H - 1, hardBlocked ? ROW_READONLY : ROW_CHECKED);
            }
            // 复选框
            int boxY = rowY + 2;
            graphics.fill(x + 2, boxY, x + 10, boxY + 8, CHECK_OFF);
            if (checked) {
                graphics.fill(x + 3, boxY + 1, x + 9, boxY + 7, CHECK_ON);
            }
            String label = isModColumn
                    ? (value + "  " + modDisplay.getOrDefault(value, value))
                    : value;
            int color = hardBlocked ? SUBTEXT_COLOR : TEXT_COLOR;
            graphics.drawString(this.font, label, x + 14, rowY + 2, color, false);
            if (hardBlocked) {
                graphics.drawString(this.font,
                        Component.translatable("gui.maid_file_manager.config.bauble_extra_hard"),
                        x + columnW - 34, rowY + 2, SUBTEXT_COLOR, false);
            }
        }
        int total = values.size();
        String pageInfo = (total == 0 ? 0 : (page + 1)) + "/" + Math.max(1, (total + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
        graphics.drawString(this.font, pageInfo, x + columnW / 2 - 8, panelY + panelH - 18, SUBTEXT_COLOR, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
