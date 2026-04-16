package org.mark.llamacpp.server.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.mark.llamacpp.server.struct.Timing;



/**
 * 	处理llama.cpp响应中的timings性能参数，并持久化记录。（未完成）
 */
public class LlamaRecordService {
	
	/**
	 * 	
	 */
	private static final LlamaRecordService INSTANCE = new LlamaRecordService();
	
	/**
	 * 	
	 * @return
	 */
	public static LlamaRecordService getInstance() {
		return INSTANCE;
	}
	
	/**
	 * 	
	 */
	private final Gson gson = new Gson();
	
	
	/**
	 * 	
	 */
	public LlamaRecordService() {
		
	}
 
	/**
	 * 	{"choices":[{"finish_reason":"stop","index":0,"delta":{}}],"created":1776325491,"id":"chatcmpl-6S2MNBmiAoyxhGHSmona59U0GtUhx6UX","model":"Qwen3-0.6B-GGUF","system_fingerprint":"b8267-1a5631bea","object":"chat.completion.chunk","timings":{"cache_n":0,"prompt_n":15,"prompt_ms":28.295,"prompt_per_token_ms":1.8863333333333334,"prompt_per_second":530.1289980561936,"predicted_n":44,"predicted_ms":387.788,"predicted_per_token_ms":8.813363636363636,"predicted_per_second":113.46405768100095}}
	 * @param json
	 */
	public Timing handleStream(String json) {
		try {
			JsonObject root = JsonParser.parseString(json).getAsJsonObject();
			if (root.has("timings")) {
				return this.gson.fromJson(root.get("timings"), Timing.class);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

}
