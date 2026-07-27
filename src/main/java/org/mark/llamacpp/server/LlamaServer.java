package org.mark.llamacpp.server;

import java.awt.Desktop;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.mark.file.downloader.DownloadTaskManager;
import org.mark.llamacpp.server.io.ConsoleBroadcastOutputStream;
import org.mark.llamacpp.server.io.ConsoleBufferLogAppender;
import org.mark.llamacpp.server.mcp.McpClientService;
import org.mark.llamacpp.server.service.AutoLoadPolicyManager;
import org.mark.llamacpp.server.service.LlamaRecordService;
import org.mark.llamacpp.server.service.ModelSamplingService;
import org.mark.llamacpp.gguf.GGUFModel;
import org.mark.llamacpp.server.struct.LlamaCppConfig;
import org.mark.llamacpp.server.struct.LlamaCppDataStruct;
import org.mark.llamacpp.server.struct.ModelPathConfig;
import org.mark.llamacpp.server.struct.ProxyConfigData;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.tools.ParamTool;
import org.mark.llamacpp.server.websocket.WebSocketManager;
import org.mark.test.mcp.DefaultMcpServiceImpl;
import org.mark.llamacpp.win.WindowsTray;
import org.mark.llamacpp.win.AutoStartManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.stream.ChunkedFile;
import io.netty.handler.stream.ChunkedInput;
import io.netty.util.CharsetUtil;


/**
 * 	程序的入口。
 */
public class LlamaServer {
	
