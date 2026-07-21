package org.mark.llamacpp.server.controller;

import com.google.gson.JsonObject;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;

import org.mark.llamacpp.server.LlamaHubNode;
import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.NodeManager;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NodeController implements BaseController {

	private static final Logger logger = LoggerFactory.getLogger(NodeController.class);

	private static final String I18N_NODE_LIST_FAILED = "api.error.node.list.failed";
	private static final String I18N_NODE_MASTER_ONLY = "api.error.node.master.only";
	private static final String I18N_NODE_BODY_REQUIRED = "api.error.node.body.required";
	private static final String I18N_NODE_ID_EMPTY = "api.error.node.id.empty";
	private static final String I18N_NODE_ID_EXISTS = "api.error.node.id.exists";
	private static final String I18N_NODE_BASEURL_EMPTY = "api.error.node.baseurl.empty";
	private static final String I18N_NODE_BASEURL_INVALID = "api.error.node.baseurl.invalid";
	private static final String I18N_NODE_ADD_FAILED = "api.error.node.add.failed";
	private static final String I18N_NODE_NOTFOUND = "api.error.node.notfound";
	private static final String I18N_NODE_REMOVE_FAILED = "api.error.node.remove.failed";
	private static final String I18N_NODE_UPDATE_FAILED = "api.error.node.update.failed";
	private static final String I18N_NODE_STATUS_FAILED = "api.error.node.status.failed";
	private static final String I18N_NODE_INFO_FAILED = "api.error.node.info.failed";

	private static final String I18N_METHOD_GET_ONLY = "common.method.get.only";
	private static final String I18N_METHOD_POST_ONLY = "common.method.post.only";

    @Override
    public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
        if (uri.equals("/api/node/list")) {
            handleNodeListRequest(ctx, request);
            return true;
        }
        if (uri.equals("/api/node/add")) {
            handleNodeAddRequest(ctx, request);
            return true;
        }
        if (uri.equals("/api/node/remove")) {
            handleNodeRemoveRequest(ctx, request);
            return true;
        }
        if (uri.equals("/api/node/update")) {
            handleNodeUpdateRequest(ctx, request);
            return true;
        }
        if (uri.equals("/api/node/test")) {
            handleNodeTestRequest(ctx, request);
            return true;
        }
        if (uri.equals("/api/node/status")) {
            handleNodeStatusRequest(ctx, request);
            return true;
        }
        if (uri.equals("/api/node/info")) {
            handleNodeInfoRequest(ctx, request);
            return true;
        }
        return false;
    }

    private void handleNodeListRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
        this.assertRequestMethod(request.method() != HttpMethod.GET, I18N_METHOD_GET_ONLY);
        try {
            List<LlamaHubNode> nodes = NodeManager.getInstance().listNodes();
            LlamaServer.sendJsonResponse(ctx, ApiResponse.success(nodes));
        } catch (Exception e) {
            logger.error("获取节点列表失败", e);
		LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_NODE_LIST_FAILED + ": " + e.getMessage()));
        }
    }

    private void handleNodeAddRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
        this.assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);
        if (!LlamaServer.isMasterNode()) {
		LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_NODE_MASTER_ONLY));
            return;
        }
        try {
            JsonObject obj = JsonUtil.fromJson(JsonUtil.readRequestBytes(request), JsonObject.class);
            if (obj == null) {
		LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_NODE_BODY_REQUIRED));
                return;
            }

            String nodeId = JsonUtil.getJsonString(obj, "nodeId");
            if (nodeId == null || nodeId.isBlank()) {
		LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_NODE_ID_EMPTY));
                return;
            }

            if (NodeManager.getInstance().getNode(nodeId) != null) {
		LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_NODE_ID_EXISTS + ": " + nodeId));
                return;
            }

            String baseUrl = JsonUtil.getJsonString(obj, "baseUrl");
            if (baseUrl == null || baseUrl.isBlank()) {
		LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_NODE_BASEURL_EMPTY));
                return;
            }

            if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
		LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_NODE_BASEURL_INVALID));
                return;
            }

            LlamaHubNode node = new LlamaHubNode();
            node.setNodeId(nodeId);
            node.setName(JsonUtil.getJsonString(obj, "name"));
            if (node.getName() == null) node.setName(nodeId);
            node.setBaseUrl(baseUrl);
            node.setApiKey(JsonUtil.getJsonString(obj, "apiKey"));

            if (obj.has("tags")) {
                node.setTags(JsonUtil.getJsonStringList(obj.get("tags")));
            }

            if (obj.has("enabled")) {
                node.setEnabled(obj.get("enabled").getAsBoolean());
            }

            boolean added = NodeManager.getInstance().addNode(node);
            if (added) {
                NodeManager.getInstance().healthCheck(nodeId);
                LlamaServer.sendJsonResponse(ctx, ApiResponse.success(node));
            } else {
                LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_NODE_ADD_FAILED));
            }
        } catch (Exception e) {
            logger.error("添加节点失败", e);
            LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_NODE_ADD_FAILED + ": " + e.getMessage()));
        }
    }

    private void handleNodeRemoveRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
        this.assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);
        if (!LlamaServer.isMasterNode()) {
		LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_NODE_MASTER_ONLY));
            return;
        }
        try {
            JsonObject obj = JsonUtil.fromJson(JsonUtil.readRequestBytes(request), JsonObject.class);
            if (obj == null) {
		LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_NODE_BODY_REQUIRED));
                return;
            }

            String nodeId = JsonUtil.getJsonString(obj, "nodeId");
            if (nodeId == null || nodeId.isBlank()) {
		LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_NODE_ID_EMPTY));
                return;
            }

            boolean removed = NodeManager.getInstance().removeNode(nodeId);
            if (removed) {
                LlamaServer.sendJsonResponse(ctx, ApiResponse.success());
            } else {
		LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_NODE_NOTFOUND + ": " + nodeId));
            }
        } catch (Exception e) {
            logger.error("移除节点失败", e);
		LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_NODE_REMOVE_FAILED + ": " + e.getMessage()));
        }
    }

    private void handleNodeUpdateRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
        this.assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);
        if (!LlamaServer.isMasterNode()) {
		LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_NODE_MASTER_ONLY));
            return;
        }
        try {
            JsonObject obj = JsonUtil.fromJson(JsonUtil.readRequestBytes(request), JsonObject.class);
            if (obj == null) {
		LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_NODE_BODY_REQUIRED));
                return;
            }

            String nodeId = JsonUtil.getJsonString(obj, "nodeId");
            if (nodeId == null || nodeId.isBlank()) {
		LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_NODE_ID_EMPTY));
                return;
            }

            if (NodeManager.getInstance().getNode(nodeId) == null) {
		LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_NODE_NOTFOUND + ": " + nodeId));
                return;
            }

            LlamaHubNode update = new LlamaHubNode();
            if (obj.has("name")) update.setName(JsonUtil.getJsonString(obj, "name"));
            if (obj.has("baseUrl")) update.setBaseUrl(JsonUtil.getJsonString(obj, "baseUrl"));
            if (obj.has("apiKey")) update.setApiKey(JsonUtil.getJsonString(obj, "apiKey"));
            if (obj.has("tags")) update.setTags(JsonUtil.getJsonStringList(obj.get("tags")));
            if (obj.has("enabled")) update.setEnabled(obj.get("enabled").getAsBoolean());

            boolean updated = NodeManager.getInstance().updateNode(nodeId, update);
            if (updated) {
                LlamaServer.sendJsonResponse(ctx, ApiResponse.success(NodeManager.getInstance().getNode(nodeId)));
            } else {
                LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_NODE_UPDATE_FAILED));
            }
        } catch (Exception e) {
            logger.error("更新节点失败", e);
            LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_NODE_UPDATE_FAILED + ": " + e.getMessage()));
        }
    }

    private void handleNodeTestRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
        this.assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);
        if (!LlamaServer.isMasterNode()) {
		LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_NODE_MASTER_ONLY));
            return;
        }
        try {
            JsonObject obj = JsonUtil.fromJson(JsonUtil.readRequestBytes(request), JsonObject.class);
            if (obj == null) {
		LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_NODE_BODY_REQUIRED));
                return;
            }

            String nodeId = JsonUtil.getJsonString(obj, "nodeId");
            if (nodeId == null || nodeId.isBlank()) {
		LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_NODE_ID_EMPTY));
                return;
            }

            if (NodeManager.getInstance().getNode(nodeId) == null) {
		LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_NODE_NOTFOUND + ": " + nodeId));
                return;
            }

            long startTime = System.currentTimeMillis();
            NodeManager.getInstance().healthCheck(nodeId);
            long latency = System.currentTimeMillis() - startTime;
            LlamaHubNode node = NodeManager.getInstance().getNode(nodeId);
            boolean connected = node != null && node.getStatus() == LlamaHubNode.NodeStatus.ONLINE;
            String version = node != null && node.getMetadata() != null && node.getMetadata().get("version") != null
                    ? String.valueOf(node.getMetadata().get("version")) : "";

            Map<String, Object> response = new HashMap<>();
            response.put("connected", connected);
            response.put("version", version);
            response.put("latency", latency);
            response.put("statusCode", connected ? 200 : 502);
            LlamaServer.sendJsonResponse(ctx, ApiResponse.success(response));
        } catch (Exception e) {
            logger.error("测试节点连通性失败", e);
            Map<String, Object> response = new HashMap<>();
            response.put("connected", false);
            response.put("version", "");
            response.put("latency", 0);
            response.put("error", e.getMessage());
            LlamaServer.sendJsonResponse(ctx, ApiResponse.success(response));
        }
    }

    private void handleNodeStatusRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
        this.assertRequestMethod(request.method() != HttpMethod.GET, I18N_METHOD_GET_ONLY);
        try {
            Map<String, Object> statusMap = new HashMap<>();
            for (LlamaHubNode node : NodeManager.getInstance().listNodes()) {
                Map<String, Object> info = new HashMap<>();
                info.put("nodeId", node.getNodeId());
                info.put("name", node.getName());
                info.put("status", node.getStatus().name());
                info.put("lastHeartbeat", node.getLastHeartbeat());
                info.put("enabled", node.isEnabled());
                statusMap.put(node.getNodeId(), info);
            }
            LlamaServer.sendJsonResponse(ctx, ApiResponse.success(statusMap));
        } catch (Exception e) {
            logger.error("获取节点状态失败", e);
		LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_NODE_STATUS_FAILED + ": " + e.getMessage()));
        }
    }

    private void handleNodeInfoRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
        this.assertRequestMethod(request.method() != HttpMethod.GET, I18N_METHOD_GET_ONLY);
        try {
            Map<String, Object> result = new HashMap<>();
            Map<String, Object> selfNode = new HashMap<>();
            selfNode.put("nodeId", "local");
            selfNode.put("name", "本机");
            selfNode.put("status", "ONLINE");
            result.put("selfNode", selfNode);

            List<LlamaHubNode> nodes = NodeManager.getInstance().listNodes();
            int onlineCount = 0;
            for (LlamaHubNode node : nodes) {
                if (node.getStatus() == LlamaHubNode.NodeStatus.ONLINE) {
                    onlineCount++;
                }
            }
            result.put("isMaster", LlamaServer.isMasterNode());
            result.put("connectedNodes", nodes.size());
            result.put("onlineNodes", onlineCount);

            LlamaServer.sendJsonResponse(ctx, ApiResponse.success(result));
        } catch (Exception e) {
            logger.error("获取节点信息失败", e);
		LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_NODE_INFO_FAILED + ": " + e.getMessage()));
        }
    }
}
