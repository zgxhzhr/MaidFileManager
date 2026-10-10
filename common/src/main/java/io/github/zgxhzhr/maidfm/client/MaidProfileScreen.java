package io.github.zgxhzhr.maidfm.client;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.zgxhzhr.maidfm.Constants;
import io.github.zgxhzhr.maidfm.data.MaidFileData;
import io.github.zgxhzhr.maidfm.data.MaidProfile;
import io.github.zgxhzhr.maidfm.data.MaidProfileView;
import io.github.zgxhzhr.maidfm.network.IMaidFileNetwork;
import io.github.zgxhzhr.maidfm.platform.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.Util;
import net.minecraft.world.item.ItemStack;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 女仆档案界面。
 *
 * <p>版式遵循「网格对齐 + 分组」原则：
 * 左侧为 1:1 档案照片（玩家从 {@code maid_file/photos/} 挑选，带相框装饰角）；
 * 右侧为档案信息区，自上而下分三组，用细分隔线区隔——
 * <ul>
 *   <li>档案编号（只读，可横向查看与复制）</li>
 *   <li>基本资料：所属势力 / 模型、基础数值（血量 / 攻击 / 好感度，浅色底框）</li>
 *   <li>个人档案：职业与生日并排、个人资料与背景故事为多行文本域、偏好与特长单行、随身饰品图标</li>
 * </ul>
 *
 * <p>背景故事即 TLM 的 AI 人设（{@code MaidAIChat.CustomSetting}）：读取时取当前值，保存时同步写回，
 * 在 TLM 侧修改后再次打开本界面即可看到最新内容，实现双向同步。
 *
 * <p>当本界面由导入列表打开时（{@code entityId < 0}），展示的是 {@code .maid} 文件内的档案快照，
 * 全部为只读，且不提供「保存」。文字一律禁用阴影、本界面为非暂停界面。
 */
public class MaidProfileScreen extends Screen implements IMaidFileNetwork.ClientHandler {
    // ===== 配色（柔和暗金 / 古铜，降低饱和度，避免刺眼） =====
    private static final int SCREEN_BG = 0xFF0E0E12;
    private static final int PANEL_BG = 0xFF2A2620;
    private static final int PANEL_BORDER = 0xFF6E5A3A;
    private static final int HEADER_COLOR = 0xFFE6C989;
    private static final int TEXT_COLOR = 0xFFF2EDE4;
    private static final int SUBTEXT_COLOR = 0xFF9C9385;
    private static final int ACCENT = 0xFFC9A96A;
    private static final int DIVIDER = 0xFF463B2C;
    private static final int GROUP_BG = 0xFF3A3226;
    private static final int BUTTON_BG = 0xFF3A2C1C;
    private static final int BUTTON_BG_HOVER = 0xFF4A3826;
    private static final int BUTTON_DISABLED = 0xFF2A2018;
    private static final int SAVE_BG = 0xFF7A5E2E;
    private static final int SAVE_BG_HOVER = 0xFF8E6E38;
    private static final int ERROR_COLOR = 0xFFFF8080;

    // ===== 尺寸 =====
    private static final int PANEL_MAX_W = 470;
    private static final int PANEL_MIN_W = 340;
    private static final int HEADER_H = 24;
    private static final int BOTTOM_H = 34;
    private static final int ROW_GAP = 4;
    private static final int FIELD_H = 16;
    private static final int LINE_H = 11;
    private static final int STATS_H = 20;
    private static final int DIVIDER_GAP = 11;
    private static final int LABEL_W = 60;
    private static final int LABEL_SMALL_W = 26;
    private static final int BAUBLE_ICON = 16;
    private static final int BAUBLE_GAP = 2;
    private static final int PHOTO_DISPLAY = 96;
    private static final int PHOTO_FRAME_PAD = 3;
    private static final int PHOTO_BTN_H = 16;
    private static final int PICKER_ROW_H = 14;
    private static final int PICKER_SCROLL_W = 3;
    private static final int PICKER_BTN_H = 18;

    private final Screen parent;
    private final MaidProfileView view;
    /** 打开本界面前活跃的回调（通常是主管理界面），关闭时归还，避免回调指针悬空 */
    private final IMaidFileNetwork.ClientHandler prevHandler;

    /** 可编辑档案副本（界面内修改，点保存才提交） */
    private final MaidProfile profile;
    /** 该档案是否来自真实实体（false = 导入文件预览，全部只读） */
    private final boolean liveEntity;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;

    private int photoX;
    private int photoY;
    private int colX;
    private int colW;

    // 右列各行 Y 坐标（init 时由 assignLayout 统一计算，render 复用）
    private int idY;
    private int factionY;
    private int statsY;
    private int dividerBasicY;
    private int occY;
    private int noteY;
    private int prefY;
    private int storyY;
    private int dividerPersonalY;
    private int baubleY;

    /** 多行文本域高度（随字体行高自适应，窗口过矮时收缩） */
    private int noteH;
    private int storyH;

    private MultiLineEditBox uuidField;
    private MultiLineEditBox occupationField;
    private MultiLineEditBox birthdayField;
    private MultiLineEditBox noteField;
    private MultiLineEditBox prefField;
    private MultiLineEditBox storyField;

