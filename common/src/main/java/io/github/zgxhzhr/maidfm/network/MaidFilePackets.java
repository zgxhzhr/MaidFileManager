package io.github.zgxhzhr.maidfm.network;

import io.github.zgxhzhr.maidfm.Constants;
import io.github.zgxhzhr.maidfm.data.MaidFileData;
import io.github.zgxhzhr.maidfm.data.MaidInfo;
import io.github.zgxhzhr.maidfm.data.MaidProfile;
import io.github.zgxhzhr.maidfm.data.MaidProfileView;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/**
 * 网络包 ID 常量与序列化辅助方法。
 *
 * <p>所有列表/字节数组读取都带显式上限：线格式中的 VarInt 长度字段不可信，
 * 直接 {@code new byte[len]} 会被恶意/损坏包打爆内存。
 *
 * <p>包结构：
 * <ul>
 *   <li>{@link #ID_REQUEST_MAID_LIST} 客户端 -> 服务端：请求附近属于玩家的女仆列表</li>
 *   <li>{@link #ID_EXPORT_MAID} 客户端 -> 服务端：请求导出指定 entityId 的女仆</li>
 *   <li>{@link #ID_EXPORT_BATCH} 客户端 -> 服务端：请求批量导出</li>
 *   <li>{@link #ID_IMPORT_FILE} 客户端 -> 服务端：导入单个女仆文件数据</li>
 *   <li>{@link #ID_IMPORT_BATCH} 客户端 -> 服务端：批量导入女仆文件数据</li>
 *   <li>{@link #ID_MAID_LIST} 服务端 -> 客户端：返回女仆列表</li>
 *   <li>{@link #ID_FEEDBACK} 服务端 -> 客户端：返回反馈消息</li>
 *   <li>{@link #ID_EXPORT_RESULT} / {@link #ID_EXPORT_BATCH_RESULT} 服务端 -> 客户端：导出数据回传</li>
 * </ul>
 */
public final class MaidFilePackets {
    public static final ResourceLocation ID_REQUEST_MAID_LIST = id("request_maid_list");
    public static final ResourceLocation ID_EXPORT_MAID = id("export_maid");
    public static final ResourceLocation ID_EXPORT_BATCH = id("export_batch");
    public static final ResourceLocation ID_IMPORT_BATCH = id("import_batch");
    /** S2C：批量导入逐项结果回传（仅在客户端请求了 deleteAfterImport 时发送）。
     * body: Component 汇总文案 + VarInt 条目数 + 每项 boolean spawned（与请求 dataList 严格同序，
     * 客户端据此只删除确实生成成功的源文件，失败/禁止的文件保留）
     */
    public static final ResourceLocation ID_IMPORT_BATCH_RESULT = id("import_batch_result");
    public static final ResourceLocation ID_MAID_LIST = id("maid_list");
    public static final ResourceLocation ID_FEEDBACK = id("feedback");
    public static final ResourceLocation ID_EXPORT_RESULT = id("export_result");
    public static final ResourceLocation ID_EXPORT_BATCH_RESULT = id("export_batch_result");
    public static final ResourceLocation ID_IMPORT_FILE = id("import_file");
    /** C2S：客户端请求修改服务端配置（body: utf key + boolean value，服务端校验 OP） */
    public static final ResourceLocation ID_SET_SERVER_CONFIG = id("set_server_config");
    /** C2S：OP 修改「禁用携带的饰品 ID 列表」（body: 字符串列表，服务端校验 OP 后写文件并广播同步） */
    public static final ResourceLocation ID_SET_SERVER_BAUBLE_BLOCKED_LIST = id("set_server_bauble_blocked_list");
    /** C2S：客户端上报「允许服务端统一导出」同意状态（body: boolean） */
    public static final ResourceLocation ID_CLIENT_CONSENT = id("client_consent");

    /** OP 统一导出：请求服务端收集所有在线玩家（以各玩家为中心）的女仆列表 */
    public static final ResourceLocation ID_REQUEST_SERVER_EXPORT_LIST = id("request_server_export_list");
    /** OP 统一导出：服务端返回的分组女仆列表 */
    public static final ResourceLocation ID_SERVER_EXPORT_LIST = id("server_export_list");
    /** OP 统一导出：按玩家分组提交导出请求（文件保存到服务端磁盘） */
    public static final ResourceLocation ID_SERVER_EXPORT_BATCH = id("server_export_batch");
    /** S2C：服务端配置同步（body: boolean allowImport + boolean allowBaubles + boolean allowAdvancements） */
    public static final ResourceLocation ID_SERVER_CONFIG_SYNC = id("server_config_sync");

