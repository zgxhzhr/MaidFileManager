package com.example.examplemod.platform;

import com.example.examplemod.Constants;
import com.example.examplemod.network.IMaidFileNetwork;
import com.example.examplemod.platform.services.IPlatformHelper;

import java.util.ServiceLoader;
import java.util.function.Supplier;

public class Services {

    public static final ServiceProvider<IPlatformHelper> PLATFORM = new ServiceProvider<>();
    public static final ServiceProvider<IMaidFileNetwork> NETWORK = new ServiceProvider<>();

    static {
        try {
            PLATFORM.loadService(IPlatformHelper.class, () -> load(IPlatformHelper.class));
        } catch (Exception ignored) {
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