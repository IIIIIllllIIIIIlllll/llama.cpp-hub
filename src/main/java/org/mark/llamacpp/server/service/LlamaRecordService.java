package org.mark.llamacpp.server.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.mark.llamacpp.server.struct.Timing;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 处理 llama.cpp 响应中的 timings 性能参数，并持久化累计记录。
 */
public class LlamaRecordService {
	
	private static final LlamaRecordService INSTANCE = new LlamaRecordService();
	private final Gson gson = new Gson();
	private static final String RECORD_DIR = "cache/record/";
	private final Map<String, Timing> recordMap = new ConcurrentHashMap<>();

	public static LlamaRecordService getInstance() {
		return INSTANCE;
	}

	public LlamaRecordService() {
		try {
			Files.createDirectories(Paths.get(RECORD_DIR));
			loadRecords();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * 从本地目录加载所有已保存的 JSON 记录。
	 */
	private void loadRecords() {
		try (Stream<java.nio.file.Path> paths = Files.list(Paths.get(RECORD_DIR))) {
			paths.filter(path -> path.toString().endsWith(".json"))
				 .forEach(path -> {
					try {
						String content = new String(Files.readAllBytes(path));
						Timing timing = this.gson.fromJson(content, Timing.class);
						String fileName = path.getFileName().toString().replace(".json", "");
						this.recordMap.put(fileName, timing);
					} catch (IOException e) {
						e.printStackTrace();
					}
				});
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * 处理流式响应中的 timings 数据，将其累加到对应模型的记录中并持久化。
	 * 
	 * @param modelId 模型唯一标识
	 * @param json    包含 timings 数据的 JSON 字符串
	 * @return 解析出的本次 Timing 数据
	 */
	public Timing handleStream(String modelId, String json) {
		synchronized (this) {
			Timing timing = this.recordMap.computeIfAbsent(modelId, k -> new Timing());
			Timing data = null;
			try {
				JsonObject root = JsonParser.parseString(json).getAsJsonObject();
				if (root.has("timings")) {
					data = this.gson.fromJson(root.get("timings"), Timing.class);
					
					timing.setCache_n(timing.getCache_n() + data.getCache_n());
					timing.setPrompt_n(timing.getPrompt_n() + data.getPrompt_n());
					timing.setPrompt_ms(timing.getPrompt_ms() + data.getPrompt_ms());
					timing.setPrompt_per_token_ms(timing.getPrompt_per_token_ms() + data.getPrompt_per_token_ms());
					timing.setPrompt_per_second(timing.getPrompt_per_second() + data.getPrompt_per_second());
					timing.setPredicted_n(timing.getPredicted_n() + data.getPredicted_n());
					timing.setPredicted_ms(timing.getPredicted_ms() + data.getPredicted_ms());
					timing.setPredicted_per_token_ms(timing.getPredicted_per_token_ms() + data.getPredicted_per_token_ms());
					timing.setPredicted_per_second(timing.getPredicted_per_second() + data.getPredicted_per_second());
					this.recordTiming(modelId, timing, data);
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
			return data;
		}
	}

	/**
	 * 根据模型ID获取累计性能记录。
	 * 
	 * @param modelId 模型ID
	 * @return 累计的 Timing 记录，不存在则返回 null
	 */
	public Timing getRecord(String modelId) {
		return this.recordMap.get(modelId);
	}

	/**
	 * 将累积的 timings 数据以 JSON 格式覆盖写入本地文件。
	 * 同时将本次请求的 timings 逐行追加到日志文件。
	 * 
	 * @param modelId    模型ID，用作文件名
	 * @param timing     累计的性能数据，写入 .json
	 * @param requestTiming 本次请求的原始性能数据，逐行追加到 .log
	 */
	private void recordTiming(String modelId, Timing timing, Timing requestTiming) {
		if (modelId == null || timing == null) {
			return;
		}
		// 累计计算
		String filePath = RECORD_DIR + modelId + ".json";
		String content = this.gson.toJson(timing);
		
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath, false))) {
			writer.write(content);
		} catch (IOException e) {
			e.printStackTrace();
		}
		// 请求记录，以模型名字.log 逐行追加
		if (requestTiming != null) {
			String logPath = RECORD_DIR + modelId + ".log";
			String logLine = this.gson.toJson(requestTiming);
			try (BufferedWriter writer = new BufferedWriter(new FileWriter(logPath, true))) {
				writer.write(logLine);
				writer.newLine();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
}
