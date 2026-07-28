package org.mark.llamacpp.server.controller;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.mark.llamacpp.gguf.GGUFMetaData;
import org.mark.llamacpp.gguf.GGUFModel;
import org.mark.llamacpp.server.LlamaCppProcess;
import org.mark.llamacpp.server.LlamaHubNode;
import org.mark.llamacpp.server.ConfigManager;
import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.NodeManager;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.service.BenchmarkService;
import org.mark.llamacpp.server.service.ModelRequestTracker;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.tools.ChatTemplateFileTool;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.tools.ParamTool;
import org.mark.llamacpp.server.util.TailCharBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;

/**
 * 	关于模型的控制器。
 */
public class ModelActionController implements BaseController {
	
	
	private static final Logger logger = LoggerFactory.getLogger(ModelActionController.class);

	private static final String I18N_METHOD_POST_ONLY = "common.method.post.only";
	private static final String I18N_METHOD_GET_ONLY = "common.method.get.only";
	private static final String I18N_BODY_EMPTY = "api.error.body.empty";
	private static final String I18N_BODY_PARSE = "api.error.body.parse";
	private static final String I18N_PARAM_MODELID_REQUIRED = "api.error.param.modelId.required";
	//private static final String I18N_PARAM_MODELID_MISSING = "api.error.param.modelId.missing";
	private static final String I18N_PARAM_CMD_MISSING = "api.error.param.cmd.missing";
	private static final String I18N_PARAM_CLONE_ID_MISSING = "api.error.param.cloneId.missing";
	private static final String I18N_PARAM_SOURCE_MODEL_ID_MISSING = "api.error.param.sourceModelId.missing";
	private static final String I18N_PARAM_FILE_NAME_MISSING = "api.error.param.fileName.missing";
	private static final String I18N_PARAM_LINE_NUMBER_MISSING = "api.error.param.lineNumber.missing";
	private static final String I18N_PARAM_LLAMA_BIN_PATH_MISSING = "api.error.param.llamaBinPath.missing";
	private static final String I18N_REMOTE_CALL_FAILED = "api.error.remote.call.failed";
	private static final String I18N_REMOTE_NODE_CALL_FAILED = "api.error.remote.node.call.failed";
	private static final String I18N_REMOTE_NODE_INVALID = "api.error.remote.node.invalid";
	private static final String I18N_MODEL_NOT_FOUND = "api.error.model.notfound";
	private static final String I18N_MODEL_NOT_LOADED = "api.error.model.not.loaded";
	private static final String I18N_MODEL_STOP_FAILED_OR_NOT_LOADED = "api.error.model.stop.failed.or.not.loaded";
	private static final String I18N_MODEL_REFRESH_LIST_FAILED = "api.error.model.refresh.list.failed";
	private static final String I18N_MODEL_LIST_FAILED = "api.error.model.list.failed";
	private static final String I18N_MODEL_LOADED_LIST_FAILED = "api.error.model.loaded.list.failed";
	private static final String I18N_MODEL_STOP_FAILED = "api.error.model.stop.failed";
	private static final String I18N_MODEL_LOAD_FAILED = "api.error.model.load.failed";
	private static final String I18N_MODEL_CLONE_CREATE_FAILED = "api.error.model.clone.create.failed";
	private static final String I18N_MODEL_CLONE_ID_CONFLICT = "api.error.model.clone.id.conflict";
	private static final String I18N_MODEL_CLONE_CONFIG_EXISTS = "api.error.model.clone.config.exists";
	private static final String I18N_MODEL_CLONE_SOURCE_NOT_FOUND = "api.error.model.clone.source.notfound";
	private static final String I18N_MODEL_CLONE_SOURCE_IS_CLONE = "api.error.model.clone.source.is.clone";
	private static final String I18N_MODEL_CLONE_NO_LAUNCH_PARAMS = "api.error.model.clone.no.launch.params";
	private static final String I18N_MODEL_CLONE_NO_BIN_PATH = "api.error.model.clone.no.bin.path";
	private static final String I18N_MODEL_CLONE_SAVE_FAILED = "api.error.model.clone.save.failed";
	private static final String I18N_MODEL_CLONE_CONFIG_NOT_FOUND = "api.error.model.clone.config.notfound";
	private static final String I18N_MODEL_CLONE_SOURCE_MISMATCH = "api.error.model.clone.source.mismatch";
	//private static final String I18N_MODEL_BENCHMARK_CMD_MISSING = "api.error.model.benchmark.cmd.missing";
	//private static final String I18N_MODEL_BENCHMARK_NOT_FOUND = "api.error.model.benchmark.notfound";
	private static final String I18N_MODEL_BENCHMARK_METADATA_INCOMPLETE = "api.error.model.benchmark.metadata.incomplete";
	private static final String I18N_MODEL_BENCHMARK_EXE_NOT_FOUND = "api.error.model.benchmark.exe.not.found";
	private static final String I18N_MODEL_BENCHMARK_TIMEOUT = "api.error.model.benchmark.timeout";
	private static final String I18N_MODEL_BENCHMARK_FAILED = "api.error.model.benchmark.failed";
	private static final String I18N_MODEL_BENCHMARK_LIST_FAILED = "api.error.model.benchmark.list.failed";
	private static final String I18N_MODEL_BENCHMARK_READ_FAILED = "api.error.model.benchmark.read.failed";
	private static final String I18N_MODEL_BENCHMARK_V2_READ_FAILED = "api.error.model.benchmark.v2.read.failed";
	private static final String I18N_MODEL_BENCHMARK_DELETE_FAILED = "api.error.model.benchmark.delete.failed";
	private static final String I18N_MODEL_BENCHMARK_V2_DELETE_FAILED = "api.error.model.benchmark.v2.delete.failed";
	private static final String I18N_MODEL_METRICS_FAILED = "api.error.model.metrics.failed";
	private static final String I18N_MODEL_PROPS_FAILED = "api.error.model.props.failed";
	private static final String I18N_MODEL_LOADED_CMD_MISSING = "api.error.model.loaded.cmd.missing";
	private static final String I18N_MODEL_ALREADY_LOADED = "api.error.model.already.loaded";
	private static final String I18N_MODEL_LOADING = "api.error.model.loading";
	private static final String I18N_FILE_NOT_FOUND = "api.error.file.notfound";
	private static final String I18N_FILE_NAME_INVALID = "api.error.file.name.invalid";
	private static final String I18N_RECORD_NOT_FOUND = "api.error.record.notfound";
	private static final String I18N_MODEL_PORT_NOT_FOUND = "api.error.model.port.not.found";
	private static final String I18N_MODEL_STOP_SUCCESS = "api.error.model.stop.success";
	private static final String I18N_MODEL_NAME_UNKNOWN = "api.model.name.unknown";
	private static final String I18N_MODEL_NAME_UNNAMED = "api.model.name.unnamed";

	/**
	 * 	
	 */
	private BenchmarkService benchmarkService = new BenchmarkService();
	
	/**
	 * 	远程节点 HTTP 连接追踪，用于客户端中断时断开远程请求。
	 */
	private ConcurrentHashMap<ChannelHandlerContext, HttpURLConnection> remoteConnections = new ConcurrentHashMap<>();
	
	
	public ModelActionController() {
		
	}
	
	@Override
	public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 强制刷新模型列表API
		if (uri.equals("/api/models/refresh")) {
			this.handleRefreshModelListRequest(ctx, request);
			return true;
		}
		// 列出全部的模型
		if (uri.equals("/api/models/list")) {
			this.handleModelListRequest(ctx, request);
			return true;
		}
		// 查询已经被加载的模型
		if (uri.equals("/api/models/loaded")) {
			this.handleLoadedModelsRequest(ctx, request);
			return true;
		}
		// 创建克隆体模型配置
		if (uri.equals("/api/models/clone/create")) {
			this.handleCloneCreateRequest(ctx, request);
			return true;
		}
		// 加载指定的模型
		if (uri.equals("/api/models/load")) {
			this.handleLoadModelRequest(ctx, request);
			return true;
		}
		// 停止指定的运行中的模型
		if (uri.equals("/api/models/stop")) {
			this.handleStopModelRequest(ctx, request);
			return true;
		}
		// 执行benchmark
		if (uri.equals("/api/models/benchmark")) {
			this.handleModelBenchmark(ctx, request);
			return true;
		}
		// 获取指定模型的测试记录
		if (uri.equals("/api/models/benchmark/list")) {
			this.handleModelBenchmarkList(ctx, request);
			return true;
		}
		// 查询指定的测试记录
		if (uri.equals("/api/models/benchmark/get")) {
			this.handleModelBenchmarkGet(ctx, request);
			return true;
		}
		// 删除指定的测试记录
		if (uri.equals("/api/models/benchmark/delete")) {
			this.handleModelBenchmarkDelete(ctx, request);
			return true;
		}
		if (uri.equals("/api/v2/models/benchmark")) {
			this.handleModelBenchmarkV2(ctx, request);
			return true;
		}

