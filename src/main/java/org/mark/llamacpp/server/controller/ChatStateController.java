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
import org.mark.llamacpp.server.service.EasyChatGlobalLock;
import org.mark.llamacpp.server.service.EasyChatService;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * Chat 前端专用的状态接口。
 *
 * <p>与 EasyChatController 逻辑相同，但使用独立的数据目录 {@code cache/chat/}，
 * 使 {@code /chat/index.html} 和 {@code /easy-chat/index.html} 的状态完全隔离。</p>
 *
 * <p><b>端点</b>：</p>
 * <ul>
 * <li>GET  {@code /api/chat/state}            — 获取状态摘要</li>
 * <li>GET  {@code /api/chat/state/revision}   — 获取修订版</li>
 * <li>POST {@code /api/chat/sync}             — 同步状态摘要</li>
 * <li>DELETE {@code /api/chat/delete}         — 删除会话/消息</li>
 * </ul>
 */
public class ChatStateController implements BaseController {

	private static final Logger logger = LoggerFactory.getLogger(ChatStateController.class);

	private static final String I18N_METHOD_GET_ONLY = "common.method.get.only";
	private static final String I18N_METHOD_POST_ONLY = "common.method.post.only";
	private static final String I18N_METHOD_DELETE_ONLY = "common.method.delete.only";
	private static final String I18N_CHAT_LOCK_BUSY = "api.error.chat.lock.busy";
	private static final String I18N_CHAT_LOCK_CURRENT_OPERATION = "api.error.chat.lock.current.operation";
	private static final String I18N_CHAT_STATE_LOAD_FAILED = "api.error.chat.state.load.failed";
	private static final String I18N_CHAT_REVISION_LOAD_FAILED = "api.error.chat.revision.load.failed";
	private static final String I18N_CHAT_SYNC_FAILED = "api.error.chat.sync.failed";
	private static final String I18N_CHAT_PARAM_MISSING = "api.error.chat.param.missing";
	private static final String I18N_CHAT_DELETE_MESSAGE_SEQ_REQUIRED = "api.error.chat.delete.message.seq.required";
	private static final String I18N_CHAT_TYPE_UNKNOWN = "api.error.chat.type.unknown";
	private static final String I18N_CHAT_DELETE_FAILED = "api.error.chat.delete.failed";

	private static final Object STATE_LOCK = new Object();
	private static final String STATE_REVISION_KEY = "_revision";
	private static final String BASE_REVISION_FIELD = "baseRevision";
	private static final String REVISION_CONFLICT_CODE = "STATE_REVISION_CONFLICT";
	private final EasyChatGlobalLock globalLock = EasyChatGlobalLock.getInstance();

	private static final String PATH_STATE = "/api/chat/state";
	private static final String PATH_STATE_REVISION = "/api/chat/state/revision";
	private static final String PATH_SYNC = "/api/chat/sync";
	private static final String PATH_DELETE = "/api/chat/delete";

