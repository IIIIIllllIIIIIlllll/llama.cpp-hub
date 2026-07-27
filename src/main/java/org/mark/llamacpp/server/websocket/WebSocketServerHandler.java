package org.mark.llamacpp.server.websocket;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

import org.mark.llamacpp.server.tools.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.*;

/**
 * WebSocket服务器处理器
 */
public class WebSocketServerHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
    
    private static final Logger logger = LoggerFactory.getLogger(WebSocketServerHandler.class);
    
    // WebSocket管理器
    private final WebSocketManager wsManager = WebSocketManager.getInstance();
    
    // 当前连接的ID
    private String connectionId;
    
    //
    private boolean connected = false;
    
    // JSON解析器
    private static final Gson gson = JsonUtil.gson();
    
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) throws Exception {
    	
    	if(!this.connected) {
    		this.connected = true;
            this.connectionId = this.wsManager.addConnection(ctx);
            
            // 发送欢迎消息
            String welcomeMessage = String.format(
                "{\"type\":\"welcome\",\"connectionId\":\"%s\",\"message\":\"欢迎连接到WebSocket服务器!\",\"timestamp\":%d}",
                this.connectionId,
                System.currentTimeMillis()
            );
            ctx.channel().writeAndFlush(new TextWebSocketFrame(welcomeMessage));
    	}
        // 处理不同类型的WebSocket帧
        if (frame instanceof TextWebSocketFrame) {
            handleTextFrame(ctx, (TextWebSocketFrame) frame);
        } else if (frame instanceof PingWebSocketFrame) {
            handlePingFrame(ctx, (PingWebSocketFrame) frame);
        } else if (frame instanceof PongWebSocketFrame) {
            handlePongFrame(ctx, (PongWebSocketFrame) frame);
        } else if (frame instanceof CloseWebSocketFrame) {
            handleCloseFrame(ctx, (CloseWebSocketFrame) frame);
        } else {
        	this.connected = false;
            throw new UnsupportedOperationException(String.format("%s frame types not supported", frame.getClass().getName()));
        }
    }
    
    /**
     * 处理文本帧
     */
    private void handleTextFrame(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        String request = frame.text();
        logger.info("WebSocket收到消息[{}]: {}", this.connectionId, request);
        
        try {
            // 尝试解析JSON消息
            JsonElement jsonElement = gson.fromJson(request, JsonElement.class);
            JsonObject jsonObject = jsonElement.getAsJsonObject();
            
            if (jsonObject.has("type")) {
                String messageType = jsonObject.get("type").getAsString();
                
                switch (messageType) {
                    case "connect":
                        // 处理连接确认消息
                        handleConnectMessage(ctx, jsonObject);
                        break;
                    default:
                        
                        break;
                }
            } else {
                // 非JSON格式消息或没有type字段，忽略
            }
        } catch (JsonSyntaxException e) {
            // JSON 解析失败，忽略
        } catch (Exception e) {
            
            ctx.channel().writeAndFlush(new TextWebSocketFrame("服务器处理消息时发生错误"));
        }
    }
    
    /**
     * 处理连接确认消息
     */
    private void handleConnectMessage(ChannelHandlerContext ctx, JsonObject message) {
        
        
        // 标记连接为已确认状态
        if (this.connectionId != null) {
            this.wsManager.confirmConnection(this.connectionId);
        }
        
        // 发送确认响应
        String response = String.format(
            "{\"type\":\"connect_ack\",\"connectionId\":\"%s\",\"message\":\"连接已确认\",\"timestamp\":%d}",
            this.connectionId,
            System.currentTimeMillis()
        );
        ctx.channel().writeAndFlush(new TextWebSocketFrame(response));
    }
    
    /**
     * 处理Ping帧
     */
    private void handlePingFrame(ChannelHandlerContext ctx, PingWebSocketFrame frame) {
        
        ctx.channel().writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
    }
    
    /**
     * 处理Pong帧
     */
    private void handlePongFrame(ChannelHandlerContext ctx, PongWebSocketFrame frame) {
        
    }
    
    /**
     * 处理关闭帧
     */
    private void handleCloseFrame(ChannelHandlerContext ctx, CloseWebSocketFrame frame) {
        
        if (this.connectionId != null) {
            this.wsManager.removeConnection(this.connectionId);
        }
        ctx.close();
    }
    
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
    }
    
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        // 无条件清理：握手后未发 "connect" 就断开的客户端也会在 WebSocketManager 留下条目
        if (this.connectionId != null) {
            this.wsManager.removeConnection(this.connectionId);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (this.connectionId != null) {
            this.wsManager.removeConnection(this.connectionId);
        }
        ctx.close();
    }
    
    /**
     * 向所有连接的客户端广播消息
     */
    public static void broadcast(String message) {
        WebSocketManager.getInstance().broadcast(message);
    }
    
    /**
     * 获取当前连接数
     */
    public static int getConnectionCount() {
        return WebSocketManager.getInstance().getConnectionCount();
    }
}
