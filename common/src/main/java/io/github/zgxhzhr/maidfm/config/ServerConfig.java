package io.github.zgxhzhr.maidfm.config;

import io.github.zgxhzhr.maidfm.Constants;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * 服务端配置（{@code config/maid_file_manager-server.properties}，OP 在游戏内修改）。
 *
 * <p>从 {@code MaidConfigManager} 拆出的第三个域，也是三者中性质最特殊的一个：
 * <b>它同时是服务端的权威值和客户端的同步缓存</b>。服务端读自己的文件；客户端收到
 * S2C 同步后把同一批字段覆盖成服务端下发的值——所以 {@code cachedXxx} 与
 * {@code isXxx} 读的是同一个字段，只是语义标签不同。
 *
 * <p>配置键是<b>对外契约</b>：C2S {@code set_server_config} 包里传的就是这些字符串，
 * 未知键必须返回 false 让调用方回执失败、且不得触发全服广播。所以这张键表不能随手改。
 */
public final class ServerConfig {

    // ---------- 布尔键 ----------
    /** 服务端配置键：是否允许客户端导入女仆（默认开，方便单人玩家） */
    public static final String KEY_ALLOW_CLIENT_IMPORT = "allow_client_import";
    /** 服务端配置键：导入时是否允许携带饰品 */
    public static final String KEY_ALLOW_BAUBLES = "allow_baubles";
    /** 服务端配置键：导入时是否允许一并转移 TLM 成就 */
    public static final String KEY_ALLOW_ADVANCEMENTS = "allow_advancements";
    /** 服务端配置键：导入时是否允许恢复药水效果到实体 */
    public static final String KEY_ALLOW_EFFECTS = "allow_effects";
    /** 服务端配置键：导入时是否允许带走 TLM 本体无敌状态 */
    public static final String KEY_ALLOW_INVULNERABLE = "allow_invulnerable";
    /** 服务端配置键：导入时丢弃饰品属性（附魔/耐久/无法破坏/属性修饰符等），恢复为全新物品（默认关=保留） */
    public static final String KEY_BAUBLE_STRIP_ATTRIBUTES = "bauble_strip_attributes";
    /**
     * 服务端配置键：是否允许携带 Curios 饰品。
     *
     * <p>默认关闭 = 女仆 Curios 槽位内的饰品一律不随女仆迁移；开启后也只携带
     * <b>被声明允许</b>的命名空间，未声明仍丢弃。背包类槽位（back 等）无论本开关如何都禁止携带。
     */
    public static final String KEY_CARRY_CURIOS = "carry_curios_baubles";

    // ---------- 清单键 ----------
    /** 服务端配置键：OP 维护的「按模组（命名空间）禁用携带」清单（逗号分隔；同时作用于饰品栏与 Curios） */
    public static final String KEY_BAUBLE_BLOCKED_NAMESPACES = "bauble_blocked_namespaces";
    /** 服务端配置键：OP 维护的「按 Curios 槽位禁用携带」清单（逗号分隔） */
    public static final String KEY_BAUBLE_BLOCKED_CURIOS_SLOTS = "bauble_blocked_curios_slots";
    /** 服务端配置键：禁用携带的饰品 ID 列表（逗号分隔，仅限车万本体/万法皆通两命名空间，导入时直接丢弃） */
    public static final String KEY_BAUBLE_BLOCKED_LIST = "bauble_blocked_list";

    // ---------- 状态 ----------
    /** 服务端配置内存值（volatile 保证跨线程可见：网络线程写、服务端逻辑线程读） */
    private static volatile boolean allowClientImport = true;
    private static volatile boolean allowBaubles = true;
    private static volatile boolean allowAdvancements = true;
    private static volatile boolean allowEffects = true;
    private static volatile boolean allowInvulnerable = false;
    private static volatile boolean baubleStripAttributes = false;
    private static volatile boolean carryCurios = false;
    /** OP 维护的按模组（命名空间）禁用清单（不可变，默认空） */
    private static volatile List<String> blockedNamespaces = List.of();
    /** OP 维护的按 Curios 槽位禁用清单（不可变，默认空） */
    private static volatile List<String> blockedCuriosSlots = List.of();
    /** OP 在游戏内维护的「禁用携带饰品」清单（不可变，默认空；与整合包黑名单相互独立） */
    private static volatile List<String> blockedList = List.of();

    private static Path configFile;

    private ServerConfig() {
    }

    /** 设置配置文件路径并加载（加载失败不抛出：回退默认值，不能因此让游戏启动崩溃）。 */
    public static void init(Path file) {
        configFile = file;
        load();
    }

