package com.example.examplemod;

import com.example.examplemod.client.MaidFileManagerScreen;
import com.example.examplemod.client.MaidFileKeyMappings;
import com.example.examplemod.network.C2SPacket;
import com.example.examplemod.network.ForgeNetwork;
import com.example.examplemod.network.IMaidFileNetwork;
import com.example.examplemod.network.S2CPacket;
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
        Constants.LOG.info("========================================");
        Constants.LOG.info("[maid_file_manager] Mod constructor START (Forge)");
        Constants.LOG.info("[maid_file_manager] MOD_ID={}, gameDir={}", Constants.MOD_ID,
                net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get());

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
        Constants.LOG.info("[maid_file_manager] SimpleChannel registered: {} C2S+S2C packets", id);

        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::onCommonSetup);
        modBus.addListener(this::onRegisterKeyMappings);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            modBus.addListener(this::onClientSetup);
            MinecraftForge.EVENT_BUS.addListener(this::onClientTick);
            Constants.LOG.info("[maid_file_manager] Client listeners added (RegisterKeyMappings + ClientTick)");
        } else {
            Constants.LOG.info("[maid_file_manager] Dedicated server: skip client listeners");
        }
        Constants.LOG.info("[maid_file_manager] Mod constructor END");
        Constants.LOG.info("========================================");
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        Constants.LOG.info("[maid_file_manager] onCommonSetup fired — network ready");
    }

    private void onRegisterKeyMappings(net.minecraftforge.client.event.RegisterKeyMappingsEvent event) {
        event.register(MaidFileKeyMappings.OPEN_MANAGER);
        Constants.LOG.info("[maid_file_manager] KeyMapping registered: key.maid_file_manager.open_manager (default KEY_U)");
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        IMaidFileNetwork.Holder.set(new ForgeNetwork());
        Constants.LOG.info("[maid_file_manager] onClientSetup fired — ForgeNetwork injected");
    }

    private void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // 注意：Forge 运行时（reobf 后）Minecraft.getInstance() 可能因映射问题抛 NoSuchMethodError，
        // 所以这里用反射获取 Minecraft 实例，确保对 Mojang/SRG 两套命名都稳定。
        Minecraft mc = getMinecraftInstance();
        if (mc == null) {
            return;
        }
        if (mc.player == null || mc.screen != null) {
            return;
        }
        while (MaidFileKeyMappings.OPEN_MANAGER.consumeClick()) {
            Constants.LOG.info("[maid_file_manager] Hotkey consumed — opening MaidFileManagerScreen");
            mc.setScreen(new MaidFileManagerScreen());
        }
    }

    /**
     * 通过反射获取 Minecraft 客户端实例。
     * 由于 Forge reobf 后，getInstance() 方法名/INSTANCE 字段名都可能被重新映射为 SRG 名
     * （例如 m_91087_() 或 f_91073_），因此不依赖具体名字，而是遍历所有静态方法（0 参、返回 Minecraft）
     * 与静态字段（类型 Minecraft），命中第一个即调用/取值。
     */
    private static Minecraft getMinecraftInstance() {
        // 首先尝试遍历方法：找 public static Minecraft xxx() （0 参）
        for (java.lang.reflect.Method m : Minecraft.class.getDeclaredMethods()) {
            int mods = m.getModifiers();
            if (java.lang.reflect.Modifier.isStatic(mods)
                    && java.lang.reflect.Modifier.isPublic(mods)
                    && m.getParameterCount() == 0
                    && m.getReturnType() == Minecraft.class) {
                try {
                    m.setAccessible(true);
                    Minecraft inst = (Minecraft) m.invoke(null);
                    if (inst != null) {
                        return inst;
                    }
                } catch (ReflectiveOperationException ignored) {
                }
            }
        }
        // 方法没命中：遍历字段，找 public static Minecraft xxx
        for (java.lang.reflect.Field f : Minecraft.class.getDeclaredFields()) {
            int mods = f.getModifiers();
            if (java.lang.reflect.Modifier.isStatic(mods)
                    && f.getType() == Minecraft.class) {
                try {
                    f.setAccessible(true);
                    Minecraft inst = (Minecraft) f.get(null);
                    if (inst != null) {
                        return inst;
                    }
                } catch (ReflectiveOperationException ignored) {
                }
            }
        }
        Constants.LOG.error("[maid_file_manager] getMinecraftInstance: not found any static method/field returning Minecraft");
        return null;
    }
}
