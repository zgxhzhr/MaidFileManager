package io.github.zgxhzhr.maidfm.network;

import io.github.zgxhzhr.maidfm.Constants;
import io.github.zgxhzhr.maidfm.data.MaidFileData;
import io.github.zgxhzhr.maidfm.data.MaidInfo;
import io.github.zgxhzhr.maidfm.data.MaidProfile;
import io.github.zgxhzhr.maidfm.data.MaidProfileView;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
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
    /** 【兼容保留】旧版单发导出协议，当前无发送方，仅为接收极旧客户端而保留 */
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
    /** 【兼容保留】旧版单发导出回执，当前无发送方（批量导出走 ID_EXPORT_BATCH_RESULT），保留以兼容旧服务端 */
    public static final ResourceLocation ID_EXPORT_RESULT = id("export_result");
    public static final ResourceLocation ID_EXPORT_BATCH_RESULT = id("export_batch_result");
    /** 【兼容保留】旧版单文件导入协议，当前无发送方，仅为接收极旧客户端而保留 */
    public static final ResourceLocation ID_IMPORT_FILE = id("import_file");
    /** C2S：客户端请求修改服务端配置（body: utf key + boolean value，服务端校验 OP） */
    public static final ResourceLocation ID_SET_SERVER_CONFIG = id("set_server_config");
    /** C2S：OP 修改「禁用携带的饰品 ID 列表」（body: 字符串列表，服务端校验 OP 后写文件并广播同步） */
    public static final ResourceLocation ID_SET_SERVER_BAUBLE_BLOCKED_LIST = id("set_server_bauble_blocked_list");
    /** C2S：客户端上报「允许服务端统一导出」同意状态（body: boolean） */
    public static final ResourceLocation ID_CLIENT_CONSENT = id("client_consent");
    /** C2S：分片上传（1.20.x 单包 32767 限制下承载 >32KB 的负载；见 ChunkedUploadAssembler） */
    public static final ResourceLocation ID_CHUNKED_UPLOAD = id("chunked_upload");
    /** S2C：分片下载（服务端 → 客户端，导出结果等大负载的反向通道） */
    public static final ResourceLocation ID_CHUNKED_DOWNLOAD = id("chunked_download");
    /** C2S：客户端已把导出结果写盘的回执（true=写入成功）。用于「导出并移除」延迟删除实体。 */
    public static final ResourceLocation ID_EXPORT_WRITE_ACK = id("export_write_ack");
    /**
     * C2S：客户端连上后上报自身协议版本。
     *
     * <p>Forge / NeoForge 由 {@code NetworkRegistry.newSimpleChannel} 的三个 predicate 自动校验，
     * Fabric 没有等价机制，因此补一个显式上报：服务端收到后与 {@link #PROTOCOL_VERSION} 比对，
     * 不一致就断开并给出可读原因（避免 1.6.3 与 1.6.4 混连时出现难排查的字段错位）。
     * 注意：这只影响联机握手，`.maid` 文件格式与版本号无关，跨版本仍可互导。
     */
    public static final ResourceLocation ID_PROTOCOL_CHECK = id("protocol_check");
    /** C2S：客户端上报「模型 ID → 显示名」映射 */
    public static final ResourceLocation ID_MODEL_NAMES = id("model_names");

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
    /** 模型名映射条目上限 */
    public static final int MAX_MODEL_NAME_ENTRIES = 8192;
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
    // 单个 .maid 解压后的 NBT 树体积上限。
    // 原为 16 MiB —— 与 MAX_IMPORT_BATCH_FILES(64) 相乘最坏可达 1 GiB 常驻内存。
    // 实际档案 NBT 通常只有几十 KB 到数 MB，4 MiB 对正常档案绰绰有余，
    // 整批最坏 64×4 MiB = 256 MiB，而且**这些是同时驻留**的：readMaidFileDataList 会把 64 份解析结果
    // 全部收进 List（每份内含 CompoundTag 树），并非「逐文件串行、压完即弃」。
    // （第六轮 N6：原注释写「串行解压的瞬时峰值而非同时驻留」，与实现相反。）
    // 要真正降峰值只能把导入改成流式逐份处理，属更大改动；当前先用 4 MiB 把最坏值压在 256 MiB。
    public static final int MAX_NBT_DECOMPRESSED_BYTES = 4 * 1024 * 1024;
    /**
     * 整个自定义负载的自定字节上限（1 MiB）——仅适用于 1.21+（其 C2S 载荷上限为 1 MiB）。
     * 发送端必须预检，超限一律回执明确失败，绝不靠帧解码断连来兜底。
     */
    public static final int MAX_PACKET_BYTES = 1024 * 1024;
    /** 线上帧的固定开销余量（通道 ID 文本 + 标志位），预检时必须从上限里扣掉，否则边界包仍会超过真实帧限 */
    private static final int PACKET_OVERHEAD_MARGIN = 64;
    /**
     * 1.20.x 的 C2S 自定义载荷硬上限：原版 {@code ServerboundCustomPayloadPacket} 只允许 32767 字节。
     * 本模组的导入批次是**单包**发送的，超过这个值会被服务端在解码线程直接断开连接，**不给任何回执**，
     * 玩家只会看到"导入时掉线"。这里留约 700 字节余量给通道 ID 与标志位。
     */
    private static final int LEGACY_PAYLOAD_MAX_BYTES = 32_000;
    /**
     * 网络协议版本（客户端与服务端必须一致，否则拒绝连接）。
     *
     * <p><b>递增规则</b>：仅在「新增/删除网络包、修改字段顺序或字段类型」时 +1；
     * 纯后端逻辑、UI、资源、配置项变化<b>不得</b>改动此值，否则会让本可互通的版本无谓互拒。
     *
     * <p><b>现状与风险</b>：本值自首个版本沿用至今始终是 {@code "1"}，但<b>包结构在历史上确实变过</b>
     * （1.5.0 起累计新增了 7 个网络通道）。所以 "1" 并不能保证跨版本互换：两端都报 "1" 时握手照常通过，
     * 旧端遇到新通道只会静默忽略或解析失败。真正的兼容性只能靠"两端同版本"。
     *
     * <p>今后任何新增/删除网络包、或改动字段布局，<b>必须</b>把本值 +1，并同步更新 README 兼容性说明。
     */
    public static final String PROTOCOL_VERSION = "4";

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
        return checkMaidDataBatchForWire(blobs, maxFiles, false);
    }

    /**
     * @param allowChunkedFallback 允许超限时改走分片上传（{@code ID_CHUNKED_UPLOAD}）。
     *                             为 true 时跳过「整包体积」两项检查——上限由分片组装器把关。
     *                             单文件上限仍检查：单片也装不下就没有意义。
     */
    public static String checkMaidDataBatchForWire(List<byte[]> blobs, int maxFiles,
                                                   boolean allowChunkedFallback) {
        if (blobs.size() > maxFiles) {
            return Component.translatable("maid_file_manager.import.check.too_many_files", maxFiles, blobs.size()).getString();
        }
        long total = varIntSize(blobs.size());
        for (int i = 0; i < blobs.size(); i++) {
            int len = blobs.get(i).length;
            if (len > MAX_SINGLE_FILE_BYTES) {
                return Component.translatable("maid_file_manager.import.check.file_too_large",
                        i + 1, len / 1024, MAX_SINGLE_FILE_BYTES / 1024).getString();
            }
            total += varIntSize(len) + (long) len;
        }
        // 允许分片兜底时，整包体积不再受限（分片绕过单包上限，总量上限由 ChunkedUploadAssembler 把关）
        if (allowChunkedFallback) {
            return null;
        }
        if (total > MAX_PACKET_BYTES - PACKET_OVERHEAD_MARGIN) {
            return Component.translatable("maid_file_manager.import.check.total_too_large",
                    total / 1024, (MAX_PACKET_BYTES - PACKET_OVERHEAD_MARGIN) / 1024).getString();
        }
        // 1.20.x 存在额外的 32767 字节硬限（1.21+ 放宽到 1 MiB）：必须在客户端拦住，
        // 否则服务端解码线程会直接断连，玩家只看到"导入即掉线"且没有任何提示。
        // 单人/局域网主机不走这条（isLegacyPayloadLimit 已排除集成服务器）。
        if (isLegacyPayloadLimit() && total > LEGACY_PAYLOAD_MAX_BYTES) {
            return Component.translatable("maid_file_manager.import.check.legacy_wire_too_large",
                    total / 1024, LEGACY_PAYLOAD_MAX_BYTES / 1024).getString();
        }
        return null;
    }

    /** 当前运行环境是否受 1.20.x 的 32767 字节 C2S 载荷限制 */
    public static boolean isLegacyPayloadLimit() {
        try {
            String version = io.github.zgxhzhr.maidfm.platform.Services.PLATFORM.get().getMcVersion();
            if (version == null || !version.startsWith("1.20")) {
                return false;
            }
            // 关键：单人/局域网主机之间的集成服务器走 LocalServerConnection（内存直连），
            // 数据不经过 ServerboundCustomPayloadPacket 序列化，32767 上限**不生效**。
            // 此前只看 MC 版本，会把单人世界本来可用的导入/保存一律拦掉（误伤）。
            return !io.github.zgxhzhr.maidfm.platform.Services.PLATFORM.get().isSingleplayer();
        } catch (Throwable t) {
            return true; // 判断不了就保守按受限处理
        }
    }

    /** 按线格式写入已预检通过的序列化批次（复用预检时的字节，避免二次 GZIP） */
    public static void writeMaidDataBlobs(FriendlyByteBuf buf, List<byte[]> blobs) {
        buf.writeVarInt(blobs.size());
        for (byte[] bytes : blobs) {
            buf.writeVarInt(bytes.length);
            buf.writeBytes(bytes);
        }
    }

    /**
     * 从 buf 反序列化「已序列化字节列表」（{@link #writeMaidDataBlobs} 的对称读端）。
     * 每项字节需要调用方再用 {@code readMaidFileData} 还原成对象。
     */
    public static List<byte[]> readMaidDataBlobs(FriendlyByteBuf buf, int maxFiles) {
        int size = checkSize(buf.readVarInt(), maxFiles, "maidDataBlobs");
        List<byte[]> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            int len = checkSize(buf.readVarInt(), MAX_SINGLE_FILE_BYTES, "maidDataBlob");
            byte[] arr = new byte[len];
            buf.readBytes(arr);
            out.add(arr);
        }
        return out;
    }

    /** 序列化 MaidInfo 列表到 buf */

    /** 从 buf 反序列化 MaidInfo 列表 */


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

    /** 读统一导出分组列表 */

    /** 写统一导出提交请求：每项 = 玩家名 + 要导出的 entityId 列表 */

    /** 读统一导出提交请求 */

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
    /** 写「模型 ID → 显示名」展平列表 */
    public static void writeModelNames(FriendlyByteBuf buf, java.util.Map<String, String> map) {
        java.util.Map<String, String> safe = map == null ? java.util.Map.of() : map;
        int count = Math.min(safe.size(), MAX_MODEL_NAME_ENTRIES);
        buf.writeVarInt(count);
        int written = 0;
        for (java.util.Map.Entry<String, String> e : safe.entrySet()) {
            if (written >= count) break;
            buf.writeUtf(e.getKey() == null ? "" : e.getKey(), MAX_TEXT_LEN);
            buf.writeUtf(e.getValue() == null ? "" : e.getValue(), MAX_TEXT_LEN);
            written++;
        }
    }

    /** 读「模型 ID → 显示名」展平列表 */
    public static List<String> readModelNames(FriendlyByteBuf buf) {
        int count = checkSize(buf.readVarInt(), MAX_MODEL_NAME_ENTRIES, "modelNames");
        List<String> out = new ArrayList<>(count * 2);
        for (int i = 0; i < count; i++) {
            out.add(buf.readUtf(MAX_TEXT_LEN));
            out.add(buf.readUtf(MAX_TEXT_LEN));
        }
        return out;
    }


    /** 读备份列表；各层级数量超限一律拒绝（读端自律，坏包走异常断连/回执） */

    // ---------- 饰品导入设置（跨版本属性保留 / 禁用携带列表） ----------

    /** 写字符串列表（数量上限 256，单串上限 256 字符，防畸形包 OOM） */
    public static void writeStringList(FriendlyByteBuf buf, List<String> list) {
        List<String> safe = list == null ? List.of() : list;
        // 写端必须与 readStringList 的上限（256）完全一致：
        // 原先只写 min(size,256) 却遍历全部元素，超过 256 条时整包错位。
        int size = Math.min(safe.size(), 256);
        buf.writeVarInt(size);
        for (int i = 0; i < size; i++) {
            String s = safe.get(i);
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

    /** 写可空字符串（1 字节存在标志 + 字符串） */

    /** 读可空字符串 */

    /** 写档案（可空）：存在标志 + NBT */

    /** 读档案（可空） */

    /** 写档案视图（S2C） */

    /** 读档案视图（S2C） */
}
