package org.mark.llamacpp.server.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.mark.llamacpp.server.LlamaCppProcess;
import org.mark.llamacpp.server.LlamaHubNode;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.NodeManager;
import org.mark.llamacpp.server.io.NettyWriteHelper;
import org.mark.llamacpp.server.struct.ActiveRequest;
import org.mark.llamacpp.server.struct.Timing;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;

/**
 * 	处理openai api请求的服务。
 */
public class OpenAIService {
	
	private static final Logger logger = LoggerFactory.getLogger(OpenAIService.class);

	/**
	 * 	存储当前通道正在处理的模型链接，用于在连接关闭时停止对应的模型进程
	 */
	private final Map<ChannelHandlerContext, HttpURLConnection> channelConnectionMap = new HashMap<>();
	
	/**
	 * 	线程池。
	 */
	private static final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();
	
	/**
	 * 	给响应头做时间转换
	 */
	private SimpleDateFormat sdf = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH);
	
	/**
	 * 	集霸矛！
	 */
	public OpenAIService() {
		
	}
	
	/**
	 * 	处理模型列表请求
	 * 	/api/models
	 * 	
	 * @param ctx
	 * @param request
	 */
	public void handleOpenAIModelsRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			if (request.method() != HttpMethod.GET) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 405, null, "Only GET method is supported", "method");
				return;
			}
			LlamaServerManager manager = LlamaServerManager.getInstance();
			Map<String, LlamaCppProcess> loaded = manager.getLoadedProcesses();

			Map<String, JsonObject> modelsByKey = new LinkedHashMap<>();
			Map<String, JsonObject> dataById = new LinkedHashMap<>();

			for (Map.Entry<String, LlamaCppProcess> e : loaded.entrySet()) {
				String modelId = e.getKey();
				if (modelId == null || modelId.isBlank()) {
					continue;
				}
				JsonObject capabilities = manager.getModelCapabilities(modelId);
				int runtimeCtx = e.getValue().getCtxSize();

				JsonObject info = manager.getLoadedModelInfo(modelId);
				if (info == null) {
					try {
						info = manager.handleModelInfo(modelId);
					} catch (Exception ignore) {
						info = null;
					}
				}
				if (info == null) {
					continue;
				}

				if (!info.has("items") || !info.get("items").isJsonArray()) {
					continue;
				}
				JsonArray items = info.getAsJsonArray("items");
				for (JsonElement itemEl : items) {
					if (itemEl == null || itemEl.isJsonNull() || !itemEl.isJsonObject()) {
						continue;
					}
					JsonObject item = itemEl.getAsJsonObject();

					if (item.has("model") && item.get("model").isJsonObject()) {
						JsonObject m = item.getAsJsonObject("model");
						String key = JsonUtil.getJsonString(m, "model");
						if (key.isEmpty()) {
							key = JsonUtil.getJsonString(m, "name");
						}
						if (!key.isEmpty() && !modelsByKey.containsKey(key)) {
							JsonObject mCopy = m.deepCopy();
							mCopy.addProperty("runtimeCtx", runtimeCtx);
							mCopy.add("my_capabilities", capabilities);
							modelsByKey.put(key, mCopy);
						}
					}

					if (item.has("data") && item.get("data").isJsonObject()) {
						JsonObject d = item.getAsJsonObject("data");
						String id = JsonUtil.getJsonString(d, "id");
						if (!id.isEmpty() && !dataById.containsKey(id)) {
							JsonObject dCopy = d.deepCopy();
							dCopy.addProperty("runtimeCtx", runtimeCtx);
							dCopy.add("my_capabilities", capabilities);
							dCopy.add("status", manager.buildModelStatus(modelId, true));
							String srcId = e.getValue().getSourceModelId();
							if (srcId != null) {
								dCopy.addProperty("sourceModelId", srcId);
							}
							if (!dCopy.has("aliases")) {
								dCopy.add("aliases", new JsonArray());
							}
							if (!dCopy.has("tags")) {
								dCopy.add("tags", new JsonArray());
							}
							if (!dCopy.has("need_download")) {
								dCopy.addProperty("need_download", false);
							}
							dataById.put(id, dCopy);
						}
					}
				}
			}

			for (org.mark.llamacpp.server.LlamaHubNode node : NodeManager.getInstance().listEnabledNodes()) {
				this.mergeRemoteModels(node.getNodeId(), node.getName(), modelsByKey, dataById);
			}

			// 从缓存文件读取未加载但允许自动加载的模型
			Set<String> loadedIds = new HashSet<>(loaded.keySet());
			JsonObject cache = manager.readAutoLoadModelCache();
			if (cache != null && cache.has("data") && cache.get("data").isJsonArray()) {
				for (JsonElement el : cache.getAsJsonArray("data")) {
					if (!el.isJsonObject()) continue;
					JsonObject cached = el.getAsJsonObject();
					String modelId = JsonUtil.getJsonString(cached, "id");
					if (modelId == null || modelId.isBlank() || loadedIds.contains(modelId)) {
						continue;
					}
					if (!modelsByKey.containsKey(modelId)) {
						JsonObject m = new JsonObject();
						m.addProperty("model", modelId);
						m.addProperty("name", modelId);
						if (cached.has("runtimeCtx")) {
							m.addProperty("runtimeCtx", cached.get("runtimeCtx").getAsInt());
						}
						if (cached.has("my_capabilities")) {
							m.add("my_capabilities", cached.getAsJsonObject("my_capabilities"));
						}
						modelsByKey.put(modelId, m);
					}
					if (!dataById.containsKey(modelId)) {
						dataById.put(modelId, cached);
					}
				}
			}

			JsonArray models = new JsonArray();
			for (JsonObject m : modelsByKey.values()) {
				models.add(m);
			}
			JsonArray data = new JsonArray();
			for (JsonObject d : dataById.values()) {
				data.add(d);
			}

			JsonObject response = new JsonObject();
			response.addProperty("object", "list");
			response.add("models", models);
			response.add("data", data);
			sendOpenAIJsonResponse(ctx, response);
		} catch (Exception e) {
			logger.info("获取模型列表时发生错误", e);
			this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, e.getMessage(), null);
		}
	}
	
	/**
	 * 合并远程节点的模型列表到本地结果中
	 */
	private void mergeRemoteModels(String nodeId, String nodeName,
	                               Map<String, JsonObject> modelsByKey,
	                               Map<String, JsonObject> dataById) {
		try {
			NodeManager.HttpResult result = NodeManager.getInstance().callRemoteApi(
					nodeId, "GET", "/v1/models", null);
			if (!result.isSuccess()) {
				logger.warn("获取远程节点模型列表失败: nodeId={}, code={}, body={}", nodeId, result.getStatusCode(), result.getBody());
				return;
			}

			JsonObject root = JsonUtil.fromJson(result.getBody(), JsonObject.class);
			if (root == null) return;

			if (root.has("models") && root.get("models").isJsonArray()) {
				JsonArray remoteModels = root.getAsJsonArray("models");
				for (JsonElement el : remoteModels) {
					if (!el.isJsonObject()) continue;
					JsonObject m = el.getAsJsonObject();
					String key = JsonUtil.getJsonString(m, "model");
					if (key.isEmpty()) key = JsonUtil.getJsonString(m, "name");
					if (key.isEmpty()) continue;
					key = nodeId + ":" + key;
					if (!modelsByKey.containsKey(key)) {
						JsonObject copy = m.deepCopy();
						copy.addProperty("nodeId", nodeId);
						copy.addProperty("nodeName", nodeName);
						modelsByKey.put(key, copy);
					}
				}
			}
			if (root.has("data") && root.get("data").isJsonArray()) {
				JsonArray remoteData = root.getAsJsonArray("data");
				for (JsonElement el : remoteData) {
					if (!el.isJsonObject()) continue;
					JsonObject d = el.getAsJsonObject();
					String id = JsonUtil.getJsonString(d, "id");
					if (id.isEmpty()) continue;
					id = nodeId + ":" + id;
					if (!dataById.containsKey(id)) {
						JsonObject copy = d.deepCopy();
						copy.addProperty("nodeId", nodeId);
						copy.addProperty("nodeName", nodeName);
						dataById.put(id, copy);
					}
				}
			}
		} catch (Exception e) {
			logger.warn("合并远程节点模型列表失败: nodeId={}, error={}", nodeId, e.getMessage());
		}
	}

	private String tryAutoLoadModel(LlamaServerManager manager, String modelName) {
		if (!AutoLoadPolicyManager.getInstance().canAutoLoad(modelName)) {
			logger.info("[自动加载] 策略拒绝: model={}", modelName);
			return "Auto-load denied by policy for: " + modelName;
		}
		logger.info("[自动加载] 尝试加载模型: model={}", modelName);
		long timeout = AutoLoadPolicyManager.getInstance().getAutoLoadTimeoutMs();
		String loadError = manager.autoLoadModelFromConfig(modelName, timeout);
		if (loadError == null) {
			logger.info("[自动加载] 加载成功: model={}", modelName);
			manager.updateModelLastUsedTime(modelName);
		} else {
			logger.warn("[自动加载] 加载失败: model={}, error={}", modelName, loadError);
		}
		return loadError;
	}
	
	/**
	 * 	处理 OpenAI 文本补全请求：/v1/completions
	 * @param ctx
	 * @param request
	 */
	public void handleOpenAICompletionsRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			// 只支持POST请求
			if (request.method() != HttpMethod.POST) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 405, null, "Only POST method is supported", "method");
				return;
			}

			// 读取请求体
			byte[] bodyBytes = JsonUtil.readRequestBytes(request);
			if (bodyBytes == null || bodyBytes.length == 0) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 400, null, "Request body is empty", "messages");
				return;
			}

			// 解析JSON请求体
			JsonObject requestJson = JsonUtil.fromJson(bodyBytes, JsonObject.class);

			// 获取LlamaServerManager实例
			LlamaServerManager manager = LlamaServerManager.getInstance();

			String modelName = null;

			// 搜索模型的名字，如果没有这个字段，则直接取用第一个模型。
			if (!requestJson.has("model")) {
				modelName = manager.getFirstModelName();
				if (modelName == null) {
					this.sendOpenAIErrorResponseWithCleanup(ctx, 404, null, "No models are currently loaded", null);
					return;
				}
				requestJson.addProperty("model", modelName);
			} else {
				modelName = requestJson.get("model").getAsString();
			}

			// 检查是否为流式请求
			boolean isStream = false;
			if (requestJson.has("stream")) {
				isStream = requestJson.get("stream").getAsBoolean();
			}

			String targetUrl = null;
			String remoteApiKey = null;
			Integer localPort = null;
			String loadError = null;

			if (modelName != null && !modelName.isBlank()) {
				if (!manager.getLoadedProcesses().containsKey(modelName)) {
					String resolved = manager.findModelIdByAlias(modelName);
					if (resolved != null) {
						modelName = resolved;
					}
				}
				if (!manager.getLoadedProcesses().containsKey(modelName)) {
					loadError = this.tryAutoLoadModel(manager, modelName);
					if (loadError == null) {
						Integer port = manager.getModelPort(modelName);
						if (port != null) {
							localPort = port;
							targetUrl = String.format("http://localhost:%d/v1/completions", port.intValue());
						}
					}
				}
				if (manager.getLoadedProcesses().containsKey(modelName)) {
					manager.updateModelLastUsedTime(modelName);
					localPort = manager.getModelPort(modelName);
					if (localPort != null) {
						targetUrl = String.format("http://localhost:%d/v1/completions", localPort.intValue());
					}
				}
			}

			if (targetUrl == null && modelName != null && !modelName.isBlank()) {
				logger.info("[OpenAICompletions路由] 本地未找到模型，开始搜索远程节点: model={}", modelName);
				String[] remote = this.resolveFromRemoteNodes(modelName, "[OpenAICompletions路由]");
				if (remote != null) {
					targetUrl = remote[0] + "/v1/completions";
					remoteApiKey = remote[1];
				}
			}

			ModelSamplingService service = ModelSamplingService.getInstance();
			service.handleOpenAI(requestJson);

			if (targetUrl == null) {
				int statusCode = loadError != null ? 500 : 404;
				this.sendOpenAIErrorResponseWithCleanup(ctx, statusCode, null,
					loadError != null ? loadError : "Model not found: " + (modelName != null ? modelName : "unknown"), "model");
				return;
			}

			if (localPort != null) {
				this.forwardRequestToLlamaCpp(ctx, request, modelName, localPort, "/v1/completions", isStream, JsonUtil.toJson(requestJson));
			} else if (isStream) {
				this.forwardStreamToRemote(ctx, request, modelName, targetUrl, remoteApiKey, "/v1/completions", requestJson);
			} else {
				this.forwardNonStreamToRemote(ctx, request, modelName, targetUrl, remoteApiKey, "/v1/completions", requestJson);
			}
		} catch (Exception e) {
			logger.info("处理OpenAI文本补全请求时发生错误", e);
			this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, e.getMessage(), null);
		}
	}
	
	/**
	 * 	处理 OpenAI 嵌入请求
	 * @param ctx
	 * @param request
	 */
	public void handleOpenAIEmbeddingsRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			if (request.method() != HttpMethod.POST) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 405, null, "Only POST method is supported", "method");
				return;
			}
			byte[] bodyBytes = JsonUtil.readRequestBytes(request);
			if (bodyBytes == null || bodyBytes.length == 0) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 400, null, "Request body is empty", "messages");
				return;
			}
			JsonObject requestJson = JsonUtil.fromJson(bodyBytes, JsonObject.class);
			if (requestJson == null) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 400, null, "Request body is not a valid JSON object", null);
				return;
			}

			String bodyNodeId = JsonUtil.getJsonString(requestJson, "nodeId", "");
			if (bodyNodeId != null && !bodyNodeId.isBlank()) {
				requestJson.remove("nodeId");
			}

			String modelName = null;
			if (!requestJson.has("model")) {
				modelName = LlamaServerManager.getInstance().getFirstModelName();
			} else {
				modelName = requestJson.get("model").getAsString();
			}

			String targetUrl = null;
			String remoteApiKey = null;
			Integer localPort = null;
			String loadError = null;

			if (bodyNodeId != null && !bodyNodeId.isBlank()) {
				logger.info("[OpenAIEmbed路由] 请求体指定 nodeId，直接路由远程节点: nodeId={}, model={}", bodyNodeId, modelName);
				LlamaHubNode node = NodeManager.getInstance().getNode(bodyNodeId);
				if (node == null || !node.isEnabled()) {
					this.sendOpenAIErrorResponseWithCleanup(ctx, 404, null, "Remote node not found or disabled: " + bodyNodeId, null);
					return;
				}
				targetUrl = node.getBaseUrl() + "/v1/embeddings";
				remoteApiKey = node.getApiKey();
			} else {
				if (modelName != null && !modelName.isBlank()) {
					LlamaServerManager manager = LlamaServerManager.getInstance();
					if (!manager.getLoadedProcesses().containsKey(modelName)) {
						String resolved = manager.findModelIdByAlias(modelName);
						if (resolved != null) {
							modelName = resolved;
						}
					}
					if (!manager.getLoadedProcesses().containsKey(modelName)) {
						loadError = this.tryAutoLoadModel(manager, modelName);
						if (loadError == null) {
							Integer port = manager.getModelPort(modelName);
							if (port != null) {
								localPort = port;
								targetUrl = String.format("http://localhost:%d/v1/embeddings", localPort.intValue());
							}
						}
					}
					if (manager.getLoadedProcesses().containsKey(modelName)) {
						manager.updateModelLastUsedTime(modelName);
						localPort = manager.getModelPort(modelName);
						if (localPort != null) {
							targetUrl = String.format("http://localhost:%d/v1/embeddings", localPort.intValue());
						}
					}
				}
				if (targetUrl == null && modelName != null && !modelName.isBlank()) {
					logger.info("[OpenAIEmbed路由] 本地未找到模型，开始搜索远程节点: model={}", modelName);
					String[] remote = this.resolveEmbeddingsFromRemoteNodes(modelName);
					if (remote != null) {
						targetUrl = remote[0];
						remoteApiKey = remote[1];
					}
				}
			}

			if (targetUrl == null) {
				int statusCode = loadError != null ? 500 : 404;
				this.sendOpenAIErrorResponseWithCleanup(ctx, statusCode, null,
					loadError != null ? loadError : "Model not found: " + (modelName != null ? modelName : "unknown"), "model");
				return;
			}

			if (localPort != null) {
				this.forwardEmbeddingsToLlamaCppRaw(ctx, request, modelName, localPort, requestJson);
			} else {
				this.forwardEmbeddingsToRemote(ctx, request, modelName, targetUrl, remoteApiKey, requestJson);
			}
		} catch (Exception e) {
			logger.info("处理OpenAI嵌入请求时发生错误", e);
			this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, e.getMessage(), null);
		}
	}

	private void forwardEmbeddingsToRemote(ChannelHandlerContext ctx, FullHttpRequest request,
			String modelName, String targetUrl, String remoteApiKey, JsonObject bodyJson) {
		this.forwardRawJsonToTarget(ctx, request, modelName, targetUrl, remoteApiKey, "/v1/embeddings", bodyJson);
	}

	private void forwardEmbeddingsToLlamaCppRaw(ChannelHandlerContext ctx, FullHttpRequest request,
			String modelName, int port, JsonObject bodyJson) {
		String targetUrl = String.format("http://localhost:%d/v1/embeddings", port);
		this.forwardRawJsonToTarget(ctx, request, modelName, targetUrl, null, "/v1/embeddings", bodyJson);
	}

	private void forwardRerankToRemoteRaw(ChannelHandlerContext ctx, FullHttpRequest request,
			String modelName, String targetUrl, String remoteApiKey, String endpoint, JsonObject bodyJson) {
		this.forwardRawJsonToTarget(ctx, request, modelName, targetUrl, remoteApiKey, endpoint, bodyJson);
	}

	private void forwardRerankToLlamaCppRaw(ChannelHandlerContext ctx, FullHttpRequest request,
			String modelName, int port, String endpoint, JsonObject bodyJson) {
		String targetUrl = String.format("http://localhost:%d%s", port, endpoint);
		this.forwardRawJsonToTarget(ctx, request, modelName, targetUrl, null, endpoint, bodyJson);
	}

	private void forwardRawJsonToTarget(ChannelHandlerContext ctx, FullHttpRequest request,
			String modelName, String targetUrl, String remoteApiKey, String endpoint, JsonObject bodyJson) {
		byte[] bodyBytes = JsonUtil.toJson(bodyJson).getBytes(StandardCharsets.UTF_8);
		Map<String, String> headers = new HashMap<>();
		for (Map.Entry<String, String> entry : request.headers()) {
			headers.put(entry.getKey(), entry.getValue());
		}
		HttpMethod method = request.method();

		worker.execute(() -> {
			String requestId = ModelRequestTracker.getInstance().createRequest(modelName, endpoint);
			HttpURLConnection connection = null;
			try {
				connection = this.openTrackedConnection(ctx, targetUrl, method, headers, false);
				if (remoteApiKey != null && !remoteApiKey.isBlank()) {
					connection.setRequestProperty("Authorization", "Bearer " + remoteApiKey);
				}
				if (method == HttpMethod.POST && bodyBytes.length > 0) {
					try (OutputStream os = connection.getOutputStream()) {
						os.write(bodyBytes, 0, bodyBytes.length);
					}
				}

				int responseCode = connection.getResponseCode();
				ModelRequestTracker.getInstance().updatePhase(requestId, ActiveRequest.Phase.GENERATION);
				this.streamRawProxyResponse(ctx, connection, responseCode, modelName, requestId, true);
			} catch (Exception e) {
				logger.info("转发原始 JSON 请求时发生错误: endpoint={}", endpoint, e);
				this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, e.getMessage(), null);
			} catch (Throwable t) {
				logger.error("虚拟线程异常已兜底: {}", t.getMessage(), t);
			} finally {
				ModelRequestTracker.getInstance().removeRequest(requestId);
				this.cleanupTrackedConnection(ctx, connection);
			}
		});
	}

	private void forwardNonStreamToRemote(ChannelHandlerContext ctx, FullHttpRequest request,
			String modelName, String targetUrl, String remoteApiKey, String endpoint, JsonObject bodyJson) {
		byte[] bodyBytes = JsonUtil.toJson(bodyJson).getBytes(StandardCharsets.UTF_8);
		Map<String, String> headers = new HashMap<>();
		for (Map.Entry<String, String> entry : request.headers()) {
			headers.put(entry.getKey(), entry.getValue());
		}
		HttpMethod method = request.method();

		worker.execute(() -> {
			String requestId = ModelRequestTracker.getInstance().createRequest(modelName, endpoint);
			HttpURLConnection connection = null;
			try {
				connection = (HttpURLConnection) URI.create(targetUrl).toURL().openConnection();
				if (connection instanceof javax.net.ssl.HttpsURLConnection) {
					NodeManager.trustAllCerts((javax.net.ssl.HttpsURLConnection) connection);
				}
				connection.setRequestMethod(method.name());
				connection.setConnectTimeout(36000 * 1000);
				connection.setReadTimeout(36000 * 1000);
				for (Map.Entry<String, String> entry : headers.entrySet()) {
					if (this.shouldForwardRequestHeader(entry.getKey())) {
						connection.setRequestProperty(entry.getKey(), entry.getValue());
					}
				}
				if (remoteApiKey != null && !remoteApiKey.isBlank()) {
					connection.setRequestProperty("Authorization", "Bearer " + remoteApiKey);
				}
				connection.setDoOutput(true);
				try (OutputStream os = connection.getOutputStream()) {
					os.write(bodyBytes);
				}

				int responseCode = connection.getResponseCode();
				ModelRequestTracker.getInstance().updatePhase(requestId, ActiveRequest.Phase.GENERATION);

				this.streamRawProxyResponse(ctx, connection, responseCode, modelName, requestId, responseCode >= 200 && responseCode < 300);
			} catch (Exception e) {
				logger.info("转发非流式请求到远程节点时发生错误", e);
				this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, e.getMessage(), null);
			} catch (Throwable t) {
				logger.error("虚拟线程异常已兜底: {}", t.getMessage(), t);
			} finally {
				ModelRequestTracker.getInstance().removeRequest(requestId);
				if (connection != null) {
					connection.disconnect();
				}
			}
		});
	}

	private void forwardStreamToRemote(ChannelHandlerContext ctx, FullHttpRequest request,
			String modelName, String targetUrl, String remoteApiKey, String endpoint, JsonObject bodyJson) {
		byte[] bodyBytes = JsonUtil.toJson(bodyJson).getBytes(StandardCharsets.UTF_8);
		Map<String, String> headers = new HashMap<>();
		for (Map.Entry<String, String> entry : request.headers()) {
			headers.put(entry.getKey(), entry.getValue());
		}
		HttpMethod method = request.method();

		worker.execute(() -> {
			String requestId = ModelRequestTracker.getInstance().createRequest(modelName, endpoint);
			HttpURLConnection connection = null;
			try {
				connection = (HttpURLConnection) URI.create(targetUrl).toURL().openConnection();
				if (connection instanceof javax.net.ssl.HttpsURLConnection) {
					NodeManager.trustAllCerts((javax.net.ssl.HttpsURLConnection) connection);
				}
				connection.setRequestMethod(method.name());
				connection.setConnectTimeout(36000 * 1000);
				connection.setReadTimeout(36000 * 1000);
				for (Map.Entry<String, String> entry : headers.entrySet()) {
					if (this.shouldForwardRequestHeader(entry.getKey())) {
						connection.setRequestProperty(entry.getKey(), entry.getValue());
					}
				}
				if (remoteApiKey != null && !remoteApiKey.isBlank()) {
					connection.setRequestProperty("Authorization", "Bearer " + remoteApiKey);
				}
				connection.setDoOutput(true);
				try (OutputStream os = connection.getOutputStream()) {
					os.write(bodyBytes);
				}

				int responseCode = connection.getResponseCode();
				ModelRequestTracker.getInstance().updatePhase(requestId, ActiveRequest.Phase.GENERATION);

				if (!(responseCode >= 200 && responseCode < 300)) {
					FullHttpResponse errResp = new DefaultFullHttpResponse(
						HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(responseCode));
				errResp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
				String errBody = "";
				try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
					StringBuilder sb = new StringBuilder();
					String line;
					while ((line = br.readLine()) != null) {
						sb.append(line);
					}
					errBody = sb.toString();
				}
				byte[] errBytes = errBody.getBytes(StandardCharsets.UTF_8);
				errResp.headers().set(HttpHeaderNames.CONTENT_LENGTH, errBytes.length);
				errResp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
				errResp.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
				errResp.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
				errResp.content().writeBytes(errBytes);
				ctx.writeAndFlush(errResp).addListener(ChannelFutureListener.CLOSE);
				return;
				}

				HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(responseCode));
				response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream; charset=UTF-8");
				response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
				response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
				response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
				response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
				if (!NettyWriteHelper.writeAndFlushBlocking(ctx, response, logger, "[OpenAIService-remote]")) {
					return;
				}

				Map<Integer, String> toolCallIds = new HashMap<>();
				try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
					String line;
					while ((line = br.readLine()) != null) {
						if (!ctx.channel().isActive()) {
							logger.info("客户端连接已断开，中止远程节点流式代理: endpoint={}", endpoint);
							break;
						}
						if (line.startsWith("data: ")) {
							String data = line.substring(6);
							if (data.equals("[DONE]")) {
								ByteBuf doneContent = ctx.alloc().buffer();
								doneContent.writeBytes("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
								NettyWriteHelper.writeAndFlushBlocking(ctx, new DefaultHttpContent(doneContent), logger, "[OpenAIService-remote]");
								break;
							}

							if (data.contains("\"timings\"")) {
								Timing timing = LlamaRecordService.getInstance().handleStream(modelName, data, requestId);
								if (requestId != null && timing != null) {
									ModelRequestTracker.getInstance().updateTiming(requestId, timing);
								}
							}

							String outLine = line;
							JsonObject parsed = JsonUtil.tryParseObject(data);
							if (parsed != null) {
								boolean changed = JsonUtil.ensureToolCallIds(parsed, toolCallIds);
								if (changed) {
									outLine = "data: " + JsonUtil.toJson(parsed);
								}
							}

							ByteBuf content = ctx.alloc().buffer();
							content.writeBytes(outLine.getBytes(StandardCharsets.UTF_8));
							content.writeBytes("\n".getBytes(StandardCharsets.UTF_8));
							if (!NettyWriteHelper.writeAndFlushBlocking(
									ctx,
									new DefaultHttpContent(content),
									logger,
									"[OpenAIService-remote]")) {
								return;
							}
						} else if (line.startsWith("event: ")) {
							ByteBuf content = ctx.alloc().buffer();
							content.writeBytes(line.getBytes(StandardCharsets.UTF_8));
							content.writeBytes("\n".getBytes(StandardCharsets.UTF_8));
							if (!NettyWriteHelper.writeAndFlushBlocking(
									ctx,
									new DefaultHttpContent(content),
									logger,
									"[OpenAIService-remote]")) {
								return;
							}
						} else if (line.isEmpty()) {
							ByteBuf content = ctx.alloc().buffer();
							content.writeBytes("\n".getBytes(StandardCharsets.UTF_8));
							if (!NettyWriteHelper.writeAndFlushBlocking(
									ctx,
									new DefaultHttpContent(content),
									logger,
									"[OpenAIService-remote]")) {
								return;
							}
						}
					}
				}

				if (ctx.channel().isActive()
						&& NettyWriteHelper.writeAndFlushBlocking(
								ctx,
								LastHttpContent.EMPTY_LAST_CONTENT,
								logger,
								"[OpenAIService-remote]")) {
					ctx.close();
				}
			} catch (Exception e) {
				logger.info("转发流式请求到远程节点时发生错误", e);
				this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, e.getMessage(), null);
			} catch (Throwable t) {
				logger.error("虚拟线程异常已兜底: {}", t.getMessage(), t);
			} finally {
				ModelRequestTracker.getInstance().removeRequest(requestId);
				if (connection != null) {
					connection.disconnect();
				}
			}
		});
	}

	private String[] resolveEmbeddingsFromRemoteNodes(String modelName) {
		String[] remote = this.resolveFromRemoteNodes(modelName, "[OpenAIEmbed路由]");
		if (remote == null) {
			return null;
		}
		return new String[] { this.joinBaseUrlAndPath(remote[0], "/v1/embeddings"), remote[1] };
	}

	private String[] resolveRerankFromRemoteNodes(String modelName) {
		return this.resolveFromRemoteNodes(modelName, "[OpenAIRerank路由]");
	}

	private String[] resolveResponsesFromRemoteNodes(String modelName) {
		return this.resolveFromRemoteNodes(modelName, "[OpenAIResponses路由]");
	}

	private String joinBaseUrlAndPath(String baseUrl, String path) {
		if (baseUrl == null || baseUrl.isBlank()) {
			return path;
		}
		if (path == null || path.isBlank()) {
			return baseUrl;
		}
		if (baseUrl.endsWith("/") && path.startsWith("/")) {
			return baseUrl.substring(0, baseUrl.length() - 1) + path;
		}
		if (!baseUrl.endsWith("/") && !path.startsWith("/")) {
			return baseUrl + "/" + path;
		}
		return baseUrl + path;
	}

	private boolean shouldForwardRequestHeader(String headerName) {
		if (headerName == null || headerName.isBlank()) {
			return false;
		}
		return !headerName.equalsIgnoreCase("Connection") &&
				!headerName.equalsIgnoreCase("Content-Length") &&
				!headerName.equalsIgnoreCase("Transfer-Encoding") &&
				!headerName.equalsIgnoreCase("Accept-Encoding") &&
				!headerName.equalsIgnoreCase("X-Node-Id");
	}

	/** 边收边下发时保留的响应尾部字节数（llama.cpp/OpenAI 的 timings、usage 均在 JSON 末尾，16KB 足够覆盖） */
	private static final int RAW_PROXY_TAIL_BYTES = 16 * 1024;

	/**
	 * 边收边下发：将上游非流式响应以 chunked 方式流式写给客户端，
	 * 同时只保留尾部 {@link #RAW_PROXY_TAIL_BYTES} 字节用于提取 timings/usage 统计，避免全量缓冲响应体。
	 *
	 * @param recordStats 是否从尾部片段提取 timings/usage 并记录
	 */
	private void streamRawProxyResponse(ChannelHandlerContext ctx, HttpURLConnection connection,
			int responseCode, String modelName, String requestId, boolean recordStats) throws IOException {
		String contentType = connection.getContentType();
		HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(responseCode));
		response.headers().set(HttpHeaderNames.CONTENT_TYPE,
				(contentType == null || contentType.isBlank()) ? "application/json; charset=UTF-8" : contentType);
		response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
		response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
		if (!NettyWriteHelper.writeAndFlushBlocking(ctx, response, logger, "[OpenAIService-raw]")) {
			return;
		}
		boolean success = responseCode >= 200 && responseCode < 300;
		InputStream in = success ? connection.getInputStream() : connection.getErrorStream();
		TailBuffer tail = recordStats ? new TailBuffer(RAW_PROXY_TAIL_BYTES) : null;
		if (in != null) {
			try {
				byte[] buffer = new byte[8192];
				int read;
				while ((read = in.read(buffer)) != -1) {
					if (tail != null) {
						tail.append(buffer, read);
					}
					ByteBuf content = ctx.alloc().buffer(read);
					content.writeBytes(buffer, 0, read);
					if (!NettyWriteHelper.writeAndFlushBlocking(ctx, new DefaultHttpContent(content), logger, "[OpenAIService-raw]")) {
						return;
					}
				}
			} finally {
				in.close();
			}
		}
		if (ctx.channel().isActive()
				&& NettyWriteHelper.writeAndFlushBlocking(ctx, LastHttpContent.EMPTY_LAST_CONTENT, logger, "[OpenAIService-raw]")) {
			ctx.close();
		}
		if (tail != null) {
			Timing timing = LlamaRecordService.getInstance().handleStreamTail(modelName, tail.toUtf8String(), requestId);
			if (requestId != null && timing != null) {
				ModelRequestTracker.getInstance().updateTiming(requestId, timing);
			}
		}
	}

	/**
	 * 尾部环形缓冲：只保留最后写入的 N 字节，用于流式场景下提取末尾的 timings/usage。
	 */
	private static final class TailBuffer {
		private final byte[] data;
		private long count = 0;

		TailBuffer(int capacity) {
			this.data = new byte[capacity];
		}

		void append(byte[] src, int len) {
			int cap = this.data.length;
			int off = len > cap ? len - cap : 0; // 单次写入超过容量时只保留末尾 cap 字节
			int n = len - off;
			for (int i = 0; i < n; i++) {
				this.data[(int) ((this.count + i) % cap)] = src[off + i];
			}
			this.count += n;
		}

		String toUtf8String() {
			int cap = this.data.length;
			int size = (int) Math.min(this.count, cap);
			byte[] out = new byte[size];
			long first = this.count - size;
			for (int i = 0; i < size; i++) {
				out[i] = this.data[(int) ((first + i) % cap)];
			}
			return new String(out, StandardCharsets.UTF_8);
		}
	}

	private String[] resolveFromRemoteNodes(String modelName, String logTag) {
		NodeManager nodeManager = NodeManager.getInstance();
		List<LlamaHubNode> enabledNodes = nodeManager.listEnabledNodes();
		logger.info("{} 远程节点列表: count={}", logTag, enabledNodes.size());

		for (LlamaHubNode node : enabledNodes) {
			logger.info("{} 检查远程节点: nodeId={}, baseUrl={}", logTag, node.getNodeId(), node.getBaseUrl());
			try {
				NodeManager.HttpResult result = nodeManager.callRemoteApi(node.getNodeId(), "GET", "/v1/models", null);
				if (!result.isSuccess()) {
					logger.warn("{} 远程节点请求失败: nodeId={}, body={}", logTag, node.getNodeId(), result.getBody());
					continue;
				}

				JsonObject root = JsonUtil.fromJson(result.getBody(), JsonObject.class);
				if (root == null) continue;

				if (root.has("models") && root.get("models").isJsonArray()) {
					JsonArray remoteModels = root.getAsJsonArray("models");
					for (JsonElement el : remoteModels) {
						if (!el.isJsonObject()) continue;
						JsonObject m = el.getAsJsonObject();
						String remoteKey = JsonUtil.getJsonString(m, "model");
						if (remoteKey.isEmpty()) remoteKey = JsonUtil.getJsonString(m, "name");
						if (modelName.equals(remoteKey)) {
							logger.info("{} 匹配成功: model={}, nodeId={}", logTag, modelName, node.getNodeId());
							return new String[]{ node.getBaseUrl(), node.getApiKey() };
						}
					}
				}
				if (root.has("data") && root.get("data").isJsonArray()) {
					JsonArray dataArr = root.getAsJsonArray("data");
					for (JsonElement el : dataArr) {
						if (!el.isJsonObject()) continue;
						JsonObject d = el.getAsJsonObject();
						String id = JsonUtil.getJsonString(d, "id", "");
						if (modelName.equals(id)) {
							logger.info("{} data匹配成功: model={}, nodeId={}", logTag, modelName, node.getNodeId());
							return new String[]{ node.getBaseUrl(), node.getApiKey() };
						}
					}
				}
			} catch (Exception e) {
				logger.warn("{} 异常: nodeId={}, error={}", logTag, node.getNodeId(), e.getMessage());
			}
		}
		logger.warn("{} 所有远程节点均未找到: model={}", logTag, modelName);
		return null;
	}
	
	/**
	 * 	转发rerank请求，重排序用。
	 * @param ctx
	 * @param request
	 */
	public void handleOpenAIRerankRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			if (request.method() != HttpMethod.POST) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 405, null, "Only POST method is supported", "method");
				return;
			}
			byte[] bodyBytes = JsonUtil.readRequestBytes(request);
			if (bodyBytes == null || bodyBytes.length == 0) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 400, null, "Request body is empty", "query");
				return;
			}
			JsonObject requestJson = JsonUtil.fromJson(bodyBytes, JsonObject.class);
			if (requestJson == null) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 400, null, "Request body is not a valid JSON object", null);
				return;
			}

			String endpoint = request.uri();
			if (endpoint != null && endpoint.startsWith("/rerank")) {
				endpoint = "/v1" + endpoint;
			}
			if (endpoint == null || endpoint.isBlank()) {
				endpoint = "/v1/rerank";
			}

			String bodyNodeId = JsonUtil.getJsonString(requestJson, "nodeId", "");
			if (bodyNodeId != null && !bodyNodeId.isBlank()) {
				requestJson.remove("nodeId");
			}

			String modelName;
			if (!requestJson.has("model")) {
				modelName = LlamaServerManager.getInstance().getFirstModelName();
			} else {
				modelName = requestJson.get("model").getAsString();
			}

			String targetUrl = null;
			String remoteApiKey = null;
			Integer localPort = null;
			String loadError = null;

			if (bodyNodeId != null && !bodyNodeId.isBlank()) {
				logger.info("[OpenAIRerank路由] 请求体指定 nodeId，直接路由远程节点: nodeId={}, model={}", bodyNodeId, modelName);
				LlamaHubNode node = NodeManager.getInstance().getNode(bodyNodeId);
				if (node == null || !node.isEnabled()) {
					this.sendOpenAIErrorResponseWithCleanup(ctx, 404, null, "Remote node not found or disabled: " + bodyNodeId, null);
					return;
				}
				targetUrl = node.getBaseUrl() + endpoint;
				remoteApiKey = node.getApiKey();
			} else {
				if (modelName != null && !modelName.isBlank()) {
					LlamaServerManager manager = LlamaServerManager.getInstance();
					if (!manager.getLoadedProcesses().containsKey(modelName)) {
						String resolved = manager.findModelIdByAlias(modelName);
						if (resolved != null) {
							modelName = resolved;
						}
					}
					if (!manager.getLoadedProcesses().containsKey(modelName)) {
						loadError = this.tryAutoLoadModel(manager, modelName);
						if (loadError == null) {
							Integer port = manager.getModelPort(modelName);
							if (port != null) {
								localPort = port;
								targetUrl = String.format("http://localhost:%d%s", localPort.intValue(), endpoint);
							}
						}
					}
					if (manager.getLoadedProcesses().containsKey(modelName)) {
						manager.updateModelLastUsedTime(modelName);
						localPort = manager.getModelPort(modelName);
						if (localPort != null) {
							targetUrl = String.format("http://localhost:%d%s", localPort.intValue(), endpoint);
						}
					}
				}
				if (targetUrl == null && modelName != null && !modelName.isBlank()) {
					logger.info("[OpenAIRerank路由] 本地未找到模型，开始搜索远程节点: model={}", modelName);
					String[] remote = this.resolveRerankFromRemoteNodes(modelName);
					if (remote != null) {
						targetUrl = remote[0] + endpoint;
						remoteApiKey = remote[1];
					}
				}
			}

			if (targetUrl == null) {
				int statusCode = loadError != null ? 500 : 404;
				this.sendOpenAIErrorResponseWithCleanup(ctx, statusCode, null,
					loadError != null ? loadError : "Model not found: " + (modelName != null ? modelName : "unknown"), "model");
				return;
			}

			if (localPort != null) {
				this.forwardRerankToLlamaCppRaw(ctx, request, modelName, localPort, endpoint, requestJson);
			} else {
				this.forwardRerankToRemoteRaw(ctx, request, modelName, targetUrl, remoteApiKey, endpoint, requestJson);
			}
		} catch (Exception e) {
			logger.info("处理OpenAI rerank 请求时发生错误", e);
			this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, e.getMessage(), null);
		}
	}
	
	/**
	 * 	对应端点：/v1/responses
	 * @param ctx
	 * @param request
	 */
	public void handleOpenAIResponsesRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			if (request.method() != HttpMethod.POST) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 405, null, "Only POST method is supported", "method");
				return;
			}
			byte[] bodyBytes = JsonUtil.readRequestBytes(request);
			if (bodyBytes == null || bodyBytes.length == 0) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 400, null, "Request body is empty", "input");
				return;
			}

			JsonObject requestJson = JsonUtil.fromJson(bodyBytes, JsonObject.class);
			if (requestJson == null) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 400, null, "Request body is not a valid JSON object", null);
				return;
			}

			String endpoint = request.uri();
			if (endpoint != null && endpoint.startsWith("/responses")) {
				endpoint = "/v1" + endpoint;
			}
			if (endpoint == null || endpoint.isBlank()) {
				endpoint = "/v1/responses";
			}

			String bodyNodeId = JsonUtil.getJsonString(requestJson, "nodeId", "");
			if (bodyNodeId != null && !bodyNodeId.isBlank()) {
				requestJson.remove("nodeId");
			}

			String modelName;
			if (!requestJson.has("model")) {
				modelName = LlamaServerManager.getInstance().getFirstModelName();
			} else {
				modelName = requestJson.get("model").getAsString();
			}

			boolean isStream = false;
			if (requestJson.has("stream")) {
				isStream = requestJson.get("stream").getAsBoolean();
			}

			String targetUrl = null;
			String remoteApiKey = null;
			Integer localPort = null;
			String loadError = null;

			if (bodyNodeId != null && !bodyNodeId.isBlank()) {
				logger.info("[OpenAIResponses路由] 请求体指定 nodeId，直接路由远程节点: nodeId={}, model={}", bodyNodeId, modelName);
				LlamaHubNode node = NodeManager.getInstance().getNode(bodyNodeId);
				if (node == null || !node.isEnabled()) {
					this.sendOpenAIErrorResponseWithCleanup(ctx, 404, null, "Remote node not found or disabled: " + bodyNodeId, null);
					return;
				}
				targetUrl = node.getBaseUrl() + endpoint;
				remoteApiKey = node.getApiKey();
			} else {
				if (modelName != null && !modelName.isBlank()) {
					LlamaServerManager manager = LlamaServerManager.getInstance();
					if (!manager.getLoadedProcesses().containsKey(modelName)) {
						String resolved = manager.findModelIdByAlias(modelName);
						if (resolved != null) {
							modelName = resolved;
						}
					}
					if (!manager.getLoadedProcesses().containsKey(modelName)) {
						loadError = this.tryAutoLoadModel(manager, modelName);
						if (loadError == null) {
							Integer port = manager.getModelPort(modelName);
							if (port != null) {
								localPort = port;
								targetUrl = String.format("http://localhost:%d%s", localPort.intValue(), endpoint);
							}
						}
					}
					if (manager.getLoadedProcesses().containsKey(modelName)) {
						manager.updateModelLastUsedTime(modelName);
						localPort = manager.getModelPort(modelName);
						if (localPort != null) {
							targetUrl = String.format("http://localhost:%d%s", localPort.intValue(), endpoint);
						}
					}
				}
				if (targetUrl == null && modelName != null && !modelName.isBlank()) {
					logger.info("[OpenAIResponses路由] 本地未找到模型，开始搜索远程节点: model={}", modelName);
					String[] remote = this.resolveResponsesFromRemoteNodes(modelName);
					if (remote != null) {
						targetUrl = remote[0] + endpoint;
						remoteApiKey = remote[1];
					}
				}
			}

			if (targetUrl == null) {
				int statusCode = loadError != null ? 500 : 404;
				this.sendOpenAIErrorResponseWithCleanup(ctx, statusCode, null,
					loadError != null ? loadError : "Model not found: " + (modelName != null ? modelName : "unknown"), "model");
				return;
			}

			if (localPort != null) {
				this.forwardRequestToLlamaCpp(ctx, request, modelName, localPort, endpoint, isStream, JsonUtil.toJson(requestJson));
			} else if (isStream) {
				this.forwardStreamToRemote(ctx, request, modelName, targetUrl, remoteApiKey, endpoint, requestJson);
			} else {
				this.forwardNonStreamToRemote(ctx, request, modelName, targetUrl, remoteApiKey, endpoint, requestJson);
			}
		} catch (Exception e) {
			logger.info("处理OpenAI responses 请求时发生错误", e);
			this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, e.getMessage(), null);
		}
	}
	
	
	/**
	 * 	转发请求到对应的llama.cpp进程
	 * @param ctx
	 * @param request
	 * @param modelName
	 * @param port
	 * @param endpoint
	 * @param isStream
	 * @param requestBody
	 */
	private void forwardRequestToLlamaCpp(ChannelHandlerContext ctx, FullHttpRequest request, String modelName, int port, String endpoint, boolean isStream, String requestBody) {
		byte[] requestBodyBytes = requestBody == null ? new byte[0] : requestBody.getBytes(StandardCharsets.UTF_8);
		this.forwardRequestToLlamaCpp(ctx, request, modelName, port, endpoint, isStream, requestBodyBytes);
	}
	
	private void forwardRequestToLlamaCpp(ChannelHandlerContext ctx, FullHttpRequest request, String modelName, int port, String endpoint, boolean isStream, byte[] requestBodyBytes) {
		// 在异步执行前先读取请求体，避免ByteBuf引用计数问题
		HttpMethod method = request.method();
		// 复制请求头，避免在异步任务中访问已释放的请求对象
		Map<String, String> headers = new HashMap<>();
		for (Map.Entry<String, String> entry : request.headers()) {
			headers.put(entry.getKey(), entry.getValue());
		}

		int requestBodyLength = requestBodyBytes == null ? 0 : requestBodyBytes.length;
		logger.info("转发请求到llama.cpp进程: {} {} 端口: {} 请求体长度: {}", method.name(), endpoint, port, requestBodyLength);
		
		worker.execute(() -> {
			String requestId = ModelRequestTracker.getInstance().createRequest(modelName, endpoint);
			HttpURLConnection connection = null;
			try {
				String targetUrl = String.format("http://localhost:%d%s", port, endpoint);
				logger.info("连接到llama.cpp进程: {}", targetUrl);
				connection = this.openTrackedConnection(ctx, targetUrl, method, headers, false);
				
				if (method == HttpMethod.POST && requestBodyBytes != null && requestBodyBytes.length > 0) {
					try (OutputStream os = connection.getOutputStream()) {
						os.write(requestBodyBytes, 0, requestBodyBytes.length);
						logger.info("已发送请求体到llama.cpp进程，大小: {} 字节", requestBodyBytes.length);
					}
				}
				long t = System.currentTimeMillis();
				int responseCode = connection.getResponseCode();
				logger.info("llama.cpp进程响应码: {}，等待时间：{}", responseCode, System.currentTimeMillis() - t);
				ModelRequestTracker.getInstance().updatePhase(requestId, ActiveRequest.Phase.GENERATION);
				this.handleProxyResponse(ctx, connection, responseCode, modelName, requestId);
			} catch (Exception e) {
				logger.info("转发请求到llama.cpp进程时发生错误", e);
				if (e.getMessage() != null && e.getMessage().contains("Connection reset by peer")) {
					
				}
				this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, e.getMessage(), null);
			} catch (Throwable t) {
				logger.error("虚拟线程异常已兜底: {}", t.getMessage(), t);
			} finally {
				ModelRequestTracker.getInstance().removeRequest(requestId);
				this.cleanupTrackedConnection(ctx, connection);
			}
		});
	}


	/**
	 * 	
	 * @param ctx
	 * @param request
	 */
	public void handleOpenAIAudioTranscriptionsRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			if (request.method() != HttpMethod.POST) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 405, null, "Only POST method is supported", "method");
				return;
			}
			
			ByteBuf requestContent = request.content();
			if (requestContent == null || !requestContent.isReadable()) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 400, null, "Request body is empty", "file");
				return;
			}
			
			byte[] bodyBytes = new byte[requestContent.readableBytes()];
			requestContent.getBytes(requestContent.readerIndex(), bodyBytes);
			
			String endpoint = request.uri();
			if (endpoint != null && endpoint.startsWith("/audio/transcriptions")) {
				endpoint = "/v1" + endpoint;
			}
			if (endpoint == null || endpoint.isBlank()) {
				endpoint = "/v1/audio/transcriptions";
			}
			
			LlamaServerManager manager = LlamaServerManager.getInstance();
			String modelName = this.resolveAudioTranscriptionModel(request);
			if (modelName == null || modelName.isBlank()) {
				modelName = manager.getFirstModelName();
			}
			if (modelName == null || modelName.isBlank()) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 404, null, "No models are currently loaded", "model");
				return;
			}
			if (!manager.getLoadedProcesses().containsKey(modelName)) {
				String resolved = manager.findModelIdByAlias(modelName);
				if (resolved != null) {
					modelName = resolved;
				}
			}
			if (!manager.getLoadedProcesses().containsKey(modelName)) {
				String loadError = this.tryAutoLoadModel(manager, modelName);
				if (loadError == null) {
					Integer modelPort = manager.getModelPort(modelName);
					if (modelPort != null) {
						this.forwardRequestToLlamaCpp(ctx, request, modelName, modelPort, endpoint, false, bodyBytes);
						return;
					}
				}
				
				logger.info("[OpenAIAudioTranscriptions路由] 本地未找到模型，开始搜索远程节点: model={}", modelName);
				String[] remote = this.resolveFromRemoteNodes(modelName, "[OpenAIAudioTranscriptions路由]");
				if (remote != null) {
					String targetUrl = this.joinBaseUrlAndPath(remote[0], endpoint);
					this.forwardRawBodyToRemote(ctx, request, modelName, targetUrl, remote[1], endpoint, bodyBytes);
					return;
				}
				
				this.sendOpenAIErrorResponseWithCleanup(ctx, 404, null,
					loadError != null ? loadError : "Model not found: " + modelName, "model");
				return;
			}
			
			Integer modelPort = manager.getModelPort(modelName);
			if (modelPort == null) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, "Model port not found: " + modelName, null);
				return;
			}
			
			this.forwardRequestToLlamaCpp(ctx, request, modelName, modelPort, endpoint, false, bodyBytes);
		} catch (Exception e) {
			logger.info("处理OpenAI音频转录请求时发生错误", e);
			this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, e.getMessage(), null);
		}
	}
	
	private String resolveAudioTranscriptionModel(FullHttpRequest request) {
		String contentType = request.headers().get(HttpHeaderNames.CONTENT_TYPE);
		if (contentType == null) {
			return null;
		}
		String lowered = contentType.toLowerCase(Locale.ROOT);
		if (!lowered.startsWith("multipart/form-data")) {
			return null;
		}
		
		HttpPostRequestDecoder decoder = null;
		try {
			decoder = new HttpPostRequestDecoder(new DefaultHttpDataFactory(false), request);
			for (InterfaceHttpData data : decoder.getBodyHttpDatas()) {
				if (data == null || data.getHttpDataType() != InterfaceHttpData.HttpDataType.Attribute) {
					continue;
				}
				Attribute attribute = (Attribute) data;
				if (!"model".equals(attribute.getName())) {
					continue;
				}
				String model = attribute.getValue();
				if (model != null) {
					model = model.trim();
				}
				if (model != null && !model.isBlank()) {
					return model;
				}
			}
		} catch (Exception e) {
			logger.info("解析audio/transcriptions表单模型参数失败，尝试回退默认模型", e);
		} finally {
			if (decoder != null) {
				try {
					decoder.destroy();
				} catch (Exception ignore) {
				}
			}
		}
		return null;
	}

	private void forwardRawBodyToRemote(ChannelHandlerContext ctx, FullHttpRequest request,
			String modelName, String targetUrl, String remoteApiKey, String endpoint, byte[] bodyBytes) {
		Map<String, String> headers = new HashMap<>();
		for (Map.Entry<String, String> entry : request.headers()) {
			headers.put(entry.getKey(), entry.getValue());
		}
		HttpMethod method = request.method();

		worker.execute(() -> {
			String requestId = ModelRequestTracker.getInstance().createRequest(modelName, endpoint);
			HttpURLConnection connection = null;
			try {
				connection = (HttpURLConnection) URI.create(targetUrl).toURL().openConnection();
				if (connection instanceof javax.net.ssl.HttpsURLConnection) {
					NodeManager.trustAllCerts((javax.net.ssl.HttpsURLConnection) connection);
				}
				connection.setRequestMethod(method.name());
				connection.setConnectTimeout(36000 * 1000);
				connection.setReadTimeout(36000 * 1000);
				for (Map.Entry<String, String> entry : headers.entrySet()) {
					if (this.shouldForwardRequestHeader(entry.getKey())) {
						connection.setRequestProperty(entry.getKey(), entry.getValue());
					}
				}
				if (remoteApiKey != null && !remoteApiKey.isBlank()) {
					connection.setRequestProperty("Authorization", "Bearer " + remoteApiKey);
				}
				connection.setDoOutput(true);
				try (OutputStream os = connection.getOutputStream()) {
					os.write(bodyBytes);
				}

				int responseCode = connection.getResponseCode();
				ModelRequestTracker.getInstance().updatePhase(requestId, ActiveRequest.Phase.GENERATION);

				this.streamRawProxyResponse(ctx, connection, responseCode, modelName, requestId, responseCode >= 200 && responseCode < 300);
			} catch (Exception e) {
				logger.info("转发音频转录请求到远程节点时发生错误", e);
				this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, e.getMessage(), null);
			} catch (Throwable t) {
				logger.error("虚拟线程异常已兜底: {}", t.getMessage(), t);
			} finally {
				ModelRequestTracker.getInstance().removeRequest(requestId);
				if (connection != null) {
					connection.disconnect();
				}
			}
		});
	}

	public HttpURLConnection openTrackedConnection(ChannelHandlerContext ctx, String targetUrl, HttpMethod method, Map<String, String> headers, boolean chunkedStreaming) throws IOException {
		URL url = URI.create(targetUrl).toURL();
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();

		if (connection instanceof javax.net.ssl.HttpsURLConnection) {
			try {
				NodeManager.trustAllCerts((javax.net.ssl.HttpsURLConnection) connection);
			} catch (Exception e) {
				logger.warn("配置HTTPS证书信任失败: {}", e.getMessage());
			}
		}

		synchronized (this.channelConnectionMap) {
			this.channelConnectionMap.put(ctx, connection);
		}

		connection.setRequestMethod(method.name());
		for (Map.Entry<String, String> entry : headers.entrySet()) {
			if (this.shouldForwardRequestHeader(entry.getKey())) {
				connection.setRequestProperty(entry.getKey(), entry.getValue());
			}
		}
		connection.setConnectTimeout(36000 * 1000);
		connection.setReadTimeout(36000 * 1000);
		if (method == HttpMethod.POST) {
			connection.setDoOutput(true);
			if (chunkedStreaming) {
				connection.setChunkedStreamingMode(8192);
			}
		}
		return connection;
	}

	public void handleProxyResponse(ChannelHandlerContext ctx, HttpURLConnection connection, int responseCode, String modelName) throws IOException {
		this.handleProxyResponse(ctx, connection, responseCode, modelName, null, null);
	}

	public void handleProxyResponse(ChannelHandlerContext ctx, HttpURLConnection connection, int responseCode, String modelName, String requestId) throws IOException {
		this.handleProxyResponse(ctx, connection, responseCode, modelName, requestId, null);
	}

	public void handleProxyResponse(ChannelHandlerContext ctx, HttpURLConnection connection, int responseCode, String modelName, String requestId, String nodeId) throws IOException {
		String contentType = connection.getContentType();
		boolean isStream = contentType != null && contentType.contains("text/event-stream");
		logger.debug("[响应路由] contentType={}, isStream={}", contentType, isStream);
		if (isStream) {
			this.handleStreamResponse(ctx, connection, responseCode, modelName, requestId, nodeId);
			return;
		}
		this.handleNonStreamResponse(ctx, connection, responseCode, modelName, requestId, nodeId);
	}

	public void cleanupTrackedConnection(ChannelHandlerContext ctx, HttpURLConnection connection) {
		if (connection != null) {
			connection.disconnect();
		}
		synchronized (this.channelConnectionMap) {
			HttpURLConnection mapped = this.channelConnectionMap.remove(ctx);
			if (mapped != null && mapped != connection) {
				try {
					mapped.disconnect();
				} catch (Exception e) {
				}
			}
		}
	}

	private void handleNonStreamResponse(ChannelHandlerContext ctx, HttpURLConnection connection, int responseCode, String modelName, String requestId, String nodeId) throws IOException {
		// 读取响应
		String responseBody;
		if (responseCode >= 200 && responseCode < 300) {
			try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
				StringBuilder response = new StringBuilder();
				String responseLine;
				while ((responseLine = br.readLine()) != null) {
					response.append(responseLine.trim());
				}
				responseBody = response.toString();
			}
		} else {
			// 读取错误响应
			try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
				StringBuilder response = new StringBuilder();
				String responseLine;
				while ((responseLine = br.readLine()) != null) {
					response.append(responseLine.trim());
				}
				responseBody = response.toString();
			}
		}

		if (responseCode >= 200 && responseCode < 300) {
			JsonObject parsed = JsonUtil.tryParseObject(responseBody);
			if (parsed != null) {
				boolean changed = JsonUtil.ensureToolCallIds(parsed, null);
				if (changed) {
					responseBody = JsonUtil.toJson(parsed);
				}
			}
		}
		
		// 创建响应
		FullHttpResponse response = new DefaultFullHttpResponse(
			HttpVersion.HTTP_1_1,
			HttpResponseStatus.valueOf(responseCode)
		);
		
		// 设置响应头
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
		byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, responseBytes.length);
		response.headers().set(HttpHeaderNames.ETAG, buildEtag(responseBytes));
		// 添加CORS头
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type, Authorization");
		
		// 设置响应体
		response.content().writeBytes(responseBytes);
		
		// 发送响应
		ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				ctx.close();
			}
		});
		// 缓存生成信息。
		Timing timing = LlamaRecordService.getInstance().handleStream(modelName, responseBody, requestId);
		if (requestId != null && timing != null) {
			ModelRequestTracker.getInstance().updateTiming(requestId, timing);
		}
	}

	private void handleStreamResponse(ChannelHandlerContext ctx, HttpURLConnection connection, int responseCode, String modelName, String requestId, String nodeId) throws IOException {
		// 创建响应头
		HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(responseCode));
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream; charset=UTF-8");
		response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
		response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
		response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
		response.headers().set(HttpHeaderNames.ETAG, buildEtag((modelName + ":" + responseCode + ":" + System.nanoTime()).getBytes(StandardCharsets.UTF_8)));
		
		// 发送响应头
		if (!NettyWriteHelper.writeAndFlushBlocking(ctx, response, logger, "[OpenAIService-local]")) {
			return;
		}
		
		logger.info("llama.cpp - 响应码: {}", responseCode);
		
		// 读取流式响应
		try (BufferedReader br = new BufferedReader(
			new InputStreamReader(
				responseCode >= 200 && responseCode < 300 ?
					connection.getInputStream() : connection.getErrorStream(),
				StandardCharsets.UTF_8
			)
		)) {
			String line;
			int chunkCount = 0;
			Map<Integer, String> toolCallIds = new HashMap<>();
			while ((line = br.readLine()) != null) {
				// 检查客户端连接是否仍然活跃
				if (!ctx.channel().isActive()) {
					logger.info("检测到客户端连接已断开，停止流式响应处理");
					if (connection != null) {
						connection.disconnect();
					}
					break;
				}
				
				// 处理SSE格式的数据行
				if (line.startsWith("data: ")) {
					String data = line.substring(6); // 去掉 "data: " 前缀
					// 检查是否为结束标记
					if (data.equals("[DONE]")) {
						logger.info("收到流式响应结束标记");
						ByteBuf doneContent = ctx.alloc().buffer();
						doneContent.writeBytes("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
						NettyWriteHelper.writeAndFlushBlocking(ctx, new DefaultHttpContent(doneContent), logger, "[OpenAIService-local]");
						break;
					}
					else 
					// 统计生成信息 — timings 只在最后一个 chunk 出现，天然作为结束标记
					if(data.contains("\"timings\"")) {
						Timing timing = LlamaRecordService.getInstance().handleStream(modelName, data, requestId);
						if (requestId != null && timing != null) {
							ModelRequestTracker.getInstance().updateTiming(requestId, timing);
						}
					}
					
					String outLine = line;
					JsonObject parsed = JsonUtil.tryParseObject(data);
					if (parsed != null) {
						boolean changed = JsonUtil.ensureToolCallIds(parsed, toolCallIds);
						if (changed) {
							outLine = "data: " + JsonUtil.toJson(parsed);
						}
					}
					
					// 创建数据块
					ByteBuf content = ctx.alloc().buffer();
					content.writeBytes(outLine.getBytes(StandardCharsets.UTF_8));
					content.writeBytes("\n".getBytes(StandardCharsets.UTF_8));
					
					// 创建HTTP内容块
					HttpContent httpContent = new DefaultHttpContent(content);
					
					if (!NettyWriteHelper.writeAndFlushBlocking(
							ctx,
							httpContent,
							logger,
							"[OpenAIService-local]")) {
						if (connection != null) {
							connection.disconnect();
						}
						break;
					}
					
					chunkCount++;
					
					// 每发送10个数据块记录一次日志
					if (chunkCount % 10 == 0) {
						//logger.info("已发送 {} 个流式数据块", chunkCount);
					}
				} else if (line.startsWith("event: ")) {
					// 处理事件行
					ByteBuf content = ctx.alloc().buffer();
					content.writeBytes(line.getBytes(StandardCharsets.UTF_8));
					content.writeBytes("\n".getBytes(StandardCharsets.UTF_8));
					
					HttpContent httpContent = new DefaultHttpContent(content);
					if (!NettyWriteHelper.writeAndFlushBlocking(
							ctx,
							httpContent,
							logger,
							"[OpenAIService-local]")) {
						if (connection != null) {
							connection.disconnect();
						}
						break;
					}
				} else if (line.isEmpty()) {
					// 发送空行作为分隔符
					ByteBuf content = ctx.alloc().buffer();
					content.writeBytes("\n".getBytes(StandardCharsets.UTF_8));
					
					HttpContent httpContent = new DefaultHttpContent(content);
					if (!NettyWriteHelper.writeAndFlushBlocking(
							ctx,
							httpContent,
							logger,
							"[OpenAIService-local]")) {
						if (connection != null) {
							connection.disconnect();
						}
						break;
					}
				}
			}
			
			logger.info("流式响应处理完成，共发送 {} 个数据块", chunkCount);
		} catch (Exception e) {
			String nodeCtx = this.resolveNodeName(nodeId);
			logger.info("处理流式响应时发生错误 [{}]", nodeCtx, e);
			// 检查是否是客户端断开连接导致的异常
			if (e.getMessage() != null && (e.getMessage().contains("Connection reset by peer") || e.getMessage().contains("Broken pipe") || e.getMessage().contains("Connection closed"))) {
				logger.info("检测到客户端断开连接，尝试断开与llama.cpp的连接");
				if (connection != null) {
					connection.disconnect();
				}
			}
		}
		
		// 发送结束标记
		if (ctx.channel().isActive()
				&& NettyWriteHelper.writeAndFlushBlocking(
						ctx,
						LastHttpContent.EMPTY_LAST_CONTENT,
						logger,
						"[OpenAIService-local]")) {
			ctx.close();
		}
	}

