package org.mark.llamacpp.server;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import org.mark.llamacpp.server.tools.JsonUtil;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.mark.llamacpp.gguf.GGUFBundle;
import org.mark.llamacpp.gguf.GGUFMetaData;
import org.mark.llamacpp.gguf.GGUFModel;
import org.mark.llamacpp.server.service.ModelRequestTracker;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.struct.ModelPathConfig;
import org.mark.llamacpp.server.struct.ModelPathDataStruct;
import org.mark.llamacpp.server.tools.CommandLineRunner;
import org.mark.llamacpp.server.tools.ChatTemplateFileTool;
import org.mark.llamacpp.server.tools.GPUInfoHelper;
import org.mark.llamacpp.server.tools.ParamTool;
import org.mark.llamacpp.server.tools.PortChecker;
import org.apache.logging.log4j.CloseableThreadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 	
 */
public class LlamaServerManager {
	
	/**
	 * 	
	 */
	private static final Logger logger = LoggerFactory.getLogger(LlamaServerManager.class);

	/**
	 * 	
	 */
	private static final Gson gson = JsonUtil.gson();
	
/**
 	 * 	这是锁。
 	 */
 	private static final String I18N_SLOT_SAVE_FAILED = "api.error.model.slot.save.failed";
	private static final String I18N_SLOT_LOAD_FAILED = "api.error.model.slot.load.failed";

	private static final ConcurrentHashMap<String, Object> CAPABILITIES_FILE_LOCKS = new ConcurrentHashMap<>();

 	/**
 	 * 	自动加载缓存文件路径
 	 */
 	private static final String AUTO_LOAD_CACHE_FILE = "config/auto_load_models_cache.json";

 	/**
 	 * 	自动加载缓存锁文件路径（用于跨进程互斥）
 	 */
 	private static final String AUTO_LOAD_CACHE_LOCK_FILE = "config/auto_load_models_cache.json.lock";

 	/**
 	 * 	缓存文件锁
 	 */
 	private final Object cacheFileLock = new Object();
 	
 	/**
 	 * 	
 	 */
 	private final ConfigManager configManager = ConfigManager.getInstance();
	
	/**
	 * 	单例
	 */
	private static final LlamaServerManager INSTANCE = new LlamaServerManager();

	/**
	 * 	获取单例
	 * @return
	 */
	public static LlamaServerManager getInstance() {
		return INSTANCE;
	}

    /**
     * 存放模型的路径（支持多个根目录）。
     */
    private List<ModelPathDataStruct> modelPaths = new ArrayList<>();
	
	
	/**
	 * 	所有GGUF模型的列表
	 */
	private List<GGUFModel> list = new LinkedList<>();
	
	/**
	 * 已加载的模型进程列表
	 */
	private Map<String, LlamaCppProcess> loadedProcesses = new LinkedHashMap<>();
	
	private final Object processLock = new Object();
	
	private Map<String, LlamaCppProcess> loadingProcesses = new HashMap<>();
	
	private Map<String, Future<?>> loadingTasks = new HashMap<>();
	
	private Set<String> canceledLoadingModels = new HashSet<>();
	
	/**
	 * 端口计数器，用于递增分配端口
	 */
	private AtomicInteger portCounter = new AtomicInteger(8081);
	
	/**
	 * 模型ID到端口映射
	 */
	private Map<String, Integer> modelPorts = new HashMap<>();

	/**
	 * 模型ID到最后使用时间映射（毫秒时间戳）
	 */
	private Map<String, Long> modelLastUsedTime = new ConcurrentHashMap<>();

	private final Map<String, JsonObject> loadedModelInfos = new ConcurrentHashMap<>();
	
	/**
	 * 	正在加载中的模型。
	 */
	private Set<String> loadingModels = new HashSet<>();
	
