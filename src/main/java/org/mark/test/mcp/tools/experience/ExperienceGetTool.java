package org.mark.test.mcp.tools.experience;

import java.util.HashMap;
import java.util.Map;

import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.test.mcp.IMCPTool;
import org.mark.test.mcp.struct.McpMessage;
import org.mark.test.mcp.struct.McpToolInputSchema;

import com.google.gson.JsonObject;

public class ExperienceGetTool implements IMCPTool {

	@Override
	public String getMcpName() {
		return "experience_get";
	}

	@Override
	public String getMcpTitle() {
		return "经验详情";
	}

	@Override
	public String getMcpDescription() {
		return "根据经验ID获取详细内容";
	}

	@Override
	public McpToolInputSchema getInputSchema() {
		return new McpToolInputSchema().addProperty("experienceId", "string", "经验ID", true);
	}

	@Override
	public McpMessage execute(String serviceKey, JsonObject arguments) {
		try {
			String id = ExperienceToolSupport.getString(arguments, "experienceId");
			if (id.isBlank()) {
				return new McpMessage().addText(JsonUtil.toJson(error("experienceId不能为空")));
			}
			ExperienceRecord record = ExperienceModule.repository().getById(id);
			Map<String, Object> response = new HashMap<>();
			response.put("success", record != null);
			response.put("experience", record);
			if (record == null) {
				response.put("error", "未找到经验: " + id);
			}
			return new McpMessage().addText(JsonUtil.toJson(response));
		} catch (Exception e) {
			return new McpMessage().addText(JsonUtil.toJson(error("获取经验详情失败: " + e.getMessage())));
		}
	}

	private Map<String, Object> error(String message) {
		Map<String, Object> response = new HashMap<>();
		response.put("success", false);
		response.put("error", message == null ? "" : message);
		return response;
	}
}