    /** C2S：请求某女仆的档案（body: int entityId） */
    public static final ResourceLocation ID_REQUEST_MAID_PROFILE = id("request_maid_profile");
    /** S2C：返回女仆档案视图（档案正文 + 只读展示数据 + 饰品图标） */
    public static final ResourceLocation ID_MAID_PROFILE = id("maid_profile");
    /** C2S：保存女仆档案（body: int entityId + MaidProfile NBT） */
    public static final ResourceLocation ID_SAVE_MAID_PROFILE = id("save_maid_profile");

    /** C2S：备份管理——请求服务端返回可浏览的自动备份列表（服务端按权限过滤） */
    public static final ResourceLocation ID_REQUEST_BACKUP_LIST = id("request_backup_list");
    /** S2C：备份管理——返回按权限过滤后的备份列表 */
    public static final ResourceLocation ID_BACKUP_LIST = id("backup_list");
    /** C2S：备份管理——请求服务端导出某条备份（body: utf 主人 UUID + utf 女仆 UUID + utf 文件名） */
    public static final ResourceLocation ID_REQUEST_BACKUP_EXPORT = id("request_backup_export");
    /** S2C：备份管理——回传单条备份数据（客户端据此写入本地 maid_file/maid_exports/） */
    public static final ResourceLocation ID_BACKUP_EXPORT_RESULT = id("backup_export_result");

    // ---------- 收包长度上限（防止恶意/损坏包打爆内存或触发底层数组上限断连） ----------
    /** 单个女仆信息/实体 ID 列表的最大条目数 */
    public static final int MAX_MAID_LIST_ENTRIES = 256;
    /** 一次批量导出的 entityId 上限 */
    public static final int MAX_EXPORT_IDS = 512;
    /** 一次批量导入的文件数上限 */
    public static final int MAX_IMPORT_BATCH_FILES = 64;
    /** 统一导出分组（玩家组数）上限 */
    public static final int MAX_PLAYER_GROUPS = 128;
    /** 备份列表：主人（玩家）组数上限 */
    public static final int MAX_BACKUP_OWNERS = 128;
    /** 备份列表：单个主人名下的女仆数上限 */
    public static final int MAX_BACKUP_MAIDS = 256;
    /** 备份列表：单个女仆的备份文件数上限 */
    public static final int MAX_BACKUP_FILES = 64;
    /** 单个 .maid 文件经网络传输的最大字节数（512 KiB，指 GZIP 压缩后的线上体积） */
    public static final int MAX_SINGLE_FILE_BYTES = 512 * 1024;
    /**
     * NBT 解压后节点内存配额（16 MiB）。
     * 注意与 {@link #MAX_SINGLE_FILE_BYTES} 解耦：后者管线上压缩字节，NbtAccounter 管解压后树体积，
     * 高压缩比合法女仆文件（压缩后不足 512 KiB、解压后超过 512 KiB）不应被误判为坏数据；
     * 16 MiB 仍足以封死 GZIP 炸弹（放大倍率被压到约 32 倍以内）。
     */
    public static final int MAX_NBT_DECOMPRESSED_BYTES = 16 * 1024 * 1024;
    /**
     * 整个自定义负载的自定字节上限（1 MiB）。
     * 网络帧解码的真实硬限制在 2 MiB 左右，本上限是模组自律红线：
     * 发送端必须预检，超限一律回执明确失败，绝不靠帧解码断连来兜底。
     */
    public static final int MAX_PACKET_BYTES = 1024 * 1024;
    /** 字符串字段长度上限（模型 ID、玩家名、自定义名等） */
    public static final int MAX_TEXT_LEN = 256;

    private MaidFilePackets() {
    }

    private static ResourceLocation id(String path) {
        // 1.20.x 使用双参构造器（1.21 起工厂方法才是唯一公开入口）
        return new ResourceLocation(Constants.MOD_ID, path);
    }

