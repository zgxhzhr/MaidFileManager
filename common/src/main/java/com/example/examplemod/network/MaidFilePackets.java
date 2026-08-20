package com.example.examplemod.network;

import com.example.examplemod.Constants;
import com.example.examplemod.data.MaidFileData;
import com.example.examplemod.data.MaidInfo;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 网络包 ID 常量与序列化辅助方法。
 *
 * <p>包结构：
 * <ul>
 *   <li>{@link #ID_REQUEST_MAID_LIST} 客户端 -> 服务端：请求附近属于玩家的女仆列表</li>
 *   <li>{@link #ID_EXPORT_MAID} 客户端 -> 服务端：请求导出指定 entityId 的女仆</li>
 *   <li>{@link #ID_REQUEST_FILE_LIST} 客户端 -> 服务端：请求 maid_files 目录文件列表</li>
 *   <li>{@link #ID_IMPORT_MAID} 客户端 -> 服务端：请求导入指定文件名的女仆</li>
 *   <li>{@link #ID_MAID_LIST} 服务端 -> 客户端：返回女仆列表</li>
 *   <li>{@link #ID_FILE_LIST} 服务端 -> 客户端：返回文件列表</li>
 *   <li>{@link #ID_FEEDBACK} 服务端 -> 客户端：返回反馈消息</li>
 * </ul>
 */
public final class MaidFilePackets {
    public static final ResourceLocation ID_REQUEST_MAID_LIST = id("request_maid_list");
    public static final ResourceLocation ID_EXPORT_MAID = id("export_maid");
    public static final ResourceLocation ID_REQUEST_FILE_LIST = id("request_file_list");
    public static final ResourceLocation ID_IMPORT_MAID = id("import_maid");
    public static final ResourceLocation ID_MAID_LIST = id("maid_list");
    public static final ResourceLocation ID_FILE_LIST = id("file_list");
    public static final ResourceLocation ID_FEEDBACK = id("feedback");
    public static final ResourceLocation ID_EXPORT_RESULT = id("export_result");
    public static final ResourceLocation ID_IMPORT_FILE = id("import_file");

    private MaidFilePackets() {
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation(Constants.MOD_ID, path);
    }

    // ---------- 序列化辅助 ----------

    /** 将 MaidFileData 序列化为字节数组（压缩 NBT） */
    public static byte[] serializeMaidFileData(MaidFileData data) {
        CompoundTag root = data.writeToNbt();
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            NbtIo.writeCompressed(root, baos);
            return baos.toByteArray();
        } catch (Exception e) {
            Constants.LOG.error("序列化 MaidFileData 失败", e);
            return null;
        }
    }

    /** 从字节数组反序列化 MaidFileData */
    public static MaidFileData deserializeMaidFileData(byte[] bytes) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes)) {
            CompoundTag root = NbtIo.readCompressed(bais);
            if (root == null) {
                return null;
            }
            return MaidFileData.readFromNbt(root);
        } catch (Exception e) {
            Constants.LOG.error("反序列化 MaidFileData 失败", e);
            return null;
        }
    }

    /** 将 MaidFileData 写入 FriendlyByteBuf */
    public static void writeMaidFileData(FriendlyByteBuf buf, MaidFileData data) {
        byte[] bytes = serializeMaidFileData(data);
        if (bytes == null) {
            buf.writeVarInt(0);
        } else {
            buf.writeVarInt(bytes.length);
            buf.writeBytes(bytes);
        }
    }

    /** 从 FriendlyByteBuf 读取 MaidFileData */
    public static MaidFileData readMaidFileData(FriendlyByteBuf buf) {
        int len = buf.readVarInt();
        if (len <= 0) {
            return null;
        }
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return deserializeMaidFileData(bytes);
    }

    /** 序列化 MaidInfo 列表到 buf */
    public static void writeMaidInfoList(FriendlyByteBuf buf, List<MaidInfo> list) {
        buf.writeVarInt(list.size());
        for (MaidInfo info : list) {
            writeMaidInfo(buf, info);
        }
    }

    /** 从 buf 反序列化 MaidInfo 列表 */
    public static List<MaidInfo> readMaidInfoList(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<MaidInfo> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(readMaidInfo(buf));
        }
        return list;
    }

    public static void writeMaidInfo(FriendlyByteBuf buf, MaidInfo info) {
        buf.writeInt(info.entityId());
        buf.writeUtf(info.modelId());
        buf.writeUtf(info.displayName());
        buf.writeVarInt(info.favorability());
        buf.writeFloat(info.health());
        buf.writeFloat(info.maxHealth());
        buf.writeBoolean(info.tamed());
        buf.writeBoolean(info.ownerUuid() != null);
        if (info.ownerUuid() != null) {
            buf.writeUUID(info.ownerUuid());
        }
        buf.writeBoolean(info.ownerName() != null);
        if (info.ownerName() != null) {
            buf.writeUtf(info.ownerName());
        }
        buf.writeBoolean(info.customName() != null);
        if (info.customName() != null) {
            buf.writeUtf(info.customName());
        }
    }

    public static MaidInfo readMaidInfo(FriendlyByteBuf buf) {
        int entityId = buf.readInt();
        String modelId = buf.readUtf();
        String displayName = buf.readUtf();
        int favorability = buf.readVarInt();
        float health = buf.readFloat();
        float maxHealth = buf.readFloat();
        boolean tamed = buf.readBoolean();
        UUID ownerUuid = buf.readBoolean() ? buf.readUUID() : null;
        String ownerName = buf.readBoolean() ? buf.readUtf() : null;
        String customName = buf.readBoolean() ? buf.readUtf() : null;
        return new MaidInfo(entityId, modelId, displayName, favorability, health, maxHealth, tamed, ownerUuid, ownerName, customName);
    }

    public static void writeStringList(FriendlyByteBuf buf, List<String> list) {
        buf.writeVarInt(list.size());
        for (String s : list) {
            buf.writeUtf(s);
        }
    }

    public static List<String> readStringList(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<String> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(buf.readUtf());
        }
        return list;
    }
}
