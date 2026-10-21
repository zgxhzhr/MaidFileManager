package io.github.zgxhzhr.maidfm.spi;

import java.util.ArrayList;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 女仆迁移 provider 注册表。
 *
 * <p>附属模组在自己的初始化代码中调用
 * {@link #register(MaidMigrationProvider)} 即可接入，一行代码完成注册。
 */
public final class MaidMigrationRegistry {

    private static final List<MaidMigrationProvider> PROVIDERS = new CopyOnWriteArrayList<>();

    private MaidMigrationRegistry() {
    }

    /**
     * 注册一个迁移 provider。重复 id 自动去重。
     *
     * @param provider 要注册的 provider
     */
    public static void register(MaidMigrationProvider provider) {
        if (provider == null || provider.getId() == null) {
            return;
        }
        final ResourceLocation newId;
        try {
            newId = provider.getId();
        } catch (Throwable t) {
            // 第三方 provider 的 getId() 抛异常时不能让注册整体崩掉（硬约束：附属模组异常必须隔离）
            io.github.zgxhzhr.maidfm.Constants.LOG.warn(
                    "[maid_file_manager] 迁移 provider 的 getId() 抛异常，已跳过注册: {}", t.toString());
            return;
        }
        for (int i = 0; i < PROVIDERS.size(); i++) {
            MaidMigrationProvider p = PROVIDERS.get(i);
            try {
                if (newId.equals(p.getId())) {
                    return;
                }
            } catch (Throwable t) {
                io.github.zgxhzhr.maidfm.Constants.LOG.warn(
                        "[maid_file_manager] 已注册 provider 的 getId() 抛异常，跳过第 {} 个: {}", i, t.toString());
            }
        }
        PROVIDERS.add(provider);
    }

    /** 返回所有已注册且当前可用的 provider（导出时遍历）。 */
    public static List<MaidMigrationProvider> getAvailable() {
        // 逐项 try：第三方 provider 的 isAvailable() 若抛异常，不能让整个遍历崩掉（此前在 stream 里裸调用）
        List<MaidMigrationProvider> out = new ArrayList<>();
        for (int i = 0; i < PROVIDERS.size(); i++) {
            MaidMigrationProvider provider = PROVIDERS.get(i);
            try {
                if (provider.isAvailable()) {
                    out.add(provider);
                }
            } catch (Throwable t) {
                // 不在 catch 里调 provider.getId()：若正是 getId() 抛的异常，这里会二次抛出，
                // 把整个遍历（乃至导出主流程）打断。改用序号定位。
                io.github.zgxhzhr.maidfm.Constants.LOG.warn(
                        "[maid_file_manager] 迁移 provider 可用性检查抛异常，已跳过第 {} 个: {}",
                        i, t.toString());
            }
        }
        return out;
    }

    /** 按 id 查找 provider（导入时按 id 匹配），找不到返回 null。 */
    public static MaidMigrationProvider get(ResourceLocation id) {
        if (id == null) {
            return null;
        }
        for (int i = 0; i < PROVIDERS.size(); i++) {
            MaidMigrationProvider p = PROVIDERS.get(i);
            try {
                if (id.equals(p.getId())) {
                    return p;
                }
            } catch (Throwable t) {
                // 按 id 匹配时某个 provider 的 getId() 抛异常，不能让整条导入打挂
                io.github.zgxhzhr.maidfm.Constants.LOG.warn(
                        "[maid_file_manager] 迁移 provider 的 getId() 抛异常，跳过第 {} 个: {}", i, t.toString());
            }
        }
        return null;
    }
}
