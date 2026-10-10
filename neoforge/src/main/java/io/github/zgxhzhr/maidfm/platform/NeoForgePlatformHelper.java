package io.github.zgxhzhr.maidfm.platform;

import io.github.zgxhzhr.maidfm.Constants;
import io.github.zgxhzhr.maidfm.platform.services.IPlatformHelper;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.nio.file.Path;

public class NeoForgePlatformHelper implements IPlatformHelper {

    @Override
    public String getPlatformName() {
        return "NeoForge";
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLLoader.isProduction();
    }

    @Override
    public String getModVersion(String modId) {
        return ModList.get().getModContainerById(modId)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("");
    }

    @Override
    public String getMcVersion() {
        return SharedConstants.getCurrentVersion().getName();
    }

    @Override
    public Path getGameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    // TLM（NeoForge 版）的 BaubleItemHandler 直接继承
    // net.neoforged.neoforge.items.ItemStackHandler（javap 已核实），
    // 三个方法均为 public，无需反射：getSlots()/setSize(int)/setStackInSlot(int,ItemStack)。

    @Override
    public int baubleGetSlots(EntityMaid maid) {
        return ((ItemStackHandler) maid.getMaidBauble()).getSlots();
    }

    @Override
    public void baubleResize(EntityMaid maid, int slots) {
        ((ItemStackHandler) maid.getMaidBauble()).setSize(slots);
    }

    @Override
    public void baubleSetStack(EntityMaid maid, int slot, ItemStack stack) {
        ((ItemStackHandler) maid.getMaidBauble()).setStackInSlot(slot, stack);
    }

