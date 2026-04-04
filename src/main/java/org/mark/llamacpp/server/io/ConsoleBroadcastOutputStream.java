package org.mark.llamacpp.server.io;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.function.Consumer;

import org.mark.llamacpp.server.LlamaServer;


/**
 * 	重定向用的输出流。
 */
public class ConsoleBroadcastOutputStream extends OutputStream {
    private final Consumer<String> lineConsumer;
    private final Charset charset;
    private final StringBuilder buffer = new StringBuilder();
    private volatile boolean closed = false;
    
    public ConsoleBroadcastOutputStream(Consumer<String> lineConsumer, Charset charset) {
        this.lineConsumer = lineConsumer;
        this.charset = charset;
    }

    private void emitLine() {
        String line = buffer.toString();
        LlamaServer.sendConsoleLineEvent(null, line);
        lineConsumer.accept(line);
        buffer.setLength(0);
    }

    @Override
    public synchronized void write(int b) throws IOException {
        char c = (char) (b & 0xFF);
        if (c == '\n') {
            emitLine();
        } else if (c != '\r') {
            buffer.append(c);
        }
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) throws IOException {
        String text = new String(b, off, len, charset);
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                if (i > start) buffer.append(text, start, i);
                emitLine();
                start = i + 1;
            } else if (c == '\r') {
                if (i > start) buffer.append(text, start, i);
                start = i + 1;
            }
        }
        if (start < text.length()) buffer.append(text.substring(start));
    }

    @Override
    public synchronized void flush() throws IOException {
        if (buffer.length() > 0) {
            emitLine();
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            flush();
            closed = true;
        }
    }
}
