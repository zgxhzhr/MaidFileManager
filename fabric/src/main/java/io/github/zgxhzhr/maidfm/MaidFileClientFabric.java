package io.github.zgxhzhr.maidfm;

import io.github.zgxhzhr.maidfm.client.MaidBackupBrowserScreen;
import io.github.zgxhzhr.maidfm.client.MaidFileKeyMappings;
import io.github.zgxhzhr.maidfm.client.PlayerNameResolver;
import io.github.zgxhzhr.maidfm.client.MaidFileManagerScreen;
import io.github.zgxhzhr.maidfm.network.FabricClientPayloadRegistration;
import io.github.zgxhzhr.maidfm.network.FabricNetwork;
import io.github.zgxhzhr.maidfm.network.IMaidFileNetwork;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

/**
 * Fabric 客户端入口。
 *
 * <p>对应 NeoForge 版 ClientRegistration 的四件事：挂客户端网络实现、注册按键映射、
 * 每 tick 消费按键打开界面、在主菜单注入「备份管理」按钮。
 *
 * <p>这个类引用客户端类型，由 fabric.mod.json 的 client entrypoint 加载；
 * 混进 MaidFileModFabric（main entrypoint）会让专用服务器在类加载阶段崩掉。
 */
public class MaidFileClientFabric implements ClientModInitializer {

    /** 本连接是否已上报模型名映射 */
    private static boolean MODEL_NAMES_SENT;

    /** 把本机认识的「模型 ID → 显示名」上报给服务端（每个连接只发一次） */
    private static void sendModelNamesOnce() {
        if (MODEL_NAMES_SENT) {
            return;
        }
        MODEL_NAMES_SENT = true;
        try {
            java.util.Map<String, String> names = io.github.zgxhzhr.maidfm.client.ModelNames.exportModelNameMap();
            if (names == null || names.isEmpty()) {
                return;
            }
            java.util.List<String> pairs = new java.util.ArrayList<>(names.size() * 2);
            for (java.util.Map.Entry<String, String> e : names.entrySet()) {
                pairs.add(e.getKey());
                pairs.add(e.getValue());
            }
            IMaidFileNetwork net = IMaidFileNetwork.Holder.get();
            if (net != null) {
                net.sendModelNames(pairs);
                Constants.LOG.info("[maid_file_manager] 已上报 {} 条模型名映射", names.size());
            }
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 上报模型名失败（不影响功能）: {}", t.toString());
        }
    }

    @Override
    public void onInitializeClient() {
        Constants.LOG.info("========================================");
        Constants.LOG.info("[maid_file_manager] Client initializer START (Fabric)");

        // 客户端网络实现：FabricNetwork 引用了 ClientPlayNetworking 与 Minecraft，
        // 只能在客户端初始化里实例化（NeoForge 版同样把它从 @Mod 构造器挪到了 Dist.CLIENT 专属类）。
        IMaidFileNetwork.Holder.set(new FabricNetwork());

        // 按键映射：U 键（可在「选项 → 控制设置」里改）
        KeyMappingHelper.registerKeyMapping(MaidFileKeyMappings.OPEN_MANAGER);
        Constants.LOG.info("[maid_file_manager] KeyMapping registered");

        // S2C 接收器
        FabricClientPayloadRegistration.registerClientReceivers();

        // 进入世界即开始解析属主名字（不等玩家打开备份界面）。
        // Mojang 查询接口约每分钟一次，提前在后台排队，打开界面时名字基本已就位。
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            java.nio.file.Path gameDir = client.gameDirectory.toPath();
            PlayerNameResolver.loadCache(gameDir);
            PlayerNameResolver.warmUpFromSaves(gameDir);
            io.github.zgxhzhr.maidfm.client.ClientModelNameCache.load(gameDir);
            io.github.zgxhzhr.maidfm.service.ModelNameRegistry.externalProvider =
                    io.github.zgxhzhr.maidfm.client.ClientModelNameCache::lookup;
            MODEL_NAMES_SENT = false;
        });

        // 每 tick 消费一次按键：NeoForge 用 InputEvent.Key，Fabric 用 ClientTickEvents。
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (!MODEL_NAMES_SENT && mc.player != null) {
                sendModelNamesOnce();
            }
            if (mc.player == null || mc.screen != null) {
                return;
            }
            while (MaidFileKeyMappings.OPEN_MANAGER.consumeClick()) {
                openGui();
            }
        });

        // 原版主菜单左上角注入「女仆档案管理」入口：与游戏内管理界面分离，
        // 用于浏览各存档中车万女仆的自动备份并导出为 .maid，因此不依赖进入世界。
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof TitleScreen)) {
                return;
            }
            Button button = Button.builder(
                            Component.translatable("maid_file_manager.gui.button.profile_browser"),
                            b -> client.setScreen(new MaidBackupBrowserScreen(client.screen)))
                    .bounds(6, 6, 56, 20)
                    .tooltip(Tooltip.create(
                            Component.translatable("maid_file_manager.gui.button.profile_browser.tooltip")))
                    .build();
            addWidget(screen, button);
        });

        Constants.LOG.info("[maid_file_manager] Client initializer END");
        Constants.LOG.info("========================================");
    }

    /**
     * 供 /maidfile gui 命令通过反射调用（{@code MaidServerCommands.openClientGui}）。
     * common 层不能静态引用客户端类，因此命令侧用类名反射；方法名必须保持 {@code openGui}。
     */
    public static void openGui() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        mc.setScreen(new MaidFileManagerScreen());
    }
    /**
     * 往 Screen 里注入控件。
     *
     * <p>26.1 的 Fabric API 移除了 {@code Screens.getButtons}，而原版
     * {@code Screen.addRenderableWidget} 是 protected。这里用反射调用，
     * 失败只告警不崩——主菜单入口是便利功能，不该因为它拖垮整个客户端初始化。
     */
    private static void addWidget(net.minecraft.client.gui.screens.Screen screen,
                                  net.minecraft.client.gui.components.events.GuiEventListener widget) {
        try {
            java.lang.reflect.Method m = net.minecraft.client.gui.screens.Screen.class
                    .getDeclaredMethod("addRenderableWidget",
                            net.minecraft.client.gui.components.events.GuiEventListener.class);
            m.setAccessible(true);
            m.invoke(screen, widget);
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 主菜单入口注入失败（不影响其它功能）: {}", t.toString());
        }
    }
}