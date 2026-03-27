package org.mark.test.mcp.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.mark.llamacpp.gguf.GGUFMetaData;
import org.mark.llamacpp.gguf.GGUFModel;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.test.mcp.IMCPTool;
import org.mark.test.mcp.struct.McpMessage;
import org.mark.test.mcp.struct.McpToolInputSchema;
// import org.slf4j.Logger;
// import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

public class GetModelsTool implements IMCPTool {

	// private static final Logger logger = LoggerFactory.getLogger(GetModelsTool.class);

	@Override
	public String getMcpName() {
		return "get_models";
	}

	@Override
	public String getMcpTitle() {
		return "模型获取";
	}

	@Override
	public String getMcpDescription() {
		return "获取当前服务端扫描到的全部GGUF模型信息";
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
			Map<String, Object> errorResponse = new HashMap<>();
			errorResponse.put("success", false);
			errorResponse.put("error", "获取模型列表失败: " + e.getMessage());
			return new McpMessage().addText(JsonUtil.toJson(errorResponse));
		}
	}

	private Map<String, Object> buildResponse() {
		LlamaServerManager manager = LlamaServerManager.getInstance();
		List<GGUFModel> models = manager.listModel();
		List<Map<String, Object>> modelList = new ArrayList<>();

		for (GGUFModel model : models) {
			Map<String, Object> modelInfo = new HashMap<>();

			GGUFMetaData primaryModel = model.getPrimaryModel();
			GGUFMetaData mmproj = model.getMmproj();

			String modelName = "未知模型";
			String modelId = "unknown-model-" + System.currentTimeMillis();

			if (primaryModel != null) {
				modelName = model.getName();
				if (modelName == null || modelName.trim().isEmpty()) {
					modelName = "未命名模型";
				}
				modelId = model.getModelId();
			}

			modelInfo.put("id", modelId);
			modelInfo.put("name", modelName);
			modelInfo.put("alias", model.getAlias());
			modelInfo.put("favourite", model.isFavourite());
			modelInfo.put("size", model.getSize());

			boolean isMultimodal = mmproj != null;
			modelInfo.put("isMultimodal", isMultimodal);

			if (manager.isLoading(modelId)) {
				modelInfo.put("isLoading", true);
			}

			String architecture = "未知";
			String quantization = "";
			if (primaryModel != null) {
				String value = primaryModel.getStringValue("general.architecture");
				if (value != null && !value.trim().isEmpty()) {
					architecture = value;
				}
				String quantizationValue = primaryModel.getQuantizationType();
				if (quantizationValue != null) {
					quantization = quantizationValue;
				}
			}
			modelInfo.put("architecture", architecture);
			modelInfo.put("quantization", quantization);

			modelList.add(modelInfo);
		}

		Map<String, Object> response = new HashMap<>();
		response.put("success", true);
		response.put("models", modelList);
		return response;
	}
}
