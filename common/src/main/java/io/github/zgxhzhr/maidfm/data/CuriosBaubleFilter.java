package io.github.zgxhzhr.maidfm.data;

import io.github.zgxhzhr.maidfm.spi.MaidBaubleCarrierRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Curios 饰品载荷过滤：把"不允许携带"的饰品从女仆实体 NBT 中剔除。
 *
 * <p>背景：TLM 本体通过 {@code data/touhou_little_maid/curios/entities/curios.json} 给女仆声明了
 * 13 个 Curios 槽位，Curios 会把槽位内容持久化到实体 NBT：
 * <pre>
 * 1.20.x Forge    : ForgeCaps  → "curios:inventory" → "Curios"[i] → { Identifier, StacksHandler }
 * 1.21.x NeoForge : neoforge:attachments → "curios:inventory" → "Curios"[i] → { Identifier, StacksHandler }
 * </pre>
 * 内层结构两代一致：{@code StacksHandler} 下 {@code Stacks} 与 {@code Cosmetics} 各含 {@code Items} 列表。
 * 条目形状则不同：
 * <ul>
 *   <li>1.20.x：{@code {Slot, id, Count, tag}}（{@code ItemStack.save} 平铺）</li>
 *   <li>1.21.x：{@code {Slot, Stack{id, count, components}}}（{@code ItemStack.CODEC} 嵌在 {@code Stack} 内）</li>
 * </ul>
 * 因此本类在 common 层即可同时适配两代，无需下沉平台层。
 *
 * <p>本模组<b>不做</b>对 Curios 的任何写入依赖：只在导出（写文件前）与导入（读文件后）时
 * 把不允许携带的条目删掉，其余结构原样保留，Curios 反序列化后表现为对应槽位为空。
 */
public final class CuriosBaubleFilter {

    /** capability / attachment 的容器键，两代都尝试，存在哪个处理哪个 */
    private static final String[] CONTAINER_KEYS = {"ForgeCaps", "neoforge:attachments"};
    /** Curios 自己的 capability / attachment 键 */
    private static final String CURIOS_INVENTORY_KEY = "curios:inventory";
    private static final String CURIOS_LIST_KEY = "Curios";
    private static final String IDENTIFIER_KEY = "Identifier";
    private static final String STACKS_HANDLER_KEY = "StacksHandler";
    private static final String STACKS_KEY = "Stacks";
    private static final String COSMETICS_KEY = "Cosmetics";
    private static final String ITEMS_KEY = "Items";
    /** 1.20.x 条目里的物品 ID 键 */
    private static final String ITEM_ID_KEY = "id";
    /** 1.21.x 条目里包裹 ItemStack codec 结果的键 */
    private static final String ITEM_STACK_KEY = "Stack";

    /**
     * 批次统计：一次导出/导入流程内累计被裁剪的 Curios 饰品，用于在流程结束点给玩家一条清晰提示。
     * <p>服务端主线程使用（ThreadLocal），{@link #takeBatch()} 取出后立即清零，避免跨批次累积重复提示。
     */
    public static final class Stats {
        private int dropped;
        private int forbiddenSlots;
        private int forbiddenItems;
        private int kept;
        private int missingItems;
        private final Map<String, Integer> byNamespace = new LinkedHashMap<>();

        public void add(Result result) {
            if (result == null) {
                return;
            }
            dropped += result.droppedItems();
            forbiddenSlots += result.forbiddenSlots();
            forbiddenItems += result.forbiddenItems();
            kept += result.keptItems();
            missingItems += result.missingItems();
            result.droppedByNamespace().forEach((ns, count) -> byNamespace.merge(ns, count, Integer::sum));
        }

        /** 是否有任何物品被丢弃 */
        public boolean hasDrops() {
            return dropped > 0 || forbiddenItems > 0 || missingItems > 0;
        }

        /** 物品在当前世界不存在而被丢弃的件数 */

        public int missingItems() {

            return missingItems;

        }


        public int dropped() {
            return dropped;
        }

        public int forbiddenSlots() {
            return forbiddenSlots;
        }

        public int forbiddenItems() {
            return forbiddenItems;
        }

        public int kept() {
            return kept;
        }

