package org.mark.test.mcp.channel;

import java.util.Map;

import org.mark.llamacpp.server.tools.ParamTool;
import org.mark.test.mcp.struct.McpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;

public class LegacySseTransportHandler {

	private static final Logger logger = LoggerFactory.getLogger(LegacySseTransportHandler.class);

	private final NettySseMcpServer server;
	private final McpProtocolHandler protocolHandler;

	public LegacySseTransportHandler(NettySseMcpServer server, McpProtocolHandler protocolHandler) {
		this.server = server;
		this.protocolHandler = protocolHandler;
	}

	public void handleConnect(ChannelHandlerContext ctx, String serviceKey) {
		this.server.openLegacySseStream(ctx, serviceKey);
	}

	public void handleMessagePost(ChannelHandlerContext ctx, FullHttpRequest request, String serviceKey) {
		logger.info("MCP旧版消息请求进入: method={}, uri={}, serviceKey={}, remote={}", request.method().name(), request.uri(), serviceKey,
				ctx.channel().remoteAddress());
		Map<String, String> query = ParamTool.getQueryParam(request.uri());
		String sessionId = query.get("sessionId");
		if (sessionId == null || sessionId.isBlank()) {
			logger.info("MCP旧版消息请求缺少sessionId: uri={}", request.uri());
			this.server.sendJsonHttp(ctx, HttpResponseStatus.BAD_REQUEST, this.server.newErrorBody("2.0", null, -32602, "缺少sessionId"));
			return;
		}
		McpSession session = this.server.getSession(sessionId);
		if (session == null || !serviceKey.equals(session.getServiceKey())) {
			logger.info("MCP旧版消息请求session无效: sessionId={}, serviceKey={}", sessionId, serviceKey);
			this.server.sendJsonHttp(ctx, HttpResponseStatus.BAD_REQUEST, this.server.newErrorBody("2.0", null, -32602, "无效的sessionId"));
			return;
		}

		try {
			McpParsedRequest parsedRequest = this.protocolHandler.parseRequest(request, sessionId, "MCP旧版");
			McpProtocolResult result = this.protocolHandler.processRequest(serviceKey, sessionId, parsedRequest);
			if (result.hasResponse()) {
				this.server.sendSseData(sessionId, result.getResponse());
			}
			this.server.sendAccepted(ctx);
		} catch (McpProtocolException e) {
			this.server.sendJsonHttp(ctx, e.getStatus(), e.getResponseBody());
		}
	}
}
