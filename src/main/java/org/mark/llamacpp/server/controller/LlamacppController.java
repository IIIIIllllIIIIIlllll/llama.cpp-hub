package org.mark.llamacpp.server.controller;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
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
import org.mark.llamacpp.server.struct.LlamaCppConfig;
import org.mark.llamacpp.server.struct.LlamaCppDataStruct;
import org.mark.llamacpp.server.tools.CommandLineRunner;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.tools.ParamTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.CharsetUtil;

/**
 * 	
 */
public class LlamacppController implements BaseController {

	private static final Logger logger = LoggerFactory.getLogger(LlamacppController.class);
	
	
	public LlamacppController() {
		
	}
	
	/**
	 * 	
	 */
	public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 添加一个llamacpp
		if (uri.startsWith("/api/llamacpp/add")) {
			this.handleLlamaCppAdd(ctx, request);
			return true;
		}
		// 移除
		if (uri.startsWith("/api/llamacpp/remove")) {
			this.handleLlamaCppRemove(ctx, request);
			return true;
		}
		// 列出全部
		if (uri.startsWith("/api/llamacpp/list")) {
			this.handleLlamaCppList(ctx, request);
			return true;
		}
		// 执行测试
		if (uri.startsWith("/api/llamacpp/test")) {
			this.handleLlamaCppTest(ctx, request);
			return true;
		}
		
		// 代码补全
		if (uri.startsWith("/infill")) {
			this.handleInfillRequest(ctx, request);
			return true;
		}
		
		// 处理分词
		if (uri.startsWith("/tokenize")) {
			this.handleTokenizeRequest(ctx, request);
			return true;
		}
		// 处理模板
		if (uri.startsWith("/apply-template")) {
			this.handleApplyTemplateRequest(ctx, request);
			return true;
		}
		