//	private static String safeString(JsonObject obj, String key) {
//		try {
//			if (obj == null || key == null) {
//				return null;
//			}
//			JsonElement el = obj.get(key);
//			if (el == null || el.isJsonNull()) {
//				return null;
//			}
//			return el.getAsString();
//		} catch (Exception e) {
//			return null;
//		}
//	}

	private static String buildEtag(byte[] content) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(content == null ? new byte[0] : content);
			StringBuilder sb = new StringBuilder(hash.length * 2 + 2);
			sb.append('"');
			for (byte b : hash) {
				sb.append(String.format("%02x", b));
			}
			sb.append('"');
			return sb.toString();
		} catch (Exception e) {
			return "\"" + UUID.randomUUID().toString().replace("-", "") + "\"";
		}
	}

	/**
	 * 	发送OpenAI格式的JSON响应
	 * @param ctx
	 * @param data
	 */
	private void sendOpenAIJsonResponse(ChannelHandlerContext ctx, Object data) {
		String json = JsonUtil.toJson(data);
		byte[] content = json.getBytes(StandardCharsets.UTF_8);

		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
		response.headers().set(HttpHeaderNames.ETAG, buildEtag(content));
		response.headers().set("X-Powered-By", "Express");
		// 添加CORS头
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		//response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
		response.headers().set(HttpHeaderNames.CONNECTION, "alive");
		response.headers().set(HttpHeaderNames.DATE, this.sdf.format(new Date()));
		
		response.content().writeBytes(content);

		ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				ctx.close();
			}
		});
	}
	
	/**
	 * 	发送OpenAI格式的错误响应并清理资源
	 * @param ctx
	 * @param httpStatus
	 * @param openAiErrorCode
	 * @param message
	 * @param param
	 */
	public void sendOpenAIErrorResponseWithCleanup(ChannelHandlerContext ctx, int httpStatus, String openAiErrorCode, String message, String param) {
		String type = "invalid_request_error";
		// 通过code判断错误类型
		if(httpStatus == 401) {
			type = "authentication_error";
		}
		if(httpStatus == 403) {
			type = "permission_error";
		}
		if(httpStatus == 404 || httpStatus == 400) {
			type = "invalid_request_error";
		}
		if(httpStatus == 429) {
			type = "rate_limit_error";
		}
		if(httpStatus == 500 || httpStatus == 502 || httpStatus == 503 || httpStatus == 504) {
			type = "server_error";
		}
		
		Map<String, Object> error = new HashMap<>();
		error.put("message", message);
		error.put("type", type);
		error.put("code", openAiErrorCode);
		error.put("param", param);
		
		Map<String, Object> response = new HashMap<>();
		response.put("error", error);
		sendOpenAIJsonResponseWithCleanup(ctx, response, HttpResponseStatus.valueOf(httpStatus));
	}
	
	
	/**
	 * 	发送OpenAI格式的JSON响应并清理资源
	 * @param ctx
	 * @param data
	 * @param httpStatus
	 */
	private void sendOpenAIJsonResponseWithCleanup(ChannelHandlerContext ctx, Object data, HttpResponseStatus httpStatus) {
		String json = JsonUtil.toJson(data);
		byte[] content = json.getBytes(StandardCharsets.UTF_8);

		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, httpStatus);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
		response.headers().set(HttpHeaderNames.ETAG, buildEtag(content));
		response.headers().set("X-Powered-By", "Express");
		// 添加CORS头
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
		response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
		response.headers().set(HttpHeaderNames.DATE, this.sdf.format(new Date()));
		
		
		response.content().writeBytes(content);

		ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				ctx.close();
			}
		});
	}
	
	/**
	 * 解析节点名称
	 */
	private String resolveNodeName(String nodeId) {
		if (nodeId == null || nodeId.isBlank()) {
			return "本机";
		}
		try {
			LlamaHubNode node = NodeManager.getInstance().getNode(nodeId);
			if (node != null && node.getName() != null && !node.getName().isBlank()) {
				return node.getName();
			}
		} catch (Exception e) {
		}
		return nodeId;
	}

	/**
	 * 	当连接断开时调用，用于清理{@link #channelConnectionMap}
	 * 
	 * @param ctx
	 * @throws Exception
	 */
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		// 关闭正在进行的链接
		synchronized (this.channelConnectionMap) {
			HttpURLConnection conn = this.channelConnectionMap.remove(ctx);
			if (conn != null) {
				try {
					conn.disconnect();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
	}
}
