package org.mark.llamacpp.server.controller;

import java.io.RandomAccessFile;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.service.EasyChatAvatarService;
import org.mark.llamacpp.server.service.EasyChatBackgroundService;
import org.mark.llamacpp.server.service.EasyChatGlobalLock;
import org.mark.llamacpp.server.service.EasyChatService;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.tools.ParamTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.handler.stream.ChunkedFile;

/**
 * EasyChat 后端接口。
 * <p>
 * 提供流式聊天、消息更新等功能。
 * </p>
 */
public class EasyChatController implements BaseController {

	private static final Logger logger = LoggerFactory.getLogger(EasyChatController.class);
	private final EasyChatGlobalLock globalLock = EasyChatGlobalLock.getInstance();

	private static final String PATH_STREAM_CHAT = "/api/chat/stream-chat";
	private static final String PATH_MESSAGE_UPDATE = "/api/chat/message/update";
	private static final String PATH_GENERATE_TITLE = "/api/chat/generate-title";
	private static final String PATH_AVATAR_UPLOAD = "/api/chat/avatar/upload";
	private static final String PATH_AVATAR_GET = "/api/chat/avatar/get";
	private static final String PATH_BACKGROUND_UPLOAD = "/api/chat/background/upload";
	private static final String PATH_BACKGROUND_LIST = "/api/chat/background/list";
	private static final String PATH_BACKGROUND_ACTIVE = "/api/chat/background/active";
	private static final String PATH_BACKGROUND_OPACITY = "/api/chat/background/opacity";
	private static final String PATH_BACKGROUND_PREFIX = "/api/chat/background/";
	private static final String PATH_BACKGROUND_IMAGE = "/api/chat/background/image/";
	private static final String PATH_BACKGROUND_THUMB = "/api/chat/background/thumb/";

	private static final long MAX_AVATAR_UPLOAD_BYTES = 1L * 1024L * 1024L;
	private static final long MAX_BACKGROUND_UPLOAD_BYTES = 2L * 1024L * 1024L;

	@Override
	public void inactive(ChannelHandlerContext ctx) {
		EasyChatService.getInstance().channelInactive(ctx);
	}

