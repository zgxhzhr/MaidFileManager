package io.github.zgxhzhr.maidfm.config;

import io.github.zgxhzhr.maidfm.Constants;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 整合包饰品导入配置（{@code config/maid_file_manager/bauble_import.json}）。
 *
 * <p>从 {@code MaidConfigManager} 拆出的独立配置域。它和 properties 那两个域的区别在于
 * <b>由整合包作者托管、游戏内只读</b>，而且有一条别人没有的语义：<b>失败关闭</b>。
 *
 * <p><b>失败关闭</b>：文件存在但解析失败时，不是「当作没有配置」而是「拒绝导入全部饰品」。
 * 因为把管控静默放宽与文档承诺相反——整合包作者写了白名单，玩家却仍能把白名单外的饰品
 * 带进来，他根本发现不了。宁可这次导入拿不到饰品，也不能让管控悄悄失效。
 *
 * <p>判定口径与 properties 侧一致：清单装载时统一 {@code toLowerCase}，比较前同样归一化，
 * 否则手写配置里带大写的条目永不命中（这是 fail-open 的反向错误）。
 */
public final class BaubleImportConfig {

    /** 整合包白名单（来自 bauble_import.json；不可变，默认空） */
    private static volatile List<String> whitelist = List.of();

    /** 整合包黑名单（不可变，默认空；游戏内只读，OP 亦不可改） */
    private static volatile List<String> blacklist = List.of();

    /** 整合包按模组禁用清单（bauble_import.json 的 {@code blocked_namespaces}；不可变，默认空） */
    private static volatile List<String> blockedNamespaces = List.of();

    /** 整合包按 Curios 槽位禁用清单（{@code blocked_curios_slots}；不可变，默认空） */
    private static volatile List<String> blockedCuriosSlots = List.of();

    /** 是否存在整合包配置（文件存在即 true；用于界面提示与判定来源，不代表整张列表只读） */
    private static volatile boolean managed = false;

    /**
     * 是否强制白名单：白名单非空时为 true；配置文件解析失败时亦置 true 并按「拒绝全部饰品」处理
     * （失败关闭：宁可拒绝也不能让整合包作者的管控被静默绕过）。
     */
    private static volatile boolean whitelistEnforced = false;

    private BaubleImportConfig() {
    }

    /**
     * 加载配置文件。文件不存在则维持 properties 行为（无整合包管控）。
     *
     * @param file 配置文件路径；{@code null} 视为不存在
     */
    public static void load(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            managed = false;
            whitelistEnforced = false;
            whitelist = List.of();
            blacklist = List.of();
            blockedNamespaces = List.of();
            blockedCuriosSlots = List.of();
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            List<String> wl = ConfigFileIo.readStringArray(root, "whitelist");
            List<String> bl = ConfigFileIo.readStringArray(root, "blacklist");
            whitelist = wl;
            blacklist = bl;
            blockedNamespaces = ConfigFileIo.sanitizeList(ConfigFileIo.readStringArray(root, "blocked_namespaces"));
            blockedCuriosSlots = ConfigFileIo.sanitizeList(ConfigFileIo.readStringArray(root, "blocked_curios_slots"));
            managed = true;
            whitelistEnforced = !wl.isEmpty();
            Constants.LOG.info("[maid_file_manager] 已加载整合包饰品导入配置：白名单 {} 项，黑名单 {} 项，"
                            + "按模组禁用 {} 项，按 Curios 槽位禁用 {} 项",
                    wl.size(), bl.size(), blockedNamespaces.size(), blockedCuriosSlots.size());
        } catch (Throwable t) {
            whitelist = List.of();
            blacklist = List.of();
            blockedNamespaces = List.of();
            blockedCuriosSlots = List.of();
            managed = true;
            // 失败关闭：强制白名单 + 白名单为空 => 拒绝导入全部饰品
            whitelistEnforced = true;
            Constants.LOG.error("[maid_file_manager] 整合包饰品导入配置解析失败，已按「拒绝导入全部饰品」的失败关闭策略处理，"
                    + "请检查 {}: {}", file, t.toString());
        }
    }

    /**
     * 应用服务端同步下来的配置（S2C 场景）。
     *
     * <p>直接采用服务端判定结果，不在客户端重算——服务端在 json 解析失败时会「失败关闭」
     * 并置 {@code enforced=true}，客户端若按「managed && 白名单非空」自行重算会得出相反结论
     * （界面显示可放行、实际全拒绝）。
     */
    public static void applyServerSync(List<String> newWhitelist, List<String> newBlacklist,
                                       List<String> newBlockedNamespaces, List<String> newBlockedCuriosSlots,
                                       boolean newManaged, boolean newWhitelistEnforced) {
        whitelist = List.copyOf(newWhitelist == null ? List.of() : newWhitelist);
        blacklist = List.copyOf(newBlacklist == null ? List.of() : newBlacklist);
        blockedNamespaces = List.copyOf(
                newBlockedNamespaces == null ? List.of() : ConfigFileIo.sanitizeList(newBlockedNamespaces));
        blockedCuriosSlots = List.copyOf(
                newBlockedCuriosSlots == null ? List.of() : ConfigFileIo.sanitizeList(newBlockedCuriosSlots));
        managed = newManaged;
        whitelistEnforced = newWhitelistEnforced;
    }

    public static List<String> whitelist() {
        return whitelist;
    }

    public static List<String> blacklist() {
        return blacklist;
    }

    public static List<String> blockedNamespaces() {
        return blockedNamespaces;
    }

    public static List<String> blockedCuriosSlots() {
        return blockedCuriosSlots;
    }

    public static boolean isManaged() {
        return managed;
    }

    public static boolean isWhitelistEnforced() {
        return whitelistEnforced;
    }

    /**
     * 判定单件饰品是否通过整合包白名单。
     *
     * <p>未启用白名单时一律放行；启用时需物品注册 ID 或物品中文显示名精确命中，否则拒绝。
     */
    public static boolean isWhitelistPass(String id, String displayName) {
        if (!whitelistEnforced) {
            return true;
        }
        String normId = normalize(id);
        String normName = normalize(displayName);
        if (normId != null && !normId.isEmpty() && whitelist.contains(normId)) {
            return true;
        }
        return normName != null && !normName.isEmpty() && whitelist.contains(normName);
    }

    /** 是否命中整合包黑名单（ID 或中文显示名，大小写不敏感）。 */
    public static boolean isBlacklisted(String id, String displayName) {
        String normId = normalize(id);
        String normName = normalize(displayName);
        if (normId != null && !normId.isEmpty() && blacklist.contains(normId)) {
            return true;
        }
        return normName != null && !normName.isEmpty() && blacklist.contains(normName);
    }

    private static String normalize(String s) {
        return s == null ? null : s.trim().toLowerCase(Locale.ROOT);
    }
}
