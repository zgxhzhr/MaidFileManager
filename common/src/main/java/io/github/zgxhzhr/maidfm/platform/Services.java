package io.github.zgxhzhr.maidfm.platform;

import io.github.zgxhzhr.maidfm.Constants;
import io.github.zgxhzhr.maidfm.platform.services.IPlatformHelper;
import io.github.zgxhzhr.maidfm.platform.services.ServerMessaging;

import java.util.ServiceLoader;
import java.util.function.Supplier;

public class Services {

    public static final ServiceProvider<IPlatformHelper> PLATFORM = new ServiceProvider<>();

    /** 服务端 -> 客户端消息发送（阶段 4 平台层收敛，见 {@link PacketSender}） */
    public static final ServiceProvider<ServerMessaging> MESSAGING = new ServiceProvider<>();

    static {
        // 两个服务各自独立加载：一个失败不能让另一个也丢掉。
        // 正常打包下 META-INF/services 声明必在；失败只可能是打包损坏/类加载被阻断，
        // 不能静默吞掉，否则后续 get() 只会以裸 NPE 暴露、无从排查。
        try {
            PLATFORM.loadService(IPlatformHelper.class, () -> load(IPlatformHelper.class));
        } catch (Exception e) {
            Constants.LOG.error("[maid_file_manager] 平台服务 IPlatformHelper 加载失败，模组功能将不可用", e);
        }
        try {
            MESSAGING.loadService(ServerMessaging.class, () -> load(ServerMessaging.class));
        } catch (Exception e) {
            // 不致命：PacketSender 有 NOOP 兜底，反馈消息会丢但功能仍可运行
            Constants.LOG.error("[maid_file_manager] 平台服务 ServerMessaging 加载失败，"
                    + "服务端反馈消息将无法送达客户端", e);
        }
    }

    public static <T> T load(Class<T> clazz) {
        final T loadedService = ServiceLoader.load(clazz)
                .findFirst()
                .orElseThrow(() -> new NullPointerException("Failed to load service for " + clazz.getName()));
        Constants.LOG.debug("Loaded {} for service {}", loadedService, clazz);
        return loadedService;
    }

    public static final class ServiceProvider<T> {
        private T instance;
        private boolean loaded;

        public synchronized void loadService(Class<T> clazz, Supplier<T> supplier) {
            if (!loaded) {
                this.instance = supplier.get();
                this.loaded = true;
                Constants.LOG.debug("Registered service provider for {}", clazz.getName());
            }
        }

        public T get() {
            return instance;
        }
    }
}