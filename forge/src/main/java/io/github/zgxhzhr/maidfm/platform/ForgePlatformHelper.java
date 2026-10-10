package io.github.zgxhzhr.maidfm.platform;

import io.github.zgxhzhr.maidfm.Constants;
import io.github.zgxhzhr.maidfm.platform.services.IPlatformHelper;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
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
    // 档案界面读取饰品图标需要按槽位取物品（与 Fabric/Orihime 版同名反射）
    private static Method mGetStack;
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
            mGetStack = c.getMethod("getStackInSlot", int.class);
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
     * 读取饰品栏指定槽位的物品（档案界面的饰品图标需要）。
     * 槽位越界/读取失败返回空物品栈，绝不抛出（不影响档案其它字段展示）。
     * mGetStack 独立判空：即使三个既有方法已解析，getStackInSlot 缺失时也不影响它们。
     */
    @Override
    public ItemStack baubleGetStack(EntityMaid maid, int slot) {
        try {
            Object handler = maid.getMaidBauble();
            if (!ensureBaubleMethods(handler) || mGetStack == null) {
                return ItemStack.EMPTY;
            }
            Object stack = mGetStack.invoke(handler, slot);
            return stack instanceof ItemStack is ? is : ItemStack.EMPTY;
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] baubleGetStack 失败: {}", t.toString());
            return ItemStack.EMPTY;
        }
    }

    /**
     * 1.20.x 物品 NBT 为 id+Count+tag 结构，ItemStack.of 直接完整还原
     * （附魔/耐久等全部 tag 状态）；registries 参数在 1.20 不需要，仅为与 1.21 接口对齐。
     */
    @Override
    public ItemStack parseItemStack(RegistryAccess registries, CompoundTag tag) {
        try {
            return ItemStack.of(tag);
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] Forge parseItemStack 失败，回退全新化: {}", t.toString());
            return ItemStack.EMPTY;
        }
    }

    /**
     * 1.20.1 平台没有 References.ITEM_STACK（Mojang 在 1.20.2 才引入该引用），
     * DataFixerUpper 只能升不能降，无法直接驱动物品 NBT 格式升级。
     * 反向跨版本（源 DataVersion 高于 1.20.1 的 1.20.5+ / 1.21.x 组件格式）时，
     * 走 {@link io.github.zgxhzhr.maidfm.data.NbtDowngrade} 手动降级（组件 → 1.20 tag），
     * 保留附魔/耐久/无法破坏/属性修饰符；其余情形返回空物品栈，调用方回退全新化。
     */
    @Override
    public ItemStack convertItemStackNbt(RegistryAccess registries, CompoundTag legacyTag, int sourceDataVersion) {
        if (sourceDataVersion > io.github.zgxhzhr.maidfm.data.NbtVersion.DATA_VERSION_1_20_1) {
            return io.github.zgxhzhr.maidfm.data.NbtDowngrade.downgradeItemStackNbt(legacyTag);
        }
        return ItemStack.EMPTY;
    }

    /**
     * 1.20.x 物品序列化：直接 {@code stack.save(new CompoundTag())} 写入 id+Count+tag 结构，
     * 完整保留附魔/耐久/自定义 tag。1.20 无数据组件，registries 参数不使用。空物品返回空标签，
     * 序列化异常一律捕获并返回空标签（档案界面丢弃该图标，不影响其它字段展示）。
     */
    @Override
    public CompoundTag serializeItemStack(RegistryAccess registries, ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) {
                return new CompoundTag();
            }
            return stack.save(new CompoundTag());
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] Forge serializeItemStack 失败: {}", t.toString());
            return new CompoundTag();
        }
    }

    /**
     * Forge 持久化数据键名：用 modId 命名空间前缀避免与其他模组冲突。
     * ForgeData（entity.getPersistentData() 返回的 CompoundTag）会被写入实体 NBT 的 "ForgeData" 键下，
     * 随实体一起保存到存档，服务器重启后数据仍在。
     */
    private static final String KEY_EFFECTS = "maid_file_manager:stored_effects";
    /** 女仆档案 NBT 键名（与 NeoForge 版一致），同样落在 ForgeData 下随实体保存 */
    private static final String KEY_PROFILE = "maid_file_manager:profile";

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

    /**
     * 读取女仆实体上持久化的档案 NBT（键 {@code maid_file_manager:profile}）。
     * Forge 有 PersistentData 机制，档案随 ForgeData 写入实体 NBT 一并存档，服务器重启后仍在；
     * 无则返回 null。
     */
    @Override
    public CompoundTag readMaidProfile(Entity entity) {
        try {
            CompoundTag persistentData = entity.getPersistentData();
            if (persistentData.contains(KEY_PROFILE, CompoundTag.TAG_COMPOUND)) {
                return persistentData.getCompound(KEY_PROFILE);
            }
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] Forge readMaidProfile 失败: {}", t.toString());
        }
        return null;
    }

    /**
     * 将档案 NBT 写入女仆实体并随实体持久化；{@code tag} 为 null 时移除已有档案。
     */
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
            Constants.LOG.warn("[maid_file_manager] Forge writeMaidProfile 失败: {}", t.toString());
        }
    }
}
