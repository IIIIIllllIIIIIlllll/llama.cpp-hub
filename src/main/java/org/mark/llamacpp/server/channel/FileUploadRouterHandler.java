package org.mark.llamacpp.server.channel;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.security.ApiKeyValidator;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.LastHttpContent;

public class FileUploadRouterHandler extends ChannelInboundHandlerAdapter {

    static final AttributeKey<Boolean> UPLOADING = AttributeKey.newInstance("uploading");
    static final AttributeKey<RandomAccessFile> UPLOAD_RAF = AttributeKey.newInstance("uploadRaf");
    static final AttributeKey<Path> UPLOAD_PATH = AttributeKey.newInstance("uploadPath");
    static final AttributeKey<HttpResponseStatus> UPLOAD_ERROR_STATUS = AttributeKey.newInstance("uploadErrorStatus");
    static final AttributeKey<String> UPLOAD_ERROR_MSG = AttributeKey.newInstance("uploadErrorMsg");

    private static final String I18N_UPLOAD_PARAM_NAME_MISSING = "api.error.upload.param.name.missing";
    private static final String I18N_UPLOAD_FILE_NAME_INVALID = "api.error.upload.file.name.invalid";
    private static final String I18N_UPLOAD_FILE_CREATE_FAILED = "api.error.upload.file.create.failed";
    private static final String I18N_UPLOAD_LIST_FAILED = "api.error.upload.list.failed";
    private static final String I18N_UPLOAD_FILE_NOT_FOUND = "api.error.upload.file.notfound";
    private static final String I18N_UPLOAD_DELETE_FAILED = "api.error.upload.delete.failed";

    private static final String UPLOAD_DIR = "llama.cpp-sources";

    private static final ConcurrentHashMap<String, ReentrantLock> uploadLocks = new ConcurrentHashMap<>();

    public FileUploadRouterHandler() {
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpRequest) {
            HttpRequest request = (HttpRequest) msg;
            String uri = request.uri();
            HttpMethod method = request.method();

            if (method == HttpMethod.OPTIONS) {
                ctx.fireChannelRead(msg);
                return;
            }

            // 认证检查：拦截 /api/uploads 开头的请求，未通过验证则返回 401
            if (uri.startsWith("/api/uploads")) {
                String clientIp = ApiKeyValidator.getClientIp(ctx, request);
                if (!ApiKeyValidator.validate(request, clientIp)) {
                    ApiKeyValidator.sendUnauthorized(ctx, request);
                    ReferenceCountUtil.release(msg);
                    return;
                }
            }

            if (uri.startsWith("/api/uploads") && method == HttpMethod.POST) {
                String query = null;
                int qIdx = uri.indexOf('?');
                if (qIdx >= 0) {
                    query = uri.substring(qIdx + 1);
                }

                String fileName = getQueryParam(query, "name");
                if (fileName == null || fileName.trim().isEmpty()) {
                    if (request instanceof LastHttpContent) {
                        LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_UPLOAD_PARAM_NAME_MISSING);
                        ReferenceCountUtil.release(request);
                    } else {
                        setUploadError(ctx, HttpResponseStatus.BAD_REQUEST, I18N_UPLOAD_PARAM_NAME_MISSING);
                    }
                    return;
                }
                fileName = sanitizeFileName(fileName.trim());
                if (fileName == null || fileName.isEmpty()) {
                    if (request instanceof LastHttpContent) {
                        LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_UPLOAD_FILE_NAME_INVALID);
                        ReferenceCountUtil.release(request);
                    } else {
                        setUploadError(ctx, HttpResponseStatus.BAD_REQUEST, I18N_UPLOAD_FILE_NAME_INVALID);
                    }
                    return;
                }

