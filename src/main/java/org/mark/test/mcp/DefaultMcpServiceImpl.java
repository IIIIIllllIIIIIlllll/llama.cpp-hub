package org.mark.test.mcp;

import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.test.mcp.channel.McpParsedRequest;
import org.mark.test.mcp.channel.McpProtocolException;
import org.mark.test.mcp.channel.McpProtocolHandler;
import org.mark.test.mcp.channel.McpProtocolResult;
import org.mark.test.mcp.channel.NettySseMcpServer;
import org.mark.test.mcp.struct.McpMessage;
import org.mark.test.mcp.struct.McpToolRegistry;
import org.mark.test.mcp.tools.GetLlamaCppInfoTool;
import org.mark.test.mcp.tools.GetMcpServiceInfoTool;
import org.mark.test.mcp.tools.GetModelPathTool;
import org.mark.test.mcp.tools.GetModelsTool;
import org.mark.test.mcp.tools.GetParamInfoTool;
import org.mark.test.mcp.tools.ReadStaticImageTool;
import org.mark.test.mcp.tools.context.ContextSummaryTool;
import org.mark.test.mcp.tools.experience.ExperienceGetTool;
import org.mark.test.mcp.tools.experience.ExperienceListTool;
import org.mark.test.mcp.tools.experience.ExperienceLogTool;
import org.mark.test.mcp.tools.experience.ExperienceMatchTool;
import org.mark.test.mcp.tools.file.WriteTextFileTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.CharsetUtil;



/**
 * 	实现默认MCP服务的地方。和org.mark.llamacpp.server.mcp是不同的实现。
 */
public class DefaultMcpServiceImpl implements McpProtocolHandler {

	private static final Logger logger = LoggerFactory.getLogger(DefaultMcpServiceImpl.class);
	private static final String JSONRPC_VERSION = "2.0";
	private static final String MCP_PROTOCOL_VERSION = "2024-11-05";
	
	
	private static final String DEFAULT_SERVICE_KEY = "llama_hub_info";
	
	
	private static final String IMAGE_SERVICE_KEY = "llama_hub_image";
	
	
	private static final String CONTEXT_SERVICE_KEY = "llama_hub_context";
	
	
	private static final String FILE_SERVICE_KEY = "llama_hub_file";
	
	
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
		// 图片相关的。
		this.registerTool(IMAGE_SERVICE_KEY, new ReadStaticImageTool());
		// 默认的服务器相关。
		this.registerTool(DEFAULT_SERVICE_KEY, new GetModelsTool());
		this.registerTool(DEFAULT_SERVICE_KEY, new GetModelPathTool());
		this.registerTool(DEFAULT_SERVICE_KEY, new GetLlamaCppInfoTool());
		this.registerTool(DEFAULT_SERVICE_KEY, new GetParamInfoTool());
		this.registerTool(DEFAULT_SERVICE_KEY, new GetMcpServiceInfoTool());
		// 上下文相关。
		this.registerTool(CONTEXT_SERVICE_KEY, new ContextSummaryTool());
		// 自定义的‘经验’工具。
		this.registerTool(DEFAULT_SERVICE_KEY, new ExperienceLogTool());
		this.registerTool(DEFAULT_SERVICE_KEY, new ExperienceListTool());
		this.registerTool(DEFAULT_SERVICE_KEY, new ExperienceGetTool());
		this.registerTool(DEFAULT_SERVICE_KEY, new ExperienceMatchTool());
		this.registerTool(DEFAULT_SERVICE_KEY, new WriteTextFileTool());
		
