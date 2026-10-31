package io.github.zgxhzhr.maidfm.network;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import io.github.zgxhzhr.maidfm.Constants;
import io.github.zgxhzhr.maidfm.config.MaidConfigManager;
import io.github.zgxhzhr.maidfm.data.ImportResult;
import io.github.zgxhzhr.maidfm.data.MaidFileData;
import io.github.zgxhzhr.maidfm.data.MaidProfile;
import io.github.zgxhzhr.maidfm.data.MaidProfileView;
import io.github.zgxhzhr.maidfm.service.MaidProfileService;
import io.github.zgxhzhr.maidfm.service.MaidTransferService;
import io.github.zgxhzhr.maidfm.service.BaubleHandler;
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
    /** 发送反馈（实现已收敛到 PacketSender） */
    private static void feedback(ServerPlayer sp, Component message) {
        io.github.zgxhzhr.maidfm.platform.PacketSender.sendFeedback(sp, message);
    }

    /** 批量导入 + 删除源文件：汇总文案 + 逐项 spawned 标志（顺序与请求对齐） */
    /** 发送批量导入结果（实现已收敛到 PacketSender） */
    private static void sendImportBatchResult(ServerPlayer sp, Component summary, List<Boolean> spawned) {
        io.github.zgxhzhr.maidfm.platform.PacketSender.sendImportBatchResult(sp, summary, spawned);
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
        // Curios 饰品裁剪提示：未声明模组的饰品不会随女仆迁移，必须明确告知发起导出的人。
        // curiosDropTip() 取出即清零，同一批次只提示一次。
        net.minecraft.network.chat.Component curiosTip =
                io.github.zgxhzhr.maidfm.service.BaubleHandler.curiosDropTip();
        if (curiosTip != null) {
            feedback(sp, curiosTip);
        }
        List<byte[]> blobs = MaidFilePackets.serializeMaidDataBatch(results);
        if (blobs == null) {
            Constants.LOG.error("[maid_file_manager] 导出结果序列化失败，拒绝发送: player={}",
                    sp.getName().getString());
            feedback(sp, Component.translatable("maid_file_manager.server.serialize_failed"));
            return false;
        }
        // 必须传 allowChunkedFallback=true：下面 :86 起有分片导出路径（超过 24 KiB 走
        // ChunkedDownloadPayload.sendChunks）。用 2 参版本会在 >1 MiB 时直接拒绝，
        // 而 forge/fabric 都是 3 参 true —— 同一操作在 neoforge 上十余只带头像女仆
        // 批量导出就报「超过单包上限」，另两端却能正常走分片。四端口径统一。
        String reject = MaidFilePackets.checkMaidDataBatchForWire(
                blobs, MaidFilePackets.MAX_EXPORT_IDS, true);
        if (reject != null) {
            Constants.LOG.warn("[maid_file_manager] 导出结果预检被拒绝: player={}, count={}, reason={}",
                    sp.getName().getString(), blobs.size(), reject);
            feedback(sp, Component.translatable("maid_file_manager.server.prefix").append(reject));
            return false;
        }
        // 提前按整包体积预检（第六轮 N2）：原先只在 sendChunkedDownload 里判，
        // 那时 blobs + buf + encoded 三份副本都已分配（最坏主线程瞬时占用数倍于负载）。
        long declaredTotal = (long) blobs.size() * 4L;
        for (byte[] oneBlob : blobs) {
            declaredTotal += oneBlob.length;
        }
        if (declaredTotal > io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.MAX_TOTAL_BYTES) {
            Constants.LOG.warn("[maid_file_manager] 导出结果整包超上限，拒绝发送: player={} bytes={}",
                    sp.getName().getString(), declaredTotal);
            feedback(sp, Component.translatable(
                    "maid_file_manager.server.export_too_large",
                    declaredTotal / 1024, io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.MAX_TOTAL_BYTES / 1024));
            return false;
        }
        // 导出侧没有独立上限，但导入侧 MAX_NBT_DECOMPRESSED_BYTES(4 MiB) 是硬闸门：
        // 超过它的档案「导得出、导不回」，这里留一条 WARN 便于排查（第六轮 D4）。
        if (declaredTotal > MaidFilePackets.MAX_NBT_DECOMPRESSED_BYTES) {
            Constants.LOG.warn("[maid_file_manager] 导出数据 {} 字节超过导入侧 4 MiB 上限，该 .maid 可能无法再导入: player={}",
                    declaredTotal, sp.getName().getString());
        }
        byte[] encodedOut = ExportBatchResultPayload.encodeExportBatchResult(blobs);
        if (encodedOut.length > io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.CHUNK_BYTES) {
            if (!ChunkedDownloadPayload.sendChunks(sp,
                    io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.KIND_EXPORT_BATCH, encodedOut)) {
                return false;
            }
        } else {
            PacketDistributor.sendToPlayer(sp, new ExportBatchResultPayload(encodedOut));
        }
        return true;
    }

    // ================ C2S 包（客户端 -> 服务端） ================

    public record RequestMaidListPayload() implements CustomPacketPayload {
        public static final Type<RequestMaidListPayload> TYPE =
                new Type<>(MaidFilePackets.ID_REQUEST_MAID_LIST);
        public static final StreamCodec<ByteBuf, RequestMaidListPayload> STREAM_CODEC =
                StreamCodec.unit(new RequestMaidListPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (ctx.player() instanceof ServerPlayer sp) {
                    // 频次限制：这类请求在主线程同步跑，循环发包能把服务器卡死（不需 OP）
                    if (!io.github.zgxhzhr.maidfm.network.RequestThrottle.allow(sp.getUUID(), "maid_list", 1000L)) {
                        return;
                    }
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
                new Type<>(MaidFilePackets.ID_EXPORT_MAID);
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
                    // 旧单发通道与批量通道做的是同一件重活，批量已加频控，这条不能成为绕过口（第六轮 N3）。
                    if (!io.github.zgxhzhr.maidfm.network.RequestThrottle.allow(sp.getUUID(), "export_maid", 1000L)) {
                        return;
                    }
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

    /** 写字符串列表（数量上限 256，单串上限 256 字符，防畸形包 OOM；公共助手见 MaidFilePackets） */
    private static void writeStringList(FriendlyByteBuf fbb, List<String> list) {
        MaidFilePackets.writeStringList(fbb, list);
    }

    /** 读字符串列表；数量与单串超限一律拒绝（读端自律，坏包走异常断连/回执；公共助手见 MaidFilePackets） */
    private static List<String> readStringList(FriendlyByteBuf fbb) {
        return MaidFilePackets.readStringList(fbb);
    }

    public record ImportFilePayload(byte[] bytes, boolean keepBaubles, boolean deleteAfterImport)
            implements CustomPacketPayload {
        public static final Type<ImportFilePayload> TYPE =
                new Type<>(MaidFilePackets.ID_IMPORT_FILE);
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
                    // 旧单发通道不能成为频控绕过口（第六轮 N3）。
                    if (!io.github.zgxhzhr.maidfm.network.RequestThrottle.allow(sp.getUUID(), "import_file", 1000L)) {
                        return;
                    }
                    try {
                        MaidFileData data = MaidFilePackets.deserializeMaidFileData(bytes);
                        if (data == null) {
                            feedback(sp, Component.translatable(
                                    "maid_file_manager.import.fail.invalid"));
                            return;
                        }
                        // 饰品导入策略由服务端配置决定，客户端不上传，杜绝非 OP 绕过
                        ImportResult result = MaidTransferService.importMaidFromData(sp, data, keepBaubles,
                                MaidConfigManager.isBaubleStripAttributes(),
                                MaidConfigManager.getBaubleBlockedList());
                        // 单条导入同样要消费 Curios 批次统计（第六轮 N5）。
                        BaubleHandler.curiosDropTip();
                        feedback(sp, BaubleHandler.withBaubleDetails(result));
                    } catch (Throwable t) {
                        handleError(sp, "IMPORT_FILE", t);
                    }
                }
            });
        }
    }

    public record ExportBatchPayload(byte[] encoded) implements CustomPacketPayload {
        public static final Type<ExportBatchPayload> TYPE =
                new Type<>(MaidFilePackets.ID_EXPORT_BATCH);
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
                    // 频次限制：写盘/生成实体类 handler，原先只有只读请求有节流
                    if (!io.github.zgxhzhr.maidfm.network.RequestThrottle.allow(sp.getUUID(), "export_batch", 1000L)) {
                        return;
                    }
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
                            // 空批次绝不能登记待删：客户端对空结果**不发 ack**（onExportResultReceived 拿到空表直接返回），
                            // 登记项会一直占到 TTL 过期，期间下一次「导出并移除」被误报 remove_busy、静默降级成只导出（第六轮 N4）。
                            if (removeAfter && !exportedIds.isEmpty()) {
                                // 【P2-10】不再立刻 discard：服务端无法确认客户端写盘成功。
                                // 登记待删，等客户端回执 ID_EXPORT_WRITE_ACK 再删；不回执则永不删除。
                                // 同一玩家同一时刻只允许一笔待删登记。已有登记时不覆盖 —— 覆盖会让「旧回执」
                                // 删除**新批次**的女仆，而新批次文件可能根本没写成功，构成不可逆数据丢失。
                                // putIfAbsent 前先清掉已过 TTL 的陈旧登记：TTL 原先只在 ack 到达时判，
                                // 回执永远不来时该玩家会一直命中 putIfAbsent != null → 「导出并移除」永久失效。
                                io.github.zgxhzhr.maidfm.service.PendingRemovalRegistry.RegisterResult reg =
                                        io.github.zgxhzhr.maidfm.service.PendingRemovalRegistry.register(sp.getUUID(), exportedIds);
                                if (reg == io.github.zgxhzhr.maidfm.service.PendingRemovalRegistry.RegisterResult.BUSY) {
                                    Constants.LOG.warn("[maid_file_manager] 上一笔待删回执未到，本次导出不执行移除: player={}, 本批 {} 个",
                                            sp.getName().getString(), exportedIds.size());
                                    feedback(sp, Component.translatable("maid_file_manager.server.remove_busy"));
                                } else {
                                    if (reg == io.github.zgxhzhr.maidfm.service.PendingRemovalRegistry.RegisterResult.REGISTERED_AFTER_EXPIRY) {
                                        Constants.LOG.warn("[maid_file_manager] 陈旧待删登记已过 TTL，清掉后重建: player={}",
                                                sp.getName().getString());
                                    }
                                    Constants.LOG.info("[maid_file_manager] 导出并移除：登记待删 {} 个实体，等待客户端写盘回执 player={}",
                                            exportedIds.size(), sp.getName().getString());
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

    public record ImportBatchPayload(byte[] encoded, boolean keepBaubles, boolean deleteAfterImport)
            implements CustomPacketPayload {
        public static final Type<ImportBatchPayload> TYPE =
                new Type<>(MaidFilePackets.ID_IMPORT_BATCH);
        public static final StreamCodec<ByteBuf, ImportBatchPayload> STREAM_CODEC =
                StreamCodec.ofMember(
                        (p, buf) -> {
                            FriendlyByteBuf fbb = new FriendlyByteBuf(buf);
                            fbb.writeVarInt(p.encoded.length);
                            fbb.writeBytes(p.encoded);
                            fbb.writeBoolean(p.keepBaubles);
                            fbb.writeBoolean(p.deleteAfterImport);
                        },
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
                    // 频次限制：导入会生成实体并写盘，同 EXPORT_BATCH 一并纳入节流
                    if (!io.github.zgxhzhr.maidfm.network.RequestThrottle.allow(sp.getUUID(), "import_batch", 1000L)) {
                        return;
                    }
                    try {
                        FriendlyByteBuf fbb = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(encoded));
                        List<MaidFileData> list = MaidFilePackets.readMaidFileDataList(fbb);
                        // 饰品导入策略以服务端配置为准（客户端不上传，杜绝非 OP 绕过）；
                        // 禁用清单直接取服务端配置（含整合包 bauble_import.json 的中文名条目），不再做命名空间收窄
                        boolean stripAttributes = MaidConfigManager.isBaubleStripAttributes();
                        List<String> blocked = MaidConfigManager.getBaubleBlockedList();
                        // 成败统计只认 ImportResult.State 枚举，严禁反解中文展示文案
                        List<ImportResult> results = new ArrayList<>(list.size());
                        for (MaidFileData d : list) {
                            if (d == null) {
                                results.add(ImportResult.failed(Component.translatable(
                                        "maid_file_manager.import.fail.invalid")));
                                continue;
                            }
                            results.add(MaidTransferService.importMaidFromData(sp, d, keepBaubles,
                                    stripAttributes, blocked));
                        }
                        // 导入路径同样会产生 Curios 批次统计，必须在每次导入结束时消费掉（第六轮 N5）。
                        BaubleHandler.curiosDropTip();
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
                new Type<>(MaidFilePackets.ID_CLIENT_CONSENT);
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
                new Type<>(MaidFilePackets.ID_REQUEST_SERVER_EXPORT_LIST);
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
                            feedback(sp, Component.translatable("maid_file_manager.server.bulk_export_op_only"));
                            return;
                        }
                        var groups = io.github.zgxhzhr.maidfm.service.MaidServerCommands
                                .collectOnlinePlayerMaids(sp.server);
                        // 组数维度预检：接收端 readPlayerMaidGroups 硬上限 128，必须在分配 probe 前拒绝，
                        // 否则数千在线时会先按真实条目分配 GB 级缓冲，且坏包必然在客户端解码端断连
                        if (groups.size() > MaidFilePackets.MAX_PLAYER_GROUPS) {
                            feedback(sp, Component.translatable("maid_file_manager.server.too_many_players", groups.size(), MaidFilePackets.MAX_PLAYER_GROUPS));
                            return;
                        }
                        // 发送前按真实线格式预检总字节：全服女仆聚合可能远超单包上限，
                        // 超限必须回执明确提示而非发出坏包把 OP 客户端断连
                        FriendlyByteBuf probe =
                                new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                        try {
                            MaidInfoCodec.writePlayerMaidGroups(probe, groups);
                            int size = probe.readableBytes();
                            if (size > MaidFilePackets.MAX_PACKET_BYTES) {
                                Constants.LOG.warn("[maid_file_manager] 统一导出列表 {} 字节超过单包上限 {}，拒绝发送",
                                        size, MaidFilePackets.MAX_PACKET_BYTES);
                                feedback(sp, Component.translatable("maid_file_manager.server.list_too_large", size / 1024, MaidFilePackets.MAX_PACKET_BYTES / 1024));
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
                new Type<>(MaidFilePackets.ID_SERVER_EXPORT_BATCH);
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
                MaidInfoCodec.writePlayerExportRequests(fbb, groups);
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
                            feedback(sp, Component.translatable("maid_file_manager.server.bulk_export_op_only"));
                            return;
                        }
                        FriendlyByteBuf fbb = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(encoded));
                        List<IMaidFileNetwork.PlayerExportRequest> requests = MaidInfoCodec.readPlayerExportRequests(fbb);
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
                new Type<>(MaidFilePackets.ID_SET_SERVER_CONFIG);
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
                            feedback(sp, Component.translatable("maid_file_manager.server.unknown_config"));
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

    /** C2S：OP 修改自己维护的「禁用携带的饰品」清单：权限校验 → 收窄写入 → 广播同步 */
    public record SetServerBaubleBlockedListPayload(String key, List<String> ids) implements CustomPacketPayload {
        public static final Type<SetServerBaubleBlockedListPayload> TYPE =
                new Type<>(MaidFilePackets.ID_SET_SERVER_BAUBLE_BLOCKED_LIST);
        public static final StreamCodec<ByteBuf, SetServerBaubleBlockedListPayload> STREAM_CODEC =
                StreamCodec.ofMember(
                                                (p, buf) -> {
                            FriendlyByteBuf fbb = new FriendlyByteBuf(buf);
                            fbb.writeUtf(p.key == null ? "" : p.key, MaidFilePackets.MAX_TEXT_LEN);
                            writeStringList(fbb, p.ids);
                        },
                        buf -> {
                            FriendlyByteBuf fbb = new FriendlyByteBuf(buf);
                            String key = fbb.readUtf(MaidFilePackets.MAX_TEXT_LEN);
                            return new SetServerBaubleBlockedListPayload(key, readStringList(fbb));
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
                        // 服务端配置仅 OP 可改（局域网联机=宿主默认 OP）
                        if (!sp.hasPermissions(2)) {
                            feedback(sp, Component.translatable("maid_file_manager.config.fail.no_permission"));
                            return;
                        }
                                                // 键名区分三类清单：物品 / 模组（命名空间）/ Curios 槽位
                        java.util.List<String> values;
                        if (MaidConfigManager.KEY_BAUBLE_BLOCKED_LIST.equals(key)) {
                            // 只保存 OP 自己维护的禁用项；整合包黑名单由 bauble_import.json 托管、游戏内只读
                            values = BaubleHandler.sanitizeBaubleBlockedList(ids);
                        } else if (MaidConfigManager.KEY_BAUBLE_BLOCKED_NAMESPACES.equals(key)
                                || MaidConfigManager.KEY_BAUBLE_BLOCKED_CURIOS_SLOTS.equals(key)) {
                            // 模组 / 槽位清单：客户端提交可被篡改，服务端按标识符字符集收窄
                            values = MaidTransferService.sanitizeIdentifierList(ids);
                        } else {
                            Constants.LOG.warn("[maid_file_manager] SET_SERVER_LIST_CONFIG rejected (unknown key): {}",
                                    key);
                            return;
                        }
                        MaidConfigManager.setServerListConfig(key, values);
                        broadcastServerConfig(sp.server);
                    } catch (Throwable t) {
                        handleError(sp, "SET_SERVER_BAUBLE_BLOCKED_LIST", t);
                    }
                }
            });
        }
    }

    /** C2S：请求某女仆档案（服务端校验归属后回发 {@link MaidProfilePayload}） */
    public record RequestMaidProfilePayload(int entityId) implements CustomPacketPayload {
        public static final Type<RequestMaidProfilePayload> TYPE =
                new Type<>(MaidFilePackets.ID_REQUEST_MAID_PROFILE);
        public static final StreamCodec<ByteBuf, RequestMaidProfilePayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, RequestMaidProfilePayload::entityId,
                        RequestMaidProfilePayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (ctx.player() instanceof ServerPlayer sp) {
                    // 频次限制：这类请求在主线程同步跑，循环发包能把服务器卡死（不需 OP）
                    if (!io.github.zgxhzhr.maidfm.network.RequestThrottle.allow(sp.getUUID(), "maid_profile", 1000L)) {
                        return;
                    }
                    try {
                        EntityMaid maid = resolveOwnedMaid(sp, entityId);
                        MaidProfileView view = maid == null ? null : MaidProfileService.buildView(sp, maid);
                        if (view == null) {
                            // 女仆不存在或非请求者所有：直接拒绝，不泄露任何档案内容
                            feedback(sp, Component.translatable("gui.maid_file_manager.profile.no_permission"));
                            return;
                        }
                        PacketDistributor.sendToPlayer(sp, new MaidProfilePayload(view));
                    } catch (Throwable t) {
                        handleError(sp, "REQUEST_MAID_PROFILE", t);
                    }
                }
            });
        }
    }

    /** C2S：保存女仆档案（服务端校验归属后写入实体并回执） */
    public record SaveMaidProfilePayload(int entityId, MaidProfile profile) implements CustomPacketPayload {
        public static final Type<SaveMaidProfilePayload> TYPE =
                new Type<>(MaidFilePackets.ID_SAVE_MAID_PROFILE);
        public static final StreamCodec<ByteBuf, SaveMaidProfilePayload> STREAM_CODEC =
                StreamCodec.ofMember(
                        (p, buf) -> {
                            FriendlyByteBuf fbb = new FriendlyByteBuf(buf);
                            fbb.writeInt(p.entityId);
                            MaidProfileCodec.writeMaidProfile(fbb, p.profile);
                        },
                        buf -> {
                            FriendlyByteBuf fbb = new FriendlyByteBuf(buf);
                            int entityId = fbb.readInt();
                            return new SaveMaidProfilePayload(entityId, MaidProfileCodec.readMaidProfile(fbb));
                        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (ctx.player() instanceof ServerPlayer sp) {
                    try {
                        if (profile == null) {
                            feedback(sp, Component.translatable("gui.maid_file_manager.profile.save_failed"));
                            return;
                        }
                        EntityMaid maid = resolveOwnedMaid(sp, entityId);
                        if (maid == null) {
                            feedback(sp, Component.translatable("gui.maid_file_manager.profile.no_permission"));
                            return;
                        }
                        // 档案界面保存：背景故事同步写回 TLM AI 人设，保证双向一致
                        MaidProfileService.writeToMaid(maid, profile, true);
                        feedback(sp, Component.translatable("gui.maid_file_manager.profile.save_ok"));
                    } catch (Throwable t) {
                        handleError(sp, "SAVE_MAID_PROFILE", t);
                    }
                }
            });
        }
    }

    /** C2S：备份管理——请求服务端返回可浏览的自动备份列表（服务端按权限过滤后回发 BackupListPayload） */
    /** C2S：客户端上报「模型 ID → 显示名」映射，服务端合并后用于解析备份列表的模型名 */
    public record ModelNamesPayload(List<String> pairs) implements CustomPacketPayload {
        public static final Type<ModelNamesPayload> TYPE = new Type<>(MaidFilePackets.ID_MODEL_NAMES);

        public static final StreamCodec<net.minecraft.network.FriendlyByteBuf, ModelNamesPayload> STREAM_CODEC =
                StreamCodec.of(
                        (buf, p) -> {
                            List<String> safe = p.pairs() == null ? List.of() : p.pairs();
                            int n = Math.min(safe.size(), MaidFilePackets.MAX_MODEL_NAME_ENTRIES * 2);
                            buf.writeVarInt(n / 2);
                            for (int i = 0; i + 1 < n; i += 2) {
                                buf.writeUtf(safe.get(i) == null ? "" : safe.get(i), MaidFilePackets.MAX_TEXT_LEN);
                                buf.writeUtf(safe.get(i + 1) == null ? "" : safe.get(i + 1), MaidFilePackets.MAX_TEXT_LEN);
                            }
                        },
                        buf -> new ModelNamesPayload(MaidFilePackets.readModelNames(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp) {
                    if (!RequestThrottle.allow(sp.getUUID(), "model_names", 10000L)) {
                        return;
                    }
                    io.github.zgxhzhr.maidfm.service.ModelNameRegistry.mergeFromPairs(pairs());
                }
            });
        }
    }

    public record RequestBackupListPayload() implements CustomPacketPayload {
        public static final Type<RequestBackupListPayload> TYPE =
                new Type<>(MaidFilePackets.ID_REQUEST_BACKUP_LIST);
        public static final StreamCodec<ByteBuf, RequestBackupListPayload> STREAM_CODEC =
                StreamCodec.unit(new RequestBackupListPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (ctx.player() instanceof ServerPlayer sp) {
                    // 频次限制：这类请求在主线程同步跑，循环发包能把服务器卡死（不需 OP）
                    if (!io.github.zgxhzhr.maidfm.network.RequestThrottle.allow(sp.getUUID(), "backup_list", 5000L)) {
                        return;
                    }
                    try {
                        // 权限：OP 可见全部玩家备份，非 OP 仅可见自己名下的备份
                        io.github.zgxhzhr.maidfm.service.MaidBackupService.ScanResult scanResult =
                                io.github.zgxhzhr.maidfm.service.MaidBackupService.scan(
                                        sp.server, sp.getUUID(), sp.hasPermissions(2));
                        List<IMaidFileNetwork.BackupOwner> owners = scanResult.owners();
                        // 扫描被上限/时间预算截断时明确告知：否则玩家会以为「就这么多备份」，
                        // 从而误判某只女仆没有备份。走已有 feedback 通道，不改协议。
                        if (scanResult.truncated()) {
                            feedback(sp, Component.translatable("maid_file_manager.server.backup_list_truncated"));
                        }
                        // 发送前按接收端解码维度预检：主人组数、总字节均需在上限内，
                        // 超限只回执提示，绝不发坏包把客户端断连
                        if (owners.size() > MaidFilePackets.MAX_BACKUP_OWNERS) {
                            feedback(sp, Component.translatable("maid_file_manager.server.too_many_backup_owners", owners.size(), MaidFilePackets.MAX_BACKUP_OWNERS));
                            return;
                        }
                        FriendlyByteBuf probe = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                        try {
                            MaidBackupCodec.writeBackupOwners(probe, owners);
                            int size = probe.readableBytes();
                            if (size > MaidFilePackets.MAX_PACKET_BYTES) {
                                Constants.LOG.warn("[maid_file_manager] 备份列表 {} 字节超过单包上限 {}，拒绝发送",
                                        size, MaidFilePackets.MAX_PACKET_BYTES);
                                feedback(sp, Component.translatable("maid_file_manager.server.backup_list_too_large", size / 1024, MaidFilePackets.MAX_PACKET_BYTES / 1024));
                                return;
                            }
                        } finally {
                            probe.release();
                        }
                        PacketDistributor.sendToPlayer(sp, new BackupListPayload(owners));
                    } catch (Throwable t) {
                        handleError(sp, "REQUEST_BACKUP_LIST", t);
                    }
                }
            });
        }
    }

    /** C2S：备份管理——请求服务端导出某条备份（服务端校验权限后回传 BackupExportResultPayload） */
    public record RequestBackupExportPayload(String ownerUuid, String maidUuid, String fileName)
            implements CustomPacketPayload {
        public static final Type<RequestBackupExportPayload> TYPE =
                new Type<>(MaidFilePackets.ID_REQUEST_BACKUP_EXPORT);
        public static final StreamCodec<ByteBuf, RequestBackupExportPayload> STREAM_CODEC =
                StreamCodec.ofMember(
                        (p, buf) -> {
                            FriendlyByteBuf fbb = new FriendlyByteBuf(buf);
                            fbb.writeUtf(p.ownerUuid == null ? "" : p.ownerUuid, MaidFilePackets.MAX_TEXT_LEN);
                            fbb.writeUtf(p.maidUuid == null ? "" : p.maidUuid, MaidFilePackets.MAX_TEXT_LEN);
                            fbb.writeUtf(p.fileName == null ? "" : p.fileName, MaidFilePackets.MAX_TEXT_LEN);
                        },
                        buf -> {
                            FriendlyByteBuf fbb = new FriendlyByteBuf(buf);
                            return new RequestBackupExportPayload(
                                    fbb.readUtf(MaidFilePackets.MAX_TEXT_LEN),
                                    fbb.readUtf(MaidFilePackets.MAX_TEXT_LEN),
                                    fbb.readUtf(MaidFilePackets.MAX_TEXT_LEN));
                        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (ctx.player() instanceof ServerPlayer sp) {
                    // 频次限制：写盘/生成实体类 handler，原先只有只读请求有节流
                    if (!io.github.zgxhzhr.maidfm.network.RequestThrottle.allow(sp.getUUID(), "backup_export", 2000L)) {
                        return;
                    }
                    try {
                        MaidFileData data = io.github.zgxhzhr.maidfm.service.MaidBackupService.readForExport(
                                sp.server, sp.getUUID(), sp.hasPermissions(2), ownerUuid, maidUuid, fileName);
                        if (data == null) {
                            feedback(sp, Component.translatable("gui.maid_file_manager.backup.export_failed"));
                            return;
                        }
                        byte[] blob = MaidFilePackets.serializeMaidFileData(data);
                        if (blob == null || blob.length > MaidFilePackets.MAX_SINGLE_FILE_BYTES) {
                            Constants.LOG.warn("[maid_file_manager] 备份导出体积异常，拒绝发送: owner={}, maid={}, size={}",
                                    ownerUuid, maidUuid, blob == null ? -1 : blob.length);
                            feedback(sp, Component.translatable("maid_file_manager.server.backup_too_large"));
                            return;
                        }
                        PacketDistributor.sendToPlayer(sp, new BackupExportResultPayload(blob));
                    } catch (Throwable t) {
                        handleError(sp, "REQUEST_BACKUP_EXPORT", t);
                    }
                }
            });
        }
    }

    /** 解析请求者名下的女仆：实体不存在或不属于请求者时返回 null */
    private static EntityMaid resolveOwnedMaid(ServerPlayer sp, int entityId) {
        if (!(sp.level().getEntity(entityId) instanceof EntityMaid maid)) {
            return null;
        }
        return maid.isOwnedBy(sp) ? maid : null;
    }

    // ================ S2C 包（服务端 -> 客户端） ================

    /** 服务端配置同步（登录时单播 + 修改后全服同步）；客户端收到后更新缓存并回发同意状态 */
    public record ServerConfigSyncPayload(boolean allowImport, boolean allowBaubles, boolean allowAdvancements,
                                          boolean allowEffects, boolean allowInvulnerable, boolean carryCurios,
                                          boolean baubleStripAttributes, List<String> baubleBlockedList,
                                          List<String> baubleBlacklist, List<String> baubleWhitelist,
                                          boolean baubleManaged, List<String> baubleBlockedNamespaces,
                                          List<String> baubleBlockedCuriosSlots,
                                          List<String> baubleImportBlockedNamespaces,
                                          List<String> baubleImportBlockedCuriosSlots, boolean baubleWhitelistEnforced)
            implements CustomPacketPayload {
        public static final Type<ServerConfigSyncPayload> TYPE =
                new Type<>(MaidFilePackets.ID_SERVER_CONFIG_SYNC);
        public static final StreamCodec<ByteBuf, ServerConfigSyncPayload> STREAM_CODEC =
                StreamCodec.ofMember(
                        (p, buf) -> {
                            FriendlyByteBuf fbb = new FriendlyByteBuf(buf);
                            fbb.writeBoolean(p.allowImport);
                            fbb.writeBoolean(p.allowBaubles);
                            fbb.writeBoolean(p.allowAdvancements);
                            fbb.writeBoolean(p.allowEffects);
                            fbb.writeBoolean(p.allowInvulnerable);
                            fbb.writeBoolean(p.carryCurios);
                            fbb.writeBoolean(p.baubleStripAttributes);
                            writeStringList(fbb, p.baubleBlockedList);
                            // 整合包饰品配置：黑名单 + 白名单 + 是否托管（客户端据此把整合包强制的条目显示为只读勾选）
                            writeStringList(fbb, p.baubleBlacklist);
                            writeStringList(fbb, p.baubleWhitelist);
                            fbb.writeBoolean(p.baubleManaged);
                            writeStringList(fbb, p.baubleBlockedNamespaces);
                            writeStringList(fbb, p.baubleBlockedCuriosSlots);
                            writeStringList(fbb, p.baubleImportBlockedNamespaces);
                            writeStringList(fbb, p.baubleImportBlockedCuriosSlots);
                            // 白名单强制状态：解码端 :837 会读它，此前漏写导致每次登录解码越界断连
                            fbb.writeBoolean(p.baubleWhitelistEnforced);
                        },
                        buf -> {
                            FriendlyByteBuf fbb = new FriendlyByteBuf(buf);
                            boolean allowImport = fbb.readBoolean();
                            boolean allowBaubles = fbb.readBoolean();
                            boolean allowAdvancements = fbb.readBoolean();
                            boolean allowEffects = fbb.readBoolean();
                            boolean allowInvulnerable = fbb.readBoolean();
                            boolean carryCurios = fbb.readBoolean();
                            boolean baubleStripAttributes = fbb.readBoolean();
                            List<String> baubleBlockedList = readStringList(fbb);
                            List<String> baubleBlacklist = readStringList(fbb);
                            List<String> baubleWhitelist = readStringList(fbb);
                            boolean baubleManaged = fbb.readBoolean();
                            List<String> baubleBlockedNamespaces = readStringList(fbb);
                            List<String> baubleBlockedCuriosSlots = readStringList(fbb);
                            List<String> baubleImportBlockedNamespaces = readStringList(fbb);
                            List<String> baubleImportBlockedCuriosSlots = readStringList(fbb);
                            boolean baubleWhitelistEnforced = fbb.readBoolean();
                            return new ServerConfigSyncPayload(allowImport, allowBaubles, allowAdvancements,
                                    allowEffects, allowInvulnerable, carryCurios, baubleStripAttributes, baubleBlockedList,
                                    baubleBlacklist, baubleWhitelist, baubleManaged,
                                    baubleBlockedNamespaces, baubleBlockedCuriosSlots,
                                    baubleImportBlockedNamespaces, baubleImportBlockedCuriosSlots, baubleWhitelistEnforced);
                        }
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> MaidConfigManager.handleServerConfigSync(
                    allowImport, allowBaubles, allowAdvancements, allowEffects, allowInvulnerable, carryCurios,
                    baubleStripAttributes, baubleBlockedList, baubleBlacklist, baubleWhitelist, baubleManaged,
                    baubleBlockedNamespaces, baubleBlockedCuriosSlots,
                    baubleImportBlockedNamespaces, baubleImportBlockedCuriosSlots, baubleWhitelistEnforced));
        }
    }

    /** 把服务端配置单播给指定玩家（登录同步用） */
    public static void sendServerConfig(ServerPlayer player) {
        if (player != null) {
            PacketDistributor.sendToPlayer(player, new ServerConfigSyncPayload(
                    MaidConfigManager.isClientImportAllowed(),
                    MaidConfigManager.isBaublesAllowed(),
                    MaidConfigManager.isAdvancementsAllowed(),
                    MaidConfigManager.isEffectsAllowed(),
                    MaidConfigManager.isInvulnerableAllowed(),
                    MaidConfigManager.isCarryCurios(),
                    MaidConfigManager.isBaubleStripAttributes(),
                    MaidConfigManager.getOpBaubleBlockedList(),
                    MaidConfigManager.getBaubleBlacklist(),
                    MaidConfigManager.getBaubleWhitelist(),
                    MaidConfigManager.isBaubleConfigManaged(),
                                        MaidConfigManager.getOpBaubleBlockedNamespaces(),
                    MaidConfigManager.getOpBaubleBlockedCuriosSlots(),
                    MaidConfigManager.getBaubleImportBlockedNamespaces(),
                    MaidConfigManager.getBaubleImportBlockedCuriosSlots(),
                    MaidConfigManager.isBaubleWhitelistEnforced()));
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
                new Type<>(MaidFilePackets.ID_MAID_LIST);
        public static final StreamCodec<ByteBuf, MaidListPayload> STREAM_CODEC =
                StreamCodec.ofMember(
                        (payload, buf) -> MaidInfoCodec.writeMaidInfoList(new FriendlyByteBuf(buf), payload.list),
                        buf -> new MaidListPayload(MaidInfoCodec.readMaidInfoList(new FriendlyByteBuf(buf)))
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
                new Type<>(MaidFilePackets.ID_EXPORT_BATCH_RESULT);
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
        static byte[] encodeExportBatchResult(List<byte[]> blobs) {
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
                    List<MaidFileData> list = new ArrayList<>();
                    for (byte[] blob : MaidFilePackets.readMaidDataBlobs(fbb, MaidFilePackets.MAX_EXPORT_IDS)) {
                        MaidFileData one = MaidFilePackets.deserializeMaidFileData(blob);
                        if (one != null) {
                            list.add(one);
                        }
                    }
                    handler.onExportResultReceived(list);
                }
            });
        }
    }

    public record FeedbackPayload(Component message)
            implements CustomPacketPayload {
        public static final Type<FeedbackPayload> TYPE =
                new Type<>(MaidFilePackets.ID_FEEDBACK);
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
                new Type<>(MaidFilePackets.ID_IMPORT_BATCH_RESULT);
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
                new Type<>(MaidFilePackets.ID_SERVER_EXPORT_LIST);
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
                MaidInfoCodec.writePlayerMaidGroups(fbb, groups);
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
                    handler.onServerExportListReceived(MaidInfoCodec.readPlayerMaidGroups(fbb));
                }
            });
        }
    }

    /** S2C：返回女仆档案视图（档案正文 + 只读展示数据 + 饰品图标） */
    public record MaidProfilePayload(MaidProfileView view) implements CustomPacketPayload {
        public static final Type<MaidProfilePayload> TYPE =
                new Type<>(MaidFilePackets.ID_MAID_PROFILE);
        public static final StreamCodec<ByteBuf, MaidProfilePayload> STREAM_CODEC =
                StreamCodec.ofMember(
                        (p, buf) -> MaidProfileCodec.writeMaidProfileView(new FriendlyByteBuf(buf), p.view),
                        buf -> new MaidProfilePayload(MaidProfileCodec.readMaidProfileView(new FriendlyByteBuf(buf))));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                var handler = IMaidFileNetwork.ClientHandlerHolder.get();
                if (handler != null && view != null) {
                    handler.onMaidProfileReceived(view);
                }
            });
        }
    }

    /** S2C：备份管理——返回按权限过滤后的自动备份列表 */
    public record BackupListPayload(List<IMaidFileNetwork.BackupOwner> owners) implements CustomPacketPayload {
        public static final Type<BackupListPayload> TYPE =
                new Type<>(MaidFilePackets.ID_BACKUP_LIST);
        public static final StreamCodec<ByteBuf, BackupListPayload> STREAM_CODEC =
                StreamCodec.ofMember(
                        (p, buf) -> MaidBackupCodec.writeBackupOwners(new FriendlyByteBuf(buf), p.owners),
                        buf -> new BackupListPayload(MaidBackupCodec.readBackupOwners(new FriendlyByteBuf(buf))));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                var handler = IMaidFileNetwork.ClientHandlerHolder.get();
                if (handler != null) handler.onBackupListReceived(owners);
            });
        }
    }

    /** S2C：备份管理——回传单条备份数据（客户端据此写入本地 maid_file/maid_exports/） */
    public record BackupExportResultPayload(byte[] encoded) implements CustomPacketPayload {
        public static final Type<BackupExportResultPayload> TYPE =
                new Type<>(MaidFilePackets.ID_BACKUP_EXPORT_RESULT);
        public static final StreamCodec<ByteBuf, BackupExportResultPayload> STREAM_CODEC =
                StreamCodec.ofMember(
                        (p, buf) -> {
                            FriendlyByteBuf fbb = new FriendlyByteBuf(buf);
                            fbb.writeVarInt(p.encoded.length);
                            fbb.writeBytes(p.encoded);
                        },
                        buf -> {
                            FriendlyByteBuf fbb = new FriendlyByteBuf(buf);
                            int len = MaidFilePackets.checkSize(fbb.readVarInt(),
                                    MaidFilePackets.MAX_SINGLE_FILE_BYTES, "backup_export_result");
                            byte[] arr = new byte[len];
                            fbb.readBytes(arr);
                            return new BackupExportResultPayload(arr);
                        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                var handler = IMaidFileNetwork.ClientHandlerHolder.get();
                if (handler != null) {
                    handler.onBackupExportReceived(MaidFilePackets.deserializeMaidFileData(encoded));
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
        feedback(sp, Component.translatable("maid_file_manager.server.handler_error", packetName));
    }

    /**
     * 分片上传（1.20.x 单包 32767 限制的绕行方案）。
     *
     * <p>客户端把可能超限的负载切成 ≤ {@code ChunkedUploadAssembler.CHUNK_BYTES} 的小片，
     * 服务端按 {@code playerUUID + sessionId} 重组，收齐后按 {@code kind} 分发到导入/保存档案处理。
     */
    public record ChunkedUploadPayload(String kind, int sessionId, int total, int index, byte[] chunk)
            implements CustomPacketPayload {
        public static final Type<ChunkedUploadPayload> TYPE =
                new Type<>(MaidFilePackets.ID_CHUNKED_UPLOAD);
        public static final StreamCodec<ByteBuf, ChunkedUploadPayload> STREAM_CODEC =
                StreamCodec.ofMember(
                        (p, buf) -> {
                            FriendlyByteBuf fbb = new FriendlyByteBuf(buf);
                            fbb.writeUtf(p.kind == null ? "" : p.kind, MaidFilePackets.MAX_TEXT_LEN);
                            fbb.writeVarInt(p.sessionId);
                            fbb.writeVarInt(p.total);
                            fbb.writeVarInt(p.index);
                            fbb.writeVarInt(p.chunk.length);
                            fbb.writeBytes(p.chunk);
                        },
                        buf -> {
                            FriendlyByteBuf fbb = new FriendlyByteBuf(buf);
                            String kind = fbb.readUtf(MaidFilePackets.MAX_TEXT_LEN);
                            int sessionId = fbb.readVarInt();
                            int total = fbb.readVarInt();
                            int index = fbb.readVarInt();
                            int len = MaidFilePackets.checkSize(fbb.readVarInt(),
                                    io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.CHUNK_BYTES, "chunk");
                            byte[] arr = new byte[len];
                            fbb.readBytes(arr);
                            return new ChunkedUploadPayload(kind, sessionId, total, index, arr);
                        }
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (!(ctx.player() instanceof ServerPlayer sp)) {
                    return;
                }
                byte[] full = io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.accept(
                        sp.getUUID(), kind, sessionId, total, index, chunk);
                if (full == null) {
                    return;
                }
                // 统一交给共享分发器：SAVE_PROFILE / IMPORT_BATCH / EXPORT_BATCH 三种负载
                // 与 Forge、Fabric 两端走同一份实现（此前只有 SAVE_PROFILE 接了，
                // IMPORT_BATCH 落到一条 WARN 上——而带一张头像的 .maid 就超过 24 KiB，
                // 该端的批量导入实际上基本全废）。
                io.github.zgxhzhr.maidfm.service.ChunkedUploadDispatcher.dispatch(sp, kind, full);
            });
        }
    }

    /**
     * 分片收齐后的保存档案处理。负载格式与 {@link SaveMaidProfilePayload} 一致：
     * {@code VarInt entityId + 账号档案 NBT}。
     *
     * <p>这里是**有意重复** SaveMaidProfilePayload.handle 的校验逻辑：该 record 的处理内联在
     * handle 里、没有可复用的静态入口，抽取会牵动四端同名结构；重复一份更小的风险。
     */
    private static void processSaveProfileBytes(ServerPlayer sp, byte[] full) {
        try {
            FriendlyByteBuf fbb = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(full));
            int entityId = fbb.readVarInt();
            MaidProfile profile = MaidProfileCodec.readMaidProfile(fbb);
            if (profile == null) {
                feedback(sp, Component.translatable("gui.maid_file_manager.profile.save_failed"));
                return;
            }
            EntityMaid maid = resolveOwnedMaid(sp, entityId);
            if (maid == null) {
                feedback(sp, Component.translatable("gui.maid_file_manager.profile.no_permission"));
                return;
            }
            MaidProfileService.writeToMaid(maid, profile, true);
            feedback(sp, Component.translatable("gui.maid_file_manager.profile.save_ok"));
        } catch (Throwable t) {
            handleError(sp, "SAVE_MAID_PROFILE_CHUNKED", t);
        }
    }

    /**
     * 分片下发（服务端 → 客户端）。1.20.x 的 32767 字节上限对 S2C 同样生效，
     * 带照片的导出结果必然超限，故与 C2S 方向对称地切片发送。
     */
    public record ChunkedDownloadPayload(String kind, int sessionId, int total, int index, byte[] chunk)
            implements CustomPacketPayload {
        public static final Type<ChunkedDownloadPayload> TYPE =
                new Type<>(MaidFilePackets.ID_CHUNKED_DOWNLOAD);
        public static final StreamCodec<ByteBuf, ChunkedDownloadPayload> STREAM_CODEC =
                StreamCodec.ofMember(
                        (p, buf) -> {
                            FriendlyByteBuf fbb = new FriendlyByteBuf(buf);
                            fbb.writeUtf(p.kind == null ? "" : p.kind, MaidFilePackets.MAX_TEXT_LEN);
                            fbb.writeVarInt(p.sessionId);
                            fbb.writeVarInt(p.total);
                            fbb.writeVarInt(p.index);
                            fbb.writeVarInt(p.chunk.length);
                            fbb.writeBytes(p.chunk);
                        },
                        buf -> {
                            FriendlyByteBuf fbb = new FriendlyByteBuf(buf);
                            String kind = fbb.readUtf(MaidFilePackets.MAX_TEXT_LEN);
                            int sessionId = fbb.readVarInt();
                            int total = fbb.readVarInt();
                            int index = fbb.readVarInt();
                            int len = MaidFilePackets.checkSize(fbb.readVarInt(),
                                    io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.CHUNK_BYTES, "chunk");
                            byte[] arr = new byte[len];
                            fbb.readBytes(arr);
                            return new ChunkedDownloadPayload(kind, sessionId, total, index, arr);
                        }
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        /** 服务端调用：把负载切片逐片发给玩家 */
        static boolean sendChunks(ServerPlayer player, String kind, byte[] payload) {
            if (player == null || payload == null || payload.length == 0) {
                return true;
            }

            // 总量预检：客户端 ChunkedUploadAssembler 会在首片就按 declared > MAX_TOTAL_BYTES 拒收，
            // 发送端照样发满几百片纯属浪费。超限直接回执失败并返回 false ——
            // 调用方据此不登记待删，避免「文件没发出去却删了女仆」。
            if (payload.length > io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.MAX_TOTAL_BYTES) {
                Constants.LOG.warn("[maid_file_manager] 分片下发超总量上限，拒绝发送: player={} bytes={} limit={}",
                        player.getName().getString(), payload.length, io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.MAX_TOTAL_BYTES);
                feedback(player, net.minecraft.network.chat.Component.translatable(
                        "maid_file_manager.server.export_too_large",
                        payload.length / 1024, io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.MAX_TOTAL_BYTES / 1024));
                return false;
            }
            int chunkBytes = io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.CHUNK_BYTES;
            int total = (payload.length + chunkBytes - 1) / chunkBytes;
            int sessionId = java.util.concurrent.ThreadLocalRandom.current().nextInt();
            for (int i = 0; i < total; i++) {
                int off = i * chunkBytes;
                int len = Math.min(chunkBytes, payload.length - off);
                byte[] chunk = java.util.Arrays.copyOfRange(payload, off, off + len);
                PacketDistributor.sendToPlayer(player,
                        new ChunkedDownloadPayload(kind, sessionId, total, i, chunk));
            }
            Constants.LOG.info("[maid_file_manager] 导出结果分片下发: player={} bytes={} chunks={}",
                    player.getName().getString(), payload.length, total);
        return true;
        }

        /** S2C 包：客户端收到后累积，收齐再交给 UI 回调 */
        public void handle(IPayloadContext ctx) {
            // 组装也移到主线程：accept 会做 ConcurrentHashMap 查找 + 多片拷贝，
            // 放在 netty 事件循环上会占用该连接的所有收发线程（大包几百片时更明显）。
            // clientUuid() 已带缓存，仍在 netty 线程取一次即可。
            final java.util.UUID playerUuid = clientUuid();
            ctx.enqueueWork(() -> {
                // 新会话开始时清掉本玩家残留的未完成分片会话：客户端从不主动 clear，
                // 被中断的下载会占满 MAX_SESSIONS_PER_PLAYER(4)，之后 60s 内新导出首片即被拒且无提示（第六轮 N7）。
                if (index == 0) {
                    io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.clear(playerUuid);
                }
                byte[] full = io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.accept(
                        playerUuid, kind, sessionId, total, index, chunk);
                if (full == null
                        || !io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.KIND_EXPORT_BATCH.equals(kind)) {
                    return;
                }
                var clientHandler = IMaidFileNetwork.ClientHandlerHolder.get();
                if (clientHandler == null) {
                    return;
                }
                FriendlyByteBuf fullBuf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(full));
                List<MaidFileData> decoded = new ArrayList<>();
                for (byte[] blob : MaidFilePackets.readMaidDataBlobs(fullBuf, MaidFilePackets.MAX_EXPORT_IDS)) {
                    MaidFileData one = MaidFilePackets.deserializeMaidFileData(blob);
                    if (one != null) {
                        decoded.add(one);
                    }
                }
                var h = IMaidFileNetwork.ClientHandlerHolder.get();
                // 管理器界面已关闭时结果无处投递：原先静默丢弃，该批导出彻底消失且无任何痕迹。
                // 至少留一条 WARN，便于玩家/维护者从日志判断「导出为什么没反应」（第六轮 M10）。
                if (h == null) {
                    io.github.zgxhzhr.maidfm.Constants.LOG.warn(
                            "[maid_file_manager] 导出结果被丢弃（管理器界面已关闭）: 本批 {} 条", decoded.size());
                }
                if (h != null) {
                    h.onExportResultReceived(decoded);
                }
            });
        }

        /**
         * 客户端玩家族。
         *
         * <p>只在第一次成功解析后缓存：原先每收到一片都要做 4 次反射
         * （Class.forName + getMethod("getInstance") + getMethod("getPlayer") + getMethod("getUUID")），
         * 而一次导出可能有几百片 —— 且这些调用发生在 **netty 事件循环线程** 上，
         * 反射的类查找开销会直接拖慢该连接的所有收发。
         *
         * <p>解析失败时**不缓存**（此时玩家可能还没就绪），返回零 UUID 供组装器按「无主」处理。
         */
        private static volatile java.util.UUID cachedClientUuid;

        private static java.util.UUID clientUuid() {
            java.util.UUID cached = cachedClientUuid;
            if (cached != null) {
                return cached;
            }
            try {
                Object mc = Class.forName("net.minecraft.client.Minecraft").getMethod("getInstance").invoke(null);
                Object player = mc.getClass().getMethod("getPlayer").invoke(mc);
                if (player == null) {
                    return new java.util.UUID(0L, 0L);
                }
                java.util.UUID resolved = (java.util.UUID) player.getClass().getMethod("getUUID").invoke(player);
                if (resolved != null) {
                    cachedClientUuid = resolved;
                }
                return resolved != null ? resolved : new java.util.UUID(0L, 0L);
            } catch (Throwable t) {
                return new java.util.UUID(0L, 0L);
            }
        }
    }

    /** C2S：客户端导出写盘回执（true=写入成功） */
    public record ExportWriteAckPayload(boolean success) implements CustomPacketPayload {
        public static final Type<ExportWriteAckPayload> TYPE =
                new Type<>(MaidFilePackets.ID_EXPORT_WRITE_ACK);
        public static final StreamCodec<ByteBuf, ExportWriteAckPayload> STREAM_CODEC =
                StreamCodec.ofMember(
                        (p, buf) -> new FriendlyByteBuf(buf).writeBoolean(p.success),
                        buf -> new ExportWriteAckPayload(new FriendlyByteBuf(buf).readBoolean())
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (ctx.player() instanceof ServerPlayer sp) {
                    io.github.zgxhzhr.maidfm.service.PendingRemovalRegistry.confirmAndRemove(sp.getUUID(), success, sp);
                }
            });
        }
    }
}
