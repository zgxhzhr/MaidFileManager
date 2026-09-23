package io.github.zgxhzhr.maidfm.network;

import io.github.zgxhzhr.maidfm.Constants;
import io.github.zgxhzhr.maidfm.data.MaidFileData;
import io.github.zgxhzhr.maidfm.data.MaidInfo;
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
                    io.github.zgxhzhr.maidfm.config.MaidConfigManager.handleServerConfigSync(allowImport, allowBaubles, allowAdvancements, allowEffects);
                    return;
                }
                IMaidFileNetwork.ClientHandler handler = IMaidFileNetwork.ClientHandlerHolder.get();
                if (handler == null) {
                    return;
                }
                FriendlyByteBuf data = packet.data;
                if (MaidFilePackets.ID_MAID_LIST.equals(id)) {
                    List<MaidInfo> list = MaidFilePackets.readMaidInfoList(data);
                    handler.onMaidListReceived(list);
                } else if (MaidFilePackets.ID_EXPORT_RESULT.equals(id)) {
                    // 兼容旧单条导出：包装成 singleton list
                    MaidFileData maidData = MaidFilePackets.readMaidFileData(data);
                    handler.onExportResultReceived(maidData == null
                            ? new java.util.ArrayList<>() : java.util.Collections.singletonList(maidData));
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
                    List<IMaidFileNetwork.PlayerMaidGroup> groups = MaidFilePackets.readPlayerMaidGroups(data);
                    handler.onServerExportListReceived(groups);
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
