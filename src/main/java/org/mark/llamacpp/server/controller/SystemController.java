package org.mark.llamacpp.server.controller;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.mark.llamacpp.server.BuildInfo;
import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.NodeManager;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.service.ModelSamplingService;
import org.mark.llamacpp.server.service.ComputerService;
import org.mark.llamacpp.server.tools.GPUInfoHelper;
import org.mark.llamacpp.win.AutoStartManager;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.tools.ParamTool;
import org.mark.llamacpp.update.GitHubTagFetcherNative;
import org.mark.llamacpp.update.LetsUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;



/**
 * 	系统相关。
 */
public class SystemController implements BaseController {

	private static final Logger logger = LoggerFactory.getLogger(SystemController.class);

	// i18n keys — returned to frontend for translation
	private static final String I18N_METHOD_GET_ONLY = "common.method.get.only";
	private static final String I18N_METHOD_POST_ONLY = "common.method.post.only";
	// update
	private static final String I18N_NOT_OFFICIAL_BUILD = "update.not.official.build";
	private static final String I18N_URL_MISSING = "update.url.missing";
	private static final String I18N_GITHUB_ONLY = "update.github.only";
	private static final String I18N_DOWNLOAD_FAILED = "update.download.failed";
	private static final String I18N_CHECK_FAILED = "update.check.failed";
	private static final String I18N_APPLY_FAILED = "update.apply.failed";
	private static final String I18N_STATUS_FAILED = "update.status.failed";
	private static final String I18N_CANCEL_FAILED = "update.cancel.failed";
	// dir browse
	private static final String I18N_PATH_INVALID = "api.error.path.invalid";
	private static final String I18N_DIR_NOT_FOUND = "api.error.dir.notfound";
	private static final String I18N_NOT_DIR = "api.error.not.dir";
	private static final String I18N_SYMLINK_NOT_ALLOWED = "api.error.symlink.not.allowed";
	private static final String I18N_DIR_BROWSE_FAILED = "api.error.dir.browse.failed";
	// body
	private static final String I18N_BODY_EMPTY = "api.error.body.empty";
	private static final String I18N_BODY_NOT_JSON = "api.error.body.not.json";
	// param validation
	private static final String I18N_PARAM_ENABLE_REQUIRED = "api.error.param.enable.required";
	//private static final String I18N_PARAM_PORT_INVALID = "api.error.param.port.invalid";
	private static final String I18N_PARAM_WEB_PORT_INVALID = "api.error.param.web.port.invalid";
	private static final String I18N_PARAM_MODEL_ID_MISSING = "api.error.param.modelId.missing";
	private static final String I18N_PARAM_MODEL_ID_MISSING_REQUIRED = "api.error.param.modelId.required";
	private static final String I18N_PARAM_LLAMA_BIN_REQUIRED = "api.error.param.llamaBinPath.required";
	private static final String I18N_PARAM_SAMPLING_CONFIG_MISSING = "api.error.param.samplingConfigName.missing";
	private static final String I18N_PARAM_SAVABLE_MISSING = "api.error.param.savable.missing";
	private static final String I18N_PARAM_LAUNCH_REQUIRED = "api.error.param.launch.required";
	// operation results
	private static final String I18N_COMPAT_STATUS_FAILED = "api.error.compat.status.failed";
	private static final String I18N_VERSION_INFO_FAILED = "api.error.version.info.failed";
	private static final String I18N_PID_FAILED = "api.error.pid.failed";
	private static final String I18N_MCP_TOGGLE_FAILED = "api.error.mcp.toggle.failed";
	private static final String I18N_SETTINGS_GET_FAILED = "api.error.settings.get.failed";
	private static final String I18N_SETTINGS_SAVE_FAILED = "api.error.settings.save.failed";
	private static final String I18N_SAMPLING_SAVE_FAILED = "api.error.sampling.save.failed";
	private static final String I18N_SAMPLING_QUERY_FAILED = "api.error.sampling.query.failed";
	private static final String I18N_SAMPLING_CONFIG_LIST_FAILED = "api.error.sampling.config.list.failed";
	private static final String I18N_SAMPLING_CONFIG_SAVE_FAILED = "api.error.sampling.config.save.failed";
	private static final String I18N_SAMPLING_CONFIG_DELETE_FAILED = "api.error.sampling.config.delete.failed";
	private static final String I18N_COMPUTER_INFO_FAILED = "api.error.computer.info.failed";
	private static final String I18N_SHUTDOWN_FAILED = "api.error.shutdown.failed";
	private static final String I18N_SYSLOG_READ_FAILED = "api.error.syslog.read.failed";
	private static final String I18N_SYSLOG_LIST_FAILED = "api.error.syslog.list.failed";
	private static final String I18N_DEVICE_LIST_FAILED = "api.error.device.list.failed";
	private static final String I18N_DEVICE_REMOTE_LIST_FAILED = "api.error.device.remote.list.failed";
	private static final String I18N_VRAM_ESTIMATE_FAILED = "api.error.vram.estimate.failed";
	private static final String I18N_FIT_PARAMS_FAILED = "api.error.fit.params.failed";
	private static final String I18N_REMOTE_CALL_FAILED = "api.error.remote.call.failed";
	private static final String I18N_REMOTE_FS_BROWSE_FAILED = "api.error.remote.fs.browse.failed";
	private static final String I18N_STARTUP_STATUS_FAILED = "api.error.startup.status.failed";
	private static final String I18N_STARTUP_TOGGLE_FAILED = "api.error.startup.toggle.failed";
	private static final String I18N_MODEL_LOG_READ_FAILED = "api.error.model.log.read.failed";
	private static final String I18N_REMOTE_RESPONSE_FORMAT = "api.error.remote.response.format";
	private static final String I18N_WINDOWS_ONLY = "api.error.windows.only";
	
	/**
	 * 	依旧请求入口。
	 */
	@Override
	public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 停止服务API
		if (uri.equals("/api/shutdown")) {
			this.handleShutdownRequest(ctx, request);
			return true;
		}
		// 控制台
		if (uri.equals("/api/sys/console")) {
			this.handleSysConsoleRequest(ctx, request);
			return true;
		}
		// 返回有日志文件的模型列表
		if (uri.equals("/api/sys/log-models")) {
			this.handleLogModelsRequest(ctx, request);
			return true;
		}
		// 返回指定模型的日志文件内容
		if (uri.equals("/api/sys/model-log")) {
			this.handleModelLogRequest(ctx, request);
			return true;
		}
		
		// 列出可用的设备，基于当前选择的llamacpp
		if (uri.equals("/api/model/device/list")) {
			this.handleDeviceListRequest(ctx, request);
			return true;
		}
		
