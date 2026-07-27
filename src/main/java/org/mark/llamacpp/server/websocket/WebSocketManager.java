package org.mark.llamacpp.server.websocket;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.tools.JsonUtil;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * WebSocket连接管理器
 */
public class WebSocketManager {
    
    // 单例实例
    private static volatile WebSocketManager instance;
    
    // 存储所有活跃的WebSocket连接
    private final ConcurrentMap<String, ChannelHandlerContext> connections = new ConcurrentHashMap<>();
    
    // 存储连接的确认状态
    private final ConcurrentMap<String, Boolean> connectionStatus = new ConcurrentHashMap<>();
    
    // 连接计数器
    private final java.util.concurrent.atomic.AtomicInteger connectionCounter = new java.util.concurrent.atomic.AtomicInteger(0);
    
    // 定时任务执行器，用于发送心跳和定期消息
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    
    private WebSocketManager() {
        // 启动定时任务，每30秒发送一次心跳
        scheduler.scheduleAtFixedRate(this::sendHeartbeat, 30, 30, TimeUnit.SECONDS);
        
        // 启动定时任务，每60秒发送一次系统状态
        scheduler.scheduleAtFixedRate(this::sendSystemStatus, 60, 60, TimeUnit.SECONDS);
    }
    
    /**
     * 获取单例实例
     */
    public static WebSocketManager getInstance() {
        if (instance == null) {
            synchronized (WebSocketManager.class) {
                if (instance == null) {
                    instance = new WebSocketManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * 添加新的WebSocket连接
     */
    public String addConnection(ChannelHandlerContext ctx) {
        int id = connectionCounter.incrementAndGet();
        String connectionId = "conn-" + id;
        connections.put(connectionId, ctx);
        connectionStatus.put(connectionId, false);
        return connectionId;
    }
    
    /**
     * 移除WebSocket连接
     */
    public void removeConnection(String connectionId) {
        connections.remove(connectionId);
        connectionStatus.remove(connectionId);
    }
    
    /**
     * 向指定连接发送消息
     */
    public void sendMessage(String connectionId, String message) {
        ChannelHandlerContext ctx = connections.get(connectionId);
        if (ctx != null && ctx.channel().isActive()) {
            ctx.writeAndFlush(new TextWebSocketFrame(message));
        } else {
            connections.remove(connectionId);
        }
    }
    
    /**
     * 向所有连接广播消息
     */
    public void broadcast(String message) {
        //logger.info("向所有WebSocket客户端广播消息: {}", message);
        
        // 使用迭代器安全地遍历并发集合
        connections.entrySet().removeIf(entry -> {
            //String connectionId = entry.getKey();
            ChannelHandlerContext ctx = entry.getValue();
            
            if (ctx.channel().isActive()) {
                ctx.writeAndFlush(new TextWebSocketFrame(message));
                return false;
            } else {
                return true;
            }
        });
        // 兜底清理：connections 中已不存在的 connectionStatus 条目（如握手后未发 connect 即断开的客户端）
        connectionStatus.keySet().removeIf(id -> !connections.containsKey(id));
    }
    
    /**
     * 获取当前连接数
     */
    public int getConnectionCount() {
        return connections.size();
    }
    
    /**
     * 确认WebSocket连接
     */
    public void confirmConnection(String connectionId) {
        if (connections.containsKey(connectionId)) {
            connectionStatus.put(connectionId, true);
        }
    }
    
    /**
     * 检查连接是否已确认
     */
    public boolean isConnectionConfirmed(String connectionId) {
        return connectionStatus.getOrDefault(connectionId, false);
    }
    
    /**
     * 获取已确认的连接数
     */
    public int getConfirmedConnectionCount() {
        return (int) connectionStatus.values().stream().mapToInt(status -> status ? 1 : 0).sum();
    }
    
    /**
     * 发送心跳消息
     */
    private void sendHeartbeat() {
        if (getConnectionCount() > 0) {
            long timestamp = System.currentTimeMillis();
            broadcast("{\"type\":\"heartbeat\",\"timestamp\":" + timestamp + "}");
        }
    }
    
    /**
     * 发送系统状态
     */
    private void sendSystemStatus() {
        if (getConnectionCount() > 0) {
            try {
                LlamaServerManager serverManager = LlamaServerManager.getInstance();
                int loadedModelsCount = serverManager.getLoadedProcesses().size();
                
                String statusMessage = String.format(
                    "{\"type\":\"systemStatus\",\"timestamp\":%d,\"loadedModels\":%d,\"connections\":%d,\"confirmedConnections\":%d}",
                    System.currentTimeMillis(),
                    loadedModelsCount,
                    getConnectionCount(),
                    getConfirmedConnectionCount()
                );
                
                broadcast(statusMessage);
            } catch (Exception e) {
            }
        }
    }
    
    /**
     * 发送模型加载事件
     */
    public void sendModelLoadEvent(String modelId, boolean success, String message) {
        sendModelLoadEvent(modelId, null, success, message, null);
    }

    public void sendModelLoadEvent(String modelId, boolean success, String message, Integer port) {
        sendModelLoadEvent(modelId, null, success, message, port);
    }

    public void sendModelLoadEvent(String modelId, String sourceModelId, boolean success, String message, Integer port) {
        sendModelLoadEvent(modelId, sourceModelId, success, message, port, null);
    }

    public void sendModelLoadEvent(String modelId, String sourceModelId, boolean success, String message, Integer port, Integer slotNum) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "modelLoad");
        event.addProperty("modelId", modelId != null ? modelId : "");
        if (sourceModelId != null) {
            event.addProperty("sourceModelId", sourceModelId);
        }
        event.addProperty("success", success);
        event.addProperty("message", message != null ? message : "");
        if (port != null) {
            event.addProperty("port", port);
        }
        if (slotNum != null) {
            event.addProperty("slotNum", slotNum);
        }
        event.addProperty("timestamp", System.currentTimeMillis());
        broadcast(JsonUtil.toJson(event));
    }

    public void sendModelLoadStartEvent(String modelId, Integer port, String message) {
        sendModelLoadStartEvent(modelId, null, port, message);
    }

    public void sendModelLoadStartEvent(String modelId, String sourceModelId, Integer port, String message) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "modelLoadStart");
        event.addProperty("modelId", modelId != null ? modelId : "");
        if (sourceModelId != null) {
            event.addProperty("sourceModelId", sourceModelId);
        }
        if (port != null) {
            event.addProperty("port", port);
        }
        event.addProperty("message", message != null ? message : "");
        event.addProperty("timestamp", System.currentTimeMillis());
        broadcast(JsonUtil.toJson(event));
    }
    
