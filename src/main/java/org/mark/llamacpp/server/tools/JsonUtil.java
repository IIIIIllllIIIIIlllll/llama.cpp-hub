package org.mark.llamacpp.server.tools;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.struct.ApiResponse;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.util.CharsetUtil;

public class JsonUtil {
	
	
	//private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
	private static final Gson gson = new Gson();
	
	
	public static String toJson(Object obj) {
		return gson.toJson(obj);
	}
	
	
	public static <T> T fromJson(String json, Class<T> type) {
		return gson.fromJson(json, type);
	}
	
	
	public static <T> T fromJson(String json, Type type) {
		return gson.fromJson(json, type);
	}
	
	public static <T> T fromJson(JsonElement json, Class<T> type) {
		return gson.fromJson(json, type);
	}
	
	public static <T> T fromJson(JsonElement json, Type type) {
		return gson.fromJson(json, type);
	}
	
	/**
	 * 	
	 * @param obj
	 * @param key
	 * @return
	 */
	public static String getJsonString(JsonObject obj, String key) {
		if (obj == null || key == null || key.isBlank()) {
			return "";
		}
		if (!obj.has(key) || obj.get(key).isJsonNull()) {
			return "";
		}
		try {
			return obj.get(key).getAsString().trim();
		} catch (Exception ignore) {
			return "";
		}
	}
	
	public static String getJsonString(JsonObject o, String key, String fallback) {
		if (o == null || key == null || !o.has(key) || o.get(key) == null || o.get(key).isJsonNull())
			return fallback;
		try {
			return o.get(key).getAsString();
		} catch (Exception e) {
			return fallback;
		}
	}
	
	public static String getJsonStringAny(JsonObject o, String fallback, String... keys) {
		if (o == null || keys == null) {
			return fallback;
		}
		for (String key : keys) {
			if (key == null || key.isBlank()) {
				continue;
			}
			String value = getJsonString(o, key, null);
			if (value != null) {
				String s = value.trim();
				if (!s.isEmpty()) {
					return s;
				}
			}
		}
		return fallback;
	}
	
