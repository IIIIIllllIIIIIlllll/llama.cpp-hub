package org.mark.llamacpp.server.controller;

import com.google.gson.JsonObject;
import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.NodeManager;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.service.AutoLoadPolicyManager;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.tools.ParamTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;

public class AutoLoadPolicyController implements BaseController {

	private static final Logger logger = LoggerFactory.getLogger(AutoLoadPolicyController.class);

	//private static final String I18N_METHOD_GET_ONLY = "common.method.get.only";
	//private static final String I18N_METHOD_POST_ONLY = "common.method.post.only";
	private static final String I18N_BODY_EMPTY = "api.error.body.empty";
	private static final String I18N_BODY_PARSE = "api.error.body.parse";
	private static final String I18N_PARAM_MODELID_MISSING = "api.error.param.modelId.missing";
	private static final String I18N_REMOTE_CALL_FAILED = "api.error.remote.call.failed";
	private static final String I18N_REMOTE_NODE_INVALID = "api.error.remote.node.invalid";
	private static final String I18N_REMOTE_NODE_CALL_FAILED = "api.error.remote.node.call.failed";
	private static final String I18N_AUTO_LOAD_UNSUPPORTED_METHOD = "api.error.autoload.unsupported.method";
	private static final String I18N_AUTO_LOAD_MODE_INVALID = "api.error.autoload.mode.invalid";
	private static final String I18N_AUTO_LOAD_AUTO_UNLOAD_INVALID = "api.error.autoload.autoUnload.invalid";
	private static final String I18N_AUTO_LOAD_TIMEOUT_POSITIVE = "api.error.autoload.timeout.positive";
	private static final String I18N_AUTO_LOAD_TIMEOUT_INVALID = "api.error.autoload.timeout.invalid";
	private static final String I18N_AUTO_LOAD_GET_FAILED = "api.error.autoload.get.failed";
	private static final String I18N_AUTO_LOAD_SET_FAILED = "api.error.autoload.set.failed";
	private static final String I18N_AUTO_LOAD_RESET_FAILED = "api.error.autoload.reset.failed";