		// 显存估算API
		if (uri.equals("/api/models/vram/estimate")) {
			this.handleVramEstimateRequest(ctx, request);
			return true;
		}
		// llama-fit-params拟合参数API
		if (uri.equals("/api/models/fit-params")) {
			this.handleFitParamsRequest(ctx, request);
			return true;
		}
		// 暂时用不上，注释掉，转移到独立项目去
//		// gguf-mem显存估算API
//		if (uri.equals("/api/models/gguf-mem/estimate")) {
//			this.handleGgufMemEstimateRequest(ctx, request);
//			return true;
//		}
		// 启用、禁用内置MCP服务
		if (uri.equals("/api/sys/mcp")) {
			this.handleMcpEnableRequest(ctx, request);
			return true;
		}
		// 获取兼容服务状态
		if (uri.equals("/api/sys/compat/status")) {
			this.handleCompatStatusRequest(ctx, request);
			return true;
		}
     // 获取构建版本信息
		if (uri.equals("/api/sys/version")) {
			this.handleVersionInfoRequest(ctx, request);
			return true;
		}
		// 获取进程PID
		if (uri.equals("/api/sys/pid")) {
			this.handlePidRequest(ctx, request);
			return true;
		}
//		// 获取GPU服务信息（初始化快照）
//		if (uri.equals("/api/sys/gpu/info")) {
//			this.handleGpuInfoRequest(ctx, request);
//			return true;
//		}
//		// 查询GPU实时状态（弃用）
//		if (uri.equals("/api/sys/gpu/status")) {
//			this.handleGpuStatusRequest(ctx, request);
//			return true;
//		}
		// 获取系统设置
		if (uri.equals("/api/sys/setting") && request.method() == HttpMethod.GET) {
			this.handleSysSettingGetRequest(ctx, request);
			return true;
		}
		// 验证 API Key（登录页用，只返回 200/401）
		if (uri.equals("/api/auth/verify") && request.method() == HttpMethod.GET) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(null));
			return true;
		}
		// 保存系统设置
		if (uri.equals("/api/sys/setting")) {
			this.handleSysSettingRequest(ctx, request);
			return true;
		}
		// 获取指定模型的采样配置
		if (uri.equals("/api/sys/model/sampling/setting/get")) {
			this.handleModelSamplingSettingGetRequest(ctx, request);
			return true;
		}
		
		if (uri.equals("/api/sys/model/sampling/setting/add")) {
			this.handleModelSamplingSettingAddRequest(ctx, request);
			return true;
		}
		// 获取的采样配置
		if (uri.equals("/api/sys/model/sampling/setting/list")) {
			this.handleModelSamplingSettingListRequest(ctx, request);
			return true;
		}
		// 删除指定的采样
		if (uri.equals("/api/sys/model/sampling/setting/delete")) {
			this.handleModelSamplingSettingDeleteRequest(ctx, request);
			return true;
		}
		// 设置指定模型的采样配置
		if (uri.equals("/api/sys/model/sampling/setting/set")) {
			this.handleModelSamplingSettingRequest(ctx, request);
			return true;
		}
		
		// 文件系统：目录浏览
		if (uri.equals("/api/sys/fs/list")) {
			this.handleFsListRequest(ctx, request);
			return true;
		}

		// 清空静态文件缓存
		if (uri.equals("/api/sys/static/cache/clear")) {
			this.handleClearStaticCacheRequest(ctx, request);
			return true;
		}

		// 检查更新
		if (uri.equals("/api/sys/update/check")) {
			this.handleUpdateCheckRequest(ctx, request);
			return true;
		}
		// 下载更新
		if (uri.equals("/api/sys/update/download")) {
			this.handleUpdateDownloadRequest(ctx, request);
			return true;
		}
		// 应用更新
		if (uri.equals("/api/sys/update/apply")) {
			this.handleUpdateApplyRequest(ctx, request);
			return true;
		}
		// 更新状态查询
		if (uri.equals("/api/sys/update/status")) {
			this.handleUpdateStatusRequest(ctx, request);
			return true;
		}
		// 取消下载
		if (uri.equals("/api/sys/update/cancel")) {
			this.handleUpdateCancelRequest(ctx, request);
			return true;
		}

       // 获取计算机信息（gpu-info）
		if (uri.equals("/api/sys/sysinfo")) {
			this.handleSysInfoRequest(ctx, request);
			return true;
		}

		// 开机自启
		if (uri.equals("/api/sys/autostart")) {
			this.handleAutoStartRequest(ctx, request);
			return true;
		}

		return false;
	}
	
	/**
	 * 	文件系统：目录浏览
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleFsListRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			Map<String, String> params = ParamTool.getQueryParam(request.uri());
			String in = params.get("path");
			if (in != null) in = in.trim();

			String nodeId = params.get("nodeId");
			if (nodeId != null) nodeId = nodeId.trim();
			if (nodeId != null && !nodeId.isEmpty() && !"local".equals(nodeId)) {
				this.handleFsListRemote(ctx, nodeId, request);
				return;
			}

			String rawFilter = params.get("filter");
			if (rawFilter != null) rawFilter = rawFilter.trim();
			final String filter = (rawFilter != null && !rawFilter.isEmpty()) ? rawFilter.toLowerCase() : null;
			String rawExtensions = params.get("extensions");
			boolean rootsMode = this.parseBooleanParam(params.get("roots"));
			boolean dirOnly = this.parseBooleanParam(params.get("dirOnly"));
			boolean fileOnly = this.parseBooleanParam(params.get("fileOnly"));
			if (dirOnly && fileOnly) {
				dirOnly = false;
				fileOnly = false;
			}
			long minSize = this.parseLongParam(params.get("minSize"), 0L);
			long maxSize = this.parseLongParam(params.get("maxSize"), 0L);
			if (minSize < 0L) minSize = 0L;
			if (maxSize < 0L) maxSize = 0L;
			if (maxSize > 0L && minSize > maxSize) {
				long tmp = minSize;
				minSize = maxSize;
				maxSize = tmp;
			}
			List<String> extensions = this.parseExtensions(rawExtensions);
			if (extensions.isEmpty() && filter != null) {
				extensions = this.parseExtensions(filter);
			}
			final List<String> activeExtensions = extensions;
			final boolean hasExtensionFilter = !activeExtensions.isEmpty();
			String sortBy = this.normalizeFsSortBy(params.get("sortBy"));
			String sortOrder = this.normalizeFsSortOrder(params.get("sortOrder"));

			int fileLimit = hasExtensionFilter ? 500 : 50;
			int dirLimit = 500;
			
			Map<String, Object> data = new HashMap<>();

			if (rootsMode) {
				List<Map<String, Object>> roots = new ArrayList<>();
				File[] rootFiles = File.listRoots();
				if (rootFiles != null) {
					for (File root : rootFiles) {
						if (root == null) continue;
						String path = root.getAbsolutePath();
						Map<String, Object> item = this.createFsItem(path, path, true, 0L, 0L, false);
						roots.add(item);
					}
				}
				roots.sort(this.buildFsItemComparator(sortBy, sortOrder));
				data.put("path", null);
				data.put("parent", null);
				data.put("items", roots);
				data.put("directories", new ArrayList<>(roots));
				data.put("files", new ArrayList<>());
				data.put("truncated", false);
				data.put("truncatedDirs", false);
				data.put("truncatedFiles", false);
				data.put("filter", hasExtensionFilter ? rawExtensions : filter);
				data.put("mode", "roots");
				LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
				return;
			}
			
			if (in == null || in.isEmpty()) {
				in = System.getProperty("user.dir");
			}
			
			Path raw;
			try {
				raw = Paths.get(in);
			} catch (Exception e) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PATH_INVALID));
				return;
			}
			Path abs = raw.toAbsolutePath().normalize();
			if (!Files.exists(abs)) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_DIR_NOT_FOUND));
				return;
			}
			if (!Files.isDirectory(abs)) {
		LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_NOT_DIR));
			return;
		}
		if (this.pathHasSymlink(abs)) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_SYMLINK_NOT_ALLOWED));
				return;
			}
			
			Path base;
			try {
				base = abs.toRealPath();
			} catch (Exception e) {
				base = abs;
			}
			if (!Files.isDirectory(base)) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_NOT_DIR));
				return;
			}

			// 路径白名单校验：只允许浏览模型目录、下载目录、llamacpp目录，防止遍历整个文件系统
			{
				Path baseNorm = base.toAbsolutePath().normalize();
				Path modelsDir = Paths.get(LlamaServer.getDefaultModelsPath()).toAbsolutePath().normalize();
				Path downloadDir = Paths.get(LlamaServer.getDownloadDirectory()).toAbsolutePath().normalize();
				Path llamacppDir = Paths.get(LlamaServer.getDefaultLlamaCppPath()).toAbsolutePath().normalize();
				if (!baseNorm.startsWith(modelsDir) && !baseNorm.startsWith(downloadDir) && !baseNorm.startsWith(llamacppDir)) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PATH_INVALID));
					return;
				}
			}

			final List<Map<String, Object>> dirs = new ArrayList<>();
			final List<Map<String, Object>> files = new ArrayList<>();
			final boolean onlyDirectories = dirOnly;
			final boolean onlyFiles = fileOnly;
			final long minFileSize = minSize;
			final long maxFileSize = maxSize;
			boolean truncatedDirs = false;
			boolean truncatedFiles = false;
			
			try (Stream<Path> stream = Files.list(base)) {
				stream.forEach(p -> {
					if (p == null) return;
					try {
						String name = p.getFileName() == null ? p.toString() : p.getFileName().toString();
						if (Files.isDirectory(p)) {
							if (onlyFiles) return;
							Map<String, Object> item = createFsItem(name, p.toAbsolutePath().normalize().toString(), true, 0L, 0L, false);
							dirs.add(item);
							return;
						}
						if (onlyDirectories) return;
						if (hasExtensionFilter && !matchesAnyExtension(name, activeExtensions)) {
							return;
						}
						long size = Files.size(p);
						if (size < minFileSize) return;
						if (maxFileSize > 0L && size > maxFileSize) return;
						long lastModified = 0L;
						try {
							lastModified = Files.getLastModifiedTime(p).toMillis();
						} catch (Exception ignore) {
						}
						Map<String, Object> item = createFsItem(name, p.toAbsolutePath().normalize().toString(), false, size, lastModified, false);
						files.add(item);
					} catch (Exception ignore) {
					}
				});
			}
			
			dirs.sort(Comparator.comparing(o -> String.valueOf(o.getOrDefault("name", "")), String.CASE_INSENSITIVE_ORDER));
			files.sort(Comparator.comparing(o -> String.valueOf(o.getOrDefault("name", "")), String.CASE_INSENSITIVE_ORDER));
			
			List<Map<String, Object>> outDirs = dirs;
			List<Map<String, Object>> outFiles = files;
			
			if (outDirs.size() > dirLimit) {
				outDirs = new ArrayList<>(outDirs.subList(0, dirLimit));
				truncatedDirs = true;
			}
			if (outFiles.size() > fileLimit) {
				outFiles = new ArrayList<>(outFiles.subList(0, fileLimit));
				truncatedFiles = true;
			}
			
			Path parent = base.getParent();
			List<Map<String, Object>> items = new ArrayList<>();
			if (parent != null) {
				items.add(this.createFsItem("..", parent.toString(), true, 0L, 0L, true));
			}
			items.addAll(outDirs);
			items.addAll(outFiles);
			items.sort(this.buildFsItemComparator(sortBy, sortOrder));
			data.put("path", base.toString());
			data.put("parent", parent == null ? null : parent.toString());
			data.put("items", items);
			data.put("directories", outDirs);
			data.put("files", outFiles);
			data.put("truncated", truncatedDirs || truncatedFiles);
			data.put("truncatedDirs", truncatedDirs);
			data.put("truncatedFiles", truncatedFiles);
			data.put("filter", hasExtensionFilter ? rawExtensions : filter);
			data.put("mode", "directory");
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("处理目录浏览请求时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_DIR_BROWSE_FAILED + ": " + e.getMessage()));
		}
	}

	private boolean parseBooleanParam(String value) {
		if (value == null) return false;
		String v = value.trim();
		return "1".equals(v) || "true".equalsIgnoreCase(v) || "yes".equalsIgnoreCase(v) || "on".equalsIgnoreCase(v);
	}

	private long parseLongParam(String value, long defaultValue) {
		if (value == null) return defaultValue;
		try {
			return Long.parseLong(value.trim());
		} catch (Exception e) {
			return defaultValue;
		}
	}

	private List<String> parseExtensions(String rawExtensions) {
		List<String> list = new ArrayList<>();
		if (rawExtensions == null) return list;
		String[] parts = rawExtensions.split(",");
		Set<String> seen = new LinkedHashSet<>();
		for (String part : parts) {
			String ext = this.normalizeExtension(part);
			if (ext == null || ext.isEmpty()) continue;
			if (seen.add(ext)) {
				list.add(ext);
			}
		}
		return list;
	}

	private String normalizeExtension(String rawExtension) {
		if (rawExtension == null) return null;
		String ext = rawExtension.trim().toLowerCase();
		if (ext.isEmpty()) return null;
		if (!ext.startsWith(".")) {
			ext = "." + ext;
		}
		return ext;
	}

	private boolean matchesAnyExtension(String name, List<String> extensions) {
		if (name == null || extensions == null || extensions.isEmpty()) return true;
		String lowerName = name.toLowerCase();
		for (String ext : extensions) {
			if (ext != null && !ext.isEmpty() && lowerName.endsWith(ext)) {
				return true;
			}
		}
		return false;
	}

	private String normalizeFsSortBy(String sortBy) {
		if ("size".equalsIgnoreCase(sortBy)) return "size";
		if ("date".equalsIgnoreCase(sortBy)) return "date";
		return "name";
	}

	private String normalizeFsSortOrder(String sortOrder) {
		return "desc".equalsIgnoreCase(sortOrder) ? "desc" : "asc";
	}

	private Map<String, Object> createFsItem(String name, String path, boolean isDirectory, long size, long lastModified, boolean isParent) {
		Map<String, Object> item = new HashMap<>();
		item.put("name", name);
		item.put("path", path);
		item.put("isDirectory", isDirectory);
		item.put("size", size);
		item.put("lastModified", lastModified);
		if (isParent) {
			item.put("isParent", true);
		}
		return item;
	}

	private Comparator<Map<String, Object>> buildFsItemComparator(String sortBy, String sortOrder) {
		Comparator<Map<String, Object>> comparator = Comparator.comparingLong(o -> Boolean.TRUE.equals(o.get("isParent")) ? 0L : 1L);
		Comparator<Map<String, Object>> fieldComparator;
		if ("size".equals(sortBy)) {
			fieldComparator = Comparator.comparingLong(o -> this.getFsItemLong(o, "size"));
		} else if ("date".equals(sortBy)) {
			fieldComparator = Comparator.comparingLong(o -> this.getFsItemLong(o, "lastModified"));
		} else {
			fieldComparator = Comparator.comparing(o -> String.valueOf(o.getOrDefault("name", "")), String.CASE_INSENSITIVE_ORDER);
		}
		if ("desc".equals(sortOrder)) {
			fieldComparator = fieldComparator.reversed();
		}
		return comparator
			.thenComparing(fieldComparator)
			.thenComparing(o -> String.valueOf(o.getOrDefault("name", "")), String.CASE_INSENSITIVE_ORDER);
	}

	private long getFsItemLong(Map<String, Object> item, String key) {
		if (item == null || key == null) return 0L;
		Object value = item.get(key);
		if (value instanceof Number) {
			return ((Number) value).longValue();
		}
		try {
			return Long.parseLong(String.valueOf(value));
		} catch (Exception e) {
			return 0L;
		}
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
					if (Files.isSymbolicLink(cur)) {
						return true;
					}
				} catch (Exception ignore) {
				}
			}
			return false;
		} catch (Exception e) {
			return false;
		}
	}
	
//	/**
//	 * 	获取GPU服务信息（初始化时的快照）
//	 * @param ctx
//	 * @param request
//	 * @throws RequestMethodException
//	 */
//	private void handleGpuInfoRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
//		if (request.method() == HttpMethod.OPTIONS) {
//			LlamaServer.sendCorsResponse(ctx);
//			return;
//		}
//		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
//		try {
//			JsonObject info = GpuService.getInstance().getServiceInfo();
//			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(info));
//		} catch (Exception e) {
//			logger.info("获取GPU信息时发生错误", e);
//			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取GPU信息失败: " + e.getMessage()));
//		}
//	}
//
//	/**
//	 * 	查询GPU实时状态
//	 * @param ctx
//	 * @param request
//	 * @throws RequestMethodException
//	 */
//	private void handleGpuStatusRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
//		if (request.method() == HttpMethod.OPTIONS) {
//			LlamaServer.sendCorsResponse(ctx);
//			return;
//		}
//		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
//		try {
//			Map<String, String> params = ParamTool.getQueryParam(request.uri());
//			String nodeId = params.get("nodeId");
//			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
//				NodeManager.HttpResult result = NodeManager.getInstance().callRemoteApi(
//						nodeId, "GET", "api/sys/gpu/status", null);
//				if (result.isSuccess()) {
//					JsonObject remoteResp = JsonUtil.fromJson(result.getBody(), JsonObject.class);
//					if (remoteResp != null && remoteResp.has("data")) {
//						LlamaServer.sendJsonResponse(ctx, ApiResponse.success(remoteResp.get("data")));
//					} else {
//						LlamaServer.sendJsonResponse(ctx, ApiResponse.error("远程节点响应格式错误"));
//					}
//				} else {
//					LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_CALL_FAILED + ": code=" + result.getStatusCode()));
//				}
//				return;
//			}
//			JsonObject status = GpuService.getInstance().queryGpuStatus();
//			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(status));
//		} catch (Exception e) {
//			logger.info("查询GPU状态时发生错误", e);
//			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("查询GPU状态失败: " + e.getMessage()));
//		}
//	}

	/**
	 * 	获取兼容服务状态（MCP服务、请求日志）
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleCompatStatusRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			Map<String, Object> data = new HashMap<>();

			Map<String, Object> mcpServerData = new HashMap<>();
			mcpServerData.put("enabled", LlamaServer.isMcpServerEnabled());
			mcpServerData.put("running", LlamaServer.isMcpServerRunning());
			mcpServerData.put("port", LlamaServer.getMcpServerPort());
			data.put("mcpServer", mcpServerData);

			Map<String, Object> requestLogData = new HashMap<>();
			requestLogData.put("logRequestUrl", LlamaServer.isLogRequestUrlEnabled());
			requestLogData.put("logRequestHeader", LlamaServer.isLogRequestHeaderEnabled());
			requestLogData.put("logRequestBody", LlamaServer.isLogRequestBodyEnabled());
			data.put("requestLog", requestLogData);
			
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("获取兼容服务状态时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_COMPAT_STATUS_FAILED + ": " + e.getMessage()));
		}
	}

    private void handleVersionInfoRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			Map<String, Object> data = new HashMap<>();
			data.put("tag", LlamaServer.getTag());
			data.put("version", LlamaServer.getVersion());
			data.put("createdTime", LlamaServer.getCreatedTime());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("获取版本信息时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_VERSION_INFO_FAILED + ": " + e.getMessage()));
		}
	}

	/**
	 * 获取进程PID
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handlePidRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			Map<String, Object> data = new HashMap<>();
			data.put("pid", LlamaServer.getPID());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("获取PID时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PID_FAILED + ": " + e.getMessage()));
		}
	}

	/**
	 * 	启用、禁用内置MCP服务监听
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleMcpEnableRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		try {
			JsonObject obj = parseJsonBody(ctx, request);
			if (obj == null) {
				return;
			}
			if (!obj.has("enable") || obj.get("enable") == null || obj.get("enable").isJsonNull()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_ENABLE_REQUIRED));
				return;
			}

			boolean enable = ParamTool.parseJsonBoolean(obj, "enable", false);
			LlamaServer.setMcpServerEnabled(enable);

			Map<String, Object> data = new HashMap<>();
			data.put("enable", enable);
			data.put("running", LlamaServer.isMcpServerRunning());
			data.put("port", LlamaServer.getMcpServerPort());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("处理MCP服务启停请求时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MCP_TOGGLE_FAILED + ": " + e.getMessage()));
		}
	}
	
	/**
	 * 	获取系统设置
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleSysSettingGetRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		try {
			Map<String, Object> data = new HashMap<>();
			
			Map<String, Object> server = new HashMap<>();
			server.put("webPort", LlamaServer.getWebPort());
			data.put("server", server);
			
			Map<String, Object> download = new HashMap<>();
			download.put("directory", LlamaServer.getDownloadDirectory());
			data.put("download", download);
			
			Map<String, Object> security = new HashMap<>();
			security.put("apiKeyEnabled", LlamaServer.isApiKeyValidationEnabled());
			security.put("apiKeyConfigured", LlamaServer.getApiKey() != null && !LlamaServer.getApiKey().isBlank());
			data.put("security", security);
			
			Map<String, Object> compat = new HashMap<>();
			Map<String, Object> mcpServer = new HashMap<>();
			mcpServer.put("enabled", LlamaServer.isMcpServerEnabled());
			compat.put("mcpServer", mcpServer);
			
			data.put("compat", compat);
			
			Map<String, Object> logging = new HashMap<>();
			logging.put("logRequestUrl", LlamaServer.isLogRequestUrlEnabled());
			logging.put("logRequestHeader", LlamaServer.isLogRequestHeaderEnabled());
			logging.put("logRequestBody", LlamaServer.isLogRequestBodyEnabled());
			data.put("logging", logging);
			
Map<String, Object> https = new HashMap<>();
		https.put("enabled", LlamaServer.isHttpsEnabled());
		https.put("keystorePath", LlamaServer.getHttpsCertPath());
		https.put("keystoreConfigured", LlamaServer.getHttpsPassword() != null && !LlamaServer.getHttpsPassword().isBlank());
		data.put("https", https);

		String nodeRole = LlamaServer.getNodeRole();
		data.put("nodeRole", nodeRole != null ? nodeRole : "slave");

		LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("获取系统设置时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_SETTINGS_GET_FAILED + ": " + e.getMessage()));
		}
	}
	
	/**
	 * 	保存系统设置
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleSysSettingRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		try {
			JsonObject obj = parseJsonBody(ctx, request);
			if (obj == null) {
				return;
			}

			Boolean logRequestUrl = firstBoolean(obj, "LlamaServer.logRequestUrl", "logRequestUrl", "log_request_url");
			Boolean logRequestHeader = firstBoolean(obj, "LlamaServer.logRequestHeader", "logRequestHeader", "log_request_header");
			Boolean logRequestBody = firstBoolean(obj, "LlamaServer.logRequestBody", "logRequestBody", "log_request_body");
			
Integer webPort = firstPort(obj, "webPort", "web_port");
		Boolean apiKeyEnabled = firstBoolean(obj, "apiKeyEnabled", "api_key_enabled");
		String apiKey = JsonUtil.getJsonString(obj, "apiKey", null);
		Boolean httpsEnabled = firstBoolean(obj, "httpsEnabled", "https_enabled");
		String httpsCertPath = JsonUtil.getJsonString(obj, "httpsCertPath", null);
		String httpsPassword = JsonUtil.getJsonString(obj, "httpsPassword", null);
		String downloadDirectory = JsonUtil.getJsonString(obj, "downloadDirectory", null);
		String nodeRole = JsonUtil.getJsonString(obj, "nodeRole", null);

		if (logRequestUrl == null && logRequestHeader == null && logRequestBody == null
			&& webPort == null && apiKeyEnabled == null && apiKey == null
			&& httpsEnabled == null && httpsCertPath == null && httpsPassword == null
			&& downloadDirectory == null
			&& nodeRole == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_SAVABLE_MISSING));
				return;
			}

			if (logRequestUrl != null || logRequestHeader != null || logRequestBody != null) {
				LlamaServer.updateRequestLogConfig(logRequestUrl, logRequestHeader, logRequestBody);
			}
			
			if (webPort != null) {
				if (webPort != null && !isValidPort(webPort.intValue())) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_WEB_PORT_INVALID));
					return;
				}
				LlamaServer.updateServerPorts(webPort);
			}
			
			if (apiKeyEnabled != null || apiKey != null) {
				LlamaServer.updateApiKeyConfig(apiKeyEnabled != null ? apiKeyEnabled : LlamaServer.isApiKeyValidationEnabled(), apiKey);
			}
			
			if (httpsEnabled != null || httpsCertPath != null || httpsPassword != null) {
				LlamaServer.updateHttpsConfig(httpsEnabled, httpsCertPath, httpsPassword);
			}
			
if (downloadDirectory != null && !downloadDirectory.isEmpty()) {
			LlamaServer.setDownloadDirectory(downloadDirectory);
		}

		if (nodeRole != null) {
			LlamaServer.updateNodeRole(nodeRole);
		}

			Map<String, Object> data = new HashMap<>();
			Map<String, Object> requestLog = new HashMap<>();
			requestLog.put("logRequestUrl", LlamaServer.isLogRequestUrlEnabled());
			requestLog.put("logRequestHeader", LlamaServer.isLogRequestHeaderEnabled());
			requestLog.put("logRequestBody", LlamaServer.isLogRequestBodyEnabled());
			data.put("requestLog", requestLog);

			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("处理系统设置请求时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_SETTINGS_SAVE_FAILED + ": " + e.getMessage()));
		}
	}

	private void handleModelSamplingSettingRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		try {
			JsonObject obj = parseJsonBody(ctx, request);
			if (obj == null) {
				return;
			}

			String nodeId = JsonUtil.getJsonString(obj, "nodeId", "");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				obj.remove("nodeId");
				NodeManager.HttpResult result = NodeManager.getInstance().callRemoteApi(
						nodeId, "POST", "api/sys/model/sampling/setting/set", obj);
				if (result.isSuccess()) {
					NodeManager.writeHttpResultToChannel(ctx, result, "[采样配置远程]");
				} else {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_CALL_FAILED + ": code=" + result.getStatusCode()));
				}
				return;
			}

			String modelId = JsonUtil.getJsonString(obj, "modelId", null);
			modelId = modelId == null ? "" : modelId.trim();
			if (modelId.isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_MODEL_ID_MISSING));
				return;
			}
			String samplingConfigName = JsonUtil.getJsonStringAny(obj, "", "samplingConfigName", "configName");

			Path configPath = Paths.get("config", "model-sampling-settings.json");
			JsonObject out = new JsonObject();
			if (Files.exists(configPath)) {
				String oldText = Files.readString(configPath, StandardCharsets.UTF_8);
				JsonObject oldObj = JsonUtil.fromJson(oldText, JsonObject.class);
				if (oldObj != null) {
					out = oldObj;
				}
			}

			if (samplingConfigName.isEmpty()) {
				out.remove(modelId);
			} else {
				out.addProperty(modelId, samplingConfigName);
			}
			if (!Files.exists(configPath.getParent())) {
				Files.createDirectories(configPath.getParent());
			}
			Files.write(configPath, JsonUtil.toJson(out).getBytes(StandardCharsets.UTF_8));
			ModelSamplingService.getInstance().reload();

			Map<String, Object> data = new HashMap<>();
			data.put("saved", true);
			data.put("modelId", modelId);
			data.put("samplingConfigName", samplingConfigName);
			data.put("enabled", !samplingConfigName.isEmpty());
			data.put("file", configPath.toString());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("处理模型采样设定请求时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_SAMPLING_SAVE_FAILED + ": " + e.getMessage()));
		}
	}

	private void handleModelSamplingSettingGetRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			Map<String, String> params = ParamTool.getQueryParam(request.uri());
			String modelId = params.get("modelId");
			modelId = modelId == null ? "" : modelId.trim();
			if (modelId.isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_MODEL_ID_MISSING));
				return;
			}
			
			String nodeId = params.get("nodeId");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				String path = "api/sys/model/sampling/setting/get?modelId=" + java.net.URLEncoder.encode(modelId, "UTF-8");
				NodeManager.HttpResult result = NodeManager.getInstance().callRemoteApi(
						nodeId, "GET", path, null);
				if (result.isSuccess()) {
					NodeManager.writeHttpResultToChannel(ctx, result, "[采样配置远程]");
				} else {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_CALL_FAILED + ": code=" + result.getStatusCode()));
				}
				return;
			}
			
			Path configPath = Paths.get("config", "model-sampling-settings.json");
			String samplingConfigName = "";
			if (Files.exists(configPath)) {
				String text = Files.readString(configPath, StandardCharsets.UTF_8);
				JsonObject obj = JsonUtil.fromJson(text, JsonObject.class);
				if (obj != null && obj.has(modelId) && obj.get(modelId) != null && !obj.get(modelId).isJsonNull()) {
					samplingConfigName = obj.get(modelId).getAsString();
				}
			}
			samplingConfigName = samplingConfigName == null ? "" : samplingConfigName.trim();
			Map<String, Object> data = new HashMap<>();
			data.put("modelId", modelId);
			data.put("samplingConfigName", samplingConfigName);
			data.put("configName", samplingConfigName);
			data.put("enabled", !samplingConfigName.isEmpty());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("查询模型采样设定请求时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_SAMPLING_QUERY_FAILED + ": " + e.getMessage()));
		}
	}
	
	private void handleModelSamplingSettingListRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			Map<String, String> params = ParamTool.getQueryParam(request.uri());
			String nodeId = params.get("nodeId");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				NodeManager.HttpResult result = NodeManager.getInstance().callRemoteApi(
						nodeId, "GET", "api/sys/model/sampling/setting/list", null);
				if (result.isSuccess()) {
					NodeManager.writeHttpResultToChannel(ctx, result, "[采样配置远程]");
				} else {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_CALL_FAILED + ": code=" + result.getStatusCode()));
				}
				return;
			}
			Map<String, Object> data = ModelSamplingService.getInstance().listSamplingSettings();
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("获取采样配置列表请求时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_SAMPLING_CONFIG_LIST_FAILED + ": " + e.getMessage()));
		}
	}
	
	private void handleModelSamplingSettingAddRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		try {
			JsonObject obj = parseJsonBody(ctx, request);
			if (obj == null) {
				return;
			}

			String nodeId = JsonUtil.getJsonString(obj, "nodeId", "");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				obj.remove("nodeId");
				NodeManager.HttpResult result = NodeManager.getInstance().callRemoteApi(
						nodeId, "POST", "api/sys/model/sampling/setting/add", obj);
				if (result.isSuccess()) {
					NodeManager.writeHttpResultToChannel(ctx, result, "[采样配置远程]");
				} else {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_CALL_FAILED + ": code=" + result.getStatusCode()));
				}
				return;
			}

			String samplingConfigName = JsonUtil.getJsonStringAny(obj, "", "samplingConfigName", "configName");
			if (samplingConfigName.isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_SAMPLING_CONFIG_MISSING));
				return;
			}
			JsonObject sampling = new JsonObject();
			if (obj.has("sampling") && obj.get("sampling") != null && obj.get("sampling").isJsonObject()) {
				sampling = obj.getAsJsonObject("sampling");
			} else {
				for (String k : new String[] {"temperature", "temp", "top_p", "topP", "top_k", "topK", "min_p", "minP", "presence_penalty", "presencePenalty", "repeat_penalty", "repeatPenalty", "frequency_penalty", "frequencyPenalty", "enable_thinking", "cmd"}) {
					if (obj.has(k) && obj.get(k) != null && !obj.get(k).isJsonNull()) {
						sampling.add(k, obj.get(k));
					}
				}
			}
			JsonObject savedSampling = ModelSamplingService.getInstance().upsertSamplingConfig(samplingConfigName, sampling);
			Map<String, Object> data = new HashMap<>();
			data.put("saved", true);
			data.put("samplingConfigName", samplingConfigName);
			data.put("sampling", savedSampling);
			data.put("file", Paths.get("config", "model-sampling.json").toString());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("新增或更新采样配置请求时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_SAMPLING_CONFIG_SAVE_FAILED + ": " + e.getMessage()));
		}
	}
	
	private void handleModelSamplingSettingDeleteRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		try {
			JsonObject obj = parseJsonBody(ctx, request);
			if (obj == null) {
				return;
			}

			String nodeId = JsonUtil.getJsonString(obj, "nodeId", "");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				obj.remove("nodeId");
				NodeManager.HttpResult result = NodeManager.getInstance().callRemoteApi(
						nodeId, "POST", "api/sys/model/sampling/setting/delete", obj);
				if (result.isSuccess()) {
					NodeManager.writeHttpResultToChannel(ctx, result, "[采样配置远程]");
				} else {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_CALL_FAILED + ": code=" + result.getStatusCode()));
				}
				return;
			}

			String samplingConfigName = JsonUtil.getJsonStringAny(obj, "", "samplingConfigName", "configName");
			if (samplingConfigName.isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_SAMPLING_CONFIG_MISSING));
				return;
			}
			Map<String, Object> data = ModelSamplingService.getInstance().deleteSamplingConfig(samplingConfigName);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("删除采样配置请求时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_SAMPLING_CONFIG_DELETE_FAILED + ": " + e.getMessage()));
		}
	}
	
	private JsonObject parseJsonBody(ChannelHandlerContext ctx, FullHttpRequest request) {
		return JsonUtil.parseFullHttpRequestToJsonObject(request, ctx);
	}
	
	/**
	 * 	工具
	 * @param obj
	 * @param keys
	 * @return
	 */
	private static Integer firstPort(JsonObject obj, String... keys) {
		if (obj == null || keys == null) {
			return null;
		}
		for (String k : keys) {
			Integer v = JsonUtil.getJsonInt(obj, k, null);
			if (v != null) {
				return v;
			}
		}
		return null;
	}
	
	/**
	 * 	工具
	 * @param obj
	 * @param keys
	 * @return
	 */
	private static Boolean firstBoolean(JsonObject obj, String... keys) {
		if (obj == null || keys == null) {
			return null;
		}
		for (String k : keys) {
			if (k == null || k.isEmpty() || !obj.has(k)) {
				continue;
			}
			JsonElement v = obj.get(k);
			if (v == null || v.isJsonNull()) {
				continue;
			}
			if (v.isJsonPrimitive()) {
				try {
					if (v.getAsJsonPrimitive().isBoolean()) {
						return v.getAsBoolean();
					}
					if (v.getAsJsonPrimitive().isString()) {
						String raw = v.getAsString();
						if (raw != null) {
							String s = raw.trim().toLowerCase();
							if ("true".equals(s) || "1".equals(s) || "yes".equals(s) || "on".equals(s)) {
								return true;
							}
							if ("false".equals(s) || "0".equals(s) || "no".equals(s) || "off".equals(s)) {
								return false;
							}
						}
					}
					if (v.getAsJsonPrimitive().isNumber()) {
						return v.getAsInt() != 0;
					}
				} catch (Exception e) {
				}
			}
		}
		return null;
	}
	
	/**
	 * 	检查端口的合法性。
	 * @param port
	 * @return
	 */
	private static boolean isValidPort(int port) {
		return port > 0 && port <= 65535;
	}



	/**
	 * 静态文件缓存已移除（改为文件属性 ETag + 磁盘直读），此接口保留用于前端兼容
	 */
	private void handleClearStaticCacheRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		LlamaServer.sendJsonResponse(ctx, ApiResponse.success());
	}

	/**
	 * 	检查更新
	 */
	private void handleUpdateCheckRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		this.assertRequestMethod(request.method() != HttpMethod.GET, I18N_METHOD_GET_ONLY);
		try {
			GitHubTagFetcherNative fetcher = new GitHubTagFetcherNative();
			GitHubTagFetcherNative.CheckResult result = fetcher.check();

			Map<String, Object> data = new HashMap<>();
			data.put("currentTag", GitHubTagFetcherNative.getCurrentTag());
			data.put("hasUpdate", result.isHasUpdate());
			if (result.isSuccess() && result.getRelease() != null) {
				data.put("release", result.getRelease());
			}
			if (!result.isSuccess()) {
				data.put("error", result.getError());
			}
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("检查更新时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_CHECK_FAILED));
		}
	}
	
	/**
	 * 下载更新包
	 */
	private void handleUpdateDownloadRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		this.assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);
		try {
			if (!isOfficialBuild()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_NOT_OFFICIAL_BUILD));
				return;
			}
			JsonObject obj = parseJsonBody(ctx, request);
			if (obj == null) {
				return;
			}
			String url = JsonUtil.getJsonString(obj, "url", null);
			String version = JsonUtil.getJsonString(obj, "version", null);
			if (url == null || url.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_URL_MISSING));
				return;
			}
			if (!isGitHubReleaseUrl(url.trim())) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_GITHUB_ONLY));
				return;
			}
			Map<String, Object> data = LetsUpdate.getInstance().download(url.trim(), version);
			boolean success = (Boolean) data.getOrDefault("success", false);
			if (success) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
			} else {
				String error = (String) data.getOrDefault("error", I18N_DOWNLOAD_FAILED);
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(error));
			}
		} catch (Exception e) {
			logger.info("下载更新包时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_DOWNLOAD_FAILED));
		}
	}

	/**
	 *  简单校验 URL 是否为 GitHub Release 资源下载链接。<br>
	 * 	https://github.com/IIIIIllllIIIIIlllll/llama.cpp-hub/releases/download/
	 */
	private boolean isGitHubReleaseUrl(String url) {
		return url.contains("://github.com");
	}

	/**
	 * 应用更新包
	 */
	private void handleUpdateApplyRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		this.assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);
		try {
			if (!isOfficialBuild()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_NOT_OFFICIAL_BUILD));
				return;
			}
			File zip = new File(System.getProperty("user.dir"), "cache" + File.separator + "update.zip");
			Map<String, Object> data = LetsUpdate.getInstance().doUpdate(zip);
			boolean success = (Boolean) data.getOrDefault("success", false);
			if (success) {
				String message = (String) data.getOrDefault("message", LetsUpdate.I18N_APPLY_SUCCESS);
				Map<String, Object> respData = new HashMap<>();
				respData.put("success", true);
				respData.put("message", message);
				LlamaServer.sendJsonResponse(ctx, ApiResponse.success(respData));
			} else {
				String error = (String) data.getOrDefault("error", I18N_APPLY_FAILED);
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(error));
			}
		} catch (Exception e) {
			logger.info("应用更新包时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_APPLY_FAILED));
		}
	}

	/**
	 * 更新状态查询
	 */
		private void handleUpdateStatusRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		this.assertRequestMethod(request.method() != HttpMethod.GET, I18N_METHOD_GET_ONLY);
		try {
			LetsUpdate updater = LetsUpdate.getInstance();
			LetsUpdate.UpdateStatus status = updater.getStatus();

			Path zipPath = Paths.get(System.getProperty("user.dir")).resolve("cache" + File.separator + "update.zip");
			boolean zipExists = Files.exists(zipPath);
			long zipSize = zipExists ? Files.size(zipPath) : 0L;

			Map<String, Object> data = new HashMap<>();
			data.put("status", status.getLabel());
			data.put("currentVersion", BuildInfo.getTag());
			data.put("zipDownloaded", zipExists);
			data.put("zipSize", zipSize);
			data.put("pendingVersion", updater.getPendingVersion());
			data.put("downloadedBytes", updater.getDownloadedBytes());
			data.put("totalBytes", updater.getTotalBytes());
			data.put("progressRatio", updater.getProgressRatio());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("查询更新状态时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_STATUS_FAILED));
		}
	}

	/**
	 * 判断是否为官方发行版本（BuildInfo 占位符已被替换）。
	 */
	private boolean isOfficialBuild() {
		String tag = BuildInfo.getTag();
		return tag != null && !tag.isEmpty() && !"{tag}".equals(tag);
	}

	/**
	 * 取消下载
	 */
	private void handleUpdateCancelRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		this.assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);
		try {
			Map<String, Object> data = LetsUpdate.getInstance().cancelDownload();
			boolean success = (Boolean) data.getOrDefault("success", false);
			if (success) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
			} else {
				String error = (String) data.getOrDefault("error", I18N_CANCEL_FAILED);
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(error));
			}
		} catch (Exception e) {
			logger.info("取消更新下载时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_CANCEL_FAILED));
		}
	}

	/**
	 * 	获取计算机信息（通过 gpu-info）
	 */
	private void handleSysInfoRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			Map<String, String> params = ParamTool.getQueryParam(request.uri());
			String nodeId = params.get("nodeId");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				NodeManager.HttpResult result = NodeManager.getInstance().callRemoteApi(
						nodeId, "GET", "api/sys/sysinfo", null);
				if (result.isSuccess()) {
					JsonObject remoteResp = JsonUtil.fromJson(result.getBody(), JsonObject.class);
					if (remoteResp != null && remoteResp.has("data")) {
						LlamaServer.sendJsonResponse(ctx, ApiResponse.success(remoteResp.get("data")));
					} else {
						LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_RESPONSE_FORMAT));
					}
				} else {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_CALL_FAILED + ": code=" + result.getStatusCode()));
				}
				return;
			}
			GPUInfoHelper helper = GPUInfoHelper.getInstance();
			String initErr = helper.init();
			JsonObject data = helper.getInfo();

			Map<String, Object> resp = new HashMap<>();
			resp.put("available", helper.isAvailable());
			resp.put("error", initErr);
			if (data != null) {
				resp.put("data", data);
			} else {
				resp.put("data", null);
			}

			// JVM 信息
			JsonObject jvmInfo = new JsonObject();
			jvmInfo.addProperty("name", ComputerService.getJvmName());
			jvmInfo.addProperty("version", ComputerService.getJvmVersion());
			jvmInfo.addProperty("vendor", ComputerService.getJvmVendor());
			jvmInfo.addProperty("javaVersion", ComputerService.getJavaVersion());
			jvmInfo.addProperty("javaVendor", ComputerService.getJavaVendor());
			jvmInfo.addProperty("inputArguments", ComputerService.getJvmInputArguments());
			jvmInfo.addProperty("startTime", ComputerService.getJvmStartTime());
			jvmInfo.addProperty("maxMemoryMB", ComputerService.getJvmMaxMemoryMB());
			jvmInfo.addProperty("totalMemoryMB", ComputerService.getJvmTotalMemoryMB());
			jvmInfo.addProperty("freeMemoryMB", ComputerService.getJvmFreeMemoryMB());
			jvmInfo.addProperty("usedMemoryMB", ComputerService.getJvmUsedMemoryMB());
			jvmInfo.addProperty("availableProcessors", ComputerService.getJvmAvailableProcessors());
			resp.put("jvm", jvmInfo);

			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(resp));
		} catch (Exception e) {
			logger.info("获取计算机信息时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_COMPUTER_INFO_FAILED + ": " + e.getMessage()));
		}
	}

	/**
	 * 处理停止服务请求
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleShutdownRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		
		try {
			logger.info("收到停止服务请求");

			// 先发送响应，然后再执行关闭操作
			Map<String, Object> data = new HashMap<>();
			data.put("message", "服务正在停止，所有模型进程将被终止");

			// 发送响应
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));

			// 在新线程中执行关闭操作，避免阻塞响应发送
			new Thread(() -> {
				try {
					// 等待一小段时间确保响应已发送
					Thread.sleep(500);

					// 调用LlamaServerManager停止所有进程并退出
					LlamaServerManager manager = LlamaServerManager.getInstance();
					manager.shutdownAll();
					//
					NodeManager.getInstance().shutdown();
					//
					System.exit(0);
				} catch (Exception e) {
					logger.info("停止服务时发生错误", e);
				}
			}).start();

		} catch (Exception e) {
			logger.info("处理停止服务请求时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_SHUTDOWN_FAILED + ": " + e.getMessage()));
		}
	}
	
	/**
	 * 处理控制台的请求。
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleSysConsoleRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			Map<String, String> params = ParamTool.getQueryParam(request.uri());
			String nodeId = params.get("nodeId");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				NodeManager.HttpResult result = NodeManager.getInstance().callRemoteApi(
						nodeId, "GET", "api/sys/console", null);
				if (result.isSuccess()) {
					LlamaServer.sendTextResponse(ctx, result.getBody());
				} else {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_CALL_FAILED + ": code=" + result.getStatusCode()));
				}
				return;
			}
			LlamaServer.sendTextResponse(ctx, LlamaServer.getConsoleBufferText());
		} catch (Exception e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_SYSLOG_READ_FAILED + ": " + e.getMessage()));
		}
	}
	
	
	/**
	 * 处理设备列表请求 执行 llama-bench --list-devices 命令获取可用设备列表
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleDeviceListRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");

		try {
			Map<String, String> params = ParamTool.getQueryParam(request.uri());
			String llamaBinPath = params.get("llamaBinPath");
			String nodeId = params.get("nodeId");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				this.handleDeviceListRemote(ctx, nodeId, llamaBinPath);
				return;
			}

			if (llamaBinPath == null || llamaBinPath.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_LLAMA_BIN_REQUIRED));
				return;
			}

			List<String> devices = LlamaServerManager.getInstance().handleListDevices(llamaBinPath);
			Map<String, Object> data = new HashMap<>();
			data.put("devices", devices);

			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("获取设备列表时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_DEVICE_LIST_FAILED + ": " + e.getMessage()));
		}
	}

	private void handleDeviceListRemote(ChannelHandlerContext ctx, String nodeId, String llamaBinPath) {
		try {
			String encodedPath = (llamaBinPath != null) ? java.net.URLEncoder.encode(llamaBinPath, "UTF-8") : "";
			String path = "api/model/device/list?llamaBinPath=" + encodedPath;
			NodeManager.HttpResult result = NodeManager.getInstance().callRemoteApi(
					nodeId, "GET", path, null);
			if (result.isSuccess()) {
				NodeManager.writeHttpResultToChannel(ctx, result, "[设备列表远程]");
			} else {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_CALL_FAILED + ": code=" + result.getStatusCode()));
			}
		} catch (Exception e) {
			logger.warn("获取远程节点设备列表失败: nodeId={}, error={}", nodeId, e.getMessage());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_DEVICE_REMOTE_LIST_FAILED + ": " + e.getMessage()));
		}
	}
	
	/**
	 * 估算模型显存需求
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleVramEstimateRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");

		try {
			byte[] content = JsonUtil.readRequestBytes(request);
			if (content == null || JsonUtil.isBlank(content)) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_EMPTY));
				return;
			}

			JsonElement root = JsonUtil.fromJson(content, JsonElement.class);
			if (root == null || !root.isJsonObject()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_NOT_JSON));
				return;
			}
			JsonObject obj = root.getAsJsonObject();
			String nodeId = JsonUtil.getJsonString(obj, "nodeId", "");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				logger.info("[显存估算] 远程节点代理: nodeId={}, modelId={}", nodeId, JsonUtil.getJsonString(obj, "modelId", ""));
				this.handleVramEstimateRemote(ctx, nodeId, obj);
				return;
			}

			String cmd = JsonUtil.getJsonString(obj, "cmd", "");
			String extraParams = JsonUtil.getJsonString(obj, "extraParams", "");
			List<String> device = JsonUtil.getJsonStringList(obj.get("device"));
			Integer mg = JsonUtil.getJsonInt(obj, "mg", null);

			// 过滤无效设备值
			if (device != null) {
				device.removeIf(d -> d == null || d.trim().isEmpty() || d.trim().equalsIgnoreCase("none") || d.trim().equalsIgnoreCase("all"));
			}

			if (cmd != null) cmd = cmd.trim();
			if (extraParams != null) extraParams = extraParams.trim();
			if ((cmd == null || cmd.isEmpty()) && (extraParams == null || extraParams.isEmpty())) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_LAUNCH_REQUIRED));
				return;
			}
			String combinedCmd = "";
			if (cmd != null && !cmd.isEmpty()) combinedCmd = cmd;
			if (extraParams != null && !extraParams.isEmpty()) combinedCmd = combinedCmd.isEmpty() ? extraParams : (combinedCmd + " " + extraParams);
			boolean enableVision = ParamTool.parseJsonBoolean(obj, "enableVision", true);
			String modelId = JsonUtil.getJsonString(obj, "modelId", null);
			String llamaBinPathSelect = JsonUtil.getJsonString(obj, "llamaBinPathSelect", null);
			if (llamaBinPathSelect == null || llamaBinPathSelect.trim().isEmpty()) {
				llamaBinPathSelect = JsonUtil.getJsonString(obj, "llamaBinPath", null);
			}

			if (device != null && !device.isEmpty()) {
				if(device.size() == 1) {
					combinedCmd += " --device " + device.get(0);
				}else {
					combinedCmd += " --device ";
					combinedCmd += ParamTool.quoteIfNeeded(String.join(",", device));
				}
			}
			combinedCmd += " --main-gpu " + mg;

			logger.info("[显存估算] 本地执行: modelId={}, llamaBinPath={}, cmd={}", modelId, llamaBinPathSelect, combinedCmd);

			List<String> cmdlist = ParamTool.splitCmdArgs(combinedCmd);
			Map<String, String> result = LlamaServerManager.getInstance().handleFitParam(llamaBinPathSelect, modelId, enableVision, cmdlist);

			String output = result.get("output");
			if (output == null || output.trim().isEmpty()) {
				String error = result.get("error");
				logger.warn("[显存估算] 执行失败: modelId={}, error={}", modelId, error);
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(error != null ? error.trim() : I18N_VRAM_ESTIMATE_FAILED));
				return;
			}
			Map<String, Object> data = new HashMap<>();
			Pattern devicePattern = Pattern.compile("(\\S+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)");
			Matcher deviceMatcher = devicePattern.matcher(output);
			long totalVram = 0;
			boolean found = false;
			while (deviceMatcher.find()) {
				String deviceName = deviceMatcher.group(1);
				if ("estimated".equalsIgnoreCase(deviceName) || "MiB".equalsIgnoreCase(deviceName)) {
					continue;
				}
				long modelMem = Long.parseLong(deviceMatcher.group(2));
				long contextMem = Long.parseLong(deviceMatcher.group(3));
				long computeMem = Long.parseLong(deviceMatcher.group(4));
				totalVram += modelMem + contextMem + computeMem;
				found = true;
			}
			if (found) {
				data.put("vram", String.valueOf(totalVram));
				logger.info("[显存估算] 成功: modelId={}, vram={} MiB", modelId, totalVram);
			} else {
				Pattern pattern = Pattern.compile("^.*llama_init_from_model.*$", Pattern.MULTILINE);
		        Matcher matcher = pattern.matcher(output);
		        if (matcher.find()) {
		            data.put("message", matcher.group(0));
		        }
			}
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("估算显存时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_VRAM_ESTIMATE_FAILED + ": " + e.getMessage()));
		}
	}

	private void handleVramEstimateRemote(ChannelHandlerContext ctx, String nodeId, JsonObject body) {
		try {
			if (body != null) {
				body.remove("nodeId");
			}
			NodeManager.HttpResult result = NodeManager.getInstance().callRemoteApi(
					nodeId, "POST", "api/models/vram/estimate", body);
			logger.info("[显存估算] 远程节点响应: nodeId={}, code={}", nodeId, result.getStatusCode());
			if (result.isSuccess()) {
				NodeManager.writeHttpResultToChannel(ctx, result, "[显存估算远程]");
			} else {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_CALL_FAILED + ": code=" + result.getStatusCode()));
			}
		} catch (Exception e) {
			logger.warn("[显存估算] 远程节点调用异常: nodeId={}, error={}", nodeId, e.getMessage());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_CALL_FAILED + ": " + e.getMessage()));
		}
	}

	private void handleFitParamsRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");

		try {
			byte[] content = JsonUtil.readRequestBytes(request);
			if (content == null || JsonUtil.isBlank(content)) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_EMPTY));
				return;
			}

			JsonElement root = JsonUtil.fromJson(content, JsonElement.class);
			if (root == null || !root.isJsonObject()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_NOT_JSON));
				return;
			}
			JsonObject obj = root.getAsJsonObject();
			String nodeId = JsonUtil.getJsonString(obj, "nodeId", "");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				logger.info("[拟合参数] 远程节点代理: nodeId={}, modelId={}", nodeId, JsonUtil.getJsonString(obj, "modelId", ""));
				this.handleFitParamsRemote(ctx, nodeId, obj);
				return;
			}

			String cmd = JsonUtil.getJsonString(obj, "cmd", "");
			String extraParams = JsonUtil.getJsonString(obj, "extraParams", "");
			List<String> device = JsonUtil.getJsonStringList(obj.get("device"));
			Integer mg = JsonUtil.getJsonInt(obj, "mg", null);

			// 过滤无效设备值
			if (device != null) {
				device.removeIf(d -> d == null || d.trim().isEmpty() || d.trim().equalsIgnoreCase("none") || d.trim().equalsIgnoreCase("all"));
			}

			if (cmd != null) cmd = cmd.trim();
			if (extraParams != null) extraParams = extraParams.trim();
			String combinedCmd = "";
			if (cmd != null && !cmd.isEmpty()) combinedCmd = cmd;
			if (extraParams != null && !extraParams.isEmpty()) combinedCmd = combinedCmd.isEmpty() ? extraParams : (combinedCmd + " " + extraParams);

			String modelId = JsonUtil.getJsonString(obj, "modelId", null);
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_MODEL_ID_MISSING_REQUIRED));
				return;
			}
			String llamaBinPathSelect = JsonUtil.getJsonString(obj, "llamaBinPathSelect", null);
			if (llamaBinPathSelect == null || llamaBinPathSelect.trim().isEmpty()) {
				llamaBinPathSelect = JsonUtil.getJsonString(obj, "llamaBinPath", null);
			}
			if (llamaBinPathSelect == null || llamaBinPathSelect.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_LLAMA_BIN_REQUIRED));
				return;
			}

			if (device != null && !device.isEmpty()) {
				if (device.size() == 1) {
					combinedCmd += " --device " + device.get(0);
				} else {
					combinedCmd += " --device ";
					combinedCmd += ParamTool.quoteIfNeeded(String.join(",", device));
				}
			}
			if (mg != null) {
				combinedCmd += " --main-gpu " + mg;
			}

			logger.info("[拟合参数] 本地执行: modelId={}, llamaBinPath={}, cmd={}", modelId, llamaBinPathSelect, combinedCmd);

			Map<String, String> fittedParams = LlamaServerManager.getInstance().handleFitParam(llamaBinPathSelect, modelId, combinedCmd);

			Map<String, Object> data = new HashMap<>();
			data.put("fittedParams", fittedParams);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("拟合参数时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_FIT_PARAMS_FAILED + ": " + e.getMessage()));
		}
	}

	private void handleFitParamsRemote(ChannelHandlerContext ctx, String nodeId, JsonObject body) {
		try {
			if (body != null) {
				body.remove("nodeId");
			}
			NodeManager.HttpResult result = NodeManager.getInstance().callRemoteApi(
					nodeId, "POST", "api/models/fit-params", body);
			logger.info("[拟合参数] 远程节点响应: nodeId={}, code={}", nodeId, result.getStatusCode());
			if (result.isSuccess()) {
				NodeManager.writeHttpResultToChannel(ctx, result, "[拟合参数远程]");
			} else {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_CALL_FAILED + ": code=" + result.getStatusCode()));
			}
		} catch (Exception e) {
			logger.warn("[拟合参数] 远程节点调用异常: nodeId={}, error={}", nodeId, e.getMessage());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_CALL_FAILED + ": " + e.getMessage()));
		}
	}

	private void handleFsListRemote(ChannelHandlerContext ctx, String nodeId, FullHttpRequest request) {
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
				fullPath = cleanQuery.length() > 0 ? "api/sys/fs/list?" + cleanQuery : "api/sys/fs/list";
			} else {
				fullPath = "api/sys/fs/list";
			}
			NodeManager.HttpResult result = NodeManager.getInstance().callRemoteApi(nodeId, "GET", fullPath, null);
			if (result.isSuccess()) {
				NodeManager.writeHttpResultToChannel(ctx, result, "[文件系统远程]");
			} else {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_CALL_FAILED + ": code=" + result.getStatusCode()));
			}
		} catch (Exception e) {
			logger.warn("远程文件系统浏览失败: nodeId={}, error={}", nodeId, e.getMessage());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_FS_BROWSE_FAILED + ": " + e.getMessage()));
		}
	}

//	/**
//	 * 	
//	 * @param ctx
//	 * @param request
//	 * @throws RequestMethodException
//	 */
//	@Deprecated
//	private void handleGgufMemEstimateRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
//		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
//
//		try {
//			String content = request.content().toString(CharsetUtil.UTF_8);
//			if (content == null || content.trim().isEmpty()) {
//				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_EMPTY));
//				return;
//			}
//
//			JsonElement root = JsonUtil.fromJson(content, JsonElement.class);
//			if (root == null || !root.isJsonObject()) {
//				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_NOT_JSON));
//				return;
//			}
//			JsonObject obj = root.getAsJsonObject();
//			String nodeId = JsonUtil.getJsonString(obj, "nodeId", "");
//			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
//				logger.info("[gguf-mem估算] 远程节点代理: nodeId={}, model={}", nodeId, JsonUtil.getJsonString(obj, "model", ""));
//				this.handleGgufMemEstimateRemote(ctx, nodeId, obj);
//				return;
//			}
//
//			String model = JsonUtil.getJsonString(obj, "model", "");
//			String cmd = JsonUtil.getJsonString(obj, "cmd", "");
//			String extraParams = JsonUtil.getJsonString(obj, "extraParams", "");
//			List<String> device = JsonUtil.getJsonStringList(obj.get("device"));
//			Integer mg = JsonUtil.getJsonInt(obj, "mg", null);
//
//			if (model != null) model = model.trim();
//			if (cmd != null) cmd = cmd.trim();
//			if (extraParams != null) extraParams = extraParams.trim();
//
//			if (model == null || model.isEmpty()) {
//				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的model参数"));
//				return;
//			}
//			if ((cmd == null || cmd.isEmpty()) && (extraParams == null || extraParams.isEmpty())) {
//				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的启动参数"));
//				return;
//			}
//
//			String combinedCmd = "";
//			if (cmd != null && !cmd.isEmpty()) combinedCmd = cmd;
//			if (extraParams != null && !extraParams.isEmpty()) {
//				combinedCmd = combinedCmd.isEmpty() ? extraParams : (combinedCmd + " " + extraParams);
//			}
//
//			if (device != null && !device.isEmpty()) {
//				if (device.size() == 1) {
//					combinedCmd += " --device " + device.get(0);
//				} else {
//					combinedCmd += " --device ";
//					combinedCmd += ParamTool.quoteIfNeeded(String.join(",", device));
//				}
//			}
//			if (mg != null) {
//				combinedCmd += " --main-gpu " + mg;
//			}
//
//			logger.info("[gguf-mem估算] 本地执行: model={}, cmd={}", model, combinedCmd);
//			// 下载
//			String path = GGufMetaDataExtractor.downloadHeader(model);
//			// 执行计算
//			Map<String, String> result = LlamaServerManager.getInstance().handleFitParam(path, combinedCmd);
//			String output = result.get("output");
//			if (output == null || output.trim().isEmpty()) {
//				String error = result.get("error");
//				logger.warn("[gguf-mem显存估算] 执行失败: model={}, error={}", model, error);
//				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(error != null ? error.trim() : "估算显存失败"));
//				return;
//			}
//			Map<String, Object> data = new HashMap<>();
//			Pattern devicePattern = Pattern.compile("(\\S+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)");
//			Matcher deviceMatcher = devicePattern.matcher(output);
//			long totalVram = 0;
//			boolean found = false;
//			while (deviceMatcher.find()) {
//				String deviceName = deviceMatcher.group(1);
//				if ("estimated".equalsIgnoreCase(deviceName) || "MiB".equalsIgnoreCase(deviceName)) {
//					continue;
//				}
//				long modelMem = Long.parseLong(deviceMatcher.group(2));
//				long contextMem = Long.parseLong(deviceMatcher.group(3));
//				long computeMem = Long.parseLong(deviceMatcher.group(4));
//				totalVram += modelMem + contextMem + computeMem;
//				found = true;
//			}
//			if (found) {
//				data.put("vram", String.valueOf(totalVram));
//				logger.info("[gguf-mem显存估算] 成功: model={}, vram={} MiB", model, totalVram);
//			} else {
//				Pattern pattern = Pattern.compile("^.*llama_init_from_model.*$", Pattern.MULTILINE);
//		        Matcher matcher = pattern.matcher(output);
//		        if (matcher.find()) {
//		            data.put("message", matcher.group(0));
//		        }
//			}
//			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
//		} catch (Exception e) {
//			logger.info("gguf-mem估算时发生错误", e);
//			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("估算显存失败: " + e.getMessage()));
//		}
//	}
//
//	private void handleGgufMemEstimateRemote(ChannelHandlerContext ctx, String nodeId, JsonObject body) {
//		try {
//			if (body != null) {
//				body.remove("nodeId");
//			}
//			NodeManager.HttpResult result = NodeManager.getInstance().callRemoteApi(
//					nodeId, "POST", "api/models/gguf-mem/estimate", body);
//			logger.info("[gguf-mem估算] 远程节点响应: nodeId={}, code={}", nodeId, result.getStatusCode());
//			if (result.isSuccess()) {
//				NodeManager.writeHttpResultToChannel(ctx, result, "[gguf-mem估算远程]");
//			} else {
//				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_CALL_FAILED + ": code=" + result.getStatusCode()));
//			}
//		} catch (Exception e) {
//			logger.warn("[gguf-mem估算] 远程节点调用异常: nodeId={}, error={}", nodeId, e.getMessage());
//			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_CALL_FAILED + ": " + e.getMessage()));
//		}
//	}

	/**
	 * 处理开机自启请求
	 * GET: 查询当前状态
	 * POST: 启用/禁用 (body: {"enable": true/false})
	 *
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleAutoStartRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}

		String osName = System.getProperty("os.name", "").toLowerCase();
		if (!osName.startsWith("windows")) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_WINDOWS_ONLY));
			return;
		}

		if (request.method() == HttpMethod.GET) {
			// 查询当前状态
			try {
				boolean enabled = AutoStartManager.isAutoStartEnabled();
				Map<String, Object> data = new HashMap<>();
				data.put("enabled", enabled);
				LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
			} catch (Exception e) {
				logger.info("查询开机自启状态时发生错误", e);
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_STARTUP_STATUS_FAILED + ": " + e.getMessage()));
			}
			return;
		}

		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");

		try {
			JsonObject obj = parseJsonBody(ctx, request);
			if (obj == null) {
				return;
			}
			if (!obj.has("enable") || obj.get("enable") == null || obj.get("enable").isJsonNull()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_ENABLE_REQUIRED));
				return;
			}

			boolean enable = obj.get("enable").getAsBoolean();
			boolean success;
			if (enable) {
				success = AutoStartManager.enableAutoStart();
			} else {
				success = AutoStartManager.disableAutoStart();
			}

			if (success) {
				Map<String, Object> data = new HashMap<>();
				data.put("enabled", enable);
				data.put("message", enable ? "开机自启已启用" : "开机自启已禁用");
				LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
			} else {
				String errorMsg = enable ? "启用开机自启失败" : "禁用开机自启失败";
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(errorMsg));
			}
		} catch (Exception e) {
			logger.info("处理开机自启请求时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_STARTUP_TOGGLE_FAILED + ": " + e.getMessage()));
		}
	}

	/**
	 * 返回有日志文件的模型ID列表（扫描 logs/*.log 排除 app.log）
	 */
	private void handleLogModelsRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			Map<String, String> params = ParamTool.getQueryParam(request.uri());
			String nodeId = params.get("nodeId");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				NodeManager.HttpResult result = NodeManager.getInstance().callRemoteApi(
						nodeId, "GET", "api/sys/log-models", null);
				if (result.isSuccess()) {
					LlamaServer.sendJsonResponse(ctx, com.google.gson.JsonParser.parseString(result.getBody()));
				} else {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_CALL_FAILED + ": code=" + result.getStatusCode()));
				}
				return;
			}
			Path logDir = Paths.get("logs");
			List<String> modelIds = new ArrayList<>();
			if (Files.exists(logDir)) {
				try (Stream<Path> files = Files.list(logDir)) {
					modelIds = files
						.filter(Files::isRegularFile)
						.filter(name -> name.endsWith(".log"))
						.map(p -> p.getFileName().toString())
						.filter(name -> !name.equals("app.log"))
						.map(name -> name.substring(0, name.length() - 4))
						.collect(java.util.stream.Collectors.toList());
				}
			}
			Map<String, Object> response = new HashMap<>();
			response.put("success", true);
			response.put("data", modelIds);
			LlamaServer.sendJsonResponse(ctx, response);
		} catch (Exception e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_SYSLOG_LIST_FAILED + ": " + e.getMessage()));
		}
	}

	/**
	 * 返回指定模型日志文件的尾部内容
	 */
	private void handleModelLogRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			Map<String, String> params = ParamTool.getQueryParam(request.uri());
			String nodeId = params.get("nodeId");
			String modelId = params.get("modelId");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				logger.info("[模型日志] 远程请求: nodeId={}, modelId={}", nodeId, modelId);
				NodeManager.HttpResult result = NodeManager.getInstance().callRemoteApi(
						nodeId, "GET", "api/sys/model-log?modelId=" + java.net.URLEncoder.encode(modelId, java.nio.charset.StandardCharsets.UTF_8), null);
				logger.info("[模型日志] 远程响应: nodeId={}, success={}, statusCode={}, bodyLength={}", nodeId, result.isSuccess(), result.getStatusCode(), result.getBody() != null ? result.getBody().length() : 0);
				if (result.isSuccess()) {
					LlamaServer.sendTextResponse(ctx, result.getBody());
				} else {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_CALL_FAILED + ": code=" + result.getStatusCode() + ", body=" + result.getBody()));
				}
				return;
			}
			if (modelId == null || modelId.isBlank()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_MODEL_ID_MISSING));
				return;
			}
			String text = LlamaServer.getModelLogText(modelId);
			LlamaServer.sendTextResponse(ctx, text != null ? text : "");
		} catch (Exception e) {
			logger.warn("[模型日志] 读取失败: error={}", e.getMessage(), e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_MODEL_LOG_READ_FAILED + ": " + e.getMessage()));
		}
	}
}
