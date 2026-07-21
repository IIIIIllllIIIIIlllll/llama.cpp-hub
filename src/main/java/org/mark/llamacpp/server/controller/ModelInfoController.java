package org.mark.llamacpp.server.controller;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.mark.llamacpp.gguf.GGUFMetaData;
import org.mark.llamacpp.gguf.GGUFModel;
import org.mark.llamacpp.server.ConfigManager;
import org.mark.llamacpp.server.LlamaCppProcess;
import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.NodeManager;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.service.ChatTemplateKwargsService;
import org.mark.llamacpp.server.service.LlamaRecordService;
import org.mark.llamacpp.server.service.ModelSamplingService;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.struct.TokenSummaryEntry;
import org.mark.llamacpp.server.tools.ChatTemplateFileTool;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.tools.ParamTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;


/**
 * 	模型信息相关的控制器s
 */
public class ModelInfoController implements BaseController {
	
	private static final Logger logger = LoggerFactory.getLogger(ModelInfoController.class);

	private static final String I18N_METHOD_POST_ONLY = "common.method.post.only";
	private static final String I18N_METHOD_GET_ONLY = "common.method.get.only";
	private static final String I18N_BODY_EMPTY = "api.error.body.empty";
	private static final String I18N_BODY_PARSE = "api.error.body.parse";
	private static final String I18N_BODY_NOT_JSON = "api.error.body.not.json";
	private static final String I18N_PARAM_MODELID_REQUIRED = "api.error.param.modelId.required";
	private static final String I18N_REMOTE_CALL_FAILED = "api.error.remote.call.failed";
	private static final String I18N_REMOTE_NODE_INVALID = "api.error.remote.node.invalid";
	private static final String I18N_REMOTE_NODE_CALL_FAILED = "api.error.remote.node.call.failed";
	private static final String I18N_MODEL_NOT_FOUND = "api.error.model.notfound";
	private static final String I18N_MODEL_RUNNING_CANNOT_SET_ALIAS = "api.error.model.running.cannot.set.alias";
	private static final String I18N_PARAM_MODELID_EMPTY = "api.error.param.modelId.empty";
	private static final String I18N_PARAM_MODELID_OR_ALIAS_REQUIRED = "api.error.param.modelId.or.alias.required";
	private static final String I18N_PARAM_CONFIG_NAME_MISSING = "api.error.param.configName.missing";
	private static final String I18N_PARAM_SHARED_NAME_MISSING = "api.error.param.sharedName.missing";
	private static final String I18N_PARAM_CHAT_TEMPLATE_MISSING = "api.error.param.chatTemplate.missing";
	private static final String I18N_PARAM_MODELID_MISSING = "api.error.param.modelId.missing";
	private static final String I18N_MODEL_ALIAS_SET_FAILED = "api.error.model.alias.set.failed";
	private static final String I18N_MODEL_ALIAS_INVALID_CHARS = "api.error.model.alias.invalid.chars";
	private static final String I18N_MODEL_ALIAS_DOT_RESERVED = "api.error.model.alias.dot.reserved";
	private static final String I18N_MODEL_ALIAS_SYSTEM_RESERVED = "api.error.model.alias.system.reserved";
	private static final String I18N_MODEL_ALIAS_TRAILING_SPACE = "api.error.model.alias.trailing.space";
	private static final String I18N_MODEL_ALIAS_TOO_LONG = "api.error.model.alias.too.long";
	private static final String I18N_MODEL_FAVOURITE_FAILED = "api.error.model.favourite.failed";
	private static final String I18N_MODEL_CONFIG_GET_FAILED = "api.error.model.config.get.failed";
	private static final String I18N_MODEL_CONFIG_SET_FAILED = "api.error.model.config.set.failed";
	private static final String I18N_MODEL_CONFIG_SAVE_FAILED = "api.error.model.config.save.failed";
	private static final String I18N_MODEL_CONFIG_DELETE_FAILED = "api.error.model.config.delete.failed";
	private static final String I18N_MODEL_CONFIG_DELETE_ITEM_FAILED = "api.error.model.config.delete.item.failed";
	private static final String I18N_MODEL_CAPABILITIES_SET_FAILED = "api.error.model.capabilities.set.failed";
	private static final String I18N_MODEL_CAPABILITIES_GET_FAILED = "api.error.model.capabilities.get.failed";
	private static final String I18N_MODEL_RECORD_FAILED = "api.error.model.record.failed";
	private static final String I18N_MODEL_SPEED_FAILED = "api.error.model.speed.failed";
	private static final String I18N_MODEL_LIST_FAILED = "api.error.model.list.failed";
	private static final String I18N_MODEL_DETAILS_FAILED = "api.error.model.details.failed";
	private static final String I18N_MODEL_TEMPLATE_GET_FAILED = "api.error.model.template.get.failed";
	private static final String I18N_MODEL_TEMPLATE_SET_FAILED = "api.error.model.template.set.failed";
	private static final String I18N_MODEL_TEMPLATE_DELETE_FAILED = "api.error.model.template.delete.failed";
	private static final String I18N_MODEL_TEMPLATE_DEFAULT_FAILED = "api.error.model.template.default.failed";
	private static final String I18N_MODEL_SLOTS_GET_FAILED = "api.error.model.slots.get.failed";
	private static final String I18N_MODEL_SLOTS_SAVE_FAILED = "api.error.model.slots.save.failed";
	private static final String I18N_MODEL_SLOTS_LOAD_FAILED = "api.error.model.slots.load.failed";
	private static final String I18N_MODEL_KWARGS_SET_FAILED = "api.error.model.kwargs.set.failed";
	private static final String I18N_MODEL_KWARGS_GET_FAILED = "api.error.model.kwargs.get.failed";
	private static final String I18N_MODEL_KWARGS_DELETE_FAILED = "api.error.model.kwargs.delete.failed";
	private static final String I18N_SHARED_CONFIG_SET_FAILED = "api.error.shared.config.set.failed";
	private static final String I18N_SHARED_CONFIG_GET_FAILED = "api.error.shared.config.get.failed";
	private static final String I18N_SHARED_CONFIG_DELETE_FAILED = "api.error.shared.config.delete.failed";
	//private static final String I18N_SHARED_CONFIG_NOT_FOUND = "api.error.shared.config.notfound";
	private static final String I18N_SHARED_CONFIG_UNSHARE_FAILED = "api.error.shared.config.unshare.failed";
	
	public ModelInfoController() {
		
	}
	
	
	@Override
	public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		
		// 获取模型。
		if(uri.equals("/api/models/openai/list")) {
			this.handleOpenAIModelsRequest(ctx, request);
			return true;
		}
		
