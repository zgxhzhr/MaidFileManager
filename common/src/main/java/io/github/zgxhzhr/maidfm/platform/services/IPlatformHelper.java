package io.github.zgxhzhr.maidfm.platform.services;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Path;

public interface IPlatformHelper {

    /**
     * 获取当前平台名称。
     *
     * @return 平台名称（如 Fabric/Forge/NeoForge）
     */
    String getPlatformName();

    /**
     * 判断指定 modId 的模组是否已加载。
     *
     * @param modId 要检查的模组 ID
     * @return 已加载返回 true，否则 false
     */
    boolean isModLoaded(String modId);

    /**
     * 判断当前是否运行在开发环境。
     *
     * @return 开发环境返回 true，否则 false
     */
    boolean isDevelopmentEnvironment();

    /**
     * 获取环境类型名称字符串。
     *
     * @return 环境名称（development/production）
     */
    default String getEnvironmentName() {
        return isDevelopmentEnvironment() ? "development" : "production";
    }

    /**
     * 获取指定 modId 的版本字符串，未加载返回空字符串。
     *
     * @param modId 模组 ID
     * @return 版本字符串
     */
    String getModVersion(String modId);

    /**
     * 获取当前 Minecraft 版本字符串。
     *
     * @return MC 版本号
     */
    String getMcVersion();

    /**
     * 获取 Minecraft 运行根目录（即 .minecraft 目录），maid_files 文件夹应放在此处，
     * 方便玩家在切换整合包时直接拷贝。
     *
     * @return 游戏根目录的绝对路径
     */
    Path getGameDir();

    /**
     * 返回女仆饰品栏当前槽位数；失败返回 -1。
     * <p>TLM 的 BaubleItemHandler 在各加载器上的父类不同
     * （Forge: net.minecraftforge.items.ItemStackHandler；
     * NeoForge: net.neoforged.neoforge.items.ItemStackHandler；
     * Fabric/Orihime: cn.sh1rocu...ItemStackHandler），common 无法直接引用，
     * 由各平台实现用本平台类型直调（Fabric 用经 Orihime 源码核实的同名反射）。
     */
    int baubleGetSlots(EntityMaid maid);

    /**
     * 调整饰品栏容量（setSize 会重建槽位列表，必须在写入物品之前调用一次）。
     */
    void baubleResize(EntityMaid maid, int slots);

    /**
     * 直接写入指定槽位，触发 TLM onContentsChanged 以注册饰品效果。
     */
    void baubleSetStack(EntityMaid maid, int slot, ItemStack stack);

    /**
     * 从实体持久化标签读取存储的药水效果 NBT。
     * 用于"禁药水服务器导入后再导出"的场景——效果不恢复到实体但保留在持久化标签中。
     *
     * @param entity 女仆实体
     * @return 存储的效果 NBT（含 active_effects 键），无则 null
     */
    CompoundTag getStoredEffects(Entity entity);

    /**
     * 将药水效果 NBT 写入实体持久化标签。
     *
     * @param entity     女仆实体
     * @param effectsTag 效果 NBT（含 active_effects 键）
     */
    void storeEffects(Entity entity, CompoundTag effectsTag);
}
