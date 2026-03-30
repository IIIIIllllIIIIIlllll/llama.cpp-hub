package org.mark.test.mcp.tools.experience;

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
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.mark.llamacpp.server.tools.JsonUtil;

public class FileExperienceRepository implements ExperienceRepository {

	private final AtomicLong sequence;
	private final Path repositoryPath;

	public FileExperienceRepository() {
		this(Paths.get(System.getProperty("user.dir"), "experience"));
	}

	public FileExperienceRepository(Path repositoryPath) {
		this.repositoryPath = repositoryPath == null ? Paths.get(System.getProperty("user.dir"), "experience") : repositoryPath;
		ensureRepositoryDirectory();
		this.sequence = new AtomicLong(loadMaxSequence());
	}

	@Override
	public synchronized ExperienceRecord save(ExperienceRecord record) {
		if (record == null) {
			return null;
		}
		long now = System.currentTimeMillis();
		ExperienceRecord target = copy(record);
		ExperienceRecord existing = null;
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

	@Override
	public synchronized ExperienceRecord getById(String id) {
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
			ExperienceRecord record = JsonUtil.fromJson(json, ExperienceRecord.class);
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

	@Override
	public synchronized List<ExperienceRecord> list(String taskType, List<String> tags, int limit) {
		int actualLimit = limit <= 0 ? 20 : Math.min(limit, 200);
		String normalizedTaskType = normalize(taskType);
		List<String> normalizedTags = normalizeTags(tags);
		return readAllRecords().stream().filter(it -> matchTaskType(it, normalizedTaskType)).filter(it -> matchTags(it, normalizedTags))
				.sorted(Comparator.comparingLong(ExperienceRecord::getUpdatedAt).reversed()).limit(actualLimit).map(this::copy)
				.collect(Collectors.toList());
	}

	@Override
	public synchronized List<ExperienceRecord> listAll() {
		return readAllRecords().stream().sorted(Comparator.comparingLong(ExperienceRecord::getUpdatedAt).reversed()).map(this::copy)
				.collect(Collectors.toList());
	}

	private void ensureRepositoryDirectory() {
		try {
			Files.createDirectories(this.repositoryPath);
		} catch (IOException e) {
			throw new IllegalStateException("无法创建经验目录: " + this.repositoryPath, e);
		}
	}

	private long loadMaxSequence() {
		long max = 0;
		for (ExperienceRecord record : readAllRecords()) {
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
			nextId = "exp_" + this.sequence.incrementAndGet();
		} while (Files.exists(resolveFilePath(nextId)));
		return nextId;
	}

	private List<ExperienceRecord> readAllRecords() {
		if (!Files.exists(this.repositoryPath) || !Files.isDirectory(this.repositoryPath)) {
			return List.of();
		}
		List<ExperienceRecord> records = new ArrayList<>();
		try (Stream<Path> stream = Files.list(this.repositoryPath)) {
			stream.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().endsWith(".json")).forEach(path -> {
				ExperienceRecord record = readRecord(path);
				if (record != null) {
					records.add(record);
				}
			});
		} catch (IOException e) {
			throw new IllegalStateException("读取经验目录失败: " + this.repositoryPath, e);
		}
		return records;
	}

	private ExperienceRecord readRecord(Path path) {
		try {
			String json = Files.readString(path, StandardCharsets.UTF_8);
			ExperienceRecord record = JsonUtil.fromJson(json, ExperienceRecord.class);
			if (record == null) {
				return null;
			}
			return copy(record);
		} catch (Exception e) {
			return null;
		}
	}

	private void writeRecord(ExperienceRecord record) {
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
			throw new IllegalStateException("保存经验失败: " + record.getId(), e);
		}
	}

	private Path resolveFilePath(String id) {
		return this.repositoryPath.resolve(normalizeFileName(id) + ".json");
	}

	private String normalizeFileName(String id) {
		String value = normalizeId(id);
		if (value == null) {
			throw new IllegalArgumentException("经验ID不能为空");
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
		if (normalizedId == null || !normalizedId.startsWith("exp_")) {
			return 0;
		}
		try {
			return Long.parseLong(normalizedId.substring(4));
		} catch (Exception e) {
			return 0;
		}
	}

	private boolean matchTaskType(ExperienceRecord item, String normalizedTaskType) {
		if (normalizedTaskType == null) {
			return true;
		}
		String itemTaskType = normalize(item.getTaskType());
		return itemTaskType != null && itemTaskType.equals(normalizedTaskType);
	}

	private boolean matchTags(ExperienceRecord item, List<String> normalizedTags) {
		if (normalizedTags.isEmpty()) {
			return true;
		}
		List<String> itemTags = normalizeTags(item.getTags());
		for (String tag : normalizedTags) {
			if (itemTags.contains(tag)) {
				return true;
			}
		}
		return false;
	}

	private List<String> normalizeTags(List<String> tags) {
		if (tags == null || tags.isEmpty()) {
			return List.of();
		}
		List<String> out = new ArrayList<>();
		for (String tag : tags) {
			String value = normalize(tag);
			if (value != null && !out.contains(value)) {
				out.add(value);
			}
		}
		return out;
	}

	private String normalize(String text) {
		if (text == null) {
			return null;
		}
		String value = text.trim();
		if (value.isEmpty()) {
			return null;
		}
		return value.toLowerCase(Locale.ROOT);
	}

	private ExperienceRecord copy(ExperienceRecord source) {
		ExperienceRecord target = new ExperienceRecord();
		target.setId(source.getId());
		target.setTaskType(source.getTaskType());
		target.setContext(source.getContext());
		target.setSymptom(source.getSymptom());
		target.setRootCause(source.getRootCause());
		target.setFix(source.getFix());
		target.setAntiPattern(source.getAntiPattern());
		target.setTags(source.getTags());
		target.setSeverity(source.getSeverity());
		target.setHelpfulScore(source.getHelpfulScore());
		target.setSuccessCount(source.getSuccessCount());
		target.setFailCount(source.getFailCount());
		target.setCreatedAt(source.getCreatedAt());
		target.setUpdatedAt(source.getUpdatedAt());
		return target;
	}
}
