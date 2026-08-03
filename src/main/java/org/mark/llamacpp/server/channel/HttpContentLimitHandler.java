package org.mark.llamacpp.server.channel;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;

/**
 * 按路由限制 HTTP 请求体大小的入站守卫，插在 HttpObjectAggregator 之前。
 * <p>
 * 背景：聚合器上限历史上统一为 16MB，在 -Xmx96m / MaxDirectMemorySize=128m 的
 * 运行参数下，N 个并发大请求会产生 N×(16MB direct + toString 堆拷贝)，
 * 是最现实的 OOM 路径。本守卫按 URI 前缀决定每个请求允许的最大 body：
 * 默认 2MB，少数合法大 body 端点（音频转录、会话全量同步、多模态聊天）走白名单。
 * <p>
 * 超限返回 413 JSON 并关闭连接（替代旧行为：聚合器抛 TooLongFrameException 后
 * 静默掐连接）。未超限的请求零拷贝透传，状态在 LastHttpContent 后重置，
 * 支持 keep-alive 复用连接。
 * <p>
 * 非 @Sharable，每个连接一个实例。
 */
public class HttpContentLimitHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(HttpContentLimitHandler.class);

    private static final int KB = 1024;
    private static final int MB = 1024 * KB;

    /**
     * 默认请求体上限：2MB
     */
    public static final int DEFAULT_MAX_BYTES = 2 * MB;

    private final int defaultMaxBytes;
    private final Map<String, Integer> prefixLimits;

    /**
     * 当前请求的限额（-1 表示当前没有进行中的请求）
     */
    private long currentMax = -1;

    /**
     * 当前请求已累计的 body 字节数
     */
    private long received;

    /**
     * 已发送 413，后续帧直接丢弃直到连接关闭
     */
    private boolean rejecting;

    public HttpContentLimitHandler(int defaultMaxBytes, Map<String, Integer> prefixLimits) {
        this.defaultMaxBytes = defaultMaxBytes;
        this.prefixLimits = prefixLimits;
    }

    /**
     * 主服务限额表：音频转录 / 会话全量同步 / 背景图上传为合法大 body 端点。
     * 多模态聊天（/api/chat/stream-chat）不设上限：上游 EasyChatStreamingHandler
     * 已把 body 全部落盘到临时文件，到达本守卫的只是空 body 的转发请求，
     * 不存在聚合大 body 占内存的路径。
     */
    public static HttpContentLimitHandler forMainServer() {
        Map<String, Integer> limits = new LinkedHashMap<>();
        limits.put("/v1/audio/transcriptions", 16 * MB);
        limits.put("/api/chat/sync", 16 * MB);
        limits.put("/api/chat/background/upload", 4 * MB);
        limits.put("/api/chat/stream-chat", Integer.MAX_VALUE);
        return new HttpContentLimitHandler(DEFAULT_MAX_BYTES, limits);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (this.rejecting) {
            ReferenceCountUtil.release(msg);
            return;
        }

        if (msg instanceof HttpRequest request) {
            this.currentMax = resolveLimit(request.uri());
            this.received = 0;
            long declaredLength = HttpUtil.getContentLength(request, -1);
            if (declaredLength > this.currentMax) {
                ReferenceCountUtil.release(msg);
                reject(ctx, request.uri(), declaredLength);
                return;
            }
            if (!(msg instanceof HttpContent)) {
                ctx.fireChannelRead(msg);
                return;
            }
            // FullHttpRequest：继续按内容计数
        }

        if (msg instanceof HttpContent content) {
            if (this.currentMax >= 0) {
                this.received += content.content().readableBytes();
                if (this.received > this.currentMax) {
                    ReferenceCountUtil.release(msg);
                    reject(ctx, null, this.received);
                    return;
                }
            }
            if (msg instanceof LastHttpContent) {
                this.currentMax = -1;
                this.received = 0;
            }
        }

        ctx.fireChannelRead(msg);
    }

    /**
     * 按最长前缀匹配解析 URI 的限额（匹配前剥离 query string）
     */
    private int resolveLimit(String uri) {
        String path = uri == null ? "" : uri;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        String bestPrefix = null;
        for (String prefix : this.prefixLimits.keySet()) {
            if (path.startsWith(prefix) && (bestPrefix == null || prefix.length() > bestPrefix.length())) {
                bestPrefix = prefix;
            }
        }
        return bestPrefix != null ? this.prefixLimits.get(bestPrefix) : this.defaultMaxBytes;
    }

    /**
     * 返回 413 并关闭连接（body 未读完，无法安全复用连接）
     */
    private void reject(ChannelHandlerContext ctx, String uri, long actualBytes) {
        this.rejecting = true;
        logger.info("请求体超限，返回413: uri={}, limit={} bytes, declared/received={} bytes", uri, this.currentMax,
                actualBytes);
        byte[] body = ("{\"error\":\"request body too large\",\"limit\":" + this.currentMax + "}")
                .getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        response.content().writeBytes(body);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
