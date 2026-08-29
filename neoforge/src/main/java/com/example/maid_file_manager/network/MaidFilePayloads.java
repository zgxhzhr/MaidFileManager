package com.example.maid_file_manager.network;

import com.example.maid_file_manager.Constants;
import com.example.maid_file_manager.config.MaidConfigManager;
import com.example.maid_file_manager.data.MaidFileData;
import com.example.maid_file_manager.service.MaidTransferService;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MaidFilePayloads {

    private MaidFilePayloads() {
    }

    // ================ C2S 包（客户端 -> 服务端） ================

    public record RequestMaidListPayload() implements CustomPacketPayload {
        public static final Type<RequestMaidListPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "request_maid_list"));
        public static final StreamCodec<ByteBuf, RequestMaidListPayload> STREAM_CODEC =
                StreamCodec.unit(new RequestMaidListPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (ctx.player() instanceof ServerPlayer sp) {
                    var list = MaidTransferService.listOwnMaids(sp);
                    PacketDistributor.sendToPlayer(sp, new MaidListPayload(list));
                }
            });
        }
    }

    public record ExportMaidPayload(int entityId) implements CustomPacketPayload {
        public static final Type<ExportMaidPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "export_maid"));
        public static final StreamCodec<ByteBuf, ExportMaidPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT,
                        ExportMaidPayload::entityId,
                        ExportMaidPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (ctx.player() instanceof ServerPlayer sp) {
                    MaidFileData data = MaidTransferService.exportMaidToData(sp, entityId);
                    if (data == null) {
                        PacketDistributor.sendToPlayer(sp, new FeedbackPayload(
                                net.minecraft.network.chat.Component.translatable("maid_file_manager.export.fail", "invalid maid")));
                    } else {
                        PacketDistributor.sendToPlayer(sp, new ExportResultPayload(data));
                    }
                }
            });
        }
    }

    public record ImportFilePayload(byte[] bytes, boolean keepBaubles) implements CustomPacketPayload {
        public static final Type<ImportFilePayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "import_file"));
        public static final StreamCodec<ByteBuf, ImportFilePayload> STREAM_CODEC =
                StreamCodec.ofMember(
                        (p, buf) -> {
                            FriendlyByteBuf fbb = new FriendlyByteBuf(buf);
                            fbb.writeVarInt(p.bytes.length);
                            fbb.writeBytes(p.bytes);
                            fbb.writeBoolean(p.keepBaubles);
                        },
                        buf -> {
                            FriendlyByteBuf fbb = new FriendlyByteBuf(buf);
                            int len = fbb.readVarInt();
                            byte[] arr = new byte[len];
                            fbb.readBytes(arr);
                            boolean keepBaubles = fbb.readBoolean();
                            return new ImportFilePayload(arr, keepBaubles);
                        }
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (ctx.player() instanceof ServerPlayer sp) {
                    MaidFileData data = MaidFilePackets.deserializeMaidFileData(bytes);
                    net.minecraft.network.chat.Component result =
                            MaidTransferService.importMaidFromData(sp, data, keepBaubles);
                    PacketDistributor.sendToPlayer(sp, new FeedbackPayload(result));
                }
            });
        }
    }

    public record ExportBatchPayload(byte[] encoded) implements CustomPacketPayload {
        public static final Type<ExportBatchPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "export_batch"));
        public static final StreamCodec<ByteBuf, ExportBatchPayload> STREAM_CODEC =
                StreamCodec.ofMember(
                        (p, buf) -> { FriendlyByteBuf fbb = new FriendlyByteBuf(buf); fbb.writeVarInt(p.encoded.length); fbb.writeBytes(p.encoded); },
                        buf -> { FriendlyByteBuf fbb = new FriendlyByteBuf(buf); int len = fbb.readVarInt(); byte[] arr = new byte[len]; fbb.readBytes(arr); return new ExportBatchPayload(arr); }
                );
        private static byte[] encodeExportBatch(List<Integer> ids, boolean removeAfter) {
            FriendlyByteBuf fbb = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            MaidFilePackets.writeIntList(fbb, ids);
            fbb.writeBoolean(removeAfter);
            byte[] out = new byte[fbb.readableBytes()];
            fbb.getBytes(0, out);
            return out;
        }
        public ExportBatchPayload(List<Integer> ids, boolean removeAfter) {
            this(encodeExportBatch(ids, removeAfter));
        }
        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (ctx.player() instanceof ServerPlayer sp) {
                    FriendlyByteBuf fbb = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(encoded));
                    List<Integer> ids = MaidFilePackets.readIntList(fbb);
                    boolean removeAfter = fbb.readBoolean();
                    List<MaidFileData> results = new ArrayList<>(ids.size());
                    int removed = 0;
                    for (Integer entityId : ids) {
                        if (entityId == null) continue;
                        MaidFileData d = MaidTransferService.exportMaidToData(sp, entityId);
                        if (d != null) {
                            results.add(d);
                            if (removeAfter) {
                                var e = sp.level().getEntity(entityId);
                                if (e != null) { e.discard(); removed++; }
                            }
                        }
                    }
                    PacketDistributor.sendToPlayer(sp, new ExportBatchResultPayload(results));
                    Constants.LOG.info("[maid_file_manager] EXPORT_BATCH handled: ids={}, sent={}, removed={}", ids.size(), results.size(), removed);
                }
            });
        }
    }

    public record ImportBatchPayload(byte[] encoded, boolean keepBaubles) implements CustomPacketPayload {
        public static final Type<ImportBatchPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "import_batch"));
        public static final StreamCodec<ByteBuf, ImportBatchPayload> STREAM_CODEC =
                StreamCodec.ofMember(
                        (p, buf) -> { FriendlyByteBuf fbb = new FriendlyByteBuf(buf); fbb.writeVarInt(p.encoded.length); fbb.writeBytes(p.encoded); fbb.writeBoolean(p.keepBaubles); },
                        buf -> { FriendlyByteBuf fbb = new FriendlyByteBuf(buf); int len = fbb.readVarInt(); byte[] arr = new byte[len]; fbb.readBytes(arr); boolean keepBaubles = fbb.readBoolean(); return new ImportBatchPayload(arr, keepBaubles); }
                );
        private static byte[] encodeImportBatch(List<MaidFileData> dataList) {
            FriendlyByteBuf fbb = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            MaidFilePackets.writeMaidFileDataList(fbb, dataList);
            byte[] out = new byte[fbb.readableBytes()];
            fbb.getBytes(0, out);
            return out;
        }
        public ImportBatchPayload(List<MaidFileData> dataList, boolean keepBaubles) {
            this(encodeImportBatch(dataList), keepBaubles);
        }
        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (ctx.player() instanceof ServerPlayer sp) {
                    FriendlyByteBuf fbb = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(encoded));
                    List<MaidFileData> list = MaidFilePackets.readMaidFileDataList(fbb);
                    int ok = 0, fail = 0;
                    boolean serverBlocked = false;
                    for (MaidFileData d : list) {
                        if (d == null) { fail++; continue; }
                        Component fb = MaidTransferService.importMaidFromData(sp, d, keepBaubles);
                        String s = fb == null ? "" : fb.getString();
                        if (s.contains("成功")) ok++;
                        else {
                            fail++;
                            // 明确写明被服务端策略拦截的原因（非静默失败）
                            if (s.contains("禁止导入")) serverBlocked = true;
                        }
                    }
                    StringBuilder summaryText = new StringBuilder(String.format(java.util.Locale.ROOT,
                            "批量导入完成：成功 %d 个，失败 %d 个", ok, fail));
                    if (serverBlocked) {
                        summaryText.append("。失败原因：服务器已禁止导入女仆（管理员在服务端设置中关闭了「允许客户端导入女仆」）");
                    }
                    Component summary = Component.literal(summaryText.toString());
                    PacketDistributor.sendToPlayer(sp, new FeedbackPayload(summary));
                    Constants.LOG.info("[maid_file_manager] IMPORT_BATCH handled: count={}, ok={}, fail={}, keepBaubles={}", list.size(), ok, fail, keepBaubles);
                }
            });
        }
    }

    public record ClientConsentPayload(boolean allow) implements CustomPacketPayload {
        public static final Type<ClientConsentPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "client_consent"));
        public static final StreamCodec<ByteBuf, ClientConsentPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.BOOL, ClientConsentPayload::allow,
                        ClientConsentPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (ctx.player() instanceof ServerPlayer sp) {
                    MaidConfigManager.handleClientConsent(sp.getUUID(), allow);
                    Constants.LOG.info("[maid_file_manager] CLIENT_CONSENT: player={} allow={}", sp.getName().getString(), allow);
                }
            });
        }
    }

    /** OP 统一导出：请求服务端收集所有在线玩家（以各玩家为中心）的女仆列表 */
    public record RequestServerExportListPayload() implements CustomPacketPayload {
        public static final Type<RequestServerExportListPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "request_server_export_list"));
        public static final StreamCodec<ByteBuf, RequestServerExportListPayload> STREAM_CODEC =
                StreamCodec.unit(new RequestServerExportListPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (ctx.player() instanceof ServerPlayer sp) {
                    if (!sp.hasPermissions(2)) {
                        PacketDistributor.sendToPlayer(sp, new FeedbackPayload(
                                Component.literal("[女仆文件管理] 统一导出仅 OP 可用")));
                        return;
                    }
                    var groups = com.example.maid_file_manager.service.MaidServerCommands
                            .collectOnlinePlayerMaids(sp.server);
                    PacketDistributor.sendToPlayer(sp, new ServerExportListPayload(groups));
                    Constants.LOG.info("[maid_file_manager] SERVER_EXPORT_LIST sent to OP {}: {} players",
                            sp.getName().getString(), groups.size());
                }
            });
        }
    }

    /** OP 统一导出提交：按玩家分组导出女仆到服务端磁盘 maid_exports/<玩家名>/ */
    public record ServerExportBatchPayload(byte[] encoded) implements CustomPacketPayload {
        public static final Type<ServerExportBatchPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "server_export_batch"));
        public static final StreamCodec<ByteBuf, ServerExportBatchPayload> STREAM_CODEC =
                StreamCodec.ofMember(
                        (p, buf) -> { FriendlyByteBuf fbb = new FriendlyByteBuf(buf); fbb.writeVarInt(p.encoded.length); fbb.writeBytes(p.encoded); },
                        buf -> { FriendlyByteBuf fbb = new FriendlyByteBuf(buf); int len = fbb.readVarInt(); byte[] arr = new byte[len]; fbb.readBytes(arr); return new ServerExportBatchPayload(arr); }
                );
        private static byte[] encodeRequests(List<IMaidFileNetwork.PlayerExportRequest> groups) {
            FriendlyByteBuf fbb = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            MaidFilePackets.writePlayerExportRequests(fbb, groups);
            byte[] out = new byte[fbb.readableBytes()];
            fbb.getBytes(0, out);
            return out;
        }
        public ServerExportBatchPayload(List<IMaidFileNetwork.PlayerExportRequest> groups) {
            this(encodeRequests(groups));
        }
        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (ctx.player() instanceof ServerPlayer sp) {
                    if (!sp.hasPermissions(2)) {
                        PacketDistributor.sendToPlayer(sp, new FeedbackPayload(
                                Component.literal("[女仆文件管理] 统一导出仅 OP 可用")));
                        return;
                    }
                    FriendlyByteBuf fbb = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(encoded));
                    List<IMaidFileNetwork.PlayerExportRequest> requests = MaidFilePackets.readPlayerExportRequests(fbb);
                    Component result = com.example.maid_file_manager.service.MaidServerCommands
                            .exportForPlayers(sp.server, requests);
                    PacketDistributor.sendToPlayer(sp, new FeedbackPayload(result));
                    Constants.LOG.info("[maid_file_manager] SERVER_EXPORT_BATCH by OP {}: {}",
                            sp.getName().getString(), result.getString());
                }
            });
        }
    }

    public record SetServerConfigPayload(String key, boolean value) implements CustomPacketPayload {
        public static final Type<SetServerConfigPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "set_server_config"));
        public static final StreamCodec<ByteBuf, SetServerConfigPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.stringUtf8(128), SetServerConfigPayload::key,
                        ByteBufCodecs.BOOL, SetServerConfigPayload::value,
                        SetServerConfigPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (ctx.player() instanceof ServerPlayer sp) {
                    // 服务端配置仅 OP 可改（局域网联机=宿主默认 OP）
                    if (!sp.hasPermissions(2)) {
                        Constants.LOG.warn("[maid_file_manager] SET_SERVER_CONFIG rejected (no OP): player={} key={}",
                                sp.getName().getString(), key);
                        PacketDistributor.sendToPlayer(sp, new FeedbackPayload(
                                Component.translatable("maid_file_manager.config.fail.no_permission")));
                        return;
                    }
                    MaidConfigManager.setServerConfig(key, value);
                    Constants.LOG.info("[maid_file_manager] SET_SERVER_CONFIG: player={} key={} value={}",
                            sp.getName().getString(), key, value);
                    broadcastServerConfig(sp.server);
                }
            });
        }
    }

    // ================ S2C 包（服务端 -> 客户端） ================

    /** 服务端配置同步（登录时推送 + 修改后广播）；客户端收到后更新缓存并回发同意状态 */
    public record ServerConfigSyncPayload(boolean allowImport, boolean allowBaubles) implements CustomPacketPayload {
        public static final Type<ServerConfigSyncPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "server_config_sync"));
        public static final StreamCodec<ByteBuf, ServerConfigSyncPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.BOOL, ServerConfigSyncPayload::allowImport,
                        ByteBufCodecs.BOOL, ServerConfigSyncPayload::allowBaubles,
                        ServerConfigSyncPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> MaidConfigManager.handleServerConfigSync(allowImport, allowBaubles));
        }
    }

    /** 把服务端配置同步给所有在线玩家（登录时/修改后广播） */
    public static void broadcastServerConfig(MinecraftServer server) {
        if (server == null) {
            return;
        }
        boolean allowImport = MaidConfigManager.isClientImportAllowed();
        boolean allowBaubles = MaidConfigManager.isBaublesAllowed();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(p, new ServerConfigSyncPayload(allowImport, allowBaubles));
        }
    }

    public record MaidListPayload(java.util.List<com.example.maid_file_manager.data.MaidInfo> list)
            implements CustomPacketPayload {
        public static final Type<MaidListPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "maid_list"));
        public static final StreamCodec<ByteBuf, MaidListPayload> STREAM_CODEC =
                StreamCodec.ofMember(
                        (payload, buf) -> MaidFilePackets.writeMaidInfoList(new FriendlyByteBuf(buf), payload.list),
                        buf -> new MaidListPayload(MaidFilePackets.readMaidInfoList(new FriendlyByteBuf(buf)))
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                var handler = IMaidFileNetwork.ClientHandlerHolder.get();
                if (handler != null) handler.onMaidListReceived(list);
            });
        }
    }

    public record ExportResultPayload(byte[] bytes) implements CustomPacketPayload {
        public static final Type<ExportResultPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "export_result"));
        public static final StreamCodec<ByteBuf, ExportResultPayload> STREAM_CODEC =
                StreamCodec.ofMember(
                        (p, buf) -> {
                            FriendlyByteBuf fbb = new FriendlyByteBuf(buf);
                            fbb.writeVarInt(p.bytes.length);
                            fbb.writeBytes(p.bytes);
                        },
                        buf -> {
                            FriendlyByteBuf fbb = new FriendlyByteBuf(buf);
                            int len = fbb.readVarInt();
                            byte[] arr = new byte[len];
                            fbb.readBytes(arr);
                            return new ExportResultPayload(arr);
                        }
                );

        public ExportResultPayload(MaidFileData data) {
            this(MaidFilePackets.serializeMaidFileData(data));
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                var handler = IMaidFileNetwork.ClientHandlerHolder.get();
                if (handler != null) {
                    MaidFileData data = MaidFilePackets.deserializeMaidFileData(bytes);
                    handler.onExportResultReceived(data == null ? new ArrayList<>() : Collections.singletonList(data));
                }
            });
        }
    }

    public record ExportBatchResultPayload(byte[] encoded) implements CustomPacketPayload {
        public static final Type<ExportBatchResultPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "export_batch_result"));
        public static final StreamCodec<ByteBuf, ExportBatchResultPayload> STREAM_CODEC =
                StreamCodec.ofMember(
                        (p, buf) -> { FriendlyByteBuf fbb = new FriendlyByteBuf(buf); fbb.writeVarInt(p.encoded.length); fbb.writeBytes(p.encoded); },
                        buf -> { FriendlyByteBuf fbb = new FriendlyByteBuf(buf); int len = fbb.readVarInt(); byte[] arr = new byte[len]; fbb.readBytes(arr); return new ExportBatchResultPayload(arr); }
                );
        private static byte[] encodeExportBatchResult(List<MaidFileData> dataList) {
            FriendlyByteBuf fbb = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            MaidFilePackets.writeMaidFileDataList(fbb, dataList);
            byte[] out = new byte[fbb.readableBytes()];
            fbb.getBytes(0, out);
            return out;
        }
        public ExportBatchResultPayload(List<MaidFileData> dataList) {
            this(encodeExportBatchResult(dataList));
        }
        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                var handler = IMaidFileNetwork.ClientHandlerHolder.get();
                if (handler != null) {
                    FriendlyByteBuf fbb = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(encoded));
                    List<MaidFileData> list = MaidFilePackets.readMaidFileDataList(fbb);
                    handler.onExportResultReceived(list);
                }
            });
        }
    }

    public record FeedbackPayload(Component message)
            implements CustomPacketPayload {
        public static final Type<FeedbackPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "feedback"));
        private static final RegistryAccess.Frozen EMPTY = RegistryAccess.EMPTY;
        private static final StreamCodec<ByteBuf, Component> COMPONENT_STREAM_CODEC = StreamCodec.ofMember(
                (component, buf) -> {
                    String json = Component.Serializer.toJson(component, EMPTY);
                    new FriendlyByteBuf(buf).writeUtf(json, 32767);
                },
                buf -> {
                    String json = new FriendlyByteBuf(buf).readUtf(32767);
                    return Component.Serializer.fromJson(json, EMPTY);
                }
        );
        public static final StreamCodec<ByteBuf, FeedbackPayload> STREAM_CODEC =
                StreamCodec.composite(
                        COMPONENT_STREAM_CODEC, FeedbackPayload::message,
                        FeedbackPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                var handler = IMaidFileNetwork.ClientHandlerHolder.get();
                if (handler != null) handler.onFeedbackReceived(message);
            });
        }
    }

    /** OP 统一导出：服务端返回的分组女仆列表（每项 = 玩家名 + 同意状态 + 女仆列表） */
    public record ServerExportListPayload(byte[] encoded) implements CustomPacketPayload {
        public static final Type<ServerExportListPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "server_export_list"));
        public static final StreamCodec<ByteBuf, ServerExportListPayload> STREAM_CODEC =
                StreamCodec.ofMember(
                        (p, buf) -> { FriendlyByteBuf fbb = new FriendlyByteBuf(buf); fbb.writeVarInt(p.encoded.length); fbb.writeBytes(p.encoded); },
                        buf -> { FriendlyByteBuf fbb = new FriendlyByteBuf(buf); int len = fbb.readVarInt(); byte[] arr = new byte[len]; fbb.readBytes(arr); return new ServerExportListPayload(arr); }
                );
        private static byte[] encodeGroups(List<IMaidFileNetwork.PlayerMaidGroup> groups) {
            FriendlyByteBuf fbb = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            MaidFilePackets.writePlayerMaidGroups(fbb, groups);
            byte[] out = new byte[fbb.readableBytes()];
            fbb.getBytes(0, out);
            return out;
        }
        public ServerExportListPayload(List<IMaidFileNetwork.PlayerMaidGroup> groups) {
            this(encodeGroups(groups));
        }
        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                var handler = IMaidFileNetwork.ClientHandlerHolder.get();
                if (handler != null) {
                    FriendlyByteBuf fbb = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(encoded));
                    handler.onServerExportListReceived(MaidFilePackets.readPlayerMaidGroups(fbb));
                }
            });
        }
    }
}
