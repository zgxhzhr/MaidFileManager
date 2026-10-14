package io.github.zgxhzhr.maidfm.data;

import io.github.zgxhzhr.maidfm.spi.MaidBaubleCarrierRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

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
        private final Map<String, Integer> byNamespace = new LinkedHashMap<>();

        public void add(Result result) {
            if (result == null) {
                return;
            }
            dropped += result.droppedItems();
            forbiddenSlots += result.forbiddenSlots();
            forbiddenItems += result.forbiddenItems();
            kept += result.keptItems();
            result.droppedByNamespace().forEach((ns, count) -> byNamespace.merge(ns, count, Integer::sum));
        }

        /** 是否有任何物品被丢弃 */
        public boolean hasDrops() {
            return dropped > 0 || forbiddenItems > 0;
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
    }

    private static final ThreadLocal<Stats> BATCH_STATS = ThreadLocal.withInitial(Stats::new);

    /** 当前批次的累加器（导出/导入流程内累加） */
    public static Stats batch() {
        return BATCH_STATS.get();
    }

    /** 取出并清零当前批次统计（流程结束点提示用） */
    public static Stats takeBatch() {
        Stats stats = BATCH_STATS.get();
        BATCH_STATS.set(new Stats());
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
                         int forbiddenSlots, int forbiddenItems, int keptItems, boolean touched) {

        public static Result empty() {
            return new Result(0, Map.of(), 0, 0, 0, false);
        }

        /** 是否有任何物品被丢弃（用于决定要不要给玩家提示） */
        public boolean hasDrops() {
            return droppedItems > 0 || forbiddenItems > 0;
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
     * @param namespaceCarriable 命名空间是否允许携带（未登记的一律 false）
     * @return 统计结果；NBT 里没有 Curios 载荷时返回 {@link Result#empty()}
     */
    public static Result strip(CompoundTag entityNbt, boolean allowCarry, Predicate<String> namespaceCarriableIn) {
        if (entityNbt == null || entityNbt.isEmpty()) {
            return Result.empty();
        }
        // 总开关关闭时一律不允许携带（谓词恒 false）；统计仍然照做，便于给玩家提示"丢了什么"
        final Predicate<String> namespaceCarriable =
                (allowCarry && namespaceCarriableIn != null) ? namespaceCarriableIn : namespace -> false;
        int droppedItems = 0;
        int forbiddenSlots = 0;
        int forbiddenItems = 0;
        int keptItems = 0;
        boolean touched = false;
        Map<String, Integer> droppedByNamespace = new LinkedHashMap<>();

        for (String containerKey : CONTAINER_KEYS) {
            if (!entityNbt.contains(containerKey, Tag.TAG_COMPOUND)) {
                continue;
            }
            CompoundTag container = entityNbt.getCompound(containerKey);
            if (!container.contains(CURIOS_INVENTORY_KEY, Tag.TAG_COMPOUND)) {
                continue;
            }
            CompoundTag curiosInventory = container.getCompound(CURIOS_INVENTORY_KEY);
            if (!curiosInventory.contains(CURIOS_LIST_KEY, Tag.TAG_LIST)) {
                continue;
            }
            ListTag curiosList = curiosInventory.getList(CURIOS_LIST_KEY, Tag.TAG_COMPOUND);
            ListTag keptList = new ListTag();
            for (Tag element : curiosList) {
                if (!(element instanceof CompoundTag entry)) {
                    keptList.add(element);
                    continue;
                }
                String identifier = entry.contains(IDENTIFIER_KEY, Tag.TAG_STRING)
                        ? entry.getString(IDENTIFIER_KEY) : "";
                // 硬禁槽位：不看声明，整条移除（背包类槽位会连带其内部物品一起被搬运）
                if (MaidBaubleCarrierRegistry.isCuriosSlotForbidden(identifier)) {
                    int inSlot = countItems(entry);
                    forbiddenSlots++;
                    forbiddenItems += inSlot;
                    touched = true;
                    continue;
                }
                if (!entry.contains(STACKS_HANDLER_KEY, Tag.TAG_COMPOUND)) {
                    keptList.add(entry);
                    continue;
                }
                CompoundTag stacksHandler = entry.getCompound(STACKS_HANDLER_KEY);
                int before = countItems(entry);
                droppedItems += filterItemsList(stacksHandler, STACKS_KEY, namespaceCarriable, droppedByNamespace);
                droppedItems += filterItemsList(stacksHandler, COSMETICS_KEY, namespaceCarriable, droppedByNamespace);
                int after = countItems(entry);
                keptItems += after;
                if (after != before) {
                    touched = true;
                }
                keptList.add(entry);
            }
            curiosInventory.put(CURIOS_LIST_KEY, keptList);
        }

        if (!touched) {
            return Result.empty();
        }
        return new Result(droppedItems, Map.copyOf(droppedByNamespace), forbiddenSlots, forbiddenItems,
                keptItems, true);
    }

    /**
     * 过滤一个 {@code Stacks}/{@code Cosmetics} 复合内的 {@code Items} 列表，原地重建。
     *
     * @return 被丢弃的件数
     */
    private static int filterItemsList(CompoundTag stacksHandler, String subKey,
                                       Predicate<String> namespaceCarriable,
                                       Map<String, Integer> droppedByNamespace) {
        if (!stacksHandler.contains(subKey, Tag.TAG_COMPOUND)) {
            return 0;
        }
        CompoundTag sub = stacksHandler.getCompound(subKey);
        if (!sub.contains(ITEMS_KEY, Tag.TAG_LIST)) {
            return 0;
        }
        ListTag items = sub.getList(ITEMS_KEY, Tag.TAG_COMPOUND);
        ListTag kept = new ListTag();
        int dropped = 0;
        for (Tag element : items) {
            if (!(element instanceof CompoundTag itemEntry)) {
                kept.add(element);
                continue;
            }
            String namespace = namespaceOfItemEntry(itemEntry);
            if (namespace != null && namespaceCarriable.test(namespace)) {
                kept.add(itemEntry);
                continue;
            }
            dropped++;
            droppedByNamespace.merge(namespace == null ? "unknown" : namespace, 1, Integer::sum);
        }
        if (dropped > 0) {
            sub.put(ITEMS_KEY, kept);
        }
        return dropped;
    }

    /** 统计某条 Curios 槽位条目里（Stacks + Cosmetics）的物品件数 */
    private static int countItems(CompoundTag curiosEntry) {
        if (!curiosEntry.contains(STACKS_HANDLER_KEY, Tag.TAG_COMPOUND)) {
            return 0;
        }
        CompoundTag stacksHandler = curiosEntry.getCompound(STACKS_HANDLER_KEY);
        return countItemsIn(stacksHandler, STACKS_KEY) + countItemsIn(stacksHandler, COSMETICS_KEY);
    }

    private static int countItemsIn(CompoundTag stacksHandler, String subKey) {
        if (!stacksHandler.contains(subKey, Tag.TAG_COMPOUND)) {
            return 0;
        }
        CompoundTag sub = stacksHandler.getCompound(subKey);
        if (!sub.contains(ITEMS_KEY, Tag.TAG_LIST)) {
            return 0;
        }
        return sub.getList(ITEMS_KEY, Tag.TAG_COMPOUND).size();
    }

    /**
     * 取条目里物品的命名空间；取不到返回 {@code null}（调用方按"不允许携带"处理，失败关闭）。
     * <p>兼容两代形状：1.20.x 取 {@code id}；1.21.x 取 {@code Stack.id}。
     */
    private static String namespaceOfItemEntry(CompoundTag itemEntry) {
        String id = null;
        if (itemEntry.contains(ITEM_ID_KEY, Tag.TAG_STRING)) {
            id = itemEntry.getString(ITEM_ID_KEY);
        } else if (itemEntry.contains(ITEM_STACK_KEY, Tag.TAG_COMPOUND)) {
            CompoundTag stack = itemEntry.getCompound(ITEM_STACK_KEY);
            if (stack.contains(ITEM_ID_KEY, Tag.TAG_STRING)) {
                id = stack.getString(ITEM_ID_KEY);
            }
        }
        if (id == null || id.isEmpty()) {
            return null;
        }
        int colon = id.indexOf(':');
        if (colon <= 0) {
            return "minecraft";
        }
        return id.substring(0, colon);
    }
}
