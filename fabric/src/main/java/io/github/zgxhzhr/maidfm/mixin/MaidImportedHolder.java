package io.github.zgxhzhr.maidfm.mixin;

/**
 * 女仆实体「导入产物」标记的持久化承载接口（由 {@link EntityMaidMixin} 实现）。
 *
 * <p>Fabric 的原版实体没有 Forge/NeoForge 那样的持久化标签 API，因此在 {@code EntityMaid} 内
 * 通过 Mixin 新增一个布尔字段承载标记，并在 {@code addAdditionalSaveData/readAdditionalSaveData}
 * 中随实体存档读写，从而保证服务器重启后标记仍然存在（非易失内存）。
 *
 * <p>方法名统一用 {@code maidfm$} 前缀，避免与 TLM 或其它模组注入的成员冲突。
 */
public interface MaidImportedHolder {
    /**
     * 读取该女仆是否带有「导入产物」标记。
     *
     * @return 是否由本模组导入产生
     */
    boolean maidfm$isImported();

    /**
     * 设置「导入产物」标记（随实体存档持久化）。
     *
     * @param imported 是否标记为导入产物
     */
    void maidfm$setImported(boolean imported);
}
