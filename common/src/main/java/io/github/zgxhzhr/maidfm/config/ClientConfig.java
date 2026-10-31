package io.github.zgxhzhr.maidfm.config;

import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端本地配置（{@code config/maid_file_manager-client.properties}）。
 *
 * <p>从 {@code MaidConfigManager} 拆出的第三个配置域。它是三者中最独立的一个：
 * <b>完全不参与网络同步</b>——只是客户端自己的一些开关，改了就落盘。
 *
 * <p>与另外两个域的区别：服务端配置由 OP 维护并广播给客户端；整合包配置由整合包作者
 * 托管且游戏内只读。客户端配置是本机玩家的偏好，与服务器无关。
 */
public final class ClientConfig {

    /** 客户端配置键：允许服务端统一导出你的女仆 */
    public static final String KEY_ALLOW_SERVER_EXPORT = "allow_server_export";
    /** 客户端配置键：界面背景音乐开关（试验性功能，默认开启） */
    public static final String KEY_UI_MUSIC = "ui_music";
    /** 客户端配置键：界面背景音乐音量（0.0 - 1.0，默认 0.15） */
    public static final String KEY_UI_MUSIC_VOLUME = "ui_music_volume";
    /** 客户端配置键：导出并移除女仆时跳过"背包物品将丢失"二次确认的键前缀，完整键为 前缀+存档标识 */
    public static final String SKIP_REMOVE_CONFIRM_PREFIX = "skip_remove_confirm.";

    /** 客户端配置内存值（volatile 保证跨线程可见） */
    private static volatile boolean allowServerExport = false;
    /** 客户端：界面背景音乐开关（试验性功能）与音量 */
    private static volatile boolean uiMusic = true;
    private static volatile float uiMusicVolume = 0.15F;
    /** 各存档/服务器是否跳过移除二次确认（key=存档标识） */
    private static final Map<String, Boolean> skipRemoveConfirmByWorld = new ConcurrentHashMap<>();

    private static Path configFile;

    private ClientConfig() {
    }

    /**
     * 设置配置文件路径并加载。
     *
     * <p>解析失败不抛出：客户端配置损坏时退回默认值，不能因此让游戏启动崩溃
     * （{@code ConfigFileIo.load} 已就地记录非法转义并保留已解析部分）。
     */
    public static void init(Path file) {
        configFile = file;
        load();
    }

    private static void load() {
        // 首次运行配置文件不存在属正常情况：返回空实例，下方 parse 全部走默认值（默认拒绝）
        Properties props = ConfigFileIo.load(configFile, "客户端配置");
        allowServerExport = ConfigFileIo.parse(props, KEY_ALLOW_SERVER_EXPORT, false);
        uiMusic = ConfigFileIo.parse(props, KEY_UI_MUSIC, true);
        uiMusicVolume = ConfigFileIo.parseVolume(props, KEY_UI_MUSIC_VOLUME, 0.15F);
        // 按存档记录的跳过确认：遍历 skip_remove_confirm.<存档标识> 全部装入内存
        skipRemoveConfirmByWorld.clear();
        for (String name : props.stringPropertyNames()) {
            if (name.startsWith(SKIP_REMOVE_CONFIRM_PREFIX) && name.length() > SKIP_REMOVE_CONFIRM_PREFIX.length()
                    && ConfigFileIo.parse(props, name, false)) {
                skipRemoveConfirmByWorld.put(name.substring(SKIP_REMOVE_CONFIRM_PREFIX.length()), Boolean.TRUE);
            }
        }
        // 旧版本曾写过无存档后缀的全局键 skip_remove_confirm：语义已改为按存档，此处直接忽略，
        // 下次 save 时该键自然从文件消失，无需专门迁移。
    }

    private static void save() {
        if (configFile == null) {
            return;
        }
        Properties props = new Properties();
        props.setProperty(KEY_ALLOW_SERVER_EXPORT, String.valueOf(allowServerExport));
        props.setProperty(KEY_UI_MUSIC, String.valueOf(uiMusic));
        props.setProperty(KEY_UI_MUSIC_VOLUME, String.valueOf(uiMusicVolume));
        // 每个勾选过"不再提示"的存档各写一行（只存 true；false=默认值不落盘）
        for (String worldKey : skipRemoveConfirmByWorld.keySet()) {
            props.setProperty(SKIP_REMOVE_CONFIRM_PREFIX + worldKey, String.valueOf(true));
        }
        ConfigFileIo.store(props, configFile);
    }

    /** 是否允许服务端统一导出你的女仆 */
    public static boolean isAllowServerExport() {
        return allowServerExport;
    }

    /** 修改「允许服务端统一导出」并立即落盘。 */
    public static synchronized void setAllowServerExport(boolean value) {
        allowServerExport = value;
        save();
    }

    public static boolean isUiMusic() {
        return uiMusic;
    }

    public static float getUiMusicVolume() {
        return uiMusicVolume;
    }

    /** 修改界面背景音乐开关并立盘。 */
    public static synchronized void setUiMusic(boolean value) {
        uiMusic = value;
        save();
    }

    /** 指定存档是否跳过「背包物品将丢失」二次确认。 */
    public static boolean isSkipRemoveConfirm(String worldKey) {
        return Boolean.TRUE.equals(skipRemoveConfirmByWorld.get(worldKey));
    }

    /** 设置/取消指定存档的跳过确认并立即落盘（false 时从文件移除该键）。 */
    public static synchronized void setSkipRemoveConfirm(String worldKey, boolean value) {
        if (value) {
            skipRemoveConfirmByWorld.put(worldKey, Boolean.TRUE);
        } else {
            skipRemoveConfirmByWorld.remove(worldKey);
        }
        save();
    }
}
