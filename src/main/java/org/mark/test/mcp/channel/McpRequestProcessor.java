package org.mark.test.mcp.channel;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;

public interface McpRequestProcessor {

	void handleMessagePost(NettySseMcpServer server, ChannelHandlerContext ctx, FullHttpRequest request, String serviceKey);
}
