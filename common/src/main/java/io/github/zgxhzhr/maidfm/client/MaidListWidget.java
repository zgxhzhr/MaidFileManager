package io.github.zgxhzhr.maidfm.client;

import io.github.zgxhzhr.maidfm.data.MaidInfo;
import io.github.zgxhzhr.maidfm.data.MaidProfileView;
import io.github.zgxhzhr.maidfm.network.IMaidFileNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/**
 * 女仆选择列表（导出 Tab / 统一导出模式共用）。
 *
 * <p>从 {@code MaidFileManagerScreen} 移出的独立 Widget。它原本是该界面的非静态内部类，
 * 靠隐式外部引用访问宿主的 4 个状态字段与 4 个方法。现在宿主需求收窄成
 * {@link MaidListView} 的 8 个成员——<b>这不是 MVVM</b>，宿主持有状态，Widget 只读或调它改。
 *
 * <p>行内几何由本类在渲染时记录、命中测试时回算，是「只渲染过的行才可点中」的载体。
 */
public class MaidListWidget extends ObjectSelectionList<MaidListWidget.ListRow> {
    private final MaidListView view;
    /** 父类的 minecraft 字段在本版本不一定可见，自己存一份。 */
    private final net.minecraft.client.Minecraft mc;
    /** 面板几何：包内可见，宿主算布局时要读。 */
    final int listX, listW, listTop, listBottom;

    /** 列表行基类：让女仆行 Entry 与玩家标题行 HeaderEntry 可共存于同一个列表 */
    abstract static class ListRow extends ObjectSelectionList.Entry<ListRow> {
        /** 清掉上一帧记录的命中区：只有本帧真正渲染过的行才允许被点中 */
        void resetHitArea() {
        }
    }

    public MaidListWidget(net.minecraft.client.Minecraft minecraft,
                          MaidListView view,
                          int listX, int listW, int listTop, int listBottom) {
        // 1.20.x 列表构造器为 6 参（mc, 宽, 高, top, bottom, 行高）
        super(minecraft, listW,
                listBottom - listTop, listTop, listBottom, MaidFileManagerScreen.ROW_HEIGHT);
        this.view = view;
        this.mc = minecraft;
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
        int index = (int) ((mouseY - this.listTop - 4 + this.getScrollAmount()) / MaidFileManagerScreen.ROW_HEIGHT);
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
        if (view.serverExportMode()) {
            // 统一导出浏览：玩家标题行（点击展开/收起）+ 展开玩家的女仆行
            for (IMaidFileNetwork.PlayerMaidGroup g : view.serverGroups()) {
                addEntry(new HeaderEntry(g));
                if (!view.collapsedPlayers().contains(g.playerName())) {
                    for (MaidInfo info : g.maids()) {
                        addEntry(new Entry(info));
                    }
                }
            }
            if (view.serverGroups().isEmpty()) {
                addEntry(new HeaderEntry(null));
            }
        } else {
            for (MaidInfo info : view.maidList()) {
                addEntry(new Entry(info));
            }
        }
    }

