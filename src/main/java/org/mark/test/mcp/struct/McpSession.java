package org.mark.test.mcp.struct;

import io.netty.channel.ChannelHandlerContext;

public class McpSession {

	private final String id;
	private final String serviceKey;
	private final boolean legacySse;
	private volatile ChannelHandlerContext ctx;

	public McpSession(String id, String serviceKey, ChannelHandlerContext ctx, boolean legacySse) {
		this.id = id;
		this.serviceKey = serviceKey;
		this.ctx = ctx;
		this.legacySse = legacySse;
	}

	public String getId() {
		return id;
	}

	public String getServiceKey() {
		return serviceKey;
	}

	public boolean isLegacySse() {
		return legacySse;
	}

	public ChannelHandlerContext getCtx() {
		return ctx;
	}

	public void bindCtx(ChannelHandlerContext ctx) {
		this.ctx = ctx;
	}

	public void clearCtx() {
		this.ctx = null;
	}

	public void clearCtxIfMatches(ChannelHandlerContext ctx) {
		if (this.ctx == ctx) {
			this.ctx = null;
		}
	}
}
