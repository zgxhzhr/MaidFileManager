package io.github.zgxhzhr.maidfm.service;

import io.github.zgxhzhr.maidfm.Constants;
import io.github.zgxhzhr.maidfm.platform.Services;
import io.github.zgxhzhr.maidfm.platform.services.IPlatformHelper;

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

    /**
     * 消息发送服务的持有者。
     *
     * <p>从 {@code Services} 拆出来：Services 只保留纯环境能力（PLATFORM，无出边），
     * 而这里的接口签名要认识 MaidInfo / MaidProfileView / IMaidFileNetwork.* 等业务类型，
     * 属于服务层。
     */
    private static final Services.ServiceProvider<ServerMessaging> MESSAGING =
            new Services.ServiceProvider<>();

    static {
        try {
            MESSAGING.loadService(ServerMessaging.class,
                    () -> Services.load(ServerMessaging.class));
        } catch (Exception e) {
            // 不致命：下面的 NOOP 兜底，反馈消息会丢但功能仍可运行
            Constants.LOG.error("[maid_file_manager] 平台服务 ServerMessaging 加载失败，"
                    + "服务端反馈消息将无法送达客户端", e);
        }
    }

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

        @Override
        public void sendMaidList(ServerPlayer player, java.util.List<io.github.zgxhzhr.maidfm.data.MaidInfo> list) {
            Constants.LOG.error("[maid_file_manager] ServerMessaging 未加载，女仆列表被丢弃（{} 条）", list.size());
        }

        @Override
        public void sendMaidProfile(ServerPlayer player, io.github.zgxhzhr.maidfm.data.MaidProfileView view) {
            Constants.LOG.error("[maid_file_manager] ServerMessaging 未加载，女仆档案被丢弃");
        }

        @Override
        public void sendServerConfig(ServerPlayer player) {
            Constants.LOG.error("[maid_file_manager] ServerMessaging 未加载，服务端配置无法下发");
        }

        @Override
        public void sendExportBatchResult(ServerPlayer player, byte[] encodedPayload) {
            Constants.LOG.error("[maid_file_manager] ServerMessaging 未加载，导出结果被丢弃（{} 字节）",
                    encodedPayload.length);
        }

        @Override
        public boolean sendChunkedDownload(ServerPlayer player, String kind, byte[] payload) {
            Constants.LOG.error("[maid_file_manager] ServerMessaging 未加载，分片下发被丢弃（{} 字节）",
                    payload == null ? 0 : payload.length);
            return false;
        }

        @Override
        public void sendServerExportList(ServerPlayer player, java.util.List<io.github.zgxhzhr.maidfm.network.IMaidFileNetwork.PlayerMaidGroup> groups) {
            Constants.LOG.error("[maid_file_manager] ServerMessaging 未加载，统一导出列表被丢弃（{} 组）",
                    groups.size());
        }

        @Override
        public void sendBackupList(ServerPlayer player, java.util.List<io.github.zgxhzhr.maidfm.network.IMaidFileNetwork.BackupOwner> owners) {
            Constants.LOG.error("[maid_file_manager] ServerMessaging 未加载，备份列表被丢弃（{} 组）",
                    owners.size());
        }

        @Override
        public boolean hasOpPermission(ServerPlayer player) {
            return false;
        }

        @Override
        public net.minecraft.server.MinecraftServer serverOf(ServerPlayer player) {
            return null;
        }
    };

    private PacketSender() {
    }

    private static ServerMessaging messaging() {
        ServerMessaging m = MESSAGING.get();
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

    /** 发送女仆列表。 */
    public static void sendMaidList(net.minecraft.server.level.ServerPlayer player,
                                    java.util.List<io.github.zgxhzhr.maidfm.data.MaidInfo> list) {
        if (player == null) {
            return;
        }
        messaging().sendMaidList(player, list == null ? java.util.List.of() : list);
    }

    /** 发送女仆档案视图。 */
    public static void sendMaidProfile(net.minecraft.server.level.ServerPlayer player,
                                       io.github.zgxhzhr.maidfm.data.MaidProfileView view) {
        if (player == null || view == null) {
            return;
        }
        messaging().sendMaidProfile(player, view);
    }

    /** 把服务端配置推给单个玩家。 */
    public static void sendServerConfig(net.minecraft.server.level.ServerPlayer player) {
        if (player == null) {
            return;
        }
        messaging().sendServerConfig(player);
    }

    /**
     * 把服务端配置广播给全服玩家（配置变更后调用）。
     *
     * <p>遍历逻辑放在这里而不是各端：它是纯 common 代码，而且漏掉某个玩家会导致
     * 该玩家的客户端配置与服务端不一致——正是这类「六端各写一遍」最容易写错的地方。
     */
    public static void broadcastServerConfig(net.minecraft.server.MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (net.minecraft.server.level.ServerPlayer p : server.getPlayerList().getPlayers()) {
            sendServerConfig(p);
        }
    }

    /** 该玩家是否 OP（平台差异收敛见 ServerMessaging#hasOpPermission）。 */
    public static boolean hasOpPermission(net.minecraft.server.level.ServerPlayer player) {
        return player != null && messaging().hasOpPermission(player);
    }

    /** 取玩家所属服务端（平台差异收敛见 ServerMessaging#serverOf）。 */
    public static net.minecraft.server.MinecraftServer serverOf(
            net.minecraft.server.level.ServerPlayer player) {
        return player == null ? null : messaging().serverOf(player);
    }

    /** 把配置广播给玩家所在的全服（自己拿 server，供业务层调用）。 */
    public static void broadcastServerConfigTo(net.minecraft.server.level.ServerPlayer context) {
        broadcastServerConfig(serverOf(context));
    }

    /** 发送导出结果整包（已编码）。 */
    public static void sendExportBatchResult(net.minecraft.server.level.ServerPlayer player,
                                             byte[] encodedPayload) {
        if (player == null || encodedPayload == null || encodedPayload.length == 0) {
            return;
        }
        messaging().sendExportBatchResult(player, encodedPayload);
    }

    /** 分片下发；返回 false 表示超总量上限（已回执失败）。 */
    public static boolean sendChunkedDownload(net.minecraft.server.level.ServerPlayer player,
                                              String kind, byte[] payload) {
        if (player == null) {
            return false;
        }
        return messaging().sendChunkedDownload(player, kind, payload);
    }

    /** 发送 OP 统一导出用的分组列表。 */
    public static void sendServerExportList(net.minecraft.server.level.ServerPlayer player,
                                            java.util.List<io.github.zgxhzhr.maidfm.network.IMaidFileNetwork.PlayerMaidGroup> groups) {
        if (player == null) {
            return;
        }
        messaging().sendServerExportList(player, groups == null ? java.util.List.of() : groups);
    }

    /** 发送可浏览的备份列表。 */
    public static void sendBackupList(net.minecraft.server.level.ServerPlayer player,
                                      java.util.List<io.github.zgxhzhr.maidfm.network.IMaidFileNetwork.BackupOwner> owners) {
        if (player == null) {
            return;
        }
        messaging().sendBackupList(player, owners == null ? java.util.List.of() : owners);
    }
}
