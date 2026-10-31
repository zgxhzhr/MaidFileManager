package io.github.zgxhzhr.maidfm.network;

import io.github.zgxhzhr.maidfm.Constants;
import io.github.zgxhzhr.maidfm.data.MaidFileData;
import io.github.zgxhzhr.maidfm.data.MaidInfo;
import io.github.zgxhzhr.maidfm.data.MaidProfileView;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

/**
 * 服务端到客户端的通用网络包。
 * 根据 {@link #packetId} 分发到当前活跃的 {@link IMaidFileNetwork.ClientHandler}。
 * decode 阶段 {@code buf.readBytes(len)} 分配的独立堆缓冲在 handle 结束时统一释放。
 */
public record S2CPacket(ResourceLocation packetId, FriendlyByteBuf data) {

    public static void encode(S2CPacket packet, FriendlyByteBuf buf) {
        buf.writeResourceLocation(packet.packetId);
        buf.writeVarInt(packet.data.readableBytes());
        buf.writeBytes(packet.data);
    }

    public static S2CPacket decode(FriendlyByteBuf buf) {
        ResourceLocation id = buf.readResourceLocation();
        int len = buf.readVarInt();
        FriendlyByteBuf data = new FriendlyByteBuf(buf.readBytes(len));
        return new S2CPacket(id, data);
    }