    private Component feedback = Component.empty();
    private int feedbackColor = SUBTEXT_COLOR;

    private ResourceLocation photoRl;
    private DynamicTexture photoTexture;

    /** 照片选择覆盖层 */
    private boolean pickerOpen;
    private int pickerX;
    private int pickerY;
    private int pickerW;
    private int pickerH;
    private int pickerScroll;
    private final List<String> photoFiles = new ArrayList<>();
    /** 覆盖层内「打开图片文件夹」按钮矩形 */
    private int pickerOpenBtnX;
    private int pickerOpenBtnY;
    private int pickerOpenBtnW;
    /** 覆盖层内「取消」按钮矩形 */
    private int pickerCancelX;
    private int pickerCancelY;
    private int pickerCancelW;

    /** 已解析的饰品图标（客户端渲染用） */
    private final List<ItemStack> baubleIcons = new ArrayList<>();

    public MaidProfileScreen(Screen parent, MaidProfileView view) {
        super(Component.translatable("gui.maid_file_manager.profile.title"));
        this.parent = parent;
        this.view = view;
        this.prevHandler = IMaidFileNetwork.ClientHandlerHolder.get();
        this.liveEntity = view != null && view.entityId() >= 0;
        this.profile = (view != null && view.profile() != null) ? view.profile() : new MaidProfile();
    }

    /**
     * 由导入列表的 {@code .maid} 文件数据构造只读预览视图。
     *
     * <p>好感度与饰品图标不在文件顶层字段中，而是从文件内的实体 NBT 现场提取：
     * 好感度读 {@code MaidFavorability}（旧版键 {@code MaidFavorabilityManagerCounter}），
     * 饰品栏读 {@code MaidBaubleInventory.Items}，去掉槽位键后交给客户端按当前版本反序列化渲染。
     */
    public static MaidProfileView buildOfflineView(MaidFileData data) {
        return new MaidProfileView(
                -1,
                data.getSourceMaidUuid(),
                data.getOwnerName(),
                data.getModelId(),
                data.getDisplayName(),
                data.getCustomName(),
                0f, 0f, 0f,
                readOfflineFavorability(data.getData()),
                isOfflineStruckByLightning(data.getData()),
                data.getProfile(),
                extractOfflineBaubles(data.getData()));
    }

    /** 从 .maid 内实体 NBT 读取好感度（双键名兼容，与导入端一致）；缺失返回 0 */
    private static int readOfflineFavorability(CompoundTag entity) {
        if (entity == null) {
            return 0;
        }
        if (entity.contains("MaidFavorability", Tag.TAG_INT)) {
            return entity.getInt("MaidFavorability");
        }
        if (entity.contains("MaidFavorabilityManagerCounter", Tag.TAG_INT)) {
            return entity.getInt("MaidFavorabilityManagerCounter");
        }
        return 0;
    }

    /** 从 .maid 内实体 NBT 读取渡劫标记（TLM 雷击标记键 {@code StruckByLightning}）；缺失返回 false */
    private static boolean isOfflineStruckByLightning(CompoundTag entity) {
        return entity != null && entity.getBoolean("StruckByLightning");
    }

    /** 从 .maid 内实体 NBT 提取饰品栏物品 NBT（移除槽位键，供客户端反序列化渲染） */
    private static List<CompoundTag> extractOfflineBaubles(CompoundTag entity) {
        List<CompoundTag> result = new ArrayList<>();
        if (entity == null || !entity.contains("MaidBaubleInventory", Tag.TAG_COMPOUND)) {
            return result;
        }
        ListTag items = entity.getCompound("MaidBaubleInventory").getList("Items", Tag.TAG_COMPOUND);
        for (int i = 0; i < items.size(); i++) {
            CompoundTag entry = items.getCompound(i).copy();
            entry.remove("Slot");
            if (!entry.isEmpty()) {
                result.add(entry);
            }
        }
        return result;
    }

