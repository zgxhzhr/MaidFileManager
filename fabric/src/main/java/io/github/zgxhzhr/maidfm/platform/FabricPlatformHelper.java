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

    /**
     * 取模组显示名（玩家语言下的名字）。取不到时退化为 modId —— 提示功能不能因此出错。
     */
    @Override
    public String getModDisplayName(String modId) {
        if (modId == null || modId.isEmpty()) {
            return "";
        }
        try {
            return FabricLoader.getInstance().getModContainer(modId)
                    .map(container -> container.getMetadata().getName())
                    .orElse(modId);
        } catch (Throwable t) {
            return modId;
        }
    }
    @Override
    public java.util.Map<String, String> getAllMods() {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        try {
            for (net.fabricmc.loader.api.ModContainer container
                    : net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods()) {
                out.put(container.getMetadata().getId(), container.getMetadata().getName());
            }
        } catch (Throwable t) {
            // 加载器 API 差异不应让界面崩掉
        }
        return out;
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
        // 必须先判空再置位：否则 handler 为 null 时 getClass() 抛 NPE 被 catch 吞掉，
        // 而 baubleMethodsResolved 已被置 true，会导致本进程后续所有饰品恢复永久失效。
        if (handler == null) {
            return false;
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

    /**
     * 读取女仆实体上持久化的档案 NBT。
     * Fabric 无原版持久化标签 API，档案由 {@link io.github.zgxhzhr.maidfm.mixin.EntityMaidMixin}
     * 注入 {@code EntityMaid} 字段承载，并随实体存档读写；无则返回 null。
     */
    @Override
    public CompoundTag readMaidProfile(Entity entity) {
        try {
            if (entity instanceof MaidProfileHolder holder) {
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
            if (entity instanceof MaidProfileHolder holder) {
                holder.maidfm$setProfile(tag);
            }
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] Fabric writeMaidProfile 失败: {}", t.toString());
        }
    }

    /**
     * 在女仆实体上写入导入产物标记。
     * Fabric 无原版持久化标签 API，标记由 {@link io.github.zgxhzhr.maidfm.mixin.EntityMaidMixin}
     * 注入 {@code EntityMaid} 字段承载，并在实体保存时写入根 NBT 键，随实体存档与 .maid 文件迁移。
     */
    @Override
    public void markMaidImported(Entity entity) {
        try {
            if (entity instanceof MaidImportedHolder holder) {
                holder.maidfm$setImported(true);
            }
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] Fabric markMaidImported 失败: {}", t.toString());
        }
    }

    @Override
    public boolean isMaidImported(Entity entity) {
        try {
            if (entity instanceof MaidImportedHolder holder) {
                return holder.maidfm$isImported();
            }
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] Fabric isMaidImported 失败: {}", t.toString());
        }
        return false;
    }

    /**
     * 当前进程是否是「单人/局域网主机」。
     *
     * <p>判断分两步，顺序不能反：
     * <ol>
     *   <li>先判断运行环境是否客户端。该分支在专用服务器上是编译期常量，会被直接优化掉，
     *       绝不会加载 {@code Minecraft}——否则平台加载器会拦下并报 ERROR
     *       「Attempted to load class net/minecraft/client/Minecraft for invalid dist DEDICATED_SERVER」。</li>
     *   <li>客户端再反射取 {@code Minecraft.getInstance().getSingleplayerServer()}：
     *       非 null 就是集成服务器在跑。</li>
     * </ol>
     *
     * <p>用途见 {@link IPlatformHelper#isSingleplayer()}：集成服务器与客户端之间是内存直连，
     * 1.20.x 的 32767 字节 C2S 上限不生效。
     *
     * <p>判断失败一律按「非单人」处理——保守方向，最坏结果是多走一次分片上传，不丢数据。
     */
    @Override
    public boolean isSingleplayer() {
        if (net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType()
                != net.fabricmc.api.EnvType.CLIENT) {
            return false;
        }
        try {
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
            Object mc = mcClass.getMethod("getInstance").invoke(null);
            if (mc == null) {
                return false;
            }
            return mcClass.getMethod("getSingleplayerServer").invoke(mc) != null;
        } catch (Throwable t) {
            return false;
        }
    }
}

