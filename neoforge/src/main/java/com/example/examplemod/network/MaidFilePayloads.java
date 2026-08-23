package com.example.examplemod.network;

import com.example.examplemod.Constants;
import com.example.examplemod.data.MaidFileData;
import com.example.examplemod.service.MaidTransferService;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MaidFilePayloads {

    private MaidFilePayloads() {
    }

    // ================ C2S 包（客户端 -> 服务端） ================

    public record RequestMaidListPayload() implements CustomPacketPayload {
        public static final Type<RequestMaidListPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "request_maid_list"));
        public static final StreamCodec<ByteBuf, RequestMaidListPayload> STREAM_CODEC =
                StreamCodec.unit(new RequestMaidListPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (ctx.player() instanceof ServerPlayer sp) {
                    var list = MaidTransferService.listOwnMaids(sp);
                    PacketDistributor.sendToPlayer(sp, new MaidListPayload(list));
                }
            });
        }
    }

    public record ExportMaidPayload(int entityId) implements CustomPacketPayload {
        public static final Type<ExportMaidPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "export_maid"));
        public static final StreamCodec<ByteBuf, ExportMaidPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT,
                        ExportMaidPayload::entityId,
                        ExportMaidPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (ctx.player() instanceof ServerPlayer sp) {
                    MaidFileData data = MaidTransferService.exportMaidToData(sp, entityId);
                    if (data == null) {
                        PacketDistributor.sendToPlayer(sp, new FeedbackPayload(
                                net.minecraft.network.chat.Component.translatable("maid_file_manager.export.fail", "invalid maid")));
                    } else {
                        PacketDistributor.sendToPlayer(sp, new ExportResultPayload(data));
                    }
                }
            });
        }
    }

    public record ImportFilePayload(byte[] bytes) implements CustomPacketPayload {
        public static final Type<ImportFilePayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "import_file"));
        public static final StreamCodec<ByteBuf, ImportFilePayload> STREAM_CODEC =
                StreamCodec.ofMember(
                        (p, buf) -> {
                            FriendlyByteBuf fbb = new FriendlyByteBuf(buf);
                            fbb.writeVarInt(p.bytes.length);
                            fbb.writeBytes(p.bytes);
                        },
                        buf -> {
                            FriendlyByteBuf fbb = new FriendlyByteBuf(buf);
                            int len = fbb.readVarInt();
                            byte[] arr = new byte[len];
                            fbb.readBytes(arr);
                            return new ImportFilePayload(arr);
                        }
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (ctx.player() instanceof ServerPlayer sp) {
                    MaidFileData data = MaidFilePackets.deserializeMaidFileData(bytes);
                    net.minecraft.network.chat.Component result =
                            MaidTransferService.importMaidFromData(sp, data);
                    PacketDistributor.sendToPlayer(sp, new FeedbackPayload(result));
                }
            });
        }
    }

    public record ExportBatchPayload(byte[] encoded) implements CustomPacketPayload {
        public static final Type<ExportBatchPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "export_batch"));
        public static final StreamCodec<ByteBuf, ExportBatchPayload> STREAM_CODEC =
                StreamCodec.ofMember(
                        (p, buf) -> { FriendlyByteBuf fbb = new FriendlyByteBuf(buf); fbb.writeVarInt(p.encoded.length); fbb.writeBytes(p.encoded); },
                        buf -> { FriendlyByteBuf fbb = new FriendlyByteBuf(buf); int len = fbb.readVarInt(); byte[] arr = new byte[len]; fbb.readBytes(arr); return new ExportBatchPayload(arr); }
                );
        private static byte[] encodeExportBatch(List<Integer> ids, boolean removeAfter) {
            FriendlyByteBuf fbb = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            MaidFilePackets.writeIntList(fbb, ids);
            fbb.writeBoolean(removeAfter);
            byte[] out = new byte[fbb.readableBytes()];
            fbb.getBytes(0, out);
            return out;
        }
        public ExportBatchPayload(List<Integer> ids, boolean removeAfter) {
            this(encodeExportBatch(ids, removeAfter));
        }
        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (ctx.player() instanceof ServerPlayer sp) {
                    FriendlyByteBuf fbb = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(encoded));
                    List<Integer> ids = MaidFilePackets.readIntList(fbb);
                    boolean removeAfter = fbb.readBoolean();
                    List<MaidFileData> results = new ArrayList<>(ids.size());
                    int removed = 0;
                    for (Integer entityId : ids) {
                        if (entityId == null) continue;
                        MaidFileData d = MaidTransferService.exportMaidToData(sp, entityId);
                        if (d != null) {
                            results.add(d);
                            if (removeAfter) {
                                var e = sp.level().getEntity(entityId);
                                if (e != null) { e.discard(); removed++; }
                            }
                        }
                    }
                    PacketDistributor.sendToPlayer(sp, new ExportBatchResultPayload(results));
                    Constants.LOG.info("[maid_file_manager] EXPORT_BATCH handled: ids={}, sent={}, removed={}", ids.size(), results.size(), removed);
                }
            });
        }
    }

    public record ImportBatchPayload(byte[] encoded) implements CustomPacketPayload {
        public static final Type<ImportBatchPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "import_batch"));
        public static final StreamCodec<ByteBuf, ImportBatchPayload> STREAM_CODEC =
                StreamCodec.ofMember(
                        (p, buf) -> { FriendlyByteBuf fbb = new FriendlyByteBuf(buf); fbb.writeVarInt(p.encoded.length); fbb.writeBytes(p.encoded); },
                        buf -> { FriendlyByteBuf fbb = new FriendlyByteBuf(buf); int len = fbb.readVarInt(); byte[] arr = new byte[len]; fbb.readBytes(arr); return new ImportBatchPayload(arr); }
                );
        private static byte[] encodeImportBatch(List<MaidFileData> dataList) {
            FriendlyByteBuf fbb = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            MaidFilePackets.writeMaidFileDataList(fbb, dataList);
            byte[] out = new byte[fbb.readableBytes()];
            fbb.getBytes(0, out);
            return out;
        }
        public ImportBatchPayload(List<MaidFileData> dataList) {
            this(encodeImportBatch(dataList));
        }
        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (ctx.player() instanceof ServerPlayer sp) {
                    FriendlyByteBuf fbb = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(encoded));
                    List<MaidFileData> list = MaidFilePackets.readMaidFileDataList(fbb);
                    int ok = 0, fail = 0;
                    for (MaidFileData d : list) {
                        if (d == null) { fail++; continue; }
                        Component fb = MaidTransferService.importMaidFromData(sp, d);
                        if (fb != null && fb.getString().contains("成功")) ok++; else fail++;
                    }
                    Component summary = Component.literal(String.format(java.util.Locale.ROOT,
                            "批量导入完成：成功 %d 个，失败 %d 个", ok, fail));
                    PacketDistributor.sendToPlayer(sp, new FeedbackPayload(summary));
                    Constants.LOG.info("[maid_file_manager] IMPORT_BATCH handled: count={}, ok={}, fail={}", list.size(), ok, fail);
                }
            });
        }
    }

    // ================ S2C 包（服务端 -> 客户端） ================

    public record MaidListPayload(java.util.List<com.example.examplemod.data.MaidInfo> list)
            implements CustomPacketPayload {
        public static final Type<MaidListPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "maid_list"));
        public static final StreamCodec<ByteBuf, MaidListPayload> STREAM_CODEC =
                StreamCodec.ofMember(
                        (payload, buf) -> MaidFilePackets.writeMaidInfoList(new FriendlyByteBuf(buf), payload.list),
                        buf -> new MaidListPayload(MaidFilePackets.readMaidInfoList(new FriendlyByteBuf(buf)))
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                var handler = IMaidFileNetwork.ClientHandlerHolder.get();
                if (handler != null) handler.onMaidListReceived(list);
            });
        }
    }

    public record ExportResultPayload(byte[] bytes) implements CustomPacketPayload {
        public static final Type<ExportResultPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "export_result"));
        public static final StreamCodec<ByteBuf, ExportResultPayload> STREAM_CODEC =
                StreamCodec.ofMember(
                        (p, buf) -> {
                            FriendlyByteBuf fbb = new FriendlyByteBuf(buf);
                            fbb.writeVarInt(p.bytes.length);
                            fbb.writeBytes(p.bytes);
                        },
                        buf -> {
                            FriendlyByteBuf fbb = new FriendlyByteBuf(buf);
                            int len = fbb.readVarInt();
                            byte[] arr = new byte[len];
                            fbb.readBytes(arr);
                            return new ExportResultPayload(arr);
                        }
                );

        public ExportResultPayload(MaidFileData data) {
            this(MaidFilePackets.serializeMaidFileData(data));
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                var handler = IMaidFileNetwork.ClientHandlerHolder.get();
                if (handler != null) {
                    MaidFileData data = MaidFilePackets.deserializeMaidFileData(bytes);
                    handler.onExportResultReceived(data == null ? new ArrayList<>() : Collections.singletonList(data));
                }
            });
        }
    }

    public record ExportBatchResultPayload(byte[] encoded) implements CustomPacketPayload {
        public static final Type<ExportBatchResultPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "export_batch_result"));
        public static final StreamCodec<ByteBuf, ExportBatchResultPayload> STREAM_CODEC =
                StreamCodec.ofMember(
                        (p, buf) -> { FriendlyByteBuf fbb = new FriendlyByteBuf(buf); fbb.writeVarInt(p.encoded.length); fbb.writeBytes(p.encoded); },
                        buf -> { FriendlyByteBuf fbb = new FriendlyByteBuf(buf); int len = fbb.readVarInt(); byte[] arr = new byte[len]; fbb.readBytes(arr); return new ExportBatchResultPayload(arr); }
                );
        private static byte[] encodeExportBatchResult(List<MaidFileData> dataList) {
            FriendlyByteBuf fbb = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            MaidFilePackets.writeMaidFileDataList(fbb, dataList);
            byte[] out = new byte[fbb.readableBytes()];
            fbb.getBytes(0, out);
            return out;
        }
        public ExportBatchResultPayload(List<MaidFileData> dataList) {
            this(encodeExportBatchResult(dataList));
        }
        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                var handler = IMaidFileNetwork.ClientHandlerHolder.get();
                if (handler != null) {
                    FriendlyByteBuf fbb = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(encoded));
                    List<MaidFileData> list = MaidFilePackets.readMaidFileDataList(fbb);
                    handler.onExportResultReceived(list);
                }
            });
        }
    }

    public record FeedbackPayload(Component message)
            implements CustomPacketPayload {
        public static final Type<FeedbackPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "feedback"));
        private static final RegistryAccess.Frozen EMPTY = RegistryAccess.EMPTY;
        private static final StreamCodec<ByteBuf, Component> COMPONENT_STREAM_CODEC = StreamCodec.ofMember(
                (component, buf) -> {
                    String json = Component.Serializer.toJson(component, EMPTY);
                    new FriendlyByteBuf(buf).writeUtf(json, 32767);
                },
                buf -> {
                    String json = new FriendlyByteBuf(buf).readUtf(32767);
                    return Component.Serializer.fromJson(json, EMPTY);
                }
        );
        public static final StreamCodec<ByteBuf, FeedbackPayload> STREAM_CODEC =
                StreamCodec.composite(
                        COMPONENT_STREAM_CODEC, FeedbackPayload::message,
                        FeedbackPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                var handler = IMaidFileNetwork.ClientHandlerHolder.get();
                if (handler != null) handler.onFeedbackReceived(message);
            });
        }
    }
}
