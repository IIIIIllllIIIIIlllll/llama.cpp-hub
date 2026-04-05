package org.mark.test.mcp.channel;

import com.google.gson.JsonObject;

public class McpProtocolResult {

	private final JsonObject response;

	private McpProtocolResult(JsonObject response) {
		this.response = response;
	}

	public JsonObject getResponse() {
		return response;
	}

	public boolean hasResponse() {
		return response != null;
	}

	public static McpProtocolResult respond(JsonObject response) {
		return new McpProtocolResult(response);
	}

	public static McpProtocolResult accept() {
		return new McpProtocolResult(null);
	}
}
