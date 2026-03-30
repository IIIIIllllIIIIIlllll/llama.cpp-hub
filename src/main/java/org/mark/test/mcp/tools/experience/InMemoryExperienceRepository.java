package org.mark.test.mcp.tools.experience;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class InMemoryExperienceRepository implements ExperienceRepository {

	private final AtomicLong sequence = new AtomicLong(0);
	private final Map<String, ExperienceRecord> store = new ConcurrentHashMap<>();

	@Override
	public ExperienceRecord save(ExperienceRecord record) {
		if (record == null) {
			return null;
		}
		long now = System.currentTimeMillis();
		if (record.getId() == null || record.getId().isBlank()) {
			record.setId("exp_" + sequence.incrementAndGet());
			record.setCreatedAt(now);
		}
		record.setUpdatedAt(now);
		this.store.put(record.getId(), copy(record));
		return copy(record);
	}

	@Override
	public ExperienceRecord getById(String id) {
		if (id == null || id.isBlank()) {
			return null;
		}
		ExperienceRecord found = this.store.get(id.trim());
		return found == null ? null : copy(found);
	}

	@Override
	public List<ExperienceRecord> list(String taskType, List<String> tags, int limit) {
		int actualLimit = limit <= 0 ? 20 : Math.min(limit, 200);
		String normalizedTaskType = normalize(taskType);
		List<String> normalizedTags = normalizeTags(tags);
		return this.store.values().stream().filter(it -> matchTaskType(it, normalizedTaskType)).filter(it -> matchTags(it, normalizedTags))
				.sorted(Comparator.comparingLong(ExperienceRecord::getUpdatedAt).reversed()).limit(actualLimit).map(this::copy)
				.collect(Collectors.toList());
	}

	@Override
	public List<ExperienceRecord> listAll() {
		return this.store.values().stream().sorted(Comparator.comparingLong(ExperienceRecord::getUpdatedAt).reversed()).map(this::copy)
				.collect(Collectors.toList());
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
			String t = normalize(tag);
			if (t != null && !out.contains(t)) {
				out.add(t);
			}
		}
		return out;
	}

	private String normalize(String text) {
		if (text == null) {
			return null;
		}
		String s = text.trim();
		if (s.isEmpty()) {
			return null;
		}
		return s.toLowerCase(Locale.ROOT);
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
