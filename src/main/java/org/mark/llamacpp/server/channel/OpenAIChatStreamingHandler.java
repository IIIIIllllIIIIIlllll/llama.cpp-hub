package org.mark.llamacpp.server.channel;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.service.ChatStreamSession;
import org.mark.llamacpp.server.service.OpenAIService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;

/**
 * 负责在 Netty 管线里拦截 OpenAI 聊天接口的分块请求体。
 * 这个 Handler 自己不直接解析完整 JSON，而是把每个 HttpContent 片段转交给 ChatStreamSession，
 * 让后者一边接收请求体、一边解析 model / stream 等关键字段，并尽早建立到 llama.cpp 的转发连接。
 */
public class OpenAIChatStreamingHandler extends ChannelInboundHandlerAdapter {

	private static final Logger logger = LoggerFactory.getLogger(OpenAIChatStreamingHandler.class);

	private final OpenAIService openAIService = new OpenAIService();
	
	/**
	 * 	当前连接正在处理的聊天流式会话；当本次请求符合聊天补全+超大长度时才会创建。
	 */
	private ChatStreamSession currentSession;
	
	/**
	 * 	标记当前请求是否已经被本 Handler 接管，避免后续 HttpContent 再落到普通路由逻辑。
	 */
	private boolean intercepting;
	
	
	public OpenAIChatStreamingHandler() {
		
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		// 非HTTP请求直接跳过。
		if (!(msg instanceof HttpObject)) {
			ctx.fireChannelRead(msg);
			return;
		}
		
		HttpObject httpObject = (HttpObject) msg;
		// 1、没有启用接管
		if (!this.intercepting && httpObject instanceof HttpRequest request) {
			// 只有开启聊天流式能力，且命中 chat completion 路径时，才进入“边收边转发”模式。
			if (!LlamaServer.isChatStreamingEnabled() || !this.isChatUri(request.uri())) {
				ctx.fireChannelRead(msg);
				return;
			}
			
			// 命中后立刻切换到拦截模式，后续同一请求的 HttpContent 都由当前 Handler 消费。
			this.intercepting = true;
			if (request.method() == HttpMethod.OPTIONS) {
				// 浏览器预检请求不需要进入模型转发流程，直接返回 CORS 响应。
				this.sendCorsPreflight(ctx);
				ReferenceCountUtil.release(msg);
				this.resetSession();
				return;
			}
			// 判断是不是v1的API，再判断有无密钥，实际上密钥压根没用过。
			if (request.uri().startsWith("/v1") && !this.validateApiKey(request)) {
				// OpenAI 兼容前缀下先做鉴权，失败时立即终止当前会话。
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.UNAUTHORIZED, "invalid api key");
				ReferenceCountUtil.release(msg);
				this.resetSession();
				return;
			}

			// 会话启动后会在独立线程中读取 requestBodyStream，并在识别出 model 后连接目标 llama.cpp 进程。
			this.currentSession = new ChatStreamSession(ctx, this.openAIService, request.method(), this.copyHeaders(request));
			this.currentSession.start();
		}
		// 2. 没有启用接管，也没有相应的会话，直接跳过。
		if (!this.intercepting || this.currentSession == null) {
			ctx.fireChannelRead(msg);
			return;
		}
		
		
		try {
			if (httpObject instanceof HttpContent content) {
				// Netty 可能把请求体拆成多个 chunk，这里逐块喂给 ChatStreamSession。
				this.currentSession.offer(content.content());
				// 请求结束的处理。
				if (httpObject instanceof LastHttpContent) {
					// 收到最后一个分块后通知会话“输入结束”，让后台线程继续完成转发和响应回写。
					this.currentSession.complete();
					// 这里重置的是 Handler 的拦截状态，不影响已经启动的后台会话继续执行。
					this.resetSession();
				}
			}
		} catch (IOException e) {
			logger.info("接收聊天流式请求体失败", e);
			this.currentSession.cancel();
			this.resetSession();
			this.openAIService.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, e.getMessage(), null);
		} finally {
			ReferenceCountUtil.release(msg);
		}
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		if (this.currentSession != null) {
			// 客户端断开时主动取消后台转发，避免继续占用 llama.cpp 连接。
			this.currentSession.cancel();
		}
		this.openAIService.channelInactive(ctx);
		this.resetSession();
		super.channelInactive(ctx);
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
		logger.info("处理聊天流式请求时发生异常", cause);
		if (this.currentSession != null) {
			// 异常场景同样要取消会话，确保输入流、输出流、代理连接都能尽快释放。
			this.currentSession.cancel();
		}
		this.resetSession();
		ctx.close();
	}
	
	/**
	 * 	是否为聊天补全的端点。
	 * @param uri
	 * @return
	 */
	private boolean isChatUri(String uri) {
		if (uri == null) {
			return false;
		}
		return uri.startsWith("/v1/chat/completions")
				|| uri.startsWith("/v1/chat/completion")
				|| uri.startsWith("/chat/completion");
	}
	
	/**
	 * 	复制请求头。
	 * @param request
	 * @return
	 */
	private Map<String, String> copyHeaders(HttpRequest request) {
		Map<String, String> headers = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : request.headers()) {
			headers.put(entry.getKey(), entry.getValue());
		}
		return headers;
	}
	
	/**
	 * 	验证API的密钥，但是实际没啥用。
	 * @param request
	 * @return
	 */
	private boolean validateApiKey(HttpRequest request) {
		if (!LlamaServer.isApiKeyValidationEnabled()) {
			return true;
		}
		String expected = LlamaServer.getApiKey();
		if (expected == null || expected.isBlank()) {
			return false;
		}
		String auth = request.headers().get(HttpHeaderNames.AUTHORIZATION);
		if (auth == null) {
			return false;
		}
		auth = auth.replace("Bearer ", "");
		return expected.equals(auth);
	}

	private void sendCorsPreflight(ChannelHandlerContext ctx) {
		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type, Authorization");
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);

		ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				// 预检响应发完即可关闭连接，避免保留一个空闲 HTTP 通道。
				ctx.close();
			}
		});
	}

	private void resetSession() {
		// 仅清理 Handler 持有的当前请求状态，不会中断已经交给 ChatStreamSession 的后台执行逻辑。
		this.intercepting = false;
		this.currentSession = null;
	}
}
