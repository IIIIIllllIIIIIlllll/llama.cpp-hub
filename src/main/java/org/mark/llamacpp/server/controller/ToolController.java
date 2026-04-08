package org.mark.llamacpp.server.controller;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.mcp.McpClientService;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.CharsetUtil;

/**
 * 工具控制器类，负责处理与工具执行和 MCP (Model Context Protocol) 相关的 HTTP 请求。
 */
public class ToolController implements BaseController {

	private static final Logger logger = LoggerFactory.getLogger(ToolController.class);
				
	/** MCP 客户端服务，用于管理 MCP 服务器和调用 MCP 工具 */
	private static final McpClientService mcpClientService = McpClientService.getInstance();
	private static final ExecutorService ioExecutor = Executors.newVirtualThreadPerTaskExecutor();


	private static final String PATH_TOOL_EXECUTE = "/api/tools/execute";
	/** 添加 MCP 服务 API 路径 */
	private static final String PATH_MCP_ADD = "/api/mcp/add";
	/** 获取 MCP 工具列表 API 路径 */
	private static final String PATH_MCP_TOOLS = "/api/mcp/tools";
	/** 移除 MCP 服务 API 路径 */
	private static final String PATH_MCP_REMOVE = "/api/mcp/remove";
	/** 修改 MCP 服务名称 API 路径 */
	private static final String PATH_MCP_RENAME = "/api/mcp/rename";

	/**
	 * 实现 BaseController 的 handleRequest 方法，分发不同的工具相关请求。
	 * 
	 * @param uri     请求的 URI
	 * @param ctx     Netty 通道上下文
	 * @param request HTTP 请求对象
	 * @return 如果请求被处理则返回 true，否则返回 false
	 * @throws RequestMethodException 请求方法不正确时抛出异常
	 */
	@Override
	public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (uri.startsWith(PATH_TOOL_EXECUTE)) {
			this.handleToolExecute(ctx, request);
			return true;
		} else if (uri.startsWith(PATH_MCP_ADD)) {
			this.handleMcpAdd(ctx, request);
			return true;
		} else if (uri.startsWith(PATH_MCP_TOOLS)) {
			this.handleMcpTools(ctx, request);
			return true;
		} else if (uri.startsWith(PATH_MCP_REMOVE)) {
			this.handleMcpRemove(ctx, request);
			return true;
		} else if (uri.startsWith(PATH_MCP_RENAME)) {
			this.handleMcpRename(ctx, request);
			return true;
		}

