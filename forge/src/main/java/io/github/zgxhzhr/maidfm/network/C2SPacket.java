package io.github.zgxhzhr.maidfm.network;

import io.github.zgxhzhr.maidfm.Constants;
import io.github.zgxhzhr.maidfm.data.ImportResult;
import io.github.zgxhzhr.maidfm.data.MaidFileData;
import io.github.zgxhzhr.maidfm.data.MaidInfo;
import io.github.zgxhzhr.maidfm.data.MaidProfile;
import io.github.zgxhzhr.maidfm.data.MaidProfileView;
import io.github.zgxhzhr.maidfm.service.MaidProfileService;
import io.github.zgxhzhr.maidfm.service.MaidTransferService;
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
                } else if (MaidFilePackets.ID_CLIENT_CONSENT.equals(id)) {
                    boolean allow = data.readBoolean();
                    io.github.zgxhzhr.maidfm.config.MaidConfigManager.handleClientConsent(player.getUUID(), allow);
                } else if (MaidFilePackets.ID_SET_SERVER_CONFIG.equals(id)) {
                    String key = data.readUtf(MaidFilePackets.MAX_TEXT_LEN);
                    boolean value = data.readBoolean();
                    handleSetServerConfig(player, key, value);
                } else if (MaidFilePackets.ID_SET_SERVER_BAUBLE_BLOCKED_LIST.equals(id)) {
                    handleSetServerBaubleBlockedList(player, MaidFilePackets.readStringList(data));
                } else if (MaidFilePackets.ID_REQUEST_SERVER_EXPORT_LIST.equals(id)) {
                    handleRequestServerExportList(player);
                } else if (MaidFilePackets.ID_SERVER_EXPORT_BATCH.equals(id)) {
                    handleServerExportBatch(player, data);
                } else if (MaidFilePackets.ID_REQUEST_MAID_PROFILE.equals(id)) {
                    handleRequestMaidProfile(player, data.readVarInt());
                } else if (MaidFilePackets.ID_SAVE_MAID_PROFILE.equals(id)) {
                    int entityId = data.readVarInt();
                    handleSaveMaidProfile(player, entityId, MaidFilePackets.readMaidProfile(data));
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
                        sendFeedback(player, Component.literal("[女仆档案管理] 服务端处理失败，请联系服主查看日志（错误位置："
                                + id + "）"));
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

    private static void handleRequestMaidList(ServerPlayer player) {
        List<MaidInfo> list = MaidTransferService.listOwnMaids(player);
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        MaidFilePackets.writeMaidInfoList(buf, list);
        ServerNetworkBridge.sendToPlayer(player, MaidFilePackets.ID_MAID_LIST, buf);
    }

    private static void handleExportMaid(ServerPlayer player, int entityId) {
        MaidFileData data = MaidTransferService.exportMaidToData(player, entityId);
        if (data != null) {
            sendExportResults(player, List.of(data));
        } else {
            sendFeedback(player, Component.translatable("maid_file_manager.export.fail.unknown"));
        }
    }

    private static void handleImportFile(ServerPlayer player, FriendlyByteBuf data) {
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
        sendFeedback(player, MaidTransferService.withBaubleDetails(result));
    }

    private static void handleExportBatch(ServerPlayer player, List<Integer> ids, boolean removeAfter) {
        // 两阶段处理：先完成全部序列化并通过体积预检、确认结果包已发送，之后才允许删除实体。
        // 若边导出边删除，预检失败时女仆已被 discard 而文件未送达，构成不可逆数据丢失
        List<MaidFileData> results = new ArrayList<>(ids.size());
        List<Integer> exportedIds = new ArrayList<>(ids.size());
        for (Integer entityId : ids) {
            if (entityId == null) {
                continue;
            }
            MaidFileData data = MaidTransferService.exportMaidToData(player, entityId);
            if (data != null) {
                results.add(data);
                exportedIds.add(entityId);
            }
        }
        if (sendExportResults(player, results)) {
            int removedCount = 0;
            if (removeAfter) {
                for (Integer entityId : exportedIds) {
                    Entity e = player.level().getEntity(entityId);
                    if (e != null) {
                        // 必须在 discard 前清除 MaidWorldData 存活登记：discard 时
                        // isAlive=false，TLM 不会清理既有记录，残留登记会被双胞胎
                        // 拦截误判为女仆仍存活，导致"导出并移除后再导入"被拒。
                        MaidTransferService.unregisterMaidWorldData(e);
                        e.discard();
                        removedCount++;
                    }
                }
            }
            Constants.LOG.debug("[maid_file_manager] EXPORT_BATCH: player={} sent={} removed={}",
                    player.getName().getString(), results.size(), removedCount);
        }
    }

    private static void handleImportBatch(ServerPlayer player, FriendlyByteBuf data) {
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

    private static void sendFeedback(ServerPlayer player, Component message) {
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeComponent(message);
        ServerNetworkBridge.sendToPlayer(player, MaidFilePackets.ID_FEEDBACK, buf);
    }

    /** 批量导入 + 删除源文件：汇总文案 + 逐项 spawned 标志（顺序与请求对齐） */
    private static void sendImportBatchResult(ServerPlayer player, Component summary, List<Boolean> spawned) {
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeComponent(summary);
        MaidFilePackets.writeBooleanList(buf, spawned);
        ServerNetworkBridge.sendToPlayer(player, MaidFilePackets.ID_IMPORT_BATCH_RESULT, buf);
    }

    /**
     * 发送导出数据回客户端写文件。
     * 发送前按客户端解码端的全部契约维度（512 条目/单文件 512 KiB/整包 1 MiB 自律红线）预检，
     * 序列化字节在预检与实际发包间复用；任一维度不过只回执明确失败并返回 false，
     * 调用方（尤其 removeAfter 路径）据此保证实体绝不被 discard。
     *
     * @return true=已发送；false=序列化失败或契约预检被拒（调用方不得删除实体）
     */
    private static boolean sendExportResults(ServerPlayer player, List<MaidFileData> results) {
        List<byte[]> blobs = MaidFilePackets.serializeMaidDataBatch(results);
        if (blobs == null) {
            Constants.LOG.error("[maid_file_manager] 导出结果序列化失败，拒绝发送: player={}",
                    player.getName().getString());
            sendFeedback(player, Component.literal("[女仆档案管理] 导出数据序列化失败，请重试；如反复失败请联系服主查看日志"));
            return false;
        }
        String reject = MaidFilePackets.checkMaidDataBatchForWire(blobs, MaidFilePackets.MAX_EXPORT_IDS);
        if (reject != null) {
            Constants.LOG.warn("[maid_file_manager] 导出结果预检被拒绝: player={}, count={}, reason={}",
                    player.getName().getString(), blobs.size(), reject);
            sendFeedback(player, Component.literal("[女仆档案管理] " + reject));
            return false;
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        MaidFilePackets.writeMaidDataBlobs(buf, blobs);
        ServerNetworkBridge.sendToPlayer(player, MaidFilePackets.ID_EXPORT_BATCH_RESULT, buf);
        return true;
    }

    /** 客户端请求修改服务端配置：先校验 OP 权限，再写文件并同步给所有在线客户端 */
    private static void handleSetServerConfig(ServerPlayer player, String key, boolean value) {
        if (!player.hasPermissions(2)) {
            Constants.LOG.warn("[maid_file_manager] SET_SERVER_CONFIG rejected (no OP): player={} key={}",
                    player.getName().getString(), key);
            sendFeedback(player, Component.translatable("maid_file_manager.config.fail.no_permission"));
            return;
        }
        // 未知键直接回执失败，不得把旧配置向全服重新广播
        if (!io.github.zgxhzhr.maidfm.config.MaidConfigManager.setServerConfig(key, value)) {
            sendFeedback(player, Component.literal("[女仆档案管理] 未知配置项，修改已拒绝"));
            return;
        }
        broadcastServerConfig(player.server);
    }

    /** OP 修改「禁用携带的饰品 ID 列表」：权限校验 → 收窄写入 → 广播同步；整合包托管时拒绝 */
    private static void handleSetServerBaubleBlockedList(ServerPlayer player, List<String> ids) {
        if (!player.hasPermissions(2)) {
            Constants.LOG.warn("[maid_file_manager] SET_SERVER_BAUBLE_BLOCKED_LIST rejected (no OP): player={}",
                    player.getName().getString());
            sendFeedback(player, Component.translatable("maid_file_manager.config.fail.no_permission"));
            return;
        }
        // 整合包配置（bauble_import.json）存在时黑/白名单被托管，游戏内（含 OP）不可改
        if (!io.github.zgxhzhr.maidfm.config.MaidConfigManager.setServerBaubleBlockedList(
                MaidTransferService.sanitizeBaubleBlockedList(ids))) {
            sendFeedback(player, Component.translatable("maid_file_manager.config.fail.bauble_managed"));
            return;
        }
        broadcastServerConfig(player.server);
    }

    /** OP 统一导出：返回所有在线玩家（以各玩家为中心）的女仆分组列表 */
    private static void handleRequestServerExportList(ServerPlayer player) {
        if (!player.hasPermissions(2)) {
            sendFeedback(player, Component.literal("[女仆档案管理] 统一导出仅 OP 可用"));
            return;
        }
        List<IMaidFileNetwork.PlayerMaidGroup> groups =
                io.github.zgxhzhr.maidfm.service.MaidServerCommands.collectOnlinePlayerMaids(player.server);
        // 组数维度预检：接收端 readPlayerMaidGroups 硬上限 128，必须在分配缓冲/编码前拒绝，
        // 否则数百在线时既可能分配超大缓冲，又会在客户端解码端炸包断连
        if (groups.size() > MaidFilePackets.MAX_PLAYER_GROUPS) {
            sendFeedback(player, Component.literal(String.format(java.util.Locale.ROOT,
                    "在线玩家数超过统一导出上限（%d > %d），无法生成列表，请缩小范围后重试",
                    groups.size(), MaidFilePackets.MAX_PLAYER_GROUPS)));
            return;
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        MaidFilePackets.writePlayerMaidGroups(buf, groups);
        // 发送前按真实线格式预检总字节：全服女仆聚合可能远超单包上限，
        // 超限必须回执明确提示而非发出坏包把 OP 客户端断连
        int size = buf.readableBytes();
        if (size > MaidFilePackets.MAX_PACKET_BYTES) {
            Constants.LOG.warn("[maid_file_manager] 统一导出列表 {} 字节超过单包上限 {}，拒绝发送",
                    size, MaidFilePackets.MAX_PACKET_BYTES);
            sendFeedback(player, Component.literal(String.format(java.util.Locale.ROOT,
                    "全服女仆列表数据过大（%d KB > %d KB），请缩小查询范围后重试",
                    size / 1024, MaidFilePackets.MAX_PACKET_BYTES / 1024)));
            // 未进入发送流程，缓冲由本方法释放；进入 sendToPlayer 后无论成败都由其内部释放
            buf.release();
            return;
        }
        ServerNetworkBridge.sendToPlayer(player, MaidFilePackets.ID_SERVER_EXPORT_LIST, buf);
    }

    /** OP 统一导出提交：代各玩家导出女仆到服务端磁盘 maid_exports/<玩家名>/ */
    private static void handleServerExportBatch(ServerPlayer player, FriendlyByteBuf data) {
        if (!player.hasPermissions(2)) {
            sendFeedback(player, Component.literal("[女仆档案管理] 统一导出仅 OP 可用"));
            return;
        }
        List<IMaidFileNetwork.PlayerExportRequest> requests = MaidFilePackets.readPlayerExportRequests(data);
        Component result = io.github.zgxhzhr.maidfm.service.MaidServerCommands
                .exportForPlayers(player.server, requests);
        sendFeedback(player, result);
    }

    /**
     * 请求女仆档案：非本人直接拒绝（不返回任何只读数据），本人则回发档案视图。
     * 归属校验在实体类型判断之外由 {@link MaidProfileService#buildView} 再兜底一次。
     */
    private static void handleRequestMaidProfile(ServerPlayer player, int entityId) {
        Entity e = player.level().getEntity(entityId);
        if (!(e instanceof EntityMaid maid) || !maid.isOwnedBy(player)) {
            sendFeedback(player, Component.translatable("gui.maid_file_manager.profile.no_permission"));
            return;
        }
        MaidProfileView view = MaidProfileService.buildView(player, maid);
        if (view == null) {
            sendFeedback(player, Component.translatable("gui.maid_file_manager.profile.no_permission"));
            return;
        }
        FriendlyByteBuf out = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        MaidFilePackets.writeMaidProfileView(out, view);
        ServerNetworkBridge.sendToPlayer(player, MaidFilePackets.ID_MAID_PROFILE, out);
    }

    /**
     * 保存女仆档案：校验归属后写入实体并回执（复用 ID_FEEDBACK）。
     * 档案界面保存时同步把背景故事写回 TLM AI 人设，保证内存态与存档一致。
     */
    private static void handleSaveMaidProfile(ServerPlayer player, int entityId, MaidProfile profile) {
        if (profile == null) {
            sendFeedback(player, Component.translatable("gui.maid_file_manager.profile.save_failed"));
            return;
        }
        Entity e = player.level().getEntity(entityId);
        if (!(e instanceof EntityMaid maid) || !maid.isOwnedBy(player)) {
            sendFeedback(player, Component.translatable("gui.maid_file_manager.profile.no_permission"));
            return;
        }
        MaidProfileService.writeToMaid(maid, profile, true);
        sendFeedback(player, Component.translatable("gui.maid_file_manager.profile.save_ok"));
    }

    /** 备份管理：请求服务端返回可浏览的自动备份列表（按权限过滤后回发） */
    private static void handleRequestBackupList(ServerPlayer player) {
        // 权限：OP 可见全部玩家备份，非 OP 仅可见自己名下的备份
        List<IMaidFileNetwork.BackupOwner> owners =
                io.github.zgxhzhr.maidfm.service.MaidBackupService.scan(
                        player.server, player.getUUID(), player.hasPermissions(2));
        // 发送前按接收端解码维度预检：主人组数、总字节均需在上限内，
        // 超限只回执提示，绝不发坏包把客户端断连
        if (owners.size() > MaidFilePackets.MAX_BACKUP_OWNERS) {
            sendFeedback(player, Component.literal(String.format(java.util.Locale.ROOT,
                    "[女仆档案管理] 备份主人数量超过上限（%d > %d），无法列出，请清理备份后重试",
                    owners.size(), MaidFilePackets.MAX_BACKUP_OWNERS)));
            return;
        }
        FriendlyByteBuf probe = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            MaidFilePackets.writeBackupOwners(probe, owners);
            int size = probe.readableBytes();
            if (size > MaidFilePackets.MAX_PACKET_BYTES) {
                Constants.LOG.warn("[maid_file_manager] 备份列表 {} 字节超过单包上限 {}，拒绝发送",
                        size, MaidFilePackets.MAX_PACKET_BYTES);
                sendFeedback(player, Component.literal(String.format(java.util.Locale.ROOT,
                        "[女仆档案管理] 备份列表数据过大（%d KB > %d KB），请清理备份后重试",
                        size / 1024, MaidFilePackets.MAX_PACKET_BYTES / 1024)));
                return;
            }
        } finally {
            probe.release();
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        MaidFilePackets.writeBackupOwners(buf, owners);
        ServerNetworkBridge.sendToPlayer(player, MaidFilePackets.ID_BACKUP_LIST, buf);
    }

    /** 备份管理：请求服务端导出某条备份（校验权限后读取并回传为 .maid 数据） */
    private static void handleRequestBackupExport(ServerPlayer player, FriendlyByteBuf data) {
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
            sendFeedback(player, Component.literal("[女仆档案管理] 该备份体积过大，无法通过服务器导出"));
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
        buf.writeBoolean(io.github.zgxhzhr.maidfm.config.MaidConfigManager.isBaubleStripAttributes());
        MaidFilePackets.writeStringList(buf,
                io.github.zgxhzhr.maidfm.config.MaidConfigManager.getBaubleBlockedList());
        // 整合包饰品配置：白名单 + 是否托管（客户端据此只读展示黑/白名单）
        MaidFilePackets.writeStringList(buf,
                io.github.zgxhzhr.maidfm.config.MaidConfigManager.getBaubleWhitelist());
        buf.writeBoolean(io.github.zgxhzhr.maidfm.config.MaidConfigManager.isBaubleConfigManaged());
        ServerNetworkBridge.sendToPlayer(player, MaidFilePackets.ID_SERVER_CONFIG_SYNC, buf);
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
}
