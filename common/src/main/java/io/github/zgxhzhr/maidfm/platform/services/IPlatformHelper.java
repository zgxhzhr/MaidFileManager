package io.github.zgxhzhr.maidfm.platform.services;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.RegistryAccess;
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
     * 从物品的原始存档 NBT 完整解析 ItemStack（保留附魔、耐久、数据组件等全部物品状态）。
     * 仅用于<b>同版本</b>导入：1.20.x 与 1.21.x 的物品 NBT 结构不同
     * （1.21 起标签内联为 components 数据组件），跨版本解析结果不可信，调用方须改走全新化重建。
     *
     * @param registries 当前世界的注册表访问（1.21 组件解析需要；1.20.x 实现忽略此参数）
     * @param tag        物品栏条目 NBT（含 Slot 等容器字段亦不影响解析）
     * @return 完整物品；物品不存在/标签损坏时返回空物品栈（ItemStack.EMPTY），由调用方回退全新化
     */
    ItemStack parseItemStack(RegistryAccess registries, CompoundTag tag);

    /**
     * 将<b>跨版本</b>（源版本与当前版本不同）的旧版物品 NBT 转换为当前版本的
     * ItemStack（保留无法破坏、耐久、附魔、属性修饰符等全部属性）。
     *
     * <p>方向约定：
     * <ul>
     *   <li>1.21.x 平台：DataFixerUpper 只能升不能降。源 DataVersion 低于当前版本时走 DFU 升级
     *       （1.20 传统 tag 格式 → 1.21 数据组件格式）；源版本未知（≤0）返回空物品栈回退全新化；</li>
     *   <li>1.20.x 平台：无 {@code References.ITEM_STACK}（1.20.2 才引入），DFU 不可用。
     *       源 DataVersion 高于 1.20.1（1.20.5+ / 1.21.x 数据组件格式）时走 {@link io.github.zgxhzhr.maidfm.data.NbtDowngrade}
     *       手动降级（组件格式 → 1.20 传统 tag 格式）；其余返回空物品栈回退全新化；</li>
     *   <li>转换/解析任何异常一律捕获并返回空物品栈（非静默失败，日志留痕），调用方回退全新化，绝不阻断导入。</li>
     * </ul>
     *
     * @param registries        当前世界的注册表访问（解析新版本物品组件需要）
     * @param legacyTag         旧版物品栏条目 NBT（含 Slot 等容器字段亦不影响）
     * @param sourceDataVersion 源版本的 Mojang 官方 DataVersion（见 NbtVersion.mojangDataVersion）
     * @return 转换解析后的完整物品；任何不可转换/失败情形返回空物品栈
     */
    ItemStack convertItemStackNbt(RegistryAccess registries, CompoundTag legacyTag, int sourceDataVersion);

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