	@Override
	public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (uri.equals("/api/auto-load/policy")) {
			handlePolicy(ctx, request);
			return true;
		}
		return false;
	}

	private void handlePolicy(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}

		if (request.method() == HttpMethod.GET) {
			handleGetPolicies(ctx, request);
		} else if (request.method() == HttpMethod.PUT) {
			handleSetPolicy(ctx, request);
		} else if (request.method() == HttpMethod.DELETE) {
			handleResetPolicy(ctx, request);
		} else {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_AUTO_LOAD_UNSUPPORTED_METHOD));
		}
	}

	private void handleGetPolicies(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			Map<String, String> params = ParamTool.getQueryParam(request.uri());
			String nodeId = params.get("nodeId");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				logger.info("[自动加载策略] 远程代理获取策略: nodeId={}", nodeId);
				proxyGetRemote(ctx, request, nodeId, "api/auto-load/policy");
				return;
			}
			String modelId = params.get("modelId");
			AutoLoadPolicyManager manager = AutoLoadPolicyManager.getInstance();
			Map<String, Object> data;
			if (modelId != null && !modelId.isBlank()) {
				data = manager.getPolicyForModel(modelId);
			} else {
				data = manager.getAllPolicies();
			}
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("获取自动加载策略失败", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_AUTO_LOAD_GET_FAILED + ": " + e.getMessage()));
		}
	}

	private void handleSetPolicy(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			byte[] content = JsonUtil.readRequestBytes(request);
			if (content == null || content.length == 0) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_EMPTY));
				return;
			}

			JsonObject obj = JsonUtil.fromJson(content, JsonObject.class);
			if (obj == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_PARSE));
				return;
			}

			String nodeId = JsonUtil.getJsonString(obj, "nodeId", "");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				logger.info("[自动加载策略] 远程代理设置策略: nodeId={}, modelId={}", nodeId, JsonUtil.getJsonString(obj, "modelId", ""));
				proxyPutRemote(ctx, request, nodeId, "api/auto-load/policy");
				return;
			}

			String modelId = JsonUtil.getJsonString(obj, "modelId");
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_MODELID_MISSING));
				return;
			}

			AutoLoadPolicyManager manager = AutoLoadPolicyManager.getInstance();

			// 处理自动加载策略
			String mode = JsonUtil.getJsonString(obj, "mode");
			if (mode != null && !mode.isBlank()) {
				if (!("allow".equalsIgnoreCase(mode) || "deny".equalsIgnoreCase(mode))) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_AUTO_LOAD_MODE_INVALID));
					return;
				}
				String error = manager.setModelPolicy(modelId, mode);
				if (error != null) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error(error));
					return;
				}
			}

			// 处理自动卸载策略
			String autoUnload = JsonUtil.getJsonString(obj, "autoUnload");
			if (autoUnload != null && !autoUnload.isBlank()) {
				if (!("allow".equalsIgnoreCase(autoUnload) || "deny".equalsIgnoreCase(autoUnload))) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_AUTO_LOAD_AUTO_UNLOAD_INVALID));
					return;
				}
				String error = manager.setAutoUnloadPolicy(modelId, autoUnload);
				if (error != null) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error(error));
					return;
				}
			}

			// 处理自动卸载超时时间
			if (obj.has("autoUnloadTimeoutMs")) {
				try {
					long timeoutMs = obj.get("autoUnloadTimeoutMs").getAsLong();
					if (timeoutMs <= 0) {
						LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_AUTO_LOAD_TIMEOUT_POSITIVE));
						return;
					}
					String error = manager.setAutoUnloadTimeoutMs(modelId, timeoutMs);
					if (error != null) {
						LlamaServer.sendJsonResponse(ctx, ApiResponse.error(error));
						return;
					}
				} catch (Exception e) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_AUTO_LOAD_TIMEOUT_INVALID));
					return;
				}
			}

			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(null));
		} catch (Exception e) {
			logger.info("设置自动加载策略失败", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_AUTO_LOAD_SET_FAILED + ": " + e.getMessage()));
		}
	}

	private void handleResetPolicy(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			Map<String, String> params = ParamTool.getQueryParam(request.uri());
			String modelId = params.get("modelId");
			String nodeId = params.get("nodeId");

			// 如果查询参数中没有，尝试从请求体获取
			if (modelId == null || modelId.trim().isEmpty()) {
				byte[] content = JsonUtil.readRequestBytes(request);
				if (content != null && content.length > 0) {
					JsonObject obj = JsonUtil.fromJson(content, JsonObject.class);
					if (obj != null) {
						modelId = JsonUtil.getJsonString(obj, "modelId");
						nodeId = JsonUtil.getJsonString(obj, "nodeId", "");
					}
				}
			}

			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				logger.info("[自动加载策略] 远程代理重置策略: nodeId={}, modelId={}", nodeId, modelId);
				proxyDeleteRemote(ctx, request, nodeId, "api/auto-load/policy", modelId);
				return;
			}

			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_MODELID_MISSING));
				return;
			}

			AutoLoadPolicyManager manager = AutoLoadPolicyManager.getInstance();

			String error = manager.resetModelPolicy(modelId);
			if (error != null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(error));
				return;
			}

			error = manager.resetAutoUnloadPolicy(modelId);
			if (error != null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(error));
				return;
			}

			error = manager.resetAutoUnloadTimeoutMs(modelId);
			if (error != null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(error));
				return;
			}

			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(null));
		} catch (Exception e) {
			logger.info("重置自动加载策略失败", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_AUTO_LOAD_RESET_FAILED + ": " + e.getMessage()));
		}
	}

	/**
	 * 代理GET请求到远程节点（透传URI查询参数）
	 */
	private void proxyGetRemote(ChannelHandlerContext ctx, FullHttpRequest request, String nodeId, String path) {
		if (nodeId == null || nodeId.isBlank() || "local".equals(nodeId)) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_NODE_INVALID + ": " + nodeId));
			return;
		}
		try {
			String uri = request.uri();
			int qIdx = uri.indexOf('?');
			String fullPath;
			if (qIdx >= 0) {
				String query = uri.substring(qIdx + 1);
				String[] pairs = query.split("&");
				StringBuilder cleanQuery = new StringBuilder();
				for (String pair : pairs) {
					if (pair.startsWith("nodeId=")) continue;
					if (cleanQuery.length() > 0) cleanQuery.append('&');
					cleanQuery.append(pair);
				}
				fullPath = cleanQuery.length() > 0 ? path + "?" + cleanQuery.toString() : path;
			} else {
				fullPath = path;
			}
			NodeManager.HttpResult result = NodeManager.getInstance().callRemoteApi(nodeId, "GET", fullPath, null);
			writeRemoteResult(ctx, result, nodeId);
		} catch (Exception e) {
			logger.warn("[自动加载策略] 远程代理失败: nodeId={}, path={}, error={}", nodeId, path, e.getMessage());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_NODE_CALL_FAILED + ": " + e.getMessage()));
		}
	}

	/**
	 * 代理PUT请求到远程节点（透传请求体，移除nodeId避免回环）
	 */
	private void proxyPutRemote(ChannelHandlerContext ctx, FullHttpRequest request, String nodeId, String path) {
		if (nodeId == null || nodeId.isBlank() || "local".equals(nodeId)) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_NODE_INVALID + ": " + nodeId));
			return;
		}
		try {
			byte[] content = JsonUtil.readRequestBytes(request);
			JsonObject body = content != null && content.length > 0
					? JsonUtil.fromJson(content, JsonObject.class) : null;
			if (body != null) {
				body.remove("nodeId");
				if (body.size() == 0) body = null;
			}
			NodeManager.HttpResult result = NodeManager.getInstance().callRemoteApi(nodeId, "PUT", path, body);
			writeRemoteResult(ctx, result, nodeId);
		} catch (Exception e) {
			logger.warn("[自动加载策略] 远程代理失败: nodeId={}, path={}, error={}", nodeId, path, e.getMessage());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_NODE_CALL_FAILED + ": " + e.getMessage()));
		}
	}

	/**
	 * 代理DELETE请求到远程节点
	 */
	private void proxyDeleteRemote(ChannelHandlerContext ctx, FullHttpRequest request, String nodeId, String path, String modelId) {
		if (nodeId == null || nodeId.isBlank() || "local".equals(nodeId)) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_NODE_INVALID + ": " + nodeId));
			return;
		}
		try {
			String fullPath = path + "?modelId=" + URLEncoder.encode(modelId, StandardCharsets.UTF_8);
			JsonObject body = new JsonObject();
			if (modelId != null) {
				body.addProperty("modelId", modelId);
			}
			NodeManager.HttpResult result = NodeManager.getInstance().callRemoteApi(nodeId, "DELETE", fullPath, body);
			writeRemoteResult(ctx, result, nodeId);
		} catch (Exception e) {
			logger.warn("[自动加载策略] 远程代理失败: nodeId={}, path={}, error={}", nodeId, path, e.getMessage());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_NODE_CALL_FAILED + ": " + e.getMessage()));
		}
	}

	private void writeRemoteResult(ChannelHandlerContext ctx, NodeManager.HttpResult result, String nodeId) {
		if (result.isSuccess()) {
			NodeManager.writeHttpResultToChannel(ctx, result, "[自动加载策略]");
		} else {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_CALL_FAILED + ": code=" + result.getStatusCode()));
		}
	}
}
