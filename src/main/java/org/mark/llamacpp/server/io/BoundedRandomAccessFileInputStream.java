package org.mark.llamacpp.server.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Path;

/**
 * InputStream backed by a {@link RandomAccessFile} starting at {@code offset}
 * and yielding at most {@code length} bytes. Used to stream a bounded slice of
 * a fragment file (header-prefixed binary layout) without materializing the
 * whole file in JVM memory.
 */
public final class BoundedRandomAccessFileInputStream extends InputStream {

	private final RandomAccessFile raf;
	private long remaining;

	public BoundedRandomAccessFileInputStream(Path file, long offset, long length) throws IOException {
		if (file == null) {
			throw new IllegalArgumentException("file");
		}
		if (offset < 0 || length < 0) {
			throw new IllegalArgumentException("offset/length");
		}
		this.raf = new RandomAccessFile(file.toFile(), "r");
		this.raf.seek(offset);
		this.remaining = length;
	}

	@Override
	public int read() throws IOException {
		if (remaining <= 0) {
			return -1;
		}
		int b = raf.read();
		if (b >= 0) {
			remaining--;
		}
		return b;
	}

	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		if (remaining <= 0) {
			return -1;
		}
		if (len <= 0) {
			return 0;
		}
		int toRead = (int) Math.min(len, remaining);
		int n = raf.read(b, off, toRead);
		if (n > 0) {
			remaining -= n;
		}
		return n;
	}

	@Override
	public long skip(long n) throws IOException {
		if (n <= 0 || remaining <= 0) {
			return 0;
		}
		long toSkip = Math.min(n, remaining);
		long skipped = raf.skipBytes(toSkip < Integer.MAX_VALUE ? (int) toSkip : Integer.MAX_VALUE);
		remaining -= skipped;
		return skipped;
	}

	@Override
	public int available() throws IOException {
		long available = remaining < Integer.MAX_VALUE ? (int) remaining : Integer.MAX_VALUE;
		return available > 0 ? (int) available : 0;
	}

	@Override
	public void close() throws IOException {
		raf.close();
	}
}
