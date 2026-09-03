package org.mark.llamacpp.server.service;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.mark.llamacpp.server.LlamaServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StreamingForwarder {

    private static final Logger logger = LoggerFactory.getLogger(StreamingForwarder.class);

    private static final int QUEUE_CAPACITY = 16;
    private static final int CHUNK_PREVIEW_MAX = 120;

    private static final byte[] EOF_MARKER = new byte[0];

    /* ---------- 目标字段常量 ---------- */
    private static final TargetField TARGET_MODEL = new TargetField("model", FieldType.STRING);
    private static final TargetField TARGET_ENABLE_THINKING = new TargetField("enable_thinking", FieldType.BOOLEAN);
    private static final TargetField TARGET_THINKING_BUDGET = new TargetField("thinking_budget_tokens", FieldType.NUMBER);
    private static final TargetField TARGET_THINKING = new TargetField("thinking", FieldType.OBJECT);
    private static final TargetField TARGET_THINKING_TYPE = new TargetField("type", FieldType.STRING, true);
    private static final TargetField[] ALL_TARGETS = { TARGET_MODEL, TARGET_ENABLE_THINKING, TARGET_THINKING_BUDGET, TARGET_THINKING, TARGET_THINKING_TYPE };

    /* ---------- 状态机常量 ---------- */
    private static final int STATE_NORMAL         = 0;
    private static final int STATE_KEY_MATCH      = 1;
    private static final int STATE_VALUE_PARSE    = 2;
    private static final int STATE_DONE           = 3;

    private final BlockingQueue<Object> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean failed = new AtomicBoolean(false);
    private volatile IOException failure;

    private final UnifiedBodyBuffer bodyBuffer = new UnifiedBodyBuffer();

    /* 提取结果 */
    private String modelName;
    private Boolean enableThinking;

    /* 状态机字段（跨 chunk 持久化） */
    private int state = STATE_NORMAL;
    private int depth;
    private boolean inString;
    private TargetField currentTarget;
    private int keyMatchLen;
    private StringBuilder valueBuf;
    private boolean escapePending;
    private boolean afterColon;
    private boolean inValueString;
    private int boolMatchLen;
    private boolean inThinkingObject;

    /* key 跨分块时暂存的尾部字节（从开引号到 chunk 末尾，最长为目标 key 长度 + 1） */
    private byte[] pendingKeyTail;

    /* nodeId 由外部从请求头设置，不从 body 提取 */
    private volatile String nodeId;

    private volatile byte[] lastChunk;
    private final AtomicLong chunkSeq = new AtomicLong(0);

    public StreamingForwarder() {
    }

    /**
     * 从请求头设置 nodeId。
     */
    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public void offer(byte[] chunk) throws IOException {
        if (chunk == null || chunk.length == 0) {
            return;
        }
        if (this.closed.get()) {
            throw new IOException("stream closed");
        }
        long seq = this.chunkSeq.incrementAndGet();
        try {
        	this.bodyBuffer.write(chunk);
        	this.extractFields(chunk);
        } catch (IOException e) {
        	this.fail(e);
            throw e;
        }
        Object marker = seq;
        try {
            while (!this.closed.get() && !this.failed.get()) {
                if (this.queue.offer(marker, 100, TimeUnit.MILLISECONDS)) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while enqueuing", e);
        }
        if (this.failed.get() && this.failure != null) {
            throw this.failure;
        }
        throw new IOException("stream closed");
    }

    public void offerLast(byte[] chunk) {
        if (chunk == null || chunk.length == 0) {
            return;
        }
        this.lastChunk = chunk;
    }

    public void complete() {
    	this.closed.compareAndSet(false, true);
        try {
        	this.queue.put(EOF_MARKER);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void fail(IOException e) {
    	this.failed.compareAndSet(false, true);
        this.failure = e;
        this.closed.set(true);
        try {
        	this.queue.put(EOF_MARKER);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 等待所有 chunk 到达（已在 offer() 中写入 bodyBuffer），返回路由信息。
     */
    public TransformResult extract() throws IOException {
        while (true) {
            try {
                Object marker = this.queue.poll(1, TimeUnit.SECONDS);
                if (marker == EOF_MARKER) {
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while waiting for chunk", e);
            }
        }
        if (failed.get() && failure != null) {
            throw failure;
        }

        /* 处理最后一个 chunk（必须在检查 modelName 之前） */
        byte[] storedLast = this.lastChunk;
        if (storedLast != null && storedLast.length > 0) {
            try {
                bodyBuffer.write(storedLast);
            } catch (IOException e) {
                fail(e);
                throw e;
            }
            extractFields(storedLast);
        }

        if (modelName == null || modelName.isBlank()) {
            throw new ForwarderException(400, "Missing required parameter: model", "model");
        }

        return new TransformResult(modelName, nodeId, enableThinking);
    }

    /**
     * 将 bodyBuffer 中的数据流式转发到目标输出流。
     * 有 nodeId → 仅注入 timing 参数后转发；无 nodeId → 注入采样参数 + timing 参数后转发。
     */
    public void streamBody(OutputStream output, Boolean clientEnableThinking) throws IOException {
        OutputStream logOutput = null;
        OutputStream targetOutput = output;
        
        if (LlamaServer.logRequestBodyToFile) {
            try {
                logOutput = this.createLogFile();
                targetOutput = new TeeOutputStream(output, logOutput);
                logger.info("[Debug] 请求体日志已开启: {}", modelName);
            } catch (IOException e) {
                logger.warn("[Debug] 创建请求体日志文件失败，继续使用原始输出: {}", e.getMessage());
            }
        }
        
        String timingInjection = "\"timings_per_token\":true,\"return_progress\":true,\"verbose\":true";
        try {
            if (this.nodeId != null && !this.nodeId.isBlank()) {
                long injected = this.bodyBuffer.streamInjected(targetOutput, timingInjection);
                logger.info("[远程代理] nodeId={}, injected={} bytes", this.nodeId, injected);
            } else {
                String injection = SamplingInjectionBuilder.buildInjectionString(this.modelName, clientEnableThinking);
                if (!injection.isEmpty()) {
                    injection = injection + "," + timingInjection;
                } else {
                    injection = timingInjection;
                }
                long injected = this.bodyBuffer.streamInjected(targetOutput, injection);
                logger.info("[注入] model={}, injected={} bytes: {}", this.modelName, injected, injection);
            }
        } finally {
            if (logOutput != null) {
                try {
                    logOutput.close();
                } catch (IOException e) {
                    logger.warn("[Debug] 关闭请求体日志文件失败", e);
                }
            }
        }
    }
    
    private OutputStream createLogFile() throws IOException {
        Path logDir = Paths.get("cache", "logs").toAbsolutePath();
        if (!Files.exists(logDir)) {
            Files.createDirectories(logDir);
        }
        String safeModel = this.modelName != null && !this.modelName.isEmpty() 
            ? this.modelName.replace("/", "_").replace("\\", "_") 
            : "unknown";
        String filename = System.currentTimeMillis() + "_" + safeModel + ".json";
        Path logFile = logDir.resolve(filename);
        return new FileOutputStream(logFile.toFile());
    }

    /**
     * 基于状态机的 JSON 字段提取。
     * 逐字节扫描，追踪嵌套深度和字符串状态，提取顶层 "model"（string）和 "enable_thinking"（boolean）字段。
     * 内存 O(1)，不依赖 JSON 总大小。
     */
    void extractFields(byte[] chunk) {
        if (this.state == STATE_DONE) {
            return;
        }

        /* 上个 chunk 末尾可能有跨分块的 key，拼接后重扫 */
        byte[] pending = this.pendingKeyTail;
        if (pending != null) {
            byte[] merged = new byte[pending.length + chunk.length];
            System.arraycopy(pending, 0, merged, 0, pending.length);
            System.arraycopy(chunk, 0, merged, pending.length, chunk.length);
            this.pendingKeyTail = null;
            chunk = merged;
        }

        //logger.debug("[状态机] === chunk: {} 字节, preview={}", chunk.length, previewChunk(chunk));

        for (int i = 0; i < chunk.length; i++) {
            byte b = chunk[i];
            int prevState = this.state;

            switch (this.state) {

                case STATE_DONE:
                    return;

                /* ===== 主状态：逐字节扫描 JSON 结构 ===== */
                default:
                case STATE_NORMAL: {
                    if (this.escapePending) {
                    	this.escapePending = false;
                        break;
                    }
                    if (b == '\\') {
                    	this.escapePending = true;
                        break;
                    }
                    if (b == '"') {
                        if (!this.inString) {
                            /* 前瞻检查：是否为顶层目标字段 key，或 thinking 对象内的 type */
                            if (this.depth == 1 || (this.inThinkingObject && depth == 2)) {
                                TargetField matched = findMatchingTarget(chunk, i + 1, this.depth, this.inThinkingObject);
                                if (matched != null) {
                                	this.inString = true;
                                	this.currentTarget = matched;
                                    this.keyMatchLen = 0;
                                    this.state = STATE_KEY_MATCH;
                                    //logger.debug("[状态机] pos={} 匹配到 {} key 开头", i, matched.name());
                                    break;
                                }
                                /* key 可能跨分块：暂存从开引号到 chunk 末尾的字节，等下个 chunk 拼接后重扫 */
                                if (hasPartialKeyTail(chunk, i + 1, this.depth, this.inThinkingObject)) {
                                	this.pendingKeyTail = Arrays.copyOfRange(chunk, i, chunk.length);
                                    //logger.debug("[状态机] pos={} key 可能跨分块，暂存 {} 字节", i, this.pendingKeyTail.length);
                                    return;
                                }
                            }
                        	this.inString = true;
                        } else {
                        	this.inString = false;
                        }
                        break;
                    }
                    if (this.inString) {
                        break;
                    }
                    if (b == '{') {
                    	this.depth++;
                        break;
                    }
                    if (b == '}') {
                        if (this.inThinkingObject && this.depth == 2) {
                        	this.inThinkingObject = false;
                        }
                        this.depth--;
                        if (this.depth < 0) this.depth = 0;
                        if (this.depth == 0) {
                        	this.state = STATE_DONE;
                            //logger.debug("[状态机] pos={} 顶层 JSON 结束", i);
                            return;
                        }
                        break;
                    }
                    break;
                }

                /* ===== 消耗目标 key 剩余字符（前瞻已确认匹配） ===== */
                case STATE_KEY_MATCH: {
                	this.keyMatchLen++;
                    if (this.keyMatchLen == this.currentTarget.keyBytes().length) {
                        /* key 匹配完成，准备解析 value */
                    	this.afterColon = false;
                    	this.inValueString = false;
                    	this.valueBuf = null;
                    	this.boolMatchLen = 0;
                    	this.state = STATE_VALUE_PARSE;
                        //logger.debug("[状态机] pos={} {} key 匹配完成，解析 value", i, currentTarget.name());
                    }
                    break;
                }

                /* ===== 解析字段的 value ===== */
                case STATE_VALUE_PARSE: {
                    if (this.currentTarget.type() == FieldType.STRING) {
                    	this.handleStringValue(b, prevState);
                    } else if (this.currentTarget.type() == FieldType.BOOLEAN) {
                    	this.handleBooleanValue(b, prevState, chunk, i);
                    } else if (this.currentTarget.type() == FieldType.NUMBER) {
                    	this.handleNumberValue(b, prevState, chunk, i);
                    } else if (this.currentTarget.type() == FieldType.OBJECT) {
                    	this.handleObjectValue(b);
                    }
                    break;
                }
            }

            //if (prevState != state) {
                //logger.debug("[状态机] {} -> {}", stateName(prevState), stateName(state));
            //}
        }
    }

    /**
     * 解析字符串类型的 value（用于 model 字段）。
     */
    void handleStringValue(byte b, int prevState) {
        if (this.escapePending) {
        	this.escapePending = false;
            if (this.valueBuf != null) {
            	this.valueBuf.append((char) b);
            }
            return;
        }
        if (b == '\\') {
        	this.escapePending = true;
            return;
        }
        if (b == '"') {
            if (!this.afterColon) {
                /* 不应到达：key 已在 KEY_MATCH 中消耗完 */
                return;
            }
            if (!this.inValueString) {
                /* value 的打开引号 */
            	this.inValueString = true;
                return;
            }
            /* value 的关闭引号 —— 提取完成 */
            String val = (this.valueBuf == null) ? "" : this.valueBuf.toString();
            if (this.currentTarget == TARGET_MODEL) {
            	this.modelName = val;
            	this.bodyBuffer.setModelFound();
            } else if (this.currentTarget == TARGET_THINKING_TYPE) {
                String trimmed = val.trim();
                if ("enabled".equalsIgnoreCase(trimmed)) {
                	this.enableThinking = true;
                } else if ("disabled".equalsIgnoreCase(trimmed)) {
                	this.enableThinking = false;
                }
            }
            //logger.info("[状态机] *** 提取到 {}={}", currentTarget.name(), val);
            this.resetToNormal();
            return;
        }
        if (b == ':') {
        	this.afterColon = true;
            return;
        }
        if (isWhitespace(b)) {
            return;
        }
        /* value 字符 */
        if (this.valueBuf == null) {
        	this.valueBuf = new StringBuilder(32);
        }
        this.valueBuf.append((char) b);
    }

    /**
     * 解析布尔类型的 value（用于 enable_thinking 字段）。
     */
    void handleBooleanValue(byte b, int prevState, byte[] chunk, int pos) {
        if (this.escapePending) {
        	this.escapePending = false;
            return;
        }
        if (b == '\\') {
        	this.escapePending = true;
            return;
        }
        if (b == ':') {
        	this.afterColon = true;
            return;
        }
        if (isWhitespace(b)) {
            return;
        }
        if (b == '"') {
            if (!this.afterColon) {
                return;
            }
            if (!this.inValueString) {
                /* 布尔值以字符串形式出现，例如 "true" / "false" */
            	this.inValueString = true;
            	this.valueBuf = null;
                return;
            }
            /* 字符串形式的布尔值关闭引号 */
            String val = (this.valueBuf == null) ? "" : this.valueBuf.toString();
            Boolean parsed = parseBooleanString(val);
            if (parsed != null) {
            	this.enableThinking = parsed;
            }
            this.resetToNormal();
            return;
        }

        /* 字符串形式布尔值的字符累积 */
        if (this.inValueString) {
            if (this.valueBuf == null) {
            	this.valueBuf = new StringBuilder(32);
            }
            this.valueBuf.append((char) b);
            return;
        }

        /* 累积布尔值字符 */
        if (!this.afterColon) {
            return;
        }

        if (b == 't' && this.boolMatchLen == 0) {
        	this.boolMatchLen++;
            return;
        }
        if (b == 'r' && this.boolMatchLen == 1) {
        	this.boolMatchLen++;
            return;
        }
        if (b == 'u' && this.boolMatchLen == 2) {
        	this.boolMatchLen++;
            return;
        }
        if (b == 'e' && this.boolMatchLen == 3) {
        	this.boolMatchLen++;
        	this.enableThinking = true;
            //logger.info("[状态机] *** 提取到 enable_thinking=true");
        	this.resetToNormal();
            return;
        }
        if (b == 'f' && this.boolMatchLen == 0) {
        	this.boolMatchLen++;
            return;
        }
        if (b == 'a' && this.boolMatchLen == 1) {
        	this.boolMatchLen++;
            return;
        }
        if (b == 'l' && this.boolMatchLen == 2) {
        	this.boolMatchLen++;
            return;
        }
        if (b == 's' && this.boolMatchLen == 3) {
        	this.boolMatchLen++;
            return;
        }
        if (b == 'e' && this.boolMatchLen == 4) {
        	this.boolMatchLen++;
        	this.enableThinking = false;
            //logger.info("[状态机] *** 提取到 enable_thinking=false");
        	this.resetToNormal();
            return;
        }

        /* 不匹配任何布尔字面量，重置 */
        this.resetToNormal();
    }

    /**
     * 解析数字类型的 value（用于 thinking_budget_tokens 字段）。
     * 仅解析正整数，当值大于 0 时表示启用 thinking。
     */
    void handleNumberValue(byte b, int prevState, byte[] chunk, int pos) {
        if (b == '"') {
            /* key 的关闭引号，忽略 */
            return;
        }
        if (b == ':') {
        	this.afterColon = true;
            return;
        }
        if (isWhitespace(b)) {
            if (!this.afterColon) {
                return;
            }
            if (this.valueBuf != null) {
            	this.finishNumberValue();
            }
            return;
        }
        if (b == ',' || b == '}') {
            if (this.valueBuf != null) {
            	this.finishNumberValue();
            }
            if (b == '}') {
                if (this.inThinkingObject && this.depth == 2) {
                	this.inThinkingObject = false;
                }
                this.depth--;
                if (this.depth < 0) this.depth = 0;
                if (this.depth == 0) {
                	this.state = STATE_DONE;
                } else {
                	this.resetToNormal();
                }
            } else {
            	this.resetToNormal();
            }
            return;
        }
        if (b >= '0' && b <= '9') {
            if (!this.afterColon) {
                return;
            }
            if (this.valueBuf == null) {
            	this.valueBuf = new StringBuilder(16);
            }
            this.valueBuf.append((char) b);
            return;
        }
        /* 非预期字符，重置 */
        this.resetToNormal();
    }

    /**
     * 完成数字 value 的解析。
     */
    void finishNumberValue() {
        try {
            int value = Integer.parseInt(this.valueBuf.toString());
            if (value > 0) {
            	this.enableThinking = true;
            }
        } catch (NumberFormatException e) {
            // ignore
        }
        this.resetToNormal();
    }

    /**
     * 解析对象类型的 value（用于 thinking 字段）。
     * 当值为对象时，进入 thinking 对象扫描其内部的 type 字段。
     */
    void handleObjectValue(byte b) {
        if (b == '"') {
            /* key 的关闭引号，忽略 */
            return;
        }
        if (b == ':') {
        	this.afterColon = true;
            return;
        }
        if (isWhitespace(b)) {
            return;
        }
        if (b == '{' && this.afterColon) {
        	this.depth++;
        	this.inThinkingObject = true;
        	this.resetToNormal();
            return;
        }
        /* 非预期字符，重置 */
        this.resetToNormal();
    }

    /**
     * 解析字符串形式的布尔值。
     */
    static Boolean parseBooleanString(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        if ("true".equalsIgnoreCase(trimmed)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(trimmed)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * 重置状态机到 NORMAL，准备扫描下一个目标字段。
     */
    void resetToNormal() {
    	this.state = STATE_NORMAL;
    	this.currentTarget = null;
    	this.keyMatchLen = 0;
    	this.valueBuf = null;
    	this.afterColon = false;
    	this.inValueString = false;
    	this.boolMatchLen = 0;
    	this.escapePending = false;
    	this.inString = false;
    }

    /**
     * 检查 chunk 中从 offset 开始是否匹配任意目标字段的 key。
     * 匹配成功返回对应的 TargetField，否则返回 null。
     * 数据不足（key 跨分块）时也返回 null，由调用方通过 hasPartialKeyTail 暂存尾部后重试。
     */
    static TargetField findMatchingTarget(byte[] chunk, int offset, int depth, boolean inThinkingObject) {
        for (TargetField target : ALL_TARGETS) {
            if (target.nestedOnly()) {
                if (!(inThinkingObject && depth == 2)) {
                    continue;
                }
            } else {
                if (depth != 1) {
                    continue;
                }
            }
            byte[] key = target.keyBytes();
            /* 必须能在当前 chunk 中看到 key 之后的关闭引号，避免 "thinking" 前缀误匹配 "thinking_budget_tokens" */
            if (offset + key.length >= chunk.length) {
                continue;
            }
            if (chunk[offset + key.length] != '"') {
                continue;
            }
            if (matchesKey(chunk, offset, key)) {
                return target;
            }
        }
        return null;
    }

    /**
     * 检查 chunk 尾部是否可能是某个目标 key 的不完整前缀（即 key 被分块边界切开）。
     * 仅当 offset 之后的剩余字节是某候选 key 的前缀时返回 true，
     * 包括：剩余长度为 0（开引号是最后一个字节）、key 完整但闭合引号缺失的情况。
     * 剩余长度超过 key 长度时完整匹配已被 findMatchingTarget 排除，无需暂存。
     */
    static boolean hasPartialKeyTail(byte[] chunk, int offset, int depth, boolean inThinkingObject) {
        int remaining = chunk.length - offset;
        for (TargetField target : ALL_TARGETS) {
            if (target.nestedOnly()) {
                if (!(inThinkingObject && depth == 2)) {
                    continue;
                }
            } else {
                if (depth != 1) {
                    continue;
                }
            }
            byte[] key = target.keyBytes();
            if (remaining > key.length) {
                continue;
            }
            boolean prefix = true;
            for (int k = 0; k < remaining; k++) {
                if (chunk[offset + k] != key[k]) {
                    prefix = false;
                    break;
                }
            }
            if (prefix) {
                return true;
            }
        }
        return false;
    }

    static String stateName(int s) {
        return switch (s) {
            case STATE_NORMAL -> "NORMAL";
            case STATE_KEY_MATCH -> "KEY_MATCH";
            case STATE_VALUE_PARSE -> "VALUE_PARSE";
            case STATE_DONE -> "DONE";
            default -> "UNKNOWN(" + s + ")";
        };
    }

    static boolean isWhitespace(byte b) {
        return b == ' ' || b == '\t' || b == '\n' || b == '\r';
    }

    /**
     * 检查 chunk 中从 offset 开始是否完整匹配 key。
     * 数据不足时返回 false，由调用方通过 hasPartialKeyTail 暂存尾部后重试。
     */
    static boolean matchesKey(byte[] chunk, int offset, byte[] key) {
        if (offset + key.length > chunk.length) {
            return false;
        }
        for (int k = 0; k < key.length; k++) {
            if (chunk[offset + k] != key[k]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 关闭并清理 bodyBuffer 资源。
     */
    public void close() throws IOException {
    	this.bodyBuffer.close();
    }

    String getModelName() {
        return this.modelName;
    }

    Boolean getEnableThinking() {
        return this.enableThinking;
    }

    static String previewChunk(byte[] chunk) {
        int len = Math.min(chunk.length, CHUNK_PREVIEW_MAX);
        String preview = new String(chunk, 0, len, StandardCharsets.UTF_8);
        if (chunk.length > CHUNK_PREVIEW_MAX) {
            preview += "...(+" + (chunk.length - CHUNK_PREVIEW_MAX) + "bytes)";
        }
        return preview;
    }

    /**
     * 目标字段定义。
     */
    static class TargetField {
        private final String name;
        private final FieldType type;
        private final byte[] keyBytes;
        private final boolean nestedOnly;

        TargetField(String name, FieldType type) {
            this(name, type, false);
        }

        TargetField(String name, FieldType type, boolean nestedOnly) {
            this.name = name;
            this.type = type;
            this.keyBytes = name.getBytes(StandardCharsets.US_ASCII);
            this.nestedOnly = nestedOnly;
        }

        String name() { return name; }
        FieldType type() { return type; }
        byte[] keyBytes() { return keyBytes; }
        boolean nestedOnly() { return nestedOnly; }
    }

    /**
     * 字段类型。
     */
    enum FieldType {
        STRING, BOOLEAN, NUMBER, OBJECT
    }

    public static class TransformResult {
        private final String modelName;
        private final String nodeId;
        private final Boolean enableThinking;

        public TransformResult(String modelName, String nodeId) {
            this(modelName, nodeId, null);
        }

        public TransformResult(String modelName, String nodeId, Boolean enableThinking) {
            this.modelName = modelName;
            this.nodeId = nodeId;
            this.enableThinking = enableThinking;
        }

        public String getModelName() {
            return modelName;
        }

        public String getNodeId() {
            return nodeId;
        }

        public Boolean getEnableThinking() {
            return enableThinking;
        }
    }

    public static class ForwarderException extends IOException {
        private static final long serialVersionUID = 1L;
		private final int httpStatus;
        private final String param;

        public ForwarderException(int httpStatus, String message, String param) {
            super(message);
            this.httpStatus = httpStatus;
            this.param = param;
        }

        public int getHttpStatus() {
            return httpStatus;
        }

        public String getParam() {
            return param;
        }
    }
}
