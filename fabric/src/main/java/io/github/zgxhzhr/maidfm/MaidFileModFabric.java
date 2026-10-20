package io.github.zgxhzhr.maidfm;

import io.github.zgxhzhr.maidfm.config.MaidConfigManager;
import io.github.zgxhzhr.maidfm.data.ImportResult;
import io.github.zgxhzhr.maidfm.data.MaidFileData;
import io.github.zgxhzhr.maidfm.data.MaidInfo;
import io.github.zgxhzhr.maidfm.data.MaidProfile;
import io.github.zgxhzhr.maidfm.data.MaidProfileView;
import io.github.zgxhzhr.maidfm.network.IMaidFileNetwork;
import io.github.zgxhzhr.maidfm.network.MaidFilePackets;
import io.github.zgxhzhr.maidfm.service.MaidBackupService;
import io.github.zgxhzhr.maidfm.service.MaidProfileService;
import io.github.zgxhzhr.maidfm.service.MaidServerCommands;
import io.github.zgxhzhr.maidfm.service.MaidTransferService;
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
        Constants.LOG.info("Maid File Manager (Fabric) loading...");

        // 配置系统初始化（服务端/客户端 properties 文件）
        MaidConfigManager.init(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir());

        registerC2SReceiver(MaidFilePackets.ID_REQUEST_MAID_LIST, (server, player, buf) -> {
            List<MaidInfo> list = MaidTransferService.listOwnMaids(player);
            FriendlyByteBuf out = PacketByteBufs.create();
            MaidFilePackets.writeMaidInfoList(out, list);
            ServerPlayNetworking.send(player, MaidFilePackets.ID_MAID_LIST, out);
        });

        registerC2SReceiver(MaidFilePackets.ID_EXPORT_MAID, (server, player, buf) -> {
            int entityId = buf.readInt();
            MaidFileData data = MaidTransferService.exportMaidToData(player, entityId);
            if (data != null && !sendExportResults(player, List.of(data))) {
                return; // 超负载上限时已发送失败反馈
            }
            if (data == null) {
                sendFeedback(player, Component.translatable("maid_file_manager.export.fail.unknown"));
            }
        });

        // 批量导出：数据发回客户端写文件；removeAfter=true 时移除已导出的女仆
        registerC2SReceiver(MaidFilePackets.ID_EXPORT_BATCH, (server, player, buf) -> {
            List<Integer> ids = MaidFilePackets.readIntList(buf);
            boolean removeAfter = buf.readBoolean();
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
            if (!sendExportResults(player, results)) {
                return; // 数据体积超单包上限，已回执明确失败原因（非静默），实体一律未删除
            }
            int removedCount = 0;
            if (removeAfter) {
                // 【P2-10】不再立刻 discard：服务端无法确认客户端写盘成功。
                // 改为登记待删，等客户端回执 ID_EXPORT_WRITE_ACK 再删；不回执则永不删除。
                PENDING_REMOVAL.put(player.getUUID(), new ArrayList<>(exportedIds));
                Constants.LOG.info("[maid_file_manager] 导出并移除：登记待删 {} 个实体，等待客户端写盘回执 player={}",
                        exportedIds.size(), player.getName().getString());
            }
            Constants.LOG.debug("[maid_file_manager] EXPORT_BATCH: player={} sent={} removed={}",
                    player.getName().getString(), results.size(), removedCount);
        });

        registerC2SReceiver(MaidFilePackets.ID_IMPORT_FILE, (server, player, buf) -> {
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
            sendFeedback(player, MaidTransferService.withBaubleDetails(result));
        });

        // 批量导入：成败统计只认 ImportResult.State 枚举，严禁反解中文展示文案
        registerC2SReceiver(MaidFilePackets.ID_IMPORT_BATCH, (server, player, buf) -> {
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
                    io.github.zgxhzhr.maidfm.network.ChunkedUploadDispatcher.dispatch(player, kind, full);
                }
            } catch (Throwable e) {
                Constants.LOG.warn("[maid_file_manager] 分片上传处理失败: {}", e.toString());
            }
        });
        // 导出写盘回执：客户端确认文件已落盘后才删除女仆（P2-10 数据安全）
        registerC2SReceiver(MaidFilePackets.ID_EXPORT_WRITE_ACK, (server, player, buf) -> {
            handleExportWriteAck(player, buf.readBoolean());
        });
        registerC2SReceiver(MaidFilePackets.ID_CLIENT_CONSENT, (server, player, buf) -> {
            boolean allow = buf.readBoolean();
            MaidConfigManager.handleClientConsent(player.getUUID(), allow);
        });

        registerC2SReceiver(MaidFilePackets.ID_SET_SERVER_CONFIG, (server, player, buf) -> {
            String key = buf.readUtf(MaidFilePackets.MAX_TEXT_LEN);
            boolean value = buf.readBoolean();
            if (!player.hasPermissions(2)) {
                sendFeedback(player, Component.translatable("maid_file_manager.config.fail.no_permission"));
                return;
            }
            // 未知键直接回执失败，不得把旧配置向全服重新广播
            if (!MaidConfigManager.setServerConfig(key, value)) {
                sendFeedback(player, Component.translatable("maid_file_manager.server.unknown_config"));
                return;
            }
            broadcastServerConfig(server);
        });

        // OP 修改自己维护的禁用清单（物品 / 模组 / Curios 槽位）：权限校验 → 收窄写入 → 广播同步
        registerC2SReceiver(MaidFilePackets.ID_SET_SERVER_BAUBLE_BLOCKED_LIST, (server, player, buf) -> {
            String listKey = buf.readUtf(MaidFilePackets.MAX_TEXT_LEN);
            List<String> ids = MaidFilePackets.readStringList(buf);
            if (!player.hasPermissions(2)) {
                sendFeedback(player, Component.translatable("maid_file_manager.config.fail.no_permission"));
                return;
            }
            List<String> values;
            if (MaidConfigManager.KEY_BAUBLE_BLOCKED_LIST.equals(listKey)) {
                // 只保存 OP 自己维护的禁用项；整合包黑名单由 bauble_import.json 托管、游戏内只读
                values = MaidTransferService.sanitizeBaubleBlockedList(ids);
            } else if (MaidConfigManager.KEY_BAUBLE_BLOCKED_NAMESPACES.equals(listKey)
                    || MaidConfigManager.KEY_BAUBLE_BLOCKED_CURIOS_SLOTS.equals(listKey)) {
                // 模组 / 槽位清单：客户端提交可被篡改，服务端按标识符字符集收窄
                values = MaidTransferService.sanitizeIdentifierList(ids);
            } else {
                Constants.LOG.warn("[maid_file_manager] SET_SERVER_LIST_CONFIG rejected (unknown key): {}", listKey);
                return;
            }
            MaidConfigManager.setServerListConfig(listKey, values);
            broadcastServerConfig(server);
        });

        // OP 统一导出：返回所有在线玩家（以各玩家为中心）的女仆分组列表
        registerC2SReceiver(MaidFilePackets.ID_REQUEST_SERVER_EXPORT_LIST, (server, player, buf) -> {
            if (!player.hasPermissions(2)) {
                sendFeedback(player, Component.translatable("maid_file_manager.server.bulk_export_op_only"));
                return;
            }
            List<IMaidFileNetwork.PlayerMaidGroup> groups = MaidServerCommands.collectOnlinePlayerMaids(server);
            // 组数维度预检：接收端 readPlayerMaidGroups 硬上限 128，空列表时整包极小，
            // 只查字节数会在数百在线时放行、于客户端解码端炸包断连，必须先按组数拒绝
            if (groups.size() > MaidFilePackets.MAX_PLAYER_GROUPS) {
                sendFeedback(player, Component.translatable("maid_file_manager.server.too_many_players", groups.size(), MaidFilePackets.MAX_PLAYER_GROUPS));
                return;
            }
            FriendlyByteBuf out = PacketByteBufs.create();
            MaidFilePackets.writePlayerMaidGroups(out, groups);
            // 发送前按真实线格式预检总字节：全服女仆聚合可能远超单包上限，
            // 超限必须回执明确提示而非发出坏包把 OP 客户端断连
            int size = out.readableBytes();
            if (size > MaidFilePackets.MAX_PACKET_BYTES) {
                Constants.LOG.warn("[maid_file_manager] 统一导出列表 {} 字节超过单包上限 {}，拒绝发送",
                        size, MaidFilePackets.MAX_PACKET_BYTES);
                sendFeedback(player, Component.translatable("maid_file_manager.server.list_too_large", size / 1024, MaidFilePackets.MAX_PACKET_BYTES / 1024));
                out.release();
                return;
            }
            ServerPlayNetworking.send(player, MaidFilePackets.ID_SERVER_EXPORT_LIST, out);
        });

        // OP 统一导出提交：代各玩家导出女仆到服务端磁盘 maid_exports/<玩家名>/
        registerC2SReceiver(MaidFilePackets.ID_SERVER_EXPORT_BATCH, (server, player, buf) -> {
            if (!player.hasPermissions(2)) {
                sendFeedback(player, Component.translatable("maid_file_manager.server.bulk_export_op_only"));
                return;
            }
            List<IMaidFileNetwork.PlayerExportRequest> requests = MaidFilePackets.readPlayerExportRequests(buf);
            sendFeedback(player, MaidServerCommands.exportForPlayers(server, requests));
        });

        // 请求女仆档案：非本人直接拒绝（不返回任何只读数据），本人则回发档案视图
        registerC2SReceiver(MaidFilePackets.ID_REQUEST_MAID_PROFILE, (server, player, buf) -> {
            int entityId = buf.readVarInt();
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
            FriendlyByteBuf out = PacketByteBufs.create();
            MaidFilePackets.writeMaidProfileView(out, view);
            ServerPlayNetworking.send(player, MaidFilePackets.ID_MAID_PROFILE, out);
        });

        // 保存女仆档案：校验归属后写入实体并回执（复用 ID_FEEDBACK）
        registerC2SReceiver(MaidFilePackets.ID_SAVE_MAID_PROFILE, (server, player, buf) -> {
            int entityId = buf.readVarInt();
            MaidProfile profile = MaidFilePackets.readMaidProfile(buf);
            if (profile == null) {
                sendFeedback(player, Component.translatable("gui.maid_file_manager.profile.save_failed"));
                return;
            }
            Entity e = player.level().getEntity(entityId);
            if (!(e instanceof EntityMaid maid) || !maid.isOwnedBy(player)) {
                sendFeedback(player, Component.translatable("gui.maid_file_manager.profile.no_permission"));
                return;
            }
            // 档案界面保存：背景故事同步写回 TLM AI 人设，保证双向一致
            MaidProfileService.writeToMaid(maid, profile, true);
            sendFeedback(player, Component.translatable("gui.maid_file_manager.profile.save_ok"));
        });

        // 备份管理：服务端按权限过滤后返回可浏览的自动备份列表（OP 可见全部玩家，非 OP 仅自己）
        registerC2SReceiver(MaidFilePackets.ID_REQUEST_BACKUP_LIST, (server, player, buf) -> {
            List<IMaidFileNetwork.BackupOwner> owners = MaidBackupService.scan(
                    server, player.getUUID(), player.hasPermissions(2));
            // 发送前按接收端解码维度预检：主人组数、总字节均需在上限内，超限只回执提示，绝不发坏包把客户端断连
            if (owners.size() > MaidFilePackets.MAX_BACKUP_OWNERS) {
                sendFeedback(player, Component.translatable("maid_file_manager.server.too_many_backup_owners", owners.size(), MaidFilePackets.MAX_BACKUP_OWNERS));
                return;
            }
            FriendlyByteBuf out = PacketByteBufs.create();
            MaidFilePackets.writeBackupOwners(out, owners);
            int size = out.readableBytes();
            if (size > MaidFilePackets.MAX_PACKET_BYTES) {
                Constants.LOG.warn("[maid_file_manager] 备份列表 {} 字节超过单包上限 {}，拒绝发送",
                        size, MaidFilePackets.MAX_PACKET_BYTES);
                sendFeedback(player, Component.translatable("maid_file_manager.server.backup_list_too_large", size / 1024, MaidFilePackets.MAX_PACKET_BYTES / 1024));
                out.release();
                return;
            }
            ServerPlayNetworking.send(player, MaidFilePackets.ID_BACKUP_LIST, out);
        });

        // 备份管理：服务端校验权限（OP 或该备份主人）后读取该备份并回传，客户端再写本地文件
        registerC2SReceiver(MaidFilePackets.ID_REQUEST_BACKUP_EXPORT, (server, player, buf) -> {
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
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                MaidConfigManager.removeClientConsent(handler.player.getUUID()));

        // /maidfile exportall 命令（仅 OP，权限校验在命令类内）
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                MaidServerCommands.register(dispatcher));

        Constants.LOG.info("Maid File Manager network registered");
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
        String reject = MaidFilePackets.checkMaidDataBatchForWire(blobs, MaidFilePackets.MAX_EXPORT_IDS, true);
        if (reject != null) {
            Constants.LOG.warn("[maid_file_manager] 导出结果预检被拒绝: player={}, count={}, reason={}",
                    player.getName().getString(), blobs.size(), reject);
            sendFeedback(player, Component.translatable("maid_file_manager.server.prefix").append(reject));
            return false;
        }
        FriendlyByteBuf out = PacketByteBufs.create();
        MaidFilePackets.writeMaidDataBlobs(out, blobs);
        byte[] encoded = new byte[out.readableBytes()];
        out.getBytes(0, encoded);
        if (encoded.length > io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.CHUNK_BYTES) {
            sendChunkedDownload(player, io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.KIND_EXPORT_BATCH, encoded);
        } else {
            ServerPlayNetworking.send(player, MaidFilePackets.ID_EXPORT_BATCH_RESULT, out);
        }
        return true;
    }

    /** 把服务端配置单播给指定玩家（登录同步用） */
    private static void sendServerConfig(ServerPlayer player) {
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
    private static void broadcastServerConfig(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            sendServerConfig(p);
        }
    }

    private static void sendFeedback(ServerPlayer player, Component message) {
        FriendlyByteBuf out = PacketByteBufs.create();
        // 1.20.x 旧网络 API 可用 FriendlyByteBuf.writeComponent（1.20.5 后才移除）
        out.writeComponent(message);
        ServerPlayNetworking.send(player, MaidFilePackets.ID_FEEDBACK, out);
    }

    /** 批量导入 + 删除源文件：汇总文案 + 逐项 spawned 标志（顺序与请求对齐） */
    private static void sendImportBatchResult(ServerPlayer player, Component summary, List<Boolean> spawned) {
        FriendlyByteBuf out = PacketByteBufs.create();
        out.writeComponent(summary);
        MaidFilePackets.writeBooleanList(out, spawned);
        ServerPlayNetworking.send(player, MaidFilePackets.ID_IMPORT_BATCH_RESULT, out);
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
    private static void sendChunkedDownload(ServerPlayer player, String kind, byte[] payload) {
        if (payload == null || payload.length == 0) {
            return;
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
    }

    /** 待删除实体：玩家 UUID → 实体 ID 列表（等客户端写盘回执后才删） */
    private static final java.util.Map<java.util.UUID, List<Integer>> PENDING_REMOVAL =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 客户端写盘回执：false 则放弃删除，女仆留在世界里 */
    private static void handleExportWriteAck(ServerPlayer player, boolean success) {
        List<Integer> ids = PENDING_REMOVAL.remove(player.getUUID());
        if (ids == null || ids.isEmpty()) {
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
