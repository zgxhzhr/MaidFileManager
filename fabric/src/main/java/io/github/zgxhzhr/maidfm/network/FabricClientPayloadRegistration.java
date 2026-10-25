package io.github.zgxhzhr.maidfm.network;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

/**
 * Fabric 客户端侧负载接收器。
 *
 * <p>必须与 FabricPayloadRegistration 分开：这里引用了 ClientPlayNetworking 与 Minecraft，
 * 只在客户端入口（ClientModInitializer）里加载；混进公共注册类会让专用服务器在类加载阶段崩掉。
 */
public final class FabricClientPayloadRegistration {

    private FabricClientPayloadRegistration() {}

    /** 客户端：挂 S2C 接收器（服务端回包后由客户端处理） */
    public static void registerClientReceivers() {
        ClientPlayNetworking.registerGlobalReceiver(MaidFilePayloads.ServerConfigSyncPayload.TYPE, (payload, ctx) ->
                payload.handle(new ClientCtx()));
        ClientPlayNetworking.registerGlobalReceiver(MaidFilePayloads.MaidListPayload.TYPE, (payload, ctx) ->
                payload.handle(new ClientCtx()));
        ClientPlayNetworking.registerGlobalReceiver(MaidFilePayloads.ExportBatchResultPayload.TYPE, (payload, ctx) ->
                payload.handle(new ClientCtx()));
        ClientPlayNetworking.registerGlobalReceiver(MaidFilePayloads.FeedbackPayload.TYPE, (payload, ctx) ->
                payload.handle(new ClientCtx()));
        ClientPlayNetworking.registerGlobalReceiver(MaidFilePayloads.ImportBatchResultPayload.TYPE, (payload, ctx) ->
                payload.handle(new ClientCtx()));
        ClientPlayNetworking.registerGlobalReceiver(MaidFilePayloads.ServerExportListPayload.TYPE, (payload, ctx) ->
                payload.handle(new ClientCtx()));
        ClientPlayNetworking.registerGlobalReceiver(MaidFilePayloads.MaidProfilePayload.TYPE, (payload, ctx) ->
                payload.handle(new ClientCtx()));
        ClientPlayNetworking.registerGlobalReceiver(MaidFilePayloads.BackupListPayload.TYPE, (payload, ctx) ->
                payload.handle(new ClientCtx()));
        ClientPlayNetworking.registerGlobalReceiver(MaidFilePayloads.BackupExportResultPayload.TYPE, (payload, ctx) ->
                payload.handle(new ClientCtx()));
        ClientPlayNetworking.registerGlobalReceiver(MaidFilePayloads.ChunkedDownloadPayload.TYPE, (payload, ctx) ->
                payload.handle(new ClientCtx()));
    }

    /** 客户端入站上下文：回调时从客户端实例取当前玩家 */
    private record ClientCtx(Player ignored) implements PayloadCtx {
        ClientCtx() {
            this(Minecraft.getInstance().player);
        }

        @Override
        public Player player() {
            return Minecraft.getInstance().player;
        }
    }
}
