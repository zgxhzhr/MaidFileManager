package io.github.zgxhzhr.maidfm.mixin;

import net.minecraft.nbt.CompoundTag;

/**
 * 女仆实体档案持久化承载接口（由 {@link EntityMaidMixin} 实现）。
 *
 * <p>Fabric 的原版实体没有 Forge/NeoForge 那样的持久化标签 API，档案 NBT 无法直接挂到
 * {@code Entity#getPersistentData()} 上。这里通过 Mixin 在 {@code EntityMaid} 内新增一个字段承载档案，
 * 并在 {@code addAdditionalSaveData/readAdditionalSaveData} 中随实体存档读写，
 * 从而保证服务器重启后档案仍然存在（非易失内存）。
 *
 * <p>方法名统一用 {@code maidfm$} 前缀，避免与 TLM 或其它模组注入的成员冲突。
 */
public interface MaidProfileHolder {
    /**
     * 读取女仆实体上持久化的档案 NBT。
     *
     * @return 档案 NBT；不存在或已被移除返回 null
     */
    CompoundTag maidfm$getProfile();

    /**
     * 写入女仆实体上的档案 NBT（随实体存档持久化）。
     *
     * @param tag 档案 NBT；null 表示移除已有档案
     */
    void maidfm$setProfile(CompoundTag tag);
}
