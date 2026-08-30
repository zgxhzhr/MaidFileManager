package com.example.maid_file_manager;

import com.example.maid_file_manager.client.MaidFileManagerScreen;
import com.example.maid_file_manager.client.MaidFileKeyMappings;
import com.example.maid_file_manager.config.MaidConfigManager;
import com.example.maid_file_manager.data.MaidFileData;
import com.example.maid_file_manager.data.MaidInfo;
import com.example.maid_file_manager.network.FabricNetwork;
import com.example.maid_file_manager.network.IMaidFileNetwork;
import com.example.maid_file_manager.network.MaidFilePackets;
import com.example.maid_file_manager.network.MaidPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;

import java.util.List;

public class MaidFileModFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        KeyBindingHelper.registerKeyBinding(MaidFileKeyMappings.OPEN_MANAGER);
        IMaidFileNetwork.Holder.set(new FabricNetwork());

        ClientPlayNetworking.registerGlobalReceiver(MaidPayload.typeOf(MaidFilePackets.ID_MAID_LIST), (payload, context) -> {
            List<MaidInfo> list = MaidFilePackets.readMaidInfoList(payload.body());
            context.client().execute(() -> {
                IMaidFileNetwork.ClientHandler h = IMaidFileNetwork.ClientHandlerHolder.get();
                if (h != null) {
                    h.onMaidListReceived(list);
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(MaidPayload.typeOf(MaidFilePackets.ID_EXPORT_RESULT), (payload, context) -> {
            MaidFileData data = MaidFilePackets.readMaidFileData(payload.body());
            context.client().execute(() -> {
                IMaidFileNetwork.ClientHandler h = IMaidFileNetwork.ClientHandlerHolder.get();
                if (h != null) {
                    h.onExportResultReceived(data == null
                            ? java.util.Collections.emptyList()
                            : java.util.Collections.singletonList(data));
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(MaidPayload.typeOf(MaidFilePackets.ID_FEEDBACK), (payload, context) -> {
            Component message = Component.Serializer.fromJson(payload.body().readUtf(32767), RegistryAccess.EMPTY);
            context.client().execute(() -> {
                IMaidFileNetwork.ClientHandler h = IMaidFileNetwork.ClientHandlerHolder.get();
                if (h != null) {
                    h.onFeedbackReceived(message);
                }
            });
        });

        // 批量导出结果：服务端序列化数据回传，由客户端写 maid_exports/<玩家名>/ 目录
        ClientPlayNetworking.registerGlobalReceiver(MaidPayload.typeOf(MaidFilePackets.ID_EXPORT_BATCH_RESULT), (payload, context) -> {
            List<MaidFileData> dataList = MaidFilePackets.readMaidFileDataList(payload.body());
            context.client().execute(() -> {
                IMaidFileNetwork.ClientHandler h = IMaidFileNetwork.ClientHandlerHolder.get();
                if (h != null) {
                    h.onExportResultReceived(dataList);
                }
            });
        });

        // OP 统一导出：收到服务端收集的所有在线玩家女仆分组列表
        ClientPlayNetworking.registerGlobalReceiver(MaidPayload.typeOf(MaidFilePackets.ID_SERVER_EXPORT_LIST), (payload, context) -> {
            List<IMaidFileNetwork.PlayerMaidGroup> groups = MaidFilePackets.readPlayerMaidGroups(payload.body());
            context.client().execute(() -> {
                IMaidFileNetwork.ClientHandler h = IMaidFileNetwork.ClientHandlerHolder.get();
                if (h != null) {
                    h.onServerExportListReceived(groups);
                }
            });
        });

        // 服务端配置同步：不依赖 Screen，收到即更新客户端缓存并回发同意状态
        ClientPlayNetworking.registerGlobalReceiver(MaidPayload.typeOf(MaidFilePackets.ID_SERVER_CONFIG_SYNC), (payload, context) -> {
            boolean allowImport = payload.body().readBoolean();
            boolean allowBaubles = payload.body().readBoolean();
            context.client().execute(() -> MaidConfigManager.handleServerConfigSync(allowImport, allowBaubles));
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
