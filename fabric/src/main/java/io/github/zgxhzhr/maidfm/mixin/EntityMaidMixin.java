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
 */
@Mixin(EntityMaid.class)
public abstract class EntityMaidMixin implements MaidProfileHolder {

    /** 档案承载字段（{@code @Unique} 保证不与父类/其它 Mixin 成员冲突） */
    @Unique
    private CompoundTag maidfm$profile;

    @Override
    public CompoundTag maidfm$getProfile() {
        return this.maidfm$profile;
    }

    @Override
    public void maidfm$setProfile(CompoundTag tag) {
        this.maidfm$profile = tag;
    }

    /** 实体保存：档案存在时写入自定义键 */
    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void maidfm$saveProfile(CompoundTag tag, CallbackInfo ci) {
        if (this.maidfm$profile != null) {
            tag.put(Constants.PROFILE_NBT_KEY, this.maidfm$profile);
        }
    }

    /** 实体读取：存在自定义键时回填字段 */
    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void maidfm$readProfile(CompoundTag tag, CallbackInfo ci) {
        if (tag.contains(Constants.PROFILE_NBT_KEY, CompoundTag.TAG_COMPOUND)) {
            this.maidfm$profile = tag.getCompound(Constants.PROFILE_NBT_KEY);
        }
    }
}
