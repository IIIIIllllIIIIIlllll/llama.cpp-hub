package org.mark.llamacpp.server.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.ReferenceCountUtil;

/**
 * WebSocket 握手认证处理器。
 * <p>
 * 插入在 {@link io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler} 之前，
 * 用于在 WebSocket 握手阶段进行 API Key 验证，防止未认证的 WS 连接。
 * <ul>
 *   <li>如果是 WebSocket Upgrade 请求（uri 以 /ws 开头），先调用 {@link ApiKeyValidator#validate} 验证</li>
 *   <li>验证失败：返回 401 并关闭连接</li>
 *   <li>验证成功或非 WS 请求：直接传递给下游 handler</li>
 * </ul>
 */
public class WebSocketAuthHandler extends ChannelInboundHandlerAdapter {

	private static final Logger logger = LoggerFactory.getLogger(WebSocketAuthHandler.class);

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		if (msg instanceof HttpRequest request) {
			// 检查是否是 WebSocket Upgrade 请求（uri 以 /ws 开头）
			String uri = request.uri();
			String upgradeHeader = request.headers().get(HttpHeaderNames.UPGRADE);
			if (uri != null && uri.startsWith("/ws")
					&& upgradeHeader != null && upgradeHeader.equalsIgnoreCase("websocket")) {
				// WebSocket 握手请求，先验证 API Key
				String clientIp = ApiKeyValidator.getClientIp(ctx, request);
				if (!ApiKeyValidator.validate(request, clientIp)) {
					logger.warn("WebSocket 握手认证失败，拒绝连接: uri={}, ip={}", uri, clientIp);
					ApiKeyValidator.sendUnauthorized(ctx, request);
					ReferenceCountUtil.release(msg);
					return;
				}
			}
		}
		// 非 WS 请求或验证通过，传递给下游
		ctx.fireChannelRead(msg);
	}
}