    public static void handle(S2CPacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        try {
            ctx.enqueueWork(() -> {
            ResourceLocation id = packet.packetId;
            try {
                // 服务端配置同步：不依赖任何打开的 Screen，收到即更新客户端缓存
                if (MaidFilePackets.ID_SERVER_CONFIG_SYNC.equals(id)) {
                    boolean allowImport = packet.data.readBoolean();
                    boolean allowBaubles = packet.data.readBoolean();
                    boolean allowAdvancements = packet.data.readBoolean();
                    boolean allowEffects = packet.data.readBoolean();
                    boolean allowInvulnerable = packet.data.readBoolean();
                    boolean carryCurios = packet.data.readBoolean();
                    boolean baubleStripAttributes = packet.data.readBoolean();
                    java.util.List<String> baubleBlockedList = MaidFilePackets.readStringList(packet.data);
                    java.util.List<String> baubleBlacklist = MaidFilePackets.readStringList(packet.data);
                    java.util.List<String> baubleWhitelist = MaidFilePackets.readStringList(packet.data);
                    boolean baubleManaged = packet.data.readBoolean();
                    java.util.List<String> baubleBlockedNamespaces = MaidFilePackets.readStringList(packet.data);
                    java.util.List<String> baubleBlockedCuriosSlots = MaidFilePackets.readStringList(packet.data);
                    java.util.List<String> baubleImportBlockedNamespaces = MaidFilePackets.readStringList(packet.data);
                    java.util.List<String> baubleImportBlockedCuriosSlots = MaidFilePackets.readStringList(packet.data);
                    // 必须与写端顺序严格一致：写端在最后一个列表之后才写这个布尔（C2SPacket:493）
                    boolean baubleWhitelistEnforced = packet.data.readBoolean();
                    io.github.zgxhzhr.maidfm.config.MaidConfigManager.handleServerConfigSync(
                            allowImport, allowBaubles, allowAdvancements, allowEffects, allowInvulnerable, carryCurios,
                            baubleStripAttributes, baubleBlockedList, baubleBlacklist, baubleWhitelist, baubleManaged,
                            baubleBlockedNamespaces, baubleBlockedCuriosSlots,
                            baubleImportBlockedNamespaces, baubleImportBlockedCuriosSlots, baubleWhitelistEnforced);
                    return;
                }
                IMaidFileNetwork.ClientHandler handler = IMaidFileNetwork.ClientHandlerHolder.get();
                if (handler == null) {
                    return;
                }
                FriendlyByteBuf data = packet.data;
                if (MaidFilePackets.ID_MAID_LIST.equals(id)) {
                    List<MaidInfo> list = MaidInfoCodec.readMaidInfoList(data);
                    handler.onMaidListReceived(list);
                } else if (MaidFilePackets.ID_EXPORT_RESULT.equals(id)) {
                    // 兼容旧单条导出：包装成 singleton list
                    MaidFileData maidData = MaidFilePackets.readMaidFileData(data);
                    handler.onExportResultReceived(maidData == null
                            ? new java.util.ArrayList<>() : java.util.Collections.singletonList(maidData));
                } else if (MaidFilePackets.ID_CHUNKED_DOWNLOAD.equals(id)) {
                    // 分片下载：累积后交回同一个 UI 回调，界面无需感知分片
                    String kind = packet.data.readUtf(MaidFilePackets.MAX_TEXT_LEN);
                    int sessionId = packet.data.readVarInt();
                    int total = packet.data.readVarInt();
                    int index = packet.data.readVarInt();
                    byte[] chunk = packet.data.readByteArray(
                            io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.CHUNK_BYTES);
                    var localPlayer = net.minecraft.client.Minecraft.getInstance().player;
                    if (localPlayer != null) {
                        // 新会话开始时清掉本玩家残留的未完成分片会话：
                        // 客户端从不主动 clear，被中断的下载会把 MAX_SESSIONS_PER_PLAYER(4) 占满，
                        // 之后 60 秒内的新导出在首片就被「会话数达上限」拒收且无提示（第六轮 N7）。
                        if (index == 0) {
                            io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.clear(localPlayer.getUUID());
                        }
                        byte[] full = io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.accept(
                                localPlayer.getUUID(), kind, sessionId, total, index, chunk);
                        if (full != null
                                && io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.KIND_EXPORT_BATCH.equals(kind)) {
                            // 【诊断】打印重组结果的长度与前 8 字节：用于确认拼装是否错位
                            StringBuilder head = new StringBuilder();
                            for (int bi = 0; bi < Math.min(8, full.length); bi++) {
                                head.append(String.format("%02X ", full[bi]));
                            }
                            io.github.zgxhzhr.maidfm.Constants.LOG.info(
                                    "[maid_file_manager] 分片重组完成: len={} head={}", full.length, head);
                            FriendlyByteBuf fullBuf = new FriendlyByteBuf(
                                    io.netty.buffer.Unpooled.wrappedBuffer(full));
                            java.util.List<io.github.zgxhzhr.maidfm.data.MaidFileData> decoded =
                                    new java.util.ArrayList<>();
                            for (byte[] blob : MaidFilePackets.readMaidDataBlobs(fullBuf,
                                    MaidFilePackets.MAX_EXPORT_IDS)) {
                                // readMaidDataBlobs 解出的已经是**纯 GZIP 字节**，不能再走
                                // readMaidFileData（它会先读一个 VarInt 当长度，把 GZIP 魔数 0x1F
                                // 当成"长度 31"吃掉，导致后续字节错位报 Not in GZIP format）。
                                io.github.zgxhzhr.maidfm.data.MaidFileData one =
                                        MaidFilePackets.deserializeMaidFileData(blob);
                                if (one != null) {
                                    decoded.add(one);
                                }
                            }
                            IMaidFileNetwork.ClientHandler clientHandler = IMaidFileNetwork.ClientHandlerHolder.get();
                            // 管理器界面已关闭时结果无处投递：原先静默丢弃，该批导出彻底消失且无任何痕迹。
                            // 至少留一条 WARN，便于玩家/维护者从日志判断「导出为什么没反应」（第六轮 M10）。
                            if (clientHandler == null) {
                                io.github.zgxhzhr.maidfm.Constants.LOG.warn(
                                        "[maid_file_manager] 导出结果被丢弃（管理器界面已关闭）: 本批 {} 条", decoded.size());
                            }
                            if (clientHandler != null) {
                                clientHandler.onExportResultReceived(decoded);
                            }
                        }
                    }
                } else if (MaidFilePackets.ID_EXPORT_BATCH_RESULT.equals(id)) {
                    // 条目上限与服务端导出请求侧 MAX_EXPORT_IDS(512) 对齐，不能沿用导入通道的 64
                    List<MaidFileData> list = MaidFilePackets.readMaidFileDataList(data, MaidFilePackets.MAX_EXPORT_IDS);
                    handler.onExportResultReceived(list);
                } else if (MaidFilePackets.ID_IMPORT_BATCH_RESULT.equals(id)) {
                    // 批量导入 + 删除源文件：汇总文案 + 逐项 spawned 标志，客户端只删除成功导入的本地文件
                    Component summary = data.readComponent();
                    List<Boolean> spawned = MaidFilePackets.readBooleanList(data);
                    handler.onImportBatchResultReceived(summary, spawned);
                } else if (MaidFilePackets.ID_FEEDBACK.equals(id)) {
                    // 损坏数据时 readComponent 可能返回 null，归一为空文案避免下游 NPE
                    Component msg = data.readComponent();
                    handler.onFeedbackReceived(msg != null ? msg : Component.empty());
                } else if (MaidFilePackets.ID_SERVER_EXPORT_LIST.equals(id)) {
                    List<IMaidFileNetwork.PlayerMaidGroup> groups = MaidInfoCodec.readPlayerMaidGroups(data);
                    handler.onServerExportListReceived(groups);
                } else if (MaidFilePackets.ID_MAID_PROFILE.equals(id)) {
                    // 服务端校验归属后返回的档案视图，交由当前活跃界面展示
                    MaidProfileView view = MaidProfileCodec.readMaidProfileView(data);
                    if (view != null) {
                        handler.onMaidProfileReceived(view);
                    }
                } else if (MaidFilePackets.ID_BACKUP_LIST.equals(id)) {
                    // 备份管理：服务端按权限过滤后返回的可浏览备份列表
                    List<IMaidFileNetwork.BackupOwner> owners = MaidBackupCodec.readBackupOwners(data);
                    handler.onBackupListReceived(owners);
                } else if (MaidFilePackets.ID_BACKUP_EXPORT_RESULT.equals(id)) {
                    // 备份管理：服务端回传的单条备份数据（null=权限不足或读取失败）
                    MaidFileData backup = MaidFilePackets.readMaidFileData(data);
                    handler.onBackupExportReceived(backup);
                } else {
                    Constants.LOG.warn("[maid_file_manager] 未知的 S2C 包: {}", id);
                }
            } catch (Throwable t) {
                Constants.LOG.error("[maid_file_manager] S2C handler 崩溃: id={}, cause={}", id, t.toString(), t);
            } finally {
                packet.data.release();
            }
        });
        } catch (Throwable t) {
            // 客户端关闭瞬间 enqueueWork 可能拒绝任务：此时 lambda 不会执行，入站缓冲必须在此释放
            Constants.LOG.warn("[maid_file_manager] S2C 包入队失败: {}", packet.packetId, t);
            packet.data.release();
        }
        ctx.setPacketHandled(true);
    }
}
