package com.example.maid_file_manager;

import com.example.maid_file_manager.config.MaidConfigManager;
import com.example.maid_file_manager.data.MaidFileData;
import com.example.maid_file_manager.data.MaidInfo;
import com.example.maid_file_manager.network.MaidFilePackets;
import com.example.maid_file_manager.service.MaidTransferService;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

public class MaidFileModFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        Constants.LOG.info("Maid File Manager (Fabric) loading...");

        // 配置系统初始化（服务端/客户端 properties 文件）
        MaidConfigManager.init(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir());

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
                boolean keepBaubles = buf.readBoolean();
                if (maidData == null) {
                    sendFeedback(player, Component.translatable("maid_file_manager.import.fail.exception", "无效的女仆数据"));
                    return;
                }
                Component feedback = MaidTransferService.importMaidFromData(player, maidData, keepBaubles);
                sendFeedback(player, feedback);
            });
        });

        registerC2SReceiver(MaidFilePackets.ID_CLIENT_CONSENT, (server, player, handler, buf, responseSender) -> {
            boolean allow = buf.readBoolean();
            server.execute(() -> MaidConfigManager.handleClientConsent(player.getUUID(), allow));
        });

        registerC2SReceiver(MaidFilePackets.ID_SET_SERVER_CONFIG, (server, player, handler, buf, responseSender) -> {
            String key = buf.readUtf(128);
            boolean value = buf.readBoolean();
            server.execute(() -> {
                if (!player.hasPermissions(2)) {
                    sendFeedback(player, Component.translatable("maid_file_manager.config.fail.no_permission"));
                    return;
                }
                MaidConfigManager.setServerConfig(key, value);
                broadcastServerConfig(server, player);
            });
        });

        // 玩家登录 → 推送服务端配置同步；退出 → 清理同意状态
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.player;
            FriendlyByteBuf out = PacketByteBufs.create();
            out.writeBoolean(MaidConfigManager.isClientImportAllowed());
            out.writeBoolean(MaidConfigManager.isBaublesAllowed());
            ServerPlayNetworking.send(player, MaidFilePackets.ID_SERVER_CONFIG_SYNC, out);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                MaidConfigManager.removeClientConsent(handler.player.getUUID()));

        Constants.LOG.info("Maid File Manager network registered");
    }

    /** 把服务端配置同步给指定玩家（可扩展为广播） */
    private static void broadcastServerConfig(net.minecraft.server.MinecraftServer server, ServerPlayer player) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            FriendlyByteBuf out = PacketByteBufs.create();
            out.writeBoolean(MaidConfigManager.isClientImportAllowed());
            out.writeBoolean(MaidConfigManager.isBaublesAllowed());
            ServerPlayNetworking.send(p, MaidFilePackets.ID_SERVER_CONFIG_SYNC, out);
        }
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
