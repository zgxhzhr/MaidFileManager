package com.example.maid_file_manager.network;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * 1.21+ Fabric 统一网络负载。
 *
 * <p>1.20.5 起 Fabric 移除了按 {@link ResourceLocation} 直接收发 {@link FriendlyByteBuf}
 * 的旧 API，所有包必须实现 {@link CustomPacketPayload}。本工程全部包体格式与
 * Forge/NeoForge 版完全一致（由 {@link MaidFilePackets} 的读写辅助方法定义），
 * 这里用唯一一个包装类承载数据（body = 字节数组），每个包 ID 对应一个静态 Type。
 *
 * <p>通道必须在建立连接前于 playC2S/playS2C 两个方向注册编解码器
 * （见 {@link #registerAll()}，在服务端/客户端共同的 onInitialize 中调用）。
 */
public final class MaidPayload implements CustomPacketPayload {
    private final CustomPacketPayload.Type<MaidPayload> typeId;
    private final byte[] data;

    public MaidPayload(CustomPacketPayload.Type<MaidPayload> typeId, byte[] data) {
        this.typeId = typeId;
        this.data = data;
    }

    public static CustomPacketPayload.Type<MaidPayload> typeOf(ResourceLocation id) {
        return new CustomPacketPayload.Type<>(id);
    }

    /** 由已填充的 buf 构造待发送负载（拷贝 buf 全部可读字节，不移动读指针） */
    public static MaidPayload of(ResourceLocation id, FriendlyByteBuf buf) {
        return new MaidPayload(typeOf(id), ByteBufUtil.getBytes(buf));
    }

    /** 新建空缓冲 */
    public static FriendlyByteBuf buffer() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    /** 包体缓冲（每次调用生成新的包装，供包处理器按与 Forge 版相同的格式读取） */
    public FriendlyByteBuf body() {
        return new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
    }

    /**
     * 全部包 ID：playC2S/playS2C 双向注册同一编解码器。
     * 未用到的方向仅多一条无用注册，无副作用。
     */
    public static void registerAll() {
        List<ResourceLocation> ids = List.of(
                MaidFilePackets.ID_REQUEST_MAID_LIST,
                MaidFilePackets.ID_EXPORT_MAID,
                MaidFilePackets.ID_EXPORT_BATCH,
                MaidFilePackets.ID_IMPORT_BATCH,
                MaidFilePackets.ID_REQUEST_FILE_LIST,
                MaidFilePackets.ID_IMPORT_MAID,
                MaidFilePackets.ID_MAID_LIST,
                MaidFilePackets.ID_FILE_LIST,
                MaidFilePackets.ID_FEEDBACK,
                MaidFilePackets.ID_EXPORT_RESULT,
                MaidFilePackets.ID_EXPORT_BATCH_RESULT,
                MaidFilePackets.ID_IMPORT_FILE,
                MaidFilePackets.ID_SET_SERVER_CONFIG,
                MaidFilePackets.ID_CLIENT_CONSENT,
                MaidFilePackets.ID_REQUEST_SERVER_EXPORT_LIST,
                MaidFilePackets.ID_SERVER_EXPORT_LIST,
                MaidFilePackets.ID_SERVER_EXPORT_BATCH,
                MaidFilePackets.ID_SERVER_CONFIG_SYNC
        );
        for (ResourceLocation id : ids) {
            CustomPacketPayload.Type<MaidPayload> type = typeOf(id);
            PerIdCodec codec = new PerIdCodec(type);
            PayloadTypeRegistry.playC2S().register(type, codec);
            PayloadTypeRegistry.playS2C().register(type, codec);
        }
    }

    /**
     * 每个包 ID 专属编解码器：decode 必须用该 wire id 的 Type 构造实例。
     *
     * <p>实机验证（1.21.1 Fabric 0.19.5）：Fabric 的 addon 按「解码后 payload.type().id()」
     * 查找接收器（AbstractChanneledNetworkAddon.handle → getHandler(id)），
     * 若像旧版那样统一返回一个 FALLBACK type，所有包都会被路由到不存在的接收器上
     * 静默丢弃（getHandler 返回 null → 直接 return false，无任何日志），
     * 表现为：女仆列表永远为空、批量导入无任何服务端响应。
     */
    private static final class PerIdCodec implements StreamCodec<RegistryFriendlyByteBuf, MaidPayload> {
        private final CustomPacketPayload.Type<MaidPayload> type;

        PerIdCodec(CustomPacketPayload.Type<MaidPayload> type) {
            this.type = type;
        }

        @Override
        public MaidPayload decode(RegistryFriendlyByteBuf buf) {
            // body 以 VarInt 长度前缀的字节数组表示；用 RegistryFriendlyByteBuf 声明
            // （play 阶段注册表的缓冲类型），只使用其父类 FriendlyByteBuf 的方法，
            // 包体格式与 Forge 版字节级一致。
            return new MaidPayload(this.type, buf.readByteArray());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, MaidPayload payload) {
            buf.writeByteArray(payload.data);
        }
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return typeId;
    }
}
