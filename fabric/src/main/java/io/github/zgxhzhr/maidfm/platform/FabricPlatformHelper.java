package io.github.zgxhzhr.maidfm.platform;

import io.github.zgxhzhr.maidfm.Constants;
import io.github.zgxhzhr.maidfm.platform.services.IPlatformHelper;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Dynamic;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FabricPlatformHelper implements IPlatformHelper {

    // Orihime（Fabric 版 TLM）的 BaubleItemHandler 继承 cn.sh1rocu...ItemStackHandler，
    // 其 setSize(int)/setStackInSlot(int,ItemStack)/getSlots() 方法名已在
    // Orihime 1.20.1 / 1.21.1 两份源码中逐一核实（见 TouhouLittleMaid-Orihime-*）。
    // 编译期工程只挂 NeoForge 版 TLM jar，无法直接引用该类，故在此集中反射并缓存。
    // volatile：反射解析可能在网络/服务端线程首次触发，保证方法句柄对其它线程立即可见
    private static volatile Method mGetSlots;
    private static volatile Method mSetSize;
    private static volatile Method mSetStack;
    private static volatile Method mGetStack;
    private static volatile boolean baubleMethodsResolved;

    // 运行时持久化标签缓存（按实体 UUID 索引）。
    // Fabric 没有万法皆通附属（touhou_little_maid_spell），禁药水服务器再导出场景极罕见；
    // 且 Fabric 缺少 Forge 的 PersistentData 机制，引入 mixin 注入 EntityMaid 字段成本过高。
    // 这里用进程内 ConcurrentHashMap 做运行时缓存：运行时禁药水再导出能工作，服务器重启后丢失。
    // 实体从世界卸载时不会自动清理（依赖实体 UUID 唯一性 + JVM GC 后条目滞留，内存开销可忽略）。
    private static final ConcurrentHashMap<UUID, CompoundTag> EFFECTS_CACHE = new ConcurrentHashMap<>();

    @Override
    public String getPlatformName() {
        return "Fabric";
    }

    @Override
    public boolean isModLoaded(String modId) {

        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {

        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    @Override
    public String getModVersion(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("");
    }

    @Override
    public String getMcVersion() {
        return SharedConstants.getCurrentVersion().getName();
    }

    @Override
    public Path getGameDir() {
        return FabricLoader.getInstance().getGameDir().toAbsolutePath();
    }

    /**
     * 解析运行时饰品 handler 方法（成功后只解析一次）；handler 为 null 或方法缺失时返回 false。
     * 注意：handler 为 null（实体饰品能力尚未初始化的时序窗口）不得标记为"已解析"，
     * 否则后续真实 handler 出现时会被永久毒化为"方法缺失"。
     */
    private static boolean ensureBaubleMethods(Object handler) {
        if (handler == null) {
            return false;
        }
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
            Constants.LOG.error("[maid_file_manager] Fabric 饰品 handler 方法解析失败，饰品将无法恢复: {}",
                    t.toString());
        }
        return mGetSlots != null && mSetSize != null && mSetStack != null && mGetStack != null;
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
     */
    @Override
    public ItemStack baubleGetStack(EntityMaid maid, int slot) {
        try {
            Object handler = maid.getMaidBauble();
            if (!ensureBaubleMethods(handler)) {
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
     * 1.21 物品以数据组件形式保存，经 ItemStack.parse 用当前世界注册表解析，
     * 保留附魔/耐久/全部组件；解析失败返回 EMPTY，由调用方回退全新化。
     */
    @Override
    public ItemStack parseItemStack(RegistryAccess registries, CompoundTag tag) {
        try {
            return ItemStack.parse(registries, tag).orElse(ItemStack.EMPTY);
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] Fabric parseItemStack 失败，回退全新化: {}", t.toString());
            return ItemStack.EMPTY;
        }
    }

    /**
     * 跨版本物品 NBT 转换（1.21 平台方向：升级）。
     * 旧 1.20 物品 NBT → 当前版本用 Mojang DataFixerUpper 做格式升级，
     * 尽可能保留附魔/耐久/无法破坏/属性修饰符等组件。
     * DFU 只能升不能降：来源版本缺失/不低于当前版本时返回 EMPTY，由调用方回退全新化。
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
            Constants.LOG.warn("[maid_file_manager] Fabric 物品 NBT 跨版本升级失败，回退全新化: {}", t.toString());
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
            Constants.LOG.warn("[maid_file_manager] Fabric serializeItemStack 失败: {}", t.toString());
            return new CompoundTag();
        }
    }

    @Override
    public CompoundTag getStoredEffects(Entity entity) {
        return EFFECTS_CACHE.get(entity.getUUID());
    }

    @Override
    public void storeEffects(Entity entity, CompoundTag effectsTag) {
        if (effectsTag == null) {
            EFFECTS_CACHE.remove(entity.getUUID());
        } else {
            EFFECTS_CACHE.put(entity.getUUID(), effectsTag);
        }
    }

    /**
     * 读取女仆实体上持久化的档案 NBT。
     * Fabric 无原版持久化标签 API，档案由 {@link io.github.zgxhzhr.maidfm.mixin.EntityMaidMixin}
     * 注入 {@code EntityMaid} 字段承载，并随实体存档读写；无则返回 null。
     */
    @Override
    public CompoundTag readMaidProfile(Entity entity) {
        try {
            if (entity instanceof io.github.zgxhzhr.maidfm.mixin.MaidProfileHolder holder) {
                return holder.maidfm$getProfile();
            }
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] Fabric readMaidProfile 失败: {}", t.toString());
        }
        return null;
    }

    /**
     * 将档案 NBT 写入女仆实体并随实体持久化；{@code tag} 为 null 时移除已有档案。
     */
    @Override
    public void writeMaidProfile(Entity entity, CompoundTag tag) {
        try {
            if (entity instanceof io.github.zgxhzhr.maidfm.mixin.MaidProfileHolder holder) {
                holder.maidfm$setProfile(tag);
            }
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] Fabric writeMaidProfile 失败: {}", t.toString());
        }
    }
}

