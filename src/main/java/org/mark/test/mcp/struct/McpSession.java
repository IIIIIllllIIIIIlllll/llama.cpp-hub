package org.mark.test.mcp.struct;

import io.netty.channel.ChannelHandlerContext;

public class McpSession {

	private final String id;
	private final String serviceKey;
	private final ChannelHandlerContext ctx;

	public McpSession(String id, String serviceKey, ChannelHandlerContext ctx) {
		this.id = id;
		this.serviceKey = serviceKey;
		this.ctx = ctx;
	}

	public String getId() {
		return id;
	}

	public String getServiceKey() {
		return serviceKey;
	}

	public ChannelHandlerContext getCtx() {
		return ctx;
	}
}
