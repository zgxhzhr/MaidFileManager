package io.github.zgxhzhr.maidfm.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zgxhzhr.maidfm.Constants;
import io.github.zgxhzhr.maidfm.data.MaidFileIo;
import io.github.zgxhzhr.maidfm.network.IMaidFileNetwork;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 联机配置管理器（双端配置 + 客户端同意状态）。
 *
 * <p>配置文件（properties 格式，UTF-8）：
 * <ul>
 *   <li>服务端配置 {@code config/maid_file_manager-server.properties}（专用服务器=服务器根目录；局域网联机=宿主 gameDir；仅 OP 可改）：
 *     <ul>
 *       <li>{@code allow_client_import}：是否允许客户端导入女仆（默认开启）</li>
 *       <li>{@code allow_baubles}：导入时是否允许携带饰品（默认开启）</li>
 *       <li>{@code allow_advancements}：导入时是否一并转移女仆相关成就（默认开启）</li>
 *       <li>{@code allow_effects}：导入时是否恢复药水效果到实体（默认开启）</li>
 *       <li>{@code allow_invulnerable}：导入时是否允许携带女仆无敌状态（默认关闭）</li>
 *       <li>{@code bauble_strip_attributes}：导入时丢弃饰品属性，恢复为全新物品（默认 false=保留）</li>
 *       <li>{@code bauble_blocked_list}：禁用携带的饰品 ID 列表，逗号分隔（默认空=不限制）</li>
 *     </ul>
 *   </li>
 *   <li><b>整合包饰品导入配置</b> {@code config/maid_file_manager/bauble_import.json}（JSON，整合包作者专用）：
 *     <ul>
 *       <li>该文件<b>存在即接管</b>（{@code managed}）：白/黑名单以文件内容为准，覆盖 properties 的
 *           {@code bauble_blocked_list}，且游戏内（含 OP）无法再修改这两张清单，实现"整合包作者设置后玩家不易改"；</li>
 *       <li>结构：{@code {"whitelist": ["物品ID 或 中文名"], "blacklist": ["物品ID 或 中文名"]}}；</li>
 *       <li>匹配规则：物品注册 ID 或物品中文显示名<b>精确匹配</b>；黑名单优先于白名单；
 *           白名单非空时只有命中白名单的物品才放行（仍受命名空间白名单约束）；</li>
 *       <li>文件解析失败时<b>失败关闭</b>：按"拒绝导入全部饰品"处理并打 ERROR 日志，防止管控形同虚设；</li>
 *       <li>OP 仍可在游戏内修改其它开关（{@code allow_baubles}、{@code bauble_strip_attributes} 等）。</li>
 *     </ul>
 *   </li>
 *   <li>客户端配置 {@code config/maid_file_manager-client.properties}：
 *     <ul>
 *       <li>{@code allow_server_export}：是否允许服务端统一导出你的女仆（默认 false）</li>
 *       <li>{@code skip_remove_confirm.<存档标识>}：在指定存档/服务器中导出并移除女仆时是否跳过二次确认（默认 false；按存档分别记录）</li>
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
    /** 服务端配置键：导入时允许一并转移 TLM 成就 */
    public static final String KEY_ALLOW_ADVANCEMENTS = "allow_advancements";
    /** 服务端配置键：导入时允许恢复药水效果到实体（关闭则效果保留在持久化标签，不恢复到实体） */
    public static final String KEY_ALLOW_EFFECTS = "allow_effects";
    /** 服务端配置键：导入时携带 TLM 本体无敌状态（替身地藏赋予的 Invulnerable，默认关闭） */
    public static final String KEY_ALLOW_INVULNERABLE = "allow_invulnerable";
    /** 服务端配置键：导入时丢弃饰品属性（附魔/耐久/无法破坏/属性修饰符等），恢复为全新物品（默认关=保留） */
    public static final String KEY_BAUBLE_STRIP_ATTRIBUTES = "bauble_strip_attributes";
    /**
     * 服务端配置键：是否允许携带 Curios 饰品。
     * <p>默认关闭 = 女仆 Curios 槽位内的饰品一律不随女仆迁移；
     * 开启后也只携带<b>被声明允许</b>的命名空间（见 MaidBaubleCarrier），未声明仍丢弃。
     * <p>背包类槽位（back 等）无论本开关如何都被硬编码禁止携带。
     */
    public static final String KEY_CARRY_CURIOS = "carry_curios_baubles";

    /** 服务端配置键：OP 维护的「按模组（命名空间）禁用携带」清单（逗号分隔；同时作用于饰品栏与 Curios） */
    public static final String KEY_BAUBLE_BLOCKED_NAMESPACES = "bauble_blocked_namespaces";

    /** 服务端配置键：OP 维护的「按 Curios 槽位禁用携带」清单（逗号分隔） */
    public static final String KEY_BAUBLE_BLOCKED_CURIOS_SLOTS = "bauble_blocked_curios_slots";
    /** 服务端配置键：禁用携带的饰品 ID 列表（逗号分隔，仅限车万本体/万法皆通两命名空间，导入时直接丢弃） */
    public static final String KEY_BAUBLE_BLOCKED_LIST = "bauble_blocked_list";
    /** 客户端配置键：允许服务端统一导出你的女仆 */
    public static final String KEY_ALLOW_SERVER_EXPORT = "allow_server_export";
    /** 客户端配置键：界面背景音乐开关（试验性功能，默认开启） */
    public static final String KEY_UI_MUSIC = "ui_music";
    /** 客户端配置键：界面背景音乐音量（0.0 - 1.0，默认 0.15） */
    public static final String KEY_UI_MUSIC_VOLUME = "ui_music_volume";
    /** 客户端配置键：导出并移除女仆时跳过"背包物品将丢失"二次确认的键前缀，完整键为 前缀+存档标识 */
    public static final String SKIP_REMOVE_CONFIRM_PREFIX = "skip_remove_confirm.";

    /** 服务端配置内存值（volatile 保证跨线程可见：网络线程写、服务端逻辑线程读）；默认开启（方便单人玩家） */
    private static volatile boolean serverAllowClientImport = true;
    private static volatile boolean serverAllowBaubles = true;
    private static volatile boolean serverAllowAdvancements = true;
    private static volatile boolean serverAllowEffects = true;
    private static volatile boolean serverAllowInvulnerable = false;
    /** 服务端配置内存值：导入时丢弃饰品属性（默认关=保留） */
    private static volatile boolean serverBaubleStripAttributes = false;
    /** 服务端配置内存值：是否允许携带 Curios 饰品（默认关=一律不迁移） */
    private static volatile boolean serverCarryCurios = false;
    /** 服务端配置内存值：OP 维护的按模组（命名空间）禁用清单（不可变，默认空） */
    private static volatile List<String> serverBaubleBlockedNamespaces = List.of();
    /** 服务端配置内存值：OP 维护的按 Curios 槽位禁用清单（不可变，默认空） */
    private static volatile List<String> serverBaubleBlockedCuriosSlots = List.of();
    /** 服务端配置内存值：OP 在游戏内维护的「禁用携带饰品」清单（不可变，默认空；与整合包黑名单相互独立） */
    private static volatile List<String> serverBaubleBlockedList = List.of();
    /** 客户端配置内存值 */
    private static volatile boolean clientAllowServerExport = false;
    /** 客户端：界面背景音乐开关（试验性功能）与音量 */
    private static volatile boolean clientUiMusic = true;
    private static volatile float clientUiMusicVolume = 0.15F;
    /** 客户端配置内存值：各存档/服务器是否跳过移除二次确认（key=存档标识，见 client 包存档标识工具） */
    private static final Map<String, Boolean> clientSkipRemoveConfirmByWorld = new ConcurrentHashMap<>();
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
        Path baubleImportConfigFile =
                gameDir.resolve("config").resolve("maid_file_manager").resolve("bauble_import.json");
        // 目录结构升级：把旧版本散落的导出/导入/照片目录搬入 maid_file/ 之下（幂等，无旧目录则跳过）
        MaidFileIo.migrateLegacyDirs(gameDir);
        // 配置解析必须逐段兜底：properties 里的非法转义（如单独的反斜杠）会抛 IllegalArgumentException，
        // json 语法错误会抛运行时异常；这些若外泄会让模组初始化失败，表现为游戏启动崩溃。
        try {
            loadServer();
        } catch (Throwable t) {
            io.github.zgxhzhr.maidfm.Constants.LOG.error(
                    "[maid_file_manager] 服务端配置加载失败，已回退默认值: {}", t.toString());
        }
        try {
            loadClient();
        } catch (Throwable t) {
            io.github.zgxhzhr.maidfm.Constants.LOG.error(
                    "[maid_file_manager] 客户端配置加载失败，已回退默认值: {}", t.toString());
        }
        // 整合包配置最后加载：存在时覆盖 properties 的黑名单并启用白名单（整合包作者优先级最高）
        try {
            BaubleImportConfig.load(baubleImportConfigFile);
        } catch (Throwable t) {
            io.github.zgxhzhr.maidfm.Constants.LOG.error(
                    "[maid_file_manager] 整合包饰品配置加载失败，已忽略该文件: {}", t.toString());
        }
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

    /** 导入时是否允许一并转移 TLM 成就 */
    public static boolean isAdvancementsAllowed() {
        return serverAllowAdvancements;
    }

    /** 导入时是否允许恢复药水效果到实体 */
    public static boolean isEffectsAllowed() {
        return serverAllowEffects;
    }

    /** 导入时是否允许带走 TLM 本体无敌状态 */
    public static boolean isInvulnerableAllowed() {
        return serverAllowInvulnerable;
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

    /**
     * 服务端修改配置（C2S 包处理，调用方已做 OP 权限校验）。
     *
     * @return true=配置键合法且已生效；false=未知键，调用方须回执失败且不得触发全服广播
     */
    public static synchronized boolean setServerConfig(String key, boolean value) {
        if (KEY_ALLOW_CLIENT_IMPORT.equals(key)) {
            serverAllowClientImport = value;
        } else if (KEY_ALLOW_BAUBLES.equals(key)) {
            serverAllowBaubles = value;
        } else if (KEY_ALLOW_ADVANCEMENTS.equals(key)) {
            serverAllowAdvancements = value;
        } else if (KEY_ALLOW_EFFECTS.equals(key)) {
            serverAllowEffects = value;
        } else if (KEY_ALLOW_INVULNERABLE.equals(key)) {
            serverAllowInvulnerable = value;
        } else if (KEY_BAUBLE_STRIP_ATTRIBUTES.equals(key)) {
            serverBaubleStripAttributes = value;
        } else if (KEY_CARRY_CURIOS.equals(key)) {
            serverCarryCurios = value;
        } else {
            Constants.LOG.warn("[maid_file_manager] 收到未知服务端配置键: {}", key);
            return false;
        }
        if (!saveServer()) {
            return false;
        }
        return true;
    }

    /**
     * 服务端修改 OP 维护的「禁用携带的饰品」清单（C2S 包处理，调用方已做 OP 权限校验）。
     * <p>仅做去除首尾空白与去重；命名空间收窄由调用方在写入前完成（收窄不放开）。
     * <p>该清单与整合包黑名单相互独立：整合包黑名单由 bauble_import.json 托管、游戏内只读，
     * OP 仍可在此清单上追加/取消自己的禁用项，两者在导入判定时取并集。
     */
    public static synchronized boolean setServerBaubleBlockedList(List<String> ids) {
        serverBaubleBlockedList = List.copyOf(ids == null ? List.of() : ids);
        if (!saveServer()) {
            return false;
        }
        return true;
    }

    // ==================== 客户端配置 ====================

    /** 本客户端是否允许服务端统一导出 */
    /** 界面背景音乐是否开启（客户端配置） */
    public static boolean isClientUiMusic() {
        return clientUiMusic;
    }

    /** 界面背景音乐音量（0.0 - 1.0，已钳制） */
    public static float getClientUiMusicVolume() {
        return clientUiMusicVolume;
    }

    /** 设置界面背景音乐开关并落盘（供设置界面/调试使用） */
    public static synchronized void setClientUiMusic(boolean value) {
        clientUiMusic = value;
        saveClient();
    }

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

    /** 在指定存档/服务器中导出并移除女仆时是否跳过二次确认（纯本地偏好，按存档分别记录，不上报服务端） */
    public static boolean isSkipRemoveConfirm(String worldKey) {
        return Boolean.TRUE.equals(clientSkipRemoveConfirmByWorld.get(worldKey));
    }

    /** 修改指定存档/服务器的"跳过移除二次确认"客户端配置（仅写本地文件；false 时清除该存档记录） */
    public static synchronized void setSkipRemoveConfirm(String worldKey, boolean value) {
        if (value) {
            clientSkipRemoveConfirmByWorld.put(worldKey, Boolean.TRUE);
        } else {
            clientSkipRemoveConfirmByWorld.remove(worldKey);
        }
        saveClient();
    }

    /** 导入时是否丢弃饰品属性（恢复为全新物品）；服务端读配置、客户端读登录同步缓存 */
    public static boolean isBaubleStripAttributes() {
        return serverBaubleStripAttributes;
    }

    /**
     * 是否允许携带 Curios 饰品（服务端读配置；默认关 = Curios 饰品一律不随女仆迁移）。
     * <p>开启时也只携带被声明允许的命名空间；背包类槽位始终禁止。
     */
    public static boolean isCarryCurios() {
        return serverCarryCurios;
    }

    /**
     * 按配置键读取**服务端**当前生效值（客户端缓存）。
     *
     * <p>界面里的服务端开关是「乐观切换 + 超时回滚」：点击时先翻转本地显示并置 pending，
     * 若若干毫秒内没收到服务端配置同步，就用本方法取回权威值把开关复位。
     * 未知键返回 {@code false}（调用方只在已知的服务端键上使用）。
     */
    public static boolean getServerConfigByKey(String key) {
        if (key == null) {
            return false;
        }
        return switch (key) {
            case KEY_ALLOW_CLIENT_IMPORT -> isClientImportAllowed();
            case KEY_ALLOW_BAUBLES -> isBaublesAllowed();
            case KEY_ALLOW_ADVANCEMENTS -> isAdvancementsAllowed();
            case KEY_ALLOW_EFFECTS -> isEffectsAllowed();
            case KEY_ALLOW_INVULNERABLE -> isInvulnerableAllowed();
            case KEY_BAUBLE_STRIP_ATTRIBUTES -> isBaubleStripAttributes();
            case KEY_CARRY_CURIOS -> isCarryCurios();
            default -> false;
        };
    }

    /** OP 维护的「按模组禁用携带」清单（不可变；同时作用于女仆自带饰品栏与 Curios 槽位） */
    public static List<String> getBaubleBlockedNamespaces() {
        return ConfigFileIo.mergeLists(serverBaubleBlockedNamespaces, BaubleImportConfig.blockedNamespaces());
    }

    /** 整合包按模组禁用清单（只读；用于界面把整合包强制的条目显示为不可取消） */
    /** 纯 OP 清单（不含整合包项）：用于 S2C 同步，客户端据此显示"可取消的勾选" */
    public static List<String> getOpBaubleBlockedNamespaces() {
        return serverBaubleBlockedNamespaces;
    }

    /** 纯 OP 清单（不含整合包项）：用于 S2C 同步 */
    public static List<String> getOpBaubleBlockedCuriosSlots() {
        return serverBaubleBlockedCuriosSlots;
    }

    /** 整合包按模组禁用清单（只读） */
    public static List<String> getBaubleImportBlockedNamespaces() {
        return BaubleImportConfig.blockedNamespaces();
    }

    /** OP 维护的「按 Curios 槽位禁用携带」清单（不可变） */
    public static List<String> getBaubleBlockedCuriosSlots() {
        return ConfigFileIo.mergeLists(serverBaubleBlockedCuriosSlots, BaubleImportConfig.blockedCuriosSlots());
    }

    /** 整合包按 Curios 槽位禁用清单（只读；用于界面把整合包强制的条目显示为不可取消） */
    public static List<String> getBaubleImportBlockedCuriosSlots() {
        return BaubleImportConfig.blockedCuriosSlots();
    }

    /**
     * 按清单键写入 OP 清单（供 C2S 提交与界面使用）。值统一做去空白 / 去重 / 小写规范化。
     *
     * @return 键不认识返回 false
     */
    public static synchronized boolean setServerListConfig(String key, List<String> values) {
        List<String> cleaned = ConfigFileIo.sanitizeList(values);
        if (KEY_BAUBLE_BLOCKED_LIST.equals(key)) {
            serverBaubleBlockedList = cleaned;
        } else if (KEY_BAUBLE_BLOCKED_NAMESPACES.equals(key)) {
            serverBaubleBlockedNamespaces = cleaned;
        } else if (KEY_BAUBLE_BLOCKED_CURIOS_SLOTS.equals(key)) {
            serverBaubleBlockedCuriosSlots = cleaned;
        } else {
            Constants.LOG.warn("[maid_file_manager] 收到未知清单配置键: {}", key);
            return false;
        }
        if (!saveServer()) {
            return false;
        }
        return true;
    }

    /** 导入判定用的禁用清单（OP 清单 ∪ 整合包黑名单，不可变）；服务端读配置、客户端读登录同步缓存 */
    public static List<String> getBaubleBlockedList() {
        if (BaubleImportConfig.blacklist().isEmpty()) {
            return serverBaubleBlockedList;
        }
        List<String> merged = new ArrayList<>(serverBaubleBlockedList);
        merged.addAll(BaubleImportConfig.blacklist());
        return List.copyOf(merged);
    }

    /** OP 在游戏内维护的禁用清单（不含整合包黑名单；客户端界面读写用） */
    public static List<String> getOpBaubleBlockedList() {
        return serverBaubleBlockedList;
    }

    /** 整合包黑名单（不可变，只读；物品 ID 或中文显示名） */
    public static List<String> getBaubleBlacklist() {
        return BaubleImportConfig.blacklist();
    }

    /** 整合包白名单（不可变；物品 ID 或中文显示名） */
    public static List<String> getBaubleWhitelist() {
        return BaubleImportConfig.whitelist();
    }

    /** 是否存在整合包配置（bauble_import.json）：用于界面提示，不代表整张列表只读 */
    /** 白名单是否处于强制状态（由服务端判定并同步，客户端不得自行推断） */
    public static boolean isBaubleWhitelistEnforced() {
        return BaubleImportConfig.isWhitelistEnforced();
    }

    public static boolean isBaubleConfigManaged() {
        return BaubleImportConfig.isManaged();
    }

    /**
     * 判定单件饰品是否通过整合包白名单（门面方法，实现见 {@link BaubleImportConfig#isWhitelistPass}）。
     */
    public static boolean isBaubleWhitelistPass(String id, String displayName) {
        return BaubleImportConfig.isWhitelistPass(id, displayName);
    }

    /**
     * 判定某饰品是否被整合包「强制禁用且只读」：命中整合包黑名单，或白名单非空且未命中白名单。
     *
     * <p>仅供界面把这类条目显示为「已勾选且不可取消」；OP 在游戏内维护的禁用清单不在此列
     * （那是 OP 可自行增删的，界面正常可勾选/取消）。
     */
    public static boolean isBaubleForcedBlocked(String id, String displayName) {
        if (BaubleImportConfig.isBlacklisted(id, displayName)) {
            return true;
        }
        return BaubleImportConfig.isWhitelistEnforced() && !BaubleImportConfig.isWhitelistPass(id, displayName);
    }

    /** 客户端收到服务端配置同步（S2C 包处理）：更新本地缓存并回发同意状态 */
    public static void handleServerConfigSync(boolean allowImport, boolean allowBaubles, boolean allowAdvancements,
                                              boolean allowEffects, boolean allowInvulnerable, boolean carryCurios,
                                              boolean baubleStripAttributes, List<String> baubleBlockedList,
                                              List<String> baubleBlacklist, List<String> baubleWhitelist,
                                              boolean baubleManaged, List<String> baubleBlockedNamespaces,
                                              List<String> baubleBlockedCuriosSlots,
                                              List<String> baubleImportBlockedNamespaces,
                                              List<String> baubleImportBlockedCuriosSlots, boolean whitelistEnforced) {
        serverAllowClientImport = allowImport;
        serverAllowBaubles = allowBaubles;
        serverAllowAdvancements = allowAdvancements;
        serverAllowEffects = allowEffects;
        serverAllowInvulnerable = allowInvulnerable;
        serverCarryCurios = carryCurios;
        serverBaubleBlockedNamespaces = List.copyOf(
                baubleBlockedNamespaces == null ? List.of() : ConfigFileIo.sanitizeList(baubleBlockedNamespaces));
        serverBaubleBlockedCuriosSlots = List.copyOf(
                baubleBlockedCuriosSlots == null ? List.of() : ConfigFileIo.sanitizeList(baubleBlockedCuriosSlots));
        serverBaubleStripAttributes = baubleStripAttributes;
        serverBaubleBlockedList = List.copyOf(baubleBlockedList == null ? List.of() : baubleBlockedList);
        // 整合包配置域整体交给 BaubleImportConfig。
        // 注意：原实现在这里写的是 `baubleImportBlockedNamespaces = List.copyOf(baubleImportBlockedNamespaces ...)`，
        // 参数名遮蔽了同名字段，赋值给了参数自己——字段从未被更新，客户端因此从未同步到
        // 整合包的「按模组禁用 / 按 Curios 槽位禁用」清单。改用 applyServerSync 后该缺陷一并消失。
        // 直接采用服务端判定：服务端在 bauble_import.json 解析失败时会「失败关闭」并置 true，
        // 客户端若按「managed && 白名单非空」自行重算会得出相反结论（UI 显示可放行、实际全拒绝）。
        BaubleImportConfig.applyServerSync(baubleWhitelist, baubleBlacklist,
                baubleImportBlockedNamespaces, baubleImportBlockedCuriosSlots,
                baubleManaged, whitelistEnforced);
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

    /** 客户端读取服务端配置缓存（设置界面显示用） */
    public static boolean cachedAdvancementsAllowed() {
        return serverAllowAdvancements;
    }

    /** 客户端读取服务端配置缓存（设置界面显示用） */
    public static boolean cachedEffectsAllowed() {
        return serverAllowEffects;
    }

    /** 客户端读取服务端配置缓存（设置界面显示用） */
    public static boolean cachedInvulnerableAllowed() {
        return serverAllowInvulnerable;
    }

    /** 客户端读取服务端同步缓存：导入时是否丢弃饰品属性（饰品导入设置界面显示用） */
    public static boolean cachedBaubleStripAttributes() {
        return serverBaubleStripAttributes;
    }

    /** 客户端读取服务端同步缓存：是否允许携带 Curios 饰品（设置界面显示用） */
    public static boolean cachedCarryCurios() {
        return serverCarryCurios;
    }

    /** 客户端读取服务端同步缓存：按模组禁用清单（设置界面显示用） */
    public static List<String> cachedBaubleBlockedNamespaces() {
        return serverBaubleBlockedNamespaces;
    }

    /** 客户端读取服务端同步缓存：按 Curios 槽位禁用清单（设置界面显示用） */
    /** 客户端读取服务端同步缓存：整合包按模组禁用清单（界面显示为只读勾选） */
    public static List<String> cachedBaubleImportBlockedNamespaces() {
        return BaubleImportConfig.blockedNamespaces();
    }

    /** 客户端读取服务端同步缓存：整合包按 Curios 槽位禁用清单（界面显示为只读勾选） */
    public static List<String> cachedBaubleImportBlockedCuriosSlots() {
        return BaubleImportConfig.blockedCuriosSlots();
    }

    public static List<String> cachedBaubleBlockedCuriosSlots() {
        return serverBaubleBlockedCuriosSlots;
    }

    /** 客户端读取服务端同步缓存：OP 维护的禁用清单（饰品导入设置界面读写用） */
    public static List<String> cachedBaubleBlockedList() {
        return serverBaubleBlockedList;
    }

    /** 客户端读取服务端同步缓存：整合包黑名单（只读展示用） */
    public static List<String> cachedBaubleBlacklist() {
        return BaubleImportConfig.blacklist();
    }

    /** 客户端读取服务端同步缓存：整合包白名单（饰品导入设置界面显示用） */
    public static List<String> cachedBaubleWhitelist() {
        return BaubleImportConfig.whitelist();
    }

    /** 客户端读取服务端同步缓存：是否存在整合包配置（界面提示用，不代表整张列表只读） */
    public static boolean cachedBaubleConfigManaged() {
        return BaubleImportConfig.isManaged();
    }

    // ==================== 文件 IO ====================

    private static void loadServer() {
        Properties props = ConfigFileIo.load(serverConfigFile, "服务端配置");
        serverAllowClientImport = ConfigFileIo.parse(props, KEY_ALLOW_CLIENT_IMPORT, true);
        serverAllowBaubles = ConfigFileIo.parse(props, KEY_ALLOW_BAUBLES, true);
        serverAllowAdvancements = ConfigFileIo.parse(props, KEY_ALLOW_ADVANCEMENTS, true);
        serverAllowEffects = ConfigFileIo.parse(props, KEY_ALLOW_EFFECTS, true);
        serverAllowInvulnerable = ConfigFileIo.parse(props, KEY_ALLOW_INVULNERABLE, false);
        serverBaubleStripAttributes = ConfigFileIo.parse(props, KEY_BAUBLE_STRIP_ATTRIBUTES, false);
        serverCarryCurios = ConfigFileIo.parse(props, KEY_CARRY_CURIOS, false);
        serverBaubleBlockedNamespaces = ConfigFileIo.parseSanitizedList(props, KEY_BAUBLE_BLOCKED_NAMESPACES);
        serverBaubleBlockedCuriosSlots = ConfigFileIo.parseSanitizedList(props, KEY_BAUBLE_BLOCKED_CURIOS_SLOTS);
        // 与另外两张清单口径对齐：统一走 parseSanitizedList（trim + 去重 + 小写），
        // 否则手写配置里带大写字母的条目永远命中不了，黑名单方向属于"失败开放"。
        serverBaubleBlockedList = ConfigFileIo.parseSanitizedList(props, KEY_BAUBLE_BLOCKED_LIST);
    }

    private static void loadClient() {
        // 首次运行配置文件不存在属正常情况：返回空实例，下方 parse 全部走默认值（默认拒绝）
        Properties props = ConfigFileIo.load(clientConfigFile, "客户端配置");
        clientAllowServerExport = ConfigFileIo.parse(props, KEY_ALLOW_SERVER_EXPORT, false);
        clientUiMusic = ConfigFileIo.parse(props, KEY_UI_MUSIC, true);
        clientUiMusicVolume = ConfigFileIo.parseVolume(props, KEY_UI_MUSIC_VOLUME, 0.15F);
        // 按存档记录的跳过确认：遍历 skip_remove_confirm.<存档标识> 全部装入内存
        clientSkipRemoveConfirmByWorld.clear();
        for (String name : props.stringPropertyNames()) {
            if (name.startsWith(SKIP_REMOVE_CONFIRM_PREFIX) && name.length() > SKIP_REMOVE_CONFIRM_PREFIX.length()
                    && ConfigFileIo.parse(props, name, false)) {
                clientSkipRemoveConfirmByWorld.put(name.substring(SKIP_REMOVE_CONFIRM_PREFIX.length()), Boolean.TRUE);
            }
        }
        // 旧版本曾写过无存档后缀的全局键 skip_remove_confirm：语义已改为按存档，此处直接忽略，
        // 下次 saveClient 时该键自然从文件消失，无需专门迁移。
    }

    private static boolean saveServer() {
        Properties props = new Properties();
        props.setProperty(KEY_ALLOW_CLIENT_IMPORT, String.valueOf(serverAllowClientImport));
        props.setProperty(KEY_ALLOW_BAUBLES, String.valueOf(serverAllowBaubles));
        props.setProperty(KEY_ALLOW_ADVANCEMENTS, String.valueOf(serverAllowAdvancements));
        props.setProperty(KEY_ALLOW_EFFECTS, String.valueOf(serverAllowEffects));
        props.setProperty(KEY_ALLOW_INVULNERABLE, String.valueOf(serverAllowInvulnerable));
        props.setProperty(KEY_BAUBLE_STRIP_ATTRIBUTES, String.valueOf(serverBaubleStripAttributes));
        props.setProperty(KEY_CARRY_CURIOS, String.valueOf(serverCarryCurios));
        if (!serverBaubleBlockedNamespaces.isEmpty()) {
            props.setProperty(KEY_BAUBLE_BLOCKED_NAMESPACES, String.join(",", serverBaubleBlockedNamespaces));
        }
        if (!serverBaubleBlockedCuriosSlots.isEmpty()) {
            props.setProperty(KEY_BAUBLE_BLOCKED_CURIOS_SLOTS, String.join(",", serverBaubleBlockedCuriosSlots));
        }
        // OP 维护的禁用清单始终写入 properties；整合包黑名单来自 bauble_import.json，不在此持久化
        if (!serverBaubleBlockedList.isEmpty()) {
            props.setProperty(KEY_BAUBLE_BLOCKED_LIST, String.join(",", serverBaubleBlockedList));
        }
        return ConfigFileIo.store(props, serverConfigFile);
    }


    private static void saveClient() {
        Properties props = new Properties();
        props.setProperty(KEY_ALLOW_SERVER_EXPORT, String.valueOf(clientAllowServerExport));
        props.setProperty(KEY_UI_MUSIC, String.valueOf(clientUiMusic));
        props.setProperty(KEY_UI_MUSIC_VOLUME, String.valueOf(clientUiMusicVolume));
        // 每个勾选过"不再提示"的存档各写一行（只存 true；false=默认值不落盘）
        for (String worldKey : clientSkipRemoveConfirmByWorld.keySet()) {
            props.setProperty(SKIP_REMOVE_CONFIRM_PREFIX + worldKey, String.valueOf(true));
        }
        ConfigFileIo.store(props, clientConfigFile);
    }

}
