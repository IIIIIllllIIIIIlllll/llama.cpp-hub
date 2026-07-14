package org.mark.llamacpp.server.controller;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;

/**
 * 	
 */
public class ParamController implements BaseController {
	
	private static final Logger logger = LoggerFactory.getLogger(ParamController.class);

	private static final String I18N_METHOD_GET_ONLY = "common.method.get.only";
	private static final String I18N_PARAM_CONFIG_NOTFOUND = "api.error.param.config.notfound";
	private static final String I18N_PARAM_LIST_FAILED = "api.error.param.list.failed";
	private static final String I18N_PARAM_BENCHMARK_LIST_FAILED = "api.error.param.benchmark.list.failed";
	
	public ParamController() {
		
	}
	
	

	@Override
	public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request)
			throws RequestMethodException {
		
        if (uri.equals("/api/models/param/server/list")) {
            this.handleParamServerListRequest(ctx, request);
            return true;
        }
        if (uri.equals("/api/models/param/benchmark/list")) {
            this.handleParamBenchmarkListRequest(ctx, request);
            return true;
        }
		
		return false;
	}

	
	/**
	 * 处理server参数列表请求
	 * 返回 server-params.json 文件的全部内容
	 *
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleParamServerListRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, I18N_METHOD_GET_ONLY);

		try {
			InputStream inputStream = getClass().getClassLoader().getResourceAsStream("server-params.json");
			if (inputStream == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_CONFIG_NOTFOUND + ": server-params.json"));
				return;
			}
			
			String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
			inputStream.close();
			
			Object parsed = JsonUtil.fromJson(content, Object.class);
			
			Map<String, Object> response = new HashMap<>();
			response.put("success", true);
			response.put("params", parsed);
			LlamaServer.sendJsonResponse(ctx, response);
		} catch (Exception e) {
			logger.info("获取参数列表时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_LIST_FAILED + ": " + e.getMessage()));
		}
	}
	
	
	/**
	 * 	处理benchmark参数列表请求
	 *  返回 benchmark-params.json 文件的全部内容
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleParamBenchmarkListRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, I18N_METHOD_GET_ONLY);

		try {
			InputStream inputStream = getClass().getClassLoader().getResourceAsStream("benchmark-params.json");
			if (inputStream == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_CONFIG_NOTFOUND + ": benchmark-params.json"));
				return;
			}

			String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
			inputStream.close();
			Object parsed = JsonUtil.fromJson(content, Object.class);

			Map<String, Object> response = new HashMap<>();
			response.put("success", true);
			response.put("params", parsed);
			LlamaServer.sendJsonResponse(ctx, response);
		} catch (Exception e) {
			logger.info("获取基准测试参数列表时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_BENCHMARK_LIST_FAILED + ": " + e.getMessage()));
		}
	}
}
