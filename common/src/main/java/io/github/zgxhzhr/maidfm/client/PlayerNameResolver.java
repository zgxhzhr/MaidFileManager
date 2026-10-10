package io.github.zgxhzhr.maidfm.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zgxhzhr.maidfm.Constants;
import net.minecraft.client.Minecraft;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家名解析器：把玩家 UUID 解析成游戏名，供备份管理器显示「所属玩家」。
 *
 * <p>解析顺序：
 * <ol>
 *   <li>游戏目录下由 Minecraft 本体维护的 {@code usernamecache.json}（「UUID → 最后已知用户名」
 *       对照表，正版与离线玩家登录过的名字都在其中）——命中即用，不发起任何网络请求；</li>
 *   <li>本模组自己的缓存 {@code config/maid_file_manager/name_cache.json}，优先级高于前者，
 *       因为它记录的是实时接口查询结果或界面手输结果；</li>
 *   <li>两者都未命中时，正版玩家通过 Mojang 公开接口
 *       {@code https://api.mojang.com/user/profile/<无横线UUID>} 查询，接口限速约为每分钟一次，
 *       故采用单后台线程串行队列 + 最小间隔 {@link Constants#NAME_LOOKUP_MIN_INTERVAL_MS} 的节流策略，
 *       结果落盘到自己的缓存。</li>
 * </ol>
 *
 * <p>离线模式的玩家名也可由界面上的输入框提供：先用 {@link #resolveUuidByName(String)}
 * 在已载入的名字对照表里反查 UUID（可命中正版 UUID），未命中再按
 * {@code OfflinePlayer:<名字>} 规则计算离线 UUID 与列表中记录的 UUID 匹配，
 * 匹配成功才用该名字显示，匹配不上则继续显示 UUID。
 */
public final class PlayerNameResolver {
    /** 解析结果回调（在客户端主线程触发） */
    public interface Listener {
        void onNameResolved(UUID uuid, String name);
    }

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();
    private static final Deque<Pending> QUEUE = new ArrayDeque<>();

    private static boolean cacheLoaded;
    private static boolean workerRunning;
    /** 上一次成功发起请求的时间（毫秒），0 表示尚未请求过 */
    private static long lastRequestMs;

    private PlayerNameResolver() {
    }

    private record Pending(UUID uuid, Listener listener) {
    }

    // ---------- 缓存 ----------

    private static Path cacheFile(Path gameDir) {
        return gameDir.resolve("config/" + Constants.MOD_ID + "/" + Constants.NAME_CACHE_FILE);
    }

    private static String key(UUID uuid) {
        return uuid.toString();
    }

    /** 载入本地名字缓存（幂等，首次调用时读取） */
    public static void loadCache(Path gameDir) {
        if (cacheLoaded) {
            return;
        }
        cacheLoaded = true;
        loadUsernameCache(gameDir);
        loadOwnCache(gameDir);
    }

    /**
     * 载入游戏目录下由 Minecraft 本体维护的 {@code usernamecache.json}。
     *
     * <p>该文件可能在版本隔离模式下位于实例目录根部，键既有带连字符的写法也有不带的，
     * 统一规范化后并入缓存；文件由游戏或其他工具写入，格式异常时只记 WARN，
     * 不影响本模组自身的缓存与后续解析。
     */
    private static void loadUsernameCache(Path gameDir) {
        Path file = gameDir.resolve(Constants.USERNAME_CACHE_FILE);
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            int count = 0;
            for (String k : obj.keySet()) {
                String name = obj.get(k).getAsString();
                UUID uuid = parseUuid(k);
                if (uuid == null || name == null || name.isEmpty()) {
                    continue;
                }
                CACHE.put(key(uuid), name);
                count++;
            }
            Constants.LOG.info("[maid_file_manager] 已从 usernamecache.json 载入 {} 条玩家名", count);
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 读取 usernamecache.json 失败: {}", t.toString());
        }
    }

    /** 载入本模组自己的名字缓存（优先级高于 usernamecache.json，后载入以覆盖同名键） */
    private static void loadOwnCache(Path gameDir) {
        Path file = cacheFile(gameDir);
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            for (String k : obj.keySet()) {
                CACHE.put(k, obj.get(k).getAsString());
            }
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 读取玩家名缓存失败: {}", t.toString());
        }
    }

    /** 解析 UUID 字符串：容忍带连字符与不带连字符两种写法 */
    private static UUID parseUuid(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().replace("-", "");
        if (s.length() != 32) {
            return null;
        }
        try {
            return UUID.fromString(s.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})",
                    "$1-$2-$3-$4-$5"));
        } catch (Throwable t) {
            return null;
        }
    }

    private static void saveCache() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gameDirectory == null) {
            return;
        }
        Path file = cacheFile(mc.gameDirectory.toPath());
        try {
            Files.createDirectories(file.getParent());
            JsonObject obj = new JsonObject();
            for (Map.Entry<String, String> e : CACHE.entrySet()) {
                obj.addProperty(e.getKey(), e.getValue());
            }
            Files.writeString(file, new Gson().toJson(obj), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 写入玩家名缓存失败: {}", t.toString());
        }
    }

    // ---------- 查询 ----------

    /** 取已缓存的名字；未缓存返回 null（不会触发网络请求） */
    public static String getCached(UUID uuid) {
        return uuid == null ? null : CACHE.get(key(uuid));
    }

    /** 手动写入名字（例如离线玩家输入的匹配结果），持久化到缓存 */
    public static void putCached(UUID uuid, String name) {
        if (uuid == null || name == null || name.isEmpty()) {
            return;
        }
        CACHE.put(key(uuid), name);
        saveCache();
    }

    /** 请求解析：命中缓存立即回调，否则入队等待后台线程节流查询 */
    public static void resolve(UUID uuid, Listener listener) {
        if (uuid == null || listener == null) {
            return;
        }
        String cached = CACHE.get(key(uuid));
        if (cached != null) {
            listener.onNameResolved(uuid, cached);
            return;
        }
        synchronized (QUEUE) {
            QUEUE.add(new Pending(uuid, listener));
            QUEUE.notifyAll();
        }
        ensureWorker();
    }

    /** 按离线模式规则计算 UUID（与 Minecraft 服务端 OfflinePlayer 规则一致） */
    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 按用户名反查 UUID。
     *
     * <p>先在已载入的名字对照表（含 {@code usernamecache.json} 与本模组缓存）里按名字匹配
     * （忽略大小写），命中即返回其 UUID，因此正版玩家的 UUID 同样能被找到；
     * 对照表未命中再按 {@code OfflinePlayer:<名字>} 规则计算离线 UUID。
     */
    public static UUID resolveUuidByName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, String> e : CACHE.entrySet()) {
            if (e.getValue().equalsIgnoreCase(name)) {
                try {
                    return UUID.fromString(e.getKey());
                } catch (Throwable ignored) {
                    // 键不是标准 UUID 字符串，跳过该条
                }
            }
        }
        return offlineUuid(name);
    }

    // ---------- 后台节流线程 ----------

    private static synchronized void ensureWorker() {
        if (workerRunning) {
            return;
        }
        workerRunning = true;
        Thread thread = new Thread(PlayerNameResolver::workerLoop, "maidfm-name-resolver");
        thread.setDaemon(true);
        thread.start();
    }

    private static void workerLoop() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        while (true) {
            Pending pending;
            synchronized (QUEUE) {
                while (QUEUE.isEmpty()) {
                    try {
                        QUEUE.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                pending = QUEUE.poll();
            }
            try {
                // 接口限速：两次请求之间至少间隔一分钟
                long elapsed = System.currentTimeMillis() - lastRequestMs;
                if (lastRequestMs > 0 && elapsed < Constants.NAME_LOOKUP_MIN_INTERVAL_MS) {
                    Thread.sleep(Constants.NAME_LOOKUP_MIN_INTERVAL_MS - elapsed);
                }
                lastRequestMs = System.currentTimeMillis();
                String name = fetchName(client, pending.uuid());
                if (name != null && !name.isEmpty()) {
                    CACHE.put(key(pending.uuid()), name);
                    saveCache();
                    UUID uuid = pending.uuid();
                    Listener listener = pending.listener();
                    Minecraft.getInstance().execute(() -> listener.onNameResolved(uuid, name));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                Constants.LOG.warn("[maid_file_manager] 查询玩家名失败: {}", t.toString());
            }
        }
    }

    private static String fetchName(HttpClient client, UUID uuid) throws Exception {
        String id = uuid.toString().replace("-", "");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.mojang.com/user/profile/" + id))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }
        JsonObject obj = JsonParser.parseString(response.body()).getAsJsonObject();
        return obj.has("name") ? obj.get("name").getAsString() : null;
    }
}
