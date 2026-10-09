package io.github.zgxhzhr.maidfm;

import io.github.zgxhzhr.maidfm.client.MaidFileKeyMappings;
import io.github.zgxhzhr.maidfm.client.MaidFileManagerScreen;
import io.github.zgxhzhr.maidfm.network.ForgeNetwork;
import io.github.zgxhzhr.maidfm.network.IMaidFileNetwork;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Forge 客户端专属初始化。仅在物理客户端加载。
 *
 * <p>必须与 mod 主类分离的原因：Forge 的专用服务端在加载 mod 主类时会用
 * RuntimeDistCleaner 拒绝任何引用了客户端专属类型（net.minecraft.client.*、
 * net.minecraftforge.client.*）的字节码——方法签名与方法体里的引用同样会被扫描。
 * 主类一旦出现这些类型，专用服务端启动即崩溃。因此键盘绑定、客户端网络注册、
 * 按键打开界面这几件客户端专属的事全部收进本类，主类不再引用任何客户端类型。
 *
 * <p>本类通过 {@code @Mod.EventBusSubscriber(value = Dist.CLIENT)} 自动注册，
 * 专用服务端既不会注册、也不会加载它。
 */
@Mod.EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class MaidFileModForgeClient {

    private MaidFileModForgeClient() {
    }

    /** 注册 U 键（可在控制设置中修改） */
    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(MaidFileKeyMappings.OPEN_MANAGER);
    }

    /** 客户端初始化：挂载客户端网络实现，并注册客户端 tick 监听 */
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        IMaidFileNetwork.Holder.set(new ForgeNetwork());
        MinecraftForge.EVENT_BUS.addListener(MaidFileModForgeClient::onClientTick);
    }

    /** 客户端 tick：按下按键且当前无界面时打开女仆档案管理界面 */
    private static void onClientTick(TickEvent.ClientTickEvent event) {
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
}
