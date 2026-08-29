package com.example.maid_file_manager.network;

import com.example.maid_file_manager.Constants;
import com.example.maid_file_manager.data.MaidFileData;
import com.example.maid_file_manager.data.MaidInfo;
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
    public static final ResourceLocation ID_EXPORT_BATCH = id("export_batch");
    public static final ResourceLocation ID_IMPORT_BATCH = id("import_batch");
    public static final ResourceLocation ID_REQUEST_FILE_LIST = id("request_file_list");
    public static final ResourceLocation ID_IMPORT_MAID = id("import_maid");
    public static final ResourceLocation ID_MAID_LIST = id("maid_list");
    public static final ResourceLocation ID_FILE_LIST = id("file_list");
    public static final ResourceLocation ID_FEEDBACK = id("feedback");
    public static final ResourceLocation ID_EXPORT_RESULT = id("export_result");
    public static final ResourceLocation ID_EXPORT_BATCH_RESULT = id("export_batch_result");
    public static final ResourceLocation ID_IMPORT_FILE = id("import_file");
    /** C2S：客户端请求修改服务端配置（body: utf key + boolean value，服务端校验 OP） */
    public static final ResourceLocation ID_SET_SERVER_CONFIG = id("set_server_config");
    /** C2S：客户端上报「允许服务端统一导出」同意状态（body: boolean） */
    public static final ResourceLocation ID_CLIENT_CONSENT = id("client_consent");

    /** OP 统一导出：请求服务端收集所有在线玩家（以各玩家为中心）的女仆列表 */
    public static final ResourceLocation ID_REQUEST_SERVER_EXPORT_LIST = id("request_server_export_list");
    /** OP 统一导出：服务端返回的分组女仆列表 */
    public static final ResourceLocation ID_SERVER_EXPORT_LIST = id("server_export_list");
    /** OP 统一导出：按玩家分组提交导出请求（文件保存到服务端磁盘） */
    public static final ResourceLocation ID_SERVER_EXPORT_BATCH = id("server_export_batch");
    /** S2C：服务端配置同步（body: boolean allowImport + boolean allowBaubles） */
    public static final ResourceLocation ID_SERVER_CONFIG_SYNC = id("server_config_sync");

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

    // ---------- 批量导出辅助 ----------

    /** 写 int 列表（entityId 列表） */
    public static void writeIntList(FriendlyByteBuf buf, List<Integer> list) {
        buf.writeVarInt(list.size());
        for (int v : list) {
            buf.writeInt(v);
        }
    }

    /** 读 int 列表（entityId 列表） */
    public static List<Integer> readIntList(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<Integer> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(buf.readInt());
        }
        return list;
    }

    /** 写统一导出分组列表：每项 = 玩家名 + 同意状态 + 该玩家为中心的女仆列表 */
    public static void writePlayerMaidGroups(FriendlyByteBuf buf, List<IMaidFileNetwork.PlayerMaidGroup> groups) {
        buf.writeVarInt(groups.size());
        for (IMaidFileNetwork.PlayerMaidGroup g : groups) {
            buf.writeUtf(g.playerName(), 256);
            buf.writeBoolean(g.consented());
            writeMaidInfoList(buf, g.maids());
        }
    }

    /** 读统一导出分组列表 */
    public static List<IMaidFileNetwork.PlayerMaidGroup> readPlayerMaidGroups(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<IMaidFileNetwork.PlayerMaidGroup> groups = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            String name = buf.readUtf(256);
            boolean consented = buf.readBoolean();
            groups.add(new IMaidFileNetwork.PlayerMaidGroup(name, consented, readMaidInfoList(buf)));
        }
        return groups;
    }

    /** 写统一导出提交请求：每项 = 玩家名 + 要导出的 entityId 列表 */
    public static void writePlayerExportRequests(FriendlyByteBuf buf, List<IMaidFileNetwork.PlayerExportRequest> groups) {
        buf.writeVarInt(groups.size());
        for (IMaidFileNetwork.PlayerExportRequest g : groups) {
            buf.writeUtf(g.playerName(), 256);
            writeIntList(buf, g.entityIds());
        }
    }

    /** 读统一导出提交请求 */
    public static List<IMaidFileNetwork.PlayerExportRequest> readPlayerExportRequests(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<IMaidFileNetwork.PlayerExportRequest> groups = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            String name = buf.readUtf(256);
            groups.add(new IMaidFileNetwork.PlayerExportRequest(name, readIntList(buf)));
        }
        return groups;
    }

    /** 写 MaidFileData 列表 */
    public static void writeMaidFileDataList(FriendlyByteBuf buf, List<MaidFileData> list) {
        buf.writeVarInt(list.size());
        for (MaidFileData d : list) {
            writeMaidFileData(buf, d);
        }
    }

    /** 读 MaidFileData 列表 */
    public static List<MaidFileData> readMaidFileDataList(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<MaidFileData> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(readMaidFileData(buf));
        }
        return list;
    }
}
