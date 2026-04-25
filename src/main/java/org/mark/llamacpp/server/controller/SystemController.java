package org.mark.llamacpp.server.controller;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.mark.llamacpp.lmstudio.LMStudio;
import org.mark.llamacpp.ollama.Ollama;
import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.NodeManager;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.service.GpuService;
import org.mark.llamacpp.server.service.ModelSamplingService;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.tools.ParamTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.util.CharsetUtil;



/**
 * 	系统相关。
 */
public class SystemController implements BaseController {

	private static final Logger logger = LoggerFactory.getLogger(SystemController.class);
	
	/**
	 * 	依旧请求入口。
	 */
	@Override
	public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 停止服务API
		if (uri.startsWith("/api/shutdown")) {
			this.handleShutdownRequest(ctx, request);
			return true;
		}
		// 控制台
		if (uri.startsWith("/api/sys/console")) {
			this.handleSysConsoleRequest(ctx, request);
			return true;
		}
		
		// 列出可用的设备，基于当前选择的llamacpp
		if (uri.startsWith("/api/model/device/list")) {
			this.handleDeviceListRequest(ctx, request);
			return true;
		}
		
		// 显存估算API
		if (uri.startsWith("/api/models/vram/estimate")) {
			this.handleVramEstimateRequest(ctx, request);
			return true;
		}
		// 启用、禁用ollama兼容api
		if (uri.startsWith("/api/sys/ollama")) {
			this.handleOllamaEnableRequest(ctx, request);
			return true;
		}
		// 启用、禁用lmstudio
		if (uri.startsWith("/api/sys/lmstudio")) {
			this.handleLmstudioEnableRequest(ctx, request);
			return true;
		}
		// 启用、禁用内置MCP服务
		if (uri.startsWith("/api/sys/mcp")) {
			this.handleMcpEnableRequest(ctx, request);
			return true;
		}
		// 获取兼容服务状态
		if (uri.startsWith("/api/sys/compat/status")) {
			this.handleCompatStatusRequest(ctx, request);
			return true;
		}
		// 获取构建版本信息
		if (uri.startsWith("/api/sys/version")) {
			this.handleVersionInfoRequest(ctx, request);
			return true;
		}
		// 获取GPU服务信息（初始化快照）
		if (uri.startsWith("/api/sys/gpu/info")) {
			this.handleGpuInfoRequest(ctx, request);
			return true;
		}
		// 查询GPU实时状态
		if (uri.startsWith("/api/sys/gpu/status")) {
			this.handleGpuStatusRequest(ctx, request);
			return true;
		}
		// 获取系统设置
		if (uri.startsWith("/api/sys/setting") && request.method() == HttpMethod.GET) {
			this.handleSysSettingGetRequest(ctx, request);
			return true;
		}
		// 保存系统设置
		if (uri.startsWith("/api/sys/setting")) {
			this.handleSysSettingRequest(ctx, request);
			return true;
		}
		// 保存搜索设置
		if (uri.startsWith("/api/search/setting")) {
			this.handleSearchSettingRequest(ctx, request);
			return true;
		}
		// 获取指定模型的采样配置
		if (uri.startsWith("/api/sys/model/sampling/setting/get")) {
			this.handleModelSamplingSettingGetRequest(ctx, request);
			return true;
		}
		
		if (uri.startsWith("/api/sys/model/sampling/setting/add")) {
			this.handleModelSamplingSettingAddRequest(ctx, request);
			return true;
		}
		// 获取的采样配置
		if (uri.startsWith("/api/sys/model/sampling/setting/list")) {
			this.handleModelSamplingSettingListRequest(ctx, request);
			return true;
		}
		// 删除指定的采样
		if (uri.startsWith("/api/sys/model/sampling/setting/delete")) {
			this.handleModelSamplingSettingDeleteRequest(ctx, request);
			return true;
		}
		// 设置指定模型的采样配置
		if (uri.startsWith("/api/sys/model/sampling/setting/set")) {
			this.handleModelSamplingSettingRequest(ctx, request);
			return true;
		}
		
