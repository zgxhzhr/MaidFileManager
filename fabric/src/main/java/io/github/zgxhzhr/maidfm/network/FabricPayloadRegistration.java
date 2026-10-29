package io.github.zgxhzhr.maidfm.network;

import io.github.zgxhzhr.maidfm.Constants;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.world.entity.player.Player;

/**
 * Fabric 侧的负载类型注册与接收器挂载。
 *
 * <p>NeoForge 用 RegisterPayloadHandlersEvent 一次注册方向、编解码器与处理器；Fabric 拆成三步：
 * PayloadTypeRegistry 按方向登记类型与编解码器，ServerPlayNetworking / ClientPlayNetworking
 * 分别挂 C2S 与 S2C 的接收器。C2S 与 S2C 各有一套，服务端入口只调前三步中的服务端部分，
 * 客户端入口只调客户端部分——否则专用服务器会尝试加载客户端类而崩溃。
 */
public final class FabricPayloadRegistration {

    private FabricPayloadRegistration() {}

    /** 登记全部负载的类型与编解码器（C2S 与 S2C 都必须登记，两端各自调用） */
    public static void registerTypes() {
        PayloadTypeRegistry.serverboundPlay().register(MaidFilePayloads.RequestMaidListPayload.TYPE, MaidFilePayloads.RequestMaidListPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(MaidFilePayloads.ExportMaidPayload.TYPE, MaidFilePayloads.ExportMaidPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(MaidFilePayloads.ImportFilePayload.TYPE, MaidFilePayloads.ImportFilePayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(MaidFilePayloads.ExportBatchPayload.TYPE, MaidFilePayloads.ExportBatchPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(MaidFilePayloads.ImportBatchPayload.TYPE, MaidFilePayloads.ImportBatchPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(MaidFilePayloads.ClientConsentPayload.TYPE, MaidFilePayloads.ClientConsentPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(MaidFilePayloads.RequestServerExportListPayload.TYPE, MaidFilePayloads.RequestServerExportListPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(MaidFilePayloads.ServerExportBatchPayload.TYPE, MaidFilePayloads.ServerExportBatchPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(MaidFilePayloads.SetServerConfigPayload.TYPE, MaidFilePayloads.SetServerConfigPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(MaidFilePayloads.SetServerBaubleBlockedListPayload.TYPE, MaidFilePayloads.SetServerBaubleBlockedListPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(MaidFilePayloads.RequestMaidProfilePayload.TYPE, MaidFilePayloads.RequestMaidProfilePayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(MaidFilePayloads.SaveMaidProfilePayload.TYPE, MaidFilePayloads.SaveMaidProfilePayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(MaidFilePayloads.RequestBackupListPayload.TYPE, MaidFilePayloads.RequestBackupListPayload.STREAM_CODEC);
PayloadTypeRegistry.serverboundPlay().register(MaidFilePayloads.ModelNamesPayload.TYPE, MaidFilePayloads.ModelNamesPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(MaidFilePayloads.RequestBackupExportPayload.TYPE, MaidFilePayloads.RequestBackupExportPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(MaidFilePayloads.ChunkedUploadPayload.TYPE, MaidFilePayloads.ChunkedUploadPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(MaidFilePayloads.ExportWriteAckPayload.TYPE, MaidFilePayloads.ExportWriteAckPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MaidFilePayloads.ServerConfigSyncPayload.TYPE, MaidFilePayloads.ServerConfigSyncPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MaidFilePayloads.MaidListPayload.TYPE, MaidFilePayloads.MaidListPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MaidFilePayloads.ExportBatchResultPayload.TYPE, MaidFilePayloads.ExportBatchResultPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MaidFilePayloads.FeedbackPayload.TYPE, MaidFilePayloads.FeedbackPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MaidFilePayloads.ImportBatchResultPayload.TYPE, MaidFilePayloads.ImportBatchResultPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MaidFilePayloads.ServerExportListPayload.TYPE, MaidFilePayloads.ServerExportListPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MaidFilePayloads.MaidProfilePayload.TYPE, MaidFilePayloads.MaidProfilePayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MaidFilePayloads.BackupListPayload.TYPE, MaidFilePayloads.BackupListPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MaidFilePayloads.BackupExportResultPayload.TYPE, MaidFilePayloads.BackupExportResultPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MaidFilePayloads.ChunkedDownloadPayload.TYPE, MaidFilePayloads.ChunkedDownloadPayload.STREAM_CODEC);
        Constants.LOG.info("[maid_file_manager] 负载类型注册完成: C2S={} S2C={}", 16, 10);
    }

    /** 服务端：挂 C2S 接收器（客户端发包后由服务端处理） */
    public static void registerServerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(MaidFilePayloads.RequestMaidListPayload.TYPE, (payload, ctx) ->
                payload.handle(new ServerCtx(ctx.player())));
        ServerPlayNetworking.registerGlobalReceiver(MaidFilePayloads.ExportMaidPayload.TYPE, (payload, ctx) ->
                payload.handle(new ServerCtx(ctx.player())));
        ServerPlayNetworking.registerGlobalReceiver(MaidFilePayloads.ImportFilePayload.TYPE, (payload, ctx) ->
                payload.handle(new ServerCtx(ctx.player())));
        ServerPlayNetworking.registerGlobalReceiver(MaidFilePayloads.ExportBatchPayload.TYPE, (payload, ctx) ->
                payload.handle(new ServerCtx(ctx.player())));
        ServerPlayNetworking.registerGlobalReceiver(MaidFilePayloads.ImportBatchPayload.TYPE, (payload, ctx) ->
                payload.handle(new ServerCtx(ctx.player())));
        ServerPlayNetworking.registerGlobalReceiver(MaidFilePayloads.ClientConsentPayload.TYPE, (payload, ctx) ->
                payload.handle(new ServerCtx(ctx.player())));
        ServerPlayNetworking.registerGlobalReceiver(MaidFilePayloads.RequestServerExportListPayload.TYPE, (payload, ctx) ->
                payload.handle(new ServerCtx(ctx.player())));
        ServerPlayNetworking.registerGlobalReceiver(MaidFilePayloads.ServerExportBatchPayload.TYPE, (payload, ctx) ->
                payload.handle(new ServerCtx(ctx.player())));
        ServerPlayNetworking.registerGlobalReceiver(MaidFilePayloads.SetServerConfigPayload.TYPE, (payload, ctx) ->
                payload.handle(new ServerCtx(ctx.player())));
        ServerPlayNetworking.registerGlobalReceiver(MaidFilePayloads.SetServerBaubleBlockedListPayload.TYPE, (payload, ctx) ->
                payload.handle(new ServerCtx(ctx.player())));
        ServerPlayNetworking.registerGlobalReceiver(MaidFilePayloads.RequestMaidProfilePayload.TYPE, (payload, ctx) ->
                payload.handle(new ServerCtx(ctx.player())));
        ServerPlayNetworking.registerGlobalReceiver(MaidFilePayloads.SaveMaidProfilePayload.TYPE, (payload, ctx) ->
                payload.handle(new ServerCtx(ctx.player())));
        ServerPlayNetworking.registerGlobalReceiver(MaidFilePayloads.RequestBackupListPayload.TYPE, (payload, ctx) ->
                payload.handle(new ServerCtx(ctx.player())));
        ServerPlayNetworking.registerGlobalReceiver(MaidFilePayloads.RequestBackupExportPayload.TYPE, (payload, ctx) ->
                payload.handle(new ServerCtx(ctx.player())));
        ServerPlayNetworking.registerGlobalReceiver(MaidFilePayloads.ChunkedUploadPayload.TYPE, (payload, ctx) ->
                payload.handle(new ServerCtx(ctx.player())));
        ServerPlayNetworking.registerGlobalReceiver(MaidFilePayloads.ExportWriteAckPayload.TYPE, (payload, ctx) ->
                payload.handle(new ServerCtx(ctx.player())));
    }

    /** 服务端入站上下文 */
    private record ServerCtx(Player player) implements PayloadCtx {}
}
