package org.mark.test.mcp.tools.context;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.mark.llamacpp.server.tools.JsonUtil;

public class FileContextSummaryRepository {

	private final AtomicLong sequence;
	private final Path repositoryPath;

	public FileContextSummaryRepository() {
		this(Paths.get(System.getProperty("user.dir"), "context-summary"));
	}

	public FileContextSummaryRepository(Path repositoryPath) {
		this.repositoryPath = repositoryPath == null ? Paths.get(System.getProperty("user.dir"), "context-summary") : repositoryPath;
		ensureRepositoryDirectory();
		this.sequence = new AtomicLong(loadMaxSequence());
	}

	public synchronized ContextSummaryRecord save(ContextSummaryRecord record) {
		if (record == null) {
			return null;
		}
		long now = System.currentTimeMillis();
		ContextSummaryRecord target = copy(record);
		ContextSummaryRecord existing = null;
		if (target.getId() == null || target.getId().isBlank()) {
			target.setId(nextId());
			target.setCreatedAt(now);
		} else {
			existing = getById(target.getId());
			if (existing != null && target.getCreatedAt() <= 0) {
				target.setCreatedAt(existing.getCreatedAt());
			}
			if (target.getCreatedAt() <= 0) {
				target.setCreatedAt(now);
			}
		}
		target.setUpdatedAt(now);
		writeRecord(target);
		return copy(target);
	}

	public synchronized ContextSummaryRecord getById(String id) {
		String normalizedId = normalizeId(id);
		if (normalizedId == null) {
			return null;
		}
		Path filePath = resolveFilePath(normalizedId);
		if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
			return null;
		}
		try {
			String json = Files.readString(filePath, StandardCharsets.UTF_8);
			ContextSummaryRecord record = JsonUtil.fromJson(json, ContextSummaryRecord.class);
			if (record == null) {
				return null;
			}
			if (record.getId() == null || record.getId().isBlank()) {
				record.setId(normalizedId);
			}
			return copy(record);
		} catch (Exception e) {
			return null;
		}
	}

	public synchronized ContextSummaryRecord getLatest() {
		return readAllRecords().stream().sorted(Comparator.comparingLong(ContextSummaryRecord::getUpdatedAt).reversed()).findFirst().map(this::copy)
				.orElse(null);
	}

	private void ensureRepositoryDirectory() {
		try {
			Files.createDirectories(this.repositoryPath);
		} catch (IOException e) {
			throw new IllegalStateException("无法创建上下文总结目录: " + this.repositoryPath, e);
		}
	}

	private long loadMaxSequence() {
		long max = 0;
		for (ContextSummaryRecord record : readAllRecords()) {
			long sequenceValue = parseSequence(record.getId());
			if (sequenceValue > max) {
				max = sequenceValue;
			}
		}
		return max;
	}

	private String nextId() {
		String nextId;
		do {
			nextId = "ctx_" + this.sequence.incrementAndGet();
		} while (Files.exists(resolveFilePath(nextId)));
		return nextId;
	}

	private List<ContextSummaryRecord> readAllRecords() {
		if (!Files.exists(this.repositoryPath) || !Files.isDirectory(this.repositoryPath)) {
			return List.of();
		}
		List<ContextSummaryRecord> records = new ArrayList<>();
		try (Stream<Path> stream = Files.list(this.repositoryPath)) {
			stream.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().endsWith(".json")).forEach(path -> {
				ContextSummaryRecord record = readRecord(path);
				if (record != null) {
					records.add(record);
				}
			});
		} catch (IOException e) {
			throw new IllegalStateException("读取上下文总结目录失败: " + this.repositoryPath, e);
		}
		return records;
	}

	private ContextSummaryRecord readRecord(Path path) {
		try {
			String json = Files.readString(path, StandardCharsets.UTF_8);
			ContextSummaryRecord record = JsonUtil.fromJson(json, ContextSummaryRecord.class);
			if (record == null) {
				return null;
			}
			return copy(record);
		} catch (Exception e) {
			return null;
		}
	}

	private void writeRecord(ContextSummaryRecord record) {
		Path target = resolveFilePath(record.getId());
		Path temp = target.resolveSibling(target.getFileName().toString() + ".tmp");
		String json = JsonUtil.toJson(record);
		try {
			Files.writeString(temp, json, StandardCharsets.UTF_8);
			try {
				Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			throw new IllegalStateException("保存上下文总结失败: " + record.getId(), e);
		}
	}

	private Path resolveFilePath(String id) {
		return this.repositoryPath.resolve(normalizeFileName(id) + ".json");
	}

	private String normalizeFileName(String id) {
		String value = normalizeId(id);
		if (value == null) {
			throw new IllegalArgumentException("总结ID不能为空");
		}
		return value.replaceAll("[\\\\/:*?\"<>|]", "_");
	}

	private String normalizeId(String id) {
		if (id == null) {
			return null;
		}
		String value = id.trim();
		return value.isEmpty() ? null : value;
	}

	private long parseSequence(String id) {
		String normalizedId = normalizeId(id);
		if (normalizedId == null || !normalizedId.startsWith("ctx_")) {
			return 0;
		}
		try {
			return Long.parseLong(normalizedId.substring(4));
		} catch (Exception e) {
			return 0;
		}
	}

	private ContextSummaryRecord copy(ContextSummaryRecord source) {
		ContextSummaryRecord target = new ContextSummaryRecord();
		target.setId(source.getId());
		target.setTopic(source.getTopic());
		target.setScene(source.getScene());
		target.setSourceHint(source.getSourceHint());
		target.setSummary(source.getSummary());
		target.setKeyPoints(source.getKeyPoints());
		target.setPendingItems(source.getPendingItems());
		target.setNextSuggestion(source.getNextSuggestion());
		target.setSuggestedPrompt(source.getSuggestedPrompt());
		target.setMood(source.getMood());
		target.setCreatedAt(source.getCreatedAt());
		target.setUpdatedAt(source.getUpdatedAt());
		return target;
	}
}
