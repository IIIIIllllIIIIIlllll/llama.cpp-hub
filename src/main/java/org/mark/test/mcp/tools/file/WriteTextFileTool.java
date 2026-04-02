package org.mark.test.mcp.tools.file;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.test.mcp.IMCPTool;
import org.mark.test.mcp.struct.McpMessage;
import org.mark.test.mcp.struct.McpToolInputSchema;

import com.google.gson.JsonObject;

/**
 * 	写文件的小工具。
 */
public class WriteTextFileTool implements IMCPTool {

	@Override
	public String getMcpName() {
		return "write_text_file";
	}

	@Override
	public String getMcpTitle() {
		return "写入文本文件";
	}

	@Override
	public String getMcpDescription() {
		return "将文本内容写入指定绝对路径，支持 txt、md、html、js 等文本格式文件，支持覆盖写入或追加写入";
	}

	@Override
	public McpToolInputSchema getInputSchema() {
		return new McpToolInputSchema()
				.addProperty("absolutePath", "string", "文本文件绝对路径，例如 C:\\temp\\note.md 或 C:\\site\\index.html", true)
				.addProperty("content", "string", "要写入的文本内容（UTF-8）", true).addProperty("append", "boolean", "是否追加写入，默认false", false)
				.addProperty("createParentDirectories", "boolean", "是否自动创建父目录，默认true", false);
	}

	@Override
	public McpMessage execute(String serviceKey, JsonObject arguments) {
		String absolutePathText = JsonUtil.getJsonString(arguments, "absolutePath");
		if (absolutePathText == null || absolutePathText.isBlank()) {
			return new McpMessage().addText(JsonUtil.toJson(this.error("absolutePath不能为空")));
		}
		String content = this.getContent(arguments);
		if (content == null) {
			return new McpMessage().addText(JsonUtil.toJson(this.error("content不能为空")));
		}
		boolean append = this.getBoolean(arguments, "append", false);
		boolean createParentDirectories = this.getBoolean(arguments, "createParentDirectories", true);
		try {
			Path rawPath = Paths.get(absolutePathText);
			if (!rawPath.isAbsolute()) {
				return new McpMessage().addText(JsonUtil.toJson(this.error("必须传入绝对路径: " + absolutePathText)));
			}
			Path filePath = rawPath.toAbsolutePath().normalize();
			Path parent = filePath.getParent();
			if (parent != null && createParentDirectories) {
				Files.createDirectories(parent);
			}
			if (append) {
				Files.writeString(filePath, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND,
						StandardOpenOption.WRITE);
			} else {
				Files.writeString(filePath, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
						StandardOpenOption.WRITE);
			}
			Map<String, Object> response = new LinkedHashMap<>();
			response.put("success", true);
			response.put("path", filePath.toString());
			response.put("mode", append ? "append" : "overwrite");
			response.put("byteSize", content.getBytes(StandardCharsets.UTF_8).length);
			return new McpMessage().addText(JsonUtil.toJson(response));
		} catch (IOException e) {
			return new McpMessage().addText(JsonUtil.toJson(this.error("写入失败: " + e.getMessage())));
		} catch (Exception e) {
			return new McpMessage().addText(JsonUtil.toJson(this.error("路径非法或参数错误: " + e.getMessage())));
		}
	}

	private String getContent(JsonObject arguments) {
		if (arguments == null || !arguments.has("content") || arguments.get("content").isJsonNull()) {
			return null;
		}
		try {
			return arguments.get("content").getAsString();
		} catch (Exception e) {
			return null;
		}
	}

	private boolean getBoolean(JsonObject arguments, String key, boolean fallback) {
		if (arguments == null || key == null || key.isBlank() || !arguments.has(key) || arguments.get(key).isJsonNull()) {
			return fallback;
		}
		try {
			return arguments.get(key).getAsBoolean();
		} catch (Exception e) {
			return fallback;
		}
	}

	private Map<String, Object> error(String message) {
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("success", false);
		response.put("error", message == null ? "" : message);
		return response;
	}
}
