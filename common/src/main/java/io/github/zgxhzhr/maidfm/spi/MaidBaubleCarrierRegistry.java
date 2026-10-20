package io.github.zgxhzhr.maidfm.spi;

import io.github.zgxhzhr.maidfm.Constants;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 饰品携带声明注册表。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>默认拒绝</b>：未出现在本表中的命名空间，其饰品一律不被携带（含 TLM 饰品栏与 Curios 槽位）；</li>
 *   <li><b>内置声明</b>：车万女仆本体（{@code touhou_little_maid}）与万法皆通
 *       （{@code touhou_little_maid_spell}）作为内置项登记，保持既有行为；</li>
 *   <li><b>只许声明自己</b>：{@link MaidBaubleCarrier#carriedNamespaces()} 的每项必须等于
 *       {@link MaidBaubleCarrier#getId()} 的命名空间，否则整条注册被拒绝并记 WARN；</li>
 *   <li><b>留痕可审计</b>：每次登记都写 INFO 日志（谁声明了哪个命名空间），服主可据此核对范围；</li>
 *   <li><b>文件不携带授权</b>：{@code .maid} 文件里不含任何"可携带声明"，导入时只以<b>本地注册表</b>
 *       为准，改文件或跨端搬运都无法给自己授权。</li>
 * </ul>
 */
public final class MaidBaubleCarrierRegistry {

    /**
     * 硬编码禁止携带的 Curios 槽位标识。
     *
     * <p>背包类槽位的物品体积之外还挂着自己的容器数据（背包内容），若允许携带，
     * 等于把整包物品一起搬运/复制，风险远大于单件饰品。因此这里<b>不看声明、一律禁止</b>。
     */
    /**
     * 硬编码禁止携带的 Curios 槽位名（精确匹配）。
     *
     * <p>两类：
     * <ul>
     *   <li><b>容器类</b>：背包/袋/箭袋/口袋等——携带会连带其内部物品，属复制风险；</li>
     *   <li><b>有状态数据容器</b>：物品本身是个"存数据"的壳（存 EMC、训练数据、灵魂等），
     *       携带等于搬运/复制其内部数据。</li>
     * </ul>
     *
     * <p>这里只放<b>精确名</b>：例如精确禁止 {@code soul_stone}，但 TLM 自家的
     * {@code maid_soul_stone} 不会被误伤（内置声明的车万本体饰品要保留）。
     * 万法皆通的 {@code spellbook} / {@code scroll} 有意<b>不</b>禁止（属内置声明要保留的附属）。
     *
     * <p>名单依据：TLM 自带的 24 个 Curios 适配文件（69 个槽位名）＋ 两个真实整合包实扫
     * （494 个 mod jar、110 个槽位名）。
     */
    private static final Set<String> FORBIDDEN_CURIOS_SLOT_NAMES = Set.of(
            // 容器类
            "back", "curio_back", "backpack", "backpacks", "ionocraft_backpack", "arrow_bag",
            // 有状态数据容器
            "klein_star", "deep_learner", "soul", "soul_stone", "soultotem");

    /**
     * 硬编码禁止携带的槽位名<b>词根</b>（按 {@code _}、{@code -}、{@code .} 分词后逐词比对，含复数形式）。
     *
     * <p>TLM 与第三方模组都能给女仆新增槽位，槽位名无法穷举，词根用于拦住
     * "未来出现、名字里带容器语义"的槽位。
     *
     * <p>故意<b>不含</b> {@code soul}/{@code book}/{@code star}/{@code crystal} 等模糊词：
     * 它们会误伤 TLM 自家槽位（{@code maid_soul_stone}）与万法皆通槽位（{@code spellbook}）。
     */
    private static final Set<String> FORBIDDEN_CURIOS_SLOT_WORDS = Set.of(
            "back", "backpack", "pack", "bag", "pouch", "satchel",
            "chest", "container", "storage", "inventory", "quiver", "pocket");

    private static final List<MaidBaubleCarrier> PROVIDERS = new CopyOnWriteArrayList<>();
    private static final Set<String> DECLARED_NAMESPACES = ConcurrentHashMap.newKeySet();
    /**
     * 命名空间 → 该命名空间允许携带的 Curios 槽位名单。
     * <p>只有声明者显式列出槽位时才有条目；没有条目表示"不限制槽位"（按命名空间携带）。
     */
    private static final Map<String, Set<String>> SLOT_RESTRICTIONS = new ConcurrentHashMap<>();

    static {
        // 内置声明：保持既有行为（车万本体 + 万法皆通）。
        // 注意：这里直接登记命名空间、不构造 ResourceLocation —— 1.21 起
        // ResourceLocation(String,String) 构造器已变为 private，
        // common 层无法让两代共用同一写法（外部接入方在自己版本编译，写对本版本的 API 即可）。
        declareBuiltin("touhou_little_maid");
        declareBuiltin("touhou_little_maid_spell");
    }

    private MaidBaubleCarrierRegistry() {
    }

    private static void declareBuiltin(String namespace) {
        if (DECLARED_NAMESPACES.add(namespace)) {
            Constants.LOG.info("[maid_file_manager] 内置可携带饰品命名空间: {}", namespace);
        }
    }

    /**
     * 注册一个携带声明。重复 id 自动去重；越权声明（声明别人的命名空间）整条拒绝。
     *
     * @param carrier 要注册的声明
     */
    public static void register(MaidBaubleCarrier carrier) {
        // 第三方实现：任何一步（getId / isAvailable / carriedNamespaces / 其它 getter）抛异常
        // 都不能连累注册流程与其它模组——统一兜底并记录。
        try {
            registerInternal(carrier);
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 饰品携带声明注册抛异常，已跳过该声明: {}",
                    t.toString());
        }
    }

    private static void registerInternal(MaidBaubleCarrier carrier) {
        if (carrier == null || carrier.getId() == null) {
            return;
        }
        String owner = carrier.getId().getNamespace();
        if (!carrier.isAvailable()) {
            Constants.LOG.info("[maid_file_manager] 饰品携带声明暂不可用（依赖未加载），跳过: {}",
                    carrier.getId());
            return;
        }
        Set<String> declared = carrier.carriedNamespaces();
        if (declared == null || declared.isEmpty()) {
            return;
        }
        for (MaidBaubleCarrier existing : PROVIDERS) {
            if (existing.getId().equals(carrier.getId())) {
                return;
            }
        }
        // 越权校验：只允许声明自己的命名空间（防止替他人放行物品、绕过携带限制）
        Set<String> accepted = new LinkedHashSet<>();
        List<String> rejected = new ArrayList<>();
        for (String ns : declared) {
            if (ns == null || ns.isBlank()) {
                continue;
            }
            if (!owner.equals(ns)) {
                rejected.add(ns);
                continue;
            }
            accepted.add(ns);
        }
        if (!rejected.isEmpty()) {
            Constants.LOG.warn("[maid_file_manager] 拒绝饰品携带声明: 声明者 {} 试图声明不属于自己的命名空间 {}"
                    + "（只允许声明自己的命名空间；若确需放行他人命名空间，请让该命名空间所属模组自行声明）",
                    owner, rejected);
            return;
        }
        if (accepted.isEmpty()) {
            return;
        }
        PROVIDERS.add(carrier);
        Set<String> slotWhitelist = carrier.carriedCuriosSlots() == null
                ? Set.of()
                : carrier.carriedCuriosSlots().stream()
                        .filter(slot -> slot != null && !slot.isBlank())
                        .map(MaidBaubleCarrierRegistry::normalizeIdentifier)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (String ns : accepted) {
            if (!slotWhitelist.isEmpty()) {
                SLOT_RESTRICTIONS.put(ns, slotWhitelist);
                Constants.LOG.info("[maid_file_manager] 声明者 {} 限定了可携带的 Curios 槽位: {}", owner, slotWhitelist);
            }
            if (DECLARED_NAMESPACES.add(ns)) {
                Constants.LOG.info("[maid_file_manager] 已登记可携带饰品的命名空间: {}（声明者 {}）", ns, owner);
            } else {
                Constants.LOG.warn("[maid_file_manager] 命名空间 {} 已被其它声明登记，重复声明来自 {}（忽略重复项）",
                        ns, owner);
            }
        }
    }

    /** 该命名空间的饰品是否可被携带（未登记即不可携带）。 */
    public static boolean isNamespaceCarriable(String namespace) {
        return namespace != null && DECLARED_NAMESPACES.contains(namespace);
    }

    /** 当前生效的全部可携带命名空间（只读快照，供界面/日志/审计展示）。 */
    public static Set<String> declaredNamespaces() {
        return Set.copyOf(DECLARED_NAMESPACES);
    }

    /**
     * 该 Curios 槽位标识是否被硬编码禁止携带。
     *
     * <p>判定顺序：去掉可能的命名空间前缀 → 精确名单 → 分词后的词根名单（含复数）。
     * 与"命名空间声明"无关：容器类槽位即使所属模组声明了自己，也一律不携带。
     */
    public static boolean isCuriosSlotForbidden(String slotIdentifier) {
        if (slotIdentifier == null || slotIdentifier.isEmpty()) {
            // 空/未知槽位默认**拒绝**：容器类槽位（背包、盒子等）本就该被硬禁，
            // 此前返回 false 属 fail-open —— 条目缺 Identifier 时会跳过硬禁判定。
            return true;
        }
        String name = normalizeIdentifier(slotIdentifier);
        if (FORBIDDEN_CURIOS_SLOT_NAMES.contains(name)) {
            return true;
        }
        for (String word : name.split("[_\\-.]")) {
            if (word.isEmpty()) {
                continue;
            }
            for (String root : FORBIDDEN_CURIOS_SLOT_WORDS) {
                if (word.equals(root) || word.equals(root + "s")) {
                    return true;
                }
            }
        }
        return false;
    }
    /**
     * 综合判定：某命名空间的物品能否出现在某 Curios 槽位。
     *
     * <p>规则：命名空间必须已被声明；若该声明的模组给出了槽位名单（{@link MaidBaubleCarrier#carriedCuriosSlots()}），
     * 槽位名还必须落在名单内。硬禁槽位由 {@link #isCuriosSlotForbidden(String)} 单独拦截，且优先级更高。
     */
    public static boolean isCuriosItemCarriable(String namespace, String slotIdentifier) {
        if (!isNamespaceCarriable(namespace)) {
            return false;
        }
        Set<String> allowedSlots = SLOT_RESTRICTIONS.get(namespace);
        if (allowedSlots == null || allowedSlots.isEmpty()) {
            return true;
        }
        return slotIdentifier != null && allowedSlots.contains(normalizeIdentifier(slotIdentifier));
    }

    /** 槽位标识规范化：剥掉可能的命名空间前缀并转小写（{@code Curios:BACK} → {@code back}）。 */
    private static String normalizeIdentifier(String slotIdentifier) {
        if (slotIdentifier == null) {
            return "";
        }
        int colon = slotIdentifier.indexOf(':');
        String name = colon >= 0 ? slotIdentifier.substring(colon + 1) : slotIdentifier;
        return name.toLowerCase(java.util.Locale.ROOT);
    }

    /** 当前所有可用的声明（供界面展示"谁声明了什么"）。 */
    public static List<MaidBaubleCarrier> getAvailable() {
        return PROVIDERS.stream().filter(MaidBaubleCarrier::isAvailable).toList();
    }
}
