package org.mark.llamacpp.server.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.mark.llamacpp.record.BinaryRequestLog;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.record.RequestLogRecord;
import org.mark.llamacpp.server.struct.ActiveRequest;
import org.mark.llamacpp.server.struct.Timing;
import org.mark.llamacpp.server.struct.TokenSummaryEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * 处理 llama.cpp 响应中的 timings 性能参数，并持久化累计记录。
 */
public class LlamaRecordService {
    static final Logger logger = LoggerFactory.getLogger(LlamaRecordService.class);
	
	private static final LlamaRecordService INSTANCE = new LlamaRecordService();
	private final Gson gson = JsonUtil.gson();
	private static final String RECORD_DIR = "cache/record/";
	private final Map<String, LogEntry> logMap = new ConcurrentHashMap<>();
	private final AtomicLong totalRecordCount = new AtomicLong(0);
	private final Map<String, TokenSummaryEntry> tokenSummaryCache = new ConcurrentHashMap<>();
	private final ScheduledExecutorService sweeper;

	/**
	 * 日志句柄空闲超时：超过该时间未写入的 BinaryRequestLog 会被关闭（下次写入时自动重开）。
	 * 解决旧实现中 FileChannel 打开后永不关闭的句柄泄漏。
	 */
	private static final long LOG_IDLE_TIMEOUT_MS = 10 * 60 * 1000;
	private static final long LOG_SWEEP_INTERVAL_MS = 5 * 60 * 1000;

	/**
	 * logMap 的条目：append 与 close 在同一个 entry 监视器下进行，保证无竞态。
	 */
	private static final class LogEntry {
		final BinaryRequestLog log;
		volatile long lastUsed;
		volatile boolean closed;

		LogEntry(BinaryRequestLog log) {
			this.log = log;
			this.lastUsed = System.currentTimeMillis();
		}
	}

	public static LlamaRecordService getInstance() {
		return INSTANCE;
	}

	public LlamaRecordService() {
		try {
			Files.createDirectories(Paths.get(RECORD_DIR));
			this.migrateOldLogs();
			this.migrateHeaderV1toV2();
			this.loadTotalRecordCount();
		} catch (IOException e) {
			logger.error("Failed to initialize LlamaRecordService", e);
		}
		this.sweeper = new ScheduledThreadPoolExecutor(1,
				Thread.ofVirtual().name("record-log-sweeper-", 0).factory());
		this.sweeper.scheduleWithFixedDelay(this::sweepIdleLogs, LOG_SWEEP_INTERVAL_MS, LOG_SWEEP_INTERVAL_MS,
				TimeUnit.MILLISECONDS);
	}

    private void loadTotalRecordCount() {
		Path dir = Paths.get(RECORD_DIR);
		if (!Files.exists(dir)) {
			return;
		}
		try (Stream<Path> paths = Files.list(dir)) {
			List<Path> binFiles = paths
				.filter(p -> p.toString().endsWith(".requests.bin"))
				.collect(java.util.stream.Collectors.toList());
			for (Path binPath : binFiles) {
				try (BinaryRequestLog log = new BinaryRequestLog(binPath)) {
					String modelId = binPath.getFileName().toString().replace(".requests.bin", "");
					this.totalRecordCount.addAndGet(log.getRecordCount());
					TokenSummaryEntry entry = new TokenSummaryEntry();
					entry.setModelId(modelId);
					entry.setTotalCacheTokens(log.getTotalCacheTokens());
					entry.setTotalPromptTokens(log.getTotalPromptTokens());
					entry.setTotalPredictedTokens(log.getTotalPredictedTokens());
					entry.setTotalTokens(log.getTotalPromptTokens() + log.getTotalPredictedTokens());
					entry.setTotalPromptMs(log.getTotalPromptMs());
					entry.setTotalPredictedMs(log.getTotalPredictedMs());
					entry.setTotalDraftTokens(log.getTotalDraftTokens());
					entry.setTotalDraftAccepted(log.getTotalDraftAccepted());
					entry.setMaxPredictedPerSecond(log.getMaxPredictedPerSecond());
					entry.setMaxPromptPerSecond(log.getMaxPromptPerSecond());
					entry.setTotalPredictedPerSecond(log.getTotalPredictedPerSecond());
					entry.setTotalPromptPerSecond(log.getTotalPromptPerSecond());
					entry.setRecordCount(log.getRecordCount());
					this.tokenSummaryCache.put(modelId, entry);
				} catch (Exception ignore) {
				}
			}
		} catch (IOException ignore) {
		}
    }

    public long getTotalRecordCount() {
		return this.totalRecordCount.get();
	}

