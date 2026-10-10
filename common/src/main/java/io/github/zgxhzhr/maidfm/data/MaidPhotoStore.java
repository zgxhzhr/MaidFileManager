package io.github.zgxhzhr.maidfm.data;

import io.github.zgxhzhr.maidfm.Constants;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * 女仆档案照片的外置存储。
 *
 * <p>照片不再写入女仆实体 NBT，而是以 {@code <女仆UUID>.png} 独立存盘于服务端的
 * {@code maid_file/maid_photos/} 目录。这样做的收益：
 * <ul>
 *   <li>实体存档体积不再随照片增大而膨胀</li>
 *   <li>照片可以按更高分辨率保存（最长边 {@link Constants#PROFILE_PHOTO_MAX_SIDE}），
 *       档案界面显示更清晰</li>
 *   <li>导出时照片仍随 {@code .maid} 一并打包；导入时按新生成的女仆 UUID 释放回本目录</li>
 * </ul>
 *
 * <p>与 {@link io.github.zgxhzhr.maidfm.client.MaidPhotoUtil} 的分工：后者负责扫描玩家投放的
 * 候选图目录并编码缩放（客户端），本类负责按女仆 UUID 读写/删除已入库的照片（服务端）。
 */
public final class MaidPhotoStore {
    /** PNG 文件头魔数（89 50 4E 47 0D 0A 1A 0A） */
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    /** IHDR 数据块类型名 */
    private static final byte[] PNG_IHDR = {'I', 'H', 'D', 'R'};
    /** PNG 尺寸探测所需的最少字节数：8 魔数 + 4 长度 + 4 类型 + 4 宽 + 4 高 */
    private static final int PNG_HEADER_MIN_BYTES = 24;
    /** 原子写临时文件后缀 */
    private static final String TMP_SUFFIX = ".tmp";

    private MaidPhotoStore() {
    }

    /** 照片库目录（相对游戏根目录） */
    public static Path photosDir(Path gameDir) {
        return gameDir.resolve(Constants.MAID_PHOTOS_DIR);
    }

    /** 某女仆的照片文件路径 */
    public static Path photoFile(Path gameDir, UUID maidUuid) {
        if (gameDir == null || maidUuid == null) {
            return null;
        }
        return photosDir(gameDir).resolve(maidUuid + ".png");
    }

    /** 读取某女仆的照片字节；文件不存在、非普通文件或读取失败返回 null */
    public static byte[] read(Path gameDir, UUID maidUuid) {
        Path file = photoFile(gameDir, maidUuid);
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0) {
                return null;
            }
            return bytes;
        } catch (IOException e) {
            Constants.LOG.warn("[maid_file_manager] 读取档案照片失败: {}: {}", file, e.toString());
            return null;
        }
    }

    /** 照片文件是否存在 */
    public static boolean exists(Path gameDir, UUID maidUuid) {
        Path file = photoFile(gameDir, maidUuid);
        return file != null && Files.isRegularFile(file);
    }

    /**
     * 写入某女仆的照片（原子写：先写同目录 {@code .tmp} 再移动替换）。
     *
     * @return 是否写入成功；失败记 WARN 日志，调用方据此决定是否提示
     */
    public static boolean write(Path gameDir, UUID maidUuid, byte[] png) {
        Path file = photoFile(gameDir, maidUuid);
        if (file == null || png == null || png.length == 0) {
            return false;
        }
        Path tmp = file.resolveSibling(file.getFileName().toString() + TMP_SUFFIX);
        try {
            Files.createDirectories(file.getParent());
            Files.write(tmp, png);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            Constants.LOG.warn("[maid_file_manager] 写入档案照片失败: {}: {}", file, e.toString());
            return false;
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 清理临时文件失败不影响主流程
            }
        }
    }

    /** 删除某女仆的照片（不存在时静默）；删除失败记 WARN 日志 */
    public static void delete(Path gameDir, UUID maidUuid) {
        Path file = photoFile(gameDir, maidUuid);
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            Constants.LOG.warn("[maid_file_manager] 删除档案照片失败: {}: {}", file, e.toString());
        }
    }

    /**
     * 校验是否为可入库的档案照片：体积不超上限，且是尺寸合理的 PNG。
     *
     * <p>服务端在写入前必须校验：档案照片来自客户端上传或外部 {@code .maid}，
     * 若不校验尺寸，一个高压缩比的小体积巨图会在客户端贴图解码时撑爆内存。
     * 这里只探测 PNG 文件头中的宽高，不整体解码，开销极小。
     */
    public static boolean isValidProfilePhoto(byte[] png) {
        if (png == null || png.length < PNG_HEADER_MIN_BYTES || png.length > Constants.PROFILE_PHOTO_MAX_BYTES) {
            return false;
        }
        for (int i = 0; i < PNG_MAGIC.length; i++) {
            if (png[i] != PNG_MAGIC[i]) {
                return false;
            }
        }
        for (int i = 0; i < PNG_IHDR.length; i++) {
            if (png[12 + i] != PNG_IHDR[i]) {
                return false;
            }
        }
        int width = readInt(png, 16);
        int height = readInt(png, 20);
        if (width <= 0 || height <= 0) {
            return false;
        }
        return width <= Constants.PROFILE_PHOTO_MAX_SIDE && height <= Constants.PROFILE_PHOTO_MAX_SIDE;
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24)
                | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8)
                | (bytes[offset + 3] & 0xFF);
    }
}
