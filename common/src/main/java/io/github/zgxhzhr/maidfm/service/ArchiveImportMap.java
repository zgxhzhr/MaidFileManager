package io.github.zgxhzhr.maidfm.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zgxhzhr.maidfm.Constants;
import net.minecraft.server.MinecraftServer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 存档级的「.maid 源 UUID → 导入产物 UUID」记录。
 *
 * <p>为什么按存档隔离而不是全局：派生新 UUID 是「当前存档里原 UUID 被占用」的产物，
 * 属于存档局部信息。若跨存档共享，会把 A 存档的冲突强加到 B 存档（B 里原 UUID 明明可用，
 * 却被迫沿用 A 派生出的新 UUID）。因此记录落在存档内，与 TLM 的 maid_backups 同级。
 *
 * <p>文件位置：{@code <存档>/data/maid_file_manager/import_map.json}，内容形如
 * {@code {"<源UUID>|<玩家UUID>": "<目标UUID>"}}。
 *
 * <p>作用有二：
 * <ol>
 *   <li>幂等：同一份文件在同一存档重复导入时复用同一 UUID，不会再生成第二只；</li>
 *   <li>避让：原 UUID 被占用时派生的目标 UUID 会被记住，后续导入沿用同一结果。</li>
 * </ol>
 */
public final class ArchiveImportMap {

    private static final String FILE_NAME = "import_map.json";
    /** 进程内缓存：key 为「存档数据目录的绝对路径」，避免每次导入都读盘 */
    private static final Map<String, Map<String, String>> CACHE = new ConcurrentHashMap<>();

    private ArchiveImportMap() {
    }

    /** 记录文件路径：与 TLM 备份根同级（两者都在存档的 data 目录下） */
    private static Path mapFile(MinecraftServer server) {
        Path backupRoot = MaidBackupService.backupRoot(server);
        return backupRoot.getParent().resolve(Constants.MOD_ID).resolve(FILE_NAME);
    }

    private static String cacheKey(MinecraftServer server) {
        return mapFile(server).toAbsolutePath().toString();
    }

    private static Map<String, String> load(MinecraftServer server) {
        String key = cacheKey(server);
        return CACHE.computeIfAbsent(key, k -> {
            Map<String, String> out = new ConcurrentHashMap<>();
            Path file = mapFile(server);
            if (!Files.isRegularFile(file)) {
                return out;
            }
            try {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                JsonObject obj = JsonParser.parseString(text).getAsJsonObject();
                for (String k2 : obj.keySet()) {
                    out.put(k2, obj.get(k2).getAsString());
                }
            } catch (Throwable t) {
                Constants.LOG.warn("[maid_file_manager] 读取导入 UUID 记录失败（按空记录继续）: {}", t.toString());
            }
            return out;
        });
    }

    private static String entryKey(UUID sourceUuid, UUID playerUuid) {
        return sourceUuid + "|" + playerUuid;
    }

    /** 查这条「源 UUID + 玩家」此前导入得到的 UUID；没有记录返回 null */
    public static UUID lookup(MinecraftServer server, UUID sourceUuid, UUID playerUuid) {
        if (server == null || sourceUuid == null || playerUuid == null) {
            return null;
        }
        String v = load(server).get(entryKey(sourceUuid, playerUuid));
        if (v == null || v.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(v);
        } catch (IllegalArgumentException e) {
            Constants.LOG.warn("[maid_file_manager] 导入 UUID 记录损坏，忽略: {}", v);
            return null;
        }
    }

    /** 记录本次导入的结果 UUID（覆盖旧值），并立即落盘 */
    public static void record(MinecraftServer server, UUID sourceUuid, UUID playerUuid, UUID targetUuid) {
        if (server == null || sourceUuid == null || playerUuid == null || targetUuid == null) {
            return;
        }
        Map<String, String> map = load(server);
        map.put(entryKey(sourceUuid, playerUuid), targetUuid.toString());
        Path file = mapFile(server);
        try {
            Files.createDirectories(file.getParent());
            JsonObject obj = new JsonObject();
            for (Map.Entry<String, String> e : map.entrySet()) {
                obj.addProperty(e.getKey(), e.getValue());
            }
            Files.writeString(file, obj.toString(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 写入导入 UUID 记录失败（不影响本次导入）: {}", t.toString());
        }
    }
}