    @Override
    protected void init() {
        super.init();
        IMaidFileNetwork.ClientHandlerHolder.set(this);

        panelW = Math.max(PANEL_MIN_W, Math.min(PANEL_MAX_W, this.width - 16));
        panelX = (this.width - panelW) / 2;

        // 多行文本域高度随字体行高自适应：个人资料 2 行、背景故事 3 行
        int lh = this.font.lineHeight + 1;
        noteH = 2 * lh + 12;
        storyH = 3 * lh + 12;

        // 窗口过矮时依次把背景故事、个人资料压到 1 行，避免底部按钮被挤出屏幕
        while (HEADER_H + measureContentH() + BOTTOM_H > this.height - 8 && storyH > lh + 12) {
            storyH = Math.max(lh + 12, storyH - lh);
        }
        while (HEADER_H + measureContentH() + BOTTOM_H > this.height - 8 && noteH > lh + 12) {
            noteH = Math.max(lh + 12, noteH - lh);
        }
        int contentH = measureContentH();
        panelH = HEADER_H + contentH + BOTTOM_H;
        panelY = Math.max(4, (this.height - panelH) / 2);

        int contentTop = panelY + HEADER_H;
        int leftColW = 14 + PHOTO_DISPLAY + PHOTO_FRAME_PAD * 2 + 14;
        photoX = panelX + 14 + PHOTO_FRAME_PAD;
        photoY = contentTop + PHOTO_FRAME_PAD + 2;
        colX = panelX + leftColW;
        colW = panelX + panelW - 14 - colX;
        assignLayout(contentTop);

        boolean editable = liveEntity;
        int singleW = colW - LABEL_W - 4;

        // 档案编号（只读，单行）
        uuidField = addField(editable, true, idY, colX + LABEL_W + 4, singleW, FIELD_H,
                "gui.maid_file_manager.profile.id", view != null && view.maidUuid() != null ? view.maidUuid() : "",
                false, SUBTEXT_COLOR, null);

        // 职业 / 生日 并排两列
        int halfW = (colW - 8) / 2;
        int boxW = halfW - LABEL_SMALL_W;
        occupationField = addField(editable, true, occY, colX + LABEL_SMALL_W, boxW, FIELD_H,
                "gui.maid_file_manager.profile.occupation", profile.getOccupation(), true, TEXT_COLOR,
                "gui.maid_file_manager.profile.default_occupation");
        birthdayField = addField(editable, true, occY, colX + halfW + 8 + LABEL_SMALL_W, boxW, FIELD_H,
                "gui.maid_file_manager.profile.birthday", profile.getBirthday(), true, TEXT_COLOR, null);

        // 个人资料 / 背景故事：多行文本域
        noteField = addField(editable, false, noteY, colX + LABEL_W + 4, singleW, noteH,
                "gui.maid_file_manager.profile.note", profile.getPersonalNote(), true, TEXT_COLOR, null);
        prefField = addField(editable, true, prefY, colX + LABEL_W + 4, singleW, FIELD_H,
                "gui.maid_file_manager.profile.preferences", profile.getPreferences(), true, TEXT_COLOR, null);
        storyField = addField(editable, false, storyY, colX + LABEL_W + 4, singleW, storyH,
                "gui.maid_file_manager.profile.story", profile.getStorySnapshot(), true, TEXT_COLOR, null);

        // 照片按钮（头像正下方，两个半宽小按钮）
        int frameW = PHOTO_DISPLAY + PHOTO_FRAME_PAD * 2;
        int btnW = (frameW - 4) / 2;
        int btnY = photoY + PHOTO_DISPLAY + PHOTO_FRAME_PAD + 6;
        addRenderableWidget(new WoodButton(photoX - PHOTO_FRAME_PAD, btnY, btnW, PHOTO_BTN_H,
                Component.translatable("gui.maid_file_manager.profile.choose_photo"),
                this::openPicker)).active = editable;
        addRenderableWidget(new WoodButton(photoX - PHOTO_FRAME_PAD + btnW + 4, btnY, btnW, PHOTO_BTN_H,
                Component.translatable("gui.maid_file_manager.profile.clear_photo"),
                this::clearPhoto)).active = editable;

        // 底部按钮：保存（实心暗金，主操作）在左，返回（空心描边，次操作）在右
        int bottomBtnY = panelY + panelH - 26;
        int backW = 76;
        int saveW = 92;
        int backX = panelX + panelW - 14 - backW;
        addRenderableWidget(new WoodButton(backX, bottomBtnY, backW, FIELD_H + 4,
                Component.translatable("gui.maid_file_manager.profile.back"), this::onClose, false));
        if (editable) {
            addRenderableWidget(new WoodButton(backX - 8 - saveW, bottomBtnY, saveW, FIELD_H + 4,
                    Component.translatable("gui.maid_file_manager.profile.save"), this::onSave, true));
        } else {
            setFeedback(Component.translatable("gui.maid_file_manager.profile.preview_only"), SUBTEXT_COLOR);
        }

        parseBaubleIcons();
        refreshPhotoTexture();
    }

    /** 试算内容区高度（originY=0），用于定高与窗口过矮时的收缩判断 */
    private int measureContentH() {
        assignLayout(0);
        int rightH = baubleY + BAUBLE_ICON;
        int leftH = PHOTO_DISPLAY + PHOTO_FRAME_PAD * 2 + 6 + PHOTO_BTN_H + 4 + PHOTO_BTN_H;
        return Math.max(leftH, rightH);
    }

    /** 统一计算右列各行 Y 坐标（originY 为内容区顶部） */
    private void assignLayout(int originY) {
        int y = originY;
        idY = y;                        // 档案编号
        y += FIELD_H + ROW_GAP;
        dividerBasicY = y;              // 分隔：基本资料
        y += DIVIDER_GAP;
        factionY = y;                   // 所属势力 / 模型
        y += LINE_H + ROW_GAP;
        statsY = y;                     // 基础数值（血量 / 攻击 / 好感度）
        y += STATS_H + ROW_GAP;
        dividerPersonalY = y;           // 分隔：个人档案
        y += DIVIDER_GAP;
        occY = y;                       // 职业 / 生日（两列并排）
        y += FIELD_H + ROW_GAP;
        noteY = y;                      // 个人资料（多行）
        y += noteH + ROW_GAP;
        prefY = y;                      // 偏好与特长
        y += FIELD_H + ROW_GAP;
        storyY = y;                     // 背景故事（多行）
        y += storyH + ROW_GAP;
        baubleY = y;                    // 随身饰品
    }

