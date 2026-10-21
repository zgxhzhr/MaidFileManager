package io.github.zgxhzhr.maidfm.network;

import io.github.zgxhzhr.maidfm.Constants;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分片上传组装器（服务端侧）。
 *
 * <p><b>为什么需要</b>：1.20.x 原版 {@code ServerboundCustomPayloadPacket} 对单包硬限 32767 字节，
 * 而 `.maid`（内嵌头像）与档案照片都可能超过这个值。真联机（独立服务器 / 远程服）时整包发送会被
 * 服务端解码线程直接断连；单人/局域网主机走 {@code LocalServerConnection} 内存直连不受此限。
 *
 * <p><b>协议</b>：客户端把负载切成 ≤ {@link #CHUNK_BYTES} 的小片，逐片发送；服务端按
 * {@code playerId + sessionId} 累积，收齐后返回完整字节数组，由调用方按 {@code kind} 分发处理。
 * 会话有 60 秒超时与总量上限，避免恶意/异常占用内存。
 *
 * <p>线程：服务端网络线程与主线程都可能触达，故用 {@link ConcurrentHashMap}。
 */
public final class ChunkedUploadAssembler {

    /** 单片负载上限。24 KiB 远低于 1.20.x 的 32767，给通道 ID 与包其它字段留足余量。 */
    public static final int CHUNK_BYTES = 24 * 1024;

    /** 单次上传总字节上限（防内存被撑爆）。 */
    public static final int MAX_TOTAL_BYTES = 8 * 1024 * 1024;

    /** 单片数上限。 */
    public static final int MAX_CHUNKS = 512;

    /** 会话超时（毫秒）。 */
    private static final long TIMEOUT_MS = 60_000L;

    /** 全局未完成会话数上限：防止恶意客户端开大量会话拖爆服务端堆。 */
    /**
     * 同时存在的分片会话上限。
     *
     * <p>**按总内存定，不按人数定**：单个会话的声明量上限是 {@link #MAX_TOTAL_BYTES}(8 MiB)，
     * 原先 64 个会话的最坏常驻就是 64 × 8 MiB = **512 MiB** —— 几个玩家同时发大包就能把堆吃光。
     * 收到 8 个会话时最坏 64 MiB，仍在安全区内；正常玩法下同时进行的导出/导入远小于 8。
     */
    private static final int MAX_SESSIONS = 8;

    /** 单个玩家允许同时存在的未完成会话数上限。 */
    private static final int MAX_SESSIONS_PER_PLAYER = 4;

    /**
     * 发送端预检：负载是否超过协议允许的整包上限。
     *
     * <p>{@link #MAX_TOTAL_BYTES} 原先只在接收端「收齐后」校验，发送端照样逐片发完才被丢弃 ——
     * 8 MiB 的负载要发 342 片才失败，期间白占带宽、白占接收端内存，且客户端拿不到任何错误。
     * 发送端应在切分前先问一句。
     *
     * @return true 表示超限，调用方应直接用反馈通道报错而不是发分片
     */
    public static boolean exceedsTotalLimit(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return false;
        }
        if (payload.length > MAX_TOTAL_BYTES) {
            return true;
        }
        // 必须与接收端同一口径：接收端按「声明量 = 片数 × CHUNK_BYTES」判上限
        //（那是它最坏要占的内存），末尾不满一片也按整片计入。
        // 只比 payload.length 会留下 MAX_TOTAL_BYTES-8191 .. MAX_TOTAL_BYTES 的 8 KiB 带：
        // 发送端预检放行、接收端首片就拒收 → 表现为「导出/导入什么都没发生」（第六轮 N1）。
        long chunks = (payload.length + (long) CHUNK_BYTES - 1) / CHUNK_BYTES;
        return chunks * CHUNK_BYTES > MAX_TOTAL_BYTES;
    }

    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();

    /** 传输负载类型，服务端据此决定交给哪个处理函数 */
    public static final String KIND_IMPORT_BATCH = "import_batch";
    public static final String KIND_SAVE_PROFILE = "save_profile";
    /** 导出结果（服务端 → 客户端，反向分片） */
    public static final String KIND_EXPORT_BATCH = "export_batch";

    private static final class Session {
        final String kind;
        final int totalChunks;
        final byte[][] chunks;
        int received;
        volatile long lastAccess;

        Session(String kind, int totalChunks) {
            this.kind = kind;
            this.totalChunks = totalChunks;
            this.chunks = new byte[totalChunks][];
            this.lastAccess = System.currentTimeMillis();
        }
    }

    /**
     * 接收一片。
     *
     * @return 收齐时返回完整负载；未收齐/校验失败返回 null
     */
    public static byte[] accept(UUID playerId, String kind, int sessionId, int totalChunks, int index, byte[] data) {
        if (playerId == null || kind == null || data == null) {
            return null;
        }
        if (data.length > CHUNK_BYTES || totalChunks <= 0 || totalChunks > MAX_CHUNKS
                || index < 0 || index >= totalChunks) {
            return null;
        }
        // 声明量先算一遍：totalChunks × CHUNK_BYTES 就是接收端最坏要占的内存。
        // 原实现只在「收齐后」按实际字节校验 MAX_TOTAL_BYTES，而 512 × 24 KiB = 12 MiB
        // 已经超过 8 MiB 上限 —— 等于允许客户端先把 12 MiB 堆起来再告诉他超了。
        long declared = (long) totalChunks * CHUNK_BYTES;
        if (declared > MAX_TOTAL_BYTES) {
            Constants.LOG.warn("[maid_file_manager] 分片会话声明量超上限，已拒绝: player={} kind={} chunks={} declared={}",
                    playerId, kind, totalChunks, declared);
            return null;
        }
        purgeExpired();
        String key = playerId + "|" + sessionId;
        Session session = SESSIONS.get(key);
        if (session == null) {
            // 会话数上限：全局与单玩家各一道。此前完全无上限，单会话就能占 12 MiB，
            // 恶意客户端连开几十个会话即可拖爆服务端堆。
            if (SESSIONS.size() >= MAX_SESSIONS) {
                Constants.LOG.warn("[maid_file_manager] 分片会话总数达上限({})，拒绝新会话: player={}",
                        MAX_SESSIONS, playerId);
                return null;
            }
            String playerPrefix = playerId + "|";
            long mine = SESSIONS.keySet().stream().filter(k -> k.startsWith(playerPrefix)).count();
            if (mine >= MAX_SESSIONS_PER_PLAYER) {
                Constants.LOG.warn("[maid_file_manager] 玩家分片会话数达上限({})，拒绝新会话: player={}",
                        MAX_SESSIONS_PER_PLAYER, playerId);
                return null;
            }
            Session created = new Session(kind, totalChunks);
            session = SESSIONS.computeIfAbsent(key, k -> created);
            if (session != created && (session.totalChunks != totalChunks || !session.kind.equals(kind))) {
                SESSIONS.remove(key);
                return null;
            }
        }
        if (session.totalChunks != totalChunks || !session.kind.equals(kind)) {
            SESSIONS.remove(key);
            return null;
        }
        session.lastAccess = System.currentTimeMillis();
        synchronized (session) {
            if (session.chunks[index] == null) {
                session.chunks[index] = data;
                session.received++;
            }
            if (session.received < session.totalChunks) {
                Constants.LOG.info("[maid_file_manager] 分片进度: player={} kind={} session={} {}/{}",
                        playerId, kind, sessionId, session.received, session.totalChunks);
                return null;
            }
        }
        SESSIONS.remove(key);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int total = 0;
        for (byte[] chunk : session.chunks) {
            if (chunk == null) {
                return null; // 理论不可达
            }
            total += chunk.length;
            if (total > MAX_TOTAL_BYTES) {
                Constants.LOG.warn("[maid_file_manager] 分片上传超过总量上限，已丢弃: player={} kind={} total={}",
                        playerId, kind, total);
                return null;
            }
            out.write(chunk, 0, chunk.length);
        }
        return out.toByteArray();
    }

    /** 玩家断开/退出时清理其未完成会话 */
    public static void clear(UUID playerId) {
        if (playerId == null) {
            return;
        }
        String prefix = playerId + "|";
        SESSIONS.keySet().removeIf(k -> k.startsWith(prefix));
    }

    private static void purgeExpired() {
        long now = System.currentTimeMillis();
        SESSIONS.entrySet().removeIf(e -> now - e.getValue().lastAccess > TIMEOUT_MS);
    }

    private ChunkedUploadAssembler() {
    }
}