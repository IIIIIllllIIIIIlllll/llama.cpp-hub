package org.mark.llamacpp.server;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;

/**
 * 全局共享的 Netty EventLoopGroup。
 * <p>
 * 主服务（NettyWebServer）、Ollama 兼容层、LMStudio 兼容层、MCP 服务共用同一组
 * boss/worker 事件循环，避免每个服务独立创建 NioEventLoopGroup 造成的
 * PooledByteBufAllocator arena（堆 + direct）与线程重复开销。
 * <p>
 * 注意：单个服务停止时只允许关闭自己的 Channel，<b>不允许</b>关闭共享组；
 * 共享组仅在 JVM 退出时由 shutdown hook 通过 {@link #shutdownAll()} 优雅关闭。
 * 线程均为 daemon，不影响 JVM 退出语义。
 */
public final class NettySharedGroups {

    private static final Logger logger = LoggerFactory.getLogger(NettySharedGroups.class);

    private static final Object LOCK = new Object();

    private static volatile NioEventLoopGroup bossGroup;
    private static volatile NioEventLoopGroup workerGroup;

    private NettySharedGroups() {
    }

    /**
     * 共享 boss 组（仅负责 accept，1 线程足够）
     */
    public static EventLoopGroup boss() {
        NioEventLoopGroup group = bossGroup;
        if (group == null) {
            synchronized (LOCK) {
                group = bossGroup;
                if (group == null) {
                    group = new NioEventLoopGroup(1, newDaemonThreadFactory("netty-shared-boss"));
                    bossGroup = group;
                }
            }
        }
        return group;
    }

    /**
     * 共享 worker 组（4 线程，与原主服务一致；管理台与兼容层均为低并发 IO 场景）
     */
    public static EventLoopGroup worker() {
        NioEventLoopGroup group = workerGroup;
        if (group == null) {
            synchronized (LOCK) {
                group = workerGroup;
                if (group == null) {
                    group = new NioEventLoopGroup(4, newDaemonThreadFactory("netty-shared-worker"));
                    workerGroup = group;
                }
            }
        }
        return group;
    }

    /**
     * 关闭共享组，仅应在 JVM 退出（shutdown hook）时调用
     */
    public static void shutdownAll() {
        synchronized (LOCK) {
            if (bossGroup != null) {
                try {
                    bossGroup.shutdownGracefully();
                } catch (Exception e) {
                    logger.error("关闭共享 boss 线程组失败", e);
                }
                bossGroup = null;
            }
            if (workerGroup != null) {
                try {
                    workerGroup.shutdownGracefully();
                } catch (Exception e) {
                    logger.error("关闭共享 worker 线程组失败", e);
                }
                workerGroup = null;
            }
        }
    }

    private static ThreadFactory newDaemonThreadFactory(String namePrefix) {
        AtomicInteger index = new AtomicInteger(0);
        return runnable -> {
            Thread thread = new Thread(runnable, namePrefix + "-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
