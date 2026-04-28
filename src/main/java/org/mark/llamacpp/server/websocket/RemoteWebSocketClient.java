package org.mark.llamacpp.server.websocket;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.mark.llamacpp.server.tools.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class RemoteWebSocketClient {

    private static final Logger logger = LoggerFactory.getLogger(RemoteWebSocketClient.class);
    private static final int INITIAL_RECONNECT_DELAY_SECONDS = 1;
    private static final int MAX_RECONNECT_DELAY_SECONDS = 30;

    private final String nodeId;
    private final String baseUrl;
    private volatile WebSocket webSocket;
    private final ScheduledExecutorService scheduler;
    private volatile boolean stopped = false;
    private int reconnectDelay = INITIAL_RECONNECT_DELAY_SECONDS;

    public RemoteWebSocketClient(String nodeId, String baseUrl) {
        this.nodeId = nodeId;
        this.baseUrl = baseUrl;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ws-remote-" + nodeId);
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        stopped = false;
        connect();
    }

    /**
     * 阻塞直到首次连接成功或超时，确保初始化阶段不会丢失早期日志。
     */
    public void startAndWait(int timeoutSeconds) {
        stopped = false;
        java.util.concurrent.CompletableFuture<Void> cf = new java.util.concurrent.CompletableFuture<>();
        try {
            URI httpUri = URI.create(baseUrl);
            String wsScheme = "https".equalsIgnoreCase(httpUri.getScheme()) ? "wss" : "ws";
            String host = httpUri.getHost();
            if (host == null || host.isBlank()) {
                logger.warn("远程节点 {} URL 无法解析 host: {}", nodeId, baseUrl);
                return;
            }
            int port = httpUri.getPort();
            String wsUriStr = port > 0
                    ? wsScheme + "://" + host + ":" + port + "/ws"
                    : wsScheme + "://" + host + "/ws";
            URI wsUri = URI.create(wsUriStr);

            HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(timeoutSeconds));
            if ("wss".equals(wsScheme)) {
                clientBuilder.sslContext(createTrustAllSSLContext());
            }
            HttpClient client = clientBuilder.build();

            client.newWebSocketBuilder()
                    .buildAsync(wsUri, new WebSocketListener())
                    .thenAccept(ws -> {
                        webSocket = ws;
                        reconnectDelay = INITIAL_RECONNECT_DELAY_SECONDS;
                        ws.sendText("{\"type\":\"connect\"}", true);
                        logger.info("已连接到远程节点 WebSocket: {} ({})", nodeId, baseUrl);
                        cf.complete(null);
                    })
                    .exceptionally(e -> {
                        Throwable cause = e;
                        if (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
                            cause = cause.getCause();
                        }
                        logger.warn("连接远程节点 WebSocket 失败 {}: {} - {} (cause: {})", nodeId, wsUri, cause, cause.getClass().getName());
                        cf.completeExceptionally(cause);
                        return null;
                    });

            java.util.concurrent.CompletableFuture.allOf(cf).get(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            logger.warn("连接远程节点 WebSocket 超时 {}: {}s", nodeId, timeoutSeconds);
        } catch (IllegalArgumentException e) {
            logger.warn("远程节点 URL 格式错误 {}: {} - {}", nodeId, baseUrl, e.getMessage());
        } catch (Exception e) {
            logger.warn("连接远程节点 WebSocket 失败 {}: {} - {}", nodeId, baseUrl, e.getMessage());
        }
    }

    public void stop() {
        stopped = true;
        WebSocket ws = webSocket;
        webSocket = null;
        if (ws != null) {
            try {
                ws.sendClose(1000, "Shutdown");
            } catch (Exception ignore) {
            }
        }
        scheduler.shutdownNow();
    }

    public boolean isConnected() {
        WebSocket ws = webSocket;
        return ws != null && !stopped;
    }

    private void connect() {
        if (stopped) return;

        try {
            URI httpUri = URI.create(baseUrl);
            String wsScheme = "https".equalsIgnoreCase(httpUri.getScheme()) ? "wss" : "ws";
            String host = httpUri.getHost();
            if (host == null || host.isBlank()) {
                logger.warn("远程节点 {} URL 无法解析 host: {}", nodeId, baseUrl);
                scheduleReconnect();
                return;
            }
            int port = httpUri.getPort();
            String wsUriStr = port > 0
                    ? wsScheme + "://" + host + ":" + port + "/ws"
                    : wsScheme + "://" + host + "/ws";
            URI wsUri = URI.create(wsUriStr);
            logger.info("正在连接远程节点 WebSocket {}: {} -> {}", nodeId, baseUrl, wsUri);

            HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2));
            if ("wss".equals(wsScheme)) {
                clientBuilder.sslContext(createTrustAllSSLContext());
            }
            HttpClient client = clientBuilder.build();

            client.newWebSocketBuilder()
                    .buildAsync(wsUri, new WebSocketListener())
                    .thenAccept(ws -> {
                        if (stopped) {
                            try { ws.sendClose(1000, "Stopped"); } catch (Exception ignored) {}
                            return;
                        }
                        webSocket = ws;
                        reconnectDelay = INITIAL_RECONNECT_DELAY_SECONDS;
                        ws.sendText("{\"type\":\"connect\"}", true);
                        logger.info("已连接到远程节点 WebSocket: {} ({})", nodeId, baseUrl);
                    })
                    .exceptionally(e -> {
                        Throwable cause = e;
                        if (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
                            cause = cause.getCause();
                        }
                        logger.warn("连接远程节点 WebSocket 失败 {}: {} - {} (cause: {})", nodeId, wsUri, cause, cause.getClass().getName());
                        scheduleReconnect();
                        return null;
                    });

        } catch (IllegalArgumentException e) {
            logger.warn("远程节点 URL 格式错误 {}: {} - {}", nodeId, baseUrl, e.getMessage());
        } catch (Exception e) {
            logger.warn("连接远程节点 WebSocket 失败 {}: {} - {} (cause: {})", nodeId, baseUrl, e, e.getClass().getName());
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (stopped) return;
        webSocket = null;
        logger.info("{} 秒后重连远程节点 {}...", reconnectDelay, nodeId);
        try {
            scheduler.schedule(() -> {
                if (!stopped) connect();
            }, reconnectDelay, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            if (!stopped) {
                logger.warn("远程节点 {} 重连任务被拒绝", nodeId);
            }
            return;
        }
        reconnectDelay = Math.min(reconnectDelay * 2, MAX_RECONNECT_DELAY_SECONDS);
    }

    private static SSLContext createTrustAllSSLContext() throws Exception {
        TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }

                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }
                }
        };
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, trustAll, new SecureRandom());
        return sc;
    }

    private class WebSocketListener implements WebSocket.Listener {

        private final StringBuilder messageBuilder = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            messageBuilder.append(data);
            if (last) {
                String message = messageBuilder.toString();
                messageBuilder.setLength(0);
                relayMessage(message);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            logger.info("远程节点 {} WebSocket 关闭: {} {}", nodeId, statusCode, reason);
            webSocket = null;
            if (!stopped) scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            logger.warn("远程节点 {} WebSocket 错误: {}", nodeId, error.getMessage());
            webSocket = null;
            if (!stopped) scheduleReconnect();
        }
    }

    private void relayMessage(String message) {
        if (message == null || message.isBlank()) return;
        try {
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            if (!json.has("type")) return;

            String type = json.get("type").getAsString();
            if ("heartbeat".equals(type) || "connect_ack".equals(type) || "welcome".equals(type)) {
                return;
            }

            json.addProperty("nodeId", nodeId);

            if ("console".equals(type)) {
                if (json.has("line") && json.get("line").isJsonPrimitive()) {
                    String raw = json.get("line").getAsString();
                    json.addProperty("line64", java.util.Base64.getEncoder().encodeToString(
                            raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    json.remove("line");
                }
            }

            WebSocketManager.getInstance().broadcast(JsonUtil.toJson(json));
        } catch (Exception e) {
            logger.warn("转发远程消息失败 {}: {}", nodeId, e.getMessage());
        }
    }
}
