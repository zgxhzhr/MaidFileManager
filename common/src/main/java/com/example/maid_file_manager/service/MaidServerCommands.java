package com.example.maid_file_manager.service;

import com.example.maid_file_manager.Constants;
import com.example.maid_file_manager.config.MaidConfigManager;
import com.example.maid_file_manager.data.MaidFileData;
import com.example.maid_file_manager.data.MaidFileIo;
import com.example.maid_file_manager.network.IMaidFileNetwork;
import com.example.maid_file_manager.platform.Services;
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
    /** 玩家名作为目录名时的非法字符正则（与 MaidFileIo 同规则） */
    private static final String INVALID_CHAR_REGEX = "[\\\\/:*?\"<>|\\[\\]\\u0000-\\u001F]";

    private MaidServerCommands() {
    }

    /** 由 loader 侧在指令注册事件中调用 */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal("maidfile")
                .requires(source -> source.hasPermission(2))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("exportall")
                        .executes(context -> executeExportAll(context.getSource()))));
    }

    private static int executeExportAll(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        if (server == null) {
            source.sendFailure(Component.literal("[女仆文件管理] 无法获取服务端"));
            return 0;
        }
        Path exportRoot = Services.PLATFORM.get().getGameDir().toAbsolutePath().resolve(Constants.MAID_EXPORTS_DIR);
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
            Path playerDir = exportRoot.resolve(MaidFileIo.sanitizePlayerName(playerName));
            int count = 0;
            for (EntityMaid maid : maids) {
                try {
                    // 统一导出专用 API：永远不会移除/丢弃女仆，只写入 NBT 数据到磁盘
                    MaidFileData data = MaidTransferService.exportMaidOwnedBy(player, maid.getId());
                    if (data == null) {
                        continue;
                    }
                    MaidFileIo.writeMaidFile(playerDir, data.getDisplayName(), data.getModelId(),
                            data.getOwnerUuid(), data, LocalDateTime.now());
                    count++;
                } catch (Exception e) {
                    Constants.LOG.error("[女仆文件管理] 统一导出女仆失败: player={}, maid={}", playerName, maid.getId(), e);
                }
            }
            exportedPlayers++;
            exportedFiles += count;
            Constants.LOG.info("[女仆文件管理] 统一导出: 玩家 {} 导出 {} 个女仆文件", playerName, count);
        }

        final int fExported = exportedPlayers;
        final int fSkipped = skippedPlayers;
        final int fFiles = exportedFiles;
        final String skippedDesc = skippedNames.isEmpty() ? "无" : String.join(", ", skippedNames);
        final String summary = String.format("[女仆文件管理] 统一导出完成：玩家 %d 人（跳过未同意 %d 人: %s），共 %d 个文件 → %s",
                fExported, fSkipped, skippedDesc, fFiles, exportRoot);
        source.sendSuccess(() -> Component.literal(summary), false);
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
    public static Component exportForPlayers(MinecraftServer server,
                                             List<IMaidFileNetwork.PlayerExportRequest> requests) {
        if (server == null) {
            return Component.literal("[女仆文件管理] 无法获取服务端");
        }
        Path exportRoot = Services.PLATFORM.get().getGameDir().toAbsolutePath().resolve(Constants.MAID_EXPORTS_DIR);
        int totalOk = 0;
        int totalFail = 0;
        List<String> details = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        for (IMaidFileNetwork.PlayerExportRequest req : requests) {
            String name = req.playerName();
            ServerPlayer owner = server.getPlayerList().getPlayerByName(name);
            if (owner == null) {
                skipped.add(name + "：不在线");
                Constants.LOG.warn("[maid_file_manager] 统一导出跳过：玩家不在线 player={}, 请求实体数={}",
                        name, req.entityIds() == null ? 0 : req.entityIds().size());
                continue;
            }
            if (!MaidConfigManager.getClientConsent(owner.getUUID())) {
                skipped.add(name + "：未同意「允许服务端统一导出你的女仆」");
                Constants.LOG.warn("[maid_file_manager] 统一导出跳过：玩家未开启同意 player={}, uuid={}",
                        name, owner.getUUID());
                continue;
            }
            // 玩家名仅清洗非法字符（禁止折叠下划线→把 "_" 清空为 unknown 的 bug）
            Path playerDir = exportRoot.resolve(MaidFileIo.sanitizePlayerName(name));
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
                    MaidFileIo.writeMaidFile(playerDir, data.getDisplayName(), data.getModelId(),
                            data.getOwnerUuid(), data, LocalDateTime.now());
                    ok++;
                } catch (Exception e) {
                    fail++;
                    Constants.LOG.error("[女仆文件管理] OP 统一导出单只女仆失败: owner={}, entityId={}, dir={}",
                            name, entityId, playerDir, e);
                }
            }
            totalOk += ok;
            totalFail += fail;
            details.add(name + "：" + ok + " 个" + (fail > 0 ? "（失败 " + fail + " 个）" : ""));
            Constants.LOG.warn("[maid_file_manager] 统一导出玩家完成：player={}, 成功={}, 失败={}, 保存目录={}",
                    name, ok, fail, playerDir);
        }

        StringBuilder sb = new StringBuilder("[女仆文件管理] 统一导出完成：共成功 ").append(totalOk).append(" 个");
        if (totalFail > 0) {
            sb.append("，失败 ").append(totalFail).append(" 个");
        }
        sb.append("（保存到服务端 maid_exports/<玩家名>/）");
        if (!details.isEmpty()) {
            sb.append("。明细：").append(String.join("、", details));
        }
        if (!skipped.isEmpty()) {
            sb.append("。跳过：").append(String.join("、", skipped));
        }
        return Component.literal(sb.toString());
    }
}
