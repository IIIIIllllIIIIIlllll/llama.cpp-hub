package org.mark.llamacpp.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hub 节点数据模型
 */
public class LlamaHubNode {
    String nodeId;           // 唯一标识，如 "server-gpu-a"
    String name;             // 显示名称，如 "GPU 服务器 A"
    String baseUrl;          // 远程节点地址，如 "http://192.168.1.100:8080"
    String apiKey;           // 远程节点的 API Key（可选）
    List<String> tags;       // 标签，如 ["A100", "production"]
    NodeStatus status;       // ONLINE / OFFLINE / PENDING
    long lastHeartbeat;      // 最后心跳时间戳
    long createdAt;          // 创建时间
    boolean enabled;         // 是否启用
    Map<String, Object> metadata; // 缓存的元信息（GPU、模型数等）

    public enum NodeStatus {
        ONLINE,    // 健康检查通过
        OFFLINE,   // 健康检查失败
        PENDING    // 刚添加，尚未检查
    }

    public LlamaHubNode() {
        this.tags = new ArrayList<>();
        this.metadata = new HashMap<>();
        this.status = NodeStatus.PENDING;
        this.enabled = true;
        this.createdAt = System.currentTimeMillis();
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags != null ? tags : new ArrayList<>();
    }

    public NodeStatus getStatus() {
        return status;
    }

    public void setStatus(NodeStatus status) {
        this.status = status;
    }

    public long getLastHeartbeat() {
        return lastHeartbeat;
    }

    public void setLastHeartbeat(long lastHeartbeat) {
        this.lastHeartbeat = lastHeartbeat;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata != null ? metadata : new HashMap<>();
    }
}
