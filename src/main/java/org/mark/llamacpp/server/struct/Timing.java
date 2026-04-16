package org.mark.llamacpp.server.struct;

public class Timing {
	private int cache_n;
	private int prompt_n;
	private double prompt_ms;
	private double prompt_per_token_ms;
	private double prompt_per_second;
	private int predicted_n;
	private double predicted_ms;
	private double predicted_per_token_ms;
	private double predicted_per_second;

	public int getCache_n() {
		return cache_n;
	}

	public void setCache_n(int cache_n) {
		this.cache_n = cache_n;
	}

	public int getPrompt_n() {
		return prompt_n;
	}

	public void setPrompt_n(int prompt_n) {
		this.prompt_n = prompt_n;
	}

	public double getPrompt_ms() {
		return prompt_ms;
	}

	public void setPrompt_ms(double prompt_ms) {
		this.prompt_ms = prompt_ms;
	}

	public double getPrompt_per_token_ms() {
		return prompt_per_token_ms;
	}

	public void setPrompt_per_token_ms(double prompt_per_token_ms) {
		this.prompt_per_token_ms = prompt_per_token_ms;
	}

	public double getPrompt_per_second() {
		return prompt_per_second;
	}

	public void setPrompt_per_second(double prompt_per_second) {
		this.prompt_per_second = prompt_per_second;
	}

	public int getPredicted_n() {
		return predicted_n;
	}

	public void setPredicted_n(int predicted_n) {
		this.predicted_n = predicted_n;
	}

	public double getPredicted_ms() {
		return predicted_ms;
	}

	public void setPredicted_ms(double predicted_ms) {
		this.predicted_ms = predicted_ms;
	}

	public double getPredicted_per_token_ms() {
		return predicted_per_token_ms;
	}

	public void setPredicted_per_token_ms(double predicted_per_token_ms) {
		this.predicted_per_token_ms = predicted_per_token_ms;
	}

	public double getPredicted_per_second() {
		return predicted_per_second;
	}

	public void setPredicted_per_second(double predicted_per_second) {
		this.predicted_per_second = predicted_per_second;
	}

	@Override
	public String toString() {
		return "Timing{" +
				"cache_n=" + cache_n +
				", prompt_n=" + prompt_n +
				", prompt_ms=" + prompt_ms +
				", prompt_per_token_ms=" + prompt_per_token_ms +
				", prompt_per_second=" + prompt_per_second +
				", predicted_n=" + predicted_n +
				", predicted_ms=" + predicted_ms +
				", predicted_per_token_ms=" + predicted_per_token_ms +
				", predicted_per_second=" + predicted_per_second +
				'}';
	}
}