    @Override
    public ItemStack baubleGetStack(EntityMaid maid, int slot) {
        try {
            return ((ItemStackHandler) maid.getMaidBauble()).getStackInSlot(slot);
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] NeoForge baubleGetStack 失败: {}", t.toString());
            return ItemStack.EMPTY;
        }
    }

    /**
     * 1.21 物品以数据组件形式保存，必须经 ItemStack.parse(HolderLookup.Provider, CompoundTag)
     * 用当前世界注册表解析才能保留附魔/耐久/全部组件；RegistryAccess 即 HolderLookup.Provider。
     * 解析失败（组件损坏/物品不存在）返回 EMPTY，由调用方回退全新化。
     */
    @Override
    public ItemStack parseItemStack(RegistryAccess registries, CompoundTag tag) {
        try {
            return ItemStack.parse(registries, tag).orElse(ItemStack.EMPTY);
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] NeoForge parseItemStack 失败，回退全新化: {}", t.toString());
            return ItemStack.EMPTY;
        }
    }

    /**
     * 跨版本物品 NBT 转换（1.21 平台方向：升级）。
     * 借助 Mojang 官方 DataFixerUpper（随游戏捆绑分发）把 1.20 系旧格式
     * （id+Count+tag）升级为当前版本的组件格式（id+count+components），从而保留无法破坏、
     * 耐久、附魔、属性修饰符等属性。DFU 只能升不能降，源版本未知或已不低于当前版本时直接返回空。
     */
    @Override
    public ItemStack convertItemStackNbt(RegistryAccess registries, CompoundTag legacyTag, int sourceDataVersion) {
        try {
            int current = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
            if (sourceDataVersion <= 0 || sourceDataVersion >= current) {
                return ItemStack.EMPTY;
            }
            Dynamic<?> upgraded = DataFixers.getDataFixer().update(References.ITEM_STACK,
                    new Dynamic<>(NbtOps.INSTANCE, legacyTag), sourceDataVersion, current);
            if (upgraded.getValue() instanceof CompoundTag tag) {
                return ItemStack.parse(registries, tag).orElse(ItemStack.EMPTY);
            }
            return ItemStack.EMPTY;
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] NeoForge 物品 NBT 跨版本升级失败，回退全新化: {}", t.toString());
            return ItemStack.EMPTY;
        }
    }

    /**
     * 1.21 物品序列化必须经 ItemStack.save(HolderLookup.Provider) 才能完整保留数据组件
     * （附魔/耐久/自定义组件）；RegistryAccess 即 HolderLookup.Provider。空物品返回空标签。
     */
    @Override
    public CompoundTag serializeItemStack(RegistryAccess registries, ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) {
                return new CompoundTag();
            }
            net.minecraft.nbt.Tag saved = stack.save(registries);
            return saved instanceof CompoundTag ct ? ct : new CompoundTag();
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] NeoForge serializeItemStack 失败: {}", t.toString());
            return new CompoundTag();
        }
    }

    /**
     * NeoForge 持久化数据键名：用 modId 命名空间前缀避免与其他模组冲突。
     * entity.getPersistentData() 返回的 CompoundTag 会被写入实体 NBT 的 "ForgeData" 键下，
     * 随实体一起保存到存档，服务器重启后数据仍在（与 Forge 行为一致）。
     */
    private static final String KEY_EFFECTS = "maid_file_manager:stored_effects";

    @Override
    public CompoundTag getStoredEffects(Entity entity) {
        try {
            CompoundTag persistentData = entity.getPersistentData();
            if (persistentData.contains(KEY_EFFECTS, CompoundTag.TAG_COMPOUND)) {
                return persistentData.getCompound(KEY_EFFECTS);
            }
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] NeoForge getStoredEffects 失败: {}", t.toString());
        }
        return null;
    }

    @Override
    public void storeEffects(Entity entity, CompoundTag effectsTag) {
        try {
            CompoundTag persistentData = entity.getPersistentData();
            if (effectsTag == null) {
                persistentData.remove(KEY_EFFECTS);
            } else {
                persistentData.put(KEY_EFFECTS, effectsTag);
            }
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] NeoForge storeEffects 失败: {}", t.toString());
        }
    }

    /** 女仆档案持久化键名（同样用 modId 命名空间前缀隔离） */
    private static final String KEY_PROFILE = "maid_file_manager:profile";

    @Override
    public CompoundTag readMaidProfile(Entity entity) {
        try {
            CompoundTag persistentData = entity.getPersistentData();
            if (persistentData.contains(KEY_PROFILE, CompoundTag.TAG_COMPOUND)) {
                return persistentData.getCompound(KEY_PROFILE);
            }
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] NeoForge readMaidProfile 失败: {}", t.toString());
        }
        return null;
    }

    @Override
    public void writeMaidProfile(Entity entity, CompoundTag tag) {
        try {
            CompoundTag persistentData = entity.getPersistentData();
            if (tag == null) {
                persistentData.remove(KEY_PROFILE);
            } else {
                persistentData.put(KEY_PROFILE, tag);
            }
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] NeoForge writeMaidProfile 失败: {}", t.toString());
        }
    }

    /** 导入产物标记键（落在 NeoForgeData 下，即 KubeJS 的 entity.persistentData） */
    private static final String KEY_IMPORTED = Constants.IMPORTED_NBT_KEY;

    /**
     * 在女仆实体的持久化标签上写入导入产物标记。
     * NeoForge 的 {@code getPersistentData()} 会被写入实体 NBT 的 {@code NeoForgeData} 键随实体保存，
     * 也正是 KubeJS 的 {@code entity.persistentData}，脚本可直接读取。
     */
    @Override
    public void markMaidImported(Entity entity) {
        try {
            entity.getPersistentData().putBoolean(KEY_IMPORTED, true);
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] NeoForge markMaidImported 失败: {}", t.toString());
        }
    }

    @Override
    public boolean isMaidImported(Entity entity) {
        try {
            return entity.getPersistentData().getBoolean(KEY_IMPORTED);
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] NeoForge isMaidImported 失败: {}", t.toString());
            return false;
        }
    }
}
