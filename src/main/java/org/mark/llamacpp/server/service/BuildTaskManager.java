package org.mark.llamacpp.server.service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.tools.CommandLineRunner;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.websocket.WebSocketManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

public class BuildTaskManager {

    private static final Logger logger = LoggerFactory.getLogger(BuildTaskManager.class);
    private static final WebSocketManager wsManager = WebSocketManager.getInstance();
    private static final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private static final int DEFAULT_TIMEOUT_MINUTES = 60;
    private static final long PERSIST_INTERVAL_MS = 1000;

    private static final String TASKS_DIR = "cache/build-tasks";
    private static final String TASK_FILE_SUFFIX = ".json";
    private static final String LOG_FILE_SUFFIX = ".log";

    private static volatile BuildTaskManager instance;

    private final AtomicBoolean building = new AtomicBoolean(false);
    private final Map<String, BuildTask> tasks = new ConcurrentHashMap<>();

    private BuildTaskManager() {
        loadTasks();
    }

    public static BuildTaskManager getInstance() {
        if (instance == null) {
            synchronized (BuildTaskManager.class) {
                if (instance == null) {
                    instance = new BuildTaskManager();
                }
            }
        }
        return instance;
    }

    public BuildTask submitTask(String sourceArchive, String sourceDir, String outputDirName,
            String cmakeCommand, String buildCommand) {
        if (!building.compareAndSet(false, true)) {
            return null;
        }

        BuildTask task = new BuildTask();
        task.taskId = java.util.UUID.randomUUID().toString().substring(0, 8);
        task.sourceArchive = sourceArchive;
        task.sourceDir = sourceDir;
        task.outputDirName = outputDirName;
        task.cmakeCommand = cmakeCommand;
        task.buildCommand = buildCommand;
        task.status = "PENDING";
        task.startTime = System.currentTimeMillis();
        task.exitCode = 0;

        tasks.put(task.taskId, task);
        persistTask(task);

        executor.execute(() -> executeTask(task));

        return task;
    }

