package io.github.zgxhzhr.maidfm.client;

import io.github.zgxhzhr.maidfm.Constants;
import io.github.zgxhzhr.maidfm.config.MaidConfigManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.sounds.SoundEvent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 界面背景音乐（<b>试验性功能，可整体移除</b>）。
 *
 * <p>行为：进入本模组的任意界面时开始循环播放 {@code maid_file_manager:ui_music}，离开界面后
 * 延迟一小段时间停止 —— 延迟是为了在「界面之间切换」（如 导出面板 → 设置界面）时**不中断重播**。
 *
 * <p>移除方式（回滚）：删除本文件、`assets/maid_file_manager/sounds.json` 与
 * `assets/maid_file_manager/sounds/ui_music.ogg`，并移除各界面里对
 * {@link #enter(Screen)} / {@link #leave(Screen)} 的调用即可；不涉及任何存档或网络协议。
 *
 * <p>实现约束：不依赖任何加载器事件（Forge/NeoForge/Fabric 通用），只用 {@link Screen#init()} 与
 * {@link Screen#removed()} 两个原版生命周期方法。
 */
public final class UiMusicManager {

    /** 声音注册 ID（与 sounds.json 中的键一致）。 */
    private static final String SOUND_ID = "maid_file_manager:ui_music";

    /** 离开界面后延迟多久停止（毫秒）。用于跨越界面切换，避免音乐从头重播。 */
    private static final long STOP_DELAY_MS = 600L;

    /** 默认音量（0.0 - 1.0）；实际值读客户端配置 {@code ui_music_volume}。 */
    private static final float DEFAULT_VOLUME = 0.15F;

    private static final Set<Screen> ACTIVE = ConcurrentHashMap.newKeySet();

    /** 跨线程访问（主线程播放、定时线程停止），必须 volatile。 */
    private static volatile SoundInstance current;

    private static ScheduledExecutorService timer;
    private static ScheduledFuture<?> pendingStop;
    private static boolean failureLogged;

    private UiMusicManager() {
    }

    /** 进入界面时调用（放在 {@code init()} 首行）。 */
    public static void enter(Screen screen) {
        if (screen == null) {
            return;
        }
        ACTIVE.add(screen);
        cancelPendingStop();
        if (!MaidConfigManager.isClientUiMusic() || current != null) {
            return;
        }
        try {
            ResourceLocation id = ResourceLocation.tryParse(SOUND_ID);
            if (id == null) {
                return;
            }
            float volume = MaidConfigManager.getClientUiMusicVolume();
            if (volume <= 0F) {
                return;
            }
            // 用 MUSIC 类别：跟随玩家「音乐」音量滑块（而不是主音量），与其它音乐同处一个音量组
            SimpleSoundInstance instance = new SimpleSoundInstance(
                    id, SoundSource.MUSIC, volume, 1.0F, RandomSource.create(),
                    false, 0, SoundInstance.Attenuation.NONE, 0.0D, 0.0D, 0.0D, true);
            // 让原版背景音乐让位，避免两首曲子同时响（原版会自行在后续 tick 重新判断是否播放）
            try {
                Minecraft.getInstance().getMusicManager().stopPlaying();
            } catch (Throwable ignored) {
                // 该 API 在个别版本缺失时忽略：仅退化为「不暂停原版音乐」
            }
            Minecraft.getInstance().getSoundManager().play(instance);
            current = instance;
        } catch (Throwable t) {
            // 声音资源缺失或平台差异都不应影响界面使用：记录一次后静默停用
            if (!failureLogged) {
                failureLogged = true;
                Constants.LOG.warn("[maid_file_manager] 界面背景音乐无法播放，已静默停用: {}", t.toString());
            }
        }
    }

    /** 离开界面时调用（放在 {@code removed()} 中）。 */
    public static void leave(Screen screen) {
        if (screen != null) {
            ACTIVE.remove(screen);
        }
        if (!ACTIVE.isEmpty()) {
            return;
        }
        scheduleStop();
    }

    /** 立即停止（配置项关闭、或需要强制静音时调用）。 */
    public static void stopNow() {
        cancelPendingStop();
        stop();
    }

    private static void scheduleStop() {
        cancelPendingStop();
        try {
            if (timer == null) {
                timer = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "maidfm-ui-music");
                    t.setDaemon(true);
                    return t;
                });
            }
            pendingStop = timer.schedule(UiMusicManager::stop, STOP_DELAY_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            stop();
        }
    }

    private static void cancelPendingStop() {
        ScheduledFuture<?> pending = pendingStop;
        if (pending != null) {
            pending.cancel(false);
            pendingStop = null;
        }
    }

    private static void stop() {
        SoundInstance instance = current;
        current = null;
        if (instance == null) {
            return;
        }
        try {
            Minecraft.getInstance().getSoundManager().stop(instance);
        } catch (Throwable ignored) {
            // 声音已自然结束或声音系统已关闭：忽略
        }
    }
}
