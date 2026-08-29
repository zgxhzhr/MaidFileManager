package com.example.maid_file_manager.data;

import com.example.maid_file_manager.Constants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * .maid 文件的读写与命名工具。
 *
 * <p>导出文件命名规则：{@code 中文名_yyyyMMdd-HHmmss_短UUID.maid}
 * <ul>
 *   <li>中文名从模型 ID 推断（找不到时回退到模型 ID 转下划线形式）</li>
 *   <li>短 UUID 取前 8 位，便于在同名同时间文件多份时区分来源</li>
 *   <li>文件名中的非法字符替换为下划线</li>
 *   <li>同模型多个文件可通过文件名中的时间戳自然排序</li>
 * </ul>
 *
 * <p>目录：导出 → {@code maid_exports/}，导入源 → {@code maid_imports/}（用户把自己的 .maid 放到此处）。
 */
public final class MaidFileIo {
    /** 时间戳格式：年月日-时分秒 */
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /** 文件名中非法字符的正则（Windows 不允许 <>:"/\|?*[]，及控制字符） */
    private static final String INVALID_CHAR_REGEX = "[\\\\/:*?\"<>|\\[\\]\\u0000-\\u001F]";
    /** 替换字符 */
    private static final String SEP = "_";

    private MaidFileIo() {
    }

    /** 获取导出目录（maid_exports），若不存在则创建 */
    public static Path ensureExportsDir(Path gameDir) {
        return ensureDir(gameDir, Constants.MAID_EXPORTS_DIR);
    }

    /** 获取导入目录（maid_imports），若不存在则创建 */
    public static Path ensureImportsDir(Path gameDir) {
        return ensureDir(gameDir, Constants.MAID_IMPORTS_DIR);
    }

