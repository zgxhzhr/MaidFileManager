package io.github.zgxhzhr.maidfm;

import io.github.zgxhzhr.maidfm.client.MaidFileKeyMappings;
import io.github.zgxhzhr.maidfm.client.MaidFileManagerScreen;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

@EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public class ClientRegistration {

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onKey(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return;
        if (MaidFileKeyMappings.OPEN_MANAGER.consumeClick()) {
            openGui();
        }
    }

    public static void openGui() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.setScreen(new MaidFileManagerScreen());
    }

    /**
     * MOD 事件总线订阅：注册按键映射。
     *
     * <p>单独嵌套成类的原因：一个类只能归属一条事件总线（本类已订阅 GAME 总线），
     * 更重要的是 mod 主类的方法签名里不能出现客户端专属事件类型
     * （专用服务端会剥离 net.neoforged.neoforge.client.*），否则主类在服务端加载即失败。
     * 因此 RegisterKeyMappingsEvent 的订阅必须留在客户端专属类里，由本类自动注册。
     */
    @EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class ModBus {
        private ModBus() {
        }

        @SubscribeEvent
        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            event.register(MaidFileKeyMappings.OPEN_MANAGER);
            Constants.LOG.info("[maid_file_manager] KeyMapping registered");
        }
    }
}
