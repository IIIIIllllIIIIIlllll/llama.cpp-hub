package org.mark.test.mcp.tools;

import org.mark.test.mcp.IMCPTool;
import org.mark.test.mcp.struct.McpMessage;
import org.mark.test.mcp.struct.McpToolInputSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;


/**
 * 	测试参数和响应的。
 */
public class GetMcpServiceInfoTool implements IMCPTool {

	private static final Logger logger = LoggerFactory.getLogger(GetMcpServiceInfoTool.class);

	public GetMcpServiceInfoTool() {
	}

	@Override
	public String getMcpName() {
		return "get_mcp_service_info";
	}

	@Override
	public String getMcpTitle() {
		return "获取mcp服务信息";
	}

	@Override
	public String getMcpDescription() {
		return "测试工具：接收query参数并返回固定服务状态";
	}

	@Override
	public McpToolInputSchema getInputSchema() {
		return new McpToolInputSchema().addProperty("query", "string", "用户查询内容", true);
	}

	@Override
	public McpMessage execute(String serviceKey, JsonObject arguments) {
		String query = "";
		if (arguments != null && arguments.has("query") && arguments.get("query").isJsonPrimitive()) {
			query = arguments.get("query").getAsString();
		}
		logger.info("MCP工具执行: name={}, serviceKey={}, query={}", this.getMcpName(), serviceKey, query);
		return new McpMessage().addText("服务器工作良好，请用户放心。");
	}
}
