package io.github.zgxhzhr.maidfm;

import io.github.zgxhzhr.maidfm.config.MaidConfigManager;
import io.github.zgxhzhr.maidfm.data.ImportResult;
import io.github.zgxhzhr.maidfm.data.MaidFileData;
import io.github.zgxhzhr.maidfm.data.MaidInfo;
import io.github.zgxhzhr.maidfm.data.MaidProfile;
import io.github.zgxhzhr.maidfm.data.MaidProfileView;
import io.github.zgxhzhr.maidfm.network.IMaidFileNetwork;
import io.github.zgxhzhr.maidfm.network.MaidFilePackets;
import io.github.zgxhzhr.maidfm.network.MaidProfileCodec;
import io.github.zgxhzhr.maidfm.network.MaidBackupCodec;
import io.github.zgxhzhr.maidfm.network.MaidInfoCodec;
import io.github.zgxhzhr.maidfm.service.MaidBackupService;
import io.github.zgxhzhr.maidfm.service.MaidProfileService;
import io.github.zgxhzhr.maidfm.service.MaidServerCommands;
import io.github.zgxhzhr.maidfm.service.MaidTransferService;
import io.github.zgxhzhr.maidfm.service.BaubleHandler;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;

public class MaidFileModFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        // 模型名注册表：启动恢复 / 停止落盘
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(
                server -> io.github.zgxhzhr.maidfm.service.ModelNameRegistry.loadFromServer(server));
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(
                server -> io.github.zgxhzhr.maidfm.service.ModelNameRegistry.saveAndClear(server));
        Constants.LOG.info("Maid File Manager (Fabric) loading...");

        // 配置系统初始化（服务端/客户端 properties 文件）
        MaidConfigManager.init(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir());

        // 协议版本校验：客户端连上后会上报自身版本，不一致就断开并说明原因。
        // 只影响联机握手；.maid 文件格式与 PROTOCOL_VERSION 无关，跨版本仍可互导。
        registerC2SReceiver(MaidFilePackets.ID_PROTOCOL_CHECK, (server, player, buf) -> {
            String clientVersion;
            try {
                clientVersion = buf.readUtf(64);
            } catch (Throwable t) {
                clientVersion = "<unreadable>";
            }
            if (!MaidFilePackets.PROTOCOL_VERSION.equals(clientVersion)) {
                Constants.LOG.warn("[maid_file_manager] 协议版本不匹配，断开连接: player={} server={} client={}",
                        player.getName().getString(), MaidFilePackets.PROTOCOL_VERSION, clientVersion);
                player.connection.disconnect(Component.translatable(
                        "maid_file_manager.protocol.mismatch",
                        MaidFilePackets.PROTOCOL_VERSION, clientVersion));
            }
        });

        registerC2SReceiver(MaidFilePackets.ID_REQUEST_MAID_LIST, (server, player, buf) -> {
            // 业务逻辑已下沉到 ServerPacketHandlers（六端共用一份）；本端只做转发
            io.github.zgxhzhr.maidfm.service.ServerPacketHandlers.requestMaidList(player);
        });

        registerC2SReceiver(MaidFilePackets.ID_EXPORT_MAID, (server, player, buf) -> {
            io.github.zgxhzhr.maidfm.service.ServerPacketHandlers.exportMaid(player, buf.readInt());
        });

        // 批量导出：数据发回客户端写文件；removeAfter=true 时移除已导出的女仆
        registerC2SReceiver(MaidFilePackets.ID_EXPORT_BATCH, (server, player, buf) -> {
            java.util.List<Integer> ids = MaidFilePackets.readIntList(buf);
            boolean removeAfter = buf.readBoolean();
            io.github.zgxhzhr.maidfm.service.ServerPacketHandlers.exportBatch(player, ids, removeAfter);
        });

        registerC2SReceiver(MaidFilePackets.ID_IMPORT_FILE, (server, player, buf) -> {
            // 旧单发通道不能成为频控绕过口（第六轮 N3）
            if (!io.github.zgxhzhr.maidfm.network.RequestThrottle.allow(player.getUUID(), "import_file", 1000L)) { return; }
            MaidFileData maidData = MaidFilePackets.readMaidFileData(buf);
            boolean keepBaubles = buf.readBoolean();
            // 单文件通道与批量通道线格式保持一致（尾部为 deleteAfterImport）；
            // 单文件通道无对应 UI 删除流程，读掉但不触发回传
            buf.readBoolean();
            if (maidData == null) {
                sendFeedback(player, Component.translatable("maid_file_manager.import.fail.invalid"));
                return;
            }
            // 饰品导入策略由服务端配置决定，客户端不上传（杜绝非 OP 绕过）
            ImportResult result = MaidTransferService.importMaidFromData(
                    player, maidData, keepBaubles,
                    MaidConfigManager.isBaubleStripAttributes(),
                    MaidConfigManager.getBaubleBlockedList());
            // 单条导入同样要消费 Curios 批次统计（第六轮 N5）。
            BaubleHandler.curiosDropTip();
            sendFeedback(player, BaubleHandler.withBaubleDetails(result));
        });

        // 批量导入：成败统计只认 ImportResult.State 枚举，严禁反解中文展示文案
        registerC2SReceiver(MaidFilePackets.ID_IMPORT_BATCH, (server, player, buf) -> {
            // 频次限制：写盘/生成实体类 handler，原先只有只读请求有节流
            if (!io.github.zgxhzhr.maidfm.network.RequestThrottle.allow(player.getUUID(), "import_batch", 1000L)) {
                return;
            }
            List<MaidFileData> list = MaidFilePackets.readMaidFileDataList(buf);
            boolean keepBaubles = buf.readBoolean();
            boolean deleteAfterImport = buf.readBoolean();
            // 饰品导入策略以服务端配置为准（客户端不上传，杜绝非 OP 绕过）；
            // 禁用清单直接取服务端配置（含整合包 bauble_import.json 的中文名条目），不再做命名空间收窄
            boolean stripAttributes = MaidConfigManager.isBaubleStripAttributes();
            List<String> blockedList = MaidConfigManager.getBaubleBlockedList();
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
            // 导入路径同样会产生 Curios 批次统计，必须在每次导入结束时消费掉（第六轮 N5）。
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
        });

        // 分片上传：读一片 → 组装器 → 收齐后交给四端共用分发器
        registerC2SReceiver(MaidFilePackets.ID_CHUNKED_UPLOAD, (server, player, buf) -> {
            try {
                String kind = buf.readUtf(MaidFilePackets.MAX_TEXT_LEN);
                int sessionId = buf.readVarInt();
                int total = buf.readVarInt();
                int index = buf.readVarInt();
                byte[] chunk = buf.readByteArray(io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.CHUNK_BYTES);
                byte[] full = io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.accept(
                        player.getUUID(), kind, sessionId, total, index, chunk);
                if (full != null) {
                    io.github.zgxhzhr.maidfm.service.ChunkedUploadDispatcher.dispatch(player, kind, full);
                }
            } catch (Throwable e) {
                Constants.LOG.warn("[maid_file_manager] 分片上传处理失败: {}", e.toString());
            }
        });
        // 导出写盘回执：客户端确认文件已落盘后才删除女仆（P2-10 数据安全）
        registerC2SReceiver(MaidFilePackets.ID_EXPORT_WRITE_ACK, (server, player, buf) -> {
            io.github.zgxhzhr.maidfm.service.PendingRemovalRegistry.confirmAndRemove(player.getUUID(), buf.readBoolean(), player);
        });
        registerC2SReceiver(MaidFilePackets.ID_CLIENT_CONSENT, (server, player, buf) -> {
            boolean allow = buf.readBoolean();
            MaidConfigManager.handleClientConsent(player.getUUID(), allow);
        });

        registerC2SReceiver(MaidFilePackets.ID_SET_SERVER_CONFIG, (server, player, buf) -> {
            String key = buf.readUtf(MaidFilePackets.MAX_TEXT_LEN);
            boolean value = buf.readBoolean();
            io.github.zgxhzhr.maidfm.service.ServerPacketHandlers.setServerConfig(player, key, value);
        });

        // OP 修改自己维护的禁用清单（物品 / 模组 / Curios 槽位）：权限校验 → 收窄写入 → 广播同步
        registerC2SReceiver(MaidFilePackets.ID_SET_SERVER_BAUBLE_BLOCKED_LIST, (server, player, buf) -> {
            String listKey = buf.readUtf(MaidFilePackets.MAX_TEXT_LEN);
            java.util.List<String> ids = MaidFilePackets.readStringList(buf);
            io.github.zgxhzhr.maidfm.service.ServerPacketHandlers.setServerListConfig(player, listKey, ids);
        });

        // OP 统一导出：返回所有在线玩家（以各玩家为中心）的女仆分组列表
        registerC2SReceiver(MaidFilePackets.ID_REQUEST_SERVER_EXPORT_LIST, (server, player, buf) -> {
            io.github.zgxhzhr.maidfm.service.ServerPacketHandlers.requestServerExportList(player);
        });

        // OP 统一导出提交：代各玩家导出女仆到服务端磁盘 maid_exports/<玩家名>/
        registerC2SReceiver(MaidFilePackets.ID_SERVER_EXPORT_BATCH, (server, player, buf) -> {
            if (!player.hasPermissions(2)) {
                sendFeedback(player, Component.translatable("maid_file_manager.server.bulk_export_op_only"));
                return;
            }
            List<IMaidFileNetwork.PlayerExportRequest> requests = MaidInfoCodec.readPlayerExportRequests(buf);
            sendFeedback(player, MaidServerCommands.exportForPlayers(server, requests));
        });

        // 请求女仆档案：非本人直接拒绝（不返回任何只读数据），本人则回发档案视图
        registerC2SReceiver(MaidFilePackets.ID_REQUEST_MAID_PROFILE, (server, player, buf) -> {
            int entityId = buf.readVarInt();
            io.github.zgxhzhr.maidfm.service.ServerPacketHandlers.requestMaidProfile(player, entityId);
        });

        // 保存女仆档案：校验归属后写入实体并回执（复用 ID_FEEDBACK）
        registerC2SReceiver(MaidFilePackets.ID_SAVE_MAID_PROFILE, (server, player, buf) -> {
            int entityId = buf.readVarInt();
            MaidProfile profile = MaidProfileCodec.readMaidProfile(buf);
            io.github.zgxhzhr.maidfm.service.ServerPacketHandlers.saveMaidProfile(player, entityId, profile);
        });

        // 备份管理：服务端按权限过滤后返回可浏览的自动备份列表（OP 可见全部玩家，非 OP 仅自己）
        registerC2SReceiver(MaidFilePackets.ID_REQUEST_BACKUP_LIST, (server, player, buf) -> {
            io.github.zgxhzhr.maidfm.service.ServerPacketHandlers.requestBackupList(player);
        });
        // 客户端上报的「模型 ID → 显示名」映射：合并进服务端汇总表，
        // 备份列表解析模型名时优先查它（拿到别人私有模型中文名的唯一来源）
        registerC2SReceiver(MaidFilePackets.ID_MODEL_NAMES, (server, player, buf) -> {
            io.github.zgxhzhr.maidfm.service.ModelNameRegistry.mergeFromPairs(
                    MaidFilePackets.readModelNames(buf));
        });

        // 备份管理：服务端校验权限（OP 或该备份主人）后读取该备份并回传，客户端再写本地文件
        registerC2SReceiver(MaidFilePackets.ID_REQUEST_BACKUP_EXPORT, (server, player, buf) -> {
            // 频次限制：写盘/生成实体类 handler，原先只有只读请求有节流
            if (!io.github.zgxhzhr.maidfm.network.RequestThrottle.allow(player.getUUID(), "backup_export", 2000L)) {
                return;
            }
            String ownerUuid = buf.readUtf(MaidFilePackets.MAX_TEXT_LEN);
            String maidUuid = buf.readUtf(MaidFilePackets.MAX_TEXT_LEN);
            String fileName = buf.readUtf(MaidFilePackets.MAX_TEXT_LEN);
            MaidFileData data = MaidBackupService.readForExport(
                    server, player.getUUID(), player.hasPermissions(2), ownerUuid, maidUuid, fileName);
            if (data == null) {
                sendFeedback(player, Component.translatable("gui.maid_file_manager.backup.export_failed"));
                return;
            }
            byte[] blob = MaidFilePackets.serializeMaidFileData(data);
            // 预检单文件体积：超限只回执明确失败，绝不发坏包把客户端断连
            if (blob == null || blob.length > MaidFilePackets.MAX_SINGLE_FILE_BYTES) {
                Constants.LOG.warn("[maid_file_manager] 备份导出体积异常，拒绝发送: owner={}, maid={}, size={}",
                        ownerUuid, maidUuid, blob == null ? -1 : blob.length);
                sendFeedback(player, Component.translatable("maid_file_manager.server.backup_too_large"));
                return;
            }
            FriendlyByteBuf out = PacketByteBufs.create();
            out.writeVarInt(blob.length);
            out.writeBytes(blob);
            ServerPlayNetworking.send(player, MaidFilePackets.ID_BACKUP_EXPORT_RESULT, out);
        });

        // 玩家登录 → 仅向该玩家单播服务端配置（不再全服广播）；退出 → 清理同意状态
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                sendServerConfig(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            MaidConfigManager.removeClientConsent(handler.player.getUUID());
            // 清掉该玩家未完成的分片会话：否则中途断线的分片累积器会一直留在内存里
            io.github.zgxhzhr.maidfm.service.ChunkedUploadDispatcher.onPlayerDisconnect(handler.player.getUUID());
            // 同时清掉待删登记：回执不可能再来了，否则该玩家「导出并移除」会一直命中 remove_busy
            io.github.zgxhzhr.maidfm.service.PendingRemovalRegistry.clear(handler.player.getUUID());
        });

        // /maidfile exportall 命令（仅 OP，权限校验在命令类内）
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                MaidServerCommands.register(dispatcher));

        Constants.LOG.info("Maid File Manager network registered");
    }


    /** 把服务端配置单播给指定玩家（登录同步用） */
    public static void sendServerConfig(ServerPlayer player) {
        if (player == null) {
            return;
        }
        FriendlyByteBuf out = PacketByteBufs.create();
        out.writeBoolean(MaidConfigManager.isClientImportAllowed());
        out.writeBoolean(MaidConfigManager.isBaublesAllowed());
        out.writeBoolean(MaidConfigManager.isAdvancementsAllowed());
        out.writeBoolean(MaidConfigManager.isEffectsAllowed());
        out.writeBoolean(MaidConfigManager.isInvulnerableAllowed());
        out.writeBoolean(MaidConfigManager.isCarryCurios());
        out.writeBoolean(MaidConfigManager.isBaubleStripAttributes());
        MaidFilePackets.writeStringList(out, MaidConfigManager.getOpBaubleBlockedList());
        // 整合包饰品配置：黑名单 + 白名单 + 是否存在整合包配置（客户端据此把整合包强制的条目显示为只读勾选）
        MaidFilePackets.writeStringList(out, MaidConfigManager.getBaubleBlacklist());
        MaidFilePackets.writeStringList(out, MaidConfigManager.getBaubleWhitelist());
        out.writeBoolean(MaidConfigManager.isBaubleConfigManaged());
        MaidFilePackets.writeStringList(out, MaidConfigManager.getOpBaubleBlockedNamespaces());
        MaidFilePackets.writeStringList(out, MaidConfigManager.getOpBaubleBlockedCuriosSlots());
        MaidFilePackets.writeStringList(out, MaidConfigManager.getBaubleImportBlockedNamespaces());
        MaidFilePackets.writeStringList(out, MaidConfigManager.getBaubleImportBlockedCuriosSlots());
        // 白名单强制状态（服务端判定，含解析失败时的失败关闭）
        out.writeBoolean(MaidConfigManager.isBaubleWhitelistEnforced());
        ServerPlayNetworking.send(player, MaidFilePackets.ID_SERVER_CONFIG_SYNC, out);
    }

    /** 把服务端配置同步给所有在线玩家（OP 改配置后用） */
    public static void broadcastServerConfig(MinecraftServer server) {
        // 遍历逻辑已下沉到 PacketSender.broadcastServerConfig（六端共用一份）
        io.github.zgxhzhr.maidfm.service.PacketSender.broadcastServerConfig(server);
    }

    private static void sendFeedback(ServerPlayer player, Component message) {
        io.github.zgxhzhr.maidfm.service.PacketSender.sendFeedback(player, message);
    }

    /** 批量导入 + 删除源文件：汇总文案 + 逐项 spawned 标志（顺序与请求对齐） */
    private static void sendImportBatchResult(ServerPlayer player, Component summary, List<Boolean> spawned) {
        io.github.zgxhzhr.maidfm.service.PacketSender.sendImportBatchResult(player, summary, spawned);
    }

    /**
     * 统一注册 C2S 处理器：网络线程读包 → 主线程执行 → 异常兜底
     * （崩溃转 FEEDBACK 回执，非静默失败，与 Forge 版 C2SPacket 行为对齐）。
     * 旧版网络 API 的 buf 包装的 netty 缓冲在回调返回后立即释放（refCnt=0），
     * 必须在网络线程同步拷贝出独立堆缓冲快照，主线程任务只读快照。
     */
    private static void registerC2SReceiver(ResourceLocation id, C2SHandler handler) {
        ServerPlayNetworking.registerGlobalReceiver(id, (server, player, netHandler, buf, responseSender) -> {
            Constants.LOG.debug("[maid_file_manager] S<-C RECV: id={}, player={}", id,
                    player == null ? "<null>" : player.getName().getString());
            try {
                if (player == null) {
                    Constants.LOG.warn("[maid_file_manager] C2S 包 {} 被丢弃：player 为 null", id);
                    return;
                }
                ServerPlayer p = player;
                FriendlyByteBuf snapshot = PacketByteBufs.create();
                snapshot.writeBytes(buf);
                server.execute(() -> {
                    try {
                        handler.handle(server, p, snapshot);
                    } catch (Throwable t) {
                        Constants.LOG.error("[maid_file_manager] C2S handler 崩溃: id={}, player={}, cause={}",
                                id, p.getName().getString(), t.toString(), t);
                        // 异常原文可能含服务端路径/类名，仅保留在日志，回执只给通用提示
                        try {
                            sendFeedback(p, Component.translatable("maid_file_manager.server.handler_error", id));
                        } catch (Throwable feedbackError) {
                            Constants.LOG.debug("[maid_file_manager] 失败回执发送异常", feedbackError);
                        }
                    }
                });
            } catch (Throwable t) {
                Constants.LOG.error("[maid_file_manager] C2S 读取阶段崩溃: id={}, cause={}", id, t.toString(), t);
            }
        });
    }

    @FunctionalInterface
    private interface C2SHandler {
        void handle(MinecraftServer server, ServerPlayer player, FriendlyByteBuf buf);
    }

    /**
     * 分片下发（服务端 → 客户端）。1.20.x 的 32767 字节上限对 S2C 同样生效，
     * 带照片的导出结果必然超限，故与 C2S 方向对称地切片发送。
     */
    public static boolean sendChunkedDownload(ServerPlayer player, String kind, byte[] payload) {
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
            FriendlyByteBuf buf = PacketByteBufs.create();
            buf.writeUtf(kind == null ? "" : kind, MaidFilePackets.MAX_TEXT_LEN);
            buf.writeVarInt(sessionId);
            buf.writeVarInt(total);
            buf.writeVarInt(i);
            buf.writeByteArray(chunk);
            ServerPlayNetworking.send(player, MaidFilePackets.ID_CHUNKED_DOWNLOAD, buf);
        }
        Constants.LOG.info("[maid_file_manager] 导出结果分片下发: player={} bytes={} chunks={}",
                player.getName().getString(), payload.length, total);
        return true;
    }
}
