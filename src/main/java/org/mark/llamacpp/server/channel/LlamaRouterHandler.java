package org.mark.llamacpp.server.channel;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;

import org.mark.llamacpp.server.LlamaHubNode;
import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.NodeManager;
import org.mark.llamacpp.server.service.AnthropicService;
import org.mark.llamacpp.server.service.OpenAIService;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.security.ApiKeyValidator;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.tools.ParamTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 	服务端的主要实现。
 */
public class LlamaRouterHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

	private static final Logger logger = LoggerFactory.getLogger(LlamaRouterHandler.class);

	private static final String I18N_INTERNAL = "api.error.internal";
	private static final String I18N_METHOD_GET_ONLY = "common.method.get.only";
	private static final String I18N_METHOD_POST_ONLY = "common.method.post.only";
	private static final String I18N_BODY_EMPTY = "api.error.body.empty";
	private static final String I18N_PARAM_MODEL_ID_REQUIRED = "api.error.param.modelId.required";
	private static final String I18N_PARAM_MODEL_MISSING = "api.error.param.model.missing";
	private static final String I18N_PARAM_PARSE_FAILED = "api.error.param.parse.failed";
	private static final String I18N_MODEL_NOT_LOADED = "api.error.model.not.loaded";
	private static final String I18N_MODEL_RESPONSE_NOT_JSON = "api.error.model.response.not.json";
	private static final String I18N_REMOTE_RESPONSE_FORMAT = "api.error.remote.response.format";
	private static final String I18N_SLOTS_GET_FAILED = "api.error.slots.get.failed";
	private static final String I18N_CONTROL_FAILED = "api.error.control.failed";
	private static final String I18N_CONTROL_REMOTE_FAILED = "api.error.control.remote.failed";
	private static final String I18N_MODEL_HTTP_ERROR = "api.error.model.http.error";
	private static final String I18N_REMOTE_HTTP_ERROR = "api.error.remote.http.error";

	private static final ExecutorService async = Executors.newVirtualThreadPerTaskExecutor();
	
	/**
	 * 	OpenAI接口的实现。
	 */
	private OpenAIService openAIServerHandler = new OpenAIService();
	
	/**
	 * 	Anthropic接口的实现。
	 */
	private AnthropicService anthropicService = new AnthropicService();
	
	public LlamaRouterHandler() {

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
		String uri = request.uri();
		
		// 处理CORS预检请求
		if (request.method() == HttpMethod.OPTIONS) {
			this.handleCorsPreflight(ctx, request);
			return;
		}
		
		this.handleApiRequest(ctx, request, uri);
		return;
	}

	/**
	 * 处理API请求
	 */
    private void handleApiRequest(ChannelHandlerContext ctx, FullHttpRequest request, String uri) {
		try {
		// 验证key
		if (uri.startsWith("/v1") && request.method() != HttpMethod.OPTIONS) {
			String clientIp = ApiKeyValidator.getClientIp(ctx, request);
			if (!ApiKeyValidator.validate(request, clientIp)) {
				ApiKeyValidator.sendUnauthorized(ctx, request);
				return;
			}
		}
			
			// OpenAI API 端点
			// 获取模型列表
			if (uri.startsWith("/llama.cpp/v1/models")) {
				this.openAIServerHandler.handleOpenAIModelsRequest(ctx, request);
				return;
			}
			if (uri.startsWith("/v1/models") || uri.startsWith("/models")) {
				if (this.isAnthropicClient(request)) {
					this.anthropicService.handleModelsRequest(ctx, request);
				} else {
					this.openAIServerHandler.handleOpenAIModelsRequest(ctx, request);
				}
				return;
			}
			// 文本补全
			if (uri.startsWith("/v1/completions") || uri.startsWith("/completions")) {
				this.openAIServerHandler.handleOpenAICompletionsRequest(ctx, request);
				return;
			}
			if (uri.startsWith("/v1/embeddings") || uri.startsWith("/embeddings")) {
				this.openAIServerHandler.handleOpenAIEmbeddingsRequest(ctx, request);
				return;
			}
			if (uri.startsWith("/v1/responses") || uri.startsWith("/responses")) {
				this.openAIServerHandler.handleOpenAIResponsesRequest(ctx, request);
				return;
			}
			if (uri.startsWith("/v1/rerank") || uri.startsWith("/v1/reranking") || uri.startsWith("/rerank") || uri.startsWith("/reranking")) {
				this.openAIServerHandler.handleOpenAIRerankRequest(ctx, request);
				return;
			}
			// 音频
			if(uri.startsWith("/v1/audio/transcriptions") || uri.startsWith("/audio/transcriptions")) {
				this.openAIServerHandler.handleOpenAIAudioTranscriptionsRequest(ctx, request);
				return;
			}
			
			// Anthropic API 端点 (Messages)
			if (uri.startsWith("/v1/messages/count_tokens")) {
				this.anthropicService.handleMessagesCountTokensRequest(ctx, request);
				return;
			}
			// /llama.cpp/slots and /slots - proxy to model's /slots endpoint
			if (uri.startsWith("/llama.cpp/slots") || uri.startsWith("/slots")) {
				this.handleSlotsRequest(ctx, request);
				return;
			}
			// /control endpoint - proxy to model's /v1/chat/completions/control
			if (uri.startsWith("/llama.cpp/v1/chat/completions/control")) {
				this.handleControlRequest(ctx, request);
				return;
			}

			this.sendJsonResponse(ctx, ApiResponse.error("404 Not Found"));
		} catch (Exception e) {
			logger.info("处理API请求时发生错误", e);
			this.sendJsonResponse(ctx, ApiResponse.error(I18N_INTERNAL));
		}
    }
    
    /**
     * 处理CORS预检请求
     */
    private void handleCorsPreflight(ChannelHandlerContext ctx, FullHttpRequest request) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type, Authorization");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        
        ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) {
                ctx.close();
            }
        });
    }

 /**
  *
  * @param ctx
  * @param data
  */
 private void sendJsonResponse(ChannelHandlerContext ctx, Object data) {
		String json = JsonUtil.toJson(data);
		byte[] content = json.getBytes(CharsetUtil.UTF_8);

		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
		// 添加CORS头
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type, Authorization");
		response.content().writeBytes(content);

		ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				ctx.close();
			}
		});
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		// 事件通知
		this.openAIServerHandler.channelInactive(ctx);
		this.anthropicService.channelInactive(ctx);
		super.channelInactive(ctx);
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		logger.info("处理请求时发生异常", cause);
		ctx.close();
	}

	private boolean isAnthropicClient(FullHttpRequest request) {
		String anthropicVersion = request.headers().get("anthropic-version");
		if (anthropicVersion != null && !anthropicVersion.isBlank()) {
			return true;
		}
		if (request.headers().contains("x-api-key")) {
			return true;
		}
		return false;
	}

	/**
	 * 处理 /llama.cpp/slots 和 /slots 请求，代理到对应模型的 /slots 端点。
	 * 用法：/llama.cpp/slots?model=Qwen3.5-0.8B-Q4_K_M
	 */
	private void handleSlotsRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		if (request.method() != HttpMethod.GET) {
			this.sendJsonResponse(ctx, ApiResponse.error(I18N_METHOD_GET_ONLY));
			return;
		}
		try {
			Map<String, String> params = ParamTool.getQueryParam(request.uri());
			String model = params.get("model");
			if (model == null || model.trim().isEmpty()) {
				this.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_MODEL_ID_REQUIRED));
				return;
			}
			LlamaServerManager manager = LlamaServerManager.getInstance();
			if (manager.getLoadedProcesses().containsKey(model)) {
				com.google.gson.JsonObject result = manager.handleModelSlotsGet(model);
				this.sendJsonResponse(ctx, ApiResponse.success(result));
				return;
			}

			logger.info("[Slots路由] 本地模型未加载，开始搜索远程节点: model={}", model);
			for (LlamaHubNode node : NodeManager.getInstance().listEnabledNodes()) {
				String path = "llama.cpp/slots?model=" + model;
				NodeManager.HttpResult result = NodeManager.getInstance().callRemoteApi(node.getNodeId(), "GET", path, null);
				if (result.isSuccess()) {
					com.google.gson.JsonElement parsed = null;
					try {
						parsed = JsonUtil.fromJson(result.getBody(), com.google.gson.JsonElement.class);
					} catch (Exception ignore) {
					}
					if (parsed != null) {
						this.sendJsonResponse(ctx, parsed);
						return;
					}
				}
			}
			this.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_NOT_LOADED + ": " + model));
		} catch (Exception e) {
			logger.info("获取slots信息时发生错误", e);
			this.sendJsonResponse(ctx, ApiResponse.error(I18N_SLOTS_GET_FAILED + ": " + e.getMessage()));
		}
	}

	/**
	 * 处理 /llama.cpp/v1/chat/completions/control 请求，代理到对应模型的 /v1/chat/completions/control 端点。
	 */
	private void handleControlRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		if (request.method() != HttpMethod.POST) {
			this.sendJsonResponse(ctx, ApiResponse.error(I18N_METHOD_POST_ONLY));
			return;
		}
		String content;
		String modelName;
		try {
			content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				this.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_EMPTY));
				return;
			}

			com.google.gson.JsonObject body = JsonUtil.fromJson(content, com.google.gson.JsonObject.class);
			modelName = JsonUtil.getJsonString(body, "model");
			if (modelName == null || modelName.trim().isEmpty()) {
				this.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_MODEL_MISSING));
				return;
			}
			modelName = modelName.trim();
		} catch (Exception e) {
			logger.info("control解析参数失败", e);
			this.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_PARSE_FAILED + ": " + e.getMessage()));
			return;
		}

		LlamaServerManager manager = LlamaServerManager.getInstance();
		String modelId = manager.resolveModelId(modelName);
		if (modelId != null && manager.getLoadedProcesses().containsKey(modelId)) {
			Integer port = manager.getModelPort(modelId);
			if (port != null) {
				this.forwardControlToLocal(ctx, request, content, port);
				return;
			}
		}

		logger.info("[Control路由] 本地模型未加载，开始搜索远程节点: model={}", modelName);
		String[] remoteTarget = this.resolveControlRemoteUrl(modelName);
		if (remoteTarget != null) {
			this.forwardControlToRemote(ctx, request, content, remoteTarget[0], remoteTarget[1]);
			return;
		}

		this.sendJsonResponse(ctx, ApiResponse.error("Model not found: " + modelName));
	}

	private String[] resolveControlRemoteUrl(String modelName) {
		for (LlamaHubNode node : NodeManager.getInstance().listEnabledNodes()) {
			NodeManager.HttpResult result = NodeManager.getInstance().callRemoteApi(node.getNodeId(), "GET", "v1/models", null);
			if (!result.isSuccess()) continue;
			try {
				com.google.gson.JsonObject root = JsonUtil.fromJson(result.getBody(), com.google.gson.JsonObject.class);
				if (root == null) continue;
				boolean found = false;
				if (root.has("models") && root.get("models").isJsonArray()) {
					for (com.google.gson.JsonElement el : root.getAsJsonArray("models")) {
						if (!el.isJsonObject()) continue;
						String key = JsonUtil.getJsonString(el.getAsJsonObject(), "model");
						if (key.isEmpty()) key = JsonUtil.getJsonString(el.getAsJsonObject(), "name");
						if (modelName.equals(key)) { found = true; break; }
					}
				}
				if (!found && root.has("data") && root.get("data").isJsonArray()) {
					for (com.google.gson.JsonElement el : root.getAsJsonArray("data")) {
						if (!el.isJsonObject()) continue;
						String id = JsonUtil.getJsonString(el.getAsJsonObject(), "id", "");
						if (modelName.equals(id)) { found = true; break; }
					}
				}
				if (found) {
					logger.info("[Control路由] 远程节点匹配成功: model={}, nodeId={}", modelName, node.getNodeId());
					return new String[]{node.getBaseUrl() + "/v1/chat/completions/control", node.getApiKey()};
				}
			} catch (Exception ignore) {
			}
		}
		return null;
	}

	private void forwardControlToLocal(ChannelHandlerContext ctx, FullHttpRequest request, String content, int port) {
		HttpURLConnection connection = null;
		try {
			String targetUrl = String.format("http://localhost:%d/v1/chat/completions/control", port);
			connection = (HttpURLConnection) URI.create(targetUrl).toURL().openConnection();
			connection.setRequestMethod("POST");
			connection.setDoOutput(true);
			connection.setConnectTimeout(30000);
			connection.setReadTimeout(30000);

			for (Map.Entry<String, String> entry : request.headers()) {
				String key = entry.getKey();
				if (key == null) continue;
				if ("Host".equalsIgnoreCase(key)
						|| "Connection".equalsIgnoreCase(key)
						|| "Content-Length".equalsIgnoreCase(key)
						|| "Transfer-Encoding".equalsIgnoreCase(key)
						|| "X-Node-Id".equalsIgnoreCase(key)
						|| "Authorization".equalsIgnoreCase(key)
						|| "Cookie".equalsIgnoreCase(key)
						|| "X-Forwarded-For".equalsIgnoreCase(key)
						|| "X-Real-Ip".equalsIgnoreCase(key)) {
					continue;
				}
				connection.setRequestProperty(key, entry.getValue());
			}

			byte[] outBytes = content.getBytes(StandardCharsets.UTF_8);
			connection.setRequestProperty("Content-Length", String.valueOf(outBytes.length));
			try (OutputStream os = connection.getOutputStream()) {
				os.write(outBytes);
			}

			int responseCode = connection.getResponseCode();
			String responseBody = this.readBody(connection, responseCode >= 200 && responseCode < 300);
			com.google.gson.JsonElement parsed = null;
			try {
				parsed = JsonUtil.fromJson(responseBody, com.google.gson.JsonElement.class);
			} catch (Exception ignore) {
			}

			if (responseCode >= 200 && responseCode < 300) {
				if (parsed != null) {
					this.sendJsonResponse(ctx, parsed);
				} else {
					this.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_RESPONSE_NOT_JSON));
				}
				return;
			}

			if (parsed != null) {
				this.sendJsonResponse(ctx, parsed);
				return;
			}
			String msg = responseBody == null || responseBody.isBlank() ? (I18N_MODEL_HTTP_ERROR + ": HTTP " + responseCode) : responseBody;
			this.sendJsonResponse(ctx, ApiResponse.error(msg));
		} catch (Exception e) {
			logger.info("control本地代理失败", e);
			this.sendJsonResponse(ctx, ApiResponse.error(I18N_CONTROL_FAILED + ": " + e.getMessage()));
		} finally {
			if (connection != null) {
				try {
					connection.disconnect();
				} catch (Exception ignore) {
				}
			}
		}
	}

	private void forwardControlToRemote(ChannelHandlerContext ctx, FullHttpRequest request, String content, String targetUrl, String nodeApiKey) {
		HttpURLConnection connection = null;
		try {
			connection = (HttpURLConnection) URI.create(targetUrl).toURL().openConnection();
			if (connection instanceof javax.net.ssl.HttpsURLConnection) {
				try {
					NodeManager.trustAllCerts((javax.net.ssl.HttpsURLConnection) connection);
				} catch (Exception e) {
					logger.warn("配置HTTPS证书信任失败: {}", e.getMessage());
				}
			}
			connection.setRequestMethod("POST");
			connection.setDoOutput(true);
			connection.setConnectTimeout(30000);
			connection.setReadTimeout(30000);

			for (Map.Entry<String, String> entry : request.headers()) {
				String key = entry.getKey();
				if (key == null) continue;
				if ("Host".equalsIgnoreCase(key)
						|| "Connection".equalsIgnoreCase(key)
						|| "Content-Length".equalsIgnoreCase(key)
						|| "Transfer-Encoding".equalsIgnoreCase(key)
						|| "X-Node-Id".equalsIgnoreCase(key)
						|| "Authorization".equalsIgnoreCase(key)
						|| "Cookie".equalsIgnoreCase(key)
						|| "X-Forwarded-For".equalsIgnoreCase(key)
						|| "X-Real-Ip".equalsIgnoreCase(key)) {
					continue;
				}
				connection.setRequestProperty(key, entry.getValue());
			}

			// 添加远程节点的 API Key 认证
			if (nodeApiKey != null && !nodeApiKey.isBlank()) {
				connection.setRequestProperty("Authorization", "Bearer " + nodeApiKey);
			}

			byte[] outBytes = content.getBytes(StandardCharsets.UTF_8);
			connection.setRequestProperty("Content-Length", String.valueOf(outBytes.length));
			try (OutputStream os = connection.getOutputStream()) {
				os.write(outBytes);
			}

			int responseCode = connection.getResponseCode();
			String responseBody = this.readBody(connection, responseCode >= 200 && responseCode < 300);
			com.google.gson.JsonElement parsed = null;
			try {
				parsed = JsonUtil.fromJson(responseBody, com.google.gson.JsonElement.class);
			} catch (Exception ignore) {
			}

			if (responseCode >= 200 && responseCode < 300) {
				if (parsed != null) {
					this.sendJsonResponse(ctx, parsed);
				} else {
					this.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_RESPONSE_FORMAT));
				}
				return;
			}

			if (parsed != null) {
				this.sendJsonResponse(ctx, parsed);
				return;
			}
			String msg = responseBody == null || responseBody.isBlank() ? (I18N_REMOTE_HTTP_ERROR + ": HTTP " + responseCode) : responseBody;
			this.sendJsonResponse(ctx, ApiResponse.error(msg));
		} catch (Exception e) {
			logger.info("control远程代理失败", e);
			this.sendJsonResponse(ctx, ApiResponse.error(I18N_CONTROL_REMOTE_FAILED + ": " + e.getMessage()));
		} finally {
			if (connection != null) {
				try {
					connection.disconnect();
				} catch (Exception ignore) {
				}
			}
		}
	}

	private String readBody(HttpURLConnection connection, boolean ok) {
		if (connection == null) return "";
		java.io.InputStream in = null;
		try {
			in = ok ? connection.getInputStream() : connection.getErrorStream();
			if (in == null) return "";
			try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
				StringBuilder sb = new StringBuilder();
				String line;
				while ((line = br.readLine()) != null) {
					sb.append(line);
				}
				return sb.toString();
			}
		} catch (Exception e) {
			return "";
		} finally {
			if (in != null) {
				try {
					in.close();
				} catch (Exception ignore) {
				}
			}
		}
	}
}
