package io.github.zgxhzhr.maidfm.data;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * 1.21+ 数据组件格式物品 NBT → 1.20 传统 tag 格式的手动降级转换。
 *
 * <p>背景：DataFixerUpper 只能把低版本格式升级到高版本，无法反向降级。
 * 因此 1.21.x 导出的 .maid 文件（饰品条目为 {@code id/Count/components} 结构）
 * 导入到 1.20.1 时，平台层不能走 DFU，只能按已知组件键逐一手动折算回
 * 1.20 的 {@code tag} 传统结构，再交给 {@code ItemStack.parse} 完整解析。
 *
 * <p>覆盖的组件（与 1.20 键的对应关系）：
 * <ul>
 *   <li>{@code minecraft:enchantments}（map 结构 {@code levels:{附魔ID:等级}}）
 *       → {@code tag.Enchantments}（list 结构 {@code [{id,lvl}]}）；</li>
 *   <li>{@code minecraft:damage} → {@code tag.Damage}；</li>
 *   <li>{@code minecraft:unbreakable} → {@code tag.Unbreakable}；</li>
 *   <li>{@code minecraft:attribute_modifiers}（含 slot 分组）→ {@code tag.AttributeModifiers}；</li>
 *   <li>{@code minecraft:custom_name} → {@code tag.display.Name}；</li>
 *   <li>{@code minecraft:lore} → {@code tag.display.Lore}；</li>
 *   <li>{@code minecraft:custom_data} → 合并进 {@code tag}（1.20 的自定义数据直接放 tag 下）。</li>
 * </ul>
 *
 * <p>未识别的组件键安全忽略；整体转换异常时返回空物品栈，由调用方回退全新化
 * （日志留痕，非静默失败）。仅在 1.20 平台被调用（源 DataVersion 高于 1.20.1），
 * 放 common 层仅为两个 1.20 平台复用，1.21 平台不会触发。
 */
public final class NbtDowngrade {

    private NbtDowngrade() {
    }

    /** 1.21 物品 NBT 的组件根键 */
    private static final String KEY_COMPONENTS = "components";
    private static final String KEY_ENCHANTMENTS = "minecraft:enchantments";
    private static final String KEY_DAMAGE = "minecraft:damage";
    private static final String KEY_UNBREAKABLE = "minecraft:unbreakable";
    private static final String KEY_ATTRIBUTE_MODIFIERS = "minecraft:attribute_modifiers";
    private static final String KEY_CUSTOM_NAME = "minecraft:custom_name";
    private static final String KEY_LORE = "minecraft:lore";
    private static final String KEY_CUSTOM_DATA = "minecraft:custom_data";

    /**
     * 把 1.21+ 组件格式的物品 NBT 降级为 1.20 传统 tag 格式并完整解析。
     * 任何一步异常都返回空物品栈（调用方据此全新化）。
     */
    public static ItemStack downgradeItemStackNbt(RegistryAccess registries, CompoundTag entry) {
        try {
            if (entry == null || !entry.contains(KEY_COMPONENTS)) {
                // 没有 components 键就不是组件格式：交给调用方按常规路径处理
                return ItemStack.EMPTY;
            }
            CompoundTag components = entry.getCompoundOrEmpty(KEY_COMPONENTS);

            // 组装 1.20 传统结构：id + Count + tag
            CompoundTag legacy = new CompoundTag();
            String id = entry.getStringOr("id", "");
            if (id.isEmpty()) {
                return ItemStack.EMPTY;
            }
            legacy.putString("id", id);
            int count = 1;
            if (entry.contains("Count")) {
                count = entry.getIntOr("Count", 0);
            } else if (entry.contains("count")) {
                count = entry.getIntOr("count", 0);
            }
            // Count 是 byte 字段：count > 127 时 (byte) 强转会溢出成负数
            //（1.21 模组物品允许 >127 堆叠时可达）→ 写出版本 1.20 侧无法表达的坏堆叠。
            // 1.20 的上限本来就是 127，超出部分夹到 127 并告警（第六轮 D3）。
            if (count > 127) {
                io.github.zgxhzhr.maidfm.Constants.LOG.warn("[maid_file_manager] 物品堆叠数 {} 超出 1.20 byte 上限，已夹到 127: id={}",
                        count, legacy.getStringOr("id", ""));
                count = 127;
            }
            legacy.putByte("Count", (byte) Math.max(1, count));

            CompoundTag tag = new CompoundTag();
            convertEnchantments(components, tag);
            convertDamage(components, tag);
            convertUnbreakable(components, tag);
            convertAttributeModifiers(components, tag);
            convertDisplay(components, tag);
            convertCustomData(components, tag);

            if (!tag.isEmpty()) {
                legacy.put("tag", tag);
            }
            return ItemStack.CODEC.parse(net.minecraft.resources.RegistryOps.create(NbtOps.INSTANCE, registries), legacy).result().orElse(ItemStack.EMPTY);
        } catch (Throwable t) {
            // 降级失败：返回空物品栈，调用方全新化；留日志不静默
            io.github.zgxhzhr.maidfm.Constants.LOG.warn(
                    "[maid_file_manager] 饰品组件格式降级失败，回退全新化: {}", t.toString());
            return ItemStack.EMPTY;
        }
    }

