package io.github.zgxhzhr.maidfm;

import io.github.zgxhzhr.maidfm.config.MaidConfigManager;
import io.github.zgxhzhr.maidfm.network.IMaidFileNetwork;
import io.github.zgxhzhr.maidfm.network.MaidFilePayloads;
import io.github.zgxhzhr.maidfm.network.NeoForgeNetwork;
import io.github.zgxhzhr.maidfm.platform.NeoForgePlatformHelper;
import io.github.zgxhzhr.maidfm.platform.Services;
import io.github.zgxhzhr.maidfm.platform.services.IPlatformHelper;
import io.github.zgxhzhr.maidfm.service.MaidServerCommands;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@Mod(Constants.MOD_ID)
public class MaidFileModNeoForge {

    public MaidFileModNeoForge(IEventBus modBus, ModContainer container) {
        Constants.LOG.info("========================================");
        Constants.LOG.info("[maid_file_manager] Mod constructor START (NeoForge)");

        Services.PLATFORM.loadService(IPlatformHelper.class, NeoForgePlatformHelper::new);
        Services.NETWORK.loadService(IMaidFileNetwork.class, NeoForgeNetwork::new);
        IMaidFileNetwork.Holder.set(Services.NETWORK.get());
        Constants.LOG.info("[maid_file_manager] Platform & Network services registered");

        modBus.addListener(this::registerPayloads);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLogin);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLogout);

        // 配置初始化（gameDir：专用服务器=服务器根目录；客户端=.minecraft；局域网=宿主 gameDir）
        MaidConfigManager.init(FMLPaths.GAMEDIR.get());

        Constants.LOG.info("[maid_file_manager] Mod constructor END");
        Constants.LOG.info("========================================");
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        // registrar 的第一个参数就是 NeoForge 用来做协议版本校验的字符串：
        // 原先传的是 MOD_ID（恒定），等价于永不拒绝版本差异；改为公共协议版本常量。
        final PayloadRegistrar registrar =
                event.registrar(io.github.zgxhzhr.maidfm.network.MaidFilePackets.PROTOCOL_VERSION);

        registrar.playToServer(MaidFilePayloads.RequestMaidListPayload.TYPE,
                MaidFilePayloads.RequestMaidListPayload.STREAM_CODEC,
                MaidFilePayloads.RequestMaidListPayload::handle);
        registrar.playToServer(MaidFilePayloads.ExportMaidPayload.TYPE,
                MaidFilePayloads.ExportMaidPayload.STREAM_CODEC,
                MaidFilePayloads.ExportMaidPayload::handle);
        registrar.playToServer(MaidFilePayloads.ExportBatchPayload.TYPE,
                MaidFilePayloads.ExportBatchPayload.STREAM_CODEC,
                MaidFilePayloads.ExportBatchPayload::handle);
        registrar.playToServer(MaidFilePayloads.ImportFilePayload.TYPE,
                MaidFilePayloads.ImportFilePayload.STREAM_CODEC,
                MaidFilePayloads.ImportFilePayload::handle);
        registrar.playToServer(MaidFilePayloads.ExportWriteAckPayload.TYPE,
                MaidFilePayloads.ExportWriteAckPayload.STREAM_CODEC,
                MaidFilePayloads.ExportWriteAckPayload::handle);
        registrar.playToServer(MaidFilePayloads.ChunkedUploadPayload.TYPE,
                MaidFilePayloads.ChunkedUploadPayload.STREAM_CODEC,
                MaidFilePayloads.ChunkedUploadPayload::handle);
        registrar.playToServer(MaidFilePayloads.ImportBatchPayload.TYPE,
                MaidFilePayloads.ImportBatchPayload.STREAM_CODEC,
                MaidFilePayloads.ImportBatchPayload::handle);
        registrar.playToServer(MaidFilePayloads.ClientConsentPayload.TYPE,
                MaidFilePayloads.ClientConsentPayload.STREAM_CODEC,
                MaidFilePayloads.ClientConsentPayload::handle);
        registrar.playToServer(MaidFilePayloads.SetServerConfigPayload.TYPE,
                MaidFilePayloads.SetServerConfigPayload.STREAM_CODEC,
                MaidFilePayloads.SetServerConfigPayload::handle);
        registrar.playToServer(MaidFilePayloads.SetServerBaubleBlockedListPayload.TYPE,
                MaidFilePayloads.SetServerBaubleBlockedListPayload.STREAM_CODEC,
                MaidFilePayloads.SetServerBaubleBlockedListPayload::handle);
        registrar.playToServer(MaidFilePayloads.RequestServerExportListPayload.TYPE,
                MaidFilePayloads.RequestServerExportListPayload.STREAM_CODEC,
                MaidFilePayloads.RequestServerExportListPayload::handle);
        registrar.playToServer(MaidFilePayloads.ServerExportBatchPayload.TYPE,
                MaidFilePayloads.ServerExportBatchPayload.STREAM_CODEC,
                MaidFilePayloads.ServerExportBatchPayload::handle);
        registrar.playToServer(MaidFilePayloads.RequestMaidProfilePayload.TYPE,
                MaidFilePayloads.RequestMaidProfilePayload.STREAM_CODEC,
                MaidFilePayloads.RequestMaidProfilePayload::handle);
        registrar.playToServer(MaidFilePayloads.SaveMaidProfilePayload.TYPE,
                MaidFilePayloads.SaveMaidProfilePayload.STREAM_CODEC,
                MaidFilePayloads.SaveMaidProfilePayload::handle);
        registrar.playToServer(MaidFilePayloads.RequestBackupListPayload.TYPE,
                MaidFilePayloads.RequestBackupListPayload.STREAM_CODEC,
                MaidFilePayloads.RequestBackupListPayload::handle);
        registrar.playToServer(MaidFilePayloads.RequestBackupExportPayload.TYPE,
                MaidFilePayloads.RequestBackupExportPayload.STREAM_CODEC,
                MaidFilePayloads.RequestBackupExportPayload::handle);

        registrar.playToClient(MaidFilePayloads.ChunkedDownloadPayload.TYPE,
                MaidFilePayloads.ChunkedDownloadPayload.STREAM_CODEC,
                MaidFilePayloads.ChunkedDownloadPayload::handle);
        registrar.playToClient(MaidFilePayloads.MaidListPayload.TYPE,
                MaidFilePayloads.MaidListPayload.STREAM_CODEC,
                MaidFilePayloads.MaidListPayload::handle);
        registrar.playToClient(MaidFilePayloads.ExportBatchResultPayload.TYPE,
                MaidFilePayloads.ExportBatchResultPayload.STREAM_CODEC,
                MaidFilePayloads.ExportBatchResultPayload::handle);
        registrar.playToClient(MaidFilePayloads.FeedbackPayload.TYPE,
                MaidFilePayloads.FeedbackPayload.STREAM_CODEC,
                MaidFilePayloads.FeedbackPayload::handle);
        registrar.playToClient(MaidFilePayloads.ImportBatchResultPayload.TYPE,
                MaidFilePayloads.ImportBatchResultPayload.STREAM_CODEC,
                MaidFilePayloads.ImportBatchResultPayload::handle);
        registrar.playToClient(MaidFilePayloads.ServerConfigSyncPayload.TYPE,
                MaidFilePayloads.ServerConfigSyncPayload.STREAM_CODEC,
                MaidFilePayloads.ServerConfigSyncPayload::handle);
        registrar.playToClient(MaidFilePayloads.ServerExportListPayload.TYPE,
                MaidFilePayloads.ServerExportListPayload.STREAM_CODEC,
                MaidFilePayloads.ServerExportListPayload::handle);
        registrar.playToClient(MaidFilePayloads.MaidProfilePayload.TYPE,
                MaidFilePayloads.MaidProfilePayload.STREAM_CODEC,
                MaidFilePayloads.MaidProfilePayload::handle);
        registrar.playToClient(MaidFilePayloads.BackupListPayload.TYPE,
                MaidFilePayloads.BackupListPayload.STREAM_CODEC,
                MaidFilePayloads.BackupListPayload::handle);
        registrar.playToClient(MaidFilePayloads.BackupExportResultPayload.TYPE,
                MaidFilePayloads.BackupExportResultPayload.STREAM_CODEC,
                MaidFilePayloads.BackupExportResultPayload::handle);

        Constants.LOG.info("[maid_file_manager] CustomPayload handlers registered: 13 C2S + 9 S2C");
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        // 只注册一次根节点：/maidfile gui 与 exportall 都在 MaidServerCommands 的同一次 builder 内注册。
        // 历史坑：此处曾额外注册一次无 requires 的 maidfile（含 gui），Brigadier 合并同名节点时
        // 保留先注册者的 requires，导致后面那次带 hasPermission(2) 的注册被丢弃、OP 门槛失效。
        MaidServerCommands.register(event.getDispatcher());
        Constants.LOG.info("[maid_file_manager] /maidfile exportall command registered");
    }

    /** 玩家登录：仅向该玩家单播服务端配置（客户端收到后回发同意状态） */
    private void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp) {
            MaidFilePayloads.sendServerConfig(sp);
        }
    }

    /** 玩家退出：清理服务端侧记录的同意状态 */
    private void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        MaidConfigManager.removeClientConsent(event.getEntity().getUUID());
    }
}
