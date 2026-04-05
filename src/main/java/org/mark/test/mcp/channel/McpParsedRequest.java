package org.mark.test.mcp.channel;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class McpParsedRequest {

	private final JsonObject body;
	private final String method;
	private final JsonElement id;

	public McpParsedRequest(JsonObject body, String method, JsonElement id) {
		this.body = body;
		this.method = method;
		this.id = id;
	}

	public JsonObject getBody() {
		return body;
	}

	public String getMethod() {
		return method;
	}

	public JsonElement getId() {
		return id;
	}
}
