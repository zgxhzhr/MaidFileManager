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

    /** 读取留在本端（各版本 buf API 不同），业务已下沉到 {@link ServerPacketHandlers}。 */
    private static void handleImportFile(ServerPlayer player, FriendlyByteBuf data) {
        MaidFileData maidData = MaidFilePackets.readMaidFileData(data);
        boolean keepBaubles = data.readBoolean();
        // 单文件通道与批量通道线格式保持一致（尾部为 deleteAfterImport）；
        // 单文件通道无对应 UI 删除流程，读掉但不触发回传
        data.readBoolean();
        ServerPacketHandlers.importFile(player, maidData, keepBaubles);
    }

    /** 业务逻辑已下沉到 {@link ServerPacketHandlers}（六端共用一份）。 */
    private static void handleExportBatch(ServerPlayer player, List<Integer> ids, boolean removeAfter) {
        ServerPacketHandlers.exportBatch(player, ids, removeAfter);
    }

    /** 读取留在本端，业务已下沉到 {@link ServerPacketHandlers}。 */
    private static void handleImportBatch(ServerPlayer player, FriendlyByteBuf data) {
        List<MaidFileData> list = MaidFilePackets.readMaidFileDataList(data);
        boolean keepBaubles = data.readBoolean();
        boolean deleteAfterImport = data.readBoolean();
        ServerPacketHandlers.importBatch(player, list, keepBaubles, deleteAfterImport);
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

    /** 读取留在本端，业务已下沉到 {@link ServerPacketHandlers}。 */
    private static void handleServerExportBatch(ServerPlayer player, FriendlyByteBuf data) {
        List<IMaidFileNetwork.PlayerExportRequest> requests =
                MaidInfoCodec.readPlayerExportRequests(data);
        ServerPacketHandlers.serverExportBatch(player, requests);
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

    /** 读取留在本端，业务已下沉到 {@link ServerPacketHandlers}。 */
    private static void handleRequestBackupExport(ServerPlayer player, FriendlyByteBuf data) {
        String ownerUuid = data.readUtf(MaidFilePackets.MAX_TEXT_LEN);
        String maidUuid = data.readUtf(MaidFilePackets.MAX_TEXT_LEN);
        String fileName = data.readUtf(MaidFilePackets.MAX_TEXT_LEN);
        ServerPacketHandlers.requestBackupExport(player, ownerUuid, maidUuid, fileName);
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
     * 读取留在本端，业务已下沉到 {@link ServerPacketHandlers}。
     *
     * <p>读取本身也包在 try 里：畸形分片数据可能让 readUtf/readByteArray 抛异常，
     * 那条日志要能指明是「分片上传」出的问题，而不是落到外层通用 catch 里丢掉上下文。
     */
    private static void handleChunkedUpload(ServerPlayer player, FriendlyByteBuf data) {
        try {
            String kind = data.readUtf(MaidFilePackets.MAX_TEXT_LEN);
            int sessionId = data.readVarInt();
            int total = data.readVarInt();
            int index = data.readVarInt();
            byte[] chunk = data.readByteArray(
                    io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.CHUNK_BYTES);
            ServerPacketHandlers.chunkedUpload(player, kind, sessionId, total, index, chunk);
        } catch (Throwable e) {
            Constants.LOG.warn("[maid_file_manager] 分片上传处理失败（读取阶段）: {}", e.toString());
        }
    }

}
