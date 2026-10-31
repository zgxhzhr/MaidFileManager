package io.github.zgxhzhr.maidfm.service;

import io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler;
import io.github.zgxhzhr.maidfm.network.IMaidFileNetwork;
import io.github.zgxhzhr.maidfm.network.MaidBackupCodec;
import io.github.zgxhzhr.maidfm.network.MaidFilePackets;
import io.github.zgxhzhr.maidfm.network.MaidInfoCodec;
import io.github.zgxhzhr.maidfm.network.RequestThrottle;
import io.github.zgxhzhr.maidfm.Constants;
import io.github.zgxhzhr.maidfm.config.MaidConfigManager;
import io.github.zgxhzhr.maidfm.data.ImportResult;
import io.github.zgxhzhr.maidfm.data.MaidFileData;
import io.github.zgxhzhr.maidfm.data.MigrationNoteCollector;
import io.github.zgxhzhr.maidfm.data.MaidInfo;
import io.github.zgxhzhr.maidfm.data.MaidProfile;
import io.github.zgxhzhr.maidfm.data.MaidProfileView;
import io.github.zgxhzhr.maidfm.service.PacketSender;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.List;

/**
 * C2S 请求的业务处理（平台无关部分）。
 *
 * <p>各平台的网络层收到包后，只负责「把参数从 buf 读出来」，然后调本类；
 * 本类做频控、权限校验、业务调用、回包，全平台共用一份。
 *
 * <p><b>为什么不包括读 buf</b>：各版本的读取 API 不同（{@code readComponent} 签名在
 * 1.20.5+ 变过、{@code PacketByteBufs} 在 1.21+ 移除、26.1.2 的类型化 getter 返回
 * Optional）。把「读」统一就等于改线格式，那需要 bump {@code PROTOCOL_VERSION} 并在
 * 六端发版。所以边界画在这里：**读在平台层，处理在 common**。
 *
 * <p>回包走 {@link PacketSender}——它背后是各端实现的 {@code ServerMessaging}，
 * 编码留在平台层（同样是因为跨版本不可行）。
 */
public final class ServerPacketHandlers {

    private ServerPacketHandlers() {
    }

    /**
     * 处理「请求我的女仆列表」（导出 Tab 打开时发一次）。
     *
     * <p>保留原实现的频控：这类请求在主线程同步跑，循环发包能把服务器卡死，
     * 且不需要 OP 权限。同一玩家 1 秒内重复请求直接丢弃。
     */
    public static void requestMaidList(ServerPlayer player) {
        if (player == null) {
            return;
        }
        if (!RequestThrottle.allow(player.getUUID(), "maid_list", 1000L)) {
            return;
        }
        List<MaidInfo> list = MaidTransferService.listOwnMaids(player);
        PacketSender.sendMaidList(player, list);
    }

    /**
     * 处理「保存女仆档案」。
     *
     * <p>校验失败只回执失败文案，不改动实体——档案写入是不可逆的，宁可不写。
     */
    public static void saveMaidProfile(ServerPlayer player, int entityId, MaidProfile profile) {
        if (player == null) {
            return;
        }
        if (profile == null) {
            PacketSender.sendFeedback(player,
                    Component.translatable("gui.maid_file_manager.profile.save_failed"));
            return;
        }
        Entity e = player.level().getEntity(entityId);
        if (!(e instanceof EntityMaid maid) || !maid.isOwnedBy(player)) {
            PacketSender.sendFeedback(player,
                    Component.translatable("gui.maid_file_manager.profile.no_permission"));
            return;
        }
        MaidProfileService.writeToMaid(maid, profile, true);
        PacketSender.sendFeedback(player,
                Component.translatable("gui.maid_file_manager.profile.save_ok"));
    }