    private MultiLineEditBox addField(boolean editable, boolean singleLine, int y, int x, int w, int h,
                                      String labelKey, String value, boolean allowEdit, int color, String hintKey) {
        MultiLineEditBox box = new MultiLineEditBox(this.font, x, y, w, h,
                Constants.PROFILE_TEXT_MAX_LEN, Component.translatable(labelKey));
        box.setSingleLine(singleLine);
        box.setValue(value == null ? "" : value);
        box.setTextColor(color);
        box.setEditable(editable && allowEdit);
        if (hintKey != null) {
            box.setHint(Component.translatable(hintKey));
        }
        addRenderableWidget(box);
        return box;
    }

    // ===== 保存与回执 =====

    /** 保存：把可编辑字段写回档案副本并提交服务端 */
    private void onSave() {
        if (view == null || !liveEntity) {
            return;
        }
        profile.setOccupation(occupationField.getValue());
        profile.setBirthday(birthdayField.getValue());
        profile.setPersonalNote(noteField.getValue());
        profile.setPreferences(prefField.getValue());
        profile.setStorySnapshot(storyField.getValue());
        IMaidFileNetwork net = IMaidFileNetwork.Holder.get();
        if (net == null) {
            setFeedback(Component.translatable("gui.maid_file_manager.profile.save_failed"), ERROR_COLOR);
            Constants.LOG.warn("[maid_file_manager] 网络实现缺失，档案未保存");
            return;
        }
        net.sendSaveMaidProfile(view.entityId(), profile);
    }

    @Override
    public void onFeedbackReceived(Component message) {
        if (message != null) {
            setFeedback(message, ACCENT);
        }
    }

    // ===== 照片 =====

    private void openPicker() {
        photoFiles.clear();
        Path dir = MaidPhotoUtil.photosDir(this.minecraft.gameDirectory.toPath());
        photoFiles.addAll(MaidPhotoUtil.listPhotoFiles(dir));
        pickerScroll = 0;
        pickerOpen = true;
        layoutPicker();
    }

    private void layoutPicker() {
        pickerW = Math.min(340, this.width - 40);
        pickerH = Math.min(230, this.height - 40);
        pickerX = (this.width - pickerW) / 2;
        pickerY = (this.height - pickerH) / 2;
        pickerOpenBtnX = pickerX + 8;
        pickerOpenBtnY = pickerY + pickerH - PICKER_BTN_H - 8;
        pickerOpenBtnW = 108;
        pickerCancelW = 64;
        pickerCancelX = pickerX + pickerW - 8 - pickerCancelW;
        pickerCancelY = pickerOpenBtnY;
    }

    private void choosePhoto(String fileName) {
        Path dir = MaidPhotoUtil.photosDir(this.minecraft.gameDirectory.toPath());
        Path file = dir.resolve(fileName);
        try {
            if (MaidPhotoUtil.isSourceTooLarge(file)) {
                setFeedback(Component.translatable("gui.maid_file_manager.profile.photo_source_too_large"), ERROR_COLOR);
                return;
            }
            byte[] bytes = MaidPhotoUtil.loadAndEncode(file, Constants.PROFILE_PHOTO_SIZE);
            if (bytes.length > Constants.PROFILE_PHOTO_MAX_BYTES) {
                setFeedback(Component.translatable("gui.maid_file_manager.profile.photo_too_large"), ERROR_COLOR);
                return;
            }
            profile.setPhoto(bytes);
            refreshPhotoTexture();
            pickerOpen = false;
            setFeedback(Component.translatable("gui.maid_file_manager.profile.photo_selected", fileName), SUBTEXT_COLOR);
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 读取档案照片失败: {}", t.toString());
            setFeedback(Component.translatable("gui.maid_file_manager.profile.photo_load_failed"), ERROR_COLOR);
        }
    }

    /** 打开档案照片投放目录（不存在则创建） */
    private void openPhotosDir() {
        try {
            Path dir = MaidPhotoUtil.ensurePhotosDir(this.minecraft.gameDirectory.toPath());
            // 用游戏原生的平台打开方式（各系统分支已内建），比直接调 AWT Desktop 更可靠
            Util.getPlatform().openFile(dir.toFile());
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 打开照片目录失败: {}", t.toString());
            setFeedback(Component.translatable("gui.maid_file_manager.profile.picker_open_dir_failed"), ERROR_COLOR);
        }
    }

    private void clearPhoto() {
        profile.setPhoto(null);
        releasePhotoTexture();
        setFeedback(Component.translatable("gui.maid_file_manager.profile.photo_cleared"), SUBTEXT_COLOR);
    }

