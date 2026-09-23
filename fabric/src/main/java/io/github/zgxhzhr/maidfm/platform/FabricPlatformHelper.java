package io.github.zgxhzhr.maidfm.platform;

import io.github.zgxhzhr.maidfm.Constants;
import io.github.zgxhzhr.maidfm.platform.services.IPlatformHelper;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
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
        } catch (Throwable t) {
            Constants.LOG.error("[maid_file_manager] Fabric 饰品 handler 方法解析失败，饰品将无法恢复: {}",
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
}

