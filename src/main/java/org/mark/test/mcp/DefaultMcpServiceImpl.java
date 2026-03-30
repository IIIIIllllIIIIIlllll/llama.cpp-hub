package org.mark.test.mcp;

import java.util.Map;

import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.tools.ParamTool;
import org.mark.test.mcp.channel.McpRequestProcessor;
import org.mark.test.mcp.channel.NettySseMcpServer;
import org.mark.test.mcp.struct.McpMessage;
import org.mark.test.mcp.struct.McpSession;
import org.mark.test.mcp.struct.McpToolRegistry;
import org.mark.test.mcp.tools.GetLlamaCppInfoTool;
import org.mark.test.mcp.tools.GetMcpServiceInfoTool;
import org.mark.test.mcp.tools.GetModelPathTool;
import org.mark.test.mcp.tools.GetModelsTool;
import org.mark.test.mcp.tools.GetParamInfoTool;
import org.mark.test.mcp.tools.experience.ExperienceGetTool;
import org.mark.test.mcp.tools.experience.ExperienceListTool;
import org.mark.test.mcp.tools.experience.ExperienceLogTool;
import org.mark.test.mcp.tools.experience.ExperienceMatchTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.CharsetUtil;



/**
 * 	实现默认MCP服务的地方。和org.mark.llamacpp.server.mcp是不同的实现。
 */
public class DefaultMcpServiceImpl implements McpRequestProcessor {

	private static final Logger logger = LoggerFactory.getLogger(DefaultMcpServiceImpl.class);
	private static final String JSONRPC_VERSION = "2.0";
	private static final String MCP_PROTOCOL_VERSION = "2024-11-05";
	private static final String DEFAULT_SERVICE_KEY = "llama_server_info";

	private final McpToolRegistry toolRegistry = new McpToolRegistry();
	private final NettySseMcpServer nettyServer;

	public DefaultMcpServiceImpl(int port) {
		this.nettyServer = new NettySseMcpServer(port, this);
		this.registerBuiltinTools();
	}

	public void start() throws Exception {
		this.nettyServer.start();
	}

	public void stop() {
		this.nettyServer.stop();
	}

	public void awaitClose() throws InterruptedException {
		this.nettyServer.awaitClose();
	}

	public boolean isRunning() {
		return this.nettyServer.isRunning();
	}

	public int getPort() {
		return this.nettyServer.getPort();
	}

	public void registerTool(String serviceKey, IMCPTool tool) {
		this.toolRegistry.register(serviceKey, tool);
	}

	private void registerBuiltinTools() {
		this.registerTool(DEFAULT_SERVICE_KEY, new GetModelsTool());
		this.registerTool(DEFAULT_SERVICE_KEY, new GetModelPathTool());
		this.registerTool(DEFAULT_SERVICE_KEY, new GetLlamaCppInfoTool());
		this.registerTool(DEFAULT_SERVICE_KEY, new GetParamInfoTool());
		this.registerTool(DEFAULT_SERVICE_KEY, new GetMcpServiceInfoTool());
		this.registerTool(DEFAULT_SERVICE_KEY, new ExperienceLogTool());
		this.registerTool(DEFAULT_SERVICE_KEY, new ExperienceListTool());
		this.registerTool(DEFAULT_SERVICE_KEY, new ExperienceGetTool());
		this.registerTool(DEFAULT_SERVICE_KEY, new ExperienceMatchTool());
	}

