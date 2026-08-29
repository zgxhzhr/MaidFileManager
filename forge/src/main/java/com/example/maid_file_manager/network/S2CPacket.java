package com.example.maid_file_manager.network;

import com.example.maid_file_manager.Constants;
import com.example.maid_file_manager.data.MaidFileData;
import com.example.maid_file_manager.data.MaidInfo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

/**
 * 服务端到客户端的通用网络包。
 * 根据 {@link #packetId} 分发到当前活跃的 {@link IMaidFileNetwork.ClientHandler}。
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
        ctx.enqueueWork(() -> {
            ResourceLocation id = packet.packetId;
            // 服务端配置同步：不依赖任何打开的 Screen，收到即更新客户端缓存
            if (MaidFilePackets.ID_SERVER_CONFIG_SYNC.equals(id)) {
                boolean allowImport = packet.data.readBoolean();
                boolean allowBaubles = packet.data.readBoolean();
                com.example.maid_file_manager.config.MaidConfigManager.handleServerConfigSync(allowImport, allowBaubles);
                Constants.LOG.info("[maid_file_manager] S->C SERVER_CONFIG_SYNC: allowImport={}, allowBaubles={}",
                        allowImport, allowBaubles);
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
                handler.onExportResultReceived(maidData == null ? new java.util.ArrayList<>() : java.util.Collections.singletonList(maidData));
            } else if (MaidFilePackets.ID_EXPORT_BATCH_RESULT.equals(id)) {
                List<MaidFileData> list = MaidFilePackets.readMaidFileDataList(data);
                handler.onExportResultReceived(list);
            } else if (MaidFilePackets.ID_FEEDBACK.equals(id)) {
                Component msg = data.readComponent();
                handler.onFeedbackReceived(msg);
            } else if (MaidFilePackets.ID_SERVER_EXPORT_LIST.equals(id)) {
                List<IMaidFileNetwork.PlayerMaidGroup> groups = MaidFilePackets.readPlayerMaidGroups(data);
                handler.onServerExportListReceived(groups);
            } else {
                Constants.LOG.warn("未知的 S2C 包: {}", id);
            }
        });
        ctx.setPacketHandled(true);
    }
}
