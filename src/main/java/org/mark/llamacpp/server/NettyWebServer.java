package org.mark.llamacpp.server;

import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;

import org.mark.llamacpp.server.channel.HttpHttpsUnificationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;

/**
 * Netty Web 服务器封装。
 * <p>
 * 负责 HTTP/HTTPS 统一端口绑定、SSL 上下文初始化、通道注册与关闭。
 * 支持以"纯 HTTP"模式启动（sslContext 为 null 时不启用 TLS、不做 HTTP→HTTPS 重定向），
 * 用于为不支持自签名证书的应用提供独立的 HTTP 专用端口。
 * LlamaServer 只调用 start()/stop()，不暴露 Netty 技术细节。
 */
public class NettyWebServer {

    private static final Logger logger = LoggerFactory.getLogger(NettyWebServer.class);

    /**
     * WebSocket 地址
     */
    private static final String WEBSOCKET_PATH = "/ws";

    /**
     * 最大 HTTP 内容长度：16MB
     */
    private static final int MAX_HTTP_CONTENT_LENGTH = 16 * 1024 * 1024;

    private final int port;
    private final String certPath;
    private final String password;
    private final boolean httpsEnabled;

    /**
     * 绑定失败时是否终止整个进程。主 Web 端口为 true（无法绑定则整个服务无意义）；
     * 辅助的纯 HTTP 端口为 false（绑定失败仅记录日志，不影响主服务运行）。
     */
    private final boolean exitOnBindFailure;

    /**
     * 本服务实例使用的 SSL 上下文；为 null 表示纯 HTTP（不启用 TLS、不做 HTTP→HTTPS 重定向）。
     * 与共享静态上下文解耦，避免独立 HTTP 端口误继承主端口的 SSL 上下文而被强制重定向。
     */
    private SslContext sslContext;

    /**
     * 共享 HTTPS SSL 上下文，供其他兼容服务使用。
     */
    private static SslContext sharedSslContext;

    public static SslContext getHttpsSslContext() {
        return sharedSslContext;
    }

    /**
     * 所有 Web 服务 Netty ServerChannel 集合，用于重启时统一关闭释放端口
     */
    private static final java.util.List<Channel> webServerChannels = new java.util.ArrayList<>();
    private static final Object CHANNEL_LOCK = new Object();

    public NettyWebServer(int port, boolean httpsEnabled, String certPath, String password) {
        this(port, httpsEnabled, certPath, password, true);
    }

    public NettyWebServer(int port, boolean httpsEnabled, String certPath, String password, boolean exitOnBindFailure) {
        this.port = port;
        this.httpsEnabled = httpsEnabled;
        this.certPath = certPath;
        this.password = password;
        this.exitOnBindFailure = exitOnBindFailure;
    }

    /**
     * 启动 Web 服务器（阻塞当前线程，直到服务器关闭）
     */
    public void start() {
        initHttpsContext();
        bindOpenAI(port);
    }

    /**
     * 关闭所有已注册的 Web 服务端口
     */
    public static void stop() {
        synchronized (CHANNEL_LOCK) {
            for (Channel ch : webServerChannels) {
                try {
                    ch.close().await();
                } catch (Exception e) {
                    logger.error("关闭 Web 服务通道失败", e);
                }
            }
            webServerChannels.clear();
        }
    }

    /**
     * 初始化 HTTPS 上下文
     */
    private void initHttpsContext() {
        if (!httpsEnabled) {
            logger.info("HTTPS未启用，使用HTTP协议启动");
            return;
        }
        try {
            File keystoreFile = new File(certPath);
            if (keystoreFile.isDirectory()) {
                File[] candidates = keystoreFile.listFiles((dir, name) -> {
                    String lower = name.toLowerCase();
                    return lower.endsWith(".p12") || lower.endsWith(".pfx") || lower.endsWith(".jks")
                            || lower.endsWith(".keystore");
                });
                if (candidates == null || candidates.length == 0) {
                    logger.info("HTTPS证书目录中未找到证书文件: {}, 使用HTTP协议启动", certPath);
                    return;
                }
                File chosen = null;
                for (File f : candidates) {
                    if (f.getName().toLowerCase().endsWith(".p12")) {
                        chosen = f;
                        break;
                    }
                }
                if (chosen == null)
                    chosen = candidates[0];
                keystoreFile = chosen;
            }
            if (!keystoreFile.exists()) {
                logger.info("HTTPS证书文件不存在: {}, 使用HTTP协议启动", certPath);
                return;
            }
            String storeType = "PKCS12";
            String fileName = keystoreFile.getName().toLowerCase();
            if (fileName.endsWith(".jks") || fileName.endsWith(".keystore")) {
                storeType = "JKS";
            }
            KeyStore keyStore = KeyStore.getInstance(storeType);
            try (FileInputStream fis = new FileInputStream(keystoreFile)) {
                keyStore.load(fis, password != null ? password.toCharArray() : new char[0]);
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, password != null ? password.toCharArray() : new char[0]);
            SslContext built = SslContextBuilder.forServer(kmf).build();
            // 同时写入实例字段与共享静态字段：实例字段决定本端口行为，静态字段供其他兼容服务复用
            this.sslContext = built;
            sharedSslContext = built;
            logger.info("HTTPS证书加载成功: {}", keystoreFile.getAbsolutePath());
        } catch (Exception e) {
            logger.info("HTTPS证书加载失败: {}, 使用HTTP协议启动", e.getMessage());
        }
    }

    /**
     * 绑定 OpenAI 服务端口
     */
    private void bindOpenAI(int port) {
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(NettySharedGroups.boss(), NettySharedGroups.worker()).channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 1024)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(32 * 1024, 48 * 1024))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            ch.pipeline().addLast(new HttpHttpsUnificationHandler(
                                    sslContext, port, WEBSOCKET_PATH, MAX_HTTP_CONTENT_LENGTH));
                        }

                        @Override
                        public void exceptionCaught(io.netty.channel.ChannelHandlerContext ctx, Throwable cause) throws Exception {
                            logger.info("Failed to initialize a channel. Closing: " + ctx.channel(), cause);
                            ctx.close();
                        }
                    });

            ChannelFuture future = bootstrap.bind(port).sync();
            logger.info("Web服务启动成功，端口: {} {}", port,
                    (sslContext == null) ? "(纯HTTP，无TLS/重定向)" : "(HTTP/HTTPS统一)");
            logger.info("访问地址: http://localhost:{}", port);
            registerWebServerChannel(future.channel());

            future.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            logger.info("服务器被中断", e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (exitOnBindFailure) {
                logger.error("OpenAI服务启动失败，端口 {} 可能已被占用，退出进程", port, e);
                System.exit(1);
            } else {
                // 辅助 HTTP 端口失败不应终止整个进程，主服务继续运行
                logger.error("辅助HTTP端口启动失败，端口 {} 可能已被占用，忽略并继续运行", port, e);
            }
        } finally {
            // 共享 EventLoopGroup 由 shutdown hook 统一关闭（NettySharedGroups.shutdownAll），此处不关闭
            logger.info("[{}]服务器已关闭", port);
        }
    }

    private void registerWebServerChannel(Channel ch) {
        synchronized (CHANNEL_LOCK) {
            webServerChannels.add(ch);
        }
    }
}
