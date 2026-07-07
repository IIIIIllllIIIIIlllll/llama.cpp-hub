package org.mark.llamacpp.server.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.struct.ChatSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;


/**
 * 	前端会话管理。暂时弃坑。
 */
@Deprecated
public class SessionService {

	
	private static Logger logger = LoggerFactory.getLogger(SessionService.class);

	private static final String I18N_METHOD_POST_ONLY = "common.method.post.only";
	private static final String I18N_METHOD_GET_ONLY = "common.method.get.only";
	private static final String I18N_SESSION_CREATE_FAILED = "api.error.session.create.failed";
	private static final String I18N_SESSION_LIST_FAILED = "api.error.session.list.failed";
	
	private static final List<ChatSession> chatSessions = new CopyOnWriteArrayList<>();
	
	
	
	
	
	
	public ApiResponse handleChatSessionCreate(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			if (request.method() != HttpMethod.POST) {
				return ApiResponse.error(I18N_METHOD_POST_ONLY);
			}
			long createdAt = System.currentTimeMillis();
			long id = generateChatSessionId(createdAt);
			chatSessions.add(new ChatSession(id, createdAt));
			saveChatSessionToDatabase(id, createdAt);

			Map<String, Object> data = new HashMap<>();
			data.put("id", Long.toString(id));
			data.put("createdAt", createdAt);
			return ApiResponse.success(data);
		} catch (Exception e) {
			logger.info("创建会话失败", e);
			return ApiResponse.error(I18N_SESSION_CREATE_FAILED + ": " + e.getMessage());
		}
	}

	public ApiResponse handleChatSessionList(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			if (request.method() != HttpMethod.GET) {
				return ApiResponse.error(I18N_METHOD_GET_ONLY);
			}
			List<Map<String, Object>> sessions = new ArrayList<>();
			for (ChatSession s : chatSessions) {
				Map<String, Object> item = new HashMap<>();
				item.put("id", Long.toString(s.getId()));
				item.put("createdAt", s.getCreatedAt());
				sessions.add(item);
			}
			Map<String, Object> data = new HashMap<>();
			data.put("sessions", sessions);
			return ApiResponse.success(data);
		} catch (Exception e) {
			logger.info("获取会话列表失败", e);
			return ApiResponse.error(I18N_SESSION_LIST_FAILED + ": " + e.getMessage());
		}
	}
	
	
	private static long generateChatSessionId(long nowMillis) {
		return nowMillis * 1000L + ThreadLocalRandom.current().nextInt(0, 1000);
	}

	private void saveChatSessionToDatabase(long id, long createdAt) {
		
		
		
	}
}
