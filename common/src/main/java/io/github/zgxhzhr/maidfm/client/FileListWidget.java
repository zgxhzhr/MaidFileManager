package io.github.zgxhzhr.maidfm.client;

import io.github.zgxhzhr.maidfm.Constants;
import io.github.zgxhzhr.maidfm.data.MaidFileData;
import io.github.zgxhzhr.maidfm.data.MaidFileIo;
import io.github.zgxhzhr.maidfm.data.MaidInfo;
import io.github.zgxhzhr.maidfm.data.MaidProfile;
import io.github.zgxhzhr.maidfm.data.MaidProfileView;
import io.github.zgxhzhr.maidfm.network.IMaidFileNetwork;
import java.nio.file.Path;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.nio.file.Path;

/**
 * 导入 Tab 的文件列表。
 *
 * <p>从 MaidFileManagerScreen 移出的独立 Widget。宿主需求收窄成 {@link FileListView}
 * 的 5 个成员——这不是 MVVM，宿主持有状态，Widget 只读或调它改。
 */
public class FileListWidget extends ObjectSelectionList<FileListWidget.Entry> {
    private final FileListView host;
    private final net.minecraft.client.Minecraft mc;
    /** 面板几何：包内可见，宿主算布局时要读。 */
    final int listX, listW, listTop, listBottom;

    public FileListWidget(net.minecraft.client.Minecraft minecraft,
                          FileListView host,
                          int listX, int listW, int listTop, int listBottom) {
        super(minecraft, listW,
                listBottom - listTop, listTop, MaidFileManagerScreen.ROW_HEIGHT);
        this.host = host;
        this.mc = minecraft;
        this.listX = listX;
        this.listW = listW;
        this.listTop = listTop;
        this.listBottom = listBottom;
        // 几何走公开 setter（与 MaidListWidget 同一套唯一基准）
        this.setX(listX);
        this.setY(listTop);
        this.setWidth(listW);
        this.setHeight(listBottom - listTop);
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
        for (String fileName : host.fileList()) {
            addEntry(new Entry(fileName));
        }
    }

            private void renderListBackground(GuiGraphicsExtractor graphics) {
        graphics.fill(this.listX, this.listTop,
                this.listX + this.listW, this.listBottom, MaidFileManagerScreen.LIST_BG);
    }