    /**
     * 处理「请求女仆档案」（客户端打开档案界面时发一次）。
     *
     * <p>保留原实现的频控，理由同 {@link #requestMaidList}。
     */
    public static void requestMaidProfile(ServerPlayer player, int entityId) {
        if (player == null) {
            return;
        }
        if (!RequestThrottle.allow(player.getUUID(), "maid_profile", 1000L)) {
            return;
        }
        Entity e = player.level().getEntity(entityId);
        if (!(e instanceof EntityMaid maid) || !maid.isOwnedBy(player)) {
            PacketSender.sendFeedback(player,
                    Component.translatable("gui.maid_file_manager.profile.no_permission"));
            return;
        }
        MaidProfileView view = MaidProfileService.buildView(player, maid);
        if (view == null) {
            PacketSender.sendFeedback(player,
                    Component.translatable("gui.maid_file_manager.profile.no_permission"));
            return;
        }
        PacketSender.sendMaidProfile(player, view);
    }

    /**
     * 处理「修改单个布尔配置项」（OP 操作，改完广播全服）。
     *
     * <p>未知键直接回执失败、**不广播**：若把旧配置重新广播一遍，客户端会以为修改生效了。
     */
    public static void setServerConfig(ServerPlayer player, String key, boolean value) {
        if (player == null) {
            return;
        }
        if (!PacketSender.hasOpPermission(player)) {
            Constants.LOG.warn("[maid_file_manager] SET_SERVER_CONFIG rejected (no OP): player={} key={}",
                    player.getName().getString(), key);
            PacketSender.sendFeedback(player,
                    Component.translatable("maid_file_manager.config.fail.no_permission"));
            return;
        }
        if (!MaidConfigManager.setServerConfig(key, value)) {
            PacketSender.sendFeedback(player,
                    Component.translatable("maid_file_manager.server.unknown_config"));
            return;
        }
        PacketSender.broadcastServerConfigTo(player);
    }

    /**
     * 处理「修改字符串列表配置项」（OP 操作，改完广播全服）。
     *
     * <p>客户端提交的列表可被篡改，必须按标识符字符集收窄后再保存。
     * 整合包管理的黑名单不在此写入（由 bauble_import.json 托管、游戏内只读）。
     */
    public static void setServerListConfig(ServerPlayer player, String key, List<String> ids) {
        if (player == null) {
            return;
        }
        if (!PacketSender.hasOpPermission(player)) {
            Constants.LOG.warn("[maid_file_manager] SET_SERVER_LIST_CONFIG rejected (no OP): player={}, key={}",
                    player.getName().getString(), key);
            PacketSender.sendFeedback(player,
                    Component.translatable("maid_file_manager.config.fail.no_permission"));
            return;
        }
        List<String> values;
        if (MaidConfigManager.KEY_BAUBLE_BLOCKED_LIST.equals(key)) {
            // 只保存 OP 自己维护的禁用项；整合包黑名单由 bauble_import.json 托管、游戏内只读
            values = BaubleHandler.sanitizeBaubleBlockedList(ids);
        } else if (MaidConfigManager.KEY_BAUBLE_BLOCKED_NAMESPACES.equals(key)
                || MaidConfigManager.KEY_BAUBLE_BLOCKED_CURIOS_SLOTS.equals(key)) {
            // 模组 / 槽位清单：客户端提交可被篡改，服务端按标识符字符集收窄
            values = MaidTransferService.sanitizeIdentifierList(ids);
        } else {
            Constants.LOG.warn("[maid_file_manager] SET_SERVER_LIST_CONFIG rejected (unknown key): {}", key);
            return;
        }
        MaidConfigManager.setServerListConfig(key, values);
        PacketSender.broadcastServerConfigTo(player);
    }

    // ==================== 导出（含契约预检与分片兜底） ====================