        /** 形如 {@code create:3, botania:1}（按件数降序），用于提示里说明"谁的东西没跟走" */
        public String namespacesText() {
            return byNamespace.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .map(e -> e.getKey() + ":" + e.getValue())
                    .collect(java.util.stream.Collectors.joining(", "));
        }
        /** 按件数降序的「命名空间 → 件数」映射（供调用方把 modId 换成模组显示名再拼给玩家看） */
        public Map<String, Integer> sortedNamespaces() {
            Map<String, Integer> sorted = new LinkedHashMap<>();
            byNamespace.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .forEach(e -> sorted.put(e.getKey(), e.getValue()));
            return sorted;
        }
    }

    private static final ThreadLocal<Stats> BATCH_STATS = ThreadLocal.withInitial(Stats::new);

    /** 当前批次的累加器（导出/导入流程内累加） */
    public static Stats batch() {
        return BATCH_STATS.get();
    }

    /** 取出并清零当前批次统计（流程结束点提示用） */
    public static Stats takeBatch() {
        Stats stats = BATCH_STATS.get();
        // 用 remove() 而不是 set(new Stats())：ThreadLocal 会随线程存活，set 会一直留一个
        // 空 Stats 实例在线程上；remove() 让下一次 get() 通过 withInitial 重新创建，语义等价且释放更干净。
        BATCH_STATS.remove();
        return stats;
    }

    private CuriosBaubleFilter() {
    }

    /**
     * 过滤结果统计（用于给玩家清晰提示与日志留痕）。
     *
     * @param droppedItems       被丢弃的饰品件数（不含被硬禁槽位内的物品）
     * @param droppedByNamespace 按命名空间统计的丢弃件数（取不到命名空间记为 {@code unknown}）
     * @param forbiddenSlots     被整条硬禁的槽位数（背包类槽位）
     * @param forbiddenItems     被硬禁槽位内一并丢弃的物品件数
     * @param keptItems          被保留（允许携带）的饰品件数
     * @param touched            是否真的动过 Curios 载荷
     */
    public record Result(int droppedItems, Map<String, Integer> droppedByNamespace,
                         int forbiddenSlots, int forbiddenItems, int missingItems, int keptItems, boolean touched) {

        public static Result empty() {
            return new Result(0, Map.of(), 0, 0, 0, 0, false);
        }

        /** 是否有任何物品被丢弃（用于决定要不要给玩家提示） */
        public boolean hasDrops() {
            return droppedItems > 0 || forbiddenItems > 0 || missingItems > 0;
        }

        /** 丢弃涉及的命名空间（按件数降序，便于拼提示文案） */
        public Map<String, Integer> sortedNamespaces() {
            Map<String, Integer> sorted = new LinkedHashMap<>();
            droppedByNamespace.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .forEach(e -> sorted.put(e.getKey(), e.getValue()));
            return sorted;
        }
    }

