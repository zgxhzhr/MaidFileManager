package com.example.examplemod.network;

import com.example.examplemod.Constants;
import com.example.examplemod.data.MaidFileData;
import com.example.examplemod.data.MaidInfo;
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
            IMaidFileNetwork.ClientHandler handler = IMaidFileNetwork.ClientHandlerHolder.get();
            if (handler == null) {
                return;
            }
            ResourceLocation id = packet.packetId;
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
            } else {
                Constants.LOG.warn("未知的 S2C 包: {}", id);
            }
        });
        ctx.setPacketHandled(true);
    }
}
