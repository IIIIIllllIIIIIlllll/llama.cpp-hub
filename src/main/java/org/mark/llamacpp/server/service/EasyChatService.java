package org.mark.llamacpp.server.service;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPOutputStream;

import org.mark.llamacpp.gguf.GGUFModel;
import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.NodeManager;
import org.mark.llamacpp.server.channel.EasyChatStreamingHandler;
import org.mark.llamacpp.server.io.NettyChunkedOutputStream;
import org.mark.llamacpp.server.io.NettyWriteHelper;
import org.mark.llamacpp.server.struct.ActiveRequest;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.struct.Timing;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.stream.ChunkedFile;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;

public class EasyChatService {

	private static final Logger logger = LoggerFactory.getLogger(EasyChatService.class);

	private static final String I18N_METHOD_POST_ONLY = "common.method.post.only";
	private static final String I18N_BODY_EMPTY = "api.error.body.empty";
	private static final String I18N_BODY_PARSE = "api.error.body.parse";
	private static final String I18N_PARAM_CONVERSATION_ID_REQUIRED = "api.error.param.conversationId.required";
	private static final String I18N_PARAM_MODEL_ID_REQUIRED = "api.error.param.modelId.required";
	private static final String I18N_MODEL_NOT_FOUND = "api.error.model.notfound";
	private static final String I18N_CHAT_EPHEMERAL_CONTINUE_UNSUPPORTED = "api.error.chat.ephemeral.continue.unsupported";
	private static final String I18N_CHAT_CONTINUE_SEQ_INVALID = "api.error.chat.continue.seq.invalid";
	private static final String I18N_CHAT_CONTINUE_TARGET_NOTFOUND = "api.error.chat.continue.target.notfound";
	private static final String I18N_CHAT_CONTINUE_TOOLCALLS_UNSUPPORTED = "api.error.chat.continue.toolcalls.unsupported";
	private static final String I18N_CHAT_REGENERATE_TOOLCONTEXT_UNSUPPORTED = "api.error.chat.regenerate.toolcontext.unsupported";
	private static final String I18N_CHAT_PARAM_CONFLICT = "api.error.chat.param.conflict";
	private static final String I18N_CHAT_PROCESS_FAILED = "api.error.chat.process.failed";
	private static final String I18N_CHAT_MODEL_PORT_NOTFOUND = "api.error.chat.model.port.notfound";
	private static final String I18N_CHAT_MODEL_LOAD_FAILED = "api.error.chat.model.load.failed";
	private static final String I18N_CHAT_PARAM_PROMPT_MISSING = "api.error.chat.param.prompt.missing";
	private static final String I18N_CHAT_TITLE_GENERATE_FAILED = "api.error.chat.title.generate.failed";
	private static final String I18N_CHAT_MODEL_RESPONSE_ERROR = "api.error.chat.model.response.error";
	private static final String I18N_CHAT_REMOTE_RESPONSE_ERROR = "api.error.chat.remote.response.error";
	private static final String I18N_CHAT_GLOBAL_LOCK_BUSY = "api.error.chat.global.lock.busy";
	private static final String I18N_CHAT_HISTORY_FAILED = "api.error.chat.history.failed";

	private static final int STREAM_TIMEOUT_MS = 36000 * 1000;

	private static EasyChatService instance;

	public static EasyChatService getInstance() {
		if (instance == null) {
			instance = new EasyChatService();
		}
		return instance;
	}

	private final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();
	private final Map<ChannelHandlerContext, TrackedConnection> channelConnectionMap = new ConcurrentHashMap<>();
	private final Map<ChannelHandlerContext, EasyChatGlobalLock.Lease> channelLeaseMap = new ConcurrentHashMap<>();
	/**
	 * 会话锁：固定 64 分片，按 conversationId 哈希映射。
	 * 替代原先的 ConcurrentHashMap.computeIfAbsent —— key 由客户端 HTTP header 传入且从不清理，会无界增长。
	 * 不同会话哈希冲突时仅短暂串行，不影响正确性。
	 */
	private final Object[] conversationLocks = new Object[64];
	{
		for (int i = 0; i < this.conversationLocks.length; i++) {
			this.conversationLocks[i] = new Object();
		}
	}
	private final EasyChatStorage storage = new EasyChatStorage();
	private final EasyChatRequestWriter requestWriter = new EasyChatRequestWriter(storage);
	private final EasyChatGlobalLock globalLock = EasyChatGlobalLock.getInstance();

	private EasyChatService() {
	}

	private Object conversationLock(String conversationId) {
		int hash = conversationId == null ? 0 : conversationId.hashCode();
		return this.conversationLocks[(hash & 0x7fffffff) % this.conversationLocks.length];
	}

	/**
	 * Decode a URL-encoded header value. Returns the original string if decoding
	 * fails.
	 */
	private static String decodeHeader(String value) {
		if (value == null || value.isBlank()) {
			return value;
		}
		try {
			return URLDecoder.decode(value, StandardCharsets.UTF_8);
		} catch (Exception e) {
			return value;
		}
	}

	private static String readTerminalFinishReason(JsonObject json) {
		if (json == null || !json.has("choices") || !json.get("choices").isJsonArray()) {
			return null;
		}
		JsonArray choices = json.getAsJsonArray("choices");
		if (choices.size() == 0 || !choices.get(0).isJsonObject()) {
			return null;
		}
		JsonObject choice = choices.get(0).getAsJsonObject();
		if (!choice.has("finish_reason") || choice.get("finish_reason").isJsonNull()) {
			return null;
		}
		try {
			String finishReason = choice.get("finish_reason").getAsString();
			return finishReason == null || finishReason.isBlank() ? null : finishReason.trim();
		} catch (Exception ignore) {
			return null;
		}
	}

	/* ---- Public API ---- */

