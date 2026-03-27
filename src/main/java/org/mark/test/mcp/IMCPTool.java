package org.mark.test.mcp;

import org.mark.test.mcp.struct.McpToolInputSchema;
import org.mark.test.mcp.struct.McpMessage;

import com.google.gson.JsonObject;

public interface IMCPTool {

	String getMcpName();

	String getMcpTitle();

	String getMcpDescription();

	McpToolInputSchema getInputSchema();

	McpMessage execute(String serviceKey, JsonObject arguments);
}
