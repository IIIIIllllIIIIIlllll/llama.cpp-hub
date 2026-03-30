package org.mark.test.mcp.tools.context;

import java.util.ArrayList;
import java.util.List;

public class ContextSummaryRecord {

	private String id;
	private String topic;
	private String scene;
	private String sourceHint;
	private String summary;
	private List<String> keyPoints = new ArrayList<>();
	private List<String> pendingItems = new ArrayList<>();
	private String nextSuggestion;
	private String suggestedPrompt;
	private String mood;
	private long createdAt;
	private long updatedAt;

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getTopic() {
		return topic;
	}

	public void setTopic(String topic) {
		this.topic = topic;
	}

	public String getScene() {
		return scene;
	}

	public void setScene(String scene) {
		this.scene = scene;
	}

	public String getSourceHint() {
		return sourceHint;
	}

	public void setSourceHint(String sourceHint) {
		this.sourceHint = sourceHint;
	}

	public String getSummary() {
		return summary;
	}

	public void setSummary(String summary) {
		this.summary = summary;
	}

	public List<String> getKeyPoints() {
		return keyPoints;
	}

	public void setKeyPoints(List<String> keyPoints) {
		this.keyPoints = keyPoints == null ? new ArrayList<>() : new ArrayList<>(keyPoints);
	}

	public List<String> getPendingItems() {
		return pendingItems;
	}

	public void setPendingItems(List<String> pendingItems) {
		this.pendingItems = pendingItems == null ? new ArrayList<>() : new ArrayList<>(pendingItems);
	}

	public String getNextSuggestion() {
		return nextSuggestion;
	}

	public void setNextSuggestion(String nextSuggestion) {
		this.nextSuggestion = nextSuggestion;
	}

	public String getSuggestedPrompt() {
		return suggestedPrompt;
	}

	public void setSuggestedPrompt(String suggestedPrompt) {
		this.suggestedPrompt = suggestedPrompt;
	}

	public String getMood() {
		return mood;
	}

	public void setMood(String mood) {
		this.mood = mood;
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