    private static void load() {
        Properties props = ConfigFileIo.load(configFile, "服务端配置");
        allowClientImport = ConfigFileIo.parse(props, KEY_ALLOW_CLIENT_IMPORT, true);
        allowBaubles = ConfigFileIo.parse(props, KEY_ALLOW_BAUBLES, true);
        allowAdvancements = ConfigFileIo.parse(props, KEY_ALLOW_ADVANCEMENTS, true);
        allowEffects = ConfigFileIo.parse(props, KEY_ALLOW_EFFECTS, true);
        allowInvulnerable = ConfigFileIo.parse(props, KEY_ALLOW_INVULNERABLE, false);
        baubleStripAttributes = ConfigFileIo.parse(props, KEY_BAUBLE_STRIP_ATTRIBUTES, false);
        carryCurios = ConfigFileIo.parse(props, KEY_CARRY_CURIOS, false);
        blockedNamespaces = ConfigFileIo.parseSanitizedList(props, KEY_BAUBLE_BLOCKED_NAMESPACES);
        blockedCuriosSlots = ConfigFileIo.parseSanitizedList(props, KEY_BAUBLE_BLOCKED_CURIOS_SLOTS);
        // 与另外两张清单口径对齐：统一走 parseSanitizedList（trim + 去重 + 小写），
        // 否则手写配置里带大写字母的条目永远命中不了，黑名单方向属于"失败开放"。
        blockedList = ConfigFileIo.parseSanitizedList(props, KEY_BAUBLE_BLOCKED_LIST);
    }

    private static boolean save() {
        if (configFile == null) {
            return false;
        }
        Properties props = new Properties();
        props.setProperty(KEY_ALLOW_CLIENT_IMPORT, String.valueOf(allowClientImport));
        props.setProperty(KEY_ALLOW_BAUBLES, String.valueOf(allowBaubles));
        props.setProperty(KEY_ALLOW_ADVANCEMENTS, String.valueOf(allowAdvancements));
        props.setProperty(KEY_ALLOW_EFFECTS, String.valueOf(allowEffects));
        props.setProperty(KEY_ALLOW_INVULNERABLE, String.valueOf(allowInvulnerable));
        props.setProperty(KEY_BAUBLE_STRIP_ATTRIBUTES, String.valueOf(baubleStripAttributes));
        props.setProperty(KEY_CARRY_CURIOS, String.valueOf(carryCurios));
        if (!blockedNamespaces.isEmpty()) {
            props.setProperty(KEY_BAUBLE_BLOCKED_NAMESPACES, String.join(",", blockedNamespaces));
        }
        if (!blockedCuriosSlots.isEmpty()) {
            props.setProperty(KEY_BAUBLE_BLOCKED_CURIOS_SLOTS, String.join(",", blockedCuriosSlots));
        }
        // OP 维护的禁用清单始终写入 properties；整合包黑名单来自 bauble_import.json，不在此持久化
        if (!blockedList.isEmpty()) {
            props.setProperty(KEY_BAUBLE_BLOCKED_LIST, String.join(",", blockedList));
        }
        return ConfigFileIo.store(props, configFile);
    }

    // ---------- 读取 ----------

    public static boolean isAllowClientImport() {
        return allowClientImport;
    }

    public static boolean isAllowBaubles() {
        return allowBaubles;
    }

    public static boolean isAllowAdvancements() {
        return allowAdvancements;
    }

    public static boolean isAllowEffects() {
        return allowEffects;
    }

    public static boolean isAllowInvulnerable() {
        return allowInvulnerable;
    }

    public static boolean isBaubleStripAttributes() {
        return baubleStripAttributes;
    }

    public static boolean isCarryCurios() {
        return carryCurios;
    }

    /** 纯 OP 清单（不含整合包项）：用于 S2C 同步，客户端据此显示"可取消的勾选"。 */
    public static List<String> opBlockedNamespaces() {
        return blockedNamespaces;
    }

    /** 纯 OP 清单（不含整合包项）：用于 S2C 同步。 */
    public static List<String> opBlockedCuriosSlots() {
        return blockedCuriosSlots;
    }

    /** 纯 OP 清单（不含整合包项）：用于 S2C 同步。 */
    public static List<String> opBlockedList() {
        return blockedList;
    }

    /**
     * 按配置键读取**服务端**当前生效值（客户端缓存）。
     *
     * <p>界面里的服务端开关是「乐观切换 + 超时回滚」：点击时先翻转本地显示并置 pending，
     * 若若干毫秒内没收到服务端配置同步，就用本方法取回权威值把开关复位。
     * 未知键返回 {@code false}（调用方只在已知的服务端键上使用）。
     */
    public static boolean getByKey(String key) {
        if (key == null) {
            return false;
        }
        return switch (key) {
            case KEY_ALLOW_CLIENT_IMPORT -> allowClientImport;
            case KEY_ALLOW_BAUBLES -> allowBaubles;
            case KEY_ALLOW_ADVANCEMENTS -> allowAdvancements;
            case KEY_ALLOW_EFFECTS -> allowEffects;
            case KEY_ALLOW_INVULNERABLE -> allowInvulnerable;
            case KEY_BAUBLE_STRIP_ATTRIBUTES -> baubleStripAttributes;
            case KEY_CARRY_CURIOS -> carryCurios;
            default -> false;
        };
    }

