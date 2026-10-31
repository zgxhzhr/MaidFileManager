package io.github.zgxhzhr.maidfm.network;

import io.github.zgxhzhr.maidfm.Constants;
import io.github.zgxhzhr.maidfm.data.MaidInfo;
import io.github.zgxhzhr.maidfm.data.MaidProfile;
import io.github.zgxhzhr.maidfm.data.MaidProfileView;
import io.github.zgxhzhr.maidfm.platform.PacketSender;
import io.github.zgxhzhr.maidfm.service.MaidProfileService;
import io.github.zgxhzhr.maidfm.service.MaidTransferService;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.List;

/**
 * C2S 请求的业务处理（平台无关部分）。
 *
 * <p>各平台的网络层收到包后，只负责「把参数从 buf 读出来」，然后调本类；
 * 本类做频控、权限校验、业务调用、回包，全平台共用一份。
 *
 * <p><b>为什么不包括读 buf</b>：各版本的读取 API 不同（{@code readComponent} 签名在
 * 1.20.5+ 变过、{@code PacketByteBufs} 在 1.21+ 移除、26.1.2 的类型化 getter 返回
 * Optional）。把「读」统一就等于改线格式，那需要 bump {@code PROTOCOL_VERSION} 并在
 * 六端发版。所以边界画在这里：**读在平台层，处理在 common**。
 *
 * <p>回包走 {@link PacketSender}——它背后是各端实现的 {@code ServerMessaging}，
 * 编码留在平台层（同样是因为跨版本不可行）。
 */
public final class ServerPacketHandlers {

    private ServerPacketHandlers() {
    }

    /**
     * 处理「请求我的女仆列表」（导出 Tab 打开时发一次）。
     *
     * <p>保留原实现的频控：这类请求在主线程同步跑，循环发包能把服务器卡死，
     * 且不需要 OP 权限。同一玩家 1 秒内重复请求直接丢弃。
     */
    public static void requestMaidList(ServerPlayer player) {
        if (player == null) {
            return;
        }
        if (!RequestThrottle.allow(player.getUUID(), "maid_list", 1000L)) {
            return;
        }
        List<MaidInfo> list = MaidTransferService.listOwnMaids(player);
        PacketSender.sendMaidList(player, list);
    }

    /**
     * 处理「保存女仆档案」。
     *
     * <p>校验失败只回执失败文案，不改动实体——档案写入是不可逆的，宁可不写。
     */
    public static void saveMaidProfile(ServerPlayer player, int entityId, MaidProfile profile) {
        if (player == null) {
            return;
        }
        if (profile == null) {
            PacketSender.sendFeedback(player,
                    Component.translatable("gui.maid_file_manager.profile.save_failed"));
            return;
        }
        Entity e = player.level().getEntity(entityId);
        if (!(e instanceof EntityMaid maid) || !maid.isOwnedBy(player)) {
            PacketSender.sendFeedback(player,
                    Component.translatable("gui.maid_file_manager.profile.no_permission"));
            return;
        }
        MaidProfileService.writeToMaid(maid, profile, true);
        PacketSender.sendFeedback(player,
                Component.translatable("gui.maid_file_manager.profile.save_ok"));
    }

    /**
     * 处理「请求女仆档案」（客户端打开档案界面时发一次）。
     *
     * <p>保留原实现的频控，理由同 {@link #requestMaidList}。
     */
    public static void requestMaidProfile(ServerPlayer player, int entityId) {
        if (player == null) {
            return;
        }
        if (!RequestThrottle.allow(player.getUUID(), "maid_profile", 1000L)) {
            return;
        }
        Entity e = player.level().getEntity(entityId);
        if (!(e instanceof EntityMaid maid) || !maid.isOwnedBy(player)) {
            PacketSender.sendFeedback(player,
                    Component.translatable("gui.maid_file_manager.profile.no_permission"));
            return;
        }
        MaidProfileView view = MaidProfileService.buildView(player, maid);
        if (view == null) {
            PacketSender.sendFeedback(player,
                    Component.translatable("gui.maid_file_manager.profile.no_permission"));
            return;
        }
        PacketSender.sendMaidProfile(player, view);
    }
}
