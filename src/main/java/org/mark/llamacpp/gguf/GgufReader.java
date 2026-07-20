package org.mark.llamacpp.gguf;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * GGUF 文件流式读取器（小端序）。
 * <p>
 * 替代旧的 64MB 内存映射（MappedByteBuffer）方案：内存映射在 JDK 17+ 下无法
 * 通过反射可靠 unmap，只能等 GC 回收 cleaner，频繁读取会导致堆外内存持续累积。
 * 本实现基于 BufferedInputStream 顺序读取，仅使用少量堆内存，并跟踪当前读取位置
 * （{@link #position()}）以支持张量数据区偏移计算。
 * <p>
 * 数值的装箱类型与旧 ByteBuffer 实现保持一致：UINT8/UINT16→Integer、INT8→Byte、
 * INT16→Short、UINT32→Long、INT32→Integer、UINT64/INT64→Long、FLOAT32→Float、
 * FLOAT64→Double、BOOL→Boolean。
 */
final class GgufReader implements Closeable {

    // GGUF value type 常量
    static final int UINT8 = 0;
    static final int INT8 = 1;
    static final int UINT16 = 2;
    static final int INT16 = 3;
    static final int UINT32 = 4;
    static final int INT32 = 5;
    static final int FLOAT32 = 6;
    static final int BOOL = 7;
    static final int STRING = 8;
    static final int ARRAY = 9;
    static final int UINT64 = 10;
    static final int INT64 = 11;
    static final int FLOAT64 = 12;

    private final BufferedInputStream in;
    private final byte[] buf = new byte[8];
    private long position = 0;

    GgufReader(File file) throws IOException {
        this.in = new BufferedInputStream(new FileInputStream(file), 256 * 1024);
    }

    /**
     * 当前已读取的字节数（即文件中的逻辑位置）
     */
    long position() {
        return position;
    }

    void readFully(byte[] dst) throws IOException {
        readFully(dst, dst.length);
    }

    void readFully(byte[] dst, int len) throws IOException {
        int offset = 0;
        while (offset < len) {
            int n = in.read(dst, offset, len - offset);
            if (n < 0) {
                throw new IOException("Unexpected EOF at offset " + offset + " of " + len);
            }
            offset += n;
        }
        position += len;
    }

    void skipNBytes(long n) throws IOException {
        if (n <= 0) {
            return;
        }
        long skipped = 0;
        while (skipped < n) {
            long s = in.skip(n - skipped);
            if (s <= 0) {
                if (in.read() < 0) {
                    throw new IOException("Unexpected EOF while skipping " + n + " bytes");
                }
                s = 1;
            }
            skipped += s;
        }
        position += n;
    }

    int readUInt8() throws IOException {
        readFully(buf, 1);
        return buf[0] & 0xFF;
    }

    int readUInt16() throws IOException {
        readFully(buf, 2);
        return ((buf[1] & 0xFF) << 8) | (buf[0] & 0xFF);
    }

    int readUInt32() throws IOException {
        readFully(buf, 4);
        return ((buf[3] & 0xFF) << 24) | ((buf[2] & 0xFF) << 16) | ((buf[1] & 0xFF) << 8) | (buf[0] & 0xFF);
    }

    long readUInt64() throws IOException {
        readFully(buf);
        return ((long) (buf[7] & 0xFF) << 56) | ((long) (buf[6] & 0xFF) << 48) | ((long) (buf[5] & 0xFF) << 40)
                | ((long) (buf[4] & 0xFF) << 32) | ((long) (buf[3] & 0xFF) << 24) | ((long) (buf[2] & 0xFF) << 16)
                | ((long) (buf[1] & 0xFF) << 8) | (buf[0] & 0xFF);
    }

    float readFloat32() throws IOException {
        return Float.intBitsToFloat(readUInt32());
    }

    double readFloat64() throws IOException {
        return Double.longBitsToDouble(readUInt64());
    }

    /**
     * 读取 4 字节 magic 并转成 ASCII 字符串
     */
    String readMagic() throws IOException {
        byte[] magic = new byte[4];
        readFully(magic);
        return new String(magic, StandardCharsets.US_ASCII);
    }

    /**
     * 读取 ULE64 长度前缀的 UTF-8 字符串
     */
    String readString() throws IOException {
        long len = readUInt64();
        if (len < 0 || len > Integer.MAX_VALUE) {
            throw new IOException("GGUF string too long: " + len);
        }
        byte[] bytes = new byte[(int) len];
        readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * 按 GGUF value type 读取一个值，装箱类型与旧 ByteBuffer 实现一致
     */
    Object readValue(int type) throws IOException {
        switch (type) {
        case UINT8:
            return readUInt8();
        case INT8:
            return (byte) readUInt8();
        case UINT16:
            return readUInt16();
        case INT16:
            return (short) readUInt16();
        case UINT32:
            return readUInt32() & 0xFFFFFFFFL;
        case INT32:
            return readUInt32();
        case FLOAT32:
            return readFloat32();
        case BOOL:
            return readUInt8() != 0;
        case STRING:
            return readString();
        case ARRAY: {
            int subType = readUInt32();
            long len = readUInt64();
            List<Object> list = new ArrayList<>((int) len);
            for (long i = 0; i < len; i++) {
                list.add(readValue(subType));
            }
            return list;
        }
        case UINT64:
        case INT64:
            return readUInt64();
        case FLOAT64:
            return readFloat64();
        default:
            throw new IllegalArgumentException("Unknown GGUF value type: " + type);
        }
    }

    /**
     * 按 GGUF value type 跳过一个值
     */
    void skipValue(int type) throws IOException {
        switch (type) {
        case UINT8:
        case INT8:
        case BOOL:
            skipNBytes(1);
            break;
        case UINT16:
        case INT16:
            skipNBytes(2);
            break;
        case UINT32:
        case INT32:
        case FLOAT32:
            skipNBytes(4);
            break;
        case UINT64:
        case INT64:
        case FLOAT64:
            skipNBytes(8);
            break;
        case STRING: {
            long len = readUInt64();
            skipNBytes(len);
            break;
        }
        case ARRAY: {
            int subType = readUInt32();
            long len = readUInt64();
            for (long i = 0; i < len; i++) {
                skipValue(subType);
            }
            break;
        }
        default:
            break;
        }
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
