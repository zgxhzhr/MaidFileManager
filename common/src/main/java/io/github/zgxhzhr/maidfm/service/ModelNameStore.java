package io.github.zgxhzhr.maidfm.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zgxhzhr.maidfm.Constants;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「模型 ID → 显示名」映射的磁盘读写。
 *
 * <p>两处用途，共用同一套 JSON 格式：
 * <ul>
 *   <li><b>服务端</b>：{@code <world>/data/maid_file_manager/model_names.json} ——
 *       {@link ModelNameRegistry} 的持久化副本，避免服务器重启后丢掉各客户端上报过的名字</li>
 *   <li><b>客户端</b>：{@code <gameDir>/config/maid_file_manager/client_model_names.json} ——
 *       从服务端下发的备份列表里「学到」的名字。B 客户端退到主界面后走本地扫描，
 *       此时它的注册表是空的，靠这份缓存才不会把 A 的私有模型显示成 path</li>
 * </ul>
 *
 * <p>写入是原子的（先写临时文件再 move），避免服务端被杀时留下半个 JSON 导致下次启动读不出来。
 */
public final class ModelNameStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** 单个映射文件最多收多少条，防止异常数据把文件撑爆 */
    private static final int MAX_ENTRIES = 16384;

    private ModelNameStore() {
    }

    /** 服务端：存档根目录下的持久化路径 */
    public static Path serverPath(net.minecraft.server.MinecraftServer server) {
        return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("data").resolve("maid_file_manager").resolve("model_names.json");
    }

    /** 客户端：config 目录下的缓存路径 */
    public static Path clientPath(Path gameDir) {
        return gameDir.resolve("config").resolve("maid_file_manager").resolve("client_model_names.json");
    }

    /** 读映射；文件不存在或损坏时返回空表（不抛异常，名字只是展示性数据） */
    public static Map<String, String> load(Path file) {
        Map<String, String> out = new LinkedHashMap<>();
        if (file == null || !Files.isRegularFile(file)) {
            return out;
        }
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(r);
            if (!root.isJsonObject()) {
                return out;
            }
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
                if (out.size() >= MAX_ENTRIES) {
                    break;
                }
                String k = e.getKey();
                JsonElement v = e.getValue();
                if (k == null || k.isEmpty() || v == null || !v.isJsonPrimitive()) {
                    continue;
                }
                String name = v.getAsString();
                if (name != null && !name.isEmpty()) {
                    out.put(k, name);
                }
            }
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 读取模型名映射失败（忽略）: {}", t.toString());
        }
        return out;
    }

    /** 写映射（原子写：临时文件 + move）；失败只记日志，不影响游戏 */
    public static void save(Path file, Map<String, String> map) {
        if (file == null || map == null) {
            return;
        }
        try {
            JsonObject root = new JsonObject();
            int n = 0;
            for (Map.Entry<String, String> e : map.entrySet()) {
                if (n >= MAX_ENTRIES) {
                    break;
                }
                if (e.getKey() != null && !e.getKey().isEmpty()
                        && e.getValue() != null && !e.getValue().isEmpty()) {
                    root.addProperty(e.getKey(), e.getValue());
                    n++;
                }
            }
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(root, w);
            }
            try {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                // 某些文件系统不支持 ATOMIC_MOVE，退化为普通 move
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 保存模型名映射失败（忽略）: {}", t.toString());
        }
    }
}
