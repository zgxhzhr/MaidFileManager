package com.example.maid_file_manager;

import com.example.maid_file_manager.config.MaidConfigManager;
import com.example.maid_file_manager.network.IMaidFileNetwork;
import com.example.maid_file_manager.network.MaidFilePayloads;
import com.example.maid_file_manager.network.NeoForgeNetwork;
import com.example.maid_file_manager.platform.NeoForgePlatformHelper;
import com.example.maid_file_manager.platform.Services;
import com.example.maid_file_manager.platform.services.IPlatformHelper;
import com.example.maid_file_manager.service.MaidServerCommands;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@Mod(Constants.MOD_ID)
public class MaidFileModNeoForge {

    public MaidFileModNeoForge(IEventBus modBus, ModContainer container) {
        Constants.LOG.info("========================================");
        Constants.LOG.info("[maid_file_manager] Mod constructor START (NeoForge)");

        Services.PLATFORM.loadService(IPlatformHelper.class, NeoForgePlatformHelper::new);
        Services.NETWORK.loadService(IMaidFileNetwork.class, NeoForgeNetwork::new);
        IMaidFileNetwork.Holder.set(Services.NETWORK.get());
        Constants.LOG.info("[maid_file_manager] Platform & Network services registered");

        modBus.addListener(this::registerPayloads);
        modBus.addListener(this::onRegisterClient);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLogin);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLogout);

        // 配置初始化（gameDir：专用服务器=服务器根目录；客户端=.minecraft；局域网=宿主 gameDir）
        MaidConfigManager.init(FMLPaths.GAMEDIR.get());

        Constants.LOG.info("[maid_file_manager] Mod constructor END");
        Constants.LOG.info("========================================");
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(Constants.MOD_ID);

        registrar.playToServer(MaidFilePayloads.RequestMaidListPayload.TYPE,
                MaidFilePayloads.RequestMaidListPayload.STREAM_CODEC,
                MaidFilePayloads.RequestMaidListPayload::handle);
        registrar.playToServer(MaidFilePayloads.ExportMaidPayload.TYPE,
                MaidFilePayloads.ExportMaidPayload.STREAM_CODEC,
                MaidFilePayloads.ExportMaidPayload::handle);
        registrar.playToServer(MaidFilePayloads.ExportBatchPayload.TYPE,
                MaidFilePayloads.ExportBatchPayload.STREAM_CODEC,
                MaidFilePayloads.ExportBatchPayload::handle);
        registrar.playToServer(MaidFilePayloads.ImportFilePayload.TYPE,
                MaidFilePayloads.ImportFilePayload.STREAM_CODEC,
                MaidFilePayloads.ImportFilePayload::handle);
        registrar.playToServer(MaidFilePayloads.ImportBatchPayload.TYPE,
                MaidFilePayloads.ImportBatchPayload.STREAM_CODEC,
                MaidFilePayloads.ImportBatchPayload::handle);
        registrar.playToServer(MaidFilePayloads.ClientConsentPayload.TYPE,
                MaidFilePayloads.ClientConsentPayload.STREAM_CODEC,
                MaidFilePayloads.ClientConsentPayload::handle);
        registrar.playToServer(MaidFilePayloads.SetServerConfigPayload.TYPE,
                MaidFilePayloads.SetServerConfigPayload.STREAM_CODEC,
                MaidFilePayloads.SetServerConfigPayload::handle);
        registrar.playToServer(MaidFilePayloads.RequestServerExportListPayload.TYPE,
                MaidFilePayloads.RequestServerExportListPayload.STREAM_CODEC,
                MaidFilePayloads.RequestServerExportListPayload::handle);
        registrar.playToServer(MaidFilePayloads.ServerExportBatchPayload.TYPE,
                MaidFilePayloads.ServerExportBatchPayload.STREAM_CODEC,
                MaidFilePayloads.ServerExportBatchPayload::handle);

        registrar.playToClient(MaidFilePayloads.MaidListPayload.TYPE,
                MaidFilePayloads.MaidListPayload.STREAM_CODEC,
                MaidFilePayloads.MaidListPayload::handle);
        registrar.playToClient(MaidFilePayloads.ExportResultPayload.TYPE,
                MaidFilePayloads.ExportResultPayload.STREAM_CODEC,
                MaidFilePayloads.ExportResultPayload::handle);
        registrar.playToClient(MaidFilePayloads.ExportBatchResultPayload.TYPE,
                MaidFilePayloads.ExportBatchResultPayload.STREAM_CODEC,
                MaidFilePayloads.ExportBatchResultPayload::handle);
        registrar.playToClient(MaidFilePayloads.FeedbackPayload.TYPE,
                MaidFilePayloads.FeedbackPayload.STREAM_CODEC,
                MaidFilePayloads.FeedbackPayload::handle);
        registrar.playToClient(MaidFilePayloads.ServerConfigSyncPayload.TYPE,
                MaidFilePayloads.ServerConfigSyncPayload.STREAM_CODEC,
                MaidFilePayloads.ServerConfigSyncPayload::handle);
        registrar.playToClient(MaidFilePayloads.ServerExportListPayload.TYPE,
                MaidFilePayloads.ServerExportListPayload.STREAM_CODEC,
                MaidFilePayloads.ServerExportListPayload::handle);

        Constants.LOG.info("[maid_file_manager] CustomPayload handlers registered: 9 C2S + 6 S2C");
    }

    private void onRegisterClient(RegisterKeyMappingsEvent event) {
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        ClientRegistration.registerKeyMappings(event);
        Constants.LOG.info("[maid_file_manager] KeyMapping registered");
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        // /maidfile gui：打开客户端管理界面（仅客户端）
        event.getDispatcher().register(
                Commands.literal("maidfile")
                        .then(Commands.literal("gui")
                                .executes(ctx -> {
                                    if (FMLEnvironment.dist != Dist.CLIENT) {
                                        ctx.getSource().sendFailure(Component.literal("仅客户端可用"));
                                        return 0;
                                    }
                                    ClientRegistration.openGui();
                                    return 1;
                                }))
        );
        // /maidfile exportall：服务端统一导出（仅 OP，需客户端在设置里同意）
        MaidServerCommands.register(event.getDispatcher());
        Constants.LOG.info("[maid_file_manager] /maidfile gui + exportall commands registered");
    }

    /** 玩家登录：把服务端配置推给客户端（客户端收到后回发同意状态） */
    private void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp) {
            MaidFilePayloads.broadcastServerConfig(sp.server);
        }
    }

    /** 玩家退出：清理服务端侧记录的同意状态 */
    private void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        MaidConfigManager.removeClientConsent(event.getEntity().getUUID());
    }
}
