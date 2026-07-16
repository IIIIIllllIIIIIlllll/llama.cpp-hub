package org.mark.llamacpp.server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.apache.logging.log4j.CloseableThreadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 	llamacpp进程 — Fix 版本。
 * 	对比老版本的做了这些修改：<br>
 * 	
 * 	1. reader 线程改用虚拟线程 (Thread.ofVirtual())，不占用平台线程
 * 	2. stop() 中显式关闭 stdwriter + 子进程 stdout/stderr 流，避免 SIGPIPE + 让 readLine() 立即返回
 * 	3. outputHandler.accept() 包裹 try-catch，防止异常穿透杀死 reader 线程
 * 	4. error 线程和 output 线程的异常处理对称，不再吞没异常
 * 	5. send() 写入后 flush()，确保数据及时送达
 * 	6. 提取 closeQuietly() / joinThread() 辅助方法，消除重复样板
 */
public class LlamaCppProcess {

	private static final Logger logger = LoggerFactory.getLogger(LlamaCppProcess.class);
	private static final Logger RAW_PROCESS_LOGGER = LoggerFactory.getLogger("LLAMA_CPP_RAW");

	private final String name;
	private final String cmd;
	private final String llamaBinPath;
	private final String modelId;
	private final String sourceModelId;
	private long pid;
	private Process process;
	private Thread outputThread;
	private Thread errorThread;
	private final AtomicBoolean isRunning = new AtomicBoolean(false);
	private final AtomicBoolean stopRequested = new AtomicBoolean(false);
	private Consumer<String> outputHandler;
	private Consumer<ProcessExitInfo> onProcessExited;
	private BufferedWriter stdwriter;
	private int ctxSize;
	private int slotNum;
	private int[] slotStatus;
	private CompletableFuture<Void> exitFuture;
	private final AtomicReference<ProcessExitInfo> exitInfoRef = new AtomicReference<>();
	private Map<String, String> envVars = java.util.Collections.emptyMap();

	public LlamaCppProcess(String name, String cmd, String llamaBinPath, String modelId, String sourceModelId) {
		this.name = name;
		this.cmd = cmd;
		this.llamaBinPath = llamaBinPath;
		this.modelId = sanitizeModelId(modelId);
		this.sourceModelId = sourceModelId;
	}

	public String getLlamaBinPath() {
		return this.llamaBinPath;
	}

	public void setOutputHandler(Consumer<String> outputHandler) {
		this.outputHandler = outputHandler;
	}

	public void setOnProcessExited(Consumer<ProcessExitInfo> onProcessExited) {
		this.onProcessExited = onProcessExited;
	}

	public void setCtxSize(int ctxSize) {
		this.ctxSize = ctxSize;
	}

	public int getCtxSize() {
		return this.ctxSize;
	}

	public void setSlotNum(int slotNum) {
		this.slotNum = slotNum;
		this.slotStatus = new int[slotNum];
	}
	
	public int getSlotNum() {
		return this.slotNum;
	}
	
	public int[] getSlotStaus() {
		return this.slotStatus;
	}

	public void setEnvVars(Map<String, String> envVars) {
		this.envVars = envVars == null ? Collections.emptyMap() : new LinkedHashMap<>(envVars);
	}

	/**
	 * 	写入输入内容
	 */
	public synchronized void send(String cmd) {
		if (this.stdwriter == null) return;
		try {
			this.stdwriter.write(cmd);
			this.stdwriter.flush();
		} catch (IOException e) {
			logger.warn("写入进程 stdin 失败: {}", e.getMessage());
		}
	}