    /**
     * 从女仆实体 NBT 中剔除不允许携带的 Curios 饰品。
     *
     * @param entityNbt          女仆实体 NBT（原地修改）
     * @param itemCarriable 综合判定（命名空间, Curios 槽位名）-> 是否允许携带；未登记的一律 false
     * @return 统计结果；NBT 里没有 Curios 载荷时返回 {@link Result#empty()}
     */
    public static Result strip(CompoundTag entityNbt, boolean allowCarry,
                               CuriosItemPolicy policy, boolean stripAttributes) {
        if (entityNbt == null || entityNbt.isEmpty()) {
            return Result.empty();
        }
        // 总开关关闭时一律不允许携带（谓词恒 false）；统计仍然照做，便于给玩家提示"丢了什么"
        final CuriosItemPolicy itemPolicy =
                (allowCarry && policy != null) ? policy : (id, namespace, slot) -> false;
        int droppedItems = 0;
        int forbiddenSlots = 0;
        int forbiddenItems = 0;
        int missingItems = 0;
        int[] missingCounter = new int[1];
        int keptItems = 0;
        boolean touched = false;
        Map<String, Integer> droppedByNamespace = new LinkedHashMap<>();

        for (String containerKey : CONTAINER_KEYS) {
            if (!entityNbt.contains(containerKey)) {
                continue;
            }
            CompoundTag container = entityNbt.getCompoundOrEmpty(containerKey);
            if (!container.contains(CURIOS_INVENTORY_KEY)) {
                continue;
            }
            CompoundTag curiosInventory = container.getCompoundOrEmpty(CURIOS_INVENTORY_KEY);
            if (!curiosInventory.contains(CURIOS_LIST_KEY)) {
                continue;
            }
            // 原实现直接 getList(..., TAG_COMPOUND)：元素类型不是 compound 时返回的是**空表**，
            // 而后面会把 keptList 无条件回写 → 整个 Curios 列表被静默清空（不计数、不告警）。
            // 先确认元素类型，不符就跳过该容器，绝不回写（第六轮 D5）。
            Tag rawCurios = curiosInventory.get(CURIOS_LIST_KEY);
            if (rawCurios instanceof ListTag rawCuriosList
                    && !rawCuriosList.isEmpty() && !(rawCuriosList.get(0) instanceof CompoundTag)) {
                io.github.zgxhzhr.maidfm.Constants.LOG.warn(
                        "[maid_file_manager] Curios 列表元素类型异常，跳过该容器以免清空: container={}", containerKey);
                continue;
            }
            ListTag curiosList = curiosInventory.getListOrEmpty(CURIOS_LIST_KEY);
            ListTag keptList = new ListTag();
            for (Tag element : curiosList) {
                if (!(element instanceof CompoundTag entry)) {
                    keptList.add(element);
                    continue;
                }
                String identifier = entry.contains(IDENTIFIER_KEY)
                        ? entry.getStringOr(IDENTIFIER_KEY, "") : "";
                // 硬禁槽位：不看声明，整条移除（背包类槽位会连带其内部物品一起被搬运）
                if (MaidBaubleCarrierRegistry.isCuriosSlotForbidden(identifier)) {
                    int inSlot = countItems(entry);
                    forbiddenSlots++;
                    forbiddenItems += inSlot;
                    touched = true;
                    continue;
                }
                if (!entry.contains(STACKS_HANDLER_KEY)) {
                    keptList.add(entry);
                    continue;
                }
                CompoundTag stacksHandler = entry.getCompoundOrEmpty(STACKS_HANDLER_KEY);
                int before = countItems(entry);
                droppedItems += filterItemsList(stacksHandler, STACKS_KEY, identifier, itemPolicy,
                        stripAttributes, droppedByNamespace, missingCounter);
                droppedItems += filterItemsList(stacksHandler, COSMETICS_KEY, identifier, itemPolicy,
                        stripAttributes, droppedByNamespace, missingCounter);
                int after = countItems(entry);
                keptItems += after;
                if (after != before) {
                    touched = true;
                }
                keptList.add(entry);
            }
            curiosInventory.put(CURIOS_LIST_KEY, keptList);
        }

        missingItems += missingCounter[0];

        if (!touched) {
            return Result.empty();
        }
        return new Result(droppedItems, Map.copyOf(droppedByNamespace), forbiddenSlots, forbiddenItems,
                    missingItems, keptItems, true);
    }