		// 设置模型的别名
		if (uri.equals("/api/models/alias/set")) {
			this.handleSetModelAliasRequest(ctx, request);
			return true;
		}
		// 获取偏好模型的API
		if (uri.equals("/api/models/favourite")) {
			this.handleModelFavouriteRequest(ctx, request);
			return true;
		}
		// 查询指定模型启动参数的API
		if (uri.equals("/api/models/config/get")) {
			this.handleModelConfigRequest(ctx, request);
			return true;
		}
		// 用于更新启动参数的API
		if (uri.equals("/api/models/config/set")) {
			this.handleModelConfigSetRequest(ctx, request);
			return true;
		}
		// 用于删除指定配置项的API
		if (uri.equals("/api/models/config/delete")) {
			this.handleModelConfigDeleteRequest(ctx, request);
			return true;
		}
		// 设置共享配置
		if (uri.equals("/api/models/config/shared/set")) {
			this.handleModelConfigSharedSetRequest(ctx, request);
			return true;
		}
		// 获取全部共享配置
		if (uri.equals("/api/models/config/shared/get")) {
			this.handleModelConfigSharedGetRequest(ctx, request);
			return true;
		}
		// 删除共享配置
		if (uri.equals("/api/models/config/shared/delete")) {
			this.handleModelConfigSharedDeleteRequest(ctx, request);
			return true;
		}
		// 获取指定模型详情的API
		if (uri.equals("/api/models/details")) {
			this.handleModelDetailsRequest(ctx, request);
			return true;
		}
		// 模型的能力设定
		if(uri.equals("/api/models/capabilities/set")) {
			this.handleModelCapabilitiesSetRequest(ctx, request);
			return true;
		}
		// 模型的能力获取
		if(uri.equals("/api/models/capabilities/get")) {
			this.handleModelCapabilitiesGetRequest(ctx, request);
			return true;
		}
		
		//============================聊天模板相关============================
		// 
		if (uri.equals("/api/model/template/get")) {
			this.handleModelTemplateGetRequest(ctx, request);
			return true;
		}
		
		if (uri.equals("/api/model/template/set")) {
			this.handleModelTemplateSetRequest(ctx, request);
			return true;
		}

		if (uri.equals("/api/model/template/delete")) {
			this.handleModelTemplateDeleteRequest(ctx, request);
			return true;
		}

		if (uri.equals("/api/model/template/default")) {
			this.handleModelTemplateDefaultRequest(ctx, request);
			return true;
		}
		
		if (uri.equals("/api/model/chat_template_kwargs/set")) {
			this.handleChatTemplateKwargsSet(ctx, request);
			return true;
		}
		
		if (uri.equals("/api/model/chat_template_kwargs/get")) {
			this.handleChatTemplateKwargsGet(ctx, request);
			return true;
		}

		if (uri.equals("/api/model/chat_template_kwargs/delete")) {
			this.handleChatTemplateKwargsDelete(ctx, request);
			return true;
		}
		
		
		//============================用量信息============================
		// 查询对应模型的最快解码/预填充速度（必须放在 /record 之前，避免被误匹配）
		if (uri.equals("/api/models/record/speed")) {
			this.handleModelRecordSpeedRequest(ctx, request);
			return true;
		}
		// 查询对应模型的用量记录
		if (uri.equals("/api/models/record")) {
			this.handleModelRecordRequest(ctx, request);
			return true;
		}
		//============================运行时信息============================
		// 查询对应模型的/solts的API
		if (uri.equals("/api/models/slots/get")) {
			this.handleModelSlotsGet(ctx, request);
			return true;
		}
		// 对应URL-POST：/slots/{solt_id}?action=save
		if (uri.equals("/api/models/slots/save")) {
			this.handleModelSlotsSave(ctx, request);
			return true;
		}
		// 对应URL-POST：/slots/{slot_id}?action=load
		if (uri.equals("/api/models/slots/load")) {
			this.handleModelSlotsLoad(ctx, request);
			return true;
		}
		//============================其它============================
		
