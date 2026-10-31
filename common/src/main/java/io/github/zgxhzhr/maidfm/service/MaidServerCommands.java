package io.github.zgxhzhr.maidfm.service;

import io.github.zgxhzhr.maidfm.Constants;
import io.github.zgxhzhr.maidfm.config.MaidConfigManager;
import io.github.zgxhzhr.maidfm.data.MaidFileData;
import io.github.zgxhzhr.maidfm.data.MaidFileIo;
import io.github.zgxhzhr.maidfm.network.IMaidFileNetwork;
import io.github.zgxhzhr.maidfm.platform.Services;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 服务端指令：{@code /maidfile exportall}（仅 OP）。
 *
 * <p>统一导出所有在线玩家同意后的女仆到服务端文件系统：
 * {@code maid_exports/<玩家名>/<女仆中文名>_<时间戳>_<短UUID>.maid}
 * （女仆文件命名逻辑与客户端导出完全一致，仅多一层玩家名目录）
 *
 * <p>只有客户端在设置中同意「允许服务端统一导出你的女仆」（默认不允许）的玩家才会被导出。
 */
public final class MaidServerCommands {
    /** 统一导出时搜索主人周围女仆的范围（格）：确保离服主较远的玩家的女仆也能被导出 */
    private static final double EXPORT_ALL_RADIUS = 256.0D;

    private MaidServerCommands() {
    }

    /**
     * 由 loader 侧在指令注册事件中调用。
     *
     * <p><b>根节点不得带 requires</b>：`/maidfile gui` 任何玩家都能用（只是打开自己的界面），
     * 而 `/maidfile exportall` 必须 OP。门槛各自挂在子节点上。
     *
     * <p>历史坑：此前 NeoForge 主类先注册了一次无 requires 的 maidfile（含 gui），随后这里再注册
     * 同名节点（含 requires(2)）。Brigadier 合并同名节点时<b>保留先注册者的 requires</b>，
     * 导致 OP 门槛被丢弃、非 OP 也能执行 exportall。现在根节点只在这里注册一次。
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal("maidfile")
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("exportall")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> executeExportAll(context.getSource()))));
    }


    private static int executeExportAll(CommandSourceStack source) {
        // 双保险：即使命令树的 requires 因任何原因失效，执行体自身也拒绝非 OP。
        if (!source.hasPermission(2)) {
            source.sendFailure(Component.translatable("maid_file_manager.config.fail.no_permission"));
            return 0;
        }
        MinecraftServer server = source.getServer();
        if (server == null) {
            source.sendFailure(Component.translatable("maid_file_manager.command.no_server"));
            return 0;
        }
        Path exportRoot = Services.PLATFORM.get().getGameDir().toAbsolutePath().resolve(Constants.MAID_EXPORTS_DIR);
        // 与 exportMaid / exportBatch 保持一致：清掉上一轮残留。
        // 这条命令路径以前既不 discard 也不 take，导致 provider 导出失败在这里完全静默，
        // 而它正是「本版要消灭静默」要覆盖的一条路。
        io.github.zgxhzhr.maidfm.data.CuriosBaubleFilter.takeBatch();
        io.github.zgxhzhr.maidfm.data.MigrationNoteCollector.discard();
        int exportedPlayers = 0;
        int skippedPlayers = 0;
        int exportedFiles = 0;
        List<String> skippedNames = new ArrayList<>();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String playerName = player.getName().getString();
            if (!MaidConfigManager.getClientConsent(player.getUUID())) {
                skippedPlayers++;
                skippedNames.add(playerName);
                continue;
            }
            List<EntityMaid> maids = player.level().getEntitiesOfClass(EntityMaid.class,
                    player.getBoundingBox().inflate(EXPORT_ALL_RADIUS),
                    maid -> maid.isTame() && player.equals(maid.getOwner()));
            Path playerDir = MaidFileIo.resolvePlayerDir(exportRoot, playerName);
            int count = 0;
            for (EntityMaid maid : maids) {
                try {
                    // 统一导出专用 API：永远不会移除/丢弃女仆，只写入 NBT 数据到磁盘
                    MaidFileData data = MaidTransferService.exportMaidOwnedBy(player, maid.getId());
                    if (data == null) {
                        continue;
                    }
                    MaidFileIo.writeMaidFile(playerDir,
                            MaidFileIo.composeDisplayName(data.getCustomName(), data.getDisplayName()),
                            data.getModelId(), data.getOwnerUuid(), data, LocalDateTime.now());
                    count++;
                } catch (Exception e) {
                    Constants.LOG.error("[女仆档案管理] 统一导出女仆失败: player={}, maid={}", playerName, maid.getId(), e);
                }
            }
            exportedPlayers++;
            exportedFiles += count;
            Constants.LOG.info("[女仆档案管理] 统一导出: 玩家 {} 导出 {} 个女仆文件", playerName, count);
        }

        final int fExported = exportedPlayers;
        final int fSkipped = skippedPlayers;
        final int fFiles = exportedFiles;
        // 文案走 lang 键：英文客户端不应看到硬编码中文
        final Component skippedDesc = skippedNames.isEmpty()
                ? Component.translatable("maid_file_manager.command.none")
                : Component.literal(String.join(", ", skippedNames));
        net.minecraft.network.chat.MutableComponent done =
                Component.translatable("maid_file_manager.command.exportall.done",
                        fExported, fSkipped, skippedDesc, fFiles, exportRoot.toString());
        // 附加数据未带上：不再静默
        List<Component> migrationNotes = io.github.zgxhzhr.maidfm.data.MigrationNoteCollector.take();
        if (!migrationNotes.isEmpty()) {
            done.append(Component.literal("。"));
            done.append(Component.translatable("maid_file_manager.export.migration_details"));
            int shown = Math.min(migrationNotes.size(), 5);
            for (int i = 0; i < shown; i++) {
                if (i > 0) {
                    done.append(Component.translatable("maid_file_manager.list_separator.newline"));
                }
                done.append(migrationNotes.get(i));
            }
            if (migrationNotes.size() > shown) {
                done.append(Component.translatable(
                        "maid_file_manager.export.migration.more", migrationNotes.size() - shown));
            }
        }
        source.sendSuccess(() -> done, false);
        return 1;
    }



    /**
     * OP 统一导出浏览：收集所有在线玩家（以各玩家为中心、128 格内）的女仆列表。
     * 每个玩家附带其「允许服务端统一导出」同意状态；未同意的玩家女仆仍会列出，
     * 但提交导出时会被服务端以同意闸门拦截并说明原因。
     */
    public static List<IMaidFileNetwork.PlayerMaidGroup> collectOnlinePlayerMaids(MinecraftServer server) {
        List<IMaidFileNetwork.PlayerMaidGroup> groups = new ArrayList<>();
        if (server == null) {
            return groups;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            groups.add(new IMaidFileNetwork.PlayerMaidGroup(
                    player.getName().getString(),
                    MaidConfigManager.getClientConsent(player.getUUID()),
                    MaidTransferService.listOwnMaids(player)));
        }
        return groups;
    }

