package org.mark.llamacpp.server.channel;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.security.ApiKeyValidator;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.EmptyHttpHeaders;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Intercepts POST /api/chat/stream-chat before HttpObjectAggregator.
 * Streams incoming HttpContent chunks directly to a temp file on disk,
 * then passes the file path to downstream via channel attribute.
 * This avoids the 16MB in-memory limit of HttpObjectAggregator.
 */
public class EasyChatStreamingHandler extends ChannelInboundHandlerAdapter {

	private static final Logger logger = LoggerFactory.getLogger(EasyChatStreamingHandler.class);

	private static final String I18N_STREAM_RECEIVE_FAILED = "api.error.stream.receive.failed";

	public static final AttributeKey<Path> STREAMING_BODY_FILE = AttributeKey.newInstance("easyChatStreamingBodyFile");

	private static final String PATH_STREAM_CHAT = "/api/chat/stream-chat";

	private boolean intercepting;
	private HttpRequest initialRequest;
	private Path tempFile;
	private RandomAccessFile tempRaf;

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		if (!(msg instanceof HttpObject)) {
			ctx.fireChannelRead(msg);
			return;
		}

		HttpObject httpObject = (HttpObject) msg;

		// Not intercepting yet — check if this is a POST stream-chat request
		if (!this.intercepting && httpObject instanceof HttpRequest request) {
			if (!request.uri().startsWith(PATH_STREAM_CHAT)) {
				ctx.fireChannelRead(msg);
				return;
			}

			// Only intercept POST — GET/OPTIONS pass through
			if (request.method() != HttpMethod.POST) {
				ctx.fireChannelRead(msg);
				return;
			}

			// 认证检查：未通过验证则返回 401，不写临时文件
			String clientIp = ApiKeyValidator.getClientIp(ctx, request);
			if (!ApiKeyValidator.validate(request, clientIp)) {
				logger.warn("[EasyChat][Streaming] 认证失败，拒绝请求: uri={}, ip={}", request.uri(), clientIp);
				ApiKeyValidator.sendUnauthorized(ctx, request);
				ReferenceCountUtil.release(msg);
				return;
			}

			this.intercepting = true;
			this.initialRequest = request;

			// Create temp file immediately
			Path cacheDir = LlamaServer.getCachePath().toAbsolutePath().normalize();
			Path tempDir = cacheDir.resolve("temp");
			if (!Files.exists(tempDir)) {
				Files.createDirectories(tempDir);
			}
			this.tempFile = Files.createTempFile(tempDir, "easychat-", ".bin");
			this.tempRaf = new RandomAccessFile(this.tempFile.toFile(), "rw");

			logger.info("[EasyChat][Streaming] 拦截请求: method={}, uri={}, tempFile={}", request.method(), request.uri(), this.tempFile);
			ReferenceCountUtil.release(msg);
			return;
		}

		// Not intercepting — pass through
		if (!this.intercepting) {
			ctx.fireChannelRead(msg);
			return;
		}

		// Write content chunks to temp file
		try {
			if (httpObject instanceof HttpContent content) {
				ByteBuf data = content.content();
				if (data.isReadable() && this.tempRaf != null) {
					int readable = data.readableBytes();
					if (data.hasArray()) {
						this.tempRaf.write(data.array(), data.arrayOffset() + data.readerIndex(), readable);
					} else {
						byte[] tmp = new byte[readable];
						data.getBytes(data.readerIndex(), tmp);
						this.tempRaf.write(tmp);
					}
				}

				if (httpObject instanceof LastHttpContent last) {
					this.completeInterception(ctx, last.trailingHeaders());
				}
			}
		} catch (Exception e) {
			logger.info("[EasyChat][Streaming] 接收请求体失败", e);
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, I18N_STREAM_RECEIVE_FAILED + ": " + e.getMessage());
			this.reset();
		} finally {
			ReferenceCountUtil.release(msg);
		}
	}

	/**
	 * Request body fully received — close temp file, pass path via channel attr,
	 * construct empty-body FullHttpRequest and forward downstream.
	 */
	private void completeInterception(ChannelHandlerContext ctx, io.netty.handler.codec.http.HttpHeaders trailingHeaders) throws Exception {
		try {
			if (this.tempRaf != null) {
				this.tempRaf.close();
				this.tempRaf = null;
			}

			long fileSize = this.tempFile.toFile().length();
			logger.info("[EasyChat][Streaming] 请求体写入完成: size={} bytes, file={}", fileSize, this.tempFile);

			// Pass temp file path to downstream via channel attribute
			ctx.channel().attr(STREAMING_BODY_FILE).set(this.tempFile);

			// Build FullHttpRequest with empty body — data is in the temp file
			FullHttpRequest fullRequest = new DefaultFullHttpRequest(
				initialRequest.protocolVersion(),
				initialRequest.method(),
				initialRequest.uri(),
				ctx.alloc().buffer(0),
				initialRequest.headers(),
				trailingHeaders != null ? trailingHeaders : EmptyHttpHeaders.INSTANCE);

			// Forward to downstream handlers
			ctx.fireChannelRead(fullRequest);
		} finally {
			// Don't delete temp file — downstream will read it
			this.tempFile = null;
			this.reset();
		}
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		this.cleanupTempFile();
		this.reset();
		super.channelInactive(ctx);
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
		if (this.intercepting) {
			logger.info("[EasyChat][Streaming] 异常", cause);
			this.cleanupTempFile();
			this.reset();
		}
		ctx.fireExceptionCaught(cause);
	}

	private void cleanupTempFile() {
		if (this.tempRaf != null) {
			try {
				this.tempRaf.close();
			} catch (Exception ignore) {
			}
			this.tempRaf = null;
		}
		if (this.tempFile != null) {
			try {
				Files.deleteIfExists(this.tempFile);
			} catch (Exception ignore) {
			}
			this.tempFile = null;
		}
	}

	private void reset() {
		this.initialRequest = null;
		this.intercepting = false;
	}
}
