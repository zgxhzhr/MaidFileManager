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
        if (ModelNameTable.isZhLike()) {
            ModelNameTable.ensure();
            String zh = ModelNameTable.lookupKey(raw);
            if (zh == null) {
                zh = ModelNameTable.lookupEn(raw);
            }
            if (zh != null && !zh.isEmpty()) {
                return zh;
            }
        }
        // 纯模型 path（如 winefox_new_year，既无命名空间也无 '.'）：档案界面的 displayName
        // 传的就是这种形态，旧逻辑会原样返回裸 ID。按 26.1 的 geckolib 与旧版
        // touhou_little_maid 两个命名空间各查一次。
        if (raw.indexOf(':') < 0 && raw.indexOf('.') < 0) {
            if (ModelNameTable.isZhLike()) {
                ModelNameTable.ensure();
                String zh = ModelNameTable.lookupZh(ModelNameTable.modelNameKeys("geckolib", raw));
                if (zh == null || zh.isEmpty()) {
                    zh = ModelNameTable.lookupZh(ModelNameTable.modelNameKeys("touhou_little_maid", raw));
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
            out.putAll(ModelPackNames.cacheSnapshot());
        } catch (Throwable t) {
            Constants.LOG.debug("[maid_file_manager] 复制包名缓存失败（已忽略，继续构建其余部分）: {}", t.toString());
        }
        try {
            ModelNameTable.ensure();
            java.util.Map<String, String> zhKeyMap = ModelNameTable.zhByKeyMap();
            if (zhKeyMap != null && !zhKeyMap.isEmpty()) {
                for (java.util.Map.Entry<String, String> e : zhKeyMap.entrySet()) {
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
     * 从模型 ID（{@code namespace:path}）解析可读名。
     *
     * <p><b>顺序很重要</b>：文言/繁体下必须先查中文表。否则 {@code Component.translatable(key)}
     * 会回退到 en_us 返回英文名，而「返回值 ≠ 键名」会被误判成翻译成功，直接把英文名返回
     *（这正是文言界面显示英文的原因）。
     *
     * @param modelId 形如 {@code touhou_little_maid:motoori_kosuzu}
     * @return 中文名（命中中文表或翻译成功时），否则退回短名
     */
    public static String displayModelId(String modelId) {
        if (modelId == null || modelId.isEmpty()) {
            return "";
        }
        modelId = ModelNameTable.stripUuidSuffix(modelId);
        int colon = modelId.indexOf(':');
        if (colon > 0 && colon + 1 < modelId.length()) {
            String namespace = modelId.substring(0, colon);
            String path = modelId.substring(colon + 1);
            // ① 文言/繁体：先查中文模型名表（必须在翻译之前）
            if (ModelNameTable.isZhLike()) {
                ModelNameTable.ensure();
                String zh = ModelNameTable.lookupZh(ModelNameTable.modelNameKeys(namespace, path));
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
            String packName = ModelPackNames.clientPackModelName(modelId, path);
            if (packName != null && !packName.isEmpty()) {
                return packName;
            }
            return prettifyModelPath(path);
        }
        // 不带命名空间的裸模型 ID：同样先查中文表，再问客户端模型表
        if (ModelNameTable.isZhLike()) {
            ModelNameTable.ensure();
            String zh = ModelNameTable.lookupZh("model." + modelId + ".name",
                    "model.geckolib." + modelId + ".name",
                    "model.touhou_little_maid." + modelId + ".name");
            if (zh != null && !zh.isEmpty()) {
                return zh;
            }
        }
        String packName = ModelPackNames.clientPackModelName(modelId, modelId);
        if (packName != null && !packName.isEmpty()) {
            return packName;
        }
        return display(modelId);
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
        int before = ModelNameTable.size();
        ModelNameTable.invalidate();
        try {
            ModelNameTable.ensure();
        } catch (Throwable t) {
            io.github.zgxhzhr.maidfm.Constants.LOG.warn(
                    "[maid_file_manager] 重新扫描模型名表失败（沿用旧表）: {}", t.toString());
            return;
        }
        int after = ModelNameTable.size();
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

    // ================= TLM 客户端模型表（自定义包英文名兜底） =================


    /**
     * 导出文件用的展示名（含 YSM 换模）。
     *
     * <p><b>为什么不能只看 modelId</b>：女仆被 YSM 换模后，TLM 的 {@code modelId} 仍是底层
     * geckolib 模型 ID，而 {@code .maid} 里的 {@code displayName} 是服务端算好的
     * ——见 {@code MaidTransferService.doSerializeMaid}，那里优先取 YSM 模型名。
     * 客户端若再用 {@code displayModelId(modelId)} 反查，就会把 YSM 名覆盖回旧的车万女仆包
     * 模型名，表现为「界面显示 Bn_月雪宫子：常服，导出文件名却是尔子田田里乃」。
     *
     * <p>所以规则是：**先用 data 里记的 displayName，它为空才回退到按 modelId 反查**。
     */
    public static String exportDisplayName(io.github.zgxhzhr.maidfm.data.MaidFileData data) {
        if (data == null) {
            return "unknown";
        }
        String dn = data.getDisplayName();
        if (dn != null && !dn.isEmpty()) {
            return dn;
        }
        String modelId = data.getModelId();
        return (modelId != null && !modelId.isEmpty()) ? displayModelId(modelId) : "unknown";
    }
}
