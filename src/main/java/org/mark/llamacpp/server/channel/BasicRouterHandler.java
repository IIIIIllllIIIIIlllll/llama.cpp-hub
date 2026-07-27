package org.mark.llamacpp.server.channel;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.mark.llamacpp.server.BuildInfo;
import org.mark.llamacpp.server.LlamaHubNode;
import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.NodeManager;
import org.mark.llamacpp.server.controller.BaseController;
import org.mark.llamacpp.server.controller.AutoLoadPolicyController;
import org.mark.llamacpp.server.controller.BuildController;
import org.mark.llamacpp.server.controller.ChatStateController;
import org.mark.llamacpp.server.controller.EasyChatController;
import org.mark.llamacpp.server.controller.HuggingFaceController;
import org.mark.llamacpp.server.controller.LlamacppController;
import org.mark.llamacpp.server.controller.ModelActionController;
import org.mark.llamacpp.server.controller.ModelInfoController;
import org.mark.llamacpp.server.controller.ModelPathController;
import org.mark.llamacpp.server.controller.NodeController;
import org.mark.llamacpp.server.controller.PerplexityController;
import org.mark.llamacpp.server.controller.ProxyController;
import org.mark.llamacpp.server.controller.ParamController;
import org.mark.llamacpp.server.controller.SystemController;
import org.mark.llamacpp.server.controller.CertController;
import org.mark.llamacpp.server.controller.ToolController;
import org.mark.llamacpp.server.controller.UsageReportController;
import org.mark.llamacpp.server.security.ApiKeyValidator;
import org.mark.test.mcp.DefaultMcpServiceImpl;
import org.mark.test.mcp.IMCPTool;
import org.mark.test.mcp.struct.McpToolInputSchema;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.tools.ParamTool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;

/**
 * 	基本路由处理器。 实现本项目用到的API端点。
 */