    public List<TokenSummaryEntry> getTokenSummary() {
		return new ArrayList<>(this.tokenSummaryCache.values());
	}

	public TokenSummaryEntry getTokenSummaryEntry(String modelId) {
		return this.tokenSummaryCache.get(modelId);
	}

	private void updateTokenSummary(String modelId, RequestLogRecord record) {
		TokenSummaryEntry entry = this.tokenSummaryCache.computeIfAbsent(modelId, id -> {
			TokenSummaryEntry e = new TokenSummaryEntry();
			e.setModelId(id);
			return e;
		});
		// entry 内同步：多个虚拟线程并发累加时避免读-改-写丢更新（压测实测 399/400）
		synchronized (entry) {
			entry.setTotalCacheTokens(entry.getTotalCacheTokens() + record.cacheN);
			entry.setTotalPromptTokens(entry.getTotalPromptTokens() + record.promptN);
			entry.setTotalPredictedTokens(entry.getTotalPredictedTokens() + record.predictedN);
			entry.setTotalTokens(entry.getTotalPromptTokens() + entry.getTotalPredictedTokens());
			entry.setTotalPromptMs(entry.getTotalPromptMs() + record.promptMs);
			entry.setTotalPredictedMs(entry.getTotalPredictedMs() + record.predictedMs);
			entry.setTotalDraftTokens(entry.getTotalDraftTokens() + record.draftN);
			entry.setTotalDraftAccepted(entry.getTotalDraftAccepted() + record.draftNAccepted);
			if (record.predictedN > 1 && record.draftN == 0 && record.predictedPerSecond > entry.getMaxPredictedPerSecond()) {
				entry.setMaxPredictedPerSecond(record.predictedPerSecond);
			}
			if (record.predictedN > 1 && record.draftN == 0 && record.promptPerSecond > entry.getMaxPromptPerSecond()) {
				entry.setMaxPromptPerSecond(record.promptPerSecond);
			}
			if (record.predictedN > 1) {
				entry.setTotalPredictedPerSecond(entry.getTotalPredictedPerSecond() + record.predictedPerSecond);
				entry.setTotalPromptPerSecond(entry.getTotalPromptPerSecond() + record.promptPerSecond);
			}
			entry.setRecordCount(entry.getRecordCount() + 1);
		}
	}

