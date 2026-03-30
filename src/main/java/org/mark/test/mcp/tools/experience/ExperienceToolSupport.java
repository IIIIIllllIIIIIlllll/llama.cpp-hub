package org.mark.test.mcp.tools.experience;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.mark.llamacpp.server.tools.JsonUtil;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public final class ExperienceToolSupport {

	private ExperienceToolSupport() {
	}

	public static String getString(JsonObject arguments, String key) {
		String value = JsonUtil.getJsonString(arguments, key, "");
		return value == null ? "" : value.trim();
	}

	public static int getInt(JsonObject arguments, String key, int fallback) {
		Integer value = JsonUtil.getJsonInt(arguments, key, fallback);
		return value == null ? fallback : value.intValue();
	}

	public static List<String> getTags(JsonObject arguments) {
		if (arguments == null || !arguments.has("tags")) {
			return List.of();
		}
		JsonElement el = arguments.get("tags");
		List<String> values = JsonUtil.getJsonStringList(el);
		if (values == null || values.isEmpty()) {
			return List.of();
		}
		Set<String> set = new LinkedHashSet<>();
		for (String value : values) {
			if (value == null || value.isBlank()) {
				continue;
			}
			set.add(value.trim());
		}
		return new ArrayList<>(set);
	}

	public static ExperienceRecord toRecord(JsonObject arguments) {
		ExperienceRecord record = new ExperienceRecord();
		record.setTaskType(getString(arguments, "taskType"));
		record.setContext(getString(arguments, "context"));
		record.setSymptom(getString(arguments, "symptom"));
		record.setRootCause(getString(arguments, "rootCause"));
		record.setFix(getString(arguments, "fix"));
		record.setAntiPattern(getString(arguments, "antiPattern"));
		record.setTags(getTags(arguments));
		record.setSeverity(getString(arguments, "severity"));
		return record;
	}
}
