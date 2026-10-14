package io.github.zgxhzhr.maidfm.spi;

import io.github.zgxhzhr.maidfm.Constants;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
    private static final Set<String> FORBIDDEN_CURIOS_SLOT_IDENTIFIERS =
            Set.of("back", "backpack", "satchel", "bag", "curio_back");

    private static final List<MaidBaubleCarrier> PROVIDERS = new CopyOnWriteArrayList<>();
    private static final Set<String> DECLARED_NAMESPACES = ConcurrentHashMap.newKeySet();

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
        for (String ns : accepted) {
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

    /** 该 Curios 槽位标识是否被硬编码禁止携带（背包类槽位）。 */
    public static boolean isCuriosSlotForbidden(String slotIdentifier) {
        return slotIdentifier != null && FORBIDDEN_CURIOS_SLOT_IDENTIFIERS.contains(slotIdentifier);
    }

    /** 当前所有可用的声明（供界面展示"谁声明了什么"）。 */
    public static List<MaidBaubleCarrier> getAvailable() {
        return PROVIDERS.stream().filter(MaidBaubleCarrier::isAvailable).toList();
    }
}