	/**
	 * 线程池，用于异步执行模型加载任务
	 */
	private final ExecutorService executorService = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("llama-loader-", 0).factory());

	/**
	 * 自动卸载调度器，用于定期检查并卸载空闲模型
	 */
	private final ScheduledExecutorService autoUnloadScheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("llama-auto-unload-", 0).factory());
	
	/**
	 *
	 */
	private LlamaServerManager() {
		// 尝试从配置文件加载设置
		this.loadSettingsFromFile();
		//this.startSlotsPolling();
	}
	
	/**
	 * 从JSON文件加载设置
	 */
    private void loadSettingsFromFile() {
        try {
			try {
				Path configFile = LlamaServer.getModelPathConfigPath();
				ModelPathConfig cfg = LlamaServer.readModelPathConfig(configFile);
				
				List<ModelPathDataStruct> paths = new ArrayList<>();
				if (cfg != null && cfg.getItems() != null) {
					for (ModelPathDataStruct item : cfg.getItems()) {
						if (item == null || item.getPath() == null) continue;
						String p = item.getPath().trim();
						if (p.isEmpty()) continue;
						try {
							Path candidate = Paths.get(p).toAbsolutePath().normalize();
							// 拒绝卷根目录
							Path root = candidate.getRoot();
							if (root != null && candidate.equals(root)) continue;
							// 拒绝与已添加路径重叠
							boolean conflict = false;
							for (ModelPathDataStruct existing : paths) {
								String ep = existing.getPath().trim();
								Path existingPath = Paths.get(ep).toAbsolutePath().normalize();
								if (candidate.equals(existingPath)) { conflict = true; break; }
								if (candidate.startsWith(existingPath) || existingPath.startsWith(candidate)) { conflict = true; break; }
							}
							if (conflict) continue;
						} catch (Exception e) {
							continue;
						}
						paths.add(item);
					}
				}
				if (!paths.isEmpty()) {
					this.modelPaths = paths;
					return;
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		} catch (Exception e) {
			e.printStackTrace();
			logger.info("从配置文件加载设置失败，使用默认设置: {}", e);
		}
	}

    /**
     * 	设置模型路径列表
     * @param paths
     */
    public void setModelPaths(List<ModelPathDataStruct> paths) {
        this.modelPaths = new ArrayList<>();
        if (paths != null) {
            for (ModelPathDataStruct p : paths) {
                if (p != null && !p.getPath().trim().isEmpty()) this.modelPaths.add(p);
            }
        }
    }

    /**
     * 	获取当前设定的模型路径列表
     * @return
     */
    public List<ModelPathDataStruct> getModelPaths() {
        return new ArrayList<>(this.modelPaths);
    }
	
//    /**
//     * 	原本是用来定时查询slots信息的，但是似乎有潜在的风险。
//     */
//	private void startSlotsPolling() {
//		this.slotsScheduler.scheduleAtFixedRate(() -> {
//			try {
//				Map<String, LlamaCppProcess> loaded = this.getLoadedProcesses();
//				if (loaded.isEmpty()) {
//					return;
//				}
//				for (String modelId : loaded.keySet()) {
//					if (modelId == null || modelId.isBlank()) {
//						continue;
//					}
//					JsonObject resp;
//					try {
//						resp = this.handleModelSlotsGet(modelId);
//					} catch (Exception e) {
//						continue;
//					}
//					JsonArray slots = resp != null && resp.has("slots") && resp.get("slots").isJsonArray()
//							? resp.getAsJsonArray("slots")
//							: null;
//					if (slots == null) {
//						continue;
//					}
//					JsonArray filtered = new JsonArray();
//					for (JsonElement el : slots) {
//						if (el == null || !el.isJsonObject()) {
//							continue;
//						}
//						JsonObject slot = el.getAsJsonObject();
//						JsonObject out = new JsonObject();
//						if (slot.has("id") && !slot.get("id").isJsonNull()) {
//							out.add("id", slot.get("id"));
//						}
//						boolean speculative = slot.has("speculative") && !slot.get("speculative").isJsonNull()
//								? slot.get("speculative").getAsBoolean()
//								: false;
//						boolean isProcessing = slot.has("is_processing") && !slot.get("is_processing").isJsonNull()
//								? slot.get("is_processing").getAsBoolean()
//								: false;
//						out.addProperty("speculative", speculative);
//						out.addProperty("is_processing", isProcessing);
//						filtered.add(out);
//					}
//					LlamaServer.sendModelSlotsEvent(modelId, filtered);
//				}
//			} catch (Exception e) {
//				logger.info("轮询slots时发生错误", e);
//			}
//		}, 1, 1, TimeUnit.SECONDS);
//	}
	
	/**
	 * 	获取模型列表。
	 * @return
	 */
	public List<GGUFModel> listModel() {
		return this.listModel(false);
	}
	
	
	/**
	 * 	获取模型列表 
	 * @param reload 是否重新加载
	 * @return
	 */
    public List<GGUFModel> listModel(boolean reload) {
        synchronized (this.list) {
            // 如果列表是空的，就去检索
            if(this.list.size() == 0 || reload) {
                this.list.clear();
                // 新建一个临时集合
                List<ModelPathDataStruct> list = new ArrayList<>(this.modelPaths);
                // 扫描默认目录
                list.add(new ModelPathDataStruct(LlamaServer.getDefaultModelsPath(), "", ""));

                // 归一化路径，去重（卷根、重复、子目录重叠）
                List<Path> scannedRoots = new ArrayList<>();
                for (ModelPathDataStruct root : list) {
                    if (root == null || root.getPath().trim().isEmpty()) continue;
                    Path modelDir;
                    try {
                        modelDir = Paths.get(root.getPath().trim()).toAbsolutePath().normalize();
                    } catch (Exception e) {
                        continue;
                    }
                    if (!Files.exists(modelDir) || !Files.isDirectory(modelDir)) {
                        continue;
                    }
                    // 拒绝卷根
                    Path r = modelDir.getRoot();
                    if (r != null && modelDir.equals(r)) {
                        continue;
                    }
                    // 跳过是已扫描路径子目录的路径
                    boolean dominated = false;
                    for (Path scanned : scannedRoots) {
                        if (modelDir.equals(scanned) || modelDir.startsWith(scanned)) {
                            dominated = true;
                            break;
                        }
                    }
                    if (dominated) continue;
                    scannedRoots.add(modelDir);
                }

                // 按路径长度升序排列，短路径优先扫描
                scannedRoots.sort(Comparator.comparing(p -> p.toString().length()));

                // 收集所有待扫描的目录
                List<Path> allDirs = new ArrayList<>();
                for (Path modelDir : scannedRoots) {
                    try (Stream<Path> paths = Files.walk(modelDir, 5)) {
                        List<Path> files = paths.filter(Files::isDirectory).sorted().toList();
                        allDirs.addAll(files);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

                // 并行扫描所有目录，使用固定线程池避免 ForkJoinPool 调度开销
                int parallelism = Math.min(8, Math.max(4, allDirs.size()));
                try (ExecutorService executor = Executors.newFixedThreadPool(parallelism)) {
                    List<CompletableFuture<GGUFModel>> futures = new ArrayList<>(allDirs.size());
                    for (Path dir : allDirs) {
                        futures.add(CompletableFuture.supplyAsync(() -> this.handleDirectory(dir), executor));
                    }
                    for (CompletableFuture<GGUFModel> f : futures) {
                        try {
                            GGUFModel model = f.join();
                            if (model != null) this.list.add(model);
                        } catch (Exception ignore) {
                        }
                    }
                }
                List<Map<String, Object>> persisted = this.configManager.loadModelsConfigCached();
                Map<String, String> aliasMap = new HashMap<>();
                Map<String, Boolean> favouriteMap = new HashMap<>();
                for (Map<String, Object> rec : persisted) {
                    if (rec == null) continue;
                    Object id = rec.get("modelId");
                    if (id == null) continue;
                    String modelId = String.valueOf(id);
                    Object alias = rec.get("alias");
                    if (alias != null) {
                        String a = String.valueOf(alias);
                        if (!a.isEmpty()) aliasMap.put(modelId, a);
                    }
                    Object fav = rec.get("favourite");
                    if (fav != null) {
                        boolean v;
                        if (fav instanceof Boolean) {
                            v = (Boolean) fav;
                        } else {
                            v = Boolean.parseBoolean(String.valueOf(fav));
                        }
                        favouriteMap.put(modelId, v);
                    }
                }
                for (GGUFModel m : this.list) {
                    String alias = aliasMap.get(m.getModelId());
                    if (alias != null && !alias.isEmpty()) {
                        m.setAlias(alias);
                    }
                    Boolean fav = favouriteMap.get(m.getModelId());
                    if (fav != null) {
                        m.setFavourite(fav);
                    }
                }
               this.ensureCapabilitiesFilesExistForCurrentList();
                // 重建自动加载缓存
                if (!this.list.isEmpty()) {
                    this.buildAutoLoadModelCache();
                }
            }
            // 如果集合不是空的，就直接返回。
            else {
				this.ensureCapabilitiesFilesExistForCurrentList();
                return this.list;
            }
        }
        return this.list;
    }
    
    /**
     * 	锁定文件。
     * @param modelId
     * @return
     */
	private Object lockForCapabilitiesFile(String modelId) {
		if (modelId == null) {
			return this;
		}
		return CAPABILITIES_FILE_LOCKS.computeIfAbsent(modelId, k -> new Object());
	}
	
	/**
	 * 	获取文件路径
	 * @param modelId
	 * @return
	 * @throws Exception
	 */
	private Path resolveCapabilitiesFilePath(String modelId) throws Exception {
		String currentDir = System.getProperty("user.dir");
		Path configDir = Paths.get(currentDir, "config" + File.separator + "capabilities");
		if (!Files.exists(configDir)) {
			Files.createDirectories(configDir);
		}
		String baseName = modelId == null ? "" : modelId.trim();
		baseName = baseName.replace('\\', '_').replace('/', '_');
		if (baseName.isEmpty()) {
			throw new IllegalArgumentException("modelId不能为空");
		}
		String fileName = baseName.endsWith(".json") ? baseName : (baseName + ".json");
		return configDir.resolve(fileName);
	}

	private static String safeLower(String s) {
		return s == null ? "" : s.trim().toLowerCase();
	}

	private static boolean containsAny(String haystackLower, String... needlesLower) {
		if (haystackLower == null || haystackLower.isEmpty() || needlesLower == null || needlesLower.length == 0) {
			return false;
		}
		for (String n : needlesLower) {
			if (n == null || n.isEmpty()) continue;
			if (haystackLower.contains(n)) return true;
		}
		return false;
	}

	private Map<String, Object> resolveModelType(File primaryFile, GGUFMetaData primaryMeta, GGUFModel model) {
		String fileName = primaryFile == null ? "" : primaryFile.getName();
		String architecture = primaryMeta == null ? "" : primaryMeta.getArchitecture();
		String baseName = primaryMeta == null ? "" : primaryMeta.getBaseName();
		String name = primaryMeta == null ? "" : primaryMeta.getName();
		String modelName = model == null ? "" : model.getName();

		String combined = String.join(" ",
				safeLower(fileName),
				safeLower(architecture),
				safeLower(baseName),
				safeLower(name),
				safeLower(modelName));

		boolean rerank = containsAny(combined,
				"rerank", "re-rank", "reranker", "ranker", "cross-encoder", "crossencoder", "cross_encoder");

		boolean embedding = false;
		if (!rerank) {
			embedding = containsAny(combined,
					"embedding", "embeddings", "text-embedding", "text_embedding", "embed", "e5", "gte", "jina", "nomic", "mxbai", "arctic-embed", "bge");
		}
		String archLower = safeLower(architecture);
		if (!rerank && !embedding) {
			if (containsAny(archLower, "bert", "roberta", "xlm-roberta", "xlm_roberta")) {
				embedding = true;
			}
		}

		String chatTemplate = "";
		if (primaryMeta != null) {
			String tpl = primaryMeta.getChatTemplate();
			if (tpl != null) chatTemplate = tpl;
		}

		String tplLower = safeLower(chatTemplate);
		boolean tools = containsAny(tplLower, "tool_call", "tool_calls", "tools", "mcp", "function");
		if (!tools && tplLower.contains("tool")) {
			tools = true;
		}
		boolean thinking = containsAny(tplLower, "enable_thinking", "thinking");
		if (rerank || embedding) {
			tools = false;
			thinking = false;
		}
		// 多模态相关
		GGUFMetaData mmproj = model.getMmproj();
		boolean supportsAudio = false;
		boolean supportsVision = false;
		if(mmproj != null) {
			if(mmproj.isSupportsAudio()) {
				supportsAudio = true;
			}
			if(mmproj.isSupportsVision()) {
				supportsVision = true;
			}
		}

		Map<String, Object> out = new HashMap<>();
		out.put("rerank", rerank);
		out.put("embedding", embedding);
		out.put("tools", tools);
		out.put("thinking", thinking);
		out.put("audio", supportsAudio);
		out.put("vision", supportsVision);
		return out;
	}
	
	/**
	 * 	确保模型的能力信息文件存在。
	 * @param model
	 * @param primaryFile
	 * @param primaryMeta
	 */
	private void ensureCapabilitiesFileExists(GGUFModel model, File primaryFile, GGUFMetaData primaryMeta) {
		if (model == null) return;
		String modelId = model.getModelId();
		if (modelId == null || modelId.trim().isEmpty()) return;
		try {
			Path filePath = this.resolveCapabilitiesFilePath(modelId);
			synchronized (this.lockForCapabilitiesFile(modelId)) {
				if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
					return;
				}
				Map<String, Object> caps = this.resolveModelType(primaryFile, primaryMeta, model);
				Map<String, Object> payload = new HashMap<>();
				payload.put("modelId", modelId);
				payload.put("updatedAt", System.currentTimeMillis());
				payload.put("autoGenerated", true);
				if (caps != null) payload.putAll(caps);
				String json = gson.toJson(payload);
				Files.write(filePath, json.getBytes(StandardCharsets.UTF_8));
			}
		} catch (Exception ignore) {
			ignore.printStackTrace();
		}
	}

	private void ensureCapabilitiesFilesExistForCurrentList() {
		for (GGUFModel m : this.list) {
			this.ensureCapabilitiesFileExistsForModel(m);
		}
	}
	
	private void ensureCapabilitiesFileExistsForModel(GGUFModel model) {
		if (model == null) return;
		GGUFMetaData primary = model.getPrimaryModel();
		if (primary == null) return;
		String fp = primary.getFilePath();
		if (fp == null || fp.trim().isEmpty()) return;
		File primaryFile = new File(fp);
		if (!primaryFile.exists() || !primaryFile.isFile()) return;
		this.ensureCapabilitiesFileExists(model, primaryFile, primary);
	}
	
	private void ensureCapabilitiesFileExistsForModelId(String modelId) {
		String id = modelId == null ? "" : modelId.trim();
		if (id.isEmpty()) return;
		GGUFModel model = this.findModelById(id);
		this.ensureCapabilitiesFileExistsForModel(model);
	}
	
	private JsonObject readCapabilitiesFileIfExists(String modelId) {
		String id = modelId == null ? "" : modelId.trim();
		if (id.isEmpty()) {
			return null;
		}
		try {
			Path filePath = this.resolveCapabilitiesFilePath(id);
			synchronized (this.lockForCapabilitiesFile(id)) {
				if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
					try {
						String json = new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);
						return gson.fromJson(json, JsonObject.class);
					} catch (Exception ignore) {
						return null;
					}
				}
			}
			return null;
		} catch (Exception ignore) {
			return null;
		}
	}
	
	private static JsonObject buildDefaultCapabilities(String modelId) {
		String id = modelId == null ? "" : modelId.trim();
		if (id.isEmpty()) {
			return null;
		}
		JsonObject fallback = new JsonObject();
		fallback.addProperty("modelId", id);
		fallback.addProperty("tools", false);
		fallback.addProperty("thinking", false);
		fallback.addProperty("rerank", false);
		fallback.addProperty("embedding", false);
		return fallback;
	}
	
	public JsonObject getModelCapabilities(String modelId) {
		String id = modelId == null ? "" : modelId.trim();
		if (id.isEmpty()) {
			return null;
		}
		
		JsonObject out = this.readCapabilitiesFileIfExists(id);
		if (out != null) {
			return out;
		}
		
		this.ensureCapabilitiesFileExistsForModelId(id);
		
		out = this.readCapabilitiesFileIfExists(id);
		if (out != null) {
			return out;
		}
		
		return buildDefaultCapabilities(id);
	}

	public JsonObject getModelCapabilitiesSummary(String modelId) throws Exception {
		String id = modelId == null ? "" : modelId.trim();
		if (id.isEmpty()) {
			throw new IllegalArgumentException("缺少必需的modelId参数");
		}
		JsonObject caps = this.getModelCapabilities(id);
		if (caps == null) {
			throw new IllegalStateException("获取模型能力配置失败");
		}
		Path filePath = this.resolveCapabilitiesFilePath(id);
		JsonObject out = new JsonObject();
		out.addProperty("modelId", id);
		out.addProperty("tools", ParamTool.parseJsonBoolean(caps, "tools", false));
		out.addProperty("thinking", ParamTool.parseJsonBoolean(caps, "thinking", false));
		out.addProperty("rerank", ParamTool.parseJsonBoolean(caps, "rerank", false));
		out.addProperty("embedding", ParamTool.parseJsonBoolean(caps, "embedding", false));
		out.addProperty("vision", ParamTool.parseJsonBoolean(caps, "vision", false));
		out.addProperty("audio", ParamTool.parseJsonBoolean(caps, "audio", false));
		out.addProperty("file", filePath.toString());
		return out;
	}

	public JsonObject setModelCapabilities(String modelId, JsonObject capabilities) throws Exception {
		String id = modelId == null ? "" : modelId.trim();
		if (id.isEmpty()) {
			throw new IllegalArgumentException("缺少必需的modelId参数");
		}
		JsonObject capsObj = capabilities == null ? new JsonObject() : capabilities;
		boolean tools = ParamTool.parseJsonBoolean(capsObj, "tools", false);
		boolean thinking = ParamTool.parseJsonBoolean(capsObj, "thinking", false);
		boolean rerank = ParamTool.parseJsonBoolean(capsObj, "rerank", false);
		boolean embedding = ParamTool.parseJsonBoolean(capsObj, "embedding", false);
		boolean vision = ParamTool.parseJsonBoolean(capsObj, "vision", false);
		boolean audio = ParamTool.parseJsonBoolean(capsObj, "audio", false);

		if (embedding && rerank) {
			rerank = false;
		}
		boolean nonChat = rerank || embedding;
		if (nonChat) {
			tools = false;
			thinking = false;
		} else if (tools || thinking) {
			rerank = false;
			embedding = false;
		}
		if (vision || audio) {
			rerank = false;
			embedding = false;
		}

		JsonObject saved = new JsonObject();
		saved.addProperty("modelId", id);
		saved.addProperty("tools", tools);
		saved.addProperty("thinking", thinking);
		saved.addProperty("rerank", rerank);
		saved.addProperty("embedding", embedding);
		saved.addProperty("vision", vision);
		saved.addProperty("audio", audio);
		saved.addProperty("updatedAt", System.currentTimeMillis());

		Path filePath = this.resolveCapabilitiesFilePath(id);
		synchronized (this.lockForCapabilitiesFile(id)) {
			Files.write(filePath, saved.toString().getBytes(StandardCharsets.UTF_8));
		}

		// 重建自动加载缓存
		this.buildAutoLoadModelCache();

		JsonObject out = new JsonObject();
		out.addProperty("modelId", id);
		out.addProperty("saved", true);
		JsonObject outCaps = new JsonObject();
		outCaps.addProperty("tools", tools);
		outCaps.addProperty("thinking", thinking);
		outCaps.addProperty("rerank", rerank);
		outCaps.addProperty("embedding", embedding);
		outCaps.addProperty("vision", vision);
		outCaps.addProperty("audio", audio);
		out.add("capabilities", outCaps);
	out.addProperty("file", filePath.toString());
		return out;
	}

	/**
	 * 查询指定 modelId 的克隆体源模型 ID。
	 * 直接读取 launch_config.json，不经过 resolveModelId（克隆体不在磁盘上）。
	 * @param modelId 克隆体 modelId
	 * @return sourceModelId，非克隆体或配置不存在时返回 null
	 */
	public String getSourceModelId(String modelId) {
		return configManager.getSourceModelId(modelId);
	}

	/**
	 * 获取模型的自动加载策略
	 * @param modelId 模型 ID 或别名
	 * @return "allow", "deny", 或 null（未设置）
	 */
	public String getAutoLoadPolicy(String modelId) {
		String resolved = resolveModelId(modelId);
		if (resolved == null) return null;
		return configManager.getAutoLoadPolicy(resolved);
	}

	/**
	 * 批量获取所有模型的自动加载策略
	 * @return 模型ID到策略的映射
	 */
	public Map<String, String> getAllAutoLoadPolicies() {
		return configManager.getAllAutoLoadPolicies();
	}

	/**
	 * 设置模型的自动加载策略
	 * @param modelId 模型 ID 或别名
	 * @param mode "allow" 或 "deny"
	 * @return null 表示成功，非 null 为错误信息
	 */
	public String setAutoLoadPolicy(String modelId, String mode) {
		String resolved = resolveModelId(modelId);
		if (resolved == null) {
			return "Model not found: " + modelId;
		}
		if ("allow".equalsIgnoreCase(mode)) {
			String validationError = checkLaunchConfigValid(resolved);
			if (validationError != null) {
				return validationError;
			}
		}
		configManager.setAutoLoadPolicy(resolved, mode);
		buildAutoLoadModelCache();
		return null;
	}

	/**
	 * 重置模型的自动加载策略（删除 autoLoad 字段）
	 * @param modelId 模型 ID 或别名
	 * @return null 表示成功，非 null 为错误信息
	 */
	public String resetAutoLoadPolicy(String modelId) {
		String resolved = resolveModelId(modelId);
		if (resolved == null) {
			return "Model not found: " + modelId;
		}
		configManager.resetAutoLoadPolicy(resolved);
		buildAutoLoadModelCache();
		return null;
	}

	/**
	 * 获取模型的自动卸载策略
	 * @param modelId 模型 ID 或别名
	 * @return "allow", "deny", 或 null（未设置）
	 */
	public String getAutoUnloadPolicy(String modelId) {
		String resolved = resolveModelId(modelId);
		if (resolved == null) return null;
		return configManager.getAutoUnloadPolicy(resolved);
	}

	/**
	 * 批量获取所有模型的自动卸载策略
	 * @return 模型ID到策略的映射
	 */
	public Map<String, String> getAllAutoUnloadPolicies() {
		return configManager.getAllAutoUnloadPolicies();
	}

	/**
	 * 批量获取所有模型的自动卸载超时时间（一次性读取配置文件）
	 * @return 模型ID到超时时间（毫秒）的映射，未设置的不包含在内
	 */
	public Map<String, Long> getAllAutoUnloadTimeouts() {
		return configManager.getAllAutoUnloadTimeouts();
	}

	/**
	 * 设置模型的自动卸载策略
	 * @param modelId 模型 ID 或别名
	 * @param mode "allow" 或 "deny"
	 * @return null 表示成功，非 null 为错误信息
	 */
	public String setAutoUnloadPolicy(String modelId, String mode) {
		String resolved = resolveModelId(modelId);
		if (resolved == null) {
			return "Model not found: " + modelId;
		}
		configManager.setAutoUnloadPolicy(resolved, mode);
		return null;
	}

	/**
	 * 重置模型的自动卸载策略（删除 autoUnload 字段）
	 * @param modelId 模型 ID 或别名
	 * @return null 表示成功，非 null 为错误信息
	 */
	public String resetAutoUnloadPolicy(String modelId) {
		String resolved = resolveModelId(modelId);
		if (resolved == null) {
			return "Model not found: " + modelId;
		}
		configManager.resetAutoUnloadPolicy(resolved);
		return null;
	}

	/**
	 * 获取模型的自动卸载超时时间（毫秒）
	 * @param modelId 模型 ID 或别名
	 * @return 超时时间，未设置返回 null
	 */
	public Long getAutoUnloadTimeoutMs(String modelId) {
		String resolved = resolveModelId(modelId);
		if (resolved == null) return null;
		return configManager.getAutoUnloadTimeoutMs(resolved);
	}

	/**
	 * 设置模型的自动卸载超时时间（毫秒）
	 * @param modelId 模型 ID 或别名
	 * @param timeoutMs 超时时间（毫秒）
	 * @return null 表示成功，非 null 为错误信息
	 */
	public String setAutoUnloadTimeoutMs(String modelId, long timeoutMs) {
		String resolved = resolveModelId(modelId);
		if (resolved == null) {
			return "Model not found: " + modelId;
		}
		configManager.setAutoUnloadTimeoutMs(resolved, timeoutMs);
		return null;
	}

	/**
	 * 重置模型的自动卸载超时时间（删除 autoUnloadTimeoutMs 字段）
	 * @param modelId 模型 ID 或别名
	 * @return null 表示成功，非 null 为错误信息
	 */
	public String resetAutoUnloadTimeoutMs(String modelId) {
		String resolved = resolveModelId(modelId);
		if (resolved == null) {
			return "Model not found: " + modelId;
		}
		configManager.resetAutoUnloadTimeoutMs(resolved);
		return null;
	}

	/**
	 * 检查模型是否允许自动卸载
	 * @param modelId 模型 ID 或别名
	 * @return true 如果允许自动卸载
	 */
	public boolean canAutoUnload(String modelId) {
		String mode = getAutoUnloadPolicy(modelId);
		return "allow".equalsIgnoreCase(mode);
	}

	/**
	 * 更新模型的最后使用时间
	 * @param modelId 模型 ID
	 */
	public void updateModelLastUsedTime(String modelId) {
		if (modelId != null && !modelId.trim().isEmpty()) {
			modelLastUsedTime.put(modelId.trim(), System.currentTimeMillis());
		}
	}

	/**
	 * 获取模型的最后使用时间
	 * @param modelId 模型 ID
	 * @return 最后使用时间（毫秒时间戳），未找到返回 null
	 */
	public Long getModelLastUsedTime(String modelId) {
		return modelLastUsedTime.get(modelId);
	}

	/**
	 * 检查并自动卸载空闲模型
	 */
	public void checkAndAutoUnload() {
		try {
			Map<String, LlamaCppProcess> loaded;
			synchronized (this.processLock) {
				loaded = new HashMap<>(this.loadedProcesses);
			}
			if (loaded.isEmpty()) return;

			// 批量读取策略与超时，避免在循环中重复读取配置文件
			Map<String, String> allPolicies = getAllAutoUnloadPolicies();
			Map<String, Long> allTimeouts = getAllAutoUnloadTimeouts();

			long now = System.currentTimeMillis();
			for (Map.Entry<String, LlamaCppProcess> entry : loaded.entrySet()) {
				String modelId = entry.getKey();
				String mode = allPolicies.get(modelId);
				if (!"allow".equalsIgnoreCase(mode)) continue;

			Long timeoutMs = allTimeouts.get(modelId);
			if (timeoutMs == null || timeoutMs <= 0) continue;

			Long lastUsed = modelLastUsedTime.get(modelId);
			if (lastUsed == null) continue;

			long idleMs = now - lastUsed;
			if (idleMs >= timeoutMs) {
				if (ModelRequestTracker.getInstance().isModelBusy(modelId)) {
					logger.info("[自动卸载] 模型正在处理请求，跳过卸载: model={}, activeCount={}", modelId, ModelRequestTracker.getInstance().getModelActiveCount(modelId));
					continue;
				}
				logger.info("[自动卸载] 模型空闲超时: model={}, idleMs={}, timeoutMs={}", modelId, idleMs, timeoutMs);
				boolean stopped = stopModel(modelId);
				if (stopped) {
					logger.info("[自动卸载] 卸载成功: model={}", modelId);
				} else {
					logger.warn("[自动卸载] 卸载失败: model={}", modelId);
				}
			}
		}
	} catch (Exception e) {
			logger.warn("[自动卸载] 检查异常: {}", e.getMessage());
		}
	}

	/**
	 * 启动自动卸载调度器，每10秒检查一次
	 */
	public void startAutoUnloadScheduler() {
		autoUnloadScheduler.scheduleAtFixedRate(() -> {
			try {
				checkAndAutoUnload();
			} catch (Exception e) {
				logger.warn("[自动卸载] 调度器异常: {}", e.getMessage());
			}
		}, 10, 10, TimeUnit.SECONDS);
		logger.info("[自动卸载] 调度器已启动，检查间隔: 10秒");
	}

	/**
	 * 检查模型是否有可用的启动配置
	 * @param modelId 模型 ID
	 * @return null 表示可用，非 null 为错误信息
	 */
	@SuppressWarnings("unchecked")
	private String checkLaunchConfigValid(String modelId) {
		Map<String, Object> bundle = this.configManager.getModelLaunchConfigBundle(modelId);
		if (bundle == null) {
			return "模型未配置启动参数";
		}
		String selectedConfigName = (String) bundle.get("selectedConfig");
		if (selectedConfigName == null || selectedConfigName.trim().isEmpty()) {
			return "模型未配置启动参数";
		}
		Map<String, Object> configs = (Map<String, Object>) bundle.get("configs");
		if (configs == null) {
			return "模型未配置启动参数";
		}
		Map<String, Object> selectedConfig = (Map<String, Object>) configs.get(selectedConfigName);
		if (selectedConfig == null) {
			return "模型未配置启动参数";
		}
		String llamaBinPath = (String) selectedConfig.get("llamaBinPathSelect");
		if (llamaBinPath == null || llamaBinPath.trim().isEmpty()) {
			llamaBinPath = (String) selectedConfig.get("llamaBinPath");
		}
		if (llamaBinPath == null || llamaBinPath.trim().isEmpty()) {
			return "模型未配置启动参数";
		}
		return null;
	}

	/**
	 * 检查模型是否允许自动加载
	 * @param modelId 模型 ID 或别名
	 * @return true 如果允许自动加载
	 */
	public boolean canAutoLoad(String modelId) {
		String mode = getAutoLoadPolicy(modelId);
		return "allow".equalsIgnoreCase(mode);
	}

	/**
	 * 解析模型名称，支持别名
	 * @param name 模型 ID 或别名
	 * @return 真实 modelId，不存在返回 null
	 */
	public String resolveModelId(String name) {
		if (name == null || name.trim().isEmpty()) {
			return null;
		}
		if (findModelById(name) != null) {
			return name;
		}
		String resolved = findModelIdByAlias(name);
		if (resolved != null) {
			return resolved;
		}
		// 克隆体回退：launch_config 中存在该条目且含 sourceModelId 即视为有效
		// 克隆体不在磁盘上，常规查找必然失败；其规范 id 即 cloneId 自身
		if (this.getSourceModelId(name) != null) {
			return name;
		}
		return null;
	}

	/**
	 * 构建自动加载模型缓存文件
	 */
	public void buildAutoLoadModelCache() {
		try {
			logger.info("[自动加载缓存] 开始构建缓存文件");

			List<GGUFModel> allModels = listModel();
			Set<String> loadedIds = new HashSet<>(getLoadedProcesses().keySet());

			JsonArray dataArray = new JsonArray();
			long now = System.currentTimeMillis() / 1000;

			for (GGUFModel model : allModels) {
				String modelId = model.getModelId();
				if (loadedIds.contains(modelId)) continue;
				if (!canAutoLoad(modelId)) continue;

				JsonObject caps = this.getModelCapabilities(modelId);

				JsonObject entry = new JsonObject();
				entry.addProperty("id", modelId);
				entry.add("aliases", this.buildModelAliases(model));
				entry.add("tags", new JsonArray());
				entry.addProperty("object", "model");
				entry.addProperty("owned_by", "llamacpp");
				entry.addProperty("created", now);
				entry.add("status", this.buildModelStatus(modelId, false));
				entry.add("architecture", this.buildModelArchitecture(model, caps));
				entry.addProperty("need_download", false);

				if (caps != null) {
					entry.add("my_capabilities", caps);
				}

				int ctxSize = this.extractCtxSizeFromLaunchConfig(modelId);
				if (ctxSize > 0) {
					entry.addProperty("runtimeCtx", ctxSize);
				}

				dataArray.add(entry);
			}

			// 追加源模型仍存在的克隆体；源模型不存在的克隆体不显示
			Map<String, Map<String, Object>> allLaunchConfigs = configManager.loadAllLaunchConfigs();
			for (Map.Entry<String, Map<String, Object>> launchEntry : allLaunchConfigs.entrySet()) {
				String cloneId = launchEntry.getKey();
				if (findModelById(cloneId) != null) {
					continue; // 已有真实模型条目，不重复
				}
				String srcId = configManager.getSourceModelId(cloneId);
				if (srcId == null) {
					continue; // 不是克隆体
				}
				GGUFModel sourceModel = findModelById(srcId);
				if (sourceModel == null) {
					continue; // 源模型不存在，不显示该克隆体
				}
				if (loadedIds.contains(cloneId)) {
					continue;
				}
				if (!canAutoLoad(cloneId)) {
					continue;
				}

				JsonObject caps = this.getModelCapabilities(srcId);

				JsonObject entry = new JsonObject();
				entry.addProperty("id", cloneId);
				JsonArray aliases = new JsonArray();
				aliases.add(cloneId);
				entry.add("aliases", aliases);
				entry.add("tags", new JsonArray());
				entry.addProperty("object", "model");
				entry.addProperty("owned_by", "llamacpp");
				entry.addProperty("created", now);
				entry.add("status", this.buildModelStatus(cloneId, false));
				entry.add("architecture", this.buildModelArchitecture(sourceModel, caps));
				entry.addProperty("need_download", false);
				entry.addProperty("isClone", true);
				entry.addProperty("sourceModelId", srcId);

				if (caps != null) {
					entry.add("my_capabilities", caps);
				}

				int ctxSize = this.extractCtxSizeFromLaunchConfig(cloneId);
				if (ctxSize > 0) {
					entry.addProperty("runtimeCtx", ctxSize);
				}

				dataArray.add(entry);
			}

			JsonObject root = new JsonObject();
			root.addProperty("object", "list");
			root.add("data", dataArray);

			synchronized (cacheFileLock) {
				// 跨进程锁：使用独立的锁文件，避免影响目标文件的原子替换
				File lockFile = new File(AUTO_LOAD_CACHE_LOCK_FILE);
				File parent = lockFile.getParentFile();
				if (parent != null && !parent.exists()) {
					parent.mkdirs();
				}
				if (!lockFile.exists()) {
					lockFile.createNewFile();
				}
				RandomAccessFile raf = null;
				FileChannel channel = null;
				FileLock fileLock = null;
				try {
					raf = new RandomAccessFile(lockFile, "rw");
					channel = raf.getChannel();
					fileLock = channel.lock();

					Path cacheDir = Paths.get("config");
					if (!Files.exists(cacheDir)) {
						Files.createDirectories(cacheDir);
					}
					Path target = Paths.get(AUTO_LOAD_CACHE_FILE);
					Path temp = target.resolveSibling(target.getFileName() + ".tmp");
					boolean tempCreated = false;
					try {
						try (FileWriter writer = new FileWriter(temp.toFile())) {
							gson.toJson(root, writer);
						}
						tempCreated = true;
						try {
							Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
						} catch (AtomicMoveNotSupportedException e) {
							logger.warn("[自动加载缓存] 原子写入不支持，回退到非原子写入: {}", e.getMessage());
							Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
						}
					} finally {
						if (tempCreated) {
							try {
								Files.deleteIfExists(temp);
							} catch (IOException ignore) {}
						}
					}
				} finally {
					if (fileLock != null) {
						try { fileLock.release(); } catch (IOException ignore) {}
					}
					if (channel != null) {
						try { channel.close(); } catch (IOException ignore) {}
					}
					if (raf != null) {
						try { raf.close(); } catch (IOException ignore) {}
					}
				}
			}

			logger.info("[自动加载缓存] 缓存文件构建完成，共 {} 个模型", dataArray.size());
		} catch (Exception e) {
			logger.warn("[自动加载缓存] 构建失败: {}", e.getMessage());
		}
	}

	/**
	 * 读取自动加载模型缓存
	 * @return OpenAI 标准的 JsonObject
	 */
	public JsonObject readAutoLoadModelCache() {
		synchronized (cacheFileLock) {
			File lockFile = new File(AUTO_LOAD_CACHE_LOCK_FILE);
			File parent = lockFile.getParentFile();
			if (parent != null && !parent.exists()) {
				parent.mkdirs();
			}
			if (!lockFile.exists()) {
				try {
					lockFile.createNewFile();
				} catch (IOException e) {
					// 锁文件无法创建，继续尝试读取缓存
				}
			}
			RandomAccessFile raf = null;
			FileChannel channel = null;
			FileLock fileLock = null;
			try {
				raf = new RandomAccessFile(lockFile, "rw");
				channel = raf.getChannel();
				fileLock = channel.lock();
				return readAutoLoadCacheFileInternal();
			} catch (Exception e) {
				logger.warn("[自动加载缓存] 读取失败: {}", e.getMessage());
				return new JsonObject();
			} finally {
				if (fileLock != null) {
					try { fileLock.release(); } catch (IOException ignore) {}
				}
				if (channel != null) {
					try { channel.close(); } catch (IOException ignore) {}
				}
				if (raf != null) {
					try { raf.close(); } catch (IOException ignore) {}
				}
			}
		}
	}

	/**
	 * 实际读取缓存文件内容（必须在已持有锁的情况下调用）
	 */
	private JsonObject readAutoLoadCacheFileInternal() {
		try {
			Path filePath = Paths.get(AUTO_LOAD_CACHE_FILE);
			if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
				return new JsonObject();
			}
			String json = new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);
			return gson.fromJson(json, JsonObject.class);
		} catch (Exception e) {
			logger.warn("[自动加载缓存] 读取缓存文件失败: {}", e.getMessage());
			return new JsonObject();
		}
	}

	/**
	 * 从 launch config 的 cmd 中提取 --ctx-size
	 * @param modelId 模型 ID
	 * @return ctx-size 值，未找到返回 0
	 */
	private int extractCtxSizeFromLaunchConfig(String modelId) {
		try {
			Map<String, Object> bundle = configManager.getModelLaunchConfigBundle(modelId);
			if (bundle == null) return 0;

			String selectedConfigName = ParamTool.asString(bundle.get("selectedConfig"));
			if (selectedConfigName.trim().isEmpty()) return 0;

			Map<String, Object> configs = ParamTool.asConfigMap(bundle.get("configs"));
			if (configs == null) return 0;

			Map<String, Object> config = ParamTool.asConfigMap(configs.get(selectedConfigName));
			if (config == null) return 0;

			String cmd = ParamTool.asString(config.getOrDefault("cmd", ""));
			String extraParams = ParamTool.asString(config.getOrDefault("extraParams", ""));

			List<String> args = ParamTool.splitCmdArgs(cmd + " " + extraParams);
			for (int i = 0; i < args.size() - 1; i++) {
				if ("--ctx-size".equals(args.get(i))) {
					try {
						return Integer.parseInt(args.get(i + 1));
					} catch (NumberFormatException e) {
						return 0;
					}
				}
			}
		} catch (Exception e) {
			logger.warn("[自动加载缓存] 提取 ctx-size 失败: modelId={}, error={}", modelId, e.getMessage());
		}
		return 0;
	}

	/**
	 * 构建模型 status 对象，格式对齐 llama.cpp 新版：
	 * { "value": "unloaded"|"loaded", "args": [...], "preset": "..." }
	 * @param modelId 模型 ID
	 * @param loaded 是否已加载
	 * @return status JsonObject
	 */
	public JsonObject buildModelStatus(String modelId, boolean loaded) {
		JsonObject status = new JsonObject();
		status.addProperty("value", loaded ? "loaded" : "unloaded");

		JsonArray argsArray = new JsonArray();
		String preset = "";

		try {
			if (loaded) {
				String cmd = this.getModelStartCmd(modelId);
				if (cmd != null && !cmd.trim().isEmpty()) {
					List<String> cmdArgs = ParamTool.splitCmdArgs(cmd);
					for (String arg : cmdArgs) {
						argsArray.add(arg);
					}
				}
			} else {
				String cmd = this.buildLaunchCommandStrFromConfig(modelId);
				if (cmd != null && !cmd.trim().isEmpty()) {
					List<String> cmdArgs = ParamTool.splitCmdArgs(cmd);
					for (String arg : cmdArgs) {
						argsArray.add(arg);
					}
				}
			}

			preset = this.buildPresetString(modelId);

		} catch (Exception e) {
			logger.warn("[模型状态] 构建 status 失败: modelId={}, error={}", modelId, e.getMessage());
		}

		status.add("args", argsArray);
		status.addProperty("preset", preset);
		return status;
	}

	/**
	 * 从 launch config 重建模型的完整启动命令字符串
	 * @param modelId 模型 ID
	 * @return 命令字符串，无法构建返回空字符串
	 */
	@SuppressWarnings("unchecked")
	private String buildLaunchCommandStrFromConfig(String modelId) {
		try {
			GGUFModel model = this.findModelById(modelId);
			if (model == null) return "";

			Map<String, Object> bundle = configManager.getModelLaunchConfigBundle(modelId);
			if (bundle == null) return "";

			String selectedConfigName = ParamTool.asString(bundle.get("selectedConfig"));
			if (selectedConfigName.trim().isEmpty()) return "";

			Map<String, Object> configs = ParamTool.asConfigMap(bundle.get("configs"));
			if (configs == null) return "";

			Map<String, Object> config = ParamTool.asConfigMap(configs.get(selectedConfigName));
			if (config == null) return "";

			String llamaBinPath = ParamTool.asString(config.get("llamaBinPath"));
			if (llamaBinPath == null || llamaBinPath.trim().isEmpty()) return "";

			String cmd = ParamTool.asString(config.getOrDefault("cmd", ""));
			String extraParams = ParamTool.asString(config.getOrDefault("extraParams", ""));
			Object evObj = config.get("enableVision");
			boolean enableVision = evObj instanceof Boolean ? (Boolean) evObj : true;
			List<String> device = (List<String>) config.get("device");
			Object mgObj = config.get("mg");
			Integer mg = (mgObj instanceof Number) ? ((Number) mgObj).intValue() : null;
			String mode = ParamTool.asString(config.getOrDefault("mode", config.getOrDefault("paramMode", "form")));

			String chatTemplateFilePath = ChatTemplateFileTool.getChatTemplateCacheFilePathIfExists(modelId);

			return this.buildCommandStr(model, 0, llamaBinPath, device, mg, enableVision, cmd, extraParams, chatTemplateFilePath, null, mode, new java.util.LinkedHashMap<>());
		} catch (Exception e) {
			logger.warn("[模型状态] 重建启动命令失败: modelId={}, error={}", modelId, e.getMessage());
			return "";
		}
	}

	/**
	 * 构建模型的 preset 字符串（INI 格式配置）
	 * @param modelId 模型 ID
	 * @return preset 字符串
	 */
	private String buildPresetString(String modelId) {
		try {
			GGUFModel model = this.findModelById(modelId);
			if (model == null) return "";

			String modelFile = Paths.get(model.getPath(), model.getPrimaryModel().getFileName()).toString();

			StringBuilder sb = new StringBuilder();
			String alias = model.getAlias();
			if (alias == null || alias.trim().isEmpty()) {
				alias = model.getModelId();
			}
			sb.append("[").append(alias).append("]\n");
			sb.append("model = ").append(modelFile).append("\n");

			Map<String, Object> bundle = configManager.getModelLaunchConfigBundle(modelId);
			if (bundle == null) return sb.toString();

			String selectedConfigName = ParamTool.asString(bundle.get("selectedConfig"));
			if (selectedConfigName.trim().isEmpty()) return sb.toString();

			Map<String, Object> configs = ParamTool.asConfigMap(bundle.get("configs"));
			if (configs == null) return sb.toString();

			Map<String, Object> config = ParamTool.asConfigMap(configs.get(selectedConfigName));
			if (config == null) return sb.toString();

			Object evObj = config.get("enableVision");
			boolean enableVision = evObj instanceof Boolean ? (Boolean) evObj : true;

			if (enableVision && model.getMmproj() != null) {
				String mmprojFile = Paths.get(model.getPath(), model.getMmproj().getFileName()).toString();
				sb.append("mmproj = ").append(mmprojFile).append("\n");
			}

			return sb.toString();
		} catch (Exception e) {
			logger.warn("[模型状态] 构建 preset 失败: modelId={}, error={}", modelId, e.getMessage());
			return "";
		}
	}

	/**
	 * 构建模型 architecture 对象：
	 * { "input_modalities": ["text", ...], "output_modalities": ["text", ...] }
	 * @param model GGUF 模型对象
	 * @param caps 能力配置
	 * @return architecture JsonObject
	 */
	public JsonObject buildModelArchitecture(GGUFModel model, JsonObject caps) {
		JsonObject arch = new JsonObject();
		JsonArray inputs = new JsonArray();
		JsonArray outputs = new JsonArray();

		boolean isEmbedding = caps != null && ParamTool.parseJsonBoolean(caps, "embedding", false);
		boolean isRerank = caps != null && ParamTool.parseJsonBoolean(caps, "rerank", false);
		boolean hasVision = caps != null && ParamTool.parseJsonBoolean(caps, "vision", false);
		boolean hasAudio = caps != null && ParamTool.parseJsonBoolean(caps, "audio", false);

		inputs.add("text");
		if (hasVision || (model != null && model.getMmproj() != null)) {
			inputs.add("image");
		}
		if (hasAudio) {
			inputs.add("audio");
		}

		if (isEmbedding || isRerank) {
			outputs.add("embedding");
		} else {
			outputs.add("text");
		}

		arch.add("input_modalities", inputs);
		arch.add("output_modalities", outputs);
		return arch;
	}

	/**
	 * 构建模型 aliases 数组。
	 * @param model GGUF 模型对象
	 * @return aliases JsonArray
	 */
	private JsonArray buildModelAliases(GGUFModel model) {
		JsonArray aliases = new JsonArray();
		if (model != null && model.getAlias() != null && !model.getAlias().trim().isEmpty()) {
			String alias = model.getAlias().trim();
			if (!alias.equals(model.getModelId())) {
				aliases.add(alias);
			}
		}
		return aliases;
	}

    /**
      * 	处理这个路径的文件夹，找到可用的GGUF文件。
     * 	使用GGUFBundle来处理文件分组和识别
     * @param path
     * @return
     */
	private GGUFModel handleDirectory(Path path) {
		File dir = path.toFile();
		if (dir.getName().startsWith("."))
			return null;

		if (dir == null || !dir.isDirectory()) {
			logger.info("Invalid directory: {}", path);
			return null;
		}
		
		File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".gguf"));
		if (files == null || files.length == 0) {
			logger.info("No GGUF files found in directory: {}", path);
			return null;
		}

       // 寻找最佳的种子文件来初始化GGUFBundle
        File seedFile = null;
        GGUFMetaData cachedMeta = null;

        // 1. 尝试找到分卷的第一卷 (匹配 *-00001-of-*.gguf)
        for(File f : files) {
            String name = f.getName().toLowerCase();
            if(name.matches(".*-00001-of-\\d{5}\\.gguf$")) {
                seedFile = f;
                break;
            }
        }

        // 2. 如果没找到明确的第一卷，找一个真正的主模型文件（排除mmproj/projector等辅助文件）
        if(seedFile == null) {
            for(File f : files) {
                GGUFMetaData md = this.checkMainModel(f);
                if(md != null) {
                    seedFile = f;
                    cachedMeta = md;
                    break;
                }
            }
        }

        // 3. 如果仍没找到，说明目录里全是辅助文件（mmproj/mtp donor等），不当作模型
        if(seedFile == null) {
            logger.info("目录中未找到主模型文件（全是mmproj/projector等辅助文件）: {}", path);
            return null;
        }

        try {
            GGUFBundle bundle = new GGUFBundle(seedFile);

            GGUFModel model = new GGUFModel(dir.getName(), dir.getAbsolutePath());
            model.setAlias(dir.getName());

            // 处理主模型文件
            File primaryFile = bundle.getPrimaryFile();
            GGUFMetaData primaryMeta = null;
            if(cachedMeta != null && primaryFile != null && primaryFile.equals(seedFile)) {
                primaryMeta = cachedMeta;
                model.setPrimaryModel(cachedMeta);
                model.addMetaData(cachedMeta);
            } else if(primaryFile != null && primaryFile.exists()) {
                GGUFMetaData md = GGUFMetaData.readFile(primaryFile);
                if (md != null) {
                    primaryMeta = md;
                    model.setPrimaryModel(md);
                    model.addMetaData(md);
                }
            }
			
			// 处理mmproj文件
			File mmprojFile = bundle.getMmprojFile();
			if(mmprojFile != null && mmprojFile.exists()) {
				GGUFMetaData md = GGUFMetaData.readFile(mmprojFile);
				if (md != null) {
					model.setMmproj(md);
					model.addMetaData(md);
				}
			}
			
			// 优化：不再读取所有分卷文件的元数据
			// 分卷文件的元数据通常与主文件相同，或者只包含张量信息
			// 逐个读取会导致严重的IO性能问题
			/*
			List<File> splitFiles = bundle.getSplitFiles();
			if(splitFiles != null) {
				for(File f : splitFiles) {
					if(f.exists()) {
						GGUFMetaData md = GGUFMetaData.readFile(f);
						model.addMetaData(md);
					}
				}
			}
			*/
			
			model.setSize(bundle.getTotalFileSize());
			
			// 如果没有PrimaryModel，尝试从metaDataList中找一个
			if(model.getPrimaryModel() == null && !model.getMetaDataList().isEmpty()) {
				for(GGUFMetaData md : model.getMetaDataList()) {
					if("model".equals(md.getStringValue("general.type"))) {
						model.setPrimaryModel(md);
						break;
					}
				}
			}
			
			if (primaryFile != null && primaryFile.exists() && primaryFile.isFile()) {
				this.ensureCapabilitiesFileExists(model, primaryFile, primaryMeta != null ? primaryMeta : model.getPrimaryModel());
			}
			
			return model;
		} catch (Exception e) {
			logger.info("处理目录失败 " + path + ": {}", e);
			return null;
		}
	}

