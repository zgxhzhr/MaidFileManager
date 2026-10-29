package io.github.zgxhzhr.maidfm.client;

import io.github.zgxhzhr.maidfm.Constants;
import io.github.zgxhzhr.maidfm.network.IMaidFileNetwork;
import io.github.zgxhzhr.maidfm.service.ModelNameStore;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端侧的「模型 ID → 显示名」本地缓存。
 *
 * <p><b>为什么需要它</b>：{@code ModelNameRegistry} 是服务端进程内的表，纯客户端从不填充它。
 * 但客户端<u>退到游戏主界面</u>后，备份界面会走本地扫描（{@code scanAllWorlds}），
 * 此时注册表为空、本机也可能没装别人的私有模型包 —— 于是别人的女仆就退化成一串 path。
 *
 * <p>解决办法：每次从服务端收到备份列表时，把里面的 {@code modelId → maidName}
 * 「学」下来存成 JSON。之后本地扫描时通过 {@code ModelNameRegistry.externalProvider} 查这份缓存，
 * 名字就还在。
 *
 * <p>缓存按游戏目录（{@code <gameDir>/config/maid_file_manager/client_model_names.json}）存放，
 * 跨服务器共用：同名 modelId 在不同服务器的显示名通常一致，若冲突以先写入者为准。
 */
public final class ClientModelNameCache {

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    /** 单条键/值长度上限，和网络层保持一致 */
    private static final int MAX_TEXT = 128;
    /** 缓存条目上限 */
    private static final int MAX_ENTRIES = 16384;

    private static Path file;
    private static volatile boolean dirty;
    private static volatile boolean loaded;

    private ClientModelNameCache() {
    }

    /** 读本地缓存；每个游戏目录只读一次 */
    public static void load(Path gameDir) {
        if (gameDir == null) {
            return;
        }
        try {
            file = ModelNameStore.clientPath(gameDir);
            if (!loaded) {
                CACHE.putAll(ModelNameStore.load(file));
                loaded = true;
                if (!CACHE.isEmpty()) {
                    Constants.LOG.info("[maid_file_manager] 客户端模型名缓存：载入 {} 条", CACHE.size());
                }
            }
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 读取客户端模型名缓存失败（忽略）: {}", t.toString());
        }
    }

    /**
     * 从服务端下发的备份列表里学习名字。
     *
     * <p>只收「能解析出中文/shift 名」的条目：服务端本身就是 path 美化名的结果没学习价值，
     * 存进去只会把缓存污染成 path。
     */
    public static void learn(List<IMaidFileNetwork.BackupOwner> owners) {
        if (owners == null || owners.isEmpty()) {
            return;
        }
        int added = 0;
        for (IMaidFileNetwork.BackupOwner owner : owners) {
            if (owner == null || owner.maids() == null) {
                continue;
            }
            for (IMaidFileNetwork.BackupMaid maid : owner.maids()) {
                String modelId = maid.modelId();
                String name = maid.maidName();
                if (modelId == null || modelId.isEmpty() || name == null || name.isEmpty()) {
                    continue;
                }
                if (modelId.length() > MAX_TEXT || name.length() > MAX_TEXT) {
                    continue;
                }
                if (CACHE.size() >= MAX_ENTRIES) {
                    break;
                }
                if (CACHE.putIfAbsent(modelId, name) == null) {
                    added++;
                }
            }
        }
        if (added > 0) {
            dirty = true;
            Constants.LOG.info("[maid_file_manager] 客户端模型名缓存：新学到 {} 条（累计 {} 条）",
                    added, CACHE.size());
        }
    }

    /** 供 {@code ModelNameRegistry.externalProvider} 使用 */
    public static String lookup(String modelId) {
        if (modelId == null || modelId.isEmpty()) {
            return null;
        }
        return CACHE.get(modelId);
    }

    /** 把有新内容的缓存落盘（结构变化时调用，不必每帧写） */
    public static void flush() {
        if (!dirty || file == null) {
            return;
        }
        try {
            Map<String, String> snapshot = new LinkedHashMap<>(CACHE);
            ModelNameStore.save(file, snapshot);
            dirty = false;
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 写客户端模型名缓存失败（忽略）: {}", t.toString());
        }
    }

    /** 诊断用 */
    public static int size() {
        return CACHE.size();
    }
}
