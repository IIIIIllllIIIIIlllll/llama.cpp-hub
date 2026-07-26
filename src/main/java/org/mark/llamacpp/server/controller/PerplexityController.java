package org.mark.llamacpp.server.controller;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.NodeManager;
import org.mark.llamacpp.server.NodeManager.StreamResult;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.service.PerplexityService;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.CharsetUtil;

/**
 * 困惑度（Perplexity）测试控制器。
 */
public class PerplexityController implements BaseController {

	private static final Logger logger = LoggerFactory.getLogger(PerplexityController.class);

	private static final String I18N_METHOD_POST_ONLY = "common.method.post.only";
	private static final String I18N_METHOD_GET_ONLY = "common.method.get.only";
	private static final String I18N_METHOD_GET_DELETE_ONLY = "common.method.get.delete.only";
	private static final String I18N_BODY_EMPTY = "api.error.body.empty";
	private static final String I18N_BODY_PARSE = "api.error.body.parse";
	private static final String I18N_REMOTE_CALL_FAILED = "api.error.remote.call.failed";
	private static final String I18N_PARAM_MODELID_REQUIRED = "api.error.param.modelId.required";
	private static final String I18N_PARAM_LLAMABINPATH_REQUIRED = "api.error.param.llamaBinPath.required";
	private static final String I18N_PERPLEXITY_RECORDS_LIST_FAILED = "api.error.perplexity.records.list.failed";
	private static final String I18N_PERPLEXITY_FILENAME_MISSING = "api.error.perplexity.filename.missing";
	private static final String I18N_PERPLEXITY_FILENAME_INVALID = "api.error.perplexity.filename.invalid";
	private static final String I18N_PERPLEXITY_FILE_NOTFOUND = "api.error.perplexity.file.notfound";
	private static final String I18N_PERPLEXITY_RECORD_PARSE_FAILED = "api.error.perplexity.record.parse.failed";
	private static final String I18N_PERPLEXITY_RECORD_READ_FAILED = "api.error.perplexity.record.read.failed";
	private static final String I18N_PERPLEXITY_RECORD_DELETE_FAILED = "api.error.perplexity.record.delete.failed";
	private static final String I18N_PERPLEXITY_REMOTE_RECORD_FAILED = "api.error.perplexity.remote.record.failed";
	private static final String I18N_UNKNOWN = "api.error.unknown";
	private static final String I18N_NODE_NOTFOUND = "api.error.node.notfound";

	private final PerplexityService perplexityService = new PerplexityService();
	private final ConcurrentHashMap<ChannelHandlerContext, Process> activeProcesses = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<ChannelHandlerContext, StreamResult> activeRemoteCalls = new ConcurrentHashMap<>();

