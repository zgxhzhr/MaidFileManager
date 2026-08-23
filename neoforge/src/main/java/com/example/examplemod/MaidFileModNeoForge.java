package com.example.examplemod;

import com.example.examplemod.network.IMaidFileNetwork;
import com.example.examplemod.network.MaidFilePayloads;
import com.example.examplemod.network.NeoForgeNetwork;
import com.example.examplemod.platform.NeoForgePlatformHelper;
import com.example.examplemod.platform.Services;
import com.example.examplemod.platform.services.IPlatformHelper;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
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

        Constants.LOG.info("[maid_file_manager] CustomPayload handlers registered: 5 C2S + 4 S2C");
    }

    private void onRegisterClient(RegisterKeyMappingsEvent event) {
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        ClientRegistration.registerKeyMappings(event);
        Constants.LOG.info("[maid_file_manager] KeyMapping registered");
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
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
        Constants.LOG.info("[maid_file_manager] /maidfile gui command registered");
    }
}