    /**
     * OP 统一导出提交：代各玩家把指定女仆导出到服务端磁盘 {@code maid_exports/<玩家名>/}。
     * 逐组校验：目标玩家在线 + 已同意统一导出；逐只校验女仆归属。
     *
     * @return 汇总反馈（成功数量、按玩家明细、跳过原因）
     */
    /**
     * 单次「统一导出」请求允许的实体 ID 总数上限。
     *
     * <p>协议层允许 128 组 x 512 个 id（MAX_PLAYER_GROUPS / MAX_EXPORT_IDS），
     * 但每个 id 都要做一次实体查找 + NBT 序列化，65536 个足以让主线程卡死几十秒。
     * 正常玩法下一次导出也就几十到几百只，2048 已是宽松上限。
     */
    private static final int MAX_TOTAL_EXPORT_IDS = 2048;

    public static Component exportForPlayers(MinecraftServer server,
                                             List<IMaidFileNetwork.PlayerExportRequest> requests) {
        if (server == null) {
            return Component.translatable("maid_file_manager.command.no_server");
        }
        // 先按总量拦一次：避免 128x512 的请求把主线程拖死
        int requestedTotal = 0;
        for (IMaidFileNetwork.PlayerExportRequest req : requests) {
            if (req.entityIds() != null) {
                requestedTotal += req.entityIds().size();
            }
        }
        if (requestedTotal > MAX_TOTAL_EXPORT_IDS) {
            Constants.LOG.warn("[maid_file_manager] 统一导出请求数量超限，已拒绝: {} > {}",
                    requestedTotal, MAX_TOTAL_EXPORT_IDS);
            return Component.translatable("maid_file_manager.command.export.too_many",
                    requestedTotal, MAX_TOTAL_EXPORT_IDS);
        }
        Path exportRoot = Services.PLATFORM.get().getGameDir().toAbsolutePath().resolve(Constants.MAID_EXPORTS_DIR);
        // 先清掉上一轮残留的 Curios 批次统计：CuriosBaubleFilter 的统计是 ThreadLocal，
        // 而 exportall / OP 统一导出这条路径**不消费**它（只有 sendExportResults 会调
        // curiosDropTip 取走），残留值会串到下一位玩家的提示里。
        io.github.zgxhzhr.maidfm.data.CuriosBaubleFilter.takeBatch();
        // 同理清掉上一轮残留的「附加数据未带上」记录
        io.github.zgxhzhr.maidfm.data.MigrationNoteCollector.discard();
        int totalOk = 0;
        int totalFail = 0;
        List<Component> details = new ArrayList<>();
        List<Component> skipped = new ArrayList<>();

        for (IMaidFileNetwork.PlayerExportRequest req : requests) {
            String name = req.playerName();
            ServerPlayer owner = server.getPlayerList().getPlayerByName(name);
            if (owner == null) {
                skipped.add(Component.translatable("maid_file_manager.command.export.player_offline", name));
                Constants.LOG.warn("[maid_file_manager] 统一导出跳过：玩家不在线 player={}, 请求实体数={}",
                        name, req.entityIds() == null ? 0 : req.entityIds().size());
                continue;
            }
            if (!MaidConfigManager.getClientConsent(owner.getUUID())) {
                skipped.add(Component.translatable("maid_file_manager.command.export.no_consent", name));
                Constants.LOG.warn("[maid_file_manager] 统一导出跳过：玩家未开启同意 player={}, uuid={}",
                        name, owner.getUUID());
                continue;
            }
            // 玩家名经 resolvePlayerDir 清洗并断言目录不越界（兼容单独下划线玩家名，拒绝 "."/".."）
            Path playerDir = MaidFileIo.resolvePlayerDir(exportRoot, name);
            int ok = 0;
            int fail = 0;
            for (int entityId : req.entityIds()) {
                try {
                    // 统一导出专用 API：永远不会移除/丢弃女仆，只写入 NBT 数据到磁盘
                    MaidFileData data = MaidTransferService.exportMaidOwnedBy(owner, entityId);
                    if (data == null) {
                        fail++;
                        Constants.LOG.warn("[maid_file_manager] 统一导出失败：未找到女仆 owner={}, uuid={}, entityId={}",
                                name, owner.getUUID(), entityId);
                        continue;
                    }
                    MaidFileIo.writeMaidFile(playerDir,
                            MaidFileIo.composeDisplayName(data.getCustomName(), data.getDisplayName()),
                            data.getModelId(), data.getOwnerUuid(), data, LocalDateTime.now());
                    ok++;
                } catch (Exception e) {
                    fail++;
                    Constants.LOG.error("[女仆档案管理] OP 统一导出单只女仆失败: owner={}, entityId={}, dir={}",
                            name, entityId, playerDir, e);
                }
            }
            totalOk += ok;
            totalFail += fail;
            net.minecraft.network.chat.MutableComponent detail =
                    Component.translatable("maid_file_manager.command.export.player_detail", name, ok);
            if (fail > 0) {
                detail.append(Component.translatable("maid_file_manager.command.export.player_detail_fail", fail));
            }
            details.add(detail);
            // 正常完成路径用 INFO：即使个别失败（已在上方 error 记录），整批完成本身不是警告，避免污染告警筛选
            Constants.LOG.info("[maid_file_manager] 统一导出玩家完成：player={}, 成功={}, 失败={}, 保存目录={}",
                    name, ok, fail, playerDir);
        }

        net.minecraft.network.chat.MutableComponent out =
                Component.translatable("maid_file_manager.command.export.done", totalOk);
        if (totalFail > 0) {
            out.append(Component.translatable("maid_file_manager.command.export.done_fail", totalFail));
        }
        out.append(Component.translatable("maid_file_manager.command.export.dir_hint"));
        if (!details.isEmpty()) {
            out.append(Component.translatable("maid_file_manager.command.export.details"));
            out.append(joinWithSeparator(details));
        }
        if (!skipped.isEmpty()) {
            out.append(Component.translatable("maid_file_manager.command.export.skipped"));
            out.append(joinWithSeparator(skipped));
        }
        // 附加数据未带上：这条路径是服务端写盘，不能静默
        List<Component> migrationNotes = io.github.zgxhzhr.maidfm.data.MigrationNoteCollector.take();
        if (!migrationNotes.isEmpty()) {
            // export.migration_details 是给「独立提示」用的，不带前导句号；
            // 这条命令路径是把它追加到上一次输出末尾，所以这里自己补一个断句。
            out.append(Component.literal("。"));
            out.append(Component.translatable("maid_file_manager.export.migration_details"));
            int shown = Math.min(migrationNotes.size(), 5);
            for (int i = 0; i < shown; i++) {
                if (i > 0) {
                    out.append(Component.translatable("maid_file_manager.list_separator.newline"));
                }
                out.append(migrationNotes.get(i));
            }
            if (migrationNotes.size() > shown) {
                out.append(Component.translatable(
                        "maid_file_manager.export.migration.more", migrationNotes.size() - shown));
            }
        }
        return out;
    }

    /** 用语言文件里的分隔符连接若干 Component（明细 / 跳过列表展示用；中文「、」英文「, 」） */
    private static Component joinWithSeparator(List<Component> parts) {
        net.minecraft.network.chat.MutableComponent out = Component.empty();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                out.append(Component.translatable("maid_file_manager.command.list_separator"));
            }
            out.append(parts.get(i));
        }
        return out;
    }
}
