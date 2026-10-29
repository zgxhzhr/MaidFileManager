package io.github.zgxhzhr.maidfm.service;

import io.github.zgxhzhr.maidfm.Constants;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端侧的「模型 ID → 显示名」汇总表。
 *
 * <p><b>为什么需要它</b>：备份列表要显示女仆的模型名，但中文名只存在于「装了那个模型包的那台机器」上。
 * 服务端往往没装玩家私有模型，自己解析只能拿到 {@code namespace:path}；客户端 C 同样没有别人（B）的私有模型。
 * 因此让每个客户端在进服时把自己认识的「模型 ID → 名字」上报上来，服务端合并成一张表，
 * 备份列表解析时优先查这张表——只要该模型的主人至少上线过一次，别的客户端就能看到正确名字。
 *
 * <p><b>覆盖边界</b>：只覆盖「至少上线过一次」的模型。从未上线过的模型，
 * 服务端与任何客户端都无从得知其名字，最终回退到 {@code ModelNames} 的 path 美化显示。
 *
 * <p>表按服务器实例生命周期存活（进程内），不落盘：模型名属于展示性数据，
 * 每次有人上线都会重新上报，没必要持久化，也避免跨存档串味。
 */
public final class ModelNameRegistry {

    /** modelId → 显示名 */
    private static final Map<String, String> NAMES = new ConcurrentHashMap<>();

    /**
     * 客户端侧的外部名字来源（本地缓存）。
     *
     * <p>common 层不能引用 client 包（专用服务端会裁剪 client 类），所以用函数注入：
     * 客户端初始化时把 {@code ClientModelNameCache::lookup} 挂进来，
     * {@link MaidBackupService} 查不到注册表时再问它。
     */
    public static volatile java.util.function.Function<String, String> externalProvider;

    /** 上限：防止恶意客户端用超大映射把服务端内存打爆 */
    private static final int MAX_ENTRIES = 8192;
    /** 单个键/值的长度上限（模型 ID 与名字都不该比这更长） */
    private static final int MAX_TEXT = 128;

    private ModelNameRegistry() {
    }

    /** 合并一批上报（键值交替的展平列表，形如 [k1,v1,k2,v2,...]） */
    public static void mergeFromPairs(Iterable<String> pairs) {
        if (pairs == null) {
            return;
        }
        String key = null;
        int added = 0;
        for (String s : pairs) {
            if (key == null) {
                key = s;
                continue;
            }
            if (key != null && !key.isEmpty() && s != null && !s.isEmpty()
                    && key.length() <= MAX_TEXT && s.length() <= MAX_TEXT
                    && NAMES.size() < MAX_ENTRIES) {
                if (NAMES.putIfAbsent(key, s) == null) {
                    added++;
                }
            }
            key = null;
        }
        if (added > 0) {
            Constants.LOG.info("[maid_file_manager] 模型名注册表：新增 {} 条（累计 {} 条）", added, NAMES.size());
        }
    }

    /** 直接合并一个 Map（测试与内部使用） */
    public static void merge(Map<String, String> in) {
        if (in == null || in.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> e : in.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            if (k == null || k.isEmpty() || v == null || v.isEmpty()) {
                continue;
            }
            if (k.length() > MAX_TEXT || v.length() > MAX_TEXT) {
                continue;
            }
            if (NAMES.size() >= MAX_ENTRIES) {
                break;
            }
            NAMES.putIfAbsent(k, v);
        }
    }

    /** 服务端启动：从存档加载上次持久化的注册表（重启后不用等客户端重新上报） */
    public static void loadFromServer(net.minecraft.server.MinecraftServer server) {
        NAMES.clear();
        try {
            java.util.Map<String, String> loaded = ModelNameStore.load(ModelNameStore.serverPath(server));
            if (!loaded.isEmpty()) {
                NAMES.putAll(loaded);
                Constants.LOG.info("[maid_file_manager] 模型名注册表：从存档恢复 {} 条", loaded.size());
            }
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 恢复模型名注册表失败（忽略）: {}", t.toString());
        }
    }

    /** 服务端停止：先落盘再清空，避免跨服务器串味 */
    public static void saveAndClear(net.minecraft.server.MinecraftServer server) {
        try {
            if (!NAMES.isEmpty()) {
                ModelNameStore.save(ModelNameStore.serverPath(server), NAMES);
                Constants.LOG.info("[maid_file_manager] 模型名注册表已保存 {} 条", NAMES.size());
            }
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 保存模型名注册表失败（忽略）: {}", t.toString());
        } finally {
            NAMES.clear();
        }
    }

    /** 查名字；没有返回 null */
    public static String get(String modelId) {
        if (modelId == null || modelId.isEmpty()) {
            return null;
        }
        return NAMES.get(modelId);
    }

    /** 当前条目数（诊断用） */
    public static int size() {
        return NAMES.size();
    }

    /** 清空：服务器停止或切换到另一个服务器时调用，避免把 A 服的名字带到 B 服 */
    public static void clear() {
        NAMES.clear();
    }

    /** 快照（诊断用） */
    public static Map<String, String> snapshot() {
        return new LinkedHashMap<>(NAMES);
    }
}
