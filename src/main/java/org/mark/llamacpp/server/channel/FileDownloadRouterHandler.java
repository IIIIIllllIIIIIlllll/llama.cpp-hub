package org.mark.llamacpp.server.channel;


import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import org.mark.file.downloader.DownloadTaskInfo;
import org.mark.file.downloader.DownloadTaskManager;
import org.mark.file.downloader.DownloadTaskStatus;
import org.mark.file.downloader.ModelDownloadRequest;
import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.NodeManager;
import org.mark.llamacpp.server.tools.JsonUtil;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.stream.ChunkedFile;
import io.netty.util.ReferenceCountUtil;

/**
 * 模型下载API路由处理器
 */
public class FileDownloadRouterHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    
    private static final String I18N_PATH_INVALID = "api.error.path.invalid";
    private static final String I18N_METHOD_POST_ONLY = "common.method.post.only";
    private static final String I18N_METHOD_GET_ONLY = "common.method.get.only";
    private static final String I18N_BODY_EMPTY = "api.error.body.empty";
    private static final String I18N_BODY_PARSE = "api.error.body.parse";
    private static final String I18N_PARAM_PATH_EMPTY = "api.error.param.path.empty";
    private static final String I18N_PARAM_MODEL_ID_MISSING = "api.error.param.modelId.missing";
    private static final String I18N_FILE_NOT_FOUND = "api.error.file.notfound";
    private static final String I18N_FILE_NAME_INVALID = "api.error.file.name.invalid";
    private static final String I18N_DOWNLOAD_PARAM_AUTHOR_EMPTY = "api.error.download.param.author.empty";
    private static final String I18N_DOWNLOAD_PARAM_URL_EMPTY = "api.error.download.param.url.empty";
    private static final String I18N_DOWNLOAD_PARAM_AUTHOR_INVALID = "api.error.download.param.author.invalid";
    private static final String I18N_DOWNLOAD_PARAM_MODEL_ID_INVALID = "api.error.download.param.modelId.invalid";
    private static final String I18N_DOWNLOAD_PARAM_PATH_INVALID = "api.error.download.param.path.invalid";
    private static final String I18N_DOWNLOAD_PATH_EXISTS_NOT_DIR = "api.error.download.path.exists.not.dir";
    private static final String I18N_DOWNLOAD_DIR_NOT_EMPTY = "api.error.download.dir.not.empty";
    private static final String I18N_DOWNLOAD_DIR_CHECK_FAILED = "api.error.download.dir.check.failed";
    private static final String I18N_DOWNLOAD_PARAM_URL_CONTAINS_EMPTY = "api.error.download.param.url.contains.empty";
    private static final String I18N_DOWNLOAD_MODEL_CREATE_FAILED = "api.error.download.model.create.failed";
    private static final String I18N_DOWNLOAD_LIST_FAILED = "api.error.download.list.failed";
    private static final String I18N_DOWNLOAD_CREATE_FAILED = "api.error.download.create.failed";
    private static final String I18N_DOWNLOAD_PARAM_TASKID_EMPTY = "api.error.download.param.taskId.empty";
    private static final String I18N_DOWNLOAD_PAUSE_FAILED = "api.error.download.pause.failed";
    private static final String I18N_DOWNLOAD_RESUME_FAILED = "api.error.download.resume.failed";
    private static final String I18N_DOWNLOAD_DELETE_FAILED = "api.error.download.delete.failed";
    private static final String I18N_DOWNLOAD_TASK_NOTFOUND = "api.error.download.task.notfound";
    private static final String I18N_DOWNLOAD_STATS_FAILED = "api.error.download.stats.failed";
    private static final String I18N_DOWNLOAD_FILENAME_INFER_FAILED = "api.error.download.filename.infer.failed";
    private static final String I18N_DOWNLOAD_FOLDERNAME_INVALID = "api.error.download.foldername.invalid";
    private static final String I18N_DOWNLOAD_FILE_EXISTS = "api.error.download.file.exists";
    private static final String I18N_DOWNLOAD_CREATE_SUCCESS = "api.error.download.create.success";
    private static final String I18N_DOWNLOAD_TASK_PAUSED = "api.error.download.task.paused";
    private static final String I18N_DOWNLOAD_TASK_RESUMED = "api.error.download.task.resumed";
    private static final String I18N_DOWNLOAD_TASK_DELETED = "api.error.download.task.deleted";
    private static final String I18N_DOWNLOAD_VERSION_IN_QUEUE = "api.error.download.version.in.queue";
    private static final String I18N_DOWNLOAD_VERSION_COMPLETED = "api.error.download.version.completed";
    private static final String I18N_DOWNLOAD_PATH_DECODE_FAILED = "api.error.download.path.decode.failed";
    private static final String I18N_DOWNLOAD_FILE_READ_FAILED = "api.error.download.file.read.failed";
    private static final String I18N_DOWNLOAD_STREAM_FAILED = "api.error.download.stream.failed";

    private static final ConcurrentHashMap<String, ReentrantLock> downloadLocks = new ConcurrentHashMap<>();

	private static final ExecutorService async = Executors.newVirtualThreadPerTaskExecutor();
    
    /**
     * 	空的构造器。
     */
    public FileDownloadRouterHandler() {
    	
    }
    
	@Override
	protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
		FullHttpRequest retained = request.retainedDuplicate();
		async.execute(() -> {
			try {
				this.handleRequest(ctx, retained);
			} finally {
				ReferenceCountUtil.release(retained);
			}
		});
	}

	private void handleRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		// 处理CORS
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		String uri = request.uri();
		// 解析路径
		String[] pathParts = uri.split("/");
		if (pathParts.length < 2) {
			LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_PATH_INVALID);
			return;
		}
		// 列出全部的下载任务
		if (uri.startsWith("/api/downloads/list")) {
			this.handleListDownloads(ctx);
			return;
		}
		// 创建下载任务
		if (uri.startsWith("/api/downloads/create")) {
			this.handleCreateDownload(ctx, request);
			return;
		}
		// 创建模型下载任务
		if (uri.startsWith("/api/downloads/model/create")) {
			this.handleModelDownload(ctx, request);
			return;
		}
		
		// 暂停指定的下载任务
		if (uri.startsWith("/api/downloads/pause")) {
			this.handlePauseDownload(ctx, request);
			return;
		}
		// 恢复下载任务
		if (uri.startsWith("/api/downloads/resume")) {
			this.handleResumeDownload(ctx, request);
			return;
		}
		// 删除下载任务
		if (uri.startsWith("/api/downloads/delete")) {
			this.handleDeleteDownload(ctx, request);
			return;
		}
		// 获取状态
		if (uri.startsWith("/api/downloads/stats")) {
			this.handleGetStats(ctx);
			return;
		}
		// 文件流式下载
		if (uri.startsWith("/api/downloads/stream")) {
			this.handleStreamFile(ctx, request);
			return;
		}
		ctx.fireChannelRead(request.retain());
	}
	
	
	/**
	 * 	处理模型下载的请求。
	 * @param ctx
	 * @param request
	 */
	private void handleModelDownload(ChannelHandlerContext ctx, FullHttpRequest request) {
		if (request.method() != HttpMethod.POST) {
			LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_METHOD_POST_ONLY);
			return;
		}
		try {
			byte[] content = JsonUtil.readRequestBytes(request);
			if (content == null || content.length == 0) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_BODY_EMPTY);
				return;
			}
			ModelDownloadRequest req = JsonUtil.fromJson(content, ModelDownloadRequest.class);
			if (req == null) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_BODY_PARSE);
				return;
			}
			String author = trimToNull(req.getAuthor());
			String modelId = trimToNull(req.getModelId());
			String[] downloadUrl = req.getDownloadUrl();
			String ggufPath = trimToNull(req.getPath());
			if (author == null) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_DOWNLOAD_PARAM_AUTHOR_EMPTY);
				return;
			}
			if (modelId == null) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_PARAM_MODEL_ID_MISSING);
				return;
			}
			if (downloadUrl == null || downloadUrl.length == 0) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_DOWNLOAD_PARAM_URL_EMPTY);
				return;
			}
			String safeAuthor = sanitizePathSegment(author);
			String safeModelId = sanitizePathSegment(modelId);
			if (safeAuthor.isBlank()) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_DOWNLOAD_PARAM_AUTHOR_INVALID);
				return;
			}
			if (safeModelId.isBlank()) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_DOWNLOAD_PARAM_MODEL_ID_INVALID);
				return;
			}

			Path baseDir = Paths.get(LlamaServer.getDefaultModelsPath()).toAbsolutePath().normalize();
			Path modelRootDir = baseDir.resolve(safeAuthor).resolve(safeModelId).toAbsolutePath().normalize();
			if (!modelRootDir.startsWith(baseDir)) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_DOWNLOAD_PARAM_PATH_INVALID);
				return;
			}
			if (Files.exists(modelRootDir) && !Files.isDirectory(modelRootDir)) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.CONFLICT, I18N_DOWNLOAD_PATH_EXISTS_NOT_DIR);
				return;
			}
			if (!Files.exists(modelRootDir)) {
				Files.createDirectories(modelRootDir);
			}

			String folderName = null;
			if (ggufPath != null) {
				folderName = normalizeVariantFolderName(ggufPath);
			}
			if (folderName == null) {
				folderName = normalizeVariantFolderName(req.getName());
			}
			folderName = sanitizePathSegment(folderName);
			if (folderName.isBlank()) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_DOWNLOAD_PARAM_PATH_INVALID);
				return;
			}

			Path targetDir = modelRootDir.resolve(folderName).toAbsolutePath().normalize();
			Path modelLeaf = modelRootDir.getFileName();
			if (modelLeaf != null && modelLeaf.toString().equalsIgnoreCase(folderName)) {
				targetDir = modelRootDir;
			}
			if (!targetDir.startsWith(modelRootDir)) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_DOWNLOAD_PARAM_PATH_INVALID);
				return;
			}
			if (Files.exists(targetDir) && !Files.isDirectory(targetDir)) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.CONFLICT, I18N_DOWNLOAD_PATH_EXISTS_NOT_DIR);
				return;
			}
			if (Files.exists(targetDir)) {
				try (Stream<Path> entries = Files.list(targetDir)) {
					if (entries.findAny().isPresent()) {
						LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.CONFLICT, I18N_DOWNLOAD_DIR_NOT_EMPTY);
						return;
					}
				} catch (IOException e) {
					LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, I18N_DOWNLOAD_DIR_CHECK_FAILED + ": " + e.getMessage());
					return;
				}
			} else {
				Files.createDirectories(targetDir);
			}
			
			List<Map<String, Object>> taskResults = new ArrayList<>();
			boolean allSuccess = true;
			for (int i = 0; i < downloadUrl.length; i++) {
				String url = trimToNull(downloadUrl[i]);
				if (url == null) {
					allSuccess = false;
					Map<String, Object> r = new HashMap<>();
					r.put("success", false);
					r.put("error", I18N_DOWNLOAD_PARAM_URL_CONTAINS_EMPTY);
					taskResults.add(r);
					continue;
				}
				Map<String, Object> r = createAndStartTaskDirect(url, targetDir.toString(), null);
				if (!Boolean.TRUE.equals(r.get("success"))) {
					allSuccess = false;
				}
				taskResults.add(r);
			}

			Map<String, Object> resp = new HashMap<>();
			resp.put("success", allSuccess);
			resp.put("path", targetDir.toString());
			resp.put("tasks", taskResults);
			LlamaServer.sendJsonResponse(ctx, resp);
		} catch (Exception e) {
			e.printStackTrace();
			LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR,
					I18N_DOWNLOAD_MODEL_CREATE_FAILED + ": " + e.getMessage());
		}
	}

	private static String trimToNull(String s) {
		if (s == null) {
			return null;
		}
		String t = s.trim();
		return t.isEmpty() ? null : t;
	}

	private static String sanitizePathSegment(String segment) {
		if (segment == null) {
			return "";
		}
		String s = segment.trim();
		if (s.isEmpty()) {
			return "";
		}
		return s.replaceAll("[^a-zA-Z0-9-_\\.]", "_");
	}

	private static String normalizeVariantFolderName(String s) {
		String v = trimToNull(s);
		if (v == null) {
			return null;
		}
		try {
			v = Paths.get(v).getFileName().toString();
		} catch (Exception ignored) {
		}
		v = v.trim();
		v = v.replaceFirst("(?i)\\.gguf$", "");
		v = v.trim();
return v.isEmpty() ? null : v;
	}

	/**
	 * 	处理获取下载列表请求
	 * @param ctx
	 */
	private void handleListDownloads(ChannelHandlerContext ctx) {
		try {
			List<Map<String, Object>> downloads = new ArrayList<>();

			// Local tasks
			for (DownloadTaskInfo task : DownloadTaskManager.getInstance().listTasks()) {
				downloads.add(toTaskView(task));
			}

			// Remote tasks: proxy to all enabled nodes
			List<org.mark.llamacpp.server.LlamaHubNode> enabledNodes = NodeManager.getInstance().listEnabledNodes();
			for (org.mark.llamacpp.server.LlamaHubNode node : enabledNodes) {
				NodeManager.HttpResult remoteResult = NodeManager.getInstance()
						.callRemoteApi(node.getNodeId(), "GET", "api/downloads/list", null);
				if (remoteResult.isSuccess() && remoteResult.getBody() != null) {
					try {
						com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(remoteResult.getBody()).getAsJsonObject();
						com.google.gson.JsonArray remoteDownloads = root.getAsJsonArray("downloads");
						if (remoteDownloads != null) {
							String nodeId = node.getNodeId();
							String nodeName = node.getName() != null ? node.getName() : nodeId;
							for (int i = 0; i < remoteDownloads.size(); i++) {
								com.google.gson.JsonElement elem = remoteDownloads.get(i);
								if (!elem.isJsonObject()) continue;
								com.google.gson.JsonObject d = elem.getAsJsonObject();
								d.addProperty("nodeId", nodeId);
								d.addProperty("nodeName", nodeName);
								@SuppressWarnings("unchecked")
								Map<String, Object> taskView = JsonUtil.fromJson(d, Map.class);
								downloads.add(taskView);
							}
						}
					} catch (Exception e) {
						// skip malformed response
					}
				}
			}

			Map<String, Object> result = new HashMap<>();
			result.put("success", true);
			result.put("downloads", downloads);
			LlamaServer.sendJsonResponse(ctx, result);
		} catch (Exception e) {
			LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, I18N_DOWNLOAD_LIST_FAILED + ": " + e.getMessage());
		}
	}
    
	/**
	 * 	处理创建下载任务请求
	 * @param ctx
	 * @param request
	 */
	private void handleCreateDownload(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			byte[] content = JsonUtil.readRequestBytes(request);

			// Check for remote node
			com.google.gson.JsonObject json = JsonUtil.fromJson(content, com.google.gson.JsonObject.class);
			if (json != null) {
				String nodeId = JsonUtil.getJsonString(json, "nodeId");
				if (!nodeId.isEmpty() && !"local".equals(nodeId)) {
					json.remove("nodeId");
					NodeManager.HttpResult result = NodeManager.getInstance()
							.callRemoteApi(nodeId, "POST", "api/downloads/create", json);
					NodeManager.writeHttpResultToChannel(ctx, result, "download");
					return;
				}
			}

			@SuppressWarnings("unchecked")
			java.util.Map<String, Object> requestData = JsonUtil.fromJson(content, java.util.Map.class);

			String url = (String) requestData.get("url");
			String fileName = (String) requestData.get("fileName");
			String folderName = (String) requestData.get("folderName");
			String path = (String) requestData.get("path");

			if (url == null || url.trim().isEmpty()) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_DOWNLOAD_PARAM_URL_EMPTY);
				return;
			}

			// llama.cpp 下载：特殊处理
			if ("llamacpp".equalsIgnoreCase(path)) {
				var result = createAndStartLlamaCppTask(url, fileName);
				LlamaServer.sendJsonResponse(ctx, result);
				return;
			}

			var result = createAndStartTask(url, LlamaServer.getDownloadDirectory(), fileName, folderName);
			LlamaServer.sendJsonResponse(ctx, result);
		} catch (Exception e) {
			e.printStackTrace();
			LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, I18N_DOWNLOAD_CREATE_FAILED + ": " + e.getMessage());
		}
	}
    
	/**
	 * 	处理暂停下载任务请求
	 * @param ctx
	 * @param request
	 */
	private void handlePauseDownload(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			byte[] content = JsonUtil.readRequestBytes(request);
			com.google.gson.JsonObject json = JsonUtil.fromJson(content, com.google.gson.JsonObject.class);
			if (json != null) {
				String nodeId = JsonUtil.getJsonString(json, "nodeId");
				if (!nodeId.isEmpty() && !"local".equals(nodeId)) {
					json.remove("nodeId");
					NodeManager.HttpResult result = NodeManager.getInstance()
							.callRemoteApi(nodeId, "POST", "api/downloads/pause", json);
					NodeManager.writeHttpResultToChannel(ctx, result, "download");
					return;
				}
			}

			@SuppressWarnings("unchecked")
			java.util.Map<String, Object> requestData = JsonUtil.fromJson(content, java.util.Map.class);

			String taskId = (String) requestData.get("taskId");

			if (taskId == null || taskId.trim().isEmpty()) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_DOWNLOAD_PARAM_TASKID_EMPTY);
				return;
			}

			DownloadTaskManager.getInstance().pauseTask(taskId);
			Map<String, Object> result = new HashMap<>();
			result.put("success", true);
			result.put("taskId", taskId);
			result.put("message", I18N_DOWNLOAD_TASK_PAUSED);
			LlamaServer.sendJsonResponse(ctx, result);
		} catch (Exception e) {
			LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, I18N_DOWNLOAD_PAUSE_FAILED + ": " + e.getMessage());
		}
	}
    
	/**
	 * 	处理恢复下载任务请求
	 * @param ctx
	 * @param request
	 */
	private void handleResumeDownload(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			byte[] content = JsonUtil.readRequestBytes(request);
			com.google.gson.JsonObject json = JsonUtil.fromJson(content, com.google.gson.JsonObject.class);
			if (json != null) {
				String nodeId = JsonUtil.getJsonString(json, "nodeId");
				if (!nodeId.isEmpty() && !"local".equals(nodeId)) {
					json.remove("nodeId");
					NodeManager.HttpResult result = NodeManager.getInstance()
							.callRemoteApi(nodeId, "POST", "api/downloads/resume", json);
					NodeManager.writeHttpResultToChannel(ctx, result, "download");
					return;
				}
			}

			@SuppressWarnings("unchecked")
			java.util.Map<String, Object> requestData = JsonUtil.fromJson(content, java.util.Map.class);

			String taskId = (String) requestData.get("taskId");

			if (taskId == null || taskId.trim().isEmpty()) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_DOWNLOAD_PARAM_TASKID_EMPTY);
				return;
			}

			DownloadTaskInfo task = DownloadTaskManager.getInstance().startTask(taskId);
			Map<String, Object> result = new HashMap<>();
			result.put("success", true);
			result.put("taskId", task.getTaskId());
			result.put("message", I18N_DOWNLOAD_TASK_RESUMED);
			LlamaServer.sendJsonResponse(ctx, result);
		} catch (Exception e) {
			LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, I18N_DOWNLOAD_RESUME_FAILED + ": " + e.getMessage());
		}
	}
    
	/**
	 * 	处理删除下载任务请求
	 * @param ctx
	 * @param request
	 */
	private void handleDeleteDownload(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			byte[] content = JsonUtil.readRequestBytes(request);
			com.google.gson.JsonObject json = JsonUtil.fromJson(content, com.google.gson.JsonObject.class);
			if (json != null) {
				String nodeId = JsonUtil.getJsonString(json, "nodeId");
				if (!nodeId.isEmpty() && !"local".equals(nodeId)) {
					json.remove("nodeId");
					NodeManager.HttpResult result = NodeManager.getInstance()
							.callRemoteApi(nodeId, "POST", "api/downloads/delete", json);
					NodeManager.writeHttpResultToChannel(ctx, result, "download");
					return;
				}
			}

			@SuppressWarnings("unchecked")
			java.util.Map<String, Object> requestData = JsonUtil.fromJson(content, java.util.Map.class);

			String taskId = (String) requestData.get("taskId");

			if (taskId == null || taskId.trim().isEmpty()) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_DOWNLOAD_PARAM_TASKID_EMPTY);
				return;
			}

			Object deleteFileObj = requestData.get("deleteFile");
			boolean deleteLocalFile = !(deleteFileObj instanceof Boolean) || Boolean.TRUE.equals(deleteFileObj);
			boolean deleted = DownloadTaskManager.getInstance().deleteTask(taskId, deleteLocalFile);
			Map<String, Object> result = new HashMap<>();
			result.put("success", deleted);
			result.put("taskId", taskId);
			if (deleted) {
				result.put("message", I18N_DOWNLOAD_TASK_DELETED);
			} else {
				result.put("error", I18N_DOWNLOAD_TASK_NOTFOUND);
			}
			LlamaServer.sendJsonResponse(ctx, result);
		} catch (Exception e) {
			LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, I18N_DOWNLOAD_DELETE_FAILED + ": " + e.getMessage());
		}
	}
    
	/**
	 * 	处理获取下载统计信息请求
	 * @param ctx
	 */
	private void handleGetStats(ChannelHandlerContext ctx) {
		try {
			List<DownloadTaskInfo> tasks = DownloadTaskManager.getInstance().listTasks();
			long activeCount = tasks.stream().filter(t -> t.getStatus() == DownloadTaskStatus.RUNNING).count();
			long pendingCount = tasks.stream().filter(t -> t.getStatus() == DownloadTaskStatus.PENDING).count();
			long completedCount = tasks.stream().filter(t -> t.getStatus() == DownloadTaskStatus.COMPLETED).count();
			long failedCount = tasks.stream().filter(t -> t.getStatus() == DownloadTaskStatus.FAILED).count();

			// Aggregate stats from remote nodes
			List<org.mark.llamacpp.server.LlamaHubNode> enabledNodes = NodeManager.getInstance().listEnabledNodes();
			for (org.mark.llamacpp.server.LlamaHubNode node : enabledNodes) {
				NodeManager.HttpResult remoteResult = NodeManager.getInstance()
						.callRemoteApi(node.getNodeId(), "GET", "api/downloads/stats", null);
				if (remoteResult.isSuccess() && remoteResult.getBody() != null) {
					try {
						com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(remoteResult.getBody()).getAsJsonObject();
						com.google.gson.JsonObject remoteStats = root.getAsJsonObject("stats");
						if (remoteStats != null) {
							activeCount += getJsonLongSafe(remoteStats, "active");
							pendingCount += getJsonLongSafe(remoteStats, "pending");
							completedCount += getJsonLongSafe(remoteStats, "completed");
							failedCount += getJsonLongSafe(remoteStats, "failed");
						}
					} catch (Exception e) {
						// skip malformed response
					}
				}
			}

			Map<String, Object> stats = new HashMap<>();
			stats.put("active", activeCount);
			stats.put("pending", pendingCount);
			stats.put("completed", completedCount);
			stats.put("failed", failedCount);
			stats.put("total", activeCount + pendingCount + completedCount + failedCount);
			Map<String, Object> result = new HashMap<>();
			result.put("success", true);
			result.put("stats", stats);
			LlamaServer.sendJsonResponse(ctx, result);
		} catch (Exception e) {
			LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, I18N_DOWNLOAD_STATS_FAILED + ": " + e.getMessage());
		}
	}

	private long getJsonLongSafe(com.google.gson.JsonObject obj, String key) {
		try {
			if (obj.has(key) && !obj.get(key).isJsonNull()) {
				return obj.get(key).getAsLong();
			}
		} catch (Exception e) {
			// ignore
		}
		return 0;
	}

	private Map<String, Object> createAndStartTask(String url, String path, String fileName, String folderName) {
		Map<String, Object> result = new HashMap<>();
		try {
			String selectedName = trimToNull(fileName);
			if (selectedName == null) {
				selectedName = inferFileName(url);
			}
			if (selectedName == null || selectedName.isBlank()) {
				throw new IllegalArgumentException(I18N_DOWNLOAD_FILENAME_INFER_FAILED);
			}
			selectedName = selectedName.replaceAll("[<>:\"/\\\\|?*]", "_").trim();
			if (selectedName.isEmpty()) {
				throw new IllegalArgumentException(I18N_FILE_NAME_INVALID);
			}

			// Determine target folder
			String targetFolder = trimToNull(folderName);
			if (targetFolder == null) {
				// Auto-create folder from file name (without extension)
				targetFolder = selectedName;
				int dotIndex = selectedName.lastIndexOf('.');
				if (dotIndex > 0) {
					targetFolder = selectedName.substring(0, dotIndex);
				}
			} else {
				targetFolder = targetFolder.replaceAll("[<>:\"/\\\\|?*]", "_").trim();
			}
			if (targetFolder.isEmpty()) {
				throw new IllegalArgumentException(I18N_DOWNLOAD_FOLDERNAME_INVALID);
			}

			Path base = Paths.get(path);
			Path targetDir = base.resolve(targetFolder);
			Files.createDirectories(targetDir);

			Path targetFile = targetDir.resolve(selectedName).toAbsolutePath().normalize();
			String lockKey = targetFile.toString();
			ReentrantLock lock = downloadLocks.computeIfAbsent(lockKey, k -> new ReentrantLock());
			lock.lock();
			try {
				if (Files.exists(targetFile)) {
					result.put("success", false);
					result.put("error", I18N_DOWNLOAD_FILE_EXISTS + ": " + selectedName);
					return result;
				}
				DownloadTaskInfo created = DownloadTaskManager.getInstance().createTask(url, targetFile, 8);
				DownloadTaskManager.getInstance().startTask(created.getTaskId());
				result.put("success", true);
				result.put("taskId", created.getTaskId());
				result.put("message", I18N_DOWNLOAD_CREATE_SUCCESS);
			} finally {
				lock.unlock();
				downloadLocks.remove(lockKey, lock);
			}
		} catch (Exception e) {
			result.put("success", false);
			result.put("error", I18N_DOWNLOAD_CREATE_FAILED + ": " + e.getMessage());
		}
		return result;
	}

	private Map<String, Object> createAndStartTaskDirect(String url, String path, String fileName) {
		Map<String, Object> result = new HashMap<>();
		try {
			String selectedName = trimToNull(fileName);
			if (selectedName == null) {
				selectedName = inferFileName(url);
			}
			if (selectedName == null || selectedName.isBlank()) {
				throw new IllegalArgumentException(I18N_DOWNLOAD_FILENAME_INFER_FAILED);
			}
			selectedName = selectedName.replaceAll("[<>:\"/\\\\|?*]", "_").trim();
			if (selectedName.isEmpty()) {
				throw new IllegalArgumentException(I18N_FILE_NAME_INVALID);
			}

			Path targetDir = Paths.get(path).toAbsolutePath().normalize();
			Files.createDirectories(targetDir);

			Path targetFile = targetDir.resolve(selectedName).toAbsolutePath().normalize();
			if (!targetFile.startsWith(targetDir)) {
				throw new IllegalArgumentException(I18N_DOWNLOAD_PARAM_PATH_INVALID);
			}
			String lockKey = targetFile.toString();
			ReentrantLock lock = downloadLocks.computeIfAbsent(lockKey, k -> new ReentrantLock());
			lock.lock();
			try {
				if (Files.exists(targetFile)) {
					result.put("success", false);
					result.put("error", I18N_DOWNLOAD_FILE_EXISTS + ": " + selectedName);
					return result;
				}
				DownloadTaskInfo created = DownloadTaskManager.getInstance().createTask(url, targetFile, 8);
				DownloadTaskManager.getInstance().startTask(created.getTaskId());
				result.put("success", true);
				result.put("taskId", created.getTaskId());
				result.put("message", I18N_DOWNLOAD_CREATE_SUCCESS);
			} finally {
				lock.unlock();
				downloadLocks.remove(lockKey, lock);
			}
		} catch (Exception e) {
			result.put("success", false);
			result.put("error", I18N_DOWNLOAD_CREATE_FAILED + ": " + e.getMessage());
		}
		return result;
	}

	private Map<String, Object> createAndStartLlamaCppTask(String url, String fileName) {
		Map<String, Object> result = new HashMap<>();
		try {
			String selectedName = trimToNull(fileName);
			if (selectedName == null) {
				selectedName = inferFileName(url);
			}
			if (selectedName == null || selectedName.isBlank()) {
				throw new IllegalArgumentException(I18N_DOWNLOAD_FILENAME_INFER_FAILED);
			}
			selectedName = selectedName.replaceAll("[<>:\"/\\\\|?*]", "_").trim();
			if (selectedName.isEmpty()) {
				throw new IllegalArgumentException(I18N_FILE_NAME_INVALID);
			}

			// Check for existing task with the same URL
			for (DownloadTaskInfo existing : DownloadTaskManager.getInstance().listTasks()) {
				if (url.equals(existing.getSourceUrl())) {
					DownloadTaskStatus st = existing.getStatus();
					if (st == DownloadTaskStatus.RUNNING || st == DownloadTaskStatus.PENDING || st == DownloadTaskStatus.PAUSED) {
						result.put("success", false);
						result.put("error", I18N_DOWNLOAD_VERSION_IN_QUEUE);
						result.put("taskId", existing.getTaskId());
						return result;
					}
					if (st == DownloadTaskStatus.COMPLETED) {
						result.put("success", false);
						result.put("error", I18N_DOWNLOAD_VERSION_COMPLETED);
						result.put("taskId", existing.getTaskId());
						return result;
					}
					// FAILED — allow retry, continue to create new task
					break;
				}
			}

			// Derive backend directory name from the archive base name (strip .zip / .tar.gz)
			String baseName = selectedName;
			int dotIdx = baseName.lastIndexOf('.');
			if (dotIdx > 0) baseName = baseName.substring(0, dotIdx);
			if (baseName.endsWith(".tar")) {
				dotIdx = baseName.lastIndexOf('.');
				if (dotIdx > 0) baseName = baseName.substring(0, dotIdx);
			}

			Path llamacppDir = Paths.get("llamacpp").toAbsolutePath().normalize();
			Path backendDir = llamacppDir.resolve(baseName).toAbsolutePath().normalize();
			Files.createDirectories(backendDir);

			Path targetFile = backendDir.resolve(selectedName).toAbsolutePath().normalize();
			String lockKey = targetFile.toString();
			ReentrantLock lock = downloadLocks.computeIfAbsent(lockKey, k -> new ReentrantLock());
			lock.lock();
			try {
				if (Files.exists(targetFile)) {
					result.put("success", false);
					result.put("error", I18N_DOWNLOAD_FILE_EXISTS + ": " + selectedName);
					return result;
				}
				DownloadTaskInfo created = DownloadTaskManager.getInstance().createTask(url, targetFile, 8);
				String taskId = created.getTaskId();
				DownloadTaskManager.getInstance().startTask(taskId);
				result.put("success", true);
				result.put("taskId", taskId);
				result.put("message", I18N_DOWNLOAD_CREATE_SUCCESS);
			} finally {
				lock.unlock();
				downloadLocks.remove(lockKey, lock);
			}
		} catch (Exception e) {
			result.put("success", false);
			result.put("error", I18N_DOWNLOAD_CREATE_FAILED + ": " + e.getMessage());
		}
		return result;
	}

	private static String inferFileName(String url) {
		try {
			URI uri = URI.create(url);
			String path = uri.getPath();
			if (path == null || path.isBlank()) {
				return null;
			}
			String name = Paths.get(path).getFileName().toString();
			return trimToNull(name);
		} catch (Exception e) {
			return null;
		}
	}

	private Map<String, Object> toTaskView(DownloadTaskInfo task) {
		Map<String, Object> view = new HashMap<>();
		Path target = Path.of(task.getTargetPath());
		String fileName = target.getFileName() == null ? "" : target.getFileName().toString();
		String parentPath = target.getParent() == null ? "" : target.getParent().toString();
		view.put("taskId", task.getTaskId());
		view.put("url", task.getSourceUrl());
		view.put("targetPath", parentPath);
		view.put("fileName", fileName);
		view.put("state", mapState(task.getStatus()));
		view.put("totalBytes", task.getTotalBytes());
		view.put("downloadedBytes", task.getDownloadedBytes());
		view.put("partsTotal", task.getPartsTotal());
		view.put("partsCompleted", task.getPartsCompleted());
		view.put("progressRatio", task.getProgressRatio());
		view.put("createdAt", task.getCreatedAt());
		view.put("updatedAt", task.getUpdatedAt());
		if (task.getErrorMessage() != null) {
			view.put("errorMessage", task.getErrorMessage());
		}
		return view;
	}

	private String mapState(DownloadTaskStatus status) {
		return switch (status) {
		case RUNNING -> "DOWNLOADING";
		case PAUSED -> "PAUSED";
		case COMPLETED -> "COMPLETED";
		case FAILED -> "FAILED";
		case PENDING -> "IDLE";
		};
	}

	private void handleStreamFile(ChannelHandlerContext ctx, FullHttpRequest request) {
		if (request.method() != HttpMethod.GET) {
			LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_METHOD_GET_ONLY);
			return;
		}

		String pathParam = extractQueryParam(request.uri(), "path");
		if (pathParam == null || pathParam.trim().isEmpty()) {
			LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_PARAM_PATH_EMPTY);
			return;
		}

		String mode = extractQueryParam(request.uri(), "mode");
		String decodedPath;
		try {
			if ("base64".equals(mode)) {
				String padded = pathParam + "=".repeat((4 - pathParam.length() % 4) % 4);
				decodedPath = new String(java.util.Base64.getUrlDecoder().decode(padded), StandardCharsets.UTF_8);
			} else {
				decodedPath = URLDecoder.decode(pathParam, StandardCharsets.UTF_8);
			}
		} catch (IllegalArgumentException e) {
			LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_DOWNLOAD_PATH_DECODE_FAILED + ": " + e.getMessage());
			return;
		}

		try {
			Path filePath = Paths.get(decodedPath).toAbsolutePath().normalize();

			// 路径白名单校验：只允许访问下载目录和模型目录下的文件，防止任意文件读取
			Path downloadDir = Paths.get(LlamaServer.getDownloadDirectory()).toAbsolutePath().normalize();
			Path modelsDir = Paths.get(LlamaServer.getDefaultModelsPath()).toAbsolutePath().normalize();
			if (!filePath.startsWith(downloadDir) && !filePath.startsWith(modelsDir)) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.FORBIDDEN, I18N_DOWNLOAD_PARAM_PATH_INVALID);
				return;
			}

			if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.NOT_FOUND, I18N_FILE_NOT_FOUND + ": " + filePath);
				return;
			}

			String fileName = filePath.getFileName() == null ? "file" : filePath.getFileName().toString();
			long fileLength = Files.size(filePath);
			String contentType = inferContentType(fileName);

			RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r");

			HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
			response.headers().set(HttpHeaderNames.CONTENT_LENGTH, fileLength);
			response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
			response.headers().set(HttpHeaderNames.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"");
			// 使用统一的CORS设置，不在此处硬编码
			LlamaServer.setCorsHeaders(response.headers());

			ctx.write(response);
			ctx.write(new ChunkedFile(raf, 0, fileLength, 8192), ctx.newProgressivePromise());
			ChannelFuture last = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
			last.addListener(f -> {
				try {
					raf.close();
				} catch (Exception ignore) {
				}
				ctx.close();
			});
		} catch (IOException e) {
			LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, I18N_DOWNLOAD_FILE_READ_FAILED + ": " + e.getMessage());
		} catch (Exception e) {
			LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, I18N_DOWNLOAD_STREAM_FAILED + ": " + e.getMessage());
		}
	}

	private static String extractQueryParam(String uri, String param) {
		int queryIdx = uri.indexOf('?');
		if (queryIdx < 0) {
			return null;
		}
		String query = uri.substring(queryIdx + 1);
		String[] pairs = query.split("&");
		for (String pair : pairs) {
			int eqIdx = pair.indexOf('=');
			if (eqIdx < 0) {
				continue;
			}
			String key = pair.substring(0, eqIdx);
			if (key.equals(param)) {
				return pair.substring(eqIdx + 1);
			}
		}
		return null;
	}

	private static String inferContentType(String fileName) {
		if (fileName == null) {
			return "application/octet-stream";
		}
		String lower = fileName.toLowerCase();
		if (lower.endsWith(".gguf")) return "application/octet-stream";
		if (lower.endsWith(".json")) return "application/json";
		if (lower.endsWith(".txt")) return "text/plain; charset=UTF-8";
		if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html; charset=UTF-8";
		if (lower.endsWith(".css")) return "text/css; charset=UTF-8";
		if (lower.endsWith(".js")) return "application/javascript; charset=UTF-8";
		if (lower.endsWith(".png")) return "image/png";
		if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
		if (lower.endsWith(".gif")) return "image/gif";
		if (lower.endsWith(".svg")) return "image/svg+xml";
		if (lower.endsWith(".zip")) return "application/zip";
		if (lower.endsWith(".tar") || lower.endsWith(".gz")) return "application/gzip";
		if (lower.endsWith(".pdf")) return "application/pdf";
		if (lower.endsWith(".xml")) return "application/xml";
		if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return "text/yaml";
		if (lower.endsWith(".bin")) return "application/octet-stream";
		if (lower.endsWith(".exe")) return "application/octet-stream";
		return "application/octet-stream";
	}

}
