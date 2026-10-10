package io.github.zgxhzhr.maidfm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Constants {
    public static final String MOD_ID = "maid_file_manager";
    public static final String MOD_NAME = "Maid File Manager";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_NAME);

    /**
     * 本模组玩家可见文件的根目录（位于游戏/版本根目录下）。
     * 导出、导入、档案照片三个子目录统一收纳于此，方便玩家一次性打包带走。
     */
    public static final String MAID_FILE_ROOT = "maid_file";
    /** 导出目录（相对游戏根目录）：玩家导出女仆后，.maid 文件落在这里 */
    public static final String MAID_EXPORTS_DIR = MAID_FILE_ROOT + "/maid_exports";
    /** 导入目录（相对游戏根目录）：玩家把外部的 .maid 文件放到这里后，可在游戏内导入 */
    public static final String MAID_IMPORTS_DIR = MAID_FILE_ROOT + "/maid_imports";

    // ---------- 旧版目录（迁移用） ----------
    /** 旧版导出目录（曾直接位于游戏根目录下） */
    public static final String LEGACY_EXPORTS_DIR = "maid_exports";
    /** 旧版导入目录（曾直接位于游戏根目录下） */
    public static final String LEGACY_IMPORTS_DIR = "maid_imports";
    /** .maid 文件扩展名 */
    public static final String MAID_FILE_EXT = ".maid";
    /** 当前 .maid 文件格式版本，跨版本兼容时可用于迁移（v7 起新增顶层 profile 档案字段） */
    public static final int MAID_FILE_FORMAT_VERSION = 7;
    /** 导入时女仆生成在玩家前方的距离（格） */
    public static final double IMPORT_SPAWN_DISTANCE = 2.5;

    // ---------- 女仆档案 ----------
    /** 档案在女仆实体上的自定义 NBT 标签键（带 MODID 前缀，避免与他模冲突） */
    public static final String PROFILE_NBT_KEY = MOD_ID + ":profile";
    /** 照片候选目录（相对游戏根目录）：玩家把图片放入此处后在档案界面挑选 */
    public static final String MAID_PHOTO_CANDIDATES_DIR = MAID_FILE_ROOT + "/photos";
    /**
     * 档案照片库目录（相对游戏根目录，位于服务端）。
     *
     * <p>每位女仆的照片以 {@code <女仆UUID>.png} 独立存盘，<b>不再写入女仆实体 NBT</b>，
     * 避免实体每次保存都携带体积较大的图片数据。与 {@link #MAID_PHOTO_CANDIDATES_DIR}
     * （玩家投放的候选图）职责严格分离：本目录由模组自动维护，玩家无需手动干预。
     */
    public static final String MAID_PHOTOS_DIR = MAID_FILE_ROOT + "/maid_photos";
    /** 旧版照片目录（曾位于 config 下，迁移用） */
    public static final String LEGACY_PHOTOS_DIR = "config/" + MOD_ID + "/photos";
    /**
     * 档案照片最长边上限（等比缩放，不裁剪，存盘为 PNG）。
     * 显示框为 96 逻辑像素，512 足以覆盖高 GUI 缩放下的清晰度需求，
     * 同时把字节体积控制在网络包与 {@code .maid} 单文件上限之内。
     */
    public static final int PROFILE_PHOTO_MAX_SIDE = 512;
    /**
     * 档案照片「可选原图」文件字节上限（10 MiB）。
     * 玩家在档案界面挑选的照片原图超过此值即拒绝读取，提示换一张更小的图片；
     * 与本值无关的存盘体积见 {@link #PROFILE_PHOTO_MAX_BYTES}。
     */
    public static final long PROFILE_PHOTO_SOURCE_MAX_BYTES = 10L * 1024 * 1024;
    /**
     * 档案照片「存盘 PNG」字节上限（128 KiB）。
     * 照片存盘与传输（档案视图下发、{@code .maid} 打包）共用本上限：
     * 编码时若超限会自动降低边长重编码，务必让 {@code .maid} 单文件体积
     * 仍落在网络线格式的 512 KiB 上限之内。
     */
    public static final int PROFILE_PHOTO_MAX_BYTES = 128 * 1024;
    /** 档案可编辑文本字段的字符上限（生日/个人资料/偏好/背景故事等） */
    public static final int PROFILE_TEXT_MAX_LEN = 2048;

    // ---------- TLM 备份管理器 ----------
    /** TLM 自动备份目录名（位于存档 data/ 之下） */
    public static final String MAID_BACKUPS_DIR = "maid_backups";
    /** 玩家名解析缓存文件名（相对游戏根目录 config/maid_file_manager 下） */
    public static final String NAME_CACHE_FILE = "name_cache.json";
    /** 游戏目录下由 Minecraft 本体维护的「UUID → 最后已知用户名」对照表文件名 */
    public static final String USERNAME_CACHE_FILE = "usernamecache.json";
    /** 玩家名解析最小请求间隔（毫秒）：Mojang 接口限速约 1 次/分钟 */
    public static final long NAME_LOOKUP_MIN_INTERVAL_MS = 60_000L;

    // ---------- 导入产物标记 ----------
    /**
     * 导入产物标记键名：写入女仆实体的持久化标签，随实体存档与 .maid 文件迁移。
     *
     * <p>供整合包作者在实体生成事件（如 KubeJS）中读取，识别"这只女仆由本模组导入产生"，
     * 从而对结构特殊女仆等场景发放补偿。键名一经发布不可更改，外部脚本依赖它。
     */
    public static final String IMPORTED_NBT_KEY = "maidfm_imported";
}
