package io.github.zgxhzhr.maidfm.network;

import net.minecraft.world.entity.player.Player;

/**
 * 负载处理上下文（Fabric 版）。
 *
 * <p>NeoForge 的 IPayloadContext 只在两个方向上共用一套接口：服务端入站时 player() 是
 * ServerPlayer，客户端入站时是 LocalPlayer。因此这里把 player() 保留为通用的 Player，
 * 让 payload 类里的 handle 方法可以同时服务 C2S 与 S2C 两个方向，不用按方向写两份。
 */
public interface PayloadCtx {

    /** 触发该负载的玩家（服务端为 ServerPlayer，客户端为 LocalPlayer） */
    Player player();

    /** 在游戏主线程执行；Fabric 的回调已在主线程，直接执行即可 */
    default void enqueueWork(Runnable task) {
        task.run();
    }
}