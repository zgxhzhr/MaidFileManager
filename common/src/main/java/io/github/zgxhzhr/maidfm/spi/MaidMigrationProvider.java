package io.github.zgxhzhr.maidfm.spi;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import io.github.zgxhzhr.maidfm.platform.Services;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;

/**
 * 女仆数据迁移 SPI 接口。
 *
 * <p>附属模组实现本接口，把挂在女仆身上、但不在女仆实体 NBT 内的数据
 * （如独立 SavedData、Capability、外部数据库，通过女仆 UUID 关联）
 * 接入 MaidFileManager 的导出/导入流程。
 *
 * <p>使用方式：在附属模组初始化时调用
 * {@link MaidMigrationRegistry#register(MaidMigrationProvider)} 注册即可。
 *
 * <p>设计约束：
 * <ul>
 *   <li>方法参数只依赖 {@link EntityMaid} 与 {@link CompoundTag}，不引入附属模组类型</li>
 *   <li>每个 provider 有唯一 {@link Identifier} 标识</li>
 *   <li>导出返回 {@link CompoundTag}，导入接收 {@link CompoundTag}</li>
 *   <li>{@link #isAvailable()} 用于软依赖检测，附属模组未加载时跳过不报错</li>
 * </ul>
 */
public interface MaidMigrationProvider {

    /**
     * 唯一标识，如 {@code callresponse:npc_event}。
     * 作为 .maid 文件 extras 字段的 key。
     */
    Identifier getId();

    /**
     * 依赖的模组 modId，用于软依赖检测。
     * 返回空串表示不依赖特定模组（始终可用）。
     */
    String getDependencyModId();

    /**
     * 导出：从女仆收集附属数据并序列化。
     * 数据可能在女仆 NBT、SynchedEntityData、Capability、SavedData 或外部系统，
     * 由 provider 自己负责读取与序列化。
     *
     * @param maid 待导出的女仆实体
     * @return 序列化后的 CompoundTag；无数据时返回 null
     */
    CompoundTag export(EntityMaid maid);

    /**
     * 导入：把数据写回女仆或其关联的外部系统。
     * 在女仆已 addFreshEntity 到世界之后调用，此时女仆 UUID 已确定。
     *
     * @param maid 刚导入生成的女仆实体
     * @param data 该 provider 在导出时返回的 CompoundTag
     */
    default void importData(EntityMaid maid, CompoundTag data) {
        // 二参也给了默认实现，好处是「只实现三参重载」的接入方能编译通过——
        // 否则二参 abstract 会强制他们额外写一个空方法（对外文档里没提这点，是个坑）。
        //
        // 框架统一只调用三参版本，所以正常流程不会走到这里。真走到了说明有代码
        // 直接调了二参，那属于框架内部用错，抛出来比静默跳过好。
        throw new UnsupportedOperationException(
                "请实现三参 importData(EntityMaid, CompoundTag, MigrationReporter)");
    }

    /**
     * 导入：把数据写回女仆或其关联的外部系统，并可选地向玩家补充说明。
     *
     * <p><b>这是一个带默认实现的重载，老 provider 不用改</b>：默认实现直接转调
     * {@link #importData(EntityMaid, CompoundTag)}，行为与以前完全一致。
     *
     * <p><b>什么时候该覆盖它</b>：当你能成功完成迁移、但需要告诉玩家「有些东西没带上」
     * 或「有些东西我跳过了」时。这类情况不适合抛 {@link MaidMigrationException}
     * ——那是失败语义，会让玩家以为整只女仆没导入，而实际上女仆已经生成了。
     *
     * <p><b>两种异常的分工</b>：
     * <ul>
     *   <li>单段迁移失败 → 抛 {@link MaidMigrationException}，玩家看到「部分数据未能迁移（你的 message）」。
     *       注意这**不会**让整次导入算失败，女仆仍会生成并计入成功数</li>
     *   <li>迁移成功但不完整 → 调 {@code reporter.note(...)}，玩家看到「成功」+ 你写的说明</li>
     * </ul>
     *
     * <p>框架只调用这个三参版本；只实现了二参版本的 provider 会走下面的默认实现。
     *
     * @param maid     刚导入生成的女仆实体（已加入世界，UUID 已确定）
     * @param data     该 provider 在导出时返回的 CompoundTag
     * @param reporter 用于报告「成功但有缺项」的回调，不会为 null
     */
    default void importData(EntityMaid maid, CompoundTag data, MigrationReporter reporter) {
        importData(maid, data);
    }

    /**
     * 该 provider 是否可用（依赖的模组是否已加载）。
     * 默认实现用 {@link #getDependencyModId()} 检测。
     */
    default boolean isAvailable() {
        String dep = getDependencyModId();
        return dep == null || dep.isEmpty() || Services.PLATFORM.get().isModLoaded(dep);
    }
}
