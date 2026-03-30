package org.mark.test.mcp.tools.experience;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public class ExperienceMatcher {

	public List<ExperienceMatchResult> match(List<ExperienceRecord> records, String taskType, String context, int topK) {
		if (records == null || records.isEmpty()) {
			return List.of();
		}
		String task = normalize(taskType);
		List<String> contextTerms = splitTerms(context);
		int limit = topK <= 0 ? 3 : Math.min(topK, 20);
		return records.stream().map(r -> score(r, task, contextTerms)).filter(it -> it.getScore() > 0).sorted(scoreComparator()).limit(limit)
				.collect(Collectors.toList());
	}

	private Comparator<ExperienceMatchResult> scoreComparator() {
		return Comparator.comparingDouble(ExperienceMatchResult::getScore).reversed().thenComparingLong(ExperienceMatchResult::getUpdatedAt).reversed();
	}

	private ExperienceMatchResult score(ExperienceRecord record, String task, List<String> contextTerms) {
		double score = 0;
		List<String> reasons = new ArrayList<>();
		String recordTaskType = normalize(record.getTaskType());
		if (task != null && task.equals(recordTaskType)) {
			score += 5;
			reasons.add("taskType匹配");
		}
		String haystack = normalize(join(record.getSymptom(), record.getRootCause(), record.getFix(), record.getContext(),
				record.getAntiPattern(), String.join(" ", record.getTags())));
		int hitCount = 0;
		for (String term : contextTerms) {
			if (term.length() < 2) {
				continue;
			}
			if (haystack != null && haystack.contains(term)) {
				score += 1.5;
				hitCount++;
			}
		}
		if (hitCount > 0) {
			reasons.add("命中上下文词: " + hitCount);
		}
		score += Math.max(0, record.getHelpfulScore()) * 0.2;
		score += Math.max(0, record.getSuccessCount()) * 0.1;
		score -= Math.max(0, record.getFailCount()) * 0.1;
		return new ExperienceMatchResult(record, score, reasons);
	}

	private List<String> splitTerms(String input) {
		String normalized = normalize(input);
		if (normalized == null) {
			return List.of();
		}
		String[] arr = normalized.split("[\\s,，;；|/]+");
		Set<String> set = new LinkedHashSet<>();
		for (String part : arr) {
			if (part != null) {
				String p = part.trim();
				if (!p.isEmpty()) {
					set.add(p);
				}
			}
		}
		return new ArrayList<>(set);
	}

	private String join(String... texts) {
		StringBuilder sb = new StringBuilder();
		if (texts == null) {
			return "";
		}
		for (String text : texts) {
			if (text == null || text.isBlank()) {
				continue;
			}
			if (sb.length() > 0) {
				sb.append(' ');
			}
			sb.append(text.trim());
		}
		return sb.toString();
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

	public static class ExperienceMatchResult {

		private final ExperienceRecord record;
		private final double score;
		private final List<String> reasons;

		public ExperienceMatchResult(ExperienceRecord record, double score, List<String> reasons) {
			this.record = record;
			this.score = score;
			this.reasons = reasons == null ? List.of() : List.copyOf(reasons);
		}

		public ExperienceRecord getRecord() {
			return record;
		}

		public double getScore() {
			return score;
		}

		public List<String> getReasons() {
			return reasons;
		}

		public long getUpdatedAt() {
			return record == null ? 0L : record.getUpdatedAt();
		}
	}
}
