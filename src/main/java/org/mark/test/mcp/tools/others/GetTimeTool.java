package org.mark.test.mcp.tools.others;

import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import org.mark.test.mcp.IMCPTool;
import org.mark.test.mcp.struct.McpMessage;
import org.mark.test.mcp.struct.McpToolInputSchema;

import com.google.gson.JsonObject;

public class GetTimeTool implements IMCPTool {

	@Override
	public String getMcpName() {
		return "get_time";
	}

	@Override
	public String getMcpTitle() {
		return "获取时间";
	}

	@Override
	public String getMcpDescription() {
		return "获取当前系统时间或指定时区的时间";
	}

	@Override
	public McpToolInputSchema getInputSchema() {
		return new McpToolInputSchema()
				.addProperty("timezone", "string", "要获取时间的时区ID (例如 'UTC', 'Asia/Shanghai', 'America/New_York')。如果不提供，则使用系统默认时区。", false);
	}

	@Override
	public McpMessage execute(String serviceKey, JsonObject arguments) {
		String timezoneId = Optional.ofNullable(arguments)
				.map(args -> args.has("timezone") ? args.get("timezone").getAsString() : null)
				.orElse(null);

		ZonedDateTime now;
		try {
			if (timezoneId != null && !timezoneId.isBlank()) {
				now = ZonedDateTime.now(ZoneId.of(timezoneId));
			} else {
				now = ZonedDateTime.now();
			}
		} catch (Exception e) {
			return new McpMessage().addText("错误: 无效的时区 ID '" + timezoneId + "'.");
		}

		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");
		String formattedTime = now.format(formatter);
		
		return new McpMessage().addText("当前时间 (" + now.getZone().getId() + "): " + formattedTime);
	}
}