	@Override
	public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request)
			throws RequestMethodException {
        if (uri.equals(PATH_STATE_REVISION)) {
            this.withGlobalLock(ctx, "chat.state.revision", () -> this.handleRevisionRequest(ctx, request));
            return true;
        }
        if (uri.equals(PATH_SYNC)) {
            this.withGlobalLock(ctx, "chat.sync", () -> this.handleSyncRequest(ctx, request));
            return true;
        }
        if (uri.equals(PATH_STATE)) {
            this.withGlobalLock(ctx, "chat.state", () -> this.handleStateRequest(ctx, request));
            return true;
        }
        if (uri.equals(PATH_DELETE)) {
            this.withGlobalLock(ctx, "chat.delete", () -> this.handleDeleteRequest(ctx, request));
            return true;
        }
		return false;
	}

	@FunctionalInterface
	private interface LockedAction {
		void run() throws RequestMethodException;
	}

	private void withGlobalLock(ChannelHandlerContext ctx, String operationName, LockedAction action)
			throws RequestMethodException {
		EasyChatGlobalLock.Lease lease = globalLock.tryAcquire(operationName);
		if (lease == null) {
			sendGlobalLockBusy(ctx, operationName);
			return;
		}
		try (lease) {
			action.run();
		}
	}

	private void sendGlobalLockBusy(ChannelHandlerContext ctx, String requestedOperation) {
		EasyChatGlobalLock.LockState current = globalLock.current();
		String message = I18N_CHAT_LOCK_BUSY;
		Map<String, Object> data = new HashMap<>();
		data.put("requestedOperation", requestedOperation);
		long heldMs = -1L;
		if (current != null) {
			if (current.operationName() != null && !current.operationName().isBlank()) {
				message += I18N_CHAT_LOCK_CURRENT_OPERATION + current.operationName() + "）";
				data.put("activeOperation", current.operationName());
			}
			data.put("startedAt", current.startedAt());
			heldMs = Math.max(0L, System.currentTimeMillis() - current.startedAt());
			data.put("heldMs", heldMs);
		}
		ApiResponse response = ApiResponse.error(message);
		response.setData(data);
		LlamaServer.sendExpressJsonResponse(ctx, HttpResponseStatus.LOCKED, response, true);
	}

	private void handleStateRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, I18N_METHOD_GET_ONLY);
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
			logger.info("加载 chat 状态摘要失败", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_CHAT_STATE_LOAD_FAILED + ": " + e.getMessage()));
		}
	}

	private void handleRevisionRequest(ChannelHandlerContext ctx, FullHttpRequest request)
			throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, I18N_METHOD_GET_ONLY);
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
			logger.info("加载 chat 状态版本失败", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_CHAT_REVISION_LOAD_FAILED + ": " + e.getMessage()));
		}
	}

	private void handleSyncRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);
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
			logger.info("同步 chat 状态失败", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_CHAT_SYNC_FAILED + ": " + e.getMessage()));
		}
	}

	private void handleDeleteRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.DELETE, I18N_METHOD_DELETE_ONLY);
		try {
			JsonObject body = JsonUtil.parseFullHttpRequestToJsonObject(request, ctx);
			if (body == null) {
				return;
			}
			String type = JsonUtil.getJsonString(body, "type", "");
			String conversationId = JsonUtil.getJsonString(body, "conversationId", "");
			if (type == null || type.isEmpty() || conversationId == null || conversationId.isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_CHAT_PARAM_MISSING));
				return;
			}
			EasyChatService service = EasyChatService.getInstance();
			Path stateDir = this.getStateDirPath();
			Path stateFile = this.getStateFilePath(stateDir);
			if ("conversation".equals(type)) {
				synchronized (STATE_LOCK) {
					service.deleteConversation(conversationId);
					this.removeFromStateConversations(stateFile, conversationId);
				}
			} else if ("message".equals(type)) {
				long seq = body.has("seq") ? body.get("seq").getAsLong() : -1;
				if (seq < 0) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_CHAT_DELETE_MESSAGE_SEQ_REQUIRED));
					return;
				}
				Integer variantIndex = null;
				if (body.has("variantIndex") && !body.get("variantIndex").isJsonNull()) {
					variantIndex = body.get("variantIndex").getAsInt();
				}
				Integer newActiveVariant = service.deleteMessage(conversationId, seq, variantIndex);
				LlamaServer.sendJsonResponse(ctx, ApiResponse.success(Map.of(
					"deleted", true,
					"activeVariantIndex", newActiveVariant != null ? newActiveVariant : -1
				)));
				return;
			} else {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_CHAT_TYPE_UNKNOWN + ": " + type));
				return;
			}
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(Map.of("deleted", true)));
		} catch (Exception e) {
			logger.info("删除 chat 数据失败", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_CHAT_DELETE_FAILED + ": " + e.getMessage()));
		}
	}

	private void removeFromStateConversations(Path stateFile, String conversationId) throws Exception {
		JsonObject stored = this.readJsonObjectIfExists(stateFile);
		if (stored == null || !stored.has("conversations")) {
			return;
		}
		JsonArray conversations = stored.getAsJsonArray("conversations");
		JsonArray filtered = new JsonArray();
		for (int i = 0; i < conversations.size(); i++) {
			JsonObject conv = conversations.get(i).getAsJsonObject();
			String id = JsonUtil.getJsonString(conv, "id", "");
			if (!conversationId.equals(id)) {
				filtered.add(conv);
			} else {
				int messageCount = conv.has("messageCount") ? conv.get("messageCount").getAsInt() : 0;
				updateStateMessageCount(stateFile, -messageCount);
			}
		}
		stored.add("conversations", filtered);
		this.writeJsonFile(stateFile, stored);
	}

	private void updateStateMessageCount(Path stateFile, int delta) throws Exception {
		JsonObject stored = this.readJsonObjectIfExists(stateFile);
		if (stored == null) {
			return;
		}
		int current = stored.has("messageCount") ? stored.get("messageCount").getAsInt() : 0;
		stored.addProperty("messageCount", Math.max(0, current + delta));
		this.writeJsonFile(stateFile, stored);
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

	private void assertRevisionMatches(Path stateFile, JsonObject currentState, String normalizedBaseRevision)
			throws Exception {
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
			String base64Key = this.toBase64StorageKey(id);
			if (!base64Key.equals(storageKey)) {
				expectedFiles.add(base64Key + ".json");
			}
		}
		try (Stream<Path> stream = Files.list(conversationDir)) {
			stream.filter(Files::isRegularFile).forEach(path -> {
				String fileName = path.getFileName().toString();
				if (!fileName.endsWith(".json")) {
					return;
				}
				if (!expectedFiles.contains(fileName)) {
					try {
						Path renamed = path.resolveSibling(fileName + ".deleted");
						Files.move(path, renamed, StandardCopyOption.REPLACE_EXISTING);
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

	private String normalizeStorageKey(String storageKey, String conversationId) {
		String key = storageKey == null ? "" : storageKey.trim();
		if (!key.isEmpty() && key.matches("[A-Za-z0-9_-]+")) {
			return key;
		}
		String source = conversationId == null ? "" : conversationId.trim();
		if (!source.isEmpty() && source.matches("[A-Za-z0-9_-]+")) {
			return source;
		}
		return Base64.getUrlEncoder().withoutPadding().encodeToString(source.getBytes(StandardCharsets.UTF_8));
	}

	private String toBase64StorageKey(String conversationId) {
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
		for (int attempt = 0; attempt < 3; attempt++) {
			try {
				Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				return;
			} catch (Exception e) {
				try {
					Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING);
					return;
				} catch (Exception fallbackE) {
					if (attempt < 2) {
						Thread.sleep(50 + attempt * 100);
					} else {
						throw fallbackE;
					}
				}
			}
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

	private record SyncStateResult(int conversationCount, String revision) {
	}
}
