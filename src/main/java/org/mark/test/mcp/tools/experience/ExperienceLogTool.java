package org.mark.test.mcp.tools.experience;

import java.util.HashMap;
import java.util.Map;

import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.test.mcp.IMCPTool;
import org.mark.test.mcp.struct.McpMessage;
import org.mark.test.mcp.struct.McpToolInputSchema;

import com.google.gson.JsonObject;

public class ExperienceLogTool implements IMCPTool {

	@Override
	public String getMcpName() {
		return "experience_log";
	}

	@Override
	public String getMcpTitle() {
		return "记录经验";
	}

	@Override
	public String getMcpDescription() {
		return "记录一次任务踩坑经验，包含现象、根因与修复方式";
	}

	@Override
	public McpToolInputSchema getInputSchema() {
		return new McpToolInputSchema().addProperty("taskType", "string", "任务类型，如 java_build、mcp_tooling", true)
				.addProperty("context", "string", "当前任务上下文描述", true).addProperty("symptom", "string", "错误现象", true)
				.addProperty("rootCause", "string", "根因分析", false).addProperty("fix", "string", "修复方法", true)
				.addProperty("antiPattern", "string", "应避免的错误做法", false).addProperty("tags", "array", "标签数组", false)
				.addProperty("severity", "string", "严重等级：low/medium/high", false);
	}

	@Override
	public McpMessage execute(String serviceKey, JsonObject arguments) {
		try {
			ExperienceRecord record = ExperienceToolSupport.toRecord(arguments);
			if (record.getTaskType() == null || record.getTaskType().isBlank()) {
				return new McpMessage().addText(JsonUtil.toJson(error("taskType不能为空")));
			}
			if (record.getContext() == null || record.getContext().isBlank()) {
				return new McpMessage().addText(JsonUtil.toJson(error("context不能为空")));
			}
			if (record.getSymptom() == null || record.getSymptom().isBlank()) {
				return new McpMessage().addText(JsonUtil.toJson(error("symptom不能为空")));
			}
			if (record.getFix() == null || record.getFix().isBlank()) {
				return new McpMessage().addText(JsonUtil.toJson(error("fix不能为空")));
			}
			ExperienceRecord saved = ExperienceModule.repository().save(record);
			Map<String, Object> response = new HashMap<>();
			response.put("success", true);
			response.put("experience", saved);
			return new McpMessage().addText(JsonUtil.toJson(response));
		} catch (Exception e) {
			return new McpMessage().addText(JsonUtil.toJson(error("记录经验失败: " + e.getMessage())));
		}
	}

	private Map<String, Object> error(String message) {
		Map<String, Object> response = new HashMap<>();
		response.put("success", false);
		response.put("error", message == null ? "" : message);
		return response;
	}
}