    public BuildTask getStatus(String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            return tasks.values().stream().findFirst().orElse(null);
        }
        return tasks.get(taskId);
    }

    public List<BuildTask> getHistory() {
        List<BuildTask> list = new ArrayList<>(tasks.values());
        list.sort((a, b) -> Long.compare(b.startTime, a.startTime));
        return list;
    }

    public boolean cancelTask(String taskId) {
        BuildTask task;
        if (taskId == null || taskId.isEmpty()) {
            task = tasks.values().stream()
                    .filter(t -> "RUNNING".equals(t.status)).findFirst().orElse(null);
        } else {
            task = tasks.get(taskId);
        }
        if (task == null) {
            return false;
        }
        if (!"RUNNING".equals(task.status) && !"PENDING".equals(task.status)) {
            return false;
        }
        task.cancelled.set(true);
        if (task.process != null) {
            task.process.destroy();
        }
        return true;
    }

    public boolean extractArchive(String archivePath, String targetDir) {
        File archive = new File(archivePath);
        if (!archive.exists() || !archivePath.toLowerCase().endsWith(".zip")) {
            return false;
        }

        try {
            Path target = Paths.get(targetDir);
            Files.createDirectories(target);
            extractZip(archive, target);
            return true;
        } catch (Exception e) {
            logger.error("解压失败: {}", e.getMessage(), e);
            return false;
        }
    }

    private void extractZip(File archive, Path target) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(archive.toPath()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path dest = target.resolve(entry.getName()).normalize();
                if (!dest.startsWith(target)) {
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(zis, dest, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    private void executeTask(BuildTask task) {
        try {
            Path llamacppDir = Paths.get(LlamaServer.getDefaultLlamaCppPath()).toAbsolutePath().normalize();
            Path outputDir = llamacppDir.resolve(task.outputDirName).toAbsolutePath().normalize();
            if (!outputDir.startsWith(llamacppDir)) {
                task.status = "FAILED";
                appendOutput(task, "输出目录不合法");
                task.endTime = System.currentTimeMillis();
                task.exitCode = -1;
                persistTask(task);
                broadcastStatus(task, "FAILED", -1, null);
                return;
            }

            Path sourceDir = Paths.get(task.sourceDir).toAbsolutePath().normalize();
            if (!Files.isDirectory(sourceDir)) {
                task.status = "FAILED";
                appendOutput(task, "源码目录不存在: " + task.sourceDir);
                task.endTime = System.currentTimeMillis();
                task.exitCode = -1;
                persistTask(task);
                broadcastStatus(task, "FAILED", -1, null);
                return;
            }

            Path buildDir = sourceDir.resolve("build");

            task.outputDir = outputDir.toString();
            task.status = "RUNNING";
            persistTask(task);
            broadcastStatus(task, "RUNNING", 0, null);

            if (!executePhase(task, "cmake", sourceDir, buildDir, outputDir, task.cmakeCommand, DEFAULT_TIMEOUT_MINUTES)) {
                return;
            }
            if (!executePhase(task, "build", sourceDir, buildDir, outputDir, task.buildCommand, DEFAULT_TIMEOUT_MINUTES)) {
                return;
            }
            boolean copied = copyBuildArtifacts(task, sourceDir, outputDir);
            if (!copied) {
                appendOutput(task, "[警告] 未找到编译产物，请检查构建命令或源码结构");
            }

            task.status = "COMPLETED";
            task.endTime = System.currentTimeMillis();
            task.exitCode = 0;
            persistTask(task);
            broadcastStatus(task, "COMPLETED", 0, outputDir.toString());

        } catch (Exception e) {
            logger.error("编译任务异常: {}", e.getMessage(), e);
            task.status = "FAILED";
            appendOutput(task, "[异常] " + e.getMessage());
            task.endTime = System.currentTimeMillis();
            task.exitCode = -1;
            persistTask(task);
            broadcastStatus(task, "FAILED", -1, null);
        } finally {
            closeLogWriter(task);
            building.set(false);
        }
    }

    private boolean executePhase(BuildTask task, String phase, Path sourceDir, Path buildDir, Path outputDir,
            String command, int timeoutMinutes) {
        if (command == null || command.trim().isEmpty()) {
            return true;
        }

        command = command.replace("{{BUILD_DIR}}", buildDir.toString())
                         .replace("{{OUTPUT_DIR}}", outputDir.toString());
        broadcastOutput(task, phase, "[" + phase.toUpperCase() + "] 执行: " + command);

        try {
            List<String> cmdArray;
            if (needsShell(command)) {
                if (isWindows()) {
                    cmdArray = java.util.Arrays.asList("cmd.exe", "/s", "/c", command);
                } else {
                    cmdArray = java.util.Arrays.asList("bash", "-c", command);
                }
            } else {
                cmdArray = CommandLineRunner.splitCommandLineArgs(command);
            }
            ProcessBuilder pb = new ProcessBuilder(cmdArray);
            pb.directory(sourceDir.toFile());
            pb.redirectErrorStream(true);

            Map<String, String> env = pb.environment();
            String existingPath = env.get("PATH");
            String javaHome = System.getProperty("java.home", "");
            if (javaHome.endsWith("bin") || javaHome.endsWith("bin" + File.separator)) {
                String jdkHome = new File(javaHome, "..").getAbsolutePath();
                String mvnBin = jdkHome + File.separator + "bin";
                env.put("PATH", mvnBin + File.pathSeparator + existingPath);
            }

            Set<String> gpuDeps = detectGpuDeps(command);
            if (!gpuDeps.isEmpty()) {
                if (isWindows()) {
                    applyWindowsRuntimePath(pb, env, gpuDeps);
                } else {
                    applyLinuxRuntimePath(env, gpuDeps);
                    applyLinuxCompilerPath(env, gpuDeps);
                }
            }

            Process process = pb.start();
            task.process = process;

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

            Thread readerThread = Thread.ofVirtual().start(() -> {
                try {
                    String line;
                    while (!task.cancelled.get() && (line = reader.readLine()) != null) {
                        broadcastOutput(task, phase, line);
                        appendOutput(task, line);
                    }
                } catch (IOException e) {
                    if (!task.cancelled.get()) {
                        broadcastOutput(task, phase, "[错误] 读取输出失败: " + e.getMessage());
                    }
                }
            });

            boolean finished = false;
            try {
                finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                broadcastOutput(task, phase, "[中断] 编译被中断");
            }

            try {
                readerThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            if (task.cancelled.get()) {
                task.status = "CANCELLED";
                task.endTime = System.currentTimeMillis();
                persistTask(task);
                broadcastStatus(task, "CANCELLED", -1, null);
                return false;
            }

            if (!finished) {
                process.destroyForcibly();
                task.status = "FAILED";
                appendOutput(task, "\n[超时] 编译超时 (" + timeoutMinutes + "分钟)");
                task.endTime = System.currentTimeMillis();
                task.exitCode = -2;
                persistTask(task);
                broadcastStatus(task, "FAILED", -2, null);
                return false;
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                task.status = "FAILED";
                appendOutput(task, "\n[" + phase.toUpperCase() + "] 退出码: " + exitCode);
                task.endTime = System.currentTimeMillis();
                task.exitCode = exitCode;
                persistTask(task);
                broadcastStatus(task, "FAILED", exitCode, null);
                return false;
            }

            broadcastOutput(task, phase, "[" + phase.toUpperCase() + "] 完成");
            return true;

        } catch (IOException e) {
            broadcastOutput(task, phase, "[错误] 执行失败: " + e.getMessage());
            task.status = "FAILED";
            appendOutput(task, "\n[错误] " + e.getMessage());
            task.endTime = System.currentTimeMillis();
            task.exitCode = -1;
            persistTask(task);
            broadcastStatus(task, "FAILED", -1, null);
            return false;
        }
    }

    private boolean copyBuildArtifacts(BuildTask task, Path sourceDir, Path outputDir) {
        try {
            Files.createDirectories(outputDir);

            Path releaseDir = sourceDir.resolve("build").resolve("bin").resolve("release");
            if (!Files.isDirectory(releaseDir)) {
                releaseDir = sourceDir.resolve("build").resolve("bin").resolve("Release");
            }
            if (!Files.isDirectory(releaseDir)) {
                releaseDir = sourceDir.resolve("build").resolve("Release");
            }
            if (!Files.isDirectory(releaseDir)) {
                releaseDir = sourceDir.resolve("build").resolve("bin");
            }
            if (!Files.isDirectory(releaseDir)) {
                releaseDir = sourceDir.resolve("build");
            }

            if (!Files.isDirectory(releaseDir)) {
                return false;
            }

            final Path srcDir = releaseDir;
            final AtomicBoolean found = new AtomicBoolean(false);

            try (Stream<Path> files = Files.list(srcDir)) {
                files.filter(Files::isRegularFile).forEach(file -> {
                    try {
                        Path target = outputDir.resolve(file.getFileName());
                        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                        found.set(true);
                        appendOutput(task, "[安装] 复制: " + file.getFileName());
                    } catch (IOException e) {
                        appendOutput(task, "[错误] 复制失败: " + file.getFileName() + " - " + e.getMessage());
                    }
                });
            }

            return found.get();
        } catch (Exception e) {
            logger.error("复制编译产物失败", e);
            appendOutput(task, "[错误] 复制编译产物失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 追加一行到任务的日志文件（cache/build-tasks/<id>.log）。
     * 输出不再进内存：替代原先在 task.output 字符串上累积（上限 1MB）的做法，
     * 同时消除了旧实现每秒把整个任务（含输出）序列化重写一次的开销。
     */
    private void appendOutput(BuildTask task, String line) {
        synchronized (task.outputLock) {
            try {
                if (task.logWriter == null) {
                    Path logFile = getTaskLogPath(task.taskId);
                    Files.createDirectories(logFile.getParent());
                    task.logWriter = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                }
                task.logWriter.write(line);
                task.logWriter.newLine();
            } catch (IOException e) {
                logger.error("写入编译日志失败: {}", e.getMessage(), e);
            }
        }
        long now = System.currentTimeMillis();
        if (now - task.lastPersistTime > PERSIST_INTERVAL_MS) {
            task.lastPersistTime = now;
            flushLog(task);
        }
    }

    private void flushLog(BuildTask task) {
        synchronized (task.outputLock) {
            if (task.logWriter != null) {
                try {
                    task.logWriter.flush();
                } catch (IOException ignore) {
                }
            }
        }
    }

    private void closeLogWriter(BuildTask task) {
        synchronized (task.outputLock) {
            if (task.logWriter != null) {
                try {
                    task.logWriter.close();
                } catch (IOException ignore) {
                }
                task.logWriter = null;
            }
        }
    }

    private static Path getTaskLogPath(String taskId) {
        return Paths.get(TASKS_DIR).toAbsolutePath().normalize().resolve(taskId + LOG_FILE_SUFFIX);
    }

    /**
     * 获取任务日志文件（供控制器流式下发），不存在或 taskId 不合法时返回 null
     */
    public File getTaskLogFile(String taskId) {
        if (taskId == null || !taskId.matches("[a-zA-Z0-9_-]+")) {
            return null;
        }
        Path path = getTaskLogPath(taskId);
        return Files.isRegularFile(path) ? path.toFile() : null;
    }

    private void broadcastOutput(BuildTask task, String phase, String line) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "build_output");
        msg.addProperty("taskId", task.taskId);
        msg.addProperty("phase", phase);
        msg.addProperty("line", line);
        msg.addProperty("timestamp", System.currentTimeMillis());
        wsManager.broadcast(JsonUtil.toJson(msg));
    }

    private void broadcastStatus(BuildTask task, String status, int exitCode, String outputDir) {
        task.status = status;
        task.exitCode = exitCode;

        JsonObject msg = new JsonObject();
        msg.addProperty("type", "build_status");
        msg.addProperty("taskId", task.taskId);
        msg.addProperty("status", status);
        msg.addProperty("exitCode", exitCode);
        if (outputDir != null) {
            msg.addProperty("outputDir", outputDir);
        }
        msg.addProperty("timestamp", System.currentTimeMillis());
        wsManager.broadcast(JsonUtil.toJson(msg));
    }

    private void persistTask(BuildTask task) {
        synchronized (task.outputLock) {
            try {
                Path dir = Paths.get(TASKS_DIR).toAbsolutePath().normalize();
                Files.createDirectories(dir);
                Path target = dir.resolve(task.taskId + TASK_FILE_SUFFIX);
                Path temp = dir.resolve(task.taskId + TASK_FILE_SUFFIX + ".tmp");
                String json = JsonUtil.toJson(task);
                Files.write(temp, json.getBytes(StandardCharsets.UTF_8));
                try {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                logger.error("持久化编译任务失败: {}", e.getMessage(), e);
            }
        }
    }

    private void loadTasks() {
        try {
            Path dir = Paths.get(TASKS_DIR).toAbsolutePath().normalize();
            if (!Files.isDirectory(dir)) {
                return;
            }
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(p -> p.toString().endsWith(TASK_FILE_SUFFIX))
                     .sorted(Comparator.comparing(Path::getFileName))
                     .forEach(this::loadTaskFile);
            }
        } catch (Exception e) {
            logger.error("加载编译任务失败: {}", e.getMessage(), e);
        }
    }

    private void loadTaskFile(Path file) {
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            // 旧格式迁移：output 字段从元数据 JSON 搬到独立的 .log 文件，之后 JSON 只存元数据
            boolean migrated = false;
            try {
                JsonObject obj = JsonUtil.fromJson(json, JsonObject.class);
                if (obj != null && obj.has("output") && obj.get("output").isJsonPrimitive()
                        && obj.has("taskId")) {
                    String legacyOutput = obj.get("output").getAsString();
                    if (!legacyOutput.isEmpty()) {
                        Files.writeString(getTaskLogPath(obj.get("taskId").getAsString()), legacyOutput,
                                StandardCharsets.UTF_8);
                    }
                    migrated = true;
                }
            } catch (Exception ignore) {
            }
            BuildTask task = JsonUtil.fromJson(json, BuildTask.class);
            if (task == null || task.taskId == null) {
                return;
            }
            task.ensureTransientFields();
            if ("RUNNING".equals(task.status) || "PENDING".equals(task.status)) {
                task.status = "FAILED";
                task.exitCode = -1;
                appendOutput(task, "\n[系统] 应用重启，编译任务中断");
                closeLogWriter(task);
                task.endTime = System.currentTimeMillis();
                persistTask(task);
            } else if (migrated) {
                persistTask(task);
            }
            tasks.put(task.taskId, task);
        } catch (Exception e) {
            logger.error("加载编译任务文件失败 {}: {}", file, e.getMessage(), e);
        }
    }

    private static boolean needsShell(String command) {
        if (command == null || command.trim().isEmpty()) {
            return false;
        }
        String trimmed = command.trim();
        if (trimmed.startsWith("export ") || trimmed.contains(" export ")) {
            return true;
        }
        return trimmed.contains("&&") || trimmed.contains("||") || trimmed.contains(";")
                || trimmed.contains("|") || trimmed.contains("$") || trimmed.contains("`");
    }

    private static boolean isWindows() {
        String osName = System.getProperty("os.name");
        String os = osName == null ? "" : osName.toLowerCase(java.util.Locale.ROOT);
        return os.contains("win");
    }

    private Set<String> detectGpuDeps(String command) {
        Set<String> deps = new HashSet<>();
        if (command == null) return deps;
        String lower = command.toLowerCase(Locale.ROOT);
        if (lower.contains("llama_cuda") || lower.contains("ggml_cuda") || lower.contains("cuda")) {
            deps.add("CUDA");
        }
        if (lower.contains("hipblas") || lower.contains("rocm") || lower.contains("amd")) {
            deps.add("ROCM");
        }
        if (lower.contains("vulkan") || lower.contains("ggml_vulkan")) {
            deps.add("VULKAN");
        }
        if (lower.contains("sycl") || lower.contains("ggml_sycl") || lower.contains("intel")) {
            deps.add("SYCL");
        }
        return deps;
    }

    private void applyWindowsRuntimePath(ProcessBuilder pb, Map<String, String> env, Set<String> deps) {
        List<String> paths = new ArrayList<>();
        addExistingDir(paths, null);
        addWindowsRocmDirs(paths, deps);
        addWindowsCudartDirs(paths, deps);
        addWindowsSyclDirs(paths, deps);
        prependPath(env, paths);
    }

    private void addWindowsRocmDirs(List<String> paths, Set<String> deps) {
        if (!deps.contains("ROCM")) return;
        File rocmRoot = new File("C:\\Program Files\\AMD\\ROCm");
        File[] versions = rocmRoot.listFiles(File::isDirectory);
        if (versions != null) {
            java.util.Arrays.sort(versions, Comparator.comparing(File::getName).reversed());
            for (File version : versions) {
                addExistingDir(paths, new File(version, "bin").getAbsolutePath());
                addExistingDir(paths, new File(version, "bin\\rocblas").getAbsolutePath());
                addExistingDir(paths, new File(version, "bin\\hipblaslt").getAbsolutePath());
            }
        }
        String[] fallbackRoots = {
            "C:\\Program Files\\AMD\\AI_Bundle\\ROCm",
            "C:\\Program Files\\AMD\\AI_Bundle"
        };
        for (String root : fallbackRoots) {
            File dir = new File(root);
            if (!dir.isDirectory()) continue;
            addExistingDir(paths, new File(dir, "bin").getAbsolutePath());
            addExistingDir(paths, new File(dir, "bin\\rocblas").getAbsolutePath());
            addExistingDir(paths, new File(dir, "bin\\hipblaslt").getAbsolutePath());
        }
    }

    private void addWindowsSyclDirs(List<String> paths, Set<String> deps) {
        if (!deps.contains("SYCL")) return;
        // Intel oneAPI 安装目录（包含编译器与 MKL 运行时）
        String[] oneapiRoots = {
            System.getenv("ONEAPI_ROOT"),
            "C:\\Program Files (x86)\\Intel\\oneAPI",
            "C:\\Program Files\\Intel\\oneAPI"
        };
        for (String root : oneapiRoots) {
            if (root == null || root.isBlank()) continue;
            File rootDir = new File(root);
            if (!rootDir.isDirectory()) continue;
            // 优先使用最新版本目录；若存在 latest 链接则用它
            File latest = new File(rootDir, "latest");
            if (latest.isDirectory()) {
                addExistingDir(paths, new File(latest, "bin").getAbsolutePath());
                addExistingDir(paths, new File(latest, "lib").getAbsolutePath());
            }
            File compilerLatest = new File(rootDir, "compiler\\latest");
            if (compilerLatest.isDirectory()) {
                addExistingDir(paths, new File(compilerLatest, "bin").getAbsolutePath());
                addExistingDir(paths, new File(compilerLatest, "lib").getAbsolutePath());
            }
            File mklLatest = new File(rootDir, "mkl\\latest");
            if (mklLatest.isDirectory()) {
                addExistingDir(paths, new File(mklLatest, "bin").getAbsolutePath());
                addExistingDir(paths, new File(mklLatest, "lib").getAbsolutePath());
            }
        }
    }

    private void addWindowsCudartDirs(List<String> paths, Set<String> deps) {
        if (!deps.contains("CUDA")) return;
        String root = LlamaServer.getDefaultLlamaCppPath();
        Path rootPath = Paths.get(root);
        if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) return;
        try (java.util.stream.Stream<Path> entries = Files.list(rootPath)) {
            for (Path subDir : entries.toList()) {
                if (!Files.isDirectory(subDir)) continue;
                if (!hasCudartDlls(subDir.toString())) continue;
                addExistingDir(paths, subDir.toAbsolutePath().toString());
            }
        } catch (IOException e) {
            logger.warn("扫描 cudart 目录失败: {}", e.getMessage());
        }
    }

    private boolean hasCudartDlls(String dirPath) {
        if (dirPath == null || dirPath.isBlank()) return false;
        Path dir = Paths.get(dirPath);
        if (!Files.isDirectory(dir)) return false;
        try (java.util.stream.Stream<Path> entries = Files.list(dir)) {
            boolean hasCublas = false, hasCublasLt = false, hasCudart = false;
            for (Path entry : entries.toList()) {
                if (!Files.isRegularFile(entry)) continue;
                String name = entry.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.startsWith("cublas64_") && name.endsWith(".dll")) hasCublas = true;
                if (name.startsWith("cublaslt64_") && name.endsWith(".dll")) hasCublasLt = true;
                if (name.startsWith("cudart64_") && name.endsWith(".dll")) hasCudart = true;
            }
            return hasCublas && hasCublasLt && hasCudart;
        } catch (IOException e) {
            return false;
        }
    }

    private void applyLinuxRuntimePath(Map<String, String> env, Set<String> deps) {
        String existingLdPath = env.get("LD_LIBRARY_PATH");
        StringBuilder ldPathBuilder = new StringBuilder();
        if (deps.contains("ROCM")) {
            String[] rocmPaths = {
                "/opt/rocm/core-7.14/lib", "/opt/rocm/core/lib",
                "/opt/rocm/lib", "/opt/rocm/lib64",
                "/usr/local/rocm/lib", "/usr/local/rocm/lib64",
                "/usr/local/lib64", "/usr/local/lib"
            };
            for (String p : rocmPaths) {
                if (ldPathBuilder.length() > 0) ldPathBuilder.append(":");
                ldPathBuilder.append(p);
            }
        }
        if (deps.contains("SYCL")) {
            String[] syclPaths = {
                "/opt/intel/oneapi/compiler/latest/lib",
                "/opt/intel/oneapi/mkl/latest/lib",
                "/opt/intel/oneapi/tbb/latest/lib",
                "/usr/local/lib", "/usr/local/lib64",
                "/usr/lib/x86_64-linux-gnu"
            };
            for (String p : syclPaths) {
                if (ldPathBuilder.length() > 0) ldPathBuilder.append(":");
                ldPathBuilder.append(p);
            }
        }
        if (existingLdPath != null && !existingLdPath.isEmpty()) {
            if (ldPathBuilder.length() > 0) ldPathBuilder.append(":");
            ldPathBuilder.append(existingLdPath);
        }
        if (ldPathBuilder.length() > 0) {
            env.put("LD_LIBRARY_PATH", ldPathBuilder.toString());
        }
    }

    private void applyLinuxCompilerPath(Map<String, String> env, Set<String> deps) {
        String existingPath = env.get("PATH");
        StringBuilder pathBuilder = new StringBuilder();

        if (deps.contains("CUDA")) {
            File nvcc = new File("/usr/local/cuda/bin/nvcc");
            if (nvcc.isFile()) {
                appendPathSep(pathBuilder);
                pathBuilder.append("/usr/local/cuda/bin");
                env.putIfAbsent("CUDACXX", nvcc.getAbsolutePath());
            } else {
                File cudaRoot = new File("/usr/local");
                File[] dirs = cudaRoot.listFiles((d, name) -> name.startsWith("cuda-"));
                if (dirs != null) {
                    java.util.Arrays.sort(dirs, (a, b) -> b.getName().compareTo(a.getName()));
                    for (File dir : dirs) {
                        File nvccFile = new File(dir, "bin/nvcc");
                        if (nvccFile.isFile()) {
                            String binDir = new File(dir, "bin").getAbsolutePath();
                            appendPathSep(pathBuilder);
                            pathBuilder.append(binDir);
                            env.putIfAbsent("CUDACXX", nvccFile.getAbsolutePath());
                            break;
                        }
                    }
                }
            }
        }

        if (deps.contains("ROCM")) {
            File hipcc = new File("/opt/rocm/bin/hipcc");
            if (hipcc.isFile()) {
                appendPathSep(pathBuilder);
                pathBuilder.append("/opt/rocm/bin");
                env.putIfAbsent("CMAKE_HIP_COMPILER", hipcc.getAbsolutePath());
            }
        }

        if (deps.contains("SYCL")) {
            File icpx = new File("/opt/intel/oneapi/compiler/latest/bin/icpx");
            File dpcpp = new File("/opt/intel/oneapi/compiler/latest/bin/dpcpp");
            if (icpx.isFile() || dpcpp.isFile()) {
                appendPathSep(pathBuilder);
                pathBuilder.append("/opt/intel/oneapi/compiler/latest/bin");
            }
        }

        if (pathBuilder.length() > 0) {
            if (existingPath != null && !existingPath.isEmpty()) {
                pathBuilder.append(File.pathSeparator).append(existingPath);
            }
            env.put("PATH", pathBuilder.toString());
        }
    }

    private static void appendPathSep(StringBuilder sb) {
        if (sb.length() > 0) sb.append(File.pathSeparator);
    }

    private void addExistingDir(List<String> paths, String path) {
        if (path == null || path.isBlank()) return;
        File dir = new File(path);
        if (!dir.isDirectory()) return;
        String abs = dir.getAbsolutePath();
        for (String existing : paths) {
            if (existing.equalsIgnoreCase(abs)) return;
        }
        paths.add(abs);
    }

    private void prependPath(Map<String, String> env, List<String> paths) {
        if (paths == null || paths.isEmpty()) return;
        String key = findEnvKey(env, "PATH");
        String current = key == null ? "" : env.getOrDefault(key, "");
        StringBuilder merged = new StringBuilder();
        for (String path : paths) {
            if (current != null && containsPathIgnoreCase(current, path)) continue;
            if (merged.length() > 0) merged.append(File.pathSeparator);
            merged.append(path);
        }
        if (current != null && !current.isBlank()) {
            if (merged.length() > 0) merged.append(File.pathSeparator);
            merged.append(current);
        }
        env.put(key == null ? "PATH" : key, merged.toString());
    }

    private String findEnvKey(Map<String, String> env, String key) {
        for (String existing : env.keySet()) {
            if (existing.equalsIgnoreCase(key)) return existing;
        }
        return null;
    }

    private boolean containsPathIgnoreCase(String pathList, String path) {
        if (pathList == null || path == null) return false;
        String sep = File.pathSeparator;
        for (String entry : pathList.split(sep)) {
            if (entry.trim().equalsIgnoreCase(path)) return true;
        }
        return false;
    }

    public static class BuildTask {
        public String taskId;
        public String sourceArchive;
        public String sourceDir;
        public String outputDirName;
        public String outputDir;
        public String cmakeCommand;
        public String buildCommand;
        public String status;
        public long startTime;
        public long endTime;
        public int exitCode;

        public transient Object outputLock = new Object();
        public transient Process process;
        public transient AtomicBoolean cancelled = new AtomicBoolean(false);
        public transient long lastPersistTime;
        public transient java.io.BufferedWriter logWriter;

        public BuildTask() {
            this.outputLock = new Object();
            this.cancelled = new AtomicBoolean(false);
        }

        public void ensureTransientFields() {
            if (this.outputLock == null) {
                this.outputLock = new Object();
            }
            if (this.cancelled == null) {
                this.cancelled = new AtomicBoolean(false);
            }
        }
    }
}
