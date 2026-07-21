package org.mark.llamacpp.server.util;

/**
 * 尾部保留字符缓冲：只保留最后 N 个字符，用于子进程输出等只需尾部的场景，避免无界 StringBuilder 撑爆堆。
 * 内存上界约为 2 倍容量（超过 2 倍容量时截断到容量）。
 */
public class TailCharBuffer {

	private final int capacity;
	private final StringBuilder buf;

	public TailCharBuffer(int capacity) {
		if (capacity <= 0) {
			throw new IllegalArgumentException("capacity must be positive");
		}
		this.capacity = capacity;
		this.buf = new StringBuilder(Math.min(capacity, 8192));
	}

	public synchronized TailCharBuffer append(CharSequence s) {
		this.buf.append(s);
		this.trimIfNeeded();
		return this;
	}

	public synchronized TailCharBuffer append(char c) {
		this.buf.append(c);
		this.trimIfNeeded();
		return this;
	}

	private void trimIfNeeded() {
		if (this.buf.length() > this.capacity * 2) {
			this.buf.delete(0, this.buf.length() - this.capacity);
		}
	}

	@Override
	public synchronized String toString() {
		return this.buf.toString();
	}
}
