package org.mark.test.mcp.tools.experience;

import java.util.ArrayList;
import java.util.List;

public class ExperienceRecord {

	private String id;
	private String taskType;
	private String context;
	private String symptom;
	private String rootCause;
	private String fix;
	private String antiPattern;
	private List<String> tags = new ArrayList<>();
	private String severity;
	private int helpfulScore;
	private int successCount;
	private int failCount;
	private long createdAt;
	private long updatedAt;

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getTaskType() {
		return taskType;
	}

	public void setTaskType(String taskType) {
		this.taskType = taskType;
	}

	public String getContext() {
		return context;
	}

	public void setContext(String context) {
		this.context = context;
	}

	public String getSymptom() {
		return symptom;
	}

	public void setSymptom(String symptom) {
		this.symptom = symptom;
	}

	public String getRootCause() {
		return rootCause;
	}

	public void setRootCause(String rootCause) {
		this.rootCause = rootCause;
	}

	public String getFix() {
		return fix;
	}

	public void setFix(String fix) {
		this.fix = fix;
	}

	public String getAntiPattern() {
		return antiPattern;
	}

	public void setAntiPattern(String antiPattern) {
		this.antiPattern = antiPattern;
	}

	public List<String> getTags() {
		return tags;
	}

	public void setTags(List<String> tags) {
		this.tags = tags == null ? new ArrayList<>() : new ArrayList<>(tags);
	}

	public String getSeverity() {
		return severity;
	}

	public void setSeverity(String severity) {
		this.severity = severity;
	}

	public int getHelpfulScore() {
		return helpfulScore;
	}

	public void setHelpfulScore(int helpfulScore) {
		this.helpfulScore = helpfulScore;
	}

	public int getSuccessCount() {
		return successCount;
	}

	public void setSuccessCount(int successCount) {
		this.successCount = successCount;
	}

	public int getFailCount() {
		return failCount;
	}

	public void setFailCount(int failCount) {
		this.failCount = failCount;
	}

	public long getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(long createdAt) {
		this.createdAt = createdAt;
	}

	public long getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(long updatedAt) {
		this.updatedAt = updatedAt;
	}
}
