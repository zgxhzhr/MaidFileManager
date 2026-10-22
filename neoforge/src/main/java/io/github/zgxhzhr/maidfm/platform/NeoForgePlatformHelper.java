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

    /**
     * 取模组显示名（玩家语言下的名字）。取不到时退化为 modId —— 提示功能不能因此出错。
     */
    @Override
    public String getModDisplayName(String modId) {
        if (modId == null || modId.isEmpty()) {
            return "";
        }
        try {
            return ModList.get().getModContainerById(modId)
                    .map(container -> container.getModInfo().getDisplayName())
                    .orElse(modId);
        } catch (Throwable t) {
            return modId;
        }
    }
    @Override
    public java.util.Map<String, String> getAllMods() {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        try {
            for (var info : net.neoforged.fml.ModList.get().getMods()) {
                out.put(info.getModId(), info.getDisplayName());
            }
        } catch (Throwable t) {
            // 加载器 API 差异不应让界面崩掉
        }
        return out;
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLLoader.getCurrent().isProduction();
    }

    @Override
    public String getModVersion(String modId) {
        try {
            return ModList.get().getModContainerById(modId)
                    .map(c -> c.getModInfo().getVersion().toString())
                    .orElse("");
        } catch (Throwable t) {
            // 与同类方法保持一致：查不到版本不该把调用方拖崩
            io.github.zgxhzhr.maidfm.Constants.LOG.debug("[maid_file_manager] getModVersion 查询失败: {}", t.toString());
            return "";
        }
    }

    @Override
    public String getMcVersion() {
        return SharedConstants.getCurrentVersion().name();
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
        Object handler = baubleHandler(maid);
        if (handler == null) {
            return 0;
        }
        try {
            return (Integer) handler.getClass().getMethod("size").invoke(handler);
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] NeoForge baubleGetSlots 失败: {}", t.toString());
            return 0;
        }
    }

    @Override
    public void baubleResize(EntityMaid maid, int slots) {
        // 26.1：Transfer API 的容器槽位数在构造时确定，不支持运行期扩容。
        // 明确抛出让调用方按「扩容失败」处理（超出槽位的饰品走丢弃分支），绝不静默吞掉。
        throw new UnsupportedOperationException("26.1 Transfer API 容器不支持运行期扩容");
    }

    @Override
    public void baubleSetStack(EntityMaid maid, int slot, ItemStack stack) {
        Object handler = baubleHandler(maid);
        if (handler == null) {
            throw new IllegalStateException("26.1 未暴露饰品栏访问入口");
        }
        try {
            Class<?> resourceCls = Class.forName("net.neoforged.neoforge.transfer.item.ItemResource");
            Object resource = resourceCls.getMethod("of", net.minecraft.world.item.ItemStack.class).invoke(null, stack);
            handler.getClass().getMethod("set", int.class, resourceCls, int.class)
                    .invoke(handler, slot, resource, stack.getCount());
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
            Object resource = handler.getClass().getMethod("getResource", int.class).invoke(handler, slot);
            int amount = (Integer) handler.getClass().getMethod("getAmountAsInt", int.class).invoke(handler, slot);
            if (resource == null || amount <= 0) {
                return ItemStack.EMPTY;
            }
            Object value = resource.getClass().getMethod("value").invoke(resource);
            return value instanceof net.minecraft.world.item.Item item
                    ? new ItemStack(item, amount) : ItemStack.EMPTY;
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] NeoForge baubleGetStack 失败: {}", t.toString());
            return ItemStack.EMPTY;
        }
    }

    /**
     * 1.21 物品以数据组件形式保存，必须经 ItemStack.CODEC.parse(net.minecraft.resources.RegistryOps.create(net.minecraft.nbt.NbtOps.INSTANCE, HolderLookup.Provider), CompoundTag)
     * 用当前世界注册表解析才能保留附魔/耐久/全部组件；RegistryAccess 即 HolderLookup.Provider。
     * 解析失败（组件损坏/物品不存在）返回 EMPTY，由调用方回退全新化。
     */
    @Override
    public ItemStack parseItemStack(RegistryAccess registries, CompoundTag tag) {
        try {
            return ItemStack.CODEC
                    .parse(net.minecraft.resources.RegistryOps.create(
                            net.minecraft.nbt.NbtOps.INSTANCE, registries), tag)
                    .result().orElse(ItemStack.EMPTY);
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
            int current = SharedConstants.getCurrentVersion().dataVersion().version();
            if (sourceDataVersion <= 0 || sourceDataVersion >= current) {
                return ItemStack.EMPTY;
            }
            Dynamic<?> upgraded = DataFixers.getDataFixer().update(References.ITEM_STACK,
                    new Dynamic<>(NbtOps.INSTANCE, legacyTag), sourceDataVersion, current);
            if (upgraded.getValue() instanceof CompoundTag tag) {
                return ItemStack.CODEC.parse(net.minecraft.resources.RegistryOps.create(net.minecraft.nbt.NbtOps.INSTANCE, registries), tag).result().orElse(ItemStack.EMPTY);
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
            net.minecraft.nbt.Tag encoded = ItemStack.CODEC
                    .encodeStart(net.minecraft.resources.RegistryOps.create(
                            net.minecraft.nbt.NbtOps.INSTANCE, registries), stack)
                    .result().orElse(null);
            return encoded instanceof CompoundTag ct ? ct : new CompoundTag();
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

    /** 女仆档案持久化键名（同样用 modId 命名空间前缀隔离） */
    private static final String KEY_PROFILE = "maid_file_manager:profile";

    @Override
    public CompoundTag readMaidProfile(Entity entity) {
        try {
            CompoundTag persistentData = entity.getPersistentData();
            if (persistentData.contains(KEY_PROFILE)) {
                return persistentData.getCompoundOrEmpty(KEY_PROFILE);
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
            return entity.getPersistentData().getBooleanOr(KEY_IMPORTED, false);
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] NeoForge isMaidImported 失败: {}", t.toString());
            return false;
        }
    }

    /**
     * 反射取女仆的饰品栏容器。
     *
     * <p>TLM 26.1 把 {@code BaubleItemHandler} 改成 NeoForge Transfer API 的
     * {@code ItemStacksResourceHandler}，且实体上不再暴露公开访问器。这里按候选方法名反射，
     * 取不到时返回 {@code null}，调用方按「饰品栏不可用」降级（导入跳过饰品、导出不带饰品）。
     */
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
    }}
