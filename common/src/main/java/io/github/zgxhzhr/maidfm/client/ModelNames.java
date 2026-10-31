package io.github.zgxhzhr.maidfm.client;

import io.github.zgxhzhr.maidfm.Constants;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型/女仆名的显示转换。
 *
 * <p>TLM 与 GeckoLib 的模型信息里，名字字段常常是**翻译键**（如
 * {@code model.touhou_little_maid.kirisame_marisa.name}），而不是已翻译的文本。
 * 直接显示就会在界面上露出键名。这里统一做一次「能翻译就翻译，否则退化为短名」。
 *
 * <p>为什么不在服务端做：翻译表在客户端语言文件里，服务端拿不到玩家语言设置。
 *
 * <p><b>文言（lzh）与繁体中文（zh_tw / zh_hk）必须仍显示中文名。</b>
 * 模型中文名来自翻译键，而 Minecraft 的翻译回退链是「当前语言 → en_us」——
 * 文言与繁体缺这些键时会回退到 en_us，于是显示成英文模型名（如 {@code Magical Wine Fox}）。
 * 因此这几门语言下改为优先查我们自己收集的「模型中文名表」。
 *
 * <p>数据来源两条：
 * <ol>
 *   <li>MC 资源管理器里各命名空间的 {@code lang/zh_cn.json}（模组内置模型名）；</li>
 *   <li>磁盘上 {@code <gameDir>/tlm_custom_pack/**}{@code /lang/} 下的 {@code zh_cn.json}
 *       与 {@code en_us.json}（第三方模型包）—— 这些包**不在** MC 资源管理器里，只能读盘。</li>
 * </ol>
 * 由两张表构成：{@code model.*.name → 中文} 与 {@code 英文名 → 中文}（后者用于备份列表，
 * 那里只有 TLM 备份 index 里存的英文名，没有模型 ID）。
 *
 * <p>本类位于 {@code client} 包，只在客户端代码路径被引用，服务端不会加载它，
 * 因此这里可以直接引用 {@code net.minecraft.client.*}（TLM 的类仍用反射，因为它是可选依赖）。
 */
public final class ModelNames {

    /** 翻译键（{@code model.<ns>.<path>.name}）→ 中文名。 */
    private static Map<String, String> zhByKey;
    /** 英文模型名 → 中文名（备份列表只有名字、没有模型 ID 时用）。 */
    private static Map<String, String> zhByEn;
    private static boolean modelNamesLoaded;

    /** TLM 客户端模型表反射句柄的懒加载缓存（渲染路径每帧都会调，不能每次反射查找）。 */
    private static boolean packLookupTried;
    private static Object packModels;
    private static java.lang.reflect.Method packGetInfo;

    /** 模型 ID → 可读名 的记忆表，避免同一行每帧重复反射 + 查表。 */
    private static final Map<String, String> PACK_NAME_CACHE = new ConcurrentHashMap<>();

    /**
     * 把模型 ID 或翻译键转成可读文本。
     *
     * @param raw 原始值，可能是翻译键、{@code namespace:path} 模型 ID、英文模型名，或已是可读名
     * @return 尽量可读的显示名；{@code raw} 为空时返回空串
     */
    public static String display(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        // 文言/繁体：备份 index.dat 的 Name 字段经 extractText 后是**翻译键**
        // （model.<ns>.<path>.name），也可能已经是英文模型名，两种都要查中文表。
        // 必须放在「当前语言翻译」之前：否则文言下翻译键会回退 en_us 拿到英文名，
        // 而「返回值 != 键名」会被判成翻译成功，直接把英文名返回（此前备份列表显示
        // Magical Wine Fox 就是这个原因）。
        if (isZhLikeLanguage()) {
            ensureModelNameTables();
            String zh = zhByKey == null ? null : zhByKey.get(raw);
            if (zh == null) {
                zh = zhByEn == null ? null : zhByEn.get(raw);
            }
            if (zh != null && !zh.isEmpty()) {
                return zh;
            }
        }
        // 纯模型 path（如 winefox_new_year，既无命名空间也无 '.'）：档案界面的 displayName
        // 传的就是这种形态，旧逻辑会原样返回裸 ID。按 26.1 的 geckolib 与旧版
        // touhou_little_maid 两个命名空间各查一次。
        if (raw.indexOf(':') < 0 && raw.indexOf('.') < 0) {
            if (isZhLikeLanguage()) {
                ensureModelNameTables();
                String zh = lookupZh(modelNameKeys("geckolib", raw));
                if (zh == null || zh.isEmpty()) {
                    zh = lookupZh(modelNameKeys("touhou_little_maid", raw));
                }
                if (zh != null && !zh.isEmpty()) {
                    return zh;
                }
            }
            for (String key : new String[]{
                    "model.geckolib." + raw + ".name",
                    "model.touhou_little_maid." + raw + ".name"
            }) {
                try {
                    String translated = Component.translatable(key).getString();
                    if (translated != null && !translated.isEmpty() && !translated.equals(key)) {
                        return translated;
                    }
                } catch (Throwable ignored) {
                    // 继续下一个候选
                }
            }
        }        // 翻译键形态：含 '.' 且不含命名空间分隔符（模型 ID 用 ':' 分隔）
        if (raw.indexOf('.') >= 0 && raw.indexOf(':') < 0) {
            try {
                String translated = Component.translatable(raw).getString();
                if (translated != null && !translated.isEmpty() && !translated.equals(raw)) {
                    return translated;
                }
            } catch (Throwable ignored) {
                // 翻译失败继续走短名
            }
        }
        int colon = raw.indexOf(':');
        // 本地没有该模型资源时的兜底：把 path 美化成可读文本，避免一堆同名占位
        return prettifyModelPath(colon >= 0 && colon + 1 < raw.length() ? raw.substring(colon + 1) : raw);
    }


    /**
     * 把 model_id 的 path 部分美化成可读文本，作为「本地没有该模型资源」时的兜底显示。
     *
     * <p>典型场景：服务端/别的客户端没装某个玩家的私有模型包，翻译键与模型表都查不到，
     * 只能拿到 {@code namespace:path}。直接把 {@code my_private_maid} 摆出来很难看，
     * 这里转成 {@code My Private Maid}，至少能区分「这是谁的模型」而不至于全变成同一个占位名。
     */
    public static String prettifyModelPath(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        String s = path.replace('_', ' ').trim();
        StringBuilder sb = new StringBuilder(s.length());
        boolean upper = true;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == ' ') {
                upper = true;
                sb.append(ch);
            } else if (upper) {
                sb.append(Character.toUpperCase(ch));
                upper = false;
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /**
     * 导出「模型 ID → 显示名」映射，供客户端上报给服务端汇总。
     *
     * <p>两个来源：
     * <ul>
     *   <li>{@code PACK_NAME_CACHE}：自定义模型包内 maid_model.json 读到的名字，键本来就是 modelId</li>
     *   <li>{@code zhByKey}：语言文件里的 {@code model.<ns>.<path>.name} 翻译键，反推出 modelId</li>
     * </ul>
     *
     * <p>只上报「确实存在」的条目，解析不出来的不进表——宁缺毋滥，
     * 避免把回退出来的 path 当成真名污染服务端表。
     */
    public static java.util.Map<String, String> exportModelNameMap() {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        try {
            out.putAll(PACK_NAME_CACHE);
        } catch (Throwable t) {
            Constants.LOG.debug("[maid_file_manager] 复制包名缓存失败（已忽略，继续构建其余部分）: {}", t.toString());
        }
        try {
            ensureModelNameTables();
            if (zhByKey != null && !zhByKey.isEmpty()) {
                for (java.util.Map.Entry<String, String> e : zhByKey.entrySet()) {
                    String key = e.getKey();
                    String value = e.getValue();
                    if (key == null || value == null || value.isEmpty()) {
                        continue;
                    }
                    if (!key.startsWith("model.") || !key.endsWith(".name")) {
                        continue;
                    }
                    String mid = key.substring("model.".length(), key.length() - ".name".length());
                    if (mid.isEmpty()) {
                        continue;
                    }
                    int dot = mid.indexOf('.');
                    String modelId;
                    if (dot > 0 && dot + 1 < mid.length()) {
                        modelId = mid.substring(0, dot) + ":" + mid.substring(dot + 1);
                    } else {
                        // 旧式 model.<id>.name：默认归到 TLM 命名空间
                        modelId = "touhou_little_maid:" + mid;
                    }
                    out.putIfAbsent(modelId, value);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private ModelNames() {
    }

    /**
     * 生成模型名的翻译键候选。
     *
     * <p><b>26.1 起 TLM 的内置基岩模型改用 {@code geckolib} 命名空间</b>，键形如
     * {@code model.geckolib.winefox.name}；1.20.1 / 1.21.1 则是
     * {@code model.touhou_little_maid.<id>.name}。这里把两种都列进候选。
     */
    private static String[] modelNameKeys(String namespace, String path) {
        return new String[]{
                "model." + namespace + "." + path + ".name",
                "model." + path + ".name",
                "model.geckolib." + path + ".name",
                "model.touhou_little_maid." + path + ".name"
        };
    }

    /**
     * 从模型 ID（{@code namespace:path}）解析可读名。
     *
     * <p><b>顺序很重要</b>：文言/繁体下必须先查中文表。否则 {@code Component.translatable(key)}
     * 会回退到 en_us 返回英文名，而「返回值 ≠ 键名」会被误判成翻译成功，直接把英文名返回
     *（这正是文言界面显示英文的原因）。
     *
     * @param modelId 形如 {@code touhou_little_maid:motoori_kosuzu}
     * @return 中文名（命中中文表或翻译成功时），否则退回短名
     */
    /**
     * 剥掉 TLM 26.1 模型 ID 尾部的 UUID 哈希后缀。
     *
     * <p>26.1 的 {@code ProfileData.model_id} 形如
     * {@code geckolib:winefox_magical_b073380075f0d30ea8bbcde8566f825e}
     * （模型 ID + 下划线 + 32 位十六进制，用于区分个体变体），
     * 而语言键只到 {@code model.geckolib.winefox_magical.name}，
     * 不剥离就永远查不到中文名。
     */
    private static String stripUuidSuffix(String modelId) {
        int underscore = modelId.lastIndexOf('_');
        if (underscore > 0 && modelId.length() - underscore - 1 == 32) {
            String tail = modelId.substring(underscore + 1);
            if (tail.matches("[0-9a-fA-F]{32}")) {
                return modelId.substring(0, underscore);
            }
        }
        return modelId;
    }
    public static String displayModelId(String modelId) {
        if (modelId == null || modelId.isEmpty()) {
            return "";
        }
        modelId = stripUuidSuffix(modelId);
        int colon = modelId.indexOf(':');
        if (colon > 0 && colon + 1 < modelId.length()) {
            String namespace = modelId.substring(0, colon);
            String path = modelId.substring(colon + 1);
            // ① 文言/繁体：先查中文模型名表（必须在翻译之前）
            if (isZhLikeLanguage()) {
                ensureModelNameTables();
                String zh = lookupZh(modelNameKeys(namespace, path));
                if (zh != null && !zh.isEmpty()) {
                    return zh;
                }
            }
            // ② 当前语言翻译
            for (String key : new String[]{
                    "model." + namespace + "." + path + ".name",
                    "model." + path + ".name",
                    "model.geckolib." + path + ".name",
                    "model.touhou_little_maid." + path + ".name"
            }) {
                try {
                    String translated = Component.translatable(key).getString();
                    if (translated != null && !translated.isEmpty() && !translated.equals(key)) {
                        return translated;
                    }
                } catch (Throwable ignored) {
                    // 试下一个键
                }
            }
            // ③ 自定义模型包：名字在包内 maid_model.json 里，没有翻译键，只能问客户端模型表
            String packName = clientPackModelName(modelId, path);
            if (packName != null && !packName.isEmpty()) {
                return packName;
            }
            return prettifyModelPath(path);
        }
        // 不带命名空间的裸模型 ID：同样先查中文表，再问客户端模型表
        if (isZhLikeLanguage()) {
            ensureModelNameTables();
            String zh = lookupZh("model." + modelId + ".name",
                    "model.geckolib." + modelId + ".name",
                    "model.touhou_little_maid." + modelId + ".name");
            if (zh != null && !zh.isEmpty()) {
                return zh;
            }
        }
        String packName = clientPackModelName(modelId, modelId);
        if (packName != null && !packName.isEmpty()) {
            return packName;
        }
        return display(modelId);
    }

    /** 依次查若干键在中文模型名表里的值。 */
    private static String lookupZh(String... keys) {
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

    // ================= 模型中文名表（懒加载一次） =================


    /**
     * 强制重新扫描模型名表。
     *
     * <p>玩家可能在游戏运行中下载新的模型包（TLM 的模型包市场），
     * 而 {@link #ensureModelNameTables()} 只会加载一次 —— 不重新扫就永远看不到新包的中文名。
     * 打开备份 / 档案 / 导出界面时调用一次，代价是重读几个 zip（通常几十毫秒）。
     */
    public static void reload() {
        int before = zhByKey == null ? 0 : zhByKey.size();
        modelNamesLoaded = false;
        try {
            ensureModelNameTables();
        } catch (Throwable t) {
            io.github.zgxhzhr.maidfm.Constants.LOG.warn(
                    "[maid_file_manager] 重新扫描模型名表失败（沿用旧表）: {}", t.toString());
            return;
        }
        int after = zhByKey == null ? 0 : zhByKey.size();
        if (after > before) {
            // 扫到了新模型名（玩家在游戏运行中下载了新模型包）→ 补报给服务端，
            // 否则服务端解析备份列表时仍然拿不到这些名字
            io.github.zgxhzhr.maidfm.Constants.LOG.info(
                    "[maid_file_manager] 模型名表刷新：{} -> {} 条，补报服务端", before, after);
            try {
                java.util.Map<String, String> names = exportModelNameMap();
                if (!names.isEmpty()) {
                    java.util.List<String> pairs = new java.util.ArrayList<>(names.size() * 2);
                    for (java.util.Map.Entry<String, String> e : names.entrySet()) {
                        pairs.add(e.getKey());
                        pairs.add(e.getValue());
                    }
                    io.github.zgxhzhr.maidfm.network.IMaidFileNetwork net =
                            io.github.zgxhzhr.maidfm.network.IMaidFileNetwork.Holder.get();
                    if (net != null) {
                        net.sendModelNames(pairs);
                    }
                }
            } catch (Throwable ignored) {
                // 补报失败不影响本地解析
            }
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

    // ================= TLM 客户端模型表（自定义包英文名兜底） =================

    /**
     * 查 TLM 客户端模型表（{@code CustomPackLoader.MAID_MODELS}）里该模型的名字。
     *
     * <p>TLM 是可选依赖，编译期不能引用，因此用反射；反射失败一律返回 {@code null}（静默降级）。
     *
     * @param candidates 依次尝试的模型 ID 形态（带命名空间 / 裸短名）
     */
    private static String clientPackModelName(String... candidates) {
        for (String candidate : candidates) {
            if (candidate == null || candidate.isEmpty()) {
                continue;
            }
            String cached = PACK_NAME_CACHE.get(candidate);
            if (cached != null) {
                if (!cached.isEmpty()) {
                    return cached;
                }
                continue;
            }
            String resolved = lookupPackName(candidate);
            PACK_NAME_CACHE.put(candidate, resolved == null ? "" : resolved);
            if (resolved != null && !resolved.isEmpty()) {
                return resolved;
            }
        }
        return null;
    }

    /** 单次反射查询（结果由 {@link #clientPackModelName} 记忆）。 */
    private static String lookupPackName(String modelId) {
        if (!packLookupTried) {
            packLookupTried = true;
            try {
                Class<?> loader = Class.forName(
                        "com.github.tartaricacid.touhoulittlemaid.client.resource.CustomPackLoader");
                packModels = loader.getField("MAID_MODELS").get(null);
                if (packModels != null) {
                    packGetInfo = packModels.getClass().getMethod("getInfo", String.class);
                }
            } catch (Throwable ignored) {
                packModels = null;
                packGetInfo = null;
            }
        }
        if (packModels == null || packGetInfo == null) {
            return null;
        }
        try {
            Object opt = packGetInfo.invoke(packModels, modelId);
            if (!(opt instanceof java.util.Optional<?> optional) || optional.isEmpty()) {
                return null;
            }
            Object info = optional.get();
            Object rawName = info.getClass().getMethod("getName").invoke(info);
            return readable(rawName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 把模型表里的名字字段转成可读文本。
     *
     * <p>TLM 各版本这里返回三种形态：{@code {key}} 花括号包着的翻译键、裸翻译键
     * {@code model.xxx.name}、以及已经可读的中文名。前两种翻译成功才用，翻译不出就放弃
     *（返回 {@code null}），交给调用方退回短名 —— 绝不把翻译键直接显示出来。
     */
    private static String readable(Object rawName) {
        if (!(rawName instanceof String s) || s.isEmpty()) {
            return null;
        }
        String key = s;
        if (key.startsWith("{") && key.endsWith("}") && key.length() > 2) {
            key = key.substring(1, key.length() - 1);
        }
        boolean looksLikeKey = key.indexOf('.') >= 0 && key.indexOf(':') < 0;
        if (looksLikeKey) {
            try {
                String translated = Component.translatable(key).getString();
                if (translated != null && !translated.isEmpty() && !translated.equals(key)) {
                    return translated;
                }
            } catch (Throwable ignored) {
                // 翻译失败
            }
            return null;
        }
        return s;
    }
}
