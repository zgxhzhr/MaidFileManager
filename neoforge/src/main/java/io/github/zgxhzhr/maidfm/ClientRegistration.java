package io.github.zgxhzhr.maidfm;

import io.github.zgxhzhr.maidfm.client.MaidBackupBrowserScreen;
import io.github.zgxhzhr.maidfm.client.MaidFileKeyMappings;
import io.github.zgxhzhr.maidfm.client.PlayerNameResolver;
import io.github.zgxhzhr.maidfm.client.MaidFileManagerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

@EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public class ClientRegistration {

    /** 本连接是否已上报模型名映射 */
    private static boolean MODEL_NAMES_SENT;

    /** 进入世界即开始解析属主名字（不等玩家打开备份界面） */
    @SubscribeEvent
    public static void onClientLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        java.nio.file.Path gameDir = net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath();
        PlayerNameResolver.loadCache(gameDir);
        PlayerNameResolver.warmUpFromSaves(gameDir);
        io.github.zgxhzhr.maidfm.client.ClientModelNameCache.load(gameDir);
        io.github.zgxhzhr.maidfm.service.ModelNameRegistry.externalProvider =
                io.github.zgxhzhr.maidfm.client.ClientModelNameCache::lookup;
        MODEL_NAMES_SENT = false;
        Minecraft mc2 = Minecraft.getInstance();
        if (mc2 != null) {
            mc2.execute(ClientRegistration::sendModelNamesOnce);
        }
    }

    /** 把本机认识的「模型 ID → 显示名」上报给服务端（每个连接只发一次） */
    private static void sendModelNamesOnce() {
        if (MODEL_NAMES_SENT) {
            return;
        }
        MODEL_NAMES_SENT = true;
        try {
            java.util.Map<String, String> names =
                    io.github.zgxhzhr.maidfm.client.ModelNames.exportModelNameMap();
            if (names == null || names.isEmpty()) {
                return;
            }
            java.util.List<String> pairs = new java.util.ArrayList<>(names.size() * 2);
            for (java.util.Map.Entry<String, String> e : names.entrySet()) {
                pairs.add(e.getKey());
                pairs.add(e.getValue());
            }
            io.github.zgxhzhr.maidfm.network.IMaidFileNetwork net =
                    io.github.zgxhzhr.maidfm.network.IMaidFileNetwork.Holder.get();
            if (net != null) {
                net.sendModelNames(pairs);
            }
        } catch (Throwable t) {
            io.github.zgxhzhr.maidfm.Constants.LOG.warn(
                    "[maid_file_manager] 上报模型名失败: {}", t.toString());
        }
    }

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onKey(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return;
        if (MaidFileKeyMappings.OPEN_MANAGER.consumeClick()) {
            openGui();
        }
    }

    /**
     * 在游戏主菜单（标题界面）左下角注入「女仆档案管理」入口小按钮。
     *
     * <p>该入口只放在原版主菜单，与游戏内管理界面分离：用于浏览各存档中车万女仆的
     * 自动备份并导出为符合本模组规范的 .maid 文件，因此不依赖进入世界。
     */
    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Button button = Button.builder(
                        Component.translatable("maid_file_manager.gui.button.profile_browser"),
                        b -> mc.setScreen(new MaidBackupBrowserScreen(mc.screen)))
                .bounds(6, 6, 56, 20)
                .tooltip(Tooltip.create(
                        Component.translatable("maid_file_manager.gui.button.profile_browser.tooltip")))
                .build();
        event.addListener(button);
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

        /**
         * 客户端初始化：挂载客户端网络实现。
         *
         * <p>原先这一步在 {@code @Mod} 构造器里（双端执行）→ 专用服务端也会实例化 NeoForgeNetwork，
         * 而该类在第五轮 3.5-3 加入了对 {@code net.minecraft.client.Minecraft} 的引用（超限提示），
         * 服务端加载时会触发 NoClassDefFoundError（第六轮 P1-4）。
         * 与 forge / fabric 三端对齐：都只在 Dist.CLIENT 专属类里初始化。
         */
        @SubscribeEvent
        public static void onClientSetup(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event) {
            io.github.zgxhzhr.maidfm.platform.Services.NETWORK.loadService(
                    io.github.zgxhzhr.maidfm.network.IMaidFileNetwork.class,
                    io.github.zgxhzhr.maidfm.network.NeoForgeNetwork::new);
            io.github.zgxhzhr.maidfm.network.IMaidFileNetwork.Holder.set(
                    io.github.zgxhzhr.maidfm.platform.Services.NETWORK.get());
            Constants.LOG.info("[maid_file_manager] client network impl registered");
        }

        @SubscribeEvent
        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            event.register(MaidFileKeyMappings.OPEN_MANAGER);
            Constants.LOG.info("[maid_file_manager] KeyMapping registered");
        }
    }
}
