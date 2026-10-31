package io.github.zgxhzhr.maidfm.config;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端侧记录的「各客户端是否同意被统一导出」状态。
 *
 * <p>从 {@code MaidConfigManager} 拆出。它是一个运行时状态表而不是配置：不落盘、
 * 玩家登录默认 false、退出即清理。放在配置类里属于「同处一室但不是一类东西」。
 */
public final class ClientConsentRegistry {

    /** 客户端 UUID → 是否允许服务端统一导出（登录默认 false） */
    private static final Map<UUID, Boolean> CONSENT = new ConcurrentHashMap<>();

    private ClientConsentRegistry() {
    }

    /** 指定玩家是否同意服务端统一导出（默认 false）。 */
    public static boolean get(UUID playerId) {
        return CONSENT.getOrDefault(playerId, false);
    }

    /** 玩家退出时清理同意状态。 */
    public static void remove(UUID playerId) {
        CONSENT.remove(playerId);
    }

    /** 服务端收到客户端同意状态上报（C2S 包处理）。 */
    public static void set(UUID playerId, boolean allow) {
        CONSENT.put(playerId, allow);
    }
}