    /** 用当前档案照片字节重建动态纹理（照片变化时调用） */
    private void refreshPhotoTexture() {
        releasePhotoTexture();
        byte[] bytes = profile.getPhoto();
        if (bytes == null || bytes.length == 0) {
            return;
        }
        try {
            NativeImage image = NativeImage.read(new ByteArrayInputStream(bytes));
            photoTexture = new DynamicTexture(image);
            photoRl = new ResourceLocation(Constants.MOD_ID, "profile_photo/preview");
            Minecraft.getInstance().getTextureManager().register(photoRl, photoTexture);
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 加载档案照片纹理失败: {}", t.toString());
            photoRl = null;
            photoTexture = null;
        }
    }

    private void releasePhotoTexture() {
        if (photoRl != null) {
            try {
                Minecraft.getInstance().getTextureManager().release(photoRl);
            } catch (Throwable ignored) {
                // 纹理不存在时释放失败可忽略
            }
            photoRl = null;
        }
        photoTexture = null;
    }

    // ===== 饰品 =====

    private void parseBaubleIcons() {
        baubleIcons.clear();
        if (view == null || view.baubleItems() == null) {
            return;
        }
        RegistryAccess registries = this.minecraft.level != null ? this.minecraft.level.registryAccess() : null;
        if (registries == null) {
            return;
        }
        for (CompoundTag tag : view.baubleItems()) {
            try {
                ItemStack stack = Services.PLATFORM.get().parseItemStack(registries, tag);
                if (stack != null && !stack.isEmpty()) {
                    baubleIcons.add(stack);
                }
            } catch (Throwable t) {
                Constants.LOG.warn("[maid_file_manager] 解析档案饰品图标失败: {}", t.toString());
            }
        }
    }

    // ===== 输入 =====

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (pickerOpen) {
            return handlePickerClick(mouseX, mouseY, button);
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 照片选择覆盖层的点击处理：命中文件行则选用，命中按钮或面板外则相应处理 */
    private boolean handlePickerClick(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return true;
        }
        if (inRect(mouseX, mouseY, pickerCancelX, pickerCancelY, pickerCancelW, PICKER_BTN_H)) {
            pickerOpen = false;
            return true;
        }
        if (inRect(mouseX, mouseY, pickerOpenBtnX, pickerOpenBtnY, pickerOpenBtnW, PICKER_BTN_H)) {
            openPhotosDir();
            return true;
        }
        int listTop = pickerY + 20;
        int listBottom = pickerOpenBtnY - 6;
        if (mouseX >= pickerX + 4 && mouseX <= pickerX + pickerW - 4 - PICKER_SCROLL_W
                && mouseY >= listTop && mouseY <= listBottom) {
            int index = pickerScroll + (int) ((mouseY - listTop) / PICKER_ROW_H);
            if (index >= 0 && index < photoFiles.size()) {
                choosePhoto(photoFiles.get(index));
            }
            return true;
        }
        if (mouseX < pickerX || mouseX > pickerX + pickerW || mouseY < pickerY || mouseY > pickerY + pickerH) {
            pickerOpen = false;
        }
        return true;
    }

    private static boolean inRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    /** 1.20.x 官方映射为三参 {@code mouseScrolled(mouseX, mouseY, delta)}，第三参即垂直量 */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (pickerOpen) {
            int listTop = pickerY + 20;
            int listBottom = pickerOpenBtnY - 6;
            int visibleRows = (listBottom - listTop) / PICKER_ROW_H;
            int maxScroll = Math.max(0, photoFiles.size() - visibleRows);
            pickerScroll = Math.max(0, Math.min(maxScroll, pickerScroll + (delta > 0 ? -2 : 2)));
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (pickerOpen && keyCode == 256) {
            pickerOpen = false;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ===== 渲染 =====

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, SCREEN_BG);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, PANEL_BG);
        graphics.renderOutline(panelX, panelY, panelW, panelH, PANEL_BORDER);
        graphics.drawString(this.font, this.title,
                this.width / 2 - this.font.width(this.title) / 2, panelY + 8, HEADER_COLOR, false);

        renderPhoto(graphics);
        renderReadonly(graphics);
        renderDividers(graphics);
        renderLabels(graphics);
        renderBaubles(graphics);

        super.render(graphics, mouseX, mouseY, partialTick);