    /**
     * 过滤一个 {@code Stacks}/{@code Cosmetics} 复合内的 {@code Items} 列表，原地重建。
     *
     * @return 被丢弃的件数
     */
    private static int filterItemsList(CompoundTag stacksHandler, String subKey, String slotIdentifier,
                                       CuriosItemPolicy itemPolicy, boolean stripAttributes,
                                       Map<String, Integer> droppedByNamespace,
                                       int[] missingOut) {
        if (!stacksHandler.contains(subKey)) {
            return 0;
        }
        CompoundTag sub = stacksHandler.getCompoundOrEmpty(subKey);
        if (!sub.contains(ITEMS_KEY)) {
            return 0;
        }
        ListTag items = sub.getListOrEmpty(ITEMS_KEY);
        ListTag kept = new ListTag();
        int dropped = 0;
        // 局部缺失计数：missingOut[0] 是跨槽位（跨多个 Items 列表）的累加值，
        // 直接拿它判断「本列表是否需要回写」会让后续槽位即使毫无变化也被重写一遍。
        int missingThisList = 0;
        for (Tag element : items) {
            if (!(element instanceof CompoundTag itemEntry)) {
                kept.add(element);
                continue;
            }
            String itemId = itemIdOfEntry(itemEntry);
            // 物品在当前世界不存在（模组被卸载 / 换了版本 / 物品被移除）：单独统计并提示，
            // 否则玩家只会看到饰品凭空消失，不知道原因。
            if (itemId != null && !itemExists(itemId)) {
                missingOut[0]++;
                missingThisList++;
                continue;
            }
            String namespace = namespaceOfItemId(itemId);
            if (namespace != null && itemPolicy.allow(itemId, namespace, slotIdentifier)) {
                if (stripAttributes) {
                    stripEntryAttributes(itemEntry);
                }
                kept.add(itemEntry);
                continue;
            }
            dropped++;
            droppedByNamespace.merge(namespace == null ? "unknown" : namespace, 1, Integer::sum);
        }
        if (dropped > 0 || missingThisList > 0) {
            sub.put(ITEMS_KEY, kept);
        }
        return dropped;
    }
    /**
     * 属性剥离：把保留的饰品简化为"全新物品"（与女仆自带饰品栏的「丢弃饰品属性」设置语义一致）。
     * <p>1.20.x 条目属性在 {@code tag} 子标签内；1.21.x 在 {@code Stack.components} 内，两处都清；
     * 数量不动（饰品本就是 1 件）。
     */
    private static void stripEntryAttributes(CompoundTag itemEntry) {
        if (itemEntry.contains(ITEM_ID_KEY)) {
            itemEntry.remove("tag");
            itemEntry.remove("components");
        }
        if (itemEntry.contains(ITEM_STACK_KEY)) {
            CompoundTag stack = itemEntry.getCompoundOrEmpty(ITEM_STACK_KEY);
            stack.remove("components");
            stack.remove("tag");
        }
    }


    /** 取条目里的完整物品 id（1.20.x 取 {@code id}，1.21.x 取 {@code Stack.id}）；取不到返回 null */
    private static String itemIdOfEntry(CompoundTag itemEntry) {
        if (itemEntry.contains(ITEM_ID_KEY)) {
            return itemEntry.getStringOr(ITEM_ID_KEY, "");
        }
        if (itemEntry.contains(ITEM_STACK_KEY)) {
            CompoundTag stack = itemEntry.getCompoundOrEmpty(ITEM_STACK_KEY);
            if (stack.contains(ITEM_ID_KEY)) {
                return stack.getStringOr(ITEM_ID_KEY, "");
            }
        }
        return null;
    }

    /** 由物品 id 推命名空间；取不到返回 null（调用方按"不允许携带"处理，失败关闭） */
    private static String namespaceOfItemId(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return null;
        }
        int colon = itemId.indexOf(':');
        return colon <= 0 ? "minecraft" : itemId.substring(0, colon);
    }

    /**
     * 该物品在当前世界是否存在。
     * <p>写法两代通用（{@code Identifier.tryParse} + {@code Registry.containsKey}）。
     * <p>解析异常或 id 非法的情形一律视为"存在"：宁可留给上层解析时自行降级，
     * 也不能因为我们的判断把玩家本来能保留的物品删掉。
     */
    private static boolean itemExists(String itemId) {
        try {
            net.minecraft.resources.Identifier key =
                    net.minecraft.resources.Identifier.tryParse(itemId);
            return key == null || net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(key);
        } catch (Throwable t) {
            return true;
        }
    }

    /** 统计某条 Curios 槽位条目里（Stacks + Cosmetics）的物品件数 */
    private static int countItems(CompoundTag curiosEntry) {
        if (!curiosEntry.contains(STACKS_HANDLER_KEY)) {
            return 0;
        }
        CompoundTag stacksHandler = curiosEntry.getCompoundOrEmpty(STACKS_HANDLER_KEY);
        return countItemsIn(stacksHandler, STACKS_KEY) + countItemsIn(stacksHandler, COSMETICS_KEY);
    }

    private static int countItemsIn(CompoundTag stacksHandler, String subKey) {
        if (!stacksHandler.contains(subKey)) {
            return 0;
        }
        CompoundTag sub = stacksHandler.getCompoundOrEmpty(subKey);
        if (!sub.contains(ITEMS_KEY)) {
            return 0;
        }
        return sub.getListOrEmpty(ITEMS_KEY).size();
    }

}
