package io.github.zgxhzhr.maidfm.service;

import io.github.zgxhzhr.maidfm.Constants;
import io.github.zgxhzhr.maidfm.data.MaidPhotoStore;
import io.github.zgxhzhr.maidfm.platform.Services;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「导出并移除」的待删登记表。
 *
 * <p><b>为什么需要它</b>：导出时如果直接把女仆删掉，而客户端写盘失败（磁盘满 / 权限 /
 * 路径异常），就成了「女仆没了、文件也没了」的不可逆数据丢失。所以导出时先登记
 * 「这个玩家有 N 个实体等着删」，等客户端回执 {@code EXPORT_WRITE_ACK} 确认落盘成功后
 * 才真正删除；客户端不回执则永不删除——留着女仆的代价远小于丢数据。
 *
 * <p><b>为什么上收到 service 层</b>：这段状态机原先在六个平台的网络层各写一遍
 * （{@code C2SPacket} / {@code MaidFileModFabric} / {@code MaidFilePayloads}），
 * 逻辑逐字相同、只有变量名不同。它不碰任何平台 API——只用 {@code ServerPlayer}、
 * {@code EntityMaid} 与 {@code MaidPhotoStore}，属于业务而不是传输。
 */
public final class PendingRemovalRegistry {

    /** 待删除实体：玩家 UUID → 实体 ID 列表（等客户端写盘回执后才删） */
    private static final Map<UUID, List<Integer>> PENDING_REMOVAL = new ConcurrentHashMap<>();

    /** 待删登记的写入时刻（毫秒）：客户端回执永远不来时用它判定超时 */
    private static final Map<UUID, Long> PENDING_REMOVAL_AT = new ConcurrentHashMap<>();

    /**
     * 待删登记存活时长。客户端崩溃/断线导致回执永远不来时不能让登记永久占位，
     * 否则「导出并移除」会永久失效（女仆一直不删）且玩家无法察觉。
     */
    private static final long PENDING_REMOVAL_TTL_MS = 5 * 60 * 1000L;

    private PendingRemovalRegistry() {
    }

    /** 登记结果。 */
    public enum RegisterResult {
        /** 登记成功 */
        REGISTERED,
        /** 有一笔未回执的旧登记，本次不登记（避免旧回执删掉新批次） */
        BUSY,
        /** 旧的已过 TTL，清掉后重新登记成功 */
        REGISTERED_AFTER_EXPIRY
    }

    /**
     * 登记一笔待删。
     *
     * <p>同一玩家同一时刻只允许一笔待删登记。<b>已有登记时不覆盖</b>——覆盖会让
     * 「旧回执」删除**新批次**的女仆，而新批次文件可能根本没写成功，构成不可逆数据丢失。
     *
     * <p>putIfAbsent 前先清掉已过 TTL 的陈旧登记：TTL 原先只在 ack 到达时判，
     * 回执永远不来时该玩家会一直命中 putIfAbsent != null →「导出并移除」永久失效。
     */
    public static RegisterResult register(UUID playerId, List<Integer> entityIds) {
        if (playerId == null || entityIds == null || entityIds.isEmpty()) {
            return RegisterResult.BUSY;
        }
        boolean expired = false;
        Long prevPendingAt = PENDING_REMOVAL_AT.get(playerId);
        if (prevPendingAt != null
                && System.currentTimeMillis() - prevPendingAt > PENDING_REMOVAL_TTL_MS) {
            PENDING_REMOVAL.remove(playerId);
            PENDING_REMOVAL_AT.remove(playerId);
            expired = true;
        }
        if (PENDING_REMOVAL.putIfAbsent(playerId, new ArrayList<>(entityIds)) != null) {
            return RegisterResult.BUSY;
        }
        PENDING_REMOVAL_AT.put(playerId, System.currentTimeMillis());
        return expired ? RegisterResult.REGISTERED_AFTER_EXPIRY : RegisterResult.REGISTERED;
    }

    /** 玩家断线时清掉其待删登记：回执不可能再来，留着只会阻塞后续导出。 */
    public static void clear(UUID playerId) {
        if (playerId != null) {
            PENDING_REMOVAL.remove(playerId);
            PENDING_REMOVAL_AT.remove(playerId);
        }
    }

    /** 当前是否有未回执的待删登记（诊断/测试用）。 */
    public static boolean hasPending(UUID playerId) {
        return playerId != null && PENDING_REMOVAL.containsKey(playerId);
    }

    /**
     * 客户端写盘回执。
     *
     * <p>只有 {@code success=true} 才真正删除实体与照片；失败则放弃删除并把原因写进日志，
     * 女仆留在世界里（玩家可以重新导出）。这是对「导出并移除」的数据安全兜底。
     *
     * @param playerId 回执发起者
     * @param success  客户端是否确认落盘成功
     * @param player   发起者实体（用于查找与删除目标实体、写日志）；{@code null} 时只清理登记
     */
    public static void confirmAndRemove(UUID playerId, boolean success, ServerPlayer player) {
        if (playerId == null) {
            return;
        }
        List<Integer> ids = PENDING_REMOVAL.remove(playerId);
        Long pendingAt = PENDING_REMOVAL_AT.remove(playerId);
        if (ids == null || ids.isEmpty()) {
            if (player != null) {
                Constants.LOG.debug("[maid_file_manager] 收到导出回执但没有待删记录: player={}",
                        player.getName().getString());
            }
            return;
        }
        if (pendingAt != null && System.currentTimeMillis() - pendingAt > PENDING_REMOVAL_TTL_MS) {
            Constants.LOG.warn("[maid_file_manager] 待删登记已超时（{} ms），丢弃: playerId={}",
                    PENDING_REMOVAL_TTL_MS, playerId);
            return;
        }
        if (player == null) {
            // 无法执行删除（拿不到世界），但登记已清掉，不会永久占位
            Constants.LOG.warn("[maid_file_manager] 导出回执缺少玩家实体，已放弃删除 {} 个", ids.size());
            return;
        }
        if (!success) {
            Constants.LOG.warn("[maid_file_manager] 客户端写盘失败，已放弃删除女仆（{} 个），player={}",
                    ids.size(), player.getName().getString());
            return;
        }
        int removed = 0;
        for (Integer entityId : ids) {
            Entity e = player.level().getEntity(entityId);
            if (e == null) {
                continue;
            }
            // 防御性校验：PENDING_REMOVAL 是服务端自己登记的，理论上玩家改不了，
            // 但删除是不可逆操作，删前再确认一次「确实是女仆」且「确实归这个玩家」。
            // 不校验类型的话，任何被塞进队列的非女仆实体都会被直接 discard。
            if (!(e instanceof EntityMaid maid) || !maid.isOwnedBy(player)) {
                Constants.LOG.warn("[maid_file_manager] 待删实体校验未通过，跳过: entityId={} player={}",
                        entityId, player.getName().getString());
                continue;
            }
            try {
                MaidTransferService.unregisterMaidWorldData(e);
                UUID photoUuid = e.getUUID();
                e.discard();
                try {
                    MaidPhotoStore.delete(Services.PLATFORM.get().getGameDir(), photoUuid);
                } catch (Throwable t) {
                    Constants.LOG.debug("[maid_file_manager] 删除导出照片失败（残留不影响功能）: {}",
                            t.toString());
                }
                removed++;
            } catch (Throwable t) {
                Constants.LOG.warn("[maid_file_manager] 删除实体失败: entityId={} err={}", entityId, t.toString());
            }
        }
        Constants.LOG.info("[maid_file_manager] 导出写盘已确认，删除女仆 {} 个 player={}",
                removed, player.getName().getString());
    }
}
