package org.mark.llamacpp.lmstudio.channel;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import org.mark.llamacpp.server.NettySharedGroups;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.stream.ChunkedWriteHandler;


/**
 * 	测试阶段。
 */
public class LMStudioWebServer implements AutoCloseable {
	
	private static final Logger logger = LoggerFactory.getLogger(LMStudioWebServer.class);
	
	private final String host;
	private final int port;
	private final int maxContentLength;
	
	private final AtomicBoolean started = new AtomicBoolean(false);
	private Channel serverChannel;
	
	public LMStudioWebServer(int port) {
		this("0.0.0.0", port, 16 * 1024 * 1024);
	}
	
	public LMStudioWebServer(String host, int port, int maxContentLength) {
		this.host = Objects.requireNonNull(host, "host");
		this.port = port;
		this.maxContentLength = Math.max(1024 * 1024, maxContentLength);
	}
	
	public synchronized ChannelFuture start() {
		if (!started.compareAndSet(false, true)) {
			throw new IllegalStateException("server already started");
		}
		
		ServerBootstrap bootstrap = new ServerBootstrap();
		bootstrap.group(NettySharedGroups.boss(), NettySharedGroups.worker())
				.channel(NioServerSocketChannel.class)
				.option(ChannelOption.SO_BACKLOG, 1024)
				.childOption(ChannelOption.SO_KEEPALIVE, true)
				.childHandler(new ChannelInitializer<SocketChannel>() {
					@Override
					protected void initChannel(SocketChannel ch) throws Exception {
						ch.pipeline()
								.addLast(new HttpServerCodec())
								.addLast(new HttpObjectAggregator(maxContentLength))
								.addLast(new ChunkedWriteHandler())
								.addLast(new LMStudioWebServiceHandler());
					}
				});
		
		InetSocketAddress address = new InetSocketAddress(host, port);
		ChannelFuture future = bootstrap.bind(address);
		future.addListener((ChannelFuture f) -> {
			if (f.isSuccess()) {
				serverChannel = f.channel();
				logger.info("LMStudio Web服务启动成功: http://{}:{}/", host, port);
			} else {
				logger.error("LMStudio Web服务启动失败: {}:{}", host, port, f.cause());
				close();
			}
		});
		return future;
	}
	
	public boolean isRunning() {
		Channel ch = this.serverChannel;
		return ch != null && ch.isActive();
	}
	
	public InetSocketAddress localAddress() {
		Channel ch = this.serverChannel;
		if (ch == null) {
			return null;
		}
		if (ch.localAddress() instanceof InetSocketAddress addr) {
			return addr;
		}
		return null;
	}
	
	@Override
	public synchronized void close() {
		Channel ch = this.serverChannel;
		if (ch != null) {
			ch.close();
			this.serverChannel = null;
		}
		started.set(false);
	}
}

