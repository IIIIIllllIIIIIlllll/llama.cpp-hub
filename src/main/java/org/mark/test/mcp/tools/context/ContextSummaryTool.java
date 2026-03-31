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
		return "用于保存当前会话上下文的轻量工具，重点是给出明确主题，并用简短总结说明当前在做什么。支持查看说明、写入记录、读取上一次记录。该工具是否使用只能由用户决定，只有用户指定使用时才能调用。";
	}

	@Override
	public McpToolInputSchema getInputSchema() {
		return new McpToolInputSchema().addProperty("action", "string", "操作类型：instruction/write/read_latest", true)
				.addProperty("topic", "string", "总结主题，建议一句话直接点明当前会话在处理什么", false)
				.addProperty("scene", "string", "场景说明，可选，简单说明这是代码协作、讨论还是其他场景", false)
				.addProperty("sourceHint", "string", "来源提示，可选，用很短的话说明上下文来自哪里", false)
				.addProperty("summary", "string", "当前会话的简短上下文总结，write 时必填", false)
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
		usage.put("goal", "把当前会话压缩成一条可快速接手的上下文记录，重点是主题明确、总结简短");
		usage.put("whenToUse", List.of("准备中断当前对话，之后还要继续接手", "当前在处理某件具体事情，希望下次快速恢复上下文", "不想保留整段原文，只想保留一句主题和一段简短说明"));
		usage.put("howToSummarize", List.of("先写一个明确主题，直接点明当前在做什么", "summary 只保留当前任务主线，不展开无关细节", "优先写清对象、动作和当前进展，例如正在修改哪部分代码",
				"不要把整段对话照抄进去，也不要补充对话里没有的信息"));
		usage.put("recommendedStructure", List.of("topic：一句话主题，例如“MCP工具提示词修改”", "summary：一句到几句话，说明当前在处理什么、涉及什么对象、进展到哪里", "其他字段按需补充，不必每次都写满"));
		usage.put("qualityChecklist", List.of("读完 topic 和 summary 就知道当前会话在做什么", "内容足够短，下次能快速恢复上下文", "没有混入太多无关闲聊或展开说明", "表述忠实，不虚构未发生的内容"));
		usage.put("suggestedWorkflow", List.of("先确定一个清晰主题", "写一段简短 summary", "调用 action=write 保存", "下次接手前调用 action=read_latest 读取最近一次记录"));
		usage.put("promptTemplate", "请把当前会话整理成简短上下文记录，只需要明确 topic 和 summary。topic 要直接点明主题，summary 用一两句话说明当前在做什么、涉及什么对象、进展到哪里。示例：MCP工具提示词修改：用户正在修改关于 XXX 的代码。");
		return usage;
	}

	private Map<String, Object> buildWriteGuide() {
		Map<String, Object> write = new LinkedHashMap<>();
		write.put("action", "write");
		write.put("requiredFields", List.of("summary"));
		write.put("optionalFields", List.of("topic", "scene", "sourceHint", "keyPoints", "pendingItems", "nextSuggestion", "suggestedPrompt", "mood"));
		write.put("behavior", "写入一条新的上下文总结记录，并持久化到程序根目录的 cache/context-summary 目录");
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
