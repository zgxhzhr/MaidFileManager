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
    public static final String KEY_ALLOW_CLIENT_IMPORT = ServerConfig.KEY_ALLOW_CLIENT_IMPORT;
    /** 服务端配置键：导入时允许携带饰品 */
    public static final String KEY_ALLOW_BAUBLES = ServerConfig.KEY_ALLOW_BAUBLES;
    /** 服务端配置键：导入时允许一并转移 TLM 成就 */
    public static final String KEY_ALLOW_ADVANCEMENTS = ServerConfig.KEY_ALLOW_ADVANCEMENTS;
    /** 服务端配置键：导入时允许恢复药水效果到实体（关闭则效果保留在持久化标签，不恢复到实体） */
    public static final String KEY_ALLOW_EFFECTS = ServerConfig.KEY_ALLOW_EFFECTS;
    /** 服务端配置键：导入时携带 TLM 本体无敌状态（替身地藏赋予的 Invulnerable，默认关闭） */
    public static final String KEY_ALLOW_INVULNERABLE = ServerConfig.KEY_ALLOW_INVULNERABLE;
    /** 服务端配置键：导入时丢弃饰品属性（附魔/耐久/无法破坏/属性修饰符等），恢复为全新物品（默认关=保留） */
    public static final String KEY_BAUBLE_STRIP_ATTRIBUTES = ServerConfig.KEY_BAUBLE_STRIP_ATTRIBUTES;
    /**
     * 服务端配置键：是否允许携带 Curios 饰品。
     * <p>默认关闭 = 女仆 Curios 槽位内的饰品一律不随女仆迁移；
     * 开启后也只携带<b>被声明允许</b>的命名空间（见 MaidBaubleCarrier），未声明仍丢弃。
     * <p>背包类槽位（back 等）无论本开关如何都被硬编码禁止携带。
     */
    public static final String KEY_CARRY_CURIOS = ServerConfig.KEY_CARRY_CURIOS;

    /** 服务端配置键：OP 维护的「按模组（命名空间）禁用携带」清单（逗号分隔；同时作用于饰品栏与 Curios） */
    public static final String KEY_BAUBLE_BLOCKED_NAMESPACES = ServerConfig.KEY_BAUBLE_BLOCKED_NAMESPACES;

    /** 服务端配置键：OP 维护的「按 Curios 槽位禁用携带」清单（逗号分隔） */
    public static final String KEY_BAUBLE_BLOCKED_CURIOS_SLOTS = ServerConfig.KEY_BAUBLE_BLOCKED_CURIOS_SLOTS;
    /** 服务端配置键：禁用携带的饰品 ID 列表（逗号分隔，仅限车万本体/万法皆通两命名空间，导入时直接丢弃） */
    public static final String KEY_BAUBLE_BLOCKED_LIST = ServerConfig.KEY_BAUBLE_BLOCKED_LIST;
    /** 客户端配置键：允许服务端统一导出你的女仆 */
    public static final String KEY_ALLOW_SERVER_EXPORT = ClientConfig.KEY_ALLOW_SERVER_EXPORT;
    /** 客户端配置键：界面背景音乐开关（试验性功能，默认开启） */
    public static final String KEY_UI_MUSIC = ClientConfig.KEY_UI_MUSIC;
    /** 客户端配置键：界面背景音乐音量（0.0 - 1.0，默认 0.15） */
    public static final String KEY_UI_MUSIC_VOLUME = ClientConfig.KEY_UI_MUSIC_VOLUME;
    /** 客户端配置键：导出并移除女仆时跳过"背包物品将丢失"二次确认的键前缀，完整键为 前缀+存档标识 */
    public static final String SKIP_REMOVE_CONFIRM_PREFIX = ClientConfig.SKIP_REMOVE_CONFIRM_PREFIX;



    private MaidConfigManager() {
    }

    /** 由 loader 侧初始化（gameDir：专用服务器=服务器根目录，客户端=.minecraft，局域网=宿主 gameDir） */
    public static void init(Path gameDir) {
        java.nio.file.Path serverConfigFile =
                gameDir.resolve("config").resolve("maid_file_manager-server.properties");
        java.nio.file.Path clientConfigFile =
                gameDir.resolve("config").resolve("maid_file_manager-client.properties");
        Path baubleImportConfigFile =
                gameDir.resolve("config").resolve("maid_file_manager").resolve("bauble_import.json");
        // 目录结构升级：把旧版本散落的导出/导入/照片目录搬入 maid_file/ 之下（幂等，无旧目录则跳过）
        MaidFileIo.migrateLegacyDirs(gameDir);
        // 配置解析必须逐段兜底：properties 里的非法转义（如单独的反斜杠）会抛 IllegalArgumentException，
        // json 语法错误会抛运行时异常；这些若外泄会让模组初始化失败，表现为游戏启动崩溃。
        ServerConfig.init(serverConfigFile);
        ClientConfig.init(clientConfigFile);
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
    /** 是否允许客户端导入女仆（门面，实现见 {@link ServerConfig}） */
    public static boolean isClientImportAllowed() {
        return ServerConfig.isAllowClientImport();
    }

    /** 导入时是否允许携带饰品 */
    /** 导入时是否允许携带饰品（门面，实现见 {@link ServerConfig}） */
    public static boolean isBaublesAllowed() {
        return ServerConfig.isAllowBaubles();
    }

    /** 导入时是否允许一并转移 TLM 成就 */
    /** 导入时是否允许一并转移 TLM 成就（门面，实现见 {@link ServerConfig}） */
    public static boolean isAdvancementsAllowed() {
        return ServerConfig.isAllowAdvancements();
    }

    /** 导入时是否允许恢复药水效果到实体 */
    /** 导入时是否允许恢复药水效果到实体（门面，实现见 {@link ServerConfig}） */
    public static boolean isEffectsAllowed() {
        return ServerConfig.isAllowEffects();
    }

    /** 导入时是否允许带走 TLM 本体无敌状态 */
    /** 导入时是否允许带走 TLM 本体无敌状态（门面，实现见 {@link ServerConfig}） */
    public static boolean isInvulnerableAllowed() {
        return ServerConfig.isAllowInvulnerable();
    }

    /** 指定玩家是否同意服务端统一导出（门面，实现见 {@link ClientConsentRegistry}） */
    public static boolean getClientConsent(UUID playerId) {
        return ClientConsentRegistry.get(playerId);
    }

    /** 玩家退出时清理同意状态 */
    public static void removeClientConsent(UUID playerId) {
        ClientConsentRegistry.remove(playerId);
    }

    /** 服务端收到客户端同意状态上报（C2S 包处理） */
    public static void handleClientConsent(UUID playerId, boolean allow) {
        ClientConsentRegistry.set(playerId, allow);
    }

    /**
     * 服务端修改配置（C2S 包处理，调用方已做 OP 权限校验）。
     *
     * @return true=配置键合法且已生效；false=未知键，调用方须回执失败且不得触发全服广播
     */
    public static synchronized boolean setServerConfig(String key, boolean value) {
        return ServerConfig.setByKey(key, value);
    }

    /**
     * 服务端修改 OP 维护的「禁用携带的饰品」清单（C2S 包处理，调用方已做 OP 权限校验）。
     *
     * <p>与整合包黑名单相互独立：后者由 bauble_import.json 托管、游戏内只读，
     * OP 仍可在此清单上追加/取消自己的禁用项，两者在导入判定时取并集。
     */
    public static synchronized boolean setServerBaubleBlockedList(List<String> ids) {
        return ServerConfig.setBaubleBlockedList(ids);
    }

    // ==================== 客户端配置 ====================

    /** 本客户端是否允许服务端统一导出 */
    /** 界面背景音乐是否开启（客户端配置） */
    /** 界面背景音乐开关（门面，实现见 {@link ClientConfig}） */
    public static boolean isClientUiMusic() {
        return ClientConfig.isUiMusic();
    }

    /** 界面背景音乐音量（0.0 - 1.0，已钳制） */
    public static float getClientUiMusicVolume() {
        return ClientConfig.getUiMusicVolume();
    }

    /** 设置界面背景音乐开关并落盘（供设置界面/调试使用） */
    public static synchronized void setClientUiMusic(boolean value) {
        ClientConfig.setUiMusic(value);
    }

    public static boolean isClientAllowServerExport() {
        return ClientConfig.isAllowServerExport();
    }

    /** 修改客户端配置（设置界面调用，写本地文件）并立即把新状态上报服务端 */
    public static synchronized void setClientAllowServerExport(boolean value) {
        ClientConfig.setAllowServerExport(value);
        // 立即把新状态上报服务端
        IMaidFileNetwork net = IMaidFileNetwork.Holder.get();
        if (net != null) {
            net.sendClientConsent(value);
        }
    }

    /** 在指定存档/服务器中导出并移除女仆时是否跳过二次确认（纯本地偏好，按存档分别记录，不上报服务端） */
    public static boolean isSkipRemoveConfirm(String worldKey) {
        return ClientConfig.isSkipRemoveConfirm(worldKey);
    }

    /** 修改指定存档/服务器的"跳过移除二次确认"客户端配置（仅写本地文件；false 时清除该存档记录） */
    public static synchronized void setSkipRemoveConfirm(String worldKey, boolean value) {
        ClientConfig.setSkipRemoveConfirm(worldKey, value);
    }

    /** 导入时是否丢弃饰品属性（恢复为全新物品）；服务端读配置、客户端读登录同步缓存 */
    /** 导入时是否丢弃饰品属性（门面，实现见 {@link ServerConfig}） */
    public static boolean isBaubleStripAttributes() {
        return ServerConfig.isBaubleStripAttributes();
    }

    /**
     * 是否允许携带 Curios 饰品（服务端读配置；默认关 = Curios 饰品一律不随女仆迁移）。
     * <p>开启时也只携带被声明允许的命名空间；背包类槽位始终禁止。
     */
    /** 是否允许携带 Curios 饰品（门面，实现见 {@link ServerConfig}） */
    public static boolean isCarryCurios() {
        return ServerConfig.isCarryCurios();
    }

    /**
     * 按配置键读取**服务端**当前生效值（客户端缓存；门面，实现见 {@link ServerConfig#getByKey}）。
     *
     * <p>界面里的服务端开关是「乐观切换 + 超时回滚」：点击时先翻转本地显示并置 pending，
     * 若若干毫秒内没收到服务端配置同步，就用本方法取回权威值把开关复位。
     * 未知键返回 {@code false}。
     */
    public static boolean getServerConfigByKey(String key) {
        return ServerConfig.getByKey(key);
    }

    /** OP 维护的「按模组禁用携带」清单 ∪ 整合包清单（不可变；同时作用于女仆自带饰品栏与 Curios 槽位） */
    public static List<String> getBaubleBlockedNamespaces() {
        return ConfigFileIo.mergeLists(ServerConfig.opBlockedNamespaces(), BaubleImportConfig.blockedNamespaces());
    }

    /** 整合包按模组禁用清单（只读；用于界面把整合包强制的条目显示为不可取消） */
    /** 纯 OP 清单（不含整合包项）：用于 S2C 同步，客户端据此显示"可取消的勾选" */
    public static List<String> getOpBaubleBlockedNamespaces() {
        return ServerConfig.opBlockedNamespaces();
    }

    /** 纯 OP 清单（不含整合包项）：用于 S2C 同步 */
    public static List<String> getOpBaubleBlockedCuriosSlots() {
        return ServerConfig.opBlockedCuriosSlots();
    }

    /** 整合包按模组禁用清单（只读） */
    public static List<String> getBaubleImportBlockedNamespaces() {
        return BaubleImportConfig.blockedNamespaces();
    }

    /** OP 维护的「按 Curios 槽位禁用携带」清单 ∪ 整合包清单（不可变） */
    public static List<String> getBaubleBlockedCuriosSlots() {
        return ConfigFileIo.mergeLists(ServerConfig.opBlockedCuriosSlots(), BaubleImportConfig.blockedCuriosSlots());
    }

    /** 整合包按 Curios 槽位禁用清单（只读；用于界面把整合包强制的条目显示为不可取消） */
    public static List<String> getBaubleImportBlockedCuriosSlots() {
        return BaubleImportConfig.blockedCuriosSlots();
    }

    /**
     * 按清单键写入 OP 清单（供 C2S 提交与界面使用）。值统一做去空白 / 去重 / 小写规范化。
     *
     * @return 键不认识或写盘失败返回 false
     */
    public static synchronized boolean setServerListConfig(String key, List<String> values) {
        return ServerConfig.setListByKey(key, values);
    }

    /** 导入判定用的禁用清单（OP 清单 ∪ 整合包黑名单，不可变）；服务端读配置、客户端读登录同步缓存 */
    public static List<String> getBaubleBlockedList() {
        List<String> op = ServerConfig.opBlockedList();
        List<String> pack = BaubleImportConfig.blacklist();
        if (pack.isEmpty()) {
            return op;
        }
        List<String> merged = new ArrayList<>(op);
        merged.addAll(pack);
        return List.copyOf(merged);
    }

    /** OP 在游戏内维护的禁用清单（不含整合包黑名单；客户端界面读写用） */
    public static List<String> getOpBaubleBlockedList() {
        return ServerConfig.opBlockedList();
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

    /**
     * 客户端收到服务端配置同步（S2C 包处理）：更新本地缓存并回发同意状态。
     *
     * <p>这是<b>唯一跨三个配置域的方法</b>：服务端域的 7 个开关 + 3 张清单、
     * 整合包域的 4 张清单 + 2 个判定标志、以及客户端域的同意状态回发。
     * 所以它留在门面层做编排，各域自己负责落值——签名保持不变（配置同步协议不动）。
     */
    public static void handleServerConfigSync(boolean allowImport, boolean allowBaubles, boolean allowAdvancements,
                                              boolean allowEffects, boolean allowInvulnerable, boolean carryCurios,
                                              boolean baubleStripAttributes, List<String> baubleBlockedList,
                                              List<String> baubleBlacklist, List<String> baubleWhitelist,
                                              boolean baubleManaged, List<String> baubleBlockedNamespaces,
                                              List<String> baubleBlockedCuriosSlots,
                                              List<String> baubleImportBlockedNamespaces,
                                              List<String> baubleImportBlockedCuriosSlots, boolean whitelistEnforced) {
        ServerConfig.applyServerSync(allowImport, allowBaubles, allowAdvancements, allowEffects,
                allowInvulnerable, carryCurios, baubleStripAttributes,
                baubleBlockedNamespaces, baubleBlockedCuriosSlots, baubleBlockedList);
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
            net.sendClientConsent(ClientConfig.isAllowServerExport());
        }
    }

    /** 客户端读取服务端配置缓存（设置界面显示用） */
    public static boolean cachedClientImportAllowed() {
        return ServerConfig.isAllowClientImport();
    }

    public static boolean cachedBaublesAllowed() {
        return ServerConfig.isAllowBaubles();
    }

    /** 客户端读取服务端配置缓存（设置界面显示用） */
    public static boolean cachedAdvancementsAllowed() {
        return ServerConfig.isAllowAdvancements();
    }

    /** 客户端读取服务端配置缓存（设置界面显示用） */
    public static boolean cachedEffectsAllowed() {
        return ServerConfig.isAllowEffects();
    }

    /** 客户端读取服务端配置缓存（设置界面显示用） */
    public static boolean cachedInvulnerableAllowed() {
        return ServerConfig.isAllowInvulnerable();
    }

    /** 客户端读取服务端同步缓存：导入时是否丢弃饰品属性（饰品导入设置界面显示用） */
    public static boolean cachedBaubleStripAttributes() {
        return ServerConfig.isBaubleStripAttributes();
    }

    /** 客户端读取服务端同步缓存：是否允许携带 Curios 饰品（设置界面显示用） */
    public static boolean cachedCarryCurios() {
        return ServerConfig.isCarryCurios();
    }

    /** 客户端读取服务端同步缓存：按模组禁用清单（设置界面显示用） */
    public static List<String> cachedBaubleBlockedNamespaces() {
        return ServerConfig.opBlockedNamespaces();
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
        return ServerConfig.opBlockedCuriosSlots();
    }

    /** 客户端读取服务端同步缓存：OP 维护的禁用清单（饰品导入设置界面读写用） */
    public static List<String> cachedBaubleBlockedList() {
        return ServerConfig.opBlockedList();
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




}
