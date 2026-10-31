package io.github.zgxhzhr.maidfm.service;

import io.github.zgxhzhr.maidfm.Constants;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * 药水效果的跨版本序列化。
 *
 * <p>从 {@code MaidTransferService} 抽出的独立职责。它解决的是一个纯数据问题：
 * 同一份实体 NBT 在 1.20.x 与 1.21+ 下，效果列表的键名与 ID 表示方式完全不同。
 *
 * <p>跨版本差异点：
 * <ul>
 *   <li>1.20.x save：{@code Id(int)}、{@code Amplifier}、{@code Duration}、{@code Ambient}、
 *       {@code ShowParticles}、{@code ShowIcon}，Forge 额外写 {@code forge:id(string)}</li>
 *   <li>1.21+ save：{@code id(string)}、{@code amplifier}、{@code duration}、{@code ambient}、
 *       {@code show_particles}、{@code show_icon}（小写驼峰）</li>
 *   <li>跨版本直读 {@code MobEffectInstance#load} 会失败：1.20 不识别 1.21 的 id 字符串；
 *       1.21 的 byId 兜底依赖注册表数字 ID，跨版本不稳定</li>
 * </ul>
 *
 * <p>normalized 统一存小写驼峰键名，导入端用 ResourceLocation 查
 * {@code BuiltInRegistries.MOB_EFFECT} 重建，<b>不依赖任何版本特定的字段名或数字 ID</b>。
 */
public final class MaidEffectCodec {

    /** 效果列表可能出现的两种键名（1.20 PascalCase / 1.21 小写） */
    private static final String[] EFFECT_LIST_KEYS = {"ActiveEffects", "active_effects"};

    private MaidEffectCodec() {
    }

    public static ListTag getEffectList(CompoundTag root) {
        for (String key : EFFECT_LIST_KEYS) {
            if (root.contains(key, Tag.TAG_LIST)) {
                return root.getList(key, Tag.TAG_COMPOUND);
            }
        }
        return null;
    }

    public static ListTag removeEffectList(CompoundTag root) {
        ListTag list = getEffectList(root);
        if (list != null) {
            for (String key : EFFECT_LIST_KEYS) {
                root.remove(key);
            }
        }
        return list;
    }

    public static ListTag buildNormalizedEffects(ListTag rawList) {
        ListTag out = new ListTag();
        for (int i = 0; i < rawList.size(); i++) {
            CompoundTag src = rawList.getCompound(i);
            CompoundTag item = new CompoundTag();
            // 解析效果 ResourceLocation 字符串
            //   1.20.x: 优先读 forge:id（Forge 写入），其次用 Id 数字 ID 反查
            //   1.21+:  直接读 id 字符串
            String effectId = "";
            if (src.contains("forge:id", Tag.TAG_STRING)) {
                effectId = src.getString("forge:id");
            } else if (src.contains("id", Tag.TAG_STRING)) {
                effectId = src.getString("id");
            }
            if (effectId.isEmpty() && src.contains("Id", Tag.TAG_ANY_NUMERIC)) {
                // 兜底：同版本导出时，数字 ID 反查 ResourceLocation
                int numericId = src.getInt("Id");
                net.minecraft.world.effect.MobEffect effect =
                        net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.byId(numericId);
                if (effect != null) {
                    net.minecraft.resources.ResourceLocation rl =
                            net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.getKey(effect);
                    if (rl != null) {
                        effectId = rl.toString();
                    }
                }
            }
            if (effectId.isEmpty()) {
                Constants.LOG.warn("[maid_file_manager] 药水效果 #{} 无法解析 ResourceLocation，已跳过", i);
                continue;
            }
            item.putString("id", effectId);
            // PascalCase 与小写驼峰双兼容读取（1.20 用 PascalCase，1.21 用小写驼峰）
            int amplifier = src.contains("Amplifier", Tag.TAG_INT)
                    ? src.getInt("Amplifier")
                    : src.getInt("amplifier");
            int duration = src.contains("Duration", Tag.TAG_INT)
                    ? src.getInt("Duration")
                    : src.getInt("duration");
            byte ambient = src.contains("Ambient", Tag.TAG_BYTE)
                    ? src.getByte("Ambient")
                    : src.getByte("ambient");
            byte showParticles = src.contains("ShowParticles", Tag.TAG_BYTE)
                    ? src.getByte("ShowParticles")
                    : src.getByte("show_particles");
            byte showIcon = src.contains("ShowIcon", Tag.TAG_BYTE)
                    ? src.getByte("ShowIcon")
                    : src.getByte("show_icon");
            item.putInt("amplifier", amplifier);
            item.putInt("duration", duration);
            item.putByte("ambient", ambient);
            item.putByte("show_particles", showParticles);
            item.putByte("show_icon", showIcon);
            out.add(item);
        }
        return out;
    }

    public static net.minecraft.world.effect.MobEffectInstance rebuildEffectFromNormalized(CompoundTag item) {
        try {
            String idStr = item.getString("id");
            if (idStr.isEmpty()) return null;
            net.minecraft.resources.ResourceLocation rl =
                    net.minecraft.resources.ResourceLocation.tryParse(idStr);
            if (rl == null) return null;
            net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect =
                    net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.getHolder(rl).orElse(null);
            if (effect == null) {
                Constants.LOG.warn("[maid_file_manager] 跨版本恢复：目标世界未注册效果 {}，已跳过", idStr);
                return null;
            }
            int duration = item.getInt("duration");
            int amplifier = item.getInt("amplifier");
            boolean ambient = item.getByte("ambient") != 0;
            boolean showParticles = item.getByte("show_particles") != 0;
            boolean showIcon = item.getByte("show_icon") != 0;
            return new net.minecraft.world.effect.MobEffectInstance(
                    effect, duration, amplifier, ambient, showParticles, showIcon);
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] rebuildEffectFromNormalized 失败: {}", t.toString());
            return null;
        }
    }
}
