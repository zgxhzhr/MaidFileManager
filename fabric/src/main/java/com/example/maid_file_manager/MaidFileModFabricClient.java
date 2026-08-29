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

        // 服务端配置同步：不依赖 Screen，收到即更新客户端缓存并回发同意状态
        ClientPlayNetworking.registerGlobalReceiver(MaidFilePackets.ID_SERVER_CONFIG_SYNC, (client, handler, buf, responseSender) -> {
            boolean allowImport = buf.readBoolean();
            boolean allowBaubles = buf.readBoolean();
            client.execute(() -> MaidConfigManager.handleServerConfigSync(allowImport, allowBaubles));
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