		return false;
	}
	
	
	
	/**
	 * 添加llamacpp目录
	 *
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleLlamaCppAdd(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}
			LlamaCppDataStruct reqData = JsonUtil.fromJson(content, LlamaCppDataStruct.class);
			if (reqData == null || reqData.getPath() == null || reqData.getPath().trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("path不能为空"));
				return;
			}

			Path configFile = LlamaServer.getLlamaCppConfigPath();
			LlamaCppConfig cfg = LlamaServer.readLlamaCppConfig(configFile);
			List<LlamaCppDataStruct> items = cfg.getItems();
			if (items == null) {
				items = new ArrayList<>();
				cfg.setItems(items);
			}
			String normalized = reqData.getPath().trim();
			Path validated = this.validateAndNormalizeLlamaBinDirectory(normalized);
			if (validated == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("目录无效、不可访问或缺少llama-server可执行文件"));
				return;
			}
			normalized = validated.toString();
			boolean exists = false;
			for (LlamaCppDataStruct i : items) {
				if (i != null && i.getPath() != null && normalized.equals(i.getPath().trim())) {
					exists = true;
					break;
				}
			}
			if (exists) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("路径已存在"));
				return;
			}
			LlamaCppDataStruct item = new LlamaCppDataStruct();
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
			LlamaServer.writeLlamaCppConfig(configFile, cfg);

			Map<String, Object> data = new HashMap<>();
			data.put("message", "添加llama.cpp路径成功");
			data.put("added", item);
			data.put("count", items.size());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("添加llama.cpp路径时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("添加llama.cpp路径失败: " + e.getMessage()));
		}
	}
	
	/**
	 * 移除一个llamcpp目录
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleLlamaCppRemove(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}
			LlamaCppDataStruct reqData = JsonUtil.fromJson(content, LlamaCppDataStruct.class);
			if (reqData == null || reqData.getPath() == null || reqData.getPath().trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("path不能为空"));
				return;
			}
			String normalized = reqData.getPath().trim();

			Path configFile = LlamaServer.getLlamaCppConfigPath();
			LlamaCppConfig cfg = LlamaServer.readLlamaCppConfig(configFile);
			List<LlamaCppDataStruct> items = cfg.getItems();
			int before = items == null ? 0 : items.size();
			boolean changed = false;
			if (items != null) {
				changed = items.removeIf(i -> normalized.equals(i == null || i.getPath() == null ? "" : i.getPath().trim()));
			}
			LlamaServer.writeLlamaCppConfig(configFile, cfg);

			Map<String, Object> data = new HashMap<>();
			data.put("message", "移除llama.cpp路径成功");
			data.put("removed", normalized);
			data.put("count", items == null ? 0 : items.size());
			data.put("changed", changed || before != (items == null ? 0 : items.size()));
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("移除llama.cpp路径时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("移除llama.cpp路径失败: " + e.getMessage()));
		}
	}
	
	private Path validateAndNormalizeLlamaBinDirectory(String input) {
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
		Path real;
		try {
			real = p.toRealPath();
		} catch (Exception e) {
			real = p;
		}
		
		File dir = real.toFile();
		File serverLinux = new File(dir, "llama-server");
		File serverWin = new File(dir, "llama-server.exe");
		if (!(serverLinux.exists() && serverLinux.isFile()) && !(serverWin.exists() && serverWin.isFile())) {
			return null;
		}
		return real;
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
	
	/**
	 * 返回全部的llamacpp目录
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleLlamaCppList(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			String nodeId = ParamTool.getQueryParam(request.uri()).get("nodeId");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				this.handleLlamaCppListRemote(ctx, nodeId);
				return;
			}

			Path configFile = LlamaServer.getLlamaCppConfigPath();
			LlamaCppConfig cfg = LlamaServer.readLlamaCppConfig(configFile);
			List<LlamaCppDataStruct> items = cfg.getItems();
			List<LlamaCppDataStruct> list = LlamaServer.scanLlamaCpp();
			if (list != null && list.size() > 0)
				items.addAll(list);

			Map<String, Object> data = new HashMap<>();
			data.put("items", items);
			data.put("count", items == null ? 0 : items.size());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("获取llama.cpp路径列表时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取llama.cpp路径列表失败: " + e.getMessage()));
		}
	}

	private void handleLlamaCppListRemote(ChannelHandlerContext ctx, String nodeId) {
		try {
			NodeManager.HttpResult result = NodeManager.getInstance().callRemoteApi(
					nodeId, "GET", "api/llamacpp/list", null);
			if (result.isSuccess()) {
				NodeManager.writeHttpResultToChannel(ctx, result, "[llamacpp远程]");
			} else {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("远程节点调用失败: code=" + result.getStatusCode()));
			}
		} catch (Exception e) {
			logger.warn("获取远程节点llama.cpp列表失败: nodeId={}, error={}", nodeId, e.getMessage());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取远程节点llama.cpp列表失败: " + e.getMessage()));
		}
	}
	
	/**
	 * 	
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleLlamaCppTest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}
			LlamaCppDataStruct reqData = JsonUtil.fromJson(content, LlamaCppDataStruct.class);
			if (reqData == null || reqData.getPath() == null || reqData.getPath().trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("path不能为空"));
				return;
			}

			String llamaBinPath = reqData.getPath().trim();
			String exeName = "llama-cli";
			File exeFile = new File(llamaBinPath, exeName);
			if (!exeFile.exists() || !exeFile.isFile()) {
				String osName = System.getProperty("os.name");
				String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
				if (os.contains("win")) {
					File exeFileWin = new File(llamaBinPath, exeName + ".exe");
					if (exeFileWin.exists() && exeFileWin.isFile()) {
						exeFile = exeFileWin;
					}
				}
			}
			if (!exeFile.exists() || !exeFile.isFile()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("llama-cli可执行文件不存在: " + exeFile.getAbsolutePath()));
				return;
			}

			String cmdVersion = ParamTool.quoteIfNeeded(exeFile.getAbsolutePath()) + " --version";
			CommandLineRunner.CommandResult versionResult = CommandLineRunner.execute(
					new String[] { exeFile.getAbsolutePath(), "--version" }, 30);

			String cmdListDevices = ParamTool.quoteIfNeeded(exeFile.getAbsolutePath()) + " --list-devices";
			CommandLineRunner.CommandResult listDevicesResult = CommandLineRunner.execute(
					new String[] { exeFile.getAbsolutePath(), "--list-devices" }, 30);

			Map<String, Object> data = new HashMap<>();

			Map<String, Object> version = new HashMap<>();
			version.put("command", cmdVersion);
			version.put("exitCode", versionResult.getExitCode());
			version.put("output", versionResult.getOutput());
			version.put("error", versionResult.getError());

			Map<String, Object> devices = new HashMap<>();
			devices.put("command", cmdListDevices);
			devices.put("exitCode", listDevicesResult.getExitCode());
			devices.put("output", listDevicesResult.getOutput());
			devices.put("error", listDevicesResult.getError());

			data.put("version", version);
			data.put("listDevices", devices);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("执行llama.cpp测试命令时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("执行llama.cpp测试失败: " + e.getMessage()));
		}
	}
	
	/**
	 * 	分词器接口，调用llamacpp对应的API进行分词。
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleTokenizeRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		HttpURLConnection connection = null;
		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "请求体为空");
				return;
			}
			JsonObject obj = JsonUtil.fromJson(content, JsonObject.class);
			if (obj == null) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "请求体解析失败");
				return;
			}
			String text = JsonUtil.getJsonString(obj, "content", null);
			if (text == null) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "缺少必需的content参数");
				return;
			}
			boolean addSpecial = ParamTool.parseJsonBoolean(obj, "add_special", true);
			boolean parseSpecial = ParamTool.parseJsonBoolean(obj, "parse_special", true);
			boolean withPieces = ParamTool.parseJsonBoolean(obj, "with_pieces", false);

			LlamaServerManager manager = LlamaServerManager.getInstance();
			Map<String, String> params = ParamTool.getQueryParam(request.uri());
			String modelId = JsonUtil.getJsonString(obj, "modelId", null);
			if (modelId == null || modelId.trim().isEmpty()) {
				modelId = params.get("modelId");
			}
			if (modelId != null) {
				modelId = modelId.trim();
				if (modelId.isEmpty()) {
					modelId = null;
				}
			}
			if (modelId == null) {
				modelId = manager.getFirstModelName();
			} else if (!manager.getLoadedProcesses().containsKey(modelId)) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.NOT_FOUND, "模型未加载: " + modelId);
				return;
			}
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "模型未加载");
				return;
			}
			Integer port = manager.getModelPort(modelId);
			if (port == null) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "未找到模型端口: " + modelId);
				return;
			}

			JsonObject forward = new JsonObject();
			forward.addProperty("content", text);
			forward.addProperty("add_special", addSpecial);
			forward.addProperty("parse_special", parseSpecial);
			forward.addProperty("with_pieces", withPieces);

			String targetUrl = String.format("http://localhost:%d/tokenize", port.intValue());
			URL url = URI.create(targetUrl).toURL();
			connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("POST");
			connection.setDoOutput(true);
			connection.setConnectTimeout(30000);
			connection.setReadTimeout(30000);
			connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

			byte[] outBytes = JsonUtil.toJson(forward).getBytes(StandardCharsets.UTF_8);
			connection.setRequestProperty("Content-Length", String.valueOf(outBytes.length));
			try (OutputStream os = connection.getOutputStream()) {
				os.write(outBytes);
			}

			int responseCode = connection.getResponseCode();
			String responseBody = this.readBody(connection, responseCode >= 200 && responseCode < 300);
			JsonElement parsed = null;
			try {
				parsed = JsonUtil.fromJson(responseBody, JsonElement.class);
			} catch (Exception ignore) {
			}

			if (responseCode >= 200 && responseCode < 300) {
				if (parsed != null) {
					LlamaServer.sendJsonResponse(ctx, parsed);
				} else {
					LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_GATEWAY, "模型返回了非JSON响应");
				}
				return;
			}

			if (parsed != null) {
				LlamaServer.sendJsonResponse(ctx, parsed);
				return;
			}
			String msg = responseBody == null || responseBody.isBlank() ? ("模型错误: HTTP " + responseCode) : responseBody;
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_GATEWAY, msg);
		} catch (Exception e) {
			logger.info("tokenize失败", e);
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "tokenize失败: " + e.getMessage());
		} finally {
			if (connection != null) {
				try {
					connection.disconnect();
				} catch (Exception ignore) {
				}
			}
		}
	}
	
	/**
	 * 	模板化接口，调用llamacpp对应的API进行模板化。
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleApplyTemplateRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		HttpURLConnection connection = null;
		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "请求体为空");
				return;
			}
			JsonObject obj = JsonUtil.fromJson(content, JsonObject.class);
			if (obj == null) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "请求体解析失败");
				return;
			}

			Map<String, String> params = ParamTool.getQueryParam(request.uri());
			String modelId = JsonUtil.getJsonString(obj, "modelId", null);
			if (modelId == null || modelId.trim().isEmpty()) {
				modelId = params.get("modelId");
			}
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "缺少必需的modelId参数");
				return;
			}
			modelId = modelId.trim();

			if (!obj.has("messages") || obj.get("messages") == null || obj.get("messages").isJsonNull() || !obj.get("messages").isJsonArray()) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "缺少必需的messages参数");
				return;
			}

			LlamaServerManager manager = LlamaServerManager.getInstance();
			if (!manager.getLoadedProcesses().containsKey(modelId)) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.NOT_FOUND, "模型未加载: " + modelId);
				return;
			}
			Integer port = manager.getModelPort(modelId);
			if (port == null) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "未找到模型端口: " + modelId);
				return;
			}

			JsonObject forward = new JsonObject();
			forward.add("messages", obj.get("messages"));

			String targetUrl = String.format("http://localhost:%d/apply-template", port.intValue());
			URL url = URI.create(targetUrl).toURL();
			connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("POST");
			connection.setDoOutput(true);
			connection.setConnectTimeout(30000);
			connection.setReadTimeout(30000);
			connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

			byte[] outBytes = JsonUtil.toJson(forward).getBytes(StandardCharsets.UTF_8);
			connection.setRequestProperty("Content-Length", String.valueOf(outBytes.length));
			try (OutputStream os = connection.getOutputStream()) {
				os.write(outBytes);
			}

			int responseCode = connection.getResponseCode();
			String responseBody = this.readBody(connection, responseCode >= 200 && responseCode < 300);
			JsonElement parsed = null;
			try {
				parsed = JsonUtil.fromJson(responseBody, JsonElement.class);
			} catch (Exception ignore) {
			}

			if (responseCode >= 200 && responseCode < 300) {
				if (parsed != null && parsed.isJsonObject()) {
					JsonObject outObj = parsed.getAsJsonObject();
					if (outObj.has("prompt") && outObj.get("prompt") != null && !outObj.get("prompt").isJsonNull()) {
						Map<String, Object> data = new HashMap<>();
						data.put("prompt", outObj.get("prompt").getAsString());
						LlamaServer.sendJsonResponse(ctx, data);
						return;
					}
				}
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_GATEWAY, "模型响应缺少prompt字段");
				return;
			}

			if (parsed != null) {
				LlamaServer.sendJsonResponse(ctx, parsed);
				return;
			}
			String msg = responseBody == null || responseBody.isBlank() ? ("模型错误: HTTP " + responseCode) : responseBody;
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_GATEWAY, msg);
		} catch (Exception e) {
			logger.info("apply-template失败", e);
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "apply-template失败: " + e.getMessage());
		} finally {
			if (connection != null) {
				try {
					connection.disconnect();
				} catch (Exception ignore) {
				}
			}
		}
	}
	
	private void handleInfillRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		HttpURLConnection connection = null;
		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "请求体为空");
				return;
			}
			
			JsonObject obj = null;
			try {
				obj = JsonUtil.fromJson(content, JsonObject.class);
			} catch (Exception ignore) {
			}

			LlamaServerManager manager = LlamaServerManager.getInstance();
			Map<String, String> params = ParamTool.getQueryParam(request.uri());
			String modelId = params.get("modelId");
			if ((modelId == null || modelId.trim().isEmpty()) && obj != null) {
				modelId = JsonUtil.getJsonString(obj, "modelId", null);
				if (modelId == null || modelId.trim().isEmpty()) {
					modelId = JsonUtil.getJsonString(obj, "model", null);
				}
			}
			if (modelId != null) {
				modelId = modelId.trim();
				if (modelId.isEmpty()) {
					modelId = null;
				}
			}
			if (modelId == null) {
				modelId = manager.getFirstModelName();
			} else if (!manager.getLoadedProcesses().containsKey(modelId)) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.NOT_FOUND, "模型未加载: " + modelId);
				return;
			}
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "模型未加载");
				return;
			}
			Integer port = manager.getModelPort(modelId);
			if (port == null) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "未找到模型端口: " + modelId);
				return;
			}
			
			String targetUrl = String.format("http://localhost:%d/infill", port.intValue());
			connection = (HttpURLConnection) URI.create(targetUrl).toURL().openConnection();
			connection.setRequestMethod("POST");
			connection.setDoOutput(true);
			connection.setConnectTimeout(30000);
			connection.setReadTimeout(30000);
			// 把客户端的请求头全发过去
			for (Map.Entry<String, String> entry : request.headers()) {
				String key = entry.getKey();
				if (key == null) {
					continue;
				}
				if ("Host".equalsIgnoreCase(key)
						|| "Connection".equalsIgnoreCase(key)
						|| "Content-Length".equalsIgnoreCase(key)
						|| "Transfer-Encoding".equalsIgnoreCase(key)) {
					continue;
				}
				connection.setRequestProperty(key, entry.getValue());
			}
			
			byte[] outBytes = content.getBytes(StandardCharsets.UTF_8);
			connection.setRequestProperty("Content-Length", String.valueOf(outBytes.length));
			try (OutputStream os = connection.getOutputStream()) {
				os.write(outBytes);
			}

			int responseCode = connection.getResponseCode();
			String responseBody = this.readBody(connection, responseCode >= 200 && responseCode < 300);
			JsonElement parsed = null;
			try {
				parsed = JsonUtil.fromJson(responseBody, JsonElement.class);
			} catch (Exception ignore) {
			}

			if (responseCode >= 200 && responseCode < 300) {
				if (parsed != null) {
					LlamaServer.sendJsonResponse(ctx, parsed);
				} else {
					LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_GATEWAY, "模型返回了非JSON响应");
				}
				return;
			}

			if (parsed != null) {
				LlamaServer.sendJsonResponse(ctx, parsed);
				return;
			}
			String msg = responseBody == null || responseBody.isBlank() ? ("模型错误: HTTP " + responseCode) : responseBody;
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_GATEWAY, msg);
		} catch (Exception e) {
			logger.info("infill代理失败", e);
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "infill失败: " + e.getMessage());
		} finally {
			if (connection != null) {
				try {
					connection.disconnect();
				} catch (Exception ignore) {
				}
			}
		}
	}
	

	private String readBody(HttpURLConnection connection, boolean ok) {
		if (connection == null) return "";
		InputStream in = null;
		try {
			in = ok ? connection.getInputStream() : connection.getErrorStream();
			if (in == null) return "";
			try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
				StringBuilder sb = new StringBuilder();
				String line;
				while ((line = br.readLine()) != null) {
					sb.append(line);
				}
				return sb.toString();
			}
		} catch (Exception e) {
			return "";
		}
	}
}
