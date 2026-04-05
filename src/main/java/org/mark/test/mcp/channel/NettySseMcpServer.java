package org.mark.test.mcp.channel;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.test.mcp.struct.McpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.stream.ChunkedWriteHandler;

public class NettySseMcpServer {

	private static final Logger logger = LoggerFactory.getLogger(NettySseMcpServer.class);
	private static final String SESSION_HEADER = "MCP-Session-Id";

	private final Map<String, McpSession> sessions = new ConcurrentHashMap<>();
	private final int port;
	private final LegacySseTransportHandler legacySseTransportHandler;
	private final StreamableHttpTransportHandler streamableHttpTransportHandler;

	private NioEventLoopGroup bossGroup;
	private NioEventLoopGroup workerGroup;
	private ChannelFuture bindFuture;
	private volatile boolean running;

	public NettySseMcpServer(int port, McpProtocolHandler protocolHandler) {
		this.port = port;
		this.legacySseTransportHandler = new LegacySseTransportHandler(this, protocolHandler);
		this.streamableHttpTransportHandler = new StreamableHttpTransportHandler(this, protocolHandler);
	}

	public synchronized void start() throws Exception {
		if (this.running) {
			return;
		}
		this.bossGroup = new NioEventLoopGroup(1);
		this.workerGroup = new NioEventLoopGroup();
		try {
			ServerBootstrap bootstrap = new ServerBootstrap();
			bootstrap.group(this.bossGroup, this.workerGroup).channel(NioServerSocketChannel.class)
					.option(ChannelOption.SO_BACKLOG, 1024).childOption(ChannelOption.SO_KEEPALIVE, true)
					.childHandler(new ChannelInitializer<SocketChannel>() {
						@Override
						protected void initChannel(SocketChannel ch) throws Exception {
							ch.pipeline().addLast(new HttpServerCodec()).addLast(new HttpObjectAggregator(2 * 1024 * 1024))
									.addLast(new ChunkedWriteHandler()).addLast(new McpRouterHandler(NettySseMcpServer.this));
						}
					});
			this.bindFuture = bootstrap.bind(this.port).sync();
			this.running = true;
			this.bindFuture.channel().closeFuture().addListener(future -> this.running = false);
			logger.info("MCP测试服务启动成功: http://localhost:{}", this.port);
		} catch (Exception e) {
			this.shutdownEventLoopGroups();
			this.bindFuture = null;
			this.running = false;
			throw e;
		}
	}

	public synchronized void stop() {
		this.sessions.clear();
		if (this.bindFuture != null && this.bindFuture.channel() != null) {
			this.bindFuture.channel().close().syncUninterruptibly();
		}
		this.shutdownEventLoopGroups();
		this.bindFuture = null;
		this.running = false;
	}

	public void awaitClose() throws InterruptedException {
		ChannelFuture future = this.bindFuture;
		if (future != null && future.channel() != null) {
			future.channel().closeFuture().sync();
		}
	}

	public boolean isRunning() {
		return this.running;
	}

	public int getPort() {
		return this.port;
	}

	private void shutdownEventLoopGroups() {
		if (this.bossGroup != null) {
			this.bossGroup.shutdownGracefully().syncUninterruptibly();
			this.bossGroup = null;
		}
		if (this.workerGroup != null) {
			this.workerGroup.shutdownGracefully().syncUninterruptibly();
			this.workerGroup = null;
		}
	}

	public void handleLegacySseConnect(ChannelHandlerContext ctx, String serviceKey) {
		this.legacySseTransportHandler.handleConnect(ctx, serviceKey);
	}

	public void openLegacySseStream(ChannelHandlerContext ctx, String serviceKey) {
		McpSession session = this.createSession(serviceKey, true);
		session.bindCtx(ctx);
		String sessionId = session.getId();
		logger.info("MCP旧版SSE连接建立: serviceKey={}, sessionId={}, remote={}", serviceKey, sessionId, ctx.channel().remoteAddress());
		DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		this.applySseHeaders(response.headers(), sessionId);
		ctx.writeAndFlush(response);
		String endpoint = "/mcp/" + serviceKey + "/message?sessionId=" + sessionId;
		this.sendSseEvent(sessionId, "endpoint", endpoint);
	}

	public void handleStreamableGet(ChannelHandlerContext ctx, String serviceKey, String sessionId) {
		this.streamableHttpTransportHandler.handleGet(ctx, serviceKey, sessionId);
	}

	public void openStreamableSseStream(ChannelHandlerContext ctx, String serviceKey, String sessionId) {
		McpSession session = this.sessions.get(sessionId);
		if (session == null || !serviceKey.equals(session.getServiceKey())) {
			logger.info("MCP Streamable GET会话不存在: serviceKey={}, sessionId={}", serviceKey, sessionId);
			this.handleNotFound(ctx);
			return;
		}
		session.bindCtx(ctx);
		logger.info("MCP Streamable SSE连接建立: serviceKey={}, sessionId={}, remote={}", serviceKey, sessionId, ctx.channel().remoteAddress());
		DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		this.applySseHeaders(response.headers(), sessionId);
		ctx.writeAndFlush(response);
	}

	public void handleLegacyMessagePost(ChannelHandlerContext ctx, FullHttpRequest request, String serviceKey) {
		this.legacySseTransportHandler.handleMessagePost(ctx, request, serviceKey);
	}

	public void handleStreamablePost(ChannelHandlerContext ctx, FullHttpRequest request, String serviceKey) {
		this.streamableHttpTransportHandler.handlePost(ctx, request, serviceKey);
	}

	public void handleStreamableDelete(ChannelHandlerContext ctx, FullHttpRequest request, String serviceKey) {
		this.streamableHttpTransportHandler.handleDelete(ctx, request, serviceKey);
	}

