package io.github.zgxhzhr.maidfm;

import io.github.zgxhzhr.maidfm.client.MaidBackupBrowserScreen;
import io.github.zgxhzhr.maidfm.client.MaidFileManagerScreen;
import io.github.zgxhzhr.maidfm.client.MaidFileKeyMappings;
import io.github.zgxhzhr.maidfm.config.MaidConfigManager;
import io.github.zgxhzhr.maidfm.data.MaidFileData;
import io.github.zgxhzhr.maidfm.data.MaidInfo;
import io.github.zgxhzhr.maidfm.data.MaidProfileView;
import io.github.zgxhzhr.maidfm.network.FabricNetwork;
import io.github.zgxhzhr.maidfm.network.IMaidFileNetwork;
import io.github.zgxhzhr.maidfm.network.MaidFilePackets;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

import java.util.List;

public class MaidFileModFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        KeyBindingHelper.registerKeyBinding(MaidFileKeyMappings.OPEN_MANAGER);
        IMaidFileNetwork.Holder.set(new FabricNetwork());
        registerTitleScreenEntry();

        ClientPlayNetworking.registerGlobalReceiver(MaidFilePackets.ID_MAID_LIST, (client, handler, buf, responseSender) -> {
            List<MaidInfo> list = MaidFilePackets.readMaidInfoList(buf);
            client.execute(() -> {
                IMaidFileNetwork.ClientHandler h = IMaidFileNetwork.ClientHandlerHolder.get();
                if (h != null) {
                    h.onMaidListReceived(list);
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(MaidFilePackets.ID_EXPORT_RESULT, (client, handler, buf, responseSender) -> {
            MaidFileData data = MaidFilePackets.readMaidFileData(buf);
            client.execute(() -> {
                IMaidFileNetwork.ClientHandler h = IMaidFileNetwork.ClientHandlerHolder.get();
                if (h != null) {
                    h.onExportResultReceived(data == null
                            ? java.util.Collections.emptyList()
                            : java.util.Collections.singletonList(data));
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(MaidFilePackets.ID_FEEDBACK, (client, handler, buf, responseSender) -> {
            Component message = buf.readComponent();
            client.execute(() -> {
                IMaidFileNetwork.ClientHandler h = IMaidFileNetwork.ClientHandlerHolder.get();
                if (h != null) {
                    h.onFeedbackReceived(message);
                }
            });
        });

        // 批量导入 + 删除源文件：汇总文案 + 逐项 spawned 标志，客户端只删除成功导入的本地文件
        ClientPlayNetworking.registerGlobalReceiver(MaidFilePackets.ID_IMPORT_BATCH_RESULT, (client, handler, buf, responseSender) -> {
            Component summary = buf.readComponent();
            List<Boolean> spawned = MaidFilePackets.readBooleanList(buf);
            client.execute(() -> {
                IMaidFileNetwork.ClientHandler h = IMaidFileNetwork.ClientHandlerHolder.get();
                if (h != null) {
                    h.onImportBatchResultReceived(summary, spawned);
                }
            });
        });

        // 批量导出结果：服务端序列化数据回传，由客户端写 maid_exports/<玩家名>/ 目录。
        // 条目上限必须与服务端导出请求侧 MAX_EXPORT_IDS(512) 对齐，不能沿用导入通道的 64，
        // 否则 65~512 个合法结果会在此解码抛异常把玩家踢下线
        ClientPlayNetworking.registerGlobalReceiver(MaidFilePackets.ID_EXPORT_BATCH_RESULT, (client, handler, buf, responseSender) -> {
            List<MaidFileData> dataList = MaidFilePackets.readMaidFileDataList(buf, MaidFilePackets.MAX_EXPORT_IDS);
            client.execute(() -> {
                IMaidFileNetwork.ClientHandler h = IMaidFileNetwork.ClientHandlerHolder.get();
                if (h != null) {
                    h.onExportResultReceived(dataList);
                }
            });
        });

        // OP 统一导出：收到服务端收集的所有在线玩家女仆分组列表
        ClientPlayNetworking.registerGlobalReceiver(MaidFilePackets.ID_SERVER_EXPORT_LIST, (client, handler, buf, responseSender) -> {
            List<IMaidFileNetwork.PlayerMaidGroup> groups = MaidFilePackets.readPlayerMaidGroups(buf);
            client.execute(() -> {
                IMaidFileNetwork.ClientHandler h = IMaidFileNetwork.ClientHandlerHolder.get();
                if (h != null) {
                    h.onServerExportListReceived(groups);
                }
            });
        });

        // 服务端配置同步：不依赖 Screen，收到即更新客户端缓存并回发同意状态
        ClientPlayNetworking.registerGlobalReceiver(MaidFilePackets.ID_SERVER_CONFIG_SYNC, (client, handler, buf, responseSender) -> {
            boolean allowImport = buf.readBoolean();
            boolean allowBaubles = buf.readBoolean();
            boolean allowAdvancements = buf.readBoolean();
            boolean allowEffects = buf.readBoolean();
            boolean allowInvulnerable = buf.readBoolean();
            boolean baubleStripAttributes = buf.readBoolean();
            List<String> baubleBlockedList = MaidFilePackets.readStringList(buf);
            List<String> baubleBlacklist = MaidFilePackets.readStringList(buf);
            List<String> baubleWhitelist = MaidFilePackets.readStringList(buf);
            boolean baubleManaged = buf.readBoolean();
            client.execute(() -> MaidConfigManager.handleServerConfigSync(
                    allowImport, allowBaubles, allowAdvancements, allowEffects, allowInvulnerable,
                    baubleStripAttributes, baubleBlockedList, baubleBlacklist, baubleWhitelist, baubleManaged));
        });

        // 女仆档案视图：服务端校验归属后返回，交由当前活跃界面展示
        ClientPlayNetworking.registerGlobalReceiver(MaidFilePackets.ID_MAID_PROFILE, (client, handler, buf, responseSender) -> {
            MaidProfileView view = MaidFilePackets.readMaidProfileView(buf);
            client.execute(() -> {
                IMaidFileNetwork.ClientHandler h = IMaidFileNetwork.ClientHandlerHolder.get();
                if (h != null) {
                    h.onMaidProfileReceived(view);
                }
            });
        });

        // 备份管理：服务端回传的备份列表（已按权限过滤），交由当前活跃界面展示
        ClientPlayNetworking.registerGlobalReceiver(MaidFilePackets.ID_BACKUP_LIST, (client, handler, buf, responseSender) -> {
            List<IMaidFileNetwork.BackupOwner> owners = MaidFilePackets.readBackupOwners(buf);
            client.execute(() -> {
                IMaidFileNetwork.ClientHandler h = IMaidFileNetwork.ClientHandlerHolder.get();
                if (h != null) {
                    h.onBackupListReceived(owners);
                }
            });
        });

        // 备份管理：服务端回传的单条备份数据，客户端据此写入本地 maid_exports/
        ClientPlayNetworking.registerGlobalReceiver(MaidFilePackets.ID_BACKUP_EXPORT_RESULT, (client, handler, buf, responseSender) -> {
            int len = MaidFilePackets.checkSize(buf.readVarInt(),
                    MaidFilePackets.MAX_SINGLE_FILE_BYTES, "backup_export_result");
            byte[] blob = new byte[len];
            buf.readBytes(blob);
            MaidFileData data = MaidFilePackets.deserializeMaidFileData(blob);
            client.execute(() -> {
                IMaidFileNetwork.ClientHandler h = IMaidFileNetwork.ClientHandlerHolder.get();
                if (h != null) {
                    h.onBackupExportReceived(data);
                }
            });
        });

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.player == null || mc.screen != null) {
                return;
            }
            while (MaidFileKeyMappings.OPEN_MANAGER.consumeClick()) {
                mc.setScreen(new MaidFileManagerScreen());
            }
        });

        Constants.LOG.info("Maid File Manager client initialized");
    }

    /**
     * 在游戏主菜单（标题界面）注入「女仆档案管理」入口小按钮。
     *
     * <p>该入口只放在原版主菜单，与游戏内管理界面分离：用于浏览各存档中车万女仆的
     * 自动备份并导出为符合本模组规范的 .maid 文件，因此不依赖进入世界。
     */
    private static void registerTitleScreenEntry() {
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
            Screens.getButtons(screen).add(button);
        });
    }
}
