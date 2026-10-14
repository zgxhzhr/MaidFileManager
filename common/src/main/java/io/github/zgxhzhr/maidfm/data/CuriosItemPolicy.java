package io.github.zgxhzhr.maidfm.data;

/**
 * Curios 物品携带策略：判断某件饰品能否随女仆迁移。
 *
 * <p>由 service 层实现并注入（data 层不反向依赖 service 层），使"总开关 + 命名空间声明 + 槽位白名单 +
 * OP 禁用清单 + 整合包白 / 黑名单"这一整套既有管理配置能同时管住女仆自带饰品栏与 Curios 槽位——
 * 否则同一件饰品放在 Curios 槽里就能绕开 OP 与整合包的管控。
 */
@FunctionalInterface
public interface CuriosItemPolicy {

    /**
     * @param itemId         物品 id（如 {@code create:gear}）
     * @param namespace      物品命名空间
     * @param slotIdentifier Curios 槽位标识（女仆自带饰品栏没有槽位概念，此值仅对 Curios 有意义）
     * @return true 表示允许携带
     */
    boolean allow(String itemId, String namespace, String slotIdentifier);
}