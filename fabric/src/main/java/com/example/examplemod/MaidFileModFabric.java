package com.example.examplemod;

import com.example.examplemod.data.MaidFileData;
import com.example.examplemod.data.MaidInfo;
import com.example.examplemod.network.MaidFilePackets;
import com.example.examplemod.service.MaidTransferService;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

public class MaidFileModFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        Constants.LOG.info("Maid File Manager (Fabric) loading...");

        registerC2SReceiver(MaidFilePackets.ID_REQUEST_MAID_LIST, (server, player, handler, buf, responseSender) -> {
            server.execute(() -> {
                List<MaidInfo> list = MaidTransferService.listOwnMaids(player);
                FriendlyByteBuf out = PacketByteBufs.create();
                MaidFilePackets.writeMaidInfoList(out, list);
                ServerPlayNetworking.send(player, MaidFilePackets.ID_MAID_LIST, out);
            });
        });

        registerC2SReceiver(MaidFilePackets.ID_EXPORT_MAID, (server, player, handler, buf, responseSender) -> {
            int entityId = buf.readInt();
            server.execute(() -> {
                MaidFileData data = MaidTransferService.exportMaidToData(player, entityId);
                if (data != null) {
                    FriendlyByteBuf out = PacketByteBufs.create();
                    MaidFilePackets.writeMaidFileData(out, data);
                    ServerPlayNetworking.send(player, MaidFilePackets.ID_EXPORT_RESULT, out);
                } else {
                    sendFeedback(player, Component.translatable("maid_file_manager.export.fail", "无法导出"));
                }
            });
        });

        registerC2SReceiver(MaidFilePackets.ID_IMPORT_FILE, (server, player, handler, buf, responseSender) -> {
            server.execute(() -> {
                MaidFileData maidData = MaidFilePackets.readMaidFileData(buf);
                if (maidData == null) {
                    sendFeedback(player, Component.translatable("maid_file_manager.import.fail.exception", "无效的女仆数据"));
                    return;
                }
                Component feedback = MaidTransferService.importMaidFromData(player, maidData);
                sendFeedback(player, feedback);
            });
        });

        Constants.LOG.info("Maid File Manager network registered");
    }

    private static void sendFeedback(ServerPlayer player, Component message) {
        FriendlyByteBuf out = PacketByteBufs.create();
        out.writeComponent(message);
        ServerPlayNetworking.send(player, MaidFilePackets.ID_FEEDBACK, out);
    }

    private static void registerC2SReceiver(
            net.minecraft.resources.ResourceLocation id,
            ServerPlayNetworking.PlayChannelHandler handler) {
        ServerPlayNetworking.registerGlobalReceiver(id, handler);
    }
}
