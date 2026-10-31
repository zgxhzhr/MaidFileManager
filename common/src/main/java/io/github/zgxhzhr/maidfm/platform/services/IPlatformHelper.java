package io.github.zgxhzhr.maidfm.platform.services;

import java.util.Map;
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
     * 取模组的显示名（给玩家看的名字，例如「机械动力」），用于提示里明确告诉玩家"该找哪个模组"。
     *
     * @param modId 模组 ID
     * @return 显示名；模组不存在或任何异常时返回 {@code modId} 本身（永不返回 null）
     */
    String getModDisplayName(String modId);

    /**
     * 列出当前已加载的全部模组（modId → 显示名）。
     * <p>供 OP 在界面里看到并禁用"任何模组"的女仆饰品：不依赖对方是否接入声明接口。
     * <p>实现必须容错：任何加载器 API 差异都不应让界面崩掉。
     */
    Map<String, String> getAllMods();

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
     * 当前进程是否是「单人/局域网主机」的集成服务器。
     *
     * <p><b>各端应覆盖本方法</b>——正确的判断需要平台自己的运行环境 API（Forge/NeoForge
     * 的 {@code FMLLoader.getDist()}、Fabric 的 {@code FabricLoader.getEnvironmentType()}）
     * 先短路专用服务器，再反射 {@code Minecraft.getInstance().getSingleplayerServer()}。
     *
     * <p>本默认实现<b>只能返回 false</b>：common 层不能静态引用客户端类（专用服务器加载会崩），
     * 而唯一双端共有的 {@code MinecraftServer} 没有静态的「取当前实例」入口
     * （{@code getServer()} 在 vanilla 1.20.1 / 1.21.1 与 Forge 补丁里都不存在）。
     * 早期版本反射它，结果恒抛异常、恒返回 false——即「看起来有用但从不生效」的死代码。
     *
     * <p>所以这里显式返回 false 并把决定权交给各端实现，而不是留一段永远失败的反射。
     *
     * <p>用途：集成服务器与客户端之间是 {@code LocalServerConnection} 内存直连，数据不经过
     * {@code ServerboundCustomPayloadPacket} 序列化，因此 1.20.x 的 32767 字节上限不生效。
     * 判断不出来时按「远程服」处理是保守方向：最坏结果是多走一次分片上传，不影响正确性也不丢数据。
     */
    default boolean isSingleplayer() {
        return false;
    }




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
     * 在女仆实体上写入「导入产物」标记（{@link io.github.zgxhzhr.maidfm.Constants#IMPORTED_NBT_KEY}）。
     *
     * <p>用途：整合包作者在实体生成事件中读取该标记，识别刚由本模组导入的女仆并发放补偿。
     * 各加载器实现：
     * <ul>
     *   <li>Forge / NeoForge：写入 {@code Entity#getPersistentData()}，即实体 NBT 的
     *       {@code ForgeData} / {@code NeoForgeData} 子标签；这正是 KubeJS 的
     *       {@code entity.persistentData}，脚本无需额外适配即可读取</li>
     *   <li>Fabric：写入实体根 NBT（由 Mixin 承载），随实体存档与 .maid 文件迁移</li>
     * </ul>
     *
     * @param entity 女仆实体
     */
    void markMaidImported(Entity entity);

    /**
     * 读取女仆实体上的「导入产物」标记。
     *
     * @param entity 女仆实体
     * @return 该女仆是否由本模组导入产生；未标记或读取失败返回 false
     */
    boolean isMaidImported(Entity entity);

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