	/**
	 * 	异步启动进程
	 * @return 是否启动成功
	 */
	public synchronized boolean start() {
		if (isRunning.get()) {
			return false;
		}

		try {
			List<String> args = splitCommandLineArgs(cmd);
			ProcessBuilder pb = new ProcessBuilder(args);

			Map<String, String> env = pb.environment();

			// 处理用户自定义环境变量（命令行模式）
			String userPath = null;
			String userLdPath = null;
			if (this.envVars != null && !this.envVars.isEmpty()) {
				userPath = this.envVars.get("PATH");
				userLdPath = this.envVars.get("LD_LIBRARY_PATH");
				for (Map.Entry<String, String> entry : this.envVars.entrySet()) {
					String key = entry.getKey();
					if ("PATH".equals(key) || "LD_LIBRARY_PATH".equals(key)) {
						continue;
					}
					logger.info("注入用户环境变量: {}={}", key, entry.getValue());
					env.put(key, entry.getValue());
				}
				// Linux 上 PATH 不由后端管理，直接应用用户值
				if (userPath != null && !isWindows()) {
					logger.info("注入用户 PATH: {}", userPath);
					env.put("PATH", userPath);
				}
			}

			// 处理linux，windows也需要增加
			if (isWindows()) {
				this.applyWindowsRuntimePath(pb, env);
			}

			//
			String existingLdPath = env.get("LD_LIBRARY_PATH");

			StringBuilder ldPathBuilder = new StringBuilder();
			if (this.llamaBinPath != null && !this.llamaBinPath.isEmpty()) {
				ldPathBuilder.append(this.llamaBinPath);
			}

			String[] rocmPaths = {
				"/opt/rocm-7.2.0/lib",
				"/opt/rocm-7.2.0/lib64",
				"/opt/rocm/lib",
				"/opt/rocm/lib64",
				"/usr/local/rocm/lib",
				"/usr/local/rocm/lib64",
				"/usr/local/lib64",
				"/usr/local/lib"
			};
			for (String rocmPath : rocmPaths) {
				if (ldPathBuilder.length() > 0) {
					ldPathBuilder.append(":");
				}
				ldPathBuilder.append(rocmPath);
			}

			// SYCL / Intel oneAPI 运行库路径
			if (isSyclBackend(this.cmd, this.llamaBinPath)) {
				String[] syclPaths = {
					"/opt/intel/oneapi/compiler/latest/lib",
					"/opt/intel/oneapi/mkl/latest/lib",
					"/opt/intel/oneapi/tbb/latest/lib",
					"/usr/local/lib",
					"/usr/local/lib64",
					"/usr/lib/x86_64-linux-gnu"
				};
				for (String syclPath : syclPaths) {
					if (ldPathBuilder.length() > 0) {
						ldPathBuilder.append(":");
					}
					ldPathBuilder.append(syclPath);
				}
			}

			if (existingLdPath != null && !existingLdPath.isEmpty()) {
				if (ldPathBuilder.length() > 0) {
					ldPathBuilder.append(":");
				}
				ldPathBuilder.append(existingLdPath);
			}

			env.put("LD_LIBRARY_PATH", ldPathBuilder.toString());

			// 将用户 LD_LIBRARY_PATH 置于后端构造的路径之前
			if (userLdPath != null && !userLdPath.isEmpty()) {
				String backendLdPath = env.get("LD_LIBRARY_PATH");
				String mergedLdPath = userLdPath + ":" + backendLdPath;
				logger.info("合并 LD_LIBRARY_PATH: {}", mergedLdPath);
				env.put("LD_LIBRARY_PATH", mergedLdPath);
			}

			// Windows 上将用户 PATH 置于后端运行时路径之前
			if (userPath != null && !userPath.isEmpty() && isWindows()) {
				String backendPath = env.get("PATH");
				String mergedPath = userPath + ";" + backendPath;
				logger.info("合并 PATH: {}", mergedPath);
				env.put("PATH", mergedPath);
			}

			this.process = pb.start();
			logger.info("llama-server 进程已启动");

			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}

			try {
				this.pid = this.process.pid();
				this.stdwriter = new BufferedWriter(new OutputStreamWriter(this.process.getOutputStream(), StandardCharsets.UTF_8));
			} catch (Exception e) {
				logger.error("获取进程 PID 或输出流失败", e);
				this.pid = -1;
			}

			this.isRunning.set(true);
			this.startOutputReaders();
			this.exitFuture = this.process.onExit().thenApply(v -> {
				this.onProcessExit();
				return null;
			});
			return true;

		} catch (IOException e) {
			logger.error("启动 llama-server 失败: {}", e.getMessage());
			return false;
		}
	}

	/**
	 * 	停止进程 — Windows 修复版。
	 * 	
	 * 	操作顺序（重要，特别是 Windows）：
	 * 	1. 关闭 stdin writer
	 * 	2. 先 destroy() 杀死子进程 → 关闭管道写端 → 读端 ReadFile 收到 ERROR_BROKEN_PIPE
	 * 	3. 再关闭 Java 端的 InputStream/ErrorStream
	 * 	4. 等待 reader 线程自然退出
	 * 	
	 * 	为什么不能先关流再杀进程（Windows）：
	 * 	在 Windows 上，CloseHandle(管道读端) 不会中断另一个线程中正在进行的 ReadFile()。
	 * 	虚拟线程因此永久 pinned 在 readLine() 上，joinThread 超时后漏掉线程。
	 * 	先杀进程让 Windows 关闭写端，ReadFile 返回断管错误，reader 线程自然 exit。
	 */
	public synchronized boolean stop() {
		if (!this.isRunning.getAndSet(false)) {
			return false;
		}

		// 标记为预期退出
		this.stopRequested.set(true);

		// 1. 关闭 stdin writer — 防止向已死进程写入时触发 SIGPIPE
		closeQuietly(this.stdwriter);
		this.stdwriter = null;

		if (this.process != null) {
			// 2. 先优雅终止进程 — 关闭进程端管道句柄
			this.process.destroy();

			try {
				if (!this.process.waitFor(5, TimeUnit.SECONDS)) {
					this.process.destroyForcibly();
					this.process.waitFor(1, TimeUnit.SECONDS);
				}
			} catch (InterruptedException e) {
				this.process.destroyForcibly();
				Thread.currentThread().interrupt();
			}

			// 3. 进程已死 / 管道已断裂，再关闭 Java 端流（reader 线程已收到 EOF 或 IOException）
			closeQuietly(this.process.getInputStream());
			closeQuietly(this.process.getErrorStream());
		}

		// 4. 等待 reader 线程自然退出
		joinThread(this.outputThread, 2000);
		joinThread(this.errorThread, 2000);

		return true;
	}
	
	
	// ========================================================================
	// Windows ROCm — 添加Windows上的ROCm环境
	// ========================================================================
	
	
	private void applyWindowsRuntimePath(ProcessBuilder pb, Map<String, String> env) {
		List<String> paths = new ArrayList<>();
		this.addExistingDir(paths, this.llamaBinPath);

		List<String> args = splitCommandLineArgs(this.cmd);
		if (!args.isEmpty()) {
			File exe = new File(args.get(0));
			File exeDir = exe.getParentFile();
			if (exeDir != null) {
				this.addExistingDir(paths, exeDir.getAbsolutePath());
				pb.directory(exeDir);
			}
		}

		this.addWindowsRocmDirs(paths);
		this.addWindowsCudartDirs(paths);
		this.addWindowsSyclDirs(paths);
		this.prependPath(env, paths);
	}

	private void addWindowsRocmDirs(List<String> paths) {
		File rocmRoot = new File("C:\\Program Files\\AMD\\ROCm");
		File[] versions = rocmRoot.listFiles(File::isDirectory);
		if (versions != null) {
			Arrays.sort(versions, Comparator.comparing(File::getName).reversed());
			for (File version : versions) {
				this.addExistingDir(paths, new File(version, "bin").getAbsolutePath());
				this.addExistingDir(paths, new File(version, "bin\\rocblas").getAbsolutePath());
				this.addExistingDir(paths, new File(version, "bin\\hipblaslt").getAbsolutePath());
			}
		}

		String[] fallbackRoots = {
			"C:\\Program Files\\AMD\\AI_Bundle\\ROCm",
			"C:\\Program Files\\AMD\\AI_Bundle"
		};
		for (String root : fallbackRoots) {
			File dir = new File(root);
			if (!dir.isDirectory()) {
				continue;
			}
			this.addExistingDir(paths, new File(dir, "bin").getAbsolutePath());
			this.addExistingDir(paths, new File(dir, "bin\\rocblas").getAbsolutePath());
			this.addExistingDir(paths, new File(dir, "bin\\hipblaslt").getAbsolutePath());
		}
	}

	private void addWindowsSyclDirs(List<String> paths) {
		if (!isSyclBackend(this.cmd, this.llamaBinPath)) {
			return;
		}
		// 若执行目录已包含 SYCL 运行时，则只需该目录已在 PATH 中即可
		String[] oneapiRoots = {
			System.getenv("ONEAPI_ROOT"),
			"C:\\Program Files (x86)\\Intel\\oneAPI",
			"C:\\Program Files\\Intel\\oneAPI"
		};
		for (String root : oneapiRoots) {
			if (root == null || root.isBlank()) continue;
			File rootDir = new File(root);
			if (!rootDir.isDirectory()) continue;
			File latest = new File(rootDir, "latest");
			if (latest.isDirectory()) {
				this.addExistingDir(paths, new File(latest, "bin").getAbsolutePath());
				this.addExistingDir(paths, new File(latest, "lib").getAbsolutePath());
			}
			File compilerLatest = new File(rootDir, "compiler\\latest");
			if (compilerLatest.isDirectory()) {
				this.addExistingDir(paths, new File(compilerLatest, "bin").getAbsolutePath());
				this.addExistingDir(paths, new File(compilerLatest, "lib").getAbsolutePath());
			}
			File mklLatest = new File(rootDir, "mkl\\latest");
			if (mklLatest.isDirectory()) {
				this.addExistingDir(paths, new File(mklLatest, "bin").getAbsolutePath());
				this.addExistingDir(paths, new File(mklLatest, "lib").getAbsolutePath());
			}
		}
	}

	private void addWindowsCudartDirs(List<String> paths) {
		// 如果 llamaBinPath 下已有 cudart DLL，则跳过
		if (this.hasCudartDlls(this.llamaBinPath)) {
			return;
		}
		// 扫描 llamacpp/ 下所有 cudart 目录，全部加入 PATH
		String root = LlamaServer.getDefaultLlamaCppPath();
		Path rootPath = Paths.get(root);
		if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
			return;
		}
		try (java.util.stream.Stream<Path> entries = Files.list(rootPath)) {
			for (Path subDir : entries.toList()) {
				if (!Files.isDirectory(subDir)) {
					continue;
				}
				if (!this.hasCudartDlls(subDir.toString())) {
					continue;
				}
				this.addExistingDir(paths, subDir.toAbsolutePath().toString());
			}
		} catch (IOException e) {
			logger.warn("扫描 cudart 目录失败: {}", e.getMessage());
		}
	}

	private boolean hasCudartDlls(String dirPath) {
		if (dirPath == null || dirPath.isBlank()) {
			return false;
		}
		Path dir = Paths.get(dirPath);
		if (!Files.isDirectory(dir)) {
			return false;
		}
		try (java.util.stream.Stream<Path> entries = Files.list(dir)) {
			boolean hasCublas = false;
			boolean hasCublasLt = false;
			boolean hasCudart = false;
			for (Path entry : entries.toList()) {
				if (!Files.isRegularFile(entry)) {
					continue;
				}
				String name = entry.getFileName().toString();
				String lower = name.toLowerCase();
				if (lower.startsWith("cublas64_") && lower.endsWith(".dll")) {
					hasCublas = true;
				}
				if (lower.startsWith("cublaslt64_") && lower.endsWith(".dll")) {
					hasCublasLt = true;
				}
				if (lower.startsWith("cudart64_") && lower.endsWith(".dll")) {
					hasCudart = true;
				}
			}
			return hasCublas && hasCublasLt && hasCudart;
		} catch (IOException e) {
			return false;
		}
	}

	private void addExistingDir(List<String> paths, String path) {
		if (path == null || path.isBlank()) {
			return;
		}
		File dir = new File(path);
		if (!dir.isDirectory()) {
			return;
		}
		String abs = dir.getAbsolutePath();
		for (String existing : paths) {
			if (existing.equalsIgnoreCase(abs)) {
				return;
			}
		}
		paths.add(abs);
	}

	private void prependPath(Map<String, String> env, List<String> paths) {
		if (paths == null || paths.isEmpty()) {
			return;
		}
		String key = this.findEnvKey(env, "PATH");
		String current = key == null ? "" : env.getOrDefault(key, "");
		StringBuilder merged = new StringBuilder();
		for (String path : paths) {
			if (current != null && containsPathIgnoreCase(current, path)) {
				continue;
			}
			if (merged.length() > 0) {
				merged.append(';');
			}
			merged.append(path);
		}
		if (current != null && !current.isBlank()) {
			if (merged.length() > 0) {
				merged.append(';');
			}
			merged.append(current);
		}
		env.put(key == null ? "PATH" : key, merged.toString());
	}

	private String findEnvKey(Map<String, String> env, String key) {
		for (String existing : env.keySet()) {
			if (existing.equalsIgnoreCase(key)) {
				return existing;
			}
		}
		return null;
	}

	private boolean containsPathIgnoreCase(String pathList, String path) {
		if (pathList == null || path == null) {
			return false;
		}
		for (String entry : pathList.split(";")) {
			if (entry.trim().equalsIgnoreCase(path)) {
				return true;
			}
		}
		return false;
	}

	// ========================================================================
	// Reader 线程 — 用虚拟线程
	// ========================================================================

	private void startOutputReaders() {
		Consumer<String> safeHandler = line -> {
			try (var ctx = CloseableThreadContext.put("modelId", this.modelId)) {
				if (this.outputHandler != null) {
					try {
						this.outputHandler.accept(line);
					} catch (Exception e) {
						logger.error("outputHandler 抛出异常: {}", e.getMessage());
					}
				}
				if (!line.contains("update_slots") && !line.contains("log_server_r")) {
					RAW_PROCESS_LOGGER.info(line);
				}
			}
		};

		this.outputThread = Thread.ofVirtual().name("llama-out-" + name).start(() -> {
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(this.process.getInputStream()))) {
				String line;
				while ((line = reader.readLine()) != null && this.isRunning.get()) {
					safeHandler.accept(line);
				}
			} catch (IOException e) {
				if (this.isRunning.get()) {
					logger.warn("读取进程输出流时发生错误: {}", e.getMessage());
				}
			}
		});

		this.errorThread = Thread.ofVirtual().name("llama-err-" + name).start(() -> {
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(this.process.getErrorStream()))) {
				String line;
				while ((line = reader.readLine()) != null && this.isRunning.get()) {
					safeHandler.accept(line);
				}
			} catch (IOException e) {
				if (this.isRunning.get()) {
					logger.warn("读取进程错误流时发生错误: {}", e.getMessage());
				}
			}
		});
	}

	/**
	 * Called by Process.onExit() when the process terminates.
	 * This is the authoritative source for process exit detection.
	 */
	private synchronized void onProcessExit() {
		if (this.exitInfoRef.get() != null) {
			return;
		}
		ProcessExitInfo info = new ProcessExitInfo();
		info.unexpected = !this.stopRequested.get();
		try {
			info.exitCode = this.process.exitValue();
		} catch (IllegalArgumentException e) {
			info.exitCode = -1;
		}
		this.exitInfoRef.set(info);
		if (this.onProcessExited != null) {
			try {
				this.onProcessExited.accept(info);
			} catch (Exception e) {
				logger.error("onProcessExited 回调抛出异常: {}", e.getMessage());
			}
		}
	}

	// ========================================================================
	// Getters
	// ========================================================================

	public String getName() {
		return this.name;
	}

	public String getCmd() {
		return this.cmd;
	}

	public String getSourceModelId() {
		return this.sourceModelId;
	}

	public boolean isClone() {
		return this.sourceModelId != null;
	}

	public long getPid() {
		return this.pid;
	}

	public boolean isRunning() {
		return this.isRunning.get() && this.process != null && this.process.isAlive();
	}

	public Process getProcess() {
		return this.process;
	}

	public Integer getExitCode() {
		if (this.process != null && !this.process.isAlive()) {
			return this.process.exitValue();
		}
		return null;
	}

	public CompletableFuture<Void> getExitFuture() {
		return this.exitFuture;
	}

	public ProcessExitInfo getExitInfo() {
		return this.exitInfoRef.get();
	}

	// ========================================================================
	// 静态辅助
	// ========================================================================

	private static void closeQuietly(java.io.Closeable closeable) {
		if (closeable != null) {
			try {
				closeable.close();
			} catch (IOException ignored) {
			}
		}
	}

	private static void joinThread(Thread thread, long timeoutMillis) {
		if (thread == null) return;
		try {
			thread.join(timeoutMillis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	static List<String> splitCommandLineArgs(String commandLine) {
		List<String> out = new ArrayList<>();
		if (commandLine == null) {
			return out;
		}
		String s = commandLine.trim();
		if (s.isEmpty()) {
			return out;
		}

		StringBuilder cur = new StringBuilder();
		boolean allowSingle = true;
		boolean inSingle = false;
		boolean inDouble = false;

		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);

			if (inDouble && c == '\\') {
				if (i + 1 < s.length()) {
					char n = s.charAt(i + 1);
					if (n == '"') {
						cur.append(n);
						i++;
						continue;
					}
				}
				cur.append(c);
				continue;
			}
			if (allowSingle && inSingle && c == '\\') {
				if (i + 1 < s.length()) {
					char n = s.charAt(i + 1);
					if (n == '\'') {
						cur.append(n);
						i++;
						continue;
					}
					if (n == '"') {
						if (isWindows()) {
							cur.append(c);
						}
						cur.append(n);
						i++;
						continue;
					}
				}
				cur.append(c);
				continue;
			}

			if (c == '"' && !inSingle) {
				inDouble = !inDouble;
				continue;
			}
			if (allowSingle && c == '\'' && !inDouble) {
				inSingle = !inSingle;
				continue;
			}

			if (!inSingle && !inDouble && Character.isWhitespace(c)) {
				if (cur.length() > 0) {
					out.add(cur.toString());
					cur.setLength(0);
				}
				continue;
			}

			cur.append(c);
		}
		if (cur.length() > 0) {
			out.add(cur.toString());
		}
		return out;
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
	 * 判断当前命令或二进制路径是否指向 SYCL 后端。
	 */
	private static boolean isSyclBackend(String cmd, String llamaBinPath) {
		String haystack = (cmd == null ? "" : cmd) + "|" + (llamaBinPath == null ? "" : llamaBinPath);
		String lower = haystack.toLowerCase(Locale.ROOT);
		return lower.contains("sycl") || lower.contains("intel") || lower.contains("oneapi");
	}

	/**
	 * Process exit information passed to the onProcessExited callback.
	 */
	public static class ProcessExitInfo {
		public int exitCode = -1;
		public boolean unexpected = true;
	}
}
