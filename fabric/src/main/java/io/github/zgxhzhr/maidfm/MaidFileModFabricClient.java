package io.github.zgxhzhr.maidfm;

import io.github.zgxhzhr.maidfm.client.MaidBackupBrowserScreen;
import io.github.zgxhzhr.maidfm.client.MaidFileManagerScreen;
import io.github.zgxhzhr.maidfm.client.MaidFileKeyMappings;
import io.github.zgxhzhr.maidfm.client.PlayerNameResolver;
import io.github.zgxhzhr.maidfm.config.MaidConfigManager;
import io.github.zgxhzhr.maidfm.data.MaidFileData;
import io.github.zgxhzhr.maidfm.data.MaidInfo;
import io.github.zgxhzhr.maidfm.data.MaidProfileView;
import io.github.zgxhzhr.maidfm.network.FabricNetwork;
import io.github.zgxhzhr.maidfm.network.IMaidFileNetwork;
import io.github.zgxhzhr.maidfm.network.MaidFilePackets;
import io.github.zgxhzhr.maidfm.network.MaidProfileCodec;
import io.github.zgxhzhr.maidfm.network.MaidBackupCodec;
import io.github.zgxhzhr.maidfm.network.MaidInfoCodec;
import io.github.zgxhzhr.maidfm.network.MaidPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;

import java.util.List;

public class MaidFileModFabricClient implements ClientModInitializer {

    /** 本连接是否已上报模型名映射 */
    private static boolean MODEL_NAMES_SENT;
    /** 把本机认识的「模型 ID → 显示名」上报给服务端（每个连接只发一次） */
    private static void sendModelNamesOnce() {
        if (MODEL_NAMES_SENT) {
            return;
        }
        MODEL_NAMES_SENT = true;
        try {
            java.util.Map<String, String> names =
                    io.github.zgxhzhr.maidfm.client.ModelNames.exportModelNameMap();
            if (names == null || names.isEmpty()) {
                return;
            }
            java.util.List<String> pairs = new java.util.ArrayList<>(names.size() * 2);
            for (java.util.Map.Entry<String, String> e : names.entrySet()) {
                pairs.add(e.getKey());
                pairs.add(e.getValue());
            }
            IMaidFileNetwork net = IMaidFileNetwork.Holder.get();
            if (net != null) {
                net.sendModelNames(pairs);
            }
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 上报模型名失败: {}", t.toString());
        }
    }

