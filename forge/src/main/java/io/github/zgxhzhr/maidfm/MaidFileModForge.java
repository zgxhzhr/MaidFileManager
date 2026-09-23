package io.github.zgxhzhr.maidfm;

import io.github.zgxhzhr.maidfm.client.MaidFileManagerScreen;
import io.github.zgxhzhr.maidfm.client.MaidFileKeyMappings;
import io.github.zgxhzhr.maidfm.network.C2SPacket;
import io.github.zgxhzhr.maidfm.network.ForgeNetwork;
import io.github.zgxhzhr.maidfm.network.S2CPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

@Mod(Constants.MOD_ID)
public class MaidFileModForge {
    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Constants.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    public MaidFileModForge() {
        Constants.LOG.info("Maid File Manager (Forge) loading...");

        // 注册网络包（必须显式声明 NetworkDirection，否则集成服务器下 handler 可能在错误的一侧被调用）
        int id = 0;
        CHANNEL.messageBuilder(C2SPacket.class, id++, net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SPacket::encode)
                .decoder(C2SPacket::decode)
                .consumerMainThread(C2SPacket::handle)
                .add();
        CHANNEL.messageBuilder(S2CPacket.class, id++, net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CPacket::encode)
                .decoder(S2CPacket::decode)
                .consumerMainThread(S2CPacket::handle)
                .add();

        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::onCommonSetup);
        modBus.addListener(this::onRegisterKeyMappings);

        // 配置系统初始化（服务端/客户端 properties 文件）
        io.github.zgxhzhr.maidfm.config.MaidConfigManager.init(
                net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get());
        // 指令注册：/maidfile exportall（仅 OP）
        MinecraftForge.EVENT_BUS.addListener(this::onRegisterCommands);
        // 玩家登录 → 仅向该玩家单播服务端配置；退出 → 清理同意状态
        MinecraftForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);
        MinecraftForge.EVENT_BUS.addListener(this::onPlayerLoggedOut);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            modBus.addListener(this::onClientSetup);
            MinecraftForge.EVENT_BUS.addListener(this::onClientTick);
        }
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        Constants.LOG.info("Maid File Manager network ready");
    }

    private void onRegisterKeyMappings(net.minecraftforge.client.event.RegisterKeyMappingsEvent event) {
        event.register(MaidFileKeyMappings.OPEN_MANAGER);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        io.github.zgxhzhr.maidfm.network.IMaidFileNetwork.Holder.set(new ForgeNetwork());
    }

    private void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) {
            return;
        }
        while (MaidFileKeyMappings.OPEN_MANAGER.consumeClick()) {
            mc.setScreen(new MaidFileManagerScreen());
        }
    }

    /** 注册 /maidfile 服务端指令（仅 OP） */
    private void onRegisterCommands(net.minecraftforge.event.RegisterCommandsEvent event) {
        io.github.zgxhzhr.maidfm.service.MaidServerCommands.register(event.getDispatcher());
    }

    /** 玩家登录：仅向该玩家单播服务端配置（客户端据此显示设置界面并回发同意状态） */
    private void onPlayerLoggedIn(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp) {
            C2SPacket.sendServerConfig(sp);
        }
    }

    /** 玩家退出：清理服务端记录的同意状态 */
    private void onPlayerLoggedOut(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp) {
            io.github.zgxhzhr.maidfm.config.MaidConfigManager.removeClientConsent(sp.getUUID());
        }
    }
}