    /** 附魔：levels 组件（map）→ 1.20 Enchantments（list） */
    private static void convertEnchantments(CompoundTag components, CompoundTag tag) {
        if (!components.contains(KEY_ENCHANTMENTS)) {
            return;
        }
        CompoundTag ench = components.getCompoundOrEmpty(KEY_ENCHANTMENTS);
        if (!ench.contains("levels")) {
            return;
        }
        CompoundTag levels = ench.getCompoundOrEmpty("levels");
        ListTag out = new ListTag();
        for (String enchId : levels.keySet()) {
            int lvl = levels.getIntOr(enchId, 0);
            if (lvl <= 0 || enchId.isEmpty()) {
                continue;
            }
            CompoundTag e = new CompoundTag();
            e.putString("id", enchId);
            e.putInt("lvl", lvl);
            out.add(e);
        }
        if (!out.isEmpty()) {
            tag.put("Enchantments", out);
        }
    }

    /** 耐久：damage 组件 → tag.Damage */
    private static void convertDamage(CompoundTag components, CompoundTag tag) {
        if (components.contains(KEY_DAMAGE)) {
            CompoundTag dmg = components.getCompoundOrEmpty(KEY_DAMAGE);
            if (dmg.contains("damage")) {
                int damage = Math.max(0, dmg.getIntOr("damage", 0));
                if (damage > 0) {
                    tag.putInt("Damage", damage);
                }
            }
        } else if (components.contains(KEY_DAMAGE)) {
            int damage = Math.max(0, components.getIntOr(KEY_DAMAGE, 0));
            if (damage > 0) {
                tag.putInt("Damage", damage);
            }
        }
    }

    /** 无法破坏：unbreakable 组件 → tag.Unbreakable */
    private static void convertUnbreakable(CompoundTag components, CompoundTag tag) {
        if (components.contains(KEY_UNBREAKABLE)) {
            tag.putByte("Unbreakable", (byte) 1);
        }
    }

    /** 属性修饰符：1.21 组件（type/amount/operation/slot/name/id）→ 1.20.1 物品 AttributeModifiers（AttributeName/Name/Amount/Operation/Slot/UUID int[4]） */
    private static void convertAttributeModifiers(CompoundTag components, CompoundTag tag) {
        if (!components.contains(KEY_ATTRIBUTE_MODIFIERS)) {
            return;
        }
        CompoundTag mods = components.getCompoundOrEmpty(KEY_ATTRIBUTE_MODIFIERS);
        if (!mods.contains("modifiers")) {
            return;
        }
        ListTag src = mods.getListOrEmpty("modifiers");
        ListTag out = new ListTag();
        for (int i = 0; i < src.size(); i++) {
            CompoundTag m = src.getCompoundOrEmpty(i);
            CompoundTag conv = new CompoundTag();
            // 1.20.1 的物品属性修饰符必须带 AttributeName（1.21 是 type 字符串），缺了整条会被丢弃
            String type = m.getStringOr("type", "");
            if (type.isEmpty()) {
                continue;
            }
            conv.putString("AttributeName", stripNamespace(type));
            String name = m.getStringOr("name", "");
            conv.putString("Name", name.isEmpty() ? "unknown" : name);
            if (m.contains("amount")) {
                conv.putDouble("Amount", m.getDoubleOr("amount", 0.0));
            } else {
                continue;
            }
            // 1.21 的 operation 是字符串，1.20.1 要 0/1/2
            conv.putInt("Operation", parseOperation(m.get("operation")));
            // 1.20.1 物品格式的键是 Slot（不是 SlotName，SlotName 只用于实体属性）
            conv.putString("Slot", resolveSlotName(m));
            // 1.20.1 物品 UUID 是 int[4]（UUIDMost/UUIDLeast 是实体属性列表的格式）
            UUID uuid = resolveModifierUuid(m, name);
            conv.putIntArray("UUID", new int[]{
                    (int) (uuid.getMostSignificantBits() >> 32),
                    (int) uuid.getMostSignificantBits(),
                    (int) (uuid.getLeastSignificantBits() >> 32),
                    (int) uuid.getLeastSignificantBits()});
            out.add(conv);
        }
        if (!out.isEmpty()) {
            tag.put("AttributeModifiers", out);
        }
    }

