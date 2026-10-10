package io.github.zgxhzhr.maidfm.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import io.github.zgxhzhr.maidfm.Constants;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把女仆档案 NBT 持久化到 {@code EntityMaid}。
 *
 * <p>Fabric 无原版持久化标签 API，故在女仆实体上新增一个字段承载档案（见 {@link MaidProfileHolder}），
 * 并在实体保存/读取的末尾注入读写逻辑，键名 {@link Constants#PROFILE_NBT_KEY}
 * （{@code maid_file_manager:profile}，带命名空间避免与他模冲突）。
 *
 * <p>注入点选 {@code TAIL}：TLM 的 {@code EntityMaid} 可能被其它模组 Mixin 处理，
 * 放在原逻辑之后读写最稳妥。
 *
 * <p>同时承载「导入产物」标记（见 {@link MaidImportedHolder}）：该标记供整合包作者在实体
 * 生成事件中识别刚由本模组导入的女仆，随实体存档与 .maid 文件迁移。
 */
@Mixin(EntityMaid.class)
public abstract class EntityMaidMixin implements MaidProfileHolder, MaidImportedHolder {

    /** 档案承载字段（{@code @Unique} 保证不与父类/其它 Mixin 成员冲突） */
    @Unique
    private CompoundTag maidfm$profile;

    /** 导入产物标记承载字段（{@code @Unique} 保证不与父类/其它 Mixin 成员冲突） */
    @Unique
    private boolean maidfm$imported;

    @Override
    public CompoundTag maidfm$getProfile() {
        return this.maidfm$profile;
    }

    @Override
    public void maidfm$setProfile(CompoundTag tag) {
        this.maidfm$profile = tag;
    }

    @Override
    public boolean maidfm$isImported() {
        return this.maidfm$imported;
    }

    @Override
    public void maidfm$setImported(boolean imported) {
        this.maidfm$imported = imported;
    }

    /**
     * 实体保存：档案存在时写入自定义键。
     *
     * <p>注入目标显式写出完整描述符：1.20.1 的 TLM 编译期 jar 为 SRG 命名（方法名 m_7378_/m_7380_），
     * 若仅写方法名，Mixin AP 无法在目标类上解析出该方法（编译告警且不生成 refmap）。
     * 写出 {@code addAdditionalSaveData(CompoundTag)V} 后，AP 经命名的 Entity 父类解析成功，
     * 并把 named 映射写进 refmap（→ intermediary method_5652），保证运行时正确注入。
     */
    @Inject(method = "addAdditionalSaveData(Lnet/minecraft/nbt/CompoundTag;)V", at = @At("TAIL"))
    private void maidfm$saveProfile(CompoundTag tag, CallbackInfo ci) {
        if (this.maidfm$profile != null) {
            tag.put(Constants.PROFILE_NBT_KEY, this.maidfm$profile);
        }
    }

    /** 实体读取：存在自定义键时回填字段（描述符解析原因同上） */
    @Inject(method = "readAdditionalSaveData(Lnet/minecraft/nbt/CompoundTag;)V", at = @At("TAIL"))
    private void maidfm$readProfile(CompoundTag tag, CallbackInfo ci) {
        if (tag.contains(Constants.PROFILE_NBT_KEY, CompoundTag.TAG_COMPOUND)) {
            this.maidfm$profile = tag.getCompound(Constants.PROFILE_NBT_KEY);
        }
    }

    /** 实体保存：带导入产物标记时写入根 NBT 键（仅标记为真才写，避免无谓的键膨胀；描述符解析原因同上） */
    @Inject(method = "addAdditionalSaveData(Lnet/minecraft/nbt/CompoundTag;)V", at = @At("TAIL"))
    private void maidfm$saveImported(CompoundTag tag, CallbackInfo ci) {
        if (this.maidfm$imported) {
            tag.putBoolean(Constants.IMPORTED_NBT_KEY, true);
        }
    }

    /** 实体读取：存在该键时回填字段，保证服务器重启后标记不丢（描述符解析原因同上） */
    @Inject(method = "readAdditionalSaveData(Lnet/minecraft/nbt/CompoundTag;)V", at = @At("TAIL"))
    private void maidfm$readImported(CompoundTag tag, CallbackInfo ci) {
        if (tag.getBoolean(Constants.IMPORTED_NBT_KEY)) {
            this.maidfm$imported = true;
        }
    }
}