	@Override
	public void handleMessagePost(NettySseMcpServer server, ChannelHandlerContext ctx, FullHttpRequest request, String serviceKey) {
		logger.info("MCP消息请求进入: method={}, uri={}, serviceKey={}, remote={}", request.method().name(), request.uri(), serviceKey,
				ctx.channel().remoteAddress());
		Map<String, String> query = ParamTool.getQueryParam(request.uri());
		String sessionId = query.get("sessionId");
		if (sessionId == null || sessionId.isBlank()) {
			logger.info("MCP消息请求缺少sessionId: uri={}", request.uri());
			server.sendJsonHttp(ctx, HttpResponseStatus.BAD_REQUEST, jsonError(null, -32602, "缺少sessionId"));
			return;
		}
		McpSession session = server.getSession(sessionId);
		if (session == null || !serviceKey.equals(session.getServiceKey())) {
			logger.info("MCP消息请求session无效: sessionId={}, serviceKey={}", sessionId, serviceKey);
			server.sendJsonHttp(ctx, HttpResponseStatus.BAD_REQUEST, jsonError(null, -32602, "无效的sessionId"));
			return;
		}

		JsonObject body;
		try {
			String raw = request.content().toString(CharsetUtil.UTF_8);
			logger.info("MCP消息请求体: sessionId={}, body={}", sessionId, this.clip(raw, 4000));
			body = JsonUtil.fromJson(raw, JsonObject.class);
		} catch (Exception e) {
			logger.info("MCP消息请求体解析失败: sessionId={}, error={}", sessionId, e.getMessage());
			server.sendJsonHttp(ctx, HttpResponseStatus.BAD_REQUEST, jsonError(null, -32700, "请求体JSON格式错误"));
			return;
		}
		if (body == null) {
			logger.info("MCP消息请求体为空JSON: sessionId={}", sessionId);
			server.sendJsonHttp(ctx, HttpResponseStatus.BAD_REQUEST, jsonError(null, -32700, "请求体JSON格式错误"));
			return;
		}

		String method = body.has("method") && body.get("method").isJsonPrimitive() ? body.get("method").getAsString() : "";
		JsonElement id = body.has("id") && body.get("id") != null && !body.get("id").isJsonNull() ? body.get("id") : null;
		logger.info("MCP消息解析完成: sessionId={}, method={}, id={}", sessionId, method, id == null ? "null" : id.toString());

		if ("initialize".equals(method)) {
			JsonObject result = new JsonObject();
			result.addProperty("protocolVersion", MCP_PROTOCOL_VERSION);
			JsonObject serverInfo = new JsonObject();
			serverInfo.addProperty("name", "netty-mcp-test");
			serverInfo.addProperty("version", "0.0.1");
			result.add("serverInfo", serverInfo);
			JsonObject capabilities = new JsonObject();
			JsonObject prompts = new JsonObject();
			prompts.addProperty("listChanged", false);
			capabilities.add("prompts", prompts);
			JsonObject resources = new JsonObject();
			resources.addProperty("subscribe", false);
			resources.addProperty("listChanged", false);
			capabilities.add("resources", resources);
			JsonObject tools = new JsonObject();
			tools.addProperty("listChanged", false);
			capabilities.add("tools", tools);
			result.add("capabilities", capabilities);
			logger.info("MCP initialize响应: sessionId={}, protocolVersion={}", sessionId, MCP_PROTOCOL_VERSION);
			server.sendSseData(sessionId, jsonResult(id, result));
			server.sendAccepted(ctx);
			return;
		}
		if ("ping".equals(method)) {
			logger.info("MCP收到ping请求: sessionId={}", sessionId);
			server.sendSseData(sessionId, jsonResult(id, new JsonObject()));
			server.sendAccepted(ctx);
			return;
		}
		if ("notifications/ping".equals(method)) {
			logger.info("MCP收到ping通知: sessionId={}", sessionId);
			server.sendAccepted(ctx);
			return;
		}
		if ("notifications/initialized".equals(method)) {
			logger.info("MCP收到initialized通知: sessionId={}", sessionId);
			server.sendAccepted(ctx);
			return;
		}
		if ("prompts/list".equals(method)) {
			logger.info("MCP prompts/list请求: sessionId={}, serviceKey={}", sessionId, serviceKey);
			JsonObject result = new JsonObject();
			result.add("prompts", new JsonArray());
			server.sendSseData(sessionId, jsonResult(id, result));
			server.sendAccepted(ctx);
			return;
		}
		if ("resources/list".equals(method)) {
			logger.info("MCP resources/list请求: sessionId={}, serviceKey={}", sessionId, serviceKey);
			JsonObject result = new JsonObject();
			result.add("resources", new JsonArray());
			server.sendSseData(sessionId, jsonResult(id, result));
			server.sendAccepted(ctx);
			return;
		}
		if ("resources/templates/list".equals(method)) {
			logger.info("MCP resources/templates/list请求: sessionId={}, serviceKey={}", sessionId, serviceKey);
			JsonObject result = new JsonObject();
			result.add("resourceTemplates", new JsonArray());
			server.sendSseData(sessionId, jsonResult(id, result));
			server.sendAccepted(ctx);
			return;
		}
		if ("tools/list".equals(method)) {
			logger.info("MCP tools/list请求: sessionId={}, serviceKey={}", sessionId, serviceKey);
			JsonObject result = new JsonObject();
			result.add("tools", this.toolRegistry.toToolJsonArray(serviceKey));
			server.sendSseData(sessionId, jsonResult(id, result));
			server.sendAccepted(ctx);
			return;
		}
		if ("tools/call".equals(method)) {
			JsonObject params = body.has("params") && body.get("params").isJsonObject() ? body.getAsJsonObject("params") : new JsonObject();
			String toolName = params.has("name") && params.get("name").isJsonPrimitive() ? params.get("name").getAsString() : "";
			logger.info("MCP tools/call请求: sessionId={}, serviceKey={}, toolName={}", sessionId, serviceKey, toolName);
			IMCPTool tool = this.toolRegistry.findTool(serviceKey, toolName);
			if (tool == null) {
				logger.info("MCP tools/call未找到工具: sessionId={}, toolName={}", sessionId, toolName);
				server.sendSseData(sessionId, jsonError(id, -32602, "未找到工具: " + toolName));
				server.sendAccepted(ctx);
				return;
			}
			JsonObject arguments = params.has("arguments") && params.get("arguments").isJsonObject() ? params.getAsJsonObject("arguments")
					: new JsonObject();
			logger.info("MCP tools/call参数: sessionId={}, toolName={}, arguments={}", sessionId, toolName, this.clip(JsonUtil.toJson(arguments), 4000));
			McpMessage message = tool.execute(serviceKey, arguments);
			JsonArray content = message == null ? new JsonArray() : message.toJsonArray();
			logger.info("MCP tools/call结果: sessionId={}, toolName={}, contentItems={}", sessionId, toolName, content.size());
			JsonObject result = new JsonObject();
			result.add("content", content);
			result.addProperty("isError", false);
			server.sendSseData(sessionId, jsonResult(id, result));
			server.sendAccepted(ctx);
			return;
		}
		if (id != null) {
			logger.info("MCP不支持的方法: sessionId={}, method={}", sessionId, method);
			server.sendSseData(sessionId, jsonError(id, -32601, "不支持的方法: " + method));
		}
		server.sendAccepted(ctx);
	}

	private String clip(String value, int maxLen) {
		if (value == null) {
			return "null";
		}
		if (value.length() <= maxLen) {
			return value;
		}
		return value.substring(0, maxLen) + "...(truncated)";
	}

	private JsonObject jsonResult(JsonElement id, JsonObject result) {
		JsonObject obj = new JsonObject();
		obj.addProperty("jsonrpc", JSONRPC_VERSION);
		if (id == null) {
			obj.addProperty("id", 0);
		} else {
			obj.add("id", id.deepCopy());
		}
		obj.add("result", result == null ? new JsonObject() : result);
		return obj;
	}

	private JsonObject jsonError(JsonElement id, int code, String message) {
		JsonObject obj = new JsonObject();
		obj.addProperty("jsonrpc", JSONRPC_VERSION);
		if (id == null) {
			obj.addProperty("id", 0);
		} else {
			obj.add("id", id.deepCopy());
		}
		JsonObject error = new JsonObject();
		error.addProperty("code", code);
		error.addProperty("message", message == null ? "" : message);
		obj.add("error", error);
		return obj;
	}
}
