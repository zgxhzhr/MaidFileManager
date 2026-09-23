package io.github.zgxhzhr.maidfm.network;

import io.github.zgxhzhr.maidfm.Constants;
import io.github.zgxhzhr.maidfm.config.MaidConfigManager;
import io.github.zgxhzhr.maidfm.data.ImportResult;
import io.github.zgxhzhr.maidfm.data.MaidFileData;
import io.github.zgxhzhr.maidfm.service.MaidTransferService;
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
import java.util.List;

/**
 * NeoForge 1.21+ 自定义负载集合。
 *
 * <p>约定：
 * <ul>
 *   <li>所有 C2S 处理器内部 try/catch 兜底，异常转 FEEDBACK 回执（非静默失败）；</li>
 *   <li>批量成败只认 {@link ImportResult.State} 枚举，由
 *       {@link MaidTransferService#buildBatchSummary(List)} 统一汇总，严禁反解中文文案；</li>
 *   <li>字节数组解码必须过 {@link MaidFilePackets#checkSize} 上限校验，防恶意 VarInt OOM；</li>
 *   <li>导出数据发送前按 {@link MaidFilePackets#MAX_PACKET_BYTES} 预检，超限只回执失败原因。</li>
 * </ul>
 */
public final class MaidFilePayloads {

    private MaidFilePayloads() {
    }

    /** 向玩家回执一条反馈 */
    private static void feedback(ServerPlayer sp, Component message) {
        PacketDistributor.sendToPlayer(sp, new FeedbackPayload(message));
    }

    /** 批量导入 + 删除源文件：汇总文案 + 逐项 spawned 标志（顺序与请求对齐） */
    private static void sendImportBatchResult(ServerPlayer sp, Component summary, List<Boolean> spawned) {
        PacketDistributor.sendToPlayer(sp, new ImportBatchResultPayload(summary, spawned));
    }

