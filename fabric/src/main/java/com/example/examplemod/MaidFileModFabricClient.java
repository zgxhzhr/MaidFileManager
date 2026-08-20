package com.example.examplemod;

import com.example.examplemod.client.MaidFileManagerScreen;
import com.example.examplemod.client.MaidFileKeyMappings;
import com.example.examplemod.data.MaidFileData;
import com.example.examplemod.data.MaidInfo;
import com.example.examplemod.network.FabricNetwork;
import com.example.examplemod.network.IMaidFileNetwork;
import com.example.examplemod.network.MaidFilePackets;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
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
                    h.onExportResultReceived(data);
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