/**
     * 判断一个 .gguf 文件是否为主模型文件（而非 mmproj/projector/mtp-donor 等辅助文件）。
     * 返回元数据以便缓存，避免后续重复读取；返回 null 表示不是主模型或无法读取。
     */
    private GGUFMetaData checkMainModel(File file) {
        if (file == null || !file.isFile()) return null;
        String name = file.getName().toLowerCase();
        if (name.contains("mmproj")) return null;
        // 先通过文件名排除明显为独立 MTP donor / draft 的文件（metadata 可能不标准）
        if (isStandaloneMtpDonorFileName(name)) return null;
        GGUFMetaData md = GGUFMetaData.readFile(file);
        if (md == null) return null;
        if ("projector".equals(md.getGeneralType())) return null;
        if ("dflash-draft".equals(md.getArchitecture())) return null;
        // 仅包含 MTP 层的 donor 模型不能作为主模型
        if (md.isStandaloneMtpDonor()) return null;
        return md;
    }

    /**
     * 根据文件名判断是否为仅含 MTP 层的 donor/draft 文件。
     * 规则保守，只处理明确的命名后缀，避免误伤主模型。
     */
    private static boolean isStandaloneMtpDonorFileName(String lowerName) {
        if (!lowerName.endsWith(".gguf")) return false;
        String base = lowerName.substring(0, lowerName.length() - 5);
        return base.endsWith("-mtp-donor") || base.contains("-mtp-donor-")
            || base.endsWith("-donor") || base.contains("-donor-")
            || base.endsWith("-draft") || base.contains("-draft-");
    }

	/**
	 *
	 * @param modelId
	 * @return
	 */
	public GGUFModel findModelById(String modelId) {
		for(GGUFModel e : this.list) {
			if(e.getModelId().equals(modelId))
				return e;
		}
		return null;
	}
	
	/**
	 * 通过别名查找对应的 modelId
	 * @param name 别名（可能含 @ 后缀）
	 * @return 匹配的 modelId，未找到返回 null
	 */
	public String findModelIdByAlias(String name) {
		if (name == null || name.isBlank()) {
			return null;
		}
		String trimmed = name.trim();
		String base = trimmed;
		int at = base.indexOf('@');
		if (at > 0) {
			base = base.substring(0, at);
		}
		for (GGUFModel m : this.list) {
			if (m == null) continue;
			String alias = m.getAlias();
			if (alias != null && (alias.equals(trimmed) || alias.equals(base))) {
				return m.getModelId();
			}
		}
		return null;
	}
	
	/**
	 * 获取下一个可用端口
	 * 使用PortChecker工具类检查端口是否真正可用
	 * @return 下一个可用端口号
	 */
	private synchronized int getNextAvailablePort() {
		int candidatePort = this.portCounter.get();
		try {
			// 使用PortChecker查找下一个可用端口
			int availablePort = PortChecker.findNextAvailablePort(candidatePort);
			
			// 更新端口计数器，确保下次从更高的端口开始
			this.portCounter.set(availablePort + 1);
			
			return availablePort;
		} catch (IllegalStateException e) {
			// 如果在有效范围内找不到可用端口，回退到原来的简单递增方式
			// 并打印警告信息
			logger.info("警告: 无法找到可用端口，回退到简单递增方式。错误信息: {}", e);
			return this.portCounter.getAndIncrement();
		}
	}
	
	/**
	 * 获取已加载的模型进程列表
	 * @return 已加载的模型进程列表
	 */
	public Map<String, LlamaCppProcess> getLoadedProcesses() {
		synchronized (this.processLock) {
			return new HashMap<>(this.loadedProcesses);
		}
	}
	
	/**
	 * 	获取第一个已经加载的模型的名字。
	 * @return
	 */
	public String getFirstModelName() {
		synchronized (this.processLock) {
			if (this.loadedProcesses.isEmpty()) {
				return null;
			}
			Map.Entry<String, LlamaCppProcess> firstEntry = this.loadedProcesses.entrySet().iterator().next();
			return firstEntry.getKey();
		}
	}
	
	/**
	 * 	获取指定模型的启动参数。
	 * @param modelId
	 * @return
	 */
	public String getModelStartCmd(String modelId) {
		LlamaCppProcess process;
		synchronized (this.processLock) {
			process = this.loadedProcesses.get(modelId);
		}
		if (process == null) return "";
		return process.getCmd();
	}
	
	/**
	 * 获取模型对应的端口
	 * @param modelId 模型ID
	 * @return 端口号，如果模型未加载则返回null
	 */
	public Integer getModelPort(String modelId) {
		synchronized (this.processLock) {
			return this.modelPorts.get(modelId);
		}
	}
	
	/**
	 * 停止并移除已加载的模型
	 * @param modelId 模型ID
	 * @return 是否成功停止
	 */
	public boolean stopModel(String modelId) {
		String id = modelId == null ? "" : modelId.trim();
		LlamaCppProcess process;
		Future<?> task;
		synchronized (this.processLock) {
			process = this.loadedProcesses.get(id);
			task = null;
		}
		if (process != null) {
			boolean stopped = process.stop();
			if (stopped) {
				synchronized (this.processLock) {
					this.loadedProcesses.remove(id);
					this.modelPorts.remove(id);
				}
				this.loadedModelInfos.remove(id);
				this.modelLastUsedTime.remove(id);
				// 重建自动加载缓存，使刚卸载的模型重新出现在 /v1/models 的未加载列表中
				this.buildAutoLoadModelCache();
			}
			return stopped;
		}
		
		boolean loading = this.isLoading(id);
		synchronized (this.processLock) {
			process = this.loadingProcesses.get(id);
			task = this.loadingTasks.get(id);
			if (process != null || task != null || loading) {
				this.canceledLoadingModels.add(id);
			}
		}
		
		boolean stopped = false;
		if (process != null) {
			stopped = process.stop();
		} else if (task != null) {
			stopped = true;
		} else if (loading) {
			stopped = true;
		}
		if (task != null) {
			task.cancel(true);
		}
		if (stopped) {
			synchronized (this.processLock) {
				this.loadingProcesses.remove(id);
				this.loadingTasks.remove(id);
				this.modelPorts.remove(id);
			}
			synchronized (this.loadingModels) {
				this.loadingModels.remove(id);
			}
			this.loadedModelInfos.remove(id);
			this.modelLastUsedTime.remove(id);
		}
		return stopped;
	}
	
	/**
	 * 	检查指定ID的模型是否处于加载状态。
	 * @param modelId
	 * @return
	 */
	public boolean isLoading(String modelId) {
		synchronized (this.loadingModels) {
			return this.loadingModels.contains(modelId);
		}
	}
	
	/**
	 * 	通过CMD命令启动llama-server进程
	 * @param modelId
	 * @param llamaBinPath
	 * @param device
	 * @param mg
	 * @param enbaleVision
	 * @param cmd
	 * @param extraParams
	 * @param chatTemplateFilePath
	 * @param sourceModelId 克隆体的源模型 modelId，主体传 null
	 * @return
	 */
	public boolean loadModelAsyncFromCmd(String modelId, String llamaBinPath, List<String> device, Integer mg, boolean enbaleVision, String cmd, String extraParams, String chatTemplateFilePath, String sourceModelId, String mode) {
		Map<String, Object> launchConfig = new HashMap<>();
		launchConfig.put("llamaBinPathSelect", llamaBinPath);
		launchConfig.put("device", device);
		launchConfig.put("mg", mg);
		launchConfig.put("cmd", cmd);
		launchConfig.put("extraParams", extraParams);
		launchConfig.put("enableVision", enbaleVision);
		launchConfig.put("mode", mode);
		// sourceModelId 已在 launch_config 条目顶层保存，不需要重复写入 configs.default
		
		if (chatTemplateFilePath != null && !chatTemplateFilePath.trim().isEmpty()) {
			launchConfig.put("chatTemplateFile", chatTemplateFilePath);
		}
		this.configManager.saveLaunchConfig(modelId, launchConfig);
		this.buildAutoLoadModelCache();

		synchronized (this.processLock) {
			if (this.loadedProcesses.containsKey(modelId)) {
				LlamaServer.sendModelLoadEvent(modelId, sourceModelId, false, "模型已经加载", null);
				return false;
			}
		}

		GGUFModel targetModel = this.findModelById(sourceModelId != null ? sourceModelId : modelId);
		if (targetModel == null) {
			LlamaServer.sendModelLoadEvent(modelId, sourceModelId, false, "未找到ID为 " + (sourceModelId != null ? sourceModelId : modelId) + " 的模型", null);
			return false;
		}

		if (llamaBinPath == null || llamaBinPath.trim().isEmpty()) {
			LlamaServer.sendModelLoadEvent(modelId, sourceModelId, false, "未提供llamaBinPath", null);
			return false;
		}

		synchronized (this.loadingModels) {
			if (this.loadingModels.contains(modelId)) {
				LlamaServer.sendModelLoadEvent(modelId, sourceModelId, false, "该模型正在加载中", null);
				return false;
			}
			this.loadingModels.add(modelId);
		}

		final String cmdSafe = cmd == null ? "" : cmd.trim();
		final String extraSafe = extraParams == null ? "" : extraParams.trim();
		final String binSafe = llamaBinPath.trim();
		final List<String> devSafe = device;
		final Integer mgSafe = mg;
		final String chatTemplateFileSafe = chatTemplateFilePath == null ? "" : chatTemplateFilePath;
		final String sourceModelIdSafe = sourceModelId;
		final String modeSafe = mode == null || mode.trim().isEmpty() ? "form" : mode.trim();

		try {
			Future<?> future = this.executorService.submit(() -> {
				this.loadModelInBackgroundFromCmd(modelId, targetModel, binSafe, devSafe, mgSafe, enbaleVision, cmdSafe, extraSafe, chatTemplateFileSafe, sourceModelIdSafe, modeSafe);
			});
			synchronized (this.processLock) {
				this.loadingTasks.put(modelId, future);
			}
			if (this.isLoadCanceled(modelId)) {
				future.cancel(true);
			}
			return true;
		} catch (Exception e) {
			synchronized (this.loadingModels) {
				this.loadingModels.remove(modelId);
			}
			LlamaServer.sendModelLoadEvent(modelId, sourceModelId, false, "提交加载任务失败: " + e.getMessage(), null);
			return false;
		}
	}
	
	/**
	 * 	后台启动llama-server进程。
	 * @param modelId
	 * @param targetModel 克隆场景下为源模型的 GGUFModel（GGUF 路径取自它）
	 * @param llamaBinPath
	 * @param device
	 * @param mg
	 * @param enableVision
	 * @param cmd
	 * @param extraParams
	 * @param chatTemplateFilePath
	 * @param sourceModelId 克隆体的源模型 modelId，主体传 null
	 * @param mode 参数模式：form 或 cmd
	 */
	private void loadModelInBackgroundFromCmd(String modelId, GGUFModel targetModel, String llamaBinPath, List<String> device,
			Integer mg, boolean enableVision, String cmd, String extraParams, String chatTemplateFilePath, String sourceModelId, String mode) {
		String canonicalId = modelId;
		String sanitizedId = sanitizeModelId(canonicalId);
		try (var loadCtx = CloseableThreadContext.put("modelId", sanitizedId)) {
			try {
			if (this.isLoadCanceled(modelId)) {
				return;
			}
			int port = this.getNextAvailablePort();
			String allArgs;
			if ("cmd".equalsIgnoreCase(mode)) {
				allArgs = cmd == null ? "" : cmd.trim();
			} else {
				allArgs = (cmd == null ? "" : cmd.trim()) + (extraParams == null ? "" : " " + extraParams.trim());
			}
			Integer clientPort = cmdHasFlag(allArgs, "--port") ? extractPortFromCmd(allArgs) : null;
			int actualPort = clientPort != null && clientPort > 0 && clientPort < 65535 ? clientPort : port;
			String aliasModelId = null;
			if (sourceModelId != null) {
				String cloneAlias = configManager.loadAliasMap().get(modelId);
				aliasModelId = (cloneAlias != null && !cloneAlias.trim().isEmpty()) ? cloneAlias.trim() : modelId;
			}
			Map<String, String> envVars = new LinkedHashMap<>();
			String commandStr = this.buildCommandStr(targetModel, port, llamaBinPath, device, mg, enableVision, cmd, extraParams, chatTemplateFilePath, aliasModelId, mode, envVars);
			String processName = "llama-server-" + canonicalId;
			LlamaCppProcess process = new LlamaCppProcess(processName, commandStr, llamaBinPath, canonicalId, sourceModelId);
			if (!envVars.isEmpty()) {
				process.setEnvVars(envVars);
			}

			logger.info("启动命令：{}", commandStr);

			CountDownLatch latch = new CountDownLatch(1);
			AtomicBoolean loadSuccess = new AtomicBoolean(false);
			AtomicBoolean latchResolved = new AtomicBoolean(false);

			process.setOutputHandler(line -> {
				LlamaServer.sendConsoleLineEvent(modelId, line);
				if (line.contains("llama_server: model loaded") || line.contains("update_slots: all slots are idle")) {
					if (latchResolved.compareAndSet(false, true)) {
						loadSuccess.set(true);
						latch.countDown();
					}
					return;
				}
				String lower = line.toLowerCase(Locale.ROOT);
				if (isModelErrorLine(lower)) {
					logger.warn("检测到模型加载错误: {}", line);
					if (latchResolved.compareAndSet(false, true)) {
						loadSuccess.set(false);
						latch.countDown();
					}
				}
			});

			process.setOnProcessExited(info -> {
				try (var exitCtx = CloseableThreadContext.put("modelId", sanitizedId)) {
					logger.info("模型进程退出事件: modelId={}, exitCode={}, unexpected={}", modelId, info.exitCode, info.unexpected);
				if (latchResolved.compareAndSet(false, true)) {
					// 加载期间进程退出 = 加载失败（不论 unexpected 标志）
					logger.warn("模型进程在加载期间退出 (exitCode={}): {}", info.exitCode, modelId);
					loadSuccess.set(false);
					latch.countDown();
				} else {
					if (info.unexpected) {
						logger.warn("已加载的模型进程意外崩溃 (exitCode={}): {}", info.exitCode, modelId);
						boolean wasLoaded;
						synchronized (this.processLock) {
							wasLoaded = this.loadedProcesses.containsKey(modelId);
							this.loadedProcesses.remove(modelId);
							this.modelPorts.remove(modelId);
						}
						if (wasLoaded) {
							LlamaServer.sendModelStopEvent(modelId, sourceModelId, false, "模型进程意外崩溃 (exitCode=" + info.exitCode + ")");
							// 进程意外退出后重建缓存，使模型重新出现在列表中
							this.buildAutoLoadModelCache();
						}
					}
				}
				}
			});

			boolean started = process.start();
			if (!started) {
				if (this.isLoadCanceled(modelId)) {
					return;
				}
				LlamaServer.sendModelLoadEvent(modelId, sourceModelId, false, "启动模型进程失败", null);
				return;
			}
			
			synchronized (this.processLock) {
				this.loadingProcesses.put(modelId, process);
			}
			
			if (this.isLoadCanceled(modelId)) {
				process.stop();
				return;
			}
			LlamaServer.sendModelLoadStartEvent(modelId, sourceModelId, actualPort, "模型启动中");

			try {
				boolean timeout = !latch.await(10, TimeUnit.MINUTES);
				if (timeout) {
					process.stop();
					if (this.isLoadCanceled(modelId)) {
						return;
					}
					LlamaServer.sendModelLoadEvent(modelId, sourceModelId, false, "模型加载超时", null);
					return;
				}
				
				if (this.isLoadCanceled(modelId)) {
					process.stop();
					return;
				}

if (loadSuccess.get()) {
				synchronized (this.processLock) {
					this.loadedProcesses.put(modelId, process);
					this.modelPorts.put(modelId, actualPort);
				}
				this.modelLastUsedTime.put(modelId, System.currentTimeMillis());
				// 先取 /slots 设置 slotNum/ctxSize，再广播加载完成事件，让前端拿到正确的 slotNum 即时渲染小方块。
//				// 这里请求一�?
//				try {
//					JsonObject slotsResponse = this.handleModelSlotsGet(modelId);
//						int ctxSize = 0;
//						if (slotsResponse != null && slotsResponse.has("slots") && slotsResponse.get("slots").isJsonArray()) {
//							JsonArray slots = slotsResponse.getAsJsonArray("slots");
//							if (slots.size() > 0 && slots.get(0).isJsonObject()) {
//								JsonObject slot0 = slots.get(0).getAsJsonObject();
//								if (slot0.has("n_ctx") && !slot0.get("n_ctx").isJsonNull()) {
//									ctxSize = (int) Math.round(slot0.get("n_ctx").getAsDouble());
//								}
//							}
//						}
//						// 继续添加新东?
//						// TODO
//
//
//
//						process.setCtxSize(ctxSize);
//					}catch (Exception e) {
//						e.printStackTrace();
//						process.setCtxSize(0);
//					}
				// 这里再请求一�?
				int loadedSlotNum = 0;
				try {
					JsonObject slotsResponse = this.handleModelSlotsGet(modelId);
					int ctxSize = 0;
					int slotNum = 1;
					if (slotsResponse != null && slotsResponse.has("slots") && slotsResponse.get("slots").isJsonArray()) {
						JsonArray slots = slotsResponse.getAsJsonArray("slots");
						if (slots.size() > 0 && slots.get(0).isJsonObject()) {
							JsonObject slot0 = slots.get(0).getAsJsonObject();
							if (slot0.has("n_ctx") && !slot0.get("n_ctx").isJsonNull()) {
								ctxSize = (int) Math.round(slot0.get("n_ctx").getAsDouble());
							}
						}
						slotNum = slots.size();
					}
					process.setCtxSize(ctxSize);
					process.setSlotNum(slotNum);
					loadedSlotNum = slotNum;
				}catch (Exception e) {
					e.printStackTrace();
					process.setCtxSize(0);
				}
				LlamaServer.sendModelLoadEvent(modelId, sourceModelId, true, "模型加载成功", actualPort, loadedSlotNum);
				try {
					this.handleModelInfo(modelId);
				} catch (Exception e) {
					logger.info("获取/v1/models信息失败: " + modelId, e);
				}
				} else {
					process.stop();
					if (this.isLoadCanceled(modelId)) {
						return;
					}
					LlamaServer.sendModelLoadEvent(modelId, sourceModelId, false, "模型加载失败", null);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				process.stop();
				if (this.isLoadCanceled(modelId)) {
					return;
				}
				LlamaServer.sendModelLoadEvent(modelId, sourceModelId, false, "模型加载被中断", null);
			}
		} finally {
			synchronized (this.processLock) {
				this.loadingProcesses.remove(modelId);
				this.loadingTasks.remove(modelId);
				this.canceledLoadingModels.remove(modelId);
			}
			synchronized (this.loadingModels) {
				this.loadingModels.remove(modelId);
			}
		}
		}
	}
	
	private boolean isLoadCanceled(String modelId) {
		synchronized (this.processLock) {
			return this.canceledLoadingModels.contains(modelId);
		}
	}

	private static boolean isModelErrorLine(String lower) {
		return lower.contains("exiting due to model loading error")
			|| lower.contains("exiting due to http server error")
			|| lower.contains("error while handling")
			|| lower.contains("failed to load model")
			|| lower.contains("error: failed to load")
			|| lower.contains("error: model loading")
			|| (lower.contains("error") && lower.contains("gguf"))
			|| lower.contains("segfault")
			|| lower.contains("segmentation fault")
			|| lower.contains("signal 11")
			|| lower.contains("signal 6")
			|| lower.contains("cannot allocate")
			|| lower.contains("out of memory")
			|| lower.contains("cuda error")
			|| lower.contains("hip error")
			|| lower.contains("sycl error")
			|| lower.contains("ggml_assert")
			|| lower.contains("ggml_abort")
			|| (lower.contains("assertion") && lower.contains("failed"))
			|| (lower.contains("error") && lower.contains("initialize"))
			|| (lower.contains("error") && lower.contains("context"));
	}
	
	private static Integer extractPortFromCmd(String cmd) {
		if (cmd == null || cmd.trim().isEmpty()) {
			return null;
		}
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("--port[=\\s]+(\\d+)").matcher(cmd);
		if (m.find()) {
			try {
				return Integer.parseInt(m.group(1));
			} catch (NumberFormatException e) {
				return null;
			}
		}
		return null;
	}

	/**
	 *
	 * @param targetModel
	 * @param port
	 * @param llamaBinPath
	 * @param device
	 * @param mg
	 * @param cmd
	 * @param extraParams
	 * @param chatTemplateFilePath
	 * @param aliasModelId
	 * @param mode 参数模式：form 或 cmd
	 * @param envVarsOut 命令行模式下提取出的环境变量会写入此 Map
	 * @return
	 */
	private String buildCommandStr(GGUFModel targetModel, int port, String llamaBinPath, List<String> device, Integer mg, boolean enableVision, String cmd, String extraParams, String chatTemplateFilePath, String aliasModelId, String mode, Map<String, String> envVarsOut) {
		boolean isCmdMode = "cmd".equalsIgnoreCase(mode);
		if (isCmdMode) {
			return buildCommandStrFromCmd(targetModel, port, llamaBinPath, cmd, aliasModelId, envVarsOut);
		}
		return buildCommandStrFromForm(targetModel, port, llamaBinPath, device, mg, enableVision, cmd, extraParams, chatTemplateFilePath, aliasModelId);
	}

	/**
	 * 表单模式：保持原有拼接逻辑不变。
	 */
	private String buildCommandStrFromForm(GGUFModel targetModel, int port, String llamaBinPath, List<String> device, Integer mg, boolean enableVision, String cmd, String extraParams, String chatTemplateFilePath, String aliasModelId) {
		StringBuilder sb = new StringBuilder();
		String allArgs = "";
		if (cmd != null && !cmd.trim().isEmpty()) allArgs = cmd.trim();
		if (extraParams != null && !extraParams.trim().isEmpty()) {
			String e = extraParams.trim();
			allArgs = allArgs.isEmpty() ? e : (allArgs + " " + e);
		}
		String exeName = isWindows() ? "llama-server.exe" : "llama-server";
		String exe = Paths.get(llamaBinPath, exeName).toString();
		sb.append(ParamTool.quoteIfNeeded(exe));

		sb.append(" -m ");
		String modelFile = Paths.get(targetModel.getPath(), targetModel.getPrimaryModel().getFileName()).toString();
		sb.append(ParamTool.quoteIfNeeded(modelFile));

		if (!cmdHasFlag(allArgs, "--port")) {
			sb.append(" --port ");
			sb.append(port);
		}

		//	确认启用视觉
		if(enableVision) {
			if (targetModel.getMmproj() != null && !cmdHasFlag(allArgs, "--mmproj") && !cmdHasFlag(allArgs, "--no-mmproj")) {
				sb.append(" --mmproj ");
				String mmprojFile = Paths.get(targetModel.getPath(), targetModel.getMmproj().getFileName()).toString();
				sb.append(ParamTool.quoteIfNeeded(mmprojFile));
			}
		}

		// 过滤无效设备值（'All' 是前端"全选"的标记值，不是合法设备名；旧配置中可能残留）
		List<String> effectiveDevice = new ArrayList<>();
		if (device != null) {
			for (String d : device) {
				if (d == null) continue;
				String t = d.trim();
				if (t.isEmpty() || t.equalsIgnoreCase("none") || t.equalsIgnoreCase("all")) continue;
				effectiveDevice.add(t);
			}
		}

		if (!effectiveDevice.isEmpty()) {
			if (effectiveDevice.size() == 1) {
				sb.append(" -sm none --device ");
				sb.append(ParamTool.quoteIfNeeded(effectiveDevice.get(0)));
			} else {
				sb.append(" --device ");
				sb.append(ParamTool.quoteIfNeeded(String.join(",", effectiveDevice)));
			}
			if(mg != null && mg >= 0) {
				sb.append(" --main-gpu ");
				sb.append(String.valueOf(mg));
			}
		}

		if (cmd != null && !cmd.trim().isEmpty()) {
			String processed = splitSpecType(cmd.trim());
			processed = ParamTool.stripFlagWithValue(processed, "--alias");
			sb.append(' ');
			sb.append(processed);
		}
		if (extraParams != null && !extraParams.trim().isEmpty()) {
			String processed = splitSpecType(extraParams.trim());
			processed = ParamTool.stripFlagWithValue(processed, "--alias");
			sb.append(' ');
			sb.append(processed);
		}
		if (chatTemplateFilePath != null && !chatTemplateFilePath.trim().isEmpty() && !cmdHasFlag(allArgs, "--chat-template-file") && !cmdHasFlag(allArgs, "--chat-template")) {
			sb.append(" --chat-template-file ");
			sb.append(ParamTool.quoteIfNeeded(chatTemplateFilePath.trim()));
		}

//		if (!cmdHasFlag(allArgs, "--no-webui") && !cmdHasFlag(allArgs, "--webui")) {
//			sb.append(" --no-webui");
//		}
		if (!cmdHasFlag(allArgs, "--metrics")) {
			sb.append(" --metrics");
		}
		// 一些分支不兼容这玩意，先注释掉吧，后续改为可选参数。
		//if (!cmdHasFlag(allArgs, "--slot-save-path")) {
			//sb.append(" --slot-save-path ");
			//sb.append(ParamTool.quoteIfNeeded(LlamaServer.getCachePath().toFile().getAbsolutePath()));
		//}
		//if (!cmdHasFlag(allArgs, "--cache-ram")) {
			//sb.append(" --cache-ram -1");
		//}
		String alias;
		if (aliasModelId != null && !aliasModelId.trim().isEmpty()) {
			alias = aliasModelId;
		} else {
			alias = targetModel.getAlias();
			if (alias == null || alias.trim().isEmpty()) {
				alias = targetModel.getModelId();
			}
		}
		sb.append(" --alias ").append(ParamTool.quoteIfNeeded(alias));

		sb.append(" --timeout 36000");
		// 允许任意IP地址访问
		sb.append(" --host 0.0.0.0");
		// 输出详细日志 一些分支不兼容这玩意，先注释掉吧，后续改为可选参数。
		//sb.append(" -lv 4");

		return sb.toString().trim();
	}

	/**
	 * 命令行模式：用户输入为权威来源，后端只负责补全必要参数、提取环境变量。
	 * 用户输入中的可执行文件路径和 -m/--model 会被忽略，强制使用当前选择模型。
	 */
	private String buildCommandStrFromCmd(GGUFModel targetModel, int port, String llamaBinPath, String cmd, String aliasModelId, Map<String, String> envVarsOut) {
		String trimmedCmd = cmd == null ? "" : cmd.trim();

		// 1. 拆分 token，提取前导环境变量
		List<String> tokens = LlamaCppProcess.splitCommandLineArgs(trimmedCmd);
		List<String> userArgs = new ArrayList<>();
		for (String token : tokens) {
			int eq = token.indexOf('=');
			if (eq > 0 && userArgs.isEmpty() && isValidEnvVarKey(token.substring(0, eq))) {
				String key = token.substring(0, eq);
				String value = token.substring(eq + 1);
				envVarsOut.put(key, value);
			} else {
				userArgs.add(token);
			}
		}

		// 2. 忽略用户输入的可执行文件路径（如果第一个 token 是路径）
		if (!userArgs.isEmpty()) {
			String first = userArgs.get(0);
			if (first.contains("/") || first.contains("\\")) {
				userArgs.remove(0);
			}
		}

		// 3. 忽略用户输入的 -m / --model 及其值
		for (int i = 0; i < userArgs.size(); ) {
			String t = userArgs.get(i);
			if ("-m".equals(t) || "--model".equals(t)) {
				userArgs.remove(i);
				if (i < userArgs.size()) {
					userArgs.remove(i);
				}
			} else if (t.startsWith("--model=")) {
				userArgs.remove(i);
			} else {
				i++;
			}
		}

		// 4. 组装命令：binary + 强制 -m + 用户参数 + 缺失的后端必需参数
		StringBuilder sb = new StringBuilder();
		String exeName = isWindows() ? "llama-server.exe" : "llama-server";
		String exe = Paths.get(llamaBinPath, exeName).toString();
		sb.append(ParamTool.quoteIfNeeded(exe));

		sb.append(" -m ");
		String modelFile = Paths.get(targetModel.getPath(), targetModel.getPrimaryModel().getFileName()).toString();
		sb.append(ParamTool.quoteIfNeeded(modelFile));

		for (String arg : userArgs) {
			sb.append(' ').append(ParamTool.quoteIfNeeded(arg));
		}

		if (!hasFlagToken(userArgs, "--port")) {
			sb.append(" --port ").append(port);
		}

		String alias;
		if (aliasModelId != null && !aliasModelId.trim().isEmpty()) {
			alias = aliasModelId;
		} else {
			alias = targetModel.getAlias();
			if (alias == null || alias.trim().isEmpty()) {
				alias = targetModel.getModelId();
			}
		}
		if (!hasFlagToken(userArgs, "--alias")) {
			sb.append(" --alias ").append(ParamTool.quoteIfNeeded(alias));
		}

		if (!hasFlagToken(userArgs, "--host")) {
			sb.append(" --host 0.0.0.0");
		}
		if (!hasFlagToken(userArgs, "--timeout")) {
			sb.append(" --timeout 36000");
		}
		if (!hasFlagToken(userArgs, "--metrics")) {
			sb.append(" --metrics");
		}

		return sb.toString().trim();
	}

	private static boolean isValidEnvVarKey(String key) {
		return key != null && !key.isEmpty() && key.matches("[A-Za-z_][A-Za-z0-9_]*");
	}

	private static boolean hasFlagToken(List<String> tokens, String flag) {
		if (tokens == null || tokens.isEmpty()) return false;
		for (String token : tokens) {
			if (token.equals(flag) || token.startsWith(flag + "=")) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 	判断是否包含某个字段。
	 * @param cmd
	 * @param flag
	 * @return
	 */
	private boolean cmdHasFlag(String cmd, String flag) {
		if (cmd == null || flag == null || flag.trim().isEmpty()) {
			return false;
		}
		String f = flag.trim();
		String s = " " + cmd.trim() + " ";
		return s.contains(" " + f + " ") || s.contains(" " + f + "=");
	}

	private static boolean isWindows() {
		String os = System.getProperty("os.name");
		return os != null && os.toLowerCase(Locale.ROOT).contains("win");
	}

	private static String sanitizeModelId(String id) {
		if (id == null) return "unknown";
		return id.replace('\\', '_').replace('/', '_').replace(':', '_');
	}

	/**
	 * 合并多个 --spec-type 为逗号分隔形式。
	 * 处理两种格式：
	 *   --spec-type_xxx（下划线，来自前端 LOGIC 参数，如 ngram-mod）
	 *   --spec-type xxx（空格，来自前端 STRING 参数或 extraParams）
	 * 输出：--spec-type val1,val2,val3
	 * 注意：STRING 参数 --spec-type 的值若为 none 则不添加。
	 */
	private static String splitSpecType(String input) {
		if (input == null || input.isEmpty()) return input;

		String[] tokens = input.split("\\s+");
		List<String> values = new ArrayList<>();
		List<String> out = new ArrayList<>();

		for (int i = 0; i < tokens.length; i++) {
			String t = tokens[i];

			// --spec-type_xxx（如 ngram-mod、ngram-map-k4v）
			if (t.startsWith("--spec-type_")) {
				String v = t.substring("--spec-type_".length());
				if (!v.isEmpty()) values.add(v);
				continue;
			}

			// --spec-type xxx
			if ("--spec-type".equals(t) && i + 1 < tokens.length) {
				String v = tokens[i + 1];
				// 过滤掉 "none"（表示未启用）
				if (!v.isEmpty() && !"none".equals(v)) values.add(v);
				i++;
				continue;
			}

			out.add(t);
		}

		if (values.isEmpty()) return input;

		out.add(0, "--spec-type " + String.join(",", values));
		return String.join(" ", out);
	}
	
	//##########################################################################################

	private static final class HttpResult {
		private final int statusCode;
		private final String body;

		private HttpResult(int statusCode, String body) {
			this.statusCode = statusCode;
			this.body = body == null ? "" : body;
		}
	}

	private int requireLoadedModelPort(String modelId) {
		String id = modelId == null ? "" : modelId.trim();
		if (id.isEmpty()) {
			throw new IllegalArgumentException("缺少必需的modelId参数");
		}
		if (!this.getLoadedProcesses().containsKey(id)) {
			throw new IllegalArgumentException("模型未加载: " + id);
		}
		Integer port = this.getModelPort(id);
		if (port == null) {
			throw new IllegalStateException("未找到模型端口: " + id);
		}
		return port.intValue();
	}

	private static String readAll(BufferedReader br) throws IOException {
		if (br == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		String line;
		while ((line = br.readLine()) != null) {
			sb.append(line);
		}
		return sb.toString();
	}

	private HttpResult callLocalModelEndpoint(int port, String method, String endpoint, JsonObject body, int connectTimeoutMs, int readTimeoutMs) throws Exception {
		String urlStr = String.format("http://localhost:%d%s", port, endpoint);
		URL url = URI.create(urlStr).toURL();
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();
		try {
			connection.setRequestMethod(method);
			connection.setConnectTimeout(connectTimeoutMs);
			connection.setReadTimeout(readTimeoutMs);
			if (body != null) {
				connection.setDoOutput(true);
				connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
				byte[] input = body.toString().getBytes(StandardCharsets.UTF_8);
				try (OutputStream os = connection.getOutputStream()) {
					os.write(input, 0, input.length);
				}
			}
			int code = connection.getResponseCode();
			boolean ok = code >= 200 && code < 300;
			try (BufferedReader br = new BufferedReader(new InputStreamReader(ok ? connection.getInputStream() : connection.getErrorStream(), StandardCharsets.UTF_8))) {
				return new HttpResult(code, readAll(br));
			} catch (Exception e) {
				return new HttpResult(code, "");
			}
		} finally {
			try {
				connection.disconnect();
			} catch (Exception ignore) {
			}
		}
	}

	private Object tryParseJson(String body) {
		if (body == null || body.isBlank()) {
			return null;
		}
		try {
			return gson.fromJson(body, Object.class);
		} catch (Exception e) {
			return null;
		}
	}

	private JsonObject tryParseJsonObject(String body) {
		if (body == null || body.isBlank()) {
			return null;
		}
		try {
			JsonElement el = JsonParser.parseString(body);
			if (el == null || el.isJsonNull()) {
				return null;
			}
			if (el.isJsonObject()) {
				return el.getAsJsonObject();
			}
			if (el.isJsonArray()) {
				JsonObject wrapped = new JsonObject();
				wrapped.add("slots", el.getAsJsonArray());
				return wrapped;
			}
			return null;
		} catch (Exception e) {
			return null;
		}
	}
	
	
	public JsonObject handleModelInfo(String modelId) {
		try {
			String id = modelId == null ? "" : modelId.trim();
			int port = this.requireLoadedModelPort(id);
			HttpResult r = this.callLocalModelEndpoint(port, "GET", "/v1/models", null, 30000, 30000);
			if (r.statusCode < 200 || r.statusCode >= 300) {
				throw new RuntimeException("获取模型信息失败: " + r.body);
			}
			JsonObject root = this.tryParseJsonObject(r.body);
			if (root == null) {
				throw new RuntimeException("获取模型信息失败: 返回不是JSON对象");
			}

			JsonArray models = root.has("models") && root.get("models").isJsonArray() ? root.getAsJsonArray("models") : new JsonArray();
			JsonArray data = root.has("data") && root.get("data").isJsonArray() ? root.getAsJsonArray("data") : new JsonArray();

			Map<String, JsonObject> dataById = new LinkedHashMap<>();
			for (JsonElement el : data) {
				if (el == null || el.isJsonNull() || !el.isJsonObject()) {
					continue;
				}
				JsonObject obj = el.getAsJsonObject();
				String key = jsonString(obj, "id");
				if (!key.isEmpty()) {
					dataById.put(key, obj);
				}
			}

			Set<String> used = new HashSet<>();
			JsonArray items = new JsonArray();
			for (JsonElement el : models) {
				if (el == null || el.isJsonNull() || !el.isJsonObject()) {
					continue;
				}
				JsonObject modelObj = el.getAsJsonObject();
				String key = jsonString(modelObj, "model");
				if (key.isEmpty()) {
					key = jsonString(modelObj, "name");
				}

				JsonObject item = new JsonObject();
				if (!key.isEmpty()) {
					item.addProperty("id", key);
				}
				item.add("model", modelObj.deepCopy());

				JsonObject dataObj = !key.isEmpty() ? dataById.get(key) : null;
				if (dataObj != null) {
					item.add("data", dataObj.deepCopy());
					used.add(key);
				}
				items.add(item);
			}

			for (Map.Entry<String, JsonObject> e : dataById.entrySet()) {
				String key = e.getKey();
				if (key == null || key.isBlank() || used.contains(key)) {
					continue;
				}
				JsonObject item = new JsonObject();
				item.addProperty("id", key);
				item.add("data", e.getValue().deepCopy());
				items.add(item);
			}

			JsonObject out = new JsonObject();
			out.addProperty("modelId", id);
			out.addProperty("port", port);
			out.addProperty("fetchedAt", System.currentTimeMillis());
			out.add("items", items);

			this.loadedModelInfos.put(id, out);
			return out;
		} catch (Exception e) {
			logger.info("获取模型信息时发生错误", e);
			throw new RuntimeException("获取模型信息失败: " + e.getMessage(), e);
		}
	}
	
	/**
	 * 	获取已加载模型的信息。
	 * @param modelId
	 * @return
	 */
	public JsonObject getLoadedModelInfo(String modelId) {
		String id = modelId == null ? "" : modelId.trim();
		if (id.isEmpty()) {
			return null;
		}
		JsonObject found = this.loadedModelInfos.get(id);
		return found == null ? null : found.deepCopy();
	}

	private static String jsonString(JsonObject obj, String key) {
		if (obj == null || key == null || key.isBlank()) {
			return "";
		}
		if (!obj.has(key) || obj.get(key).isJsonNull()) {
			return "";
		}
		try {
			return obj.get(key).getAsString().trim();
		} catch (Exception ignore) {
			return "";
		}
	}
	
	/**
	 * 	获取Slots信息
	 * @param modelId
	 * @return
	 */
	public JsonObject handleModelSlotsGet(String modelId) {
		try {
			int port = this.requireLoadedModelPort(modelId);
			HttpResult r = this.callLocalModelEndpoint(port, "GET", "/slots", null, 30000, 30000);
			if (r.statusCode >= 200 && r.statusCode < 300) {
				JsonObject parsed = this.tryParseJsonObject(r.body);
				return parsed != null ? parsed : new JsonObject();
			}
			throw new RuntimeException("获取slots失败: " + r.body);
		} catch (Exception e) {
			logger.info("获取slots时发生错误", e);
			throw new RuntimeException("获取slots失败: " + e.getMessage(), e);
		}
	}
	
	/**
	 * 	
	 * @param modelId
	 * @param slot
	 * @param fileName
	 * @return
	 */
	public ApiResponse handleModelSlotsSave(String modelId, int slot, String fileName) {
		try {
			int port = this.requireLoadedModelPort(modelId);
			String endpoint = String.format("/slots/%d?action=save", slot);
			JsonObject body = new JsonObject();
			body.addProperty("filename", fileName);
			HttpResult r = this.callLocalModelEndpoint(port, "POST", endpoint, body, 36000 * 1000, 36000 * 1000);
			if (r.statusCode >= 200 && r.statusCode < 300) {
				Object parsed = this.tryParseJson(r.body);
				Map<String, Object> data = new HashMap<>();
				data.put("modelId", modelId);
				data.put("result", parsed);
				return ApiResponse.success(data);
			}
			return ApiResponse.error(I18N_SLOT_SAVE_FAILED + ": " + r.body);
		} catch (Exception e) {
			logger.info("保存slot缓存时发生错误", e);
			return ApiResponse.error(I18N_SLOT_SAVE_FAILED + ": " + e.getMessage());
		}
	}
	
	/**
	 * 	
	 * @param modelId
	 * @param slot
	 * @param fileName
	 * @return
	 */
	public ApiResponse handleModelSlotsLoad(String modelId, int slot, String fileName) {
		try {
			int port = this.requireLoadedModelPort(modelId);
			String endpoint = String.format("/slots/%d?action=restore", slot);
			JsonObject body = new JsonObject();
			body.addProperty("filename", fileName);
			HttpResult r = this.callLocalModelEndpoint(port, "POST", endpoint, body, 36000 * 1000, 36000 * 1000);
			if (r.statusCode >= 200 && r.statusCode < 300) {
				Object parsed = this.tryParseJson(r.body);
				Map<String, Object> data = new HashMap<>();
				data.put("modelId", modelId);
				data.put("result", parsed);
				return ApiResponse.success(data);
			}
			return ApiResponse.error(I18N_SLOT_LOAD_FAILED + ": " + r.body);
		} catch (Exception e) {
			logger.info("加载slot缓存时发生错误", e);
			return ApiResponse.error(I18N_SLOT_LOAD_FAILED + ": " + e.getMessage());
		}
	}
	
	
	/**
	 * 	查找可用的计算设备
	 * @param llamaBinPath
	 * @return
	 */
	public List<String> handleListDevices(String llamaBinPath) {
		List<String> list = new ArrayList<>(8);
		// TEMP: 固定返回 4 张同型号 GPU，便于验证前端是否会错误合并同名设备。
		//list.add("CUDA0: NVIDIA GeForce RTX 4090 (24111 MiB, 23718 MiB free)");
		//list.add("CUDA1: NVIDIA GeForce RTX 4090 (24111 MiB, 23718 MiB free)");
		//list.add("CUDA2: NVIDIA GeForce RTX 4090 (24111 MiB, 23718 MiB free)");
		//list.add("CUDA3: NVIDIA GeForce RTX 4090 (24111 MiB, 23718 MiB free)");
		//if(true)
			//return list;
		
		String executableName = "llama-bench";
		// 拼接完整命令路径
		String command = llamaBinPath.trim();
		command += File.separator;
		
		command += executableName + " --list-devices";
		
		// 执行命令
		String osName = System.getProperty("os.name");
		String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
		int timeoutSeconds = os.contains("win") ? 30 : 5;
		CommandLineRunner.CommandResult result = CommandLineRunner.execute(command, timeoutSeconds);
		// 根据list device的返回结果。拼凑设备
		String rawOut = result.getOutput() == null ? "" : result.getOutput();
		String rawErr = result.getError() == null ? "" : result.getError();
		String raw = rawOut.contains("Available devices") ? rawOut : (rawErr.isBlank() ? rawOut : rawErr);

		String[] lines = raw.split("\\R");
		int start = 0;
		for (int i = 0; i < lines.length; i++) {
			if (lines[i] != null && lines[i].contains("Available devices")) {
				start = i + 1;
				break;
			}
		}
		for (int i = start; i < lines.length; i++) {
			String line = lines[i];
			if (line == null) {
				continue;
			}
			String v = line.trim();
			if (!v.isEmpty() && !v.equalsIgnoreCase("(none)")) {
				list.add(v);
			}
		}
		return list;
	}
	
	
	/**
	 * 	重复的代码太多了，但是也没什么必要去管。
	 * @param modelMetaDataPath
	 * @param combinedCmd
	 * @return
	 * @throws Exception
	 */
	public Map<String, String> handleFitParam(String modelMetaDataPath, String combinedCmd) throws Exception {
		List<String> cmdlist = ParamTool.splitCmdArgs(combinedCmd);
		
		Map<String, String> resultMap = new HashMap<>();
		
		File file = new File(modelMetaDataPath);
		
		if(!file.exists()) {
			resultMap.put("output", "");
			resultMap.put("error", "File not found: " + modelMetaDataPath);
			return resultMap;
		}
		
		String[] keysParam = {"--ctx-size", "--flash-attn", "--batch-size", "--ubatch-size", "--parallel", "--cache-type-k", "--cache-type-v", "--device", "--main-gpu", "--swa-full", "--split-mode", "--fit", "--spec-draft-type-k", "--spec-draft-type-v"};
		
		Map<String, String> cmdMap = new HashMap<>();
		for(int i = 0; i < cmdlist.size(); i++) {
			String param = cmdlist.get(i);
			if(param.startsWith("--") && i + 1 < cmdlist.size()) {
				// 如果当前参数的下一个不是参数名，而是值
				if(!cmdlist.get(i + 1).startsWith("--")) {
					cmdMap.put(param, cmdlist.get(i + 1));
					i += 1;
				}else {
					cmdMap.put(param, param);
				}
			}
		}
		
		// 找到可执行文件
		String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);

		String platform;
		if (os.contains("win")) {
			platform = "win-x64";
		} else if (os.contains("linux")) {
			platform = "linux-x64";
		} else {
			throw new UnsupportedOperationException("Unsupported OS: " + os);
		}

		String exeName = platform.equals("win-x64") ? "gguf-mem.exe" : "gguf-mem";
		String exePath = "/tools/gguf-mem/" + platform + "/" + exeName;

		URL url = LlamaServerManager.class.getResource(exePath);
		if(url == null) {
			throw new FileNotFoundException("gguf-mem binary not found: " + exePath);
		}
		File exeFile = Paths.get(url.toURI()).toFile();
		if (!exeFile.exists()) {
			throw new FileNotFoundException("gguf-mem binary not found: " + exePath);
		}

		// Set executable permission
		if (!exeFile.canExecute()) {
			exeFile.setExecutable(true, false);
			if (!exeFile.canExecute() && !os.contains("win")) {
				try {
					new ProcessBuilder("chmod", "+x", exePath).start().waitFor();
				} catch (Exception e) {
					logger.info("[gguf-mem] 增加可执行权限失败：", e);
				}
			}
		}
		// 拼接完整命令路径
		String command = exeFile.getAbsolutePath();
		command += " --model " + ParamTool.quoteIfNeeded(modelMetaDataPath);
		command += " -fitp on";
		//
		// 针对--main-gpu做特殊处理
		if(cmdMap.containsKey("--main-gpu")) {
			// 如果是默认值-1，要改成0
			if("-1".equals(cmdMap.get("--main-gpu"))) {
				cmdMap.put("--main-gpu", "0");
			}
		}
		// 针对--split-mode做特殊处理
		if(cmdMap.containsKey("--split-mode")) {
			cmdMap.put("-sm", cmdMap.get("--split-mode"));
			cmdMap.remove("--split-mode");
		}
		for(String key : keysParam) {
			// 如果有这个参数
			if(cmdMap.containsKey(key)) {
				String value = cmdMap.get(key);
				if(key.equals(value)) {
					command += " " + key + " ";
				}else {
					command += " " + key + " " + value;
				}
			}
		}
		logger.info("执行gguf-mem命令：{}", command);
		// 执行命令
		CommandLineRunner.CommandResult result = CommandLineRunner.execute(command, 30);
		resultMap.put("output", result.getOutput() != null ? result.getOutput() : "");
		resultMap.put("error", result.getError() != null ? result.getError() : "");
		return resultMap;
	}
	
	
	/**
	 * 	调用llama-fit-params
	 * @param llamaBinPath
	 * @param modelId
	 * @param combinedCmd
	 * @return
	 */
	public Map<String, String> handleFitParam(String llamaBinPath, String modelId, String combinedCmd) throws Exception {
		List<String> cmdlist = ParamTool.splitCmdArgs(combinedCmd);
		String[] keysParam = {"--ctx-size", "--flash-attn", "--batch-size", "--ubatch-size", "--parallel", "--cache-type-k", "--cache-type-v", "--device", "--main-gpu", "--swa-full", "--split-mode", "--fit", "--spec-draft-type-k", "--spec-draft-type-v"};
		Map<String, String> cmdMap = new HashMap<>();
		for(int i = 0; i < cmdlist.size(); i++) {
			String param = cmdlist.get(i);
			if(param.startsWith("--") && i + 1 < cmdlist.size()) {
				// 如果当前参数的下一个不是参数名，而是值
				if(!cmdlist.get(i + 1).startsWith("--")) {
					cmdMap.put(param, cmdlist.get(i + 1));
					i += 1;
				}else {
					cmdMap.put(param, param);
				}
			}
		}
		GGUFModel model = this.findModelById(modelId);
		// 克隆体用源模型 GGUF 路径做显存估算
		if (model == null) {
			String sourceModelId = this.getSourceModelId(modelId);
			if (sourceModelId != null) {
				model = this.findModelById(sourceModelId);
			}
		}
		if(model == null) {
			throw new RuntimeException("Model not found: " + modelId);
		}

		String executableName = "llama-fit-params";
		// 拼接完整命令路径
		String command = ParamTool.quoteIfNeeded(llamaBinPath.trim() + File.separator + executableName);
		command += " --model " + ParamTool.quoteIfNeeded(model.getPrimaryModel().getFilePath());
		
		// 仅针对--main-gpu做特殊处理
		if(cmdMap.containsKey("--main-gpu")) {
			// 如果是默认值-1，要改成0
			if("-1".equals(cmdMap.get("--main-gpu"))) {
				cmdMap.put("--main-gpu", "0");
			}
		}
		for(String key : keysParam) {
			// 如果有这个参数
			if(cmdMap.containsKey(key)) {
				String value = cmdMap.get(key);
				if(key.equals(value)) {
					command += " " + key + " ";
				}else {
					command += " " + key + " " + value;
				}
			}
		}
		logger.info("执行llama-fit-param命令：{}", command);
		// 执行命令
		CommandLineRunner.CommandResult result = CommandLineRunner.execute(command, 30);
		String output = result.getOutput();
		// 如果计算失败，就抛出异常
		if(output.trim().length() == 0)
			throw new RuntimeException(result.getError().trim().length() == 0 ? "No error infomation" : result.getError());
		// 解析拟合参数
		String[] lines = output.split("\n");
		String fittedLine = null;
		for (int i = lines.length - 1; i >= 0; i--) {
			String line = lines[i].trim();
			if (line.isEmpty()) continue;
			if (!line.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+\\s+I\\s+.*")) {
				fittedLine = line;
				break;
			}
		}
		if (fittedLine == null || fittedLine.isEmpty()) {
			throw new RuntimeException("llama-fit-params输出中未找到拟合参数行: " + output);
		}
		List<String> tokens = ParamTool.splitCmdArgs(fittedLine);
		Set<String> knownFlags = Set.of("-c", "-ngl", "-ts", "-ot");
		Map<String, String> fittedParams = new LinkedHashMap<>();
		for (int i = 0; i < tokens.size(); i++) {
			String tok = tokens.get(i);
			if (knownFlags.contains(tok) && i + 1 < tokens.size()) {
				fittedParams.put(tok, tokens.get(++i));
			}
		}
		logger.info("llama-fit-param拟合结果: {}", fittedParams);
		return fittedParams;
	}
	
	/**
	 * 	用户估算显存。
	 * @param llamaBinPath
	 * @param modelId
	 * @param enableVision
	 * @param cmd
	 * @return Map containing "output" (stdout) and "error" (stderr)
	 */
	public Map<String, String> handleFitParam(String llamaBinPath, String modelId, boolean enableVision, List<String> cmd) {
		Map<String, String> resultMap = new HashMap<>();
		String[] keysParam = {"--ctx-size", "--flash-attn", "--batch-size", "--ubatch-size", "--parallel", "--cache-type-k", "--cache-type-v", "--device", "--main-gpu", "--swa-full", "--split-mode", "--fit", "--spec-draft-type-k", "--spec-draft-type-v"};
		Map<String, String> cmdMap = new HashMap<>();
		for(int i = 0; i < cmd.size(); i++) {
			String param = cmd.get(i);
			if(param.startsWith("--") && i + 1 < cmd.size()) {
				// 如果当前参数的下一个不是参数名，而是值
				if(!cmd.get(i + 1).startsWith("--")) {
					cmdMap.put(param, cmd.get(i + 1));
					i += 1;
				}else {
					cmdMap.put(param, param);
				}
			}
		}
		GGUFModel model = this.findModelById(modelId);
		// 克隆体用源模型 GGUF 路径做显存估算
		if (model == null) {
			String sourceModelId = this.getSourceModelId(modelId);
			if (sourceModelId != null) {
				model = this.findModelById(sourceModelId);
			}
		}
		if(model == null) {
			resultMap.put("output", "");
			resultMap.put("error", "Model not found: " + modelId);
			return resultMap;
		}

		String executableName = "llama-fit-params";
		// 拼接完整命令路径
		String command = ParamTool.quoteIfNeeded(llamaBinPath.trim() + File.separator + executableName);
		command += " --model " + ParamTool.quoteIfNeeded(model.getPrimaryModel().getFilePath());
		//command += " -lv 4";
		command += " -fitp on";
		
		// 仅针对--main-gpu做特殊处理
		if(cmdMap.containsKey("--main-gpu")) {
			// 如果是默认值-1，要改成0
			if("-1".equals(cmdMap.get("--main-gpu"))) {
				cmdMap.put("--main-gpu", "0");
			}
		}

		for(String key : keysParam) {
			// 如果有这个参数
			if(cmdMap.containsKey(key)) {
				String value = cmdMap.get(key);
				if(key.equals(value)) {
					command += " " + key + " ";
				}else {
					command += " " + key + " " + value;
				}
			}
		}
		// 这部分代码是错误的，但是还是留在这里吧。
//		// 如果启用视觉模块
//		if(enableVision) {
//			command += " --mmproj ";
//			String mmprojFile = model.getPath() + "/" + model.getMmproj().getFileName();
//			command += ParamTool.quoteIfNeeded(mmprojFile);
//		}
		logger.info("执行llama-fit-param命令：{}", command);
		// 执行命令
		CommandLineRunner.CommandResult result = CommandLineRunner.execute(command, 30);
		resultMap.put("output", result.getOutput() != null ? result.getOutput() : "");
		resultMap.put("error", result.getError() != null ? result.getError() : "");
		return resultMap;
	}

	/**
	 * 检查系统硬件资源是否足以承载指定模型。
	 *
	 * @param modelId      模型 ID
	 * @param launchConfig 启动配置 bundle（含 selectedConfig, configs 等）
	 * @return true = 资源充足，false = 资源不足
	 */
	@SuppressWarnings("unchecked")
	public boolean canFitModelInMemory(String modelId, Map<String, Object> launchConfig) {
		try {
			logger.info("[自动加载] 开始硬件资源检查: modelId={}", modelId);

			// 1. 获取系统内存信息
			GPUInfoHelper helper = GPUInfoHelper.getInstance();
			if (helper.init() != null) {
				logger.info("[自动加载] gpu-info 初始化失败，拒绝加载: modelId={}", modelId);
				return false;
			}

			Map<String, Long> memInfo = helper.getMemoryInfo();
			if (memInfo == null) {
				logger.info("[自动加载] 获取内存信息失败，拒绝加载: modelId={}", modelId);
				return false;
			}

			long availableRam = memInfo.get("availableRam");
			long availableVram = memInfo.get("availableVram");

			// 2. 从 launchConfig bundle 中提取 selectedConfig 对应的配置项
			String selectedConfigName = (String) launchConfig.get("selectedConfig");
			if (selectedConfigName == null || selectedConfigName.trim().isEmpty()) return false;
			Map<String, Object> configs = (Map<String, Object>) launchConfig.get("configs");
			if (configs == null) return false;
			Map<String, Object> selectedConfig = (Map<String, Object>) configs.get(selectedConfigName);
			if (selectedConfig == null) return false;

			// 3. 提取参数
			String llamaBinPath = (String) selectedConfig.get("llamaBinPathSelect");
			if (llamaBinPath == null || llamaBinPath.trim().isEmpty()) {
				llamaBinPath = (String) selectedConfig.get("llamaBinPath");
			}
			if (llamaBinPath == null || llamaBinPath.trim().isEmpty()) return false;

			String cmd = (String) selectedConfig.getOrDefault("cmd", "");
			String extraParams = (String) selectedConfig.getOrDefault("extraParams", "");
			Object evObj = selectedConfig.get("enableVision");
			boolean enableVision = evObj instanceof Boolean ? (Boolean) evObj : true;

			List<String> cmdList = ParamTool.splitCmdArgs((cmd != null ? cmd : "") + " " + (extraParams != null ? extraParams : ""));
			Map<String, String> result = handleFitParam(llamaBinPath, modelId, enableVision, cmdList);

			String output = result.get("output");
			if (output == null || output.trim().isEmpty()) {
				logger.info("[自动加载] llama-fit-params 输出为空，拒绝加载: modelId={}", modelId);
				return false;
			}

			// 4. 解析显存估算
			long totalVram = parseVramFromFitOutput(output);
			if (totalVram <= 0) {
				logger.info("[自动加载] llama-fit-params 解析失败，拒绝加载: modelId={}", modelId);
				return false;
			}
			long estimatedVramBytes = totalVram * 1024 * 1024;  // MiB -> bytes
			logger.info("[自动加载] 显存估算: modelId={}, estimatedVram={} MiB ({} GiB)",
				modelId, totalVram, String.format("%.2f", estimatedVramBytes / 1024.0 / 1024.0 / 1024.0));

			// 5. 判断 --split-mode
			String splitMode = getSplitMode(cmd, extraParams);
			boolean isTensorParallel = "tensor".equalsIgnoreCase(splitMode);
			logger.info("[自动加载] splitMode={}, isTensorParallel={}", splitMode, isTensorParallel);

			// 6. 比较
			long requiredBytes = (long) (estimatedVramBytes * 1.1);
			if (isTensorParallel) {
				boolean ok = availableVram >= requiredBytes;
				logger.info("[自动加载] Tensor并行模式: availableVram={} GiB, required={} GiB, result={}",
					Math.round(availableVram / 1024.0 / 1024.0 / 1024.0 * 100.0) / 100.0,
					Math.round(requiredBytes / 1024.0 / 1024.0 / 1024.0 * 100.0) / 100.0,
					ok ? "PASS" : "FAIL");
				return ok;
			} else {
				long totalAvailable = availableRam + availableVram;
				boolean ok = totalAvailable >= requiredBytes;
				logger.info("[自动加载] 非Tensor模式: totalAvailable={} GiB, required={} GiB, result={}",
					Math.round(totalAvailable / 1024.0 / 1024.0 / 1024.0 * 100.0) / 100.0,
					Math.round(requiredBytes / 1024.0 / 1024.0 / 1024.0 * 100.0) / 100.0,
					ok ? "PASS" : "FAIL");
				return ok;
			}
		} catch (Exception e) {
			logger.warn("[自动加载] 硬件检查异常: modelId={}, error={}", modelId, e.getMessage());
			return false;
		}
	}

	private long parseVramFromFitOutput(String output) {
		Pattern devicePattern = Pattern.compile("(\\S+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)");
		Matcher matcher = devicePattern.matcher(output);
		long total = 0;
		while (matcher.find()) {
			String name = matcher.group(1);
			if ("estimated".equalsIgnoreCase(name) || "MiB".equalsIgnoreCase(name)) continue;
			total += Long.parseLong(matcher.group(2))
				+ Long.parseLong(matcher.group(3))
				+ Long.parseLong(matcher.group(4));
		}
		return total;
	}

	private String getSplitMode(String cmd, String extraParams) {
		String combined = (cmd != null ? cmd : "") + " " + (extraParams != null ? extraParams : "");
		List<String> args = ParamTool.splitCmdArgs(combined);
		for (int i = 0; i < args.size() - 1; i++) {
			if ("--split-mode".equals(args.get(i))) {
				return args.get(i + 1);
			}
		}
		return null;
	}

	/**
	 * 从保存的启动配置中自动加载模型，阻塞等待直到加载完成或超时。
	 *
	 * @param modelId   模型 ID
	 * @param timeoutMs 最大等待毫秒数
	 * @return null 表示成功，非 null 为错误信息
	 */
	@SuppressWarnings("unchecked")
	public String autoLoadModelFromConfig(String modelId, long timeoutMs) {
		// 1. 检查是否已加载
		if (this.getLoadedProcesses().containsKey(modelId)) {
			logger.info("[自动加载] 模型已加载，跳过: modelId={}", modelId);
			return null;
		}

		// 2. 检查模型是否存在（克隆体不在磁盘上，回退到源模型查找）
		GGUFModel model = this.findModelById(modelId);
		String sourceModelId = null;
		if (model == null) {
			sourceModelId = this.getSourceModelId(modelId);
			if (sourceModelId != null) {
				model = this.findModelById(sourceModelId);
			}
		}
		if (model == null) {
			return "Model not found in model list";
		}

		// 3. 读取启动配置
		Map<String, Object> bundle = this.configManager.getModelLaunchConfigBundle(modelId);
		if (bundle == null) {
			return "No launch configuration found for: " + modelId;
		}

		// 4. 提取 selectedConfig 对应的配置项
		String selectedConfigName = (String) bundle.get("selectedConfig");
		if (selectedConfigName == null || selectedConfigName.trim().isEmpty()) {
			return "No selectedConfig found for: " + modelId;
		}
		Map<String, Object> configs = (Map<String, Object>) bundle.get("configs");
		if (configs == null) {
			return "No configs found for: " + modelId;
		}
		Map<String, Object> selectedConfig = (Map<String, Object>) configs.get(selectedConfigName);
		if (selectedConfig == null) {
			return "Selected config '" + selectedConfigName + "' not found for: " + modelId;
		}

		// 5. 提取参数
		String llamaBinPath = (String) selectedConfig.get("llamaBinPathSelect");
		if (llamaBinPath == null || llamaBinPath.trim().isEmpty()) {
			llamaBinPath = (String) selectedConfig.get("llamaBinPath");
		}
		if (llamaBinPath == null || llamaBinPath.trim().isEmpty()) {
			return "llamaBinPath not configured for: " + modelId;
		}

		String cmd = (String) selectedConfig.getOrDefault("cmd", "");
		String extraParams = (String) selectedConfig.getOrDefault("extraParams", "");
		List<String> device = (List<String>) selectedConfig.get("device");
		Object mgObj = selectedConfig.get("mg");
		Integer mg = (mgObj instanceof Number) ? ((Number) mgObj).intValue() : null;
		Object evObj = selectedConfig.get("enableVision");
		boolean enableVision = evObj instanceof Boolean ? (Boolean) evObj : true;

		// 6. 硬件资源检查（克隆体用源模型 id 查找 GGUF，供 llama-fit-params 定位文件）
		String fitLookupId = sourceModelId != null ? sourceModelId : modelId;
		if (!this.canFitModelInMemory(fitLookupId, bundle)) {
			return "Insufficient memory to load model: " + modelId;
		}

		// 7. 获取 chat template 路径（克隆体从源模型查找）
		String chatTemplateLookupId = sourceModelId != null ? sourceModelId : modelId;
		String chatTemplateFilePath = ChatTemplateFileTool.getChatTemplateCacheFilePathIfExists(chatTemplateLookupId);

		// 8. 提交加载任务（如果尚未在加载中）
		if (!this.isLoading(modelId)) {
			String mode = ParamTool.asString(selectedConfig.getOrDefault("mode", selectedConfig.getOrDefault("paramMode", "form")));
			boolean submitted = this.loadModelAsyncFromCmd(modelId, llamaBinPath, device, mg, enableVision, cmd, extraParams, chatTemplateFilePath, sourceModelId, mode);
			if (!submitted) {
				// 可能已经被其他请求提交了，检查是否已加载
				if (this.getLoadedProcesses().containsKey(modelId)) {
					return null;
				}
				return "Failed to submit load task for: " + modelId;
			}
		}

		// 9. 轮询等待加载完成
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			if (this.getLoadedProcesses().containsKey(modelId)) {
				logger.info("[自动加载] 加载成功: modelId={}", modelId);
				return null;
			}
			if (!this.isLoading(modelId)) {
				logger.warn("[自动加载] 加载失败: modelId={}", modelId);
				return "Model load failed for: " + modelId;
			}
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return "Model load interrupted for: " + modelId;
			}
		}

		// 10. 超时判断（理论上不会走到这里，因为失败会提前返回）
		if (this.isLoading(modelId)) {
			return "Model load timed out for: " + modelId;
		} else {
			return "Model load failed for: " + modelId;
		}
	}

	/**
	 * 	停止所有模型进程并退出Java进程
	 */
	public void shutdownAll() {
		logger.info("开始停止所有模型进程...");
		Map<String, LlamaCppProcess> processes;
		synchronized (this.processLock) {
			processes = new HashMap<>(this.loadedProcesses);
		}
		for (Map.Entry<String, LlamaCppProcess> entry : processes.entrySet()) {
			String modelId = entry.getKey();
			LlamaCppProcess process = entry.getValue();

			logger.info("正在停止模型进程: {}", modelId);
			boolean stopped = process.stop();
			if (stopped) {
				logger.info("成功停止模型进程: {}", modelId);
			} else {
				logger.info("停止模型进程失败: {}", modelId);
			}
		}

		synchronized (this.processLock) {
			this.loadedProcesses.clear();
			this.modelPorts.clear();
		}
		this.executorService.shutdown();
	}

	/**
	 * 扫描 llamacpp/ 下已安装的 cudart 运行库目录。
	 * 判断标准：目录包含 cublas64_*.dll、cublasLt64_*.dll、cudart64_*.dll 三个文件。
	 * @return 已安装的 cudart 目录名列表
	 */
	public List<String> scanCudartPackages() {
		List<String> result = new ArrayList<>();
		String root = LlamaServer.getDefaultLlamaCppPath();
		Path rootPath = Paths.get(root);
		if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
			return result;
		}
		try (Stream<Path> entries = Files.list(rootPath)) {
			for (Path subDir : entries.toList()) {
				if (!Files.isDirectory(subDir)) {
					continue;
				}
				if (!hasCudartDlls(subDir)) {
					continue;
				}
				result.add(subDir.getFileName().toString());
			}
		} catch (IOException e) {
			logger.warn("扫描 cudart 目录失败: {}", e.getMessage());
		}
		return result;
	}

	/**
	 * 判断目录下是否包含 cudart 运行库所需的三个 DLL 文件。
	 */
	private static boolean hasCudartDlls(Path dir) {
		try (Stream<Path> entries = Files.list(dir)) {
			boolean hasCublas = false;
			boolean hasCublasLt = false;
			boolean hasCudart = false;
			for (Path entry : entries.toList()) {
				if (!Files.isRegularFile(entry)) {
					continue;
				}
				String name = entry.getFileName().toString();
				if (name.toLowerCase().startsWith("cublas64_") && name.toLowerCase().endsWith(".dll")) {
					hasCublas = true;
				}
				if (name.toLowerCase().startsWith("cublaslt64_") && name.toLowerCase().endsWith(".dll")) {
					hasCublasLt = true;
				}
				if (name.toLowerCase().startsWith("cudart64_") && name.toLowerCase().endsWith(".dll")) {
					hasCudart = true;
				}
			}
			return hasCublas && hasCublasLt && hasCudart;
		} catch (IOException e) {
			return false;
		}
	}
	
}
