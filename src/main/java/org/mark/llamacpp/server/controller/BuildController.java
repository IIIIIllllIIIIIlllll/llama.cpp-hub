package org.mark.llamacpp.server.controller;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.service.BuildTaskManager;
import org.mark.llamacpp.server.service.BuildTaskManager.BuildTask;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.tools.ParamTool;
import org.mark.llamacpp.server.tools.ToolchainChecker;
import org.mark.llamacpp.server.tools.ToolchainChecker.CheckResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.stream.ChunkedFile;

public class BuildController implements BaseController {

    private static final Logger logger = LoggerFactory.getLogger(BuildController.class);
    private static final BuildTaskManager taskManager = BuildTaskManager.getInstance();

    private static final String I18N_METHOD_POST_ONLY = "common.method.post.only";
    private static final String I18N_METHOD_GET_ONLY = "common.method.get.only";
    private static final String I18N_BODY_EMPTY = "api.error.body.empty";
    private static final String I18N_BODY_PARSE = "api.error.body.parse";
    private static final String I18N_PARAM_SOURCE_DIR_MISSING = "api.error.param.sourceDir.missing";
    private static final String I18N_PARAM_OUTPUT_DIR_NAME_MISSING = "api.error.param.outputDirName.missing";
    private static final String I18N_PARAM_CMAKE_COMMAND_MISSING = "api.error.param.cmakeCommand.missing";
    private static final String I18N_PARAM_BUILD_COMMAND_MISSING = "api.error.param.buildCommand.missing";
    private static final String I18N_PARAM_OUTPUT_DIR_NAME_INVALID = "api.error.param.outputDirName.invalid";
    private static final String I18N_PARAM_ARCHIVE_MISSING = "api.error.param.archive.missing";
    private static final String I18N_PARAM_TARGET_DIR_MISSING = "api.error.param.targetDir.missing";
    private static final String I18N_PARAM_TARGET_DIR_INVALID = "api.error.param.targetDir.invalid";
    private static final String I18N_BUILD_CMAKE_INVALID = "api.error.build.cmake.invalid";
    private static final String I18N_BUILD_BUILD_CMD_INVALID = "api.error.build.build.cmd.invalid";
    private static final String I18N_BUILD_OUTPUT_DIR_EXISTS = "api.error.build.output.dir.exists";
    private static final String I18N_BUILD_TASK_RUNNING = "api.error.build.task.running";
    private static final String I18N_BUILD_TASK_NOT_FOUND = "api.error.build.task.notfound";
    private static final String I18N_BUILD_CANCEL_TASK_NOT_FOUND = "api.error.build.cancel.task.notfound";
    private static final String I18N_BUILD_ARCHIVE_NOT_FOUND = "api.error.build.archive.notfound";
    private static final String I18N_BUILD_SUBMIT_FAILED = "api.error.build.submit.failed";
    private static final String I18N_BUILD_STATUS_FAILED = "api.error.build.status.failed";
    private static final String I18N_BUILD_CANCEL_FAILED = "api.error.build.cancel.failed";
    private static final String I18N_BUILD_EXTRACT_FAILED = "api.error.build.extract.failed";
    private static final String I18N_BUILD_HISTORY_FAILED = "api.error.build.history.failed";
    private static final String I18N_BUILD_OUTPUT_FAILED = "api.error.build.output.failed";
    private static final String I18N_BUILD_TOOLCHAIN_FAILED = "api.error.build.toolchain.failed";

    private static final String PATH_BUILD_SUBMIT = "/api/build/submit";
    private static final String PATH_BUILD_STATUS = "/api/build/status";
    private static final String PATH_BUILD_CANCEL = "/api/build/cancel";
    private static final String PATH_BUILD_EXTRACT = "/api/build/extract";
    private static final String PATH_BUILD_HISTORY = "/api/build/history";
    private static final String PATH_BUILD_OUTPUT = "/api/build/output";
    private static final String PATH_BUILD_CHECK_TOOLCHAIN = "/api/build/check-toolchain";

    /**
     * 输出日志下发上限：只回传日志文件尾部最多 1MB
     */
    private static final long MAX_LOG_SERVE_BYTES = 1024 * 1024;