    /**
     * 向指定模型的日志追加一条记录。
     * append 与清扫器的 close 在同一个 entry 监视器下互斥；
     * 若条目已被清扫关闭，则摘除后重建（重开文件），对调用方透明。
     */
    private void appendRecord(String modelId, RequestLogRecord record) throws IOException {
        while (true) {
            LogEntry entry = this.logMap.computeIfAbsent(modelId, id -> {
                try {
                    return new LogEntry(new BinaryRequestLog(Paths.get(RECORD_DIR + id + ".requests.bin")));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            synchronized (entry) {
                if (entry.closed) {
                    this.logMap.remove(modelId, entry);
                    continue;
                }
                entry.log.append(record);
                entry.lastUsed = System.currentTimeMillis();
                return;
            }
        }
    }

	/**
	 * 优雅关闭：停止空闲句柄清扫器并关闭全部日志句柄。仅由 JVM shutdown hook 调用。
	 */
	public void shutdown() {
		this.sweeper.shutdown();
		for (Map.Entry<String, LogEntry> e : this.logMap.entrySet()) {
			LogEntry entry = e.getValue();
			synchronized (entry) {
				if (entry.closed) {
					continue;
				}
				this.logMap.remove(e.getKey(), entry);
				try {
					entry.log.close();
				} catch (IOException ignore) {
				}
				entry.closed = true;
			}
		}
	}

    /**
     * 定时清扫：关闭空闲超过 LOG_IDLE_TIMEOUT_MS 的日志句柄。
     */
    private void sweepIdleLogs() {
        try {
            long threshold = System.currentTimeMillis() - LOG_IDLE_TIMEOUT_MS;
            for (Map.Entry<String, LogEntry> e : this.logMap.entrySet()) {
                LogEntry entry = e.getValue();
                if (entry.lastUsed >= threshold) {
                    continue;
                }
                synchronized (entry) {
                    if (entry.closed || entry.lastUsed >= threshold) {
                        continue;
                    }
                    // 先从 map 摘除，确保后续写入走重建路径，再关闭句柄
                    if (!this.logMap.remove(e.getKey(), entry)) {
                        continue;
                    }
                    try {
                        entry.log.close();
                    } catch (IOException ignore) {
                    }
                    entry.closed = true;
                    logger.info("关闭空闲日志句柄: {}", e.getKey());
                }
            }
        } catch (Exception e) {
            logger.warn("清扫空闲日志句柄失败", e);
        }
    }

    private static byte toEndpointByte(String endpoint) {
        if (endpoint == null) return 0;
        if (endpoint.contains("chat/completions")) return 0;
        if (endpoint.contains("completions")) return 1;
        if (endpoint.contains("embed")) return 2;
        if (endpoint.contains("messages")) return 3;
        if (endpoint.contains("/api/chat")) return 4;
        if (endpoint.contains("/api/embed")) return 5;
        if (endpoint.contains("generate")) return 6;
        if (endpoint.contains("rerank")) return 7;
        if (endpoint.contains("responses")) return 8;
        return 0;
    }

    private static byte toStatusByte(ActiveRequest.RequestStatus status) {
        if (status == null) return 1;
        switch (status) {
            case CREATED: return 0;
            case COMPLETED: return 1;
            case FAILED: return 2;
            case PROXYING: return 4;
            default: return 1;
        }
    }

    private static byte toPhaseByte(ActiveRequest.Phase phase) {
        if (phase == null) return 1;
        switch (phase) {
            case PREFILL: return 0;
            case GENERATION: return 1;
            default: return 1;
        }
    }

    /**
     * 启动时自动迁移旧的 .requests.log 和 .json 文件到 .requests.bin 二进制格式。
     * 迁移成功后，将旧文件移动到 bak/ 目录。
     */
	private void migrateOldLogs() {
		Path dir = Paths.get(RECORD_DIR);
		if (!Files.exists(dir)) {
			return;
		}
		Path bakDir = dir.resolve("bak");
		try {
			Files.createDirectories(bakDir);
		} catch (IOException ignore) {
		}

		try (Stream<Path> paths = Files.list(dir)) {
			List<Path> files = paths
				.filter(p -> p.toString().endsWith(".requests.log") || p.toString().endsWith(".json"))
				.collect(java.util.stream.Collectors.toList());

			for (Path filePath : files) {
				migrateOneFile(filePath, bakDir);
			}
		} catch (IOException e) {
			logger.error("Failed to list files during old log migration", e);
		}
	}
	/**
	 * 	兼容旧的文本日志内容，转为二进制文件。
	 */
	private void migrateOneFile(Path filePath, Path bakDir) {
		String fileName = filePath.getFileName().toString();

		if (fileName.endsWith(".requests.log")) {
			String modelId = fileName.replace(".requests.log", "");
			Path binPath = filePath.resolveSibling(modelId + ".requests.bin");
			try {
				List<String> lines = Files.readAllLines(filePath);
				try (BinaryRequestLog log = new BinaryRequestLog(binPath)) {
					for (String line : lines) {
						if (line == null || line.trim().isEmpty()) continue;
						try {
							log.appendFromJson(line);
						} catch (Exception ignore) {
						}
					}
				}
				Files.move(filePath, bakDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				logger.error("Failed to migrate file: {}", filePath, e);
			}
		} else if (fileName.endsWith(".json")) {
			try {
				Files.move(filePath, bakDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				logger.error("Failed to move JSON file to bak: {}", filePath, e);
			}
       }
    }

    /**
     * 启动时自动将 V1 header 升级到 V2，扫描所有记录计算 max 解码/预填充速度。
     */
    private void migrateHeaderV1toV2() throws IOException {
        Path dir = Paths.get(RECORD_DIR);
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.list(dir)) {
            List<Path> binFiles = paths
                .filter(p -> p.toString().endsWith(".requests.bin"))
                .collect(java.util.stream.Collectors.toList());
            for (Path binPath : binFiles) {
                String modelId = binPath.getFileName().toString().replace(".requests.bin", "");
                try {
                    boolean migrated = BinaryRequestLog.migrateV1toV2(binPath);
                    if (migrated) {
                        logger.info("Header V1->V2 migrated: {}", modelId);
                    }
                } catch (Exception e) {
                    logger.error("Header migration failed for {}: {}", modelId, e.getMessage(), e);
                }
            }
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
		return this.handleStream(modelId, json, null);
	}

	/**
	 * 处理流式响应中的 timings 数据，将其累加到对应模型的记录中并持久化。
	 * 若无 timings 但有 usage，则从 usage 提取 token 数据写入 .requests.log。
	 *
	 * @param modelId   模型唯一标识
	 * @param json      包含 timings 或 usage 的 JSON 字符串
	 * @param requestId 请求 ID（用于写入 .requests.log）
	 * @return 解析出的本次 Timing 数据
	 */
	public Timing handleStream(String modelId, String json, String requestId) {
		Timing data = null;
		try {
			JsonObject root = JsonParser.parseString(json).getAsJsonObject();
			if (root.has("timings")) {
				data = this.gson.fromJson(root.get("timings"), Timing.class);
				this.recordTiming(modelId, data);
			} else if (requestId != null && root.has("usage")) {
				this.recordUsage(requestId, modelId, root.getAsJsonObject("usage"));
			}
		} catch (Exception e) {
			logger.error("Failed to parse stream JSON for modelId={}", modelId, e);
		}
		return data;
	}

	/**
	 * 从响应尾部片段提取 timings/usage 并记录（用于边收边下发的流式代理，避免全量缓冲响应体）。
	 * llama.cpp / OpenAI 非流式响应的 timings、usage 字段均在 JSON 末尾，尾部片段即可覆盖。
	 *
	 * @param modelId   模型唯一标识
	 * @param tail      响应尾部片段（通常最后 16KB；小响应则为完整响应）
	 * @param requestId 请求 ID（用于写入 .requests.log）
	 * @return 解析出的本次 Timing 数据
	 */
	public Timing handleStreamTail(String modelId, String tail, String requestId) {
		if (tail == null || tail.isEmpty()) {
			return null;
		}
		Timing data = null;
		try {
			String timingsJson = extractJsonObjectValue(tail, "timings");
			if (timingsJson != null) {
				data = this.gson.fromJson(timingsJson, Timing.class);
				this.recordTiming(modelId, data);
				return data;
			}
			if (requestId != null) {
				String usageJson = extractJsonObjectValue(tail, "usage");
				if (usageJson != null) {
					this.recordUsage(requestId, modelId, JsonParser.parseString(usageJson).getAsJsonObject());
				}
			}
		} catch (Exception e) {
			logger.error("Failed to parse stream tail for modelId={}", modelId, e);
		}
		return data;
	}

	/**
	 * 在 JSON 文本（可能被截断的尾部片段）中定位 "key":{...} 并按平衡大括号提取子对象。
	 * 从尾部向前找最后一次出现，避开生成内容中可能包含的同名字段。
	 */
	private static String extractJsonObjectValue(String text, String key) {
		String quoted = "\"" + key + "\"";
		int idx = text.lastIndexOf(quoted);
		if (idx < 0) {
			return null;
		}
		int colon = text.indexOf(':', idx + quoted.length());
		if (colon < 0) {
			return null;
		}
		int i = colon + 1;
		while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
			i++;
		}
		if (i >= text.length() || text.charAt(i) != '{') {
			return null;
		}
		int depth = 0;
		boolean inStr = false;
		boolean esc = false;
		for (int j = i; j < text.length(); j++) {
			char c = text.charAt(j);
			if (inStr) {
				if (esc) {
					esc = false;
				} else if (c == '\\') {
					esc = true;
				} else if (c == '"') {
					inStr = false;
				}
			} else {
				if (c == '"') {
					inStr = true;
				} else if (c == '{') {
					depth++;
				} else if (c == '}') {
					depth--;
					if (depth == 0) {
						return text.substring(i, j + 1);
					}
				}
			}
		}
		return null;
	}

	/**
	 * 从 OpenAI 格式的 usage 字段提取 token 数据，写入二进制日志。
	 * 用于远程节点代理场景（无 timings，只有 usage）。
	 */
	public void recordUsage(String requestId, String modelId, JsonObject usage) {
		if (requestId == null || modelId == null || usage == null) return;
		try {
			RequestLogRecord record = new RequestLogRecord();
			record.startTime = System.currentTimeMillis();
			ActiveRequest activeReq = ModelRequestTracker.getInstance().getActiveRequest(requestId);
			record.endpoint = activeReq != null ? toEndpointByte(activeReq.getEndpoint()) : (byte) 0;
			record.status = 1;
			record.phase = 1;
			record.cacheN = getJsonInt(usage, "prompt_cache_hit_tokens", 0);
			record.promptN = getJsonInt(usage, "prompt_tokens", 0);
			record.predictedN = getJsonInt(usage, "completion_tokens", 0);
			this.appendRecord(modelId, record);
			this.totalRecordCount.incrementAndGet();
			this.updateTokenSummary(modelId, record);
		} catch (Exception e) {
			logger.error("Failed to record usage for requestId={}, modelId={}", requestId, modelId, e);
		}
	}

	private int getJsonInt(JsonObject obj, String key, int fallback) {
		if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
			return fallback;
		}
		try {
			return obj.get(key).getAsInt();
		} catch (Exception e) {
			return fallback;
		}
	}

	/**
	 * 根据模型ID获取累计性能记录（从二进制日志 header 读取）。
	 *
	 * @param modelId 模型ID
	 * @return 累计的 Timing 记录，不存在则返回 null
	 */
	public Timing getRecord(String modelId) {
		Path binPath = Paths.get(RECORD_DIR + modelId + ".requests.bin");
		if (!Files.exists(binPath)) {
			return null;
		}
		try (BinaryRequestLog log = new BinaryRequestLog(binPath)) {
			Timing timing = new Timing();
			timing.setCache_n((int) log.getTotalCacheTokens());
			timing.setPrompt_n((int) log.getTotalPromptTokens());
			timing.setPrompt_ms(log.getTotalPromptMs());
			timing.setPredicted_n((int) log.getTotalPredictedTokens());
			timing.setPredicted_ms(log.getTotalPredictedMs());
			timing.setDraft_n((int) log.getTotalDraftTokens());
			timing.setDraft_n_accepted((int) log.getTotalDraftAccepted());
			return timing;
		} catch (Exception e) {
			logger.error("Failed to get record for modelId={}", modelId, e);
			return null;
		}
	}

	/**
	 * 记录一次完整的请求记录，包含包裹了 Timing 的 ActiveRequest。
	 * 追加写入 cache/record/{modelId}.requests.bin。
	 */
	public void recordRequest(ActiveRequest request) {
		if (request == null || request.getModelId() == null) return;
		try {
			RequestLogRecord record = new RequestLogRecord();
			record.startTime = request.getStartTime();
			record.endpoint = toEndpointByte(request.getEndpoint());
			record.status = toStatusByte(request.getStatus());
			record.phase = toPhaseByte(request.getPhase());
			Timing timing = request.getTiming();
			if (timing != null) {
				record.cacheN = timing.getCache_n();
				record.promptN = timing.getPrompt_n();
				record.promptMs = (float) timing.getPrompt_ms();
				record.promptPerTokenMs = (float) timing.getPrompt_per_token_ms();
				record.promptPerSecond = (float) timing.getPrompt_per_second();
				record.predictedN = timing.getPredicted_n();
				record.predictedMs = (float) timing.getPredicted_ms();
				record.predictedPerTokenMs = (float) timing.getPredicted_per_token_ms();
				record.predictedPerSecond = (float) timing.getPredicted_per_second();
				record.draftN = timing.getDraft_n();
				record.draftNAccepted = timing.getDraft_n_accepted();
			}
			appendRecord(request.getModelId(), record);
			this.totalRecordCount.incrementAndGet();
			this.updateTokenSummary(request.getModelId(), record);
		} catch (Exception e) {
			logger.error("Failed to record request for modelId={}", request.getModelId(), e);
		}
	}

	/**
	 * 此方法已废弃，数据由 recordRequest 统一写入。
	 */
	private void recordTiming(String modelId, Timing requestTiming) {
		// No-op: recordRequest 在请求结束时统一写入，避免重复计数
	}

	/**
	 * 删除指定模型的全部记录（文件 + 内存缓存），线程安全。
	 *
	 * @param modelId 模型唯一标识
	 * @return 删除的记录数，模型不存在则返回 0
	 */
	public synchronized long deleteModelRecords(String modelId) {
		if (modelId == null || modelId.trim().isEmpty()) return 0;
		long deletedCount = 0;
		Path binPath = Paths.get(RECORD_DIR + modelId + ".requests.bin");

		// 从 logMap 移除并 close
		LogEntry removed = this.logMap.remove(modelId);
		if (removed != null) {
			synchronized (removed) {
				removed.closed = true;
				try {
					deletedCount = removed.log.getRecordCount();
					removed.log.close();
				} catch (IOException ignore) {
				}
			}
		}

		// 若 logMap 中无打开实例，从文件读取记录数
		if (deletedCount == 0 && Files.exists(binPath)) {
			try (BinaryRequestLog log = new BinaryRequestLog(binPath)) {
				deletedCount = log.getRecordCount();
			} catch (IOException ignore) {
			}
		}

		// 从 tokenSummaryCache 移除
		this.tokenSummaryCache.remove(modelId);

		// 扣减全局计数
		if (deletedCount > 0) {
			this.totalRecordCount.addAndGet(-deletedCount);
		}

		// 删除磁盘文件
		try {
			if (Files.exists(binPath)) {
				Files.delete(binPath);
			}
		} catch (IOException ignore) {
		}

		return deletedCount;
	}
}
