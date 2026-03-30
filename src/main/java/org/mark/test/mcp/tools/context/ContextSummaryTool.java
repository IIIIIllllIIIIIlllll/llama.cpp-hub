package org.mark.test.mcp.tools.context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.test.mcp.IMCPTool;
import org.mark.test.mcp.struct.McpMessage;
import org.mark.test.mcp.struct.McpToolInputSchema;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class ContextSummaryTool implements IMCPTool {

	private static final FileContextSummaryRepository REPOSITORY = new FileContextSummaryRepository();

	@Override
	public String getMcpName() {
		return "context_summary";
	}

	@Override
	public String getMcpTitle() {
		return "总结上下文";
	}

	@Override
	public String getMcpDescription() {
		return "用于轻松场景的上下文压缩工具，支持查看使用说明、写入总结记录、读取上一次总结";
	}

	@Override
	public McpToolInputSchema getInputSchema() {
		return new McpToolInputSchema().addProperty("action", "string", "操作类型：instruction/write/read_latest", true)
				.addProperty("topic", "string", "总结主题，如摸鱼讨论、临时方案、小型协作事项", false)
				.addProperty("scene", "string", "场景说明，如群聊、私聊、临时会议、闲聊串", false)
				.addProperty("sourceHint", "string", "原始聊天记录的简短来源说明，不建议直接塞全部原文", false)
				.addProperty("summary", "string", "压缩后的上下文正文，write 时必填", false)
				.addProperty("keyPoints", "array", "关键事实或已确认信息列表", false)
				.addProperty("pendingItems", "array", "待处理项、未决问题或后续动作列表", false)
				.addProperty("nextSuggestion", "string", "建议的下一步行动", false)
				.addProperty("suggestedPrompt", "string", "给后续大模型继续接手时可直接复用的提示词", false)
				.addProperty("mood", "string", "聊天氛围或语气，如轻松、吐槽、随意", false);
	}

	@Override
	public McpMessage execute(String serviceKey, JsonObject arguments) {
		try {
			String action = normalizeAction(getString(arguments, "action"));
			if ("instruction".equals(action)) {
				return new McpMessage().addText(JsonUtil.toJson(buildInstructionResponse()));
			}
			if ("write".equals(action)) {
				return new McpMessage().addText(JsonUtil.toJson(writeRecord(arguments)));
			}
			if ("read_latest".equals(action)) {
				return new McpMessage().addText(JsonUtil.toJson(readLatestRecord()));
			}
			return new McpMessage().addText(JsonUtil.toJson(error("action不支持，可用值：instruction/write/read_latest")));
		} catch (Exception e) {
			return new McpMessage().addText(JsonUtil.toJson(error("处理上下文总结失败: " + e.getMessage())));
		}
	}

	private Map<String, Object> buildInstructionResponse() {
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("success", true);
		response.put("tool", getMcpName());
		response.put("usageInstruction", buildUsageInstruction());
		response.put("writeRecord", buildWriteGuide());
		response.put("readRecord", buildReadGuide());
		return response;
	}

	private Map<String, Object> buildUsageInstruction() {
		Map<String, Object> usage = new LinkedHashMap<>();
		usage.put("goal", "把冗长聊天压缩成可快速接手的上下文记录，适合轻松、临时、非严肃的协作场景");
		usage.put("whenToUse", List.of("聊天记录太长，需要给后续模型或同事快速补上下文", "讨论比较发散，但已经出现可复用的信息、决定或待办", "不想保留完整原文，只保留浓缩后的可执行版本"));
		usage.put("howToSummarize", List.of("先提取当前到底在聊什么，不要把跑题内容全部照抄进去", "保留已确认事实、已经达成的决定、明确的偏好和限制条件",
				"把未解决的问题、下一步动作单独列出来，避免混在正文里", "对随口吐槽、寒暄、重复确认、情绪化表达做压缩，只保留对后续有帮助的部分",
				"如果聊天氛围会影响后续回复风格，可以用 mood 简短记录，例如轻松、吐槽、玩笑、随意", "不要伪造结论；不确定的内容明确标注为待确认"));
		usage.put("recommendedStructure", List.of("topic：一句话点明主题", "scene：说明聊天发生在哪里、为什么要总结", "summary：3到8句正文，写清主线、结论和背景",
				"keyPoints：列关键事实、约束、偏好、共识", "pendingItems：列未决问题和后续动作", "nextSuggestion：给出最自然的下一步",
				"suggestedPrompt：为后续模型生成一段可直接接手的提示词"));
		usage.put("qualityChecklist", List.of("读完后能在几十秒内知道发生了什么", "知道哪些事已经定了，哪些事还没定", "知道下一步应该继续问什么或做什么",
				"没有把大量原始废话照搬进 summary", "总结足够忠实，不擅自补充聊天里没有出现的事实"));
		usage.put("suggestedWorkflow", List.of("先调用 action=instruction 获取规范", "根据规范整理总结内容", "调用 action=write 写入本次压缩记录", "下次接手前调用 action=read_latest 读取最近一次总结"));
		usage.put("promptTemplate",
				"请把下面的聊天整理成轻量上下文记录。输出时明确给出：topic、scene、summary、keyPoints、pendingItems、nextSuggestion、suggestedPrompt、mood。要求忠实原意、压缩废话、保留决定与未决事项，不要凭空补事实。");
		return usage;
	}

	private Map<String, Object> buildWriteGuide() {
		Map<String, Object> write = new LinkedHashMap<>();
		write.put("action", "write");
		write.put("requiredFields", List.of("summary"));
		write.put("optionalFields", List.of("topic", "scene", "sourceHint", "keyPoints", "pendingItems", "nextSuggestion", "suggestedPrompt", "mood"));
		write.put("behavior", "写入一条新的上下文总结记录，并持久化到项目根目录的 context-summary 目录");
		return write;
	}

	private Map<String, Object> buildReadGuide() {
		Map<String, Object> read = new LinkedHashMap<>();
		read.put("action", "read_latest");
		read.put("behavior", "读取最近一次写入的上下文总结记录，适合在继续聊天前快速恢复上下文");
		return read;
	}

	private Map<String, Object> writeRecord(JsonObject arguments) {
		ContextSummaryRecord record = new ContextSummaryRecord();
		record.setTopic(getString(arguments, "topic"));
		record.setScene(getString(arguments, "scene"));
		record.setSourceHint(getString(arguments, "sourceHint"));
		record.setSummary(getString(arguments, "summary"));
		record.setKeyPoints(getStringList(arguments, "keyPoints"));
		record.setPendingItems(getStringList(arguments, "pendingItems"));
		record.setNextSuggestion(getString(arguments, "nextSuggestion"));
		record.setSuggestedPrompt(getString(arguments, "suggestedPrompt"));
		record.setMood(getString(arguments, "mood"));
		if (record.getSummary() == null || record.getSummary().isBlank()) {
			return error("write 时 summary 不能为空");
		}
		ContextSummaryRecord saved = REPOSITORY.save(record);
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("success", true);
		response.put("action", "write");
		response.put("record", saved);
		return response;
	}

	private Map<String, Object> readLatestRecord() {
		ContextSummaryRecord record = REPOSITORY.getLatest();
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("success", record != null);
		response.put("action", "read_latest");
		response.put("record", record);
		if (record == null) {
			response.put("error", "当前还没有任何上下文总结记录");
		}
		return response;
	}

	private String getString(JsonObject arguments, String key) {
		String value = JsonUtil.getJsonString(arguments, key, "");
		return value == null ? "" : value.trim();
	}

	private List<String> getStringList(JsonObject arguments, String key) {
		if (arguments == null || key == null || !arguments.has(key)) {
			return List.of();
		}
		JsonElement value = arguments.get(key);
		List<String> raw = JsonUtil.getJsonStringList(value);
		if (raw == null || raw.isEmpty()) {
			return List.of();
		}
		List<String> out = new ArrayList<>();
		LinkedHashSet<String> seen = new LinkedHashSet<>();
		for (String item : raw) {
			if (item == null) {
				continue;
			}
			String text = item.trim();
			if (text.isEmpty() || !seen.add(text)) {
				continue;
			}
			out.add(text);
		}
		return out;
	}

	private String normalizeAction(String action) {
		String value = action == null ? "" : action.trim().toLowerCase();
		if ("help".equals(value) || "guide".equals(value) || "usage".equals(value) || "instructions".equals(value)) {
			return "instruction";
		}
		if ("save".equals(value) || "log".equals(value)) {
			return "write";
		}
		if ("read".equals(value) || "latest".equals(value) || "last".equals(value) || "get_last".equals(value)) {
			return "read_latest";
		}
		return value;
	}

	private Map<String, Object> error(String message) {
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("success", false);
		response.put("error", message == null ? "" : message);
		return response;
	}
}
