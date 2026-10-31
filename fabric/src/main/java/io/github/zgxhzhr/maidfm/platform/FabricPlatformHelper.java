package io.github.zgxhzhr.maidfm.platform;

import io.github.zgxhzhr.maidfm.Constants;
import io.github.zgxhzhr.maidfm.platform.services.IPlatformHelper;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Dynamic;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Fabric 平台实现。
 *
 * <p>与 NeoForge 版相比只有四处加载器差异（模组查询、开发环境判定、游戏目录、饰品容器类型），
 * 物品 NBT 解析与档案读写全部是原版 API，逻辑与 NeoForge 版保持一致。
 */
public class FabricPlatformHelper implements IPlatformHelper {

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
            Optional<ModContainer> container = FabricLoader.getInstance().getModContainer(modId);
            return container.map(c -> c.getMetadata().getName()).orElse(modId);
        } catch (Throwable t) {
            return modId;
        }
    }

    @Override
    public Map<String, String> getAllMods() {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
                String id = container.getMetadata().getId();
                out.put(id, container.getMetadata().getName());
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
        try {
            return FabricLoader.getInstance().getModContainer(modId)
                    .map(c -> c.getMetadata().getVersion().getFriendlyString())
                    .orElse("");
        } catch (Throwable t) {
            // 与同类方法保持一致：查不到版本不该把调用方拖崩
            Constants.LOG.debug("[maid_file_manager] getModVersion 查询失败: {}", t.toString());
            return "";
        }
    }

    @Override
    public String getMcVersion() {
        return SharedConstants.getCurrentVersion().name();
    }

    @Override
    public Path getGameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    // ===== 饰品栏 =====
    //
    // TLM Fabric 版的 BaubleItemHandler 继承 TLM 自带的 shim
    // cn.sh1rocu.touhoulittlemaid.util.transfer.ItemStacksResourceHandler，
    // 后者基于 Fabric Transfer API（net.fabricmc.fabric.api.transfer.v1 包）。
    // 因此槽位读写走 ItemVariant + Transaction，与 NeoForge 的 ItemResource 不同。

    @Override
    public int baubleGetSlots(EntityMaid maid) {
        Object handler = baubleHandler(maid);
        if (handler == null) {
            return 0;
        }
        try {
            return (Integer) handler.getClass().getMethod("size").invoke(handler);
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] Fabric baubleGetSlots 失败: {}", t.toString());
            return 0;
        }
    }

    @Override
    public void baubleResize(EntityMaid maid, int slots) {
        // 与 NeoForge 版一致：容器槽位数在构造时确定，不支持运行期扩容。
        // 明确抛出让调用方按「扩容失败」处理（超出槽位的饰品走丢弃分支），绝不静默吞掉。
        throw new UnsupportedOperationException("Fabric Transfer API 容器不支持运行期扩容");
    }

    @Override
    public void baubleSetStack(EntityMaid maid, int slot, ItemStack stack) {
        Object handler = baubleHandler(maid);
        if (handler == null) {
            throw new IllegalStateException("未暴露饰品栏访问入口");
        }
        try {
            Class<?> variantCls = Class.forName("net.fabricmc.fabric.api.transfer.v1.item.ItemVariant");
            Class<?> txCls = Class.forName("net.fabricmc.fabric.api.transfer.v1.transaction.Transaction");
            Object variant = variantCls.getMethod("of", ItemStack.class).invoke(null, stack);
            Object tx = txCls.getMethod("openOuter").invoke(null);
            try {
                handler.getClass().getMethod("set", int.class, variantCls, int.class, txCls)
                        .invoke(handler, slot, variant, stack.getCount(), tx);
                txCls.getMethod("commit").invoke(tx);
            } finally {
                txCls.getMethod("close").invoke(tx);
            }
        } catch (Throwable t) {
            throw new RuntimeException("baubleSetStack failed", t);
        }
    }

    @Override
    public ItemStack baubleGetStack(EntityMaid maid, int slot) {
        Object handler = baubleHandler(maid);
        if (handler == null) {
            return ItemStack.EMPTY;
        }
        try {
            Object variant = handler.getClass().getMethod("getResource", int.class).invoke(handler, slot);
            int amount = (Integer) handler.getClass().getMethod("getAmountAsInt", int.class).invoke(handler, slot);
            if (variant == null || amount <= 0) {
                return ItemStack.EMPTY;
            }
            // ItemVariant.toStack(int) 会带上完整数据组件，比 NeoForge 侧按 Item 重建更能保留附魔/耐久。
            return (ItemStack) variant.getClass().getMethod("toStack", int.class).invoke(variant, amount);
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] Fabric baubleGetStack 失败: {}", t.toString());
            return ItemStack.EMPTY;
        }
    }

    // ===== 物品 NBT（与加载器无关，逻辑同 NeoForge 版） =====

    /**
     * 26.1 物品以数据组件形式保存，必须经 ItemStack.CODEC.parse(RegistryOps, CompoundTag)
     * 用当前世界注册表解析才能保留附魔/耐久/全部组件；RegistryAccess 即 HolderLookup.Provider。
     * 解析失败（组件损坏/物品不存在）返回 EMPTY，由调用方回退全新化。
     */
    @Override
    public ItemStack parseItemStack(RegistryAccess registries, CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return ItemStack.EMPTY;
        }
        try {
            return ItemStack.CODEC
                    .parse(net.minecraft.resources.RegistryOps.create(NbtOps.INSTANCE, registries), tag)
                    .result()
                    .orElse(ItemStack.EMPTY);
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 物品 NBT 解析失败，回退全新化: {}", t.toString());
            return ItemStack.EMPTY;
        }
    }

    /**
     * 跨版本物品升级：交给 Mojang 官方 DataFixerUpper，按源 DataVersion 升级到当前版本。
     * DFU 只升不降，源版本高于当前时返回 EMPTY 由调用方回退全新化。
     */
    @Override
    public ItemStack convertItemStackNbt(RegistryAccess registries, CompoundTag legacyTag, int sourceDataVersion) {
        if (legacyTag == null || legacyTag.isEmpty() || sourceDataVersion <= 0) {
            return ItemStack.EMPTY;
        }
        try {
            int targetDataVersion = SharedConstants.getCurrentVersion().dataVersion().version();
            if (sourceDataVersion >= targetDataVersion) {
                return ItemStack.EMPTY;
            }
            Dynamic<?> upgraded = DataFixers.getDataFixer().update(
                    References.ITEM_STACK, new Dynamic<>(NbtOps.INSTANCE, legacyTag),
                    sourceDataVersion, targetDataVersion);
            // DFU 返回 Dynamic<?>，必须取出底层 CompoundTag 再喂 CODEC（直接传 Dynamic 泛型不匹配）。
            if (upgraded.getValue() instanceof CompoundTag tag) {
                return ItemStack.CODEC
                        .parse(net.minecraft.resources.RegistryOps.create(NbtOps.INSTANCE, registries), tag)
                        .result()
                        .orElse(ItemStack.EMPTY);
            }
            return ItemStack.EMPTY;
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 物品跨版本升级失败，回退全新化: {}", t.toString());
            return ItemStack.EMPTY;
        }
    }

    @Override
    public CompoundTag serializeItemStack(RegistryAccess registries, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return new CompoundTag();
        }
        try {
            return (CompoundTag) ItemStack.CODEC
                    .encodeStart(net.minecraft.resources.RegistryOps.create(NbtOps.INSTANCE, registries), stack)
                    .result()
                    .orElseGet(CompoundTag::new);
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 物品 NBT 序列化失败: {}", t.toString());
            return new CompoundTag();
        }
    }

    // ===== 女仆档案（走 TLM 的 profile 字段，与加载器无关） =====

    // ===== 档案与导入标记（实体 attachment，等价于 NeoForge 的 getPersistentData） =====

    private static CompoundTag entityData(Entity entity) {
        return entity.getAttachedOrCreate(FabricMaidData.DATA).copy();
    }

    private static void setEntityData(Entity entity, CompoundTag tag) {
        entity.setAttached(FabricMaidData.DATA, tag);
    }

    @Override
    public CompoundTag readMaidProfile(Entity entity) {
        try {
            CompoundTag data = entity.getAttachedOrCreate(FabricMaidData.DATA);
            if (data.contains(Constants.PROFILE_NBT_KEY)) {
                return data.getCompoundOrEmpty(Constants.PROFILE_NBT_KEY);
            }
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] Fabric readMaidProfile 失败: {}", t.toString());
        }
        return null;
    }

    @Override
    public void writeMaidProfile(Entity entity, CompoundTag tag) {
        try {
            CompoundTag data = entityData(entity);
            if (tag == null) {
                data.remove(Constants.PROFILE_NBT_KEY);
            } else {
                data.put(Constants.PROFILE_NBT_KEY, tag);
            }
            setEntityData(entity, data);
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] Fabric writeMaidProfile 失败: {}", t.toString());
        }
    }

    @Override
    public void markMaidImported(Entity entity) {
        try {
            CompoundTag data = entityData(entity);
            data.putBoolean(Constants.IMPORTED_NBT_KEY, true);
            setEntityData(entity, data);
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] Fabric markMaidImported 失败: {}", t.toString());
        }
    }

    @Override
    public boolean isMaidImported(Entity entity) {
        try {
            return entity.getAttachedOrCreate(FabricMaidData.DATA)
                    .getBooleanOr(Constants.IMPORTED_NBT_KEY, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 反射取 TLM 的饰品容器（NeoForge / Fabric 方法名一致，多候选兜底） */
    private static Object baubleHandler(EntityMaid maid) {
        if (maid == null) {
            return null;
        }
        for (String name : new String[]{"getMaidBauble", "getBaubleHandler", "getBauble"}) {
            try {
                java.lang.reflect.Method m = maid.getClass().getMethod(name);
                m.setAccessible(true);
                Object handler = m.invoke(maid);
                if (handler != null) {
                    return handler;
                }
            } catch (Throwable ignored) {
                // 试下一个候选
            }
        }
        return null;
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
