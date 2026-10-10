package io.github.zgxhzhr.maidfm.data;

import io.github.zgxhzhr.maidfm.Constants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * 女仆档案数据模型。
 *
 * <p>档案随女仆实体保存在自定义 NBT 标签 {@code maid_file_manager:profile} 内，
 * 并在导出时落到 {@code .maid} 文件的顶层 {@code profile} 复合标签（格式版本 v7 起）。
 *
 * <p>字段说明：
 * <ul>
 *   <li>{@code profile_version}：档案结构版本，便于未来迁移</li>
 *   <li>{@code photo}：等比缩放后的 PNG 字节（最长边不超过
 *       {@link Constants#PROFILE_PHOTO_MAX_SIDE}），可空。
 *       <b>照片不写入女仆实体 NBT</b>：实体侧改存
 *       {@code maid_file/maid_photos/<女仆UUID>.png}，本字段仅在
 *       {@code .maid} 打包与档案视图下发的数据传输链路上出现，
 *       详见 {@link MaidPhotoStore}</li>
 *   <li>{@code occupation}：职业，空表示默认「女仆」</li>
 *   <li>{@code birthday}：生日（个人资料的一部分）</li>
 *   <li>{@code personal_note}：其他个人资料</li>
 *   <li>{@code preferences}：偏好与特长</li>
 *   <li>{@code story_snapshot}：背景故事的冗余快照；权威内容仍是女仆实体
 *       {@code MaidAIChat.CustomSetting}，本字段仅供无实体场景（如导入前预览）展示</li>
 * </ul>
 *
 * <p>所有字段均可空、读取端一律容错：旧文件缺 {@code profile} 或缺少某些子键都按默认值处理。
 */
public final class MaidProfile {
    /** 当前档案结构版本 */
    public static final int PROFILE_VERSION = 1;

    private int profileVersion = PROFILE_VERSION;
    private byte[] photo;
    private String occupation = "";
    private String birthday = "";
    private String personalNote = "";
    private String preferences = "";
    private String storySnapshot = "";

    public MaidProfile() {
    }

    // ---------- 序列化 ----------

    /** 完整序列化（含照片）：用于 {@code .maid} 打包与档案视图下发 */
    public CompoundTag writeToNbt() {
        return writeToNbt(true);
    }

    /**
     * 序列化档案。
     *
     * @param includePhoto 是否包含 {@code photo} 字节。写回女仆实体时传 {@code false}：
     *                     照片由 {@link MaidPhotoStore} 独立存盘，实体 NBT 不再携带图片数据
     */
    public CompoundTag writeToNbt(boolean includePhoto) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("profile_version", profileVersion);
        if (includePhoto && photo != null && photo.length > 0) {
            tag.putByteArray("photo", photo);
        }
        putIfNotBlank(tag, "occupation", occupation);
        putIfNotBlank(tag, "birthday", birthday);
        putIfNotBlank(tag, "personal_note", personalNote);
        putIfNotBlank(tag, "preferences", preferences);
        putIfNotBlank(tag, "story_snapshot", storySnapshot);
        return tag;
    }

    private static void putIfNotBlank(CompoundTag tag, String key, String value) {
        if (value != null && !value.isEmpty()) {
            tag.putString(key, value);
        }
    }

    /** 从 NBT 读取档案；{@code tag} 为 null 时返回 null（旧文件无 profile 字段） */
    public static MaidProfile readFromNbt(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return null;
        }
        MaidProfile p = new MaidProfile();
        p.profileVersion = tag.contains("profile_version", Tag.TAG_INT)
                ? tag.getInt("profile_version")
                : PROFILE_VERSION;
        if (tag.contains("photo", Tag.TAG_BYTE_ARRAY)) {
            byte[] bytes = tag.getByteArray("photo");
            if (bytes.length > 0 && bytes.length <= Constants.PROFILE_PHOTO_MAX_BYTES) {
                p.photo = bytes;
            }
        }
        p.occupation = tag.getString("occupation");
        p.birthday = tag.getString("birthday");
        p.personalNote = tag.getString("personal_note");
        p.preferences = tag.getString("preferences");
        p.storySnapshot = tag.getString("story_snapshot");
        return p;
    }

    /** 是否没有任何有效内容（用于判断是否需要写入实体） */
    public boolean isEmpty() {
        return (photo == null || photo.length == 0)
                && isBlank(occupation) && isBlank(birthday) && isBlank(personalNote)
                && isBlank(preferences) && isBlank(storySnapshot);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }

    // ---------- getters / setters ----------

    public int getProfileVersion() {
        return profileVersion;
    }

    public void setProfileVersion(int profileVersion) {
        this.profileVersion = profileVersion;
    }

    public byte[] getPhoto() {
        return photo;
    }

    public void setPhoto(byte[] photo) {
        this.photo = photo;
    }

    public String getOccupation() {
        return occupation;
    }

    public void setOccupation(String occupation) {
        this.occupation = occupation == null ? "" : occupation;
    }

    public String getBirthday() {
        return birthday;
    }

    public void setBirthday(String birthday) {
        this.birthday = birthday == null ? "" : birthday;
    }

    public String getPersonalNote() {
        return personalNote;
    }

    public void setPersonalNote(String personalNote) {
        this.personalNote = personalNote == null ? "" : personalNote;
    }

    public String getPreferences() {
        return preferences;
    }

    public void setPreferences(String preferences) {
        this.preferences = preferences == null ? "" : preferences;
    }

    public String getStorySnapshot() {
        return storySnapshot;
    }

    public void setStorySnapshot(String storySnapshot) {
        this.storySnapshot = storySnapshot == null ? "" : storySnapshot;
    }
}
