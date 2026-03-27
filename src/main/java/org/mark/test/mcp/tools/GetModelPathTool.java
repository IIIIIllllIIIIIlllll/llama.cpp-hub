package org.mark.test.mcp.tools;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.struct.ModelPathConfig;
import org.mark.llamacpp.server.struct.ModelPathDataStruct;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.test.mcp.IMCPTool;
import org.mark.test.mcp.struct.McpMessage;
import org.mark.test.mcp.struct.McpToolInputSchema;
// import org.slf4j.Logger;
// import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

public class GetModelPathTool implements IMCPTool {

	// private static final Logger logger = LoggerFactory.getLogger(GetModelPathTool.class);

	@Override
	public String getMcpName() {
		return "get_model_paths";
	}

	@Override
	public String getMcpTitle() {
		return "模型路径获取";
	}

	@Override
	public String getMcpDescription() {
		return "获取服务端配置的全部模型路径信息";
	}

	@Override
	public McpToolInputSchema getInputSchema() {
		return new McpToolInputSchema();
	}

	@Override
	public McpMessage execute(String serviceKey, JsonObject arguments) {
		// logger.info("MCP工具执行: name={}, serviceKey={}", this.getMcpName(), serviceKey);
		try {
			return new McpMessage().addText(JsonUtil.toJson(this.buildResponse()));
		} catch (Exception e) {
			// logger.info("MCP工具执行失败: name={}, serviceKey={}", this.getMcpName(), serviceKey, e);
			return new McpMessage().addText(JsonUtil.toJson(ApiResponse.error("获取模型路径列表失败: " + e.getMessage())));
		}
	}

	private ApiResponse buildResponse() throws Exception {
		LlamaServerManager manager = LlamaServerManager.getInstance();
		Path configFile = LlamaServer.getModelPathConfigPath();
		ModelPathConfig cfg = LlamaServer.readModelPathConfig(configFile);
		cfg = this.ensureModelPathConfigInitialized(cfg, manager.getModelPaths(), configFile);
		List<ModelPathDataStruct> items = cfg.getItems();
		Map<String, Object> data = new HashMap<>();
		data.put("items", items);
		data.put("count", items == null ? 0 : items.size());
		return ApiResponse.success(data);
	}

	private ModelPathConfig ensureModelPathConfigInitialized(ModelPathConfig cfg, List<ModelPathDataStruct> legacyPaths, Path configFile)
			throws Exception {
		if (cfg == null) {
			cfg = new ModelPathConfig();
		}
		List<ModelPathDataStruct> items = cfg.getItems();
		boolean empty = items == null || items.isEmpty();
		if (!empty) {
			return cfg;
		}
		if (legacyPaths == null || legacyPaths.isEmpty()) {
			return cfg;
		}
		List<ModelPathDataStruct> migrated = new ArrayList<>();
		for (ModelPathDataStruct pathItem : legacyPaths) {
			if (pathItem == null || pathItem.getPath() == null || pathItem.getPath().trim().isEmpty()) {
				continue;
			}
			String normalized = pathItem.getPath().trim();
			boolean exists = false;
			for (ModelPathDataStruct item : migrated) {
				if (item != null && item.getPath() != null && this.isSamePath(normalized, item.getPath().trim())) {
					exists = true;
					break;
				}
			}
			if (exists) {
				continue;
			}
			ModelPathDataStruct item = new ModelPathDataStruct();
			item.setPath(normalized);
			try {
				item.setName(Paths.get(normalized).getFileName().toString());
			} catch (Exception e) {
				item.setName(normalized);
			}
			migrated.add(item);
		}
		cfg.setItems(migrated);
		LlamaServer.writeModelPathConfig(configFile, cfg);
		return cfg;
	}

	private boolean isSamePath(String a, String b) {
		if (a == null || b == null) {
			return false;
		}
		try {
			Path pa = Paths.get(a).toAbsolutePath().normalize();
			Path pb = Paths.get(b).toAbsolutePath().normalize();
			return pa.equals(pb);
		} catch (Exception e) {
			return a.trim().equalsIgnoreCase(b.trim());
		}
	}
}
