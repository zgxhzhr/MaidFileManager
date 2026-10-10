package io.github.zgxhzhr.maidfm.client;

import io.github.zgxhzhr.maidfm.Constants;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 档案照片工具：扫描玩家投放目录、1:1 裁切缩放并编码 PNG。
 *
 * <p>照片来源：玩家把任意图片放入 {@code maid_file/photos/}，
 * 在档案界面点「选择照片」从列表挑选。处理全部走 JDK 标准库
 * （{@code ImageIO} + {@code BufferedImage}），不依赖 Minecraft 的 {@code NativeImage} 版本差异，
 * 因此 common 模块一份实现即可跨加载器复用。
 */
public final class MaidPhotoUtil {
    /** 可识别的图片后缀 */
    private static final String[] IMAGE_EXT = {".png", ".jpg", ".jpeg", ".bmp", ".gif"};

    private MaidPhotoUtil() {
    }

    /** 照片投放目录（相对游戏根目录） */
    public static Path photosDir(Path gameDir) {
        return gameDir.resolve(Constants.MAID_PHOTOS_DIR);
    }

    /** 确保照片目录存在（不存在则创建），返回该目录；创建失败时抛出 IOException */
    public static Path ensurePhotosDir(Path gameDir) throws IOException {
        Path dir = photosDir(gameDir);
        Files.createDirectories(dir);
        return dir;
    }

    /** 原图文件是否超过 {@link Constants#PROFILE_PHOTO_SOURCE_MAX_BYTES}（读不出大小时按未超限处理） */
    public static boolean isSourceTooLarge(Path file) {
        try {
            return Files.size(file) > Constants.PROFILE_PHOTO_SOURCE_MAX_BYTES;
        } catch (IOException e) {
            return false;
        }
    }

    /** 列出投放目录内的图片文件名（按字母序）；目录不存在时返回空列表 */
    public static List<String> listPhotoFiles(Path dir) {
        List<String> names = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return names;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                if (Files.isRegularFile(p) && isImageFile(p.getFileName().toString())) {
                    names.add(p.getFileName().toString());
                }
            }
        } catch (IOException e) {
            Constants.LOG.warn("[maid_file_manager] 读取照片目录失败: {}", e.toString());
        }
        names.sort(String::compareToIgnoreCase);
        return names;
    }

    private static boolean isImageFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : IMAGE_EXT) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 读取图片文件，居中裁切为 1:1 后缩放至 {@code size×size}，编码为 PNG 字节。
     *
     * <p>裁切规则：取短边为边长，从中心裁出正方形，保证 1:1 比例且不拉伸变形。
     *
     * @return PNG 编码后的字节数组
     * @throws IOException 文件无法解析或编码失败（由调用方转为界面反馈，不静默）
     */
    public static byte[] loadAndEncode(Path file, int size) throws IOException {
        if (isSourceTooLarge(file)) {
            throw new IOException("图片原图超过上限（10MB）: " + file.getFileName());
        }
        BufferedImage src = ImageIO.read(file.toFile());
        if (src == null) {
            throw new IOException("无法解析图片文件: " + file.getFileName());
        }
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) {
            throw new IOException("图片尺寸无效");
        }
        int side = Math.min(w, h);
        int sx = (w - side) / 2;
        int sy = (h - side) / 2;
        BufferedImage square = src.getSubimage(sx, sy, side, side);
        BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(square, 0, 0, size, size, null);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(scaled, "PNG", out)) {
            throw new IOException("PNG 编码器不可用");
        }
        return out.toByteArray();
    }
}
