package org.mark.test.mcp.channel;

import com.google.gson.JsonObject;

import io.netty.handler.codec.http.HttpResponseStatus;

public class McpProtocolException extends Exception {

	private static final long serialVersionUID = 1L;

	private final HttpResponseStatus status;
	private final JsonObject responseBody;

	public McpProtocolException(HttpResponseStatus status, JsonObject responseBody) {
		this.status = status;
		this.responseBody = responseBody;
	}

	public HttpResponseStatus getStatus() {
		return status;
	}

	public JsonObject getResponseBody() {
		return responseBody;
	}
}
