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
 *       <li>{@code allow_invulnerable}：导入时是否允许带走女仆无敌状态（默认开启）</li>
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
    /** 服务端配置键：导入时带走 TLM 本体无敌状态（替身地藏赋予的 Invulnerable） */
    public static final String KEY_ALLOW_INVULNERABLE = "allow_invulnerable";
    /** 服务端配置键：导入时丢弃饰品属性（附魔/耐久/无法破坏/属性修饰符等），恢复为全新物品（默认关=保留） */
    public static final String KEY_BAUBLE_STRIP_ATTRIBUTES = "bauble_strip_attributes";
    /** 服务端配置键：禁用携带的饰品 ID 列表（逗号分隔，仅限车万本体/万法皆通两命名空间，导入时直接丢弃） */
    public static final String KEY_BAUBLE_BLOCKED_LIST = "bauble_blocked_list";
    /** 客户端配置键：允许服务端统一导出你的女仆 */
    public static final String KEY_ALLOW_SERVER_EXPORT = "allow_server_export";
    /** 客户端配置键：导出并移除女仆时跳过"背包物品将丢失"二次确认的键前缀，完整键为 前缀+存档标识 */
    public static final String SKIP_REMOVE_CONFIRM_PREFIX = "skip_remove_confirm.";

    /** 服务端配置内存值（volatile 保证跨线程可见：网络线程写、服务端逻辑线程读）；默认开启（方便单人玩家） */
    private static volatile boolean serverAllowClientImport = true;
    private static volatile boolean serverAllowBaubles = true;
    private static volatile boolean serverAllowAdvancements = true;
    private static volatile boolean serverAllowEffects = true;
    private static volatile boolean serverAllowInvulnerable = true;
    /** 服务端配置内存值：导入时丢弃饰品属性（默认关=保留） */
    private static volatile boolean serverBaubleStripAttributes = false;
    /** 服务端配置内存值：禁用携带的饰品 ID 列表（不可变，默认空） */
    private static volatile List<String> serverBaubleBlockedList = List.of();
    /** 整合包白名单（来自 bauble_import.json；不可变，默认空） */
    private static volatile List<String> serverBaubleWhitelist = List.of();
    /** 整合包配置是否已接管黑/白名单（bauble_import.json 存在即 true；此时游戏内不可修改这两张清单） */
    private static volatile boolean baubleConfigManaged = false;
    /**
     * 是否强制白名单：白名单非空时为 true；配置文件解析失败时亦置 true 并按"拒绝全部饰品"处理
     * （失败关闭：宁可拒绝也不能让整合包作者的管控被静默绕过）。
     */
    private static volatile boolean baubleWhitelistEnforced = false;
    /** 客户端配置内存值 */
    private static volatile boolean clientAllowServerExport = false;
    /** 客户端配置内存值：各存档/服务器是否跳过移除二次确认（key=存档标识，见 client 包存档标识工具） */
    private static final Map<String, Boolean> clientSkipRemoveConfirmByWorld = new ConcurrentHashMap<>();
    /** 服务端侧记录的各客户端同意状态（UUID → allowServerExport），登录默认 false */
    private static final Map<UUID, Boolean> CLIENT_CONSENT = new ConcurrentHashMap<>();

    private static Path serverConfigFile;
    private static Path clientConfigFile;
    private static Path baubleImportConfigFile;

    private MaidConfigManager() {
    }

    /** 由 loader 侧初始化（gameDir：专用服务器=服务器根目录，客户端=.minecraft，局域网=宿主 gameDir） */
    public static void init(Path gameDir) {
        serverConfigFile = gameDir.resolve("config").resolve("maid_file_manager-server.properties");
        clientConfigFile = gameDir.resolve("config").resolve("maid_file_manager-client.properties");
        baubleImportConfigFile = gameDir.resolve("config").resolve("maid_file_manager").resolve("bauble_import.json");
        // 目录结构升级：把旧版本散落的导出/导入/照片目录搬入 maid_file/ 之下（幂等，无旧目录则跳过）
        MaidFileIo.migrateLegacyDirs(gameDir);
        loadServer();
        loadClient();
        // 整合包配置最后加载：存在时覆盖 properties 的黑名单并启用白名单（整合包作者优先级最高）
        loadBaubleImportConfig();
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
        } else {
            Constants.LOG.warn("[女仆档案管理] 收到未知服务端配置键: {}", key);
            return false;
        }
        saveServer();
        return true;
    }

    /**
     * 服务端修改「禁用携带的饰品 ID 列表」（C2S 包处理，调用方已做 OP 权限校验）。
     * <p>仅做去除首尾空白与去重；命名空间收窄由调用方在写入前完成（收窄不放开）。
     *
     * @return true=已写入；false=整合包配置已接管（bauble_import.json 存在），游戏内不可修改，调用方须回执拒绝
     */
    public static synchronized boolean setServerBaubleBlockedList(List<String> ids) {
        if (baubleConfigManaged) {
            Constants.LOG.warn("[女仆档案管理] 拒绝写入禁用饰品列表：黑/白名单已由整合包配置 bauble_import.json 托管");
            return false;
        }
        serverBaubleBlockedList = List.copyOf(ids == null ? List.of() : ids);
        saveServer();
        return true;
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

    /** 禁用携带的饰品 ID/中文名列表（不可变）；服务端读配置、客户端读登录同步缓存 */
    public static List<String> getBaubleBlockedList() {
        return serverBaubleBlockedList;
    }

    /** 整合包白名单（不可变；物品 ID 或中文显示名） */
    public static List<String> getBaubleWhitelist() {
        return serverBaubleWhitelist;
    }

    /** 黑/白名单是否已被整合包配置（bauble_import.json）托管：托管时游戏内不可修改这两张清单 */
    public static boolean isBaubleConfigManaged() {
        return baubleConfigManaged;
    }

    /**
     * 判定单件饰品是否通过整合包白名单。
     *
     * <p>未启用白名单时一律放行；启用时需物品注册 ID 或物品中文显示名精确命中，否则拒绝。
     * 传入的显示名由调用方通过物品注册表解析得到（导入时条目 ID 可能是跨版本旧键，
     * 中文名匹配为整合包作者提供更友好的书写方式）。
     */
    public static boolean isBaubleWhitelistPass(String id, String displayName) {
        if (!baubleWhitelistEnforced) {
            return true;
        }
        if (id != null && !id.isEmpty() && serverBaubleWhitelist.contains(id)) {
            return true;
        }
        return displayName != null && !displayName.isEmpty() && serverBaubleWhitelist.contains(displayName);
    }

    /** 客户端收到服务端配置同步（S2C 包处理）：更新本地缓存并回发同意状态 */
    public static void handleServerConfigSync(boolean allowImport, boolean allowBaubles, boolean allowAdvancements,
                                              boolean allowEffects, boolean allowInvulnerable,
                                              boolean baubleStripAttributes, List<String> baubleBlockedList,
                                              List<String> baubleWhitelist, boolean baubleManaged) {
        serverAllowClientImport = allowImport;
        serverAllowBaubles = allowBaubles;
        serverAllowAdvancements = allowAdvancements;
        serverAllowEffects = allowEffects;
        serverAllowInvulnerable = allowInvulnerable;
        serverBaubleStripAttributes = baubleStripAttributes;
        serverBaubleBlockedList = List.copyOf(baubleBlockedList == null ? List.of() : baubleBlockedList);
        serverBaubleWhitelist = List.copyOf(baubleWhitelist == null ? List.of() : baubleWhitelist);
        baubleConfigManaged = baubleManaged;
        baubleWhitelistEnforced = baubleManaged && !serverBaubleWhitelist.isEmpty();
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

    /** 客户端读取服务端同步缓存：禁用携带的饰品 ID/中文名列表（饰品导入设置界面显示用） */
    public static List<String> cachedBaubleBlockedList() {
        return serverBaubleBlockedList;
    }

    /** 客户端读取服务端同步缓存：整合包白名单（饰品导入设置界面显示用） */
    public static List<String> cachedBaubleWhitelist() {
        return serverBaubleWhitelist;
    }

    /** 客户端读取服务端同步缓存：黑/白名单是否由整合包配置托管（托管时界面只读） */
    public static boolean cachedBaubleConfigManaged() {
        return baubleConfigManaged;
    }

    // ==================== 文件 IO ====================

    private static void loadServer() {
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(serverConfigFile, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException ignored) {
            // 文件不存在时使用默认值
        }
        serverAllowClientImport = parse(props, KEY_ALLOW_CLIENT_IMPORT, true);
        serverAllowBaubles = parse(props, KEY_ALLOW_BAUBLES, true);
        serverAllowAdvancements = parse(props, KEY_ALLOW_ADVANCEMENTS, true);
        serverAllowEffects = parse(props, KEY_ALLOW_EFFECTS, true);
        serverAllowInvulnerable = parse(props, KEY_ALLOW_INVULNERABLE, true);
        serverBaubleStripAttributes = parse(props, KEY_BAUBLE_STRIP_ATTRIBUTES, false);
        serverBaubleBlockedList = parseIdList(props, KEY_BAUBLE_BLOCKED_LIST);
    }

    private static void loadClient() {
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(clientConfigFile, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException ignored) {
            // 首次运行配置文件尚不存在属正常情况：props 保持为空，下方 parse 全部走默认值（默认拒绝），无需告警
        }
        clientAllowServerExport = parse(props, KEY_ALLOW_SERVER_EXPORT, false);
        // 按存档记录的跳过确认：遍历 skip_remove_confirm.<存档标识> 全部装入内存
        clientSkipRemoveConfirmByWorld.clear();
        for (String name : props.stringPropertyNames()) {
            if (name.startsWith(SKIP_REMOVE_CONFIRM_PREFIX) && name.length() > SKIP_REMOVE_CONFIRM_PREFIX.length()
                    && parse(props, name, false)) {
                clientSkipRemoveConfirmByWorld.put(name.substring(SKIP_REMOVE_CONFIRM_PREFIX.length()), Boolean.TRUE);
            }
        }
        // 旧版本曾写过无存档后缀的全局键 skip_remove_confirm：语义已改为按存档，此处直接忽略，
        // 下次 saveClient 时该键自然从文件消失，无需专门迁移。
    }

    private static void saveServer() {
        Properties props = new Properties();
        props.setProperty(KEY_ALLOW_CLIENT_IMPORT, String.valueOf(serverAllowClientImport));
        props.setProperty(KEY_ALLOW_BAUBLES, String.valueOf(serverAllowBaubles));
        props.setProperty(KEY_ALLOW_ADVANCEMENTS, String.valueOf(serverAllowAdvancements));
        props.setProperty(KEY_ALLOW_EFFECTS, String.valueOf(serverAllowEffects));
        props.setProperty(KEY_ALLOW_INVULNERABLE, String.valueOf(serverAllowInvulnerable));
        props.setProperty(KEY_BAUBLE_STRIP_ATTRIBUTES, String.valueOf(serverBaubleStripAttributes));
        // 托管时黑名单来自 bauble_import.json，不写入 properties（避免误导与覆盖整合包配置）
        if (!baubleConfigManaged && !serverBaubleBlockedList.isEmpty()) {
            props.setProperty(KEY_BAUBLE_BLOCKED_LIST, String.join(",", serverBaubleBlockedList));
        }
        store(props, serverConfigFile, "Maid File Manager server config (edited via in-game settings, OP only)");
    }

    /**
     * 加载整合包饰品导入配置 {@code config/maid_file_manager/bauble_import.json}。
     *
     * <p>文件存在即接管（{@code managed=true}）：以文件内容覆盖 properties 的黑名单，并启用白名单，
     * 游戏内不再允许修改这两张清单。文件不存在则维持 properties 行为（不托管）。
     *
     * <p>解析失败时<b>失败关闭</b>：置为托管且强制白名单（此时白名单为空 → 拒绝导入全部饰品），
     * 并打 ERROR 日志，绝不放任整合包作者的管控被静默绕过。
     */
    private static void loadBaubleImportConfig() {
        if (baubleImportConfigFile == null || !Files.isRegularFile(baubleImportConfigFile)) {
            baubleConfigManaged = false;
            baubleWhitelistEnforced = false;
            serverBaubleWhitelist = List.of();
            return;
        }
        try (Reader reader = Files.newBufferedReader(baubleImportConfigFile, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            List<String> whitelist = readStringArray(root, "whitelist");
            List<String> blacklist = readStringArray(root, "blacklist");
            serverBaubleWhitelist = whitelist;
            serverBaubleBlockedList = blacklist;
            baubleConfigManaged = true;
            baubleWhitelistEnforced = !whitelist.isEmpty();
            Constants.LOG.info("[女仆档案管理] 已加载整合包饰品导入配置（已托管）：白名单 {} 项，黑名单 {} 项",
                    whitelist.size(), blacklist.size());
        } catch (Throwable t) {
            serverBaubleWhitelist = List.of();
            serverBaubleBlockedList = List.of();
            baubleConfigManaged = true;
            baubleWhitelistEnforced = true;
            Constants.LOG.error("[女仆档案管理] 整合包饰品导入配置解析失败，已按「拒绝导入全部饰品」的失败关闭策略处理，"
                    + "请检查 {}: {}", baubleImportConfigFile, t.toString());
        }
    }

    /** 读取 JSON 数组字段为去重后的字符串列表；字段缺失或类型不符返回空列表 */
    private static List<String> readStringArray(JsonObject root, String key) {
        if (root == null || !root.has(key) || !root.get(key).isJsonArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray(key)) {
            if (element == null || !element.isJsonPrimitive()) {
                continue;
            }
            String value = element.getAsString();
            if (value == null) {
                continue;
            }
            value = value.trim();
            if (!value.isEmpty() && !out.contains(value)) {
                out.add(value);
            }
        }
        return List.copyOf(out);
    }

    private static void saveClient() {
        Properties props = new Properties();
        props.setProperty(KEY_ALLOW_SERVER_EXPORT, String.valueOf(clientAllowServerExport));
        // 每个勾选过"不再提示"的存档各写一行（只存 true；false=默认值不落盘）
        for (String worldKey : clientSkipRemoveConfirmByWorld.keySet()) {
            props.setProperty(SKIP_REMOVE_CONFIRM_PREFIX + worldKey, String.valueOf(true));
        }
        store(props, clientConfigFile, "Maid File Manager client config");
    }

    /** 原子写：先写 .tmp 再移动替换，避免写一半崩溃导致配置文件损坏丢失全部设置 */
    private static void store(Properties props, Path file, String comment) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                props.store(writer, comment);
            }
            try {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Constants.LOG.error("[女仆档案管理] 保存配置文件失败: {}", file, e);
        }
    }

    private static boolean parse(Properties props, String key, boolean defaultValue) {
        String value = props.getProperty(key);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        return value.equalsIgnoreCase("true");
    }

    /** 解析逗号分隔的 ID 列表；容忍空白与重复，返回不可变列表 */
    private static List<String> parseIdList(Properties props, String key) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : value.split(",")) {
            String id = part.trim();
            if (!id.isEmpty() && !out.contains(id)) {
                out.add(id);
            }
        }
        return List.copyOf(out);
    }
}
