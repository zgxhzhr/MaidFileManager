package io.github.zgxhzhr.maidfm.data;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
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
            if (entry == null || !entry.contains(KEY_COMPONENTS, Tag.TAG_COMPOUND)) {
                // 没有 components 键就不是组件格式：交给调用方按常规路径处理
                return ItemStack.EMPTY;
            }
            CompoundTag components = entry.getCompound(KEY_COMPONENTS);

            // 组装 1.20 传统结构：id + Count + tag
            CompoundTag legacy = new CompoundTag();
            String id = entry.getString("id");
            if (id.isEmpty()) {
                return ItemStack.EMPTY;
            }
            legacy.putString("id", id);
            int count = 1;
            if (entry.contains("Count", Tag.TAG_ANY_NUMERIC)) {
                count = entry.getInt("Count");
            } else if (entry.contains("count", Tag.TAG_ANY_NUMERIC)) {
                count = entry.getInt("count");
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
            return ItemStack.parse(registries, legacy).orElse(ItemStack.EMPTY);
        } catch (Throwable t) {
            // 降级失败：返回空物品栈，调用方全新化；留日志不静默
            io.github.zgxhzhr.maidfm.Constants.LOG.warn(
                    "[maid_file_manager] 饰品组件格式降级失败，回退全新化: {}", t.toString());
            return ItemStack.EMPTY;
        }
    }

    /** 附魔：levels 组件（map）→ 1.20 Enchantments（list） */
    private static void convertEnchantments(CompoundTag components, CompoundTag tag) {
        if (!components.contains(KEY_ENCHANTMENTS, Tag.TAG_COMPOUND)) {
            return;
        }
        CompoundTag ench = components.getCompound(KEY_ENCHANTMENTS);
        if (!ench.contains("levels", Tag.TAG_COMPOUND)) {
            return;
        }
        CompoundTag levels = ench.getCompound("levels");
        ListTag out = new ListTag();
        for (String enchId : levels.getAllKeys()) {
            int lvl = levels.getInt(enchId);
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
        if (components.contains(KEY_DAMAGE, Tag.TAG_COMPOUND)) {
            CompoundTag dmg = components.getCompound(KEY_DAMAGE);
            if (dmg.contains("damage", Tag.TAG_ANY_NUMERIC)) {
                int damage = Math.max(0, dmg.getInt("damage"));
                if (damage > 0) {
                    tag.putInt("Damage", damage);
                }
            }
        } else if (components.contains(KEY_DAMAGE, Tag.TAG_ANY_NUMERIC)) {
            int damage = Math.max(0, components.getInt(KEY_DAMAGE));
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

    /** 属性修饰符：1.21 组件（type/amount/operation/slot/name/id）→ 1.20 列表（Name/Amount/Operation/SlotName/UUID） */
    private static void convertAttributeModifiers(CompoundTag components, CompoundTag tag) {
        if (!components.contains(KEY_ATTRIBUTE_MODIFIERS, Tag.TAG_COMPOUND)) {
            return;
        }
        CompoundTag mods = components.getCompound(KEY_ATTRIBUTE_MODIFIERS);
        if (!mods.contains("modifiers", Tag.TAG_LIST)) {
            return;
        }
        ListTag src = mods.getList("modifiers", Tag.TAG_COMPOUND);
        ListTag out = new ListTag();
        for (int i = 0; i < src.size(); i++) {
            CompoundTag m = src.getCompound(i);
            CompoundTag conv = new CompoundTag();
            String name = m.getString("name");
            conv.putString("Name", name.isEmpty() ? "unknown" : name);
            if (m.contains("amount", Tag.TAG_ANY_NUMERIC)) {
                conv.putDouble("Amount", m.getDouble("amount"));
            } else {
                continue;
            }
            conv.putInt("Operation", m.getInt("operation"));
            conv.putString("SlotName", resolveSlotName(m));
            UUID uuid = resolveModifierUuid(m, name);
            conv.putLong("UUIDMost", uuid.getMostSignificantBits());
            conv.putLong("UUIDLeast", uuid.getLeastSignificantBits());
            out.add(conv);
        }
        if (!out.isEmpty()) {
            tag.put("AttributeModifiers", out);
        }
    }

    /** 1.21 槽位字段：slot:{slot:".."} 或 slot:{slots:[..]}，取第一个；无则空串（视为全槽位） */
    private static String resolveSlotName(CompoundTag modifier) {
        CompoundTag slot = modifier.getCompound("slot");
        if (slot.contains("slot", Tag.TAG_STRING)) {
            return slot.getString("slot");
        }
        if (slot.contains("slots", Tag.TAG_LIST)) {
            ListTag slots = slot.getList("slots", Tag.TAG_STRING);
            if (!slots.isEmpty()) {
                return slots.getString(0);
            }
        }
        return "";
    }

    /** 修饰符 UUID：优先取 1.21 的 id（uuid 字符串），异常/缺失时按 类型+名称 派生确定性 UUID */
    private static UUID resolveModifierUuid(CompoundTag modifier, String name) {
        String idStr = modifier.getString("id");
        if (!idStr.isEmpty()) {
            try {
                return UUID.fromString(idStr);
            } catch (IllegalArgumentException ignored) {
                // 落入派生分支
            }
        }
        String type = modifier.getString("type");
        return UUID.nameUUIDFromBytes((type + "|" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 显示信息：custom_name → display.Name，lore → display.Lore（两者同为 JSON 文本组件串，直接透传） */
    private static void convertDisplay(CompoundTag components, CompoundTag tag) {
        boolean hasName = components.contains(KEY_CUSTOM_NAME, Tag.TAG_STRING);
        boolean hasLore = components.contains(KEY_LORE, Tag.TAG_LIST);
        if (!hasName && !hasLore) {
            return;
        }
        CompoundTag display = new CompoundTag();
        if (hasName) {
            String name = components.getString(KEY_CUSTOM_NAME);
            if (!name.isEmpty()) {
                display.putString("Name", name);
            }
        }
        if (hasLore) {
            ListTag lore = components.getList(KEY_LORE, Tag.TAG_STRING);
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
        if (!components.contains(KEY_CUSTOM_DATA, Tag.TAG_COMPOUND)) {
            return;
        }
        CompoundTag custom = components.getCompound(KEY_CUSTOM_DATA);
        for (String key : custom.getAllKeys()) {
            if (!tag.contains(key)) {
                tag.put(key, custom.get(key).copy());
            }
        }
    }
}