	public static JsonObject parseFullHttpRequestToJsonObject(FullHttpRequest request, ChannelHandlerContext ctx) {
		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return null;
			}
			JsonObject obj = JsonUtil.fromJson(content, JsonObject.class);
			if (obj == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体解析失败"));
				return null;
			}
			return obj;
		} catch (Exception e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("解析请求体失败: " + e.getMessage()));
			return null;
		}
	}

	public static Integer getJsonInt(JsonObject o, String key, Integer fallback) {
		if (o == null || key == null || !o.has(key) || o.get(key) == null || o.get(key).isJsonNull())
			return fallback;
		try {
			return o.get(key).getAsInt();
		} catch (Exception e) {
			try {
				String s = o.get(key).getAsString();
				return parseInteger(s);
			} catch (Exception e2) {
				return fallback;
			}
		}
	}
	
	public static long getJsonLong(JsonObject o, String key, long fallback) {
		if (o == null || key == null || !o.has(key) || o.get(key) == null || o.get(key).isJsonNull())
			return fallback;
		try {
			return o.get(key).getAsLong();
		} catch (Exception e) {
			try {
				String s = o.get(key).getAsString();
				Long v = parseLong(s);
				return v == null ? fallback : v.longValue();
			} catch (Exception e2) {
				return fallback;
			}
		}
	}

	public static List<String> getJsonStringList(JsonElement el) {
		if (el == null || el.isJsonNull())
			return null;
		try {
			if (el.isJsonArray()) {
				JsonArray arr = el.getAsJsonArray();
				List<String> out = new ArrayList<>();
				for (int i = 0; i < arr.size(); i++) {
					JsonElement it = arr.get(i);
					if (it == null || it.isJsonNull())
						continue;
					String s = null;
					try {
						s = it.getAsString();
					} catch (Exception e) {
						s = jsonValueToString(it);
					}
					if (s != null && !s.trim().isEmpty())
						out.add(s.trim());
				}
				return out;
			}
			String s = el.getAsString();
			if (s == null || s.trim().isEmpty())
				return null;
			return Arrays.asList(s.trim());
		} catch (Exception e) {
			return null;
		}
	}

	public static String jsonValueToString(JsonElement el) {
		if (el == null || el.isJsonNull())
			return "";
		try {
			if (el.isJsonArray()) {
				return el.toString();
			}
			if (el.isJsonObject()) {
				return el.toString();
			}
			return el.getAsString();
		} catch (Exception e) {
			try {
				return el.toString();
			} catch (Exception e2) {
				return "";
			}
		}
	}

	public static JsonObject tryParseObject(String s) {
		try {
			if (s == null || s.trim().isEmpty()) {
				return null;
			}
			JsonElement el = fromJson(s, JsonElement.class);
			return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
		} catch (Exception e) {
			return null;
		}
	}

	public static boolean ensureToolCallIds(JsonObject obj, Map<Integer, String> indexToId) {
		if (obj == null) {
			return false;
		}
		boolean changed = false;
		JsonElement direct = obj.get("tool_calls");
		if (direct != null && direct.isJsonArray()) {
			changed |= ensureToolCallIdsInArray(direct.getAsJsonArray(), indexToId);
		}
		JsonElement choicesEl = obj.get("choices");
		if (choicesEl != null && choicesEl.isJsonArray()) {
			JsonArray choices = choicesEl.getAsJsonArray();
			for (int i = 0; i < choices.size(); i++) {
				JsonElement cEl = choices.get(i);
				if (!cEl.isJsonObject()) {
					continue;
				}
				JsonObject c = cEl.getAsJsonObject();
				JsonObject message = (c.has("message") && c.get("message").isJsonObject()) ? c.getAsJsonObject("message") : null;
				if (message != null) {
					JsonElement tcs = message.get("tool_calls");
					if (tcs != null && tcs.isJsonArray()) {
						changed |= ensureToolCallIdsInArray(tcs.getAsJsonArray(), indexToId);
					}
				}
				JsonObject delta = (c.has("delta") && c.get("delta").isJsonObject()) ? c.getAsJsonObject("delta") : null;
				if (delta != null) {
					JsonElement tcs = delta.get("tool_calls");
					if (tcs != null && tcs.isJsonArray()) {
						changed |= ensureToolCallIdsInArray(tcs.getAsJsonArray(), indexToId);
					}
				}
			}
		}
		return changed;
	}

	private static boolean ensureToolCallIdsInArray(JsonArray arr, Map<Integer, String> indexToId) {
		if (arr == null) {
			return false;
		}
		boolean changed = false;
		for (int i = 0; i < arr.size(); i++) {
			JsonElement el = arr.get(i);
			if (el == null || !el.isJsonObject()) {
				continue;
			}
			JsonObject tc = el.getAsJsonObject();
			Integer idx = readToolCallIndex(tc, i);
			String id = getJsonString(tc, "id", null);
			if (id == null || id.isBlank()) {
				String existing = (indexToId == null || idx == null) ? null : indexToId.get(idx);
				if (existing == null || existing.isBlank()) {
					existing = "call_" + UUID.randomUUID().toString().replace("-", "");
					if (indexToId != null && idx != null) {
						indexToId.put(idx, existing);
					}
				}
				tc.addProperty("id", existing);
				changed = true;
			} else if (indexToId != null && idx != null) {
				indexToId.putIfAbsent(idx, id);
			}
		}
		return changed;
	}

	private static Integer readToolCallIndex(JsonObject tc, int fallback) {
		if (tc == null) {
			return fallback;
		}
		JsonElement idxEl = tc.get("index");
		if (idxEl == null || idxEl.isJsonNull()) {
			return fallback;
		}
		try {
			if (idxEl.isJsonPrimitive() && idxEl.getAsJsonPrimitive().isNumber()) {
				return idxEl.getAsInt();
			}
			if (idxEl.isJsonPrimitive() && idxEl.getAsJsonPrimitive().isString()) {
				String s = idxEl.getAsString();
				if (s != null && !s.isBlank()) {
					return Integer.parseInt(s.trim());
				}
			}
		} catch (Exception ignore) {
		}
		return fallback;
	}
	
	
	private static Integer parseInteger(String s) {
		if (s == null)
			return null;
		String t = s.trim();
		if (t.isEmpty())
			return null;
		try {
			return Integer.valueOf(Integer.parseInt(t, 10));
		} catch (Exception e) {
			return null;
		}
	}
	
	private static Long parseLong(String s) {
		if (s == null)
			return null;
		String t = s.trim();
		if (t.isEmpty())
			return null;
		try {
			return Long.valueOf(Long.parseLong(t, 10));
		} catch (Exception e) {
			return null;
		}
	}
}
