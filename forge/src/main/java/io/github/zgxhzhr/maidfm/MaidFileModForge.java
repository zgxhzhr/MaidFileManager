package io.github.zgxhzhr.maidfm;

import io.github.zgxhzhr.maidfm.network.C2SPacket;
import io.github.zgxhzhr.maidfm.network.S2CPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

@Mod(Constants.MOD_ID)
public class MaidFileModForge {
    /** 协议版本引用公共常量（递增规则见 MaidFilePackets.PROTOCOL_VERSION） */
    private static final String PROTOCOL_VERSION = io.github.zgxhzhr.maidfm.network.MaidFilePackets.PROTOCOL_VERSION;

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Constants.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    public MaidFileModForge() {
        // 【诊断】打印协议版本：客户端与服务端日志里这个值必须一致，
        // 不一致说明两端 jar 不同（SimpleChannel 会按它做握手校验）。
        Constants.LOG.info("Maid File Manager (Forge) loading... protocol={}", PROTOCOL_VERSION);

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

        // 配置系统初始化（服务端/客户端 properties 文件）
        io.github.zgxhzhr.maidfm.config.MaidConfigManager.init(
                net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get());
        // 指令注册：/maidfile exportall（仅 OP）
        MinecraftForge.EVENT_BUS.addListener(this::onRegisterCommands);
        // 玩家登录 → 仅向该玩家单播服务端配置；退出 → 清理同意状态
        MinecraftForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);
        MinecraftForge.EVENT_BUS.addListener(this::onPlayerLoggedOut);
        // 模型名注册表：启动时从存档恢复，停止时落盘并清空
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopping);
    }

    /** 服务端启动：恢复上次持久化的模型名注册表（重启后不用等客户端重新上报） */
    private void onServerStarted(net.minecraftforge.event.server.ServerStartedEvent event) {
        io.github.zgxhzhr.maidfm.service.ModelNameRegistry.loadFromServer(event.getServer());
    }

    /** 服务端停止：落盘 + 清空，避免把 A 服的名字带到 B 服 */
    private void onServerStopping(net.minecraftforge.event.server.ServerStoppingEvent event) {
        io.github.zgxhzhr.maidfm.service.ModelNameRegistry.saveAndClear(event.getServer());
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        Constants.LOG.info("Maid File Manager network ready");
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
            // 清掉该玩家未完成的分片会话：否则中途断线的分片累积器会一直留在内存里
            io.github.zgxhzhr.maidfm.service.ChunkedUploadDispatcher.onPlayerDisconnect(sp.getUUID());
            // 同时清掉待删登记：回执不可能再来了，留着会阻塞后续「导出并移除」
            io.github.zgxhzhr.maidfm.service.PendingRemovalRegistry.clear(sp.getUUID());
        }
    }
}
