package io.github.zgxhzhr.maidfm;

import io.github.zgxhzhr.maidfm.client.MaidBackupBrowserScreen;
import io.github.zgxhzhr.maidfm.client.MaidFileManagerScreen;
import io.github.zgxhzhr.maidfm.client.MaidFileKeyMappings;
import io.github.zgxhzhr.maidfm.config.MaidConfigManager;
import io.github.zgxhzhr.maidfm.data.MaidFileData;
import io.github.zgxhzhr.maidfm.data.MaidInfo;
import io.github.zgxhzhr.maidfm.data.MaidProfileView;
import io.github.zgxhzhr.maidfm.network.FabricNetwork;
import io.github.zgxhzhr.maidfm.network.IMaidFileNetwork;
import io.github.zgxhzhr.maidfm.network.MaidFilePackets;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

import java.util.List;

public class MaidFileModFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        KeyBindingHelper.registerKeyBinding(MaidFileKeyMappings.OPEN_MANAGER);
        IMaidFileNetwork.Holder.set(new FabricNetwork());
        registerTitleScreenEntry();

        // 协议版本校验（Fabric 没有 Forge 的 NetworkRegistry predicate，需显式上报）
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            try {
                net.minecraft.network.FriendlyByteBuf buf = net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create();
                buf.writeUtf(MaidFilePackets.PROTOCOL_VERSION);
                net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                        MaidFilePackets.ID_PROTOCOL_CHECK, buf);
            } catch (Throwable t) {
                io.github.zgxhzhr.maidfm.Constants.LOG.debug("[maid_file_manager] 协议版本上报失败: {}", t.toString());
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(MaidFilePackets.ID_MAID_LIST, (client, handler, buf, responseSender) -> {
            try {
            List<MaidInfo> list = MaidFilePackets.readMaidInfoList(buf);
            client.execute(() -> {
                IMaidFileNetwork.ClientHandler h = IMaidFileNetwork.ClientHandlerHolder.get();
                if (h != null) {
                    h.onMaidListReceived(list);
                }
            });
            } catch (Throwable t) {
                Constants.LOG.error("[maid_file_manager] S2C 包处理失败，已隔离（不影响网络线程）: {}", t.toString(), t);
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(MaidFilePackets.ID_EXPORT_RESULT, (client, handler, buf, responseSender) -> {
            try {
            MaidFileData data = MaidFilePackets.readMaidFileData(buf);
            client.execute(() -> {
                IMaidFileNetwork.ClientHandler h = IMaidFileNetwork.ClientHandlerHolder.get();
                if (h != null) {
                    h.onExportResultReceived(data == null
                            ? java.util.Collections.emptyList()
                            : java.util.Collections.singletonList(data));
                }
            });
            } catch (Throwable t) {
                Constants.LOG.error("[maid_file_manager] S2C 包处理失败，已隔离（不影响网络线程）: {}", t.toString(), t);
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(MaidFilePackets.ID_FEEDBACK, (client, handler, buf, responseSender) -> {
            try {
            Component message = buf.readComponent();
            client.execute(() -> {
                IMaidFileNetwork.ClientHandler h = IMaidFileNetwork.ClientHandlerHolder.get();
                if (h != null) {
                    h.onFeedbackReceived(message);
                }
            });
            } catch (Throwable t) {
                Constants.LOG.error("[maid_file_manager] S2C 包处理失败，已隔离（不影响网络线程）: {}", t.toString(), t);
            }
        });

        // 批量导入 + 删除源文件：汇总文案 + 逐项 spawned 标志，客户端只删除成功导入的本地文件
        ClientPlayNetworking.registerGlobalReceiver(MaidFilePackets.ID_IMPORT_BATCH_RESULT, (client, handler, buf, responseSender) -> {
            try {
            Component summary = buf.readComponent();
            List<Boolean> spawned = MaidFilePackets.readBooleanList(buf);
            client.execute(() -> {
                IMaidFileNetwork.ClientHandler h = IMaidFileNetwork.ClientHandlerHolder.get();
                if (h != null) {
                    h.onImportBatchResultReceived(summary, spawned);
                }
            });
            } catch (Throwable t) {
                Constants.LOG.error("[maid_file_manager] S2C 包处理失败，已隔离（不影响网络线程）: {}", t.toString(), t);
            }
        });

        // 批量导出结果：服务端序列化数据回传，由客户端写 maid_exports/<玩家名>/ 目录。
        // 条目上限必须与服务端导出请求侧 MAX_EXPORT_IDS(512) 对齐，不能沿用导入通道的 64，
        // 否则 65~512 个合法结果会在此解码抛异常把玩家踢下线
        // 分片下载：累积后交回同一个 UI 回调，界面无需感知分片
        ClientPlayNetworking.registerGlobalReceiver(MaidFilePackets.ID_CHUNKED_DOWNLOAD, (client, handler, buf, responseSender) -> {
            try {
            String kind = buf.readUtf(MaidFilePackets.MAX_TEXT_LEN);
            int sessionId = buf.readVarInt();
            int total = buf.readVarInt();
            int index = buf.readVarInt();
            byte[] chunk = buf.readByteArray(io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.CHUNK_BYTES);
            client.execute(() -> {
                var localPlayer = net.minecraft.client.Minecraft.getInstance().player;
                if (localPlayer == null) {
                    return;
                }
                // 新会话开始时清掉本玩家残留的未完成分片会话：客户端从不主动 clear，
                // 被中断的下载会占满 MAX_SESSIONS_PER_PLAYER(4)，之后 60s 内新导出首片即被拒且无提示（第六轮 N7）。
                if (index == 0) {
                    io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.clear(localPlayer.getUUID());
                }
                byte[] full = io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.accept(
                        localPlayer.getUUID(), kind, sessionId, total, index, chunk);
                if (full == null
                        || !io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.KIND_EXPORT_BATCH.equals(kind)) {
                    return;
                }
                IMaidFileNetwork.ClientHandler h = IMaidFileNetwork.ClientHandlerHolder.get();
                if (h == null) {
                    // 管理器界面已关闭时结果无处投递：原先静默 return，该批导出彻底消失且无痕迹。
                    // 至少留一条 WARN，便于从日志判断「导出为什么没反应」（第六轮 M10）。
                    Constants.LOG.warn("[maid_file_manager] 导出结果被丢弃（管理器界面已关闭）: bytes={}", full.length);
                    return;
                }
                net.minecraft.network.FriendlyByteBuf fullBuf = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(full));
                java.util.List<MaidFileData> decoded = new java.util.ArrayList<>();
                for (byte[] blob : MaidFilePackets.readMaidDataBlobs(fullBuf, MaidFilePackets.MAX_EXPORT_IDS)) {
                    MaidFileData one = MaidFilePackets.deserializeMaidFileData(blob);
                    if (one != null) {
                        decoded.add(one);
                    }
                }
                h.onExportResultReceived(decoded);
            });
            } catch (Throwable t) {
                Constants.LOG.error("[maid_file_manager] S2C 包处理失败，已隔离（不影响网络线程）: {}", t.toString(), t);
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(MaidFilePackets.ID_EXPORT_BATCH_RESULT, (client, handler, buf, responseSender) -> {
            try {
            // 服务端用 writeMaidDataBlobs 编码（VarInt 条目数 + 每项 VarInt 长度 + 纯 GZIP 字节），
            // 必须用 readMaidDataBlobs + deserializeMaidFileData 配对读取。
            // 原先误用 readMaidFileDataList 会先读 VarInt 当长度、吃掉 GZIP 魔数 0x1F，报 Not in GZIP format。
            List<MaidFileData> dataList = new java.util.ArrayList<>();
            for (byte[] blob : MaidFilePackets.readMaidDataBlobs(buf, MaidFilePackets.MAX_EXPORT_IDS)) {
                MaidFileData one = MaidFilePackets.deserializeMaidFileData(blob);
                if (one != null) {
                    dataList.add(one);
                }
            }
            client.execute(() -> {
                IMaidFileNetwork.ClientHandler h = IMaidFileNetwork.ClientHandlerHolder.get();
                if (h != null) {
                    h.onExportResultReceived(dataList);
                }
            });
            } catch (Throwable t) {
                Constants.LOG.error("[maid_file_manager] S2C 包处理失败，已隔离（不影响网络线程）: {}", t.toString(), t);
            }
        });

        // OP 统一导出：收到服务端收集的所有在线玩家女仆分组列表
        ClientPlayNetworking.registerGlobalReceiver(MaidFilePackets.ID_SERVER_EXPORT_LIST, (client, handler, buf, responseSender) -> {
            try {
            List<IMaidFileNetwork.PlayerMaidGroup> groups = MaidFilePackets.readPlayerMaidGroups(buf);
            client.execute(() -> {
                IMaidFileNetwork.ClientHandler h = IMaidFileNetwork.ClientHandlerHolder.get();
                if (h != null) {
                    h.onServerExportListReceived(groups);
                }
            });
            } catch (Throwable t) {
                Constants.LOG.error("[maid_file_manager] S2C 包处理失败，已隔离（不影响网络线程）: {}", t.toString(), t);
            }
        });

        // 服务端配置同步：不依赖 Screen，收到即更新客户端缓存并回发同意状态
        ClientPlayNetworking.registerGlobalReceiver(MaidFilePackets.ID_SERVER_CONFIG_SYNC, (client, handler, buf, responseSender) -> {
            try {
            boolean allowImport = buf.readBoolean();
            boolean allowBaubles = buf.readBoolean();
            boolean allowAdvancements = buf.readBoolean();
            boolean allowEffects = buf.readBoolean();
            boolean allowInvulnerable = buf.readBoolean();
            boolean carryCurios = buf.readBoolean();
            boolean baubleStripAttributes = buf.readBoolean();
            List<String> baubleBlockedList = MaidFilePackets.readStringList(buf);
            List<String> baubleBlacklist = MaidFilePackets.readStringList(buf);
            List<String> baubleWhitelist = MaidFilePackets.readStringList(buf);
            boolean baubleManaged = buf.readBoolean();
            List<String> baubleBlockedNamespaces = MaidFilePackets.readStringList(buf);
            List<String> baubleBlockedCuriosSlots = MaidFilePackets.readStringList(buf);
            List<String> baubleImportBlockedNamespaces = MaidFilePackets.readStringList(buf);
            List<String> baubleImportBlockedCuriosSlots = MaidFilePackets.readStringList(buf);
            boolean baubleWhitelistEnforced = buf.readBoolean();
            client.execute(() -> MaidConfigManager.handleServerConfigSync(
                    allowImport, allowBaubles, allowAdvancements, allowEffects, allowInvulnerable, carryCurios,
                    baubleStripAttributes, baubleBlockedList, baubleBlacklist, baubleWhitelist, baubleManaged,
                    baubleBlockedNamespaces, baubleBlockedCuriosSlots,
                    baubleImportBlockedNamespaces, baubleImportBlockedCuriosSlots, baubleWhitelistEnforced));
            } catch (Throwable t) {
                Constants.LOG.error("[maid_file_manager] S2C 包处理失败，已隔离（不影响网络线程）: {}", t.toString(), t);
            }
        });

        // 女仆档案视图：服务端校验归属后返回，交由当前活跃界面展示
        ClientPlayNetworking.registerGlobalReceiver(MaidFilePackets.ID_MAID_PROFILE, (client, handler, buf, responseSender) -> {
            try {
            MaidProfileView view = MaidFilePackets.readMaidProfileView(buf);
            client.execute(() -> {
                IMaidFileNetwork.ClientHandler h = IMaidFileNetwork.ClientHandlerHolder.get();
                if (h != null) {
                    h.onMaidProfileReceived(view);
                }
            });
            } catch (Throwable t) {
                Constants.LOG.error("[maid_file_manager] S2C 包处理失败，已隔离（不影响网络线程）: {}", t.toString(), t);
            }
        });

        // 备份管理：服务端回传的备份列表（已按权限过滤），交由当前活跃界面展示
        ClientPlayNetworking.registerGlobalReceiver(MaidFilePackets.ID_BACKUP_LIST, (client, handler, buf, responseSender) -> {
            try {
            List<IMaidFileNetwork.BackupOwner> owners = MaidFilePackets.readBackupOwners(buf);
            client.execute(() -> {
                IMaidFileNetwork.ClientHandler h = IMaidFileNetwork.ClientHandlerHolder.get();
                if (h != null) {
                    h.onBackupListReceived(owners);
                }
            });
            } catch (Throwable t) {
                Constants.LOG.error("[maid_file_manager] S2C 包处理失败，已隔离（不影响网络线程）: {}", t.toString(), t);
            }
        });

        // 备份管理：服务端回传的单条备份数据，客户端据此写入本地 maid_exports/
        ClientPlayNetworking.registerGlobalReceiver(MaidFilePackets.ID_BACKUP_EXPORT_RESULT, (client, handler, buf, responseSender) -> {
            try {
            int len = MaidFilePackets.checkSize(buf.readVarInt(),
                    MaidFilePackets.MAX_SINGLE_FILE_BYTES, "backup_export_result");
            byte[] blob = new byte[len];
            buf.readBytes(blob);
            MaidFileData data = MaidFilePackets.deserializeMaidFileData(blob);
            client.execute(() -> {
                IMaidFileNetwork.ClientHandler h = IMaidFileNetwork.ClientHandlerHolder.get();
                if (h != null) {
                    h.onBackupExportReceived(data);
                }
            });
            } catch (Throwable t) {
                Constants.LOG.error("[maid_file_manager] S2C 包处理失败，已隔离（不影响网络线程）: {}", t.toString(), t);
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.player == null || mc.screen != null) {
                return;
            }
            while (MaidFileKeyMappings.OPEN_MANAGER.consumeClick()) {
                mc.setScreen(new MaidFileManagerScreen());
            }
        });

        Constants.LOG.info("Maid File Manager client initialized");
    }

    /**
     * 在游戏主菜单（标题界面）注入「女仆档案管理」入口小按钮。
     *
     * <p>该入口只放在原版主菜单，与游戏内管理界面分离：用于浏览各存档中车万女仆的
     * 自动备份并导出为符合本模组规范的 .maid 文件，因此不依赖进入世界。
     */
    private static void registerTitleScreenEntry() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof TitleScreen)) {
                return;
            }
            Button button = Button.builder(
                            Component.translatable("maid_file_manager.gui.button.profile_browser"),
                            b -> client.setScreen(new MaidBackupBrowserScreen(client.screen)))
                    .bounds(6, 6, 56, 20)
                    .tooltip(Tooltip.create(
                            Component.translatable("maid_file_manager.gui.button.profile_browser.tooltip")))
                    .build();
            Screens.getButtons(screen).add(button);
        });
    }

    /**
     * 供 /maidfile gui 命令通过反射调用（{@code MaidServerCommands.openClientGui}）。
     * common 层不能静态引用客户端类，因此命令侧用类名反射；方法名必须保持 {@code openGui}。
     */
    public static void openGui() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        mc.setScreen(new io.github.zgxhzhr.maidfm.client.MaidFileManagerScreen());
    }
}
