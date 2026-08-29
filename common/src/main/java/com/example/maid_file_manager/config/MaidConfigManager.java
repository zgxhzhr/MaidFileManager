package com.example.maid_file_manager.config;

import com.example.maid_file_manager.Constants;
import com.example.maid_file_manager.network.IMaidFileNetwork;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 联机配置管理器（双端配置 + 客户端同意状态）。
 *
 * <p>配置文件（properties 格式，UTF-8）：
 * <ul>
 *   <li>服务端配置 {@code config/maid_file_manager-server.properties}（专用服务器=服务器根目录；局域网联机=宿主 gameDir）：
 *     <ul>
 *       <li>{@code allow_client_import}：是否允许客户端导入女仆（默认 true）</li>
 *       <li>{@code allow_baubles}：导入时是否允许携带饰品（默认 true）</li>
 *     </ul>
 *   </li>
 *   <li>客户端配置 {@code config/maid_file_manager-client.properties}：
 *     <ul>
 *       <li>{@code allow_server_export}：是否允许服务端统一导出你的女仆（默认 false）</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>同步机制：
 * <ul>
 *   <li>玩家登录 → 服务端把服务端配置 S2C 推给客户端（客户端设置界面据此显示）</li>
 *   <li>客户端收到同步后回发自己的同意状态（C2S），服务端存内存 Map（默认 false）</li>
 *   <li>设置界面改服务端配置 → C2S → 服务端权限校验（OP）→ 写文件 → 广播同步</li>
 * </ul>
 */
public final class MaidConfigManager {
    /** 服务端配置键：允许客户端导入女仆 */
    public static final String KEY_ALLOW_CLIENT_IMPORT = "allow_client_import";
    /** 服务端配置键：导入时允许携带饰品 */
    public static final String KEY_ALLOW_BAUBLES = "allow_baubles";
    /** 客户端配置键：允许服务端统一导出你的女仆 */
    public static final String KEY_ALLOW_SERVER_EXPORT = "allow_server_export";

    /** 服务端配置内存值（volatile 保证跨线程可见：网络线程写、服务端逻辑线程读）；默认禁止（安全优先） */
    private static volatile boolean serverAllowClientImport = false;
    private static volatile boolean serverAllowBaubles = false;
    /** 客户端配置内存值 */
    private static volatile boolean clientAllowServerExport = false;
    /** 服务端侧记录的各客户端同意状态（UUID → allowServerExport），登录默认 false */
    private static final Map<UUID, Boolean> CLIENT_CONSENT = new ConcurrentHashMap<>();

    private static Path serverConfigFile;
    private static Path clientConfigFile;

    private MaidConfigManager() {
    }

    /** 由 loader 侧初始化（gameDir：专用服务器=服务器根目录，客户端=.minecraft，局域网=宿主 gameDir） */
    public static void init(Path gameDir) {
        serverConfigFile = gameDir.resolve("config").resolve("maid_file_manager-server.properties");
        clientConfigFile = gameDir.resolve("config").resolve("maid_file_manager-client.properties");
        loadServer();
        loadClient();
    }

    // ==================== 服务端配置（读取） ====================

    /** 是否允许客户端导入女仆 */
    public static boolean isClientImportAllowed() {
        return serverAllowClientImport;
    }

    /** 导入时是否允许携带饰品 */
    public static boolean isBaublesAllowed() {
        return serverAllowBaubles;
    }

    /** 指定玩家是否同意服务端统一导出（默认 false） */
    public static boolean getClientConsent(UUID playerId) {
        return CLIENT_CONSENT.getOrDefault(playerId, false);
    }

    /** 玩家退出时清理同意状态 */
    public static void removeClientConsent(UUID playerId) {
        CLIENT_CONSENT.remove(playerId);
    }

    /** 服务端收到客户端同意状态上报（C2S 包处理） */
    public static void handleClientConsent(UUID playerId, boolean allow) {
        CLIENT_CONSENT.put(playerId, allow);
    }

    /** 服务端修改配置（C2S 包处理，调用方已做 OP 权限校验） */
    public static synchronized void setServerConfig(String key, boolean value) {
        if (KEY_ALLOW_CLIENT_IMPORT.equals(key)) {
            serverAllowClientImport = value;
        } else if (KEY_ALLOW_BAUBLES.equals(key)) {
            serverAllowBaubles = value;
        } else {
            Constants.LOG.warn("[女仆文件管理] 收到未知服务端配置键: {}", key);
            return;
        }
        saveServer();
    }

    // ==================== 客户端配置 ====================

    /** 本客户端是否允许服务端统一导出 */
    public static boolean isClientAllowServerExport() {
        return clientAllowServerExport;
    }

    /** 修改客户端配置（设置界面调用，写本地文件） */
    public static synchronized void setClientAllowServerExport(boolean value) {
        clientAllowServerExport = value;
        saveClient();
        // 立即把新状态上报服务端
        IMaidFileNetwork net = IMaidFileNetwork.Holder.get();
        if (net != null) {
            net.sendClientConsent(value);
        }
    }

    /** 客户端收到服务端配置同步（S2C 包处理）：更新本地缓存并回发同意状态 */
    public static void handleServerConfigSync(boolean allowImport, boolean allowBaubles) {
        serverAllowClientImport = allowImport;
        serverAllowBaubles = allowBaubles;
        IMaidFileNetwork net = IMaidFileNetwork.Holder.get();
        if (net != null) {
            net.sendClientConsent(clientAllowServerExport);
        }
    }

    /** 客户端读取服务端配置缓存（设置界面显示用） */
    public static boolean cachedClientImportAllowed() {
        return serverAllowClientImport;
    }

    public static boolean cachedBaublesAllowed() {
        return serverAllowBaubles;
    }

    // ==================== 文件 IO ====================

    private static void loadServer() {
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(serverConfigFile, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException ignored) {
            // 文件不存在时使用默认值
        }
        serverAllowClientImport = parse(props, KEY_ALLOW_CLIENT_IMPORT, false);
        serverAllowBaubles = parse(props, KEY_ALLOW_BAUBLES, false);
    }

    private static void loadClient() {
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(clientConfigFile, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException ignored) {
        }
        clientAllowServerExport = parse(props, KEY_ALLOW_SERVER_EXPORT, false);
    }

    private static void saveServer() {
        Properties props = new Properties();
        props.setProperty(KEY_ALLOW_CLIENT_IMPORT, String.valueOf(serverAllowClientImport));
        props.setProperty(KEY_ALLOW_BAUBLES, String.valueOf(serverAllowBaubles));
        store(props, serverConfigFile, "Maid File Manager server config (edited via in-game settings, OP only)");
    }

    private static void saveClient() {
        Properties props = new Properties();
        props.setProperty(KEY_ALLOW_SERVER_EXPORT, String.valueOf(clientAllowServerExport));
        store(props, clientConfigFile, "Maid File Manager client config");
    }

    private static void store(Properties props, Path file, String comment) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                props.store(writer, comment);
            }
        } catch (IOException e) {
            Constants.LOG.error("[女仆文件管理] 保存配置文件失败: {}", file, e);
        }
    }

    private static boolean parse(Properties props, String key, boolean defaultValue) {
        String value = props.getProperty(key);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        return value.equalsIgnoreCase("true");
    }
}
