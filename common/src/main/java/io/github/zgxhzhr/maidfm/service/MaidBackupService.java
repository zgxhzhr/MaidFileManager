package io.github.zgxhzhr.maidfm.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zgxhzhr.maidfm.Constants;
import io.github.zgxhzhr.maidfm.data.CuriosBaubleFilter;
import io.github.zgxhzhr.maidfm.data.MaidFileData;
import io.github.zgxhzhr.maidfm.data.MaidFileIo;
import io.github.zgxhzhr.maidfm.network.IMaidFileNetwork;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 车万女仆（TLM）自动备份的浏览与导出服务。
 *
 * <p>备份目录位于存档根下的 {@code data/maid_backups/<主人UUID>/<女仆UUID>/*.dat}，
 * 因此只能在持有存档的一端读取。本服务同时服务三种场景：
 * <ul>
 *   <li>本机客户端（单人世界 / 局域网主机 / 游戏主菜单）：直接读取游戏目录下
 *       {@code saves/<存档>/data/maid_backups}，游戏主菜单场景下合并展示全部存档的备份；</li>
 *   <li>专业服务端：由服务端读取自身存档并回传客户端。</li>
 * </ul>
 *
 * <p>权限模型：服务端场景下，OP（权限等级 2）可见全部主人的备份；非 OP 仅可见/导出
 * 主人 UUID 与请求者一致的那部分。所有目录名与文件名都经过合法性校验，杜绝路径穿越。
 */
public final class MaidBackupService {

    private MaidBackupService() {
    }

    /**
     * 定位某个存档根下的 TLM 备份目录。
     *
     * <p><b>26.1 起 TLM 改了存放位置</b>：备份放在维度目录下，即
     * {@code <worldRoot>/dimensions/minecraft/overworld/data/maid_backups}；
     * 1.20.1 / 1.21.1 则在存档根的 {@code data/maid_backups}。
     * 这里优先探测维度路径，不存在再回退旧路径，两种布局都能读。
     */
    private static Path backupsDir(Path worldRoot) {
        // ① worldRoot 自身可能就是维度目录（26.1 的 LevelResource.ROOT 语义可能已变）
        Path direct = worldRoot.resolve("data").resolve(Constants.MAID_BACKUPS_DIR);
        if (Files.isDirectory(direct)) {
            return direct;
        }
        // ② 26.1：备份在 <存档根>/dimensions/minecraft/overworld/data/maid_backups
        Path dimensionPath = worldRoot.resolve("dimensions")
                .resolve("minecraft")
                .resolve("overworld")
                .resolve("data")
                .resolve(Constants.MAID_BACKUPS_DIR);
        if (Files.isDirectory(dimensionPath)) {
            return dimensionPath;
        }
        // ③ 1.20.1 / 1.21.1：<存档根>/data/maid_backups
        return direct;
    }

    /** 定位服务端当前存档的 TLM 备份根目录 */
    public static Path backupRoot(MinecraftServer server) {
        return backupsDir(server.getWorldPath(LevelResource.ROOT));
    }

    /**
     * 服务端场景：扫描当前存档的备份并按权限过滤。
     *
     * @param requester 请求者 UUID
     * @param op        true=可见全部主人；false=仅可见 requester 名下的备份
     */
    public static List<IMaidFileNetwork.BackupOwner> scan(MinecraftServer server, UUID requester, boolean op) {
        List<IMaidFileNetwork.BackupOwner> all = scanRoot(backupRoot(server));
        if (op) {
            return all;
        }
        List<IMaidFileNetwork.BackupOwner> filtered = new ArrayList<>();
        for (IMaidFileNetwork.BackupOwner owner : all) {
            if (requester != null && owner.ownerUuid().equalsIgnoreCase(requester.toString())) {
                filtered.add(owner);
            }
        }
        return filtered;
    }

    /**
     * 客户端本机场景：扫描游戏目录下全部存档的备份，按存档分组返回。
     *
     * <p>用于单人世界、局域网主机与游戏主菜单（主菜单无「当前存档」上下文，
     * 故按存档逐层展示本机所有存档的备份；没有备份的存档不进入列表）。
     */
    public static List<IMaidFileNetwork.BackupWorld> scanAllWorlds(Path gameDir) {
        List<IMaidFileNetwork.BackupWorld> worlds = new ArrayList<>();
        Path saves = gameDir.resolve("saves");
        File[] worldDirs = Files.isDirectory(saves) ? saves.toFile().listFiles(File::isDirectory) : null;
        if (worldDirs == null) {
            return worlds;
        }
        for (File world : worldDirs) {
            List<IMaidFileNetwork.BackupOwner> owners = scanRoot(backupsDir(world.toPath()));
            if (owners.isEmpty()) {
                continue;
            }
            worlds.add(new IMaidFileNetwork.BackupWorld(world.getName(), owners));
        }
        worlds.sort(Comparator.comparing(IMaidFileNetwork.BackupWorld::worldName, String.CASE_INSENSITIVE_ORDER));
        return worlds;
    }

    /** 扫描指定备份根目录：返回「主人 → 女仆 → 备份文件」三层结构（不涉及权限） */
    /** 备份扫描上限：主线程同步扫描，设上限避免目录极多时长时间卡死 */
    private static final int MAX_SCAN_OWNER_DIRS = 200;
    private static final int MAX_SCAN_MAID_DIRS_PER_OWNER = 200;
    /**
     * 单次扫描的时间预算（毫秒）。
     *
     * <p>两个个数上限只能限制「目录条数」，挡不住单个目录里文件极多 / 磁盘极慢的情况：
     * 200×200 个目录逐个 listFiles + 读 index.dat，在机械盘上仍可能占住主线程十几秒。
     * 这里按墙钟时间兜底，超时即停并返回已扫到的部分（宁可少列，不可卡服）。
     */
    private static final long SCAN_TIME_BUDGET_MS = 1500L;

    private static List<IMaidFileNetwork.BackupOwner> scanRoot(Path root) {
        ensureUsernameCacheLoaded();
        final long scanDeadline = System.currentTimeMillis() + SCAN_TIME_BUDGET_MS;
        List<IMaidFileNetwork.BackupOwner> owners = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) {
            return owners;
        }
        File[] ownerDirs = root.toFile().listFiles(File::isDirectory);
        if (ownerDirs == null) {
            return owners;
        }
        for (File ownerDir : ownerDirs) {
            // 时间预算兜底：超过就停止扫描，返回已扫到的部分
            if (System.currentTimeMillis() > scanDeadline) {
                Constants.LOG.warn("[maid_file_manager] 备份扫描超过时间预算 {} ms，已提前结束（已扫 {} 个玩家目录）",
                        SCAN_TIME_BUDGET_MS, owners.size());
                break;
            }
            String ownerUuid = ownerDir.getName();
            if (!isUuid(ownerUuid)) {
                continue;
            }
            if (owners.size() >= MAX_SCAN_OWNER_DIRS) {
                Constants.LOG.warn("[maid_file_manager] 备份扫描达到玩家目录上限 {}，其余目录已跳过（避免长时间占用主线程）",
                        MAX_SCAN_OWNER_DIRS);
                break;
            }
            Map<String, String> nameByMaid = readIndexNames(ownerDir.toPath().resolve("index.dat"));
            List<IMaidFileNetwork.BackupMaid> maids = new ArrayList<>();
            File[] maidDirs = ownerDir.listFiles(File::isDirectory);
            // 同一玩家下女仆目录也设上限：主线程同步扫描，目录极多时会明显卡顿
            if (maidDirs != null && maidDirs.length > MAX_SCAN_MAID_DIRS_PER_OWNER) {
                Constants.LOG.warn("[maid_file_manager] 玩家 {} 下有 {} 个备份女仆目录，仅扫描前 {} 个",
                        ownerDir.getName(), maidDirs.length, MAX_SCAN_MAID_DIRS_PER_OWNER);
                maidDirs = java.util.Arrays.copyOf(maidDirs, MAX_SCAN_MAID_DIRS_PER_OWNER);
            }
            if (maidDirs != null) {
                for (File maidDir : maidDirs) {
                    String maidUuid = maidDir.getName();
                    File[] files = maidDir.listFiles((dir, name) -> name.endsWith(".dat"));
                    if (files == null || files.length == 0) {
                        continue;
                    }
                    List<String> names = new ArrayList<>();
                    for (File f : files) {
                        names.add(f.getName());
                    }
                    // 同一女仆的多个备份按文件名倒序：新备份（时间戳更大）排在最前
                    names.sort(Comparator.reverseOrder());
                    // 名字：index.dat 的 Name 通常只是 TLM 的默认名（「小女仆」），单看它无法区分
                    // 是哪只女仆。这里额外读该女仆最新一份备份的 model_id 拿模型名，
                    // 自定义名存在时显示「自定义名 (模型名)」，不存在时直接用模型名。
                    String customName = nameByMaid.get(maidUuid);
                    String modelName = "";
                    try {
                        java.io.File latest = new java.io.File(maidDir, names.get(0));
                        CompoundTag latestTag = MaidFileIo.readCompressedNbt(latest.toPath());
                        if (latestTag != null) {
                            String modelId = readModelIdTag(latestTag);
                            if (modelId.isEmpty()) {
                                modelId = latestTag.getStringOr("ModelId", "");
                            }
                            if (!modelId.isEmpty()) {
                                modelName = MaidTransferService.getDisplayName(modelId);
                            }
                        }
                    } catch (Throwable t) {
                        // 单个备份读不出模型名不该让整个列表失败
                        Constants.LOG.debug("[maid_file_manager] 备份模型名解析失败: {}", t.toString());
                    }
                    String shownName;
                    if (customName == null || customName.isEmpty()) {
                        shownName = modelName;
                    } else if (modelName.isEmpty() || modelName.equals(customName)) {
                        shownName = customName;
                    } else {
                        shownName = customName + " (" + modelName + ")";
                    }
                    maids.add(new IMaidFileNetwork.BackupMaid(maidUuid, shownName, names));
                }
            }
            maids.sort(Comparator.comparing(IMaidFileNetwork.BackupMaid::maidUuid));
            owners.add(new IMaidFileNetwork.BackupOwner(ownerUuid, resolveOwnerName(ownerUuid), maids));
        }
        owners.sort(Comparator.comparing(IMaidFileNetwork.BackupOwner::ownerUuid));
        return owners;
    }

    /**
     * 服务端场景：读取指定备份并组装为本模组规范的 .maid 数据。
     *
     * <p>权限校验：OP 或该备份主人本人；目录名与文件名均需合法。任一校验不通过或读取失败返回 {@code null}。
     * 本方法只读不写：服务端不会因为导出备份而修改或删除任何数据。
     */
    public static MaidFileData readForExport(MinecraftServer server, UUID requester, boolean op,
                                             String ownerUuid, String maidUuid, String fileName) {
        if (server == null) {
            return null;
        }
        // 权限闸门：非 OP 只能导出主人 UUID 与请求者一致的备份
        if (!op && (requester == null || ownerUuid == null
                || !ownerUuid.equalsIgnoreCase(requester.toString()))) {
            return null;
        }
        return readFrom(backupRoot(server), ownerUuid, maidUuid, fileName);
    }

    /**
     * 客户端本机场景：在游戏目录的全部存档中查找并读取指定备份。
     *
     * <p>女仆 UUID 全局唯一，按「主人/女仆/文件名」即可唯一定位；同源备份若在多个存档中
     * 同时存在，取第一个成功读取者。
     */
    public static MaidFileData readLocal(Path gameDir, String ownerUuid, String maidUuid, String fileName) {
        if (gameDir == null || !isValidBackupPath(ownerUuid, maidUuid, fileName)) {
            return null;
        }
        Path saves = gameDir.resolve("saves");
        File[] worlds = Files.isDirectory(saves) ? saves.toFile().listFiles(File::isDirectory) : null;
        if (worlds == null) {
            return null;
        }
        for (File world : worlds) {
            MaidFileData data = readFrom(backupsDir(world.toPath()), ownerUuid, maidUuid, fileName);
            if (data != null) {
                return data;
            }
        }
        return null;
    }

    /** 从指定备份根读取单个 .dat 并组装为 .maid 数据；路径非法或读取失败返回 null */
    private static MaidFileData readFrom(Path root, String ownerUuid, String maidUuid, String fileName) {
        if (root == null || !isValidBackupPath(ownerUuid, maidUuid, fileName)) {
            return null;
        }
        Path file = root.resolve(ownerUuid).resolve(maidUuid).resolve(fileName);
        CompoundTag tag = MaidFileIo.readCompressedNbt(file);
        if (tag == null || tag.isEmpty()) {
            return null;
        }
        return buildFileData(tag, ownerUuid, maidUuid);
    }

    /**
     * 备份相对路径合法性：目录名必须是 UUID、文件名必须是纯 .dat 名（拒绝路径穿越）。
     *
     * <p>除了 {@code / \ ..}，还必须拒绝**冒号**与 NUL：Windows 上 `C:x.dat` 这类「盘符相对路径」
     * 不含斜杠也不含 `..`，但 {@code root.resolve("C:x.dat")} 在 root 不在 C 盘时会被当成
     * **绝对路径**解析，直接逃出备份目录。NUL 截断同理。
     */
    private static boolean isValidBackupPath(String ownerUuid, String maidUuid, String fileName) {
        if (ownerUuid == null || maidUuid == null || fileName == null
                || !isUuid(ownerUuid) || !isUuid(maidUuid)
                || !fileName.endsWith(".dat")) {
            return false;
        }
        for (int i = 0; i < fileName.length(); i++) {
            char c = fileName.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '\0' || Character.isISOControl(c)) {
                return false;
            }
        }
        // ".." 单独拦一次：上面已排除分隔符，但 "..dat" 之类仍需兜底
        return !fileName.contains("..");
    }

    /** 由备份实体 NBT 组装 .maid 文件数据（备份内容与 .maid 的 data 字段同构） */
    private static MaidFileData buildFileData(CompoundTag tag, String ownerUuid, String maidUuid) {
        MaidFileData data = new MaidFileData();
        // Curios 饰品：备份导出的 .maid 同样必须裁剪——备份里存的是实体 NBT，天然带着 Curios 载荷。
        // 用副本处理，避免污染服务端持有的备份数据本身。
        // 备份导出同样不消费 Curios 批次统计（只有 sendExportResults 会取走），
        // 先在入口清一次，避免这批统计串到下一个走 sendExportResults 的玩家提示里。
        CuriosBaubleFilter.takeBatch();
        CompoundTag exportTag = tag.copy();
        CuriosBaubleFilter.Result backupCurios = CuriosBaubleFilter.strip(exportTag,
                io.github.zgxhzhr.maidfm.config.MaidConfigManager.isCarryCurios(),
                MaidTransferService::allowCuriosItem, false);
        if (backupCurios.hasDrops()) {
            Constants.LOG.info("[maid_file_manager] 备份导出裁剪 Curios 饰品: 丢弃 {} 件(按命名空间 {}), "
                            + "禁止槽位 {} 个(含 {} 件), 保留 {} 件",
                    backupCurios.droppedItems(), backupCurios.sortedNamespaces(),
                    backupCurios.forbiddenSlots(), backupCurios.forbiddenItems(), backupCurios.keptItems());
        }
        CuriosBaubleFilter.batch().add(backupCurios);
        data.setData(exportTag);
        // TLM 备份 .dat 里的 DataVersion 是 Mojang 版本号（1.20.1=3465、1.21.1=3955），
        // 而 .maid 的内部 data_version 是 NbtVersion 编码（1200100/1210100）。
        // 直接透传会让同版本备份被判成跨版本（饰品属性全新化、YsmModelName 被删、模型回默认），
        // 必须做一次正向映射。缺失 DataVersion 时按当前运行时版本。
        int mojangDataVersion = tag.contains("DataVersion")
                ? tag.getIntOr("DataVersion", 0)
                : SharedConstants.getCurrentVersion().dataVersion().version();
        data.setDataVersion(io.github.zgxhzhr.maidfm.data.NbtVersion.fromMojangDataVersion(mojangDataVersion));
        data.setSourceMcVersion(SharedConstants.getCurrentVersion().name());
        data.setSourceTlmVersion("");
        data.setExportedAt(System.currentTimeMillis());
        data.setSourceMaidUuid(maidUuid);
        data.setOwnerUuid(ownerUuid);
        data.setTamed(true);

        String modelId = readModelIdTag(tag);
        if (modelId.isEmpty()) {
            modelId = tag.getStringOr("ModelId", "");
        }
        data.setModelId(modelId);
        // YSM 换模：展示名取实体 NBT 中的 YSM 模型名（键 YsmModelName / YsmModelId），
        // modelId 字段仍保持底层 TLM 模型 ID，供导入回退与跨版本迁移使用
        String displayName = null;
        if (tag.getBooleanOr("IsYsmModel", false)) {
            displayName = extractText(tag.getStringOr("YsmModelName", ""));
            if (displayName == null || displayName.isEmpty()) {
                String ysmId = tag.getStringOr("YsmModelId", "");
                if (!ysmId.isEmpty()) {
                    displayName = ysmId;
                }
            }
        }
        if (displayName == null || displayName.isEmpty()) {
            displayName = MaidTransferService.getDisplayName(modelId);
        }
        data.setDisplayName(displayName);
        String customName = extractText(tag.getStringOr("CustomName", ""));
        if (customName != null && !customName.isEmpty()) {
            data.setCustomName(customName);
        }
        return data;
    }

    /**
     * 从备份实体 NBT 里取模型 ID。
     *
     * <p><b>26.1</b>：TLM 把 {@code ProfileData} 放进 NeoForge attachment，
     * 结构是 {@code attachments → touhou_little_maid:profile → model_id}
     * （外层 key 名由 NeoForge 决定，这里逐层递归查找，不写死）。
     * <b>1.20.1 / 1.21.1</b>：实体根 tag 的 {@code ModelId}（或已迁移的 {@code model_id}）。
     */
    private static String readModelIdTag(CompoundTag tag) {
        if (tag == null) {
            return "";
        }
        // ① 26.1 attachment 结构（最多下钻两层，避开写死外层 key）
        for (String key : tag.keySet()) {
            CompoundTag sub = tag.getCompoundOrEmpty(key);
            if (sub.isEmpty()) {
                continue;
            }
            if (sub.contains("model_id")) {
                String id = sub.getStringOr("model_id", "");
                if (!id.isEmpty()) {
                    return id;
                }
            }
            for (String key2 : sub.keySet()) {
                CompoundTag sub2 = sub.getCompoundOrEmpty(key2);
                if (!sub2.isEmpty() && sub2.contains("model_id")) {
                    String id = sub2.getStringOr("model_id", "");
                    if (!id.isEmpty()) {
                        return id;
                    }
                }
            }
        }
        // ② 旧格式
        if (tag.contains("model_id")) {
            return tag.getStringOr("model_id", "");
        }
        return tag.getStringOr("ModelId", "");
    }
    /** 读取 index.dat，返回 女仆UUID → 名字 映射（失败返回空表） */
    private static Map<String, String> readIndexNames(Path indexFile) {
        Map<String, String> map = new HashMap<>();
        if (!Files.isRegularFile(indexFile)) {
            return map;
        }
        CompoundTag index = MaidFileIo.readCompressedNbt(indexFile);
        if (index == null) {
            return map;
        }
        for (String maidUuid : index.keySet()) {
            CompoundTag entry = index.getCompoundOrEmpty(maidUuid);
            String text = readNameTag(entry);
            if (text != null && !text.isEmpty()) {
                map.put(maidUuid, text);
            }
        }
        return map;
    }

    /**
     * 读取 index.dat 里某个女仆条目的名字。
     *
     * <p>TLM 26.1 用 {@code ComponentSerialization.CODEC} 编码 {@code Name}（NBT 里可能是 Compound 或 String），
     * 而 1.20.1 / 1.21.1 存的是普通 String（组件 JSON 文本）。两种格式都兼容：先按 Component Codec 解析，
     * 失败再退回旧格式的 JSON 文本解析。
     */
    private static String readNameTag(CompoundTag entry) {
        if (entry == null || !entry.contains("Name")) {
            return null;
        }
        net.minecraft.nbt.Tag nameTag = entry.get("Name");
        if (nameTag == null) {
            return null;
        }
        try {
            var parsed = net.minecraft.network.chat.ComponentSerialization.CODEC
                    .parse(net.minecraft.nbt.NbtOps.INSTANCE, nameTag);
            if (parsed.result().isPresent()) {
                String s = parsed.result().get().getString();
                if (s != null && !s.isEmpty()) {
                    return s;
                }
            }
        } catch (Throwable ignored) {
            // 落回旧格式
        }
        if (nameTag instanceof net.minecraft.nbt.StringTag st) {
            return extractText(st.value());
        }
        try {
            return extractText(nameTag.toString());
        } catch (Throwable ignored) {
            return null;
        }
    }
    /** 从组件 JSON 中提取纯文本（兼容 {"text":..} / {"extra":[..]} / 纯字符串 / 数组） */
    private static String extractText(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return extractText(JsonParser.parseString(json));
        } catch (Throwable t) {
            return null;
        }
    }

    private static String extractText(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return null;
        }
        if (el.isJsonPrimitive()) {
            return el.getAsString();
        }
        if (el.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement e : el.getAsJsonArray()) {
                String s = extractText(e);
                if (s != null) {
                    sb.append(s);
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        }
        JsonObject o = el.getAsJsonObject();
        if (o.has("text")) {
            return o.get("text").getAsString();
        }
        if (o.has("extra")) {
            return extractText(o.get("extra"));
        }
        if (o.has("translate")) {
            return o.get("translate").getAsString();
        }
        return null;
    }

    private static boolean isUuid(String s) {
        try {
            UUID.fromString(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 从游戏目录的 {@code usernamecache.json}（Minecraft 本体维护的「UUID → 最后已知用户名」）
     * 反查玩家名。备份目录本身只以 UUID 命名，不读这张表就只能显示 UUID 前缀。
     *
     * <p>服务端与单人主机都有这份文件；客户端侧另有一套 PlayerNameResolver 兜底。
     */
    private static String resolveOwnerName(String uuid) {
        try {
            if (uuid == null || uuid.isEmpty()) {
                return null;
            }
            String cached = USERNAME_CACHE.get(uuid.toLowerCase(java.util.Locale.ROOT));
            return cached != null && !cached.isEmpty() ? cached : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** usernamecache.json 的懒加载缓存（UUID 小写 → 玩家名） */
    private static final java.util.Map<String, String> USERNAME_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile boolean usernameCacheLoaded;

    /** 载入 usernamecache.json（失败静默：备份列表退回显示 UUID 前缀，不影响其它功能） */
    private static void ensureUsernameCacheLoaded() {
        if (usernameCacheLoaded) {
            return;
        }
        usernameCacheLoaded = true;
        try {
            java.nio.file.Path file = io.github.zgxhzhr.maidfm.platform.Services.PLATFORM.get()
                    .getGameDir().resolve(io.github.zgxhzhr.maidfm.Constants.USERNAME_CACHE_FILE);
            if (!java.nio.file.Files.isRegularFile(file)) {
                Constants.LOG.info("[maid_file_manager] 未找到 {}，备份列表将显示 UUID 前缀", file);
                return;
            }
            String json = java.nio.file.Files.readString(file);
            com.google.gson.JsonElement root = com.google.gson.JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                return;
            }
            for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : root.getAsJsonObject().entrySet()) {
                if (e.getValue() != null && e.getValue().isJsonPrimitive()) {
                    USERNAME_CACHE.put(e.getKey().toLowerCase(java.util.Locale.ROOT), e.getValue().getAsString());
                }
            }
            Constants.LOG.info("[maid_file_manager] 已从 {} 载入 {} 条玩家名用于备份列表",
                    io.github.zgxhzhr.maidfm.Constants.USERNAME_CACHE_FILE, USERNAME_CACHE.size());
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 读取 usernamecache.json 失败: {}", t.toString());
        }
    }
}