    @Override
    public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request)
            throws RequestMethodException {
        if (uri.equals(PATH_BUILD_SUBMIT)) {
            handleBuildSubmit(ctx, request);
            return true;
        } else if (uri.equals(PATH_BUILD_STATUS)) {
            handleBuildStatus(ctx, request);
            return true;
        } else if (uri.equals(PATH_BUILD_CANCEL)) {
            handleBuildCancel(ctx, request);
            return true;
        } else if (uri.equals(PATH_BUILD_EXTRACT)) {
            handleBuildExtract(ctx, request);
            return true;
        } else if (uri.equals(PATH_BUILD_HISTORY)) {
            handleBuildHistory(ctx, request);
            return true;
        } else if (uri.equals(PATH_BUILD_OUTPUT)) {
            handleBuildOutput(ctx, request);
            return true;
        } else if (uri.equals(PATH_BUILD_CHECK_TOOLCHAIN)) {
            handleCheckToolchain(ctx, request);
            return true;
        }
        return false;
    }

    private void handleBuildSubmit(ChannelHandlerContext ctx, FullHttpRequest request)
            throws RequestMethodException {
        if (handleCorsOptions(ctx, request)) {
            return;
        }
        assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);

        try {
            byte[] content = readRequestBodyOrSendError(ctx, request);
            if (content == null) {
                return;
            }
            JsonObject obj = parseJsonObjectOrSendError(ctx, content);
            if (obj == null) {
                return;
            }

            String sourceArchive = trimToNull(JsonUtil.getJsonString(obj, "sourceArchive", null));
            String sourceDir = trimToNull(JsonUtil.getJsonString(obj, "sourceDir", null));
            String outputDirName = trimToNull(JsonUtil.getJsonString(obj, "outputDirName", null));
            String cmakeCommand = trimToNull(JsonUtil.getJsonString(obj, "cmakeCommand", null));
            String buildCommand = trimToNull(JsonUtil.getJsonString(obj, "buildCommand", null));

            if (sourceDir == null) {
                LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_PARAM_SOURCE_DIR_MISSING);
                return;
            }
            if (outputDirName == null) {
                LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_PARAM_OUTPUT_DIR_NAME_MISSING);
                return;
            }
            if (cmakeCommand == null) {
                LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_PARAM_CMAKE_COMMAND_MISSING);
                return;
            }
            if (buildCommand == null) {
                LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_PARAM_BUILD_COMMAND_MISSING);
                return;
            }

            if (!isValidCmakeCommand(cmakeCommand)) {
                LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_BUILD_CMAKE_INVALID);
                return;
            }
            if (!isValidBuildCommand(buildCommand)) {
                LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_BUILD_BUILD_CMD_INVALID);
                return;
            }

            outputDirName = sanitizeDirName(outputDirName);
            if (outputDirName == null || outputDirName.isEmpty()) {
                LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_PARAM_OUTPUT_DIR_NAME_INVALID);
                return;
            }

            Path llamacppDir = Paths.get(LlamaServer.getDefaultLlamaCppPath()).toAbsolutePath().normalize();
            Path targetPath = llamacppDir.resolve(outputDirName).toAbsolutePath().normalize();
            if (!targetPath.startsWith(llamacppDir)) {
                LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_PARAM_OUTPUT_DIR_NAME_INVALID);
                return;
            }
            if (Files.exists(targetPath)) {
                LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.CONFLICT, I18N_BUILD_OUTPUT_DIR_EXISTS);
                return;
            }

            BuildTask task = taskManager.submitTask(sourceArchive, sourceDir, outputDirName,
                    cmakeCommand, buildCommand);

            if (task == null) {
                LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.CONFLICT, I18N_BUILD_TASK_RUNNING);
                return;
            }

            Map<String, Object> data = new HashMap<>();
            data.put("taskId", task.taskId);
            data.put("status", task.status);
            LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));

        } catch (Exception e) {
            logger.error("提交编译任务失败", e);
            LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BUILD_SUBMIT_FAILED + ": " + e.getMessage()));
        }
    }

    private void handleBuildStatus(ChannelHandlerContext ctx, FullHttpRequest request)
            throws RequestMethodException {
        if (handleCorsOptions(ctx, request)) {
            return;
        }
        assertRequestMethod(request.method() != HttpMethod.GET, I18N_METHOD_GET_ONLY);

        try {
            Map<String, String> params = ParamTool.getQueryParam(request.uri());
            String taskId = params.get("taskId");

            BuildTask task = taskManager.getStatus(taskId);

            if (task == null) {
                LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.NOT_FOUND, I18N_BUILD_TASK_NOT_FOUND);
                return;
            }

            Map<String, Object> data = new HashMap<>();
            data.put("taskId", task.taskId);
            data.put("status", task.status);
            data.put("sourceArchive", task.sourceArchive);
            data.put("sourceDir", task.sourceDir);
            data.put("outputDirName", task.outputDirName);
            data.put("outputDir", task.outputDir);
            data.put("cmakeCommand", task.cmakeCommand);
            data.put("buildCommand", task.buildCommand);
            data.put("exitCode", task.exitCode);
            data.put("startTime", task.startTime);
            data.put("endTime", task.endTime);

            LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));

        } catch (Exception e) {
            logger.error("获取编译状态失败", e);
            LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BUILD_STATUS_FAILED + ": " + e.getMessage()));
        }
    }

    private void handleBuildCancel(ChannelHandlerContext ctx, FullHttpRequest request)
            throws RequestMethodException {
        if (handleCorsOptions(ctx, request)) {
            return;
        }
        assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);

        try {
            byte[] content = JsonUtil.readRequestBytes(request);
            String taskId = null;
            if (content != null && content.length > 0) {
                JsonObject obj = JsonUtil.fromJson(content, JsonObject.class);
                if (obj != null) {
                    taskId = trimToNull(JsonUtil.getJsonString(obj, "taskId", null));
                }
            }

            boolean cancelled = taskManager.cancelTask(taskId);
            if (!cancelled) {
                LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_BUILD_CANCEL_TASK_NOT_FOUND);
                return;
            }

            LlamaServer.sendJsonResponse(ctx, ApiResponse.success("任务已取消"));

        } catch (Exception e) {
            logger.error("取消编译任务失败", e);
            LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BUILD_CANCEL_FAILED + ": " + e.getMessage()));
        }
    }

    private void handleBuildExtract(ChannelHandlerContext ctx, FullHttpRequest request)
            throws RequestMethodException {
        if (handleCorsOptions(ctx, request)) {
            return;
        }
        assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);

        try {
            byte[] content = readRequestBodyOrSendError(ctx, request);
            if (content == null) {
                return;
            }
            JsonObject obj = parseJsonObjectOrSendError(ctx, content);
            if (obj == null) {
                return;
            }

            String archive = trimToNull(JsonUtil.getJsonString(obj, "archive", null));
            String targetDir = trimToNull(JsonUtil.getJsonString(obj, "targetDir", null));

            if (archive == null) {
                LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_PARAM_ARCHIVE_MISSING);
                return;
            }
            if (targetDir == null) {
                LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_PARAM_TARGET_DIR_MISSING);
                return;
            }

            File archiveFile = new File(archive);
            if (!archiveFile.exists()) {
                LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.NOT_FOUND, I18N_BUILD_ARCHIVE_NOT_FOUND + ": " + archive);
                return;
            }

            Path uploadDir = LlamaServer.getCachePath().resolve("llama.cpp-sources").toAbsolutePath().normalize();
            Path targetPath = Paths.get(targetDir).toAbsolutePath().normalize();
            if (!targetPath.startsWith(uploadDir)) {
                LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_PARAM_TARGET_DIR_INVALID);
                return;
            }

            boolean extracted = taskManager.extractArchive(archive, targetDir);
            if (!extracted) {
                LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BUILD_EXTRACT_FAILED));
                return;
            }

            String extractedTo = findTopLevelDir(targetDir);

            Map<String, Object> data = new HashMap<>();
            data.put("extractedTo", extractedTo != null ? extractedTo : targetDir);
            LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));

        } catch (Exception e) {
            logger.error("解压失败", e);
            LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BUILD_EXTRACT_FAILED + ": " + e.getMessage()));
        }
    }

    private void handleBuildHistory(ChannelHandlerContext ctx, FullHttpRequest request)
            throws RequestMethodException {
        if (handleCorsOptions(ctx, request)) {
            return;
        }
        assertRequestMethod(request.method() != HttpMethod.GET, I18N_METHOD_GET_ONLY);

        try {
            List<BuildTask> history = taskManager.getHistory();

            List<Map<String, Object>> list = new java.util.ArrayList<>();
            for (BuildTask task : history) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("taskId", task.taskId);
                entry.put("status", task.status);
                entry.put("outputDirName", task.outputDirName);
                entry.put("outputDir", task.outputDir);
                entry.put("startTime", task.startTime);
                entry.put("endTime", task.endTime);
                entry.put("exitCode", task.exitCode);
                list.add(entry);
            }

            Map<String, Object> data = new HashMap<>();
            data.put("tasks", list);
            LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));

        } catch (Exception e) {
            logger.error("获取编译历史失败", e);
            LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BUILD_HISTORY_FAILED + ": " + e.getMessage()));
        }
    }

    /**
     * 以 ChunkedFile 零拷贝方式流式下发任务日志（最多尾部 1MB），避免把日志读进堆内存。
     */
    private void handleBuildOutput(ChannelHandlerContext ctx, FullHttpRequest request)
            throws RequestMethodException {
        if (handleCorsOptions(ctx, request)) {
            return;
        }
        assertRequestMethod(request.method() != HttpMethod.GET, I18N_METHOD_GET_ONLY);

        RandomAccessFile raf = null;
        long offset;
        long length;
        try {
            Map<String, String> params = ParamTool.getQueryParam(request.uri());
            String taskId = params.get("taskId");
            java.io.File logFile = taskManager.getTaskLogFile(taskId);
            long fileLength = logFile == null ? 0 : logFile.length();
            offset = Math.max(0, fileLength - MAX_LOG_SERVE_BYTES);
            length = fileLength - offset;
            if (length > 0) {
                raf = new RandomAccessFile(logFile, "r");
            }
        } catch (Exception e) {
            logger.error("获取编译日志失败", e);
            LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BUILD_OUTPUT_FAILED + ": " + e.getMessage()));
            return;
        }

        try {
            DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
            response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
            ctx.write(response);

            if (raf != null) {
                final RandomAccessFile finalRaf = raf;
                ctx.write(new ChunkedFile(raf, offset, length, 8192), ctx.newProgressivePromise());
                raf = null;
                ChannelFuture last = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
                last.addListener(future -> {
                    try {
                        finalRaf.close();
                    } catch (Exception ignore) {
                    }
                    ctx.close();
                });
            } else {
                ChannelFuture last = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
                last.addListener(future -> ctx.close());
            }
        } catch (Exception e) {
            logger.error("下发编译日志失败", e);
            ctx.close();
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (Exception ignore) {
                }
            }
        }
    }

    private void handleCheckToolchain(ChannelHandlerContext ctx, FullHttpRequest request)
            throws RequestMethodException {
        if (handleCorsOptions(ctx, request)) {
            return;
        }
        assertRequestMethod(request.method() != HttpMethod.GET, I18N_METHOD_GET_ONLY);

        try {
            Map<String, CheckResult> results = ToolchainChecker.checkAll();
            Map<String, Map<String, Object>> data = new HashMap<>();
            for (Map.Entry<String, CheckResult> entry : results.entrySet()) {
                CheckResult r = entry.getValue();
                Map<String, Object> item = new HashMap<>();
                item.put("name", r.name);
                item.put("available", r.available);
                item.put("version", r.version);
                item.put("path", r.path);
                item.put("details", r.details);
                data.put(entry.getKey(), item);
            }
            LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
        } catch (Exception e) {
            logger.error("工具链检查失败", e);
            LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BUILD_TOOLCHAIN_FAILED + ": " + e.getMessage()));
        }
    }

    private static String findTopLevelDir(String baseDir) {
        try {
            Path base = Paths.get(baseDir);
            if (!Files.isDirectory(base)) {
                return baseDir;
            }
            try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(base)) {
                java.util.List<Path> children = new java.util.ArrayList<>();
                for (Path child : stream) {
                    children.add(child);
                }
                if (children.size() == 1 && Files.isDirectory(children.get(0))) {
                    return children.get(0).toString();
                }
            }
        } catch (Exception ignore) {
        }
        return baseDir;
    }

    private static boolean isValidCmakeCommand(String cmd) {
        if (cmd == null || cmd.trim().isEmpty()) return false;
        String normalized = cmd.trim();
        return normalized.matches(".*-B\\s*=?build.*") || normalized.contains("{{BUILD_DIR}}");
    }

    private static boolean isValidBuildCommand(String cmd) {
        if (cmd == null || cmd.trim().isEmpty()) return false;
        String normalized = cmd.trim();
        return normalized.matches(".*--build\\s*=?build.*") || normalized.contains("{{BUILD_DIR}}");
    }

    private static String sanitizeDirName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        name = name.trim();
        name = name.replaceAll("[<>:\"/\\\\|?*]", "_");
        name = name.replaceAll("\\.{2,}", "_");
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            return null;
        }
        return name;
    }

    private static boolean handleCorsOptions(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (request.method() != HttpMethod.OPTIONS) {
            return false;
        }
        LlamaServer.sendCorsResponse(ctx);
        return true;
    }

    private static byte[] readRequestBodyOrSendError(ChannelHandlerContext ctx, FullHttpRequest request) {
        byte[] content = JsonUtil.readRequestBytes(request);
        if (content == null || content.length == 0) {
            LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_BODY_EMPTY);
            return null;
        }
        return content;
    }

    private static JsonObject parseJsonObjectOrSendError(ChannelHandlerContext ctx, byte[] content) {
        JsonObject obj = JsonUtil.fromJson(content, JsonObject.class);
        if (obj == null) {
            LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_BODY_PARSE);
            return null;
        }
        return obj;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
