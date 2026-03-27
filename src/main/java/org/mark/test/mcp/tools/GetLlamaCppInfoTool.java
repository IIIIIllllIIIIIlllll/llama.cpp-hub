package org.mark.test.mcp.tools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.struct.LlamaCppConfig;
import org.mark.llamacpp.server.struct.LlamaCppDataStruct;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.test.mcp.IMCPTool;
import org.mark.test.mcp.struct.McpMessage;
import org.mark.test.mcp.struct.McpToolInputSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

public class GetLlamaCppInfoTool implements IMCPTool {

	private static final Logger logger = LoggerFactory.getLogger(GetLlamaCppInfoTool.class);

	@Override
	public String getMcpName() {
		return "get_llamacpp_info";
	}

	@Override
	public String getMcpTitle() {
		return "llama.cpp信息获取";
	}

	@Override
	public String getMcpDescription() {
		return "获取服务端配置与扫描到的全部llama.cpp目录信息";
	}

	@Override
	public McpToolInputSchema getInputSchema() {
		return new McpToolInputSchema();
	}

	@Override
	public McpMessage execute(String serviceKey, JsonObject arguments) {
		logger.info("MCP工具执行: name={}, serviceKey={}", this.getMcpName(), serviceKey);
		try {
			return new McpMessage().addText(JsonUtil.toJson(this.buildResponse()));
		} catch (Exception e) {
			logger.info("MCP工具执行失败: name={}, serviceKey={}", this.getMcpName(), serviceKey, e);
			return new McpMessage().addText(JsonUtil.toJson(ApiResponse.error("获取llama.cpp路径列表失败: " + e.getMessage())));
		}
	}

	private ApiResponse buildResponse() throws Exception {
		Path configFile = LlamaServer.getLlamaCppConfigPath();
		LlamaCppConfig cfg = LlamaServer.readLlamaCppConfig(configFile);
		List<LlamaCppDataStruct> items = cfg.getItems();
		if (items == null) {
			items = new ArrayList<>();
		}
		List<LlamaCppDataStruct> list = LlamaServer.scanLlamaCpp();
		if (list != null && !list.isEmpty()) {
			items.addAll(list);
		}
		Map<String, Object> data = new HashMap<>();
		data.put("items", items);
		data.put("count", items.size());
		return ApiResponse.success(data);
	}
}