    @Override
    public void onInitializeClient() {
        // 进入世界即开始解析属主名字（不等玩家打开备份界面）
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            java.nio.file.Path gameDir = client.gameDirectory.toPath();
            PlayerNameResolver.loadCache(gameDir);
            PlayerNameResolver.warmUpFromSaves(gameDir);
            io.github.zgxhzhr.maidfm.client.ClientModelNameCache.load(gameDir);
            io.github.zgxhzhr.maidfm.service.ModelNameRegistry.externalProvider =
                    io.github.zgxhzhr.maidfm.client.ClientModelNameCache::lookup;
            MODEL_NAMES_SENT = false;
        });
        KeyBindingHelper.registerKeyBinding(MaidFileKeyMappings.OPEN_MANAGER);
        IMaidFileNetwork.Holder.set(new FabricNetwork());

        // 协议版本校验（Fabric 没有 Forge 的 NetworkRegistry predicate，需显式上报）
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            try {
                net.minecraft.network.FriendlyByteBuf buf = MaidPayload.buffer();
                buf.writeUtf(MaidFilePackets.PROTOCOL_VERSION);
                net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                        MaidPayload.of(MaidFilePackets.ID_PROTOCOL_CHECK, buf));
            } catch (Throwable t) {
                io.github.zgxhzhr.maidfm.Constants.LOG.debug("[maid_file_manager] 协议版本上报失败: {}", t.toString());
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(MaidPayload.typeOf(MaidFilePackets.ID_MAID_LIST), (payload, context) -> {
            try {
            List<MaidInfo> list = MaidInfoCodec.readMaidInfoList(payload.body());
            context.client().execute(() -> {
                IMaidFileNetwork.ClientHandler h = IMaidFileNetwork.ClientHandlerHolder.get();
                if (h != null) {
                    h.onMaidListReceived(list);
                }
            });
            } catch (Throwable t) {
                Constants.LOG.error("[maid_file_manager] S2C 包处理失败，已隔离（不影响网络线程）: {}", t.toString(), t);
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(MaidPayload.typeOf(MaidFilePackets.ID_EXPORT_RESULT), (payload, context) -> {
            try {
            MaidFileData data = MaidFilePackets.readMaidFileData(payload.body());
            context.client().execute(() -> {
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

        ClientPlayNetworking.registerGlobalReceiver(MaidPayload.typeOf(MaidFilePackets.ID_FEEDBACK), (payload, context) -> {
            try {
            // 损坏 JSON 时 fromJson 返回 null，归一为空文案，避免下游 NPE
            Component parsed = Component.Serializer.fromJson(payload.body().readUtf(32767), RegistryAccess.EMPTY);
            Component message = parsed != null ? parsed : Component.empty();
            context.client().execute(() -> {
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
        ClientPlayNetworking.registerGlobalReceiver(MaidPayload.typeOf(MaidFilePackets.ID_IMPORT_BATCH_RESULT), (payload, context) -> {
            try {
            FriendlyByteBuf body = payload.body();
            Component parsed = Component.Serializer.fromJson(body.readUtf(32767), RegistryAccess.EMPTY);
            Component summary = parsed != null ? parsed : Component.empty();
            List<Boolean> spawned = MaidFilePackets.readBooleanList(body);
            context.client().execute(() -> {
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
        // 否则 65~512 个合法结果会在 netty 解码线程抛异常把玩家踢下线
        // 分片下载：累积后交回同一个 UI 回调
        ClientPlayNetworking.registerGlobalReceiver(MaidPayload.typeOf(MaidFilePackets.ID_CHUNKED_DOWNLOAD), (payload, context) -> {
            try {
            net.minecraft.network.FriendlyByteBuf body = payload.body();
            String kind = body.readUtf(MaidFilePackets.MAX_TEXT_LEN);
            int sessionId = body.readVarInt();
            int total = body.readVarInt();
            int index = body.readVarInt();
            byte[] chunk = body.readByteArray(io.github.zgxhzhr.maidfm.network.ChunkedUploadAssembler.CHUNK_BYTES);
            context.client().execute(() -> {
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
                net.minecraft.network.FriendlyByteBuf fullBuf =
                        new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(full));
                List<MaidFileData> decoded = new java.util.ArrayList<>();
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
        ClientPlayNetworking.registerGlobalReceiver(MaidPayload.typeOf(MaidFilePackets.ID_EXPORT_BATCH_RESULT), (payload, context) -> {
            try {
            // 服务端用 writeMaidDataBlobs 编码（VarInt 条目数 + 每项 VarInt 长度 + 纯 GZIP），
            // 必须用 readMaidDataBlobs + deserializeMaidFileData 配对读取。
            // 注意 body() 每次调用都新建包装缓冲，必须只取一次并在其上连续读。
            net.minecraft.network.FriendlyByteBuf body = payload.body();
            List<MaidFileData> dataList = new java.util.ArrayList<>();
            for (byte[] blob : MaidFilePackets.readMaidDataBlobs(body, MaidFilePackets.MAX_EXPORT_IDS)) {
                MaidFileData one = MaidFilePackets.deserializeMaidFileData(blob);
                if (one != null) {
                    dataList.add(one);
                }
            }
            context.client().execute(() -> {
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
        ClientPlayNetworking.registerGlobalReceiver(MaidPayload.typeOf(MaidFilePackets.ID_SERVER_EXPORT_LIST), (payload, context) -> {
            try {
            List<IMaidFileNetwork.PlayerMaidGroup> groups = MaidInfoCodec.readPlayerMaidGroups(payload.body());
            context.client().execute(() -> {
                IMaidFileNetwork.ClientHandler h = IMaidFileNetwork.ClientHandlerHolder.get();
                if (h != null) {
                    h.onServerExportListReceived(groups);
                }
            });
            } catch (Throwable t) {
                Constants.LOG.error("[maid_file_manager] S2C 包处理失败，已隔离（不影响网络线程）: {}", t.toString(), t);
            }
        });

        // 服务端配置同步：不依赖 Screen，收到即更新客户端缓存并回发同意状态。
        // 注意：body() 每次调用都新建一个包装缓冲，全部字段必须从同一个 body() 读取，
        // 否则后续 readBoolean 会从新缓冲的第 0 字节读起，导致值错位。
        ClientPlayNetworking.registerGlobalReceiver(MaidPayload.typeOf(MaidFilePackets.ID_SERVER_CONFIG_SYNC), (payload, context) -> {
            try {
            FriendlyByteBuf body = payload.body();
            boolean allowImport = body.readBoolean();
            boolean allowBaubles = body.readBoolean();
            boolean allowAdvancements = body.readBoolean();
            boolean allowEffects = body.readBoolean();
            boolean allowInvulnerable = body.readBoolean();
            boolean carryCurios = body.readBoolean();
            boolean baubleStripAttributes = body.readBoolean();
            List<String> baubleBlockedList = MaidFilePackets.readStringList(body);
            List<String> baubleBlacklist = MaidFilePackets.readStringList(body);
            List<String> baubleWhitelist = MaidFilePackets.readStringList(body);
            boolean baubleManaged = body.readBoolean();
            List<String> baubleBlockedNamespaces = MaidFilePackets.readStringList(body);
            List<String> baubleBlockedCuriosSlots = MaidFilePackets.readStringList(body);
            List<String> baubleImportBlockedNamespaces = MaidFilePackets.readStringList(body);
            List<String> baubleImportBlockedCuriosSlots = MaidFilePackets.readStringList(body);
            boolean baubleWhitelistEnforced = body.readBoolean();
            context.client().execute(() -> MaidConfigManager.handleServerConfigSync(
                    allowImport, allowBaubles, allowAdvancements, allowEffects, allowInvulnerable, carryCurios,
                    baubleStripAttributes, baubleBlockedList, baubleBlacklist, baubleWhitelist, baubleManaged,
                    baubleBlockedNamespaces, baubleBlockedCuriosSlots,
                    baubleImportBlockedNamespaces, baubleImportBlockedCuriosSlots, baubleWhitelistEnforced));
            } catch (Throwable t) {
                Constants.LOG.error("[maid_file_manager] S2C 包处理失败，已隔离（不影响网络线程）: {}", t.toString(), t);
            }
        });

        // 女仆档案视图：服务端校验归属后返回，交由当前活跃界面展示
        ClientPlayNetworking.registerGlobalReceiver(MaidPayload.typeOf(MaidFilePackets.ID_MAID_PROFILE), (payload, context) -> {
            try {
            MaidProfileView view = MaidProfileCodec.readMaidProfileView(payload.body());
            context.client().execute(() -> {
                IMaidFileNetwork.ClientHandler h = IMaidFileNetwork.ClientHandlerHolder.get();
                if (h != null) {
                    h.onMaidProfileReceived(view);
                }
            });
            } catch (Throwable t) {
                Constants.LOG.error("[maid_file_manager] S2C 包处理失败，已隔离（不影响网络线程）: {}", t.toString(), t);
            }
        });

        // 备份管理：收到服务端按权限过滤后的备份列表（OP 见全部玩家，非 OP 仅自己）
        ClientPlayNetworking.registerGlobalReceiver(MaidPayload.typeOf(MaidFilePackets.ID_BACKUP_LIST), (payload, context) -> {
            try {
            List<IMaidFileNetwork.BackupOwner> owners = MaidBackupCodec.readBackupOwners(payload.body());
            context.client().execute(() -> {
                IMaidFileNetwork.ClientHandler h = IMaidFileNetwork.ClientHandlerHolder.get();
                if (h != null) {
                    h.onBackupListReceived(owners);
                }
            });
            } catch (Throwable t) {
                Constants.LOG.error("[maid_file_manager] S2C 包处理失败，已隔离（不影响网络线程）: {}", t.toString(), t);
            }
        });

        // 备份管理：收到服务端回传的单条备份数据（线上字节经 checkSize 预检后反序列化为 MaidFileData）
        ClientPlayNetworking.registerGlobalReceiver(MaidPayload.typeOf(MaidFilePackets.ID_BACKUP_EXPORT_RESULT), (payload, context) -> {
            try {
            FriendlyByteBuf body = payload.body();
            int len = MaidFilePackets.checkSize(body.readVarInt(),
                    MaidFilePackets.MAX_SINGLE_FILE_BYTES, "backup_export_result");
            byte[] bytes = new byte[len];
            body.readBytes(bytes);
            MaidFileData data = MaidFilePackets.deserializeMaidFileData(bytes);
            context.client().execute(() -> {
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
            if (!MODEL_NAMES_SENT && mc.player != null) {
                sendModelNamesOnce();
            }
            if (mc.player == null || mc.screen != null) {
                return;
            }
            while (MaidFileKeyMappings.OPEN_MANAGER.consumeClick()) {
                mc.setScreen(new MaidFileManagerScreen());
            }
        });

        // 在原版主菜单（标题界面）左上角注入「女仆档案管理」入口小按钮：与游戏内管理界面分离，
        // 用于浏览各存档中车万女仆的自动备份并导出为符合本模组规范的 .maid 文件，无需进入世界
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

        Constants.LOG.info("Maid File Manager client initialized");
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
