package org.mark.test.mcp.tools;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.mark.test.mcp.IMCPTool;
import org.mark.test.mcp.struct.McpMessage;
import org.mark.test.mcp.struct.McpToolInputSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;


/**
 * 	凑数的。
 */
public class GetSystemTimeTool implements IMCPTool {

	private static final Logger logger = LoggerFactory.getLogger(GetSystemTimeTool.class);
	private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	@Override
	public String getMcpName() {
		return "get_system_time";
	}

	@Override
	public String getMcpTitle() {
		return "获取系统时间";
	}

	@Override
	public String getMcpDescription() {
		return "返回服务器当前系统时间";
	}

	@Override
	public McpToolInputSchema getInputSchema() {
		return new McpToolInputSchema();
	}

	@Override
	public McpMessage execute(String serviceKey, JsonObject arguments) {
		LocalDateTime now = LocalDateTime.now();
		logger.info("MCP工具执行: name={}, serviceKey={}", this.getMcpName(), serviceKey);
		String text = "now=" + now.format(FORMATTER) + ", timezone=" + ZoneId.systemDefault().getId() + ", epochMillis=" + System.currentTimeMillis();
		return new McpMessage().addText(text);
	}
}