	@SuppressWarnings("unchecked")
	public static void main(String[] args) {
		// 先输出一下启动参数助助兴。
		final String javaLibraryPath = System.getProperty("java.library.path");
		logger.info("JVM path: " + javaLibraryPath);
		
		final String javaHome = System.getProperty("java.home");
		
		logger.info("Java home: " + javaHome);
		
		RuntimeMXBean runtimeMxBean = ManagementFactory.getRuntimeMXBean();
        List<String> arguments = runtimeMxBean.getInputArguments();
		for (String arg : arguments) {
			logger.info("JVM argument: " + arg);
		}
		// 记录PID
		LlamaServer.PID = runtimeMxBean.getName().split("@")[0];
		
		logger.info("Process PID: " + LlamaServer.PID);

		// 这里重定向输出流
		try {
			Files.createDirectories(LOG_DIR);
			PrintStream stdout = new PrintStream(
					new ConsoleBroadcastOutputStream(STDOUT_LOGGER::info, StandardCharsets.UTF_8),
					true,
					StandardCharsets.UTF_8.name());
			PrintStream stderr = new PrintStream(
					new ConsoleBroadcastOutputStream(STDERR_LOGGER::error, StandardCharsets.UTF_8),
					true,
					StandardCharsets.UTF_8.name());
			System.setOut(stdout);
			System.setErr(stderr);
		} catch (Exception e) {
			e.printStackTrace();
		}
		ConsoleBufferLogAppender.install();
		preloadConsoleBufferFromAppLog();
		// 执行一次，创建缓存目录。
		LlamaServer.getCachePath();

		// 启动时清空静态文件 gzip 缓存，防止 web 资源更新后仍使用旧压缩文件
		org.mark.llamacpp.server.util.StaticFileGzipCache.clearCache();

		// 加载application.json配置文件
		logger.info("正在加载application.json配置...");
		loadApplicationConfig();

		// 加载代理配置
		try {
			Path proxyConfigFile = getProxyConfigPath();
			ProxyConfigData pCfg = readProxyConfig(proxyConfigFile);
			if (pCfg != null) {
				proxyConfig = pCfg;
				logger.info("代理配置已加载: enabled={}, host={}, port={}", pCfg.isEnabled(), pCfg.getHost(), pCfg.getPort());
			} else {
				logger.info("代理配置为空");
			}
		} catch (Exception e) {
			logger.info("加载代理配置失败，使用默认配置: {}", e.getMessage());
			e.printStackTrace();
		}

		// 初始化配置管理器并加载配置
		logger.info("正在初始化配置管理器...");
		ConfigManager configManager = ConfigManager.getInstance();

		// 预加载启动配置到内存中
		logger.info("正在加载启动配置...");
		configManager.loadAllLaunchConfigs();

		// 初始化LlamaServerManager并预加载模型列表
		logger.info("正在初始化模型管理器...");
		LlamaServerManager serverManager = LlamaServerManager.getInstance();

		// 预加载模型列表，这会同时保存模型信息到配置文件
		logger.info("正在扫描模型目录...");
		serverManager.listModel();

		AutoLoadPolicyManager.getInstance().loadConfig();
		serverManager.startAutoUnloadScheduler();

		ModelSamplingService.getInstance();

		try {
			McpClientService.getInstance().initializeFromRegistry();
		} catch (Exception e) {
			logger.info("MCP初始化失败: {}", e.getMessage());
		}

		logger.info("正在初始化节点管理器...");
		NodeManager.getInstance().initialize();

		logger.info("系统初始化完成，启动Web服务器...");
		// 这部分处理退出后要执行的代码，前提是正常退出。
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			logger.info("收到关闭信号，正在清理所有资源...");
			try {
				NettyWebServer.stop();
			} catch (Exception e) {
				logger.error("关闭Web服务通道失败", e);
			}
			try {
				LlamaServer.stopMcpServerListener();
			} catch (Exception e) {
				logger.error("停止MCP服务失败", e);
			}
			try {
				NodeManager.getInstance().shutdown();
			} catch (Exception e) {
				logger.error("关闭节点管理器失败", e);
			}
			try {
				LlamaServerManager.getInstance().shutdownAll();
			} catch (Exception e) {
				logger.error("停止所有模型失败", e);
			}
			try {
				DownloadTaskManager.getInstance().close();
			} catch (Exception e) {
				logger.error("关闭下载任务管理器失败", e);
			}
			try {
				LlamaRecordService.getInstance().shutdown();
			} catch (Exception e) {
				logger.error("关闭请求记录服务失败", e);
			}
			try {
				NettySharedGroups.shutdownAll();
			} catch (Exception e) {
				logger.error("关闭共享Netty线程组失败", e);
			}
			logger.info("清理完成，进程退出");
			
            // 以前不知道log4j也要执行shutdown
            try {
                LogManager.shutdown();
            }catch (Exception e) {
                e.printStackTrace();
            }
			
		}, "shutdown-hook"));

		Thread t1 = new Thread(() -> {
			NettyWebServer webServer = new NettyWebServer(webPort, httpsEnabled, httpsCertPath, httpsPassword);
			webServer.start();
		});
		t1.start();

		if (mcpServerEnabled) {
			try {
				startMcpServerListener();
			} catch (Exception e) {
				logger.info("启动MCP服务失败: {}", e.getMessage());
			}
		}
		
		// 初始化一下记录服务
		LlamaRecordService.getInstance();

		// 尝试创建系统托盘
		createWindowsSystemTray();
		
		// 检查命令行参数，如果提供了模型名称，则启动该模型（这部分比较奇葩 & 危险，慎用）
		if (args != null && args.length > 0) {
			String modelName = args[0];
			logger.info("检测到命令行参数，尝试启动模型：{}", modelName);

			// 查找模型
			GGUFModel model = serverManager.findModelById(modelName);
			if (model == null) {
				logger.error("错误：未找到名为 '{}' 的模型。请使用 /api/models 接口查看可用的模型列表。", modelName);
				return;
			}
			// 获取启动配置
			Map<String, Object> launchConfig = configManager.getModelLaunchConfigBundle(modelName);
			if (launchConfig == null || launchConfig.isEmpty()) {
				logger.error("错误：模型 '{}' 没有可用的启动配置。请先配置启动参数。", modelName);
				return;
			}
			// 解析嵌套配置结构：configs -> selectedConfig
			Map<String, Object> actualConfig = null;
			Object configsObj = launchConfig.get("configs");
			if (configsObj instanceof Map) {
				Map<String, Object> configs = (Map<String, Object>) configsObj;
				String selectedConfig = (String) launchConfig.getOrDefault("selectedConfig", "默认配置");
				actualConfig = (Map<String, Object>) configs.get(selectedConfig);
			}

			if (actualConfig == null || actualConfig.isEmpty()) {
				logger.error("错误：模型 '{}' 没有可用的启动配置。请先配置启动参数。", modelName);
			} else {
				// 提取启动参数
				String llamaBinPath = (String) actualConfig.getOrDefault("llamaBinPath", "");
				Object deviceObj = actualConfig.getOrDefault("device", new ArrayList<String>());
				List<String> device = (deviceObj instanceof List) ? (List<String>) deviceObj : new ArrayList<String>();
				Integer mg = null;
				Object mgObj = actualConfig.get("mg");
				if (mgObj instanceof Number) {
					mg = ((Number) mgObj).intValue();
				}
				boolean enableVision = Boolean.parseBoolean(String.valueOf(actualConfig.getOrDefault("enableVision", false)));
				String cmd = (String) actualConfig.getOrDefault("cmd", "");
				String extraParams = (String) actualConfig.getOrDefault("extraParams", "");
				String envVars = (String) actualConfig.getOrDefault("envVars", "");
				String chatTemplateFilePath = (String) actualConfig.getOrDefault("chatTemplateFile", "");
				String mode = ParamTool.asString(actualConfig.getOrDefault("mode", actualConfig.getOrDefault("paramMode", "form")));

				if (llamaBinPath.isEmpty()) {
					logger.error("错误：模型 '{}' 的启动配置中缺少 llamaBinPath 参数。", modelName);
				} else {
					// 启动模型
					boolean started = serverManager.loadModelAsyncFromCmd(modelName, llamaBinPath, device, mg, enableVision, cmd, extraParams, envVars, chatTemplateFilePath, null, mode);
					if (started) {
						logger.info("模型启动请求已提交");
					} else {
						logger.error("启动模型 '{}' 失败，请查看日志获取详细信息。", modelName);
					}
				}
			}
		}

		// 阻塞主线程，等待主服务线程结束
		try {
			t1.join();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
	
	/**
	 * 	
	 * @return
	 */
	public static String getTag() {
		return BuildInfo.getTag();
	}
	
	/**
	 * 	
	 * @return
	 */
	public static String getVersion() {
		return BuildInfo.getVersion();
	}
	
	/**
	 * 	
	 * @return
	 */
   public static String getCreatedTime() {
		return BuildInfo.getCreatedTime();
	}

	/**
	 *
	 * @return
	 */
	public static String getPID() {
		return PID;
	}
	
	
	private static final Logger logger = LoggerFactory.getLogger(LlamaServer.class);
	private static final Logger STDOUT_LOGGER = LoggerFactory.getLogger("STDOUT");
	private static final Logger STDERR_LOGGER = LoggerFactory.getLogger("STDERR");
	
	/**
	 * 	默认端口：OpenAI + 程序主要业务
	 */
	private static final int DEFAULT_WEB_PORT = 8080;
	
	private static final int DEFAULT_MCP_SERVER_PORT = 8075;

	/**
	 * 默认下载目录
	 */
	private static final String DEFAULT_DOWNLOAD_DIRECTORY = Paths.get(System.getProperty("user.dir"), "models").toString();
	
	/**
	 * 	默认模型目录
	 */
	private static final String DEFAULT_MODELS_DIRECTORY = Paths.get(System.getProperty("user.dir"), "models").toString();
	
	/**
	 * 	默认llama.cpp目录
	 */
	private static final String DEFAULT_LLAMACPP_DIRECTORY = Paths.get(System.getProperty("user.dir"), "llamacpp").toString();
	
	
	private static final Path LOG_DIR = Paths.get("logs");
	private static final Path APPLICATION_LOG_PATH = LOG_DIR.resolve("app.log");
	private static final int CONSOLE_BUFFER_MAX_BYTES = 256 * 1024;
	private static final Object CONSOLE_BUFFER_LOCK = new Object();
	private static final StringBuilder CONSOLE_BUFFER = new StringBuilder();
	private static final DateTimeFormatter CONSOLE_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");


	
	//##############################################################################################################################

	private static int webPort = DEFAULT_WEB_PORT;
	
	private static String downloadDirectory = DEFAULT_DOWNLOAD_DIRECTORY;

	private static final Object APPLICATION_CONFIG_LOCK = new Object();

	private static final Object MCP_SERVER_LOCK = new Object();
	
	private static volatile boolean apiKeyValidationEnabled = false;
	
	private static volatile String apiKey = "";

	private static volatile boolean mcpServerEnabled = false;

	private static volatile DefaultMcpServiceImpl mcpServerService;

	private static volatile boolean httpsEnabled = false;
	private static volatile String httpsCertPath = "ssl/keystore.p12";
	private static volatile String httpsPassword = "changeit";
	private static final Object RESTART_LOCK = new Object();

	private static volatile String nodeRole = null;

	//##############################################################################################################################
	
	public static final String SLOTS_SAVE_KEYWORD = "~SLOTSAVE";

	public static final String SLOTS_LOAD_KEYWORD = "~SLOTLOAD";

	public static final String HELP_KEYWORD = "~HELP";
    
    //##############################################################################################################################
    
    
    private static final Gson GSON = JsonUtil.gson();
    
    public static final PrintStream out = System.out;
    
    public static final PrintStream err = System.err;
    
    public static String PID = "0";

    //##############################################################################################################################
    
    /**
     * 	日志相关：打印请求的URL
     */
    public static boolean logRequestUrl = false;
    
    /**
     * 	日志相关：打印请求的请求头。调试用
     */
    public static boolean logRequestHeader = false;
    
    /**
     * 	日志相关：打印请求的请求体。调试用
     */
    public static boolean logRequestBody = false;
    
    /**
     * 日志相关：将请求体（注入参数后）写入本地文件，每个请求独立文件，用于调试。
     */
    public static boolean logRequestBodyToFile = false;
    
    
    // 一些默认的目录，必须创建
    static {
    	// 默认的模型目录
    	try {
    		String currentDir = System.getProperty("user.dir");
        	Path configDir = Paths.get(currentDir, "models");
    		if (!Files.exists(configDir)) {
    			Files.createDirectories(configDir);
    		}	
    	}catch (Exception e) {
    		e.printStackTrace();
		}
    	// 
    	try {
    		String currentDir = System.getProperty("user.dir");
        	Path configDir = Paths.get(currentDir, "llamacpp");
    		if (!Files.exists(configDir)) {
    			Files.createDirectories(configDir);
    		}	
    	}catch (Exception e) {
    		e.printStackTrace();
		}
    }
    
    
    /**
     * 读取application.json配置文件
     */
	private static void loadApplicationConfig() {
		JsonObject root = readApplicationConfig(true);
		if (root == null) {
			return;
		}
		if (root.has("server")) {
			JsonObject server = root.getAsJsonObject("server");
			if (server.has("webPort")) {
				webPort = server.get("webPort").getAsInt();
			}
		}

		if (root.has("download")) {
			JsonObject download = root.getAsJsonObject("download");
			if (download.has("directory")) {
				downloadDirectory = download.get("directory").getAsString();
			}
		}

		if (root.has("security")) {
			JsonObject security = root.getAsJsonObject("security");
			if (security.has("apiKeyEnabled")) {
				apiKeyValidationEnabled = security.get("apiKeyEnabled").getAsBoolean();
			}
			if (security.has("apiKey")) {
				apiKey = security.get("apiKey").getAsString();
			}
		}
		
		if (root.has("compat")) {
			JsonObject compat = root.getAsJsonObject("compat");
			if (compat != null) {
				if (compat.has("mcpServer")) {
					JsonObject mcpServer = compat.getAsJsonObject("mcpServer");
					if (mcpServer != null && mcpServer.has("enabled")) {
						mcpServerEnabled = mcpServer.get("enabled").getAsBoolean();
					}
				}
			}
		}
		
		if (root.has("logging")) {
			JsonObject logging = root.getAsJsonObject("logging");
			if (logging != null) {
				if (logging.has("logRequestUrl")) {
					logRequestUrl = logging.get("logRequestUrl").getAsBoolean();
				}
				if (logging.has("logRequestHeader")) {
					logRequestHeader = logging.get("logRequestHeader").getAsBoolean();
				}
				if (logging.has("logRequestBody")) {
					logRequestBody = logging.get("logRequestBody").getAsBoolean();
				}
				if (logging.has("logRequestBodyToFile")) {
					logRequestBodyToFile = logging.get("logRequestBodyToFile").getAsBoolean();
				}
			}
		}
		
		if (root.has("https")) {
			JsonObject https = root.getAsJsonObject("https");
			if (https != null) {
				if (https.has("enabled")) {
					httpsEnabled = https.get("enabled").getAsBoolean();
				}
				if (https.has("keystorePath")) {
					httpsCertPath = https.get("keystorePath").getAsString();
				} else if (https.has("certPath")) {
					httpsCertPath = https.get("certPath").getAsString();
				}
				if (https.has("keystorePassword")) {
					httpsPassword = https.get("keystorePassword").getAsString();
				} else if (https.has("password")) {
					httpsPassword = https.get("password").getAsString();
				}
			}
		}

		if (root.has("nodeRole")) {
			nodeRole = root.get("nodeRole").getAsString();
		}
	}
    
    /**
     * 保存配置到application.json文件
     */
	public static void saveApplicationConfig() {
		synchronized (APPLICATION_CONFIG_LOCK) {
			try {
				JsonObject root = new JsonObject();

				if (nodeRole != null) {
					root.addProperty("nodeRole", nodeRole);
				}

				JsonObject server = new JsonObject();
				server.addProperty("webPort", webPort);
				root.add("server", server);
	
				JsonObject download = new JsonObject();
				download.addProperty("directory", downloadDirectory);
				root.add("download", download);
				
				JsonObject security = new JsonObject();
				security.addProperty("apiKeyEnabled", apiKeyValidationEnabled);
				security.addProperty("apiKey", apiKey == null ? "" : apiKey);
				root.add("security", security);
				
				JsonObject compat = new JsonObject();
				JsonObject mcpServer = new JsonObject();
				mcpServer.addProperty("enabled", mcpServerEnabled);
				compat.add("mcpServer", mcpServer);
				
				root.add("compat", compat);
				
				JsonObject logging = new JsonObject();
				logging.addProperty("logRequestUrl", logRequestUrl);
				logging.addProperty("logRequestHeader", logRequestHeader);
				logging.addProperty("logRequestBody", logRequestBody);
				logging.addProperty("logRequestBodyToFile", logRequestBodyToFile);
				root.add("logging", logging);
				
				JsonObject https = new JsonObject();
				https.addProperty("enabled", httpsEnabled);
				https.addProperty("keystorePath", httpsCertPath);
				https.addProperty("keystorePassword", httpsPassword);
				root.add("https", https);
	
				String json = GSON.toJson(root);
	
				Path configPath = Paths.get("config/application.json");
				
				// 确保config目录存在
				if (!Files.exists(configPath.getParent())) {
					Files.createDirectories(configPath.getParent());
				}
				
				Files.write(configPath, json.getBytes(StandardCharsets.UTF_8));
	
				logger.info("配置已保存到文件: {}", configPath.toString());
			} catch (IOException e) {
				logger.info("保存配置文件失败", e);
				throw new RuntimeException("保存配置文件失败: " + e.getMessage(), e);
			}
		}
	}

	public static JsonObject readApplicationConfig() {
		return readApplicationConfig(false);
	}

	private static JsonObject readApplicationConfig(boolean createIfMissing) {
		synchronized (APPLICATION_CONFIG_LOCK) {
			try {
				Path configPath = Paths.get("config/application.json");
				if (!Files.exists(configPath)) {
					if (createIfMissing) {
						logger.info("配置文件不存在，使用默认配置");
						LlamaServer.saveApplicationConfig();
					}
					return new JsonObject();
				}
				String json = new String(Files.readAllBytes(configPath), StandardCharsets.UTF_8);
				JsonObject root = GSON.fromJson(json, com.google.gson.JsonObject.class);
				return root == null ? new JsonObject() : root;
			} catch (Exception e) {
				logger.info("读取配置文件失败: {}", e.getMessage());
				return new JsonObject();
			}
		}
	}

	// ==================== 端口配置的get/set方法 ====================

	public static int getWebPort() {
		return webPort;
	}

	public static void setWebPort(int webPort) {
		if (webPort > 0 && webPort <= 65535) {
			LlamaServer.webPort = webPort;
		}
	}

	public static void updateServerPorts(Integer webPort) {
		synchronized (APPLICATION_CONFIG_LOCK) {
			if (webPort != null && webPort > 0 && webPort <= 65535) {
				LlamaServer.webPort = webPort;
			}
			saveApplicationConfig();
		}
	}

	// ==================== 下载目录配置的get/set方法 ====================

	public static String getDownloadDirectory() {
		return downloadDirectory;
	}

	public static void setDownloadDirectory(String downloadDirectory) {
		synchronized (APPLICATION_CONFIG_LOCK) {
			LlamaServer.downloadDirectory = downloadDirectory == null ? "" : downloadDirectory;
			saveApplicationConfig();
		}
	}

	// ==================== HTTPS ====================

	public static boolean isHttpsEnabled() {
		return httpsEnabled;
	}

	public static String getHttpsCertPath() {
		return httpsCertPath;
	}

	public static String getHttpsPassword() {
		return httpsPassword;
	}

	public static void updateHttpsConfig(Boolean enabled, String certPath, String password) {
		synchronized (APPLICATION_CONFIG_LOCK) {
			if (enabled != null) {
				httpsEnabled = enabled;
			}
			if (certPath != null) {
				httpsCertPath = certPath;
			}
			if (password != null) {
				httpsPassword = password;
			}
			saveApplicationConfig();
		}
	}

	public static boolean isApiKeyValidationEnabled() {
		return apiKeyValidationEnabled;
	}

	public static String getApiKey() {
		return apiKey;
	}

	public static void setApiKeyValidationEnabled(boolean enabled) {
		synchronized (APPLICATION_CONFIG_LOCK) {
			apiKeyValidationEnabled = enabled;
			saveApplicationConfig();
		}
	}

	public static void setApiKey(String apiKeyValue) {
		synchronized (APPLICATION_CONFIG_LOCK) {
			apiKey = apiKeyValue == null ? "" : apiKeyValue;
			saveApplicationConfig();
		}
	}

	public static void updateApiKeyConfig(boolean enabled, String apiKeyValue) {
		synchronized (APPLICATION_CONFIG_LOCK) {
			apiKeyValidationEnabled = enabled;
			apiKey = apiKeyValue == null ? "" : apiKeyValue;
			saveApplicationConfig();
		}
	}

	public static boolean isMcpServerEnabled() {
		return mcpServerEnabled;
	}

	public static boolean isMcpServerRunning() {
		DefaultMcpServiceImpl service = mcpServerService;
		return service != null && service.isRunning();
	}

	public static DefaultMcpServiceImpl getMcpServerService() {
		return mcpServerService;
	}

	public static int getMcpServerPort() {
		return DEFAULT_MCP_SERVER_PORT;
	}

	public static boolean isLogRequestUrlEnabled() {
		return logRequestUrl;
	}

	public static boolean isLogRequestHeaderEnabled() {
		return logRequestHeader;
	}

	public static boolean isLogRequestBodyEnabled() {
		return logRequestBody;
	}

	public static boolean isMasterNode() {
		return nodeRole != null && "master".equalsIgnoreCase(nodeRole);
	}

	public static String getNodeRole() {
		return nodeRole;
	}

	/**
	 * 更新本节点角色并持久化到 application.json。
	 * "master" 为主节点（聚合远程节点）；空串/null/其它值均视为普通节点（slave）。
	 * ⚠ 仅写入配置，运行时不会改变 NodeManager 已启动的健康检查/WebSocket 连接，需重启服务生效。
	 */
	public static void updateNodeRole(String role) {
		synchronized (APPLICATION_CONFIG_LOCK) {
			String normalized = role == null ? "" : role.trim();
			nodeRole = normalized.isEmpty() ? "slave" : normalized;
			saveApplicationConfig();
		}
	}

	public static void updateRequestLogConfig(Boolean urlEnabled, Boolean headerEnabled, Boolean bodyEnabled) {
		synchronized (APPLICATION_CONFIG_LOCK) {
			if (urlEnabled != null) {
				logRequestUrl = urlEnabled.booleanValue();
			}
			if (headerEnabled != null) {
				logRequestHeader = headerEnabled.booleanValue();
			}
			if (bodyEnabled != null) {
				logRequestBody = bodyEnabled.booleanValue();
			}
			saveApplicationConfig();
		}
	}

	public static void setMcpServerEnabled(boolean enabled) throws Exception {
		synchronized (MCP_SERVER_LOCK) {
			if (enabled) {
				startMcpServerListener();
				persistMcpServerEnabled(true);
				return;
			}
			stopMcpServerListener();
			persistMcpServerEnabled(false);
		}
	}

	private static void startMcpServerListener() throws Exception {
		synchronized (MCP_SERVER_LOCK) {
			if (mcpServerService != null && mcpServerService.isRunning()) {
				return;
			}
			DefaultMcpServiceImpl service = createDefaultMcpServer();
			try {
				service.start();
				mcpServerService = service;
			} catch (Exception e) {
				try {
					service.stop();
				} catch (Exception ignore) {
				}
				throw e;
			}
		}
	}

	private static void stopMcpServerListener() {
		synchronized (MCP_SERVER_LOCK) {
			DefaultMcpServiceImpl service = mcpServerService;
			mcpServerService = null;
			if (service != null) {
				service.stop();
			}
		}
	}

	private static void persistMcpServerEnabled(boolean enabled) {
		synchronized (APPLICATION_CONFIG_LOCK) {
			mcpServerEnabled = enabled;
			saveApplicationConfig();
		}
	}

	private static DefaultMcpServiceImpl createDefaultMcpServer() {
		return new DefaultMcpServiceImpl(DEFAULT_MCP_SERVER_PORT);
	}
    
    // ==================== 默认路径的get方法 ====================
    
    public static String getDefaultLlamaCppPath() {
    	return DEFAULT_LLAMACPP_DIRECTORY;
    }
    
    
	public static String getDefaultModelsPath() {
		return DEFAULT_MODELS_DIRECTORY;
	}

    /**
     * 	获取缓存目录的路径。
     * @return
     */
	public static Path getCachePath() {
		try {
			Path currentDir = Paths.get("").toAbsolutePath();
			Path cachePath = currentDir.resolve("cache");

			if (!Files.exists(cachePath)) {
				Files.createDirectories(cachePath);
			}
			return cachePath;
		} catch (Exception e) {
			e.printStackTrace();
			throw new RuntimeException("Failed to create cache directory", e);
		}
	}
    
    
    /**
     * 广播WebSocket消息
     */
    public static Path getApplicationLogPath() {
        return APPLICATION_LOG_PATH;
    }

    public static String getConsoleBufferText() {
        synchronized (CONSOLE_BUFFER_LOCK) {
            return CONSOLE_BUFFER.toString();
        }
    }

    public static String getModelLogText(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return "";
        }
        String safeId = modelId.replaceAll("[^a-zA-Z0-9_\\-.]", "_");
        if (safeId.isBlank() || ".".equals(safeId) || "..".equals(safeId)) {
            return "";
        }
        Path logFile = LOG_DIR.resolve(safeId + ".log").toAbsolutePath().normalize();
        if (!logFile.startsWith(LOG_DIR.toAbsolutePath().normalize())) {
            return "";
        }
        try {
            if (!Files.exists(logFile) || !Files.isRegularFile(logFile)) return "";
            return readTailUtf8(logFile, CONSOLE_BUFFER_MAX_BYTES);
        } catch (Exception e) {
            logger.info("读取模型日志失败: modelId={}, path={}", modelId, logFile, e);
            return "";
        }
    }

    private static void preloadConsoleBufferFromAppLog() {
        try {
            if (!Files.exists(APPLICATION_LOG_PATH) || !Files.isRegularFile(APPLICATION_LOG_PATH)) {
                return;
            }
            String text = readTailUtf8(APPLICATION_LOG_PATH, CONSOLE_BUFFER_MAX_BYTES);
            if (text == null || text.isEmpty()) {
                return;
            }
            synchronized (CONSOLE_BUFFER_LOCK) {
                CONSOLE_BUFFER.setLength(0);
                CONSOLE_BUFFER.append(trimConsoleBufferToMaxBytes(text));
            }
        } catch (Exception ignore) {
        }
    }

    private static void appendConsoleBufferLine(String line) {
        String entry = line == null ? "\n" : line + "\n";
        synchronized (CONSOLE_BUFFER_LOCK) {
            CONSOLE_BUFFER.append(entry);
            String trimmed = trimConsoleBufferToMaxBytes(CONSOLE_BUFFER.toString());
            if (trimmed.length() != CONSOLE_BUFFER.length()) {
                CONSOLE_BUFFER.setLength(0);
                CONSOLE_BUFFER.append(trimmed);
            }
        }
    }

    private static String trimConsoleBufferToMaxBytes(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= CONSOLE_BUFFER_MAX_BYTES) {
            return text;
        }
        int start = bytes.length - CONSOLE_BUFFER_MAX_BYTES;
        while (start < bytes.length && (bytes[start] & 0xC0) == 0x80) {
            start++;
        }
        if (start >= bytes.length) {
            return "";
        }
        return new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
    }

    private static String readTailUtf8(Path path, int maxBytes) throws IOException {
        if (path == null || maxBytes <= 0) {
            return "";
        }
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            long len = raf.length();
            if (len <= 0) {
                return "";
            }
            int toRead = (int) Math.min((long) maxBytes, len);
            long start = len - toRead;
            raf.seek(start);
            byte[] bytes = new byte[toRead];
            int read = raf.read(bytes);
            if (read <= 0) {
                return "";
            }
            int offset = 0;
            while (offset < read && (bytes[offset] & 0xC0) == 0x80) {
                offset++;
            }
            if (offset >= read) {
                return "";
            }
            return new String(bytes, offset, read - offset, StandardCharsets.UTF_8);
        }
    }
    
    public static void broadcastWebSocketMessage(String message) {
        WebSocketManager.getInstance().broadcast(message);
    }
    
    /**
     * 获取当前WebSocket连接数
     */
    public static int getWebSocketConnectionCount() {
        return WebSocketManager.getInstance().getConnectionCount();
    }
    
    /**
     * 发送模型加载事件
     */
    public static void sendModelLoadEvent(String modelId, boolean success, String message) {
        WebSocketManager.getInstance().sendModelLoadEvent(modelId, null, success, message, null);
    }

    public static void sendModelLoadEvent(String modelId, boolean success, String message, Integer port) {
        WebSocketManager.getInstance().sendModelLoadEvent(modelId, null, success, message, port);
    }

    public static void sendModelLoadEvent(String modelId, String sourceModelId, boolean success, String message, Integer port) {
        WebSocketManager.getInstance().sendModelLoadEvent(modelId, sourceModelId, success, message, port, null);
    }

    public static void sendModelLoadEvent(String modelId, String sourceModelId, boolean success, String message, Integer port, Integer slotNum) {
        WebSocketManager.getInstance().sendModelLoadEvent(modelId, sourceModelId, success, message, port, slotNum);
    }

    public static void sendModelLoadStartEvent(String modelId, Integer port, String message) {
        WebSocketManager.getInstance().sendModelLoadStartEvent(modelId, null, port, message);
    }

    public static void sendModelLoadStartEvent(String modelId, String sourceModelId, Integer port, String message) {
        WebSocketManager.getInstance().sendModelLoadStartEvent(modelId, sourceModelId, port, message);
    }
    
    /**
     * 发送模型停止事件
     */
    public static void sendModelStopEvent(String modelId, boolean success, String message) {
        WebSocketManager.getInstance().sendModelStopEvent(modelId, null, success, message);
    }

    public static void sendModelStopEvent(String modelId, String sourceModelId, boolean success, String message) {
        WebSocketManager.getInstance().sendModelStopEvent(modelId, sourceModelId, success, message);
    }
    
    public static void sendConsoleLineEvent(String modelId, String line) {
        String formatted = line;
        // 仅对真实模型日志追加时间戳；system 日志本身已带 Log4j2 时间戳，避免重复
        if (modelId != null && !modelId.isEmpty() && !"system".equals(modelId)) {
            formatted = LocalDateTime.now().format(CONSOLE_TIMESTAMP_FORMATTER) + " - " + line;
        }
        appendConsoleBufferLine(formatted);
        WebSocketManager.getInstance().sendConsoleLineEvent(modelId, formatted);
    }
    
    public static void sendModelSlotsEvent(String modelId, com.google.gson.JsonArray slots) {
        WebSocketManager.getInstance().sendModelSlotsEvent(modelId, slots);
    }
    
    //================================================================================================
    
    
	/**
	 * 保存设置到JSON文件
	 */
    public synchronized static void saveSettingsToFile(List<String> modelPaths) {
        try {
            // 创建设置对象
            Map<String, Object> settings = new HashMap<>();
            settings.put("modelPaths", modelPaths);
            // 兼容旧字段，保留第一个路径
            if (modelPaths != null && !modelPaths.isEmpty()) {
                settings.put("modelPath", modelPaths.get(0));
            }
            
            // 转换为JSON字符串
            String json = GSON.toJson(settings);
            
            // 获取当前工作目录
			String currentDir = System.getProperty("user.dir");
			Path configDir = Paths.get(currentDir, "config");
			
			// 确保config目录存在
			if (!Files.exists(configDir)) {
				Files.createDirectories(configDir);
			}
			
			Path settingsPath = configDir.resolve("settings.json");
			
			// 写入文件
			Files.write(settingsPath, json.getBytes(StandardCharsets.UTF_8));
			
			logger.info("设置已保存到文件: {}", settingsPath.toString());
		} catch (IOException e) {
			logger.info("保存设置到文件失败", e);
			throw new RuntimeException("保存设置到文件失败: " + e.getMessage(), e);
		}
	}
    
    
	public synchronized static Path getLlamaCppConfigPath() throws IOException {
		String currentDir = System.getProperty("user.dir");
		Path configDir = Paths.get(currentDir, "config");
		if (!Files.exists(configDir)) {
			Files.createDirectories(configDir);
		}
		return configDir.resolve("llamacpp.json");
	}
	
	
	public synchronized static LlamaCppConfig readLlamaCppConfig(Path configFile) throws IOException {
		LlamaCppConfig cfg = new LlamaCppConfig();
		if (Files.exists(configFile)) {
			String json = new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8);
			LlamaCppConfig read = GSON.fromJson(json, LlamaCppConfig.class);
			if (read != null && read.getItems() != null) {
				cfg.setItems(read.getItems());
			}
		}
		return cfg;
	}
	

	public synchronized static void writeLlamaCppConfig(Path configFile, LlamaCppConfig cfg) throws IOException {
		String json = GSON.toJson(cfg);
		Files.write(configFile, json.getBytes(StandardCharsets.UTF_8));
		logger.info("llama.cpp配置已保存到文件: {}", configFile.toString());
	}

	public synchronized static Path getModelPathConfigPath() throws IOException {
		String currentDir = System.getProperty("user.dir");
		Path configDir = Paths.get(currentDir, "config");
		if (!Files.exists(configDir)) {
			Files.createDirectories(configDir);
		}
		return configDir.resolve("modelpaths.json");
	}

	public synchronized static ModelPathConfig readModelPathConfig(Path configFile) throws IOException {
		ModelPathConfig cfg = new ModelPathConfig();
		if (Files.exists(configFile)) {
			String json = new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8);
			ModelPathConfig read = GSON.fromJson(json, ModelPathConfig.class);
			if (read != null && read.getItems() != null) {
				cfg.setItems(read.getItems());
			}
		}
		return cfg;
	}

	public synchronized static void writeModelPathConfig(Path configFile, ModelPathConfig cfg) throws IOException {
		String json = GSON.toJson(cfg);
		Files.write(configFile, json.getBytes(StandardCharsets.UTF_8));
		logger.info("模型路径配置已保存到文件: {}", configFile.toString());
	}

	/**
	 * 	读代理配置
	 */
	public synchronized static Path getProxyConfigPath() throws IOException {
		String currentDir = System.getProperty("user.dir");
		Path configDir = Paths.get(currentDir, "config");
		if (!Files.exists(configDir)) {
			Files.createDirectories(configDir);
		}
		return configDir.resolve("proxy.json");
	}

	/**
	 * 	读代理配置
	 */
	public synchronized static ProxyConfigData readProxyConfig(Path configFile) throws IOException {
		ProxyConfigData cfg = new ProxyConfigData();
		if (Files.exists(configFile)) {
			String json = new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8);
			ProxyConfigData read = GSON.fromJson(json, ProxyConfigData.class);
			if (read != null) {
				cfg.setEnabled(read.isEnabled());
				cfg.setHost(read.getHost());
				cfg.setPort(read.getPort());
				cfg.setUsername(read.getUsername());
				cfg.setPassword(read.getPassword());
			}
		}
		return cfg;
	}

	/**
	 * 	写代理配置
	 */
	public synchronized static void writeProxyConfig(Path configFile, ProxyConfigData cfg) throws IOException {
		String json = GSON.toJson(cfg);
		Files.write(configFile, json.getBytes(StandardCharsets.UTF_8));
		logger.info("代理配置已保存到文件: {}", configFile.toString());
	}

	// --- Runtime proxy config cache ---
	private static ProxyConfigData proxyConfig = new ProxyConfigData();

	/**
	 * Get the current proxy configuration (runtime cache).
	 */
	public static ProxyConfigData getProxyConfig() {
		return proxyConfig;
	}

	/**
	 * Set the proxy configuration (runtime cache).
	 */
	public static void setProxyConfig(ProxyConfigData cfg) {
		if (cfg != null) {
			proxyConfig = cfg;
		}
	}

	//================================================================================================
	
	/**
	 * 	扫描默认目录下是否存在llamacpp。
	 * @return
	 */
	public static List<LlamaCppDataStruct> scanLlamaCpp() {
		List<LlamaCppDataStruct> result = new ArrayList<>();
		String root = DEFAULT_LLAMACPP_DIRECTORY;
		// 检查根目录是否存在且为目录
		Path rootPath = Paths.get(root);
		if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
			return result; // 目录不存在或不是目录，直接返回空列表
		}
		try {
			// 遍历根目录下的所有子目录
			Files.list(rootPath).filter(Files::isDirectory) // 只处理子文件夹
					.forEach(subDir -> {
						// 检查子目录中是否包含 llama-server 或 llama-server.exe
						Path serverPathLinux = subDir.resolve("llama-server");
						Path serverPathWin = subDir.resolve("llama-server.exe");
						// 检查 Linux/macOS 版本
						if (Files.exists(serverPathLinux) && Files.isExecutable(serverPathLinux)) {
							result.add(new LlamaCppDataStruct(subDir.getFileName().toString(), subDir.toString(), "https://github.com/ggml-org/llama.cpp"));
							return; // 找到一个即可，跳过Windows检查
						}
						// 检查 Windows 版本
						if (Files.exists(serverPathWin) && Files.isExecutable(serverPathWin)) {
							result.add(new LlamaCppDataStruct(subDir.getFileName().toString(), subDir.toString(), "https://github.com/ggml-org/llama.cpp"));
						}
					});
		} catch (Exception e) {
			e.printStackTrace();
			// 可选：记录日志，如 log.warn("Failed to scan llamaCpp directory: " + root, e);
			// 为保持健壮性，即使出错也不中断，返回已找到的结果
		}
		return result;
	}
	
	
	//================================================================================================
	
	public static void setCorsHeaders(HttpHeaders headers) {
		headers.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		headers.set("Access-Control-Allow-Headers", "Authorization, Content-Type, x-api-key, anthropic-version");
		headers.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS");
		headers.set(HttpHeaderNames.ACCESS_CONTROL_MAX_AGE, "86400");
		// 安全响应头
		headers.set("X-Content-Type-Options", "nosniff");
		headers.set("X-Frame-Options", "SAMEORIGIN");
		headers.set("Referrer-Policy", "no-referrer");
	}
	
	/**
	 * 	发送JSON响应。
	 * @param ctx
	 * @param data
	 */
	public static void sendJsonResponse(ChannelHandlerContext ctx, Object data) {
		sendJsonResponseInternal(ctx, HttpResponseStatus.OK, data);
	}

	private static void sendJsonResponseInternal(ChannelHandlerContext ctx, HttpResponseStatus status, Object data) {
		String json = GSON.toJson(data);
		byte[] content = json.getBytes(CharsetUtil.UTF_8);

		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
		response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
		setCorsHeaders(response.headers());
		response.content().writeBytes(content);

		ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				ctx.close();
			}
		});
	}

	public static void sendExpressJsonResponse(ChannelHandlerContext ctx, HttpResponseStatus status, Object data, boolean allowAllMethods) {
		String json = JsonUtil.toJson(data);
		byte[] content = json.getBytes(CharsetUtil.UTF_8);

		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status == null ? HttpResponseStatus.OK : status);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
		if (allowAllMethods) {
			response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "*");
		}
		response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
		response.headers().set(HttpHeaderNames.DATE, ParamTool.getDate());
		response.headers().set(HttpHeaderNames.ETAG, ParamTool.buildEtag(content));
		response.headers().set("X-Powered-By", "Express");

		response.content().writeBytes(content);

		ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				ctx.close();
			}
		});
	}

	public static void sendExpressRawJsonResponse(ChannelHandlerContext ctx, HttpResponseStatus status, byte[] content, boolean allowAllMethods) {
		byte[] bytes = content == null ? new byte[0] : content;

		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status == null ? HttpResponseStatus.OK : status);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
		if (allowAllMethods) {
			response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "*");
		}
		response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
		response.headers().set(HttpHeaderNames.DATE, ParamTool.getDate());
		response.headers().set(HttpHeaderNames.ETAG, ParamTool.buildEtag(bytes));
		response.headers().set("X-Powered-By", "Express");
		response.content().writeBytes(bytes);

		ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				ctx.close();
			}
		});
	}

	public static void sendJsonErrorResponse(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
		Map<String, Object> payload = new HashMap<>();
		payload.put("status", "error");
		payload.put("message", message == null ? "" : message);
		sendJsonResponseInternal(ctx, status == null ? HttpResponseStatus.INTERNAL_SERVER_ERROR : status, payload);
	}

	/**
	 * 流式发送 JSON 响应：prefix → 文件内容 → suffix。
	 * 用于大文件（如含 base64 图片的会话）的零拷贝传输，避免 OOM。
	 */
	public static void sendStreamedJsonResponse(
			ChannelHandlerContext ctx, String prefix, Path file, String suffix) throws IOException {
		HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
		setCorsHeaders(response.headers());

		ctx.write(response);

		ByteBuf prefixBuf = Unpooled.copiedBuffer(prefix, StandardCharsets.UTF_8);
		ctx.write(new DefaultHttpContent(prefixBuf));

		FileInputStream fis = new FileInputStream(file.toFile());
		long fileLength = file.toFile().length();
		ByteBuf suffixBuf = Unpooled.copiedBuffer(suffix, StandardCharsets.UTF_8);
		ChunkedInput<ByteBuf> combined = new PrefixFileSuffixInput(fis, fileLength, suffixBuf);
		ctx.write(combined);

		ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(future -> ctx.close());
	}

	/**
	 * 将 prefix ByteBuf → 文件流 → suffix ByteBuf 合并为单一 ChunkedInput。
	 * ChunkedWriteHandler 对单一 ChunkedInput 会循环 readChunk 直到返回 null，
	 * 不会中途穿插处理缓冲区中的其他消息，从而避免 suffix 和 LastHttpContent
	 * 在文件传输完成前被提前写入导致响应截断。
	 */
	public static class PrefixFileSuffixInput implements ChunkedInput<ByteBuf> {
		private static final int CHUNK_SIZE = 8192;

		private final InputStream fileStream;
		private final ByteBuf suffixBuf;
		private final long fileLength;
		private final byte[] chunkBuffer = new byte[CHUNK_SIZE];
		private volatile State state = State.FILE;
		private boolean closed;
		private long fileProgress;

		private enum State { FILE, SUFFIX, DONE }

		PrefixFileSuffixInput(InputStream fileStream, long fileLength, ByteBuf suffixBuf) {
			this.fileStream = fileStream;
			this.fileLength = fileLength;
			this.suffixBuf = suffixBuf;
		}

		@Override
		public ByteBuf readChunk(io.netty.buffer.ByteBufAllocator alloc) throws IOException {
			return doReadChunk(alloc);
		}

		@Override
		public ByteBuf readChunk(io.netty.channel.ChannelHandlerContext ctx) throws IOException {
			return doReadChunk(ctx.alloc());
		}

		private ByteBuf doReadChunk(io.netty.buffer.ByteBufAllocator alloc) throws IOException {
			if (state == State.DONE) return null;

			if (state == State.FILE) {
				int len = fileStream.read(chunkBuffer);
				if (len <= 0) {
					fileStream.close();
					state = State.SUFFIX;
					return doReadChunk(alloc);
				}
				fileProgress += len;
				ByteBuf chunk = alloc.buffer(len);
				chunk.writeBytes(chunkBuffer, 0, len);
				return chunk;
			}

			if (state == State.SUFFIX && suffixBuf.readableBytes() > 0) {
				int remaining = suffixBuf.readableBytes();
				if (remaining <= CHUNK_SIZE) {
					ByteBuf chunk = suffixBuf.retainedSlice(suffixBuf.readerIndex(), remaining);
					state = State.DONE;
					return chunk;
				} else {
					ByteBuf chunk = suffixBuf.retainedSlice(suffixBuf.readerIndex(), CHUNK_SIZE);
					suffixBuf.skipBytes(CHUNK_SIZE);
					return chunk;
				}
			}

			state = State.DONE;
			return null;
		}

		@Override
		public boolean isEndOfInput() throws IOException {
			return state == State.DONE;
		}

		@Override
		public void close() throws IOException {
			if (closed) return;
			closed = true;
			try {
				fileStream.close();
			} catch (IOException e) {
				// ignore
			}
			suffixBuf.release();
		}

		public boolean isClosed() {
			return closed;
		}

		@Override
		public long progress() {
			if (state == State.FILE) return fileProgress;
			if (state == State.DONE) return fileLength + suffixBuf.capacity();
			return fileLength;
		}

		@Override
		public long length() {
			return fileLength + suffixBuf.readableBytes();
		}
	}
	
	
	
	/**
	 * 发送文件内容（原有方法，保留用于非API下载）
	 */
	public static void sendFile(ChannelHandlerContext ctx, File file) throws IOException {
		RandomAccessFile raf = new RandomAccessFile(file, "r");
		long fileLength = raf.length();

		HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, fileLength);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, LlamaServer.getContentType(file.getName()));
		setCorsHeaders(response.headers());

		// 设置缓存头
		response.headers().set(HttpHeaderNames.CACHE_CONTROL, "max-age=3600");

		ctx.write(response);

		// 使用ChunkedFile传输文件内容
		ctx.write(new ChunkedFile(raf, 0, fileLength, 8192), ctx.newProgressivePromise());

		ChannelFuture lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);

		// 传输完成后关闭连接
		lastContentFuture.addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				ctx.close();
			}
		});
	}

    /**
     * 发送静态文件（基于文件属性的 ETag + ChunkedFile 磁盘直读，零堆内存缓存）
     */
    public static void sendStaticFile(ChannelHandlerContext ctx, File file, FullHttpRequest request) throws IOException {
        String requestPath = request.uri();
        int queryIndex = requestPath.indexOf('?');
        if (queryIndex >= 0) {
            requestPath = requestPath.substring(0, queryIndex);
        }

        // 如果客户端支持 gzip，尝试使用预压缩的缓存文件
        File fileToServe = file;
        String contentEncoding = null;
        String acceptEncoding = request.headers().get(HttpHeaderNames.ACCEPT_ENCODING);
        if (acceptEncoding != null && acceptEncoding.contains("gzip")) {
            File gzFile = org.mark.llamacpp.server.util.StaticFileGzipCache.getGzipFile(file, requestPath);
            if (gzFile != null) {
                fileToServe = gzFile;
                contentEncoding = "gzip";
            }
        }

        long lastModified = fileToServe.lastModified();
        long fileLength = fileToServe.length();
        String etag = "\"" + Long.toHexString(lastModified) + "-" + Long.toHexString(fileLength) + "\"";

        String ifNoneMatch = request.headers().get(HttpHeaderNames.IF_NONE_MATCH);
        if (ifNoneMatch != null && ifNoneMatch.equals(etag)) {
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_MODIFIED);
            response.headers().set(HttpHeaderNames.ETAG, etag);
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
            setCorsHeaders(response.headers());
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
            return;
        }

        RandomAccessFile raf = new RandomAccessFile(fileToServe, "r");
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, fileLength);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, getContentType(file.getName()));
        response.headers().set(HttpHeaderNames.ETAG, etag);
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
        if (contentEncoding != null) {
            response.headers().set(HttpHeaderNames.CONTENT_ENCODING, contentEncoding);
        }
        setCorsHeaders(response.headers());

        ctx.write(response);
        ctx.write(new ChunkedFile(raf, 0, fileLength, 8192), ctx.newProgressivePromise());
        ChannelFuture lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
        lastContentFuture.addListener(ChannelFutureListener.CLOSE);
    }

	/**
	 *
	 * @param ctx
	 * @param status
	 * @param message
	 */
    public static void sendErrorResponse(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);

        byte[] content = message.getBytes(CharsetUtil.UTF_8);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
        setCorsHeaders(response.headers());
        response.content().writeBytes(content);

        ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) {
                ctx.close();
            }
        });
    }
	
    /**
     * 	
     * @param ctx
     * @param text
     */
    public static  void sendTextResponse(ChannelHandlerContext ctx, String text) {
		byte[] content = text.getBytes(StandardCharsets.UTF_8);
		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
		setCorsHeaders(response.headers());
		response.content().writeBytes(content);
		ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				ctx.close();
			}
		});
	}
    
    
    public static void sendCorsResponse(ChannelHandlerContext ctx) {
		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);

		setCorsHeaders(response.headers());
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);

		ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				ctx.close();
			}
		});
    }
	
	/**
	 * 	判断文件类型
	 * @param fileName
	 * @return
	 */
	public static String getContentType(String fileName) {
		String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
		switch (extension) {
		case "html":
		case "htm":
			return "text/html; charset=UTF-8";
		case "css":
			return "text/css";
		case "js":
			return "application/javascript";
		case "json":
			return "application/json";
		case "xml":
			return "application/xml";
		case "pdf":
			return "application/pdf";
		case "jpg":
		case "jpeg":
			return "image/jpeg";
		case "png":
			return "image/png";
		case "gif":
			return "image/gif";
		case "txt":
			return "text/plain; charset=UTF-8";
		case "ico":
			return "image/x-icon";
		case "svg":
			return "image/svg+xml";
		case "webmanifest":
			return "application/manifest+json";
		default:
			return "application/octet-stream";
		}
	}
	
	/**
	 * 	Windows 重启功能：先停止 Web 服务（释放端口 + 断开远程节点），
	 * 	再停止全部模型，最后通过 ProcessBuilder 自举一个新 JVM 进程
	 * 	并退出当前 JVM。使用 RESTART_LOCK 防止并发调用。
	 */
	public static void restartApplication() {
		synchronized (RESTART_LOCK) {
			logger.info("准备重启程序...");
			try {
				// 1. 关闭所有 Web 服务端口
				NettyWebServer.stop();
				LlamaServer.stopMcpServerListener();
				// 断开远程节点连接
				NodeManager.getInstance().shutdown();

				// 2. 停止所有模型（阻塞等待）
				LlamaServerManager.getInstance().shutdownAll();

				// 3. 拉起新进程
				RuntimeMXBean mx = ManagementFactory.getRuntimeMXBean();
				List<String> jvmArgs = mx.getInputArguments();
				String classpath = System.getProperty("java.class.path");
				boolean isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");
				String javaBin = System.getProperty("java.home") + File.separator + "bin"
						+ File.separator + (isWindows ? "java.exe" : "java");

				List<String> cmd = new ArrayList<>();
				cmd.add(javaBin);
				cmd.addAll(jvmArgs);
				cmd.add("-classpath");
				cmd.add(classpath);
				cmd.add("org.mark.llamacpp.server.LlamaServer");

				new ProcessBuilder(cmd).inheritIO().start();
				logger.info("重启进程已启动");
			} catch (Exception e) {
				// 失败了就别重启了
				logger.error("重启失败", e);
				return;
			}
			System.exit(0);
		}
	}

	/**
	 * 	创建系统托盘。
	 */
	private static void createWindowsSystemTray() {
		// 判断操作系统是否为Windows，如果不是则直接返回
		String osName = System.getProperty("os.name");
		if (!osName.toLowerCase().startsWith("windows")) {
			return;
		}

		// 根据系统语言选择托盘菜单文本
		boolean isChinese = "zh".equals(Locale.getDefault().getLanguage());
		String btnOpen = isChinese ? "打开首页" : "Open Homepage";
		String btnRestart = isChinese ? "重启程序" : "Restart";
		String btnAutoStart = isChinese ? "开机自启" : "Auto Start";
		String btnExit = isChinese ? "退出程序" : "Exit";
		String notifyTitle = isChinese ? "启动成功" : "Started";
		String notifyMsg = isChinese ? "llama.cpp-hub 已在后台运行" : "llama.cpp-hub is running in background";

		try {
			WindowsTray tray = WindowsTray.getInstance();
			String host = "http" + (httpsEnabled ? "s" : "") + "://127.0.0.1:" + webPort;
			tray.addButton(btnOpen, () -> {
				try {
					Desktop.getDesktop().browse(new URI(host));
				} catch (Exception e) {
					e.printStackTrace();
				}
			});
			tray.addButton(btnRestart, () -> {
				LlamaServer.restartApplication();
			});
			String autoStartId = "autostart-toggle";
			tray.addCheckBoxButton(autoStartId, btnAutoStart, AutoStartManager.isAutoStartEnabled(), () -> {
				boolean current = AutoStartManager.isAutoStartEnabled();
				new Thread(() -> {
					boolean success = current ? AutoStartManager.disableAutoStart() : AutoStartManager.enableAutoStart();
					javax.swing.SwingUtilities.invokeLater(() -> {
						if (!success) {
							tray.setCheckBoxSelected(autoStartId, current);
							javax.swing.JOptionPane.showMessageDialog(null,
								current ? (isChinese ? "关闭开机自启失败" : "Failed to disable auto start")
										: (isChinese ? "设置开机自启失败，请确认 llama.cpp-hub.exe 存在"
													: "Failed to enable auto start. Ensure llama.cpp-hub.exe exists."),
								"llama.cpp-hub", javax.swing.JOptionPane.ERROR_MESSAGE);
						} else {
							javax.swing.JOptionPane.showMessageDialog(null,
								current ? (isChinese ? "已关闭开机自启" : "Auto start disabled")
										: (isChinese ? "已开启开机自启" : "Auto start enabled"),
								"llama.cpp-hub", javax.swing.JOptionPane.INFORMATION_MESSAGE);
						}
					});
				}, "autostart-toggle").start();
			});
			tray.addSeparator();
			tray.addButton(btnExit, () -> {
				LlamaServerManager.getInstance().shutdownAll();
				System.exit(0);
			});

			tray.setDefaultAction(() -> {
				// 双击托盘图标触发，暂时没东西
				try {
					Desktop.getDesktop().browse(new URI(host));
				} catch (Exception e) {
					e.printStackTrace();
				}
			});

			tray.start("llama.cpp-hub");
			tray.displayInfoMessage(notifyTitle, notifyMsg);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
