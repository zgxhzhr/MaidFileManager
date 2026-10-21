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
