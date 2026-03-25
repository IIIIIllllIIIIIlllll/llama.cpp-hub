package org.mark.llamacpp.ollama;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.mark.llamacpp.ollama.channel.OllamaRouterHandler;
import org.mark.llamacpp.server.channel.OpenAIChatStreamingHandler;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.tools.ParamTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.stream.ChunkedWriteHandler;

import java.util.concurrent.atomic.AtomicLong;


/**
 * 	Ollama的兼容层。
 */
public class Ollama {
	/**
	 * 	
	 */
	private static final Logger logger = LoggerFactory.getLogger(Ollama.class);
	
	private static final int MAX_HTTP_CONTENT_LENGTH = 16 * 1024 * 1024;
	
	
	private volatile Thread worker;
	
	private final Object lifecycleLock = new Object();
	private final AtomicLong generation = new AtomicLong(0L);
	private volatile long activeGeneration = 0L;
	
	private volatile EventLoopGroup bossGroup;
	private volatile EventLoopGroup workerGroup;
	private volatile Channel serverChannel;
	
	/**
	 * 	
	 */
	private static final Ollama INSTANCE = new Ollama();
	
	/**
	 * 	
	 * @return
	 */
	public static Ollama getInstance() {
		return INSTANCE;
	}
	
	/**
	 * 	默认端口
	 */
	private volatile int port = 11434;
	
	
	private Ollama() {
		
	}
	
	
	
	public void start() {
		this.start(this.port);
	}
	
	public int getPort() {
		return this.port;
	}
	
	public boolean isRunning() {
		Channel ch = this.serverChannel;
		return ch != null && ch.isActive();
	}
	
	public void start(int port) {
		if (port <= 0 || port > 65535) {
			throw new IllegalArgumentException("invalid port: " + port);
		}
		synchronized (lifecycleLock) {
			if (this.isRunning() && this.port == port) {
				return;
			}
			if (this.worker != null && this.worker.isAlive()) {
				this.stop();
			}
			
			this.port = port;
			long gen = generation.incrementAndGet();
			this.activeGeneration = gen;
			
			Thread t = new Thread(() -> this.runServer(gen), "ollama-netty-" + port);
			t.setDaemon(true);
			this.worker = t;
			t.start();
		}
	}
	
	private void runServer(long gen) {
		EventLoopGroup localBossGroup = new NioEventLoopGroup(1);
		EventLoopGroup localWorkerGroup = new NioEventLoopGroup();
		
		synchronized (lifecycleLock) {
			if (this.activeGeneration == gen) {
				this.bossGroup = localBossGroup;
				this.workerGroup = localWorkerGroup;
			}
		}
		
		try {
			ServerBootstrap bootstrap = new ServerBootstrap();
			bootstrap.group(localBossGroup, localWorkerGroup)
					.channel(NioServerSocketChannel.class)
					.option(ChannelOption.SO_BACKLOG, 1024)
					.childOption(ChannelOption.SO_KEEPALIVE, true)
					.childHandler(new ChannelInitializer<SocketChannel>() {
						@Override
						protected void initChannel(SocketChannel ch) throws Exception {
							ch.pipeline()
									.addLast(new HttpServerCodec())
									.addLast(new OpenAIChatStreamingHandler())
									.addLast(new HttpObjectAggregator(MAX_HTTP_CONTENT_LENGTH))
									.addLast(new ChunkedWriteHandler())
									.addLast(new OllamaRouterHandler());
						}
						@Override
						public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
							logger.info("Failed to initialize a channel. Closing: " + ctx.channel(), cause);
							ctx.close();
						}
					});
			
			int bindPort = this.port;
			ChannelFuture future = bootstrap.bind(bindPort).sync();
			synchronized (lifecycleLock) {
				if (this.activeGeneration == gen) {
					this.serverChannel = future.channel();
				} else {
					future.channel().close();
				}
			}
			
			logger.info("Ollama服务启动成功，端口: {}", bindPort);
			logger.info("访问地址: http://localhost:{}", bindPort);
			
			future.channel().closeFuture().sync();
		} catch (InterruptedException e) {
			logger.info("服务器被中断", e);
			Thread.currentThread().interrupt();
		} catch (Exception e) {
			logger.info("服务器启动失败", e);
		} finally {
			try {
				localBossGroup.shutdownGracefully();
			} catch (Exception ignore) {
			}
			try {
				localWorkerGroup.shutdownGracefully();
			} catch (Exception ignore) {
			}
			
			synchronized (lifecycleLock) {
				if (this.activeGeneration == gen) {
					this.serverChannel = null;
					this.bossGroup = null;
					this.workerGroup = null;
					this.worker = null;
				}
			}
			logger.info("服务器已关闭");
		}
	}
	
	public void stop() {
		synchronized (lifecycleLock) {
			Channel ch = this.serverChannel;
			if (ch != null) {
				try {
					ch.close();
				} catch (Exception ignore) {
				}
			}
			
			EventLoopGroup bg = this.bossGroup;
			if (bg != null) {
				try {
					bg.shutdownGracefully();
				} catch (Exception ignore) {
				}
			}
			
			EventLoopGroup wg = this.workerGroup;
			if (wg != null) {
				try {
					wg.shutdownGracefully();
				} catch (Exception ignore) {
				}
			}
		}
	}
	
	/**
	 * 	发送错误消息。
	 * @param ctx
	 * @param status
	 * @param message
	 */
	public static void sendOllamaError(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
		Map<String, Object> payload = new HashMap<>();
		payload.put("error", message == null ? "" : message);
		sendOllamaJson(ctx, status == null ? HttpResponseStatus.INTERNAL_SERVER_ERROR : status, payload);
	}
	
	/**
	 * 	发送JSON消息。
	 * @param ctx
	 * @param status
	 * @param data
	 */
	public static void sendOllamaJson(ChannelHandlerContext ctx, HttpResponseStatus status, Object data) {
		String json = JsonUtil.toJson(data);
		byte[] content = json.getBytes(StandardCharsets.UTF_8);
		
		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
		response.headers().set(HttpHeaderNames.DATE, ParamTool.getDate());
		response.content().writeBytes(content);
		
		ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				ctx.close();
			}
		});
	}
	
	/**
	 * 	发送JSON消息，但是响应头中带chunked。
	 * @param ctx
	 * @param status
	 * @param data
	 */
	public static void sendOllamaChunkedJson(ChannelHandlerContext ctx, HttpResponseStatus status, Object data) {
		String json = JsonUtil.toJson(data);
		byte[] content = json.getBytes(StandardCharsets.UTF_8);
		
		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
		response.headers().set(HttpHeaderNames.DATE, ParamTool.getDate());
		response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, "chunked");
		response.content().writeBytes(content);
		
		ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				ctx.close();
			}
		});
	}
}
