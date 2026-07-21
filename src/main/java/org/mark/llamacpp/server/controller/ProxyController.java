package org.mark.llamacpp.server.controller;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.mark.llamacpp.crawler.NettyHttpUtils;
import org.mark.llamacpp.crawler.ProxyConfig;
import org.mark.llamacpp.crawler.UserAgentUtils;
import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.NodeManager;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.struct.ProxyConfigData;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.tools.ParamTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;

/**
 * HTTP 代理相关的后端。问题在于，上哪找一个HTTP 代理服务呢。
 */
public class ProxyController implements BaseController {

    private static final Logger logger = LoggerFactory.getLogger(ProxyController.class);

    private static final String I18N_METHOD_GET_ONLY = "common.method.get.only";
    private static final String I18N_METHOD_POST_ONLY = "common.method.post.only";
    private static final String I18N_BODY_EMPTY = "api.error.body.empty";
    private static final String I18N_BODY_PARSE = "api.error.body.parse";
    private static final String I18N_REMOTE_CALL_FAILED = "api.error.remote.call.failed";
    private static final String I18N_PROXY_GET_FAILED = "api.error.proxy.get.failed";
    private static final String I18N_PROXY_SAVE_FAILED = "api.error.proxy.save.failed";
    private static final String I18N_PROXY_HOST_EMPTY = "api.error.proxy.host.empty";
    private static final String I18N_PROXY_PORT_INVALID = "api.error.proxy.port.invalid";
    private static final String I18N_PROXY_TEST_FAILED = "api.error.proxy.test.failed";

