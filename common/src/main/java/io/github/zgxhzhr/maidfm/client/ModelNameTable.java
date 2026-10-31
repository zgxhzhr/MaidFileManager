package io.github.zgxhzhr.maidfm.client;

import io.github.zgxhzhr.maidfm.Constants;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.List;
import java.util.stream.Stream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 模型中文名表：从资源管理器 / 模型包目录 / TLM 模型包 zip 里收集
 * {@code model.<ns>.<path>.name} 条目，懒加载一次并可强制重扫。
 *
 * <p>从 {@link ModelNames} 拆出：那一份原本同时承担「对外展示 API」「名字表加载」
 * 「模型包反射查询」三件事。名字表这部分是纯数据收集，与展示逻辑无耦合，
 * 也不碰反射，单独成类后：
 * <ul>
 *   <li>加载失败只影响名字表，不会连带拖垮展示 API</li>
 *   <li>重扫（玩家运行中下载新模型包）的入口在这里，语义清楚</li>
 * </ul>
 *
 * <p>只在客户端路径被引用，可以直接用 {@code net.minecraft.client.*}。
 */
final class ModelNameTable {

    /** 翻译键（{@code model.<ns>.<path>.name}）→ 中文名。 */
    private static Map<String, String> zhByKey;
    /** 英文模型名 → 中文名（备份列表只有名字、没有模型 ID 时用）。 */
    private static Map<String, String> zhByEn;
    private static boolean modelNamesLoaded;

    private ModelNameTable() {
    }

    /** 重新扫描（玩家可能在运行中下载了新的模型包）。 */
    static synchronized void invalidate() {
        modelNamesLoaded = false;
    }

    /** 当前中文名表条目数（用于判断重扫有没有收获）。 */
    static int size() {
        return zhByKey == null ? 0 : zhByKey.size();
    }

    /** 翻译键 → 中文名（可能为 null）。 */
    static String lookupKey(String key) {
        return zhByKey == null ? null : zhByKey.get(key);
    }

    /** 英文模型名 → 中文名（可能为 null）。 */
    static String lookupEn(String en) {
        return zhByEn == null ? null : zhByEn.get(en);
    }

    /** 只读视图，供导出映射用（可能为 null）。 */
    static Map<String, String> zhByKeyMap() {
        return zhByKey;
    }

    /** 当前语言是否文言 / 各类中文（这些语言下模型名要回退到中文）。 */
    static boolean isZhLike() {
        return isZhLikeLanguage();
    }

    /** 确保名字表已加载（幂等）。 */
    static void ensure() {
        ensureModelNameTables();
    }

    // ================= 以下为原有实现 =================

