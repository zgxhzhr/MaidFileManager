package io.github.zgxhzhr.maidfm.config;

import java.util.stream.Stream;
import io.github.zgxhzhr.maidfm.Constants;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * 配置文件读写的纯工具层。
 *
 * <p>从 {@code MaidConfigManager} 抽出。这里只做「字节 ↔ 内存值」的转换与原子落盘，
 * <b>不持有任何配置状态</b>，也不知道有哪些配置键——状态和键的语义仍归
 * {@code MaidConfigManager}。
 *
 * <p>抽出的理由：{@code MaidConfigManager} 原先 894 行，把「文件格式细节」和
 * 「配置领域判断」混在一起，导致连 {@code parse} 这种纯函数都无法单独测试。
 * 拆开后这一层可以被离线自检直接覆盖（纯函数 + 临时目录）。
 */
public final class ConfigFileIo {

    private ConfigFileIo() {
    }

    /**
     * 原子写：先写 {@code .tmp} 再移动替换，避免写一半崩溃导致配置文件损坏丢失全部设置。
     *
     * <p>会保留原文件里的注释行。{@code Properties.store} 是「全量重写」，会丢弃手写注释，
     * 而配置是给整合包作者手改的，注释往往是唯一的使用说明。
     *
     * @return 写入是否成功（失败时已记录 ERROR，调用方据此决定是否向玩家回报失败）
     */
    public static boolean store(Properties props, Path file) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            List<String> keptComments = new ArrayList<>();
            if (Files.exists(file)) {
                try {
                    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                        String trimmed = line.trim();
                        if (trimmed.startsWith("#") || trimmed.startsWith("!")) {
                            // 跳过 Properties.store 自己写的日期行（形如 "#Mon Oct 21 11:01:41 CST 2026"）：
                            // JDK 的 store0 无论 comment 是否为 null 都会写这一行，不跳过的话它下次会被
                            // 收集进 keptComments 再回放 → 每次保存 +1 行，注释无限累积（P2-3 只把 +2 降到 +1）。
                            if (trimmed.startsWith("#")
                                    && trimmed.matches("^#.*\\d{2}:\\d{2}:\\d{2}.*\\d{4}\\s*$")) {
                                continue;
                            }
                            keptComments.add(line);
                        }
                    }
                } catch (IOException ignored) {
                    // 读旧注释失败不影响写入，退回纯覆盖行为
                }
            }
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                for (String kept : keptComments) {
                    writer.write(kept);
                    writer.write(System.lineSeparator());
                }
                // 第二个参数传 null：传 comment 会让 Properties.store 自己再写一行 "# comment"，
                // 而下次保存时那行又被收集进 keptComments 再回放 —— 每次保存 +2 行，注释无限累积。
                props.store(writer, null);
            }
            try {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            Constants.LOG.error("[maid_file_manager] 保存配置文件失败: {}", file, e);
            return false;
        }
    }

    /** 读布尔配置；缺失或空串返回默认值，其余按 {@code equalsIgnoreCase("true")} 判定。 */
    public static boolean parse(Properties props, String key, boolean defaultValue) {
        String value = props.getProperty(key);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        return value.equalsIgnoreCase("true");
    }

    /** 解析逗号分隔的 ID 列表；容忍空白与重复，返回不可变列表（未做小写规范化）。 */
    public static List<String> parseIdList(Properties props, String key) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : value.split(",")) {
            String id = part.trim();
            if (!id.isEmpty() && !out.contains(id)) {
                out.add(id);
            }
        }
        return List.copyOf(out);
    }

    /** 读取浮点配置并按 0..1 钳制；缺失、非法或异常一律回退默认值。 */
    public static float parseVolume(Properties props, String key, float defaultValue) {
        try {
            String raw = props.getProperty(key);
            if (raw == null || raw.isBlank()) {
                return defaultValue;
            }
            float value = Float.parseFloat(raw.trim());
            if (Float.isNaN(value) || value < 0F) {
                return 0F;
            }
            return Math.min(value, 1F);
        } catch (Throwable t) {
            return defaultValue;
        }
    }

    /** 清单规范化：去空白、去重、小写（顺序保持）。 */
    public static List<String> sanitizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(values.size());
        for (String v : values) {
            if (v == null || v.isBlank()) {
                continue;
            }
            String s = v.trim().toLowerCase(Locale.ROOT);
            if (!out.contains(s)) {
                out.add(s);
            }
        }
        return List.copyOf(out);
    }

    /** 从 properties 读逗号分隔清单并规范化。 */
    public static List<String> parseSanitizedList(Properties props, String key) {
        return sanitizeList(parseIdList(props, key));
    }

    /** 合并两张清单（保持顺序、去重、返回不可变）。 */
    public static List<String> mergeLists(List<String> first, List<String> second) {
        if (second == null || second.isEmpty()) {
            return first == null ? List.of() : first;
        }
        List<String> merged = new ArrayList<>(first == null ? List.of() : first);
        for (String value : second) {
            if (value != null && !merged.contains(value)) {
                merged.add(value);
            }
        }
        return List.copyOf(merged);
    }

    /**
     * 读取 JSON 数组字段为去重后的字符串列表。
     *
     * <p>字段<b>缺失</b>（或显式 null）返回空列表；字段存在但<b>类型不是数组</b>则抛
     * {@link com.google.gson.JsonParseException}，由调用方的 catch 走「失败关闭」。
     *
     * <p>区分的理由：{@code "whitelist": "xxx"} 这类类型笔误若被当成「缺失」，白名单会静默变空
     * → {@code baubleWhitelistEnforced} 变 false → 管控整体放宽，与文档承诺的
     * 「字段类型不符 → 失败关闭」正好相反，而且现场只打一条正常的「白名单 0 项」INFO，
     * 整合包作者根本发现不了。
     */
    public static List<String> readStringArray(JsonObject root, String key) {
        if (root == null || !root.has(key) || root.get(key).isJsonNull()) {
            return List.of();
        }
        if (!root.get(key).isJsonArray()) {
            throw new com.google.gson.JsonParseException("字段 \"" + key
                    + "\" 必须是字符串数组，实际是 " + root.get(key).getClass().getSimpleName());
        }
        List<String> out = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray(key)) {
            // 只接受 JSON 字符串元素：文档承诺「数字/布尔元素被忽略」，而 isJsonPrimitive 对它们也返回 true，
            // getAsString() 会把 123 / true 当字符串收下，得到一个永不匹配的「物品 id」。
            if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                continue;
            }
            String value = element.getAsString();
            if (value == null) {
                continue;
            }
            // 与 properties 侧一致：统一小写，避免整合包作者写成首字母大写时静默失效（失败开放）。
            // 中文显示名不含大小写，转小写无副作用。
            value = value.trim().toLowerCase(Locale.ROOT);
            if (!value.isEmpty() && !out.contains(value)) {
                out.add(value);
            }
        }
        return List.copyOf(out);
    }

    /** 安全的 {@code Properties} 加载：文件不存在返回空实例，非法转义就地记录并保留已解析部分。 */
    public static Properties load(Path file, String what) {
        Properties props = new Properties();
        if (file == null) {
            return props;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException ignored) {
            // 文件不存在时使用默认值
        } catch (IllegalArgumentException badEscape) {
            // Properties.load 对手写非法转义（例如反斜杠 u 后面没有 4 位十六进制）抛
            // IllegalArgumentException（不是 IOException）。此前只 catch IOException，异常会一路
            // 冒到 init() 的兜底，导致整份配置回退默认值——其中含「禁止携带饰品」等管控项，属 fail-open。
            // Properties.load 是逐行推进的，抛异常前已读入的条目仍在 props 里，保留它们更安全。
            Constants.LOG.error("[maid_file_manager] {} 存在非法转义，已忽略出错行并保留已解析部分: {}",
                    what, badEscape.toString());
        }
        return props;
    }

    /**
     * 把旧版本散落的目录搬运并入新目录（{@code maid_file/} 之下）。
     *
     * <p>迁移对：{@code maid_exports/}→{@code maid_file/maid_exports/}、
     * {@code maid_imports/}→{@code maid_file/maid_imports/}、
     * {@code config/maid_file_manager/photos/}→{@code maid_file/photos/}。
     * 目标目录不存在时整目录直接改名移动（最快）；已存在时逐项合并，
     * 名称冲突一律保留新目录版本。迁移结束后清理已搬空的旧目录。
     *
     * <p>本方法幂等、且对缺失的旧目录静默跳过，可在每次启动时无副作用地调用。
     */
    public static void migrateLegacyDirs(Path gameDir) {
        if (gameDir == null) {
            return;
        }
        migrateInto(gameDir.resolve(Constants.LEGACY_EXPORTS_DIR), gameDir.resolve(Constants.MAID_EXPORTS_DIR));
        migrateInto(gameDir.resolve(Constants.LEGACY_IMPORTS_DIR), gameDir.resolve(Constants.MAID_IMPORTS_DIR));
        migrateInto(gameDir.resolve(Constants.LEGACY_PHOTOS_DIR), gameDir.resolve(Constants.MAID_PHOTO_CANDIDATES_DIR));
    }

    /** 迁移单个目录；旧目录不存在或新旧同路径时跳过 */
    private static void migrateInto(Path legacy, Path target) {
        if (legacy == null || !Files.isDirectory(legacy)) {
            return;
        }
        try {
            if (legacy.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
                return;
            }
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!Files.exists(target)) {
                try {
                    Files.move(legacy, target);
                    Constants.LOG.info("[maid_file_manager] 目录迁移: {} -> {}", legacy, target);
                    return;
                } catch (IOException e) {
                    // 跨卷等场景整目录移动失败，退回逐项合并
                    Files.createDirectories(target);
                }
            }
            mergeDir(legacy, target);
            deleteEmptyDirs(legacy);
        } catch (IOException e) {
            Constants.LOG.warn("[maid_file_manager] 旧目录迁移失败: {} -> {}: {}", legacy, target, e.toString());
        }
    }

    /** 递归把 src 内容并入 dst；同名文件保留 dst 中已有版本，目录则递归合并 */
    private static void mergeDir(Path src, Path dst) throws IOException {
        Files.createDirectories(dst);
        List<Path> children;
        try (Stream<Path> stream = Files.list(src)) {
            children = stream.toList();
        }
        for (Path child : children) {
            Path dest = dst.resolve(child.getFileName().toString());
            if (Files.isDirectory(child)) {
                if (Files.exists(dest) && !Files.isDirectory(dest)) {
                    continue; // 类型冲突：保留目标
                }
                mergeDir(child, dest);
            } else {
                if (Files.exists(dest)) {
                    continue; // 同名文件：保留新目录版本
                }
                Files.move(child, dest);
            }
        }
    }

    /** 自底向上删除已空的目录树（保留尚存文件的目录） */
    private static void deleteEmptyDirs(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        List<Path> children;
        try (Stream<Path> stream = Files.list(dir)) {
            children = stream.toList();
        }
        for (Path child : children) {
            if (Files.isDirectory(child)) {
                deleteEmptyDirs(child);
            }
        }
        try (Stream<Path> stream = Files.list(dir)) {
            if (stream.findAny().isEmpty()) {
                Files.deleteIfExists(dir);
            }
        }
    }
}
