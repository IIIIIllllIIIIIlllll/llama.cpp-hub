package org.mark.file.downloader;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import com.google.gson.Gson;
import org.mark.llamacpp.server.tools.JsonUtil;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DownloadTaskManager implements Closeable {

	private static final Logger logger = LoggerFactory.getLogger(DownloadTaskManager.class);

	private static final Gson GSON = JsonUtil.gson();

	private static final DownloadTaskManager INSTANCE;
	static {
		try {
			INSTANCE = new DownloadTaskManager(
					Path.of(System.getProperty("user.dir"), "cache", "tasks.json"),
					Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
		} catch (IOException e) {
			throw new RuntimeException("初始化下载任务管理器失败", e);
		}
	}

	public static DownloadTaskManager getInstance() {
		return INSTANCE;
	}

	private final Path cacheFile;
	private final ExecutorService workerPool;
	private final Map<String, DownloadTaskInfo> taskStore = new ConcurrentHashMap<>();
	private final Map<String, RuntimeTaskContext> runtimeStore = new ConcurrentHashMap<>();
	private final Map<String, DownloadProgressListener> listeners = new ConcurrentHashMap<>();
	private final Object fileLock = new Object();
	private final ScheduledExecutorService cleanupScheduler = new ScheduledThreadPoolExecutor(
			1, Thread.ofVirtual().name("download-cleanup-", 0).factory());

	private static final long COMPLETED_TASK_TTL_MS = 7L * 24 * 60 * 60 * 1000;
	private static final long RETRY_INTERVAL_MS = 5_000;

	/**
	 * 失败任务自动重试上限（通常为服务商 429/限流，无限重试只会加重封禁）
	 */
	private static final int MAX_AUTO_RETRY_ATTEMPTS = 3;

	/**
	 * 失败任务的自动重试状态：已尝试次数 + 下次允许重试时间（指数退避）
	 */
	private final Map<String, RetryState> retryStates = new ConcurrentHashMap<>();

	private static final class RetryState {
		final int attempts;
		final long nextRetryAt;

		RetryState(int attempts, long nextRetryAt) {
			this.attempts = attempts;
			this.nextRetryAt = nextRetryAt;
		}
	}

	private static long retryDelayMs(int attempts) {
		switch (attempts) {
		case 1:
			return 30_000;
		case 2:
			return 120_000;
		default:
			return 600_000;
		}
	}

	private DownloadTaskManager(Path cacheFile, int maxConcurrentTasks) throws IOException {
		Objects.requireNonNull(cacheFile, "cacheFile");
		if (maxConcurrentTasks < 1) {
			throw new IllegalArgumentException("maxConcurrentTasks must be >= 1");
		}
		this.cacheFile = cacheFile;
		this.workerPool = Executors.newFixedThreadPool(maxConcurrentTasks);
		loadFromCache();
		addProgressListener(new DownloadWebSocketListener());
		this.cleanupScheduler.scheduleWithFixedDelay(this::cleanupStaleTasks, 5, 5, TimeUnit.MINUTES);
		this.cleanupScheduler.scheduleWithFixedDelay(this::retryFailedTasks, RETRY_INTERVAL_MS, RETRY_INTERVAL_MS, TimeUnit.MILLISECONDS);
	}

	public DownloadTaskInfo createTask(String sourceUrl, Path targetFile, int threadCount) throws IOException {
		Objects.requireNonNull(sourceUrl, "sourceUrl");
		Objects.requireNonNull(targetFile, "targetFile");
		if (threadCount < 1) {
			throw new IllegalArgumentException("threadCount must be >= 1");
		}
		long now = System.currentTimeMillis();
		String taskId = UUID.randomUUID().toString();
		DownloadTaskInfo task = new DownloadTaskInfo(taskId, sourceUrl, targetFile.toString(), threadCount, DownloadTaskStatus.PENDING,
				now, now, null, -1L, 0L, threadCount, 0, 0D, null);
		this.taskStore.put(taskId, task);
		persistToCache();
		notifyStateChanged(task.copy(), null, DownloadTaskStatus.PENDING);
		return task.copy();
	}

	public DownloadTaskInfo startTask(String taskId) throws IOException {
		// 用户手动启动/恢复时重置自动重试计数
		this.retryStates.remove(taskId);
		DownloadTaskInfo task = requireTask(taskId);
		DownloadTaskStatus oldStatus;
		synchronized (task) {
			if (task.getStatus() == DownloadTaskStatus.RUNNING) {
				return task.copy();
			}
			if (task.getStatus() == DownloadTaskStatus.COMPLETED) {
				return task.copy();
			}
			oldStatus = task.getStatus();
			task.setStatus(DownloadTaskStatus.RUNNING);
			task.setErrorMessage(null);
			task.setPartsTotal(task.getThreadCount());
			task.setUpdatedAt(System.currentTimeMillis());
			persistToCache();
		}
		notifyStateChanged(task.copy(), oldStatus, DownloadTaskStatus.RUNNING);
		if (oldStatus == DownloadTaskStatus.PAUSED) {
			notifyTaskResumed(task.copy());
		}

		NettyHttpDownloader downloader = new NettyHttpDownloader(task.getThreadCount());
		downloader.setProgressListener((downloadedBytes, totalBytes, partsCompleted, partsTotal) -> {
			DownloadTaskInfo snapshot;
			DownloadTaskProgress progress;
			synchronized (task) {
				task.setDownloadedBytes(downloadedBytes);
				task.setTotalBytes(totalBytes);
				task.setPartsCompleted(partsCompleted);
				task.setPartsTotal(partsTotal);
				task.setProgressRatio(totalBytes > 0 ? (double) downloadedBytes / (double) totalBytes : 0D);
				task.setUpdatedAt(System.currentTimeMillis());
				snapshot = task.copy();
				progress = new DownloadTaskProgress(task.getDownloadedBytes(), task.getTotalBytes(), task.getPartsCompleted(),
						task.getPartsTotal(), task.getProgressRatio());
			}
			notifyProgressUpdated(snapshot, progress);
		});
		Path targetPath = Path.of(task.getTargetPath());
		Future<?> future = this.workerPool.submit(() -> {
			try (downloader) {
				NettyHttpDownloader.DownloadResult result = downloader.download(task.getSourceUrl(), targetPath);
				DownloadTaskInfo snapshot;
				synchronized (task) {
					task.setStatus(DownloadTaskStatus.COMPLETED);
					task.setFinalUrl(result.finalUrl());
					task.setTotalBytes(result.contentLength());
					task.setDownloadedBytes(result.contentLength());
					task.setPartsCompleted(result.parts());
					task.setPartsTotal(result.parts());
					task.setProgressRatio(1D);
					task.setErrorMessage(null);
					task.setUpdatedAt(System.currentTimeMillis());
					snapshot = task.copy();
				}
				this.retryStates.remove(task.getTaskId());
				notifyStateChanged(snapshot, DownloadTaskStatus.RUNNING, DownloadTaskStatus.COMPLETED);
				try {
					handlePostDownloadExtraction(snapshot);
				} catch (Exception ex) {
					// Non-fatal: extraction failure shouldn't mark the download as failed
				}
				// Clean up llama.cpp download tasks after extraction
				try {
					if (targetPath.toString().contains("llamacpp")) {
						this.taskStore.remove(task.getTaskId());
						persistToCache();
					}
				} catch (Exception ignored) {
				}
			} catch (IOException e) {
				DownloadTaskInfo snapshot;
				DownloadTaskStatus newStatus;
				synchronized (task) {
					if (downloader.isStopRequested() || isPauseException(e)) {
						task.setStatus(DownloadTaskStatus.PAUSED);
						task.setErrorMessage("任务已暂停");
						newStatus = DownloadTaskStatus.PAUSED;
					} else {
						task.setStatus(DownloadTaskStatus.FAILED);
						task.setErrorMessage(e.getMessage());
						newStatus = DownloadTaskStatus.FAILED;
					}
					task.setUpdatedAt(System.currentTimeMillis());
					snapshot = task.copy();
				}
				notifyStateChanged(snapshot, DownloadTaskStatus.RUNNING, newStatus);
				if (newStatus == DownloadTaskStatus.PAUSED) {
					notifyTaskPaused(snapshot);
				} else {
					notifyTaskFailed(snapshot, e.getMessage());
				}
			} finally {
				this.runtimeStore.remove(task.getTaskId());
				try {
					persistToCache();
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
		});
		this.runtimeStore.put(taskId, new RuntimeTaskContext(downloader, future));
		return task.copy();
	}

	public DownloadTaskInfo pauseTask(String taskId) throws IOException {
		DownloadTaskInfo task = requireTask(taskId);
		RuntimeTaskContext context = this.runtimeStore.get(taskId);
		if (context != null) {
			context.downloader.requestStop();
			context.future.cancel(true);
		}
		synchronized (task) {
			if (task.getStatus() == DownloadTaskStatus.PENDING || task.getStatus() == DownloadTaskStatus.RUNNING) {
				DownloadTaskStatus oldStatus = task.getStatus();
				task.setStatus(DownloadTaskStatus.PAUSED);
				task.setErrorMessage("任务已暂停");
				task.setUpdatedAt(System.currentTimeMillis());
				persistToCache();
				DownloadTaskInfo snapshot = task.copy();
				notifyStateChanged(snapshot, oldStatus, DownloadTaskStatus.PAUSED);
				notifyTaskPaused(snapshot);
			}
			return task.copy();
		}
	}

	public boolean deleteTask(String taskId, boolean deleteLocalFile) throws IOException {
		DownloadTaskInfo task = this.taskStore.get(taskId);
		if (task == null) {
			return false;
		}
		pauseTask(taskId);
		this.runtimeStore.remove(taskId);
		this.retryStates.remove(taskId);
		DownloadTaskInfo removed = this.taskStore.remove(taskId);
		persistToCache();
		if (removed != null) {
			notifyTaskFailed(removed.copy(), "任务已删除");
		}
		if (deleteLocalFile && removed != null) {
			deleteTaskFiles(removed);
		}
		return true;
	}

	public Optional<DownloadTaskInfo> getTask(String taskId) {
		DownloadTaskInfo task = this.taskStore.get(taskId);
		if (task == null) {
			return Optional.empty();
		}
		return Optional.of(task.copy());
	}

	public List<DownloadTaskInfo> listTasks() {
		List<DownloadTaskInfo> result = new ArrayList<>();
		for (DownloadTaskInfo task : this.taskStore.values()) {
			result.add(task.copy());
		}
		result.sort(Comparator.comparingLong(DownloadTaskInfo::getCreatedAt));
		return result;
	}

	public String addProgressListener(DownloadProgressListener listener) {
		Objects.requireNonNull(listener, "listener");
		String listenerId = UUID.randomUUID().toString();
		this.listeners.put(listenerId, listener);
		return listenerId;
	}

	public boolean removeProgressListener(String listenerId) {
		return this.listeners.remove(listenerId) != null;
	}

	@Override
	public void close() {
		this.cleanupScheduler.shutdownNow();
		for (RuntimeTaskContext context : this.runtimeStore.values()) {
			context.downloader.requestStop();
			context.future.cancel(true);
		}
		this.runtimeStore.clear();
		this.workerPool.shutdownNow();
	}

	private void cleanupStaleTasks() {
		try {
			long threshold = System.currentTimeMillis() - COMPLETED_TASK_TTL_MS;
			List<String> toRemove = new ArrayList<>();
			for (Map.Entry<String, DownloadTaskInfo> entry : this.taskStore.entrySet()) {
				DownloadTaskInfo task = entry.getValue();
				// Only clean up completed tasks, failed tasks are preserved for user recovery
				if (task.getStatus() == DownloadTaskStatus.COMPLETED
						&& task.getUpdatedAt() < threshold) {
					toRemove.add(entry.getKey());
				}
			}
			if (!toRemove.isEmpty()) {
				for (String taskId : toRemove) {
					this.taskStore.remove(taskId);
					this.runtimeStore.remove(taskId);
				}
				persistToCache();
				logger.debug("Cleaned up {} completed download tasks", toRemove.size());
			}
		} catch (Exception e) {
			logger.warn("Failed to cleanup stale download tasks", e);
		}
	}

	private void retryFailedTasks() {
		try {
			long now = System.currentTimeMillis();
			for (DownloadTaskInfo task : this.taskStore.values()) {
				if (task.getStatus() != DownloadTaskStatus.FAILED) {
					continue;
				}
				RetryState state = this.retryStates.get(task.getTaskId());
				if (state != null && (state.attempts >= MAX_AUTO_RETRY_ATTEMPTS || now < state.nextRetryAt)) {
					continue;
				}
				int attempts = state == null ? 1 : state.attempts + 1;
				long delay = retryDelayMs(attempts);
				try {
					startTask(task.getTaskId());
				} catch (IOException e) {
					// ignore - task may have been deleted or is already running
				}
				// startTask 会清空重试状态，这里以递增后的计数重建
				this.retryStates.put(task.getTaskId(), new RetryState(attempts, now + delay));
				logger.info("自动重试下载任务 {}（第 {}/{} 次，{}ms 后可再次重试）", task.getTaskId(), attempts,
						MAX_AUTO_RETRY_ATTEMPTS, delay);
			}
		} catch (Exception e) {
			logger.warn("Failed to retry failed tasks", e);
		}
	}

	private DownloadTaskInfo requireTask(String taskId) {
		Objects.requireNonNull(taskId, "taskId");
		DownloadTaskInfo task = this.taskStore.get(taskId);
		if (task == null) {
			throw new IllegalArgumentException("任务不存在: " + taskId);
		}
		return task;
	}

	private void deleteTaskFiles(DownloadTaskInfo task) {
		Path target = Path.of(task.getTargetPath());
		Path temp = target.resolveSibling(target.getFileName() + ".downloading");
		Path metadata = target.resolveSibling(target.getFileName() + ".downloading.meta");
		try {
			Files.deleteIfExists(metadata);
		} catch (IOException e) {
			// ignore
		}
		try {
			Files.deleteIfExists(temp);
		} catch (IOException e) {
			// ignore
		}
		try {
			Files.deleteIfExists(target);
		} catch (IOException e) {
			// ignore — file may be in use (e.g. loaded model)
		}
	}

	private boolean isPauseException(IOException e) {
		if (e.getMessage() == null) {
			return false;
		}
		return e.getMessage().contains("暂停") || e.getMessage().contains("中断");
	}

	private void notifyStateChanged(DownloadTaskInfo task, DownloadTaskStatus oldState, DownloadTaskStatus newState) {
		for (DownloadProgressListener listener : this.listeners.values()) {
			try {
				listener.onStateChanged(task, oldState, newState);
			} catch (Exception ignored) {
			}
		}
	}

	private void notifyProgressUpdated(DownloadTaskInfo task, DownloadTaskProgress progress) {
		for (DownloadProgressListener listener : this.listeners.values()) {
			try {
				listener.onProgressUpdated(task, progress);
			} catch (Exception ignored) {
			}
		}
	}

	private void notifyTaskFailed(DownloadTaskInfo task, String error) {
		for (DownloadProgressListener listener : this.listeners.values()) {
			try {
				listener.onTaskFailed(task, error);
			} catch (Exception ignored) {
			}
		}
	}

	private void notifyTaskPaused(DownloadTaskInfo task) {
		for (DownloadProgressListener listener : this.listeners.values()) {
			try {
				listener.onTaskPaused(task);
			} catch (Exception ignored) {
			}
		}
	}

	private void notifyTaskResumed(DownloadTaskInfo task) {
		for (DownloadProgressListener listener : this.listeners.values()) {
			try {
				listener.onTaskResumed(task);
			} catch (Exception ignored) {
			}
		}
	}

	private void handlePostDownloadExtraction(DownloadTaskInfo task) {
		Path target = Path.of(task.getTargetPath());
		String fileName = target.getFileName() == null ? "" : target.getFileName().toString().toLowerCase();
		if (!fileName.endsWith(".zip") && !fileName.endsWith(".tar.gz") && !fileName.endsWith(".tgz")) {
			return;
		}
		Path parent = target.getParent();
		if (parent == null) return;
		// Check if the target is under the llamacpp/ directory
		String parentPathStr = parent.toString();
		if (!parentPathStr.contains("llamacpp")) return;
		try {
			if (fileName.endsWith(".zip")) {
				extractZip(target, parent);
			} else {
				extractTarGz(target, parent);
			}
			// Delete the archive first so flatten sees only the extracted directory
			Files.deleteIfExists(target);
			// Flatten the single top-level directory created by the archive
			flattenSingleTopDir(parent);
			// Fallback: if llama-server is still not found, try flattening once more
			if (!hasLlamaServer(parent)) {
				flattenSingleTopDir(parent);
			}
		} catch (Exception e) {
			logger.warn("解压下载文件失败: {}", task.getTargetPath(), e);
		}
	}

	/**
	 * Flatten a single top-level directory. After extracting a release ZIP into
	 * llamacpp/{backendDir}/, the archive usually contains one top-level directory
	 * with the same name. Move its contents up so llama-server.exe ends up directly
	 * in the backend directory, allowing {@code scanLlamaCpp()} to discover it.
	 */
	private void flattenSingleTopDir(Path destDir) throws IOException {
		List<Path> entries = Files.list(destDir).collect(java.util.stream.Collectors.toList());
		if (entries.size() != 1) return;
		Path single = entries.get(0);
		if (!Files.isDirectory(single)) return;
		for (Path child : Files.list(single).collect(java.util.stream.Collectors.toList())) {
			try {
				Path tgt = destDir.resolve(child.getFileName());
				Files.move(child, tgt, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				logger.warn("移动文件失败: {} -> {}", child, destDir.resolve(child.getFileName()), e);
			}
		}
		Files.deleteIfExists(single);
	}

	private boolean hasLlamaServer(Path dir) throws IOException {
		for (Path p : Files.list(dir).collect(java.util.stream.Collectors.toList())) {
			String name = p.getFileName().toString().toLowerCase();
			if (name.equals("llama-server")) return true;
		}
		return false;
	}

	private void extractZip(Path zipFile, Path destDir) throws IOException {
		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(zipFile.toFile())) {
			java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
			while (entries.hasMoreElements()) {
				java.util.zip.ZipEntry entry = entries.nextElement();
				Path entryPath = destDir.resolve(entry.getName()).normalize();
				if (!entryPath.startsWith(destDir)) continue;
				if (entry.isDirectory()) {
					Files.createDirectories(entryPath);
				} else {
					Files.createDirectories(entryPath.getParent());
					try (java.io.InputStream in = zip.getInputStream(entry)) {
						Files.copy(in, entryPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
					}
				}
			}
		}
	}

	private void extractTarGz(Path tarGzFile, Path destDir) throws IOException {
		try (java.io.InputStream fis = Files.newInputStream(tarGzFile);
				java.util.zip.GZIPInputStream gis = new java.util.zip.GZIPInputStream(fis)) {
			extractTar(gis, destDir);
		}
	}

	private void extractTar(java.io.InputStream tarStream, Path destDir) throws IOException {
		byte[] header = new byte[512];
		String longName = null;
		while (true) {
			int read = readFully(tarStream, header);
			if (read == 0) break;
			if (isAllZeros(header)) break;
			String name = readTarHeaderName(header);
			long size = readTarHeaderSize(header);
			byte type = header[156];
			if (type == 'L') {
				// GNU LongName extension — read the name from the entry content
				if (size > 0) {
					byte[] nameBuf = new byte[(int) size];
					readFully(tarStream, nameBuf);
					longName = new String(nameBuf, java.nio.charset.StandardCharsets.UTF_8).trim();
					long padding = (512 - (size % 512)) % 512;
					if (padding > 0) consumeN(tarStream, padding);
				}
				continue;
			}
			if (type == 'x') {
				// pax extended header — skip
				consumeN(tarStream, size);
				long padding = (512 - (size % 512)) % 512;
				if (padding > 0) consumeN(tarStream, padding);
				continue;
			}
			String entryName = (name != null && !name.isEmpty()) ? name : longName;
			if (entryName == null || entryName.isEmpty()) {
				consumeN(tarStream, size);
				long padding = (512 - (size % 512)) % 512;
				if (padding > 0) consumeN(tarStream, padding);
				continue;
			}
			Path entryPath = destDir.resolve(entryName).normalize();
			if (!entryPath.startsWith(destDir)) {
				consumeN(tarStream, size);
				long padding = (512 - (size % 512)) % 512;
				if (padding > 0) consumeN(tarStream, padding);
				continue;
			}
			if (type == '5') {
				Files.createDirectories(entryPath);
			} else if (size > 0) {
				Files.createDirectories(entryPath.getParent());
				try (java.io.OutputStream out = Files.newOutputStream(entryPath)) {
					copyN(tarStream, out, size);
				}
				// Skip padding to align to 512-byte block boundary
				long padding = (512 - (size % 512)) % 512;
				if (padding > 0) consumeN(tarStream, padding);
			}
			// Reset longName after use
			longName = null;
		}
	}

	private int readFully(java.io.InputStream in, byte[] buf) throws IOException {
		int pos = 0;
		while (pos < buf.length) {
			int n = in.read(buf, pos, buf.length - pos);
			if (n == -1) break;
			pos += n;
		}
		return pos;
	}

	private boolean isAllZeros(byte[] buf) {
		for (byte b : buf) {
			if (b != 0) return false;
		}
		return true;
	}

	private String readTarHeaderName(byte[] header) {
		try {
			int end = 100;
			while (end > 0 && header[end - 1] == 0) end--;
			return new String(header, 0, end, java.nio.charset.StandardCharsets.US_ASCII).trim();
		} catch (Exception e) {
			return null;
		}
	}

	private long readTarHeaderSize(byte[] header) {
		try {
			String sizeStr = new String(header, 124, 12, java.nio.charset.StandardCharsets.US_ASCII).trim();
			if (sizeStr.isEmpty()) return 0;
			if (sizeStr.getBytes(java.nio.charset.StandardCharsets.US_ASCII)[0] == 0xFF || sizeStr.getBytes(java.nio.charset.StandardCharsets.US_ASCII)[0] == 0x80) {
				long val = 0;
				boolean negative = (header[124] & 0x80) != 0;
				for (int i = 125; i < 136; i++) {
					val = (val << 8) | (header[i] & 0xFF);
				}
				return negative ? -val : val;
			}
			return Long.parseLong(sizeStr, 8);
		} catch (Exception e) {
			return 0;
		}
	}

	private void consumeN(java.io.InputStream in, long n) throws IOException {
		byte[] buf = new byte[8192];
		while (n > 0) {
			int toRead = (int) Math.min(buf.length, n);
			int r = in.read(buf, 0, toRead);
			if (r == -1) break;
			n -= r;
		}
	}

	private void copyN(java.io.InputStream in, java.io.OutputStream out, long n) throws IOException {
		byte[] buf = new byte[8192];
		while (n > 0) {
			int toRead = (int) Math.min(buf.length, n);
			int r = in.read(buf, 0, toRead);
			if (r == -1) break;
			out.write(buf, 0, r);
			n -= r;
		}
	}

	private void loadFromCache() throws IOException {
		Path parent = this.cacheFile.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		if (!Files.exists(this.cacheFile) || Files.size(this.cacheFile) == 0) {
			return;
		}
		synchronized (this.fileLock) {
			String json = Files.readString(this.cacheFile);
			List<DownloadTaskInfo> tasks = GSON.fromJson(json, new TypeToken<List<DownloadTaskInfo>>(){}.getType());
			if (tasks == null) {
				return;
			}
			this.taskStore.clear();
			for (DownloadTaskInfo task : tasks) {
				if (task.getStatus() == DownloadTaskStatus.RUNNING) {
					task.setStatus(DownloadTaskStatus.PAUSED);
					task.setErrorMessage("程序重启后任务重置为暂停");
					task.setUpdatedAt(System.currentTimeMillis());
				}
				this.taskStore.put(task.getTaskId(), task);
			}
		}
	}

	private void persistToCache() throws IOException {
		synchronized (this.fileLock) {
			List<DownloadTaskInfo> tasks = new ArrayList<>(this.taskStore.values());
			String json = GSON.toJson(tasks);
			Files.writeString(this.cacheFile, json);
		}
	}

	private record RuntimeTaskContext(NettyHttpDownloader downloader, Future<?> future) {
	}
}