		// 文件系统：目录浏览
		if (uri.startsWith("/api/sys/fs/list")) {
			this.handleFsListRequest(ctx, request);
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
			
			int fileLimit = 10;
			int dirLimit = 500;
			
			Map<String, Object> data = new HashMap<>();
			
			if (in == null || in.isEmpty()) {
				List<Map<String, Object>> dirs = new ArrayList<>();
				File[] roots = File.listRoots();
				if (roots != null) {
					for (File r : roots) {
						if (r == null) continue;
						String p = r.getAbsolutePath();
						Map<String, Object> item = new HashMap<>();
						item.put("name", p);
						item.put("path", p);
						dirs.add(item);
					}
				}
				dirs.sort(Comparator.comparing(o -> String.valueOf(o.getOrDefault("name", "")), String.CASE_INSENSITIVE_ORDER));
				
				data.put("path", null);
				data.put("parent", null);
				data.put("directories", dirs);
				data.put("files", new ArrayList<>());
				data.put("truncatedDirs", false);
				data.put("truncatedFiles", false);
				data.put("mode", "roots");
				LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
				return;
			}
			
			Path raw;
			try {
				raw = Paths.get(in);
			} catch (Exception e) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("非法路径"));
				return;
			}
			Path abs = raw.toAbsolutePath().normalize();
			if (!Files.exists(abs)) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("目录不存在"));
				return;
			}
			if (!Files.isDirectory(abs)) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("不是目录"));
				return;
			}
			if (this.pathHasSymlink(abs)) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("不允许使用符号链接目录"));
				return;
			}
			
			Path base;
			try {
				base = abs.toRealPath();
			} catch (Exception e) {
				base = abs;
			}
			if (!Files.isDirectory(base)) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("不是目录"));
				return;
			}
			
			final List<Map<String, Object>> dirs = new ArrayList<>();
			final List<Map<String, Object>> files = new ArrayList<>();
			boolean truncatedDirs = false;
			boolean truncatedFiles = false;
			
			try (Stream<Path> stream = Files.list(base)) {
				stream.forEach(p -> {
					if (p == null) return;
					try {
						String name = p.getFileName() == null ? p.toString() : p.getFileName().toString();
						if (Files.isDirectory(p)) {
							Map<String, Object> item = new HashMap<>();
							item.put("name", name);
							item.put("path", p.toAbsolutePath().normalize().toString());
							dirs.add(item);
							return;
						}
						Map<String, Object> item = new HashMap<>();
						item.put("name", name);
						item.put("path", p.toAbsolutePath().normalize().toString());
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
			data.put("path", base.toString());
			data.put("parent", parent == null ? null : parent.toString());
			data.put("directories", outDirs);
			data.put("files", outFiles);
			data.put("truncatedDirs", truncatedDirs);
			data.put("truncatedFiles", truncatedFiles);
			data.put("mode", "directory");
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("处理目录浏览请求时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("目录浏览失败: " + e.getMessage()));
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
	
	/**
	 * 	获取GPU服务信息（初始化时的快照）
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleGpuInfoRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			JsonObject info = GpuService.getInstance().getServiceInfo();
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(info));
		} catch (Exception e) {
			logger.info("获取GPU信息时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取GPU信息失败: " + e.getMessage()));
		}
	}

	/**
	 * 	查询GPU实时状态
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleGpuStatusRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			JsonObject status = GpuService.getInstance().queryGpuStatus();
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(status));
		} catch (Exception e) {
			logger.info("查询GPU状态时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("查询GPU状态失败: " + e.getMessage()));
		}
	}

	/**
	 * 	获取兼容服务 ollama和lmstudio 状态
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
			Ollama ollama = Ollama.getInstance();
			LMStudio lmstudio = LMStudio.getInstance();
			
			Map<String, Object> data = new HashMap<>();
			
			Map<String, Object> ollamaData = new HashMap<>();
			ollamaData.put("enabled", LlamaServer.isOllamaCompatEnabled());
			ollamaData.put("configuredPort", LlamaServer.getOllamaCompatPort());
			ollamaData.put("running", ollama.isRunning());
			ollamaData.put("port", ollama.getPort());
			data.put("ollama", ollamaData);
			
			Map<String, Object> lmstudioData = new HashMap<>();
			lmstudioData.put("enabled", LlamaServer.isLmstudioCompatEnabled());
			lmstudioData.put("configuredPort", LlamaServer.getLmstudioCompatPort());
			lmstudioData.put("running", lmstudio.isRunning());
			lmstudioData.put("port", lmstudio.getPort());
			data.put("lmstudio", lmstudioData);

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
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取兼容服务状态失败: " + e.getMessage()));
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
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取版本信息失败: " + e.getMessage()));
		}
	}
	
	/**
	 * 	启用、禁用ollama兼容api
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleOllamaEnableRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
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
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的enable参数"));
				return;
			}
			
			boolean enable = ParamTool.parseJsonBoolean(obj, "enable", false);
			Integer port = JsonUtil.getJsonInt(obj, "port", null);
			int bindPort = port == null ? LlamaServer.getOllamaCompatPort() : port.intValue();
			if (bindPort <= 0 || bindPort > 65535) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("port参数不合法"));
				return;
			}
			
			Ollama ollama = Ollama.getInstance();
			if (enable) {
				ollama.start(bindPort);
			} else {
				ollama.stop();
			}
			
			LlamaServer.updateOllamaCompatConfig(enable, bindPort);
			
			Map<String, Object> data = new HashMap<>();
			data.put("enable", enable);
			data.put("port", bindPort);
			data.put("running", ollama.isRunning());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("处理ollama启停请求时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("处理ollama启停失败: " + e.getMessage()));
		}
	}
	
	/**
	 * 	启用、禁用lm studio兼容api
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleLmstudioEnableRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
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
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的enable参数"));
				return;
			}
			
			boolean enable = ParamTool.parseJsonBoolean(obj, "enable", false);
			Integer port = JsonUtil.getJsonInt(obj, "port", null);
			int bindPort = port == null ? LlamaServer.getLmstudioCompatPort() : port.intValue();
			if (bindPort <= 0 || bindPort > 65535) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("port参数不合法"));
				return;
			}
			
			LMStudio lmstudio = LMStudio.getInstance();
			if (enable) {
				lmstudio.start(bindPort);
			} else {
				lmstudio.stop();
			}
			
			LlamaServer.updateLmstudioCompatConfig(enable, bindPort);
			
			Map<String, Object> data = new HashMap<>();
			data.put("enable", enable);
			data.put("port", bindPort);
			data.put("running", lmstudio.isRunning());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("处理lmstudio启停请求时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("处理lmstudio启停失败: " + e.getMessage()));
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
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的enable参数"));
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
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("处理MCP服务启停失败: " + e.getMessage()));
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
			server.put("anthropicPort", LlamaServer.getAnthropicPort());
			data.put("server", server);
			
			Map<String, Object> download = new HashMap<>();
			download.put("directory", LlamaServer.getDownloadDirectory());
			data.put("download", download);
			
			Map<String, Object> security = new HashMap<>();
			security.put("apiKeyEnabled", LlamaServer.isApiKeyValidationEnabled());
			String key = LlamaServer.getApiKey();
			security.put("apiKey", key != null ? key : "");
			data.put("security", security);
			
			Map<String, Object> compat = new HashMap<>();
			Map<String, Object> ollama = new HashMap<>();
			ollama.put("enabled", LlamaServer.isOllamaCompatEnabled());
			ollama.put("port", LlamaServer.getOllamaCompatPort());
			compat.put("ollama", ollama);
			
			Map<String, Object> lmstudio = new HashMap<>();
			lmstudio.put("enabled", LlamaServer.isLmstudioCompatEnabled());
			lmstudio.put("port", LlamaServer.getLmstudioCompatPort());
			compat.put("lmstudio", lmstudio);
			
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
			https.put("keystorePassword", LlamaServer.getHttpsPassword());
			data.put("https", https);
			
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("获取系统设置时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取系统设置失败: " + e.getMessage()));
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

			Integer ollamaPort = firstPort(obj, "ollamaPort", "ollama_port", "ollamaCompatPort", "ollama_compat_port");
			Integer lmstudioPort = firstPort(obj, "lmstudioPort", "lmstudio_port", "lmstudioCompatPort", "lmstudio_compat_port");
			Boolean logRequestUrl = firstBoolean(obj, "LlamaServer.logRequestUrl", "logRequestUrl", "log_request_url");
			Boolean logRequestHeader = firstBoolean(obj, "LlamaServer.logRequestHeader", "logRequestHeader", "log_request_header");
			Boolean logRequestBody = firstBoolean(obj, "LlamaServer.logRequestBody", "logRequestBody", "log_request_body");
			
			Integer webPort = firstPort(obj, "webPort", "web_port");
			Integer anthropicPort = firstPort(obj, "anthropicPort", "anthropic_port");
			Boolean apiKeyEnabled = firstBoolean(obj, "apiKeyEnabled", "api_key_enabled");
			String apiKey = JsonUtil.getJsonString(obj, "apiKey", null);
			Boolean httpsEnabled = firstBoolean(obj, "httpsEnabled", "https_enabled");
			String httpsCertPath = JsonUtil.getJsonString(obj, "httpsCertPath", null);
			String httpsPassword = JsonUtil.getJsonString(obj, "httpsPassword", null);
			String downloadDirectory = JsonUtil.getJsonString(obj, "downloadDirectory", null);

			if (ollamaPort == null && lmstudioPort == null && logRequestUrl == null && logRequestHeader == null && logRequestBody == null
				&& webPort == null && anthropicPort == null && apiKeyEnabled == null && apiKey == null
				&& httpsEnabled == null && httpsCertPath == null && httpsPassword == null
				&& downloadDirectory == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少可保存参数"));
				return;
			}

			if (ollamaPort != null) {
				if (!isValidPort(ollamaPort.intValue())) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error("ollamaPort参数不合法"));
					return;
				}
				LlamaServer.updateOllamaCompatConfig(LlamaServer.isOllamaCompatEnabled(), ollamaPort.intValue());
			}

			if (lmstudioPort != null) {
				if (!isValidPort(lmstudioPort.intValue())) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error("lmstudioPort参数不合法"));
					return;
				}
				LlamaServer.updateLmstudioCompatConfig(LlamaServer.isLmstudioCompatEnabled(), lmstudioPort.intValue());
			}

			if (logRequestUrl != null || logRequestHeader != null || logRequestBody != null) {
				LlamaServer.updateRequestLogConfig(logRequestUrl, logRequestHeader, logRequestBody);
			}
			
			if (webPort != null || anthropicPort != null) {
				if (webPort != null && !isValidPort(webPort.intValue())) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error("webPort参数不合法"));
					return;
				}
				if (anthropicPort != null && !isValidPort(anthropicPort.intValue())) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error("anthropicPort参数不合法"));
					return;
				}
				LlamaServer.updateServerPorts(webPort, anthropicPort);
			}
			
			if (apiKeyEnabled != null || apiKey != null) {
				LlamaServer.updateApiKeyConfig(apiKeyEnabled != null ? apiKeyEnabled : LlamaServer.isApiKeyValidationEnabled(), apiKey);
			}
			
			if (httpsEnabled != null || httpsCertPath != null || httpsPassword != null) {
				LlamaServer.updateHttpsConfig(httpsEnabled, httpsCertPath, null, httpsPassword);
			}
			
			if (downloadDirectory != null && !downloadDirectory.isEmpty()) {
				LlamaServer.setDownloadDirectory(downloadDirectory);
			}

			Map<String, Object> data = new HashMap<>();
			Map<String, Object> ollama = new HashMap<>();
			ollama.put("enabled", LlamaServer.isOllamaCompatEnabled());
			ollama.put("port", LlamaServer.getOllamaCompatPort());
			data.put("ollama", ollama);

			Map<String, Object> lmstudio = new HashMap<>();
			lmstudio.put("enabled", LlamaServer.isLmstudioCompatEnabled());
			lmstudio.put("port", LlamaServer.getLmstudioCompatPort());
			data.put("lmstudio", lmstudio);

			Map<String, Object> requestLog = new HashMap<>();
			requestLog.put("logRequestUrl", LlamaServer.isLogRequestUrlEnabled());
			requestLog.put("logRequestHeader", LlamaServer.isLogRequestHeaderEnabled());
			requestLog.put("logRequestBody", LlamaServer.isLogRequestBodyEnabled());
			data.put("requestLog", requestLog);

			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("处理系统设置请求时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("保存系统设置失败: " + e.getMessage()));
		}
	}

	/**
	 * 	保存搜索设置
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleSearchSettingRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}
			JsonObject obj = JsonUtil.fromJson(content, JsonObject.class);
			if (obj == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体解析失败"));
				return;
			}

			String apiKey = JsonUtil.getJsonString(obj, "zhipu_search_apikey", null);
			if (apiKey == null) {
				apiKey = JsonUtil.getJsonString(obj, "apiKey", null);
			}
			apiKey = apiKey == null ? "" : apiKey.trim();

			JsonObject out = new JsonObject();
			out.addProperty("apiKey", apiKey);
			String json = JsonUtil.toJson(out);

			Path configPath = Paths.get("config", "zhipu_search.json");
			if (!Files.exists(configPath.getParent())) {
				Files.createDirectories(configPath.getParent());
			}
			Files.write(configPath, json.getBytes(StandardCharsets.UTF_8));

			Map<String, Object> data = new HashMap<>();
			data.put("saved", true);
			data.put("file", configPath.toString());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("处理搜索设置请求时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("保存搜索设置失败: " + e.getMessage()));
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

			String modelId = JsonUtil.getJsonString(obj, "modelId", null);
			modelId = modelId == null ? "" : modelId.trim();
			if (modelId.isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少modelId参数"));
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
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("保存模型采样设定失败: " + e.getMessage()));
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
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少modelId参数"));
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
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("查询模型采样设定失败: " + e.getMessage()));
		}
	}
	
	private void handleModelSamplingSettingListRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			Map<String, Object> data = ModelSamplingService.getInstance().listSamplingSettings();
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("获取采样配置列表请求时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取采样配置列表失败: " + e.getMessage()));
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
			String samplingConfigName = JsonUtil.getJsonStringAny(obj, "", "samplingConfigName", "configName");
			if (samplingConfigName.isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少samplingConfigName参数"));
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
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("新增或更新采样配置失败: " + e.getMessage()));
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
			String samplingConfigName = JsonUtil.getJsonStringAny(obj, "", "samplingConfigName", "configName");
			if (samplingConfigName.isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少samplingConfigName参数"));
				return;
			}
			Map<String, Object> data = ModelSamplingService.getInstance().deleteSamplingConfig(samplingConfigName);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("删除采样配置请求时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("删除采样配置失败: " + e.getMessage()));
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
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("停止服务失败: " + e.getMessage()));
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
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			LlamaServer.sendTextResponse(ctx, LlamaServer.getConsoleBufferText());
		} catch (Exception e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("读取控制台日志失败: " + e.getMessage()));
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
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的llamaBinPath参数"));
				return;
			}

			List<String> devices = LlamaServerManager.getInstance().handleListDevices(llamaBinPath);
			Map<String, Object> data = new HashMap<>();
			data.put("devices", devices);

			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("获取设备列表时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取设备列表失败: " + e.getMessage()));
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
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("远程节点调用失败: code=" + result.getStatusCode()));
			}
		} catch (Exception e) {
			logger.warn("获取远程节点设备列表失败: nodeId={}, error={}", nodeId, e.getMessage());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取远程节点设备列表失败: " + e.getMessage()));
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
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}

			JsonElement root = JsonUtil.fromJson(content, JsonElement.class);
			if (root == null || !root.isJsonObject()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体必须为JSON对象"));
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

			if (cmd != null) cmd = cmd.trim();
			if (extraParams != null) extraParams = extraParams.trim();
			if ((cmd == null || cmd.isEmpty()) && (extraParams == null || extraParams.isEmpty())) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的启动参数"));
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

			if(device.size() == 1) {
				String onlyOneDevice = device.get(0);
				if("All".equals(onlyOneDevice)) {

				}else {
					combinedCmd += " --device " + onlyOneDevice;
				}
			}else {
				combinedCmd += " --device ";
				combinedCmd += ParamTool.quoteIfNeeded(String.join(",", device));
			}
			combinedCmd += " --main-gpu " + mg;

			logger.info("[显存估算] 本地执行: modelId={}, llamaBinPath={}, cmd={}", modelId, llamaBinPathSelect, combinedCmd);

			Map<String, Object> data = new HashMap<>();
			List<String> cmdlist = ParamTool.splitCmdArgs(combinedCmd);
			String output = LlamaServerManager.getInstance().handleFitParam(llamaBinPathSelect, modelId, enableVision, cmdlist);

			Pattern numberPattern = Pattern.compile("(?:llama|common)_params_fit_impl: projected to use (\\d+) MiB");
			Matcher numberMatcher = numberPattern.matcher(output);
			if (numberMatcher.find()) {
			    String value = numberMatcher.group(1);
			    data.put("vram", value);
			    logger.info("[显存估算] 成功: modelId={}, vram={} MiB", modelId, value);
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
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("估算显存失败: " + e.getMessage()));
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
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("远程节点调用失败: code=" + result.getStatusCode()));
			}
		} catch (Exception e) {
			logger.warn("[显存估算] 远程节点调用异常: nodeId={}, error={}", nodeId, e.getMessage());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("调用远程节点失败: " + e.getMessage()));
		}
	}
}