public class BasicRouterHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

	private static final Logger logger = LoggerFactory.getLogger(BasicRouterHandler.class);

	private static final String I18N_INTERNAL = "api.error.internal";
	private static final String I18N_METHOD_GET_ONLY = "common.method.get.only";
	private static final String I18N_METHOD_POST_ONLY = "common.method.post.only";
	private static final String I18N_BODY_EMPTY = "api.error.body.empty";
	private static final String I18N_BODY_PARSE = "api.error.body.parse";
	private static final String I18N_PARAM_MODEL_ID_REQUIRED = "api.error.param.modelId.required";
	private static final String I18N_FILE_NOT_FOUND = "api.error.file.notfound";
	private static final String I18N_REQUEST_PARSE_FAILED = "api.error.request.parse.failed";
	private static final String I18N_DIRECTORY_ACCESS_DENIED = "api.error.directory.access.denied";
	private static final String I18N_PROPS_GET_FAILED = "api.error.props.get.failed";
	private static final String I18N_PROPS_REMOTE_FAILED = "api.error.props.remote.failed";
	private static final String I18N_MODEL_LOAD_FAILED = "api.error.model.load.failed";
	private static final String I18N_MODEL_REMOTE_LOAD_FAILED = "api.error.model.remote.load.failed";
	private static final String I18N_MODEL_STOP_FAILED = "api.error.model.stop.failed";
	private static final String I18N_MODEL_UNLOAD_FAILED = "api.error.model.unload.failed";
	private static final String I18N_TOOL_LIST_FAILED = "api.error.tool.list.failed";

	private static final ExecutorService async = Executors.newVirtualThreadPerTaskExecutor();
	
	private static final List<BaseController> pipeline = new LinkedList<>();
	
	
	static {
		pipeline.add(new ChatStateController());
		pipeline.add(new EasyChatController());
		pipeline.add(new HuggingFaceController());
		pipeline.add(new LlamacppController());
		pipeline.add(new ModelActionController());
		pipeline.add(new PerplexityController());
		pipeline.add(new ModelInfoController());
		pipeline.add(new ModelPathController());
		pipeline.add(new NodeController());
		pipeline.add(new ProxyController());
		pipeline.add(new ParamController());
		pipeline.add(new ToolController());
		pipeline.add(new SystemController());
		pipeline.add(new UsageReportController());
		pipeline.add(new AutoLoadPolicyController());
		pipeline.add(new CertController());
		pipeline.add(new BuildController());
	}
	
	

	public BasicRouterHandler() {

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
	
	
	/**
	 * 	真正处理请求的地方
	 * @param ctx
	 * @param request
	 */
	private void handleRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		if (!request.decoderResult().isSuccess()) {
			LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_REQUEST_PARSE_FAILED);
			return;
		}
		// 本服务器只发送压缩响应，不接受压缩请求体
		if (request.headers().contains(HttpHeaderNames.CONTENT_ENCODING)) {
			LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.UNSUPPORTED_MEDIA_TYPE,
					"Compressed request body is not supported");
			return;
		}
		String uri = request.uri();
		// 剥离查询参数用于路由判断，避免 startsWith/equals 误判
		String routingUri = uri.indexOf('?') > 0 ? uri.substring(0, uri.indexOf('?')) : uri;
		
		// 这里是日志专区
		// 1.
		if(LlamaServer.logRequestUrl) {
			logger.info("DEBUG - 收到请求：{}", uri);	
		}
		// 2.
		if(LlamaServer.logRequestHeader) {
			logger.info("DEBUG - 请求头：{}", request.headers());
		}
		// 3.
		if(LlamaServer.logRequestBody) {
			// 只打印前 8KB，避免大请求体整串拷贝刷日志
			ByteBuf body = request.content();
			int bodySize = body.readableBytes();
			int logLen = Math.min(bodySize, 8192);
			logger.info("DEBUG - 请求体：{}{}", body.toString(body.readerIndex(), logLen, CharsetUtil.UTF_8),
					bodySize > logLen ? "...(已截断，共" + bodySize + "字节)" : "");
		}
		
		// 傻逼浏览器不知道为什么一直在他妈的访问/.well-known/appspecific/com.chrome.devtools.json
		if ("/.well-known/appspecific/com.chrome.devtools.json".equals(uri)) {
			ctx.close();
			return;
		}
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}

		// 安全验证：如果启用了 API Key，所有路径都需要验证
		// /v1 路径由 LlamaRouterHandler 验证（Bearer/x-api-key）
		// 其他路径（WebUI、/api/*、管理接口）同样验证，浏览器返回登录页
		if (LlamaServer.isApiKeyValidationEnabled() && !routingUri.startsWith("/v1")) {
			String clientIp = ApiKeyValidator.getClientIp(ctx, request);
			if (!ApiKeyValidator.validate(request, clientIp)) {
				ApiKeyValidator.sendUnauthorized(ctx, request);
				return;
			}
		}

		try {
			// 处理模型API请求
			if (this.isApiRequest(routingUri)) {
				if (routingUri.equals("/llama.cpp/props")) {
					this.handleProps(ctx, request);
					return;
				}
				if (routingUri.equals("/llama.cpp/tools")) {
					this.handleTools(ctx, request);
					return;
				}
				if (routingUri.equals("/llama.cpp/models/load")) {
					this.handleLoadModel(ctx, request);
					return;
				}
				if (routingUri.equals("/llama.cpp/models/unload")) {
					this.handleUnloadModel(ctx, request);
					return;
				}
				boolean handled = false;
				for (BaseController c : pipeline) {
					handled = c.handleRequest(routingUri, ctx, request);
					if (handled) {
						break;
					}
				}
				if (!handled) {
					ctx.fireChannelRead(request.retain());
				}
				return;
			}
			// 断言一下请求方式
			this.assertRequestMethod(request.method() != HttpMethod.GET, I18N_METHOD_GET_ONLY);
			// 解码URI
			String path = URLDecoder.decode(uri, "UTF-8");
			if(path.indexOf('?') > 0) {
				path = path.substring(0, path.indexOf('?'));
			}
			boolean isRootRequest = path.equals("/");

			if (isRootRequest) {
				path = isMobileRequest(request) ? "/index-new.html" : "/index.html";
			}
			if (path.equals("/llama.cpp") || path.equals("/llama.cpp/")) {
				path = "/llama.cpp/index.html";
			}
			// 
			URL url = LlamaServer.class.getResource("/web" + path);

			if (url == null) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.NOT_FOUND, I18N_FILE_NOT_FOUND + ": " + path);
				return;
			}
			// 对于非API请求，只允许访问静态文件，不允许目录浏览
			// 首先尝试从resources目录获取文件
			File file = Paths.get(url.toURI()).toFile();
			if (!file.exists()) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.NOT_FOUND, I18N_FILE_NOT_FOUND + ": " + path);
				return;
			}
			if (file.isDirectory()) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.FORBIDDEN, I18N_DIRECTORY_ACCESS_DENIED);
			} else {
				LlamaServer.sendStaticFile(ctx, file, request);
			}
		} catch (RequestMethodException e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(e.getMessage()));
		} catch (Exception e) {
			logger.info("处理静态文件请求时发生错误", e);
			LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, I18N_INTERNAL);
		}
	}

	private boolean isMobileRequest(FullHttpRequest request) {
		if (request == null) {
			return false;
		}
		String chMobile = request.headers().get("Sec-CH-UA-Mobile");
		if (chMobile != null && chMobile.indexOf("?1") >= 0) {
			return true;
		}
		String userAgent = request.headers().get("User-Agent");
		if (userAgent == null || userAgent.isBlank()) {
			return false;
		}
		String ua = userAgent.toLowerCase();
		return ua.contains("mobi")
				|| ua.contains("android")
				|| ua.contains("iphone")
				|| ua.contains("ipad")
				|| ua.contains("ipod")
				|| ua.contains("windows phone")
				|| ua.contains("webos")
				|| ua.contains("blackberry")
				|| ua.contains("opera mini")
				|| ua.contains("opera mobi");
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		for(BaseController controller : pipeline) {
			controller.inactive(ctx);
		}
		// 事件通知
		super.channelInactive(ctx);
	}
	
	
	/**
	 * 	简单的断言。
	 * @param check
	 * @param message
	 * @throws RequestMethodException
	 */
	private void assertRequestMethod(boolean check, String message) throws RequestMethodException {
		if (check)
			throw new RequestMethodException(message);
	}
	
	/**
	 * 是否为llama.cpp 相关的API请求。
	 * <p>整合了路由层定义的所有 OpenAI 风格端点及系统内部 API。</p>
	 * @param uri 请求路径
	 * @return true 如果是 API 请求，否则 false
	 */
	private boolean isApiRequest(String uri) {
		if (uri == null) {
			return false;
		}
		// Tier 1: 精确匹配已知固定端点
		if (LlamaApiPathRegistry.EXACT_PATHS.contains(uri)) {
			return true;
		}
		// Tier 2: /api/ 前缀（所有控制器 API）
		if (uri.startsWith("/api/")) {
			return true;
		}
		// Tier 3: /v1 前缀（OpenAI 兼容协议）
		if (uri.startsWith("/v1")) {
			return true;
		}
		return false;
	}

	private void handleProps(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, I18N_METHOD_GET_ONLY);

		try {
			Map<String, String> params = ParamTool.getQueryParam(request.uri());
			String model = params.get("model");
			String autoload = params.get("autoload");

			if (model == null || model.trim().isEmpty()) {
				this.handleServerProps(ctx);
			} else {
				this.handleModelProps(ctx, model.trim(), autoload != null && Boolean.parseBoolean(autoload));
			}
		} catch (Exception e) {
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, I18N_PROPS_GET_FAILED + ": " + e.getMessage());
		}
	}

	private void handleServerProps(ChannelHandlerContext ctx) {
		JsonObject result = new JsonObject();
		result.addProperty("role", "router");
		result.addProperty("max_instances", 99);
		result.addProperty("models_autoload", true);
		result.addProperty("model_alias", "llama-server");
		result.addProperty("model_path", "none");

		JsonObject defaultGenSettings = new JsonObject();
		defaultGenSettings.add("params", null);
		defaultGenSettings.addProperty("n_ctx", 0);
		result.add("default_generation_settings", defaultGenSettings);

		result.add("ui_settings", new JsonObject());
		result.add("webui_settings", new JsonObject());
		result.addProperty("build_info", BuildInfo.getTag());
		result.addProperty("cors_proxy_enabled", true);

		LlamaServer.sendExpressJsonResponse(ctx, HttpResponseStatus.OK, result, true);
	}

	private void handleModelProps(ChannelHandlerContext ctx, String modelName, boolean autoload) {
		LlamaServerManager manager = LlamaServerManager.getInstance();
		String modelId = manager.resolveModelId(modelName);

		if (modelId != null) {
			if (!manager.getLoadedProcesses().containsKey(modelId)) {
				if (autoload) {
					String err = manager.autoLoadModelFromConfig(modelId, 600000);
					if (err == null) {
						Integer port = manager.getModelPort(modelId);
						if (port != null) {
							this.forwardProps(ctx, modelId, port);
							return;
						}
					}
				}
			} else {
				Integer port = manager.getModelPort(modelId);
				if (port != null) {
					this.forwardProps(ctx, modelId, port);
					return;
				}
			}
		}

		// 本地未加载或未找到，搜索远程节点
		logger.info("[Props路由] 本地模型未加载，开始搜索远程节点: model={}", modelName);
		String remoteUrl = this.resolvePropsRemoteUrl(modelName);
		if (remoteUrl != null) {
			this.forwardPropsToRemote(ctx, remoteUrl);
			return;
		}

		LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.NOT_FOUND, "Model not found: " + modelName);
	}

	private String resolvePropsRemoteUrl(String modelName) {
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
					logger.info("[Props路由] 远程节点匹配成功: model={}, nodeId={}", modelName, node.getNodeId());
					return node.getBaseUrl() + "/llama.cpp/props?model=" + modelName;
				}
			} catch (Exception ignore) {
			}
		}
		return null;
	}

	private void forwardPropsToRemote(ChannelHandlerContext ctx, String targetUrl) {
		try {
			URL url = URI.create(targetUrl).toURL();
			HttpURLConnection connection = (HttpURLConnection) url.openConnection();
			if (connection instanceof javax.net.ssl.HttpsURLConnection) {
				try {
					NodeManager.trustAllCerts((javax.net.ssl.HttpsURLConnection) connection);
				} catch (Exception e) {
					logger.warn("配置HTTPS证书信任失败: {}", e.getMessage());
				}
			}
			connection.setRequestMethod("GET");
			connection.setConnectTimeout(30000);
			connection.setReadTimeout(30000);
			int responseCode = connection.getResponseCode();
			String responseBody;

			if (responseCode >= 200 && responseCode < 300) {
				try (BufferedReader br = new BufferedReader(
						new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
					StringBuilder sb = new StringBuilder();
					String line;
					while ((line = br.readLine()) != null) {
						sb.append(line);
					}
					responseBody = sb.toString();
				}
				com.google.gson.JsonObject json = JsonUtil.fromJson(responseBody, com.google.gson.JsonObject.class);
				json.addProperty("ui", true);
				LlamaServer.sendExpressRawJsonResponse(ctx, HttpResponseStatus.OK, json.toString().getBytes(CharsetUtil.UTF_8), true);
			} else {
				try (BufferedReader br = new BufferedReader(
						new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
					StringBuilder sb = new StringBuilder();
					String line;
					while ((line = br.readLine()) != null) {
						sb.append(line);
					}
					responseBody = sb.toString();
				}
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_GATEWAY, I18N_PROPS_REMOTE_FAILED + ": " + responseBody);
			}
			connection.disconnect();
		} catch (Exception e) {
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, I18N_PROPS_REMOTE_FAILED + ": " + e.getMessage());
		}
	}

	private void forwardProps(ChannelHandlerContext ctx, String modelId, int port) {
		try {
			String targetUrl = String.format("http://localhost:%d/props", port);
			URL url = URI.create(targetUrl).toURL();
			HttpURLConnection connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("GET");
			connection.setConnectTimeout(30000);
			connection.setReadTimeout(30000);
			int responseCode = connection.getResponseCode();
			String responseBody;

			if (responseCode >= 200 && responseCode < 300) {
				try (BufferedReader br = new BufferedReader(
						new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
					StringBuilder sb = new StringBuilder();
					String line;
					while ((line = br.readLine()) != null) {
						sb.append(line);
					}
					responseBody = sb.toString();
				}
				JsonObject json = JsonUtil.fromJson(responseBody, JsonObject.class);
				json.addProperty("ui", true);
				LlamaServer.sendExpressRawJsonResponse(ctx, HttpResponseStatus.OK, json.toString().getBytes(CharsetUtil.UTF_8), true);
			} else {
				try (BufferedReader br = new BufferedReader(
						new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
					StringBuilder sb = new StringBuilder();
					String line;
					while ((line = br.readLine()) != null) {
						sb.append(line);
					}
					responseBody = sb.toString();
				}
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_GATEWAY, I18N_PROPS_GET_FAILED + ": " + responseBody);
			}
			connection.disconnect();
		} catch (Exception e) {
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, I18N_PROPS_GET_FAILED + ": " + e.getMessage());
		}
	}

	private void handleLoadModel(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);
		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_BODY_EMPTY);
				return;
			}

			JsonObject obj = JsonUtil.fromJson(content, JsonObject.class);
			if (obj == null) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_BODY_PARSE);
				return;
			}

			String modelId = JsonUtil.getJsonString(obj, "model");
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_PARAM_MODEL_ID_REQUIRED);
				return;
			}
			modelId = modelId.trim();

			LlamaServerManager manager = LlamaServerManager.getInstance();

			if (manager.getLoadedProcesses().containsKey(modelId)) {
				JsonObject resp = new JsonObject();
				resp.addProperty("success", true);
				LlamaServer.sendJsonResponse(ctx, resp);
				return;
			}
			if (manager.isLoading(modelId)) {
				JsonObject resp = new JsonObject();
				resp.addProperty("success", true);
				LlamaServer.sendJsonResponse(ctx, resp);
				return;
			}

			if (manager.resolveModelId(modelId) != null) {
				logger.info("[llama.cpp API] 加载模型: modelId={}", modelId);
				String err = manager.autoLoadModelFromConfig(modelId, 600000);
				if (err == null) {
					JsonObject resp = new JsonObject();
					resp.addProperty("success", true);
					LlamaServer.sendJsonResponse(ctx, resp);
					return;
				}
				logger.warn("[llama.cpp API] 本地加载失败: modelId={}, error={}", modelId, err);
			}

			logger.info("[llama.cpp API] 本地无法加载，开始搜索远程节点: modelId={}", modelId);
			String remoteUrl = this.resolveLoadModelRemoteUrl(modelId);
			if (remoteUrl != null) {
				this.forwardLoadModelToRemote(ctx, content, remoteUrl);
				return;
			}

			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.NOT_FOUND, "Model not found: " + modelId);
		} catch (Exception e) {
			logger.info("加载模型时发生错误", e);
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, I18N_MODEL_LOAD_FAILED + ": " + e.getMessage());
		}
	}

	private String resolveLoadModelRemoteUrl(String modelName) {
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
					logger.info("[llama.cpp API] 远程节点匹配成功: model={}, nodeId={}", modelName, node.getNodeId());
					return node.getBaseUrl() + "/llama.cpp/models/load";
				}
			} catch (Exception ignore) {
			}
		}
		return null;
	}

	private void forwardLoadModelToRemote(ChannelHandlerContext ctx, String content, String targetUrl) {
		HttpURLConnection connection = null;
		try {
			URL url = URI.create(targetUrl).toURL();
			connection = (HttpURLConnection) url.openConnection();
			if (connection instanceof javax.net.ssl.HttpsURLConnection) {
				try {
					NodeManager.trustAllCerts((javax.net.ssl.HttpsURLConnection) connection);
				} catch (Exception e) {
					logger.warn("配置HTTPS证书信任失败: {}", e.getMessage());
				}
			}
			connection.setRequestMethod("POST");
			connection.setDoOutput(true);
			connection.setConnectTimeout(60000);
			connection.setReadTimeout(600000);

			byte[] outBytes = content.getBytes(StandardCharsets.UTF_8);
			connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
			connection.setRequestProperty("Content-Length", String.valueOf(outBytes.length));
			try (OutputStream os = connection.getOutputStream()) {
				os.write(outBytes);
			}

			int responseCode = connection.getResponseCode();
			String responseBody;
			if (responseCode >= 200 && responseCode < 300) {
				try (BufferedReader br = new BufferedReader(
						new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
					StringBuilder sb = new StringBuilder();
					String line;
					while ((line = br.readLine()) != null) {
						sb.append(line);
					}
					responseBody = sb.toString();
				}
				byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
				LlamaServer.sendExpressRawJsonResponse(ctx, HttpResponseStatus.OK, bytes, true);
			} else {
				try (BufferedReader br = new BufferedReader(
						new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
					StringBuilder sb = new StringBuilder();
					String line;
					while ((line = br.readLine()) != null) {
						sb.append(line);
					}
					responseBody = sb.toString();
				}
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_GATEWAY,
					I18N_MODEL_REMOTE_LOAD_FAILED + ": " + (responseBody != null ? responseBody : "HTTP " + responseCode));
			}
			connection.disconnect();
		} catch (Exception e) {
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR,
				I18N_MODEL_REMOTE_LOAD_FAILED + ": " + e.getMessage());
		} finally {
			if (connection != null) {
				try {
					connection.disconnect();
				} catch (Exception ignore) {
				}
			}
		}
	}

	private void handleUnloadModel(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);
		try {
			byte[] bodyBytes = JsonUtil.readRequestBytes(request);
			if (bodyBytes == null) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_BODY_EMPTY);
				return;
			}

			JsonObject obj = JsonUtil.tryParseObject(bodyBytes);
			if (obj == null) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_BODY_PARSE);
				return;
			}

			String modelId = JsonUtil.getJsonString(obj, "model");
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_PARAM_MODEL_ID_REQUIRED);
				return;
			}
			modelId = modelId.trim();

			LlamaServerManager manager = LlamaServerManager.getInstance();

			if (!manager.getLoadedProcesses().containsKey(modelId)) {
				JsonObject resp = new JsonObject();
				resp.addProperty("success", true);
				LlamaServer.sendJsonResponse(ctx, resp);
				return;
			}

			logger.info("[llama.cpp API] 卸载模型: modelId={}", modelId);
			boolean success = manager.stopModel(modelId);
			if (!success) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, I18N_MODEL_STOP_FAILED);
				return;
			}

			JsonObject resp = new JsonObject();
			resp.addProperty("success", true);
			LlamaServer.sendJsonResponse(ctx, resp);
		} catch (Exception e) {
			logger.info("卸载模型时发生错误", e);
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, I18N_MODEL_UNLOAD_FAILED + ": " + e.getMessage());
		}
	}

	private void handleTools(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, I18N_METHOD_GET_ONLY);

		DefaultMcpServiceImpl mcpService = LlamaServer.getMcpServerService();
		if (mcpService == null) {
			LlamaServer.sendExpressJsonResponse(ctx, HttpResponseStatus.OK, new JsonArray(), true);
			return;
		}

		try {
			org.mark.test.mcp.struct.McpToolRegistry registry = mcpService.getToolRegistry();
			if (registry == null) {
				LlamaServer.sendExpressJsonResponse(ctx, HttpResponseStatus.OK, new JsonArray(), true);
				return;
			}

			JsonArray result = new JsonArray();
			List<IMCPTool> tools = registry.resolve("llama_hub_info");
			if (tools == null || tools.isEmpty()) {
				LlamaServer.sendExpressJsonResponse(ctx, HttpResponseStatus.OK, result, true);
				return;
			}

			for (IMCPTool tool : tools) {
				if (tool == null) {
					continue;
				}

				JsonObject item = new JsonObject();
				item.addProperty("display_name", tool.getMcpTitle() != null && !tool.getMcpTitle().isBlank() ? tool.getMcpTitle() : tool.getMcpName());
				item.addProperty("tool", tool.getMcpName());
				item.addProperty("type", "builtin");

				JsonObject permissions = new JsonObject();
				permissions.addProperty("write", tool.isWritePermission());
				item.add("permissions", permissions);

				JsonObject definition = new JsonObject();
				definition.addProperty("type", "function");

				JsonObject func = new JsonObject();
				func.addProperty("name", tool.getMcpName());
				String desc = tool.getMcpDescription();
				if (desc != null && !desc.isBlank()) {
					func.addProperty("description", desc);
				}

				McpToolInputSchema schema = tool.getInputSchema();
				if (schema != null) {
					JsonObject schemaJson = schema.toJsonObject();
					func.add("parameters", schemaJson);
				} else {
					JsonObject emptyParams = new JsonObject();
					emptyParams.addProperty("type", "object");
					emptyParams.add("properties", new JsonObject());
					func.add("parameters", emptyParams);
				}

				definition.add("function", func);
				item.add("definition", definition);
				result.add(item);
			}

			LlamaServer.sendExpressJsonResponse(ctx, HttpResponseStatus.OK, result, true);
		} catch (Exception e) {
			logger.info("获取工具列表失败", e);
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, I18N_TOOL_LIST_FAILED + ": " + e.getMessage());
		}
	}
}
