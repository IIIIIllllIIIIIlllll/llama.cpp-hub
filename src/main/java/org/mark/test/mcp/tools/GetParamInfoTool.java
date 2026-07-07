package org.mark.test.mcp.tools;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.test.mcp.IMCPTool;
import org.mark.test.mcp.struct.McpMessage;
import org.mark.test.mcp.struct.McpToolInputSchema;
// import org.slf4j.Logger;
// import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

public class GetParamInfoTool implements IMCPTool {

	// private static final Logger logger = LoggerFactory.getLogger(GetParamInfoTool.class);
	private static final String RESOURCE_NAME = "server-params.json";

	private static final String I18N_PARAM_LIST_FAILED = "api.error.param.list.failed";
	private static final String I18N_PARAM_CONFIG_NOT_FOUND = "api.error.param.config.notfound";

	@Override
	public String getMcpName() {
		return "get_param_info";
	}

	@Override
	public String getMcpTitle() {
		return "启动参数获取";
	}

	@Override
	public String getMcpDescription() {
		return "获取服务端可用的启动参数列表";
	}

	@Override
	public McpToolInputSchema getInputSchema() {
		return new McpToolInputSchema();
	}

	@Override
	public McpMessage execute(String serviceKey, JsonObject arguments, Map<String, String> headers) {
		// logger.info("MCP工具执行: name={}, serviceKey={}", this.getMcpName(), serviceKey);
		try {
			return new McpMessage().addText(JsonUtil.toJson(this.buildResponse()));
		} catch (Exception e) {
			// logger.info("MCP工具执行失败: name={}, serviceKey={}", this.getMcpName(), serviceKey, e);
			return new McpMessage().addText(JsonUtil.toJson(ApiResponse.error(I18N_PARAM_LIST_FAILED + ": " + e.getMessage())));
		}
	}

	private Map<String, Object> buildResponse() throws Exception {
		try (InputStream inputStream = this.getClass().getClassLoader().getResourceAsStream(RESOURCE_NAME)) {
			if (inputStream == null) {
				Map<String, Object> errorResponse = new HashMap<>();
				errorResponse.put("success", false);
				errorResponse.put("error", I18N_PARAM_CONFIG_NOT_FOUND + ": " + RESOURCE_NAME);
				return errorResponse;
			}
			String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
			Object parsed = JsonUtil.fromJson(content, Object.class);
			Map<String, Object> response = new HashMap<>();
			response.put("success", true);
			response.put("params", parsed);
			return response;
		}
	}
}