    /**
     * 把导出结果发给玩家。
     *
     * <p>顺序不能变：先取 Curios 提示（它会被清零，同一批次只提示一次），再序列化，
     * 再按客户端解码端的全部契约维度预检，最后才编码发送。任一预检不过只回执明确失败并
     * 返回 false —— 调用方（尤其「导出并移除」路径）据此保证实体绝不被 discard。
     *
     * @return true=已发送；false=序列化失败 / 契约预检被拒 / 分片超限（都已回执原因）
     */
    public static boolean sendExportResults(ServerPlayer player, List<MaidFileData> results) {
        if (player == null) {
            return false;
        }
        // Curios 饰品裁剪提示：未声明模组的饰品不会随女仆迁移，必须明确告知发起导出的人
        Component curiosTip = BaubleHandler.curiosDropTip();
        if (curiosTip != null) {
            PacketSender.sendFeedback(player, curiosTip);
        }
        List<byte[]> blobs = MaidFilePackets.serializeMaidDataBatch(results);
        if (blobs == null) {
            Constants.LOG.error("[maid_file_manager] 导出结果序列化失败，拒绝发送: player={}",
                    player.getName().getString());
            PacketSender.sendFeedback(player,
                    Component.translatable("maid_file_manager.server.serialize_failed"));
            return false;
        }
        // 必须走 allowChunkedFallback=true：下面有分片路径。用 2 参版本会在 >1 MiB 时直接拒绝，
        // 这样同一操作在不同端表现不一致（十余只带头像女仆批量导出就报「超过单包上限」）。
        String reject = MaidFilePackets.checkMaidDataBatchForWire(
                blobs, MaidFilePackets.MAX_EXPORT_IDS, true);
        if (reject != null) {
            Constants.LOG.warn("[maid_file_manager] 导出结果预检被拒绝: player={}, count={}, reason={}",
                    player.getName().getString(), blobs.size(), reject);
            PacketSender.sendFeedback(player,
                    Component.translatable("maid_file_manager.server.prefix").append(reject));
            return false;
        }
        // 提前按整包体积预检：原先只在 sendChunkedDownload 里判，那时三份副本都已分配
        long declaredTotal = (long) blobs.size() * 4L;
        for (byte[] oneBlob : blobs) {
            declaredTotal += oneBlob.length;
        }
        if (declaredTotal > ChunkedUploadAssembler.MAX_TOTAL_BYTES) {
            Constants.LOG.warn("[maid_file_manager] 导出结果整包超上限，拒绝发送: player={} bytes={}",
                    player.getName().getString(), declaredTotal);
            PacketSender.sendFeedback(player, Component.translatable(
                    "maid_file_manager.server.export_too_large",
                    declaredTotal / 1024, ChunkedUploadAssembler.MAX_TOTAL_BYTES / 1024));
            return false;
        }
        // 导出侧没有独立上限，但导入侧 MAX_NBT_DECOMPRESSED_BYTES(4 MiB) 是硬闸门：
        // 超过它的档案「导得出、导不回」。
        //
        // 原来只写一条 WARN——玩家不会去看日志，等于没做。
        // 现在明确告知，但**不阻断**：单个文件超限不该连累同批其他女仆导出。
        if (declaredTotal > MaidFilePackets.MAX_NBT_DECOMPRESSED_BYTES) {
            Constants.LOG.warn("[maid_file_manager] 导出数据 {} 字节超过导入侧 4 MiB 上限，"
                    + "该 .maid 可能无法再导入: player={}", declaredTotal, player.getName().getString());
            PacketSender.sendFeedback(player, Component.translatable(
                    "maid_file_manager.export.quota_exceeded",
                    declaredTotal / 1024,
                    MaidFilePackets.MAX_NBT_DECOMPRESSED_BYTES / 1024));
        }
        byte[] encoded = encodeMaidDataBlobs(blobs);
        if (encoded.length > ChunkedUploadAssembler.CHUNK_BYTES) {
            return PacketSender.sendChunkedDownload(
                    player, ChunkedUploadAssembler.KIND_EXPORT_BATCH, encoded);
        }
        PacketSender.sendExportBatchResult(player, encoded);
        return true;
    }

