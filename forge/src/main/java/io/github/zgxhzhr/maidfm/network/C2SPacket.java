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
                // 【诊断期】临时提到 INFO：用于确认服务端到底有没有收到 C2S 包。
                // 排查完（包通道/握手问题）后应改回 debug。
                Constants.LOG.info("[maid_file_manager] S<-C RECV: id={}, player={}", id,
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
                    handleExportWriteAck(player, data.readBoolean());
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

    private static void handleRequestMaidList(ServerPlayer player) {
        // 频次限制：同一玩家 1~5 秒内重复请求直接丢弃（这类请求在主线程同步跑，
        // 循环发包能把服务器卡死，且不需要 OP 权限）
        if (!io.github.zgxhzhr.maidfm.network.RequestThrottle.allow(player.getUUID(), "maid_list", 1000L)) {
            return;
        }
        List<MaidInfo> list = MaidTransferService.listOwnMaids(player);
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        MaidFilePackets.writeMaidInfoList(buf, list);
        ServerNetworkBridge.sendToPlayer(player, MaidFilePackets.ID_MAID_LIST, buf);
    }

    private static void handleExportMaid(ServerPlayer player, int entityId) {
        // 旧单发通道与批量通道做的是同一件重活（含生成实体/写盘），
        // 批量通道已加频控，这条不能成为绕过口（第六轮 N3）。
        if (!io.github.zgxhzhr.maidfm.network.RequestThrottle.allow(player.getUUID(), "export_maid", 1000L)) {
            return;
        }
        MaidFileData data = MaidTransferService.exportMaidToData(player, entityId);
        if (data != null) {
            sendExportResults(player, List.of(data));
        } else {
            sendFeedback(player, Component.translatable("maid_file_manager.export.fail.unknown"));
        }
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
        sendFeedback(player, MaidTransferService.withBaubleDetails(result));
    }

    private static void handleExportBatch(ServerPlayer player, List<Integer> ids, boolean removeAfter) {
        // 频次限制：这三个是写盘/生成实体类 handler，比只读请求更重；
        // 原先只有 3 个只读 handler 有节流，批量导出/导入与备份导出可被循环刷。
        if (!io.github.zgxhzhr.maidfm.network.RequestThrottle.allow(player.getUUID(), "export_batch", 1000L)) {
            return;
        }
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
            // 空批次绝不能登记待删：客户端对空结果**不发 ack**（onExportResultReceived 拿到空表直接返回），
            // 登记项会一直占到 TTL 过期，期间下一次「导出并移除」被误报 remove_busy、静默降级成只导出（第六轮 N4）。
            if (removeAfter && !exportedIds.isEmpty()) {
                // 【P2-10】不再立刻 discard：服务端只能确认"包已发出"，无法确认客户端写盘成功。
                // 若客户端写盘失败（磁盘满/权限/路径异常），立刻删除就是"女仆没了、文件也没了"的
                // 不可逆数据丢失。改为登记待删，等客户端回执 ID_EXPORT_WRITE_ACK 再删；
                // 客户端不回执则永不删除——留着女仆的代价远小于丢数据。
                // 同一玩家同一时刻只允许一笔待删登记。已有登记时不覆盖 —— 覆盖会让「旧回执」
                // 删除**新批次**的女仆，而新批次文件可能根本没写成功，构成不可逆数据丢失。
                // putIfAbsent 前先清掉已过 TTL 的陈旧登记：TTL 原先只在 ack 到达时判，
                // 回执永远不来时该玩家会一直命中 putIfAbsent != null → 「导出并移除」永久失效。
                Long prevPendingAt = PENDING_REMOVAL_AT.get(player.getUUID());
                if (prevPendingAt != null
                        && System.currentTimeMillis() - prevPendingAt > PENDING_REMOVAL_TTL_MS) {
                    PENDING_REMOVAL.remove(player.getUUID());
                    PENDING_REMOVAL_AT.remove(player.getUUID());
                    Constants.LOG.warn("[maid_file_manager] 陈旧待删登记已过 TTL，清掉后重建: player={}",
                            player.getName().getString());
                }
                if (PENDING_REMOVAL.putIfAbsent(player.getUUID(), new ArrayList<>(exportedIds)) != null) {
                    Constants.LOG.warn("[maid_file_manager] 上一笔待删回执未到，本次导出不执行移除: player={}, 本批 {} 个",
                            player.getName().getString(), exportedIds.size());
                    sendFeedback(player, Component.translatable("maid_file_manager.server.remove_busy"));
                } else {
                    PENDING_REMOVAL_AT.put(player.getUUID(), System.currentTimeMillis());
                    Constants.LOG.info("[maid_file_manager] 导出并移除：登记待删 {} 个实体，等待客户端写盘回执 player={}",
                            exportedIds.size(), player.getName().getString());
                }
            }
            Constants.LOG.debug("[maid_file_manager] EXPORT_BATCH: player={} sent={} removed={}",
                    player.getName().getString(), results.size(), removedCount);
        }
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
        // Curios 饰品裁剪提示：未声明模组的饰品不会随女仆迁移，必须明确告知发起导出的人。
        // curiosDropTip() 取出即清零，同一批次只提示一次。
        net.minecraft.network.chat.Component curiosTip =
                io.github.zgxhzhr.maidfm.service.MaidTransferService.curiosDropTip();
        if (curiosTip != null) {
            sendFeedback(player, curiosTip);
        }
        List<byte[]> blobs = MaidFilePackets.serializeMaidDataBatch(results);
        if (blobs == null) {
            Constants.LOG.error("[maid_file_manager] 导出结果序列化失败，拒绝发送: player={}",
                    player.getName().getString());
            sendFeedback(player, Component.translatable("maid_file_manager.server.serialize_failed"));
            return false;
        }
        // 允许分片兜底：整包体积超限时不再拒绝，改走 ID_CHUNKED_DOWNLOAD 分片下发。
        // 1.20.x 的 32767 单包上限对 S2C 同样生效，带头像的女仆导出必然超限。
        String reject = MaidFilePackets.checkMaidDataBatchForWire(blobs, MaidFilePackets.MAX_EXPORT_IDS, true);
        if (reject != null) {
            Constants.LOG.warn("[maid_file_manager] 导出结果预检被拒绝: player={}, count={}, reason={}",
                    player.getName().getString(), blobs.size(), reject);
            sendFeedback(player, Component.translatable("maid_file_manager.server.prefix").append(reject));
            return false;
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        MaidFilePackets.writeMaidDataBlobs(buf, blobs);
        byte[] encoded = new byte[buf.readableBytes()];
        buf.getBytes(0, encoded);
        if (encoded.length > io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.CHUNK_BYTES) {
            if (!sendChunkedDownload(player, io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.KIND_EXPORT_BATCH, encoded)) {
                return false;
            }
            // 分片链路已修复（客户端改为 deserializeMaidFileData(blob)，不再误读 VarInt）
        } else {
            ServerNetworkBridge.sendToPlayer(player, MaidFilePackets.ID_EXPORT_BATCH_RESULT, buf);
        }
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
            sendFeedback(player, Component.translatable("maid_file_manager.server.unknown_config"));
            return;
        }
        broadcastServerConfig(player.server);
    }

    /** OP 修改自己维护的禁用清单（物品 / 模组 / Curios 槽位）：权限校验 → 收窄写入 → 广播同步 */
    private static void handleSetServerListConfig(ServerPlayer player, String key, List<String> ids) {
        if (!player.hasPermissions(2)) {
            Constants.LOG.warn("[maid_file_manager] SET_SERVER_LIST_CONFIG rejected (no OP): player={}, key={}",
                    player.getName().getString(), key);
            sendFeedback(player, Component.translatable("maid_file_manager.config.fail.no_permission"));
            return;
        }
        List<String> values;
        if (io.github.zgxhzhr.maidfm.config.MaidConfigManager.KEY_BAUBLE_BLOCKED_LIST.equals(key)) {
            // 只保存 OP 自己维护的禁用项；整合包黑名单由 bauble_import.json 托管、游戏内只读，不在此写入
            values = MaidTransferService.sanitizeBaubleBlockedList(ids);
        } else if (io.github.zgxhzhr.maidfm.config.MaidConfigManager.KEY_BAUBLE_BLOCKED_NAMESPACES.equals(key)
                || io.github.zgxhzhr.maidfm.config.MaidConfigManager.KEY_BAUBLE_BLOCKED_CURIOS_SLOTS.equals(key)) {
            // 模组 / 槽位清单：客户端提交可被篡改，服务端按标识符字符集收窄
            values = MaidTransferService.sanitizeIdentifierList(ids);
        } else {
            Constants.LOG.warn("[maid_file_manager] SET_SERVER_LIST_CONFIG rejected (unknown key): {}", key);
            return;
        }
        io.github.zgxhzhr.maidfm.config.MaidConfigManager.setServerListConfig(key, values);
        broadcastServerConfig(player.server);
    }

    /** OP 统一导出：返回所有在线玩家（以各玩家为中心）的女仆分组列表 */
    private static void handleRequestServerExportList(ServerPlayer player) {
        if (!player.hasPermissions(2)) {
            sendFeedback(player, Component.translatable("maid_file_manager.server.bulk_export_op_only"));
            return;
        }
        List<IMaidFileNetwork.PlayerMaidGroup> groups =
                io.github.zgxhzhr.maidfm.service.MaidServerCommands.collectOnlinePlayerMaids(player.server);
        // 组数维度预检：接收端 readPlayerMaidGroups 硬上限 128，必须在分配缓冲/编码前拒绝，
        // 否则数百在线时既可能分配超大缓冲，又会在客户端解码端炸包断连
        if (groups.size() > MaidFilePackets.MAX_PLAYER_GROUPS) {
            sendFeedback(player, Component.translatable("maid_file_manager.server.too_many_players", groups.size(), MaidFilePackets.MAX_PLAYER_GROUPS));
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
            sendFeedback(player, Component.translatable("maid_file_manager.server.list_too_large", size / 1024, MaidFilePackets.MAX_PACKET_BYTES / 1024));
            // 未进入发送流程，缓冲由本方法释放；进入 sendToPlayer 后无论成败都由其内部释放
            buf.release();
            return;
        }
        ServerNetworkBridge.sendToPlayer(player, MaidFilePackets.ID_SERVER_EXPORT_LIST, buf);
    }

    /** OP 统一导出提交：代各玩家导出女仆到服务端磁盘 maid_exports/<玩家名>/ */
    private static void handleServerExportBatch(ServerPlayer player, FriendlyByteBuf data) {
        if (!player.hasPermissions(2)) {
            sendFeedback(player, Component.translatable("maid_file_manager.server.bulk_export_op_only"));
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
        // 频次限制：同一玩家 1~5 秒内重复请求直接丢弃（这类请求在主线程同步跑，
        // 循环发包能把服务器卡死，且不需要 OP 权限）
        if (!io.github.zgxhzhr.maidfm.network.RequestThrottle.allow(player.getUUID(), "maid_profile", 1000L)) {
            return;
        }
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
        // 频次限制：同一玩家 1~5 秒内重复请求直接丢弃（这类请求在主线程同步跑，
        // 循环发包能把服务器卡死，且不需要 OP 权限）
        if (!io.github.zgxhzhr.maidfm.network.RequestThrottle.allow(player.getUUID(), "backup_list", 5000L)) {
            return;
        }
        // 权限：OP 可见全部玩家备份，非 OP 仅可见自己名下的备份
        List<IMaidFileNetwork.BackupOwner> owners =
                io.github.zgxhzhr.maidfm.service.MaidBackupService.scan(
                        player.server, player.getUUID(), player.hasPermissions(2));
        // 发送前按接收端解码维度预检：主人组数、总字节均需在上限内，
        // 超限只回执提示，绝不发坏包把客户端断连
        if (owners.size() > MaidFilePackets.MAX_BACKUP_OWNERS) {
            sendFeedback(player, Component.translatable("maid_file_manager.server.too_many_backup_owners", owners.size(), MaidFilePackets.MAX_BACKUP_OWNERS));
            return;
        }
        FriendlyByteBuf probe = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            MaidFilePackets.writeBackupOwners(probe, owners);
            int size = probe.readableBytes();
            if (size > MaidFilePackets.MAX_PACKET_BYTES) {
                Constants.LOG.warn("[maid_file_manager] 备份列表 {} 字节超过单包上限 {}，拒绝发送",
                        size, MaidFilePackets.MAX_PACKET_BYTES);
                sendFeedback(player, Component.translatable("maid_file_manager.server.backup_list_too_large", size / 1024, MaidFilePackets.MAX_PACKET_BYTES / 1024));
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
    public static void broadcastServerConfig(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            sendServerConfig(p);
        }
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
                io.github.zgxhzhr.maidfm.network.ChunkedUploadDispatcher.dispatch(player, kind, full);
            }
        } catch (Throwable e) {
            Constants.LOG.warn("[maid_file_manager] 分片上传处理失败: {}", e.toString());
        }
    }

    /**
     * 分片下发（服务端 → 客户端）。1.20.x 的 32767 字节上限对 S2C 同样生效，
     * 导出结果（带照片的 .maid）必然超限，故与 C2S 方向对称地切片发送。
     */
    private static boolean sendChunkedDownload(ServerPlayer player, String kind, byte[] payload) {
        if (payload == null || payload.length == 0) {
            return true;
        }

        // 总量预检：接收端 ChunkedUploadAssembler 会在首片就按 declared > MAX_TOTAL_BYTES 拒收，
        // 发送端照样发满几百片纯属浪费（且预检前已分配多份副本）。超限直接回执失败并返回 false ——
        // 调用方据此不登记待删，避免「文件没发出去却删了女仆」。
        if (payload.length > io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.MAX_TOTAL_BYTES) {
            Constants.LOG.warn("[maid_file_manager] 分片下发超总量上限，拒绝发送: player={} bytes={} limit={}",
                    player.getName().getString(), payload.length, io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.MAX_TOTAL_BYTES);
            sendFeedback(player, net.minecraft.network.chat.Component.translatable(
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
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            buf.writeUtf(kind == null ? "" : kind, MaidFilePackets.MAX_TEXT_LEN);
            buf.writeVarInt(sessionId);
            buf.writeVarInt(total);
            buf.writeVarInt(i);
            buf.writeByteArray(chunk);
            ServerNetworkBridge.sendToPlayer(player, MaidFilePackets.ID_CHUNKED_DOWNLOAD, buf);
        }
        Constants.LOG.info("[maid_file_manager] 导出结果分片下发: player={} bytes={} chunks={}",
                player.getName().getString(), payload.length, total);
        return true;
    }

    /** 待删除实体：玩家 UUID → 实体 ID 列表（等客户端写盘回执后才删） */
    private static final java.util.Map<java.util.UUID, List<Integer>> PENDING_REMOVAL =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 待删登记的写入时刻（毫秒）：客户端回执永远不来时用它判定超时 */
    private static final java.util.Map<java.util.UUID, Long> PENDING_REMOVAL_AT =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 待删登记存活时长。客户端崩溃/断线导致回执永远不来时不能让登记永久占位，
     * 否则「导出并移除」会永久失效（女仆一直不删）且玩家无法察觉。
     */
    private static final long PENDING_REMOVAL_TTL_MS = 5 * 60 * 1000L;

    /** 玩家断线时清掉其待删登记：回执不可能再来，留着只会阻塞后续导出 */
    public static void clearPendingRemoval(java.util.UUID playerId) {
        if (playerId != null) {
            PENDING_REMOVAL.remove(playerId);
            PENDING_REMOVAL_AT.remove(playerId);
        }
    }

    /**
     * 客户端写盘回执。
     *
     * <p>只有 {@code success=true} 才真正删除实体与照片；失败则放弃删除并把原因写进日志，
     * 女仆留在世界里（玩家可以重新导出）。这是对「导出并移除」的数据安全兜底。
     */
    private static void handleExportWriteAck(ServerPlayer player, boolean success) {
        List<Integer> ids = PENDING_REMOVAL.remove(player.getUUID());
        Long pendingAt = PENDING_REMOVAL_AT.remove(player.getUUID());
        if (ids == null || ids.isEmpty()) {
            Constants.LOG.debug("[maid_file_manager] 收到导出回执但没有待删记录: player={}",
                    player.getName().getString());
            return;
        }
        if (pendingAt != null && System.currentTimeMillis() - pendingAt > PENDING_REMOVAL_TTL_MS) {
            Constants.LOG.warn("[maid_file_manager] 待删登记已超时（{} ms），丢弃: player={}",
                    PENDING_REMOVAL_TTL_MS, player.getName().getString());
            return;
        }
        if (!success) {
            Constants.LOG.warn("[maid_file_manager] 客户端写盘失败，已放弃删除女仆（{} 个），player={}",
                    ids.size(), player.getName().getString());
            return;
        }
        int removed = 0;
        for (Integer entityId : ids) {
            Entity e = player.level().getEntity(entityId);
            if (e == null) {
                continue;
            }
            // 防御性校验：PENDING_REMOVAL 是服务端自己登记的，理论上玩家改不了，
            // 但删除是不可逆操作，删前再确认一次「确实是女仆」且「确实归这个玩家」。
            // 不校验类型的话，任何被塞进队列的非女仆实体都会被直接 discard。
            if (!(e instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid)
                    || !maid.isOwnedBy(player)) {
                Constants.LOG.warn("[maid_file_manager] 待删实体校验未通过，跳过: entityId={} player={}",
                        entityId, player.getName().getString());
                continue;
            }
            try {
                MaidTransferService.unregisterMaidWorldData(e);
                java.util.UUID photoUuid = e.getUUID();
                e.discard();
                try {
                    io.github.zgxhzhr.maidfm.data.MaidPhotoStore.delete(
                            io.github.zgxhzhr.maidfm.platform.Services.PLATFORM.get().getGameDir(), photoUuid);
                } catch (Throwable ignored) {
                }
                removed++;
            } catch (Throwable t) {
                Constants.LOG.warn("[maid_file_manager] 删除实体失败: entityId={} err={}", entityId, t.toString());
            }
        }
        Constants.LOG.info("[maid_file_manager] 导出写盘已确认，删除女仆 {} 个 player={}",
                removed, player.getName().getString());
    }
}
