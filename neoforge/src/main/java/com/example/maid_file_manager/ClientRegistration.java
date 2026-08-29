package com.example.maid_file_manager;

import com.example.maid_file_manager.client.MaidFileKeyMappings;
import com.example.maid_file_manager.client.MaidFileManagerScreen;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

@EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public class ClientRegistration {

    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(MaidFileKeyMappings.OPEN_MANAGER);
    }

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
}
