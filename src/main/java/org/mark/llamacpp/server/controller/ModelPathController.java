package org.mark.llamacpp.server.controller;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.NodeManager;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.struct.ModelPathConfig;
import org.mark.llamacpp.server.struct.ModelPathDataStruct;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.tools.ParamTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;

/**
 * 	
 */
public class ModelPathController implements BaseController {

	
	private static final Logger logger = LoggerFactory.getLogger(ModelPathController.class);

	private static final String I18N_BODY_EMPTY = "api.error.body.empty";
	private static final String I18N_BODY_PARSE = "api.error.body.parse";
	private static final String I18N_PARAM_PATH_EMPTY = "api.error.param.path.empty";
	private static final String I18N_PARAM_ORIGINAL_PATH_EMPTY = "api.error.param.originalPath.empty";
	private static final String I18N_DIR_INVALID = "api.error.dir.invalid";
	private static final String I18N_DIR_SYSTEM_ROOT = "api.error.dir.system.root";
	private static final String I18N_PATH_EXISTS = "api.error.path.exists";
	private static final String I18N_PATH_OVERLAP = "api.error.path.overlap";
	private static final String I18N_PATH_EXISTS_OR_OVERLAP = "api.error.path.exists.or.overlap";
	private static final String I18N_REMOTE_CALL_FAILED = "api.error.remote.call.failed";
	private static final String I18N_MODEL_PATH_ADD_FAILED = "api.error.model.path.add.failed";
	private static final String I18N_MODEL_PATH_REMOVE_FAILED = "api.error.model.path.remove.failed";
	private static final String I18N_MODEL_PATH_UPDATE_FAILED = "api.error.model.path.update.failed";
	private static final String I18N_MODEL_PATH_LIST_FAILED = "api.error.model.path.list.failed";
	private static final String I18N_MODEL_PATH_CONFIG_NOTFOUND = "api.error.model.path.config.notfound";
	private static final String I18N_MODEL_PATH_NOTFOUND = "api.error.model.path.notfound";
	
	
	public ModelPathController() {
		
	}

