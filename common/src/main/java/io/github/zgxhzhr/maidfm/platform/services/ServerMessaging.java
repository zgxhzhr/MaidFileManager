package io.github.zgxhzhr.maidfm.platform.services;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * 服务端 → 客户端的消息发送（阶段 4「平台层收敛」的第一个接口）。
 *
 * <p><b>为什么需要它</b>：{@code sendFeedback} 在六个平台的网络层各写一遍，
 * 且被调用 32 次以上。三份实现的差别只在最后一行发送调用
 * （Forge 的 {@code ServerNetworkBridge.sendToPlayer}、Fabric 的 {@code ServerPlayNetworking.send}、
 * NeoForge 的 {@code PacketDistributor}），前面的「创建 buf + 写 Component」完全相同。
 *
 * <p><b>为什么接口收 {@code Component} 而不是 {@code FriendlyByteBuf}</b>：跨版本不可行。
 * 1.20.x 用 {@code new FriendlyByteBuf(Unpooled.buffer())} / {@code PacketByteBufs.create()}，
 * 1.21+ 移除了 {@code PacketByteBufs}，{@code writeComponent} 也在 1.20.5+ 变过签名。
 * 把 buf 抽象出来会强迫所有端用同一套编码——那等于改协议，需要 bump {@code PROTOCOL_VERSION}
 * 并在六端发版，属于单独决策。
 *
 * <p>所以这里抽象的是<b>「发什么」而不是「怎么编码」</b>：编码留在各端实现里，
 * 业务侧只认 {@code Component}。收益是契约统一——将来要加限流、去重、或者按语言分流，
 * 只需改一处接口实现，不用回头改 32 个调用点。
 *
 * <p>实现类通过 {@code META-INF/services} 注册，见各端 {@code platform} 包。
 */
public interface ServerMessaging {

    /**
     * 发送一条反馈消息给指定玩家（客户端显示为底部黄字 / 聊天栏提示）。
     *
     * @param player  接收者
     * @param message 消息内容；编码由各端实现负责
     */
    void sendFeedback(ServerPlayer player, Component message);

    /**
     * 发送批量导入结果：汇总文案 + 逐项 spawned 标志（顺序必须与请求里的文件顺序对齐）。
     *
     * <p>客户端靠 {@code spawned} 区分「这个文件成功生成了女仆」与「导入了但没生成」，
     * 顺序错位会让提示张冠李戴。
     *
     * @param player  接收者
     * @param summary 汇总文案
     * @param spawned 与请求顺序对齐的成功标志
     */
    void sendImportBatchResult(ServerPlayer player, Component summary, List<Boolean> spawned);

    /**
     * 发送当前玩家的女仆列表（导出 Tab 的数据源）。
     *
     * @param player 接收者
     * @param list   {@code MaidTransferService.listOwnMaids} 的结果
     */
    void sendMaidList(ServerPlayer player, java.util.List<io.github.zgxhzhr.maidfm.data.MaidInfo> list);

    /**
     * 发送某只女仆的档案视图（客户端档案界面打开时用）。
     *
     * @param player 接收者
     * @param view   {@code MaidProfileService.buildView} 的结果
     */
    void sendMaidProfile(ServerPlayer player, io.github.zgxhzhr.maidfm.data.MaidProfileView view);

    /**
     * 把当前服务端配置推送给一个玩家（登录时 + 配置变更广播时）。
     *
     * <p>这里只收「推给谁」，配置内容由各端实现自己从 MaidConfigManager 读出并编码——
     * 与 sendMaidList 同一考虑：跨版本的编码方式不同，统一它等于改线格式。
     */
    void sendServerConfig(ServerPlayer player);

    /**
     * 该玩家是否有 OP 权限（等级 2）。
     *
     * <p>做成平台能力而不是各端自己判：1.20.x/1.21.x 用
     * {@code player.hasPermissions(2)}，26.1.2 改成
     * {@code player.permissions().hasPermission(Permissions.COMMANDS_MODERATOR)}。
     * 这是纯版本差异，业务层不该知道。
     */
    boolean hasOpPermission(ServerPlayer player);

    /**
     * 取玩家所属的服务端实例。
     *
     * <p>1.20.x/1.21.x 的 {@code ServerPlayer.server} 是 public 字段，26.1.2 改成 private，
     * 只能走 {@code ((ServerLevel) player.level()).getServer()}。
     */
    net.minecraft.server.MinecraftServer serverOf(ServerPlayer player);

    /**
     * 发送「导出结果」整包（已编码好的字节，不走分片）。
     *
     * <p>收的是已编码字节而不是 List&lt;MaidFileData&gt;：序列化 + 契约预检 + 编码都在 common
     * 的 ServerPacketHandlers 里做完了，各端只负责把这段字节按自己的网络 API 发出去。
     */
    void sendExportBatchResult(ServerPlayer player, byte[] encodedPayload);

    /**
     * 分片下发（大载荷，超过单包上限时用）。
     *
     * <p>分片协议（sessionId / total / index / chunk）在各端实现里，因为它与接收端
     * ChunkedUploadAssembler 的读法必须严格配对。
     *
     * @return true=全部发出；false=超总量上限（已回执失败）
     */
    boolean sendChunkedDownload(ServerPlayer player, String kind, byte[] payload);

    /**
     * 发送 OP 统一导出用的「全服玩家女仆分组列表」。
     *
     * <p>收的是已算好的分组，编码在各端实现里（{@code MaidInfoCodec.writePlayerMaidGroups}），
     * 与 sendMaidList 同一考虑：跨版本编码方式不同。
     */
    void sendServerExportList(ServerPlayer player,
                              java.util.List<io.github.zgxhzhr.maidfm.network.IMaidFileNetwork.PlayerMaidGroup> groups);

    /**
     * 发送「可浏览的自动备份列表」。
     *
     * <p>编码在各端实现里（{@code MaidBackupCodec.writeBackupOwners}）。
     */
    void sendBackupList(ServerPlayer player,
                        java.util.List<io.github.zgxhzhr.maidfm.network.IMaidFileNetwork.BackupOwner> owners);
}