	/**
	 * Handle stream-chat request. Reads metadata from HTTP headers, body bytes
	 * written directly to fragment (no JSON parse), validates, acquires lock,
	 * allocates sequences, writes user fragment, then dispatches async processing.
	 */
	public void handleStreamChat(ChannelHandlerContext ctx, FullHttpRequest request) {
		if (request.method() != HttpMethod.POST) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_METHOD_POST_ONLY));
			return;
		}

		// Drain any streaming body temp file early so it is always cleaned up.
		Path streamingBodyFile = ctx.channel().attr(EasyChatStreamingHandler.STREAMING_BODY_FILE).getAndSet(null);
		EasyChatGlobalLock.Lease globalLease = null;
		try {
			globalLease = this.acquireGlobalLease(ctx, "chat.stream");
			if (globalLease == null) {
				return;
			}
			this.channelLeaseMap.put(ctx, globalLease);

			// Client already disconnected: abort immediately.
			if (!ctx.channel().isActive()) {
				logger.info("[EasyChat] channel已关闭，取消stream-chat请求");
				return;
			}

			// Read metadata from headers
			String hdrConvId = decodeHeader(request.headers().get("X-Conversation-Id"));
			String conversationId = (hdrConvId != null) ? hdrConvId : "";
			if (conversationId.isBlank()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_CONVERSATION_ID_REQUIRED));
				return;
			}

			String hdrModelId = decodeHeader(request.headers().get("X-Model-Id"));
			String modelId = (hdrModelId != null) ? hdrModelId : "";
			if (modelId.isBlank()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_MODEL_ID_REQUIRED));
				return;
			}

			String hdrAsstName = decodeHeader(request.headers().get("X-Assistant-Name"));
			String assistantName = (hdrAsstName != null) ? hdrAsstName : "";

			// streamingBodyFile was read at method entry

			// Read body bytes directly (no JSON parsing) — only if not from streaming file
			byte[] bodyBytes;
			if (streamingBodyFile != null && Files.exists(streamingBodyFile)) {
				long fileSize = Files.size(streamingBodyFile);
				logger.info("[EasyChat][Streaming] 检测到流式请求体: size={} bytes", fileSize);
				bodyBytes = null; // Will read from file when needed
			} else {
				bodyBytes = new byte[request.content().readableBytes()];
				request.content().readBytes(bodyBytes);
			}

			// Parse optional small headers (safe, tiny)
			JsonArray toolsArr = null;
			String toolsHeader = decodeHeader(request.headers().get("X-Tools"));
			if (toolsHeader != null && !toolsHeader.isBlank()) {
				try {
					JsonElement el = JsonUtil.fromJson(toolsHeader, JsonElement.class);
					if (el != null && el.isJsonArray()) {
						toolsArr = el.getAsJsonArray();
					}
				} catch (Exception e) {
					logger.warn("[EasyChat] 解析X-Tools header失败", e);
				}
			}

			String toolChoice = request.headers().get("X-Tool-Choice");
			if (toolChoice == null || toolChoice.isBlank()) {
				toolChoice = "auto";
			}

			String ephemeralMode = decodeHeader(request.headers().get("X-Ephemeral-Mode"));
			boolean isEphemeral = ephemeralMode != null && !ephemeralMode.isBlank();
			String streamHeader = decodeHeader(request.headers().get("X-Stream"));
			boolean requestStream = streamHeader == null || streamHeader.isBlank()
					|| Boolean.parseBoolean(streamHeader.trim());

			JsonObject samplingParams = null;
			String spHeader = decodeHeader(request.headers().get("X-Sampling-Params"));
			if (spHeader != null && !spHeader.isBlank()) {
				try {
					JsonElement el = JsonUtil.fromJson(spHeader, JsonElement.class);
					if (el != null && el.isJsonObject()) {
						samplingParams = el.getAsJsonObject();
					}
				} catch (Exception e) {
					logger.warn("[EasyChat] 解析X-Sampling-Params header失败", e);
				}
			}

			// Read X-Node-Id header for remote node routing
			String nodeId = decodeHeader(request.headers().get("X-Node-Id"));
			if (nodeId != null) {
				nodeId = nodeId.trim();
			}
		ModelTarget modelTarget = this.resolveModelTarget(modelId, nodeId);
			if (modelTarget.error != null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(modelTarget.error));
				return;
			}
			modelId = modelTarget.resolvedModelId;
			int modelPort = modelTarget.port != null ? modelTarget.port.intValue() : 0;
			boolean isRemoteNode = modelTarget.isRemoteNode;

			// Resolve system prompt from synced assistant config.
			String systemPrompt = this.resolveAssistantSystemPrompt(assistantName);

			// Parse regenerate headers
			Long regenerateSeq = null;
			String regHeader = request.headers().get("X-Regenerate-Id");
			if (regHeader != null && !regHeader.isBlank()) {
				try {
					regenerateSeq = Long.parseLong(regHeader);
				} catch (NumberFormatException e) {
					logger.warn("[EasyChat] 解析X-Regenerate-Id失败: {}", regHeader);
				}
			}

			// Parse continue headers
			Long continueSeq = null;
			String continueHeader = request.headers().get("X-Continue-Seq");
			if (continueHeader != null && !continueHeader.isBlank()) {
				try {
					continueSeq = Long.parseLong(continueHeader);
				} catch (NumberFormatException e) {
					logger.warn("[EasyChat] 解析X-Continue-Seq失败: {}", continueHeader);
				}
			}

			Map<Long, Integer> variants = null;
			String variantsHeader = request.headers().get("X-Variants");
			if (variantsHeader != null && !variantsHeader.isBlank()) {
				variants = new HashMap<>();
				for (String pair : variantsHeader.split(",")) {
					String[] parts = pair.split(":");
					if (parts.length == 2) {
						try {
							long vSeq = Long.parseLong(parts[0].trim());
							int vIdx = Integer.parseInt(parts[1].trim());
							variants.put(vSeq, vIdx);
						} catch (NumberFormatException e) {
							logger.warn("[EasyChat] 解析X-Variants pair失败: {}", pair);
						}
					}
				}
			}

			boolean isRegenerate = regenerateSeq != null;
			boolean isContinue = continueSeq != null;
			if (isRegenerate || isContinue) {
				// For regenerate/continue: no body needed, backend reads messages from
				// fragments
				// bodyBytes can be empty
			} else if (bodyBytes != null && bodyBytes.length == 0) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_EMPTY));
				return;
			} else if (bodyBytes == null && streamingBodyFile != null) {
				long fileSize = Files.size(streamingBodyFile);
				if (fileSize == 0) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_EMPTY));
					return;
				}
			}

			// Per-conversation lock
			Object convLock = this.conversationLock(conversationId);

			Path convDir = isEphemeral ? null : this.storage.getConversationDir(conversationId);

			// Validate continue target before acquiring seq lock.
			int continueVariantIndex = 0;
			if (isContinue) {
				if (isEphemeral) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_CHAT_EPHEMERAL_CONTINUE_UNSUPPORTED));
					return;
				}
				if (continueSeq < 0 || continueSeq % 2 != 1) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_CHAT_CONTINUE_SEQ_INVALID));
					return;
				}
				EasyChatStorage.FragmentHeader header = this.storage.readFragmentHeader(convDir, continueSeq);
				if (header == null || this.storage.isDeleted(header)) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_CHAT_CONTINUE_TARGET_NOTFOUND));
					return;
				}
				continueVariantIndex = this.storage.resolveVariantIndex(header,
						variants != null ? variants.get(continueSeq) : null);
				if (continueVariantIndex < 0) {
					continueVariantIndex = header.activeVariantIndex;
				}
				if (continueVariantIndex < 0) {
					continueVariantIndex = 0;
				}
				try {
					if (this.fragmentHasNonEmptyToolCalls(convDir, continueSeq, continueVariantIndex)) {
						LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_CHAT_CONTINUE_TOOLCALLS_UNSUPPORTED));
						return;
					}
				} catch (Exception e) {
					logger.warn("[EasyChat] 读取继续生成目标payload失败 seq={}", continueSeq, e);
				}
			}

			if (isRegenerate && !isEphemeral && convDir != null) {
				try {
					boolean targetHasToolCalls = false;
					EasyChatStorage.FragmentHeader regFragHeader = this.storage.readFragmentHeader(convDir, regenerateSeq);
					if (regFragHeader != null && !this.storage.isDeleted(regFragHeader)) {
						int regVariant = this.storage.resolveVariantIndex(regFragHeader,
								variants != null ? variants.get(regenerateSeq) : null);
						if (regVariant < 0) {
							regVariant = regFragHeader.activeVariantIndex;
						}
						if (regVariant < 0) {
							regVariant = 0;
						}
						targetHasToolCalls = this.fragmentHasNonEmptyToolCalls(convDir, regenerateSeq, regVariant);
					}
					if (!targetHasToolCalls) {
						for (long seq = regenerateSeq - 1; seq >= 0; seq--) {
							EasyChatStorage.FragmentHeader h = this.storage.readFragmentHeader(convDir, seq);
							if (h == null || this.storage.isDeleted(h)) {
								continue;
							}
							int v = this.storage.resolveVariantIndex(h, variants != null ? variants.get(seq) : null);
							if (v < 0) {
								v = h.activeVariantIndex;
							}
							if (v < 0) {
								v = 0;
							}
							String role = fragmentTopLevelRole(convDir, seq, v);
							if (role == null) {
								break;
							}
							if ("tool".equals(role)) {
								LlamaServer.sendJsonResponse(ctx,
										ApiResponse.error(I18N_CHAT_REGENERATE_TOOLCONTEXT_UNSUPPORTED));
								return;
							}
							break;
						}
					}
				} catch (Exception e) {
					logger.warn("[EasyChat] regenerate预检失败 seq={}", regenerateSeq, e);
				}
			}

			long userSeq;
			long aiSeq;
			byte[] toolsBytes;

			synchronized (convLock) {
				if (isEphemeral) {
					userSeq = -1;
					aiSeq = -1;
				} else {
					Path indexPath = this.storage.indexFile(convDir);
					this.storage.ensureIndex(convDir, assistantName);
					long idxSeq = this.storage.readIndexSeq(indexPath);

					if (isRegenerate && isContinue) {
						LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_CHAT_PARAM_CONFLICT));
						return;
					}
					if (isRegenerate) {
						// Regenerate: reuse existing AI seq, don't write user fragment
						userSeq = -1;
						aiSeq = regenerateSeq;
					} else if (isContinue) {
						// Continue: reuse existing AI seq, don't write user fragment
						userSeq = -1;
						aiSeq = continueSeq;
					} else {
						userSeq = idxSeq;
						aiSeq = idxSeq + 1;
						this.storage.writeIndexSeq(indexPath, idxSeq + 2);

						// Write user fragment (raw body bytes, no JSON parsing)
						if (streamingBodyFile != null && Files.exists(streamingBodyFile)) {
							this.storage.writeFragment(convDir, userSeq, System.currentTimeMillis(), streamingBodyFile);
						} else {
							this.storage.writeFragment(convDir, userSeq, System.currentTimeMillis(), bodyBytes);
						}
					}
				}

				// Write tools.bin if tools provided via header
				if (toolsArr != null) {
					JsonObject toolsObj = new JsonObject();
					toolsObj.add("tools", toolsArr);
					toolsObj.addProperty("tool_choice", toolChoice);
					toolsBytes = JsonUtil.toJson(toolsObj).getBytes(StandardCharsets.UTF_8);
					if (!isEphemeral) {
						this.storage.writeTools(convDir, toolsBytes);
					}
				} else {
					toolsBytes = isEphemeral ? null : this.storage.readTools(convDir);
				}

				// Persist X-Variants to fragment headers
				if (!isEphemeral && variants != null) {
					for (Map.Entry<Long, Integer> entry : variants.entrySet()) {
						this.storage.writeActiveVariantIndex(convDir, entry.getKey(), entry.getValue());
					}
				}
			}
			// Create effectively final copies for lambda capture
			final String finalModelId = modelId;
			final int finalModelPort = modelPort;
			final String finalNodeId = nodeId;
			final boolean finalIsRemoteNode = isRemoteNode;
			final String finalSystemPrompt = systemPrompt;
			final Path finalConvDir = convDir;
			final byte[] finalToolsBytes = toolsBytes;
			final JsonObject finalSamplingParams = samplingParams;
			final boolean finalIsRegenerate = isRegenerate;
			final boolean finalIsContinue = isContinue;
			final boolean finalIsEphemeral = isEphemeral;
			final Map<Long, Integer> finalVariants = variants;
			final Long finalRegenerateSeq = regenerateSeq;
			final Long finalContinueSeq = continueSeq;
			final int finalContinueVariantIndex = continueVariantIndex;
			final byte[] finalBodyBytes = bodyBytes;
			// For persisted chats, the current message has already been written to
			// fragments
			// and will be replayed from history. Only ephemeral requests should append the
			// transient body directly to the model request.
			byte[] transientBodyBytes = null;
			Path transientBodyFile = null;
			if (finalIsEphemeral) {
				if (streamingBodyFile != null && Files.exists(streamingBodyFile)) {
					// Stream the ephemeral user body straight from the streaming
					// temp file instead of materializing the whole multi-MB
					// payload in JVM heap. Cleanup is deferred to the worker's
					// finally block so the file survives until writeRequestBody
					// has streamed it.
					transientBodyFile = streamingBodyFile;
					streamingBodyFile = null;
				} else {
					transientBodyBytes = finalBodyBytes;
				}
			}
			// Non-ephemeral streaming bodies have already been written as
			// fragments; clean their temp file up here.
			this.cleanupStreamingBodyFile(streamingBodyFile);
			streamingBodyFile = null;
			final byte[] finalTransientBodyBytes = transientBodyBytes;
			final Path finalTransientBodyFile = transientBodyFile;
			final boolean finalRequestStream = requestStream;

			// If the client left while we were preparing the request, do not start the
			// worker.
			if (!ctx.channel().isActive()) {
				logger.info("[EasyChat] channel在提交任务前已关闭，取消生成");
				// Worker won't fire, so clean up the handed-off ephemeral body
				// file ourselves (otherwise it would leak on disk).
				this.cleanupStreamingBodyFile(transientBodyFile);
				return;
			}

			// Dispatch to worker thread
			this.channelLeaseMap.remove(ctx);
			final EasyChatGlobalLock.Lease finalGlobalLease = globalLease;
			this.worker.execute(() -> {
				HttpURLConnection connection = null;
				StreamAccumulator accumulator = new StreamAccumulator();
				// Record seed lengths for every continue request so hasNewContent() can
				// detect a no-op (model emits EOS immediately, nothing appended). For
				// streaming, the seed text is also appended to the accumulator so that
				// streaming deltas produce original+new; for non-stream, llama.cpp returns
				// the FULL text (prefill + new tokens) in choices[0].message.content, so
				// seeding the content would double-count the original — only lengths are
				// recorded.
				if (finalIsContinue && finalConvDir != null) {
					this.seedAccumulatorFromFragment(accumulator, finalConvDir, finalContinueSeq, finalContinueVariantIndex,
							finalRequestStream);
				}
				Path indexPath = finalConvDir == null ? null : this.storage.indexFile(finalConvDir);
				String trackerRequestId = null;
				try {
					// Double-check cancellation after acquiring a worker thread.
					if (!ctx.channel().isActive()) {
						logger.info("[EasyChat] worker启动时channel已关闭，直接退出");
						return;
					}

					// 本地请求注册到活跃请求计数器，用于自动卸载的在用保护
					if (!finalIsRemoteNode) {
						trackerRequestId = ModelRequestTracker.getInstance().createRequest(finalModelId, "easy-chat");
					}

					if (finalIsRemoteNode) {
						this.handleRemoteNodeRequest(ctx, conversationId, finalNodeId, finalModelId, finalSystemPrompt,
								finalConvDir, finalToolsBytes, finalSamplingParams, finalVariants, finalRegenerateSeq,
								finalContinueSeq, finalTransientBodyBytes, finalTransientBodyFile, finalIsEphemeral,
								finalRequestStream, accumulator);
					} else {
						connection = this.openTrackedConnection(ctx, finalModelId, finalModelPort);

						// Stream request body to llama.cpp
						this.writeRequestBody(connection, conversationId, finalModelId, finalSystemPrompt, finalConvDir,
								finalToolsBytes, finalSamplingParams, finalVariants, finalRegenerateSeq,
								finalContinueSeq, finalTransientBodyBytes, finalTransientBodyFile, finalIsEphemeral,
								finalRequestStream);

						int responseCode = connection.getResponseCode();

						if (!(responseCode >= 200 && responseCode < 300)) {
							String errBody = this.readErrorBody(connection);
							logger.info("[EasyChat] llama.cpp错误响应 code={} body={}", responseCode, errBody);
							this.sendErrorResponse(ctx, responseCode, errBody);
							return;
						}

						if (finalRequestStream) {
							HttpResponse sseResp = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
							sseResp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream; charset=UTF-8");
							sseResp.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
							sseResp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
							sseResp.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
							sseResp.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
							if (!NettyWriteHelper.writeAndFlushBlocking(ctx, sseResp, logger, "[EasyChat]")) {
								return;
							}

							this.proxySseStream(ctx, connection, accumulator);
						} else {
							byte[] responseBytes = connection.getInputStream().readAllBytes();
							this.accumulateNonStreamResponse(JsonUtil.tryParseObject(responseBytes), accumulator);
							this.sendJsonPayloadResponse(ctx, responseCode, connection.getContentType(), responseBytes);
						}
					}

					// Write AI fragment and update state (always, if buffer has content)
					if (!finalIsEphemeral) {
						synchronized (convLock) {
							if (finalIsContinue ? accumulator.hasNewContent() : accumulator.hasContent()) {
								JsonObject aiMsg = this.buildAiMessage(accumulator);
								byte[] aiBytes = JsonUtil.toJson(aiMsg).getBytes(StandardCharsets.UTF_8);
								if (finalIsContinue) {
									this.storage.updateVariant(finalConvDir, aiSeq, finalContinueVariantIndex, aiBytes);
									this.recordModelForVariant(finalConvDir, aiSeq, finalContinueVariantIndex, finalModelId,
											conversationId);
								} else {
									int newVariantIndex = this.computeNextVariantIndex(finalConvDir, aiSeq,
											finalIsRegenerate);
									boolean wroteNewAssistantFragment = this.persistAssistantFragment(finalConvDir, aiSeq,
											aiBytes, finalIsRegenerate, indexPath);
									this.recordModelForVariant(finalConvDir, aiSeq, newVariantIndex, finalModelId,
											conversationId);
									if (!finalIsRegenerate) {
										this.updateStateMessageCount(conversationId, 2);
									} else if (wroteNewAssistantFragment) {
										this.updateStateMessageCount(conversationId, 1);
									}
								}
							}
						}
					}

					// Record usage to LlamaRecordService
					if (accumulator.timings != null && (!finalIsContinue || accumulator.hasNewContent())) {
						try {
							Timing timing = this.timingFromJson(accumulator.timings);
							ActiveRequest activeReq = new ActiveRequest(conversationId, finalModelId,
									"chat/completions");
							activeReq.setTiming(timing);
							activeReq.setStatus(ActiveRequest.RequestStatus.COMPLETED);
							activeReq.setPhase(ActiveRequest.Phase.GENERATION);
							LlamaRecordService.getInstance().recordRequest(activeReq);
						} catch (Exception e) {
							logger.warn("[EasyChat] 记录用量失败 conversation={}", conversationId, e);
						}
					}

					if (finalRequestStream) {
						if (ctx.channel().isActive()) {
							ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
									.addListener(ChannelFutureListener.CLOSE);
						}
					}

				} catch (Exception e) {
					logger.info("[EasyChat] 处理流式聊天失败 conversation={}", conversationId, e);
					// Still write fragment if we have partial content
					try {
						if (!finalIsEphemeral) {
							synchronized (convLock) {
								if (finalIsContinue ? accumulator.hasNewContent() : accumulator.hasContent()) {
									JsonObject aiMsg = this.buildAiMessage(accumulator);
									byte[] aiBytes = JsonUtil.toJson(aiMsg).getBytes(StandardCharsets.UTF_8);
									if (finalIsContinue) {
										this.storage.updateVariant(finalConvDir, aiSeq, finalContinueVariantIndex, aiBytes);
										this.recordModelForVariant(finalConvDir, aiSeq, finalContinueVariantIndex,
												finalModelId, conversationId);
									} else {
										int newVariantIndex = this.computeNextVariantIndex(finalConvDir, aiSeq,
												finalIsRegenerate);
										boolean wroteNewAssistantFragment = this.persistAssistantFragment(finalConvDir,
												aiSeq, aiBytes, finalIsRegenerate, indexPath);
										this.recordModelForVariant(finalConvDir, aiSeq, newVariantIndex, finalModelId,
												conversationId);
										if (finalIsRegenerate && wroteNewAssistantFragment) {
											this.updateStateMessageCount(conversationId, 1);
										}
									}
								}
							}
						}
					} catch (Exception ex) {
						logger.warn("[EasyChat] 异常状态下写入AI碎片失败", ex);
					}
					// Record usage even on error if timings available
					if (accumulator.timings != null && (!finalIsContinue || accumulator.hasNewContent())) {
						try {
							Timing timing = this.timingFromJson(accumulator.timings);
							ActiveRequest activeReq = new ActiveRequest(conversationId, finalModelId,
									"chat/completions");
							activeReq.setTiming(timing);
							activeReq.setStatus(ActiveRequest.RequestStatus.FAILED);
							activeReq.setPhase(ActiveRequest.Phase.GENERATION);
							LlamaRecordService.getInstance().recordRequest(activeReq);
						} catch (Exception ex) {
							logger.warn("[EasyChat] 异常状态下记录用量失败 conversation={}", conversationId, ex);
						}
					}
					this.sendOpenAIError(ctx, 500, e.getMessage());
				} finally {
					if (trackerRequestId != null) {
						ModelRequestTracker.getInstance().removeRequest(trackerRequestId);
					}
					// Ephemeral streaming body file was handed off to the writer; clean
					// it up now that the request body has fully streamed (or failed).
					if (finalTransientBodyFile != null) {
						this.cleanupStreamingBodyFile(finalTransientBodyFile);
					}
					this.cleanupConnection(ctx);
					finalGlobalLease.close();
				}
			});
			globalLease = null;

		} catch (Exception e) {
			logger.info("[EasyChat] 处理stream-chat请求失败", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_CHAT_PROCESS_FAILED + ": " + e.getMessage()));
		} finally {
			if (globalLease != null) {
				this.channelLeaseMap.remove(ctx);
				globalLease.close();
			}
			this.cleanupStreamingBodyFile(streamingBodyFile);
		}
	}

	/**
	 * Stream conversation history as chunked JSON with per-message locking and
	 * zero-copy file transfer via DefaultFileRegion. Role is determined from seq
	 * parity (even=user, odd=assistant), so no payload data is ever read into JVM
	 * memory. File transfers happen in kernel space (sendfile) and never block the
	 * EventLoop thread.
	 */
	public void handleStreamChatHistory(ChannelHandlerContext ctx, String conversationId, boolean useGzip) {
		EasyChatGlobalLock.Lease globalLease = this.acquireGlobalLease(ctx, "chat.history");
		if (globalLease == null) {
			return;
		}
		if (conversationId == null || conversationId.isBlank()) {
			this.sendHistoryError(ctx, I18N_PARAM_CONVERSATION_ID_REQUIRED);
			globalLease.close();
			return;
		}

		Path convDir;
		try {
			convDir = this.storage.getConversationDir(conversationId);
		} catch (IOException e) {
			logger.info("[EasyChat] 获取碎片目录失败 conversation={}", conversationId, e);
			this.sendHistoryError(ctx, I18N_CHAT_HISTORY_FAILED + ": " + e.getMessage());
			globalLease.close();
			return;
		}

		// Per-conversation lock to prevent concurrent read/write race
		Object convLock = this.conversationLock(conversationId);

		// Phase 1: pre-scan metadata (under conversation lock for consistency)
		long recordCount = 0;
		long totalSize = 0;
		long variantCount = 0;
		long nextSeq = 0;
		try {
			synchronized (convLock) {
				long endExclusive = this.storage.readNextSeq(convDir);
				nextSeq = endExclusive;
				for (long seq = 0; seq < endExclusive; seq++) {
					EasyChatStorage.FragmentHeader header = this.storage.readFragmentHeader(convDir, seq);
					if (header == null) {
						continue;
					}
					if (!this.storage.isDeleted(header)) {
						for (int v = 0; v < header.variantCount; v++) {
							totalSize += Math.max(0, header.lengths[v]);
							if (v > 0) {
								variantCount++;
							}
						}
						recordCount++;
					}
				}
			}
		} catch (Exception e) {
			logger.info("[EasyChat] 预扫描碎片失败 conversation={}", conversationId, e);
			this.sendHistoryError(ctx, I18N_CHAT_HISTORY_FAILED + ": " + e.getMessage());
			globalLease.close();
			return;
		}

		// Phase 2: build JSON prefix
		String prefix = "{\"message\":\"success\",\"totalSize\":" + totalSize + ",\"recordCount\":" + recordCount
				+ ",\"variantCount\":" + variantCount + ",\"nextSeq\":" + nextSeq + ",\"data\":[";

		// Start chunked response — shared headers
		HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
		response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		response.headers().set(HttpHeaderNames.VARY, HttpHeaderNames.ACCEPT_ENCODING.toString());
		response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store");

		byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);

		if (useGzip) {
			response.headers().set(HttpHeaderNames.CONTENT_ENCODING, HttpHeaderValues.GZIP);
			ctx.writeAndFlush(response);

			// Phase 3 + 4: write entire history JSON through a single gzip stream
			byte[] gzipComma = ",".getBytes(StandardCharsets.UTF_8);
			byte[] gzipVariantPrefix = "{\"content\":".getBytes(StandardCharsets.UTF_8);
			byte[] gzipVariantSuffix = "}".getBytes(StandardCharsets.UTF_8);
			byte[] gzipMsgSuffix = "]}".getBytes(StandardCharsets.UTF_8);
			byte[] gzipDataSuffix = "]}".getBytes(StandardCharsets.UTF_8);
			NettyChunkedOutputStream nettyOut = null;
			GZIPOutputStream gzipStream = null;
			try {
				nettyOut = new NettyChunkedOutputStream(ctx, 32 * 1024, logger, "[EasyChat]");
				gzipStream = new GZIPOutputStream(nettyOut, 32 * 1024);
				gzipStream.write(prefixBytes);

				long endExclusive;
				Map<Long, Map<Integer, String>> modelIndex;
				synchronized (convLock) {
					endExclusive = this.storage.readNextSeq(convDir);
					modelIndex = this.storage.readModelIndex(convDir);
				}
				Map<String, String> modelNameCache = new HashMap<>();
				boolean first = true;
				for (long seq = 0; seq < endExclusive; seq++) {
					EasyChatStorage.FragmentHeader header;
					String role;
					int activeVariant;
					int msgVariantCount = 0;

					synchronized (convLock) {
						header = this.storage.readFragmentHeader(convDir, seq);
						if (header == null || this.storage.isDeleted(header)) {
							continue;
						}

						role = (header.seq % 2 == 0) ? "user" : "assistant";

						activeVariant = this.storage.resolveVariantIndex(header, header.activeVariantIndex);
						if (activeVariant < 0) {
							activeVariant = 0;
						}

						msgVariantCount = header.variantCount;

						if (!first) {
							gzipStream.write(gzipComma);
						}
						first = false;

						Map<Integer, String> seqModels = (header.seq % 2 == 1) ? modelIndex.get(seq) : null;
						String model = (seqModels != null) ? seqModels.get(activeVariant) : null;
						String modelField = (model != null) ? ",\"model\":\"" + this.escapeJsonString(model) + "\"" : "";
						String modelNameField = "";
						if (model != null) {
							String modelName = modelNameCache.computeIfAbsent(model, this::resolveModelName);
							if (modelName != null && !modelName.equals(model)) {
								modelNameField = ",\"modelName\":\"" + this.escapeJsonString(modelName) + "\"";
							}
						}
						String variantModelsField = "";
						String variantModelNamesField = "";
						if (header.seq % 2 == 1 && seqModels != null && !seqModels.isEmpty()) {
							StringBuilder variantModels = new StringBuilder();
							StringBuilder variantModelNames = new StringBuilder();
							for (int v = 0; v < msgVariantCount; v++) {
								if (v > 0) {
									variantModels.append(',');
									variantModelNames.append(',');
								}
								String vmodel = seqModels.getOrDefault(v, "");
								variantModels.append("\"").append(this.escapeJsonString(vmodel)).append("\"");
								String vmodelName = vmodel.isBlank() ? ""
										: modelNameCache.computeIfAbsent(vmodel, this::resolveModelName);
								variantModelNames.append("\"").append(this.escapeJsonString(vmodelName)).append("\"");
							}
							variantModelsField = ",\"variantModels\":[" + variantModels + "]";
							variantModelNamesField = ",\"variantModelNames\":[" + variantModelNames + "]";
						}
						String msgPrefix = "{\"seq\":" + seq + ",\"role\":\"" + this.escapeJsonString(role) + "\""
								+ modelField + modelNameField + variantModelsField + variantModelNamesField
								+ ",\"activeVariant\":" + activeVariant + ",\"variants\":[";
						gzipStream.write(msgPrefix.getBytes(StandardCharsets.UTF_8));

						for (int v = 0; v < msgVariantCount; v++) {
							if (v > 0) {
								gzipStream.write(gzipComma);
							}
							gzipStream.write(gzipVariantPrefix);
							EasyChatStorage.FragmentSlice slice = this.storage.getVariantSlice(convDir, seq, v);
							if (slice != null && slice.length > 0) {
								try {
									this.storage.streamSlice(slice, gzipStream);
								} catch (IOException e) {
									logger.warn("[EasyChat] 读取payload失败 seq={} v={}", seq, v, e);
									gzipStream.write("null".getBytes(StandardCharsets.UTF_8));
								}
							} else {
								gzipStream.write("null".getBytes(StandardCharsets.UTF_8));
							}
							gzipStream.write(gzipVariantSuffix);
						}

						gzipStream.write(gzipMsgSuffix);
					}
				}
				gzipStream.write(gzipDataSuffix);
				gzipStream.finish();
				nettyOut.close();

				logger.info("[EasyChat] 流式传输历史(gzip)完成 conversation={} records={} extraVariants={} bytes={}",
						conversationId, recordCount, variantCount, totalSize);
				final EasyChatGlobalLock.Lease finalGlobalLease = globalLease;
				ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(future -> {
					finalGlobalLease.close();
					ctx.close();
				});
				globalLease = null;
			} catch (Exception e) {
				logger.info("[EasyChat] 流式传输历史(gzip)失败 conversation={}", conversationId, e);
				ctx.close();
			} finally {
				if (gzipStream != null) {
					try {
						gzipStream.close();
					} catch (Exception ignore) {
					}
				}
				if (nettyOut != null) {
					try {
						nettyOut.close();
					} catch (Exception ignore) {
					}
				}
				if (globalLease != null) {
					globalLease.close();
				}
			}
		} else {
			ctx.writeAndFlush(response);
			ctx.writeAndFlush(Unpooled.wrappedBuffer(prefixBytes));

			// Phase 3: per-message locked read + zero-copy write via DefaultFileRegion
			byte[] comma = ",".getBytes(StandardCharsets.UTF_8);
			byte[] variantPrefix = "{\"content\":".getBytes(StandardCharsets.UTF_8);
			byte[] variantSuffix = "}".getBytes(StandardCharsets.UTF_8);
			byte[] msgSuffix = "]}".getBytes(StandardCharsets.UTF_8);
			byte[] dataSuffix = "]}".getBytes(StandardCharsets.UTF_8);
			try {
				long endExclusive;
				Map<Long, Map<Integer, String>> modelIndex;
				synchronized (convLock) {
					endExclusive = this.storage.readNextSeq(convDir);
					modelIndex = this.storage.readModelIndex(convDir);
				}
				Map<String, String> modelNameCache = new HashMap<>();
				boolean first = true;
				for (long seq = 0; seq < endExclusive; seq++) {
					EasyChatStorage.FragmentHeader header;
					String role;
					int activeVariant;
					int msgVariantCount = 0;

					synchronized (convLock) {
						header = this.storage.readFragmentHeader(convDir, seq);
						if (header == null || this.storage.isDeleted(header)) {
							continue;
						}

						// Determine role from seq parity: even=user, odd=assistant
						role = (header.seq % 2 == 0) ? "user" : "assistant";

						activeVariant = this.storage.resolveVariantIndex(header, header.activeVariantIndex);
						if (activeVariant < 0) {
							activeVariant = 0;
						}

						msgVariantCount = header.variantCount;

						// Write entire message under lock to prevent TOCTOU with getVariantSlice
						if (!first) {
							ctx.writeAndFlush(Unpooled.wrappedBuffer(comma));
						}
						first = false;

						// {"seq":N,"role":"R","model":"M","modelName":"N","variantModels\":[...],"variantModelNames\":[...],"activeVariant":N,"variants":[
						Map<Integer, String> seqModels = (header.seq % 2 == 1) ? modelIndex.get(seq) : null;
						String model = (seqModels != null) ? seqModels.get(activeVariant) : null;
						String modelField = (model != null) ? ",\"model\":\"" + this.escapeJsonString(model) + "\"" : "";
						String modelNameField = "";
						if (model != null) {
							String modelName = modelNameCache.computeIfAbsent(model, this::resolveModelName);
							if (modelName != null && !modelName.equals(model)) {
								modelNameField = ",\"modelName\":\"" + this.escapeJsonString(modelName) + "\"";
							}
						}
						String variantModelsField = "";
						String variantModelNamesField = "";
						if (header.seq % 2 == 1 && seqModels != null && !seqModels.isEmpty()) {
							StringBuilder variantModels = new StringBuilder();
							StringBuilder variantModelNames = new StringBuilder();
							for (int v = 0; v < msgVariantCount; v++) {
								if (v > 0) {
									variantModels.append(',');
									variantModelNames.append(',');
								}
								String vmodel = seqModels.getOrDefault(v, "");
								variantModels.append("\"").append(this.escapeJsonString(vmodel)).append("\"");
								String vmodelName = vmodel.isBlank() ? ""
										: modelNameCache.computeIfAbsent(vmodel, this::resolveModelName);
								variantModelNames.append("\"").append(this.escapeJsonString(vmodelName)).append("\"");
							}
							variantModelsField = ",\"variantModels\":[" + variantModels + "]";
							variantModelNamesField = ",\"variantModelNames\":[" + variantModelNames + "]";
						}
						String msgPrefix = "{\"seq\":" + seq + ",\"role\":\"" + this.escapeJsonString(role) + "\""
								+ modelField + modelNameField + variantModelsField + variantModelNamesField
								+ ",\"activeVariant\":" + activeVariant + ",\"variants\":[";
						ctx.writeAndFlush(Unpooled.wrappedBuffer(msgPrefix.getBytes(StandardCharsets.UTF_8)));

						// All variants — ChunkedFile (works with HTTPS, unlike DefaultFileRegion)
						for (int v = 0; v < msgVariantCount; v++) {
							if (v > 0) {
								ctx.writeAndFlush(Unpooled.wrappedBuffer(comma));
							}
							ctx.writeAndFlush(Unpooled.wrappedBuffer(variantPrefix));
							EasyChatStorage.FragmentSlice slice = this.storage.getVariantSlice(convDir, seq, v);
							if (slice != null && slice.length > 0) {
								try {
									ChunkedFile chunkedFile = new ChunkedFile(
											new java.io.RandomAccessFile(slice.file.toFile(), "r"), slice.offset,
											slice.length, 8192);
									ctx.writeAndFlush(chunkedFile);
								} catch (IOException e) {
									logger.warn("[EasyChat] ChunkedFile创建失败 seq={} v={}", seq, v, e);
									ctx.writeAndFlush(Unpooled.wrappedBuffer("null".getBytes(StandardCharsets.UTF_8)));
								}
							} else {
								ctx.writeAndFlush(Unpooled.wrappedBuffer("null".getBytes(StandardCharsets.UTF_8)));
							}
							ctx.writeAndFlush(Unpooled.wrappedBuffer(variantSuffix));
						}

						ctx.writeAndFlush(Unpooled.wrappedBuffer(msgSuffix));
					}
					// Lock released — DefaultFileRegion already submitted to EventLoop
				}
				ctx.writeAndFlush(Unpooled.wrappedBuffer(dataSuffix));
				logger.info("[EasyChat] 流式传输历史完成 conversation={} records={} extraVariants={} bytes={}", conversationId,
						recordCount, variantCount, totalSize);
				final EasyChatGlobalLock.Lease finalGlobalLease = globalLease;
				ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(future -> {
					finalGlobalLease.close();
					ctx.close();
				});
				globalLease = null;
			} catch (Exception e) {
				logger.info("[EasyChat] 流式传输历史失败 conversation={}", conversationId, e);
				ctx.close();
			} finally {
				if (globalLease != null) {
					globalLease.close();
				}
			}
		}
	}

	private void sendHistoryError(ChannelHandlerContext ctx, String msg) {
		byte[] body = ("{\"message\":\"" + msg + "\",\"totalSize\":0,\"recordCount\":0,\"variantCount\":0,\"data\":[]}")
				.getBytes(StandardCharsets.UTF_8);
		FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
		resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
		resp.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
		resp.content().writeBytes(body);
		ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
	}

	private EasyChatGlobalLock.Lease acquireGlobalLease(ChannelHandlerContext ctx, String operationName) {
		EasyChatGlobalLock.Lease lease = this.globalLock.tryAcquire(operationName);
		if (lease != null) {
			return lease;
		}
		this.sendGlobalLockBusy(ctx, operationName);
		return null;
	}

	/**
	 * 解析模型目标：别名解析、自动加载、远程节点路由。 返回的 ModelTarget 包含解析后的 modelId、port（本地）、nodeId（远程）以及
	 * error（失败时非空）。
	 */
	private ModelTarget resolveModelTarget(String modelId, String nodeId) {
		boolean isRemoteNode = nodeId != null && !nodeId.isEmpty();
		logger.info("[EasyChat][Node路由] X-Node-Id={}, isRemoteNode={}, modelId={}", nodeId, isRemoteNode, modelId);
		if (isRemoteNode) {
			return new ModelTarget(modelId, null, nodeId, true, null);
		}
		LlamaServerManager manager = LlamaServerManager.getInstance();
		String resolvedModelId = modelId;
		if (!manager.getLoadedProcesses().containsKey(resolvedModelId)) {
			String resolved = manager.findModelIdByAlias(resolvedModelId);
			if (resolved != null) {
				resolvedModelId = resolved;
			}
		}
		Integer modelPort = null;
		if (!manager.getLoadedProcesses().containsKey(resolvedModelId)) {
			if (AutoLoadPolicyManager.getInstance().canAutoLoad(resolvedModelId)) {
				logger.info("[EasyChat][自动加载] 尝试加载模型: model={}", resolvedModelId);
				long timeout = AutoLoadPolicyManager.getInstance().getAutoLoadTimeoutMs();
				String loadError = manager.autoLoadModelFromConfig(resolvedModelId, timeout);
				if (loadError == null) {
					modelPort = manager.getModelPort(resolvedModelId);
					if (modelPort == null) {
						return ModelTarget.error(I18N_CHAT_MODEL_PORT_NOTFOUND + ": " + resolvedModelId);
					}
					logger.info("[EasyChat][自动加载] 加载成功: model={}, port={}", resolvedModelId, modelPort);
					manager.updateModelLastUsedTime(resolvedModelId);
				} else {
					logger.warn("[EasyChat][自动加载] 加载失败: model={}, error={}", resolvedModelId, loadError);
					return ModelTarget.error(I18N_CHAT_MODEL_LOAD_FAILED + ": " + loadError);
				}
			} else {
				return ModelTarget.error(I18N_MODEL_NOT_FOUND + ": " + resolvedModelId);
			}
		} else {
			manager.updateModelLastUsedTime(resolvedModelId);
		}
		if (modelPort == null) {
			modelPort = manager.getModelPort(resolvedModelId);
			if (modelPort == null) {
				return ModelTarget.error(I18N_CHAT_MODEL_PORT_NOTFOUND + ": " + resolvedModelId);
			}
		}
		return new ModelTarget(resolvedModelId, modelPort, null, false, null);
	}

	static final class ModelTarget {
		final String resolvedModelId;
		final Integer port;
		final String nodeId;
		final boolean isRemoteNode;
		final String error;

		ModelTarget(String resolvedModelId, Integer port, String nodeId, boolean isRemoteNode, String error) {
			this.resolvedModelId = resolvedModelId;
			this.port = port;
			this.nodeId = nodeId;
			this.isRemoteNode = isRemoteNode;
			this.error = error;
		}

		static ModelTarget error(String msg) {
			return new ModelTarget(null, null, null, false, msg);
		}
	}

	/* ---- Generate title ---- */

	private static final int TITLE_GEN_TIMEOUT_MS = 60_000;
	private static final int TITLE_GEN_MAX_TOKENS = 30;

	/**
	 * Handle generate-title request.
	 * <p>
	 * Only accepts the user's first message text, builds a fixed-sampling
	 * non-stream request (temperature=0.3, max_tokens=30, thinking disabled, no
	 * multimodal), forwards to the target llama.cpp process, and returns the
	 * generated title. Does NOT use the global lock (no fragment/state access) —
	 * frontend guarantees serial.
	 */
	public void handleGenerateTitle(ChannelHandlerContext ctx, FullHttpRequest request) {
		JsonObject body;
		try {
			body = JsonUtil.parseFullHttpRequestToJsonObject(request, ctx);
		} catch (Exception e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_PARSE + ": " + e.getMessage()));
			return;
		}
		if (body == null) {
			return;
		}
		String conversationId = JsonUtil.getJsonString(body, "conversationId", "");
		if (conversationId == null || conversationId.isBlank()) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_CONVERSATION_ID_REQUIRED));
			return;
		}
		String modelId = JsonUtil.getJsonString(body, "model", "");
		if (modelId == null || modelId.isBlank()) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_MODEL_ID_REQUIRED));
			return;
		}
		String nodeId = JsonUtil.getJsonString(body, "nodeId", "");
		if (nodeId != null) {
			nodeId = nodeId.trim();
		}
		String prompt = JsonUtil.getJsonString(body, "prompt", "");
		if (prompt == null || prompt.isBlank()) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_CHAT_PARAM_PROMPT_MISSING));
			return;
		}
		String systemPrompt = JsonUtil.getJsonString(body, "systemPrompt", "");
		if (systemPrompt != null) {
			systemPrompt = systemPrompt.trim();
		}

		// Resolve model target (alias resolution + auto-load + remote node routing)
			ModelTarget modelTarget = this.resolveModelTarget(modelId, nodeId);
		if (modelTarget.error != null) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(modelTarget.error));
			return;
		}

		final String finalModelId = modelTarget.resolvedModelId;
		final int finalModelPort = modelTarget.port != null ? modelTarget.port.intValue() : 0;
		final String finalNodeId = modelTarget.nodeId;
		final boolean finalIsRemoteNode = modelTarget.isRemoteNode;
		final String finalPrompt = prompt;
		final String finalSystemPrompt = (systemPrompt != null && !systemPrompt.isBlank()) ? systemPrompt : null;
		final String finalConversationId = conversationId;

		this.worker.execute(() -> {
			try {
				if (!ctx.channel().isActive()) {
					logger.info("[EasyChat][TitleGen] channel已关闭，取消生成标题请求");
					return;
				}
				String title = finalIsRemoteNode
						? this.requestTitleFromRemoteNode(finalNodeId, finalModelId, finalPrompt, finalSystemPrompt)
						: this.requestTitleFromLocal(finalModelId, finalModelPort, finalPrompt, finalSystemPrompt);
				if (!ctx.channel().isActive()) {
					logger.info("[EasyChat][TitleGen] channel在响应前已关闭，丢弃标题");
					return;
				}
				if (title == null) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_CHAT_TITLE_GENERATE_FAILED));
					return;
				}
				Map<String, Object> data = new HashMap<>();
				data.put("title", title);
				LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
			} catch (Exception e) {
				logger.info("[EasyChat][TitleGen] 生成标题失败 conversation={}", finalConversationId, e);
				if (ctx.channel().isActive()) {
					LlamaServer.sendJsonResponse(ctx,
							ApiResponse.error(I18N_CHAT_TITLE_GENERATE_FAILED + ": " + e.getMessage()));
				}
			}
		});
	}

	private JsonObject buildTitleRequestJson(String modelId, String userPrompt, String systemPrompt) {
		String titlePrompt = "你是一个对话标题生成助手。\n" + "请根据下面的用户首条消息，生成一个简短准确的会话标题。\n" + "要求：\n"
				+ "1. 只输出标题本身，不要加引号、标签或额外说明\n" + "2. 标题语言与用户输入的语言保持一致\n" + "3. 标题尽量简短，中文控制在 18 个汉字以内，其他语言控制在 8 个词以内\n"
				+ "\n" + "[用户首条消息]\n" + userPrompt;
		JsonArray messages = new JsonArray();
		if (systemPrompt != null && !systemPrompt.isBlank()) {
			JsonObject systemMessage = new JsonObject();
			systemMessage.addProperty("role", "system");
			systemMessage.addProperty("content", systemPrompt);
			messages.add(systemMessage);
		}
		JsonObject userMessage = new JsonObject();
		userMessage.addProperty("role", "user");
		userMessage.addProperty("content", titlePrompt);
		messages.add(userMessage);

		JsonObject requestBody = new JsonObject();
		requestBody.addProperty("model", modelId);
		requestBody.addProperty("stream", false);
		requestBody.add("messages", messages);
		requestBody.addProperty("temperature", 0.3);
		requestBody.addProperty("max_tokens", TITLE_GEN_MAX_TOKENS);
		JsonObject chatTemplateKwargs = new JsonObject();
		chatTemplateKwargs.addProperty("enable_thinking", false);
		requestBody.add("chat_template_kwargs", chatTemplateKwargs);
		return requestBody;
	}

	private String requestTitleFromLocal(String modelId, int port, String userPrompt, String systemPrompt)
			throws IOException {
		String targetUrl = String.format("http://localhost:%d/v1/chat/completions", port);
		HttpURLConnection connection = (HttpURLConnection) URI.create(targetUrl).toURL().openConnection();
		try {
			connection.setRequestMethod("POST");
			connection.setRequestProperty("Content-Type", "application/json");
			connection.setConnectTimeout(TITLE_GEN_TIMEOUT_MS);
			connection.setReadTimeout(TITLE_GEN_TIMEOUT_MS);
			connection.setDoOutput(true);
			byte[] requestBody = JsonUtil.toJson(this.buildTitleRequestJson(modelId, userPrompt, systemPrompt))
					.getBytes(StandardCharsets.UTF_8);
			try (OutputStream os = connection.getOutputStream()) {
				os.write(requestBody);
				os.flush();
			}
			int responseCode = connection.getResponseCode();
			if (!(responseCode >= 200 && responseCode < 300)) {
				String errBody = this.readErrorBody(connection);
				logger.warn("[EasyChat][TitleGen] llama.cpp错误响应 code={} body={}", responseCode, errBody);
				throw new IOException(I18N_CHAT_MODEL_RESPONSE_ERROR + ": " + responseCode);
			}
			byte[] responseBytes = connection.getInputStream().readAllBytes();
			String responseBody = new String(responseBytes, StandardCharsets.UTF_8);
			return this.parseTitleFromResponse(responseBody);
		} finally {
			connection.disconnect();
		}
	}

	private String requestTitleFromRemoteNode(String nodeId, String modelId, String userPrompt, String systemPrompt) {
		JsonObject requestBody = this.buildTitleRequestJson(modelId, userPrompt, systemPrompt);
		NodeManager.HttpResult result = NodeManager.getInstance().callRemoteApi(nodeId, "POST", "v1/chat/completions",
				requestBody, TITLE_GEN_TIMEOUT_MS, TITLE_GEN_TIMEOUT_MS);
		if (!result.isSuccess()) {
			logger.warn("[EasyChat][TitleGen][Remote] 远程节点错误 code={} body={}", result.getStatusCode(),
					result.getBody());
			throw new RuntimeException(I18N_CHAT_REMOTE_RESPONSE_ERROR + ": " + result.getStatusCode());
		}
		return this.parseTitleFromResponse(result.getBody());
	}

	private String parseTitleFromResponse(String responseBody) {
		JsonObject json = JsonUtil.tryParseObject(responseBody);
		if (json == null || !json.has("choices") || !json.get("choices").isJsonArray()) {
			return null;
		}
		JsonArray choices = json.getAsJsonArray("choices");
		if (choices.size() == 0 || !choices.get(0).isJsonObject()) {
			return null;
		}
		JsonObject choice = choices.get(0).getAsJsonObject();
		String content = "";
		if (choice.has("message") && choice.get("message").isJsonObject()) {
			JsonObject message = choice.getAsJsonObject("message");
			if (message.has("content") && !message.get("content").isJsonNull()) {
				try {
					content = message.get("content").getAsString();
				} catch (Exception ignore) {
					content = "";
				}
			}
		}
		if (content == null || content.trim().isEmpty()) {
			return null;
		}
		for (String line : content.trim().split("\\r?\\n")) {
			String t = line.trim();
			if (!t.isEmpty()) {
				return t;
			}
		}
		return content.trim();
	}

	private void sendGlobalLockBusy(ChannelHandlerContext ctx, String requestedOperation) {
		EasyChatGlobalLock.LockState current = this.globalLock.current();
		String message = I18N_CHAT_GLOBAL_LOCK_BUSY;
		Map<String, Object> data = new HashMap<>();
		data.put("requestedOperation", requestedOperation);
		if (current != null) {
			if (current.operationName() != null && !current.operationName().isBlank()) {
				message += " (" + current.operationName() + ")";
				data.put("activeOperation", current.operationName());
			}
			data.put("startedAt", current.startedAt());
		}
		ApiResponse response = ApiResponse.error(message);
		response.setData(data);
		LlamaServer.sendExpressJsonResponse(ctx, HttpResponseStatus.LOCKED, response, true);
	}

	/* ---- Connection management ---- */

	@FunctionalInterface
	private interface TrackedConnection {
		void close();
	}

	private HttpURLConnection openTrackedConnection(ChannelHandlerContext ctx, String modelId, int port)
			throws IOException {
		String targetUrl = String.format("http://localhost:%d/v1/chat/completions", port);
		HttpURLConnection connection = (HttpURLConnection) URI.create(targetUrl).toURL().openConnection();
		connection.setRequestMethod("POST");
		connection.setRequestProperty("Content-Type", "application/json");
		connection.setConnectTimeout(36000000);
		connection.setReadTimeout(36000000);
		connection.setDoOutput(true);
		connection.setChunkedStreamingMode(8192);
		this.channelConnectionMap.put(ctx, connection::disconnect);
		return connection;
	}

	private void trackConnection(ChannelHandlerContext ctx, TrackedConnection connection) {
		if (connection == null) {
			return;
		}
		this.channelConnectionMap.put(ctx, connection);
	}

	private void cleanupConnection(ChannelHandlerContext ctx) {
		TrackedConnection tracked = this.channelConnectionMap.remove(ctx);
		if (tracked != null) {
			try {
				tracked.close();
			} catch (Exception ignore) {
			}
		}
	}

	private void cleanupStreamingBodyFile(Path file) {
		if (file == null) {
			return;
		}
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			logger.warn("[EasyChat][Streaming] 清理临时文件失败: {}", file, e);
		}
	}

	/**
	 * Create a FileOutputStream for request logging. Returns null if
	 * LlamaServer.logRequestBodyToFile is false or file creation fails (non-fatal).
	 */
	private OutputStream createRequestLogStream(String conversationId, String modelId) {
		if (!LlamaServer.logRequestBodyToFile) {
			return null;
		}
		try {
			Path logDir = LlamaServer.getCachePath().resolve("easy-chat");
			if (!Files.exists(logDir)) {
				Files.createDirectories(logDir);
			}
			String safeModel = modelId.replace("/", "_").replace("\\", "_");
			String safeConv = conversationId.replace("/", "_").replace("\\", "_");
			String filename = safeModel + "-" + safeConv + "-request-" + System.currentTimeMillis() + ".log";
			Path logFile = logDir.resolve(filename);
			logger.info("[EasyChat][RequestLog] {}", logFile);
			return new FileOutputStream(logFile.toFile());
		} catch (Exception e) {
			logger.warn("[EasyChat][RequestLog] 创建日志文件失败", e);
			return null;
		}
	}

	/**
	 * 在这构建请求体。
	 * 
	 * @param conn
	 * @param conversationId
	 * @param modelId
	 * @param systemPrompt
	 * @param convDir
	 * @param toolsBytes
	 * @param samplingParams
	 * @param variants
	 * @param regenerateSeq
	 * @throws IOException
	 */
	private void writeRequestBody(HttpURLConnection conn, String conversationId, String modelId, String systemPrompt,
			Path convDir, byte[] toolsBytes, JsonObject samplingParams, Map<Long, Integer> variants, Long regenerateSeq,
			Long continueSeq, byte[] transientUserMessageBytes, Path transientUserMessageFile, boolean skipHistory,
			boolean stream) throws IOException {
		OutputStream logStream = this.createRequestLogStream(conversationId, modelId);
		OutputStream primary = conn.getOutputStream();
		OutputStream os = (logStream != null) ? new TeeOutputStream(primary, logStream) : primary;
		try {
			this.requestWriter.writeRequestBody(os,
					new EasyChatRequestWriter.RequestSpec(modelId, systemPrompt, convDir, toolsBytes, samplingParams,
							false, variants, regenerateSeq, continueSeq, transientUserMessageBytes,
							transientUserMessageFile, skipHistory, stream));
		} finally {
			if (logStream != null) {
				logStream.close();
			}
		}
	}

	private boolean persistAssistantFragment(Path convDir, long aiSeq, byte[] aiBytes, boolean isRegenerate,
			Path indexPath) throws IOException {
		if (convDir == null || aiSeq < 0 || aiBytes == null) {
			return false;
		}
		EasyChatStorage.FragmentHeader existingHeader = this.storage.readFragmentHeader(convDir, aiSeq);

		// Regenerate on a live (non-deleted) fragment: append a new variant.
		if (isRegenerate && existingHeader != null && !this.storage.isDeleted(existingHeader)) {
			this.storage.appendVariant(convDir, aiSeq, aiBytes);
			return false;
		}

		// Fresh write covers three cases:
		// 1. Non-regenerate new assistant message.
		// 2. Regenerate target fragment does not exist (degraded path).
		// 3. Regenerate target fragment was deleted — MUST overwrite it instead of
		// clearing the deleted flag and appending, otherwise the old deleted
		// variant resurrects after a page refresh.
		if (isRegenerate && existingHeader == null) {
			logger.warn("[EasyChat] regenerate目标碎片不存在，降级为新写入 seq={}", aiSeq);
		}
		this.storage.writeFragment(convDir, aiSeq, System.currentTimeMillis(), aiBytes);
		if (indexPath != null) {
			long currentIndexSeq = this.storage.readIndexSeq(indexPath);
			if (currentIndexSeq <= aiSeq + 1) {
				this.storage.writeIndexSeq(indexPath, aiSeq + 1);
			} else {
				logger.warn("[EasyChat] regenerate目标碎片不存在但indexSeq更大，避免截断历史 seq={} currentIndexSeq={}", aiSeq,
						currentIndexSeq);
			}
		}
		return true;
	}

	private int computeNextVariantIndex(Path convDir, long seq, boolean isRegenerate) {
		if (!isRegenerate) {
			return 0;
		}
		try {
			EasyChatStorage.FragmentHeader existingHeader = this.storage.readFragmentHeader(convDir, seq);
			// A deleted fragment will be overwritten, so the new content becomes variant 0.
			if (existingHeader == null || this.storage.isDeleted(existingHeader)) {
				return 0;
			}
			return existingHeader.variantCount;
		} catch (IOException e) {
			return 0;
		}
	}

	private void recordModelForVariant(Path convDir, long seq, int variantIndex, String modelId,
			String conversationId) {
		if (convDir == null || seq < 0 || seq % 2 != 1 || variantIndex < 0 || modelId == null || modelId.isBlank()) {
			return;
		}
		try {
			this.storage.setModelForVariant(convDir, seq, variantIndex, modelId);
		} catch (IOException e) {
			logger.warn("[EasyChat] 写入model_index失败 seq={} variant={} conversation={}", seq, variantIndex,
					conversationId, e);
		}
	}

	private String resolveModelName(String modelId) {
		if (modelId == null || modelId.isBlank()) {
			return modelId;
		}
		try {
			LlamaServerManager manager = LlamaServerManager.getInstance();
			GGUFModel model = manager.findModelById(modelId);
			// 克隆体回退到源模型取显示名
			if (model == null) {
				String sourceModelId = manager.getSourceModelId(modelId);
				if (sourceModelId != null) {
					model = manager.findModelById(sourceModelId);
				}
			}
			if (model != null) {
				String alias = model.getAlias();
				if (alias != null && !alias.isBlank()) {
					return alias;
				}
				String name = model.getName();
				if (name != null && !name.isBlank()) {
					return name;
				}
			}
		} catch (Exception e) {
			logger.warn("[EasyChat] 解析模型名称失败 modelId={}", modelId, e);
		}
		return modelId;
	}

	private String escapeJsonString(String s) {
		if (s == null)
			return "";
		StringBuilder sb = new StringBuilder(s.length() + 16);
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
			case '"':
				sb.append('\\').append('"');
				break;
			case '\\':
				sb.append('\\').append('\\');
				break;
			case '\n':
				sb.append('\\').append('n');
				break;
			case '\r':
				sb.append('\\').append('r');
				break;
			case '\t':
				sb.append('\\').append('t');
				break;
			case '\b':
				sb.append('\\').append('b');
				break;
			case '\f':
				sb.append('\\').append('f');
				break;
			default:
				if (c < 0x20) {
					sb.append(String.format("\\u%04x", (int) c));
				} else {
					sb.append(c);
				}
				break;
			}
		}
		return sb.toString();
	}

	/* ---- SSE streaming ---- */

	/**
	 * Accumulator for streaming AI response content.
	 */
	private static final class StreamAccumulator {
		final StringBuilder content = new StringBuilder();
		final StringBuilder reasoningContent = new StringBuilder();
		// Accumulated tool calls: index -> {id, type, function:{name, arguments}}
		final Map<Integer, JsonObject> toolCalls = new HashMap<>();
		JsonObject timings = null;
		// Terminal finish_reason captured from the SSE stream / non-stream response.
		// Persisted into the fragment so the frontend can tell naturally-completed
		// responses ("stop") from user-aborted ones (null) and max_tokens-truncated
		// ones ("length"), which controls whether "continue generation" is offered.
		String finishReason = null;
		// Length of pre-existing content/reasoning seeded for "continue final message".
		// Used to distinguish "no new tokens generated" from "has content" so that a
		// continue that produces nothing (model immediately emits EOS) does not trigger
		// a fragment rewrite or usage recording.
		int seedContentLength = 0;
		int seedReasoningContentLength = 0;

		boolean hasContent() {
			return content.length() > 0 || reasoningContent.length() > 0 || !toolCalls.isEmpty();
		}

		boolean hasNewContent() {
			return content.length() > seedContentLength || reasoningContent.length() > seedReasoningContentLength
					|| !toolCalls.isEmpty();
		}
	}

	public static final class RemoteStreamTrace {
		public final long startedAt = System.currentTimeMillis();
		public long firstLineAt = -1L;
		public long lastLineAt = -1L;
		public long lastDataEventAt = -1L;
		public long lastUsefulDeltaAt = -1L;
		public long doneReceivedAt = -1L;
		public long eofAt = -1L;
		public int dataEventCount = 0;
		public int nonDataLineCount = 0;
		public String terminalFinishReason = "";
		public String endReason;
	}

	private boolean proxySseStream(ChannelHandlerContext ctx, HttpURLConnection connection,
			StreamAccumulator accumulator) throws IOException {

		Map<Integer, String> toolCallIds = new HashMap<>();
		try (BufferedReader br = new BufferedReader(
				new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {

			String line;
			while ((line = br.readLine()) != null) {
				if (!ctx.channel().isActive()) {
					logger.info("[EasyChat] 客户端断开，中止流式代理");
					return false;
				}

				if (!line.startsWith("data: ")) {
					// Pass through non-data lines
					if (!this.writeSseLine(ctx, line)) {
						return false;
					}
					continue;
				}

				String data = line.substring(6);
				if ("[DONE]".equals(data)) {
					logger.info("[EasyChat] 流式响应结束");
					return this.writeSseLine(ctx, line);
				}

				// Parse and accumulate
				JsonObject json = JsonUtil.tryParseObject(data);
				if (json != null) {
					this.accumulateDelta(json, accumulator, toolCallIds);
					if (json.has("timings")) {
						accumulator.timings = json.getAsJsonObject("timings");
					}
					boolean changed = JsonUtil.ensureToolCallIds(json, toolCallIds);
					if (changed) {
						line = "data: " + JsonUtil.toJson(json);
					}
					String terminalFinishReason = readTerminalFinishReason(json);
					if (terminalFinishReason != null) {
						accumulator.finishReason = terminalFinishReason;
						if (!this.writeSseLine(ctx, line)) {
							return false;
						}
						return this.writeSseLine(ctx, "data: [DONE]");
					}
				}

				if (!this.writeSseLine(ctx, line)) {
					return false;
				}
			}
		}
		return true;
	}

	private void accumulateDelta(JsonObject json, StreamAccumulator acc, Map<Integer, String> toolCallIds) {
		if (!json.has("choices") || !json.get("choices").isJsonArray())
			return;
		JsonArray choices = json.getAsJsonArray("choices");
		if (choices.size() == 0)
			return;
		JsonObject choice = choices.get(0).getAsJsonObject();

		// Delta
		if (!choice.has("delta") || !choice.get("delta").isJsonObject())
			return;
		JsonObject delta = choice.getAsJsonObject("delta");

		// Content
		if (delta.has("content") && !delta.get("content").isJsonNull()) {
			String content = delta.get("content").getAsString();
			if (content != null && !content.isEmpty()) {
				acc.content.append(content);
			}
		}

		// Reasoning content
		if (delta.has("reasoning_content") && !delta.get("reasoning_content").isJsonNull()) {
			String rc = delta.get("reasoning_content").getAsString();
			if (rc != null && !rc.isEmpty()) {
				acc.reasoningContent.append(rc);
			}
		}

		// Tool calls
		if (delta.has("tool_calls") && delta.get("tool_calls").isJsonArray()) {
			JsonArray tcArr = delta.getAsJsonArray("tool_calls");
			for (int i = 0; i < tcArr.size(); i++) {
				JsonObject tc = tcArr.get(i).getAsJsonObject();
				Integer idx = this.getToolCallIndex(tc);
				if (idx == null)
					continue;

				JsonObject existing = acc.toolCalls.computeIfAbsent(idx, k -> {
					JsonObject newTc = new JsonObject();
					newTc.addProperty("type", "function");
					return newTc;
				});

				// ID
				String id = JsonUtil.getJsonString(tc, "id", null);
				if (id != null && !id.isBlank() && (!existing.has("id") || existing.get("id").isJsonNull())) {
					existing.addProperty("id", id);
				}

				// Type
				String type = JsonUtil.getJsonString(tc, "type", null);
				if (type != null && !type.isBlank()) {
					existing.addProperty("type", type);
				}

				// Function
				if (tc.has("function") && tc.get("function").isJsonObject()) {
					JsonObject fn = tc.getAsJsonObject("function");
					if (!existing.has("function") || !existing.get("function").isJsonObject()) {
						existing.add("function", new JsonObject());
					}
					JsonObject existingFn = existing.getAsJsonObject("function");

					if (fn.has("name") && !fn.get("name").isJsonNull()) {
						String name = fn.get("name").getAsString();
						if (name != null && !name.isEmpty()) {
							existingFn.addProperty("name", name);
						}
					}
					if (fn.has("arguments") && !fn.get("arguments").isJsonNull()) {
						String args = fn.get("arguments").getAsString();
						if (args != null && !args.isEmpty()) {
							String current = existingFn.has("arguments") ? existingFn.get("arguments").getAsString()
									: "";
							existingFn.addProperty("arguments", current + args);
						}
					}
				}
			}
		}
	}

	private Integer getToolCallIndex(JsonObject tc) {
		if (!tc.has("index"))
			return null;
		JsonElement idxEl = tc.get("index");
		if (idxEl == null || idxEl.isJsonNull())
			return null;
		try {
			return idxEl.getAsInt();
		} catch (Exception e) {
			return null;
		}
	}

	private boolean writeSseLine(ChannelHandlerContext ctx, String line) {
		ByteBuf content = ctx.alloc().buffer();
		content.writeBytes(line.getBytes(StandardCharsets.UTF_8));
		content.writeBytes("\n".getBytes(StandardCharsets.UTF_8));
		return NettyWriteHelper.writeAndFlushBlocking(ctx, new DefaultHttpContent(content), logger, "[EasyChat]");
	}

	/* ---- AI message building ---- */

	/**
	 * Pre-seed a stream accumulator with the existing content of a persisted
	 * assistant fragment. Used for "continue final message" so that accumulated
	 * deltas are appended to the original text.
	 *
	 * @param appendContent when true (stream continue), the seed text is appended
	 *                      to the accumulator so that streaming deltas produce
	 *                      original+new; when false (non-stream continue), only the
	 *                      seed lengths are recorded so hasNewContent() can still
	 *                      detect a no-op response.
	 */
	private void seedAccumulatorFromFragment(StreamAccumulator accumulator, Path convDir, long seq, int variantIndex,
			boolean appendContent) {
		if (convDir == null || seq < 0) {
			return;
		}
		// Stream the fragment via JsonReader so we never materialize the full
		// payload byte[] nor build a JsonObject tree just to pull out two
		// string fields. Only the (possibly large) assistant content/reasoning
		// string is buffered into the accumulator, which we need anyway.
		try (InputStream in = this.storage.openVariantInputStream(convDir, seq, variantIndex)) {
			if (in == null) {
				return;
			}
			try (JsonReader reader = new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
				reader.setLenient(true);
				if (reader.peek() != JsonToken.BEGIN_OBJECT) {
					return;
				}
				reader.beginObject();
				while (reader.hasNext()) {
					String name = reader.nextName();
					JsonToken tok = reader.peek();
					if (tok == JsonToken.NULL) {
						reader.nextNull();
						continue;
					}
					if ("content".equals(name) && tok == JsonToken.STRING) {
						String content = reader.nextString();
						if (content != null && !content.isEmpty()) {
							if (appendContent) {
								accumulator.content.append(content);
							}
							accumulator.seedContentLength += content.length();
						}
					} else if ("reasoning_content".equals(name) && tok == JsonToken.STRING) {
						String reasoning = reader.nextString();
						if (reasoning != null && !reasoning.isEmpty()) {
							if (appendContent) {
								accumulator.reasoningContent.append(reasoning);
							}
							accumulator.seedReasoningContentLength += reasoning.length();
						}
					} else {
						// skipValue uses skipQuotedValue (char-by-char scan, no buffer)
						// so even multi-MB content/tool_calls variants won't OOM.
						reader.skipValue();
					}
				}
			}
		} catch (Exception e) {
			logger.warn("[EasyChat] 读取继续生成原始内容失败 seq={} variant={}", seq, variantIndex, e);
		}
	}

	/**
	 * Cheap streaming probe: returns true if the variant fragment is a JSON object
	 * whose top-level {@code "tool_calls"} is a non-empty JSON array. Scans with
	 * {@link JsonReader}, skipping irrelevant fields without materializing them;
	 * safe for multi-MB attachments.
	 */
	private boolean fragmentHasNonEmptyToolCalls(Path convDir, long seq, int variantIndex) {
		if (convDir == null || seq < 0) {
			return false;
		}
		try (InputStream in = this.storage.openVariantInputStream(convDir, seq, variantIndex)) {
			if (in == null) {
				return false;
			}
			try (JsonReader reader = new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
				reader.setLenient(true);
				if (reader.peek() != JsonToken.BEGIN_OBJECT) {
					return false;
				}
				reader.beginObject();
				while (reader.hasNext()) {
					String name = reader.nextName();
					if ("tool_calls".equals(name)) {
						JsonToken tok = reader.peek();
						if (tok == JsonToken.NULL) {
							reader.nextNull();
							continue;
						}
						if (tok != JsonToken.BEGIN_ARRAY) {
							reader.skipValue();
							continue;
						}
						reader.beginArray();
						return reader.peek() != JsonToken.END_ARRAY;
					}
					reader.skipValue();
				}
			}
		} catch (Exception e) {
			logger.warn("[EasyChat] tool_calls预检失败 seq={}", seq, e);
		}
		return false;
	}

	/**
	 * Cheap streaming probe: returns the top-level {@code "role"} string of a
	 * fragment's JSON object (or {@code null} if absent / invalid). Avoids loading
	 * the entire payload into memory — useful for sequential history scans governed
	 * by role parity.
	 */
	private String fragmentTopLevelRole(Path convDir, long seq, int variantIndex) {
		if (convDir == null || seq < 0) {
			return null;
		}
		try (InputStream in = this.storage.openVariantInputStream(convDir, seq, variantIndex)) {
			if (in == null) {
				return null;
			}
			try (JsonReader reader = new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
				reader.setLenient(true);
				if (reader.peek() != JsonToken.BEGIN_OBJECT) {
					return null;
				}
				reader.beginObject();
				while (reader.hasNext()) {
					String name = reader.nextName();
					if ("role".equals(name) && reader.peek() == JsonToken.STRING) {
						return reader.nextString();
					}
					reader.skipValue();
				}
			}
		} catch (Exception e) {
			logger.warn("[EasyChat] role预检失败 seq={}", seq, e);
		}
		return null;
	}

	private JsonObject buildAiMessage(StreamAccumulator acc) {
		JsonObject msg = new JsonObject();
		msg.addProperty("role", "assistant");

		String contentStr = acc.content.toString();
		msg.addProperty("content", contentStr);

		String reasoningStr = acc.reasoningContent.toString();
		if (!reasoningStr.isEmpty()) {
			msg.addProperty("reasoning_content", reasoningStr);
		}

		if (!acc.toolCalls.isEmpty()) {
			JsonArray tcArray = new JsonArray();
			// Sort by index to maintain order
			acc.toolCalls.entrySet().stream().sorted(Map.Entry.comparingByKey())
					.forEachOrdered(e -> tcArray.add(e.getValue()));
			msg.add("tool_calls", tcArray);
		}

		if (acc.timings != null) {
			msg.add("timings", acc.timings);
		}

		if (acc.finishReason != null && !acc.finishReason.isBlank()) {
			msg.addProperty("finish_reason", acc.finishReason);
		}

		return msg;
	}

	/* ---- Timing conversion ---- */

	private Timing timingFromJson(JsonObject timingsJson) {
		Timing timing = new Timing();
		if (timingsJson.has("cache_n"))
			timing.setCache_n(timingsJson.get("cache_n").getAsInt());
		if (timingsJson.has("prompt_n"))
			timing.setPrompt_n(timingsJson.get("prompt_n").getAsInt());
		if (timingsJson.has("prompt_ms"))
			timing.setPrompt_ms(timingsJson.get("prompt_ms").getAsDouble());
		if (timingsJson.has("prompt_per_token_ms"))
			timing.setPrompt_per_token_ms(timingsJson.get("prompt_per_token_ms").getAsDouble());
		if (timingsJson.has("prompt_per_second"))
			timing.setPrompt_per_second(timingsJson.get("prompt_per_second").getAsDouble());
		if (timingsJson.has("predicted_n"))
			timing.setPredicted_n(timingsJson.get("predicted_n").getAsInt());
		if (timingsJson.has("predicted_ms"))
			timing.setPredicted_ms(timingsJson.get("predicted_ms").getAsDouble());
		if (timingsJson.has("predicted_per_token_ms"))
			timing.setPredicted_per_token_ms(timingsJson.get("predicted_per_token_ms").getAsDouble());
		if (timingsJson.has("predicted_per_second"))
			timing.setPredicted_per_second(timingsJson.get("predicted_per_second").getAsDouble());
		if (timingsJson.has("draft_n"))
			timing.setDraft_n(timingsJson.get("draft_n").getAsInt());
		if (timingsJson.has("draft_n_accepted"))
			timing.setDraft_n_accepted(timingsJson.get("draft_n_accepted").getAsInt());
		return timing;
	}

	/* ---- State update ---- */

	private void updateStateMessageCount(String conversationId, int increment) {
		try {
			Path stateDir = LlamaServer.getCachePath().resolve("easy-chat");
			Path stateFile = stateDir.resolve("state.json");
			if (!Files.exists(stateFile))
				return;

			String json = Files.readString(stateFile, StandardCharsets.UTF_8);
			JsonObject state = JsonUtil.fromJson(json, JsonObject.class);
			if (state == null || !state.has("conversations"))
				return;

			JsonArray convs = state.getAsJsonArray("conversations");
			for (int i = 0; i < convs.size(); i++) {
				JsonElement el = convs.get(i);
				if (!el.isJsonObject())
					continue;
				JsonObject conv = el.getAsJsonObject();
				String id = JsonUtil.getJsonString(conv, "id", "");
				if (conversationId.equals(id)) {
					int current = conv.has("messageCount") ? conv.get("messageCount").getAsInt() : 0;
					conv.addProperty("messageCount", current + increment);
					break;
				}
			}
			Files.writeString(stateFile, JsonUtil.toJson(state), StandardCharsets.UTF_8);
		} catch (Exception e) {
			logger.warn("[EasyChat] 更新state.json失败 conversation={}", conversationId, e);
		}
	}

	/* ---- Assistant config lookup ---- */

	private String resolveAssistantSystemPrompt(String assistantName) {
		if (assistantName == null || assistantName.isBlank()) {
			return null;
		}
		for (Path stateFile : this.getAssistantStateFiles()) {
			String systemPrompt = this.readAssistantSystemPromptFromState(stateFile, assistantName);
			if (systemPrompt != null && !systemPrompt.isBlank()) {
				return systemPrompt;
			}
		}
		logger.info("[EasyChat] 未在同步状态中找到助手 systemPrompt assistantName={}", assistantName);
		return null;
	}

	private List<Path> getAssistantStateFiles() {
		Path cachePath = LlamaServer.getCachePath().toAbsolutePath().normalize();
		return List.of(cachePath.resolve("easy-chat").resolve("state.json"));
	}

	private String readAssistantSystemPromptFromState(Path stateFile, String assistantName) {
		if (stateFile == null || assistantName == null || assistantName.isBlank() || !Files.isRegularFile(stateFile)) {
			return null;
		}
		try {
			JsonObject state = JsonUtil.fromJson(Files.readString(stateFile, StandardCharsets.UTF_8), JsonObject.class);
			if (state == null || !state.has("assistants") || !state.get("assistants").isJsonArray()) {
				return null;
			}
			JsonArray assistants = state.getAsJsonArray("assistants");
			for (JsonElement element : assistants) {
				if (element == null || !element.isJsonObject()) {
					continue;
				}
				JsonObject assistant = element.getAsJsonObject();
				String name = JsonUtil.getJsonString(assistant, "name", "");
				if (!assistantName.equals(name)) {
					continue;
				}
				String systemPrompt = JsonUtil.getJsonString(assistant, "systemPrompt", "");
				return systemPrompt == null || systemPrompt.isBlank() ? null : systemPrompt;
			}
		} catch (Exception e) {
			logger.warn("[EasyChat] 读取助手同步状态失败 stateFile={}", stateFile, e);
		}
		return null;
	}

	public Path getFragmentsDir() throws IOException {
		return this.storage.getFragmentsDir();
	}

	/* ---- Delete operations ---- */

	/**
	 * Delete an entire conversation: remove fragment directory.
	 */
	public void deleteConversation(String conversationId) throws Exception {
		Path fragmentsBase = this.getFragmentsDir();
		Path dir = fragmentsBase.resolve(conversationId);
		if (!Files.exists(dir)) {
			logger.info("[EasyChat] 删除会话: 目录不存在 conversationId={}", conversationId);
			return;
		}
		Files.walkFileTree(dir, new java.nio.file.SimpleFileVisitor<Path>() {
			@Override
			public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs)
					throws IOException {
				Files.delete(file);
				return java.nio.file.FileVisitResult.CONTINUE;
			}

			@Override
			public java.nio.file.FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
				Files.delete(d);
				return java.nio.file.FileVisitResult.CONTINUE;
			}
		});
		logger.info("[EasyChat] 删除会话成功 conversationId={}", conversationId);
	}

	/**
	 * Delete a whole message or a single variant within a message.
	 */
	public Integer deleteMessage(String conversationId, long seq, Integer variantIndex) throws Exception {
		Path dir = this.storage.getConversationDir(conversationId);
		if (!Files.exists(dir)) {
			throw new IOException("Conversation directory not found: " + conversationId);
		}
		Object convLock = this.conversationLock(conversationId);
		synchronized (convLock) {
			if (variantIndex != null) {
				return this.storage.deleteVariant(dir, seq, variantIndex);
			} else {
				this.storage.deleteMessage(dir, seq);
				return null;
			}
		}
	}

	private String readErrorBody(HttpURLConnection conn) throws IOException {
		try (BufferedReader br = new BufferedReader(
				new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
			StringBuilder sb = new StringBuilder();
			String line;
			while ((line = br.readLine()) != null) {
				sb.append(line);
			}
			return sb.toString();
		}
	}

	private void sendJsonPayloadResponse(ChannelHandlerContext ctx, int code, String contentType, byte[] bytes) {
		byte[] safeBytes = bytes == null ? new byte[0] : bytes;
		FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(code),
				Unpooled.wrappedBuffer(safeBytes));
		resp.headers().set(HttpHeaderNames.CONTENT_TYPE,
				contentType == null || contentType.isBlank() ? "application/json; charset=UTF-8" : contentType);
		resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, safeBytes.length);
		resp.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		resp.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
		resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
		ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
	}

	private void accumulateNonStreamResponse(JsonObject json, StreamAccumulator accumulator) {
		if (json == null || accumulator == null) {
			return;
		}
		if (json.has("timings") && json.get("timings").isJsonObject()) {
			accumulator.timings = json.getAsJsonObject("timings").deepCopy();
		}
		JsonObject choice = null;
		if (json.has("choices") && json.get("choices").isJsonArray()) {
			JsonArray choices = json.getAsJsonArray("choices");
			if (choices.size() > 0 && choices.get(0).isJsonObject()) {
				choice = choices.get(0).getAsJsonObject();
			}
		}
		if (choice != null && choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()) {
			try {
				String fr = choice.get("finish_reason").getAsString();
				if (fr != null && !fr.isBlank()) {
					accumulator.finishReason = fr.trim();
				}
			} catch (Exception ignore) {
			}
		}
		JsonObject message = choice != null && choice.has("message") && choice.get("message").isJsonObject()
				? choice.getAsJsonObject("message")
				: null;
		if (message != null) {
			this.appendJsonString(accumulator.content, message.get("content"));
			this.appendJsonString(accumulator.reasoningContent, message.get("reasoning_content"));
			if (message.has("tool_calls") && message.get("tool_calls").isJsonArray()) {
				JsonArray toolCalls = message.getAsJsonArray("tool_calls");
				for (int i = 0; i < toolCalls.size(); i++) {
					JsonElement toolCall = toolCalls.get(i);
					if (toolCall != null && toolCall.isJsonObject()) {
						accumulator.toolCalls.put(i, toolCall.getAsJsonObject().deepCopy());
					}
				}
			}
		}
	}

	private void appendJsonString(StringBuilder sb, JsonElement element) {
		if (sb == null || element == null || element.isJsonNull()) {
			return;
		}
		try {
			if (element.isJsonPrimitive()) {
				sb.append(element.getAsString());
			}
		} catch (Exception ignore) {
		}
	}

	private void sendErrorResponse(ChannelHandlerContext ctx, int code, String body) {
		FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(code));
		resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
		resp.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
		resp.content().writeBytes(bytes);
		ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
	}

	private void sendOpenAIError(ChannelHandlerContext ctx, int httpStatus, String message) {
		String type = "server_error";
		if (httpStatus == 400)
			type = "invalid_request_error";
		if (httpStatus == 404)
			type = "invalid_request_error";
		if (httpStatus >= 500)
			type = "server_error";

		JsonObject error = new JsonObject();
		error.addProperty("message", message);
		error.addProperty("type", type);
		error.add("param", com.google.gson.JsonNull.INSTANCE);

		JsonObject response = new JsonObject();
		response.add("error", error);

		FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
				HttpResponseStatus.valueOf(httpStatus));
		resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
		byte[] bytes = JsonUtil.toJson(response).getBytes(StandardCharsets.UTF_8);
		resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
		resp.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		resp.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
		resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
		resp.content().writeBytes(bytes);
		ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
	}

	/**
	 * Update a specific variant's payload in an existing fragment file. Reads
	 * header, replaces the target variant, rewrites the entire file.
	 */
	public void updateFragmentVariant(Path dir, long seq, int variantIndex, byte[] newPayload) throws IOException {
		this.storage.updateVariant(dir, seq, variantIndex, newPayload);
		logger.info("[EasyChat] 更新碎片变体成功 seq={} variantIndex={} newLength={}", seq, variantIndex, newPayload.length);
	}

	/**
	 * Handle stream-chat request routed to a remote node. Builds the request body,
	 * forwards to remote node via NodeManager, and proxies the SSE stream back.
	 */
	private void handleRemoteNodeRequest(ChannelHandlerContext ctx, String conversationId, String nodeId,
			String modelId, String systemPrompt, Path convDir, byte[] toolsBytes, JsonObject samplingParams,
			Map<Long, Integer> variants, Long regenerateSeq, Long continueSeq, byte[] transientUserMessageBytes,
			Path transientUserMessageFile, boolean skipHistory, boolean stream, StreamAccumulator accumulator)
			throws Exception {

		logger.info("[EasyChat][Remote] 转发到远程节点 nodeId={}, model={}, conversation={}", nodeId, modelId, conversationId);

		// Forward to remote node
		NodeManager nodeManager = NodeManager.getInstance();
		OutputStream remoteLogStream = this.createRequestLogStream(conversationId, modelId);
		NodeManager.StreamResult streamResult = nodeManager.callRemoteApiStreaming(nodeId, "POST",
				"v1/chat/completions", output -> {
					OutputStream os = (remoteLogStream != null) ? new TeeOutputStream(output, remoteLogStream) : output;
					try {
						this.requestWriter.writeRequestBody(os,
								new EasyChatRequestWriter.RequestSpec(modelId, systemPrompt, convDir, toolsBytes,
										samplingParams, false, variants, regenerateSeq, continueSeq,
										transientUserMessageBytes, transientUserMessageFile, skipHistory, stream));
					} finally {
						if (remoteLogStream != null) {
							try {
								remoteLogStream.close();
							} catch (Exception ignore) {
							}
						}
					}
				}, null, STREAM_TIMEOUT_MS);
		this.trackConnection(ctx, streamResult::abort);

		int responseCode = streamResult.getStatusCode();
		logger.info("[EasyChat][Remote] 远程节点响应码: {} conversation={}", responseCode, conversationId);

		if (!(responseCode >= 200 && responseCode < 300)) {
			String errBody;
			try {
				errBody = new String(streamResult.getBody().readAllBytes(), StandardCharsets.UTF_8);
			} catch (Exception e) {
				errBody = "Remote node error: " + responseCode;
			}
			logger.info("[EasyChat][Remote] 远程节点错误响应 code={} body={}", responseCode, errBody);
			this.sendErrorResponse(ctx, responseCode, errBody);
			return;
		}

		if (!stream) {
			byte[] responseBytes = streamResult.getBody().readAllBytes();
			String responseBody = new String(responseBytes, StandardCharsets.UTF_8);
			this.accumulateNonStreamResponse(JsonUtil.tryParseObject(responseBody), accumulator);
			this.sendJsonPayloadResponse(ctx, responseCode, "application/json; charset=UTF-8", responseBytes);
			return;
		}

		HttpResponse sseResp = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		sseResp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream; charset=UTF-8");
		sseResp.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
		sseResp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
		sseResp.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		sseResp.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
		if (!NettyWriteHelper.writeAndFlushBlocking(ctx, sseResp, logger, "[EasyChat][Remote]")) {
			return;
		}

		this.proxySseStreamFromRemote(ctx, streamResult.getBody(), accumulator);
	}

	/**
	 * Proxy SSE stream from a remote node's InputStream to the client.
	 */
	private RemoteStreamTrace proxySseStreamFromRemote(ChannelHandlerContext ctx, java.io.InputStream inputStream,
			StreamAccumulator accumulator) throws IOException {

		RemoteStreamTrace trace = new RemoteStreamTrace();
		Map<Integer, String> toolCallIds = new HashMap<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				long now = System.currentTimeMillis();
				if (trace.firstLineAt < 0L) {
					trace.firstLineAt = now;
				}
				trace.lastLineAt = now;
				if (!ctx.channel().isActive()) {
					logger.info("[EasyChat][Remote] 客户端断开，中止流式代理");
					trace.endReason = "client_inactive";
					return trace;
				}

				if (!line.startsWith("data: ")) {
					trace.nonDataLineCount += 1;
					if (!this.writeSseLine(ctx, line)) {
						trace.endReason = "write_failed_non_data";
						return trace;
					}
					continue;
				}

				trace.dataEventCount += 1;
				trace.lastDataEventAt = now;
				String data = line.substring(6);
				if ("[DONE]".equals(data)) {
					trace.doneReceivedAt = now;
					trace.endReason = "done";
					if (!this.writeSseLine(ctx, line)) {
						trace.endReason = "write_failed_done";
					}
					return trace;
				}

				JsonObject json = JsonUtil.tryParseObject(data);
				if (json != null) {
					int contentLength = accumulator.content.length();
					int reasoningLength = accumulator.reasoningContent.length();
					int toolCallCount = accumulator.toolCalls.size();
					boolean hadTimings = accumulator.timings != null;
					this.accumulateDelta(json, accumulator, toolCallIds);
					if (json.has("timings")) {
						accumulator.timings = json.getAsJsonObject("timings");
					}
					if (accumulator.content.length() != contentLength
							|| accumulator.reasoningContent.length() != reasoningLength
							|| accumulator.toolCalls.size() != toolCallCount
							|| (!hadTimings && accumulator.timings != null) || json.has("timings")) {
						trace.lastUsefulDeltaAt = now;
					}
					boolean changed = JsonUtil.ensureToolCallIds(json, toolCallIds);
					if (changed) {
						line = "data: " + JsonUtil.toJson(json);
					}
					String terminalFinishReason = readTerminalFinishReason(json);
					if (terminalFinishReason != null) {
						trace.terminalFinishReason = terminalFinishReason;
						accumulator.finishReason = terminalFinishReason;
						if (!this.writeSseLine(ctx, line)) {
							trace.endReason = "write_failed_terminal_chunk";
							return trace;
						}
						trace.doneReceivedAt = System.currentTimeMillis();
						trace.endReason = "finish_reason";
						if (!this.writeSseLine(ctx, "\ndata: [DONE]")) {
							trace.endReason = "write_failed_synthetic_done";
						}
						return trace;
					}
				}

				if (!this.writeSseLine(ctx, line)) {
					trace.endReason = "write_failed_data";
					return trace;
				}
			}
		}
		trace.eofAt = System.currentTimeMillis();
		trace.endReason = "eof";
		return trace;
	}

	/**
	 * Clean up tracked connection on channel inactive. Also releases the global
	 * lease if the request has not yet been handed off to the worker.
	 */
	public void channelInactive(ChannelHandlerContext ctx) {
		this.cleanupConnection(ctx);
		EasyChatGlobalLock.Lease lease = this.channelLeaseMap.remove(ctx);
		if (lease != null) {
			try {
				lease.close();
			} catch (Exception ignore) {
			}
		}
	}
}
