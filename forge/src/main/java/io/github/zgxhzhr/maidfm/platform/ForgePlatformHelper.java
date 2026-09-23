package io.github.zgxhzhr.maidfm.platform;

import io.github.zgxhzhr.maidfm.Constants;
import io.github.zgxhzhr.maidfm.platform.services.IPlatformHelper;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;

import java.lang.reflect.Method;
import java.nio.file.Path;

public class ForgePlatformHelper implements IPlatformHelper {

    // TLM（Forge 版）的饰品栏继承 net.minecraftforge.items.ItemStackHandler，
    // 扩容/填槽走 setSize(int)/setStackInSlot(int,ItemStack)，槽位数走 getSlots()。
    // common 模块看不到 Forge 类，不能强转，故在平台层按方法名集中反射并缓存
    // （与 Fabric/Orihime 版保持同一套 IPlatformHelper 契约与调用方式）。
    private static Method mGetSlots;
    private static Method mSetSize;
    private static Method mSetStack;
    private static boolean baubleMethodsResolved;

    @Override
    public String getPlatformName() {
        return "Forge";
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

    /** 解析运行时饰品 handler 方法（只解析一次）；handler 为 null 或方法缺失时返回 false */
    private static boolean ensureBaubleMethods(Object handler) {
        if (baubleMethodsResolved) {
            return mGetSlots != null && mSetSize != null && mSetStack != null;
        }
        baubleMethodsResolved = true;
        try {
            Class<?> c = handler.getClass();
            mGetSlots = c.getMethod("getSlots");
            mSetSize = c.getMethod("setSize", int.class);
            mSetStack = c.getMethod("setStackInSlot", int.class, ItemStack.class);
        } catch (Throwable t) {
            Constants.LOG.error("[maid_file_manager] Forge 饰品 handler 方法解析失败，饰品将无法恢复: {}",
                    t.toString());
        }
        return mGetSlots != null && mSetSize != null && mSetStack != null;
    }

    @Override
    public int baubleGetSlots(EntityMaid maid) {
        try {
            Object handler = maid.getMaidBauble();
            if (!ensureBaubleMethods(handler)) {
                return -1;
            }
            return (Integer) mGetSlots.invoke(handler);
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] baubleGetSlots 失败: {}", t.toString());
            return -1;
        }
    }

    @Override
    public void baubleResize(EntityMaid maid, int slots) {
        try {
            Object handler = maid.getMaidBauble();
            if (!ensureBaubleMethods(handler)) {
                throw new IllegalStateException("饰品 handler 缺少 setSize 方法");
            }
            mSetSize.invoke(handler, slots);
        } catch (Throwable t) {
            throw new RuntimeException("baubleResize 失败", t);
        }
    }

    @Override
    public void baubleSetStack(EntityMaid maid, int slot, ItemStack stack) {
        try {
            Object handler = maid.getMaidBauble();
            if (!ensureBaubleMethods(handler)) {
                throw new IllegalStateException("饰品 handler 缺少 setStackInSlot 方法");
            }
            mSetStack.invoke(handler, slot, stack);
        } catch (Throwable t) {
            throw new RuntimeException("baubleSetStack 失败", t);
        }
    }

    /**
     * Forge 持久化数据键名：用 modId 命名空间前缀避免与其他模组冲突。
     * ForgeData（entity.getPersistentData() 返回的 CompoundTag）会被写入实体 NBT 的 "ForgeData" 键下，
     * 随实体一起保存到存档，服务器重启后数据仍在。
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
            Constants.LOG.warn("[maid_file_manager] Forge getStoredEffects 失败: {}", t.toString());
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
            Constants.LOG.warn("[maid_file_manager] Forge storeEffects 失败: {}", t.toString());
        }
    }
}