		return false;
	}

	/**
	 * 处理工具执行请求。支持 MCP 工具。
	 */
	private void handleToolExecute(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (handleCorsOptions(ctx, request)) {
			return;
		}
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");

		try {
			// 1. 读取并验证请求体
			String content = readRequestBodyOrSendError(ctx, request);
			if (content == null) {
				return;
			}

			// 2. 解析 JSON 请求体
			JsonObject obj = parseJsonObjectOrSendError(ctx, content);
			if (obj == null) {
				return;
			}

			// 3. 提取工具名称
			String toolName = extractToolNameOrSendError(ctx, obj);
			if (toolName == null) {
				return;
			}

			// 4. 提取预设查询和工具参数
			String preparedQuery = JsonUtil.getJsonString(obj, "preparedQuery", "");
			if (preparedQuery == null) preparedQuery = "";

			String toolArguments = extractToolArguments(obj);

			// 5. 处理 MCP 工具调用
			String url = extractMcpUrl(obj);

			String tn = toolName;
			String ta = toolArguments;
			String u = url;
			Future<?> fut = ioExecutor.submit(() -> {
				try {
					JsonObject mcpResp = (u == null)
							? mcpClientService.callTool(tn, ta)
							: mcpClientService.callToolByUrl(u, tn, ta);
					sendMcpToolResponse(ctx, mcpResp);
				} catch (Exception e) {
					if (Thread.currentThread().isInterrupted() || e instanceof java.io.InterruptedIOException || e instanceof InterruptedException) {
						return;
					}
					logger.info("执行工具失败", e);
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error("执行工具失败: " + e.getMessage()));
				}
			});
			ctx.channel().closeFuture().addListener(ignored -> fut.cancel(true));
			return;
		} catch (Exception e) {
			logger.info("执行工具失败", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("执行工具失败: " + e.getMessage()));
		}
	}

	/**
	 * 处理添加 MCP 服务的请求。
	 */
	private void handleMcpAdd(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {

		if (handleCorsOptions(ctx, request)) {
			return;
		}
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");

		try {
			String content = readRequestBodyOrSendError(ctx, request);
			if (content == null) return;
			String body = content;
			ioExecutor.execute(() -> {
				try {
					mcpClientService.addFromConfigJson(body);
					Map<String, Object> data = new HashMap<>();
					data.put("registry", mcpClientService.getSavedToolsRegistry());
					LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
				} catch (Exception e) {
					logger.info("添加MCP服务失败", e);
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error("添加MCP服务失败: " + e.getMessage()));
				}
			});
			return;
		} catch (Exception e) {
			logger.info("添加MCP服务失败", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("添加MCP服务失败: " + e.getMessage()));
		}
	}

	/**
	 * 处理获取所有已注册 MCP 工具的请求。
	 */
	private void handleMcpTools(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (handleCorsOptions(ctx, request)) {
			return;
		}
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");

		try {
			// 获取保存的工具注册表
			JsonObject registry = mcpClientService.getSavedToolsRegistry();
			JsonObject servers = (registry == null) ? null : registry.getAsJsonObject("servers");
			if (servers == null) servers = new JsonObject();
			Map<String, Object> data = new HashMap<>(2);
			data.put("servers", servers);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("获取MCP工具失败", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取MCP工具失败: " + e.getMessage()));
		}
	}

	/**
	 * 处理移除 MCP 服务的请求。
	 */
	private void handleMcpRemove(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (handleCorsOptions(ctx, request)) {
			return;
		}
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");

		try {
			String content = readRequestBodyOrSendError(ctx, request);
			if (content == null) return;

			JsonObject obj = parseJsonObjectOrSendError(ctx, content);
			if (obj == null) return;

			// 提取要移除的 MCP 服务 URL
			String url = extractMcpUrl(obj);
			if (url == null) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "缺少必需的url参数");
				return;
			}

			// 执行移除操作
			boolean removed = mcpClientService.removeServerByUrl(url);
			if (!removed) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("未找到该MCP服务: " + url));
				return;
			}
			Map<String, Object> data = new HashMap<>();
			data.put("registry", mcpClientService.getSavedToolsRegistry());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("移除MCP服务失败", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("移除MCP服务失败: " + e.getMessage()));
		}
	}

	private void handleMcpRename(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (handleCorsOptions(ctx, request)) {
			return;
		}
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");

		try {
			String content = readRequestBodyOrSendError(ctx, request);
			if (content == null) return;

			JsonObject obj = parseJsonObjectOrSendError(ctx, content);
			if (obj == null) return;

			String url = extractMcpUrl(obj);
			if (url == null) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "缺少必需的url参数");
				return;
			}

			String name = trimToNull(JsonUtil.getJsonString(obj, "name", null));
			if (name == null) {
				name = trimToNull(JsonUtil.getJsonString(obj, "serverName", null));
			}
			if (name == null) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "缺少必需的name参数");
				return;
			}

			boolean renamed = mcpClientService.renameServerByUrl(url, name);
			if (!renamed) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("未找到该MCP服务: " + url));
				return;
			}
			Map<String, Object> data = new HashMap<>();
			data.put("registry", mcpClientService.getSavedToolsRegistry());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("修改MCP服务名称失败", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("修改MCP服务名称失败: " + e.getMessage()));
		}
	}

	/**
	 * 处理 CORS OPTIONS 请求。
	 * 
	 * @return 如果是 OPTIONS 请求并已处理则返回 true
	 */
	private static boolean handleCorsOptions(ChannelHandlerContext ctx, FullHttpRequest request) {
		if (request.method() != HttpMethod.OPTIONS) {
			return false;
		}
		LlamaServer.sendCorsResponse(ctx);
		return true;
	}

	/**
	 * 读取请求体内容，如果为空则发送错误响应。
	 */
	private static String readRequestBodyOrSendError(ChannelHandlerContext ctx, FullHttpRequest request) {
		String content = request.content().toString(CharsetUtil.UTF_8);
		if (content == null || content.trim().isEmpty()) {
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "请求体为空");
			return null;
		}
		return content;
	}

	/**
	 * 将字符串解析为 JsonObject，如果解析失败则发送错误响应。
	 */
	private static JsonObject parseJsonObjectOrSendError(ChannelHandlerContext ctx, String content) {
		JsonObject obj = JsonUtil.fromJson(content, JsonObject.class);
		if (obj == null) {
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "请求体解析失败");
			return null;
		}
		return obj;
	}

	/**
	 * 从 JSON 对象中提取工具名称，支持 'tool_name' 和 'name' 字段。
	 */
	private static String extractToolNameOrSendError(ChannelHandlerContext ctx, JsonObject obj) {
		String toolName = trimToNull(JsonUtil.getJsonString(obj, "tool_name", null));
		if (toolName == null) {
			toolName = trimToNull(JsonUtil.getJsonString(obj, "name", null));
		}
		if (toolName == null) {
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "缺少必需的tool_name参数");
			return null;
		}
		return toolName;
	}

	/**
	 * 从 JSON 对象中提取工具参数，支持 'arguments' 和 'tool_arguments' 字段。
	 */
	private static String extractToolArguments(JsonObject obj) {
		if (obj.has("arguments") && obj.get("arguments") != null && !obj.get("arguments").isJsonNull()) {
			JsonElement argsEl = obj.get("arguments");
			return argsEl.isJsonPrimitive() ? argsEl.getAsString() : JsonUtil.toJson(argsEl);
		}
		return JsonUtil.getJsonString(obj, "tool_arguments", null);
	}

	/**
	 * 从 JSON 对象中提取 MCP 服务 URL，支持 'url' 和 'mcpServerUrl' 字段。
	 */
	private static String extractMcpUrl(JsonObject obj) {
		String url = trimToNull(JsonUtil.getJsonString(obj, "url", null));
		if (url == null) {
			url = trimToNull(JsonUtil.getJsonString(obj, "mcpServerUrl", null));
		}
		return url;
	}

	/**
	 * 去除字符串两端空格，如果为空字符串则返回 null。
	 */
	private static String trimToNull(String s) {
		if (s == null) return null;
		String t = s.trim();
		return t.isEmpty() ? null : t;
	}

	/**
	 * 封装返回给客户端的数据结构。
	 */
	private static Map<String, Object> contentData(String content) {
		Map<String, Object> data = new HashMap<>(2);
		data.put("content", content);
		return data;
	}

	/**
	 * 发送 MCP 工具调用的响应结果。
	 */
	private static void sendMcpToolResponse(ChannelHandlerContext ctx, JsonObject mcpResp) {
		if (mcpResp == null) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("MCP工具调用失败: 返回为空"));
			return;
		}
		// 检查 MCP 响应中是否包含错误
		if (mcpResp.has("error") && mcpResp.get("error") != null && mcpResp.get("error").isJsonObject()) {
			JsonObject err = mcpResp.getAsJsonObject("error");
			String msg = JsonUtil.getJsonString(err, "message", null);
			ApiResponse api = ApiResponse.error("MCP工具调用失败" + (msg == null || msg.isBlank() ? "" : (": " + msg.trim())));
			api.setData(contentData(mcpResp.toString()));
			LlamaServer.sendJsonResponse(ctx, api);
			return;
		}
		// 成功调用，返回结果
		LlamaServer.sendJsonResponse(ctx, ApiResponse.success(contentData(mcpResp.toString())));
	}
}
