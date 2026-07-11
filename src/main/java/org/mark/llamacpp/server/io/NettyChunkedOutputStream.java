package org.mark.llamacpp.server.io;

import java.io.IOException;
import java.io.OutputStream;

import org.slf4j.Logger;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpContent;

/**
 * Endpoint-local {@link OutputStream} that writes gzip-compressed bytes to a
 * Netty {@link ChannelHandlerContext} in HTTP chunked encoding.
 *
 * <p>
 * Designed for use inside a single virtual-thread worker. Each chunk is written
 * via {@link NettyWriteHelper#writeAndFlushBlocking} to enforce backpressure.
 * </p>
 *
 * <p>
 * {@link #close()} only flushes remaining buffered bytes; it never closes the
 * Netty channel. The caller is responsible for sending
 * {@link io.netty.handler.codec.http.LastHttpContent} and releasing resources.
 * </p>
 */
public final class NettyChunkedOutputStream extends OutputStream {

	private final ChannelHandlerContext ctx;
	private final byte[] buffer;
	private int position;
	private boolean closed;
	private final Logger logger;
	private final String logPrefix;

	public NettyChunkedOutputStream(ChannelHandlerContext ctx, int bufferSize, Logger logger, String logPrefix) {
		this.ctx = ctx;
		this.buffer = new byte[bufferSize];
		this.position = 0;
		this.logger = logger;
		this.logPrefix = logPrefix;
	}

	@Override
	public void write(int b) throws IOException {
		ensureOpen();
		if (position >= buffer.length) {
			flush();
		}
		buffer[position++] = (byte) b;
	}

	@Override
	public void write(byte[] b, int off, int len) throws IOException {
		ensureOpen();
		while (len > 0) {
			int space = buffer.length - position;
			if (space == 0) {
				flush();
				space = buffer.length - position;
			}
			int chunk = Math.min(space, len);
			System.arraycopy(b, off, buffer, position, chunk);
			position += chunk;
			off += chunk;
			len -= chunk;
		}
	}

	@Override
	public void flush() throws IOException {
		ensureOpen();
		if (position == 0) {
			return;
		}
		ByteBuf content = Unpooled.wrappedBuffer(buffer, 0, position);
		if (!NettyWriteHelper.writeAndFlushBlocking(ctx, new DefaultHttpContent(content), logger, logPrefix)) {
			position = 0;
			closed = true;
			throw new IOException(logPrefix + " 写入失败，客户端可能已断开");
		}
		position = 0;
	}

	@Override
	public void close() throws IOException {
		if (closed) {
			return;
		}
		try {
			flush();
		} finally {
			closed = true;
		}
	}

	private void ensureOpen() throws IOException {
		if (closed) {
			throw new IOException("NettyChunkedOutputStream 已关闭");
		}
	}
}
