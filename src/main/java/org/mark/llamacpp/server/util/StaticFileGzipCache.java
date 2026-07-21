package org.mark.llamacpp.server.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 静态文件 gzip 缓存工具。
 * <p>
 * 仅对文本类静态资源（html/js/css/json 等）按需生成 .gz 缓存文件，
 * 配合 {@code Content-Encoding: gzip} 返回，减少网络传输量。
 * </p>
 * <p>
 * 设计原则：
 * <ul>
 *   <li>启动时清空缓存，防止 web 文件更新后仍使用旧压缩文件</li>
 *   <li>每次请求时检查源文件是否比缓存文件更新，是则重新压缩</li>
 *   <li>不压缩图片、视频、PDF 等二进制文件</li>
 *   <li>压缩失败时返回 null，由调用方回退到原始文件</li>
 * </ul>
 * </p>
 */
public class StaticFileGzipCache {

    private static final Logger logger = LoggerFactory.getLogger(StaticFileGzipCache.class);

    private static final String CACHE_DIR_NAME = "cache/static-gzip";
    private static final Set<String> COMPRESSIBLE_EXTENSIONS = Set.of(
            "html", "htm", "css", "js", "json", "xml", "svg", "txt", "webmanifest"
    );

    /** 分片锁：固定 64 把，避免按路径持有的锁对象只增不减（参照 EasyChatService 的做法） */
    private static final Object[] FILE_LOCKS = new Object[64];
    static {
        for (int i = 0; i < FILE_LOCKS.length; i++) {
            FILE_LOCKS[i] = new Object();
        }
    }

    private StaticFileGzipCache() {
        // 工具类，禁止实例化
    }

    /**
     * 启动时调用：清空已有 gzip 缓存目录。
     */
    public static void clearCache() {
        File cacheDir = getCacheDir();
        if (cacheDir.exists()) {
            deleteRecursively(cacheDir);
            logger.info("已清空静态文件 gzip 缓存目录: {}", cacheDir.getAbsolutePath());
        }
        if (!cacheDir.mkdirs()) {
            logger.warn("无法创建静态文件 gzip 缓存目录: {}", cacheDir.getAbsolutePath());
        }
    }

    /**
     * 获取源文件对应的 gzip 缓存文件。
     * <p>
     * 如果缓存不存在、缓存比源文件旧、或缓存大小为 0，则重新压缩。
     * </p>
     *
     * @param sourceFile  源静态文件
     * @param requestPath 请求路径（如 /js/app.js），用于生成缓存目录结构
     * @return 缓存的 .gz 文件；如果不应压缩或压缩失败，返回 null
     */
    public static File getGzipFile(File sourceFile, String requestPath) {
        if (sourceFile == null || !sourceFile.exists() || !sourceFile.isFile()) {
            return null;
        }
        if (!isCompressible(sourceFile.getName())) {
            return null;
        }

        String safePath = sanitizePath(requestPath);
        if (safePath.isEmpty()) {
            safePath = sourceFile.getName();
        }

        File gzFile = new File(getCacheDir(), safePath + ".gz");

        // 如果缓存已存在且比源文件新，直接返回
        if (isCacheValid(gzFile, sourceFile)) {
            return gzFile;
        }

        // 按请求路径哈希到分片锁，防止并发请求同一文件时重复压缩
        Object lock = FILE_LOCKS[(safePath.hashCode() & 0x7FFFFFFF) % FILE_LOCKS.length];
        synchronized (lock) {
            // 双重检查
            if (isCacheValid(gzFile, sourceFile)) {
                return gzFile;
            }

            File parent = gzFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                logger.warn("无法创建 gzip 缓存父目录: {}", parent.getAbsolutePath());
                return null;
            }

            try {
                compress(sourceFile, gzFile);
                logger.debug("静态文件已压缩: {} -> {}", sourceFile.getAbsolutePath(), gzFile.getAbsolutePath());
                return gzFile;
            } catch (IOException e) {
                logger.error("压缩静态文件失败: {}", sourceFile.getAbsolutePath(), e);
                if (gzFile.exists() && !gzFile.delete()) {
                    logger.warn("无法删除失败的 gzip 缓存文件: {}", gzFile.getAbsolutePath());
                }
                return null;
            }
        }
    }

    /**
     * 判断文件是否应该被压缩。
     */
    public static boolean isCompressible(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return false;
        }
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot < 0 || lastDot == fileName.length() - 1) {
            return false;
        }
        String ext = fileName.substring(lastDot + 1).toLowerCase(Locale.ROOT);
        return COMPRESSIBLE_EXTENSIONS.contains(ext);
    }

    private static boolean isCacheValid(File gzFile, File sourceFile) {
        return gzFile.exists()
                && gzFile.length() > 0
                && gzFile.lastModified() >= sourceFile.lastModified();
    }

    private static void compress(File source, File target) throws IOException {
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new java.util.zip.GZIPOutputStream(new FileOutputStream(target), 8192)) {
            in.transferTo(out);
        }
    }

    private static File getCacheDir() {
        return new File(CACHE_DIR_NAME);
    }

    private static String sanitizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        String normalized = path.replace('\\', '/');
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }

        String[] parts = normalized.split("/");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty() || "..".equals(part) || ".".equals(part)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(part);
        }
        return sb.toString();
    }

    private static void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        if (!file.delete()) {
            logger.warn("无法删除文件或目录: {}", file.getAbsolutePath());
        }
    }
}
