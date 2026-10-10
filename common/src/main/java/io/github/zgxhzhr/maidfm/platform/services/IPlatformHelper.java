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
     * 读取饰品栏指定槽位的物品；槽位越界或读取失败返回空物品栈。
     */
    ItemStack baubleGetStack(EntityMaid maid, int slot);

    /**
     * 调整饰品栏容量（setSize 会重建槽位列表，必须在写入物品之前调用一次）。
     */
    void baubleResize(EntityMaid maid, int slots);

    /**
     * 直接写入指定槽位，触发 TLM onContentsChanged 以注册饰品效果。
     */
    void baubleSetStack(EntityMaid maid, int slot, ItemStack stack);

    /**
     * 按物品 NBT 完整解析一个 ItemStack（保留附魔/耐久/组件等全部状态）。
     * 同版本导入饰品时使用；解析失败返回 {@link ItemStack#EMPTY}，由调用方回退为全新物品。
     * 1.20.x 物品 NBT 为 id+Count+tag 结构，直接 ItemStack.of 即可，registries 参数不使用；
     * 1.21+ 为数据组件结构，需要 RegistryAccess 参与解析。
     *
     * @param registries 注册表访问（1.21+ 组件解析需要，1.20.x 实现忽略）
     * @param tag        物品条目 NBT
     * @return 解析得到的物品栈；失败返回 EMPTY
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

    /**
     * 读取女仆实体上持久化的档案 NBT（自定义标签 {@code maid_file_manager:profile}），无则返回 null。
     *
     * <p>各加载器实现：
     * <ul>
     *   <li>Forge / NeoForge：{@code Entity#getPersistentData()}（随实体自动持久化）</li>
     *   <li>Fabric：原版无持久化标签 API，由 Mixin 注入 {@code EntityMaid} 的
     *       {@code addAdditionalSaveData/readAdditionalSaveData} 承载，随实体保存</li>
     * </ul>
     *
     * @param entity 女仆实体
     * @return 档案 NBT；不存在或读取失败返回 null
     */
    CompoundTag readMaidProfile(Entity entity);

    /**
     * 将档案 NBT 写入女仆实体并随实体持久化。
     *
     * @param entity 女仆实体
     * @param tag    档案 NBT；为 null 时移除已有档案
     */
    void writeMaidProfile(Entity entity, CompoundTag tag);

    /**
     * 将 ItemStack 序列化为 NBT（档案界面的饰品图标需要跨网络传输）。
     *
     * <p>与 {@link #parseItemStack(RegistryAccess, CompoundTag)} 配对使用；
     * 网络两端版本一致，因此本方法仅需覆盖同版本序列化（不走跨版本转换）。
     *
     * @param registries 当前世界的注册表访问（1.21 组件序列化需要；1.20.x 实现忽略此参数）
     * @param stack      要序列化的物品；空物品返回空标签
     * @return 物品 NBT；序列化失败返回空标签
     */
    CompoundTag serializeItemStack(RegistryAccess registries, ItemStack stack);
}