		// 写本地文件的
		this.registerTool(FILE_SERVICE_KEY, new WriteTextFileTool());
	}

	@Override
	public McpParsedRequest parseRequest(FullHttpRequest request, String sessionId, String transportLabel) throws McpProtocolException {
		return this.parseRequestBody(request, sessionId, transportLabel);
	}

	@Override
	public McpProtocolResult processRequest(String serviceKey, String sessionId, McpParsedRequest parsedRequest) {
		return this.doProcessRequest(serviceKey, sessionId, parsedRequest);
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

	private McpParsedRequest parseRequestBody(FullHttpRequest request, String sessionId, String transportLabel) throws McpProtocolException {
		JsonObject body;
		try {
			String raw = request.content().toString(CharsetUtil.UTF_8);
			logger.info("{}请求体: sessionId={}, body={}", transportLabel, sessionId, this.clip(raw, 4000));
			body = JsonUtil.fromJson(raw, JsonObject.class);
		} catch (Exception e) {
			logger.info("{}请求体解析失败: sessionId={}, error={}", transportLabel, sessionId, e.getMessage());
			throw new McpProtocolException(HttpResponseStatus.BAD_REQUEST, jsonError(null, -32700, "请求体JSON格式错误"));
		}
		if (body == null) {
			logger.info("{}请求体为空JSON: sessionId={}", transportLabel, sessionId);
			throw new McpProtocolException(HttpResponseStatus.BAD_REQUEST, jsonError(null, -32700, "请求体JSON格式错误"));
		}

		String method = body.has("method") && body.get("method").isJsonPrimitive() ? body.get("method").getAsString() : "";
		JsonElement id = body.has("id") && body.get("id") != null && !body.get("id").isJsonNull() ? body.get("id") : null;
		logger.info("{}消息解析完成: sessionId={}, method={}, id={}", transportLabel, sessionId, method, id == null ? "null" : id.toString());
		return new McpParsedRequest(body, method, id);
	}

	private McpProtocolResult doProcessRequest(String serviceKey, String sessionId, McpParsedRequest parsedRequest) {
		String method = parsedRequest.getMethod();
		JsonElement id = parsedRequest.getId();
		JsonObject body = parsedRequest.getBody();
		if ("initialize".equals(method)) {
			JsonObject result = this.buildInitializeResult();
			logger.info("MCP initialize响应: sessionId={}, protocolVersion={}", sessionId, MCP_PROTOCOL_VERSION);
			return McpProtocolResult.respond(jsonResult(id, result));
		}
		if ("ping".equals(method)) {
			logger.info("MCP收到ping请求: sessionId={}", sessionId);
			return McpProtocolResult.respond(jsonResult(id, new JsonObject()));
		}
		if ("notifications/ping".equals(method)) {
			logger.info("MCP收到ping通知: sessionId={}", sessionId);
			return McpProtocolResult.accept();
		}
		if ("notifications/initialized".equals(method)) {
			logger.info("MCP收到initialized通知: sessionId={}", sessionId);
			return McpProtocolResult.accept();
		}
		if ("prompts/list".equals(method)) {
			logger.info("MCP prompts/list请求: sessionId={}, serviceKey={}", sessionId, serviceKey);
			JsonObject result = new JsonObject();
			result.add("prompts", new JsonArray());
			return McpProtocolResult.respond(jsonResult(id, result));
		}
		if ("resources/list".equals(method)) {
			logger.info("MCP resources/list请求: sessionId={}, serviceKey={}", sessionId, serviceKey);
			JsonObject result = new JsonObject();
			result.add("resources", new JsonArray());
			return McpProtocolResult.respond(jsonResult(id, result));
		}
		if ("resources/templates/list".equals(method)) {
			logger.info("MCP resources/templates/list请求: sessionId={}, serviceKey={}", sessionId, serviceKey);
			JsonObject result = new JsonObject();
			result.add("resourceTemplates", new JsonArray());
			return McpProtocolResult.respond(jsonResult(id, result));
		}
		if ("tools/list".equals(method)) {
			logger.info("MCP tools/list请求: sessionId={}, serviceKey={}", sessionId, serviceKey);
			JsonObject result = new JsonObject();
			result.add("tools", this.toolRegistry.toToolJsonArray(serviceKey));
			return McpProtocolResult.respond(jsonResult(id, result));
		}
		if ("tools/call".equals(method)) {
			JsonObject params = body.has("params") && body.get("params").isJsonObject() ? body.getAsJsonObject("params") : new JsonObject();
			String toolName = params.has("name") && params.get("name").isJsonPrimitive() ? params.get("name").getAsString() : "";
			logger.info("MCP tools/call请求: sessionId={}, serviceKey={}, toolName={}", sessionId, serviceKey, toolName);
			IMCPTool tool = this.toolRegistry.findTool(serviceKey, toolName);
			if (tool == null) {
				logger.info("MCP tools/call未找到工具: sessionId={}, toolName={}", sessionId, toolName);
				return McpProtocolResult.respond(jsonError(id, -32602, "未找到工具: " + toolName));
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
			return McpProtocolResult.respond(jsonResult(id, result));
		}
		if (id != null) {
			logger.info("MCP不支持的方法: sessionId={}, method={}", sessionId, method);
			return McpProtocolResult.respond(jsonError(id, -32601, "不支持的方法: " + method));
		}
		return McpProtocolResult.accept();
	}

	private JsonObject buildInitializeResult() {
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
		return result;
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
