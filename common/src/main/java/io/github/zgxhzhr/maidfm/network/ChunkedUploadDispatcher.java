package io.github.zgxhzhr.maidfm.network;

import io.github.zgxhzhr.maidfm.Constants;
import io.github.zgxhzhr.maidfm.data.MaidProfile;
import io.github.zgxhzhr.maidfm.service.MaidProfileService;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * 分片上传的**服务端分发**（四端共用，避免每端重复一遍分发与校验逻辑）。
 *
 * <p>调用约定：各平台收到 {@code ID_CHUNKED_UPLOAD} 包后，先交给
 * {@link ChunkedUploadAssembler#accept}，收齐时把完整字节交给本类 {@link #dispatch}。
 */
public final class ChunkedUploadDispatcher {

    /** 处理一个已完成的分片上传负载。任何异常都不外抛，避免打断服务的网络线程。 */
    public static void dispatch(ServerPlayer player, String kind, byte[] full) {
        if (player == null || kind == null || full == null || full.length == 0) {
            return;
        }
        try {
            Constants.LOG.info("[maid_file_manager] 收到完整分片负载: kind={} player={} bytes={}",
                    kind, player.getName().getString(), full.length);
            if (ChunkedUploadAssembler.KIND_SAVE_PROFILE.equals(kind)) {
                saveProfile(player, full);
            } else if (ChunkedUploadAssembler.KIND_IMPORT_BATCH.equals(kind)) {
                importBatch(player, full);
            } else {
                Constants.LOG.warn("[maid_file_manager] 未知的分片负载类型: {}", kind);
            }
        } catch (Throwable t) {
            Constants.LOG.error("[maid_file_manager] 分片负载处理失败: kind={} player={} err={}",
                    kind, player.getName().getString(), t.toString());
        }
    }

    /**
     * 保存档案。负载格式与 {@code SaveMaidProfilePayload} 一致：
     * {@code VarInt entityId + 档案 NBT}。
     *
     * <p>这是**有意重复**各端 SaveMaidProfilePayload.handle 的校验逻辑：那些处理内联在 record 的
     * handle 里、没有可复用的静态入口；在 common 层放一份比四处抽取更稳。
     */
    private static void saveProfile(ServerPlayer player, byte[] full) {
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(full));
        int entityId = buf.readVarInt();
        MaidProfile profile = MaidFilePackets.readMaidProfile(buf);
        if (profile == null) {
            feedback(player, "gui.maid_file_manager.profile.save_failed");
            return;
        }
        Object maid = resolveOwnedMaid(player, entityId);
        if (maid == null) {
            feedback(player, "gui.maid_file_manager.profile.no_permission");
            return;
        }
        MaidProfileService.writeToMaid((com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid) maid,
                profile, true);
        feedback(player, "gui.maid_file_manager.profile.save_ok");
    }

    /** 解析玩家拥有的女仆（服务端权威校验：实体必须存在且属于该玩家） */
    private static Object resolveOwnedMaid(ServerPlayer player, int entityId) {
        net.minecraft.world.entity.Entity e = player.level().getEntity(entityId);
        if (!(e instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid)) {
            return null;
        }
        if (!maid.isTame() || !player.equals(maid.getOwner())) {
            return null;
        }
        return maid;
    }

    private static void feedback(ServerPlayer player, String key) {
        player.sendSystemMessage(Component.translatable(key), false);
    }

    /** 玩家断开：清掉其未完成的分片会话 */
    public static void onPlayerDisconnect(UUID playerId) {
        // 顺带清掉该玩家的请求频次记录（不清也会被过期清理回收，这里只是及时释放）
        RequestThrottle.forget(playerId);
        ChunkedUploadAssembler.clear(playerId);
    }

    private ChunkedUploadDispatcher() {
    }

    /**
     * 导入批次。负载格式与 {@code ImportBatchPayload} 一致：
     * {@code writeMaidFileDataList + boolean keepBaubles + boolean deleteAfterImport}。
     *
     * <p>注意：分片路径**不回传逐项结果**，因此不执行「导入后删除源文件」——源文件保持原样，
     * 比误删安全。玩家会收到一条汇总的聊天栏反馈。
     */
    private static void importBatch(ServerPlayer player, byte[] full) {
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(full));
        java.util.List<io.github.zgxhzhr.maidfm.data.MaidFileData> list =
                MaidFilePackets.readMaidFileDataList(buf);
        buf.readBoolean(); // keepBaubles：与普通路径一致，实际是否恢复由服务端双闸门决定
        buf.readBoolean(); // deleteAfterImport：分片路径不支持回传逐项结果，忽略（不删源文件）
        boolean stripAttributes = io.github.zgxhzhr.maidfm.config.MaidConfigManager.isBaubleStripAttributes();
        java.util.List<String> blocked = io.github.zgxhzhr.maidfm.config.MaidConfigManager.getBaubleBlockedList();
        java.util.List<io.github.zgxhzhr.maidfm.data.ImportResult> results = new java.util.ArrayList<>(list.size());
        for (io.github.zgxhzhr.maidfm.data.MaidFileData d : list) {
            if (d == null) {
                results.add(io.github.zgxhzhr.maidfm.data.ImportResult.failed(
                        Component.translatable("maid_file_manager.import.fail.invalid")));
                continue;
            }
            results.add(io.github.zgxhzhr.maidfm.service.MaidTransferService.importMaidFromData(
                    player, d, true, stripAttributes, blocked));
        }
        Component summary = io.github.zgxhzhr.maidfm.service.MaidTransferService.buildBatchSummary(results);
        player.sendSystemMessage(summary, false);
    }
}