        renderFeedback(graphics);
        if (pickerOpen) {
            renderPicker(graphics, mouseX, mouseY);
        } else {
            renderBaubleTooltip(graphics, mouseX, mouseY);
        }
    }

    /** 头像：相框装饰角 + 照片或占位文字 */
    private void renderPhoto(GuiGraphics graphics) {
        int frameX = photoX - PHOTO_FRAME_PAD;
        int frameY = photoY - PHOTO_FRAME_PAD;
        int frameW = PHOTO_DISPLAY + PHOTO_FRAME_PAD * 2;
        int frameH = PHOTO_DISPLAY + PHOTO_FRAME_PAD * 2;
        graphics.fill(frameX, frameY, frameX + frameW, frameY + frameH, 0xFF1C1812);
        if (photoRl != null) {
            // 动态纹理为 128×128，显式传入纹理实际尺寸并按 96px 显示，避免默认 256 假设导致只显示左上角
            graphics.blit(photoRl, photoX, photoY, 0f, 0f, PHOTO_DISPLAY, PHOTO_DISPLAY,
                    Constants.PROFILE_PHOTO_SIZE, Constants.PROFILE_PHOTO_SIZE);
        } else {
            graphics.fill(photoX, photoY, photoX + PHOTO_DISPLAY, photoY + PHOTO_DISPLAY, 0xFF141210);
            Component none = Component.translatable("gui.maid_file_manager.profile.no_photo");
            graphics.drawString(this.font, none,
                    photoX + (PHOTO_DISPLAY - this.font.width(none)) / 2,
                    photoY + PHOTO_DISPLAY / 2 - 4, SUBTEXT_COLOR, false);
        }
        graphics.renderOutline(frameX, frameY, frameW, frameH, PANEL_BORDER);
        // 相框四角装饰角
        drawCorner(graphics, frameX, frameY, 1, 1);
        drawCorner(graphics, frameX + frameW, frameY, -1, 1);
        drawCorner(graphics, frameX, frameY + frameH, 1, -1);
        drawCorner(graphics, frameX + frameW, frameY + frameH, -1, -1);
    }

    /** 在 (x,y) 处朝 (dx,dy) 方向画一个 L 形装饰角 */
    private void drawCorner(GuiGraphics graphics, int x, int y, int dx, int dy) {
        int len = 6;
        int x0 = dx > 0 ? x : x - len;
        int y0 = dy > 0 ? y : y - 1;
        graphics.fill(x0, y0, x0 + len, y0 + 1, ACCENT);
        int vx = dx > 0 ? x : x - 1;
        int vy = dy > 0 ? y : y - len;
        graphics.fill(vx, vy, vx + 1, vy + len, ACCENT);
    }

    private void renderReadonly(GuiGraphics graphics) {
        String faction = view != null && view.ownerName() != null ? view.ownerName() : "-";
        String model = view != null && view.displayName() != null ? view.displayName() : "-";
        graphics.drawString(this.font,
                Component.translatable("gui.maid_file_manager.profile.faction")
                        .append(Component.literal("：" + faction))
                        .getString(),
                colX, factionY, TEXT_COLOR, false);
        String modelLine = tr("gui.maid_file_manager.profile.model") + "：" + model;
        graphics.drawString(this.font, modelLine, colX + colW - this.font.width(modelLine),
                factionY, TEXT_COLOR, false);

        // 基础数值底框
        graphics.fill(colX, statsY, colX + colW, statsY + STATS_H, GROUP_BG);
        graphics.renderOutline(colX, statsY, colW, STATS_H, DIVIDER);
        // 离线预览（.maid 文件）没有可靠的血量/攻击数值，保持占位符；好感度与渡劫标记在文件中有据可查，正常展示
        String stats;
        if (liveEntity && view != null) {
            stats = tr("gui.maid_file_manager.profile.health") + " " + fmtInt(view.health()) + "/" + fmtInt(view.maxHealth())
                    + "    " + tr("gui.maid_file_manager.profile.attack") + " " + fmtFloat(view.attackDamage())
                    + "    " + tr("gui.maid_file_manager.profile.favor") + " " + view.favorability()
                    + "    " + lightningMark();
        } else {
            stats = tr("gui.maid_file_manager.profile.health") + " -"
                    + "    " + tr("gui.maid_file_manager.profile.attack") + " -"
                    + "    " + tr("gui.maid_file_manager.profile.favor") + " "
                    + (view != null ? view.favorability() : 0)
                    + "    " + lightningMark();
        }
        graphics.drawString(this.font, stats, colX + 8, statsY + (STATS_H - 8) / 2, TEXT_COLOR, false);
    }

    /** 渡劫标记文本：已渡劫显示「渡劫 是」，否则「渡劫 否」 */
    private String lightningMark() {
        boolean struck = view != null && view.struckByLightning();
        return tr("gui.maid_file_manager.profile.lightning") + " "
                + tr(struck ? "gui.maid_file_manager.profile.yes" : "gui.maid_file_manager.profile.no");
    }

    /** 分组细分隔线（带小标题） */
    private void renderDividers(GuiGraphics graphics) {
        drawSectionDivider(graphics, dividerBasicY, "gui.maid_file_manager.profile.group.basic");
        drawSectionDivider(graphics, dividerPersonalY, "gui.maid_file_manager.profile.group.personal");
        // 底部操作区上方的分隔线
        int bottomDiv = panelY + panelH - BOTTOM_H + 2;
        graphics.fill(panelX + 14, bottomDiv, panelX + panelW - 14, bottomDiv + 1, DIVIDER);
    }

    private void drawSectionDivider(GuiGraphics graphics, int y, String labelKey) {
        Component label = Component.translatable(labelKey);
        int lw = this.font.width(label);
        graphics.drawString(this.font, label, colX, y, SUBTEXT_COLOR, false);
        int lineX = colX + lw + 6;
        int lineY = y + 4;
        if (lineX < colX + colW) {
            graphics.fill(lineX, lineY, colX + colW, lineY + 1, DIVIDER);
        }
    }

    /** 绘制各字段左侧标签（多行框的标签垂直居中于首行） */
    private void renderLabels(GuiGraphics graphics) {
        drawLabel(graphics, idY, FIELD_H, colX, "gui.maid_file_manager.profile.id");
        drawLabel(graphics, occY, FIELD_H, colX, "gui.maid_file_manager.profile.occupation");
        int halfW = (colW - 8) / 2;
        drawLabel(graphics, occY, FIELD_H, colX + halfW + 8, "gui.maid_file_manager.profile.birthday");
        drawLabel(graphics, noteY, FIELD_H, colX, "gui.maid_file_manager.profile.note");
        drawLabel(graphics, prefY, FIELD_H, colX, "gui.maid_file_manager.profile.preferences");
        drawLabel(graphics, storyY, FIELD_H, colX, "gui.maid_file_manager.profile.story");
    }

    private void drawLabel(GuiGraphics graphics, int fieldY, int fieldH, int x, String key) {
        graphics.drawString(this.font, Component.translatable(key),
                x, fieldY + (fieldH - 8) / 2, SUBTEXT_COLOR, false);
    }

    private void renderBaubles(GuiGraphics graphics) {
        Component label = Component.translatable("gui.maid_file_manager.profile.baubles");
        graphics.drawString(this.font, label, colX, baubleY, HEADER_COLOR, false);
        int startX = colX + this.font.width(label) + 6;
        if (baubleIcons.isEmpty()) {
            graphics.drawString(this.font,
                    Component.translatable("gui.maid_file_manager.profile.no_bauble"),
                    startX, baubleY, SUBTEXT_COLOR, false);
            return;
        }
        int iconY = baubleY - 4;
        int maxCount = Math.max(1, (colX + colW - startX) / (BAUBLE_ICON + BAUBLE_GAP));
        for (int i = 0; i < baubleIcons.size() && i < maxCount; i++) {
            graphics.renderItem(baubleIcons.get(i), startX + i * (BAUBLE_ICON + BAUBLE_GAP), iconY);
        }
    }

    private void renderFeedback(GuiGraphics graphics) {
        if (feedback.getString().isEmpty()) {
            return;
        }
        // 反馈文本左对齐绘制于底部，右端需为右对齐的保存/返回按钮留出空间
        int maxW = panelW - 210;
        String text = this.font.plainSubstrByWidth(feedback.getString(), Math.max(20, maxW));
        graphics.drawString(this.font, text, panelX + 14, panelY + panelH - 22, feedbackColor, false);
    }

    private void renderBaubleTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (baubleIcons.isEmpty()) {
            return;
        }
        int startX = colX + this.font.width(Component.translatable("gui.maid_file_manager.profile.baubles")) + 6;
        int iconY = baubleY - 4;
        int maxCount = Math.max(1, (colX + colW - startX) / (BAUBLE_ICON + BAUBLE_GAP));
        for (int i = 0; i < baubleIcons.size() && i < maxCount; i++) {
            int ix = startX + i * (BAUBLE_ICON + BAUBLE_GAP);
            if (mouseX >= ix && mouseX < ix + BAUBLE_ICON && mouseY >= iconY && mouseY < iconY + BAUBLE_ICON) {
                graphics.renderTooltip(this.font, baubleIcons.get(i), mouseX, mouseY);
                return;
            }
        }
    }

    private void renderPicker(GuiGraphics graphics, int mouseX, int mouseY) {
        // 不透明遮罩：防止背后档案文字穿透浮现
        graphics.fill(0, 0, this.width, this.height, SCREEN_BG);
        graphics.fill(pickerX, pickerY, pickerX + pickerW, pickerY + pickerH, PANEL_BG);
        graphics.renderOutline(pickerX, pickerY, pickerW, pickerH, PANEL_BORDER);
        graphics.drawString(this.font, Component.translatable("gui.maid_file_manager.profile.picker_title"),
                pickerX + 8, pickerY + 6, HEADER_COLOR, false);

        int listTop = pickerY + 20;
        int listBottom = pickerOpenBtnY - 6;
        graphics.fill(pickerX + 4, listTop, pickerX + pickerW - 4, listBottom, 0xFF1A1712);
        if (photoFiles.isEmpty()) {
            graphics.drawString(this.font,
                    Component.translatable("gui.maid_file_manager.profile.picker_empty"),
                    pickerX + 8, listTop + 4, SUBTEXT_COLOR, false);
        } else {
            graphics.enableScissor(pickerX + 4, listTop, pickerX + pickerW - 4, listBottom);
            int visibleRows = (listBottom - listTop) / PICKER_ROW_H;
            for (int i = 0; i < visibleRows; i++) {
                int index = pickerScroll + i;
                if (index >= photoFiles.size()) {
                    break;
                }
                int rowY = listTop + i * PICKER_ROW_H;
                boolean hover = mouseX >= pickerX + 4 && mouseX <= pickerX + pickerW - 4
                        && mouseY >= rowY && mouseY < rowY + PICKER_ROW_H;
                if (hover) {
                    graphics.fill(pickerX + 4, rowY, pickerX + pickerW - 4, rowY + PICKER_ROW_H, 0x30FFFFFF);
                }
                graphics.drawString(this.font, Component.literal(photoFiles.get(index)),
                        pickerX + 8, rowY + 3, TEXT_COLOR, false);
            }
            graphics.disableScissor();
            if (photoFiles.size() > visibleRows) {
                int barX = pickerX + pickerW - 4 - PICKER_SCROLL_W;
                int barH = Math.max(12, (int) ((listBottom - listTop) * ((double) visibleRows / photoFiles.size())));
                int maxScroll = photoFiles.size() - visibleRows;
                int barY = listTop + (int) (((listBottom - listTop) - barH) * ((double) pickerScroll / maxScroll));
                graphics.fill(barX, barY, barX + PICKER_SCROLL_W, barY + barH, PANEL_BORDER);
            }
        }
        // 目录提示 + 底部按钮
        graphics.drawString(this.font,
                Component.translatable("gui.maid_file_manager.profile.picker_dir_hint"),
                pickerX + 8, pickerOpenBtnY - 12, SUBTEXT_COLOR, false);
        drawFlatButton(graphics, pickerOpenBtnX, pickerOpenBtnY, pickerOpenBtnW, PICKER_BTN_H,
                Component.translatable("gui.maid_file_manager.profile.picker_open_dir"),
                inRect(mouseX, mouseY, pickerOpenBtnX, pickerOpenBtnY, pickerOpenBtnW, PICKER_BTN_H));
        drawFlatButton(graphics, pickerCancelX, pickerCancelY, pickerCancelW, PICKER_BTN_H,
                Component.translatable("gui.maid_file_manager.profile.back"),
                inRect(mouseX, mouseY, pickerCancelX, pickerCancelY, pickerCancelW, PICKER_BTN_H));
    }

    private void drawFlatButton(GuiGraphics graphics, int x, int y, int w, int h, Component label, boolean hover) {
        graphics.fill(x, y, x + w, y + h, hover ? BUTTON_BG_HOVER : BUTTON_BG);
        graphics.renderOutline(x, y, w, h, PANEL_BORDER);
        graphics.drawString(this.font, label, x + (w - this.font.width(label)) / 2, y + (h - 8) / 2, TEXT_COLOR, false);
    }

    /** 打断 Screen 默认的 renderBackground 调用链（1.20.x 会画泥土背景并触发背景模糊） */
    @Override
    public void renderBackground(GuiGraphics graphics) {
        // 全屏底色与面板统一在 render() 开头绘制
    }

    /** 非暂停界面（1.20.x 官方映射方法名为 isPauseScreen） */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        releasePhotoTexture();
        if (prevHandler != null) {
            IMaidFileNetwork.ClientHandlerHolder.set(prevHandler);
        }
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    // ===== 主界面回调：本界面只关心反馈，其余忽略 =====

    @Override
    public void onMaidListReceived(List<io.github.zgxhzhr.maidfm.data.MaidInfo> list) {
    }

    @Override
    public void onExportResultReceived(List<io.github.zgxhzhr.maidfm.data.MaidFileData> dataList) {
    }

    @Override
    public void onImportBatchResultReceived(Component summary, List<Boolean> spawned) {
    }

    @Override
    public void onServerExportListReceived(List<IMaidFileNetwork.PlayerMaidGroup> groups) {
    }

    @Override
    public void onMaidProfileReceived(MaidProfileView received) {
    }

    // ===== 辅助 =====

    private void setFeedback(Component message, int color) {
        this.feedback = message == null ? Component.empty() : message;
        this.feedbackColor = color;
    }

    private String tr(String key) {
        return Component.translatable(key).getString();
    }

    private static String fmtInt(float v) {
        return String.valueOf(Math.round(v));
    }

    private static String fmtFloat(float v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    /** 木色按钮：深棕底 + 木色描边 + 白字（无阴影）；primary=true 时用实心暗金表示主操作 */
    private class WoodButton extends AbstractButton {
        private final Runnable action;
        private final boolean primary;

        WoodButton(int x, int y, int width, int height, Component message, Runnable action) {
            this(x, y, width, height, message, action, false);
        }

        WoodButton(int x, int y, int width, int height, Component message, Runnable action, boolean primary) {
            super(x, y, width, height, message);
            this.action = action;
            this.primary = primary;
        }

        @Override
        public void onPress() {
            this.action.run();
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            boolean hovered = isHoveredOrFocused();
            int bg;
            int border;
            if (!this.active) {
                bg = BUTTON_DISABLED;
                border = DIVIDER;
            } else if (this.primary) {
                bg = hovered ? SAVE_BG_HOVER : SAVE_BG;
                border = ACCENT;
            } else {
                bg = hovered ? BUTTON_BG_HOVER : BUTTON_BG;
                border = PANEL_BORDER;
            }
            graphics.fill(getX(), getY(), getX() + width, getY() + height, bg);
            graphics.renderOutline(getX(), getY(), width, height, border);
            int tx = getX() + (width - MaidProfileScreen.this.font.width(getMessage())) / 2;
            int ty = getY() + (height - 8) / 2;
            graphics.drawString(MaidProfileScreen.this.font, getMessage(), tx, ty,
                    this.active ? TEXT_COLOR : SUBTEXT_COLOR, false);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            this.defaultButtonNarrationText(output);
        }
    }
}