	@Override
	public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		String path = uri;
		int q = uri.indexOf('?');
		if (q >= 0) {
			path = uri.substring(0, q);
		}
		if ("/api/perplexity/run".equals(path)) {
			handleRun(ctx, request);
			return true;
		}
		if ("/api/perplexity/records".equals(path)) {
			handleListRecords(ctx, request);
			return true;
		}
		if (path.startsWith("/api/perplexity/records/")) {
			// 路径格式: /api/perplexity/records/{fileName}，nodeId 从查询参数读取
			String fileName = path.substring("/api/perplexity/records/".length());
			if (!fileName.isEmpty()) {
				handleRecordByFilename(ctx, request, fileName);
				return true;
			}
		}
		return false;
	}

	private void handleRun(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);

		byte[] content = JsonUtil.readRequestBytes(request);
		if (content == null || content.length == 0) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_EMPTY));
			return;
		}

		JsonObject json;
		try {
			json = JsonUtil.fromJson(content, JsonObject.class);
		} catch (Exception e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_PARSE + ": " + e.getMessage()));
			return;
		}
		if (json == null) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_PARSE));
			return;
		}

		String modelId = JsonUtil.getJsonString(json, "modelId", "").trim();
		String llamaBinPath = JsonUtil.getJsonString(json, "llamaBinPath", "").trim();

		if (modelId.isEmpty()) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_MODELID_REQUIRED));
			return;
		}
		if (llamaBinPath.isEmpty()) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_LLAMABINPATH_REQUIRED));
			return;
		}

		String nodeId = JsonUtil.getJsonString(json, "nodeId", "").trim();
		if (!nodeId.isEmpty() && !"local".equals(nodeId)) {
			handleRemoteRun(ctx, json, nodeId);
			return;
		}

		// 发送流式响应头
		HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
		response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
		LlamaServer.setCorsHeaders(response.headers());
		ctx.writeAndFlush(response);

		try {
			perplexityService.run(ctx, json, activeProcesses);
		} catch (Exception e) {
			logger.info("启动困惑度测试失败", e);
			sendErrorAndClose(ctx, e.getMessage());
		}
	}

	private void handleRemoteRun(ChannelHandlerContext ctx, JsonObject json, String nodeId) {
		// 转发给远程节点时移除 nodeId，让远程节点执行本地运行
		JsonObject forwarded = json.deepCopy();
		forwarded.remove("nodeId");

		StreamResult result = NodeManager.getInstance().callRemoteApiStreaming(
				nodeId, "POST", "api/perplexity/run", forwarded,
				Collections.emptyMap(), 0);

		if (!result.isSuccess()) {
			result.abort();
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(
					I18N_REMOTE_CALL_FAILED + " (HTTP " + result.getStatusCode() + ")"));
			return;
		}

		activeRemoteCalls.put(ctx, result);

		HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
		response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
		LlamaServer.setCorsHeaders(response.headers());
		ctx.writeAndFlush(response);

		// 在独立线程中读取远程响应并转发给前端
		Thread.startVirtualThread(() -> {
			byte[] buffer = new byte[8192];
			try (InputStream in = result.getBody()) {
				int len;
				while ((len = in.read(buffer)) != -1) {
					if (!ctx.channel().isActive()) {
						break;
					}
					ctx.writeAndFlush(new DefaultHttpContent(Unpooled.copiedBuffer(buffer, 0, len)));
				}
			} catch (Exception e) {
				logger.debug("远程困惑度测试流读取结束: {}", e.getMessage());
			} finally {
				activeRemoteCalls.remove(ctx);
				result.abort();
				if (ctx.channel().isActive()) {
					ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
							.addListener(ChannelFutureListener.CLOSE);
				}
			}
		});
	}

	@SuppressWarnings("unchecked")
	private void handleListRecords(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, I18N_METHOD_GET_ONLY);
		try {
			List<Map<String, Object>> records = new ArrayList<>();
			// 读取本地记录
			File dir = new File("benchmarks");
			if (dir.exists() && dir.isDirectory()) {
				File[] all = dir.listFiles();
				if (all != null) {
					Arrays.sort(all, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
					SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
					for (File f : all) {
						String name = f.getName();
						if (!f.isFile() || !name.startsWith("PPL_") || !name.endsWith(".json")) {
							continue;
						}
						try {
							String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
							JsonObject obj = JsonUtil.fromJson(content, JsonObject.class);
							if (obj == null) {
								continue;
							}
							String recordNode = obj.has("nodeId") ? obj.get("nodeId").getAsString() : "local";
							Map<String, Object> info = new HashMap<>();
							info.put("fileName", name);
							info.put("fileNameWithNode", recordNode + "/" + name);
							info.put("modelId", obj.has("modelId") ? obj.get("modelId").getAsString() : "");
							info.put("nodeId", recordNode);
							info.put("timestamp", obj.has("timestamp") ? obj.get("timestamp").getAsString() : fmt.format(new Date(f.lastModified())));
							if (obj.has("ppl") && !obj.get("ppl").isJsonNull()) {
								info.put("ppl", obj.get("ppl").getAsDouble());
							}
							if (obj.has("uncertainty") && !obj.get("uncertainty").isJsonNull()) {
								info.put("uncertainty", obj.get("uncertainty").getAsDouble());
							}
							info.put("exitCode", obj.has("exitCode") ? obj.get("exitCode").getAsInt() : -1);
							info.put("elapsedMs", obj.has("elapsedMs") ? obj.get("elapsedMs").getAsLong() : 0);
							records.add(info);
						} catch (Exception e) {
							logger.debug("解析困惑度记录失败: {}", name, e);
						}
					}
				}
			}

			// 聚合远程节点记录（仅在 master 节点）
			if (LlamaServer.isMasterNode()) {
				List<org.mark.llamacpp.server.LlamaHubNode> remoteNodes = NodeManager.getInstance().listEnabledNodes();
				if (!remoteNodes.isEmpty()) {
					try {
						Map<String, Object> remoteRecords = fetchRemoteRecords(remoteNodes);
						List<Map<String, Object>> remoteRecordList = (List<Map<String, Object>>) remoteRecords.get("records");
						if (remoteRecordList != null) {
							records.addAll(remoteRecordList);
						}
						// 按时间戳降序排序所有记录
						records.sort((a, b) -> {
							String ta = (String) a.get("timestamp");
							String tb = (String) b.get("timestamp");
							if (ta == null) ta = "";
							if (tb == null) tb = "";
							return tb.compareTo(ta);
						});
					} catch (Exception e) {
						logger.warn("获取远程节点困惑度记录失败: {}", e.getMessage());
					}
				}
			}

			Map<String, Object> data = new HashMap<>();
			data.put("records", records);
			data.put("count", records.size());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PERPLEXITY_RECORDS_LIST_FAILED + ": " + e.getMessage()));
		}
	}

	/**
	 * 从远程节点获取困惑度测试记录（并行请求）
	 */
	@SuppressWarnings("unchecked")
	private Map<String, Object> fetchRemoteRecords(List<org.mark.llamacpp.server.LlamaHubNode> remoteNodes) {
		Map<String, Object> result = new HashMap<>();
		List<Map<String, Object>> allRecords = new ArrayList<>();

		// 并行获取所有远程节点的记录
		List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();
		for (org.mark.llamacpp.server.LlamaHubNode node : remoteNodes) {
			futures.add(CompletableFuture.supplyAsync(() -> {
				String currentNodeId = node.getNodeId();
				Map<String, Object> nodeRecords = new HashMap<>();
				try {
					NodeManager.StreamResult sr = NodeManager.getInstance().callRemoteApiStreaming(
							currentNodeId, "GET", "api/perplexity/records", (JsonObject) null, Collections.emptyMap(), 10000);
					if (!sr.isSuccess()) {
						logger.debug("远程节点 {} 调用失败: HTTP {}", currentNodeId, sr.getStatusCode());
						return nodeRecords;
					}
					try (InputStream in = sr.getBody()) {
						byte[] bytes = in.readAllBytes();
						String jsonStr = new String(bytes, StandardCharsets.UTF_8);
						JsonObject obj = JsonUtil.fromJson(jsonStr, JsonObject.class);
						if (obj != null && obj.has("success") && obj.get("success").getAsBoolean()) {
							JsonObject data = obj.getAsJsonObject("data");
							if (data != null && data.has("records") && data.get("records").isJsonArray()) {
								var recordArr = data.getAsJsonArray("records");
								// 转为列表，清洗数据：nodeId 始终用当前远程节点 ID，清理文件名前缀
								List<Map<String, Object>> records = new ArrayList<>();
								for (var elem2 : recordArr) {
									JsonObject r2 = elem2.getAsJsonObject();
									Map<String, Object> m = new HashMap<>();
									String rawName = r2.get("fileName").getAsString();
									// 清理 : 和 / 前缀，保留纯文件名
									String pureName = rawName;
									int colonIdx = pureName.indexOf(':');
									if (colonIdx > 0) pureName = pureName.substring(colonIdx + 1);
									int slashIdx = pureName.indexOf('/');
									if (slashIdx >= 0) pureName = pureName.substring(slashIdx + 1);
									// nodeId 必须用 currentNodeId，不读取记录的 nodeId（可能错误）
									m.put("fileName", pureName);
									m.put("fileNameWithNode", currentNodeId + "/" + pureName);
									m.put("modelId", r2.has("modelId") ? r2.get("modelId").getAsString() : "");
									m.put("nodeId", currentNodeId);
									m.put("timestamp", r2.has("timestamp") ? r2.get("timestamp").getAsString() : "");
									if (r2.has("ppl") && !r2.get("ppl").isJsonNull()) {
										m.put("ppl", r2.get("ppl").getAsDouble());
									}
									if (r2.has("uncertainty") && !r2.get("uncertainty").isJsonNull()) {
										m.put("uncertainty", r2.get("uncertainty").getAsDouble());
									}
									m.put("exitCode", r2.has("exitCode") ? r2.get("exitCode").getAsInt() : -1);
									m.put("elapsedMs", r2.has("elapsedMs") ? r2.get("elapsedMs").getAsLong() : 0);
									records.add(m);
								}
								nodeRecords.put("records", records);
							}
						}
					} finally {
						sr.abort();
					}
				} catch (Exception e) {
					logger.debug("读取远程节点 {} 困惑度记录失败: {}", currentNodeId, e.getMessage());
				}
				return nodeRecords;
			}));
		}
		// 等待所有结果
		for (CompletableFuture<Map<String, Object>> f : futures) {
			try {
				Map<String, Object> nodeRecords = f.get(30, TimeUnit.SECONDS);
				List<Map<String, Object>> recs = (List<Map<String, Object>>) nodeRecords.get("records");
				if (recs != null) {
					allRecords.addAll(recs);
				}
			} catch (Exception e) {
				logger.debug("等待远程记录结果超时或失败: {}", e.getMessage());
			}
		}

		result.put("records", allRecords);
		return result;
	}

	private void handleRecordByFilename(ChannelHandlerContext ctx, FullHttpRequest request, String fileName)
			throws RequestMethodException {
		if (fileName == null || fileName.isEmpty()) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PERPLEXITY_FILENAME_MISSING));
			return;
		}
		if (!fileName.matches("[a-zA-Z0-9._\\-]+") || !fileName.startsWith("PPL_") || !fileName.endsWith(".json")) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PERPLEXITY_FILENAME_INVALID));
			return;
		}

		// 从查询参数读取 nodeId，缺省为 local
		String nodeId = "local";
		String query = request.uri();
		int qIdx = query.indexOf('?');
		if (qIdx > 0) {
			String queryString = query.substring(qIdx + 1);
			String[] params = queryString.split("&");
			for (String param : params) {
				if (param.startsWith("nodeId=")) {
					nodeId = param.substring("nodeId=".length());
					break;
				}
			}
		}

		// 本地文件处理
		if ("local".equalsIgnoreCase(nodeId)) {
			if (request.method() == HttpMethod.GET) {
				handleGetRecord(ctx, fileName);
			} else if (request.method() == HttpMethod.DELETE) {
				handleDeleteRecord(ctx, fileName);
			} else {
				this.assertRequestMethod(true, I18N_METHOD_GET_DELETE_ONLY);
			}
			return;
		}

		// 远程节点：验证节点存在
		var node = NodeManager.getInstance().getNode(nodeId);
		if (node == null) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_NODE_NOTFOUND + ": " + nodeId));
			return;
		}

		// 转发到远程节点
		if (request.method() == HttpMethod.GET) {
			handleForwardRecord(ctx, nodeId, fileName, "GET");
		} else if (request.method() == HttpMethod.DELETE) {
			handleForwardRecord(ctx, nodeId, fileName, "DELETE");
		} else {
			this.assertRequestMethod(true, I18N_METHOD_GET_DELETE_ONLY);
		}
	}

	private void handleGetRecord(ChannelHandlerContext ctx, String fileName) {
		try {
			File dir = new File("benchmarks");
			File target = new File(dir, fileName);
			if (!target.exists() || !target.isFile()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PERPLEXITY_FILE_NOTFOUND));
				return;
			}
			String content = new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8);
			JsonObject obj = JsonUtil.fromJson(content, JsonObject.class);
			if (obj == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PERPLEXITY_RECORD_PARSE_FAILED));
				return;
			}
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(obj));
		} catch (Exception e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PERPLEXITY_RECORD_READ_FAILED + ": " + e.getMessage()));
		}
	}

	private void handleDeleteRecord(ChannelHandlerContext ctx, String fileName) {
		try {
			File dir = new File("benchmarks");
			File jsonFile = new File(dir, fileName);
			if (!jsonFile.exists() || !jsonFile.isFile()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PERPLEXITY_FILE_NOTFOUND));
				return;
			}
			Files.delete(jsonFile.toPath());
			String txtName = fileName.substring(0, fileName.length() - ".json".length()) + ".txt";
			File txtFile = new File(dir, txtName);
			if (txtFile.isFile()) {
				try {
					Files.delete(txtFile.toPath());
				} catch (Exception ignored) {
				}
			}
			Map<String, Object> data = new HashMap<>();
			data.put("fileName", fileName);
			data.put("deleted", true);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PERPLEXITY_RECORD_DELETE_FAILED + ": " + e.getMessage()));
		}
	}

	private void handleForwardRecord(ChannelHandlerContext ctx, String nodeId, String fileName, String method) {
		try {
			String remotePath = "api/perplexity/records/" + java.net.URLEncoder.encode(fileName, StandardCharsets.UTF_8)
					+ "?nodeId=local";
			NodeManager.StreamResult sr = NodeManager.getInstance().callRemoteApiStreaming(
					nodeId, method, remotePath,
					(JsonObject) null, Collections.emptyMap(), 10000);
			if (!sr.isSuccess()) {
				sr.abort();
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_CALL_FAILED + " (HTTP " + sr.getStatusCode() + ")"));
				return;
			}
			try (InputStream in = sr.getBody()) {
				byte[] bytes = in.readAllBytes();
				String jsonStr = new String(bytes, StandardCharsets.UTF_8);
				JsonObject obj = JsonUtil.fromJson(jsonStr, JsonObject.class);
				if (obj != null && obj.has("success") && obj.get("success").getAsBoolean()) {
					JsonObject data = obj.getAsJsonObject("data");
					// 还原 fileName，添加 nodeId/ 前缀，以便前端再次操作
					if (data != null && data.has("fileName")) {
						String originalFileName = data.get("fileName").getAsString();
						data.addProperty("fileName", nodeId + "/" + originalFileName);
					}
					LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
				} else {
					String error = obj != null && obj.has("error") ? obj.get("error").getAsString() : I18N_UNKNOWN;
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error(error));
				}
			} finally {
				sr.abort();
			}
		} catch (Exception e) {
			String action = "GET".equals(method) ? "读取" : "删除";
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(action + I18N_PERPLEXITY_REMOTE_RECORD_FAILED + ": " + e.getMessage()));
		}
	}

	private void sendErrorAndClose(ChannelHandlerContext ctx, String message) {
		if (!ctx.channel().isActive()) {
			return;
		}
		String errorLine = JsonUtil.toJson(
				java.util.Map.of("type", "error", "text", message)) + System.lineSeparator();
		byte[] errorBytes = errorLine.getBytes(CharsetUtil.UTF_8);
		ctx.writeAndFlush(new DefaultHttpContent(Unpooled.copiedBuffer(errorBytes)))
				.addListener(f -> ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
						.addListener(ChannelFutureListener.CLOSE));
	}

	@Override
	public void inactive(ChannelHandlerContext ctx) {
		Process process = activeProcesses.remove(ctx);
		if (process != null && process.isAlive()) {
			logger.info("客户端断开连接，终止困惑度测试进程");
			org.mark.llamacpp.server.tools.CommandLineRunner.destroyProcessTree(process);
		}
		StreamResult remote = activeRemoteCalls.remove(ctx);
		if (remote != null) {
			logger.info("客户端断开连接，中止远程困惑度测试调用");
			remote.abort();
		}
	}
}