    /** 校验线上长度字段：为负或超过上限一律按坏包拒绝 */
    public static int checkSize(int size, int max, String what) {
        if (size < 0 || size > max) {
            throw new IllegalArgumentException(
                    "非法包长度: " + what + "=" + size + "，允许上限 " + max);
        }
        return size;
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

    /** 从字节数组反序列化 MaidFileData（网络侧限定 NBT 内存配额） */
    public static MaidFileData deserializeMaidFileData(byte[] bytes) {
        // 1.20.x 的 NbtIo.readCompressed(InputStream) 不接收 NbtAccounter（内部用 UNLIMITED）；
        // 这里手动复刻原版解压管线（GZIP -> Buffered -> DataInput）并传入有界配额，
        // 外层字节长度已由 readMaidFileData 的 checkSize 预检。
        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(new GZIPInputStream(new ByteArrayInputStream(bytes))))) {
            CompoundTag root = NbtIo.read(dis, new NbtAccounter(MAX_NBT_DECOMPRESSED_BYTES));
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

    /** 从 FriendlyByteBuf 读取 MaidFileData（长度超限抛异常，由网络层兜底） */
    public static MaidFileData readMaidFileData(FriendlyByteBuf buf) {
        int len = checkSize(buf.readVarInt(), MAX_SINGLE_FILE_BYTES, "maidFileBytes");
        if (len == 0) {
            return null;
        }
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return deserializeMaidFileData(bytes);
    }

    /**
     * 批量序列化 MaidFileData（GZIP 压缩字节）。
     * 任一文件序列化失败返回 null，调用方应整体拒绝发送，绝不允许"部分预检通过"。
     */
    public static List<byte[]> serializeMaidDataBatch(List<MaidFileData> list) {
        List<byte[]> blobs = new ArrayList<>(list.size());
        for (MaidFileData d : list) {
            byte[] bytes = serializeMaidFileData(d);
            if (bytes == null) {
                return null;
            }
            blobs.add(bytes);
        }
        return blobs;
    }

    private static int varIntSize(int v) {
        int n = 1;
        while ((v >>>= 7) != 0) {
            n++;
        }
        return n;
    }

    /**
     * 发送端契约预检：严格镜像接收端解码的全部维度（条目数、单文件 512 KiB、整包 1 MiB）。
     * 任一维度不过即返回中文拒绝原因（发送方据此回执，且不得执行 discard 等不可逆动作）；全部通过返回 null。
     *
     * @param maxFiles 接收端实际上限：导入通道传 {@link #MAX_IMPORT_BATCH_FILES}，
     *                 导出结果通道传 {@link #MAX_EXPORT_IDS}（与 ID_EXPORT_BATCH 请求侧一致）
     */
    public static String checkMaidDataBatchForWire(List<byte[]> blobs, int maxFiles) {
        if (blobs.size() > maxFiles) {
            return String.format(java.util.Locale.ROOT,
                    "单次最多处理 %d 个女仆文件（当前 %d 个），请分批操作", maxFiles, blobs.size());
        }
        long total = varIntSize(blobs.size());
        for (int i = 0; i < blobs.size(); i++) {
            int len = blobs.get(i).length;
            if (len > MAX_SINGLE_FILE_BYTES) {
                return String.format(java.util.Locale.ROOT,
                        "第 %d 个女仆文件体积 %d KB 超过单文件上限 %d KB，请精简该女仆（背包/饰品）后重试",
                        i + 1, len / 1024, MAX_SINGLE_FILE_BYTES / 1024);
            }
            total += varIntSize(len) + (long) len;
        }
        if (total > MAX_PACKET_BYTES) {
            return String.format(java.util.Locale.ROOT,
                    "数据总体积过大（%d KB > %d KB），请减少单次数量后分批操作",
                    total / 1024, MAX_PACKET_BYTES / 1024);
        }
        return null;
    }

    /** 按线格式写入已预检通过的序列化批次（复用预检时的字节，避免二次 GZIP） */
    public static void writeMaidDataBlobs(FriendlyByteBuf buf, List<byte[]> blobs) {
        buf.writeVarInt(blobs.size());
        for (byte[] bytes : blobs) {
            buf.writeVarInt(bytes.length);
            buf.writeBytes(bytes);
        }
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
        int size = checkSize(buf.readVarInt(), MAX_MAID_LIST_ENTRIES, "maidInfoList");
        List<MaidInfo> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(readMaidInfo(buf));
        }
        return list;
    }

