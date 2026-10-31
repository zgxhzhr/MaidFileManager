package io.github.zgxhzhr.maidfm;

import io.github.zgxhzhr.maidfm.config.MaidConfigManager;
import io.github.zgxhzhr.maidfm.network.FabricNetwork;
import io.github.zgxhzhr.maidfm.network.FabricPayloadRegistration;
import io.github.zgxhzhr.maidfm.network.IMaidFileNetwork;
import io.github.zgxhzhr.maidfm.network.MaidFilePayloads;
import io.github.zgxhzhr.maidfm.platform.FabricPlatformHelper;
import io.github.zgxhzhr.maidfm.platform.Services;
import io.github.zgxhzhr.maidfm.platform.services.IPlatformHelper;
import io.github.zgxhzhr.maidfm.service.MaidServerCommands;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Fabric 入口。
 *
 * <p>对应 NeoForge 版 MaidFileModNeoForge 的三件事：注册服务、挂网络与命令事件、初始化配置。
 * NeoForge 用注解 + 事件总线，Fabric 用 ModInitializer + 各类 Callback。
 */
public class MaidFileModFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        // 模型名注册表：启动恢复 / 停止落盘
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(
                server -> io.github.zgxhzhr.maidfm.service.ModelNameRegistry.loadFromServer(server));
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(
                server -> io.github.zgxhzhr.maidfm.service.ModelNameRegistry.saveAndClear(server));
        Constants.LOG.info("========================================");
        Constants.LOG.info("[maid_file_manager] Mod initializer START (Fabric)");

        Services.PLATFORM.loadService(IPlatformHelper.class, FabricPlatformHelper::new);
        // 网络实现与平台服务分开注册：NeoForge 版在构造函数里一并注册，Fabric 这里同样两处都要。
        IMaidFileNetwork.Holder.set(new FabricNetwork());
        Constants.LOG.info("[maid_file_manager] Platform & Network services registered");

        // 实体 attachment 必须在初始化窗口内登记（档案与导入标记都存在这里）
        io.github.zgxhzhr.maidfm.platform.FabricMaidData.bootstrap();

        // 负载类型与服务端接收器（客户端接收器在 ClientModInitializer 里挂）
        FabricPayloadRegistration.registerTypes();
        FabricPayloadRegistration.registerServerReceivers();

        // 命令：hasPermission(2) 的门槛在 MaidServerCommands 内部，此处只负责挂到调度器
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) ->
                MaidServerCommands.register(dispatcher));

        // 玩家登录：向该玩家单播服务端配置（客户端收到后回发同意状态）
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            try {
                MaidFilePayloads.sendServerConfig(handler.player);
            } catch (Throwable t) {
                Constants.LOG.warn("[maid_file_manager] 登录同步配置失败: {}", t.toString());
            }
        });

        // 玩家退出：清理服务端侧记录的同意状态、未完成的分片会话与待删登记
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            java.util.UUID uuid = handler.player.getUUID();
            MaidConfigManager.removeClientConsent(uuid);
            io.github.zgxhzhr.maidfm.service.ChunkedUploadDispatcher.onPlayerDisconnect(uuid);
            io.github.zgxhzhr.maidfm.service.PendingRemovalRegistry.clear(uuid);
        });

        // 配置初始化（gameDir：专用服务器=服务器根目录；客户端=.minecraft）
        MaidConfigManager.init(FabricLoader.getInstance().getGameDir());

        Constants.LOG.info("[maid_file_manager] Mod initializer END");
        Constants.LOG.info("========================================");
    }
}