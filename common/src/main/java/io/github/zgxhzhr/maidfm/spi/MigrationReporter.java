package io.github.zgxhzhr.maidfm.spi;

/**
 * provider 在导入成功的前提下，向玩家补充说明的回调。
 *
 * <p><b>存在的原因</b>：{@code MaidMigrationException} 只能表达「失败」，而有些情况是
 * <b>成功但不完整</b>——归档写进去了，但有 3 张图片没带上；或者目标世界已有同 ID 的归档，
 * 本次没有覆盖。这类情况既不该让整个导入算失败（女仆已经生成了，报失败只会让玩家困惑），
 * 也不该静默（玩家会以为全都迁移完了）。
 *
 * <p><b>怎么用</b>：实现 {@link MaidMigrationProvider#importData(
 * com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid,
 * net.minecraft.nbt.CompoundTag, MigrationReporter)} 这个三参重载，
 * 在处理过程中调用 {@link #note(String)}：
 *
 * <pre>{@code
 * @Override
 * public void importData(EntityMaid maid, CompoundTag data, MigrationReporter reporter) {
 *     ImportOutcome outcome = doImport(data);
 *     if (outcome.missingImages() > 0) {
 *         reporter.note("归档已导入，但有 " + outcome.missingImages() + " 张图片未能迁移");
 *     }
 * }
 * }</pre>
 *
 * <p><b>不要传 {@code null}</b>；也不要指望调用方持久化这些文本——它们只用于当次提示。
 *
 * <p><b>文本语言</b>：这里收的是 {@code String} 而不是 {@code Component}，
 * 框架会把它原样拼进玩家可见的提示（前缀是 provider 的 id）。所以请给出
 * 目标玩家能读懂的句子——如果你们的模组有多语言支持，请自行按当前语言准备文本。
 */
@FunctionalInterface
public interface MigrationReporter {

    /**
     * 报告一条「成功但有缺项」的说明。
     *
     * @param message 给玩家看的说明；为 null 或空串时会被忽略
     */
    void note(String message);
}