		if (uri.equals("/api/v2/models/benchmark/get")) {
			this.handleModelBenchmarkV2Get(ctx, request);
			return true;
		}
		if (uri.equals("/api/v2/models/benchmark/delete")) {
			this.handleModelBenchmarkV2Delete(ctx, request);
			return true;
		}
		
		// 对应URL-GET：/metrics
		// 客户端传入modelId作为参数
		if (uri.equals("/api/models/metrics")) {
			this.handleModelMetrics(ctx, request);
			return true;
		}
		// 对应URL-GET：/props
		if (uri.equals("/api/models/props")) {
			this.handleModelProps(ctx, request);
			return true;
		}
		
		return false;
	}
	
	/**
	 * 处理强制刷新模型列表请求
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleRefreshModelListRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, I18N_METHOD_GET_ONLY);
		try {
			// 检查是否指定了远程节点
			String nodeId = ParamTool.getQueryParam(request.uri()).get("nodeId");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				// 刷新单个远程节点
				logger.info("[模型操作] 远程节点刷新模型: nodeId={}", nodeId);
				NodeManager.HttpResult result = NodeManager.getInstance().callRemoteApi(
						nodeId, "GET", "api/models/refresh", null, 5000, 15000);
				if (result.isSuccess()) {
					NodeManager.writeHttpResultToChannel(ctx, result, "[模型操作刷新远程]");
				} else {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_CALL_FAILED + ": code=" + result.getStatusCode()));
				}
				return;
			}

			// 刷新本地模型列表
			LlamaServerManager manager = LlamaServerManager.getInstance();
			manager.listModel(true);

			// 同步刷新所有已启用的远程节点
			List<LlamaHubNode> enabledNodes = NodeManager.getInstance().listEnabledNodes();
			List<Map<String, Object>> nodeResults = new ArrayList<>();
			for (LlamaHubNode node : enabledNodes) {
				Map<String, Object> nodeResult = new HashMap<>();
				nodeResult.put("nodeId", node.getNodeId());
				nodeResult.put("nodeName", node.getName());
				try {
					NodeManager.HttpResult result = NodeManager.getInstance().callRemoteApi(
						node.getNodeId(), "GET", "api/models/refresh", null, 5000, 15000);
					if (result.isSuccess()) {
						nodeResult.put("success", true);
						logger.info("[模型操作] 已刷新远程节点: nodeId={}", node.getNodeId());
					} else {
						nodeResult.put("success", false);
						nodeResult.put("error", "HTTP " + result.getStatusCode());
						logger.warn("[模型操作] 刷新远程节点失败: nodeId={}, code={}", node.getNodeId(), result.getStatusCode());
					}
				} catch (Exception e) {
					nodeResult.put("success", false);
					nodeResult.put("error", e.getMessage());
					logger.warn("[模型操作] 刷新远程节点失败: nodeId={}, error={}", node.getNodeId(), e.getMessage());
				}
				nodeResults.add(nodeResult);
			}

			Map<String, Object> response = new HashMap<>();
			response.put("success", true);
			response.put("refreshed", true);
			response.put("nodes", nodeResults);
			LlamaServer.sendJsonResponse(ctx, response);
		} catch (Exception e) {
			logger.info("强制刷新模型列表时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_REFRESH_LIST_FAILED + ": " + e.getMessage()));
		}
	}
	
	/**
	 * 处理模型列表请求
	 *
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleModelListRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, I18N_METHOD_GET_ONLY);

		try {
			String nodeId = ParamTool.getQueryParam(request.uri()).get("nodeId");

			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				List<Map<String, Object>> models = this.fetchRemoteModelList(nodeId);
				Map<String, Object> response = new HashMap<>();
				response.put("success", true);
				response.put("models", models);
				LlamaServer.sendJsonResponse(ctx, response);
				return;
			}

			List<Map<String, Object>> modelList = this.buildLocalModelList();

			List<LlamaHubNode> enabledNodes = NodeManager.getInstance().listEnabledNodes();
			for (LlamaHubNode node : enabledNodes) {
				List<Map<String, Object>> remoteModels = this.fetchRemoteModelList(node.getNodeId());
				modelList.addAll(remoteModels);
			}

			Map<String, Object> response = new HashMap<>();
			response.put("success", true);
			response.put("models", modelList);
			LlamaServer.sendJsonResponse(ctx, response);
		} catch (Exception e) {
			logger.info("获取模型列表时发生错误", e);
			Map<String, Object> errorResponse = new HashMap<>();
			errorResponse.put("success", false);
			errorResponse.put("error", I18N_MODEL_LIST_FAILED + ": " + e.getMessage());
			LlamaServer.sendJsonResponse(ctx, errorResponse);
		}
	}

	/**
	 * 构建本地模型列表
	 */
	private List<Map<String, Object>> buildLocalModelList() {
		LlamaServerManager manager = LlamaServerManager.getInstance();
		List<GGUFModel> models = manager.listModel();

		List<Map<String, Object>> modelList = new ArrayList<>();
		for (GGUFModel model : models) {
			Map<String, Object> modelInfo = new HashMap<>();

			GGUFMetaData primaryModel = model.getPrimaryModel();
			GGUFMetaData mmproj = model.getMmproj();

			String modelName = I18N_MODEL_NAME_UNKNOWN;
			String modelId = "unknown-model-" + System.currentTimeMillis();

			if (primaryModel != null) {
				modelName = model.getName();
				if (modelName == null || modelName.trim().isEmpty()) {
					modelName = I18N_MODEL_NAME_UNNAMED;
				}
				modelId = model.getModelId();
			}

			modelInfo.put("id", modelId);
			modelInfo.put("name", modelName);
			modelInfo.put("alias", model.getAlias());
			modelInfo.put("favourite", model.isFavourite());
			modelInfo.put("size", model.getSize());

			boolean isMultimodal = mmproj != null;
			boolean supportsVision = mmproj != null && mmproj.isSupportsVision();
			boolean supportsAudio = mmproj != null && mmproj.isSupportsAudio();
			modelInfo.put("isMultimodal", isMultimodal);
			modelInfo.put("supportsVision", supportsVision);
			modelInfo.put("supportsAudio", supportsAudio);

			if (manager.isLoading(modelId)) {
				modelInfo.put("isLoading", true);
			}

			String architecture = "common.unknown";
			String quantization = "";
			if (primaryModel != null) {
				String value = primaryModel.getStringValue("general.architecture");
				if (value != null && !value.trim().isEmpty()) {
					architecture = value;
				}
				String quantizationValue = primaryModel.getQuantizationType();
				if (quantizationValue != null) {
					quantization = quantizationValue;
				}
			}
			modelInfo.put("architecture", architecture);
			modelInfo.put("quantization", quantization);
			modelInfo.put("hasMtp", (primaryModel != null && primaryModel.getMtpInfo().hasMtp()) || model.hasDraftModel());
			modelInfo.put("nodeId", "local");
			modelInfo.put("nodeName", "page.model.filter.local");

			modelList.add(modelInfo);
		}

		// 追加源模型仍存在的克隆体到本地模型列表
		try {
			ConfigManager configManager = ConfigManager.getInstance();
			Map<String, String> aliasMap = configManager.loadAliasMap();
			Map<String, Boolean> favouriteMap = configManager.loadFavouriteMap();
			Map<String, Map<String, Object>> allLaunchConfigs = configManager.loadAllLaunchConfigs();
			for (Map.Entry<String, Map<String, Object>> launchEntry : allLaunchConfigs.entrySet()) {
				String cloneId = launchEntry.getKey();
				String sourceModelId = configManager.getSourceModelId(cloneId);
				if (sourceModelId == null) {
					continue;
				}
				// 源模型不存在时不显示
				GGUFModel sourceModel = manager.findModelById(sourceModelId);
				if (sourceModel == null) {
					continue;
				}
				// 避免与本地真实模型重复
				if (manager.findModelById(cloneId) != null) {
					continue;
				}

				String cloneAlias = aliasMap.getOrDefault(cloneId, "");
				boolean cloneFavourite = favouriteMap.getOrDefault(cloneId, false);

				Map<String, Object> modelInfo = new HashMap<>();
				modelInfo.put("id", cloneId);
				modelInfo.put("name", cloneAlias.isEmpty() ? cloneId : cloneAlias);
				modelInfo.put("alias", cloneAlias);
				modelInfo.put("favourite", cloneFavourite);
				modelInfo.put("size", sourceModel.getSize());
				modelInfo.put("isMultimodal", sourceModel.getMmproj() != null);
				modelInfo.put("supportsVision",
						sourceModel.getMmproj() != null && sourceModel.getMmproj().isSupportsVision());
				modelInfo.put("supportsAudio",
						sourceModel.getMmproj() != null && sourceModel.getMmproj().isSupportsAudio());
				if (manager.isLoading(cloneId)) {
					modelInfo.put("isLoading", true);
				}
				String architecture = "common.unknown";
				GGUFMetaData primaryModel = sourceModel.getPrimaryModel();
				if (primaryModel != null) {
					String value = primaryModel.getStringValue("general.architecture");
					if (value != null && !value.trim().isEmpty()) {
						architecture = value;
					}
				}
				modelInfo.put("architecture", architecture);
				modelInfo.put("quantization",
						primaryModel != null ? primaryModel.getQuantizationType() : "");
				modelInfo.put("hasMtp", (primaryModel != null && primaryModel.getMtpInfo().hasMtp()) || manager.hasDraftModelFromConfig(cloneId));
				modelInfo.put("nodeId", "local");
				modelInfo.put("nodeName", "page.model.filter.local");
				modelInfo.put("isClone", true);
				modelInfo.put("sourceModelId", sourceModelId);

				modelList.add(modelInfo);
			}
		} catch (Exception e) {
			logger.warn("追加克隆体模型到列表时出错", e);
		}

		return modelList;
	}

	/**
	 * 从远程节点获取模型列表
	 */
	private List<Map<String, Object>> fetchRemoteModelList(String nodeId) {
		List<Map<String, Object>> result = new ArrayList<>();
		NodeManager manager = NodeManager.getInstance();
		NodeManager.HttpResult httpResult = manager.fetchRemoteModels(nodeId);
		if (!httpResult.isSuccess()) {
			logger.warn("获取远程节点模型列表失败: nodeId={}, code={}", nodeId, httpResult.getStatusCode());
			return result;
		}
		LlamaHubNode node = manager.getNode(nodeId);
		String nodeName = node != null ? node.getName() : nodeId;

		try {
			JsonObject root = JsonUtil.fromJson(httpResult.getBody(), JsonObject.class);
			if (root == null || !root.has("models")) {
				return result;
			}
			com.google.gson.JsonArray modelsArray = root.getAsJsonArray("models");
			if (modelsArray == null) {
				return result;
			}
			for (com.google.gson.JsonElement elem : modelsArray) {
				if (!elem.isJsonObject()) continue;
				JsonObject modelObj = elem.getAsJsonObject();
				Map<String, Object> modelInfo = new HashMap<>();
				for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry : modelObj.entrySet()) {
					com.google.gson.JsonElement value = entry.getValue();
					if (value == null || value.isJsonNull()) {
						modelInfo.put(entry.getKey(), null);
					} else if (value.isJsonPrimitive()) {
						com.google.gson.JsonPrimitive prim = (com.google.gson.JsonPrimitive) value;
						if (prim.isBoolean()) {
							modelInfo.put(entry.getKey(), value.getAsBoolean());
						} else if (prim.isNumber()) {
							// 防止 Gson 把 LazilyParsedNumber 序列化成 {"value": 123}
							// 整数用 Long，小数用 Double
							String numStr = prim.getAsString();
							if (numStr.indexOf('.') >= 0 || numStr.indexOf('e') >= 0 || numStr.indexOf('E') >= 0) {
								modelInfo.put(entry.getKey(), value.getAsDouble());
							} else {
								modelInfo.put(entry.getKey(), value.getAsLong());
							}
						} else {
							modelInfo.put(entry.getKey(), value.getAsString());
						}
					} else {
						modelInfo.put(entry.getKey(), JsonUtil.jsonValueToString(value));
					}
				}
				modelInfo.put("nodeId", nodeId);
				modelInfo.put("nodeName", nodeName);
				result.add(modelInfo);
			}
		} catch (Exception e) {
			logger.warn("解析远程节点模型列表失败: nodeId={}, error={}", nodeId, e.getMessage());
		}
		return result;
	}
	
	/**
	 * 处理停止模型请求
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleStopModelRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);
		try {
			byte[] body = JsonUtil.readRequestBytes(request);
			if (body == null || JsonUtil.isBlank(body)) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_EMPTY));
				return;
			}

			JsonObject obj = JsonUtil.fromJson(body, JsonObject.class);
			if (obj == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_PARSE));
				return;
			}

			String modelId = JsonUtil.getJsonString(obj, "modelId");
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_MODELID_REQUIRED));
				return;
			}

			LlamaServerManager manager = LlamaServerManager.getInstance();
			String resolvedModelId = manager.resolveModelId(modelId);
			if (resolvedModelId != null) {
				logger.info("[模型操作] 停止模型别名解析: {} -> {}", modelId, resolvedModelId);
				modelId = resolvedModelId;
			}

			String nodeId = JsonUtil.getJsonString(obj, "nodeId");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				this.stopRemoteModel(ctx, nodeId, modelId);
				return;
			}

		if (manager.getLoadedProcesses().containsKey(modelId) || manager.isLoading(modelId)) {
			logger.info("[模型操作] 本地停止模型: modelId={}", modelId);
			String sourceModelId = manager.getSourceModelId(modelId);
			boolean success = manager.stopModel(modelId);
			if (success) {
				Map<String, Object> data = new HashMap<>();
				data.put("message", I18N_MODEL_STOP_SUCCESS);
				LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
				LlamaServer.sendModelStopEvent(modelId, sourceModelId, true, I18N_MODEL_STOP_SUCCESS);
			} else {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_STOP_FAILED_OR_NOT_LOADED));
				LlamaServer.sendModelStopEvent(modelId, sourceModelId, false, I18N_MODEL_STOP_FAILED_OR_NOT_LOADED);
			}
			} else {
				logger.info("[模型操作] 本地未找到模型，搜索远程节点: modelId={}", modelId);
				this.findAndStopOnRemoteNode(ctx, modelId);
			}
		} catch (Exception e) {
			logger.info("停止模型时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_STOP_FAILED + ": " + e.getMessage()));
		}
	}

	/**
	 * 在远程节点上查找并停止模型
	 */
	private void findAndStopOnRemoteNode(ChannelHandlerContext ctx, String modelId) {
		String nodeId = this.findNodeByLoadedModel(modelId);
		if (nodeId == null) {
			logger.warn("[模型操作] 远程节点也未找到已加载模型: modelId={}", modelId);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_NOT_LOADED + ": " + modelId));
			return;
		}
		logger.info("[模型操作] 找到模型所在远程节点: modelId={}, nodeId={}", modelId, nodeId);
		this.stopRemoteModel(ctx, nodeId, modelId);
	}

	/**
	 * 停止远程节点上的模型（不主动发 modelStop 事件，由 WS 中继传递）
	 */
	private void stopRemoteModel(ChannelHandlerContext ctx, String nodeId, String modelId) {
		NodeManager manager = NodeManager.getInstance();
		JsonObject body = new JsonObject();
		body.addProperty("modelId", modelId);
		NodeManager.HttpResult result = manager.callRemoteApi(nodeId, "POST", "api/models/stop", body);
		if (!result.isSuccess()) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_CALL_FAILED + ": " + result.getBody()));
			return;
		}
		String remoteError = this.extractRemoteError(result.getBody());
		if (remoteError != null) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(remoteError));
		} else {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success());
		}
	}

	/**
	 * 解析远程节点返回的 JSON，提取业务层错误信息。
	 * 仅当存在 success=false 时返回对应 error 字段；否则返回 null 视为成功。
	 */
	private String extractRemoteError(String responseBody) {
		if (responseBody == null || responseBody.isBlank()) {
			return null;
		}
		try {
			JsonObject root = JsonUtil.fromJson(responseBody, JsonObject.class);
			if (root == null || !root.has("success")) {
				return null;
			}
			com.google.gson.JsonElement successEl = root.get("success");
			if (successEl != null && successEl.isJsonPrimitive() && successEl.getAsJsonPrimitive().isBoolean() && successEl.getAsBoolean()) {
				return null;
			}
			String error = JsonUtil.getJsonString(root, "error");
			if (error == null || error.isEmpty()) {
				error = I18N_REMOTE_CALL_FAILED;
			}
			return error;
		} catch (Exception e) {
			logger.warn("[模型操作] 解析远程响应JSON失败: error={}", e.getMessage());
			return null;
		}
	}
	
	/**
	 * 处理已加载模型请求
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleLoadedModelsRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, I18N_METHOD_GET_ONLY);

		try {
			String nodeId = ParamTool.getQueryParam(request.uri()).get("nodeId");

			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				List<Map<String, Object>> remoteLoaded = this.fetchRemoteLoadedModels(nodeId);
				Map<String, Object> response = new HashMap<>();
				response.put("success", true);
				response.put("models", remoteLoaded);
				LlamaServer.sendJsonResponse(ctx, response);
				return;
			}

			List<Map<String, Object>> loadedModels = this.buildLocalLoadedModels();

			List<LlamaHubNode> enabledNodes = NodeManager.getInstance().listEnabledNodes();
			for (LlamaHubNode node : enabledNodes) {
				List<Map<String, Object>> remoteLoaded = this.fetchRemoteLoadedModels(node.getNodeId());
				loadedModels.addAll(remoteLoaded);
			}

			Map<String, Object> response = new HashMap<>();
			response.put("success", true);
			response.put("models", loadedModels);
			LlamaServer.sendJsonResponse(ctx, response);
		} catch (Exception e) {
			logger.info("获取已加载模型时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_LOADED_LIST_FAILED + ": " + e.getMessage()));
		}
	}

	/**
	 * 构建本地已加载模型列表
	 */
	private List<Map<String, Object>> buildLocalLoadedModels() {
		LlamaServerManager manager = LlamaServerManager.getInstance();
		Map<String, LlamaCppProcess> loadedProcesses = manager.getLoadedProcesses();
		List<GGUFModel> allModels = manager.listModel();

		List<Map<String, Object>> loadedModels = new ArrayList<>();

		for (Map.Entry<String, LlamaCppProcess> entry : loadedProcesses.entrySet()) {
			String modelId = entry.getKey();
			LlamaCppProcess process = entry.getValue();
			String sourceModelId = process.getSourceModelId();

			GGUFModel modelInfo = null;
			for (GGUFModel model : allModels) {
				if (model.getModelId().equals(modelId)) {
					modelInfo = model;
					break;
				}
			}
			// 克隆体不在磁盘上，用源模型信息富化 name/size/path
			if (modelInfo == null && sourceModelId != null) {
				for (GGUFModel model : allModels) {
					if (model.getModelId().equals(sourceModelId)) {
						modelInfo = model;
						break;
					}
				}
			}

			Map<String, Object> modelData = new HashMap<>();
			modelData.put("id", modelId);
			if (sourceModelId != null) {
				modelData.put("sourceModelId", sourceModelId);
			}
			modelData.put("name",
					modelInfo != null ? (modelInfo.getPrimaryModel() != null
							? modelInfo.getPrimaryModel().getStringValue("general.name")
							: I18N_MODEL_NAME_UNKNOWN) : I18N_MODEL_NAME_UNKNOWN);
			modelData.put("status", process.isRunning() ? "running" : "stopped");
			modelData.put("port", manager.getModelPort(modelId));
			modelData.put("pid", process.getPid());
			modelData.put("size", modelInfo != null ? modelInfo.getSize() : 0);
			modelData.put("path", modelInfo != null ? modelInfo.getPath() : "");
			modelData.put("nodeId", "local");
			modelData.put("nodeName", "page.model.filter.local");
			modelData.put("busy", ModelRequestTracker.getInstance().isModelBusy(modelId));
			modelData.put("slotNum", process.getSlotNum());

			loadedModels.add(modelData);
		}
		return loadedModels;
	}

	/**
	 * 从远程节点获取已加载模型列表
	 */
	private List<Map<String, Object>> fetchRemoteLoadedModels(String nodeId) {
		List<Map<String, Object>> result = new ArrayList<>();
		NodeManager manager = NodeManager.getInstance();
		NodeManager.HttpResult httpResult = manager.fetchRemoteLoadedModels(nodeId);
		if (!httpResult.isSuccess()) {
			logger.warn("获取远程节点已加载模型失败: nodeId={}, code={}", nodeId, httpResult.getStatusCode());
			return result;
		}
		LlamaHubNode node = manager.getNode(nodeId);
		String nodeName = node != null ? node.getName() : nodeId;

		try {
			JsonObject root = JsonUtil.fromJson(httpResult.getBody(), JsonObject.class);
			if (root == null || !root.has("models")) {
				return result;
			}
			com.google.gson.JsonArray modelsArray = root.getAsJsonArray("models");
			if (modelsArray == null) {
				return result;
			}
			for (com.google.gson.JsonElement elem : modelsArray) {
				if (!elem.isJsonObject()) continue;
				JsonObject modelObj = elem.getAsJsonObject();
				Map<String, Object> modelInfo = new HashMap<>();
				for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry : modelObj.entrySet()) {
					com.google.gson.JsonElement value = entry.getValue();
					if (value == null || value.isJsonNull()) {
						modelInfo.put(entry.getKey(), null);
					} else if (value.isJsonPrimitive()) {
						com.google.gson.JsonPrimitive prim = (com.google.gson.JsonPrimitive) value;
						if (prim.isBoolean()) {
							modelInfo.put(entry.getKey(), value.getAsBoolean());
						} else if (prim.isNumber()) {
							// 防止 Gson 把 LazilyParsedNumber 序列化成 {"value": 123}
							String numStr = prim.getAsString();
							if (numStr.indexOf('.') >= 0 || numStr.indexOf('e') >= 0 || numStr.indexOf('E') >= 0) {
								modelInfo.put(entry.getKey(), value.getAsDouble());
							} else {
								modelInfo.put(entry.getKey(), value.getAsLong());
							}
						} else {
							modelInfo.put(entry.getKey(), value.getAsString());
						}
					} else {
						modelInfo.put(entry.getKey(), JsonUtil.jsonValueToString(value));
					}
				}
				modelInfo.put("nodeId", nodeId);
				modelInfo.put("nodeName", nodeName);
				result.add(modelInfo);
			}
		} catch (Exception e) {
			logger.warn("解析远程节点已加载模型失败: nodeId={}, error={}", nodeId, e.getMessage());
		}
		return result;
	}
	
	/**
	 * 处理创建克隆体模型配置的请求
	 *
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleCloneCreateRequest(ChannelHandlerContext ctx, FullHttpRequest request)
			throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);
		try {
			byte[] body = JsonUtil.readRequestBytes(request);
			if (body == null || JsonUtil.isBlank(body)) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_EMPTY));
				return;
			}

			JsonObject obj = JsonUtil.fromJson(body, JsonObject.class);
			if (obj == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_PARSE));
				return;
			}

			String nodeId = JsonUtil.getJsonString(obj, "nodeId", "");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				logger.info("[模型操作] 远程代理创建克隆体: nodeId={}, cloneId={}", nodeId,
						JsonUtil.getJsonString(obj, "cloneId", ""));
				JsonObject remoteBody = obj.deepCopy();
				remoteBody.remove("nodeId");
				NodeManager.HttpResult result = this.callRemoteApiTracked(ctx, nodeId, "POST",
						"api/models/clone/create", remoteBody);
				this.writeRemoteResult(ctx, result);
				return;
			}

			String cloneId = JsonUtil.getJsonString(obj, "cloneId", null);
			String sourceModelId = JsonUtil.getJsonString(obj, "sourceModelId", null);
			if (cloneId == null || cloneId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_CLONE_ID_MISSING));
				return;
			}
			if (sourceModelId == null || sourceModelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_SOURCE_MODEL_ID_MISSING));
				return;
			}
			String trimmedCloneId = cloneId.trim();
			String trimmedSourceId = sourceModelId.trim();

			LlamaServerManager manager = LlamaServerManager.getInstance();

			// 1. cloneId 不能与本地磁盘模型冲突
			if (manager.findModelById(trimmedCloneId) != null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_CLONE_ID_CONFLICT + ": " + trimmedCloneId));
				return;
			}

			// 2. cloneId 不能已有启动配置
			ConfigManager configManager = ConfigManager.getInstance();
			if (configManager.loadAllLaunchConfigs().containsKey(trimmedCloneId)) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_CLONE_CONFIG_EXISTS + ": " + trimmedCloneId));
				return;
			}

			// 3. 源模型必须存在
			if (manager.findModelById(trimmedSourceId) == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_CLONE_SOURCE_NOT_FOUND + ": " + trimmedSourceId));
				return;
			}

			// 4. 源模型不能是克隆体（禁止套娃）
			if (manager.getSourceModelId(trimmedSourceId) != null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_CLONE_SOURCE_IS_CLONE + ": " + trimmedSourceId));
				return;
			}

			String cmd = JsonUtil.getJsonString(obj, "cmd", "");
			String extraParams = JsonUtil.getJsonString(obj, "extraParams", "");
			String envVars = JsonUtil.getJsonString(obj, "envVars", "");
			if (cmd != null) cmd = cmd.trim();
			if (extraParams != null) extraParams = extraParams.trim();
			if (envVars != null) envVars = envVars.trim();
			if (cmd.isEmpty() && extraParams.isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_CLONE_NO_LAUNCH_PARAMS));
				return;
			}

			String llamaBinPathSelect = JsonUtil.getJsonString(obj, "llamaBinPathSelect", null);
			if (llamaBinPathSelect == null || llamaBinPathSelect.trim().isEmpty()) {
				llamaBinPathSelect = JsonUtil.getJsonString(obj, "llamaBinPath", null);
			}
			if (llamaBinPathSelect == null || llamaBinPathSelect.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_CLONE_NO_BIN_PATH));
				return;
			}

			boolean enableVision = ParamTool.parseJsonBoolean(obj, "enableVision", true);
			List<String> device = JsonUtil.getJsonStringList(obj.get("device"));
			Integer mg = JsonUtil.getJsonInt(obj, "mg", null);
			String configName = JsonUtil.getJsonString(obj, "configName", null);

			if (device != null) {
				device.removeIf(d -> d == null || d.trim().isEmpty() || d.trim().equalsIgnoreCase("none") || d.trim().equalsIgnoreCase("all"));
			}

			Map<String, Object> launchConfig = new HashMap<>();
			launchConfig.put("llamaBinPathSelect", llamaBinPathSelect.trim());
			launchConfig.put("cmd", cmd);
			launchConfig.put("extraParams", extraParams);
			launchConfig.put("envVars", envVars);
			launchConfig.put("device", device);
			launchConfig.put("mg", mg);
			launchConfig.put("enableVision", enableVision);

			boolean saved = configManager.saveCloneLaunchConfig(trimmedCloneId, trimmedSourceId, configName,
					launchConfig);
			if (!saved) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_CLONE_SAVE_FAILED));
				return;
			}

			manager.buildAutoLoadModelCache();

			Map<String, Object> data = new HashMap<>();
			data.put("cloneId", trimmedCloneId);
			data.put("sourceModelId", trimmedSourceId);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
			logger.info("[模型操作] 创建克隆体配置成功: cloneId={}, sourceModelId={}", trimmedCloneId, trimmedSourceId);
		} catch (Exception e) {
			logger.warn("创建克隆体配置时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_CLONE_CREATE_FAILED + ": " + e.getMessage()));
		}
	}

	/**
	 * 处理加载模型的请求
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleLoadModelRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);
		try {
			byte[] body = JsonUtil.readRequestBytes(request);
			if (body == null || JsonUtil.isBlank(body)) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_EMPTY));
				return;
			}

			JsonObject obj = JsonUtil.fromJson(body, JsonObject.class);
			if (obj == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_PARSE));
				return;
			}

			// 防御性处理：前端缓存/旧版本可能未发送 sourceModelId，从本地克隆配置补齐
			String modelId = JsonUtil.getJsonString(obj, "modelId", null);
			if (modelId != null && !modelId.trim().isEmpty()) {
				String sourceModelId = JsonUtil.getJsonString(obj, "sourceModelId", null);
				if (sourceModelId == null || sourceModelId.trim().isEmpty()) {
					String configSourceId = LlamaServerManager.getInstance().getSourceModelId(modelId);
					if (configSourceId != null && !configSourceId.trim().isEmpty()) {
						obj.addProperty("sourceModelId", configSourceId);
					}
				}
			}

			String nodeId = JsonUtil.getJsonString(obj, "nodeId");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				this.loadRemoteModel(ctx, nodeId, obj);
				return;
			}

			String sourceModelId = JsonUtil.getJsonString(obj, "sourceModelId", null);
			boolean isCloneRequest = sourceModelId != null && !sourceModelId.trim().isEmpty();

			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_MODELID_REQUIRED));
				return;
			}

			LlamaServerManager manager = LlamaServerManager.getInstance();
			if (isCloneRequest) {
				// 克隆体：配置必须预先存在（用户通过创建流程建立），加载时仅查找并拉起
				String configSourceId = manager.getSourceModelId(modelId);
				if (configSourceId == null) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_CLONE_CONFIG_NOT_FOUND + ": " + modelId));
					return;
				}
				if (!configSourceId.equals(sourceModelId.trim())) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_CLONE_SOURCE_MISMATCH + ": " + configSourceId + " vs " + sourceModelId.trim()));
					return;
				}
				// 用源模型判定本地是否存在（克隆体本身不在磁盘上）
				if (manager.findModelById(sourceModelId.trim()) == null) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_CLONE_SOURCE_NOT_FOUND + ": " + sourceModelId));
					return;
				}
				logger.info("[模型操作] 本地加载克隆体: modelId={}, sourceModelId={}", modelId, sourceModelId);
				this.loadLocalModel(ctx, obj, manager);
			} else {
				if (manager.findModelById(modelId) != null) {
					logger.info("[模型操作] 本地加载模型: modelId={}", modelId);
					this.loadLocalModel(ctx, obj, manager);
				} else {
					logger.info("[模型操作] 本地未找到模型，搜索远程节点: modelId={}", modelId);
					this.findAndLoadOnRemoteNode(ctx, modelId, obj);
				}
			}
		} catch (Exception e) {
			logger.info("加载模型时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_LOAD_FAILED + ": " + e.getMessage()));
		}
	}

	/**
	 * 在远程节点上查找并加载模型
	 */
	private void findAndLoadOnRemoteNode(ChannelHandlerContext ctx, String modelId, JsonObject obj) {
		String nodeId = this.findNodeByModel(modelId);
		if (nodeId == null) {
			logger.warn("[模型操作] 远程节点也未找到模型: modelId={}", modelId);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_NOT_FOUND + ": " + modelId));
			return;
		}
		logger.info("[模型操作] 找到模型所在远程节点: modelId={}, nodeId={}", modelId, nodeId);
		this.loadRemoteModel(ctx, nodeId, obj);
	}

	/**
	 * 加载本地模型（原有逻辑）
	 */
	private void loadLocalModel(ChannelHandlerContext ctx, JsonObject obj, LlamaServerManager manager) {
		String cmd = JsonUtil.getJsonString(obj, "cmd", "");
		String extraParams = JsonUtil.getJsonString(obj, "extraParams", "");
		String envVars = JsonUtil.getJsonString(obj, "envVars", "");
		String mode = JsonUtil.getJsonString(obj, "mode", "form");
		if (cmd != null) cmd = cmd.trim();
		if (extraParams != null) extraParams = extraParams.trim();
		if (envVars != null) envVars = envVars.trim();
		if (mode == null || mode.trim().isEmpty()) mode = "form";
		if ((cmd == null || cmd.isEmpty()) && (extraParams == null || extraParams.isEmpty())) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_LOADED_CMD_MISSING));
			return;
		}
		boolean enableVision = ParamTool.parseJsonBoolean(obj, "enableVision", true);
		String modelId = JsonUtil.getJsonString(obj, "modelId", null);
		String sourceModelId = JsonUtil.getJsonString(obj, "sourceModelId", null);
		if (sourceModelId != null && sourceModelId.trim().isEmpty()) {
			sourceModelId = null;
		}
		String modelNameCmd = JsonUtil.getJsonString(obj, "modelName", null);
		String llamaBinPathSelect = JsonUtil.getJsonString(obj, "llamaBinPathSelect", null);
		if (llamaBinPathSelect == null || llamaBinPathSelect.trim().isEmpty()) {
			llamaBinPathSelect = JsonUtil.getJsonString(obj, "llamaBinPath", null);
		}
		List<String> device = JsonUtil.getJsonStringList(obj.get("device"));
		Integer mg = JsonUtil.getJsonInt(obj, "mg", null);

		// 过滤无效设备值
		if (device != null) {
			device.removeIf(d -> d == null || d.trim().isEmpty() || d.trim().equalsIgnoreCase("none") || d.trim().equalsIgnoreCase("all"));
		}

		if (manager.getLoadedProcesses().containsKey(modelId)) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_ALREADY_LOADED));
			return;
		}
		if (manager.isLoading(modelId)) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_LOADING));
			return;
		}
		if (llamaBinPathSelect == null || llamaBinPathSelect.trim().isEmpty()) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_CLONE_NO_BIN_PATH));
			return;
		}
		// 克隆体的 chat template 文件从源模型查找（克隆体自身无磁盘目录）
		String chatTemplateLookupId = sourceModelId != null ? sourceModelId : modelId;
		String chatTemplateFilePath = ChatTemplateFileTool.getChatTemplateCacheFilePathIfExists(chatTemplateLookupId);
		boolean started = manager.loadModelAsyncFromCmd(modelId, llamaBinPathSelect, device, mg, enableVision, cmd, extraParams, envVars, chatTemplateFilePath, sourceModelId, mode);
		if (!started) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_LOAD_FAILED));
			return;
		}

		Map<String, Object> data = new HashMap<>();
		data.put("async", true);
		data.put("modelId", modelId);
		if (sourceModelId != null) {
			data.put("sourceModelId", sourceModelId);
		}
		data.put("modelName", modelNameCmd);
		data.put("llamaBinPathSelect", llamaBinPathSelect);
		data.put("device", device);
		data.put("mg", mg);
		data.put("mode", mode);
		data.put("cmd", cmd);
		data.put("extraParams", extraParams);
		data.put("envVars", envVars);
		data.put("enableVision", enableVision);
		LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
	}

	/**
	 * 在远程节点上加载模型（移除 nodeId 避免回环）
	 */
	private void loadRemoteModel(ChannelHandlerContext ctx, String nodeId, JsonObject body) {
		if (body != null) {
			body.remove("nodeId");
		}
		NodeManager manager = NodeManager.getInstance();
		NodeManager.HttpResult result = manager.callRemoteApi(nodeId, "POST", "api/models/load", body);
		if (!result.isSuccess()) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_CALL_FAILED + ": " + result.getBody()));
			return;
		}
		String remoteError = this.extractRemoteError(result.getBody());
		if (remoteError != null) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(remoteError));
		} else {
			Map<String, Object> data = new HashMap<>();
			data.put("async", true);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		}
	}
	
	/**
	 * 执行bench测试
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleModelBenchmark(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);
		
		try {
			byte[] body = JsonUtil.readRequestBytes(request);
			if (body == null || JsonUtil.isBlank(body)) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_EMPTY));
				return;
			}
			JsonObject json = JsonUtil.fromJson(body, JsonObject.class);
			if (json == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_PARSE));
				return;
			}

			String nodeId = JsonUtil.getJsonString(json, "nodeId");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				json.remove("nodeId");
				NodeManager.HttpResult result = callRemoteApiTracked(ctx, nodeId, "POST", "api/models/benchmark", json);
				writeRemoteResult(ctx, result);
				return;
			}

			String modelId = json.has("modelId") ? json.get("modelId").getAsString() : null;
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_MODELID_REQUIRED));
				return;
			}
			String cmd = JsonUtil.getJsonString(json, "cmd", null);
			if (cmd != null) {
				cmd = cmd.trim();
				if (cmd.isEmpty()) cmd = null;
			}
			if (cmd == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_CMD_MISSING));
				return;
			}
			String llamaBinPath = null;
			if (json.has("llamaBinPath") && !json.get("llamaBinPath").isJsonNull()) {
				llamaBinPath = json.get("llamaBinPath").getAsString();
				if (llamaBinPath != null) {
					llamaBinPath = llamaBinPath.trim();
					if (llamaBinPath.isEmpty()) {
						llamaBinPath = null;
					}
				}
			}
			LlamaServerManager manager = LlamaServerManager.getInstance();
			manager.listModel();
			GGUFModel model = manager.findModelById(modelId);
			// 克隆体 benchmark 使用源模型 GGUF
			if (model == null) {
				String sourceModelId = manager.getSourceModelId(modelId);
				if (sourceModelId != null) {
					model = manager.findModelById(sourceModelId);
				}
			}
			if (model == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_NOT_FOUND + ": " + modelId));
				return;
			}
			if (model.getPrimaryModel() == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_BENCHMARK_METADATA_INCOMPLETE));
				return;
			}
			String modelPath = model.getPrimaryModel().getFilePath();
			if (llamaBinPath == null || llamaBinPath.isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_LLAMA_BIN_PATH_MISSING));
				return;
			}
			String osName = System.getProperty("os.name").toLowerCase();
			String executableName = "llama-bench";
			if (osName.contains("win")) {
				executableName = "llama-bench.exe";
			}
			File benchFile = new File(llamaBinPath, executableName);
			if (!benchFile.exists() || !benchFile.isFile()) {
				LlamaServer.sendJsonResponse(ctx,
						ApiResponse.error(I18N_MODEL_BENCHMARK_EXE_NOT_FOUND + ": " + benchFile.getAbsolutePath()));
				return;
			}
			List<String> command = new ArrayList<>();
			command.add(benchFile.getAbsolutePath());
			command.add("-m");
			command.add(modelPath);
			
			List<String> cmdArgs = sanitizeBenchmarkCmdArgs(ParamTool.splitCmdArgs(cmd));
			command.addAll(cmdArgs);
			String commandStr = String.join(" ", command);
			ProcessBuilder pb = new ProcessBuilder(command);
			pb.redirectErrorStream(true);
			String benchPath = benchFile.getAbsolutePath();
			if (benchPath.startsWith("/")) {
				int lastSlash = benchPath.lastIndexOf('/');
				if (lastSlash > 0) {
					String libPath = benchPath.substring(0, lastSlash);
					Map<String, String> env = pb.environment();
					String currentLdPath = env.get("LD_LIBRARY_PATH");

					// 构建 LD_LIBRARY_PATH
					StringBuilder newLdPath = new StringBuilder(libPath);
					if (currentLdPath != null && !currentLdPath.isEmpty()) {
						newLdPath.append(":").append(currentLdPath);
					}

					// ROCm 库路径
					String[] rocmPaths = {
						"/opt/rocm/core-7.14/lib",
						"/opt/rocm/core/lib",
						"/opt/rocm/lib",
						"/opt/rocm/lib64",
						"/usr/local/rocm/lib",
						"/usr/local/rocm/lib64",
						"/usr/local/lib64",
						"/usr/local/lib"
					};
					for (String rocmPath : rocmPaths) {
						if (!newLdPath.toString().contains(rocmPath)) {
							newLdPath.append(":").append(rocmPath);
						}
					}

					// SYCL / Intel oneAPI 库路径
					if (benchPath != null && (benchPath.toLowerCase().contains("sycl") || benchPath.toLowerCase().contains("oneapi") || benchPath.toLowerCase().contains("intel"))) {
						String[] syclPaths = {
							"/opt/intel/oneapi/compiler/latest/lib",
							"/opt/intel/oneapi/mkl/latest/lib",
							"/opt/intel/oneapi/tbb/latest/lib",
							"/usr/local/lib",
							"/usr/local/lib64",
							"/usr/lib/x86_64-linux-gnu"
						};
						for (String syclPath : syclPaths) {
							if (!newLdPath.toString().contains(syclPath)) {
								newLdPath.append(":").append(syclPath);
							}
						}
					}

					env.put("LD_LIBRARY_PATH", newLdPath.toString());
				}
			}
			Process process = pb.start();
			// 只保留尾部 64KB：llama-bench 的结果表格在输出末尾，完整输出可能远超堆预算
			TailCharBuffer output = new TailCharBuffer(64 * 1024);
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					output.append(line).append('\n');
				}
			}
			boolean finished = process.waitFor(600, TimeUnit.SECONDS);
			if (!finished) {
				process.destroyForcibly();
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_BENCHMARK_TIMEOUT));
				return;
			}
			int exitCode = process.exitValue();
			String text = output.toString().trim();
			Map<String, Object> data = new HashMap<>();
			data.put("modelId", modelId);
			data.put("command", command);
			data.put("commandStr", commandStr);
			data.put("exitCode", exitCode);
			if (!text.isEmpty()) {
				data.put("rawOutput", text);
				try {
					String safeModelId = modelId == null ? "unknown" : modelId.replaceAll("[^a-zA-Z0-9-_\\.]", "_");
					String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
					String fileName = safeModelId + "_" + timestamp + ".txt";
					File dir = new File("benchmarks");
					if (!dir.exists()) {
						dir.mkdirs();
					}
					File outFile = new File(dir, fileName);
					try (FileOutputStream fos = new FileOutputStream(outFile)) {
						StringBuilder fileContent = new StringBuilder();
						fileContent.append("command: ").append(commandStr).append(System.lineSeparator())
								.append(System.lineSeparator());
						fileContent.append(text);
						fos.write(fileContent.toString().getBytes(StandardCharsets.UTF_8));
					}
					data.put("savedPath", outFile.getAbsolutePath());
				} catch (Exception ex) {
					logger.info("保存基准测试结果到文件失败", ex);
				}
			}
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("执行模型基准测试时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_BENCHMARK_FAILED + ": " + e.getMessage()));
		}
	}
	
	/**
	 * 	基准测试V2
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleModelBenchmarkV2(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);
		try {
			byte[] body = JsonUtil.readRequestBytes(request);
			if (body == null || JsonUtil.isBlank(body)) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_EMPTY));
				return;
			}
			JsonObject json = JsonUtil.fromJson(body, JsonObject.class);
			if (json == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_PARSE));
				return;
			}

			String nodeId = JsonUtil.getJsonString(json, "nodeId");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				json.remove("nodeId");
				NodeManager.HttpResult result = callRemoteApiTracked(ctx, nodeId, "POST", "api/v2/models/benchmark", json);
				writeRemoteResult(ctx, result);
				return;
			}

			Map<String, Object> data = this.benchmarkService.handleBenchmark(ctx, json);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (IllegalArgumentException | IllegalStateException e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(e.getMessage()));
		} catch (Exception e) {
			String msg = e.getMessage();
			if (msg != null && msg.startsWith("api.error.model.benchmark.failed")) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(msg));
			} else {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_BENCHMARK_FAILED + ": " + e.getMessage()));
			}
		}
	}
	
	
	/**
	 * 返回测试结果列表。
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleModelBenchmarkList(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.GET, I18N_METHOD_GET_ONLY);
		
		try {
			String query = request.uri();
			Map<String, String> params = ParamTool.getQueryParam(query);
			String nodeId = params.get("nodeId");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				this.proxyGetRemote(ctx, request, nodeId, "api/models/benchmark/list");
				return;
			}
			String modelId = params.get("modelId");

			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_MODELID_REQUIRED));
				return;
			}
			String safeModelId = modelId.replaceAll("[^a-zA-Z0-9-_\\.]", "_");
			File dir = new File("benchmarks");
			List<Map<String, Object>> files = new ArrayList<>();
			if (dir.exists() && dir.isDirectory()) {
				File[] all = dir.listFiles();
				if (all != null) {
					Arrays.sort(all, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
					for (File f : all) {
						String name = f.getName();
						if (f.isFile() && name.startsWith(safeModelId + "_") && name.endsWith(".txt")) {
							Map<String, Object> info = new HashMap<>();
							info.put("name", name);
							info.put("size", f.length());
							info.put("modified",
									new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(f.lastModified())));
							files.add(info);
						}
					}
				}
			}
			Map<String, Object> data = new HashMap<>();
			data.put("modelId", modelId);
			data.put("files", files);
			data.put("count", files.size());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_BENCHMARK_LIST_FAILED + ": " + e.getMessage()));
		}
	}
	
	/**
	 * 	性能测试V2版，查询指定模型的测试结果。
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleModelBenchmarkV2Get(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, I18N_METHOD_GET_ONLY);
		
		try {
			String query = request.uri();
			Map<String, String> params = ParamTool.getQueryParam(query);
			String nodeId = params.get("nodeId");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				this.proxyGetRemote(ctx, request, nodeId, "api/v2/models/benchmark/get");
				return;
			}
			String modelId = params.get("modelId");
			if (modelId != null) modelId = modelId.trim();
			if (modelId == null || modelId.isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_MODELID_REQUIRED));
				return;
			}
			String safeModelId = modelId.replaceAll("[^a-zA-Z0-9-_\\.]", "_");
			File dir = new File("benchmarks");
			String fileName = safeModelId + "_V2.jsonl";
			File target = new File(dir, fileName);
			if (!target.exists() || !target.isFile()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_FILE_NOT_FOUND));
				return;
			}
			List<String> lines = Files.readAllLines(target.toPath(), StandardCharsets.UTF_8);
			List<Object> records = new ArrayList<>();
			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i);
				if (line == null) continue;
				String trimmed = line.trim();
				if (trimmed.isEmpty()) continue;
				JsonObject obj = JsonUtil.fromJson(trimmed, JsonObject.class);
				if (obj != null) {
					obj.addProperty("_lineNumber", Integer.valueOf(i + 1));
					records.add(JsonUtil.fromJson(obj, Object.class));
				}
			}
			Map<String, Object> data = new HashMap<>();
			data.put("modelId", modelId);
			data.put("fileName", fileName);
			data.put("records", records);
			data.put("savedPath", target.getAbsolutePath());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_BENCHMARK_V2_READ_FAILED + ": " + e.getMessage()));
		}
	}

	/**
	 * 删除指定的性能测试 V2 记录。
	 *
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleModelBenchmarkV2Delete(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);

		try {
			byte[] body = JsonUtil.readRequestBytes(request);
			if (body == null || JsonUtil.isBlank(body)) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_EMPTY));
				return;
			}
			JsonObject json = JsonUtil.fromJson(body, JsonObject.class);
			if (json == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_PARSE));
				return;
			}

			String nodeId = JsonUtil.getJsonString(json, "nodeId");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				json.remove("nodeId");
				NodeManager.HttpResult result = callRemoteApiTracked(ctx, nodeId, "POST", "api/v2/models/benchmark/delete", json);
				writeRemoteResult(ctx, result);
				return;
			}

			String modelId = JsonUtil.getJsonString(json, "modelId", null);
			if (modelId != null) modelId = modelId.trim();
			if (modelId == null || modelId.isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_MODELID_REQUIRED));
				return;
			}
			Integer lineNumber = JsonUtil.getJsonInt(json, "lineNumber", null);
			if (lineNumber == null || lineNumber.intValue() <= 0) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_LINE_NUMBER_MISSING));
				return;
			}

			String safeModelId = modelId.replaceAll("[^a-zA-Z0-9-_\\.]", "_");
			File dir = new File("benchmarks");
			String fileName = safeModelId + "_V2.jsonl";
			File target = new File(dir, fileName);
			if (!target.exists() || !target.isFile()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_FILE_NOT_FOUND));
				return;
			}

			List<String> lines = Files.readAllLines(target.toPath(), StandardCharsets.UTF_8);
			int lineIndex = lineNumber.intValue() - 1;
			if (lineIndex < 0 || lineIndex >= lines.size()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_RECORD_NOT_FOUND));
				return;
			}
			lines.remove(lineIndex);
			Files.write(target.toPath(), lines, StandardCharsets.UTF_8);

			Map<String, Object> data = new HashMap<>();
			data.put("modelId", modelId);
			data.put("fileName", fileName);
			data.put("lineNumber", lineNumber);
			data.put("deleted", true);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_BENCHMARK_V2_DELETE_FAILED + ": " + e.getMessage()));
		}
	}
	
	/**
	 * 获取指定的测试结果。
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleModelBenchmarkGet(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.GET, I18N_METHOD_GET_ONLY);
		
		try {
			String query = request.uri();
			Map<String, String> params = ParamTool.getQueryParam(query);
			String nodeId = params.get("nodeId");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				this.proxyGetRemote(ctx, request, nodeId, "api/models/benchmark/get");
				return;
			}
			String fileName = null;
			
			fileName = params.get("fileName");
			if (fileName == null || fileName.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_FILE_NAME_MISSING));
				return;
			}
			if (!fileName.matches("[a-zA-Z0-9._\\-]+")) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_FILE_NAME_INVALID));
				return;
			}
			File dir = new File("benchmarks");
			File target = new File(dir, fileName);
			if (!target.exists() || !target.isFile()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_FILE_NOT_FOUND));
				return;
			}
			byte[] bytes = Files.readAllBytes(target.toPath());
			String text = new String(bytes, StandardCharsets.UTF_8);
			Map<String, Object> data = new HashMap<>();
			data.put("fileName", fileName);
			data.put("rawOutput", text);
			data.put("savedPath", target.getAbsolutePath());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_BENCHMARK_READ_FAILED + ": " + e.getMessage()));
		}
	}
	
	/**
	 * 删除指定的测试结果。
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleModelBenchmarkDelete(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);
		
		try {
			String query = request.uri();
			Map<String, String> params = ParamTool.getQueryParam(query);
			String nodeId = params.get("nodeId");
			String fileName = params.get("fileName");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				JsonObject body = new JsonObject();
				body.addProperty("fileName", fileName);
				String remotePath = "api/models/benchmark/delete?fileName=" + java.net.URLEncoder.encode(fileName != null ? fileName : "", "UTF-8");
				NodeManager.HttpResult result = callRemoteApiTracked(ctx, nodeId, "POST", remotePath, body);
				writeRemoteResult(ctx, result);
				return;
			}
			
			if (fileName == null || fileName.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_FILE_NAME_MISSING));
				return;
			}
			if (!fileName.matches("[a-zA-Z0-9._\\-]+")) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_FILE_NAME_INVALID));
				return;
			}
			File dir = new File("benchmarks");
			File target = new File(dir, fileName);
			if (!target.exists() || !target.isFile()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_FILE_NOT_FOUND));
				return;
			}
			Files.delete(target.toPath());
			Map<String, Object> data = new HashMap<>();
			data.put("fileName", fileName);
			data.put("deleted", true);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_BENCHMARK_DELETE_FAILED + ": " + e.getMessage()));
		}
	}
	
	
	/**
	 * 加载指定模型指定slot的缓存
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleModelMetrics(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.GET, I18N_METHOD_GET_ONLY);

		try {
			String query = request.uri();
			String modelId = null;
			Map<String, String> params = ParamTool.getQueryParam(query);
			modelId = params.get("modelId");
			
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_MODELID_REQUIRED));
				return;
			}
			LlamaServerManager manager = LlamaServerManager.getInstance();
			if (!manager.getLoadedProcesses().containsKey(modelId)) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_NOT_LOADED + ": " + modelId));
				return;
			}
			Integer port = manager.getModelPort(modelId);
			if (port == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_PORT_NOT_FOUND + ": " + modelId));
				return;
			}
			String targetUrl = String.format("http://localhost:%d/metrics", port.intValue());
			URL url = URI.create(targetUrl).toURL();
			HttpURLConnection connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("GET");
			connection.setConnectTimeout(30000);
			connection.setReadTimeout(30000);
			int responseCode = connection.getResponseCode();
			String responseBody;
			if (responseCode >= 200 && responseCode < 300) {
				try (BufferedReader br = new BufferedReader(
						new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
					StringBuilder sb = new StringBuilder();
					String line;
					while ((line = br.readLine()) != null) {
						sb.append(line);
					}
					responseBody = sb.toString();
				}
				Object parsed = JsonUtil.fromJson(responseBody, Object.class);
				Map<String, Object> data = new HashMap<>();
				data.put("modelId", modelId);
				data.put("metrics", parsed);
				LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
			} else {
				try (BufferedReader br = new BufferedReader(
						new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
					StringBuilder sb = new StringBuilder();
					String line;
					while ((line = br.readLine()) != null) {
						sb.append(line);
					}
					responseBody = sb.toString();
				}
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_METRICS_FAILED + ": " + responseBody));
			}
			connection.disconnect();
		} catch (Exception e) {
			logger.info("获取metrics时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_METRICS_FAILED + ": " + e.getMessage()));
		}
	}
	
	/**
	 * 处理props请求
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleModelProps(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.GET, I18N_METHOD_GET_ONLY);

		try {
			String query = request.uri();
			String modelId = null;
			Map<String, String> params = ParamTool.getQueryParam(query);
			modelId = params.get("modelId");
			
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_MODELID_REQUIRED));
				return;
			}
			LlamaServerManager manager = LlamaServerManager.getInstance();
			if (!manager.getLoadedProcesses().containsKey(modelId)) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_NOT_LOADED + ": " + modelId));
				return;
			}
			Integer port = manager.getModelPort(modelId);
			if (port == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_PORT_NOT_FOUND + ": " + modelId));
				return;
			}
			String targetUrl = String.format("http://localhost:%d/props", port.intValue());
			URL url = URI.create(targetUrl).toURL();
			HttpURLConnection connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("GET");
			connection.setConnectTimeout(30000);
			connection.setReadTimeout(30000);
			int responseCode = connection.getResponseCode();
			String responseBody;
			if (responseCode >= 200 && responseCode < 300) {
				try (BufferedReader br = new BufferedReader(
						new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
					StringBuilder sb = new StringBuilder();
					String line;
					while ((line = br.readLine()) != null) {
						sb.append(line);
					}
					responseBody = sb.toString();
				}
				Object parsed = JsonUtil.fromJson(responseBody, Object.class);
				Map<String, Object> data = new HashMap<>();
				data.put("modelId", modelId);
				data.put("props", parsed);
				LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
			} else {
				try (BufferedReader br = new BufferedReader(
						new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
					StringBuilder sb = new StringBuilder();
					String line;
					while ((line = br.readLine()) != null) {
						sb.append(line);
					}
					responseBody = sb.toString();
				}
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_PROPS_FAILED + ": " + responseBody));
			}
			connection.disconnect();
		} catch (Exception e) {
			logger.info("获取props时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_PROPS_FAILED + ": " + e.getMessage()));
		}
	}
	
	
	/**
	 * 	断开连接。
	 */
	@Override
	public void inactive(ChannelHandlerContext ctx) {
		try {
			this.benchmarkService.channelInactive(ctx);
		} catch (Exception e) {
			e.printStackTrace();
		}
		HttpURLConnection remoteConn = this.remoteConnections.remove(ctx);
		if (remoteConn != null) {
			try { remoteConn.disconnect(); } catch (Exception ignore) {}
		}
	}
	
	/**
	 * 	调用远程 API，同时追踪 HTTP 连接以便客户端中断时断开。
	 */
	private NodeManager.HttpResult callRemoteApiTracked(ChannelHandlerContext ctx, String nodeId, String method, String path, JsonObject body) {
		NodeManager manager = NodeManager.getInstance();
		LlamaHubNode node = manager.getNode(nodeId);
		if (node == null || node.getBaseUrl() == null) {
			return new NodeManager.HttpResult(404, "Node not found: " + nodeId);
		}
		HttpURLConnection connection = null;
		try {
			String targetUrl = node.getBaseUrl() + "/" + path.replaceFirst("^/", "");
			URL url = URI.create(targetUrl).toURL();
			connection = (HttpURLConnection) url.openConnection();
			if (connection instanceof javax.net.ssl.HttpsURLConnection) {
				NodeManager.trustAllCerts((javax.net.ssl.HttpsURLConnection) connection);
			}
			connection.setRequestMethod(method);
			connection.setConnectTimeout(3600 * 7 * 24 * 1000);
			connection.setReadTimeout(3600 * 7 * 24 * 1000);
			if (node.getApiKey() != null && !node.getApiKey().isBlank()) {
				connection.setRequestProperty("Authorization", "Bearer " + node.getApiKey());
			}
			if (ctx != null) {
				this.remoteConnections.put(ctx, connection);
			}
			if (body != null && (method.equals("POST") || method.equals("PUT"))) {
				connection.setDoOutput(true);
				connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
				try (java.io.OutputStream os = connection.getOutputStream()) {
					String jsonStr = JsonUtil.toJson(body);
					os.write(jsonStr.getBytes(StandardCharsets.UTF_8));
				}
			}
			int responseCode = connection.getResponseCode();
			String responseBody = NodeManager.readStream(responseCode >= 200 && responseCode < 300 ? connection.getInputStream() : connection.getErrorStream());
			return new NodeManager.HttpResult(responseCode, responseBody);
		} catch (java.io.IOException e) {
			logger.warn("远程API调用失败: nodeId={}, path={}, error={}", nodeId, path, e.getMessage());
			return new NodeManager.HttpResult(502, "Connection failed: " + e.getMessage());
		} catch (Exception e) {
			logger.warn("远程API调用失败: nodeId={}, path={}, error={}", nodeId, path, e.getMessage());
			return new NodeManager.HttpResult(502, "Connection failed: " + e.getMessage());
		} finally {
			if (ctx != null) this.remoteConnections.remove(ctx);
			if (connection != null) connection.disconnect();
		}
	}
	
	/**
	 * 	
	 * @param args
	 * @return
	 */
	private List<String> sanitizeBenchmarkCmdArgs(List<String> args) {
		if (args == null || args.isEmpty()) return new ArrayList<>();
		List<String> input = args;
		String first = input.get(0);
		if (first != null) {
			String f = first.trim().toLowerCase();
			if (f.endsWith("llama-bench") || f.endsWith("llama-bench.exe")) {
				input = input.subList(1, input.size());
			}
		}
		
		List<String> out = new ArrayList<>(Math.max(0, input.size()));
		for (int i = 0; i < input.size(); i++) {
			String a = input.get(i);
			if (a == null) continue;
			if ("-m".equals(a) || "--model".equals(a)) {
				i++;
				continue;
			}
			out.add(a);
		}
		return out;
	}

	/**
	 * 在远程节点上查找已加载的模型，返回 nodeId
	 */
	private String findNodeByLoadedModel(String modelId) {
		NodeManager nodeManager = NodeManager.getInstance();
		for (LlamaHubNode node : nodeManager.listEnabledNodes()) {
			try {
				NodeManager.HttpResult result = nodeManager.fetchRemoteLoadedModels(node.getNodeId());
				if (!result.isSuccess()) continue;
				JsonObject root = JsonUtil.fromJson(result.getBody(), JsonObject.class);
				if (root == null || !root.has("models")) continue;
				com.google.gson.JsonArray models = root.getAsJsonArray("models");
				if (models == null) continue;
				for (com.google.gson.JsonElement el : models) {
					if (!el.isJsonObject()) continue;
					String id = JsonUtil.getJsonString(el.getAsJsonObject(), "id");
					if (modelId.equals(id)) {
						return node.getNodeId();
					}
				}
			} catch (Exception e) {
				logger.warn("[模型操作] 检查远程节点已加载模型异常: nodeId={}, error={}", node.getNodeId(), e.getMessage());
			}
		}
		return null;
	}

	/**
	 * 在远程节点上查找模型（未加载），返回 nodeId
	 */
	private String findNodeByModel(String modelId) {
		NodeManager nodeManager = NodeManager.getInstance();
		for (LlamaHubNode node : nodeManager.listEnabledNodes()) {
			try {
				NodeManager.HttpResult result = nodeManager.fetchRemoteModels(node.getNodeId());
				if (!result.isSuccess()) continue;
				JsonObject root = JsonUtil.fromJson(result.getBody(), JsonObject.class);
				if (root == null || !root.has("models")) continue;
				com.google.gson.JsonArray models = root.getAsJsonArray("models");
				if (models == null) continue;
				for (com.google.gson.JsonElement el : models) {
					if (!el.isJsonObject()) continue;
					String id = JsonUtil.getJsonString(el.getAsJsonObject(), "id");
					if (modelId.equals(id)) {
						return node.getNodeId();
					}
				}
			} catch (Exception e) {
				logger.warn("[模型操作] 检查远程节点模型异常: nodeId={}, error={}", node.getNodeId(), e.getMessage());
			}
		}
		return null;
	}

	/**
	 * 代理 GET 请求到远程节点（移除 nodeId 避免回环）
	 */
	private void proxyGetRemote(ChannelHandlerContext ctx, FullHttpRequest request, String nodeId, String path) {
		if (nodeId == null || nodeId.isBlank() || "local".equals(nodeId)) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_NODE_INVALID + ": " + nodeId));
			return;
		}
		try {
			String uri = request.uri();
			int qIdx = uri.indexOf('?');
			String fullPath;
			if (qIdx >= 0) {
				String query = uri.substring(qIdx + 1);
				String[] pairs = query.split("&");
				StringBuilder cleanQuery = new StringBuilder();
				for (String pair : pairs) {
					if (pair.startsWith("nodeId=")) continue;
					if (cleanQuery.length() > 0) cleanQuery.append('&');
					cleanQuery.append(pair);
				}
				fullPath = cleanQuery.length() > 0 ? path + "?" + cleanQuery.toString() : path;
			} else {
				fullPath = path;
			}
			NodeManager.HttpResult result = NodeManager.getInstance().callRemoteApi(nodeId, "GET", fullPath, null);
			writeRemoteResult(ctx, result);
		} catch (Exception e) {
			logger.warn("[模型操作] 远程代理 GET 失败: nodeId={}, path={}, error={}", nodeId, path, e.getMessage());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_NODE_CALL_FAILED + ": " + e.getMessage()));
		}
	}

	/**
	 * 将远程 HTTP 结果写入 Netty channel
	 */
	private void writeRemoteResult(ChannelHandlerContext ctx, NodeManager.HttpResult result) {
		if (result.isSuccess()) {
			NodeManager.writeHttpResultToChannel(ctx, result, "[模型操作]");
		} else {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_CALL_FAILED + ": code=" + result.getStatusCode()));
		}
	}
}