	@Override
	public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request)
			throws RequestMethodException {
		if (uri.startsWith(PATH_STREAM_CHAT)) {
			if (request.method() == HttpMethod.GET) {
				this.handleStreamChatHistory(ctx, request);
			} else {
				this.handleStreamChatRequest(ctx, request);
			}
			return true;
		}
		if (uri.startsWith(PATH_MESSAGE_UPDATE)) {
			this.withGlobalLock(ctx, "easy-chat.message.update", () -> this.handleMessageUpdateRequest(ctx, request));
			return true;
		}
		if (uri.startsWith(PATH_GENERATE_TITLE)) {
			this.handleGenerateTitleRequest(ctx, request);
			return true;
		}
		if (uri.startsWith(PATH_AVATAR_UPLOAD)) {
			this.handleAvatarUpload(ctx, request);
			return true;
		}
		if (uri.startsWith(PATH_AVATAR_GET)) {
			this.handleAvatarGet(ctx, request);
			return true;
		}
		if (uri.startsWith(PATH_BACKGROUND_UPLOAD)) {
			this.handleBackgroundUpload(ctx, request);
			return true;
		}
		if (uri.startsWith(PATH_BACKGROUND_LIST)) {
			this.handleBackgroundList(ctx, request);
			return true;
		}
		if (uri.startsWith(PATH_BACKGROUND_ACTIVE)) {
			this.handleBackgroundActive(ctx, request);
			return true;
		}
		if (uri.startsWith(PATH_BACKGROUND_OPACITY)) {
			this.handleBackgroundOpacity(ctx, request);
			return true;
		}
		if (uri.startsWith(PATH_BACKGROUND_IMAGE)) {
			this.handleBackgroundImageGet(ctx, request);
			return true;
		}
		if (uri.startsWith(PATH_BACKGROUND_THUMB)) {
			this.handleBackgroundThumbGet(ctx, request);
			return true;
		}
		if (uri.startsWith(PATH_BACKGROUND_PREFIX) && request.method() == HttpMethod.DELETE) {
			this.handleBackgroundDelete(ctx, request);
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
		String message = "Easy Chat 正在执行其它操作，请稍后再试";
		Map<String, Object> data = new HashMap<>();
		data.put("requestedOperation", requestedOperation);
		if (current != null) {
			if (current.operationName() != null && !current.operationName().isBlank()) {
				message += "（当前操作: " + current.operationName() + "）";
				data.put("activeOperation", current.operationName());
			}
			data.put("startedAt", current.startedAt());
		}
		ApiResponse response = ApiResponse.error(message);
		response.setData(data);
		LlamaServer.sendExpressJsonResponse(ctx, HttpResponseStatus.LOCKED, response, true);
	}

	private void handleStreamChatRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		EasyChatService.getInstance().handleStreamChat(ctx, request);
	}

	private void handleGenerateTitleRequest(ChannelHandlerContext ctx, FullHttpRequest request)
			throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		EasyChatService.getInstance().handleGenerateTitle(ctx, request);
	}

	private void handleStreamChatHistory(ChannelHandlerContext ctx, FullHttpRequest request) {
		Map<String, String> params = ParamTool.getQueryParam(request.uri());
		String conversationId = params.getOrDefault("conversationId", "").trim();
		EasyChatService.getInstance().handleStreamChatHistory(ctx, conversationId);
	}

	private void handleMessageUpdateRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		try {
			JsonObject body = JsonUtil.parseFullHttpRequestToJsonObject(request, ctx);
			if (body == null) {
				return;
			}
			String conversationId = JsonUtil.getJsonString(body, "conversationId", "");
			if (conversationId == null || conversationId.isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("conversationId 为必填项"));
				return;
			}
			if (!body.has("seq") || body.get("seq").isJsonNull()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("seq 为必填项"));
				return;
			}
			long seq = body.get("seq").getAsLong();
			int variantIndex = body.has("variantIndex") && !body.get("variantIndex").isJsonNull()
				? body.get("variantIndex").getAsInt() : 0;
			JsonObject payloadObj = body.has("payload") && !body.get("payload").isJsonNull()
				? body.getAsJsonObject("payload") : null;
			if (payloadObj == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("payload 为必填项"));
				return;
			}
			byte[] payloadBytes = JsonUtil.toJson(payloadObj).getBytes(StandardCharsets.UTF_8);
			EasyChatService service = EasyChatService.getInstance();
			Path fragmentsBase = service.getFragmentsDir();
			Path convDir = fragmentsBase.resolve(conversationId);
			service.updateFragmentVariant(convDir, seq, variantIndex, payloadBytes);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(Map.of("updated", true, "seq", seq)));
		} catch (Exception e) {
			logger.info("更新 easy-chat 消息失败", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("更新消息失败: " + e.getMessage()));
		}
	}

	/* ---- Avatar ---- */

	private void handleAvatarUpload(ChannelHandlerContext ctx, FullHttpRequest request) {
		if (request.method() != HttpMethod.POST) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("只支持POST请求"));
			return;
		}
		String assistantId = ParamTool.getQueryParam(request.uri()).get("assistantId");
		if (assistantId == null || assistantId.trim().isEmpty()) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少assistantId参数"));
			return;
		}
		assistantId = assistantId.trim();

		if (request.content() == null || request.content().readableBytes() <= 0) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
			return;
		}
		if (request.content().readableBytes() > MAX_AVATAR_UPLOAD_BYTES) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体过大"));
			return;
		}

		HttpPostRequestDecoder decoder = null;
		try {
			decoder = new HttpPostRequestDecoder(new DefaultHttpDataFactory(false), request);
			List<InterfaceHttpData> datas = decoder.getBodyHttpDatas();
			FileUpload upload = null;
			for (InterfaceHttpData d : datas) {
				if (d == null) {
					continue;
				}
				if (d.getHttpDataType() == InterfaceHttpData.HttpDataType.FileUpload) {
					FileUpload fu = (FileUpload) d;
					if (fu.isCompleted() && fu.length() > 0) {
						upload = fu;
						break;
					}
				}
			}
			if (upload == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("未找到上传文件"));
				return;
			}
			if (upload.length() > MAX_AVATAR_UPLOAD_BYTES) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("头像文件超过最大限制: 1MB"));
				return;
			}
			byte[] bytes = upload.get();
			if (bytes == null || bytes.length == 0) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("文件内容为空"));
				return;
			}

			EasyChatAvatarService avatarService = EasyChatAvatarService.getInstance();
			String savedName = avatarService.saveAvatar(assistantId, bytes, upload.getFilename(), upload.getContentType());
			Map<String, Object> data = new HashMap<>();
			data.put("assistantId", assistantId);
			data.put("name", savedName);
			data.put("url", PATH_AVATAR_GET + "?assistantId=" + URLEncoder.encode(assistantId, StandardCharsets.UTF_8));
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
			logger.info("[EasyChatController] 上传头像成功 assistantId={} file={}", assistantId, savedName);
		} catch (IllegalArgumentException e) {
			logger.info("[EasyChatController] 上传头像参数错误 assistantId={}", assistantId, e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(e.getMessage()));
		} catch (Exception e) {
			logger.info("[EasyChatController] 上传头像失败 assistantId={}", assistantId, e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("上传失败: " + e.getMessage()));
		} finally {
			if (decoder != null) {
				try {
					decoder.destroy();
				} catch (Exception ignore) {
				}
			}
		}
	}

	private void handleAvatarGet(ChannelHandlerContext ctx, FullHttpRequest request) {
		if (request.method() != HttpMethod.GET) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("只支持GET请求"));
			return;
		}
		String assistantId = ParamTool.getQueryParam(request.uri()).get("assistantId");
		if (assistantId == null || assistantId.trim().isEmpty()) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少assistantId参数"));
			return;
		}
		assistantId = assistantId.trim();

		try {
			Path file = EasyChatAvatarService.getInstance().findAvatarFile(assistantId);
			if (file == null || !Files.isRegularFile(file)) {
				LlamaServer.sendExpressJsonResponse(ctx, HttpResponseStatus.NOT_FOUND, ApiResponse.error("头像不存在"), true);
				return;
			}
			sendAvatarFile(ctx, file, EasyChatAvatarService.inferImageContentType(file));
		} catch (IllegalArgumentException e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(e.getMessage()));
		} catch (Exception e) {
			logger.info("[EasyChatController] 读取头像失败 assistantId={}", assistantId, e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("读取头像失败: " + e.getMessage()));
		}
	}

	private static void sendAvatarFile(ChannelHandlerContext ctx, Path file, String contentType) throws Exception {
		sendImageFile(ctx, file, contentType, "no-cache");
	}

	private static void sendImageFile(ChannelHandlerContext ctx, Path file, String contentType, String cacheControl)
			throws Exception {
		RandomAccessFile raf = null;
		try {
			raf = new RandomAccessFile(file.toFile(), "r");
			long fileLength = raf.length();

			HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
			response.headers().set(HttpHeaderNames.CONTENT_LENGTH, fileLength);
			response.headers().set(HttpHeaderNames.CONTENT_TYPE,
					contentType == null ? "application/octet-stream" : contentType);
			response.headers().set(HttpHeaderNames.CACHE_CONTROL, cacheControl);
			response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
			response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type");
			response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS");

			ctx.write(response);
			final RandomAccessFile finalRaf = raf;
			ctx.write(new ChunkedFile(raf, 0, fileLength, 8192), ctx.newProgressivePromise());
			ChannelFuture last = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
			raf = null;
			last.addListener(new ChannelFutureListener() {
				@Override
				public void operationComplete(ChannelFuture future) {
					try {
						finalRaf.close();
					} catch (Exception ignore) {
					}
					ctx.close();
				}
			});
		} finally {
			if (raf != null) {
				try {
					raf.close();
				} catch (Exception ignore) {
				}
			}
		}
	}

	/* ---- Background ---- */

	private void handleBackgroundUpload(ChannelHandlerContext ctx, FullHttpRequest request) {
		if (request.method() != HttpMethod.POST) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("只支持POST请求"));
			return;
		}
		if (request.content() == null || request.content().readableBytes() <= 0) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
			return;
		}
		if (request.content().readableBytes() > MAX_BACKGROUND_UPLOAD_BYTES) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("背景图片超过最大限制: 2MB"));
			return;
		}

		HttpPostRequestDecoder decoder = null;
		try {
			decoder = new HttpPostRequestDecoder(new DefaultHttpDataFactory(false), request);
			List<InterfaceHttpData> datas = decoder.getBodyHttpDatas();
			FileUpload upload = null;
			for (InterfaceHttpData d : datas) {
				if (d == null) {
					continue;
				}
				if (d.getHttpDataType() == InterfaceHttpData.HttpDataType.FileUpload) {
					FileUpload fu = (FileUpload) d;
					if (fu.isCompleted() && fu.length() > 0) {
						upload = fu;
						break;
					}
				}
			}
			if (upload == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("未找到上传文件"));
				return;
			}
			if (upload.length() > MAX_BACKGROUND_UPLOAD_BYTES) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("背景图片超过最大限制: 2MB"));
				return;
			}
			byte[] bytes = upload.get();
			if (bytes == null || bytes.length == 0) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("文件内容为空"));
				return;
			}

			EasyChatBackgroundService.BackgroundItem item = EasyChatBackgroundService.getInstance().saveBackground(bytes,
					upload.getFilename(), upload.getContentType());
			Map<String, Object> data = new HashMap<>();
			data.put("id", item.getId());
			data.put("name", item.getName());
			data.put("createdAt", item.getCreatedAt());
			data.put("imageUrl", PATH_BACKGROUND_IMAGE + item.getId());
			data.put("thumbUrl", PATH_BACKGROUND_THUMB + item.getId());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
			logger.info("[EasyChatController] 上传背景成功 id={}", item.getId());
		} catch (IllegalArgumentException e) {
			logger.info("[EasyChatController] 上传背景参数错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(e.getMessage()));
		} catch (Exception e) {
			logger.info("[EasyChatController] 上传背景失败", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("上传失败: " + e.getMessage()));
		} finally {
			if (decoder != null) {
				try {
					decoder.destroy();
				} catch (Exception ignore) {
				}
			}
		}
	}

	private void handleBackgroundList(ChannelHandlerContext ctx, FullHttpRequest request) {
		if (request.method() != HttpMethod.GET) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("只支持GET请求"));
			return;
		}
		try {
			EasyChatBackgroundService.BackgroundCatalog catalog = EasyChatBackgroundService.getInstance().getCatalog();
			Map<String, Object> data = new HashMap<>();
			data.put("activeId", catalog.getActiveId());
			data.put("opacity", catalog.getOpacity());
			List<Map<String, Object>> items = new ArrayList<>();
			if (catalog.getItems() != null) {
				for (EasyChatBackgroundService.BackgroundItem item : catalog.getItems()) {
					Map<String, Object> m = new HashMap<>();
					m.put("id", item.getId());
					m.put("name", item.getName());
					m.put("createdAt", item.getCreatedAt());
					m.put("imageUrl", PATH_BACKGROUND_IMAGE + item.getId());
					m.put("thumbUrl", PATH_BACKGROUND_THUMB + item.getId());
					items.add(m);
				}
			}
			data.put("items", items);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("[EasyChatController] 获取背景列表失败", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取背景列表失败: " + e.getMessage()));
		}
	}

	private void handleBackgroundActive(ChannelHandlerContext ctx, FullHttpRequest request) {
		if (request.method() != HttpMethod.POST) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("只支持POST请求"));
			return;
		}
		try {
			JsonObject body = JsonUtil.parseFullHttpRequestToJsonObject(request, ctx);
			if (body == null) {
				return;
			}
			String id = JsonUtil.getJsonString(body, "id", "");
			if (id == null || id.isEmpty() || !body.has("id") || body.get("id").isJsonNull()) {
				EasyChatBackgroundService.getInstance().setActive(null);
				LlamaServer.sendJsonResponse(ctx, ApiResponse.success(Map.of("activeId", "")));
			} else {
				EasyChatBackgroundService.getInstance().setActive(id);
				LlamaServer.sendJsonResponse(ctx, ApiResponse.success(Map.of("activeId", id)));
			}
		} catch (IllegalArgumentException e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(e.getMessage()));
		} catch (Exception e) {
			logger.info("[EasyChatController] 设置当前背景失败", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("设置失败: " + e.getMessage()));
		}
	}

	private void handleBackgroundOpacity(ChannelHandlerContext ctx, FullHttpRequest request) {
		if (request.method() != HttpMethod.POST) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("只支持POST请求"));
			return;
		}
		try {
			JsonObject body = JsonUtil.parseFullHttpRequestToJsonObject(request, ctx);
			if (body == null) {
				return;
			}
			int opacity = JsonUtil.getJsonInt(body, "opacity", -1);
			if (opacity < 0 || opacity > 100) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("透明度必须在 0-100 之间"));
				return;
			}
			EasyChatBackgroundService.getInstance().setOpacity(opacity);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(Map.of("opacity", opacity)));
		} catch (Exception e) {
			logger.info("[EasyChatController] 设置背景透明度失败", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("设置失败: " + e.getMessage()));
		}
	}

	private void handleBackgroundDelete(ChannelHandlerContext ctx, FullHttpRequest request) {
		String uri = request.uri();
		String path = uri;
		int q = path.indexOf('?');
		if (q >= 0) {
			path = path.substring(0, q);
		}
		if (!path.startsWith(PATH_BACKGROUND_PREFIX)) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("路径错误"));
			return;
		}
		String rest = path.substring(PATH_BACKGROUND_PREFIX.length());
		if (rest.isEmpty()) {
			// 清空全部
			try {
				EasyChatBackgroundService.getInstance().clearAll();
				LlamaServer.sendJsonResponse(ctx, ApiResponse.success(Map.of("cleared", true)));
			} catch (Exception e) {
				logger.info("[EasyChatController] 清空背景失败", e);
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("清空失败: " + e.getMessage()));
			}
			return;
		}
		try {
			boolean deleted = EasyChatBackgroundService.getInstance().deleteBackground(rest);
			if (!deleted) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("背景图片不存在"));
				return;
			}
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(Map.of("deleted", true)));
		} catch (IllegalArgumentException e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(e.getMessage()));
		} catch (Exception e) {
			logger.info("[EasyChatController] 删除背景失败 id={}", rest, e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("删除失败: " + e.getMessage()));
		}
	}

	private void handleBackgroundImageGet(ChannelHandlerContext ctx, FullHttpRequest request) {
		handleBackgroundFileGet(ctx, request, PATH_BACKGROUND_IMAGE, false);
	}

	private void handleBackgroundThumbGet(ChannelHandlerContext ctx, FullHttpRequest request) {
		handleBackgroundFileGet(ctx, request, PATH_BACKGROUND_THUMB, true);
	}

	private void handleBackgroundFileGet(ChannelHandlerContext ctx, FullHttpRequest request, String prefix, boolean thumb) {
		if (request.method() != HttpMethod.GET) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("只支持GET请求"));
			return;
		}
		String path = request.uri();
		int q = path.indexOf('?');
		if (q >= 0) {
			path = path.substring(0, q);
		}
		if (!path.startsWith(prefix)) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("路径错误"));
			return;
		}
		String id = path.substring(prefix.length());
		try {
			EasyChatBackgroundService service = EasyChatBackgroundService.getInstance();
			Path file = thumb ? service.findThumbnailFile(id) : service.findBackgroundFile(id);
			if (file == null || !Files.isRegularFile(file)) {
				LlamaServer.sendExpressJsonResponse(ctx, HttpResponseStatus.NOT_FOUND, ApiResponse.error("背景图片不存在"), true);
				return;
			}
			String contentType = EasyChatBackgroundService.inferImageContentType(file);
			String cacheControl = thumb ? "public, max-age=31536000" : "public, max-age=31536000";
			sendImageFile(ctx, file, contentType, cacheControl);
		} catch (IllegalArgumentException e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(e.getMessage()));
		} catch (Exception e) {
			logger.info("[EasyChatController] 读取背景文件失败 id={}", id, e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("读取背景文件失败: " + e.getMessage()));
		}
	}
}