    private static Path ensureDir(Path gameDir, String sub) {
        Path dir = gameDir.resolve(sub);
        if (!Files.exists(dir)) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                Constants.LOG.error("创建 {} 目录失败: {}", sub, dir, e);
            }
        }
        return dir;
    }

    /**
     * 生成导出文件名（不含路径）。
     *
     * <p>格式：{@code <displayName>_<yyyyMMdd-HHmmss>_<uuid前8位>.maid}
     * 例如 {@code 博丽灵梦_20260820-153012_a1b2c3d4.maid}
     *
     * @param displayName 模型显示名（中文名）；为空时回退到 {@code modelId}
     * @param modelId      模型 ID（fallback 用）
     * @param ownerUuid    主人 UUID（取前 8 位用于区分，null 时省略）
     * @param time         导出时间
     */
    public static String buildExportFileName(String displayName, String modelId, String ownerUuid, LocalDateTime time) {
        String name = (displayName == null || displayName.isEmpty())
                ? fallbackNameFromModelId(modelId)
                : displayName;
        name = sanitize(name);
        String timestamp = time.format(TIMESTAMP_FORMAT);
        String shortUuid = (ownerUuid != null && ownerUuid.length() >= 8)
                ? ownerUuid.replace("-", "").substring(0, 8)
                : "nouuid";
        return name + SEP + timestamp + SEP + shortUuid + Constants.MAID_FILE_EXT;
    }

    /** 从 modelId（如 touhou_little_maid:hakurei_reimu）回退出可读文件名 */
    private static String fallbackNameFromModelId(String modelId) {
        if (modelId == null || modelId.isEmpty()) {
            return "unknown_model";
        }
        // 取冒号后路径段作为可读名
        int colon = modelId.indexOf(':');
        String path = colon >= 0 ? modelId.substring(colon + 1) : modelId;
        return sanitize(path);
    }

    /** 把任意字符串清洗为文件名安全字符串 */
    public static String sanitize(String s) {
        if (s == null || s.isEmpty()) {
            return "unknown";
        }
        // 去除 Windows 非法字符，并 trim 首尾空白与下划线
        String cleaned = s.replaceAll(INVALID_CHAR_REGEX, SEP).trim();
        // 折叠多个连续下划线
        cleaned = cleaned.replaceAll("_+", SEP);
        // 去除首尾下划线
        while (cleaned.startsWith(SEP)) cleaned = cleaned.substring(1);
        while (cleaned.endsWith(SEP)) cleaned = cleaned.substring(0, cleaned.length() - 1);
        return cleaned.isEmpty() ? "unknown" : cleaned;
    }

    /**
     * 玩家名目录安全清洗：仅替换 Windows 非法字符为下划线，不做「首尾下划线删除/多下划线折叠」。
     * 避免单独下划线玩家名（如 "_"）被清洗成空→回退 "unknown" 导致子目录错位。
     */
    public static String sanitizePlayerName(String s) {
        if (s == null || s.isEmpty()) {
            return "unknown";
        }
        String cleaned = s.replaceAll(INVALID_CHAR_REGEX, SEP);
        return cleaned.isEmpty() ? "unknown" : cleaned;
    }

    /**
     * 写入 .maid 文件，处理重名情况（追加 _1/_2）。
     *
     * @param dir         目录
     * @param displayName 模型显示名
     * @param modelId     模型 ID（fallback）
     * @param ownerUuid   主人 UUID
     * @param data        数据
     * @param time        导出时间
     * @return 实际写入的文件名（不含路径）
     */
    public static String writeMaidFile(Path dir, String displayName, String modelId,
                                        String ownerUuid, MaidFileData data, LocalDateTime time) throws IOException {
        // 确保父目录存在（玩家子目录不存在时自动创建，避免 FileNotFoundException）
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            Constants.LOG.warn("[maid_file_manager] 创建导出目录失败: dir={}, err={}", dir, e.toString());
            throw e;
        }
        String baseName = buildExportFileName(displayName, modelId, ownerUuid, time);
        Path target = dir.resolve(baseName);
        int suffix = 1;
        while (Files.exists(target)) {
            String numbered = baseName.replace(Constants.MAID_FILE_EXT,
                    SEP + suffix + Constants.MAID_FILE_EXT);
            target = dir.resolve(numbered);
            suffix++;
        }
        CompoundTag root = data.writeToNbt();
        NbtIo.writeCompressed(root, target);
        return target.getFileName().toString();
    }

    /** 读取 .maid 文件，失败返回 null */
    public static MaidFileData readMaidFile(Path file) {
        try {
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            if (root == null) {
                return null;
            }
            return MaidFileData.readFromNbt(root);
        } catch (IOException e) {
            Constants.LOG.error("读取 maid 文件失败: {}", file, e);
            return null;
        }
    }

    /**
     * 列出目录中所有 .maid 文件，按时间戳降序排列（最新在前）。
     *
     * @return 文件名列表（不含路径）
     */
    public static List<String> listMaidFiles(Path dir) {
        List<String> result = new ArrayList<>();
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return result;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(Constants.MAID_FILE_EXT))
                    .forEach(p -> result.add(p.getFileName().toString()));
        } catch (IOException e) {
            Constants.LOG.error("列出 maid 目录失败: {}", dir, e);
        }
        // 按时间戳降序排序（最新的在前）
        result.sort(Comparator.reverseOrder());
        return result;
    }

    /**
     * 解析文件名中的时间戳，用于排序与展示。
     * 新命名格式：{@code <name>_<yyyyMMdd-HHmmss>_<uuid8>.maid}
     */
    public static long parseTimestampFromFileName(String fileName) {
        if (fileName == null || !fileName.endsWith(Constants.MAID_FILE_EXT)) {
            return 0L;
        }
        String name = fileName.substring(0, fileName.length() - Constants.MAID_FILE_EXT.length());
        // 找形如 _20260820-153012 的段
        int dashIdx = name.lastIndexOf('-');
        if (dashIdx < 0) {
            return 0L;
        }
        int tsStart = name.lastIndexOf('_', dashIdx);
        if (tsStart < 0) {
            return 0L;
        }
        String timestampPart = name.substring(tsStart + 1, dashIdx + 7);
        try {
            return LocalDateTime.parse(timestampPart, TIMESTAMP_FORMAT)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli();
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 从 maid 文件读取模型显示名（中文名），优先用 MaidFileData.getModelId()
     * 因为 modelId 在导出时已存入 .maid 文件中。
     */
    public static String extractDisplayModelId(String fileName) {
        if (fileName == null) {
            return "";
        }
        String name = fileName.endsWith(Constants.MAID_FILE_EXT)
                ? fileName.substring(0, fileName.length() - Constants.MAID_FILE_EXT.length())
                : fileName;
        // 去掉末尾的 _<时间>_<uuid8>
        int dashIdx = name.lastIndexOf('-');
        if (dashIdx > 0) {
            int tsStart = name.lastIndexOf('_', dashIdx);
            if (tsStart > 0) {
                name = name.substring(0, tsStart);
            }
        }
        return name;
    }

    public static Logger logger() {
        return Constants.LOG;
    }
}
