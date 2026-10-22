package io.github.zgxhzhr.maidfm.network;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端请求频次限制。
 *
 * <p>三个高频请求 handler 全部在主线程同步执行，且代价都不低：
 * <ul>
 *   <li>{@code REQUEST_MAID_LIST}：256³ AABB 实体扫描</li>
 *   <li>{@code REQUEST_BACKUP_LIST}：磁盘目录扫描（最多 200×200 个目录 + 每目录读 index.dat）</li>
 *   <li>{@code REQUEST_MAID_PROFILE}：读照片文件</li>
 * </ul>
 * 原先没有任何冷却或每玩家配额 —— 一个**普通玩家**（不需要 OP）循环发包就能让服务器持续卡顿。
 *
 * <p>这里用最轻的「每玩家 × 每类请求的最后放行时刻」做节流：同一玩家在冷却期内重复请求
 * 直接被丢弃（不回包也不记日志，避免日志本身被刷爆）。
 */
public final class RequestThrottle {

    /** key = playerUuid + "|" + kind → 上次放行时刻（毫秒） */
    private static final Map<String, Long> LAST_ALLOWED = new ConcurrentHashMap<>();

    /** 超过这个时长没再出现的条目可以被清掉（避免玩家进进出出导致 map 无限增长） */
    private static final long PRUNE_AFTER_MS = 10 * 60 * 1000L;

    /** 条目数超过该阈值时才触发一次清理（清理本身是 O(n)，不能每次请求都做） */
    private static final int PRUNE_THRESHOLD = 2048;

    private RequestThrottle() {
    }

    /**
     * 是否放行这次请求。
     *
     * @param player     请求玩家
     * @param kind       请求类别（如 {@code maid_list} / {@code backup_list} / {@code maid_profile}）
     * @param cooldownMs 冷却时长（毫秒）；同一玩家同类请求在该时间内只放行一次
     * @return {@code true} 放行；{@code false} 应静默丢弃
     */
    public static boolean allow(UUID player, String kind, long cooldownMs) {
        if (player == null || kind == null) {
            return false;
        }
        String key = player + "|" + kind;
        long now = System.currentTimeMillis();
        Long prev = LAST_ALLOWED.get(key);
        if (prev != null && now - prev < cooldownMs) {
            return false;
        }
        LAST_ALLOWED.put(key, now);
        if (LAST_ALLOWED.size() > PRUNE_THRESHOLD) {
            LAST_ALLOWED.entrySet().removeIf(e -> now - e.getValue() > PRUNE_AFTER_MS);
        }
        return true;
    }

    /** 玩家断线时清掉其条目（可选优化；不清也会被上面的过期清理回收） */
    public static void forget(UUID player) {
        if (player == null) {
            return;
        }
        String prefix = player + "|";
        LAST_ALLOWED.keySet().removeIf(k -> k.startsWith(prefix));
    }
}