	private void proxyPostRemote(ChannelHandlerContext ctx, FullHttpRequest request, String nodeId, String path) {
		try {
			JsonObject body = JsonUtil.fromJson(JsonUtil.readRequestBytes(request), JsonObject.class);
			if (body != null) {
				body.remove("nodeId");
				if (body.size() == 0) body = null;
			}
			NodeManager.HttpResult result = NodeManager.getInstance().callRemoteApi(nodeId, "POST", path, body);
			if (result.isSuccess()) {
				NodeManager.writeHttpResultToChannel(ctx, result, "[模型路径远程]");
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
				NodeManager.writeHttpResultToChannel(ctx, result, "[模型路径远程]");
			} else {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_CALL_FAILED + ": code=" + result.getStatusCode()));
			}
		} catch (Exception e) {
			logger.warn("远程节点调用失败: nodeId={}, path={}, error={}", nodeId, path, e.getMessage());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_CALL_FAILED + ": " + e.getMessage()));
		}
	}
	
	@Override
	public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (uri.equals("/api/model/path/add")) {
			this.handleModelPathAdd(ctx, request);
			return true;
		}

		if (uri.equals("/api/model/path/remove")) {
			this.handleModelPathRemove(ctx, request);
			return true;
		}

		if (uri.equals("/api/model/path/update")) {
			this.handleModelPathUpdate(ctx, request);
			return true;
		}

		if (uri.equals("/api/model/path/list")) {
			this.handleModelPathList(ctx, request);
			return true;
		}
		
		return false;
	}
	
	
	/**
	 * 添加模型路径
	 *
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleModelPathAdd(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		try {
			String nodeId = ParamTool.getQueryParam(request.uri()).get("nodeId");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				this.proxyPostRemote(ctx, request, nodeId, "api/model/path/add");
				return;
			}
			byte[] body = JsonUtil.readRequestBytes(request);
			if (body == null || JsonUtil.isBlank(body)) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_EMPTY));
				return;
			}
			ModelPathDataStruct reqData = JsonUtil.fromJson(body, ModelPathDataStruct.class);
			if (reqData == null || reqData.getPath() == null || reqData.getPath().trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_PATH_EMPTY));
				return;
			}

			LlamaServerManager manager = LlamaServerManager.getInstance();
			Path configFile = LlamaServer.getModelPathConfigPath();
			ModelPathConfig cfg = LlamaServer.readModelPathConfig(configFile);
			cfg = this.ensureModelPathConfigInitialized(cfg, manager.getModelPaths(), configFile);
			List<ModelPathDataStruct> items = cfg.getItems();
			if (items == null) {
				items = new ArrayList<>();
				cfg.setItems(items);
			}
			String normalized = reqData.getPath().trim();
			Path validated = this.validateAndNormalizeDirectory(normalized);
			if (validated == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_DIR_INVALID));
				return;
			}
			normalized = validated.toString();
			if (this.isVolumeRoot(validated)) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_DIR_SYSTEM_ROOT));
				return;
			}
			boolean conflict = false;
			for (ModelPathDataStruct i : items) {
				if (i == null || i.getPath() == null) continue;
				String existing = i.getPath().trim();
				Path existingPath = Paths.get(existing).toAbsolutePath().normalize();
				if (this.isSamePath(normalized, existing)) {
					conflict = true;
					break;
				}
				try {
					if (validated.startsWith(existingPath) || existingPath.startsWith(validated)) {
						conflict = true;
						break;
					}
				} catch (Exception ignore) {
				}
			}
			if (!conflict) {
				Path defaultModelsPath = Paths.get(LlamaServer.getDefaultModelsPath()).toAbsolutePath().normalize();
				try {
					if (validated.equals(defaultModelsPath) || validated.startsWith(defaultModelsPath) || defaultModelsPath.startsWith(validated)) {
						conflict = true;
					}
				} catch (Exception ignore) {
				}
			}
			if (conflict) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PATH_EXISTS_OR_OVERLAP));
				return;
			}
			ModelPathDataStruct item = new ModelPathDataStruct();
			item.setPath(normalized);
			String name = reqData.getName();
			if (name == null || name.trim().isEmpty()) {
				try {
					name = java.nio.file.Paths.get(normalized).getFileName().toString();
				} catch (Exception ex) {
					name = normalized;
				}
			}
			item.setName(name);
			item.setDescription(reqData.getDescription());
			items.add(item);
			LlamaServer.writeModelPathConfig(configFile, cfg);
			this.syncModelPathsToRuntime(manager, cfg, true);

			Map<String, Object> data = new HashMap<>();
			data.put("message", "添加模型路径成功");
			data.put("added", item);
			data.put("count", items.size());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("添加模型路径时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_PATH_ADD_FAILED + ": " + e.getMessage()));
		}
	}
	
	/**
	 * 移除模型路径
	 *
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleModelPathRemove(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		try {
			String nodeId = ParamTool.getQueryParam(request.uri()).get("nodeId");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				this.proxyPostRemote(ctx, request, nodeId, "api/model/path/remove");
				return;
			}
			byte[] body = JsonUtil.readRequestBytes(request);
			if (body == null || JsonUtil.isBlank(body)) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_EMPTY));
				return;
			}
			ModelPathDataStruct reqData = JsonUtil.fromJson(body, ModelPathDataStruct.class);
			if (reqData == null || reqData.getPath() == null || reqData.getPath().trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_PATH_EMPTY));
				return;
			}
			String normalized = reqData.getPath().trim();

			LlamaServerManager manager = LlamaServerManager.getInstance();
			Path configFile = LlamaServer.getModelPathConfigPath();
			ModelPathConfig cfg = LlamaServer.readModelPathConfig(configFile);
			cfg = this.ensureModelPathConfigInitialized(cfg, manager.getModelPaths(), configFile);
			List<ModelPathDataStruct> items = cfg.getItems();
			int before = items == null ? 0 : items.size();
			boolean changed = false;
			if (items != null) {
				changed = items.removeIf(i -> this.isSamePath(normalized, i == null || i.getPath() == null ? "" : i.getPath().trim()));
			}

			LlamaServer.writeModelPathConfig(configFile, cfg);
			this.syncModelPathsToRuntime(manager, cfg, true);

			Map<String, Object> data = new HashMap<>();
			data.put("message", "移除模型路径成功");
			data.put("removed", normalized);
			data.put("count", items == null ? 0 : items.size());
			data.put("changed", changed || before != (items == null ? 0 : items.size()));
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("移除模型路径时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_PATH_REMOVE_FAILED + ": " + e.getMessage()));
		}
	}
	
	
	/**
	 * 更新模型路径（原地修改）
	 *
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleModelPathUpdate(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		try {
			String nodeId = ParamTool.getQueryParam(request.uri()).get("nodeId");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				this.proxyPostRemote(ctx, request, nodeId, "api/model/path/update");
				return;
			}
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
			String originalPath = obj.has("originalPath") ? obj.get("originalPath").getAsString() : null;
			String newPath = obj.has("path") ? obj.get("path").getAsString() : null;
			String name = obj.has("name") ? obj.get("name").getAsString() : null;
			String description = obj.has("description") ? obj.get("description").getAsString() : null;

			if (originalPath == null || originalPath.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_ORIGINAL_PATH_EMPTY));
				return;
			}
			if (newPath == null || newPath.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_PATH_EMPTY));
				return;
			}

			String originalNormalized = originalPath.trim();
			String newNormalized = newPath.trim();
			Path validated = this.validateAndNormalizeDirectory(newNormalized);
			if (validated == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_DIR_INVALID));
				return;
			}
			newNormalized = validated.toString();

			LlamaServerManager manager = LlamaServerManager.getInstance();
			Path configFile = LlamaServer.getModelPathConfigPath();
			ModelPathConfig cfg = LlamaServer.readModelPathConfig(configFile);
			cfg = this.ensureModelPathConfigInitialized(cfg, manager.getModelPaths(), configFile);

			List<ModelPathDataStruct> items = cfg.getItems();
			if (items == null || items.isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_PATH_CONFIG_NOTFOUND));
				return;
			}

			ModelPathDataStruct target = null;
			for (ModelPathDataStruct i : items) {
				if (i == null || i.getPath() == null) continue;
				if (this.isSamePath(originalNormalized, i.getPath().trim())) {
					target = i;
					break;
				}
			}
			if (target == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_PATH_NOTFOUND + ": " + originalNormalized));
				return;
			}

			boolean pathChanged = !this.isSamePath(originalNormalized, newNormalized);
			if (pathChanged) {
				if (this.isVolumeRoot(validated)) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_DIR_SYSTEM_ROOT));
					return;
				}
				for (ModelPathDataStruct i : items) {
					if (i == null || i.getPath() == null) continue;
					if (i == target) continue;
					String existing = i.getPath().trim();
					Path existingPath = Paths.get(existing).toAbsolutePath().normalize();
					if (this.isSamePath(newNormalized, existing)) {
						LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PATH_EXISTS));
						return;
				}
				try {
					if (validated.startsWith(existingPath) || existingPath.startsWith(validated)) {
						LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PATH_OVERLAP));
						return;
					}
				} catch (Exception ignore) {
				}
			}
			// 也检查默认模型路径
			Path defaultModelsPath = Paths.get(LlamaServer.getDefaultModelsPath()).toAbsolutePath().normalize();
			try {
				if (validated.equals(defaultModelsPath) || validated.startsWith(defaultModelsPath) || defaultModelsPath.startsWith(validated)) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PATH_OVERLAP));
					return;
				}
			} catch (Exception ignore) {
			}
		}

		target.setPath(newNormalized);
			if (name == null || name.trim().isEmpty()) {
				try {
					name = Paths.get(newNormalized).getFileName().toString();
				} catch (Exception ex) {
					name = newNormalized;
				}
			}
			target.setName(name);
			target.setDescription(description);

			LlamaServer.writeModelPathConfig(configFile, cfg);
			this.syncModelPathsToRuntime(manager, cfg, pathChanged);

			Map<String, Object> data = new HashMap<>();
			data.put("message", "更新模型路径成功");
			data.put("updated", target);
			data.put("count", items.size());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("更新模型路径时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_PATH_UPDATE_FAILED + ": " + e.getMessage()));
		}
	}
	
	
	/**
	 * 	
	 * @param cfg
	 * @param legacyPaths
	 * @param configFile
	 * @return
	 * @throws Exception
	 */
	private ModelPathConfig ensureModelPathConfigInitialized(ModelPathConfig cfg, List<ModelPathDataStruct> legacyPaths, Path configFile)
			throws Exception {
		if (cfg == null) {
			cfg = new ModelPathConfig();
		}
		List<ModelPathDataStruct> items = cfg.getItems();
		boolean empty = items == null || items.isEmpty();
		if (!empty) {
			return cfg;
		}
		if (legacyPaths == null || legacyPaths.isEmpty()) {
			return cfg;
		}
		List<ModelPathDataStruct> migrated = new ArrayList<>();
		for (ModelPathDataStruct p : legacyPaths) {
			if (p == null || p.getPath().trim().isEmpty()) {
				continue;
			}
			String normalized = p.getPath().trim();
			boolean exists = false;
			for (ModelPathDataStruct i : migrated) {
				if (i != null && i.getPath() != null && this.isSamePath(normalized, i.getPath().trim())) {
					exists = true;
					break;
				}
			}
			if (exists) {
				continue;
			}
			ModelPathDataStruct item = new ModelPathDataStruct();
			item.setPath(normalized);
			try {
				item.setName(java.nio.file.Paths.get(normalized).getFileName().toString());
			} catch (Exception ex) {
				item.setName(normalized);
			}
			migrated.add(item);
		}
		cfg.setItems(migrated);
		LlamaServer.writeModelPathConfig(configFile, cfg);
		return cfg;
	}
	
	/**
	 * 返回全部的模型路径
	 *
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleModelPathList(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			String nodeId = ParamTool.getQueryParam(request.uri()).get("nodeId");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				this.proxyGetRemote(ctx, nodeId, "api/model/path/list");
				return;
			}
			LlamaServerManager manager = LlamaServerManager.getInstance();
			Path configFile = LlamaServer.getModelPathConfigPath();
			ModelPathConfig cfg = LlamaServer.readModelPathConfig(configFile);
			cfg = this.ensureModelPathConfigInitialized(cfg, manager.getModelPaths(), configFile);
			List<ModelPathDataStruct> items = cfg.getItems();
			Map<String, Object> data = new HashMap<>();
			data.put("items", items);
			data.put("count", items == null ? 0 : items.size());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("获取模型路径列表时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_PATH_LIST_FAILED + ": " + e.getMessage()));
		}
	}


	/**
	 * 	
	 * @param manager
	 * @param cfg
	 * @param refreshModelList
	 */
	private void syncModelPathsToRuntime(LlamaServerManager manager, ModelPathConfig cfg, boolean refreshModelList) {
		if (manager == null || cfg == null) {
			return;
		}
		List<ModelPathDataStruct> items = cfg.getItems();
		List<ModelPathDataStruct> paths = new ArrayList<>();
		if (items != null) {
			for (ModelPathDataStruct i : items) {
				if (i == null || i.getPath() == null || i.getPath().trim().isEmpty()) {
					continue;
				}
				String p = i.getPath().trim();
				Path candidate = Paths.get(p).toAbsolutePath().normalize();
				if (this.isVolumeRoot(candidate)) {
					continue;
				}
				boolean conflict = false;
				for (ModelPathDataStruct e : paths) {
					String ep = e.getPath().trim();
					Path existingPath = Paths.get(ep).toAbsolutePath().normalize();
					if (this.isSamePath(p, ep)) {
						conflict = true;
						break;
					}
					try {
						if (candidate.startsWith(existingPath) || existingPath.startsWith(candidate)) {
							conflict = true;
							break;
						}
					} catch (Exception ignore) {
					}
				}
				if (!conflict) {
					// 也检查默认模型路径
					Path defaultModelsPath = Paths.get(LlamaServer.getDefaultModelsPath()).toAbsolutePath().normalize();
					try {
						if (candidate.equals(defaultModelsPath) || candidate.startsWith(defaultModelsPath) || defaultModelsPath.startsWith(candidate)) {
							conflict = true;
						}
					} catch (Exception ignore) {
					}
				}
				if (!conflict) {
					paths.add(i);
				}
			}
		}
manager.setModelPaths(paths);
		if (refreshModelList) {
			try {
				manager.listModel(true);
			} catch (Exception e) {
				logger.info("刷新模型列表失败: {}", e.getMessage());
			}
		}
		manager.buildAutoLoadModelCache();
	}
	
	/**
	 * 	
	 * @param a
	 * @param b
	 * @return
	 */
	private boolean isSamePath(String a, String b) {
		String aa = this.normalizePathForCompare(a);
		String bb = this.normalizePathForCompare(b);
		if (aa.isEmpty() || bb.isEmpty()) return false;
		String os = System.getProperty("os.name");
		if (os != null && os.toLowerCase(Locale.ROOT).contains("win")) return aa.equalsIgnoreCase(bb);
		return aa.equals(bb);
	}
	
	/**
	 * 	
	 * @param p
	 * @return
	 */
	private String normalizePathForCompare(String p) {
		if (p == null) return "";
		String s = p.trim();
		if (s.isEmpty()) return "";
		String os = System.getProperty("os.name");
		boolean win = os != null && os.toLowerCase(Locale.ROOT).contains("win");
		if (win) {
			s = s.replace('/', '\\');
		}

		while (s.length() > 1) {
			char last = s.charAt(s.length() - 1);
			if (last != '\\' && last != '/') break;
			if (win && s.length() == 3 && Character.isLetter(s.charAt(0)) && s.charAt(1) == ':' && (last == '\\' || last == '/')) {
				break;
			}
			if (win && "\\\\".equals(s)) {
				break;
			}
			s = s.substring(0, s.length() - 1);
		}

		try {
			s = java.nio.file.Paths.get(s).normalize().toString();
		} catch (Exception e) {
		}
		return s;
	}
	
	private Path validateAndNormalizeDirectory(String input) {
		if (input == null) return null;
		String s = input.trim();
		if (s.isEmpty()) return null;
		Path p;
		try {
			p = Paths.get(s).toAbsolutePath().normalize();
		} catch (Exception e) {
			return null;
		}
		try {
			if (!Files.exists(p) || !Files.isDirectory(p)) return null;
		} catch (Exception e) {
			return null;
		}
		if (this.pathHasSymlink(p)) return null;
		try {
			return p.toRealPath();
		} catch (Exception e) {
			return p;
		}
	}
	
	private boolean isVolumeRoot(Path p) {
		if (p == null) return false;
		Path root = p.getRoot();
		return root != null && p.equals(root);
	}

	private boolean pathHasSymlink(Path p) {
		if (p == null) return false;
		try {
			Path abs = p.toAbsolutePath().normalize();
			Path root = abs.getRoot();
			if (root == null) {
				return Files.isSymbolicLink(abs);
			}
			Path cur = root;
			for (Path part : abs) {
				if (part == null) continue;
				cur = cur.resolve(part);
				try {
					if (Files.isSymbolicLink(cur)) return true;
				} catch (Exception ignore) {
				}
			}
			return false;
		} catch (Exception e) {
			return false;
		}
	}
}