    /** 1.21 的 operation 是字符串（add_value / add_multiplied_base / add_multiplied_total），1.20.1 要 int */
    private static int parseOperation(net.minecraft.nbt.Tag operation) {
        if (operation instanceof net.minecraft.nbt.StringTag stringTag) {
            return switch (stringTag.value()) {
                case "add_multiplied_base" -> 1;
                case "add_multiplied_total" -> 2;
                default -> 0;
            };
        }
        if (operation instanceof net.minecraft.nbt.NumericTag numericTag) {
            return numericTag.intValue();
        }
        return 0;
    }

    /** minecraft:generic.attack_damage → generic.attack_damage（1.20.1 的属性名不带命名空间） */
    private static String stripNamespace(String type) {
        int idx = type.indexOf(':');
        return idx >= 0 ? type.substring(idx + 1) : type;
    }
    /**
     * 解析属性修饰符的槽位。
     *
     * <p>格式随版本变化：
     * <ul>
     *   <li><b>1.21 / 1.21.1</b>：{@code slot} 是<b>字符串</b>（如 "chest"、"mainhand"）</li>
     *   <li><b>1.21.2+</b>：{@code slot} 是<b>复合</b>，形如 {@code {slot:"chest"}} 或 {@code {slots:["chest","legs"]}}</li>
     * </ul>
     *
     * <p>原实现直接 {@code getCompound("slot")}：对字符串调用会返回空复合，于是永远返回空串，
     * 降级后 1.20.1 侧的 {@code Slot} 只能落到默认值。这里按「先字符串、再复合」依次探测。
     */
    private static String resolveSlotName(CompoundTag modifier) {
        // 1.21 / 1.21.1：slot 是字符串
        if (modifier.contains("slot")) {
            return modifier.getStringOr("slot", "");
        }
        // 1.21.2+：slot 是复合
        if (modifier.contains("slot")) {
            CompoundTag slot = modifier.getCompoundOrEmpty("slot");
            if (slot.contains("slot")) {
                return slot.getStringOr("slot", "");
            }
            if (slot.contains("slots")) {
                ListTag slots = slot.getListOrEmpty("slots");
                if (!slots.isEmpty()) {
                    return slots.getStringOr(0, "");
                }
            }
        }
        // 其它键名兜底（部分模组直接写 Slot / slots）：
        if (modifier.contains("Slot")) {
            return modifier.getStringOr("Slot", "");
        }
        if (modifier.contains("slots")) {
            ListTag slots = modifier.getListOrEmpty("slots");
            if (!slots.isEmpty()) {
                return slots.getStringOr(0, "");
            }
        }
        return "";
    }

    /** 修饰符 UUID：优先取 1.21 的 id（uuid 字符串），异常/缺失时按 类型+名称 派生确定性 UUID */
    private static UUID resolveModifierUuid(CompoundTag modifier, String name) {
        String idStr = modifier.getStringOr("id", "");
        if (!idStr.isEmpty()) {
            try {
                return UUID.fromString(idStr);
            } catch (IllegalArgumentException ignored) {
                // 落入派生分支
            }
        }
        String type = modifier.getStringOr("type", "");
        return UUID.nameUUIDFromBytes((type + "|" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 显示信息：custom_name → display.Name，lore → display.Lore（两者同为 JSON 文本组件串，直接透传） */
    private static void convertDisplay(CompoundTag components, CompoundTag tag) {
        boolean hasName = components.contains(KEY_CUSTOM_NAME);
        boolean hasLore = components.contains(KEY_LORE);
        if (!hasName && !hasLore) {
            return;
        }
        CompoundTag display = new CompoundTag();
        if (hasName) {
            String name = components.getStringOr(KEY_CUSTOM_NAME, "");
            if (!name.isEmpty()) {
                display.putString("Name", name);
            }
        }
        if (hasLore) {
            ListTag lore = components.getListOrEmpty(KEY_LORE);
            if (!lore.isEmpty()) {
                display.put("Lore", lore.copy());
            }
        }
        if (!display.isEmpty()) {
            tag.put("display", display);
        }
    }

    /** 自定义数据：custom_data 组件的键直接并入 1.20 tag（不覆盖已转换的原版键） */
    private static void convertCustomData(CompoundTag components, CompoundTag tag) {
        if (!components.contains(KEY_CUSTOM_DATA)) {
            return;
        }
        CompoundTag custom = components.getCompoundOrEmpty(KEY_CUSTOM_DATA);
        for (String key : custom.keySet()) {
            if (!tag.contains(key)) {
                tag.put(key, custom.get(key).copy());
            }
        }
    }
}
