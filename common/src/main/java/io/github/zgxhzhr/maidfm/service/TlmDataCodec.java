package io.github.zgxhzhr.maidfm.service;

import net.minecraft.nbt.CompoundTag;

/**
 * TLM 实体数据的抽取规则（导出/导入两侧共用的键集合与裁剪逻辑）。
 *
 * <p>从 {@code MaidTransferService} 抽出的纯 NBT 转换。它决定的是<b>哪些键从实体 NBT 里
 * 抽出来单独处理、哪些留给 TLM 自己的 {@code readAdditionalSaveData} 原样还原</b>。
 *
 * <p>这个划分直接决定数据是否丢失，历史上出过两次真实事故：
 * <ol>
 *   <li>早期「不分版本一律抽取」，导致同版本导入时 {@code MaidSubConfig}（拾取/开门/爬墙等开关）、
 *       {@code MaidGameSkillData}、{@code MaidTaskDataMaps}、{@code YsmRoamingVars} 等也被永久丢弃，
 *       只打一条 WARN 玩家看不见。</li>
 *   <li>26.1 起饰品容器改为 Transfer API（{@code ItemStacksResourceHandler}），其 NBT 格式由 TLM 决定。
 *       此前仍然一律抽取 {@code MaidBaubleInventory}、再用反射 {@code set()} 回填，
 *       在 26.1 因接口差异失败 → <b>饰品丢失</b>。</li>
 * </ol>
 *
 * <p>现在的规则分成三档：
 * <ul>
 *   <li><b>物品容器</b>：1.20.1/1.21.1 始终抽取（饰品要按「声明制」逐件裁剪，不能交给 TLM 全量还原）。
 *       26.1+ 走 Transfer API 时，同版本 + 允许携带则<b>不抽取</b>，交给 TLM 自己反序列化。</li>
 *   <li><b>复杂容器</b>：仅在<b>跨版本</b>时抽取（结构与目标端可能不一致）。</li>
 *   <li><b>简单值</b>：永不抽取，留在 NBT 里由 TLM 还原（{@code restoreTlmData} 另行处理）。</li>
 * </ul>
 */
public final class TlmDataCodec {

    /** 始终抽取的物品容器（20 版与 26.1 的「跨版本/不带饰品」分支共用） */
    private static final String[] ALWAYS_EXTRACT_KEYS = {
            "MaidBaubleInventory", "MaidInventory", "MaidHideInventory", "MaidTaskInventory",
    };

    /**
     * 26.1+ Transfer API 下「同版本 + 允许携带」时不抽取的清单：只排除 {@code MaidBaubleInventory}。
     *
     * <p>它必须留在 NBT 里让 TLM 的 {@code readAdditionalSaveData} 用 Transfer API 反序列化；
     * 抽出来再用反射回填在 26.1 会失败。
     */
    private static final String[] TRANSFER_API_SAFE_KEYS = {
            "MaidInventory", "MaidHideInventory", "MaidTaskInventory",
    };

    /**
     * 只在<b>跨版本</b>时抽取：结构与目标端可能不一致的复杂容器。
     *
     * <p>同版本时全部保留在 NBT 里交给 TLM 自己的 {@code readAdditionalSaveData} 原样还原。
     */
    private static final String[] CROSS_VERSION_ONLY_KEYS = {
            "MaidGameSkillData", "MaidTaskDataMaps",
            "MaidAIChat", "MaidHistoryChat", "MaidHistorySummary", "MaidLastChatTokenUsage",
            "MaidConfig", "MaidSubConfig", "MaidWorldData",
            "MaidBackpackData", "MaidTask",
            "MaidGameRecord", "MaidKillRecord",
            "MaidSchedulePos", "YsmRoamingVars", "Brain",
    };

    private TlmDataCodec() {
    }

    /**
     * 从实体 NBT 中抽出需要单独处理的 TLM 数据（20 版 / 21 版语义：物品容器始终抽取）。
     *
     * <p><b>会修改入参</b>：抽出的键从 {@code tag} 里移除，剩下的交给 TLM 加载。
     *
     * @param tag         实体 NBT（会被就地裁剪）
     * @param sameVersion 来源与目标版本是否相同；决定是否执行第二档抽取
     * @return 抽出的部分（可能为空 compound）
     */
    public static CompoundTag extract(CompoundTag tag, boolean sameVersion) {
        return extract(tag, sameVersion, true, false);
    }

    /**
     * 从实体 NBT 中抽出需要单独处理的 TLM 数据。
     *
     * <p><b>会修改入参</b>：抽出的键从 {@code tag} 里移除，剩下的交给 TLM 加载。
     *
     * @param tag                   实体 NBT（会被就地裁剪）
     * @param sameVersion           来源与目标版本是否相同；决定是否执行第二档抽取
     * @param keepBaubles           玩家/服务端是否允许携带饰品
     * @param baubleUsesTransferApi 该平台（26.1+）的饰品容器是否走 Transfer API。为 {@code true}
     *                              且同版本且允许携带时，{@code MaidBaubleInventory} 不抽取，
     *                              交给 TLM 自己反序列化——抽出来再反射回填在 26.1 会失败。
     * @return 抽出的部分（可能为空 compound）
     */
    public static CompoundTag extract(CompoundTag tag, boolean sameVersion, boolean keepBaubles,
                                      boolean baubleUsesTransferApi) {
        CompoundTag extracted = new CompoundTag();
        String[] primary = (baubleUsesTransferApi && sameVersion && keepBaubles)
                ? TRANSFER_API_SAFE_KEYS
                : ALWAYS_EXTRACT_KEYS;
        for (String key : primary) {
            if (tag.contains(key)) {
                extracted.put(key, tag.get(key).copy());
                tag.remove(key);
            }
        }
        if (!sameVersion) {
            for (String key : CROSS_VERSION_ONLY_KEYS) {
                if (tag.contains(key)) {
                    extracted.put(key, tag.get(key).copy());
                    tag.remove(key);
                }
            }
        }
        return extracted;
    }

    /** 始终抽取的物品容器键（只读副本，供测试与诊断使用）。 */
    public static String[] alwaysExtractKeys() {
        return ALWAYS_EXTRACT_KEYS.clone();
    }

    /** 26.1+ Transfer API 下「同版本 + 允许携带」时的抽取清单（只读副本）。 */
    public static String[] transferApiSafeKeys() {
        return TRANSFER_API_SAFE_KEYS.clone();
    }

    /** 仅跨版本抽取的复杂容器键（只读副本）。 */
    public static String[] crossVersionOnlyKeys() {
        return CROSS_VERSION_ONLY_KEYS.clone();
    }
}
