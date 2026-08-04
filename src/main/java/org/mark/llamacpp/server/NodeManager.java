package org.mark.llamacpp.server;

import com.google.gson.JsonObject;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.websocket.RemoteWebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 节点管理单例：节点 CRUD、配置持久化、健康检查调度、元信息缓存
 */
public class NodeManager {

    private static final Logger logger = LoggerFactory.getLogger(NodeManager.class);

    private static final NodeManager INSTANCE = new NodeManager();

    private final HttpClient httpClient;
    private final ConcurrentHashMap<String, LlamaHubNode> nodes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> nodeLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RemoteWebSocketClient> wsClients = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "node-health-check");
        t.setDaemon(true);
        return t;
    });

    public static NodeManager getInstance() {
        return INSTANCE;
    }

    private NodeManager() {
        try {
            TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAll, new SecureRandom());
            SSLParameters sslParameters = new SSLParameters();
            sslParameters.setEndpointIdentificationAlgorithm(null);
            this.httpClient = HttpClient.newBuilder()
                .sslContext(sslContext)
                .sslParameters(sslParameters)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create HttpClient", e);
        }
    }

    /**
     * 配置 HttpsURLConnection 信任所有证书并跳过主机名验证
     */
    public static void trustAllCerts(javax.net.ssl.HttpsURLConnection connection) throws Exception {
        TrustManager[] trustAll = new TrustManager[]{
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            }
        };
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustAll, new SecureRandom());
        connection.setSSLSocketFactory(sslContext.getSocketFactory());
        connection.setHostnameVerifier((hostname, session) -> true);
    }

    /**
     * 初始化：从配置文件加载节点，启动健康检查定时任务，连接远程节点 WebSocket
     */
    public void initialize() {
        List<LlamaHubNode> loaded = ConfigManager.getInstance().loadNodesConfig();
        for (LlamaHubNode node : loaded) {
            if (node.nodeId != null) {
                nodes.put(node.nodeId, node);
            }
        }
        logger.info("NodeManager 初始化完成，加载 {} 个节点 (本节点角色: {})", nodes.size(), LlamaServer.isMasterNode() ? "master" : "slave");
        if (LlamaServer.isMasterNode()) {
            for (LlamaHubNode node : loaded) {
                if (node.isEnabled() && node.nodeId != null && node.baseUrl != null) {
                    startAndWaitWebSocketClient(node.nodeId, node.baseUrl);
                }
            }
            startHealthCheck();
        } else {
            logger.info("本节点为 slave 模式，跳过远程节点连接和健康检查");
        }
    }

    /**
     * 关闭：停止定时任务，断开所有远程 WebSocket 连接
     */
    public void shutdown() {
        stopAllWebSocketClients();
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("NodeManager 已关闭");
    }

    // ==================== CRUD ====================

    /**
     * 添加节点
     */
    public boolean addNode(LlamaHubNode node) {
        if (node.nodeId == null || node.nodeId.isBlank()) {
            return false;
        }
        if (nodes.containsKey(node.nodeId)) {
            return false;
        }
        nodes.put(node.nodeId, node);
        saveNodesConfig();
        if (LlamaServer.isMasterNode() && node.isEnabled() && node.baseUrl != null) {
            startWebSocketClient(node.nodeId, node.baseUrl);
        }
        logger.info("添加节点: {} ({})", node.nodeId, node.name);
        return true;
    }

    /**
     * 移除节点
     */
    public boolean removeNode(String nodeId) {
        LlamaHubNode removed = nodes.remove(nodeId);
        if (removed != null) {
            stopWebSocketClient(nodeId);
            saveNodesConfig();
            logger.info("移除节点: {}", nodeId);
            return true;
        }
        return false;
    }

    /**
     * 更新节点
     */
    public boolean updateNode(String nodeId, LlamaHubNode update) {
        LlamaHubNode existing = nodes.get(nodeId);
        if (existing == null) {
            return false;
        }
        if (update.name != null) existing.name = update.name;
        if (update.baseUrl != null) existing.baseUrl = update.baseUrl;
        if (update.apiKey != null) existing.apiKey = update.apiKey;
        if (update.tags != null) existing.tags = update.tags;
        existing.enabled = update.enabled;
        saveNodesConfig();

        stopWebSocketClient(nodeId);
        if (LlamaServer.isMasterNode() && existing.isEnabled() && existing.baseUrl != null) {
            startWebSocketClient(nodeId, existing.baseUrl);
        }
        logger.info("更新节点: {}", nodeId);
        return true;
    }

    /**
     * 查询节点
     */
    public LlamaHubNode getNode(String nodeId) {
        return nodes.get(nodeId);
    }

    /**
     * 列出所有节点
     */
    public List<LlamaHubNode> listNodes() {
        return new ArrayList<>(nodes.values());
    }

    /**
     * 获取启用的节点列表
     */
    public List<LlamaHubNode> listEnabledNodes() {
        if (!LlamaServer.isMasterNode()) {
            return new ArrayList<>();
        }
        List<LlamaHubNode> result = new ArrayList<>();
        for (LlamaHubNode node : nodes.values()) {
            if (node.isEnabled()) {
                result.add(node);
            }
        }
        return result;
    }

    // ==================== 持久化 ====================

    private void saveNodesConfig() {
        ConfigManager.getInstance().saveNodesConfig(new ArrayList<>(nodes.values()));
    }

    // ==================== 远程 WebSocket 客户端管理 ====================

    private void startWebSocketClient(String nodeId, String baseUrl) {
        if (nodeId == null || baseUrl == null) return;
        stopWebSocketClient(nodeId);
        RemoteWebSocketClient client = new RemoteWebSocketClient(nodeId, baseUrl);
        wsClients.put(nodeId, client);
        client.start();
        logger.info("已启动远程节点 WebSocket 客户端: {} ({})", nodeId, baseUrl);
    }

    private void startAndWaitWebSocketClient(String nodeId, String baseUrl) {
        if (nodeId == null || baseUrl == null) return;
        stopWebSocketClient(nodeId);
        RemoteWebSocketClient client = new RemoteWebSocketClient(nodeId, baseUrl);
        wsClients.put(nodeId, client);
        client.startAndWait(2);
        logger.info("远程节点 WebSocket 初始化完成: {} ({})", nodeId, baseUrl);
    }

    private void stopWebSocketClient(String nodeId) {
        RemoteWebSocketClient existing = wsClients.remove(nodeId);
        if (existing != null) {
            existing.stop();
            logger.info("已停止远程节点 WebSocket 客户端: {}", nodeId);
        }
    }

    void stopAllWebSocketClients() {
        for (String nodeId : wsClients.keySet()) {
            stopWebSocketClient(nodeId);
        }
    }

    public boolean isWebSocketConnected(String nodeId) {
        RemoteWebSocketClient client = wsClients.get(nodeId);
        return client != null && client.isConnected();
    }

    // ==================== 远程 API 调用 ====================

    /**
     * HTTP 调用结果
     */
    public static class HttpResult {
        final int statusCode;
        final String body;

        public HttpResult(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        public boolean isSuccess() {
            return statusCode >= 200 && statusCode < 300;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getBody() {
            return body;
        }
    }

    /**
     * 通用远程 API 调用（默认 2 秒超时）
     */
    public HttpResult callRemoteApi(String nodeId, String method, String path, JsonObject body) {
        return callRemoteApi(nodeId, method, path, body, 1000 * 60, 1000 * 60);
    }

    /**
     * 通用远程 API 调用（自定义超时）
     */
    public HttpResult callRemoteApi(String nodeId, String method, String path, JsonObject body, int connectTimeout, int readTimeout) {
        LlamaHubNode node = getNode(nodeId);
        if (node == null || node.baseUrl == null) {
            return new HttpResult(404, "Node not found: " + nodeId);
        }
        try {
            String targetUrl = node.baseUrl + "/" + path.replaceFirst("^/", "");
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .timeout(Duration.ofMillis(readTimeout))
                .method(method, body != null && (method.equals("POST") || method.equals("PUT"))
                    ? HttpRequest.BodyPublishers.ofString(JsonUtil.toJson(body), StandardCharsets.UTF_8)
                    : HttpRequest.BodyPublishers.noBody());

            if (node.apiKey != null && !node.apiKey.isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + node.apiKey);
            }
            if (body != null && (method.equals("POST") || method.equals("PUT"))) {
                requestBuilder.header("Content-Type", "application/json; charset=UTF-8");
            }

            HttpResponse<String> response = this.httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new HttpResult(response.statusCode(), response.body() != null ? response.body() : "");
        } catch (IOException e) {
            logger.warn("远程API调用失败: nodeId={}, path={}, error={}", nodeId, path, e.getMessage());
            return new HttpResult(502, "Connection failed: " + e.getMessage());
        } catch (Exception e) {
            logger.warn("远程API调用失败: nodeId={}, path={}, error={}", nodeId, path, e.getMessage());
            return new HttpResult(502, "Connection failed: " + e.getMessage());
        }
    }

    /**
     * 流式远程 API 调用结果
     */
    public static class StreamResult {
        final int statusCode;
        final java.io.InputStream body;
        final HttpURLConnection connection;

        public StreamResult(int statusCode, java.io.InputStream body) {
            this(statusCode, body, null);
        }

        public StreamResult(int statusCode, java.io.InputStream body, HttpURLConnection connection) {
            this.statusCode = statusCode;
            this.body = body;
            this.connection = connection;
        }

        public boolean isSuccess() {
            return statusCode >= 200 && statusCode < 300;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public java.io.InputStream getBody() {
            return body;
        }

        public HttpURLConnection getConnection() {
            return connection;
        }

        public void abort() {
            if (this.connection != null) {
                this.connection.disconnect();
            }
            if (this.body != null) {
                try {
                    this.body.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    @FunctionalInterface
    public interface RequestBodyWriter {
        void writeTo(OutputStream output) throws IOException;
    }

    /**
     * 流式远程 API 调用，返回 InputStream 用于逐行读取 SSE 响应
     * 使用 HttpURLConnection 以便在客户端断开时能真正中止远程连接
     */
    public StreamResult callRemoteApiStreaming(String nodeId, String method, String path, JsonObject body, java.util.Map<String, String> headers, int readTimeout) {
        RequestBodyWriter bodyWriter = null;
        if (body != null && (method.equals("POST") || method.equals("PUT"))) {
            bodyWriter = output -> {
                output.write(JsonUtil.toJson(body).getBytes(StandardCharsets.UTF_8));
                output.flush();
            };
        }
        return callRemoteApiStreaming(nodeId, method, path, bodyWriter, headers, readTimeout);
    }

    public StreamResult callRemoteApiStreaming(String nodeId, String method, String path, RequestBodyWriter bodyWriter, java.util.Map<String, String> headers, int readTimeout) {
        LlamaHubNode node = getNode(nodeId);
        if (node == null || node.baseUrl == null) {
            return new StreamResult(404, new java.io.ByteArrayInputStream(("Node not found: " + nodeId).getBytes(StandardCharsets.UTF_8)));
        }
        HttpURLConnection connection = null;
        try {
            String targetUrl = node.baseUrl + "/" + path.replaceFirst("^/", "");
            URL url = URI.create(targetUrl).toURL();
            connection = (HttpURLConnection) url.openConnection();

            if (connection instanceof javax.net.ssl.HttpsURLConnection) {
                try {
                    trustAllCerts((javax.net.ssl.HttpsURLConnection) connection);
                } catch (Exception e) {
                    logger.warn("配置HTTPS证书信任失败: {}", e.getMessage());
                }
            }

            connection.setRequestMethod(method);
            connection.setConnectTimeout(readTimeout);
            connection.setReadTimeout(readTimeout);
            connection.setUseCaches(false);

            if (node.apiKey != null && !node.apiKey.isBlank()) {
                connection.setRequestProperty("Authorization", "Bearer " + node.apiKey);
            }
            if (bodyWriter != null && (method.equals("POST") || method.equals("PUT"))) {
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                connection.setDoOutput(true);
                connection.setChunkedStreamingMode(8192);
            }
            if (headers != null) {
                for (java.util.Map.Entry<String, String> entry : headers.entrySet()) {
                    String key = entry.getKey();
                    if (!key.equalsIgnoreCase("Connection") &&
                        !key.equalsIgnoreCase("Content-Length") &&
                        !key.equalsIgnoreCase("Transfer-Encoding") &&
                        !key.equalsIgnoreCase("Authorization") &&
                        !key.equalsIgnoreCase("Content-Type") &&
                        !key.equalsIgnoreCase("X-Node-Id")) {
                        connection.setRequestProperty(key, entry.getValue());
                    }
                }
            }

            if (bodyWriter != null && (method.equals("POST") || method.equals("PUT"))) {
                try (OutputStream os = connection.getOutputStream()) {
                    bodyWriter.writeTo(os);
                }
            }

            int responseCode = connection.getResponseCode();
            InputStream responseStream;
            if (responseCode >= 200 && responseCode < 300) {
                responseStream = connection.getInputStream();
            } else {
                responseStream = connection.getErrorStream();
                if (responseStream == null) {
                    responseStream = new java.io.ByteArrayInputStream(new byte[0]);
                }
            }
            return new StreamResult(responseCode, responseStream, connection);
        } catch (IOException e) {
            logger.warn("流式API调用失败: nodeId={}, path={}, error={}", nodeId, path, e.getMessage());
            if (connection != null) {
                connection.disconnect();
            }
            return new StreamResult(502, new java.io.ByteArrayInputStream(("Connection failed: " + e.getMessage()).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            logger.warn("流式API调用失败: nodeId={}, path={}, error={}", nodeId, path, e.getMessage());
            if (connection != null) {
                connection.disconnect();
            }
            return new StreamResult(502, new java.io.ByteArrayInputStream(("Connection failed: " + e.getMessage()).getBytes(StandardCharsets.UTF_8)));
        }
    }

    /**
     * 调用远程 /api/models/list
     */
    public HttpResult fetchRemoteModels(String nodeId) {
        return callRemoteApi(nodeId, "GET", "api/models/list", null);
    }

    /**
     * 调用远程 /api/models/loaded
     */
    public HttpResult fetchRemoteLoadedModels(String nodeId) {
        return callRemoteApi(nodeId, "GET", "api/models/loaded", null);
    }

    /**
     * 调用远程 /api/sys/gpu/status
     */
    public HttpResult fetchRemoteGpuStatus(String nodeId) {
        return callRemoteApi(nodeId, "GET", "api/sys/gpu/status", null);
    }

    /**
     * 调用远程 /api/sys/version
     */
    public HttpResult fetchRemoteVersion(String nodeId) {
        return callRemoteApi(nodeId, "GET", "api/sys/version", null);
    }

    // ==================== 健康检查 ====================

    /**
     * 启动 30s 间隔的定时健康检查
     */
    private void startHealthCheck() {
        scheduler.scheduleAtFixedRate(this::healthCheckRound, 30, 30, TimeUnit.SECONDS);
        logger.info("健康检查定时任务已启动，间隔 30 秒");
    }

    /**
     * 对单个节点进行健康检查
     */
    public void healthCheck(String nodeId) {
        LlamaHubNode node = getNode(nodeId);
        if (node == null || !node.isEnabled()) return;

        Object lock = nodeLocks.computeIfAbsent(nodeId, k -> new Object());
        synchronized (lock) {
            try {
                HttpResult result = fetchRemoteVersion(nodeId);
                if (result.isSuccess()) {
                    LlamaHubNode.NodeStatus oldStatus = node.status;
                    node.status = LlamaHubNode.NodeStatus.ONLINE;
                    node.lastHeartbeat = System.currentTimeMillis();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = JsonUtil.fromJson(result.body, Map.class);
                    if (data != null && !data.isEmpty()) {
                        node.metadata = new ConcurrentHashMap<>(data);
                    }
                    if (oldStatus != LlamaHubNode.NodeStatus.ONLINE) {
                        onNodeStatusChanged(node, oldStatus);
                    }
                } else {
                    LlamaHubNode.NodeStatus oldStatus = node.status;
                    node.status = LlamaHubNode.NodeStatus.OFFLINE;
                    if (oldStatus != LlamaHubNode.NodeStatus.OFFLINE) {
                        onNodeStatusChanged(node, oldStatus);
                    }
                }
            } catch (Exception e) {
                LlamaHubNode.NodeStatus oldStatus = node.status;
                node.status = LlamaHubNode.NodeStatus.OFFLINE;
                if (oldStatus != LlamaHubNode.NodeStatus.OFFLINE) {
                    onNodeStatusChanged(node, oldStatus);
                }
            }
        }
    }

    /**
     * 对所有启用节点执行一轮健康检查
     */
    void healthCheckRound() {
        for (LlamaHubNode node : nodes.values()) {
            if (node.isEnabled()) {
                healthCheck(node.nodeId);
            }
        }
    }

    /**
     * 获取节点状态
     */
    public LlamaHubNode.NodeStatus getNodeStatus(String nodeId) {
        LlamaHubNode node = getNode(nodeId);
        return node != null ? node.status : LlamaHubNode.NodeStatus.OFFLINE;
    }

    /**
     * 节点状态变化回调
     */
    private void onNodeStatusChanged(LlamaHubNode node, LlamaHubNode.NodeStatus oldStatus) {
        logger.info("节点状态变化: {} {} -> {}", node.nodeId, oldStatus, node.status);
        // 广播状态变化，让前端（模型列表 / 设置面板）实时更新节点在线状态
        org.mark.llamacpp.server.websocket.WebSocketManager.getInstance()
                .sendNodeStatusEvent(node.nodeId, node.status.name());
        if (!LlamaServer.isMasterNode()) return;
        if (oldStatus == LlamaHubNode.NodeStatus.OFFLINE && node.status == LlamaHubNode.NodeStatus.ONLINE) {
            if (node.baseUrl != null) {
                startWebSocketClient(node.nodeId, node.baseUrl);
            }
        }
    }

	/**
	 * 将远程 API 调用结果直接写回 Netty 通道（透传 JSON 响应，带 CORS 头）。
	 * 替代多处重复的 DefaultFullHttpResponse + writeBytes + CLOSE 模式。
	 */
	public static void writeHttpResultToChannel(
			io.netty.channel.ChannelHandlerContext ctx,
			HttpResult result,
			String logTag) {
		if (result == null || !result.isSuccess()) {
			logger.warn("{} 远程调用失败: code={}", logTag != null ? logTag : "[代理]",
					result != null ? result.getStatusCode() : "null");
			String errorBody = (result != null && result.getBody() != null) ? result.getBody() : "{\"success\":false,\"error\":\"远程调用失败\"}";
			io.netty.handler.codec.http.HttpResponseStatus status = result != null
					? io.netty.handler.codec.http.HttpResponseStatus.valueOf(result.getStatusCode())
					: io.netty.handler.codec.http.HttpResponseStatus.BAD_GATEWAY;
			writeJsonToChannel(ctx, errorBody, status);
			return;
		}
		try {
			String body = result.getBody();
			if (body == null) body = "";
			byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
			io.netty.handler.codec.http.FullHttpResponse response =
				new io.netty.handler.codec.http.DefaultFullHttpResponse(
					io.netty.handler.codec.http.HttpVersion.HTTP_1_1,
					io.netty.handler.codec.http.HttpResponseStatus.OK);
			response.headers().set(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
			response.headers().set(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH, bytes.length);
			response.headers().set(io.netty.handler.codec.http.HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
			response.headers().set(io.netty.handler.codec.http.HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
			response.content().writeBytes(bytes);
			ctx.writeAndFlush(response).addListener(io.netty.channel.ChannelFutureListener.CLOSE);
		} catch (Exception e) {
			logger.warn("{} 写入响应失败: {}", logTag != null ? logTag : "[代理]", e.getMessage());
		}
	}

	private static void writeJsonToChannel(io.netty.channel.ChannelHandlerContext ctx, String json,
			io.netty.handler.codec.http.HttpResponseStatus status) {
		try {
			byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
			io.netty.handler.codec.http.FullHttpResponse response =
				new io.netty.handler.codec.http.DefaultFullHttpResponse(
					io.netty.handler.codec.http.HttpVersion.HTTP_1_1, status);
			response.headers().set(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
			response.headers().set(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH, bytes.length);
			response.headers().set(io.netty.handler.codec.http.HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
			response.headers().set(io.netty.handler.codec.http.HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
			response.content().writeBytes(bytes);
			ctx.writeAndFlush(response).addListener(io.netty.channel.ChannelFutureListener.CLOSE);
		} catch (Exception e) {
			logger.warn("写入响应失败: {}", e.getMessage());
		}
	}

    public static String readStream(java.io.InputStream stream) throws IOException {
        if (stream == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }
}
