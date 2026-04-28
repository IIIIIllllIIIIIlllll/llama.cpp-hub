package org.mark.llamacpp.ollama;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.service.ModelRequestTracker;
import org.mark.llamacpp.server.struct.ActiveRequest;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * 	嵌入模型的API。
 */
public class OllamaEmbedService {
	
	
	/**
	 * 	
	 */
	private static final Logger logger = LoggerFactory.getLogger(OllamaEmbedService.class);
	
	/**
	 * 	
	 */
	private final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();
	
	
	
	public OllamaEmbedService() {
		
	}
	
	/**
	 * 	处理嵌入请求。
	 * @param ctx
	 * @param request
	 */
	public void handleEmbed(ChannelHandlerContext ctx, FullHttpRequest request) {
		if (request.method() != HttpMethod.POST) {
			Ollama.sendOllamaError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "Only POST method is supported");
			return;
		}
		
		String content = request.content().toString(StandardCharsets.UTF_8);
		logger.info("收到 Ollama embed 请求: {}", content);
		if (content == null || content.trim().isEmpty()) {
			Ollama.sendOllamaError(ctx, HttpResponseStatus.BAD_REQUEST, "Request body is empty");
			return;
		}
		
		JsonObject ollamaReq = null;
		try {
			ollamaReq = JsonUtil.fromJson(content, JsonObject.class);
		} catch (Exception e) {
			Ollama.sendOllamaError(ctx, HttpResponseStatus.BAD_REQUEST, "Request body parse failed");
			return;
		}
		if (ollamaReq == null) {
			Ollama.sendOllamaError(ctx, HttpResponseStatus.BAD_REQUEST, "Request body parse failed");
			return;
		}
		
		LlamaServerManager manager = LlamaServerManager.getInstance();
		final String modelName = JsonUtil.getJsonString(ollamaReq, "model", null);
		if (modelName == null || modelName.isBlank()) {
			Ollama.sendOllamaError(ctx, HttpResponseStatus.BAD_REQUEST, "Missing required parameter: model");
			return;
		}
		
		if (!manager.getLoadedProcesses().containsKey(modelName)) {
			Ollama.sendOllamaError(ctx, HttpResponseStatus.NOT_FOUND, "Model not found: " + modelName);
			return;
		}
		
		///////////////////////////////////////////////////////////////////////////////////////////////////////////////////
		
		Integer port = manager.getModelPort(modelName);
		if (port == null) {
			Ollama.sendOllamaError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Model port not found: " + modelName);
			return;
		}
		
		JsonObject openAiReq = OllamaApiTool.toOpenAIEmbeddingsRequest(ollamaReq);
		if (openAiReq == null) {
			Ollama.sendOllamaError(ctx, HttpResponseStatus.BAD_REQUEST, "Missing required parameter: input");
			return;
		}
		openAiReq.addProperty("model", modelName);
		
		String requestBody = JsonUtil.toJson(openAiReq);
		
		this.worker.execute(() -> {
			String requestId = ModelRequestTracker.getInstance().createRequest(modelName, "/api/embed");
			HttpURLConnection connection = null;
			try {
				long startNs = System.nanoTime();
				String targetUrl = String.format("http://localhost:%d/v1/embeddings", port.intValue());
				URL url = URI.create(targetUrl).toURL();
				connection = (HttpURLConnection) url.openConnection();
				connection.setRequestMethod("POST");
				connection.setConnectTimeout(36000 * 1000);
				connection.setReadTimeout(36000 * 1000);
				connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
				connection.setDoOutput(true);
				byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
				connection.setRequestProperty("Content-Length", String.valueOf(input.length));
				try (OutputStream os = connection.getOutputStream()) {
					os.write(input, 0, input.length);
				}
				
				int responseCode = connection.getResponseCode();
				ModelRequestTracker.getInstance().updatePhase(requestId, ActiveRequest.Phase.GENERATION);
				String responseBody = OllamaApiTool.readBody(connection, responseCode >= 200 && responseCode < 300);
				long totalDurationNs = Math.max(0L, System.nanoTime() - startNs);
				if (!(responseCode >= 200 && responseCode < 300)) {
					String msg = OllamaApiTool.extractOpenAIErrorMessage(responseBody);
					Ollama.sendOllamaError(ctx, HttpResponseStatus.valueOf(responseCode), msg == null ? responseBody : msg);
					return;
				}
				
				JsonObject parsed = null;
				try {
					parsed = JsonUtil.fromJson(responseBody, JsonObject.class);
				} catch (Exception ignore) {
				}
				// 回复客户端
				Map<String, Object> out = OllamaApiTool.toOllamaEmbedResponse(modelName, parsed, totalDurationNs);
				Ollama.sendOllamaChunkedJson(ctx, HttpResponseStatus.OK, out);
			} catch (Exception e) {
				logger.info("处理Ollama embed请求时发生错误", e);
				Ollama.sendOllamaError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, e.getMessage());
			} finally {
				ModelRequestTracker.getInstance().removeRequest(requestId);
				if (connection != null) {
					connection.disconnect();
				}
			}
		});
		
	}
}
