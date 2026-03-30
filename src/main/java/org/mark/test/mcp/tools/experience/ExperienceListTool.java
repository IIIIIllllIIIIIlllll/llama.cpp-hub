package org.mark.test.mcp.tools.experience;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.test.mcp.IMCPTool;
import org.mark.test.mcp.struct.McpMessage;
import org.mark.test.mcp.struct.McpToolInputSchema;

import com.google.gson.JsonObject;

public class ExperienceListTool implements IMCPTool {

	@Override
	public String getMcpName() {
		return "experience_list";
	}

	@Override
	public String getMcpTitle() {
		return "经验列表";
	}

	@Override
	public String getMcpDescription() {
		return "查询经验列表，可按任务类型和标签过滤";
	}

	@Override
	public McpToolInputSchema getInputSchema() {
		return new McpToolInputSchema().addProperty("taskType", "string", "任务类型", false).addProperty("tags", "array", "标签数组", false)
				.addProperty("limit", "integer", "返回数量，默认20，最大200", false);
	}

	@Override
	public McpMessage execute(String serviceKey, JsonObject arguments) {
		try {
			String taskType = ExperienceToolSupport.getString(arguments, "taskType");
			List<String> tags = ExperienceToolSupport.getTags(arguments);
			int limit = ExperienceToolSupport.getInt(arguments, "limit", 20);
			List<ExperienceRecord> list = ExperienceModule.repository().list(taskType, tags, limit);
			Map<String, Object> response = new HashMap<>();
			response.put("success", true);
			response.put("total", list.size());
			response.put("experiences", list);
			return new McpMessage().addText(JsonUtil.toJson(response));
		} catch (Exception e) {
			return new McpMessage().addText(JsonUtil.toJson(error("获取经验列表失败: " + e.getMessage())));
		}
	}

	private Map<String, Object> error(String message) {
		Map<String, Object> response = new HashMap<>();
		response.put("success", false);
		response.put("error", message == null ? "" : message);
		return response;
	}
}