		return false;
	}
	
	/**
	 * 获取模型用量记录
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleModelRecordRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, I18N_METHOD_GET_ONLY);
		try {
			Map<String, String> params = ParamTool.getQueryParam(request.uri());
			String modelId = params.get("modelId");
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_MODELID_REQUIRED));
				return;
			}
			Object record = LlamaRecordService.getInstance().getRecord(modelId);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(record));
		} catch (Exception e) {
			logger.info("获取模型用量记录时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_RECORD_FAILED + ": " + e.getMessage()));
		}
   }

    /**
     * 获取模型最快解码/预填充速度
     * @param ctx
     * @param request
     * @throws RequestMethodException
     */
    private void handleModelRecordSpeedRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
        this.assertRequestMethod(request.method() != HttpMethod.GET, I18N_METHOD_GET_ONLY);
        try {
            Map<String, String> params = ParamTool.getQueryParam(request.uri());
            String modelId = params.get("modelId");
            if (modelId == null || modelId.trim().isEmpty()) {
                List<Map<String, Object>> list = new ArrayList<>();
                for (TokenSummaryEntry entry : LlamaRecordService.getInstance().getTokenSummary()) {
                    Map<String, Object> item = new HashMap<>();
                    item.put("modelId", entry.getModelId());
                    item.put("maxPredictedPerSecond", entry.getMaxPredictedPerSecond());
                    item.put("maxPromptPerSecond", entry.getMaxPromptPerSecond());
                    item.put("averagePredictedPerSecond", entry.getAveragePredictedPerSecond());
                    item.put("averagePromptPerSecond", entry.getAveragePromptPerSecond());
                    list.add(item);
                }
                LlamaServer.sendJsonResponse(ctx, ApiResponse.success(list));
                return;
            }
            TokenSummaryEntry entry = LlamaRecordService.getInstance().getTokenSummaryEntry(modelId);
            Map<String, Object> data = new HashMap<>();
            data.put("modelId", modelId);
            data.put("maxPredictedPerSecond", entry != null ? entry.getMaxPredictedPerSecond() : 0f);
            data.put("maxPromptPerSecond", entry != null ? entry.getMaxPromptPerSecond() : 0f);
            data.put("averagePredictedPerSecond", entry != null ? entry.getAveragePredictedPerSecond() : 0d);
            data.put("averagePromptPerSecond", entry != null ? entry.getAveragePromptPerSecond() : 0d);
            LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
        } catch (Exception e) {
            logger.info("获取模型最快速度时发生错误", e);
            LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_SPEED_FAILED + ": " + e.getMessage()));
        }
    }

    /**
     * 	模型的能力设定
     * @param ctx
     * @param request
     * @throws RequestMethodException
     */
    private void handleModelCapabilitiesSetRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);
		try {
			byte[] bodyBytes = JsonUtil.readRequestBytes(request);
			if (bodyBytes == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_EMPTY));
				return;
			}
			JsonObject json = JsonUtil.tryParseObject(bodyBytes);
			if (json == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_PARSE));
				return;
			}
			String nodeId = JsonUtil.getJsonString(json, "nodeId", "");
			if (nodeId != null && !nodeId.isBlank()) {
				logger.info("[模型信息] 远程代理设置能力: nodeId={}, modelId={}", nodeId, JsonUtil.getJsonString(json, "modelId", ""));
				this.proxyPostRemote(ctx, request, nodeId, "api/models/capabilities/set");
				return;
			}
			String modelId = JsonUtil.getJsonString(json, "modelId", null);
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_MODELID_REQUIRED));
				return;
			}
			JsonObject capsObj = (json.has("capabilities") && json.get("capabilities") != null && json.get("capabilities").isJsonObject())
					? json.getAsJsonObject("capabilities")
					: json;
			JsonObject result = LlamaServerManager.getInstance().setModelCapabilities(modelId, capsObj);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(result));
		} catch (Exception e) {
			logger.info("保存模型能力配置时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_CAPABILITIES_SET_FAILED + ": " + e.getMessage()));
		}
	}
	
	/**
	 * 	查询模型的能力
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleModelCapabilitiesGetRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, I18N_METHOD_GET_ONLY);
		try {
			Map<String, String> params = ParamTool.getQueryParam(request.uri());
			String modelId = params.get("modelId");
			String nodeId = params.get("nodeId");
			if (nodeId != null && !nodeId.isBlank()) {
				logger.info("[模型信息] 远程代理获取能力: nodeId={}, modelId={}", nodeId, modelId);
				this.proxyGetRemote(ctx, request, nodeId, "api/models/capabilities/get");
				return;
			}
			JsonObject result = LlamaServerManager.getInstance().getModelCapabilitiesSummary(modelId);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(result));
		} catch (Exception e) {
			logger.info("获取模型能力配置时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_CAPABILITIES_GET_FAILED + ": " + e.getMessage()));
		}
	}
	
	
	/**
	 * 	处理模型列表请求
	 * 	/api/models
	 * 	
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleOpenAIModelsRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {			
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.GET, I18N_METHOD_GET_ONLY);		
		
		try {
			LlamaServerManager manager = LlamaServerManager.getInstance();
			Map<String, LlamaCppProcess> loaded = manager.getLoadedProcesses();

			Map<String, JsonObject> modelsByKey = new LinkedHashMap<>();
			Map<String, JsonObject> dataById = new LinkedHashMap<>();

			for (Map.Entry<String, LlamaCppProcess> e : loaded.entrySet()) {
				String modelId = e.getKey();
				if (modelId == null || modelId.isBlank()) {
					continue;
				}
				JsonObject info = manager.getLoadedModelInfo(modelId);
				if (info == null) {
					try {
						info = manager.handleModelInfo(modelId);
					} catch (Exception ignore) {
						info = null;
					}
				}
				if (info == null) {
					continue;
				}

				if (!info.has("items") || !info.get("items").isJsonArray()) {
					continue;
				}
				JsonArray items = info.getAsJsonArray("items");
				for (JsonElement itemEl : items) {
					if (itemEl == null || itemEl.isJsonNull() || !itemEl.isJsonObject()) {
						continue;
					}
					JsonObject item = itemEl.getAsJsonObject();

					if (item.has("model") && item.get("model").isJsonObject()) {
						JsonObject m = item.getAsJsonObject("model");
						String key = JsonUtil.getJsonString(m, "model");
						if (key.isEmpty()) {
							key = JsonUtil.getJsonString(m, "name");
						}
						if (!key.isEmpty() && !modelsByKey.containsKey(key)) {
							modelsByKey.put(key, m.deepCopy());
						}
					}

					if (item.has("data") && item.get("data").isJsonObject()) {
						JsonObject d = item.getAsJsonObject("data");
						String id = JsonUtil.getJsonString(d, "id");
						if (!id.isEmpty() && !dataById.containsKey(id)) {
							JsonObject dCopy = d.deepCopy();
							String srcId = e.getValue().getSourceModelId();
							if (srcId != null) {
								dCopy.addProperty("sourceModelId", srcId);
							}
							dataById.put(id, dCopy);
						}
					}
				}
			}

			JsonArray models = new JsonArray();
			for (JsonObject m : modelsByKey.values()) {
				models.add(m);
			}
			JsonArray data = new JsonArray();
			for (JsonObject d : dataById.values()) {
				data.add(d);
			}

			JsonObject response = new JsonObject();
			response.addProperty("object", "list");
			response.add("models", models);
			response.add("data", data);
			LlamaServer.sendJsonResponse(ctx, response);
		} catch (Exception e) {
			logger.info("获取模型列表时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_LIST_FAILED + ": " + e.getMessage()));
		}
	}

	/**
	 * 修改别名。
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleSetModelAliasRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);

		try {
			byte[] bodyBytes = JsonUtil.readRequestBytes(request);
			if (bodyBytes == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_EMPTY));
				return;
			}
			JsonObject json = JsonUtil.tryParseObject(bodyBytes);
			if (json == null || !json.has("modelId") || !json.has("alias")) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_MODELID_OR_ALIAS_REQUIRED));
				return;
			}
			String nodeId = JsonUtil.getJsonString(json, "nodeId", "");
			if (nodeId != null && !nodeId.isBlank()) {
				logger.info("[模型信息] 远程代理设置别名: nodeId={}, modelId={}", nodeId, JsonUtil.getJsonString(json, "modelId", ""));
				this.proxyPostRemote(ctx, request, nodeId, "api/models/alias/set");
				return;
			}
			String modelId = json.get("modelId").getAsString();
			String alias = json.get("alias").getAsString();
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_MODELID_EMPTY));
				return;
			}
			if (alias == null)
				alias = "";
			alias = alias.trim();
			if (!alias.isEmpty()) {
				if (alias.matches(".*[\\\\/:*?\"<>|\\x00-\\x1F].*")) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_ALIAS_INVALID_CHARS));
					return;
				}
				if (alias.equals(".") || alias.equals("..")) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_ALIAS_DOT_RESERVED));
					return;
				}
				String base = alias.replaceAll("\\..*$", "").toUpperCase();
				if (base.matches("^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$")) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_ALIAS_SYSTEM_RESERVED));
					return;
				}
				if (alias.endsWith(" ") || alias.endsWith(".")) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_ALIAS_TRAILING_SPACE));
					return;
				}
				if (alias.length() > 255) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_ALIAS_TOO_LONG));
					return;
				}
			}
			if (LlamaServerManager.getInstance().getLoadedProcesses().containsKey(modelId)) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_RUNNING_CANNOT_SET_ALIAS));
				return;
			}
			// 更新配置文件
			ConfigManager configManager = ConfigManager.getInstance();
			boolean ok = configManager.saveModelAlias(modelId, alias);
			// 更新内存模型
			LlamaServerManager manager = LlamaServerManager.getInstance();
			GGUFModel model = manager.findModelById(modelId);
			if (model != null) {
				model.setAlias(alias);
			}
			Map<String, Object> data = new HashMap<>();
			data.put("modelId", modelId);
			data.put("alias", alias);
			data.put("saved", ok);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("设置模型别名时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_ALIAS_SET_FAILED + ": " + e.getMessage()));
		}
	}
	
	
	/**
	 * 偏好模型的请求
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleModelFavouriteRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);

		try {
			byte[] bodyBytes = JsonUtil.readRequestBytes(request);
			if (bodyBytes == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_EMPTY));
				return;
			}
			JsonObject json = JsonUtil.tryParseObject(bodyBytes);
			if (json == null || !json.has("modelId")) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_MODELID_MISSING));
				return;
			}
			String modelId = json.get("modelId").getAsString();
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_MODELID_EMPTY));
				return;
			}
			String nodeId = JsonUtil.getJsonString(json, "nodeId", "");
			if (nodeId != null && !nodeId.isBlank()) {
				logger.info("[模型信息] 远程代理设置偏好: nodeId={}, modelId={}", nodeId, modelId);
				this.proxyPostRemote(ctx, request, nodeId, "api/models/favourite");
				return;
			}

			LlamaServerManager manager = LlamaServerManager.getInstance();
			manager.listModel();
			GGUFModel model = manager.findModelById(modelId);
			if (model == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_NOT_FOUND + ": " + modelId));
				return;
			}

			boolean next = !model.isFavourite();
			model.setFavourite(next);
			ConfigManager configManager = ConfigManager.getInstance();
			boolean saved = configManager.saveModelFavourite(modelId, next);

			Map<String, Object> data = new HashMap<>();
			data.put("modelId", modelId);
			data.put("favourite", next);
			data.put("saved", saved);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("设置模型喜好时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_FAVOURITE_FAILED + ": " + e.getMessage()));
		}
	}
	
	/**
	 * 处理获取模型启动配置请求
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleModelConfigRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, I18N_METHOD_GET_ONLY);
		try {
			Map<String, String> params = ParamTool.getQueryParam(request.uri());
			String modelId = params.get("modelId");
			String nodeId = params.get("nodeId");
			if (nodeId != null && !nodeId.isBlank()) {
				logger.info("[模型信息] 远程代理配置: nodeId={}, modelId={}", nodeId, modelId);
				this.proxyGetRemote(ctx, request, nodeId, "api/models/config/get");
				return;
			}
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_MODELID_REQUIRED));
				return;
			}
			ConfigManager configManager = ConfigManager.getInstance();
			Map<String, Object> bundle = configManager.getModelLaunchConfigBundle(modelId);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(bundle));
		} catch (Exception e) {
			logger.info("获取模型启动配置时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_CONFIG_GET_FAILED + ": " + e.getMessage()));
		}
	}
	
	
	/**
	 * 设置模型的启动参数
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleModelConfigSetRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);
		try {
			byte[] bodyBytes = JsonUtil.readRequestBytes(request);
			if (bodyBytes == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_EMPTY));
				return;
			}
			JsonElement root = JsonUtil.fromJson(bodyBytes, JsonElement.class);
			if (root == null || !root.isJsonObject()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_NOT_JSON));
				return;
			}
			JsonObject obj = root.getAsJsonObject();
			String nodeId = JsonUtil.getJsonString(obj, "nodeId", "");
			if (nodeId != null && !nodeId.isBlank()) {
				logger.info("[模型信息] 远程代理设置配置: nodeId={}, modelId={}", nodeId, JsonUtil.getJsonString(obj, "modelId", ""));
				this.proxyPostRemote(ctx, request, nodeId, "api/models/config/set");
				return;
			}
			ConfigManager configManager = ConfigManager.getInstance();
			Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
			Map<String, Object> savedData = new HashMap<>();
			if (obj.has("modelId")) {
				String modelId = obj.get("modelId").getAsString();
				if (modelId == null || modelId.trim().isEmpty()) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_MODELID_REQUIRED));
					return;
				}
				String configName = JsonUtil.getJsonString(obj, "configName", null);
				boolean setSelected = obj.has("setSelected") && !obj.get("setSelected").isJsonNull() && obj.get("setSelected").getAsBoolean();
				JsonElement cfgEl = obj.has("config") ? obj.get("config") : obj;
				Map<String, Object> cfgMap = JsonUtil.fromJson(cfgEl, mapType);
				if (cfgMap == null) cfgMap = new HashMap<>();
				cfgMap.remove("modelId");
				cfgMap.remove("config");
				cfgMap.remove("configName");
				cfgMap.remove("setSelected");
				if (cfgMap.containsKey("chatTemplate")) {
					Object v = cfgMap.get("chatTemplate");
					String s = v == null ? "" : String.valueOf(v);
					if (s.trim().isEmpty()) {
						ChatTemplateFileTool.deleteChatTemplateCacheFile(modelId);
					} else {
						ChatTemplateFileTool.writeChatTemplateToCacheFile(modelId, s);
					}
					cfgMap.remove("chatTemplate");
				}
				// 关键注释：保存到命名配置，并在需要时切换当前选中配置
				boolean saved = configManager.saveLaunchConfig(modelId, configName, cfgMap, setSelected);
				if (!saved) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_CONFIG_SAVE_FAILED));
					return;
				}
				LlamaServerManager.getInstance().buildAutoLoadModelCache();
				savedData.put(modelId, configManager.getModelLaunchConfigBundle(modelId));
				LlamaServer.sendJsonResponse(ctx, ApiResponse.success(savedData));
				// 保存参数后，重新加载。
				ModelSamplingService.getInstance().reload();
				return;
			}

			for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
				String modelId = e.getKey();
				if (modelId == null || modelId.trim().isEmpty()) continue;
				JsonElement cfgEl = e.getValue();
				if (cfgEl == null || cfgEl.isJsonNull()) continue;
				if (!cfgEl.isJsonObject()) continue;
				Map<String, Object> cfgMap = JsonUtil.fromJson(cfgEl, mapType);
				if (cfgMap == null) cfgMap = new HashMap<>();
				if (cfgMap.containsKey("chatTemplate")) {
					Object v = cfgMap.get("chatTemplate");
					String s = v == null ? "" : String.valueOf(v);
					if (s.trim().isEmpty()) {
						ChatTemplateFileTool.deleteChatTemplateCacheFile(modelId);
					} else {
						ChatTemplateFileTool.writeChatTemplateToCacheFile(modelId, s);
					}
					cfgMap.remove("chatTemplate");
				}
				boolean saved = configManager.saveLaunchConfig(modelId, null, cfgMap, false);
				if (!saved) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_CONFIG_SAVE_FAILED));
					return;
				}
				LlamaServerManager.getInstance().buildAutoLoadModelCache();
				savedData.put(modelId, configManager.getModelLaunchConfigBundle(modelId));
			}

			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(savedData));
		} catch (Exception e) {
			logger.info("设置模型启动配置时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_CONFIG_SET_FAILED + ": " + e.getMessage()));
		}
	}

	private void handleModelConfigDeleteRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);
		try {
			byte[] bodyBytes = JsonUtil.readRequestBytes(request);
			if (bodyBytes == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_EMPTY));
				return;
			}
			JsonObject obj = JsonUtil.tryParseObject(bodyBytes);
			if (obj == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_NOT_JSON));
				return;
			}
			String nodeId = JsonUtil.getJsonString(obj, "nodeId", "");
			if (nodeId != null && !nodeId.isBlank()) {
				logger.info("[模型信息] 远程代理删除配置: nodeId={}, modelId={}", nodeId, JsonUtil.getJsonString(obj, "modelId", ""));
				this.proxyPostRemote(ctx, request, nodeId, "api/models/config/delete");
				return;
			}
			String modelId = JsonUtil.getJsonString(obj, "modelId", null);
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_MODELID_REQUIRED));
				return;
			}
			String configName = JsonUtil.getJsonString(obj, "configName", null);
			ConfigManager configManager = ConfigManager.getInstance();
			boolean deleted;
			// 克隆体配置直接删除整个条目，而不是只删除 configs 内的命名配置
			if ((configName == null || configName.trim().isEmpty())
					&& configManager.getSourceModelId(modelId) != null) {
				deleted = configManager.deleteLaunchConfigEntry(modelId);
			} else {
				deleted = configManager.deleteLaunchConfig(modelId, configName);
			}
			if (!deleted) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_CONFIG_DELETE_ITEM_FAILED));
				return;
			}
			// 关键注释：删除后直接返回规范化配置包，前端可立即刷新下拉与当前配置
			Map<String, Object> bundle = configManager.getModelLaunchConfigBundle(modelId);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(bundle));
      } catch (Exception e) {
            logger.info("删除模型启动配置时发生错误", e);
            LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_CONFIG_DELETE_FAILED + ": " + e.getMessage()));
        }
    }

    private void handleModelConfigSharedSetRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
        this.assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);
        try {
            byte[] bodyBytes = JsonUtil.readRequestBytes(request);
            if (bodyBytes == null) {
                LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_EMPTY));
                return;
            }
            JsonObject obj = JsonUtil.tryParseObject(bodyBytes);
            if (obj == null) {
                LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_PARSE));
                return;
            }
            String nodeId = JsonUtil.getJsonString(obj, "nodeId", "");
            if (nodeId != null && !nodeId.isBlank()) {
                logger.info("[模型信息] 远程代理设置共享配置: nodeId={}, modelId={}", nodeId, JsonUtil.getJsonString(obj, "modelId", ""));
                this.proxyPostRemote(ctx, request, nodeId, "api/models/config/shared/set");
                return;
            }
            String modelId = JsonUtil.getJsonString(obj, "modelId", null);
            if (modelId == null || modelId.trim().isEmpty()) {
                LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_MODELID_REQUIRED));
                return;
            }
            String configName = JsonUtil.getJsonString(obj, "configName", null);
            if (configName == null || configName.trim().isEmpty()) {
                LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_CONFIG_NAME_MISSING));
                return;
            }
            String sharedName = JsonUtil.getJsonString(obj, "sharedName", null);
            if (sharedName == null || sharedName.trim().isEmpty()) {
                LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_SHARED_NAME_MISSING));
                return;
            }
            ConfigManager configManager = ConfigManager.getInstance();
            Map<String, Object> result = configManager.shareLaunchConfig(modelId, configName, sharedName);
            boolean success = Boolean.TRUE.equals(result.get("success"));
            if (!success) {
                String errorMsg = (String) result.getOrDefault("error", "共享配置失败");
                LlamaServer.sendJsonResponse(ctx, ApiResponse.error(errorMsg));
                return;
            }
            Map<String, Object> data = new HashMap<>();
            data.put("sharedName", sharedName);
            data.put("source", modelId);
            data.put("sourceConfigName", configName);
            LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
        } catch (Exception e) {
            logger.info("设置共享配置时发生错误", e);
            LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_SHARED_CONFIG_SET_FAILED + ": " + e.getMessage()));
        }
    }

    private void handleModelConfigSharedGetRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
        this.assertRequestMethod(request.method() != HttpMethod.GET, I18N_METHOD_GET_ONLY);
        try {
            Map<String, String> params = ParamTool.getQueryParam(request.uri());
            String nodeId = params.get("nodeId");
            if (nodeId != null && !nodeId.isBlank()) {
                logger.info("[模型信息] 远程代理获取共享配置: nodeId={}", nodeId);
                this.proxyGetRemote(ctx, request, nodeId, "api/models/config/shared/get");
                return;
            }
            ConfigManager configManager = ConfigManager.getInstance();
            Map<String, Object> shared = configManager.getAllSharedConfigs();
            LlamaServer.sendJsonResponse(ctx, ApiResponse.success(shared));
        } catch (Exception e) {
            logger.info("获取共享配置时发生错误", e);
            LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_SHARED_CONFIG_GET_FAILED + ": " + e.getMessage()));
        }
    }

    private void handleModelConfigSharedDeleteRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
        this.assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);
        try {
            byte[] bodyBytes = JsonUtil.readRequestBytes(request);
            if (bodyBytes == null) {
                LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_EMPTY));
                return;
            }
            JsonObject obj = JsonUtil.tryParseObject(bodyBytes);
            if (obj == null) {
                LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_PARSE));
                return;
            }
            String nodeId = JsonUtil.getJsonString(obj, "nodeId", "");
            if (nodeId != null && !nodeId.isBlank()) {
                logger.info("[模型信息] 远程代理删除共享配置: nodeId={}, sharedName={}", nodeId, JsonUtil.getJsonString(obj, "sharedName", ""));
                this.proxyPostRemote(ctx, request, nodeId, "api/models/config/shared/delete");
                return;
            }
            String sharedName = JsonUtil.getJsonString(obj, "sharedName", null);
            if (sharedName == null || sharedName.trim().isEmpty()) {
                LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_SHARED_NAME_MISSING));
                return;
            }
            ConfigManager configManager = ConfigManager.getInstance();
            boolean ok = configManager.unshareLaunchConfig(sharedName);
            if (!ok) {
                LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_SHARED_CONFIG_UNSHARE_FAILED));
                return;
            }
            Map<String, Object> data = new HashMap<>();
            data.put("sharedName", sharedName);
            LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
        } catch (Exception e) {
            logger.info("删除共享配置时发生错误", e);
            LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_SHARED_CONFIG_DELETE_FAILED + ": " + e.getMessage()));
        }
    }

    /**
     * 处理器模型详情的请求
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleModelDetailsRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, I18N_METHOD_GET_ONLY);

		try {
			Map<String, String> params = ParamTool.getQueryParam(request.uri());
			String modelId = params.get("modelId");
			String nodeId = params.get("nodeId");
			if (nodeId != null && !nodeId.isBlank()) {
				logger.info("[模型信息] 远程代理详情: nodeId={}, modelId={}", nodeId, modelId);
				this.proxyGetRemote(ctx, request, nodeId, "api/models/details");
				return;
			}
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_MODELID_REQUIRED));
				return;
			}
			LlamaServerManager manager = LlamaServerManager.getInstance();
			manager.listModel();
			GGUFModel model = manager.findModelById(modelId);
			// 克隆体的 GGUF 元数据从源模型获取
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
//			Map<String, Object> metadata = new HashMap<>();
//			GGUFMetaData primary = model.getPrimaryModel();
//			if (primary != null) {
//				Map<String, Object> m = GGUFMetaDataReader.read(new File(primary.getFilePath()));
//				if (m != null) {
//					m.remove("tokenizer.ggml.merges");
//					//m.remove("tokenizer.chat_template");
//					m.remove("tokenizer.ggml.token_type");
//					metadata.putAll(m);
//				}
//			}
//			GGUFMetaData mmproj = model.getMmproj();
//			if (mmproj != null) {
//				Map<String, Object> m2 = GGUFMetaDataReader.read(new File(mmproj.getFilePath()));
//				if (m2 != null) {
//					for (Map.Entry<String, Object> e : m2.entrySet()) {
//						metadata.put("mmproj." + e.getKey(), e.getValue());
//					}
//				}
//			}
			boolean isLoaded = manager.getLoadedProcesses().containsKey(modelId);
			String startCmd = isLoaded ? manager.getModelStartCmd(modelId) : null;
			Integer port = manager.getModelPort(modelId);
			Map<String, Object> modelMap = new HashMap<>();
			String alias = model.getAlias();
			modelMap.put("name", alias != null && !alias.isEmpty() ? alias : modelId);
			modelMap.put("path", model.getPath());
			modelMap.put("size", model.getSize());
			//modelMap.put("metadata", metadata);
			modelMap.put("isLoaded", isLoaded);
			if (startCmd != null && !startCmd.isEmpty()) {
				modelMap.put("startCmd", startCmd);
			}
			if (port != null) {
				modelMap.put("port", port);
			}
			Map<String, Object> response = new HashMap<>();
			response.put("model", modelMap);
			response.put("success", true);
			LlamaServer.sendJsonResponse(ctx, response);
		} catch (Exception e) {
			logger.info("获取模型详情时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_DETAILS_FAILED + ": " + e.getMessage()));
		}
	}
	
	
	/**
	 * 	请求指定模型的模板
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleModelTemplateGetRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, I18N_METHOD_GET_ONLY);
		try {
			Map<String, String> params = ParamTool.getQueryParam(request.uri());
			String modelId = params.get("modelId");
			String nodeId = params.get("nodeId");
			if (nodeId != null && !nodeId.isBlank()) {
				logger.info("[模型信息] 远程代理获取模板: nodeId={}, modelId={}", nodeId, modelId);
				this.proxyGetRemote(ctx, request, nodeId, "api/model/template/get");
				return;
			}
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_MODELID_REQUIRED));
				return;
			}
			String chatTemplate = ChatTemplateFileTool.readChatTemplateFromCacheFile(modelId);
			String filePath = ChatTemplateFileTool.getChatTemplateCacheFilePathIfExists(modelId);
			Map<String, Object> data = new HashMap<>();
			data.put("modelId", modelId);
			data.put("exists", filePath != null && !filePath.isEmpty());
			if (filePath != null && !filePath.isEmpty()) {
				data.put("filePath", filePath);
			}
			data.put("chatTemplate", chatTemplate);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("获取模型聊天模板时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_TEMPLATE_GET_FAILED + ": " + e.getMessage()));
		}
	}
	
	/**
	 * 	设置指定模型的自定义模板
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleModelTemplateSetRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);
		try {
			byte[] bodyBytes = JsonUtil.readRequestBytes(request);
			if (bodyBytes == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_EMPTY));
				return;
			}
			JsonObject obj = JsonUtil.tryParseObject(bodyBytes);
			if (obj == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_PARSE));
				return;
			}
			String nodeId = JsonUtil.getJsonString(obj, "nodeId", "");
			if (nodeId != null && !nodeId.isBlank()) {
				logger.info("[模型信息] 远程代理设置模板: nodeId={}, modelId={}", nodeId, JsonUtil.getJsonString(obj, "modelId", ""));
				this.proxyPostRemote(ctx, request, nodeId, "api/model/template/set");
				return;
			}
			String modelId = JsonUtil.getJsonString(obj, "modelId", null);
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_MODELID_REQUIRED));
				return;
			}
			String chatTemplate = JsonUtil.getJsonString(obj, "chatTemplate", null);
			if (chatTemplate == null) chatTemplate = JsonUtil.getJsonString(obj, "template", null);
			if (chatTemplate == null) chatTemplate = JsonUtil.getJsonString(obj, "content", null);
			if (chatTemplate == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_CHAT_TEMPLATE_MISSING));
				return;
			}

			boolean deleted = false;
			String filePath = null;
			if (chatTemplate.trim().isEmpty()) {
				deleted = ChatTemplateFileTool.deleteChatTemplateCacheFile(modelId);
			} else {
				filePath = ChatTemplateFileTool.writeChatTemplateToCacheFile(modelId, chatTemplate);
			}

			Map<String, Object> data = new HashMap<>();
			data.put("modelId", modelId);
			data.put("deleted", deleted);
			if (filePath != null && !filePath.isEmpty()) {
				data.put("filePath", filePath);
			}
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("设置模型聊天模板时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_TEMPLATE_SET_FAILED + ": " + e.getMessage()));
		}
	}
	
	
	/**
	 * 	删除指定模型的自定义模板
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleModelTemplateDeleteRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);
		try {
			byte[] bodyBytes = JsonUtil.readRequestBytes(request);
			if (bodyBytes == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_EMPTY));
				return;
			}
			JsonObject obj = JsonUtil.tryParseObject(bodyBytes);
			if (obj == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_PARSE));
				return;
			}
			String nodeId = JsonUtil.getJsonString(obj, "nodeId", "");
			if (nodeId != null && !nodeId.isBlank()) {
				logger.info("[模型信息] 远程代理删除模板: nodeId={}, modelId={}", nodeId, JsonUtil.getJsonString(obj, "modelId", ""));
				this.proxyPostRemote(ctx, request, nodeId, "api/model/template/delete");
				return;
			}
			String modelId = JsonUtil.getJsonString(obj, "modelId", null);
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_MODELID_REQUIRED));
				return;
			}

			boolean existed = ChatTemplateFileTool.getChatTemplateCacheFilePathIfExists(modelId) != null;
			boolean deleted = ChatTemplateFileTool.deleteChatTemplateCacheFile(modelId);
			Map<String, Object> data = new HashMap<>();
			data.put("modelId", modelId);
			data.put("existed", existed);
			data.put("deleted", deleted);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("删除模型聊天模板时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_TEMPLATE_DELETE_FAILED + ": " + e.getMessage()));
		}
	}
	
	/**
	 * 	
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleModelTemplateDefaultRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, I18N_METHOD_GET_ONLY);
		try {
			Map<String, String> params = ParamTool.getQueryParam(request.uri());
			String modelId = params.get("modelId");
			String nodeId = params.get("nodeId");
			if (nodeId != null && !nodeId.isBlank()) {
				logger.info("[模型信息] 远程代理默认模板: nodeId={}, modelId={}", nodeId, modelId);
				this.proxyGetRemote(ctx, request, nodeId, "api/model/template/default");
				return;
			}
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_MODELID_REQUIRED));
				return;
			}

			LlamaServerManager manager = LlamaServerManager.getInstance();
			manager.listModel();
			GGUFModel model = manager.findModelById(modelId);
			// 克隆体的默认聊天模板从源模型 GGUF 中获取
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

			boolean exists = false;
			String chatTemplate = "";
			GGUFMetaData primary = model.getPrimaryModel();
			if (primary != null) {
				String tpl = primary.getChatTemplate();
				if (tpl != null) {
					exists = true;
					chatTemplate = tpl;
				}
			}

			Map<String, Object> data = new HashMap<>();
			data.put("modelId", modelId);
			data.put("exists", exists);
			data.put("chatTemplate", chatTemplate);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("获取模型默认聊天模板时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_TEMPLATE_DEFAULT_FAILED + ": " + e.getMessage()));
		}
	}
	
	/**
	 * 获取指定模型的slots信息
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleModelSlotsGet(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, I18N_METHOD_GET_ONLY);

		try {
			Map<String, String> params = ParamTool.getQueryParam(request.uri());
			String modelId = params.get("modelId");
			String nodeId = params.get("nodeId");
			if (nodeId != null && !nodeId.isBlank()) {
				logger.info("[模型信息] 远程代理获取slots: nodeId={}, modelId={}", nodeId, modelId);
				this.proxyGetRemote(ctx, request, nodeId, "api/models/slots/get");
				return;
			}
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_MODELID_REQUIRED));
				return;
			}
			JsonObject result = LlamaServerManager.getInstance().handleModelSlotsGet(modelId);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(result));
		} catch (Exception e) {
			logger.info("获取模型slots信息时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_SLOTS_GET_FAILED + ": " + e.getMessage()));
		}
	}
	
	/**
	 * 保存指定模型指定slot的缓存
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleModelSlotsSave(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);

		try {
			byte[] bodyBytes = JsonUtil.readRequestBytes(request);
			if (bodyBytes == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_EMPTY));
				return;
			}
			JsonObject json = JsonUtil.tryParseObject(bodyBytes);
			if (json == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_PARSE));
				return;
			}
			String nodeId = JsonUtil.getJsonString(json, "nodeId", "");
			if (nodeId != null && !nodeId.isBlank()) {
				logger.info("[模型信息] 远程代理保存slots: nodeId={}, modelId={}", nodeId, JsonUtil.getJsonString(json, "modelId", ""));
				this.proxyPostRemote(ctx, request, nodeId, "api/models/slots/save");
				return;
			}
			String modelId = json.has("modelId") ? json.get("modelId").getAsString() : null;
			Integer slotId = null;
			if (json.has("slotId")) {
				slotId = json.get("slotId").getAsInt();
			}
			String fileName = modelId + "_" + slotId + ".bin";
			ApiResponse response = LlamaServerManager.getInstance().handleModelSlotsSave(modelId, slotId.intValue(),
					fileName);
			// 响应消息。
			LlamaServer.sendJsonResponse(ctx, response);
		} catch (Exception e) {
			logger.info("保存模型slots缓存时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_SLOTS_SAVE_FAILED + ": " + e.getMessage()));
		}
	}

	/**
	 * 	设置指定模型的chat template kwargs
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleChatTemplateKwargsSet(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);
		try {
			byte[] bodyBytes = JsonUtil.readRequestBytes(request);
			if (bodyBytes == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_EMPTY));
				return;
			}
			JsonObject obj = JsonUtil.tryParseObject(bodyBytes);
			if (obj == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_PARSE));
				return;
			}
			String nodeId = JsonUtil.getJsonString(obj, "nodeId", "");
			if (nodeId != null && !nodeId.isBlank()) {
				logger.info("[模型信息] 远程代理设置kwargs: nodeId={}, modelId={}", nodeId, JsonUtil.getJsonString(obj, "modelId", ""));
				this.proxyPostRemote(ctx, request, nodeId, "api/model/chat_template_kwargs/set");
				return;
			}
			String modelId = JsonUtil.getJsonString(obj, "modelId", null);
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_MODELID_REQUIRED));
				return;
			}
			JsonObject kwargs = (obj.has("chat_template_kwargs") && obj.get("chat_template_kwargs") != null
					&& obj.get("chat_template_kwargs").isJsonObject())
					? obj.getAsJsonObject("chat_template_kwargs")
					: obj;
			ChatTemplateKwargsService.getInstance().upsertKwargsConfig(modelId, kwargs);
			Map<String, Object> data = new HashMap<>();
			data.put("modelId", modelId);
			data.put("saved", true);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("设置chat template kwargs时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_KWARGS_SET_FAILED + ": " + e.getMessage()));
		}
	}

	/**
	 * 	获取指定模型的chat template kwargs
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleChatTemplateKwargsGet(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, I18N_METHOD_GET_ONLY);
		try {
			Map<String, String> params = ParamTool.getQueryParam(request.uri());
			String modelId = params.get("modelId");
			String nodeId = params.get("nodeId");
			if (nodeId != null && !nodeId.isBlank()) {
				logger.info("[模型信息] 远程代理获取kwargs: nodeId={}, modelId={}", nodeId, modelId);
				this.proxyGetRemote(ctx, request, nodeId, "api/model/chat_template_kwargs/get");
				return;
			}
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_MODELID_REQUIRED));
				return;
			}
			JsonObject kwargs = ChatTemplateKwargsService.getInstance().getOpenAIChatTemplateKwargs(modelId);
			Map<String, Object> data = new HashMap<>();
			data.put("modelId", modelId);
			data.put("chat_template_kwargs", kwargs != null ? kwargs : new JsonObject());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("获取chat template kwargs时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_KWARGS_GET_FAILED + ": " + e.getMessage()));
		}
	}

	/**
	 * 	删除指定模型的chat template kwargs
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleChatTemplateKwargsDelete(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);
		try {
			byte[] bodyBytes = JsonUtil.readRequestBytes(request);
			if (bodyBytes == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_EMPTY));
				return;
			}
			JsonObject obj = JsonUtil.tryParseObject(bodyBytes);
			if (obj == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_PARSE));
				return;
			}
			String nodeId = JsonUtil.getJsonString(obj, "nodeId", "");
			if (nodeId != null && !nodeId.isBlank()) {
				logger.info("[模型信息] 远程代理删除kwargs: nodeId={}, modelId={}", nodeId, JsonUtil.getJsonString(obj, "modelId", ""));
				this.proxyPostRemote(ctx, request, nodeId, "api/model/chat_template_kwargs/delete");
				return;
			}
			String modelId = JsonUtil.getJsonString(obj, "modelId", null);
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_MODELID_REQUIRED));
				return;
			}
			ChatTemplateKwargsService.getInstance().deleteKwargsConfig(modelId);
			Map<String, Object> data = new HashMap<>();
			data.put("modelId", modelId);
			data.put("deleted", true);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("删除chat template kwargs时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_KWARGS_DELETE_FAILED + ": " + e.getMessage()));
		}
	}

	/**
	 * 加载指定模型指定slot的缓存
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleModelSlotsLoad(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);

		try {
			byte[] bodyBytes = JsonUtil.readRequestBytes(request);
			if (bodyBytes == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_EMPTY));
				return;
			}
			JsonObject json = JsonUtil.tryParseObject(bodyBytes);
			if (json == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_PARSE));
				return;
			}
			String nodeId = JsonUtil.getJsonString(json, "nodeId", "");
			if (nodeId != null && !nodeId.isBlank()) {
				logger.info("[模型信息] 远程代理加载slots: nodeId={}, modelId={}", nodeId, JsonUtil.getJsonString(json, "modelId", ""));
				this.proxyPostRemote(ctx, request, nodeId, "api/models/slots/load");
				return;
			}
			String modelId = json.has("modelId") ? json.get("modelId").getAsString() : null;
			Integer slotId = null;
			if (json.has("slotId")) {
				slotId = json.get("slotId").getAsInt();
			}
			String fileName = modelId + "_" + slotId.intValue() + ".bin";
			ApiResponse response = LlamaServerManager.getInstance().handleModelSlotsLoad(modelId, slotId.intValue(),
					fileName);
			// 响应消息。
			LlamaServer.sendJsonResponse(ctx, response);
		} catch (Exception e) {
			logger.info("加载模型slots缓存时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_SLOTS_LOAD_FAILED + ": " + e.getMessage()));
		}
	}

	/**
	 * 代理GET请求到远程节点（透传URI查询参数）
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
			this.writeRemoteResult(ctx, result, nodeId);
		} catch (Exception e) {
			logger.warn("[模型信息] 远程代理失败: nodeId={}, path={}, error={}", nodeId, path, e.getMessage());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_NODE_CALL_FAILED + ": " + e.getMessage()));
		}
	}

	/**
	 * 代理POST请求到远程节点（透传请求体，移除nodeId避免回环）
	 */
	private void proxyPostRemote(ChannelHandlerContext ctx, FullHttpRequest request, String nodeId, String path) {
		if (nodeId == null || nodeId.isBlank() || "local".equals(nodeId)) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_NODE_INVALID + ": " + nodeId));
			return;
		}
		try {
			JsonObject body = JsonUtil.fromJson(JsonUtil.readRequestBytes(request), JsonObject.class);
			if (body != null) {
				body.remove("nodeId");
				if (body.size() == 0) body = null;
			}
			NodeManager.HttpResult result = NodeManager.getInstance().callRemoteApi(nodeId, "POST", path, body);
			this.writeRemoteResult(ctx, result, nodeId);
		} catch (Exception e) {
			logger.warn("[模型信息] 远程代理失败: nodeId={}, path={}, error={}", nodeId, path, e.getMessage());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_NODE_CALL_FAILED + ": " + e.getMessage()));
		}
	}

	private void writeRemoteResult(ChannelHandlerContext ctx, NodeManager.HttpResult result, String nodeId) {
		if (result.isSuccess()) {
			NodeManager.writeHttpResultToChannel(ctx, result, "[模型信息]");
		} else {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_CALL_FAILED + ": code=" + result.getStatusCode()));
		}
	}
}
