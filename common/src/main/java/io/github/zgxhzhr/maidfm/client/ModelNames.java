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
 * <p><b>自定义模型包</b>（{@code tlm_custom_pack} 里的第三方 geckolib 模型）的名字写在
 * 资源包自带的 {@code maid_model.json} 里，<b>没有对应的翻译键</b>。这类模型只有客户端
 * {@code CustomPackLoader.MAID_MODELS} 认得，服务端的 {@code ServerMaidModels} 常常没有登记
 * （集成服务器上尤其明显）→ 服务端算出的 displayName 会退化成短 ID，界面上就出现
 * {@code zhiban_hanfu} 这类英文名。因此这里补一次「问客户端模型表」的兜底。
 */
public final class ModelNames {

    /** 客户端模型表反射句柄的懒加载缓存（渲染路径每帧都会调，不能每次反射查找）。 */
    private static boolean packLookupTried;
    private static Object packModels;
    private static java.lang.reflect.Method packGetInfo;

    /** 模型 ID → 可读名 的记忆表，避免同一行每帧重复反射 + 查表。 */
    private static final java.util.Map<String, String> PACK_NAME_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 所有资源包 {@code zh_cn.json} 里以 {@code model.} 开头、{@code .name} 结尾的键。
     *
     * <p>用途：文言（lzh）与繁体中文（zh_tw / zh_hk）下，翻译键缺失时 Minecraft 会回退到
     * {@code en_us}，模型名就会显示成英文（如 {@code Sta Bo}）。这几种语言要求仍显示中文，
     * 所以这里把各模组 {@code zh_cn} 里的模型名收集成表，作为这几种语言的兜底。
     *
     * <p>懒加载一次、结果缓存；反射调用资源管理器与 Gson（common 模块不能编译期依赖
     * 客户端类），任何一步失败都返回空表并保持原有行为。
     */
    private static java.util.Map<String, String> zhCnModelNames;
    private static boolean zhCnModelNamesTried;

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

    /** 依次查若干翻译键在「zh_cn 模型名表」里的中文值。 */
    private static String zhCnModelName(String... keys) {
        if (!zhCnModelNamesTried) {
            zhCnModelNamesTried = true;
            zhCnModelNames = buildZhCnModelNames();
        }
        if (zhCnModelNames == null || zhCnModelNames.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            String value = zhCnModelNames.get(key);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    /** 遍历资源管理器里所有 {@code assets/<ns>/lang/zh_cn.json}，收集模型名键。 */
    private static java.util.Map<String, String> buildZhCnModelNames() {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        try {
            Object mc = Class.forName("net.minecraft.client.Minecraft").getMethod("getInstance").invoke(null);
            if (mc == null) {
                return out;
            }
            Object resourceManager = mc.getClass().getMethod("getResourceManager").invoke(mc);
            if (resourceManager == null) {
                return out;
            }
            java.lang.reflect.Method listResources = null;
            for (java.lang.reflect.Method m : resourceManager.getClass().getMethods()) {
                if ("listResources".equals(m.getName()) && m.getParameterCount() == 2) {
                    listResources = m;
                    break;
                }
            }
            if (listResources == null) {
                return out;
            }
            java.util.function.Predicate<Object> filter = loc ->
                    loc != null && String.valueOf(loc).endsWith(":lang/zh_cn.json");
            Object listed = listResources.invoke(resourceManager, "lang", filter);
            if (!(listed instanceof java.util.Map<?, ?> map)) {
                return out;
            }
            for (Object resource : map.values()) {
                if (resource == null) {
                    continue;
                }
                java.io.InputStream in = null;
                try {
                    java.lang.reflect.Method open = null;
                    for (java.lang.reflect.Method m : resource.getClass().getMethods()) {
                        if ("open".equals(m.getName()) && m.getParameterCount() == 0) {
                            open = m;
                            break;
                        }
                    }
                    if (open == null) {
                        continue;
                    }
                    in = (java.io.InputStream) open.invoke(resource);
                    String text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    com.google.gson.JsonElement root = com.google.gson.JsonParser.parseString(text);
                    if (!root.isJsonObject()) {
                        continue;
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
                    // 单个语言文件解析失败不影响其它
                } finally {
                    if (in != null) {
                        try {
                            in.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // 反射或资源管理器不可用：返回已收集到的部分（可能为空）
        }
        return out;
    }

    /**
     * 把模型 ID 或翻译键转成可读文本。
     *
     * @param raw 原始值，可能是翻译键、{@code namespace:path} 模型 ID，或已是可读名
     * @return 尽量可读的显示名；{@code raw} 为空时返回空串
     */
    public static String display(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
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
     * <p>TLM 的 {@code IModelInfo.getName()} 常常直接返回**短名**（如 {@code motoori_kosuzu}），
     * 而真正的中文名是翻译键 {@code model.<namespace>.<path>.name} 的翻译结果。
     * 因此这里按 TLM 的键规则**从 modelId 反推翻译键**，翻译成功就用它；
     * 翻译键不存在时（自定义模型包）再查客户端模型表。
     *
     * @param modelId 形如 {@code touhou_little_maid:motoori_kosuzu}
     * @return 中文名（翻译或模型表命中时），否则退回短名
     */
    public static String displayModelId(String modelId) {
        if (modelId == null || modelId.isEmpty()) {
            return "";
        }
        int colon = modelId.indexOf(':');
        if (colon > 0 && colon + 1 < modelId.length()) {
            String namespace = modelId.substring(0, colon);
            String path = modelId.substring(colon + 1);
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
            // 文言 / 繁体中文下，翻译键会回退到 en_us（显示成英文模型名）。
            // 这两种语言必须仍显示中文，因此先用「所有资源包的 zh_cn 表」兜底。
            if (isZhLikeLanguage()) {
                String zh = zhCnModelName("model." + namespace + "." + path + ".name",
                        "model." + path + ".name");
                if (zh != null && !zh.isEmpty()) {
                    return zh;
                }
            }
            // 自定义模型包：名字在包内 maid_model.json 里，没有翻译键，只能问客户端模型表
            String packName = clientPackModelName(modelId, path);
            if (packName != null && !packName.isEmpty()) {
                return packName;
            }
            return path;
        }
        // 不带命名空间的裸模型 ID：同样先问客户端模型表，再退回原样
        String packName = clientPackModelName(modelId, modelId);
        if (packName != null && !packName.isEmpty()) {
            return packName;
        }
        return display(modelId);
    }

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
