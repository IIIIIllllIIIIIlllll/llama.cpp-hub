package org.mark.test.mcp.tools.experience;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.test.mcp.IMCPTool;
import org.mark.test.mcp.struct.McpMessage;
import org.mark.test.mcp.struct.McpToolInputSchema;

import com.google.gson.JsonObject;

public class ExperienceMatchTool implements IMCPTool {

	@Override
	public String getMcpName() {
		return "experience_match";
	}

	@Override
	public String getMcpTitle() {
		return "经验匹配";
	}

	@Override
	public String getMcpDescription() {
		return "根据当前任务上下文匹配最相关经验";
	}

	@Override
	public McpToolInputSchema getInputSchema() {
		return new McpToolInputSchema().addProperty("taskType", "string", "任务类型", true).addProperty("context", "string", "任务上下文", true)
				.addProperty("topK", "integer", "返回前K条，默认3，最大20", false);
	}

	@Override
	public McpMessage execute(String serviceKey, JsonObject arguments) {
		try {
			String taskType = ExperienceToolSupport.getString(arguments, "taskType");
			String context = ExperienceToolSupport.getString(arguments, "context");
			int topK = ExperienceToolSupport.getInt(arguments, "topK", 3);
			if (taskType.isBlank()) {
				return new McpMessage().addText(JsonUtil.toJson(error("taskType不能为空")));
			}
			if (context.isBlank()) {
				return new McpMessage().addText(JsonUtil.toJson(error("context不能为空")));
			}
			List<ExperienceMatcher.ExperienceMatchResult> matched = ExperienceModule.matcher().match(ExperienceModule.repository().listAll(), taskType,
					context, topK);
			List<Map<String, Object>> items = new ArrayList<>();
			for (ExperienceMatcher.ExperienceMatchResult item : matched) {
				Map<String, Object> row = new HashMap<>();
				row.put("score", item.getScore());
				row.put("reasons", item.getReasons());
				row.put("experience", item.getRecord());
				items.add(row);
			}
			Map<String, Object> response = new HashMap<>();
			response.put("success", true);
			response.put("taskType", taskType);
			response.put("context", context);
			response.put("total", items.size());
			response.put("matches", items);
			return new McpMessage().addText(JsonUtil.toJson(response));
		} catch (Exception e) {
			return new McpMessage().addText(JsonUtil.toJson(error("匹配经验失败: " + e.getMessage())));
		}
	}

	private Map<String, Object> error(String message) {
		Map<String, Object> response = new HashMap<>();
		response.put("success", false);
		response.put("error", message == null ? "" : message);
		return response;
	}
}
