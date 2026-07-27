package org.mark.llamacpp.server.channel;

import java.util.List;

import javax.net.ssl.SSLEngine;

import org.mark.llamacpp.server.security.WebSocketAuthHandler;
import org.mark.llamacpp.server.websocket.WebSocketServerHandler;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;

/**
 * HTTP/HTTPS 统一端口处理器。
 * <p>
 * 同一个 TCP 端口上，根据客户端首字节判断协议：
 * <ul>
 *   <li>首字节为 0x16（TLS ClientHello）→ 启用 HTTPS 协议栈</li>
 *   <li>否则 → 按纯 HTTP 处理；若 HTTPS 已启用，则返回 308 重定向到 HTTPS</li>
 * </ul>
 */
public class HttpHttpsUnificationHandler extends ByteToMessageDecoder {

    /**
     * TLS 握手记录的内容类型标识
     */
    private static final int TLS_HANDSHAKE_CONTENT_TYPE = 0x16;

    /**
     * 协议探测阶段的读超时秒数。探测完成后会移除该 handler，避免影响长连接。
     */
    private static final int PROTOCOL_DETECTION_READ_TIMEOUT_SECONDS = 10;

    /**
     * 读超时 handler 在 pipeline 中的名称，用于探测完成后精确移除
     */
    private static final String READ_TIMEOUT_HANDLER_NAME = "protocol-detection-read-timeout";

    /**
     * 兜底异常处理器：任何未被上层 handler 捕获的异常都直接关闭连接，防止半开连接挂起
     */
    @io.netty.channel.ChannelHandler.Sharable
    private static final class CloseOnExceptionHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            ctx.close();
        }
    }

    private static final CloseOnExceptionHandler CLOSE_ON_EXCEPTION = new CloseOnExceptionHandler();

    private final SslContext sslContext;
    private final int httpsPort;
    private final String websocketPath;
    private final int maxHttpContentLength;

    public HttpHttpsUnificationHandler(SslContext sslContext, int httpsPort, String websocketPath,
            int maxHttpContentLength) {
        this.sslContext = sslContext;
        this.httpsPort = httpsPort;
        this.websocketPath = websocketPath;
        this.maxHttpContentLength = maxHttpContentLength;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // 在协议探测阶段增加读超时，防止恶意/空连接永久挂起
        ctx.pipeline().addBefore(ctx.name(), READ_TIMEOUT_HANDLER_NAME,
                new ReadTimeoutHandler(PROTOCOL_DETECTION_READ_TIMEOUT_SECONDS));
        super.channelActive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        // 探测阶段任何异常（包括 ReadTimeoutException）都直接关闭连接
        ctx.close();
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (in.readableBytes() < 1) {
            // 数据不足，等待下一次读取
            return;
        }

        ChannelPipeline pipeline = ctx.pipeline();
        if (sslContext == null) {
            // HTTPS 未启用，直接走普通 HTTP
            enableHttp(pipeline);
        } else if (in.getUnsignedByte(0) == TLS_HANDSHAKE_CONTENT_TYPE) {
            // TLS 握手，走 HTTPS
            enableHttps(pipeline);
        } else {
            // 纯 HTTP，重定向到 HTTPS
            enableHttpRedirect(pipeline);
        }

        // 探测完成，移除超时 handler 和自身，把已读取的字节重新交给新的 pipeline 处理
        pipeline.remove(READ_TIMEOUT_HANDLER_NAME);
        pipeline.remove(this);
        out.add(in.retain());
    }

    /**
     * 组装 HTTPS 协议栈。
     * <p>
     * 注意：调用时 pipeline 中应当只有 HttpHttpsUnificationHandler 自己，
     * 因此 addLast 等价于在自身之后追加；移除自身后这些 handler 即成为新的 pipeline 头部。
     */
    private void enableHttps(ChannelPipeline pipeline) {
        SSLEngine engine = sslContext.newEngine(pipeline.channel().alloc());
        pipeline.addLast(new SslHandler(engine));
        addCommonHttpHandlers(pipeline);
        pipeline.addLast(CLOSE_ON_EXCEPTION);
    }

    /**
     * 组装普通 HTTP 协议栈
     */
    private void enableHttp(ChannelPipeline pipeline) {
        addCommonHttpHandlers(pipeline);
        pipeline.addLast(CLOSE_ON_EXCEPTION);
    }

    /**
     * 组装 HTTP 重定向协议栈（仅返回 308 重定向到 HTTPS）
     */
    private void enableHttpRedirect(ChannelPipeline pipeline) {
        pipeline.addLast(new HttpServerCodec());
        pipeline.addLast(new HttpObjectAggregator(maxHttpContentLength));
        pipeline.addLast(new HttpToHttpsRedirectHandler(httpsPort));
        pipeline.addLast(CLOSE_ON_EXCEPTION);
    }

    /**
     * 添加项目原有的业务 handler，顺序与原来保持一致
     */
    private void addCommonHttpHandlers(ChannelPipeline pipeline) {
        pipeline.addLast(new HttpServerCodec());
        pipeline.addLast(new OpenAIChatStreamingHandler());
        pipeline.addLast(new FileUploadRouterHandler());
        pipeline.addLast(new EasyChatStreamingHandler());
        pipeline.addLast(HttpContentLimitHandler.forMainServer());
        pipeline.addLast(new HttpObjectAggregator(maxHttpContentLength));
        pipeline.addLast(new ChunkedWriteHandler());
        pipeline.addLast(new WebSocketAuthHandler());  // WebSocket 握手认证，必须在 WebSocketServerProtocolHandler 之前
        pipeline.addLast(new WebSocketServerProtocolHandler(websocketPath, null, true, 32768));
        pipeline.addLast(new WebSocketServerHandler());
        pipeline.addLast(new BasicRouterHandler());
        pipeline.addLast(new FileDownloadRouterHandler());
        pipeline.addLast(new LlamaRouterHandler());
    }
}