	public void handleOptions(ChannelHandlerContext ctx) {
		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT);
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET,POST,DELETE,OPTIONS");
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
		ctx.writeAndFlush(response);
	}

	public void handleNotFound(ChannelHandlerContext ctx) {
		byte[] bytes = "Not Found".getBytes(StandardCharsets.UTF_8);
		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND, Unpooled.wrappedBuffer(bytes));
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
		response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
		ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
	}

	public void handleBadRequest(ChannelHandlerContext ctx, String message) {
		logger.info("MCP请求错误: remote={}, message={}", ctx.channel().remoteAddress(), message);
		JsonObject body = new JsonObject();
		body.addProperty("message", message == null ? "" : message);
		this.sendJsonHttp(ctx, HttpResponseStatus.BAD_REQUEST, body);
	}

	public String cleanPath(String uri) {
		int index = uri == null ? -1 : uri.indexOf('?');
		if (index < 0) {
			return uri == null ? "" : uri;
		}
		return uri.substring(0, index);
	}

	public String readSessionIdHeader(FullHttpRequest request) {
		if (request == null) {
			return null;
		}
		String sessionId = request.headers().get(SESSION_HEADER);
		return sessionId == null ? null : sessionId.trim();
	}

	public McpSession createSession(String serviceKey, boolean legacySse) {
		String sessionId = UUID.randomUUID().toString().replace("-", "");
		McpSession session = new McpSession(sessionId, serviceKey, null, legacySse);
		this.sessions.put(sessionId, session);
		return session;
	}

	public void cleanupByContext(ChannelHandlerContext ctx) {
		this.sessions.entrySet().removeIf(e -> {
			McpSession session = e.getValue();
			if (session == null || session.getCtx() != ctx) {
				return false;
			}
			session.clearCtxIfMatches(ctx);
			return session.isLegacySse();
		});
	}

	public McpSession getSession(String sessionId) {
		return this.sessions.get(sessionId);
	}

	public void removeSession(String sessionId) {
		this.sessions.remove(sessionId);
	}

	public void sendSseData(String sessionId, JsonObject data) {
		String payload = "data: " + JsonUtil.toJson(data) + "\n\n";
		this.writeSsePayload(sessionId, payload);
	}

	public void sendAccepted(ChannelHandlerContext ctx) {
		this.sendAccepted(ctx, null);
	}

	public void sendAccepted(ChannelHandlerContext ctx, String sessionId) {
		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.ACCEPTED);
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET,POST,DELETE,OPTIONS");
		if (sessionId != null && !sessionId.isBlank()) {
			response.headers().set(SESSION_HEADER, sessionId);
		}
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
		ctx.writeAndFlush(response);
	}

	public void sendNoContent(ChannelHandlerContext ctx, String sessionId) {
		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT);
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET,POST,DELETE,OPTIONS");
		if (sessionId != null && !sessionId.isBlank()) {
			response.headers().set(SESSION_HEADER, sessionId);
		}
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
		ctx.writeAndFlush(response);
	}

	public void sendJsonHttp(ChannelHandlerContext ctx, HttpResponseStatus status, JsonObject obj) {
		this.sendJsonHttp(ctx, status, obj, null);
	}

	public void sendJsonHttp(ChannelHandlerContext ctx, HttpResponseStatus status, JsonObject obj, String sessionId) {
		byte[] bytes = JsonUtil.toJson(obj).getBytes(StandardCharsets.UTF_8);
		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET,POST,DELETE,OPTIONS");
		if (sessionId != null && !sessionId.isBlank()) {
			response.headers().set(SESSION_HEADER, sessionId);
		}
		response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
		ctx.writeAndFlush(response);
	}

	public JsonObject newErrorBody(String jsonrpcVersion, JsonElement id, int code, String message) {
		JsonObject obj = new JsonObject();
		obj.addProperty("jsonrpc", jsonrpcVersion == null ? "2.0" : jsonrpcVersion);
		if (id == null) {
			obj.addProperty("id", 0);
		} else {
			obj.add("id", id.deepCopy());
		}
		JsonObject error = new JsonObject();
		error.addProperty("code", code);
		error.addProperty("message", message == null ? "" : message);
		obj.add("error", error);
		return obj;
	}

	private void applySseHeaders(HttpHeaders headers, String sessionId) {
		headers.set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream; charset=UTF-8");
		headers.set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
		headers.set(HttpHeaderNames.CONNECTION, "keep-alive");
		headers.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		headers.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
		headers.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET,POST,DELETE,OPTIONS");
		if (sessionId != null && !sessionId.isBlank()) {
			headers.set(SESSION_HEADER, sessionId);
		}
	}

	private void sendSseEvent(String sessionId, String event, String data) {
		String payload = "event: " + event + "\n" + "data: " + data + "\n\n";
		this.writeSsePayload(sessionId, payload);
	}

	private void writeSsePayload(String sessionId, String payload) {
		McpSession session = this.sessions.get(sessionId);
		if (session == null) {
			return;
		}
		ChannelHandlerContext sessionCtx = session.getCtx();
		if (sessionCtx == null || !sessionCtx.channel().isActive()) {
			if (session.isLegacySse()) {
				this.sessions.remove(sessionId);
			} else {
				session.clearCtx();
			}
			return;
		}
		ByteBuf buf = Unpooled.copiedBuffer(payload, StandardCharsets.UTF_8);
		sessionCtx.writeAndFlush(new DefaultHttpContent(buf)).addListener((ChannelFutureListener) future -> {
			if (!future.isSuccess()) {
				if (session.isLegacySse()) {
					this.sessions.remove(sessionId);
				} else {
					session.clearCtx();
				}
			}
		});
	}
}
