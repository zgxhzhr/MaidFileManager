package io.github.zgxhzhr.maidfm.service;

import io.github.zgxhzhr.maidfm.Constants;
import io.github.zgxhzhr.maidfm.config.MaidConfigManager;
import io.github.zgxhzhr.maidfm.data.CuriosBaubleFilter;
import io.github.zgxhzhr.maidfm.data.ImportResult;
import io.github.zgxhzhr.maidfm.platform.Services;
import io.github.zgxhzhr.maidfm.spi.MaidBaubleCarrierRegistry;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 饰品（曲奇饰品栏 / Curios）的携带策略与恢复流程。
 *
 * <p>从 {@code MaidTransferService} 抽出的独立职责。原先这些代码散落在 2000 行的
 * 导入流程里，但它们描述的是同一个领域问题：<b>一件来自其他存档的饰品，在当前世界
 * 该不该保留、能保留到什么程度</b>。
 *
 * <p>判定顺序（{@link #restoreBaubles} 内部）：
 * <ol>
 *   <li>禁用携带清单命中 → 丢弃（OP 配置或整合包黑名单，支持物品 ID 或中文名精确匹配）</li>
 *   <li>命名空间白名单未命中 → 丢弃（未适配的第三方模组饰品默认不携带）</li>
 *   <li>整合包白名单未命中 → 丢弃（bauble_import.json 启用时）</li>
 *   <li>未开启「丢弃属性」时尝试完整状态恢复（同版本直读，跨版本经平台层转换）</li>
 *   <li>完整恢复失败或明确要求丢弃属性 → 全新化重建</li>
 * </ol>
 *
 * <p>解析后的物品会以注册表里的<b>实际物品键</b>再复核一次白名单与禁用清单——
 * 伪造条目 ID 与实际物品不符也无法绕过。
 */
public final class BaubleHandler {

    /** 饰品槽位硬上限。槽位号来自网络 NBT，绝不可信，越界按坏数据丢弃。 */
    private static final int MAX_BAUBLE_SLOTS = 256;

    private BaubleHandler() {
    }

    public static void restoreBaubles(EntityMaid maid, CompoundTag tlmData, boolean keepBaubles,
                                       boolean sameVersion, boolean stripAttributes, List<String> blockedList,
                                       int sourceMojangDataVersion, List<Component> baubleNotes) {
        if (!tlmData.contains("MaidBaubleInventory", Tag.TAG_COMPOUND) || !keepBaubles
                || !MaidConfigManager.isBaublesAllowed()) {
            return;
        }
        try {
            ListTag items = tlmData.getCompound("MaidBaubleInventory").getList("Items", Tag.TAG_COMPOUND);
            if (items.isEmpty()) {
                return;
            }
            int currentSlots = Services.PLATFORM.get().baubleGetSlots(maid);
            if (currentSlots <= 0) {
                Constants.LOG.warn("[maid_file_manager] 饰品栏不可用，{} 件饰品全部跳过", items.size());
                return;
            }
            int needed = currentSlots;
            int oversized = 0;
            for (int i = 0; i < items.size(); i++) {
                CompoundTag entry = items.getCompound(i);
                int slot = entry.contains("Slot", Tag.TAG_INT) ? entry.getInt("Slot") : i;
                // 槽位号取自网络 NBT，绝不可信：负值或越过硬上限按坏数据丢弃，绝不据此扩容
                if (slot < 0 || slot >= MAX_BAUBLE_SLOTS) {
                    oversized++;
                    continue;
                }
                if (slot + 1 > needed) {
                    needed = slot + 1;
                }
            }
            if (oversized > 0) {
                Constants.LOG.warn("[maid_file_manager] 饰品数据含 {} 个非法槽位号（硬上限 {} 槽），对应饰品已丢弃",
                        oversized, MAX_BAUBLE_SLOTS);
            }
            if (needed > currentSlots) {
                // setSize 必须在写入前一次性完成（会重建槽位列表）
                Services.PLATFORM.get().baubleResize(maid, needed);
                currentSlots = needed;
                Constants.LOG.debug("[maid_file_manager] 饰品栏扩容至 {} 槽", needed);
            }
            int restoredFull = 0;
            int restoredFresh = 0;
            int droppedBlocked = 0;
            int droppedForeign = 0;
            int droppedWhitelist = 0;
            int droppedMissing = 0;
            int droppedFailed = 0;
            for (int i = 0; i < items.size(); i++) {
                CompoundTag entry = items.getCompound(i);
                try {
                    int slot = entry.contains("Slot", Tag.TAG_INT) ? entry.getInt("Slot") : i;
                    // 硬上限外的非法槽位已在第一遍计入 oversized 并 WARN，这里直接跳过，避免同一批条目被重复计数
                    if (slot < 0 || slot >= MAX_BAUBLE_SLOTS) {
                        continue;
                    }
                    if (slot >= currentSlots) {
                        droppedFailed++;
                        continue;
                    }
                    String id = entry.contains("id", Tag.TAG_STRING) ? entry.getString("id") : "";
                    // 条目 ID 解析出的物品（跨版本旧键可能解析失败，此时仅能按 ID 匹配）
                    Item preItem = id.isEmpty() ? null : resolveItem(id);
                    // 步骤 1：禁用携带清单命中（OP 配置或整合包黑名单，支持物品 ID 或中文显示名精确匹配）→ 直接丢弃
                    if (baubleMatchesList(id, preItem, blockedList)) {
                        droppedBlocked++;
                        addBaubleNote(baubleNotes, "maid_file_manager.import.bauble.blocked", id);
                        continue;
                    }
                    // 步骤 2：命名空间白名单前置。路径 A/DFU 解析后还会以实际物品键复核，
                    // 此处先按 entry 的 id 快速拦截第三方物品，避免无谓解析。
                    String namespace = namespaceOf(id);
                    if (!isBaubleAllowed(namespace)) {
                        droppedForeign++;
                        addBaubleNote(baubleNotes, "maid_file_manager.import.bauble.foreign", id);
                        continue;
                    }
                    // 步骤 2b：整合包白名单（bauble_import.json 启用时）：ID 或中文显示名未命中即拒绝
                    if (!MaidConfigManager.isBaubleWhitelistPass(id, displayNameOf(preItem))) {
                        droppedWhitelist++;
                        addBaubleNote(baubleNotes, "maid_file_manager.import.bauble.whitelist", id);
                        continue;
                    }
                    // 步骤 3~5：未开启"丢弃饰品属性"时尝试完整状态恢复（同版本直接解析；
                    // 跨版本先经平台层升级/降级到当前版本再解析，保留附魔/耐久/无法破坏/属性修饰符）。
                    ItemStack full = stripAttributes ? ItemStack.EMPTY
                            : tryParseFullBauble(maid, entry, sameVersion, sourceMojangDataVersion);
                    if (full != null && !full.isEmpty()) {
                        // 以解析后注册表中的实际物品键复核白名单，伪造 id 与实际物品不符也无法绕过
                        net.minecraft.resources.ResourceLocation fullKey =
                                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(full.getItem());
                        String fullId = fullKey.toString();
                        if (!isBaubleAllowed(fullKey.getNamespace())) {
                            // 完整解析得到非白名单物品：不得退回全新化（全新化仍是同一件第三方物品），直接丢弃
                            droppedForeign++;
                            addBaubleNote(baubleNotes, "maid_file_manager.import.bauble.foreign", id);
                            continue;
                        }
                        // 解析后物品可能与条目 id 不一致：禁用清单与整合包白名单同步复核
                        if (baubleMatchesList(fullId, full.getItem(), blockedList)) {
                            droppedBlocked++;
                            addBaubleNote(baubleNotes, "maid_file_manager.import.bauble.blocked", id);
                            continue;
                        }
                        if (!MaidConfigManager.isBaubleWhitelistPass(fullId, displayNameOf(full.getItem()))) {
                            droppedWhitelist++;
                            addBaubleNote(baubleNotes, "maid_file_manager.import.bauble.whitelist", id);
                            continue;
                        }
                        Services.PLATFORM.get().baubleSetStack(maid, slot, full);
                        restoredFull++;
                        continue;
                    }
                    // 步骤 6：全新化重建（开启丢弃属性 / 跨版本无法转换 / 同版本解析失败）
                    // 白名单与禁用清单已在前面按条目 ID 复核，此处复用已解析的物品，避免重复查表
                    if (id.isEmpty()) {
                        droppedFailed++;
                        continue;
                    }
                    Item item = preItem;
                    if (item == null || Items.AIR.equals(item)) {
                        droppedMissing++;
                        addBaubleNote(baubleNotes, "maid_file_manager.import.bauble.missing_item", id);
                        continue;
                    }
                    int count = 1;
                    if (entry.contains("Count", Tag.TAG_ANY_NUMERIC)) {
                        count = entry.getInt("Count");
                    } else if (entry.contains("count", Tag.TAG_ANY_NUMERIC)) {
                        count = entry.getInt("count");
                    }
                    count = Math.max(1, count);
                    ItemStack fresh = new ItemStack(item, count);
                    fresh.setCount(Math.min(count, fresh.getMaxStackSize()));
                    Services.PLATFORM.get().baubleSetStack(maid, slot, fresh);
                    restoredFresh++;
                    // 全新化原因区分：开启丢弃属性 → 设置原因；否则 → 格式无法解析（跨版本转换失败/版本未知/同版本解析失败）
                    if (stripAttributes) {
                        addBaubleNote(baubleNotes, "maid_file_manager.import.bauble.stripped", id);
                    } else {
                        addBaubleNote(baubleNotes, "maid_file_manager.import.bauble.unparseable", id);
                    }
                } catch (Throwable t) {
                    droppedFailed++;
                    Constants.LOG.warn("[maid_file_manager] 单件饰品恢复失败，跳过: {}", t.toString());
                }
            }
            Constants.LOG.info("[maid_file_manager] 饰品恢复完成: 完整恢复={} 全新化={} 禁用清单={} 非TLM命名空间={} 未入整合包白名单={} 目标世界缺失={} 失败={} 非法槽位={} 同版本={}",
                    restoredFull, restoredFresh, droppedBlocked, droppedForeign, droppedWhitelist,
                    droppedMissing, droppedFailed, oversized, sameVersion);            // 有"模组尚未适配"类丢弃时统一追加一次说明（逐条说明里不再重复那段括号注）
            if (droppedForeign > 0) {
                addBaubleNoticeOnce(baubleNotes);
            }
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 饰品整体恢复失败（不影响女仆导入）: {}", t.toString());
        }
    }
    private static ItemStack tryParseFullBauble(EntityMaid maid, CompoundTag entry,
                                                boolean sameVersion, int sourceMojangDataVersion) {
        try {
            if (sameVersion) {
                return Services.PLATFORM.get().parseItemStack(maid.level().registryAccess(), entry);
            }
            if (sourceMojangDataVersion > 0) {
                return Services.PLATFORM.get().convertItemStackNbt(
                        maid.level().registryAccess(), entry, sourceMojangDataVersion);
            }
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 饰品完整状态恢复失败，回退全新化: {}", t.toString());
        }
        return ItemStack.EMPTY;
    }
    private static Component baubleItemLabel(String id) {
        if (id != null && !id.isEmpty()) {
            Item item = resolveItem(id);
            if (item != null && !Items.AIR.equals(item)) {
                return item.getName(new ItemStack(item));
            }
            return Component.literal(id);
        }
        return Component.literal("?");
    }
    private static boolean baubleMatchesList(String id, Item item, List<String> list) {
        if (list == null || list.isEmpty()) {
            return false;
        }
        // 匹配侧再归一化一次（双保险）：即使清单由旧版本或外部工具写入未小写，也不会漏判。
        if (id != null && !id.isEmpty()
                && list.contains(id.toLowerCase(java.util.Locale.ROOT))) {
            return true;
        }
        String name = displayNameOf(item);
        return name != null && list.contains(name.toLowerCase(java.util.Locale.ROOT));
    }
    public static void addBaubleNoticeOnce(List<Component> notes) {
        if (notes == null) {
            return;
        }
        Component notice = Component.translatable("maid_file_manager.bauble.notice");
        if (!notes.contains(notice)) {
            notes.add(0, notice);
        }
    }
    private static void addBaubleNote(List<Component> notes, String langKey, String id) {
        if (notes == null) {
            return;
        }
        if ("maid_file_manager.import.bauble.foreign".equals(langKey)) {
            // 明确责任方与下一步：是"某个模组没声明支持迁移"，不是档案管理器的故障，
            // 玩家应去找该模组的作者（第二参数给出模组标识，避免玩家不知道是谁）。
            notes.add(Component.translatable(langKey, baubleItemLabel(id),
                    Services.PLATFORM.get().getModDisplayName(namespaceOf(id))));
            return;
        }
        notes.add(Component.translatable(langKey, baubleItemLabel(id)));
    }
    private static boolean isBaubleAllowed(String namespace) {
        // OP 按模组禁用：同时管住女仆自带饰品栏与 Curios 槽位（否则放 Curios 槽里即可绕过）
        if (namespace != null && MaidConfigManager.getBaubleBlockedNamespaces().contains(namespace)) {
            return false;
        }
        return MaidBaubleCarrierRegistry.isNamespaceCarriable(namespace);
    }
    public static List<String> sanitizeBaubleBlockedList(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                continue;
            }
            String trimmed = id.trim();
            if (isBaubleAllowed(namespaceOf(trimmed)) && !out.contains(trimmed)) {
                out.add(trimmed);
            }
        }
        return List.copyOf(out);
    }
    public static boolean allowCuriosItem(String itemId, String namespace, String slotIdentifier) {
        if (!MaidConfigManager.isCarryCurios()) {
            return false;
        }
        if (MaidBaubleCarrierRegistry.isCuriosSlotForbidden(slotIdentifier)) {
            return false;
        }
        if (!MaidBaubleCarrierRegistry.isCuriosItemCarriable(namespace, slotIdentifier)) {
            return false;
        }
        String slotKey = slotIdentifier == null ? "" : slotIdentifier.toLowerCase(java.util.Locale.ROOT);
        if (MaidConfigManager.getBaubleBlockedNamespaces().contains(namespace)
                || MaidConfigManager.getBaubleBlockedCuriosSlots().contains(slotKey)) {
            return false;
        }
        Item item = resolveCuriosItem(itemId);
        if (baubleMatchesList(itemId, item, MaidConfigManager.getBaubleBlockedList())) {
            return false;
        }
        return MaidConfigManager.isBaubleWhitelistPass(itemId, item == null ? null : displayNameOf(item));
    }
    private static Item resolveCuriosItem(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return null;
        }
        try {
            net.minecraft.resources.ResourceLocation key =
                    net.minecraft.resources.ResourceLocation.tryParse(itemId);
            if (key != null && net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(key)) {
                return net.minecraft.core.registries.BuiltInRegistries.ITEM.get(key);
            }
        } catch (Throwable ignored) {
            // 解析失败按缺失处理
        }
        return null;
    }
    public static Component curiosDropTip() {
        CuriosBaubleFilter.Stats stats = CuriosBaubleFilter.takeBatch();
        if (!stats.hasDrops()) {
            return null;
        }
        net.minecraft.network.chat.MutableComponent tip =
                Component.translatable("maid_file_manager.export.curios.dropped",
                        stats.dropped(), displayNamesText(stats));
        if (stats.forbiddenItems() > 0) {
            tip.append(Component.translatable("maid_file_manager.export.curios.forbidden",
                    stats.forbiddenSlots(), stats.forbiddenItems()));
        }
        if (stats.missingItems() > 0) {
            tip.append(Component.translatable("maid_file_manager.curios.missing", stats.missingItems()));
        }
        if (stats.dropped() > 0) {
            tip.append(Component.translatable("maid_file_manager.bauble.notice"));
        }
        return tip;
    }
    private static String displayNamesText(CuriosBaubleFilter.Stats stats) {
        return stats.sortedNamespaces().entrySet().stream()
                .map(e -> Services.PLATFORM.get().getModDisplayName(e.getKey()) + ":" + e.getValue())
                .collect(java.util.stream.Collectors.joining(", "));
    }
    private static Item resolveItem(String id) {
        try {
            net.minecraft.resources.ResourceLocation rl = net.minecraft.resources.ResourceLocation.tryParse(id);
            return rl == null ? null
                    : net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 解析物品 {} 失败: {}", id, t.toString());
            return null;
        }
    }
    private static String namespaceOf(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(0, colon) : "minecraft";
    }
    private static String displayNameOf(Item item) {
        if (item == null || Items.AIR.equals(item)) {
            return null;
        }
        try {
            return item.getName(new ItemStack(item)).getString();
        } catch (Throwable t) {
            return null;
        }
    }
    public static Component withBaubleDetails(ImportResult result) {
        List<Component> notes = result.baubleNotes();
        if (notes == null || notes.isEmpty()) {
            return result.message();
        }
        net.minecraft.network.chat.MutableComponent out =
                result.message().copy();
        net.minecraft.network.chat.MutableComponent detail = Component.translatable("maid_file_manager.import.bauble_details");
        int shown = Math.min(notes.size(), 5);
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                    detail.append(Component.translatable("maid_file_manager.list_separator.newline"));
            }
            detail.append(notes.get(i));
        }
        if (notes.size() > shown) {
            detail.append(Component.translatable(
                    "maid_file_manager.import.bauble.more", notes.size() - shown));
        }
        out.append(detail);
        return out;
    }

    /**
     * 单只导入的完整反馈：饰品说明 + 附加数据未迁移说明。
     *
     * <p>两个通道都要拼，否则玩家只看到「导入成功」，不知道有段数据没跟过来。
     * 顺序是饰品在前（更常见）、附加数据在后。
     */
    public static Component withImportDetails(ImportResult result) {
        net.minecraft.network.chat.MutableComponent out = result.message().copy();
        List<Component> notes = result.baubleNotes();
        if (notes != null && !notes.isEmpty()) {
            net.minecraft.network.chat.MutableComponent detail =
                    Component.translatable("maid_file_manager.import.bauble_details");
            int shown = Math.min(notes.size(), 5);
            for (int i = 0; i < shown; i++) {
                if (i > 0) {
                    detail.append(Component.translatable("maid_file_manager.list_separator.newline"));
                }
                detail.append(notes.get(i));
            }
            if (notes.size() > shown) {
                detail.append(Component.translatable(
                        "maid_file_manager.import.bauble.more", notes.size() - shown));
            }
            out.append(detail);
        }
        List<Component> migrations = result.migrationNotes();
        if (migrations != null && !migrations.isEmpty()) {
            net.minecraft.network.chat.MutableComponent detail =
                    Component.translatable("maid_file_manager.import.migration_details");
            int shown = Math.min(migrations.size(), 5);
            for (int i = 0; i < shown; i++) {
                if (i > 0) {
                    detail.append(Component.translatable("maid_file_manager.list_separator.newline"));
                }
                detail.append(migrations.get(i));
            }
            if (migrations.size() > shown) {
                detail.append(Component.translatable(
                        "maid_file_manager.import.migration.more", migrations.size() - shown));
            }
            out.append(detail);
        }
        return out;
    }
}