                Path uploadDir = getUploadDir();
                Path targetFile = uploadDir.resolve(fileName).toAbsolutePath().normalize();
                if (!targetFile.startsWith(uploadDir)) {
                    if (request instanceof LastHttpContent) {
                        LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_UPLOAD_FILE_NAME_INVALID);
                        ReferenceCountUtil.release(request);
                    } else {
                        setUploadError(ctx, HttpResponseStatus.BAD_REQUEST, I18N_UPLOAD_FILE_NAME_INVALID);
                    }
                    return;
                }

                String lockKey = targetFile.toString();
                ReentrantLock lock = uploadLocks.computeIfAbsent(lockKey, k -> new ReentrantLock());
                lock.lock();
                try {
                    targetFile = resolveUniqueFileName(uploadDir, fileName);

                    Files.createDirectories(targetFile.getParent());
                    RandomAccessFile raf = new RandomAccessFile(targetFile.toFile(), "rw");
                    ctx.channel().attr(UPLOADING).set(true);
                    ctx.channel().attr(UPLOAD_RAF).set(raf);
                    ctx.channel().attr(UPLOAD_PATH).set(targetFile);
                } catch (Exception e) {
                    if (request instanceof LastHttpContent) {
                        LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, I18N_UPLOAD_FILE_CREATE_FAILED + ": " + e.getMessage());
                        ReferenceCountUtil.release(request);
                    } else {
                        setUploadError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, I18N_UPLOAD_FILE_CREATE_FAILED + ": " + e.getMessage());
                    }
                } finally {
                    lock.unlock();
                    uploadLocks.remove(lockKey, lock);
                }
                return;
            }

            if (uri.startsWith("/api/uploads/list") && method == HttpMethod.GET) {
                String query = null;
                int qIdx = uri.indexOf('?');
                if (qIdx >= 0) {
                    query = uri.substring(qIdx + 1);
                }
                handleListFiles(ctx, query);
                return;
            }

            if (uri.startsWith("/api/uploads/delete") && method == HttpMethod.DELETE) {
                String query = null;
                int qIdx = uri.indexOf('?');
                if (qIdx >= 0) {
                    query = uri.substring(qIdx + 1);
                }
                handleDeleteFile(ctx, query);
                return;
            }

            ctx.fireChannelRead(msg);
        } else if (msg instanceof HttpContent) {
            HttpContent content = (HttpContent) msg;
            HttpResponseStatus errorStatus = ctx.channel().attr(UPLOAD_ERROR_STATUS).get();
            String errorMsg = ctx.channel().attr(UPLOAD_ERROR_MSG).get();
            if (errorStatus != null) {
                if (content instanceof LastHttpContent) {
                    LlamaServer.sendJsonErrorResponse(ctx, errorStatus, errorMsg);
                    clearUploadAttributes(ctx);
                }
                content.release();
                return;
            }

            Boolean uploading = ctx.channel().attr(UPLOADING).get();
            if (Boolean.TRUE.equals(uploading)) {
                RandomAccessFile raf = ctx.channel().attr(UPLOAD_RAF).get();
                if (raf != null) {
                    writeChunkToRaf(raf, content.content());
                }
                if (content instanceof LastHttpContent) {
                    Path targetFile = ctx.channel().attr(UPLOAD_PATH).get();
                    long size = 0;
                    if (raf != null) {
                        try {
                            raf.close();
                        } catch (Exception ignore) {
                        }
                    }
                    ctx.channel().attr(UPLOAD_RAF).set(null);

                    if (targetFile != null) {
                        try {
                            size = targetFile.toFile().length();
                        } catch (Exception ignore) {
                        }
                    }

                    Map<String, Object> resp = new HashMap<>();
                    resp.put("success", true);
                    if (targetFile != null) {
                        Path name = targetFile.getFileName();
                        resp.put("name", name != null ? name.toString() : "");
                        resp.put("size", size);
                        resp.put("path", targetFile.toString());
                    }
                    LlamaServer.sendJsonResponse(ctx, resp);
                    ctx.channel().attr(UPLOADING).set(false);
                }
                content.release();
                return;
            }
            ctx.fireChannelRead(msg);
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        Boolean uploading = ctx.channel().attr(UPLOADING).get();
        if (Boolean.TRUE.equals(uploading)) {
            RandomAccessFile raf = ctx.channel().attr(UPLOAD_RAF).get();
            Path tempFile = ctx.channel().attr(UPLOAD_PATH).get();
            if (raf != null) {
                try {
                    raf.close();
                } catch (Exception ignore) {
                }
            }
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception ignore) {
                }
            }
        }
        clearUploadAttributes(ctx);
        super.channelInactive(ctx);
    }

    private static void setUploadError(ChannelHandlerContext ctx, HttpResponseStatus status, String msg) {
        ctx.channel().attr(UPLOAD_ERROR_STATUS).set(status);
        ctx.channel().attr(UPLOAD_ERROR_MSG).set(msg);
    }

    private static void clearUploadAttributes(ChannelHandlerContext ctx) {
        ctx.channel().attr(UPLOADING).set(false);
        ctx.channel().attr(UPLOAD_RAF).set(null);
        ctx.channel().attr(UPLOAD_PATH).set(null);
        ctx.channel().attr(UPLOAD_ERROR_STATUS).set(null);
        ctx.channel().attr(UPLOAD_ERROR_MSG).set(null);
    }

    private static void writeChunkToRaf(RandomAccessFile raf, io.netty.buffer.ByteBuf buf) throws Exception {
        int readable = buf.readableBytes();
        if (readable <= 0) return;
        if (buf.hasArray()) {
            raf.write(buf.array(), buf.arrayOffset() + buf.readerIndex(), readable);
            buf.readerIndex(buf.readerIndex() + readable);
        } else {
            byte[] tmp = new byte[readable];
            buf.getBytes(buf.readerIndex(), tmp);
            raf.write(tmp);
            buf.readerIndex(buf.readerIndex() + readable);
        }
    }

    private void handleListFiles(ChannelHandlerContext ctx, String query) {
        try {
            Path uploadDir = getUploadDir();
            String extFilter = getQueryParam(query, "ext");
            if (extFilter != null) {
                extFilter = extFilter.trim().toLowerCase();
                if (!extFilter.startsWith(".")) {
                    extFilter = "." + extFilter;
                }
            }
            final String finalExt = extFilter;

            List<Map<String, Object>> files = new ArrayList<>();
            if (Files.exists(uploadDir)) {
                try (Stream<Path> stream = Files.list(uploadDir)) {
                    stream.filter(p -> Files.isRegularFile(p) && (finalExt == null || p.toString().toLowerCase().endsWith(finalExt))).forEach(p -> {
                        try {
                            Map<String, Object> entry = new HashMap<>();
                            entry.put("name", p.getFileName().toString());
                            entry.put("size", Files.size(p));
                            entry.put("modifiedAt", Files.getLastModifiedTime(p).toMillis());
                            files.add(entry);
                        } catch (Exception ignore) {
                        }
                    });
                }
            }
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("files", files);
            LlamaServer.sendJsonResponse(ctx, resp);
        } catch (Exception e) {
            LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, I18N_UPLOAD_LIST_FAILED + ": " + e.getMessage());
        }
    }

    private void handleDeleteFile(ChannelHandlerContext ctx, String query) {
        try {
            String fileName = getQueryParam(query, "name");
            if (fileName == null || fileName.trim().isEmpty()) {
                LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_UPLOAD_PARAM_NAME_MISSING);
                return;
            }
            fileName = sanitizeFileName(fileName.trim());
            if (fileName == null || fileName.isEmpty()) {
                LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_UPLOAD_FILE_NAME_INVALID);
                return;
            }

            Path uploadDir = getUploadDir();
            Path targetFile = uploadDir.resolve(fileName).toAbsolutePath().normalize();
            if (!targetFile.startsWith(uploadDir)) {
                LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_UPLOAD_FILE_NAME_INVALID);
                return;
            }

            boolean deleted = Files.deleteIfExists(targetFile);
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", deleted);
            resp.put("name", fileName);
            if (!deleted) {
                LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.NOT_FOUND, I18N_UPLOAD_FILE_NOT_FOUND + ": " + fileName);
                return;
            }
            LlamaServer.sendJsonResponse(ctx, resp);
        } catch (Exception e) {
            LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, I18N_UPLOAD_DELETE_FAILED + ": " + e.getMessage());
        }
    }

    private static Path getUploadDir() {
        return LlamaServer.getCachePath().resolve(UPLOAD_DIR).toAbsolutePath().normalize();
    }

    private static Path resolveUniqueFileName(Path dir, String fileName) {
        Path target = dir.resolve(fileName).toAbsolutePath().normalize();
        if (!Files.exists(target)) {
            return target;
        }

        int dotIndex = fileName.lastIndexOf('.');
        String base = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
        String ext = dotIndex > 0 ? fileName.substring(dotIndex) : "";

        int index = 1;
        while (true) {
            String candidate = base + "-" + index + ext;
            target = dir.resolve(candidate).toAbsolutePath().normalize();
            if (!Files.exists(target)) {
                return target;
            }
            index++;
            if (index > 10000) {
                return dir.resolve(base + "-" + System.currentTimeMillis() + ext).toAbsolutePath().normalize();
            }
        }
    }

    private static String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) return null;
        try {
            fileName = Paths.get(fileName).getFileName().toString();
        } catch (Exception e) {
            return null;
        }
        fileName = fileName.replaceAll("[<>:\"/\\\\|?*]", "_").trim();
        return fileName.isEmpty() ? null : fileName;
    }

    private static String getQueryParam(String query, String key) {
        if (query == null || query.isEmpty() || key == null || key.isEmpty()) return null;
        String[] parts = query.split("&");
        for (String p : parts) {
            int idx = p.indexOf('=');
            if (idx < 0) continue;
            String k = p.substring(0, idx);
            if (!key.equals(k)) continue;
            String v = p.substring(idx + 1);
            try {
                return java.net.URLDecoder.decode(v, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                return v;
            }
        }
        return null;
    }
}
