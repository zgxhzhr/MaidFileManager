package io.github.zgxhzhr.maidfm.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zgxhzhr.maidfm.Constants;
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

    /** 定位某个存档根下的 TLM 备份目录 */
    private static Path backupsDir(Path worldRoot) {
        return worldRoot.resolve("data").resolve(Constants.MAID_BACKUPS_DIR);
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
    private static List<IMaidFileNetwork.BackupOwner> scanRoot(Path root) {
        List<IMaidFileNetwork.BackupOwner> owners = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) {
            return owners;
        }
        File[] ownerDirs = root.toFile().listFiles(File::isDirectory);
        if (ownerDirs == null) {
            return owners;
        }
        for (File ownerDir : ownerDirs) {
            String ownerUuid = ownerDir.getName();
            if (!isUuid(ownerUuid)) {
                continue;
            }
            Map<String, String> nameByMaid = readIndexNames(ownerDir.toPath().resolve("index.dat"));
            List<IMaidFileNetwork.BackupMaid> maids = new ArrayList<>();
            File[] maidDirs = ownerDir.listFiles(File::isDirectory);
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
                    maids.add(new IMaidFileNetwork.BackupMaid(maidUuid, nameByMaid.get(maidUuid), names));
                }
            }
            maids.sort(Comparator.comparing(IMaidFileNetwork.BackupMaid::maidUuid));
            owners.add(new IMaidFileNetwork.BackupOwner(ownerUuid, maids));
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

    /** 备份相对路径合法性：目录名必须是 UUID、文件名必须是纯 .dat 名（拒绝路径穿越） */
    private static boolean isValidBackupPath(String ownerUuid, String maidUuid, String fileName) {
        return ownerUuid != null && maidUuid != null && fileName != null
                && isUuid(ownerUuid) && isUuid(maidUuid)
                && fileName.endsWith(".dat")
                && !fileName.contains("/") && !fileName.contains("\\") && !fileName.contains("..");
    }

    /** 由备份实体 NBT 组装 .maid 文件数据（备份内容与 .maid 的 data 字段同构） */
    private static MaidFileData buildFileData(CompoundTag tag, String ownerUuid, String maidUuid) {
        MaidFileData data = new MaidFileData();
        data.setData(tag);
        // 备份 NBT 已由 TLM 写入 DataVersion，缺失时退回当前版本
        int dataVersion = tag.contains("DataVersion")
                ? tag.getInt("DataVersion")
                : SharedConstants.getCurrentVersion().getDataVersion().getVersion();
        data.setDataVersion(dataVersion);
        data.setSourceMcVersion(SharedConstants.getCurrentVersion().getName());
        data.setSourceTlmVersion("");
        data.setExportedAt(System.currentTimeMillis());
        data.setSourceMaidUuid(maidUuid);
        data.setOwnerUuid(ownerUuid);
        data.setTamed(true);

        String modelId = tag.contains("model_id") ? tag.getString("model_id") : tag.getString("ModelId");
        if (modelId.isEmpty()) {
            modelId = tag.getString("ModelId");
        }
        data.setModelId(modelId);
        // YSM 换模：展示名取实体 NBT 中的 YSM 模型名（键 YsmModelName / YsmModelId），
        // modelId 字段仍保持底层 TLM 模型 ID，供导入回退与跨版本迁移使用
        String displayName = null;
        if (tag.getBoolean("IsYsmModel")) {
            displayName = extractText(tag.getString("YsmModelName"));
            if (displayName == null || displayName.isEmpty()) {
                String ysmId = tag.getString("YsmModelId");
                if (!ysmId.isEmpty()) {
                    displayName = ysmId;
                }
            }
        }
        if (displayName == null || displayName.isEmpty()) {
            displayName = MaidTransferService.getDisplayName(modelId);
        }
        data.setDisplayName(displayName);
        String customName = extractText(tag.getString("CustomName"));
        if (customName != null && !customName.isEmpty()) {
            data.setCustomName(customName);
        }
        return data;
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
        for (String maidUuid : index.getAllKeys()) {
            CompoundTag entry = index.getCompound(maidUuid);
            String text = extractText(entry.getString("Name"));
            if (text != null && !text.isEmpty()) {
                map.put(maidUuid, text);
            }
        }
        return map;
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
}
