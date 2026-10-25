package io.github.zgxhzhr.maidfm.platform;

import io.github.zgxhzhr.maidfm.Constants;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;

/**
 * 女仆实体上的本模组数据（Fabric 版）。
 *
 * <p>NeoForge 侧用 {@code entity.getPersistentData()}（落到实体 NBT 的 {@code NeoForgeData}），
 * Fabric 没有这个入口，改用 Fabric API 的 data attachment —— 两者语义等价：都随实体存档持久化。
 *
 * <p>同一个 attachment 里放两类键：
 * <ul>
 *   <li>{@code maid_file_manager:profile} —— 女仆档案（职业/生日/个人资料/偏好/背景故事）</li>
 *   <li>{@code maidfm_imported} —— 导入产物标记</li>
 * </ul>
 *
 * <p><b>注意</b>：{@code AttachmentRegistry.create} 必须在 mod 初始化期间执行，
 * 因此由 {@link #bootstrap()} 显式触发一次类加载。
 */
public final class FabricMaidData {

    /** 实体上的持久化数据容器（CompoundTag） */
    public static final AttachmentType<CompoundTag> DATA = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "entity_data"),
            builder -> builder
                    .initializer(CompoundTag::new)
                    .copyOnDeath()
                    .persistent(CompoundTag.CODEC));

    private FabricMaidData() {
    }

    /** 由 MaidFileModFabric.onInitialize 调用，确保 attachment 在注册窗口内完成登记 */
    public static void bootstrap() {
        Constants.LOG.info("[maid_file_manager] entity attachment registered: {}", DATA);
    }
}
