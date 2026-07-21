package org.mark.llamacpp.server.tools;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;

public class JsonUtil {

	private static final String I18N_BODY_EMPTY = "api.error.body.empty";
	private static final String I18N_BODY_PARSE = "api.error.body.parse";
	private static final String I18N_BODY_PARSE_FAILED = "api.error.body.parse.failed";
	
	
	//private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
	private static final Gson gson = new Gson();

	public static Gson gson() {
		return gson;
	}
	
	
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
			byte[] bytes = readRequestBytes(request);
			if (bytes == null || bytes.length == 0 || isBlank(bytes)) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_EMPTY));
				return null;
			}
			try {
				JsonElement el = gson.fromJson(
						new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8),
						JsonElement.class);
				if (el == null || !el.isJsonObject()) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_PARSE));
					return null;
				}
				return el.getAsJsonObject();
			} catch (Exception e) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_PARSE_FAILED + ": " + e.getMessage()));
				return null;
			}
		} catch (Exception e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_PARSE_FAILED + ": " + e.getMessage()));
			return null;
		}
	}

	/**
	 * 	读取请求体字节数组（仅一次拷贝，不生成中间 String）；body 为空时返回 null。
	 */
	public static byte[] readRequestBytes(FullHttpRequest request) {
		if (request == null) {
			return null;
		}
		ByteBuf buf = request.content();
		if (buf == null || !buf.isReadable()) {
			return null;
		}
		return ByteBufUtil.getBytes(buf);
	}

	/**
	 * 	判断字节数组是否全是 ASCII 空白字符（等价于原 String.trim().isEmpty() 判空）。
	 */
	public static boolean isBlank(byte[] bytes) {
		for (byte b : bytes) {
			if (b != ' ' && b != '\t' && b != '\r' && b != '\n') {
				return false;
			}
		}
		return true;
	}

	/**
	 * 	直接按字节解析请求体为 JsonObject，避免整串 String 拷贝；body 为空或解析失败时返回 null。
	 */
	public static JsonObject parseRequestBody(FullHttpRequest request) {
		return tryParseObject(readRequestBytes(request));
	}

	/**
	 * 	直接解析 UTF-8 字节数组，避免先整体拷贝成 String。
	 */
	public static <T> T fromJson(byte[] bytes, Class<T> type) {
		if (bytes == null || bytes.length == 0) {
			return null;
		}
		return gson.fromJson(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8), type);
	}

	public static <T> T fromJson(byte[] bytes, Type type) {
		if (bytes == null || bytes.length == 0) {
			return null;
		}
		return gson.fromJson(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8), type);
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

	public static double getJsonDouble(JsonObject o, String key, double fallback) {
		if (o == null || key == null || !o.has(key) || o.get(key) == null || o.get(key).isJsonNull())
			return fallback;
		try {
			return o.get(key).getAsDouble();
		} catch (Exception e) {
			try {
				String s = o.get(key).getAsString();
				return Double.parseDouble(s);
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

	/**
	 * 	直接解析 UTF-8 字节数组，避免先整体拷贝成 String。
	 */
	public static JsonObject tryParseObject(byte[] bytes) {
		try {
			if (bytes == null || bytes.length == 0) {
				return null;
			}
			JsonElement el = gson.fromJson(
					new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8),
					JsonElement.class);
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