    /** 把已序列化的 blobs 编码成「导出结果包」字节。 */
    private static byte[] encodeMaidDataBlobs(List<byte[]> blobs) {
        net.minecraft.network.FriendlyByteBuf buf =
                new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            MaidFilePackets.writeMaidDataBlobs(buf, blobs);
            byte[] out = new byte[buf.readableBytes()];
            buf.getBytes(0, out);
            return out;
        } finally {
            buf.release();
        }
    }

    /**
     * 处理「导出单只女仆」（旧单发通道）。
     *
     * <p>频控保留：旧单发通道与批量通道做的是同一件重活（含生成实体/写盘），
     * 批量通道已加频控，这条不能成为绕过口。
     */
    public static void exportMaid(ServerPlayer player, int entityId) {
        if (player == null) {
            return;
        }
        if (!RequestThrottle.allow(player.getUUID(), "export_maid", 1000L)) {
            return;
        }
        MigrationNoteCollector.discard();
        try {
            MaidFileData data = MaidTransferService.exportMaidToData(player, entityId);
            if (data != null) {
                sendExportResults(player, List.of(data));
            } else {
                PacketSender.sendFeedback(player,
                        Component.translatable("maid_file_manager.export.fail.unknown"));
            }
        } finally {
            flushExportMigrationNotes(player);
        }
    }

    /**
     * 把「本次导出时没能带上的附加数据」汇总成一条反馈。
     *
     * <p>必须放在 finally 里：{@code sendExportResults} 有多条提前 return（体积超限拒绝等），
     * 那些路径上累积器里同样有内容，漏发就会残留到下一次导出。
     */
    private static void flushExportMigrationNotes(ServerPlayer player) {
        List<Component> notes = MigrationNoteCollector.take();
        if (notes.isEmpty()) {
            return;
        }
        net.minecraft.network.chat.MutableComponent msg =
                Component.translatable("maid_file_manager.export.migration_details");
        int shown = Math.min(notes.size(), 5);
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                msg.append(Component.translatable("maid_file_manager.list_separator.newline"));
            }
            msg.append(notes.get(i));
        }
        if (notes.size() > shown) {
            msg.append(Component.translatable(
                    "maid_file_manager.export.migration.more", notes.size() - shown));
        }
        PacketSender.sendFeedback(player, msg);
    }

    /**
     * 处理「批量导出」。
     *
     * <p>两阶段：先完成全部序列化并通过体积预检、确认结果包已发送，之后才允许删除实体。
     * 若边导出边删除，预检失败时女仆已被 discard 而文件未送达，构成不可逆数据丢失。
     *
     * <p>「导出并移除」不立刻 discard：服务端只能确认「包已发出」，无法确认客户端写盘成功。
     * 改为登记待删，等客户端回执后再删；客户端不回执则永不删除——留着女仆的代价
     * 远小于丢数据。
     */
    public static void exportBatch(ServerPlayer player, List<Integer> ids, boolean removeAfter) {
        if (player == null || ids == null) {
            return;
        }
        if (!RequestThrottle.allow(player.getUUID(), "export_batch", 1000L)) {
            return;
        }
        MigrationNoteCollector.discard();
        List<MaidFileData> results = new java.util.ArrayList<>(ids.size());
        List<Integer> exportedIds = new java.util.ArrayList<>(ids.size());
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
            return;
        }
        // 空批次绝不能登记待删：客户端对空结果不发 ack，登记项会一直占到 TTL 过期，
        // 期间下一次「导出并移除」被误报 remove_busy、静默降级成只导出
        if (!removeAfter || exportedIds.isEmpty()) {
            Constants.LOG.debug("[maid_file_manager] EXPORT_BATCH: player={} sent={} removed=0",
                    player.getName().getString(), results.size());
            return;
        }
        PendingRemovalRegistry.RegisterResult reg =
                PendingRemovalRegistry.register(player.getUUID(), exportedIds);
        if (reg == PendingRemovalRegistry.RegisterResult.BUSY) {
            Constants.LOG.warn("[maid_file_manager] 上一笔待删回执未到，本次导出不执行移除: player={}, 本批 {} 个",
                    player.getName().getString(), exportedIds.size());
            PacketSender.sendFeedback(player,
                    Component.translatable("maid_file_manager.server.remove_busy"));
            return;
        }
        if (reg == PendingRemovalRegistry.RegisterResult.REGISTERED_AFTER_EXPIRY) {
            Constants.LOG.warn("[maid_file_manager] 陈旧待删登记已过 TTL，清掉后重建: player={}",
                    player.getName().getString());
        }
        Constants.LOG.info("[maid_file_manager] 导出并移除：登记待删 {} 个实体，等待客户端写盘回执 player={}",
                exportedIds.size(), player.getName().getString());
        Constants.LOG.debug("[maid_file_manager] EXPORT_BATCH: player={} sent={} removed=0",
                player.getName().getString(), results.size());
        flushExportMigrationNotes(player);
    }

    // ==================== 列表类请求 ====================

    /**
     * 处理「OP 统一导出：请求全服玩家女仆分组」。
     *
     * <p>两级预检必须在分配缓冲/编码之前：组数与总字节。全服女仆聚合可能远超单包上限，
     * 超限必须回执明确提示，而不是发出坏包把 OP 客户端断连。
     */
    public static void requestServerExportList(ServerPlayer player) {
        if (player == null) {
            return;
        }
        if (!PacketSender.hasOpPermission(player)) {
            PacketSender.sendFeedback(player,
                    Component.translatable("maid_file_manager.server.bulk_export_op_only"));
            return;
        }
        java.util.List<IMaidFileNetwork.PlayerMaidGroup> groups =
                MaidServerCommands.collectOnlinePlayerMaids(PacketSender.serverOf(player));
        // 组数维度预检：接收端 readPlayerMaidGroups 硬上限 128
        if (groups.size() > MaidFilePackets.MAX_PLAYER_GROUPS) {
            PacketSender.sendFeedback(player, Component.translatable(
                    "maid_file_manager.server.too_many_players",
                    groups.size(), MaidFilePackets.MAX_PLAYER_GROUPS));
            return;
        }
        // 发送前按真实线格式预检总字节
        net.minecraft.network.FriendlyByteBuf probe = new net.minecraft.network.FriendlyByteBuf(
                io.netty.buffer.Unpooled.buffer());
        int size;
        try {
            MaidInfoCodec.writePlayerMaidGroups(probe, groups);
            size = probe.readableBytes();
        } finally {
            probe.release();
        }
        if (size > MaidFilePackets.MAX_PACKET_BYTES) {
            Constants.LOG.warn("[maid_file_manager] 统一导出列表 {} 字节超过单包上限 {}，拒绝发送",
                    size, MaidFilePackets.MAX_PACKET_BYTES);
            PacketSender.sendFeedback(player, Component.translatable(
                    "maid_file_manager.server.list_too_large",
                    size / 1024, MaidFilePackets.MAX_PACKET_BYTES / 1024));
            return;
        }
        PacketSender.sendServerExportList(player, groups);
    }

    /**
     * 处理「请求可浏览的自动备份列表」。
     *
     * <p>权限：OP 可见全部玩家备份，非 OP 仅可见自己名下的。
     * 扫描被上限/时间预算截断时必须明确告知，否则玩家会以为「就这么多备份」，
     * 从而误判某只女仆没有备份。
     */
    public static void requestBackupList(ServerPlayer player) {
        if (player == null) {
            return;
        }
        // 频控：这类请求在主线程同步跑，循环发包能把服务器卡死，且不需要 OP 权限
        if (!RequestThrottle.allow(player.getUUID(), "backup_list", 5000L)) {
            return;
        }
        MaidBackupService.ScanResult scanResult = MaidBackupService.scan(
                PacketSender.serverOf(player), player.getUUID(), PacketSender.hasOpPermission(player));
        java.util.List<IMaidFileNetwork.BackupOwner> owners = scanResult.owners();
        if (scanResult.truncated()) {
            PacketSender.sendFeedback(player,
                    Component.translatable("maid_file_manager.server.backup_list_truncated"));
        }
        // 接收端解码维度预检：主人组数、总字节均需在上限内
        if (owners.size() > MaidFilePackets.MAX_BACKUP_OWNERS) {
            PacketSender.sendFeedback(player, Component.translatable(
                    "maid_file_manager.server.too_many_backup_owners",
                    owners.size(), MaidFilePackets.MAX_BACKUP_OWNERS));
            return;
        }
        net.minecraft.network.FriendlyByteBuf probe = new net.minecraft.network.FriendlyByteBuf(
                io.netty.buffer.Unpooled.buffer());
        int size;
        try {
            MaidBackupCodec.writeBackupOwners(probe, owners);
            size = probe.readableBytes();
        } finally {
            probe.release();
        }
        if (size > MaidFilePackets.MAX_PACKET_BYTES) {
            Constants.LOG.warn("[maid_file_manager] 备份列表 {} 字节超过单包上限 {}，拒绝发送",
                    size, MaidFilePackets.MAX_PACKET_BYTES);
            PacketSender.sendFeedback(player, Component.translatable(
                    "maid_file_manager.server.backup_list_too_large",
                    size / 1024, MaidFilePackets.MAX_PACKET_BYTES / 1024));
            return;
        }
        PacketSender.sendBackupList(player, owners);
    }

    // ==================== 需要从线上读复杂结构的请求（读仍在平台层） ====================
    //
    // 这几个 handler 的载荷是嵌套缓冲（decode 时 readBytes(len) 出来的独立堆缓冲），
    // 里面还有一层读取。做法是：平台层把内层读完，只在参数里传已解码的值。
    // 这样 common 不需要认识 FriendlyByteBuf，线格式也完全不变。

    /**
     * 处理「导入单个 .maid 文件」。
     *
     * <p>频控必须在业务之前：这条通道和批量通道做的是同一件重活（生成实体 + 写盘），
     * 批量通道有频控，单发通道不能成为绕过口。
     */
    public static void importFile(ServerPlayer player, MaidFileData maidData, boolean keepBaubles) {
        if (player == null) {
            return;
        }
        if (!RequestThrottle.allow(player.getUUID(), "import_file", 1000L)) {
            return;
        }
        if (maidData == null) {
            PacketSender.sendFeedback(player,
                    Component.translatable("maid_file_manager.import.fail.invalid"));
            return;
        }
        // 饰品导入策略由服务端配置决定，客户端不上传（杜绝非 OP 绕过）
        ImportResult result = MaidTransferService.importMaidFromData(
                player, maidData, keepBaubles,
                MaidConfigManager.isBaubleStripAttributes(),
                MaidConfigManager.getBaubleBlockedList());
        // 单条导入同样要消费 Curios 批次统计，否则残留到后续导出提示
        BaubleHandler.curiosDropTip();
        PacketSender.sendFeedback(player, BaubleHandler.withImportDetails(result));
    }

    /**
     * 处理「批量导入 .maid 文件」。
     *
     * <p>逐条独立处理：一条失败不影响其余条目的结果统计。
     */
    public static void importBatch(ServerPlayer player, List<MaidFileData> list,
                                   boolean keepBaubles, boolean deleteAfterImport) {
        if (player == null || list == null) {
            return;
        }
        // 频次限制：这是写盘/生成实体类 handler，比只读请求更重
        if (!RequestThrottle.allow(player.getUUID(), "import_batch", 1000L)) {
            return;
        }
        boolean stripAttributes = MaidConfigManager.isBaubleStripAttributes();
        List<String> blockedList = MaidConfigManager.getBaubleBlockedList();
        // 成败统计只认 ImportResult.State 枚举，严禁反解中文展示文案
        List<ImportResult> results = new java.util.ArrayList<>(list.size());
        for (MaidFileData d : list) {
            if (d == null) {
                results.add(ImportResult.failed(Component.translatable(
                        "maid_file_manager.import.fail.invalid")));
                continue;
            }
            results.add(MaidTransferService.importMaidFromData(
                    player, d, keepBaubles, stripAttributes, blockedList));
        }
        // 导入路径同样会产生 Curios 批次统计，必须消费掉，
        // 否则统计会残留到下一位玩家导出时被当成「导出丢弃」提示
        BaubleHandler.curiosDropTip();
        Component summary = MaidTransferService.buildBatchSummary(results);
        if (deleteAfterImport) {
            // 请求删除源文件：回传逐项 spawned 标志（与请求顺序对齐），客户端只删成功的文件
            List<Boolean> spawned = new java.util.ArrayList<>(results.size());
            for (ImportResult r : results) {
                spawned.add(r.spawned());
            }
            PacketSender.sendImportBatchResult(player, summary, spawned);
        } else {
            PacketSender.sendFeedback(player, summary);
        }
    }

    /**
     * 处理「OP 统一导出提交」：代各玩家导出到服务端磁盘。
     *
     * <p>权限用 {@link PacketSender#hasOpPermission}，不直接读 hasPermissions：
     * 26.1.2 的权限 API 不同，已收敛到平台层。
     */
    public static void serverExportBatch(ServerPlayer player,
                                         List<IMaidFileNetwork.PlayerExportRequest> requests) {
        if (player == null) {
            return;
        }
        if (!PacketSender.hasOpPermission(player)) {
            PacketSender.sendFeedback(player,
                    Component.translatable("maid_file_manager.server.bulk_export_op_only"));
            return;
        }
        Component result = MaidServerCommands.exportForPlayers(
                PacketSender.serverOf(player), requests == null ? List.of() : requests);
        PacketSender.sendFeedback(player, result);
    }

    /**
     * 处理「导出某条自动备份」。
     *
     * <p>体积预检必须在编码之前算好：单条备份超过接收端上限时必须明确回执失败，
     * 而不是发出一个对端解不了的包。
     */
    public static void requestBackupExport(ServerPlayer player, String ownerUuid,
                                           String maidUuid, String fileName) {
        if (player == null) {
            return;
        }
        // 频次限制：读盘 + 编码，比只读请求重
        if (!RequestThrottle.allow(player.getUUID(), "backup_export", 2000L)) {
            return;
        }
        MaidFileData out = MaidBackupService.readForExport(
                PacketSender.serverOf(player), player.getUUID(),
                PacketSender.hasOpPermission(player), ownerUuid, maidUuid, fileName);
        if (out == null) {
            PacketSender.sendFeedback(player,
                    Component.translatable("gui.maid_file_manager.backup.export_failed"));
            return;
        }
        byte[] blob = MaidFilePackets.serializeMaidFileData(out);
        if (blob == null || blob.length > MaidFilePackets.MAX_SINGLE_FILE_BYTES) {
            Constants.LOG.warn("[maid_file_manager] 备份导出体积异常，拒绝发送: owner={}, maid={}, size={}",
                    ownerUuid, maidUuid, blob == null ? -1 : blob.length);
            PacketSender.sendFeedback(player,
                    Component.translatable("maid_file_manager.server.backup_too_large"));
            return;
        }
        PacketSender.sendBackupExportResult(player, blob);
    }

    /**
     * 处理「分片上传的一片」。
     *
     * <p>组装器负责累积；返回非 null 表示这一片正好收齐，交给分发器处理。
     * 整段包 try/catch：畸形的分片数据不能让主线程崩。
     */
    public static void chunkedUpload(ServerPlayer player, String kind, int sessionId,
                                     int total, int index, byte[] chunk) {
        if (player == null) {
            return;
        }
        try {
            byte[] full = ChunkedUploadAssembler.accept(
                    player.getUUID(), kind, sessionId, total, index, chunk);
            if (full != null) {
                io.github.zgxhzhr.maidfm.service.ChunkedUploadDispatcher.dispatch(player, kind, full);
            }
        } catch (Throwable e) {
            Constants.LOG.warn("[maid_file_manager] 分片上传处理失败: {}", e.toString());
        }
    }
}
