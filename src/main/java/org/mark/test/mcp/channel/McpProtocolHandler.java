package org.mark.test.mcp.channel;

import io.netty.handler.codec.http.FullHttpRequest;

public interface McpProtocolHandler {

	McpParsedRequest parseRequest(FullHttpRequest request, String sessionId, String transportLabel) throws McpProtocolException;

	McpProtocolResult processRequest(String serviceKey, String sessionId, McpParsedRequest parsedRequest);
}
