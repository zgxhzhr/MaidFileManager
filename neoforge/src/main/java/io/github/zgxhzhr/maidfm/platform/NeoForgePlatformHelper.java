package io.github.zgxhzhr.maidfm.platform;

import io.github.zgxhzhr.maidfm.Constants;
import io.github.zgxhzhr.maidfm.platform.services.IPlatformHelper;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
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
}
