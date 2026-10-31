package io.github.zgxhzhr.maidfm.network;

import io.github.zgxhzhr.maidfm.data.MaidInfo;
import io.github.zgxhzhr.maidfm.platform.PacketSender;
import io.github.zgxhzhr.maidfm.service.MaidTransferService;

import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * C2S 请求的业务处理（平台无关部分）。
 *
 * <p>各平台的网络层收到包后，只负责「把参数从 buf 读出来」，然后调本类；
 * 本类做频控、业务调用、回包，全平台共用一份。
 *
 * <p><b>为什么不包括读 buf</b>：各版本的读取 API 不同（{@code readComponent} 签名在
 * 1.20.5+ 变过、{@code PacketByteBufs} 在 1.21+ 移除、26.1.2 的类型化 getter 返回
 * Optional）。把「读」统一就等于改线格式，那需要 bump {@code PROTOCOL_VERSION} 并在
 * 六端发版。所以边界画在这里：**读在平台层，处理在 common**。
 *
 * <p>回包走 {@link PacketSender}——它背后是各端实现的 {@code ServerMessaging}，
 * 编码留在平台层（同样是因为跨版本不可行）。
 *
 * <p>本类目前只迁移了 {@code handleRequestMaidList}。其余 handler 逐个迁，
 * 每个都要先确认它的发送方式已被 {@code ServerMessaging} 覆盖。
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
}