    @Override
    public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
        if (uri.equals("/api/proxy/get")) {
            handleProxyGet(ctx, request);
            return true;
        }
        if (uri.equals("/api/proxy/save")) {
            handleProxySave(ctx, request);
            return true;
        }
        if (uri.equals("/api/proxy/test")) {
            handleProxyTest(ctx, request);
            return true;
        }
        return false;
    }

    private void proxyPostRemote(ChannelHandlerContext ctx, FullHttpRequest request, String nodeId, String path) {
        this.proxyPostRemote(ctx, request, nodeId, path, 0, 0);
    }

    private void proxyPostRemote(ChannelHandlerContext ctx, FullHttpRequest request, String nodeId, String path, int connectTimeout, int readTimeout) {
        try {
            JsonObject body = JsonUtil.fromJson(JsonUtil.readRequestBytes(request), JsonObject.class);
            if (body != null) {
                body.remove("nodeId");
                if (body.size() == 0) body = null;
            }
            NodeManager.HttpResult result;
            if (connectTimeout > 0 && readTimeout > 0) {
                result = NodeManager.getInstance().callRemoteApi(nodeId, "POST", path, body, connectTimeout, readTimeout);
            } else {
                result = NodeManager.getInstance().callRemoteApi(nodeId, "POST", path, body);
            }
            if (result.isSuccess()) {
                NodeManager.writeHttpResultToChannel(ctx, result, "[代理远程]");
            } else {
                LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_CALL_FAILED + ": code=" + result.getStatusCode()));
            }
        } catch (Exception e) {
            logger.warn("远程节点调用失败: nodeId={}, path={}, error={}", nodeId, path, e.getMessage());
            LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_CALL_FAILED + ": " + e.getMessage()));
        }
    }

    private void proxyGetRemote(ChannelHandlerContext ctx, String nodeId, String path) {
        try {
            NodeManager.HttpResult result = NodeManager.getInstance().callRemoteApi(nodeId, "GET", path, null);
            if (result.isSuccess()) {
                NodeManager.writeHttpResultToChannel(ctx, result, "[代理远程]");
            } else {
                LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_CALL_FAILED + ": code=" + result.getStatusCode()));
            }
        } catch (Exception e) {
            logger.warn("远程节点调用失败: nodeId={}, path={}, error={}", nodeId, path, e.getMessage());
            LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_CALL_FAILED + ": " + e.getMessage()));
        }
    }

    /**
     * GET /api/proxy/get
     */
    private void handleProxyGet(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
        this.assertRequestMethod(request.method() != HttpMethod.GET, I18N_METHOD_GET_ONLY);
        try {
            String nodeId = ParamTool.getQueryParam(request.uri()).get("nodeId");
            if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
                this.proxyGetRemote(ctx, nodeId, "api/proxy/get");
                return;
            }
            Path configFile = LlamaServer.getProxyConfigPath();
            ProxyConfigData cfg = LlamaServer.readProxyConfig(configFile);
            if (cfg == null) {
                cfg = new ProxyConfigData();
            }
            Map<String, Object> data = new HashMap<>();
            data.put("enabled", cfg.isEnabled());
            data.put("host", cfg.getHost() != null ? cfg.getHost() : "");
            data.put("port", cfg.getPort());
            data.put("username", cfg.getUsername() != null ? cfg.getUsername() : "");
            LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
        } catch (Exception e) {
            logger.info("获取代理配置时发生错误", e);
            LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PROXY_GET_FAILED + ": " + e.getMessage()));
        }
    }

    /**
     * POST /api/proxy/save
     */
    private void handleProxySave(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
        this.assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);
        try {
            String nodeId = ParamTool.getQueryParam(request.uri()).get("nodeId");
            if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
                this.proxyPostRemote(ctx, request, nodeId, "api/proxy/save");
                return;
            }
            byte[] content = JsonUtil.readRequestBytes(request);
            if (content == null || JsonUtil.isBlank(content)) {
                LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_EMPTY));
                return;
            }
            ProxyConfigData reqData = JsonUtil.fromJson(content, ProxyConfigData.class);
            if (reqData == null) {
                LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_PARSE));
                return;
            }

            // Validate host if enabled
            if (reqData.isEnabled()) {
                String host = reqData.getHost();
                if (host == null || host.trim().isEmpty()) {
                    LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PROXY_HOST_EMPTY));
                    return;
                }
                int port = reqData.getPort();
                if (port <= 0 || port > 65535) {
                    LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PROXY_PORT_INVALID));
                    return;
                }
            }

            // If disabling, clear credentials
            if (!reqData.isEnabled()) {
                reqData.setHost("");
                reqData.setPort(0);
                reqData.setUsername("");
                reqData.setPassword("");
            }

            Path configFile = LlamaServer.getProxyConfigPath();
            LlamaServer.writeProxyConfig(configFile, reqData);

            // Update runtime config
            LlamaServer.setProxyConfig(reqData);

            Map<String, Object> data = new HashMap<>();
            data.put("message", reqData.isEnabled() ? "代理配置已保存" : "代理已禁用");
            data.put("enabled", reqData.isEnabled());
            LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
        } catch (Exception e) {
            logger.info("保存代理配置时发生错误", e);
            LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PROXY_SAVE_FAILED + ": " + e.getMessage()));
        }
    }

    /**
     * POST /api/proxy/test
     */
    private void handleProxyTest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
        this.assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);
        try {
            String nodeId = ParamTool.getQueryParam(request.uri()).get("nodeId");
            if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
                this.proxyPostRemote(ctx, request, nodeId, "api/proxy/test", 5000, 15000);
                return;
            }
            byte[] content = JsonUtil.readRequestBytes(request);
            ProxyConfigData reqData = null;
            if (content != null && !JsonUtil.isBlank(content)) {
                reqData = JsonUtil.fromJson(content, ProxyConfigData.class);
            }
            if (reqData == null || reqData.getHost() == null || reqData.getHost().trim().isEmpty()) {
                LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PROXY_HOST_EMPTY));
                return;
            }
            int port = reqData.getPort();
            if (port <= 0 || port > 65535) {
                LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PROXY_PORT_INVALID));
                return;
            }

            ProxyConfig proxyConfig;
            if (reqData.getUsername() != null && !reqData.getUsername().isEmpty()) {
                proxyConfig = ProxyConfig.http(reqData.getHost().trim(), port, reqData.getUsername(), reqData.getPassword() != null ? reqData.getPassword() : "");
            } else {
                proxyConfig = ProxyConfig.http(reqData.getHost().trim(), port);
            }

            try {
                NettyHttpUtils.Response resp = NettyHttpUtils.request("https://api.github.com")
                        .header("User-Agent", UserAgentUtils.random())
                        .readTimeout(10)
                        .connectTimeout(10)
                        .proxy(proxyConfig)
                        .execute();

                if (resp.isSuccess()) {
                    Map<String, Object> data = new HashMap<>();
                    data.put("success", true);
                    data.put("message", "代理连接成功");
                    data.put("response", resp.bodyAsString());
                    LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
                } else {
                    Map<String, Object> data = new HashMap<>();
                    data.put("success", false);
                    data.put("message", "代理返回异常状态码: " + resp.statusCode());
                    LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
                }
            } catch (IOException e) {
                Map<String, Object> data = new HashMap<>();
                data.put("success", false);
                data.put("message", "代理连接失败: " + e.getMessage());
                LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
            }
        } catch (Exception e) {
            logger.info("测试代理连接时发生错误", e);
            LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PROXY_TEST_FAILED + ": " + e.getMessage()));
        }
    }
}
