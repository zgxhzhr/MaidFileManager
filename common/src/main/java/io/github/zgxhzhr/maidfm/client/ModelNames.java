package io.github.zgxhzhr.maidfm.client;

import net.minecraft.network.chat.Component;

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
 * 因此这几门语言下改为优先查我们自己收集的「模型中文名表」，命中就用中文。
 *
 * <p>数据来源有两条（都要）：
 * <ol>
 *   <li>MC 资源管理器里各命名空间的 {@code lang/zh_cn.json}（模组内置模型名）；</li>
 *   <li>磁盘上 {@code <gameDir>/tlm_custom_pack/**} 的 {@code lang/zh_cn.json} 与
 *       {@code en_us.json}（第三方模型包）—— 这些包**不在** MC 资源管理器里，
 *       只能直接读盘。</li>
 * </ol>
 * 由两张表构成：{@code model.*.name → 中文} 与 {@code 英文名 → 中文}（后者用于备份列表，
 * 那里只有 TLM 备份 index 里存的英文名，没有模型 ID）。
 */
public final class ModelNames {

    /** 客户端模型表反射句柄的懒加载缓存（渲染路径每帧都会调，不能每次反射查找）。 */
    private static boolean packLookupTried;
    private static Object packModels;
    private static java.lang.reflect.Method packGetInfo;

    /** 模型 ID → 可读名 的记忆表，避免同一行每帧重复反射 + 查表。 */
    private static final java.util.Map<String, String> PACK_NAME_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 翻译键（{@code model.<ns>.<path>.name}）→ 中文名。 */
    private static java.util.Map<String, String> zhByKey;
    /** 英文模型名 → 中文名（备份列表只有名字、没有模型 ID 时用）。 */
    private static java.util.Map<String, String> zhByEn;
    private static boolean modelNamesLoaded;

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
        // 文言/繁体：先用「英文模型名 → 中文名」反查（备份列表只有名字，没有模型 ID）
        if (isZhLikeLanguage()) {
            ensureModelNameTables();
            String zh = zhByEn == null ? null : zhByEn.get(raw);
            if (zh != null && !zh.isEmpty()) {
                return zh;
            }
        }
        // 翻译键形态：含 '.' 且不含命名空间分隔符（模型 ID 用 ':' 分隔）
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
        return colon >= 0 && colon + 1 < raw.length() ? raw.substring(colon + 1) : raw;
    }

    private ModelNames() {
    }

    /**
     * 从模型 ID（{@code namespace:path}）解析可读名。
     *
     * <p><b>顺序很重要</b>：文言/繁体下必须先查中文表。否则 {@code Component.translatable(key)}
     * 会回退到 en_us 返回英文名，而「返回值 ≠ 键名」会被误判成翻译成功，直接就把英文名返回了
     *（这正是此前文言界面显示英文的原因）。
     *
     * @param modelId 形如 {@code touhou_little_maid:motoori_kosuzu}
     * @return 中文名（命中中文表或翻译成功时），否则退回短名
     */
    public static String displayModelId(String modelId) {
        if (modelId == null || modelId.isEmpty()) {
            return "";
        }
        int colon = modelId.indexOf(':');
        if (colon > 0 && colon + 1 < modelId.length()) {
            String namespace = modelId.substring(0, colon);
            String path = modelId.substring(colon + 1);
            // ① 文言/繁体：先查中文模型名表（必须在翻译之前）
            if (isZhLikeLanguage()) {
                ensureModelNameTables();
                String zh = lookupZh("model." + namespace + "." + path + ".name", "model." + path + ".name");
                if (zh != null && !zh.isEmpty()) {
                    return zh;
                }
            }
            // ② 当前语言翻译
            for (String key : new String[]{
                    "model." + namespace + "." + path + ".name",
                    "model." + path + ".name"
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
            return path;
        }
        // 不带命名空间的裸模型 ID：同样先查中文表，再问客户端模型表
        if (isZhLikeLanguage()) {
            ensureModelNameTables();
            String zh = lookupZh("model." + modelId + ".name");
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
            Object mc = Class.forName("net.minecraft.client.Minecraft").getMethod("getInstance").invoke(null);
            if (mc == null) {
                return false;
            }
            Object languageManager = mc.getClass().getMethod("getLanguageManager").invoke(mc);
            if (languageManager == null) {
                return false;
            }
            Object selected = languageManager.getClass().getMethod("getSelected").invoke(languageManager);
            String code = String.valueOf(selected);
            return "lzh".equals(code) || code.startsWith("zh_");
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ================= 模型中文名表（懒加载一次） =================

    private static void ensureModelNameTables() {
        if (modelNamesLoaded) {
            return;
        }
        modelNamesLoaded = true;
        java.util.Map<String, String> zh = new java.util.HashMap<>();
        java.util.Map<String, String> en = new java.util.HashMap<>();
        collectFromResourceManager(zh, en);
        collectFromModelPackDir(zh, en);
        zhByKey = zh;
        java.util.Map<String, String> byEn = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, String> e : zh.entrySet()) {
            String enName = en.get(e.getKey());
            String zhName = e.getValue();
            if (enName != null && !enName.isEmpty() && !enName.equals(zhName)) {
                byEn.putIfAbsent(enName, zhName);
            }
        }
        zhByEn = byEn;
    }

    /** 来源一：MC 资源管理器里所有命名空间的 {@code lang/zh_cn.json} 与 {@code en_us.json}。 */
    private static void collectFromResourceManager(java.util.Map<String, String> zh,
                                                   java.util.Map<String, String> en) {
        try {
            Object mc = Class.forName("net.minecraft.client.Minecraft").getMethod("getInstance").invoke(null);
            if (mc == null) {
                return;
            }
            Object resourceManager = mc.getClass().getMethod("getResourceManager").invoke(mc);
            if (resourceManager == null) {
                return;
            }
            java.lang.reflect.Method listResources = null;
            for (java.lang.reflect.Method m : resourceManager.getClass().getMethods()) {
                if ("listResources".equals(m.getName()) && m.getParameterCount() == 2) {
                    listResources = m;
                    break;
                }
            }
            if (listResources == null) {
                return;
            }
            collectLangFiles(resourceManager, listResources, "zh_cn.json", zh);
            collectLangFiles(resourceManager, listResources, "en_us.json", en);
        } catch (Throwable ignored) {
            // 资源管理器不可用：跳过（磁盘模型包来源仍会执行）
        }
    }

    @SuppressWarnings("unchecked")
    private static void collectLangFiles(Object resourceManager, java.lang.reflect.Method listResources,
                                         String fileName, java.util.Map<String, String> out) {
        try {
            java.util.function.Predicate<Object> filter = loc ->
                    loc != null && String.valueOf(loc).endsWith(":lang/" + fileName);
            Object listed = listResources.invoke(resourceManager, "lang", filter);
            if (!(listed instanceof java.util.Map<?, ?> map)) {
                return;
            }
            for (Object resource : map.values()) {
                if (resource == null) {
                    continue;
                }
                try {
                    mergeModelNames(readResource(resource), out);
                } catch (Throwable ignored) {
                    // 单个语言文件失败不影响其它
                }
            }
        } catch (Throwable ignored) {
            // 该语言来源不可用
        }
    }

    /** 反射调用 {@code Resource#open()} 读出文本。 */
    private static String readResource(Object resource) throws Exception {
        java.lang.reflect.Method open = null;
        for (java.lang.reflect.Method m : resource.getClass().getMethods()) {
            if ("open".equals(m.getName()) && m.getParameterCount() == 0) {
                open = m;
                break;
            }
        }
        if (open == null) {
            return null;
        }
        try (java.io.InputStream in = (java.io.InputStream) open.invoke(resource)) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /**
     * 来源二：磁盘上的第三方模型包 —— {@code <gameDir>/tlm_custom_pack/**}{@code /lang/}
     * 下的 {@code zh_cn.json} 与 {@code en_us.json}。
     *
     * <p>这些包**不在** MC 资源管理器里（TLM 自己从磁盘加载），所以只能直接读文件。
     */
    private static void collectFromModelPackDir(java.util.Map<String, String> zh,
                                               java.util.Map<String, String> en) {
        try {
            java.nio.file.Path gameDir =
                    io.github.zgxhzhr.maidfm.platform.Services.PLATFORM.get().getGameDir();
            if (gameDir == null) {
                return;
            }
            java.nio.file.Path packs = gameDir.resolve("tlm_custom_pack");
            if (!java.nio.file.Files.isDirectory(packs)) {
                return;
            }
            try (java.util.stream.Stream<java.nio.file.Path> walk =
                         java.nio.file.Files.walk(packs, 8)) {
                walk.filter(java.nio.file.Files::isRegularFile)
                        .filter(p -> {
                            java.nio.file.Path name = p.getFileName();
                            if (name == null) {
                                return false;
                            }
                            String n = name.toString();
                            return "zh_cn.json".equals(n) || "en_us.json".equals(n);
                        })
                        .limit(4000L)
                        .forEach(p -> {
                            try {
                                String text = new String(java.nio.file.Files.readAllBytes(p),
                                        java.nio.charset.StandardCharsets.UTF_8);
                                String n = p.getFileName().toString();
                                mergeModelNames(text, "zh_cn.json".equals(n) ? zh : en);
                            } catch (Throwable ignored) {
                                // 单个文件失败不影响其它
                            }
                        });
            }
        } catch (Throwable ignored) {
            // 目录不存在或无权限：跳过
        }
    }

    /** 把一份语言 JSON 里 {@code model.*.name} 的条目并入目标表（先到先得）。 */
    private static void mergeModelNames(String text, java.util.Map<String, String> out) {
        if (text == null || text.isEmpty() || out == null) {
            return;
        }
        try {
            com.google.gson.JsonElement root = com.google.gson.JsonParser.parseString(text);
            if (!root.isJsonObject()) {
                return;
            }
            for (java.util.Map.Entry<String, com.google.gson.JsonElement> e
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
     * <p>用反射而不是直接引用：{@code ModelNames} 在 common 模块，专用服务端环境没有该类，
     * 编译期引用会让服务端加载失败。反射失败一律返回 {@code null}（静默降级）。
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
                // 未装 TLM / 类被裁剪 / 字段改名：保持 null，后续全部走短名
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