    // ====== v13 交互兜底：同 MaidListWidget（绕开父类 getEntryAtPosition） ======
    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        // 容错从 ±12 收窄到 ±2：原先可以把鼠标放到面板外一整条区域仍命中，容易点穿到相邻控件
        return mouseX >= this.listX - 2 && mouseX <= this.listX + this.listW + 2
                && mouseY >= this.listTop - 2 && mouseY <= this.listBottom + 2;
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
    double mouseX = event.x();
    double mouseY = event.y();
    int button = event.button();
        if (!this.isMouseOver(mouseX, mouseY)) return false;
        for (Entry entry : this.children()) {
            if (entry.isMouseOver(mouseX, mouseY)) {
                if (entry.mouseClicked(event, doubleClick)) {
                    this.setSelected(entry);
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
    double mouseX = event.x();
    double mouseY = event.y();
    int button = event.button();
        for (Entry entry : this.children()) {
            if (entry.isMouseOver(mouseX, mouseY)) {
                entry.mouseReleased(event);
            }
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (!this.isMouseOver(mouseX, mouseY)) return false;
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    Entry getHoveredRow(double mouseX, double mouseY) {
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
        int index = (int) ((mouseY - this.listTop - 4 + this.scrollAmount()) / MaidFileManagerScreen.ROW_HEIGHT);
        java.util.List<Entry> rows = this.children();
        if (index < 0 || index >= rows.size()) {
            return null;
        }
        return rows.get(index);
    }

    /** v14 FabricFix：与 MaidListWidget 相同的自绘渲染（Fabric intermediary 环境下父类几何反射失效） */
    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (!this.visible) {
            return;
        }
        this.renderListBackground(graphics);
        java.util.List<Entry> rows = this.children();
        // 导入列表同样每帧清命中区（第五轮 P1-5 只修了导出列表 → 第六轮 P1-2）：
        // 不清的话滚出视口的行保留旧 lastTop，点某文件会勾中看不见的另一个文件，
        // 配合「导入后删除源文件」会删错文件。
        for (Entry row : rows) {
            row.resetHitArea();
        }
        int viewH = this.listBottom - this.listTop;
        int scroll = (int) this.scrollAmount();
        graphics.enableScissor(this.listX, this.listTop, this.listX + this.listW, this.listBottom);
        for (int i = 0; i < rows.size(); i++) {
            int top = this.listTop + 4 - scroll + i * MaidFileManagerScreen.ROW_HEIGHT;
            if (top >= this.listBottom || top + MaidFileManagerScreen.ROW_HEIGHT <= this.listTop) {
                continue;
            }
            boolean hovering = mouseX >= this.listX && mouseX < this.listX + this.listW
                    && mouseY >= top && mouseY < top + MaidFileManagerScreen.ROW_HEIGHT;
            var r = rows.get(i);
            r.setX(this.listX + 2);
            r.setY(top);
            r.setWidth(this.getRowWidth());
            r.setHeight(MaidFileManagerScreen.ROW_HEIGHT);
            r.extractContent(graphics, mouseX, mouseY, hovering, partialTick);
        }
        graphics.disableScissor();
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
        super.extractWidgetRenderState(graphics, mouseX, mouseY, partialTick);
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
        public void extractContent(final GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovering, float a) {
            // 26.1：Entry 的抽象方法是 extractContent(graphics, mouseX, mouseY, hovered, a)，
            // 行矩形改为通过 LayoutElement 的 getX/getY/getWidth/getHeight 获取。
            int top = getY();
            int left = getX();
            int width = getWidth();
            // 26.1：Entry 的 getHeight() 未必等于我们 setHeight(MaidFileManagerScreen.ROW_HEIGHT) 的值， 
            // 绘制一律用固定行高，避免勾选框/档案按钮各按不同基准算 y 而错位。
            int height = MaidFileManagerScreen.ROW_HEIGHT;
            this.lastTop = top;
            this.lastHeight = height;
            boolean selected = host.selectedImportFileNames().contains(fileName);
            int rowX = FileListWidget.this.listX;
            int rowW = FileListWidget.this.listW - 5; // 右侧 5px 让给自绘滚动条，与 rowRight 一致
            if (hovering) {
                graphics.fill(rowX, top - 1, rowX + rowW, top + height + 1, MaidFileManagerScreen.ROW_HOVER);
            }
            // 勾选框：rowX 对齐 MaidFileManagerScreen.LIST_BG 左边缘（框体手绘，避免 ☐/☑ 豆腐块）
            MaidScreenDraw.drawCheckBox(graphics, rowX + (rowW < 190 ? 4 : 28), top + (height - MaidScreenDraw.CHECK_SIZE) / 2, selected);
            int textLeft = rowX + (rowW < 190 ? 18 : 49);
            // 右侧为「档案」按钮预留空间，文字区主动避让，避免被按钮压住
            int contentRight = rowW < 190 ? rowX + rowW - 4 : rowX + rowW - 10 - MaidFileManagerScreen.PROFILE_BTN_W - 6;
            int contentW = contentRight - textLeft;
            net.minecraft.client.gui.Font font = mc.font;
            // 第一行：fileName 超长 → 左右跑马灯（文件名很长必须全显示）
            int nameW = font.width(fileName);
            if (nameW <= contentW) {
                graphics.text(font, Component.literal(fileName), textLeft, top + 2, MaidFileManagerScreen.TEXT_COLOR, false);
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
                graphics.text(font, Component.literal(fileName), textLeft - offset, top + 2, MaidFileManagerScreen.TEXT_COLOR, false);
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
            graphics.text(font, Component.literal(drawTime), textLeft, top + 12, MaidFileManagerScreen.SUBTEXT_COLOR, false);
            drawProfileButton(graphics, rowX + rowW, top, height, mouseX, mouseY);
        }

        /** 绘制行内「档案」按钮（导入列表用文件内档案快照预览） */
        private void drawProfileButton(GuiGraphicsExtractor graphics, int rowRight, int top, int height,
                                       int mouseX, int mouseY) {
            // 窄屏（列表区放不下按钮+文字）时不画/不命中「档案」按钮
            if (listW < 190) {
                return;
            }
            int bx = rowRight - MaidFileManagerScreen.PROFILE_BTN_W - 4;
            int by = top + (height - MaidFileManagerScreen.PROFILE_BTN_H) / 2;
            boolean hover = mouseX >= bx && mouseX <= bx + MaidFileManagerScreen.PROFILE_BTN_W
                    && mouseY >= by && mouseY <= by + MaidFileManagerScreen.PROFILE_BTN_H;
            graphics.fill(bx, by, bx + MaidFileManagerScreen.PROFILE_BTN_W, by + MaidFileManagerScreen.PROFILE_BTN_H, hover ? 0xFF4A3826 : 0xFF3A2C1C);
            graphics.outline(bx, by, MaidFileManagerScreen.PROFILE_BTN_W, MaidFileManagerScreen.PROFILE_BTN_H, MaidFileManagerScreen.PANEL_BORDER);
            Component label = Component.translatable("maid_file_manager.gui.button.profile");
            int tx = bx + (MaidFileManagerScreen.PROFILE_BTN_W - mc.font.width(label)) / 2;
            graphics.text(mc.font, label, tx,
                    by + (MaidFileManagerScreen.PROFILE_BTN_H - 8) / 2, MaidFileManagerScreen.TEXT_COLOR, false);
        }

        /** 是否命中行内「档案」按钮 */
        private boolean isProfileButtonHit(double mouseX, double mouseY) {
            if (lastTop < 0) {
                return false;
            }
            int rowRight = FileListWidget.this.listX + FileListWidget.this.listW - 5; // 与行背景/滚动条让位一致
            // 窄屏（列表区放不下按钮+文字）时不画/不命中「档案」按钮
            if (listW < 190) {
                return false;
            }
            int bx = rowRight - MaidFileManagerScreen.PROFILE_BTN_W - 4;
            int by = lastTop + (lastHeight - MaidFileManagerScreen.PROFILE_BTN_H) / 2;
            return mouseX >= bx && mouseX <= bx + MaidFileManagerScreen.PROFILE_BTN_W
                    && mouseY >= by && mouseY <= by + MaidFileManagerScreen.PROFILE_BTN_H;
        }

        /** 打开该导入文件内的档案快照（只读预览；文件无档案数据时提示） */
        /** 打开该导入文件内的档案快照（只读预览；文件无档案数据时提示） */
        private void openOfflineProfile() {
            // readMaidFile 要解 GZIP + 解析 NBT，必须离开渲染线程（第六轮 M8）。
            final Path snapGameDir = mc != null
                    ? mc.gameDirectory.toPath().toAbsolutePath() : null;
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
                if (mc == null) {
                    return;
                }
                final MaidProfileView outView = view;
                final boolean outNoProfile = noProfile;
                mc.execute(() -> {
                    if (outNoProfile) {
                        host.setFeedback(
                                Component.translatable("maid_file_manager.profile.not_in_file"));
                        return;
                    }
                    if (outView == null || mc == null) {
                        return;
                    }
                    mc.setScreen(
                            new MaidProfileScreen(host.hostScreen(), outView));
                });
            }, "maidfm-snapshot-read");
            snapWorker.setDaemon(true);
            snapWorker.start();
        }

        @Override
        public boolean isMouseOver(double mouseX, double mouseY) {
            int rowX = FileListWidget.this.listX;
            int rowW = FileListWidget.this.listW - 5; // 右侧 5px 让给自绘滚动条，与 rowRight 一致
            return mouseX >= rowX && mouseX <= rowX + rowW - 5
                    && this.lastTop >= 0
                    && mouseY >= this.lastTop - 2
                    && mouseY <= this.lastTop + this.lastHeight + 2;
        }

        @Override
        public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
    double mouseX = event.x();
    double mouseY = event.y();
    int button = event.button();
            // 命中「档案」按钮 → 打开该文件内的档案快照（只读），不再触发勾选
            if (button == 0 && isProfileButtonHit(mouseX, mouseY)) {
                openOfflineProfile();
                return true;
            }
            boolean newVal = !host.selectedImportFileNames().contains(fileName);
            host.toggleFileRowSelection(fileName, newVal);
            return true;
        }

        @Override
        public Component getNarration() {
            boolean sel = host.selectedImportFileNames().contains(fileName);
            return Component.translatable(sel
                    ? "maid_file_manager.gui.label.selected"
                    : "maid_file_manager.gui.label.not_selected").append(fileName);
        }
    }
}
