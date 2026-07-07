package org.mark.llamacpp.lmstudio.channel;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.Gson;
import org.mark.llamacpp.server.tools.JsonUtil;
import com.google.gson.JsonObject;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;

public class LMStudioWebServiceHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
	
	private static final ExecutorService async = Executors.newVirtualThreadPerTaskExecutor();
	private static final Gson gson = JsonUtil.gson();
	private static final String I18N_REQUEST_PARSE_FAILED = "api.error.request.parse.failed";
	private static final String I18N_METHOD_GET_ONLY = "common.method.get.only";
	private static final String I18N_NOT_FOUND = "api.error.notfound";
	
	@Override
	protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
		FullHttpRequest retained = request.retainedDuplicate();
		async.execute(() -> {
			try {
				handleRequest(ctx, retained);
			} finally {
				ReferenceCountUtil.release(retained);
			}
		});
	}
	
	private void handleRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		if (!request.decoderResult().isSuccess()) {
			sendJson(ctx, request, HttpResponseStatus.BAD_REQUEST, errorJson("bad_request", I18N_REQUEST_PARSE_FAILED));
			return;
		}
		
		String rawUri = request.uri();
		String path = rawUri;
		int q = rawUri.indexOf('?');
		if (q >= 0) {
			path = rawUri.substring(0, q);
		}
		
		if (request.method() == HttpMethod.OPTIONS) {
			sendNoContent(ctx, request);
			return;
		}
		
		if ("/health".equals(path)) {
			if (request.method() != HttpMethod.GET) {
				sendJson(ctx, request, HttpResponseStatus.METHOD_NOT_ALLOWED, errorJson("method_not_allowed", I18N_METHOD_GET_ONLY));
				return;
			}
			JsonObject ok = new JsonObject();
			ok.addProperty("status", "ok");
			ok.addProperty("timestamp", Instant.now().toEpochMilli());
			sendJson(ctx, request, HttpResponseStatus.OK, ok);
			return;
		}
		
		if ("/echo".equals(path)) {
			ByteBuf content = request.content();
			String body = content.isReadable() ? content.toString(CharsetUtil.UTF_8) : "";
			JsonObject resp = new JsonObject();
			resp.addProperty("method", request.method().name());
			resp.addProperty("path", path);
			resp.addProperty("body", body);
			sendJson(ctx, request, HttpResponseStatus.OK, resp);
			return;
		}
		
		if ("/".equals(path)) {
			if (request.method() != HttpMethod.GET) {
				sendJson(ctx, request, HttpResponseStatus.METHOD_NOT_ALLOWED, errorJson("method_not_allowed", I18N_METHOD_GET_ONLY));
				return;
			}
			sendText(ctx, request, HttpResponseStatus.OK, "LMStudio Web Service");
			return;
		}
		
		sendJson(ctx, request, HttpResponseStatus.NOT_FOUND, errorJson("not_found", I18N_NOT_FOUND));
	}
	
	private JsonObject errorJson(String code, String message) {
		JsonObject obj = new JsonObject();
		obj.addProperty("error", code);
		obj.addProperty("message", message);
		obj.addProperty("timestamp", Instant.now().toEpochMilli());
		return obj;
	}
	
	private void sendNoContent(ChannelHandlerContext ctx, FullHttpRequest request) {
		FullHttpResponse response = new io.netty.handler.codec.http.DefaultFullHttpResponse(
				HttpVersion.HTTP_1_1,
				HttpResponseStatus.NO_CONTENT,
				Unpooled.EMPTY_BUFFER);
		applyCommonHeaders(response, 0);
		writeResponse(ctx, request, response);
	}
	
	private void sendText(ChannelHandlerContext ctx, FullHttpRequest request, HttpResponseStatus status, String text) {
		byte[] bytes = text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
		ByteBuf buf = Unpooled.wrappedBuffer(bytes);
		FullHttpResponse response = new io.netty.handler.codec.http.DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, buf);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
		applyCommonHeaders(response, bytes.length);
		writeResponse(ctx, request, response);
	}
	
	private void sendJson(ChannelHandlerContext ctx, FullHttpRequest request, HttpResponseStatus status, JsonObject json) {
		String payload = gson.toJson(json);
		byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
		ByteBuf buf = Unpooled.wrappedBuffer(bytes);
		FullHttpResponse response = new io.netty.handler.codec.http.DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, buf);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
		applyCommonHeaders(response, bytes.length);
		writeResponse(ctx, request, response);
	}
	
	private void applyCommonHeaders(FullHttpResponse response, int contentLength) {
		response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, contentLength);
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET,POST,PUT,PATCH,DELETE,OPTIONS");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type,Authorization,Accept,Origin,User-Agent");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_EXPOSE_HEADERS, "*");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
		response.headers().set(HttpHeaderNames.CACHE_CONTROL, HttpHeaderValues.NO_CACHE);
	}
	
	private void writeResponse(ChannelHandlerContext ctx, FullHttpRequest request, FullHttpResponse response) {
		boolean keepAlive = io.netty.handler.codec.http.HttpUtil.isKeepAlive(request);
		if (keepAlive) {
			response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
			ctx.writeAndFlush(response);
		} else {
			ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
		}
	}
}