    // ====== v13 交互兜底：完全绕开父类 getEntryAtPosition 的 rowLeft 旧公式 ======
    // 第一层 Widget 范围命中：MaidFileManagerScreen.LIST_BG ± 12 像素（父类 scissor 放宽 12px）
    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        // 容错从 ±12 收窄到 ±2：原先可以把鼠标放到面板外一整条区域仍命中，容易点穿到相邻控件
        return mouseX >= this.listX - 2 && mouseX <= this.listX + this.listW + 2
                && mouseY >= this.listTop - 2 && mouseY <= this.listBottom + 2;
    }

    // 第二层 Entry 命中：自遍历 Entry.isMouseOver（我们 Entry 已经判 MaidFileManagerScreen.LIST_BG 矩形）
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
                this.listX + this.listW, this.listBottom, MaidFileManagerScreen.LIST_BG);
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
            int top = this.listTop + 4 - scroll + i * MaidFileManagerScreen.ROW_HEIGHT;
            if (top >= this.listBottom || top + MaidFileManagerScreen.ROW_HEIGHT <= this.listTop) {
                continue;
            }
            boolean hovering = mouseX >= this.listX && mouseX < this.listX + this.listW
                    && mouseY >= top && mouseY < top + MaidFileManagerScreen.ROW_HEIGHT;
            rows.get(i).render(graphics, i, top, this.listX + 2,
                    this.getRowWidth(), MaidFileManagerScreen.ROW_HEIGHT, mouseX, mouseY, hovering, partialTick);
        }
        graphics.disableScissor();
        // 滚动条：内容溢出时贴列表右缘画（位置基于我们自己的 listX/listW，不再依赖父类内部几何）
        int contentH = rows.size() * MaidFileManagerScreen.ROW_HEIGHT + 8;
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
                graphics.fill(rowX, top - 1, rowX + rowW, top + height + 1, MaidFileManagerScreen.ROW_HOVER);
            }
            net.minecraft.client.gui.Font font = mc.font;
            if (group == null) {
                graphics.drawString(font, Component.translatable("maid_file_manager.gui.server_export.empty"),
                        rowX + 28, top + 6, MaidFileManagerScreen.SUBTEXT_COLOR, false);
                return;
            }
            boolean collapsed = view.collapsedPlayers().contains(group.playerName());
            String arrow = collapsed ? "▶" : "▼";
            String consentTag = group.consented() ? "" : Component.translatable("maid_file_manager.gui.server_export.no_consent_tag").getString();
            String line = Component.translatable("maid_file_manager.gui.server_export.player_row",
                    arrow, group.playerName(), group.maids().size(), consentTag).getString();
            int color = group.consented() ? MaidFileManagerScreen.ACCENT : MaidFileManagerScreen.SUBTEXT_COLOR;
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
            if (!view.collapsedPlayers().remove(name)) {
                view.collapsedPlayers().add(name);
                // 折叠时把该组女仆从选中集移除：否则「全选」勾中的是展平全集（含不可见行），
                // 界面显示的勾选数比屏幕上能看到的行多很多，且 allSelected 判断永远算不出
                // 「已全选」，导出时那些看不见的条目仍会被提交。
                for (IMaidFileNetwork.PlayerMaidGroup g : view.serverGroups()) {
                    if (g.playerName().equals(name)) {
                        for (MaidInfo info : g.maids()) {
                            view.selectedMaidIds().remove(info.entityId());
                        }
                        break;
                    }
                }
                // 折叠/展开后按「可选集合」重算，而不是硬置 false（否则展开回来也永远不显示已全选）
                view.syncSelectAllState();
                view.syncSelectAllState();
            }
            // 展开后也要重算：展开意味着可选集合变大、新可见的女仆尚未选中，
            // 原先只在折叠分支重算，展开回来仍显示「已全选」（第六轮 M5）。
            view.syncSelectAllState();
            view.syncSelectAllState();
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
            boolean selected = view.selectedMaidIds().contains(info.entityId());
            int rowX = MaidListWidget.this.listX;
            int rowW = MaidListWidget.this.listW;
            if (hovering) {
                graphics.fill(rowX, top - 1, rowX + rowW, top + height + 1, MaidFileManagerScreen.ROW_HOVER);
            }
            // 勾选框 + 文字：rowX 对齐 MaidFileManagerScreen.LIST_BG 左边缘（手绘复选框，不用缺字字符）
            MaidScreenDraw.drawCheckBox(graphics, rowX + (rowW < 190 ? 4 : 28), top + 4, selected);
            int textLeft = rowX + (rowW < 190 ? 18 : 49);
            // 右侧为「档案」按钮预留空间，文字区主动避让，避免被按钮压住
            int contentRight = rowW < 190 ? rowX + rowW - 4 : rowX + rowW - 10 - MaidFileManagerScreen.PROFILE_BTN_W - 6;
            int contentW = contentRight - textLeft;
            net.minecraft.client.gui.Font font = mc.font;
            // 第一行：name 超长 → 截断+省略号…（不滚避免勾选框右晃动）
            // YSM 女仆的 modelId 是 YSM 模型路径（ysm:Wine Fox/02 New Year），
            // displayModelId 解析不了，只能用服务端给的 displayName（YSM 真名）。
            String baseName = info.ysmModel()
                    ? (info.displayName() != null ? info.displayName() : "")
                    : ModelNames.displayModelId(info.modelId());
            String name = info.customName() != null
                    ? info.customName() + " · " + baseName
                    : baseName;
            String drawName;
            if (font.width(name) > contentW) {
                drawName = font.plainSubstrByWidth(name, contentW - font.width("…")) + "…";
            } else {
                drawName = name;
            }
            graphics.drawString(font, Component.literal(drawName), textLeft, top + 2, MaidFileManagerScreen.TEXT_COLOR, false);
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
                graphics.drawString(font, Component.literal(detail), textLeft, top + 12, MaidFileManagerScreen.SUBTEXT_COLOR, false);
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
                graphics.drawString(font, Component.literal(detail), textLeft - offset, top + 12, MaidFileManagerScreen.SUBTEXT_COLOR, false);
                graphics.disableScissor();
            }
            drawProfileButton(graphics, rowX + rowW, top, height, mouseX, mouseY);
        }

        /** 绘制行内「档案」按钮（右边缘对齐 rowRight） */
        private void drawProfileButton(GuiGraphics graphics, int rowRight, int top, int height,
                                       int mouseX, int mouseY) {
            // 统一导出模式下列表含其他玩家的女仆，不提供「档案」入口（仅本人女仆可查看档案）
            if (view.serverExportMode()) {
                return;
            }
            // 窄屏（列表区放不下按钮+文字）时不画/不命中「档案」按钮
            if (listW < 190) {
                return;
            }
            int bx = rowRight - MaidFileManagerScreen.PROFILE_BTN_W - 4;
            int by = top + (height - MaidFileManagerScreen.PROFILE_BTN_H) / 2;
            boolean hover = mouseX >= bx && mouseX <= bx + MaidFileManagerScreen.PROFILE_BTN_W
                    && mouseY >= by && mouseY <= by + MaidFileManagerScreen.PROFILE_BTN_H;
            graphics.fill(bx, by, bx + MaidFileManagerScreen.PROFILE_BTN_W, by + MaidFileManagerScreen.PROFILE_BTN_H, hover ? 0xFF4A3826 : 0xFF3A2C1C);
            graphics.renderOutline(bx, by, MaidFileManagerScreen.PROFILE_BTN_W, MaidFileManagerScreen.PROFILE_BTN_H, MaidFileManagerScreen.PANEL_BORDER);
            Component label = Component.translatable("maid_file_manager.gui.button.profile");
            int tx = bx + (MaidFileManagerScreen.PROFILE_BTN_W - mc.font.width(label)) / 2;
            graphics.drawString(mc.font, label, tx,
                    by + (MaidFileManagerScreen.PROFILE_BTN_H - 8) / 2, MaidFileManagerScreen.TEXT_COLOR, false);
        }

        /** 是否命中行内「档案」按钮 */
        private boolean isProfileButtonHit(double mouseX, double mouseY) {
            if (view.serverExportMode() || lastTop < 0) {
                return false;
            }
            int rowRight = MaidListWidget.this.listX + MaidListWidget.this.listW;
            // 窄屏（列表区放不下按钮+文字）时不画/不命中「档案」按钮
            if (listW < 190) {
                return false;
            }
            int bx = rowRight - MaidFileManagerScreen.PROFILE_BTN_W - 4;
            int by = lastTop + (lastHeight - MaidFileManagerScreen.PROFILE_BTN_H) / 2;
            return mouseX >= bx && mouseX <= bx + MaidFileManagerScreen.PROFILE_BTN_W
                    && mouseY >= by && mouseY <= by + MaidFileManagerScreen.PROFILE_BTN_H;
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
                view.requestMaidProfile(info.entityId());
                return true;
            }
            boolean newVal = !view.selectedMaidIds().contains(info.entityId());
            view.toggleRowSelection(info, newVal);
            return true;
        }

        @Override
        public Component getNarration() {
            boolean sel = view.selectedMaidIds().contains(info.entityId());
            String name = info.customName() != null
                    ? info.customName() + " · " + ModelNames.displayModelId(info.modelId())
                    : ModelNames.displayModelId(info.modelId());
            return Component.translatable(sel
                    ? "maid_file_manager.gui.label.selected"
                    : "maid_file_manager.gui.label.not_selected").append(name);
        }
    }
}