    /**
     * 发送导出数据回客户端写文件。
     * 发送前按客户端解码端全部契约维度（512 条目/单文件 512 KiB/整包 1 MiB 自律红线）预检，
     * 序列化字节在预检与实际发包间复用；任一维度不过只回执明确失败并返回 false，
     * 调用方（尤其 removeAfter 路径）据此保证实体绝不被 discard。
     *
     * @return true=已发送；false=序列化失败或契约预检被拒（已回执原因，调用方不得删除实体）
     */
    private static boolean sendExportResults(ServerPlayer sp, List<MaidFileData> results) {
        List<byte[]> blobs = MaidFilePackets.serializeMaidDataBatch(results);
        if (blobs == null) {
            Constants.LOG.error("[maid_file_manager] 导出结果序列化失败，拒绝发送: player={}",
                    sp.getName().getString());
            feedback(sp, Component.literal("[女仆文件管理] 导出数据序列化失败，请重试；如反复失败请联系服主查看日志"));
            return false;
        }
        String reject = MaidFilePackets.checkMaidDataBatchForWire(blobs, MaidFilePackets.MAX_EXPORT_IDS);
        if (reject != null) {
            Constants.LOG.warn("[maid_file_manager] 导出结果预检被拒绝: player={}, count={}, reason={}",
                    sp.getName().getString(), blobs.size(), reject);
            feedback(sp, Component.literal("[女仆文件管理] " + reject));
            return false;
        }
        PacketDistributor.sendToPlayer(sp, new ExportBatchResultPayload(blobs));
        return true;
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
                    try {
                        var list = MaidTransferService.listOwnMaids(sp);
                        PacketDistributor.sendToPlayer(sp, new MaidListPayload(list));
                    } catch (Throwable t) {
                        handleError(sp, "REQUEST_MAID_LIST", t);
                    }
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
                    try {
                        MaidFileData data = MaidTransferService.exportMaidToData(sp, entityId);
                        if (data == null) {
                            feedback(sp, Component.translatable("maid_file_manager.export.fail.unknown"));
                        } else {
                            sendExportResults(sp, List.of(data));
                        }
                    } catch (Throwable t) {
                        handleError(sp, "EXPORT_MAID", t);
                    }
                }
            });
        }
    }

    public record ImportFilePayload(byte[] bytes, boolean keepBaubles, boolean deleteAfterImport) implements CustomPacketPayload {
        public static final Type<ImportFilePayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "import_file"));
        public static final StreamCodec<ByteBuf, ImportFilePayload> STREAM_CODEC =
                StreamCodec.ofMember(
                        (p, buf) -> {
                            FriendlyByteBuf fbb = new FriendlyByteBuf(buf);
                            fbb.writeVarInt(p.bytes.length);
                            fbb.writeBytes(p.bytes);
                            fbb.writeBoolean(p.keepBaubles);
                            fbb.writeBoolean(p.deleteAfterImport);
                        },
                        buf -> {
                            FriendlyByteBuf fbb = new FriendlyByteBuf(buf);
                            int len = MaidFilePackets.checkSize(fbb.readVarInt(),
                                    MaidFilePackets.MAX_SINGLE_FILE_BYTES, "import_file");
                            byte[] arr = new byte[len];
                            fbb.readBytes(arr);
                            boolean keepBaubles = fbb.readBoolean();
                            boolean deleteAfterImport = fbb.readBoolean();
                            return new ImportFilePayload(arr, keepBaubles, deleteAfterImport);
                        }
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (ctx.player() instanceof ServerPlayer sp) {
                    try {
                        MaidFileData data = MaidFilePackets.deserializeMaidFileData(bytes);
                        if (data == null) {
                            feedback(sp, Component.translatable(
                                    "maid_file_manager.import.fail.invalid"));
                            return;
                        }
                        ImportResult result = MaidTransferService.importMaidFromData(sp, data, keepBaubles);
                        feedback(sp, result.message());
                    } catch (Throwable t) {
                        handleError(sp, "IMPORT_FILE", t);
                    }
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
                        buf -> {
                            FriendlyByteBuf fbb = new FriendlyByteBuf(buf);
                            int len = MaidFilePackets.checkSize(fbb.readVarInt(),
                                    MaidFilePackets.MAX_PACKET_BYTES, "export_batch");
                            byte[] arr = new byte[len];
                            fbb.readBytes(arr);
                            return new ExportBatchPayload(arr);
                        }
                );
        private static byte[] encodeExportBatch(List<Integer> ids, boolean removeAfter) {
            FriendlyByteBuf fbb = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try {
                MaidFilePackets.writeIntList(fbb, ids);
                fbb.writeBoolean(removeAfter);
                byte[] out = new byte[fbb.readableBytes()];
                fbb.getBytes(0, out);
                return out;
            } finally {
                // 堆缓冲虽由 GC 兜底，仍统一显式释放，避免异常路径引用滞留
                fbb.release();
            }
        }
        public ExportBatchPayload(List<Integer> ids, boolean removeAfter) {
            this(encodeExportBatch(ids, removeAfter));
        }
        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (ctx.player() instanceof ServerPlayer sp) {
                    try {
                        FriendlyByteBuf fbb = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(encoded));
                        List<Integer> ids = MaidFilePackets.readIntList(fbb);
                        boolean removeAfter = fbb.readBoolean();
                        // 两阶段处理：先完成全部序列化并通过体积预检、确认结果包已发送，之后才允许删除实体。
                        // 若边导出边删除，预检失败时女仆已被 discard 而文件未送达，构成不可逆数据丢失
                        List<MaidFileData> results = new ArrayList<>(ids.size());
                        List<Integer> exportedIds = new ArrayList<>(ids.size());
                        for (Integer entityId : ids) {
                            if (entityId == null) continue;
                            MaidFileData d = MaidTransferService.exportMaidToData(sp, entityId);
                            if (d != null) {
                                results.add(d);
                                exportedIds.add(entityId);
                            }
                        }
                        if (sendExportResults(sp, results)) {
                            int removed = 0;
                            if (removeAfter) {
                                for (Integer entityId : exportedIds) {
                                    var e = sp.level().getEntity(entityId);
                                    if (e != null) {
                                        e.discard();
                                        removed++;
                                    }
                                }
                            }
                            Constants.LOG.debug("[maid_file_manager] EXPORT_BATCH: player={} sent={} removed={}",
                                    sp.getName().getString(), results.size(), removed);
                        }
                    } catch (Throwable t) {
                        handleError(sp, "EXPORT_BATCH", t);
                    }
                }
            });
        }
    }

    public record ImportBatchPayload(byte[] encoded, boolean keepBaubles, boolean deleteAfterImport) implements CustomPacketPayload {
        public static final Type<ImportBatchPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "import_batch"));
        public static final StreamCodec<ByteBuf, ImportBatchPayload> STREAM_CODEC =
                StreamCodec.ofMember(
                        (p, buf) -> { FriendlyByteBuf fbb = new FriendlyByteBuf(buf); fbb.writeVarInt(p.encoded.length); fbb.writeBytes(p.encoded); fbb.writeBoolean(p.keepBaubles); fbb.writeBoolean(p.deleteAfterImport); },
                        buf -> {
                            FriendlyByteBuf fbb = new FriendlyByteBuf(buf);
                            int len = MaidFilePackets.checkSize(fbb.readVarInt(),
                                    MaidFilePackets.MAX_PACKET_BYTES, "import_batch");
                            byte[] arr = new byte[len];
                            fbb.readBytes(arr);
                            boolean keepBaubles = fbb.readBoolean();
                            boolean deleteAfterImport = fbb.readBoolean();
                            return new ImportBatchPayload(arr, keepBaubles, deleteAfterImport);
                        }
                );
        private static byte[] encodeImportBatch(List<MaidFileData> dataList) {
            FriendlyByteBuf fbb = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try {
                MaidFilePackets.writeMaidFileDataList(fbb, dataList);
                byte[] out = new byte[fbb.readableBytes()];
                fbb.getBytes(0, out);
                return out;
            } finally {
                fbb.release();
            }
        }
        public ImportBatchPayload(List<MaidFileData> dataList, boolean keepBaubles, boolean deleteAfterImport) {
            this(encodeImportBatch(dataList), keepBaubles, deleteAfterImport);
        }
        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (ctx.player() instanceof ServerPlayer sp) {
                    try {
                        FriendlyByteBuf fbb = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(encoded));
                        List<MaidFileData> list = MaidFilePackets.readMaidFileDataList(fbb);
                        // 成败统计只认 ImportResult.State 枚举，严禁反解中文展示文案
                        List<ImportResult> results = new ArrayList<>(list.size());
                        for (MaidFileData d : list) {
                            if (d == null) {
                                results.add(ImportResult.failed(Component.translatable(
                                        "maid_file_manager.import.fail.invalid")));
                                continue;
                            }
                            results.add(MaidTransferService.importMaidFromData(sp, d, keepBaubles));
                        }
                        Component summary = MaidTransferService.buildBatchSummary(results);
                        if (deleteAfterImport) {
                            // 请求删除源文件：回传逐项 spawned 标志（与请求顺序对齐），客户端只删成功的文件
                            List<Boolean> spawned = new ArrayList<>(results.size());
                            for (ImportResult r : results) {
                                spawned.add(r.spawned());
                            }
                            sendImportBatchResult(sp, summary, spawned);
                        } else {
                            feedback(sp, summary);
                        }
                    } catch (Throwable t) {
                        handleError(sp, "IMPORT_BATCH", t);
                    }
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
                    try {
                        MaidConfigManager.handleClientConsent(sp.getUUID(), allow);
                    } catch (Throwable t) {
                        handleError(sp, "CLIENT_CONSENT", t);
                    }
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
                    try {
                        if (!sp.hasPermissions(2)) {
                            feedback(sp, Component.literal("[女仆文件管理] 统一导出仅 OP 可用"));
                            return;
                        }
                        var groups = io.github.zgxhzhr.maidfm.service.MaidServerCommands
                                .collectOnlinePlayerMaids(sp.server);
                        // 组数维度预检：接收端 readPlayerMaidGroups 硬上限 128，必须在分配 probe 前拒绝，
                        // 否则数千在线时会先按真实条目分配 GB 级缓冲，且坏包必然在客户端解码端断连
                        if (groups.size() > MaidFilePackets.MAX_PLAYER_GROUPS) {
                            feedback(sp, Component.literal(String.format(java.util.Locale.ROOT,
                                    "在线玩家数超过统一导出上限（%d > %d），无法生成列表，请缩小范围后重试",
                                    groups.size(), MaidFilePackets.MAX_PLAYER_GROUPS)));
                            return;
                        }
                        // 发送前按真实线格式预检总字节：全服女仆聚合可能远超单包上限，
                        // 超限必须回执明确提示而非发出坏包把 OP 客户端断连
                        FriendlyByteBuf probe =
                                new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                        try {
                            MaidFilePackets.writePlayerMaidGroups(probe, groups);
                            int size = probe.readableBytes();
                            if (size > MaidFilePackets.MAX_PACKET_BYTES) {
                                Constants.LOG.warn("[maid_file_manager] 统一导出列表 {} 字节超过单包上限 {}，拒绝发送",
                                        size, MaidFilePackets.MAX_PACKET_BYTES);
                                feedback(sp, Component.literal(String.format(java.util.Locale.ROOT,
                                        "全服女仆列表数据过大（%d KB > %d KB），请缩小查询范围后重试",
                                        size / 1024, MaidFilePackets.MAX_PACKET_BYTES / 1024)));
                                return;
                            }
                        } finally {
                            probe.release();
                        }
                        PacketDistributor.sendToPlayer(sp, new ServerExportListPayload(groups));
                    } catch (Throwable t) {
                        handleError(sp, "REQUEST_SERVER_EXPORT_LIST", t);
                    }
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
                        buf -> {
                            FriendlyByteBuf fbb = new FriendlyByteBuf(buf);
                            int len = MaidFilePackets.checkSize(fbb.readVarInt(),
                                    MaidFilePackets.MAX_PACKET_BYTES, "server_export_batch");
                            byte[] arr = new byte[len];
                            fbb.readBytes(arr);
                            return new ServerExportBatchPayload(arr);
                        }
                );
        private static byte[] encodeRequests(List<IMaidFileNetwork.PlayerExportRequest> groups) {
            FriendlyByteBuf fbb = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try {
                MaidFilePackets.writePlayerExportRequests(fbb, groups);
                byte[] out = new byte[fbb.readableBytes()];
                fbb.getBytes(0, out);
                return out;
            } finally {
                fbb.release();
            }
        }
        public ServerExportBatchPayload(List<IMaidFileNetwork.PlayerExportRequest> groups) {
            this(encodeRequests(groups));
        }
        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (ctx.player() instanceof ServerPlayer sp) {
                    try {
                        if (!sp.hasPermissions(2)) {
                            feedback(sp, Component.literal("[女仆文件管理] 统一导出仅 OP 可用"));
                            return;
                        }
                        FriendlyByteBuf fbb = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(encoded));
                        List<IMaidFileNetwork.PlayerExportRequest> requests = MaidFilePackets.readPlayerExportRequests(fbb);
                        Component result = io.github.zgxhzhr.maidfm.service.MaidServerCommands
                                .exportForPlayers(sp.server, requests);
                        feedback(sp, result);
                    } catch (Throwable t) {
                        handleError(sp, "SERVER_EXPORT_BATCH", t);
                    }
                }
            });
        }
    }

    public record SetServerConfigPayload(String key, boolean value) implements CustomPacketPayload {
        public static final Type<SetServerConfigPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "set_server_config"));
        public static final StreamCodec<ByteBuf, SetServerConfigPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.stringUtf8(MaidFilePackets.MAX_TEXT_LEN), SetServerConfigPayload::key,
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
                    try {
                        // 服务端配置仅 OP 可改（局域网联机=宿主默认 OP）
                        if (!sp.hasPermissions(2)) {
                            feedback(sp, Component.translatable("maid_file_manager.config.fail.no_permission"));
                            return;
                        }
                        // 未知键直接回执失败，不得把旧配置向全服重新广播
                        if (!MaidConfigManager.setServerConfig(key, value)) {
                            feedback(sp, Component.literal("[女仆文件管理] 未知配置项，修改已拒绝"));
                            return;
                        }
                        broadcastServerConfig(sp.server);
                    } catch (Throwable t) {
                        handleError(sp, "SET_SERVER_CONFIG", t);
                    }
                }
            });
        }
    }

    // ================ S2C 包（服务端 -> 客户端） ================

    /** 服务端配置同步（登录时单播 + 修改后全服同步）；客户端收到后更新缓存并回发同意状态 */
    public record ServerConfigSyncPayload(boolean allowImport, boolean allowBaubles, boolean allowAdvancements, boolean allowEffects) implements CustomPacketPayload {
        public static final Type<ServerConfigSyncPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "server_config_sync"));
        public static final StreamCodec<ByteBuf, ServerConfigSyncPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.BOOL, ServerConfigSyncPayload::allowImport,
                        ByteBufCodecs.BOOL, ServerConfigSyncPayload::allowBaubles,
                        ByteBufCodecs.BOOL, ServerConfigSyncPayload::allowAdvancements,
                        ByteBufCodecs.BOOL, ServerConfigSyncPayload::allowEffects,
                        ServerConfigSyncPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> MaidConfigManager.handleServerConfigSync(allowImport, allowBaubles, allowAdvancements, allowEffects));
        }
    }

    /** 把服务端配置单播给指定玩家（登录同步用） */
    public static void sendServerConfig(ServerPlayer player) {
        if (player != null) {
            PacketDistributor.sendToPlayer(player, new ServerConfigSyncPayload(
                    MaidConfigManager.isClientImportAllowed(),
                    MaidConfigManager.isBaublesAllowed(),
                    MaidConfigManager.isAdvancementsAllowed(),
                    MaidConfigManager.isEffectsAllowed()));
        }
    }

    /** 把服务端配置同步给所有在线玩家（OP 改配置后用） */
    public static void broadcastServerConfig(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            sendServerConfig(p);
        }
    }

    public record MaidListPayload(java.util.List<io.github.zgxhzhr.maidfm.data.MaidInfo> list)
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

    public record ExportBatchResultPayload(byte[] encoded) implements CustomPacketPayload {
        public static final Type<ExportBatchResultPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "export_batch_result"));
        public static final StreamCodec<ByteBuf, ExportBatchResultPayload> STREAM_CODEC =
                StreamCodec.ofMember(
                        (p, buf) -> { FriendlyByteBuf fbb = new FriendlyByteBuf(buf); fbb.writeVarInt(p.encoded.length); fbb.writeBytes(p.encoded); },
                        buf -> {
                            FriendlyByteBuf fbb = new FriendlyByteBuf(buf);
                            int len = MaidFilePackets.checkSize(fbb.readVarInt(),
                                    MaidFilePackets.MAX_PACKET_BYTES, "export_batch_result");
                            byte[] arr = new byte[len];
                            fbb.readBytes(arr);
                            return new ExportBatchResultPayload(arr);
                        }
                );
        private static byte[] encodeExportBatchResult(List<byte[]> blobs) {
            // 直接复用发送端已序列化好的 GZIP 字节，避免二次压缩
            FriendlyByteBuf fbb = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try {
                MaidFilePackets.writeMaidDataBlobs(fbb, blobs);
                byte[] out = new byte[fbb.readableBytes()];
                fbb.getBytes(0, out);
                return out;
            } finally {
                fbb.release();
            }
        }
        public ExportBatchResultPayload(List<byte[]> blobs) {
            this(encodeExportBatchResult(blobs));
        }
        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                var handler = IMaidFileNetwork.ClientHandlerHolder.get();
                if (handler != null) {
                    FriendlyByteBuf fbb = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(encoded));
                    // 条目上限与服务端导出请求侧 MAX_EXPORT_IDS(512) 对齐，不能沿用导入通道的 64
                    List<MaidFileData> list = MaidFilePackets.readMaidFileDataList(fbb, MaidFilePackets.MAX_EXPORT_IDS);
                    handler.onExportResultReceived(list);
                }
            });
        }
    }

    public record FeedbackPayload(Component message)
            implements CustomPacketPayload {
        public static final Type<FeedbackPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "feedback"));
        private static final RegistryAccess EMPTY_REGISTRY = RegistryAccess.EMPTY;
        private static final StreamCodec<ByteBuf, Component> COMPONENT_STREAM_CODEC = StreamCodec.ofMember(
                (component, buf) -> {
                    String json = Component.Serializer.toJson(component, EMPTY_REGISTRY);
                    new FriendlyByteBuf(buf).writeUtf(json, 32767);
                },
                buf -> {
                    String json = new FriendlyByteBuf(buf).readUtf(32767);
                    // 损坏 JSON 时 fromJson 返回 null，归一为空文案，避免下游 NPE
                    Component parsed = Component.Serializer.fromJson(json, EMPTY_REGISTRY);
                    return parsed != null ? parsed : Component.empty();
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
                if (handler != null) handler.onFeedbackReceived(message != null ? message : Component.empty());
            });
        }
    }

    /** 批量导入 + 删除源文件：汇总文案 + 逐项 spawned 标志（客户端只删除成功导入的本地文件） */
    public record ImportBatchResultPayload(Component summary, List<Boolean> spawned)
            implements CustomPacketPayload {
        public static final Type<ImportBatchResultPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "import_batch_result"));
        private static final RegistryAccess EMPTY_REGISTRY = RegistryAccess.EMPTY;
        private static final StreamCodec<ByteBuf, Component> COMPONENT_STREAM_CODEC = StreamCodec.ofMember(
                (component, buf) -> {
                    String json = Component.Serializer.toJson(component, EMPTY_REGISTRY);
                    new FriendlyByteBuf(buf).writeUtf(json, 32767);
                },
                buf -> {
                    String json = new FriendlyByteBuf(buf).readUtf(32767);
                    // 损坏 JSON 时 fromJson 返回 null，归一为空文案，避免下游 NPE
                    Component parsed = Component.Serializer.fromJson(json, EMPTY_REGISTRY);
                    return parsed != null ? parsed : Component.empty();
                }
        );
        private static final StreamCodec<ByteBuf, List<Boolean>> BOOLEAN_LIST_STREAM_CODEC = StreamCodec.ofMember(
                (list, buf) -> MaidFilePackets.writeBooleanList(new FriendlyByteBuf(buf), list),
                buf -> MaidFilePackets.readBooleanList(new FriendlyByteBuf(buf))
        );
        public static final StreamCodec<ByteBuf, ImportBatchResultPayload> STREAM_CODEC =
                StreamCodec.composite(
                        COMPONENT_STREAM_CODEC, ImportBatchResultPayload::summary,
                        BOOLEAN_LIST_STREAM_CODEC, ImportBatchResultPayload::spawned,
                        ImportBatchResultPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                var handler = IMaidFileNetwork.ClientHandlerHolder.get();
                if (handler != null) handler.onImportBatchResultReceived(
                        summary != null ? summary : Component.empty(), spawned);
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
                        buf -> {
                            FriendlyByteBuf fbb = new FriendlyByteBuf(buf);
                            int len = MaidFilePackets.checkSize(fbb.readVarInt(),
                                    MaidFilePackets.MAX_PACKET_BYTES, "server_export_list");
                            byte[] arr = new byte[len];
                            fbb.readBytes(arr);
                            return new ServerExportListPayload(arr);
                        }
                );
        private static byte[] encodeGroups(List<IMaidFileNetwork.PlayerMaidGroup> groups) {
            FriendlyByteBuf fbb = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try {
                MaidFilePackets.writePlayerMaidGroups(fbb, groups);
                byte[] out = new byte[fbb.readableBytes()];
                fbb.getBytes(0, out);
                return out;
            } finally {
                fbb.release();
            }
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

    /**
     * C2S 处理器统一异常兜底：服务端日志保留完整堆栈便于排查；
     * 客户端只回执通用提示，异常原文可能含服务端路径/类名等信息，不得下发
     */
    private static void handleError(ServerPlayer sp, String packetName, Throwable t) {
        Constants.LOG.error("[maid_file_manager] C2S handler 崩溃: packet={}, player={}, cause={}",
                packetName, sp.getName().getString(), t.toString(), t);
        feedback(sp, Component.literal("[女仆文件管理] 服务端处理失败，请联系服主查看日志（错误位置："
                + packetName + "）"));
    }
}
