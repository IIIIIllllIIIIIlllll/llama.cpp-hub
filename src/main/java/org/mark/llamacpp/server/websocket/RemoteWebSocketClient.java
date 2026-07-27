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
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class RemoteWebSocketClient {

    private static final Logger logger = LoggerFactory.getLogger(RemoteWebSocketClient.class);
    private static final int INITIAL_RECONNECT_DELAY_SECONDS = 1;
    private static final int MAX_RECONNECT_DELAY_SECONDS = 30;
    private static final int HEARTBEAT_INTERVAL_SECONDS = 30;
    private static final int HEARTBEAT_TIMEOUT_SECONDS = 90;
    private static final int CONNECTING_TIMEOUT_SECONDS = 30;

    private final String nodeId;
    private final String baseUrl;
    private final String apiKey;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private volatile long connectingSince = 0;

    private volatile HttpClient httpClient;
    private volatile WebSocket webSocket;
    private volatile boolean stopped = false;
    private volatile int reconnectDelay = INITIAL_RECONNECT_DELAY_SECONDS;
    private volatile long lastReceivedTime = 0;
    private volatile ScheduledFuture<?> heartbeatTask;
    private volatile ScheduledFuture<?> watchdogTask;
    private volatile ScheduledFuture<?> reconnectTask;

    public RemoteWebSocketClient(String nodeId, String baseUrl, String apiKey) {
        this.nodeId = nodeId;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ws-remote-" + nodeId);
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        stopped = false;
        reconnectDelay = INITIAL_RECONNECT_DELAY_SECONDS;
        connect();
    }

    /**
     * 阻塞直到首次连接成功或超时，确保初始化阶段不会丢失早期日志。
     * 即使首次握手未在超时内完成，后台重连任务仍会继续尝试。
     */
    public void startAndWait(int timeoutSeconds) {
        stopped = false;
        reconnectDelay = INITIAL_RECONNECT_DELAY_SECONDS;
        try {
            connect();
            long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
            while (System.currentTimeMillis() < deadline) {
                if (isConnected()) {
                    logger.info("远程节点 WebSocket 初始化完成: {} ({})", nodeId, baseUrl);
                    return;
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            logger.warn("连接远程节点 WebSocket 超时 {}: {}s", nodeId, timeoutSeconds);
        } catch (Exception e) {
            logger.warn("启动远程节点 WebSocket 失败 {}: {} - {}", nodeId, baseUrl, e.getMessage());
        }
    }

    public void stop() {
        stopped = true;
        stopHeartbeat();
        ScheduledFuture<?> pending = reconnectTask;
        if (pending != null && !pending.isDone()) {
            pending.cancel(false);
        }
        WebSocket ws = webSocket;
        webSocket = null;
        if (ws != null) {
            try {
                ws.sendClose(1000, "Shutdown").exceptionally(e -> null);
            } catch (Exception ignore) {
            }
        }
        scheduler.shutdownNow();
    }

    public boolean isConnected() {
        WebSocket ws = webSocket;
        if (ws == null || stopped) return false;
        try {
            return !ws.isOutputClosed();
        } catch (Exception e) {
            return false;
        }
    }

    private void connect() {
        if (stopped) return;
        if (webSocket != null) {
            logger.debug("远程节点 {} 已经连接，跳过本次连接", nodeId);
            return;
        }
        if (connecting.get()) {
            if (System.currentTimeMillis() - connectingSince < CONNECTING_TIMEOUT_SECONDS * 1000L) {
                logger.debug("远程节点 {} 已有连接任务在进行中，跳过", nodeId);
                return;
            }
            logger.warn("远程节点 {} 连接任务挂起超过 {} 秒，强制重置", nodeId, CONNECTING_TIMEOUT_SECONDS);
            connecting.set(false);
        }
        if (!connecting.compareAndSet(false, true)) {
            logger.debug("远程节点 {} 连接任务刚被抢占，跳过", nodeId);
            return;
        }
        connectingSince = System.currentTimeMillis();

        URI wsUri;
        try {
            URI httpUri = URI.create(baseUrl);
            String wsScheme = "https".equalsIgnoreCase(httpUri.getScheme()) ? "wss" : "ws";
            String host = httpUri.getHost();
            if (host == null || host.isBlank()) {
                logger.warn("远程节点 {} URL 无法解析 host: {}", nodeId, baseUrl);
                connecting.set(false);
                return;
            }
            int port = httpUri.getPort();
            String wsUriStr = port > 0
                    ? wsScheme + "://" + host + ":" + port + "/ws"
                    : wsScheme + "://" + host + "/ws";
            wsUri = URI.create(wsUriStr);
            logger.info("正在连接远程节点 WebSocket {}: {} -> {}", nodeId, baseUrl, wsUri);
        } catch (IllegalArgumentException e) {
            logger.warn("远程节点 URL 格式错误 {}: {} - {}", nodeId, baseUrl, e.getMessage());
            connecting.set(false);
            return;
        }

        try {
            HttpClient client = getHttpClient();
            if (client == null) {
                connecting.set(false);
                scheduleReconnect();
                return;
            }

            java.net.http.WebSocket.Builder wsBuilder = client.newWebSocketBuilder();
            if (apiKey != null && !apiKey.isBlank()) {
                wsBuilder.header("Authorization", "Bearer " + apiKey);
            }
            wsBuilder.buildAsync(wsUri, new WebSocketListener())
                    .whenComplete((ws, throwable) -> {
                        if (throwable != null) {
                            Throwable cause = throwable;
                            if (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
                                cause = cause.getCause();
                            }
                            logger.warn("连接远程节点 WebSocket 失败 {}: {} - {} (cause: {})", nodeId, wsUri, cause, cause.getClass().getName());
                            scheduleReconnect();
                        } else if (ws != null) {
                            if (stopped) {
                                try { ws.sendClose(1000, "Stopped"); } catch (Exception ignored) {}
                            } else {
                                webSocket = ws;
                                reconnectDelay = INITIAL_RECONNECT_DELAY_SECONDS;
                                lastReceivedTime = System.currentTimeMillis();
                                startHeartbeat();
                                ws.sendText("{\"type\":\"connect\"}", true)
                                        .exceptionally(ex -> {
                                            logger.warn("发送 connect 握手消息失败 {}: {}", nodeId, ex.getMessage());
                                            return null;
                                        });
                                logger.info("已连接到远程节点 WebSocket: {} ({})", nodeId, baseUrl);
                            }
                        }
                        connecting.set(false);
                    });
        } catch (Exception e) {
            logger.warn("连接远程节点 WebSocket 失败 {}: {} - {} (cause: {})", nodeId, baseUrl, e, e.getClass().getName());
            connecting.set(false);
            scheduleReconnect();
        }
    }

    private HttpClient getHttpClient() {
        HttpClient client = httpClient;
        if (client == null) {
            client = createHttpClient();
            httpClient = client;
        }
        return client;
    }

    private HttpClient createHttpClient() {
        try {
            URI httpUri = URI.create(baseUrl);
            String wsScheme = "https".equalsIgnoreCase(httpUri.getScheme()) ? "wss" : "ws";
            HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2));
            if ("wss".equals(wsScheme)) {
                clientBuilder.sslContext(createTrustAllSSLContext());
            }
            return clientBuilder.build();
        } catch (Exception e) {
            logger.error("为远程节点 {} 创建 HttpClient 失败: {}", nodeId, baseUrl, e);
            return null;
        }
    }

    private void scheduleReconnect() {
        if (stopped) return;
        stopHeartbeat();
        if (reconnectTask != null && !reconnectTask.isDone()) {
            logger.debug("远程节点 {} 已有重连任务，跳过", nodeId);
            return;
        }
        logger.info("{} 秒后重连远程节点 {}...", reconnectDelay, nodeId);
        try {
            reconnectTask = scheduler.schedule(() -> {
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

    private void startHeartbeat() {
        stopHeartbeat();
        lastReceivedTime = System.currentTimeMillis();
        heartbeatTask = scheduler.scheduleAtFixedRate(this::sendPing, HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
        watchdogTask = scheduler.scheduleAtFixedRate(this::checkHeartbeatTimeout, HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void stopHeartbeat() {
        ScheduledFuture<?> ht = heartbeatTask;
        heartbeatTask = null;
        if (ht != null) {
            ht.cancel(false);
        }
        ScheduledFuture<?> wt = watchdogTask;
        watchdogTask = null;
        if (wt != null) {
            wt.cancel(false);
        }
    }

    private void sendPing() {
        WebSocket ws = webSocket;
        if (ws == null || stopped) return;
        try {
            ws.sendPing(ByteBuffer.allocate(0))
                    .whenComplete((v, t) -> {
                        if (t != null) {
                            WebSocket current = webSocket;
                            if (current == ws) {
                                logger.warn("发送 Ping 失败 {}: {}", nodeId, t.getMessage());
                                webSocket = null;
                                stopHeartbeat();
                                scheduleReconnect();
                            }
                        }
                    });
        } catch (Exception e) {
            WebSocket current = webSocket;
            if (current == ws) {
                logger.warn("发送 Ping 异常 {}: {}", nodeId, e.getMessage());
                webSocket = null;
                stopHeartbeat();
                scheduleReconnect();
            }
        }
    }

    private void checkHeartbeatTimeout() {
        WebSocket ws = webSocket;
        if (ws == null || stopped) return;
        long elapsed = System.currentTimeMillis() - lastReceivedTime;
        if (elapsed > HEARTBEAT_TIMEOUT_SECONDS * 1000L) {
            WebSocket current = webSocket;
            if (current == ws) {
                logger.warn("远程节点 {} 心跳超时，{} ms 未收到任何帧，强制断开重连", nodeId, elapsed);
                webSocket = null;
                stopHeartbeat();
                try {
                    ws.abort();
                } catch (Exception ignored) {
                }
                scheduleReconnect();
            }
        }
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
        public void onOpen(WebSocket ws) {
            lastReceivedTime = System.currentTimeMillis();
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            lastReceivedTime = System.currentTimeMillis();
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
        public CompletionStage<?> onPing(WebSocket ws, ByteBuffer message) {
            lastReceivedTime = System.currentTimeMillis();
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket ws, ByteBuffer message) {
            lastReceivedTime = System.currentTimeMillis();
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            WebSocket current = webSocket;
            if (current == ws) {
                logger.info("远程节点 {} WebSocket 关闭: {} {}", nodeId, statusCode, reason);
                webSocket = null;
                stopHeartbeat();
                if (!stopped) scheduleReconnect();
            }
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            WebSocket current = webSocket;
            if (current == ws) {
                logger.warn("远程节点 {} WebSocket 错误: {}", nodeId, error.getMessage());
                webSocket = null;
                stopHeartbeat();
                if (!stopped) scheduleReconnect();
            }
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
