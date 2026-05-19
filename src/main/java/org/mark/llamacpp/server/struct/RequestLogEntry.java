package org.mark.llamacpp.server.struct;

public class RequestLogEntry {
	private String requestId;
	private String modelId;
	private String endpoint;
	private long startTime;
	private long elapsedMs;
	private int cacheTokens;
	private int promptTokens;
	private int predictedTokens;
	private int totalTokens;
	private double promptPerSecond;
	private double predictedPerSecond;
	private int draftTokens;
	private int draftAccepted;

	public String getRequestId() {
		return requestId;
	}

	public void setRequestId(String requestId) {
		this.requestId = requestId;
	}

	public String getModelId() {
		return modelId;
	}

	public void setModelId(String modelId) {
		this.modelId = modelId;
	}

	public String getEndpoint() {
		return endpoint;
	}

	public void setEndpoint(String endpoint) {
		this.endpoint = endpoint;
	}

	public long getStartTime() {
		return startTime;
	}

	public void setStartTime(long startTime) {
		this.startTime = startTime;
	}

	public long getElapsedMs() {
		return elapsedMs;
	}

	public void setElapsedMs(long elapsedMs) {
		this.elapsedMs = elapsedMs;
	}

	public int getCacheTokens() {
		return cacheTokens;
	}

	public void setCacheTokens(int cacheTokens) {
		this.cacheTokens = cacheTokens;
	}

	public int getPromptTokens() {
		return promptTokens;
	}

	public void setPromptTokens(int promptTokens) {
		this.promptTokens = promptTokens;
	}

	public int getPredictedTokens() {
		return predictedTokens;
	}

	public void setPredictedTokens(int predictedTokens) {
		this.predictedTokens = predictedTokens;
	}

	public int getTotalTokens() {
		return totalTokens;
	}

	public void setTotalTokens(int totalTokens) {
		this.totalTokens = totalTokens;
	}

	public double getPromptPerSecond() {
		return promptPerSecond;
	}

	public void setPromptPerSecond(double promptPerSecond) {
		this.promptPerSecond = promptPerSecond;
	}

	public double getPredictedPerSecond() {
		return predictedPerSecond;
	}

	public void setPredictedPerSecond(double predictedPerSecond) {
		this.predictedPerSecond = predictedPerSecond;
	}

	public int getDraftTokens() {
		return draftTokens;
	}

	public void setDraftTokens(int draftTokens) {
		this.draftTokens = draftTokens;
	}

	public int getDraftAccepted() {
		return draftAccepted;
	}

	public void setDraftAccepted(int draftAccepted) {
		this.draftAccepted = draftAccepted;
	}
}