    /** 依次查若干键在中文模型名表里的值。 */
    static String lookupZh(String... keys) {
        if (zhByKey == null || zhByKey.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            String value = zhByKey.get(key);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    /** 当前客户端语言是否文言 / 各类中文（这些语言下模型名要回退到中文）。 */
    private static boolean isZhLikeLanguage() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getLanguageManager() == null) {
                return false;
            }
            String code = mc.getLanguageManager().getSelected();
            return "lzh".equals(code) || (code != null && code.startsWith("zh_"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void ensureModelNameTables() {
        if (modelNamesLoaded) {
            return;
        }
        modelNamesLoaded = true;
        Map<String, String> zh = new HashMap<>();
        Map<String, String> en = new HashMap<>();
        int fromResourcePack = collectFromResourceManager(zh, en);
        int fromDisk = collectFromModelPackDir(zh, en);
        // TLM 模型包（zip + .lang）：前两个来源都覆盖不到真机上的私有模型包
        fromDisk += collectFromTlmPacks(zh, en);
        zhByKey = zh;
        Map<String, String> byEn = new HashMap<>();
        for (Map.Entry<String, String> e : zh.entrySet()) {
            String enName = en.get(e.getKey());
            String zhName = e.getValue();
            if (enName != null && !enName.isEmpty() && !enName.equals(zhName)) {
                byEn.putIfAbsent(enName, zhName);
            }
        }
        zhByEn = byEn;
        io.github.zgxhzhr.maidfm.Constants.LOG.info(
                "[maid_file_manager] 模型中文名表已加载: lang={}, zhKey={}, enKey={}, en→zh={}, 资源包文件={}, 磁盘文件={}",
                currentLanguageCode(), zh.size(), en.size(), byEn.size(), fromResourcePack, fromDisk);
    }

    /** 当前语言代码（仅用于日志）。 */
    private static String currentLanguageCode() {
        try {
            Minecraft mc = Minecraft.getInstance();
            return mc == null || mc.getLanguageManager() == null ? "?" : mc.getLanguageManager().getSelected();
        } catch (Throwable ignored) {
            return "?";
        }
    }

    /** 来源一：MC 资源管理器里所有命名空间的 {@code lang/zh_cn.json} 与 {@code en_us.json}。 */
    private static int collectFromResourceManager(Map<String, String> zh, Map<String, String> en) {
        int count = 0;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                return 0;
            }
            ResourceManager rm = mc.getResourceManager();
            if (rm == null) {
                return 0;
            }
            count += collectLangFiles(rm, "zh_cn.json", zh);
            count += collectLangFiles(rm, "en_us.json", en);
        } catch (Throwable t) {
            io.github.zgxhzhr.maidfm.Constants.LOG.warn(
                    "[maid_file_manager] 从资源管理器收集模型名失败: {}", t.toString());
        }
        return count;
    }

    private static int collectLangFiles(ResourceManager rm, String fileName, Map<String, String> out) {
        int count = 0;
        try {
            String suffix = "lang/" + fileName;
            Map<Identifier, Resource> map = rm.listResources("lang",
                    loc -> loc.getPath().endsWith(suffix) || loc.getPath().equals(suffix));
            for (Resource resource : map.values()) {
                try (InputStream in = resource.open()) {
                    mergeModelNames(new String(in.readAllBytes(), StandardCharsets.UTF_8), out);
                    count++;
                } catch (Throwable ignored) {
                    // 单个语言文件失败不影响其它
                }
            }
        } catch (Throwable t) {
            io.github.zgxhzhr.maidfm.Constants.LOG.warn(
                    "[maid_file_manager] 收集 {} 失败: {}", fileName, t.toString());
        }
        return count;
    }

    /**
     * 来源二：磁盘上的第三方模型包 —— {@code <gameDir>/tlm_custom_pack/**}{@code /lang/}
     * 下的 {@code zh_cn.json} 与 {@code en_us.json}（TLM 自己从磁盘加载，不在 MC 资源管理器里）。
     */
    private static int collectFromModelPackDir(Map<String, String> zh, Map<String, String> en) {
        int count = 0;
        try {
            Path gameDir = io.github.zgxhzhr.maidfm.platform.Services.PLATFORM.get().getGameDir();
            if (gameDir == null) {
                return 0;
            }
            // 依次尝试：<gameDir>/tlm_custom_pack 与 <gameDir 的上一级>/tlm_custom_pack
            // （PCL 等启动器有「版本隔离」与「共享目录」两种布局）
            Path[] candidates = new Path[]{
                    gameDir.resolve("tlm_custom_pack"),
                    gameDir.getParent() == null ? null : gameDir.getParent().resolve("tlm_custom_pack")
            };
            for (Path packs : candidates) {
                if (packs == null || !Files.isDirectory(packs)) {
                    continue;
                }
                try (java.util.stream.Stream<Path> walk = Files.walk(packs, 8)) {
                    java.util.List<Path> langFiles = walk
                            .filter(Files::isRegularFile)
                            .filter(p -> {
                                Path name = p.getFileName();
                                if (name == null) {
                                    return false;
                                }
                                String n = name.toString();
                                return "zh_cn.json".equals(n) || "en_us.json".equals(n);
                            })
                            .limit(4000L)
                            .toList();
                    for (Path p : langFiles) {
                        try {
                            String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                            String n = p.getFileName().toString();
                            mergeModelNames(text, "zh_cn.json".equals(n) ? zh : en);
                            count++;
                        } catch (Throwable ignored) {
                            // 单个文件失败不影响其它
                        }
                    }
                }
            }
        } catch (Throwable t) {
            io.github.zgxhzhr.maidfm.Constants.LOG.warn(
                    "[maid_file_manager] 扫描 tlm_custom_pack 失败: {}", t.toString());
        }
        return count;
    }

    /**
     * 来源三：TLM 模型包 zip（{@code config/touhou_little_maid/file/*.zip}）。
     *
     * <p>TLM 的第三方模型包是 **zip 压缩包**，里面的语言文件是 **老式 {@code .lang}（key=value）**，
     * 既不在 {@code tlm_custom_pack} 解压目录里，也不是 {@code .json} 格式——
     * 前两个来源都覆盖不到，于是自定义模型（如 {@code cao_dong:001}）永远解析不出中文名。
     */
    private static int collectFromTlmPacks(Map<String, String> zh, Map<String, String> en) {
        int count = 0;
        try {
            Path gameDir = io.github.zgxhzhr.maidfm.platform.Services.PLATFORM.get().getGameDir();
            if (gameDir == null) {
                return 0;
            }
            Path[] dirs = new Path[]{
                    gameDir.resolve("config/touhou_little_maid/file"),
                    gameDir.getParent() == null ? null
                            : gameDir.getParent().resolve("config/touhou_little_maid/file")
            };
            for (Path dir : dirs) {
                if (dir == null || !Files.isDirectory(dir)) {
                    continue;
                }
                try (java.util.stream.Stream<Path> list = Files.list(dir)) {
                    for (Path zip : list.toList()) {
                        String n = zip.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                        if (!n.endsWith(".zip")) {
                            continue;
                        }
                        count += readLangFromZip(zip, zh, en);
                    }
                }
            }
        } catch (Throwable t) {
            io.github.zgxhzhr.maidfm.Constants.LOG.warn(
                    "[maid_file_manager] 扫描 TLM 模型包失败: {}", t.toString());
        }
        return count;
    }

    /** 读一个模型包 zip 里所有 {@code zh_cn.lang} / {@code en_us.lang} */
    private static int readLangFromZip(Path zip, Map<String, String> zh, Map<String, String> en) {
        int n = 0;
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zip.toFile(), StandardCharsets.UTF_8)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry ze = entries.nextElement();
                String name = ze.getName();
                if (!name.endsWith(".lang")) {
                    continue;
                }
                boolean isZh = name.endsWith("zh_cn.lang");
                boolean isEn = name.endsWith("en_us.lang");
                if (!isZh && !isEn) {
                    continue;
                }
                try (java.io.InputStream in = zf.getInputStream(ze)) {
                    String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    mergeLangProperties(text, isZh ? zh : en);
                    n++;
                }
            }
        } catch (Throwable ignored) {
            // 单个包损坏不影响其它
        }
        return n;
    }

    /** 解析老式 {@code .lang}（key=value），只收 {@code model.*.name} 条目 */
    private static void mergeLangProperties(String text, Map<String, String> out) {
        if (text == null || text.isEmpty() || out == null) {
            return;
        }
        for (String line : text.split("\\R")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) {
                continue;
            }
            int eq = s.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = s.substring(0, eq).trim();
            String val = s.substring(eq + 1).trim();
            if (key.startsWith("model.") && key.endsWith(".name") && !val.isEmpty()) {
                out.putIfAbsent(key, val);
            }
        }
    }

    /** 把一份语言 JSON 里 {@code model.*.name} 的条目并入目标表（先到先得）。 */
    private static void mergeModelNames(String text, Map<String, String> out) {
        if (text == null || text.isEmpty() || out == null) {
            return;
        }
        try {
            com.google.gson.JsonElement root = com.google.gson.JsonParser.parseString(text);
            if (!root.isJsonObject()) {
                return;
            }
            for (Map.Entry<String, com.google.gson.JsonElement> e
                    : root.getAsJsonObject().entrySet()) {
                String key = e.getKey();
                if (key.startsWith("model.") && key.endsWith(".name")
                        && e.getValue().isJsonPrimitive()) {
                    out.putIfAbsent(key, e.getValue().getAsString());
                }
            }
        } catch (Throwable ignored) {
            // 非法 JSON：忽略该文件
        }
    }

    /**
     * 生成模型名的翻译键候选。
     *
     * <p><b>26.1 起 TLM 的内置基岩模型改用 {@code geckolib} 命名空间</b>，键形如
     * {@code model.geckolib.winefox.name}；1.20.1 / 1.21.1 则是
     * {@code model.touhou_little_maid.<id>.name}。这里把两种都列进候选。
     */
    static String[] modelNameKeys(String namespace, String path) {
        return new String[]{
                "model." + namespace + "." + path + ".name",
                "model." + path + ".name",
                "model.geckolib." + path + ".name",
                "model.touhou_little_maid." + path + ".name"
        };
    }

    /**
     * 剥掉 TLM 26.1 模型 ID 尾部的 UUID 哈希后缀。
     *
     * <p>26.1 的 {@code ProfileData.model_id} 形如
     * {@code geckolib:winefox_magical_b073380075f0d30ea8bbcde8566f825e}
     * （模型 ID + 下划线 + 32 位十六进制，用于区分个体变体），
     * 而语言键只到 {@code model.geckolib.winefox_magical.name}，
     * 不剥离就永远查不到中文名。
     */
    static String stripUuidSuffix(String modelId) {
        int underscore = modelId.lastIndexOf('_');
        if (underscore > 0 && modelId.length() - underscore - 1 == 32) {
            String tail = modelId.substring(underscore + 1);
            if (tail.matches("[0-9a-fA-F]{32}")) {
                return modelId.substring(0, underscore);
            }
        }
        return modelId;
    }
}
