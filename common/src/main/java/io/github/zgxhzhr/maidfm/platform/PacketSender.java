package io.github.zgxhzhr.maidfm.platform;

import io.github.zgxhzhr.maidfm.Constants;
import io.github.zgxhzhr.maidfm.platform.services.ServerMessaging;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * 服务端消息发送的门面（业务侧唯一入口）。
 *
 * <p>{@link ServerMessaging} 的实现由各端通过 {@code ServiceLoader} 提供；
 * 本类负责「取服务 + 空实现兜底」，让调用方不必处理「服务没加载上」这种边缘情况。
 *
 * <p>此前的写法是各端网络层各有一个 private {@code sendFeedback}，被调用 32 次。
 * 现在调用点仍然调那个同名方法（它转发到这里），所以业务代码零改动——
 * 收敛的是<b>实现</b>与<b>契约</b>，不是调用点。
 */
public final class PacketSender {

    /** 服务不可用时的空实现：记录一次错误，避免每调用一次就刷一条日志。 */
    private static final ServerMessaging NOOP = new ServerMessaging() {
        @Override
        public void sendFeedback(ServerPlayer player, Component message) {
            Constants.LOG.error("[maid_file_manager] ServerMessaging 未加载，反馈消息被丢弃: {}",
                    message.getString());
        }

        @Override
        public void sendImportBatchResult(ServerPlayer player, Component summary, List<Boolean> spawned) {
            Constants.LOG.error("[maid_file_manager] ServerMessaging 未加载，批量导入结果被丢弃: {}",
                    summary.getString());
        }
    };

    private PacketSender() {
    }

    private static ServerMessaging messaging() {
        ServerMessaging m = Services.MESSAGING.get();
        return m == null ? NOOP : m;
    }

    /** 发送一条反馈消息。 */
    public static void sendFeedback(ServerPlayer player, Component message) {
        if (player == null || message == null) {
            return;
        }
        messaging().sendFeedback(player, message);
    }

    /** 发送批量导入结果（{@code spawned} 顺序必须与请求对齐）。 */
    public static void sendImportBatchResult(ServerPlayer player, Component summary, List<Boolean> spawned) {
        if (player == null || summary == null) {
            return;
        }
        messaging().sendImportBatchResult(player, summary, spawned == null ? List.of() : spawned);
    }
}
