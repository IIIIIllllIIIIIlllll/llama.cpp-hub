package org.mark.llamacpp.server.controller;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.tools.ParamTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;

/**
 * EasyChat 后端状态接口。
 *
 * <p>状态摘要、版本校验、单会话加载、轻量写回分别走独立端点，
 * 避免每次同步都传输完整历史消息。</p>
 */
public class EasyChatController implements BaseController {

	private static final Logger logger = LoggerFactory.getLogger(EasyChatController.class);
	private static final Object STATE_LOCK = new Object();
	private static final String STATE_REVISION_KEY = "_revision";
	private static final String BASE_REVISION_FIELD = "baseRevision";
	private static final String REVISION_CONFLICT_CODE = "STATE_REVISION_CONFLICT";

	private static final String PATH_STATE = "/api/easy-chat/state";
	private static final String PATH_STATE_REVISION = "/api/easy-chat/state/revision";
	private static final String PATH_CONVERSATION = "/api/easy-chat/conversation";
	private static final String PATH_SYNC = "/api/easy-chat/sync";

	@Override
	public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request)
			throws RequestMethodException {
		if (uri.startsWith(PATH_STATE_REVISION)) {
			this.handleRevisionRequest(ctx, request);
			return true;
		}
		if (uri.startsWith(PATH_CONVERSATION)) {
			this.handleConversationRequest(ctx, request);
			return true;
		}
		if (uri.startsWith(PATH_SYNC)) {
			this.handleSyncRequest(ctx, request);
			return true;
		}
		if (uri.startsWith(PATH_STATE)) {
			this.handleStateRequest(ctx, request);
			return true;
		}
		return false;
	}

	private void handleStateRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			Path stateDir = this.getStateDirPath();
			Path stateFile = this.getStateFilePath(stateDir);
			Path conversationDir = this.getConversationDirPath(stateDir);
			JsonObject state;
			boolean exists;
			String revision;
			synchronized (STATE_LOCK) {
				exists = Files.exists(stateFile);
				state = this.loadStateSummary(stateFile);
				revision = this.resolveStateRevision(stateFile, this.readJsonObjectIfExists(stateFile));
			}
			Map<String, Object> data = new HashMap<>();
			data.put("state", state);
			data.put("exists", exists);
			data.put("revision", revision);
			data.put("file", stateFile.toAbsolutePath().normalize().toString());
			data.put("conversationDir", conversationDir.toAbsolutePath().normalize().toString());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("加载 easy-chat 状态摘要失败", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("加载 easy-chat 状态摘要失败: " + e.getMessage()));
		}
	}

	private void handleRevisionRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			Path stateDir = this.getStateDirPath();
			Path stateFile = this.getStateFilePath(stateDir);
			boolean exists;
			String revision;
			synchronized (STATE_LOCK) {
				exists = Files.exists(stateFile);
				revision = this.resolveStateRevision(stateFile, this.readJsonObjectIfExists(stateFile));
			}
			Map<String, Object> data = new HashMap<>();
			data.put("exists", exists);
			data.put("revision", revision);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("加载 easy-chat 状态版本失败", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("加载 easy-chat 状态版本失败: " + e.getMessage()));
		}
	}

	private void handleConversationRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			Map<String, String> params = ParamTool.getQueryParam(request.uri());
			String conversationId = params.getOrDefault("id", "").trim();
			this.assertRequestMethod(conversationId.isEmpty(), "缺少会话ID");
			Path stateDir = this.getStateDirPath();
			Path stateFile = this.getStateFilePath(stateDir);
			Path conversationDir = this.getConversationDirPath(stateDir);
			JsonObject conversation;
			String revision;
			synchronized (STATE_LOCK) {
				JsonObject storedState = this.readJsonObjectIfExists(stateFile);
				JsonObject summary = this.findConversationSummaryById(storedState, conversationId);
				String storageKey = this.resolveConversationStorageKey(summary, conversationId);
				Path conversationFile = this.getConversationFilePath(conversationDir, storageKey);
				if (!Files.exists(conversationFile)) {
					throw new IllegalStateException("会话不存在: " + conversationId);
				}
				JsonObject storedConversation = this.readJsonObject(conversationFile);
				conversation = this.mergeConversationWithSummary(summary, storedConversation, conversationId);
				revision = this.resolveStateRevision(stateFile, storedState);
			}
			Map<String, Object> data = new HashMap<>();
			data.put("conversation", conversation);
			data.put("revision", revision);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("加载 easy-chat 会话失败", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("加载 easy-chat 会话失败: " + e.getMessage()));
		}
	}

	private void handleSyncRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		try {
			JsonObject body = JsonUtil.parseFullHttpRequestToJsonObject(request, ctx);
			if (body == null) {
				return;
			}
			Path stateDir = this.getStateDirPath();
			Path stateFile = this.getStateFilePath(stateDir);
			Path conversationDir = this.getConversationDirPath(stateDir);
			SyncStateResult saveResult;
			synchronized (STATE_LOCK) {
				saveResult = this.syncState(body, stateFile, conversationDir);
			}
			Map<String, Object> data = new HashMap<>();
			data.put("saved", true);
			data.put("file", stateFile.toAbsolutePath().normalize().toString());
			data.put("size", Files.size(stateFile));
			data.put("conversationDir", conversationDir.toAbsolutePath().normalize().toString());
			data.put("conversationCount", saveResult.conversationCount());
			data.put("revision", saveResult.revision());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("同步 easy-chat 状态失败", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("同步 easy-chat 状态失败: " + e.getMessage()));
		}
	}

	private JsonObject loadStateSummary(Path stateFile) throws Exception {
		JsonObject storedState = this.readJsonObjectIfExists(stateFile);
		if (storedState == null) {
			JsonObject state = new JsonObject();
			state.add("conversations", new JsonArray());
			return state;
		}
		JsonObject state = storedState.deepCopy();
		state.add("conversations", this.normalizeConversationSummaries(this.getConversationArray(storedState)));
		return state;
	}

	private SyncStateResult syncState(JsonObject body, Path stateFile, Path conversationDir) throws Exception {
		String normalizedBaseRevision = this.normalizeBaseRevision(body);
		JsonObject currentState = this.readJsonObjectIfExists(stateFile);
		this.assertRevisionMatches(stateFile, currentState, normalizedBaseRevision);

		JsonObject statePayload = this.getJsonObject(body, "state");
		JsonObject nextState = statePayload == null ? new JsonObject() : statePayload.deepCopy();
		JsonArray summaries = this.normalizeConversationSummaries(this.getConversationArray(nextState));

		JsonObject currentConversation = this.getJsonObject(body, "currentConversation");
		if (currentConversation != null) {
			JsonObject normalizedConversation = currentConversation.deepCopy();
			JsonObject summary = this.buildConversationSummary(normalizedConversation);
			this.upsertConversationSummary(summaries, summary);
			this.writeConversationFile(conversationDir, normalizedConversation);
		}

		nextState.add("conversations", summaries);
		String nextRevision = UUID.randomUUID().toString();
		nextState.addProperty(STATE_REVISION_KEY, nextRevision);
		this.writeJsonFile(stateFile, nextState);
		this.deleteStaleConversationFiles(conversationDir, summaries);
		return new SyncStateResult(summaries.size(), nextRevision);
	}

	private void assertRevisionMatches(Path stateFile, JsonObject currentState, String normalizedBaseRevision) throws Exception {
		if (!Files.exists(stateFile)) {
			return;
		}
		String currentRevision = this.resolveStateRevision(stateFile, currentState);
		if (normalizedBaseRevision.isEmpty() || !normalizedBaseRevision.equals(currentRevision)) {
			throw new IllegalStateException(REVISION_CONFLICT_CODE);
		}
	}

	private String normalizeBaseRevision(JsonObject body) {
		String baseRevision = JsonUtil.getJsonString(body, BASE_REVISION_FIELD, "");
		return baseRevision == null ? "" : baseRevision.trim();
	}

	private void upsertConversationSummary(JsonArray summaries, JsonObject summary) {
		String id = JsonUtil.getJsonString(summary, "id", "");
		if (id.isBlank()) {
			return;
		}
		for (int i = 0; i < summaries.size(); i++) {
			JsonElement element = summaries.get(i);
			if (element == null || !element.isJsonObject()) {
				continue;
			}
			JsonObject current = element.getAsJsonObject();
			if (id.equals(JsonUtil.getJsonString(current, "id", ""))) {
				summaries.set(i, summary);
				return;
			}
		}
		summaries.add(summary);
	}

	private JsonObject findConversationSummaryById(JsonObject state, String conversationId) {
		JsonArray conversations = this.getConversationArray(state);
		for (int i = 0; i < conversations.size(); i++) {
			JsonElement element = conversations.get(i);
			if (element == null || !element.isJsonObject()) {
				continue;
			}
			JsonObject summary = element.getAsJsonObject();
			if (conversationId.equals(JsonUtil.getJsonString(summary, "id", ""))) {
				return this.normalizeConversationSummary(summary).deepCopy();
			}
		}
		return null;
	}

	private JsonObject mergeConversationWithSummary(JsonObject summary, JsonObject conversation, String conversationId) {
		JsonObject merged = conversation == null ? new JsonObject() : conversation.deepCopy();
		if (summary != null) {
			for (Map.Entry<String, JsonElement> entry : summary.entrySet()) {
				String key = entry.getKey();
				if ("messages".equals(key) || "storageKey".equals(key) || "messageCount".equals(key)) {
					continue;
				}
				merged.add(key, entry.getValue() == null ? null : entry.getValue().deepCopy());
			}
		}
		if (!merged.has("id") && conversationId != null && !conversationId.isBlank()) {
			merged.addProperty("id", conversationId);
		}
		if (!merged.has("messages") || !merged.get("messages").isJsonArray()) {
			merged.add("messages", new JsonArray());
		}
		return merged;
	}

	private void writeConversationFile(Path conversationDir, JsonObject conversation) throws Exception {
		if (!Files.exists(conversationDir)) {
			Files.createDirectories(conversationDir);
		}
		String id = JsonUtil.getJsonString(conversation, "id", "");
		String storageKey = this.normalizeStorageKey(null, id);
		Path conversationFile = this.getConversationFilePath(conversationDir, storageKey);
		this.writeJsonFile(conversationFile, conversation);
	}

	private JsonArray normalizeConversationSummaries(JsonArray conversations) {
		JsonArray normalized = new JsonArray();
		for (int i = 0; i < conversations.size(); i++) {
			JsonElement element = conversations.get(i);
			if (element == null || !element.isJsonObject()) {
				continue;
			}
			normalized.add(this.normalizeConversationSummary(element.getAsJsonObject()));
		}
		return normalized;
	}

	private JsonObject normalizeConversationSummary(JsonObject summary) {
		if (summary == null) {
			return new JsonObject();
		}
		if (summary.has("messages") && summary.get("messages").isJsonArray()) {
			return this.buildConversationSummary(summary);
		}
		JsonObject normalized = summary.deepCopy();
		String id = JsonUtil.getJsonString(normalized, "id", "");
		String storageKey = this.normalizeStorageKey(JsonUtil.getJsonString(normalized, "storageKey", null), id);
		int messageCount = Math.max(0, JsonUtil.getJsonInt(normalized, "messageCount", 0));
		normalized.remove("messages");
		normalized.addProperty("storageKey", storageKey);
		normalized.addProperty("messageCount", messageCount);
		return normalized;
	}

	private JsonObject buildConversationSummary(JsonObject conversation) {
		JsonObject summary = conversation == null ? new JsonObject() : conversation.deepCopy();
		String id = JsonUtil.getJsonString(summary, "id", "");
		String storageKey = this.normalizeStorageKey(null, id);
		int messageCount = 0;
		if (summary.has("messages") && summary.get("messages").isJsonArray()) {
			messageCount = summary.getAsJsonArray("messages").size();
		}
		summary.remove("messages");
		summary.addProperty("storageKey", storageKey);
		summary.addProperty("messageCount", messageCount);
		return summary;
	}

	private void deleteStaleConversationFiles(Path conversationDir, JsonArray summaries) throws Exception {
		if (!Files.exists(conversationDir)) {
			return;
		}
		Set<String> expectedFiles = new HashSet<>();
		for (int i = 0; i < summaries.size(); i++) {
			JsonElement element = summaries.get(i);
			if (element == null || !element.isJsonObject()) {
				continue;
			}
			JsonObject summary = element.getAsJsonObject();
			String id = JsonUtil.getJsonString(summary, "id", "");
			String storageKey = this.normalizeStorageKey(JsonUtil.getJsonString(summary, "storageKey", null), id);
			expectedFiles.add(storageKey + ".json");
		}
		try (Stream<Path> stream = Files.list(conversationDir)) {
			stream.filter(Files::isRegularFile).forEach(path -> {
				String fileName = path.getFileName().toString();
				if (!fileName.endsWith(".json")) {
					return;
				}
				if (!expectedFiles.contains(fileName)) {
					try {
						Files.deleteIfExists(path);
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
				}
			});
		} catch (RuntimeException e) {
			if (e.getCause() instanceof Exception ex) {
				throw ex;
			}
			throw e;
		}
	}

	private JsonObject getJsonObject(JsonObject root, String key) {
		if (root == null || key == null || key.isBlank() || !root.has(key) || !root.get(key).isJsonObject()) {
			return null;
		}
		return root.getAsJsonObject(key);
	}

	private JsonArray getConversationArray(JsonObject state) {
		if (state == null || !state.has("conversations") || !state.get("conversations").isJsonArray()) {
			return new JsonArray();
		}
		return state.getAsJsonArray("conversations");
	}

	private String resolveConversationStorageKey(JsonObject summary, String conversationId) {
		return this.normalizeStorageKey(summary == null ? null : JsonUtil.getJsonString(summary, "storageKey", null), conversationId);
	}

	private String normalizeStorageKey(String storageKey, String conversationId) {
		String key = storageKey == null ? "" : storageKey.trim();
		if (!key.isEmpty() && key.matches("[A-Za-z0-9_-]+")) {
			return key;
		}
		String source = conversationId == null ? "" : conversationId.trim();
		return Base64.getUrlEncoder().withoutPadding().encodeToString(source.getBytes(StandardCharsets.UTF_8));
	}

	private void writeJsonFile(Path file, JsonElement json) throws Exception {
		Path parent = file.getParent();
		if (parent != null && !Files.exists(parent)) {
			Files.createDirectories(parent);
		}
		Path tempFile = file.resolveSibling(file.getFileName().toString() + ".tmp");
		Files.writeString(tempFile, JsonUtil.toJson(json), StandardCharsets.UTF_8);
		try {
			Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) {
			Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private JsonObject readJsonObjectIfExists(Path file) throws Exception {
		if (file == null || !Files.exists(file)) {
			return null;
		}
		return this.readJsonObject(file);
	}

	private JsonObject readJsonObject(Path file) throws Exception {
		String json = Files.readString(file, StandardCharsets.UTF_8);
		if (json == null || json.trim().isEmpty()) {
			return new JsonObject();
		}
		JsonElement element = JsonUtil.fromJson(json, JsonElement.class);
		if (element == null || !element.isJsonObject()) {
			return null;
		}
		return element.getAsJsonObject();
	}

	private String resolveStateRevision(Path stateFile, JsonObject state) throws Exception {
		String revision = JsonUtil.getJsonString(state, STATE_REVISION_KEY, "");
		if (revision != null && !revision.isBlank()) {
			return revision.trim();
		}
		if (!Files.exists(stateFile)) {
			return "";
		}
		long modifiedAt = Files.getLastModifiedTime(stateFile).toMillis();
		long size = Files.size(stateFile);
		return "legacy-" + Long.toUnsignedString(modifiedAt, 36) + "-" + Long.toUnsignedString(size, 36);
	}

	private Path getConversationFilePath(Path conversationDir, String storageKey) {
		return conversationDir.resolve(storageKey + ".json").toAbsolutePath().normalize();
	}

	private Path getStateDirPath() throws Exception {
		Path dir = LlamaServer.getCachePath().resolve("easy-chat").toAbsolutePath().normalize();
		if (!Files.exists(dir)) {
			Files.createDirectories(dir);
		}
		return dir;
	}

	private Path getStateFilePath(Path stateDir) {
		return stateDir.resolve("state.json").toAbsolutePath().normalize();
	}

	private Path getConversationDirPath(Path stateDir) {
		return stateDir.resolve("conversations").toAbsolutePath().normalize();
	}

	private record SyncStateResult(int conversationCount, String revision) {}
}
