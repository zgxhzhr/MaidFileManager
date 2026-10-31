package io.github.zgxhzhr.maidfm.network;

import io.github.zgxhzhr.maidfm.service.ServerPacketHandlers;

import io.github.zgxhzhr.maidfm.Constants;
import io.github.zgxhzhr.maidfm.data.ImportResult;
import io.github.zgxhzhr.maidfm.data.MaidFileData;
import io.github.zgxhzhr.maidfm.data.MaidInfo;
import io.github.zgxhzhr.maidfm.data.MaidProfile;
import io.github.zgxhzhr.maidfm.data.MaidProfileView;
import io.github.zgxhzhr.maidfm.service.MaidProfileService;
import io.github.zgxhzhr.maidfm.service.MaidTransferService;
import io.github.zgxhzhr.maidfm.service.BaubleHandler;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 客户端到服务端的通用网络包。
 * 通过 {@link #packetId} 区分具体业务类型，{@link #data} 携带数据。
 *
 * <p>使用 Forge SimpleChannel 传输，避免为每种业务定义独立 packet 类。
 * decode 阶段 {@code buf.readBytes(len)} 会分配独立堆缓冲，handle 结束必须释放，
 * 否则每个入站包泄漏一个 ByteBuf（pooled/direct 缓冲泄漏最终耗尽直接内存）。
 */
public record C2SPacket(ResourceLocation packetId, FriendlyByteBuf data) {

    public static void encode(C2SPacket packet, FriendlyByteBuf buf) {
        buf.writeResourceLocation(packet.packetId);
        buf.writeVarInt(packet.data.readableBytes());
        buf.writeBytes(packet.data);
    }

    public static C2SPacket decode(FriendlyByteBuf buf) {
        ResourceLocation id = buf.readResourceLocation();
        int len = buf.readVarInt();
        FriendlyByteBuf data = new FriendlyByteBuf(buf.readBytes(len));
        return new C2SPacket(id, data);
    }

    public static void handle(C2SPacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        try {
            ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            ResourceLocation id = packet.packetId;
            try {
                if (player == null) {
                    Constants.LOG.warn("[maid_file_manager] C2S 包 {} 被丢弃：ctx.getSender() 返回 null", id);
                    return;
                }
                // 每个 C2S 包一条收发记录：量很大（服务端每个包都打），因此保持 debug；
                // 需要排查「服务端到底收没收到」时临时提到 INFO，排查完记得改回（本轮已从诊断期的 INFO 降回 debug）。
                Constants.LOG.debug("[maid_file_manager] S<-C RECV: id={}, player={}", id,
                        player.getName().getString());
                FriendlyByteBuf data = packet.data;
                if (MaidFilePackets.ID_REQUEST_MAID_LIST.equals(id)) {
                    handleRequestMaidList(player);
                } else if (MaidFilePackets.ID_EXPORT_MAID.equals(id)) {
                    // 兼容旧版单发导出协议：当前 GUI 单发也走 ID_EXPORT_BATCH，本分支暂无发送方，保留以防旧客户端
                    handleExportMaid(player, data.readInt());
                } else if (MaidFilePackets.ID_EXPORT_BATCH.equals(id)) {
                    List<Integer> ids = MaidFilePackets.readIntList(data);
                    boolean removeAfter = data.readBoolean();
                    handleExportBatch(player, ids, removeAfter);
                } else if (MaidFilePackets.ID_IMPORT_FILE.equals(id)) {
                    handleImportFile(player, data);
                } else if (MaidFilePackets.ID_IMPORT_BATCH.equals(id)) {
                    handleImportBatch(player, data);
                } else if (MaidFilePackets.ID_CHUNKED_UPLOAD.equals(id)) {
                    handleChunkedUpload(player, data);
                } else if (MaidFilePackets.ID_EXPORT_WRITE_ACK.equals(id)) {
                    io.github.zgxhzhr.maidfm.service.PendingRemovalRegistry.confirmAndRemove(
                    player.getUUID(), data.readBoolean(), player);
                } else if (MaidFilePackets.ID_CLIENT_CONSENT.equals(id)) {
                    boolean allow = data.readBoolean();
                    io.github.zgxhzhr.maidfm.config.MaidConfigManager.handleClientConsent(player.getUUID(), allow);
                } else if (MaidFilePackets.ID_SET_SERVER_CONFIG.equals(id)) {
                    String key = data.readUtf(MaidFilePackets.MAX_TEXT_LEN);
                    boolean value = data.readBoolean();
                    handleSetServerConfig(player, key, value);
                } else if (MaidFilePackets.ID_SET_SERVER_BAUBLE_BLOCKED_LIST.equals(id)) {
                    String listKey = data.readUtf(MaidFilePackets.MAX_TEXT_LEN);
                    handleSetServerListConfig(player, listKey, MaidFilePackets.readStringList(data));
                } else if (MaidFilePackets.ID_REQUEST_SERVER_EXPORT_LIST.equals(id)) {
                    handleRequestServerExportList(player);
                } else if (MaidFilePackets.ID_SERVER_EXPORT_BATCH.equals(id)) {
                    handleServerExportBatch(player, data);
                } else if (MaidFilePackets.ID_REQUEST_MAID_PROFILE.equals(id)) {
                    handleRequestMaidProfile(player, data.readVarInt());
                } else if (MaidFilePackets.ID_SAVE_MAID_PROFILE.equals(id)) {
                    int entityId = data.readVarInt();
                    handleSaveMaidProfile(player, entityId, MaidProfileCodec.readMaidProfile(data));
                } else if (MaidFilePackets.ID_MODEL_NAMES.equals(id)) {
                    // 客户端上报的「模型 ID → 显示名」映射：合并进服务端汇总表，
                    // 备份列表解析模型名时优先查它（这是拿到别人私有模型中文名的唯一来源）。
                    // 注意：data 的释放由外层 finally 统一处理，这里再 release 会 refCnt 归零后二次减一。
                    io.github.zgxhzhr.maidfm.service.ModelNameRegistry.mergeFromPairs(
                            MaidFilePackets.readModelNames(packet.data));
                } else if (MaidFilePackets.ID_REQUEST_BACKUP_LIST.equals(id)) {
                    handleRequestBackupList(player);
                } else if (MaidFilePackets.ID_REQUEST_BACKUP_EXPORT.equals(id)) {
                    handleRequestBackupExport(player, data);
                } else {
                    Constants.LOG.warn("[maid_file_manager] 未知的 C2S 包: {}", id);
                }
            } catch (Throwable t) {
                Constants.LOG.error("[maid_file_manager] C2S handler 崩溃: id={}, player={}, cause={}",
                        id, player == null ? "<null>" : player.getName().getString(), t.toString(), t);
                // 异常原文可能含服务端路径/类名，仅保留在日志，回执只给通用提示。
                // 连接断开瞬间回执自身也可能抛异常，不能让它覆盖原始异常，二次保护
                if (player != null) {
                    try {
                        sendFeedback(player, Component.translatable("maid_file_manager.server.handler_error", id));
                    } catch (Throwable suppressed) {
                        Constants.LOG.warn("[maid_file_manager] 失败回执也发送失败（连接可能已断开）: {}", id);
                    }
                }
            } finally {
                // decode 时 readBytes 分配的独立堆缓冲，统一在此释放
                packet.data.release();
            }
        });
        } catch (Throwable t) {
            // 服务器关闭瞬间 enqueueWork 可能拒绝任务：此时 lambda 不会执行，入站缓冲必须在此释放
            Constants.LOG.warn("[maid_file_manager] C2S 包入队失败: {}", packet.packetId, t);
            packet.data.release();
        }
        ctx.setPacketHandled(true);
    }

    /** 业务逻辑已下沉到 {@link ServerPacketHandlers}（六端共用一份）；本端只做转发。 */
    private static void handleRequestMaidList(ServerPlayer player) {
        ServerPacketHandlers.requestMaidList(player);
    }

    /** 业务逻辑已下沉到 {@link ServerPacketHandlers}（六端共用一份）。 */
    private static void handleExportMaid(ServerPlayer player, int entityId) {
        ServerPacketHandlers.exportMaid(player, entityId);
    }

    private static void handleImportFile(ServerPlayer player, FriendlyByteBuf data) {
        // 旧单发通道与批量通道做的是同一件重活（含生成实体/写盘），
        // 批量通道已加频控，这条不能成为绕过口（第六轮 N3）。
        if (!io.github.zgxhzhr.maidfm.network.RequestThrottle.allow(player.getUUID(), "import_file", 1000L)) {
            return;
        }
        MaidFileData maidData = MaidFilePackets.readMaidFileData(data);
        boolean keepBaubles = data.readBoolean();
        // 单文件通道与批量通道线格式保持一致（尾部为 deleteAfterImport）；
        // 单文件通道无对应 UI 删除流程，读掉但不触发回传
        data.readBoolean();
        if (maidData == null) {
            sendFeedback(player, Component.translatable("maid_file_manager.import.fail.invalid"));
            return;
        }
        // 饰品导入策略由服务端配置决定，客户端不上传（杜绝非 OP 绕过）
        // 禁用清单直接取服务端配置（含整合包 bauble_import.json 的中文名条目），不再做命名空间收窄
        ImportResult result = MaidTransferService.importMaidFromData(
                player, maidData, keepBaubles,
                io.github.zgxhzhr.maidfm.config.MaidConfigManager.isBaubleStripAttributes(),
                io.github.zgxhzhr.maidfm.config.MaidConfigManager.getBaubleBlockedList());
        // 单条导入同样要消费 Curios 批次统计，否则残留到后续导出提示（第六轮 N5）。
        BaubleHandler.curiosDropTip();
        sendFeedback(player, BaubleHandler.withBaubleDetails(result));
    }

    /** 业务逻辑已下沉到 {@link ServerPacketHandlers}（六端共用一份）。 */
    private static void handleExportBatch(ServerPlayer player, List<Integer> ids, boolean removeAfter) {
        ServerPacketHandlers.exportBatch(player, ids, removeAfter);
    }

    private static void handleImportBatch(ServerPlayer player, FriendlyByteBuf data) {
        // 频次限制：这三个是写盘/生成实体类 handler，比只读请求更重；
        // 原先只有 3 个只读 handler 有节流，批量导出/导入与备份导出可被循环刷。
        if (!io.github.zgxhzhr.maidfm.network.RequestThrottle.allow(player.getUUID(), "import_batch", 1000L)) {
            return;
        }
        List<MaidFileData> list = MaidFilePackets.readMaidFileDataList(data);
        boolean keepBaubles = data.readBoolean();
        boolean deleteAfterImport = data.readBoolean();
        // 饰品导入策略以服务端配置为准（客户端不上传，杜绝非 OP 绕过）；
        // 禁用清单直接取服务端配置（含整合包 bauble_import.json 的中文名条目），不再做命名空间收窄
        boolean stripAttributes = io.github.zgxhzhr.maidfm.config.MaidConfigManager.isBaubleStripAttributes();
        List<String> blockedList = io.github.zgxhzhr.maidfm.config.MaidConfigManager.getBaubleBlockedList();
        // 成败统计只认 ImportResult.State 枚举，严禁反解中文展示文案
        List<ImportResult> results = new ArrayList<>(list.size());
        for (MaidFileData d : list) {
            if (d == null) {
                results.add(ImportResult.failed(Component.translatable(
                        "maid_file_manager.import.fail.invalid")));
                continue;
            }
            results.add(MaidTransferService.importMaidFromData(
                    player, d, keepBaubles, stripAttributes, blockedList));
        }
        // 导入路径同样会产生 Curios 批次统计（strip 时累加），必须在每次导入结束时消费掉；
        // 否则统计会残留到**下一位玩家**走 sendExportResults 时被当成「导出丢弃」提示（第六轮 N5）。
        BaubleHandler.curiosDropTip();
        Component summary = MaidTransferService.buildBatchSummary(results);
        if (deleteAfterImport) {
            // 请求删除源文件：回传逐项 spawned 标志（与请求顺序对齐），客户端只删成功的文件
            List<Boolean> spawned = new ArrayList<>(results.size());
            for (ImportResult r : results) {
                spawned.add(r.spawned());
            }
            sendImportBatchResult(player, summary, spawned);
        } else {
            sendFeedback(player, summary);
        }
    }

    /** 发送反馈消息（实现已收敛到 {@link io.github.zgxhzhr.maidfm.service.PacketSender}） */
    private static void sendFeedback(ServerPlayer player, Component message) {
        io.github.zgxhzhr.maidfm.service.PacketSender.sendFeedback(player, message);
    }

    /** 批量导入 + 删除源文件：汇总文案 + 逐项 spawned 标志（顺序与请求对齐） */
    private static void sendImportBatchResult(ServerPlayer player, Component summary, List<Boolean> spawned) {
        io.github.zgxhzhr.maidfm.service.PacketSender.sendImportBatchResult(player, summary, spawned);
    }

    /** 客户端请求修改服务端配置：先校验 OP 权限，再写文件并同步给所有在线客户端 */
    /** 业务逻辑已下沉到 {@link ServerPacketHandlers}（六端共用一份）。 */
    private static void handleSetServerConfig(ServerPlayer player, String key, boolean value) {
        ServerPacketHandlers.setServerConfig(player, key, value);
    }

    /** OP 修改自己维护的禁用清单（物品 / 模组 / Curios 槽位）：权限校验 → 收窄写入 → 广播同步 */
    /** 业务逻辑已下沉到 {@link ServerPacketHandlers}（六端共用一份）。 */
    private static void handleSetServerListConfig(ServerPlayer player, String key, List<String> ids) {
        ServerPacketHandlers.setServerListConfig(player, key, ids);
    }

    /** OP 统一导出：返回所有在线玩家（以各玩家为中心）的女仆分组列表 */
    /** 业务逻辑已下沉到 {@link ServerPacketHandlers}（六端共用一份）。 */
    private static void handleRequestServerExportList(ServerPlayer player) {
        ServerPacketHandlers.requestServerExportList(player);
    }

    /** OP 统一导出提交：代各玩家导出女仆到服务端磁盘 maid_exports/<玩家名>/ */
    private static void handleServerExportBatch(ServerPlayer player, FriendlyByteBuf data) {
        if (!player.hasPermissions(2)) {
            sendFeedback(player, Component.translatable("maid_file_manager.server.bulk_export_op_only"));
            return;
        }
        List<IMaidFileNetwork.PlayerExportRequest> requests = MaidInfoCodec.readPlayerExportRequests(data);
        Component result = io.github.zgxhzhr.maidfm.service.MaidServerCommands
                .exportForPlayers(player.server, requests);
        sendFeedback(player, result);
    }

    /**
     * 请求女仆档案：非本人直接拒绝（不返回任何只读数据），本人则回发档案视图。
     * 归属校验在实体类型判断之外由 {@link MaidProfileService#buildView} 再兜底一次。
     */
    /** 业务逻辑已下沉到 {@link ServerPacketHandlers}（六端共用一份）。 */
    private static void handleRequestMaidProfile(ServerPlayer player, int entityId) {
        ServerPacketHandlers.requestMaidProfile(player, entityId);
    }

    /**
     * 保存女仆档案：校验归属后写入实体并回执（复用 ID_FEEDBACK）。
     * 档案界面保存时同步把背景故事写回 TLM AI 人设，保证内存态与存档一致。
     */
    /** 业务逻辑已下沉到 {@link ServerPacketHandlers}（六端共用一份）。 */
    private static void handleSaveMaidProfile(ServerPlayer player, int entityId, MaidProfile profile) {
        ServerPacketHandlers.saveMaidProfile(player, entityId, profile);
    }

    /** 备份管理：请求服务端返回可浏览的自动备份列表（按权限过滤后回发） */
    /** 业务逻辑已下沉到 {@link ServerPacketHandlers}（六端共用一份）。 */
    private static void handleRequestBackupList(ServerPlayer player) {
        ServerPacketHandlers.requestBackupList(player);
    }

    /** 备份管理：请求服务端导出某条备份（校验权限后读取并回传为 .maid 数据） */
    private static void handleRequestBackupExport(ServerPlayer player, FriendlyByteBuf data) {
        // 频次限制：这三个是写盘/生成实体类 handler，比只读请求更重；
        // 原先只有 3 个只读 handler 有节流，批量导出/导入与备份导出可被循环刷。
        if (!io.github.zgxhzhr.maidfm.network.RequestThrottle.allow(player.getUUID(), "backup_export", 2000L)) {
            return;
        }
        String ownerUuid = data.readUtf(MaidFilePackets.MAX_TEXT_LEN);
        String maidUuid = data.readUtf(MaidFilePackets.MAX_TEXT_LEN);
        String fileName = data.readUtf(MaidFilePackets.MAX_TEXT_LEN);
        MaidFileData out = io.github.zgxhzhr.maidfm.service.MaidBackupService.readForExport(
                player.server, player.getUUID(), player.hasPermissions(2), ownerUuid, maidUuid, fileName);
        if (out == null) {
            sendFeedback(player, Component.translatable("gui.maid_file_manager.backup.export_failed"));
            return;
        }
        byte[] blob = MaidFilePackets.serializeMaidFileData(out);
        if (blob == null || blob.length > MaidFilePackets.MAX_SINGLE_FILE_BYTES) {
            Constants.LOG.warn("[maid_file_manager] 备份导出体积异常，拒绝发送: owner={}, maid={}, size={}",
                    ownerUuid, maidUuid, blob == null ? -1 : blob.length);
            sendFeedback(player, Component.translatable("maid_file_manager.server.backup_too_large"));
            return;
        }
        // 单个备份体积已按接收端上限预检，直接按线格式回传（客户端 readMaidFileData 解码）
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeVarInt(blob.length);
        buf.writeBytes(blob);
        ServerNetworkBridge.sendToPlayer(player, MaidFilePackets.ID_BACKUP_EXPORT_RESULT, buf);
    }

    /** 把服务端配置单播给指定玩家（登录同步用） */
    public static void sendServerConfig(ServerPlayer player) {
        if (player == null) {
            return;
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeBoolean(io.github.zgxhzhr.maidfm.config.MaidConfigManager.isClientImportAllowed());
        buf.writeBoolean(io.github.zgxhzhr.maidfm.config.MaidConfigManager.isBaublesAllowed());
        buf.writeBoolean(io.github.zgxhzhr.maidfm.config.MaidConfigManager.isAdvancementsAllowed());
        buf.writeBoolean(io.github.zgxhzhr.maidfm.config.MaidConfigManager.isEffectsAllowed());
        buf.writeBoolean(io.github.zgxhzhr.maidfm.config.MaidConfigManager.isInvulnerableAllowed());
        buf.writeBoolean(io.github.zgxhzhr.maidfm.config.MaidConfigManager.isCarryCurios());
        buf.writeBoolean(io.github.zgxhzhr.maidfm.config.MaidConfigManager.isBaubleStripAttributes());
        MaidFilePackets.writeStringList(buf,
                io.github.zgxhzhr.maidfm.config.MaidConfigManager.getOpBaubleBlockedList());
        // 整合包饰品配置：黑名单 + 白名单 + 是否存在整合包配置（客户端据此把整合包强制的条目显示为只读勾选）
        MaidFilePackets.writeStringList(buf,
                io.github.zgxhzhr.maidfm.config.MaidConfigManager.getBaubleBlacklist());
        MaidFilePackets.writeStringList(buf,
                io.github.zgxhzhr.maidfm.config.MaidConfigManager.getBaubleWhitelist());
        buf.writeBoolean(io.github.zgxhzhr.maidfm.config.MaidConfigManager.isBaubleConfigManaged());
                MaidFilePackets.writeStringList(buf,
                io.github.zgxhzhr.maidfm.config.MaidConfigManager.getOpBaubleBlockedNamespaces());
        MaidFilePackets.writeStringList(buf,
                io.github.zgxhzhr.maidfm.config.MaidConfigManager.getOpBaubleBlockedCuriosSlots());
        MaidFilePackets.writeStringList(buf,
                io.github.zgxhzhr.maidfm.config.MaidConfigManager.getBaubleImportBlockedNamespaces());
        MaidFilePackets.writeStringList(buf,
                io.github.zgxhzhr.maidfm.config.MaidConfigManager.getBaubleImportBlockedCuriosSlots());
        // 白名单强制状态由服务端判定（含「整合包配置解析失败 → 失败关闭」），必须显式下发
        buf.writeBoolean(io.github.zgxhzhr.maidfm.config.MaidConfigManager.isBaubleWhitelistEnforced());
        ServerNetworkBridge.sendToPlayer(player, MaidFilePackets.ID_SERVER_CONFIG_SYNC, buf);
    }

    /** 把服务端配置同步给所有在线玩家（OP 改配置后用） */
    /** 遍历逻辑已下沉到 {@link io.github.zgxhzhr.maidfm.service.PacketSender}（六端共用一份）。 */
    public static void broadcastServerConfig(MinecraftServer server) {
        io.github.zgxhzhr.maidfm.service.PacketSender.broadcastServerConfig(server);
    }

    /**
     * 分片上传：读一片 → 交给组装器 → 收齐后交给四端共用的分发器。
     * 1.20.x 单包上限 32767 字节，大负载（带照片的档案 / 带头像的 .maid）走这条路。
     */
    private static void handleChunkedUpload(ServerPlayer player, FriendlyByteBuf data) {
        try {
            String kind = data.readUtf(MaidFilePackets.MAX_TEXT_LEN);
            int sessionId = data.readVarInt();
            int total = data.readVarInt();
            int index = data.readVarInt();
            byte[] chunk = data.readByteArray(io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.CHUNK_BYTES);
            byte[] full = io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.accept(
                    player.getUUID(), kind, sessionId, total, index, chunk);
            if (full != null) {
                io.github.zgxhzhr.maidfm.service.ChunkedUploadDispatcher.dispatch(player, kind, full);
            }
        } catch (Throwable e) {
            Constants.LOG.warn("[maid_file_manager] 分片上传处理失败: {}", e.toString());
        }
    }

}