    /**
     * 发送模型停止事件
     */
    public void sendModelStopEvent(String modelId, boolean success, String message) {
        sendModelStopEvent(modelId, null, success, message);
    }

    public void sendModelStopEvent(String modelId, String sourceModelId, boolean success, String message) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "modelStop");
        event.addProperty("modelId", modelId != null ? modelId : "");
        if (sourceModelId != null) {
            event.addProperty("sourceModelId", sourceModelId);
        }
        event.addProperty("success", success);
        event.addProperty("message", message != null ? message : "");
        event.addProperty("timestamp", System.currentTimeMillis());
        broadcast(JsonUtil.toJson(event));
    }

    public void sendModelSlotsEvent(String modelId, JsonArray slots) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "model_slots");
        event.addProperty("modelId", modelId == null ? "" : modelId);
        event.add("slots", slots == null ? new JsonArray() : slots);
        event.addProperty("timestamp", System.currentTimeMillis());
        broadcast(JsonUtil.toJson(event));
    }
    
    public void sendConsoleLineEvent(String modelId, String line) {
        byte[] bytes = line == null ? new byte[0] : line.getBytes(StandardCharsets.UTF_8);
        String b64 = Base64.getEncoder().encodeToString(bytes);
        JsonObject eventMessage = new JsonObject();
        eventMessage.addProperty("type", "console");
        eventMessage.addProperty("modelId", modelId != null ? modelId : "");
        eventMessage.addProperty("line64", b64);
        eventMessage.addProperty("timestamp", System.currentTimeMillis());
        broadcast(JsonUtil.toJson(eventMessage));
    }
    
    /**
     * 发送下载状态更新事件
     */
    public void sendDownloadStatusEvent(String taskId, String state, long downloadedBytes, long totalBytes,
                                      int partsCompleted, int partsTotal, String fileName, String errorMessage) {
        sendDownloadStatusEvent(taskId, state, downloadedBytes, totalBytes, partsCompleted, partsTotal, fileName, errorMessage, null, null);
    }

    public void sendDownloadStatusEvent(String taskId, String state, long downloadedBytes, long totalBytes,
                                      int partsCompleted, int partsTotal, String fileName, String errorMessage,
                                      String url, String targetPath) {
        String eventMessage = String.format(
            "{\"type\":\"download_update\",\"taskId\":\"%s\",\"state\":\"%s\",\"downloadedBytes\":%d,\"totalBytes\":%d,\"partsCompleted\":%d,\"partsTotal\":%d,\"fileName\":\"%s\",\"errorMessage\":\"%s\",\"url\":\"%s\",\"targetPath\":\"%s\",\"timestamp\":%d}",
            escapeJson(taskId),
            escapeJson(state),
            downloadedBytes,
            totalBytes,
            partsCompleted,
            partsTotal,
            escapeJson(fileName),
            escapeJson(errorMessage),
            escapeJson(url),
            escapeJson(targetPath),
            System.currentTimeMillis()
        );
        
        broadcast(eventMessage);
    }
    
    /**
     * 发送下载进度更新事件（仅针对正在下载的任务）
     */
    public void sendDownloadProgressEvent(String taskId, long downloadedBytes, long totalBytes,
                                        int partsCompleted, int partsTotal, double progressRatio) {
        sendDownloadProgressEvent(taskId, downloadedBytes, totalBytes, partsCompleted, partsTotal, progressRatio, null, null, null);
    }

    public void sendDownloadProgressEvent(String taskId, long downloadedBytes, long totalBytes,
                                        int partsCompleted, int partsTotal, double progressRatio,
                                        String fileName, String url, String targetPath) {
        String eventMessage = String.format(
            "{\"type\":\"download_progress\",\"taskId\":\"%s\",\"downloadedBytes\":%d,\"totalBytes\":%d,\"partsCompleted\":%d,\"partsTotal\":%d,\"progressRatio\":%f,\"fileName\":\"%s\",\"url\":\"%s\",\"targetPath\":\"%s\",\"timestamp\":%d}",
            escapeJson(taskId),
            downloadedBytes,
            totalBytes,
            partsCompleted,
            partsTotal,
            progressRatio,
            escapeJson(fileName),
            escapeJson(url),
            escapeJson(targetPath),
            System.currentTimeMillis()
        );
        
        broadcast(eventMessage);
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
            case '\\':
                sb.append("\\\\");
                break;
            case '"':
                sb.append("\\\"");
                break;
            case '\b':
                sb.append("\\b");
                break;
            case '\f':
                sb.append("\\f");
                break;
            case '\n':
                sb.append("\\n");
                break;
            case '\r':
                sb.append("\\r");
                break;
            case '\t':
                sb.append("\\t");
                break;
            default:
                if (c < 0x20) {
                    sb.append(String.format("\\u%04x", (int) c));
                } else {
                    sb.append(c);
                }
                break;
            }
        }
        return sb.toString();
    }
    
    /**
     * 发送应用更新进度事件
     */
    public void sendAppUpdateEvent(String status, long downloadedBytes,
            long totalBytes, double progressRatio, String version, String errorMessage) {
        String eventMessage = String.format(
            "{\"type\":\"app_update\",\"status\":\"%s\",\"downloadedBytes\":%d,\"totalBytes\":%d,\"progressRatio\":%s,\"version\":\"%s\",\"errorMessage\":\"%s\",\"timestamp\":%d}",
            status != null ? status.replace("\"", "\\\"") : "",
            downloadedBytes,
            totalBytes,
            progressRatio < 0 ? "-1" : String.format("%.4f", progressRatio),
            version != null ? version.replace("\"", "\\\"") : "",
            errorMessage != null ? errorMessage.replace("\"", "\\\"") : "",
            System.currentTimeMillis()
        );
        broadcast(eventMessage);
    }

    /**
     * 关闭管理器，释放资源
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // 关闭所有连接
        connections.values().forEach(ctx -> {
            if (ctx.channel().isActive()) {
                ctx.close();
            }
        });
        connections.clear();
        connectionStatus.clear();
    }
}