    public static void writeMaidInfo(FriendlyByteBuf buf, MaidInfo info) {
        buf.writeInt(info.entityId());
        buf.writeUtf(info.modelId(), MAX_TEXT_LEN);
        buf.writeUtf(info.displayName(), MAX_TEXT_LEN);
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
            buf.writeUtf(info.ownerName(), MAX_TEXT_LEN);
        }
        buf.writeBoolean(info.customName() != null);
        if (info.customName() != null) {
            buf.writeUtf(info.customName(), MAX_TEXT_LEN);
        }
    }

    public static MaidInfo readMaidInfo(FriendlyByteBuf buf) {
        int entityId = buf.readInt();
        String modelId = buf.readUtf(MAX_TEXT_LEN);
        String displayName = buf.readUtf(MAX_TEXT_LEN);
        int favorability = buf.readVarInt();
        float health = buf.readFloat();
        float maxHealth = buf.readFloat();
        boolean tamed = buf.readBoolean();
        UUID ownerUuid = buf.readBoolean() ? buf.readUUID() : null;
        String ownerName = buf.readBoolean() ? buf.readUtf(MAX_TEXT_LEN) : null;
        String customName = buf.readBoolean() ? buf.readUtf(MAX_TEXT_LEN) : null;
        return new MaidInfo(entityId, modelId, displayName, favorability, health, maxHealth, tamed, ownerUuid, ownerName, customName);
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
        int size = checkSize(buf.readVarInt(), MAX_EXPORT_IDS, "entityIdList");
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
            buf.writeUtf(g.playerName(), MAX_TEXT_LEN);
            buf.writeBoolean(g.consented());
            writeMaidInfoList(buf, g.maids());
        }
    }

    /** 读统一导出分组列表 */
    public static List<IMaidFileNetwork.PlayerMaidGroup> readPlayerMaidGroups(FriendlyByteBuf buf) {
        int size = checkSize(buf.readVarInt(), MAX_PLAYER_GROUPS, "playerGroups");
        List<IMaidFileNetwork.PlayerMaidGroup> groups = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            String name = buf.readUtf(MAX_TEXT_LEN);
            boolean consented = buf.readBoolean();
            groups.add(new IMaidFileNetwork.PlayerMaidGroup(name, consented, readMaidInfoList(buf)));
        }
        return groups;
    }

    /** 写统一导出提交请求：每项 = 玩家名 + 要导出的 entityId 列表 */
    public static void writePlayerExportRequests(FriendlyByteBuf buf, List<IMaidFileNetwork.PlayerExportRequest> groups) {
        buf.writeVarInt(groups.size());
        for (IMaidFileNetwork.PlayerExportRequest g : groups) {
            buf.writeUtf(g.playerName(), MAX_TEXT_LEN);
            writeIntList(buf, g.entityIds());
        }
    }

    /** 读统一导出提交请求 */
    public static List<IMaidFileNetwork.PlayerExportRequest> readPlayerExportRequests(FriendlyByteBuf buf) {
        int size = checkSize(buf.readVarInt(), MAX_PLAYER_GROUPS, "playerExportRequests");
        List<IMaidFileNetwork.PlayerExportRequest> groups = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            String name = buf.readUtf(MAX_TEXT_LEN);
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

    /** 读 MaidFileData 列表（导入通道：条目上限 64，单文件 512 KiB） */
    public static List<MaidFileData> readMaidFileDataList(FriendlyByteBuf buf) {
        return readMaidFileDataList(buf, MAX_IMPORT_BATCH_FILES);
    }

    /**
     * 读 MaidFileData 列表并显式指定条目上限。
     * 导出结果通道必须传 {@link #MAX_EXPORT_IDS}：服务端导出请求侧允许 512 个实体 ID，
     * 若沿用导入通道的 64 上限，65~512 个合法结果会在解码端抛异常把客户端踢下线。
     */
    public static List<MaidFileData> readMaidFileDataList(FriendlyByteBuf buf, int maxFiles) {
        int size = checkSize(buf.readVarInt(), maxFiles, "maidFileDataList");
        List<MaidFileData> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(readMaidFileData(buf));
        }
        return list;
    }

    /** 写批量导入逐项 spawned 布尔列表（顺序与请求 dataList 对齐） */
    public static void writeBooleanList(FriendlyByteBuf buf, List<Boolean> list) {
        buf.writeVarInt(list.size());
        for (boolean v : list) {
            buf.writeBoolean(v);
        }
    }

    /** 读批量导入逐项 spawned 布尔列表（条目上限与导入通道一致） */
    public static List<Boolean> readBooleanList(FriendlyByteBuf buf) {
        int size = checkSize(buf.readVarInt(), MAX_IMPORT_BATCH_FILES, "spawnedList");
        List<Boolean> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(buf.readBoolean());
        }
        return list;
    }

    // ---------- 备份管理列表（主人 → 女仆 → 备份文件） ----------

    /** 写备份列表：主人组数、每组主人 UUID 与其女仆节点、每个节点的备份文件名 */
    public static void writeBackupOwners(FriendlyByteBuf buf, List<IMaidFileNetwork.BackupOwner> owners) {
        List<IMaidFileNetwork.BackupOwner> safe = owners == null ? List.of() : owners;
        buf.writeVarInt(Math.min(safe.size(), MAX_BACKUP_OWNERS));
        int ownerCount = Math.min(safe.size(), MAX_BACKUP_OWNERS);
        for (int i = 0; i < ownerCount; i++) {
            IMaidFileNetwork.BackupOwner owner = safe.get(i);
            buf.writeUtf(owner.ownerUuid() == null ? "" : owner.ownerUuid(), MAX_TEXT_LEN);
            List<IMaidFileNetwork.BackupMaid> maids = owner.maids() == null ? List.of() : owner.maids();
            int maidCount = Math.min(maids.size(), MAX_BACKUP_MAIDS);
            buf.writeVarInt(maidCount);
            for (int j = 0; j < maidCount; j++) {
                IMaidFileNetwork.BackupMaid maid = maids.get(j);
                buf.writeUtf(maid.maidUuid() == null ? "" : maid.maidUuid(), MAX_TEXT_LEN);
                buf.writeUtf(maid.maidName() == null ? "" : maid.maidName(), MAX_TEXT_LEN);
                List<String> files = maid.files() == null ? List.of() : maid.files();
                int fileCount = Math.min(files.size(), MAX_BACKUP_FILES);
                buf.writeVarInt(fileCount);
                for (int k = 0; k < fileCount; k++) {
                    buf.writeUtf(files.get(k) == null ? "" : files.get(k), MAX_TEXT_LEN);
                }
            }
        }
    }

    /** 读备份列表；各层级数量超限一律拒绝（读端自律，坏包走异常断连/回执） */
    public static List<IMaidFileNetwork.BackupOwner> readBackupOwners(FriendlyByteBuf buf) {
        int ownerCount = checkSize(buf.readVarInt(), MAX_BACKUP_OWNERS, "backupOwners");
        List<IMaidFileNetwork.BackupOwner> owners = new ArrayList<>(ownerCount);
        for (int i = 0; i < ownerCount; i++) {
            String ownerUuid = buf.readUtf(MAX_TEXT_LEN);
            int maidCount = checkSize(buf.readVarInt(), MAX_BACKUP_MAIDS, "backupMaids");
            List<IMaidFileNetwork.BackupMaid> maids = new ArrayList<>(maidCount);
            for (int j = 0; j < maidCount; j++) {
                String maidUuid = buf.readUtf(MAX_TEXT_LEN);
                String maidName = buf.readUtf(MAX_TEXT_LEN);
                int fileCount = checkSize(buf.readVarInt(), MAX_BACKUP_FILES, "backupFiles");
                List<String> files = new ArrayList<>(fileCount);
                for (int k = 0; k < fileCount; k++) {
                    files.add(buf.readUtf(MAX_TEXT_LEN));
                }
                maids.add(new IMaidFileNetwork.BackupMaid(maidUuid, maidName, files));
            }
            owners.add(new IMaidFileNetwork.BackupOwner(ownerUuid, maids));
        }
        return owners;
    }

    // ---------- 饰品导入设置（跨版本属性保留 / 禁用携带列表） ----------

    /** 写字符串列表（数量上限 256，单串上限 256 字符，防畸形包 OOM） */
    public static void writeStringList(FriendlyByteBuf buf, List<String> list) {
        List<String> safe = list == null ? List.of() : list;
        buf.writeVarInt(Math.min(safe.size(), 256));
        for (String s : safe) {
            buf.writeUtf(s == null ? "" : s, MAX_TEXT_LEN);
        }
    }

    /** 读字符串列表；数量与单串超限一律拒绝（读端自律，坏包走异常断连/回执） */
    public static List<String> readStringList(FriendlyByteBuf buf) {
        int size = checkSize(buf.readVarInt(), 256, "stringList");
        List<String> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            out.add(buf.readUtf(MAX_TEXT_LEN));
        }
        return out;
    }

    // ---------- 女仆档案 ----------

    /** 随档案视图发送的饰品图标件数上限 */
    public static final int MAX_BAUBLE_ICONS = 64;

    /** 写可空字符串（1 字节存在标志 + 字符串） */
    private static void writeNullableUtf(FriendlyByteBuf buf, String s) {
        buf.writeBoolean(s != null);
        if (s != null) {
            buf.writeUtf(s, MAX_TEXT_LEN);
        }
    }

    /** 读可空字符串 */
    private static String readNullableUtf(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readUtf(MAX_TEXT_LEN) : null;
    }

    /** 写档案（可空）：存在标志 + NBT */
    public static void writeMaidProfile(FriendlyByteBuf buf, MaidProfile profile) {
        buf.writeBoolean(profile != null);
        if (profile != null) {
            buf.writeNbt(profile.writeToNbt());
        }
    }

    /** 读档案（可空） */
    public static MaidProfile readMaidProfile(FriendlyByteBuf buf) {
        if (!buf.readBoolean()) {
            return null;
        }
        return MaidProfile.readFromNbt(buf.readNbt());
    }

    /** 写档案视图（S2C） */
    public static void writeMaidProfileView(FriendlyByteBuf buf, MaidProfileView v) {
        buf.writeInt(v.entityId());
        writeNullableUtf(buf, v.maidUuid());
        writeNullableUtf(buf, v.ownerName());
        buf.writeUtf(v.modelId() == null ? "" : v.modelId(), MAX_TEXT_LEN);
        buf.writeUtf(v.displayName() == null ? "" : v.displayName(), MAX_TEXT_LEN);
        writeNullableUtf(buf, v.customName());
        buf.writeFloat(v.health());
        buf.writeFloat(v.maxHealth());
        buf.writeFloat(v.attackDamage());
        buf.writeVarInt(v.favorability());
        buf.writeBoolean(v.struckByLightning());
        writeMaidProfile(buf, v.profile());
        List<CompoundTag> baubles = v.baubleItems() == null ? List.of() : v.baubleItems();
        int count = Math.min(baubles.size(), MAX_BAUBLE_ICONS);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            buf.writeNbt(baubles.get(i));
        }
    }

    /** 读档案视图（S2C） */
    public static MaidProfileView readMaidProfileView(FriendlyByteBuf buf) {
        int entityId = buf.readInt();
        String maidUuid = readNullableUtf(buf);
        String ownerName = readNullableUtf(buf);
        String modelId = buf.readUtf(MAX_TEXT_LEN);
        String displayName = buf.readUtf(MAX_TEXT_LEN);
        String customName = readNullableUtf(buf);
        float health = buf.readFloat();
        float maxHealth = buf.readFloat();
        float attackDamage = buf.readFloat();
        int favorability = buf.readVarInt();
        boolean struckByLightning = buf.readBoolean();
        MaidProfile profile = readMaidProfile(buf);
        int count = checkSize(buf.readVarInt(), MAX_BAUBLE_ICONS, "baubleIcons");
        List<CompoundTag> baubles = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            CompoundTag t = buf.readNbt();
            if (t != null) {
                baubles.add(t);
            }
        }
        return new MaidProfileView(entityId, maidUuid, ownerName, modelId, displayName, customName,
                health, maxHealth, attackDamage, favorability, struckByLightning, profile, baubles);
    }
}