    // ---------- 写入 ----------

    /**
     * 服务端修改布尔配置（C2S 包处理，调用方已做 OP 权限校验）。
     *
     * @return true=配置键合法且已落盘；false=未知键或写盘失败，调用方须回执失败且不得触发全服广播
     */
    public static synchronized boolean setByKey(String key, boolean value) {
        if (KEY_ALLOW_CLIENT_IMPORT.equals(key)) {
            allowClientImport = value;
        } else if (KEY_ALLOW_BAUBLES.equals(key)) {
            allowBaubles = value;
        } else if (KEY_ALLOW_ADVANCEMENTS.equals(key)) {
            allowAdvancements = value;
        } else if (KEY_ALLOW_EFFECTS.equals(key)) {
            allowEffects = value;
        } else if (KEY_ALLOW_INVULNERABLE.equals(key)) {
            allowInvulnerable = value;
        } else if (KEY_BAUBLE_STRIP_ATTRIBUTES.equals(key)) {
            baubleStripAttributes = value;
        } else if (KEY_CARRY_CURIOS.equals(key)) {
            carryCurios = value;
        } else {
            Constants.LOG.warn("[maid_file_manager] 收到未知服务端配置键: {}", key);
            return false;
        }
        return save();
    }

    /**
     * 服务端修改清单配置（C2S 包处理，调用方已做 OP 权限校验）。值统一做去空白 / 去重 / 小写规范化。
     *
     * @return 键不认识或写盘失败返回 false
     */
    public static synchronized boolean setListByKey(String key, List<String> values) {
        List<String> cleaned = ConfigFileIo.sanitizeList(values);
        if (KEY_BAUBLE_BLOCKED_LIST.equals(key)) {
            blockedList = cleaned;
        } else if (KEY_BAUBLE_BLOCKED_NAMESPACES.equals(key)) {
            blockedNamespaces = cleaned;
        } else if (KEY_BAUBLE_BLOCKED_CURIOS_SLOTS.equals(key)) {
            blockedCuriosSlots = cleaned;
        } else {
            Constants.LOG.warn("[maid_file_manager] 收到未知清单配置键: {}", key);
            return false;
        }
        return save();
    }

    /**
     * 服务端修改 OP 维护的「禁用携带的饰品」清单（C2S 包处理，调用方已做 OP 权限校验）。
     *
     * <p>仅做去除首尾空白与去重；命名空间收窄由调用方在写入前完成（收窄不放开）。
     * <p>该清单与整合包黑名单相互独立：整合包黑名单由 bauble_import.json 托管、游戏内只读，
     * OP 仍可在此清单上追加/取消自己的禁用项，两者在导入判定时取并集。
     */
    public static synchronized boolean setBaubleBlockedList(List<String> ids) {
        blockedList = List.copyOf(ids == null ? List.of() : ids);
        return save();
    }

    /**
     * 应用服务端同步下来的配置（S2C 场景）。
     *
     * <p>客户端没有本地服务端配置文件，同步是它唯一的来源；同时服务端侧也会走这里
     * （广播前不调用，广播逻辑在 {@code MaidConfigManager} 里）。
     */
    public static void applyServerSync(boolean newAllowClientImport, boolean newAllowBaubles,
                                       boolean newAllowAdvancements, boolean newAllowEffects,
                                       boolean newAllowInvulnerable, boolean newCarryCurios,
                                       boolean newBaubleStripAttributes,
                                       List<String> newBlockedNamespaces, List<String> newBlockedCuriosSlots,
                                       List<String> newBlockedList) {
        allowClientImport = newAllowClientImport;
        allowBaubles = newAllowBaubles;
        allowAdvancements = newAllowAdvancements;
        allowEffects = newAllowEffects;
        allowInvulnerable = newAllowInvulnerable;
        carryCurios = newCarryCurios;
        baubleStripAttributes = newBaubleStripAttributes;
        blockedNamespaces = List.copyOf(
                newBlockedNamespaces == null ? List.of() : ConfigFileIo.sanitizeList(newBlockedNamespaces));
        blockedCuriosSlots = List.copyOf(
                newBlockedCuriosSlots == null ? List.of() : ConfigFileIo.sanitizeList(newBlockedCuriosSlots));
        blockedList = List.copyOf(newBlockedList == null ? List.of() : newBlockedList);
    }
}
