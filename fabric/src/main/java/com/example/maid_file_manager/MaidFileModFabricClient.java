package com.example.maid_file_manager;

import com.example.maid_file_manager.client.MaidFileManagerScreen;
import com.example.maid_file_manager.client.MaidFileKeyMappings;
import com.example.maid_file_manager.config.MaidConfigManager;
import com.example.maid_file_manager.data.MaidFileData;
import com.example.maid_file_manager.data.MaidInfo;
import com.example.maid_file_manager.network.FabricNetwork;
import com.example.maid_file_manager.network.IMaidFileNetwork;
import com.example.maid_file_manager.network.MaidFilePackets;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.chat.Component;

import java.util.List;

public class MaidFileModFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        KeyBindingHelper.registerKeyBinding(MaidFileKeyMappings.OPEN_MANAGER);
        IMaidFileNetwork.Holder.set(new FabricNetwork());

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
            client.execute(() -> MaidConfigManager.handleServerConfigSync(
                    allowImport, allowBaubles, allowAdvancements, allowEffects));
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
}
