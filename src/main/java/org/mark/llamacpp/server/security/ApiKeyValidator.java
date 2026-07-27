package org.mark.llamacpp.server.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.mark.llamacpp.server.LlamaServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.DefaultHttpResponse;

/**
 * API Key 安全验证工具。
 * 提供：
 * 1. 常量时间比较（防计时攻击）
 * 2. Bearer / x-api-key 验证方式
 * 3. IP 级别暴力破解防护（5 次失败后封禁 15 分钟）
 */
public class ApiKeyValidator {

    private static final Logger logger = LoggerFactory.getLogger(ApiKeyValidator.class);

    /** 登录页 HTML，浏览器无 API Key 时返回 */
    private static final String LOGIN_PAGE_HTML = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>llama.cpp-hub</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:system-ui,-apple-system,sans-serif;display:flex;align-items:center;justify-content:center;min-height:100vh;background:#1a1a2e;color:#e0e0e0}
.card{background:#16213e;padding:2rem;border-radius:0.75rem;box-shadow:0 4px 24px rgba(0,0,0,.4);width:90%;max-width:360px}
.card h1{font-size:1.1rem;margin-bottom:1.2rem;text-align:center;color:#e94560}
.card input{width:100%;padding:0.65rem 0.8rem;border:1px solid #333;border-radius:0.4rem;background:#0f3460;color:#e0e0e0;font-size:0.9rem;outline:none}
.card input:focus{border-color:#e94560}
.card button{width:100%;margin-top:0.8rem;padding:0.65rem;border:none;border-radius:0.4rem;background:#e94560;color:#fff;font-size:0.9rem;cursor:pointer}
.card button:hover{background:#c81d45}
.card button:disabled{opacity:.6;cursor:wait}
.err{color:#ff6b6b;font-size:0.8rem;margin-top:0.6rem;text-align:center;min-height:1rem}
</style>
</head>
<body>
<div class="card">
<h1>llama.cpp-hub</h1>
<input type="password" id="key" placeholder="API Key" autofocus />
<button id="btn" onclick="submit()">Login</button>
<div class="err" id="err"></div>
</div>
<script>
function submit(){
var k=document.getElementById('key').value.trim();
if(!k)return;
var b=document.getElementById('btn');b.disabled=true;
var e=document.getElementById('err');e.textContent='';
fetch('/api/auth/verify',{headers:{'Authorization':'Bearer '+k}})
.then(function(r){if(!r.ok)throw 0;return r.json()})
.then(function(){document.cookie='lh-api-key='+encodeURIComponent(k)+';path=/;max-age=604800;SameSite=Strict'+(location.protocol==='https:'?';Secure':'');location.reload()})
.catch(function(){e.textContent='Invalid API Key';b.disabled=false})
}
document.getElementById('key').addEventListener('keydown',function(ev){if(ev.key==='Enter')submit()});
</script>
</body>
</html>
""";

    /** 最大失败次数 */
    private static final int MAX_FAILURES = 5;
    /** 封禁时长（毫秒） */
    private static final long BAN_DURATION_MS = 15 * 60 * 1000;
    /** 记录清理间隔 */
    private static final long CLEANUP_INTERVAL_MS = 5 * 60 * 1000;

    private static final ConcurrentHashMap<String, AttemptTracker> failedAttempts = new ConcurrentHashMap<>();
    private static volatile long lastCleanup = System.currentTimeMillis();

    static class AttemptTracker {
        final AtomicInteger count = new AtomicInteger(0);
        volatile long lastAttempt;
        volatile long bannedUntil;

        AttemptTracker() {
            this.lastAttempt = System.currentTimeMillis();
        }
    }

    private ApiKeyValidator() {
    }

    /**
     * 验证请求是否携带正确的 API Key。
     * 支持：
     * - Authorization: Bearer <key>（推荐，API 客户端用）
     * - x-api-key: <key>（Anthropic 风格）
     * - Cookie: lh-api-key=<key>（浏览器导航请求自动携带）
     *
     * @return true 如果验证通过或未启用验证
     */
    public static boolean validate(HttpRequest request, String clientIp) {
        if (!LlamaServer.isApiKeyValidationEnabled()) {
            return true;
        }
        String expected = LlamaServer.getApiKey();
        if (expected == null || expected.isBlank()) {
            return false;
        }

        // 检查 IP 是否被封禁
        if (isBanned(clientIp)) {
            logger.warn("IP {} 已被封禁，拒绝访问", clientIp);
            return false;
        }

        boolean ok = doValidate(request, expected);
        if (!ok) {
            recordFailure(clientIp);
        } else {
            clearFailures(clientIp);
        }
        return ok;
    }

    private static boolean doValidate(HttpRequest request, String expected) {
        // 1. Bearer token（推荐，API 客户端用）
        String auth = request.headers().get(HttpHeaderNames.AUTHORIZATION);
        if (auth != null && auth.startsWith("Bearer ")) {
            return constantTimeEquals(auth.substring(7), expected);
        }

        // 2. x-api-key header（Anthropic 风格）
        String apiKey = request.headers().get("x-api-key");
        if (apiKey != null && !apiKey.isBlank()) {
            return constantTimeEquals(apiKey, expected);
        }

        // 3. Cookie（浏览器导航请求自动携带）
        String cookieHeader = request.headers().get(HttpHeaderNames.COOKIE);
        if (cookieHeader != null) {
            String cookieKey = extractCookie(cookieHeader, "lh-api-key");
            if (cookieKey != null && !cookieKey.isEmpty()) {
                return constantTimeEquals(cookieKey, expected);
            }
        }

        return false;
    }

    private static String extractCookie(String cookieHeader, String name) {
        for (String cookie : cookieHeader.split(";")) {
            String trimmed = cookie.trim();
            if (trimmed.startsWith(name + "=")) {
                try {
                    return java.net.URLDecoder.decode(trimmed.substring(name.length() + 1), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    return trimmed.substring(name.length() + 1);
                }
            }
        }
        return null;
    }

    /**
     * 常量时间字符串比较，防止计时攻击。
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] ba = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(ba, bb);
    }

    // ========== IP 限速 ==========

    private static boolean isBanned(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }
        AttemptTracker tracker = failedAttempts.get(ip);
        if (tracker == null) {
            return false;
        }
        if (tracker.bannedUntil > System.currentTimeMillis()) {
            return true;
        }
        // 封禁过期，重置
        if (tracker.bannedUntil > 0 && tracker.bannedUntil <= System.currentTimeMillis()) {
            failedAttempts.remove(ip);
        }
        return false;
    }

    private static void recordFailure(String ip) {
        if (ip == null || ip.isEmpty()) {
            return;
        }
        cleanupIfNeeded();
        // 防止 map 无限增长：超过上限时不再记录新 IP
        if (failedAttempts.size() >= 10000 && !failedAttempts.containsKey(ip)) {
            logger.warn("failedAttempts map 已达上限 (10000)，跳过新 IP 记录: {}", ip);
            return;
        }
        AttemptTracker tracker = failedAttempts.computeIfAbsent(ip, k -> new AttemptTracker());
        int count = tracker.count.incrementAndGet();
        tracker.lastAttempt = System.currentTimeMillis();
        if (count >= MAX_FAILURES) {
            tracker.bannedUntil = System.currentTimeMillis() + BAN_DURATION_MS;
            logger.warn("IP {} 连续 {} 次验证失败，封禁 15 分钟", ip, count);
        }
    }

    private static void clearFailures(String ip) {
        if (ip == null || ip.isEmpty()) {
            return;
        }
        failedAttempts.remove(ip);
    }

    private static void cleanupIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastCleanup < CLEANUP_INTERVAL_MS) {
            return;
        }
        lastCleanup = now;
        failedAttempts.entrySet().removeIf(entry -> {
            AttemptTracker t = entry.getValue();
            return t.bannedUntil > 0 && t.bannedUntil <= now
                    || t.bannedUntil == 0 && now - t.lastAttempt > BAN_DURATION_MS;
        });
    }

    /**
     * 发送 401 响应。
     * 浏览器请求（Accept: text/html）返回登录页 HTML；
     * API 请求返回 JSON 401。
     */
    public static void sendUnauthorized(ChannelHandlerContext ctx, HttpRequest request) {
        String accept = request.headers().get(HttpHeaderNames.ACCEPT);
        boolean isBrowser = accept != null && accept.contains("text/html");

        if (isBrowser) {
            sendHtmlResponse(ctx, LOGIN_PAGE_HTML);
        } else {
            sendJsonResponse(ctx, "{\"error\":{\"message\":\"Unauthorized\",\"type\":\"authentication_error\"}}", HttpResponseStatus.UNAUTHORIZED);
        }
    }

    private static void sendHtmlResponse(ChannelHandlerContext ctx, String html) {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.UNAUTHORIZED);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=utf-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
        LlamaServer.setCorsHeaders(response.headers());
        ctx.write(response);
        ctx.writeAndFlush(io.netty.buffer.Unpooled.wrappedBuffer(body));
    }

    private static void sendJsonResponse(ChannelHandlerContext ctx, String json, HttpResponseStatus status) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, status);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
        LlamaServer.setCorsHeaders(response.headers());
        ctx.write(response);
        ctx.writeAndFlush(io.netty.buffer.Unpooled.wrappedBuffer(body));
    }

    /**
     * 从请求中提取客户端 IP。
     */
    public static String getClientIp(ChannelHandlerContext ctx, HttpRequest request) {
        // 直接从 TCP 连接提取客户端 IP，不信任 X-Forwarded-For / X-Real-IP 等可伪造的代理头
        if (ctx.channel().remoteAddress() != null) {
            String addr = ctx.channel().remoteAddress().toString();
            // 格式: /192.168.1.1:12345
            int slash = addr.indexOf('/');
            int colon = addr.lastIndexOf(':');
            if (slash >= 0 && colon > slash) {
                return addr.substring(slash + 1, colon);
            }
        }
        return "";
    }
}
