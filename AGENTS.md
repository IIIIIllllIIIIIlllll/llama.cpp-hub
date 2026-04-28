# llama.cpp-hub — Agent 指南

本项目是一个 llama.cpp 的 WebUI + API 兼容层。后端 Java 21 + 裸 Netty，前端纯原生 JS 无框架。将 llama.cpp 的 `llama-server` 作为子进程启动并管理，向前端和外部客户端提供 OpenAI / Anthropic / Ollama / LM Studio 兼容 API。

---

## 构建

### Maven 方式
```
mvn clean package
```
通过 `maven-shade-plugin` 打包 fat JAR，主类 `org.mark.llamacpp.server.LlamaServer`。**pom.xml 中没有任何 `<dependency>`**——所有依赖在 `lib/` 下手动管理，classpath 由 Eclipse `.classpath` 维护。

### 手动编译
- Windows: `javac-win.bat`
- Linux: `javac-linux.sh`

流程：`--release 21` 编译全部 Java 源文件 → 复制 `lib/*.jar` 到 `build/lib/` → 复制 `src/main/resources/` 到 `build/classes/` → 生成 `build/run.bat` 或 `build/run.sh`。运行脚本使用 `javaw.exe -Xms512m -Xmx512m -XX:MaxDirectMemorySize=256m`。

### 运行时依赖（`lib/`）
| JAR | 版本 | 用途 |
|-----|------|------|
| gson | 2.8.9 | JSON 解析 |
| netty-all | 4.1.35.Final | HTTP/WebSocket 服务器 |
| log4j-api | 2.22.1 | 日志 API |
| log4j-core | 2.22.1 | 日志核心 |
| log4j-slf4j2-impl | 2.22.1 | SLF4J 绑定 |
| slf4j-api | 2.0.9 | SLF4J API |

**修改依赖需：** 替换 `lib/` 下的 JAR → 更新 `.classpath` → 不可改 pom.xml。

### 版本信息
`src/main/java/org/mark/llamacpp/server/BuildInfo.java` 包含占位符：
```java
private static final String TAG = "{tag}";
private static final String VERSION = "{version}";
private static final String CREATED_TIME = "{createdTime}";
```
CI 打包时被替换。本地测试时显示原始 `{tag}` 等。

---

## 测试 / Lint / Typecheck / Format

**完全没有。** `src/test/` 是空目录。无测试框架、无 linter、无 formatter、无 type checker。无 pre-commit 配置。

---

## 启动流程（`LlamaServer.main()`）

1. 重定向 `System.out/err` 到 `ConsoleBroadcastOutputStream`（发送到 WebSocket 控制台 + logger）
2. 安装 `ConsoleBufferLogAppender`（自定义 Log4j2 appender，将日志行发送到 WebSocket）
3. 预加载 `logs/app.log` 尾部（最近 2MB）到控制台缓冲区
4. 创建 `cache/` 目录
5. 读取 `config/application.json`，如不存在则自动创建（默认配置）
6. 初始化 `ConfigManager`（单例）：加载所有模型的启动配置
7. 初始化 `LlamaServerManager`（单例）：扫描模型目录，构建模型列表
8. 初始化 `ModelSamplingService`（单例）
9. 初始化 `McpClientService`（单例）：从 `config/mcp-tools.json` 注册 MCP 客户端工具
10. 初始化 `NodeManager`（单例）：加载远程节点，判断 `nodeRole`。master 模式下连接所有远程节点的 WebSocket 并启动 30 秒健康检查；slave 模式下跳过
11. 加载 HTTPS 上下文（`initHttpsContext()`）
12. 在独立线程启动 `bindOpenAI(webPort)`（默认 8080）
13. 在独立线程启动 `bindAnthropic(anthropicPort)`（默认 8070）
14. 可选：启动 LM Studio 兼容（默认 1234）
15. 可选：启动 Ollama 兼容（默认 11434）
16. 可选：启动 MCP 服务器（默认 8075）
17. Windows 下创建系统托盘
18. 可选：如果命令行参数提供了模型名称，自动加载该模型

---

## 端口布局

| 端口 | 服务 | 默认 | 配置项 |
|------|------|------|--------|
| 8080 | 主服务：OpenAI API + WebUI + 内部 API + WebSocket | 开 | `application.json` → `server.webPort` |
| 8070 | Anthropic 兼容 API | 开 | `server.anthropicPort` |
| 8075 | MCP 服务器（Streamable HTTP） | 关 | `compat.mcpServer.enabled` |
| 11434 | Ollama 兼容 API | 关 | `compat.ollama.enabled/port` |
| 1234 | LM Studio 兼容 API | 关 | `compat.lmstudio.enabled/port` |
| 8081+ | 每个加载的 llama-server 子进程独占 | - | 自动分配（`PortChecker` 三重检测） |

---

## 路由流水线

### 8080 端口（`bindOpenAI()`）
```
SslHandler (可选)
  → HttpServerCodec
  → OpenAIChatStreamingHandler（拦截 /v1/chat/completions 并提前流式解析请求体）
  → HttpObjectAggregator（最大 16MB）
  → ChunkedWriteHandler
  → WebSocketServerProtocolHandler（路径 /ws）
  → WebSocketServerHandler
  ─────────────────────────────────
  → BasicRouterHandler（静态文件 + `/api/*` 控制器链）
  → CompletionRouterHandler（EasyRP 角色/文件/头像管理）
  → FileDownloadRouterHandler（下载管理）
  → OpenAIRouterHandler（`/v1/*` OpenAI API + API Key 验证）
```

### 8070 端口（`bindAnthropic()`）
```
SslHandler (可选)
  → HttpServerCodec
  → HttpObjectAggregator（最大 16MB）
  → ChunkedWriteHandler
  → BasicRouterHandler（仅 `/api/*` 和静态文件）
  → CompletionRouterHandler
  → AnthropicRouterHandler（`/v1/messages` 等 Anthropic 兼容 API）
  → FileDownloadRouterHandler
```

---

## `BasicRouterHandler` 控制器链（严格顺序）

请求 URI 以 `/api/`、`/v1`（非 HTML）、`/session`、`/tokenize`、`/apply-template`、`/infill`、`/models`、`/chat/completion`、`/completions`、`/embeddings`、`/rerank`、`/responses` 开头时进入。按顺序遍历控制器，一旦 `handleRequest()` 返回 `true` 则停止。

1. **`EasyChatController`** — `/api/easy-chat/*`
2. **`HuggingFaceController`** — `/api/hf/*`
3. **`LlamacppController`** — `/api/llamacpp/*` + 代理端点 `/tokenize`、`/apply-template`、`/infill`
4. **`ModelActionController`** — `/api/models/load/stop/list/loaded/refresh/benchmark/metrics/props`
5. **`ModelInfoController`** — `/api/models/{config, alias, favourite, details, capabilities, template, kwargs, slots, record, openai}`
6. **`ModelPathController`** — `/api/model/path/*`
7. **`NodeController`** — `/api/node/*`
8. **`ParamController`** — `/api/models/param/server/list`、`/api/models/param/benchmark/list`
9. **`ToolController`** — `/api/mcp/*`、`/api/tools/execute`
10. **`SystemController`** — `/api/sys/*`、`/api/shutdown`（会调用 `NodeManager.shutdown()` 断开所有远程 WS）、`/api/models/vram/estimate`、`/api/model/device/list`

如果都不是 API 请求 → 作为静态文件请求处理：移动端检测（`Sec-CH-UA-Mobile` + `User-Agent`），根路径重定向到 `index.html` 或 `index-mobile.html`，从 `/web/` classpath 资源目录提供文件。

---

## 后端核心组件

### 单例管理器
| 类 | 职责 |
|----|------|
| `ConfigManager` | 读写 `config/models.json`、`launch_config.json`、`nodes.json`，原子写入（`.tmp` + `ATOMIC_MOVE`） |
| `LlamaServerManager` | 模型发现/加载/停止/端口分配/能力检测/设备列表/VRAM 估算 |
| `NodeManager` | 远程节点 CRUD + 30s 健康检查 + API 代理 |
| `WebSocketManager` | 8080 端口的 WebSocket 连接管理，30s 心跳，60s 系统状态广播 |
| `ModelSamplingService` | 采样参数预设管理，支持模型绑定 |
| `ChatTemplateKwargsService` | 每个模型的 `chat_template_kwargs` 配置管理 |
| `McpClientService` | MCP 客户端：注册 SSE/Streamable HTTP 服务器、工具索引、工具调用 |
| `GpuService` | 启动时 GPU 检测快照 + 实时状态查询 |
| `DownloadManager` | 下载任务管理（并发限制 4、断点续传、进度通知） |
| `LlamaRecordService` | 累积模型推理性能记录（timings）|
| `ModelRequestTracker` | 模型工作状态机：追踪活跃推理请求，广播 `model_busy` WebSocket 事件 |
| `AnthropicService` | Anthropic ↔ OpenAI 消息格式转换 |
| `OpenAIService` | OpenAI API 请求转发到模型子进程 |

### 线程模型
- 所有请求处理使用虚拟线程：`Executors.newVirtualThreadPerTaskExecutor()`
- 模型加载使用单虚拟线程执行器：`Executors.newSingleThreadExecutor(Thread.ofVirtual().name("llama-loader-", 0).factory())`

---

### 模型工作状态机（`ModelRequestTracker`）

追踪每个模型当前是否有活跃的推理请求，通过 WebSocket 实时广播给前端。

#### 数据结构

| 类 | 包 | 说明 |
|----|----|------|
| `ActiveRequest` | `struct` | 单次请求结构体：requestId、modelId、endpoint、startTime、Timing、状态枚举、Phase 枚举 |
| `ModelRequestTracker` | `service` | 单例，维护两重 `ConcurrentHashMap`：`modelId → Set<requestId>` 和 `requestId → ActiveRequest` |

**状态枚举（`RequestStatus`）：** `CREATED` → `PROXYING` → `COMPLETED` / `FAILED`

**阶段枚举（`Phase`，仅后端内部使用，不暴露给前端）：** `PREFILL`（等待 llama.cpp 返回 HTTP 200）、`GENERATION`（llama.cpp 已返回 200，正在生成 token）

#### 核心方法

| 方法 | 说明 |
|------|------|
| `createRequest(modelId, endpoint) → requestId` | 创建请求记录，返回 UUID，初始 phase=`PREFILL` |
| `removeRequest(requestId)` | 移除请求记录。内部判断该模型是否还有其他活跃请求，避免并发下误报空闲 |
| `updatePhase(requestId, Phase)` | 更新请求的推理阶段（PREFILL → GENERATION）|
| `updateTiming(requestId, Timing)` | 回填 Timing 数据 |
| `isModelBusy(modelId) → boolean` | 查询模型是否有活跃请求 |
| `getModelActiveCount(modelId) → int` | 模型当前活跃请求数 |
| `getModelAggregatedPhase(modelId) → String` | 聚合阶段：任一活跃请求为 GENERATION 返回 `"generation"`，否则 `"prefill"` |

#### 集成点位

每次推理请求经过以下 4 处 `connection.getResponseCode()` 后调用 `updatePhase(GENERATION)`：
1. `OpenAIService.forwardRequestToLlamaCpp()` — 聊天/补全/嵌入/rerank/responses/音频
2. `ChatStreamSession.run()` — 流式聊天
3. `OllamaChatService.handleChat()` — Ollama 聊天
4. `OllamaEmbedService.handleEmbed()` — Ollama 嵌入

请求结束时（正常/异常/网络断开）在 `finally` 或 `cancel()` 中调用 `removeRequest()`。

#### 并发安全

- `modelActiveRequests` 使用 `ConcurrentHashMap< String, Set<String>>`，Value 为 `ConcurrentHashMap.newKeySet()`（线程安全 Set）
- `allActiveRequests` 使用 `ConcurrentHashMap`
- 每个 ActiveRequest 只被所属虚拟线程写入，无跨线程竞争
- `removeRequest()` 在移除前通过 `modelActiveRequests.containsKey(modelId)` 判断是否还有其它活跃请求，避免多请求并发下误报 `busy=false`

#### WebSocket 事件

`ModelRequestTracker` 在 create/remove 时广播 `model_busy` 事件：

```json
{
  "type": "model_busy",
  "modelId": "Qwen3-0.6B-GGUF",
  "busy": true,
  "activeCount": 2
}
```

前端 `websocket.js` 监听此事件，调用 `applyModelPatch()` 更新 `currentModelsData` 中的 `busy` 字段。

#### 记录持久化

`ModelRequestTracker.removeRequest()` 在 `timing != null` 时调用 `LlamaRecordService.recordRequest(activeRequest)`，将完整请求记录（含 requestId、modelId、endpoint、耗时、Phase 时间线、Timing）追加写入 `cache/record/{modelId}.requests.log`，每行一个 JSON 对象。

---

## 模型管理

### 模型发现
- 扫描 `config/modelpaths.json` 中的路径 + 默认 `models/` 目录
- 递归遍历目录，每个包含 `.gguf` 文件的子目录视为一个模型
- 过滤掉以 `.` 开头的目录

### GGUF 文件处理（`GGUFBundle.java`）
- 自动识别分卷：文件名匹配 `*-00001-of-*.gguf` 模式
- **性能优化：** 只读取第一个文件的元数据，不再读取所有分卷（见 `LlamaServerManager.handleDirectory()` 注释）
- 自动关联 mmproj 文件：按以下优先级查找：
  1. `mmproj-{baseName}.gguf`
  2. `{baseName}-mmproj.gguf`
  3. 目录中包含 `mmproj` 的 `.gguf` 文件
- 种子文件选择：优先找分卷第一卷 → 不含 mmproj 的文件 → 第一个文件

### GGUF 元数据（`GGUFMetaData.java`）
从 GGUF 文件头解析的字段：
- `general.architecture` — 架构名（`llama`、`qwen2` 等）
- `general.basename` — 基名
- `general.name` — 模型名
- `general.size_label` — 大小标签
- `general.file_type` — 文件类型 ID，映射到 30+ 种量化名
- `*.context_length` — 上下文长度
- `clip.has_audio_encoder` / `clip.has_vision_encoder` — 多模态能力

`GGUFMetaDataReader.java` 使用内存映射文件（最大 64MB）读取完整 KV 元数据，用于模型详情展示。

### 量化类型映射（`fileTypeToQuantizationName()`）
将 GGUF 内部 `file_type` 整数映射为可读名称：`F32`/`F16`/`Q4_0`/`Q4_1`/`Q8_0`/`Q2_K`/`Q3_K_S`/`Q4_K_S`/`Q4_K_M`/`Q5_K_S`/`Q5_K_M`/`Q6_K`/`IQ2_XXS`/`IQ3_XXS`/`IQ1_S`/`IQ4_NL`/`IQ3_M`/`IQ2_M`/`IQ4_XS`/`IQ1_M`/`BF16`/`MXFP4` 等。

### 模型加载流程（`LlamaServerManager.loadModelAsyncFromCmd()`）
1. 检查模型是否已加载或正在加载，检查 `modelId` 是否存在
2. 通过 `PortChecker.findNextAvailablePort()` 分配端口（从 8081 开始，三重检测）
3. 调用 `buildCommandStr()` 构造 `llama-server` 命令行
4. 创建 `LlamaCppProcess`，设置输出处理器
5. 启动子进程，等待输出中包含 `"srv  update_slots: all slots are idle"`（最长 10 分钟）
6. 超时 → 停止进程 → 报错
7. 成功 → 查询 `/slots` 获取 `n_ctx` → 查询 `/v1/models` 缓存模型信息
8. 更新 `loadedProcesses`、`modelPorts`、`loadedModelInfos`

### `buildCommandStr()` 构造的命令行
```
{exe} -m {modelFile} --port {port} [--mmproj {mmprojFile}] [-sm none --device {device}] [--main-gpu {mg}]
  {cmd} {extraParams} [--chat-template-file {file}]
  --metrics [--slot-save-path {cacheDir}] --cache-ram -1 --alias {modelId} --timeout 36000 --host 0.0.0.0
```
- 自动避免重复添加用户已在 `cmd`/`extraParams` 中指定的 flag
- mmproj 只在 `enableVision=true` 且存在 mmproj 文件时添加
- GPU 选择：单 GPU 用 `-sm none --device`，多 GPU 用 `--device` 逗号分隔
- Windows/Linux 自动检测可执行文件名

### 模型停止流程（`stopModel()`）
- `process.stop()` → `Process.destroy()` → 等 5 秒 → `destroyForcibly()` → 中断读线程
- `LlamaCppProcess.stop()` 包含错误处理（进程已退出则吞掉异常）
- 清理 `loadedProcesses`、`modelPorts`、`loadedModelInfos`、`loadingProcesses`、`loadingTasks`

### 能力检测（`resolveModelType()`）
从 GGUF 文件名、架构名、chat_template 自动判断，缓存到 `config/capabilities/{modelId}.json`：
- `rerank` — 文件名包含 rerank/re-rank/reranker/ranker/cross-encoder
- `embedding` — 文件名包含 embedding/e5/gte/jina/nomic/mxbai/bge/arctic-embed，或架构是 bert/roberta
- `tools` — chat_template 包含 tool_call/tools/function/mcp
- `thinking` — chat_template 包含 enable_thinking/thinking
- `vision` — mmproj 的 `clip.has_vision_encoder=true`
- `audio` — mmproj 的 `clip.has_audio_encoder=true`
- rerank 和 embedding 互斥；tools/thinking 与 rerank/embedding 互斥

### 端口分配（`PortChecker`）
三重检测确保端口可用：
1. `ServerSocket.bind()` — 快速检测
2. `Socket.connect("localhost", port)` — 检测是否有进程在监听
3. `netstat -ano` (Windows) / `ss -tuln` (Linux) — OS 级检测

---

## 完整 API 端点文档

### OpenAI 兼容 API（`OpenAIRouterHandler` → `OpenAIService`）
| 端点 | 方法 | 说明 | 请求参数 |
|------|------|------|----------|
| `/v1/models` | GET | 列出已加载模型（含能力标记） | - |
| `/v1/chat/completions` | POST | 聊天补全（流式/非流式） | 标准 OpenAI 格式 + 可选 `nodeId` 字段直达远程节点 |
| `/v1/completions` | POST | 文本补全 | `{model, prompt, stream, max_tokens, ...}` |
| `/v1/embeddings` | POST | 文本嵌入 | `{model, input, ...}` |
| `/v1/responses` | POST | Responses API | `{model, input, ...}` |
| `/v1/rerank` | POST | 重排序 | `{model, query, documents, ...}` |
| `/v1/audio/transcriptions` | POST | 音频转写（multipart） | `model, file, language` |

**API Key 验证：** 读取 `Authorization: Bearer {key}` 头，对比 `application.json` 中的 `security.apiKey`。只在 `security.apiKeyEnabled=true` 时启用，仅对 `/v1/*` 路径生效。

**流式聊天流程（`OpenAIChatStreamingHandler` → `ChatStreamSession`）：**
1. `OpenAIChatStreamingHandler` 在 `HttpObjectAggregator` 之前拦截请求
2. 创建 `ChatStreamSession`，用 `BoundedQueueInputStream` 接收请求体分片
3. `ChatRequestStreamingTransformer` **流式解析** JSON，提取 `model` 和 `nodeId` 字段
4. 三大注入：`applyThinkingInjection()`（thinking 参数）、`applyChatTemplateKwargsInjection()`（模型 kwargs）、`applySamplingInjection()`（采样预设）
5. `nodeId` 字段在写入输出前被剥离，不转发给子进程；`TransformResult` 携带 `modelName` + `nodeId`
6. 完整解析后根据 `nodeId` 路由：有 `nodeId` 则直达远程节点（`resolveRemoteModelUrl`），否则查本地 → 查所有远程节点兜底
7. `DeferredConnectionOutputStream` 缓冲输出（先内存最大 1MB → 溢出到临时文件），连接建立后写入
8. 响应通过 SSE 转发回客户端，支持 `tool_call_id` 修复

### Anthropic 兼容 API（`AnthropicRouterHandler` → `AnthropicService`）
| 端点 | 方法 | 说明 |
|------|------|------|
| `/v1/models` | GET | 列出已加载模型（Anthropic 格式） |
| `/v1/messages` | POST | 聊天消息（自动转换 Anthropic ↔ OpenAI 格式） |
| `/v1/messages/count_tokens` | POST | 计 token |
| `/v1/complete` | POST | 旧版文本补全 |

**格式转换：**
- 请求：`convertAnthropicToOai()` — system/messages/tools/tool_choice/stop_sequences/max_tokens/thinking
- 响应：`convertOaiResponseToAnthropic()` — thinking → thinking block, content → text block, tool_calls → tool_use
- 流式：`convertOaiStreamChunkToAnthropicSse()` — 每个 SSE chunk 转换为 `content_block_start`/`content_block_delta` 事件

### Ollama 兼容 API（端口 11434，独立 Netty 服务器）
| 端点 | 方法 | 说明 | 实现类 |
|------|------|------|--------|
| `/api/tags` | GET | 列出所有可用模型 | `OllamaTagsService` |
| `/api/ps` | GET | 列出已加载模型 | `OllamaTagsService` |
| `/api/show` | GET/POST | 模型详情（含元数据、张量、能力） | `OllamaShowService` |
| `/api/chat` | POST | 聊天（含流式+工具调用，支持请求体 `nodeId` 字段直达远程节点） | `OllamaChatService` |
| `/api/embed` | POST | 文本嵌入 | `OllamaEmbedService` |
| `/v1/models` | GET | OpenAI 兼容 | `OpenAIService` |
| `/v1/chat/completions` | POST | OpenAI 兼容 | `OpenAIService` |
| `/v1/completions` | POST | OpenAI 兼容 | `OpenAIService` |
| `/v1/embeddings` | POST | OpenAI 兼容 | `OpenAIService` |
| `/api/copy`/`/api/delete`/`/api/pull`/`/api/push`/`/api/generate` | 任意 | 返回 404（不支持） | `OllamaRouterHandler` |

**Ollama 聊天转换：**
- 消息格式转换：`normalizeOllamaMessagesForOpenAI()` — 处理 images、tool_calls、function_call
- 选项转换：`options.temperature/top_p/top_k/repeat_penalty/frequency_penalty/presence_penalty/seed/num_predict/stop`
- 流式：ndjson 格式，支持 tool_calls
- 内置 `thinking` 注入
- **远程路由：** 同样支持请求体中添加 `nodeId` 字段直达远程节点

### LM Studio 兼容 API（端口 1234，独立 Netty 服务器）
| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/v0/models` | GET | 列出已加载模型（含量化、架构、能力） |
| `/api/v0/models/{modelId}` | GET | 单个模型详情 |
| `/api/v0/chat/completions` | POST | 聊天补全 |
| `/api/v0/completions` | POST | 文本补全 |
| `/api/v0/embeddings` | POST | 文本嵌入 |
| `/v1/models` | GET | OpenAI 兼容 |
| `/v1/chat/completions` | POST | OpenAI 兼容 |
| `/v1/completions` | POST | OpenAI 兼容 |
| `/v1/embeddings` | POST | OpenAI 兼容 |
| `/health` | GET | 健康检查 |
| `/echo` | ALL | 回显测试 |

**WebSocket RPC（LM Studio `LMStudioWebSocketHandler`）：**
- 路径 `/llm` 或 `/system` 触发 WebSocket 升级
- `{"type":"rpcCall","endpoint":"listDownloadedModels","callId":N}` — 列出所有 GGUF 模型
- `{"type":"rpcCall","endpoint":"listLoaded","callId":N}` — 列出已加载模型
- `{"type":"rpcCall","endpoint":"getModelInfo","callId":N,"parameter":{"specifier":{...}}}` — 模型详情

### 内部 API（`/api/*`）

#### 模型管理
| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/models/list` | GET | 列出所有模型（本地+远程），可选 `?nodeId=` |
| `/api/models/loaded` | GET | 列出已加载模型，含 id/name/status/port/pid/size/path/node |
| `/api/models/refresh` | GET | 强制刷新模型列表，同步刷新所有远程节点 |
| `/api/models/load` | POST | 加载模型。Body: `{modelId, cmd, extraParams, enableVision, llamaBinPathSelect, device, mg, nodeId}` |
| `/api/models/stop` | POST | 停止模型。Body: `{modelId, nodeId}` |
| `/api/models/favourite` | POST | 切换收藏。Body: `{modelId}` |
| `/api/models/alias/set` | POST | 设置别名。Body: `{modelId, alias, nodeId}` |
| `/api/models/openai/list` | GET | 获取 OpenAI 格式的模型列表（合并 `models` 和 `data` 数组） |
| `/api/models/details` | GET | 模型详情。Query: `modelId`、`nodeId` |
| `/api/models/record` | GET | 模型推理性能记录。Query: `modelId` |
| `/api/models/metrics` | GET | 代理到子进程的 `/metrics`。Query: `modelId` |
| `/api/models/props` | GET | 代理到子进程的 `/props`。Query: `modelId` |

#### 模型配置
| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/models/config/get` | GET | 获取启动配置。Query: `modelId`、`nodeId` |
| `/api/models/config/set` | POST | 保存启动配置。Body: `{modelId, configName, setSelected, config:{...}, nodeId}` |
| `/api/models/config/delete` | POST | 删除启动配置。Body: `{modelId, configName, nodeId}` |

#### 模型能力
| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/models/capabilities/get` | GET | 获取能力。Query: `modelId`、`nodeId` |
| `/api/models/capabilities/set` | POST | 设置能力。Body: `{modelId, capabilities:{tools,thinking,...}, nodeId}` |

#### 聊天模板
| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/model/template/get` | GET | 获取自定义 chat template。Query: `modelId` |
| `/api/model/template/set` | POST | 设置自定义 chat template。Body: `{modelId, chatTemplate}` |
| `/api/model/template/delete` | POST | 删除自定义 template。Body: `{modelId}` |
| `/api/model/template/default` | GET | 从 GGUF 获取默认 template。Query: `modelId` |

#### Chat Template Kwargs
| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/model/chat_template_kwargs/get` | GET | 获取 kwargs。Query: `modelId` |
| `/api/model/chat_template_kwargs/set` | POST | 设置 kwargs。Body: `{modelId, chat_template_kwargs:{...}}` |
| `/api/model/chat_template_kwargs/delete` | POST | 删除 kwargs。Body: `{modelId}` |

#### KV Cache Slots
| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/models/slots/get` | GET | 获取 slots 信息。Query: `modelId` |
| `/api/models/slots/save` | POST | 保存 slot 缓存。Body: `{modelId, slotId, fileName}` |
| `/api/models/slots/load` | POST | 加载 slot 缓存。Body: `{modelId, slotId, fileName}` |

#### 模型路径管理
| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/model/path/list` | GET | 列出所有搜索目录 |
| `/api/model/path/add` | POST | 添加目录。Body: `{path, name, description}`。验证目录存在、无符号链接 |
| `/api/model/path/update` | POST | 更新目录。Body: `{originalPath, path, name, description}` |
| `/api/model/path/remove` | POST | 删除目录。Body: `{path}` |

#### llama.cpp 路径管理
| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/llamacpp/list` | GET | 列出所有 llama.cpp 目录（含默认目录扫描结果） |
| `/api/llamacpp/add` | POST | 添加目录。Body: `{path, name, description}`。验证包含 `llama-server` |
| `/api/llamacpp/remove` | POST | 删除目录。Body: `{path}` |
| `/api/llamacpp/test` | POST | 测试目录。Body: `{path}`。运行 `llama-cli --version` + `--list-devices` |

#### 下载管理
| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/downloads/list` | GET | 列出所有下载任务 |
| `/api/downloads/create` | POST | 创建下载。Body: `{url, path, fileName}` |
| `/api/downloads/model/create` | POST | 创建模型下载。Body: `{author, modelId, downloadUrl[], path, name}` |
| `/api/downloads/pause` | POST | 暂停。Body: `{taskId}`。保存断点信息 |
| `/api/downloads/resume` | POST | 恢复。Body: `{taskId}`。验证 ETag/size |
| `/api/downloads/delete` | POST | 删除。Body: `{taskId, deleteFile}` |
| `/api/downloads/stats` | GET | 下载统计（active/pending/completed/failed/total） |
| `/api/downloads/path/get` | GET | 获取下载目录 |
| `/api/downloads/path/set` | POST | 设置下载目录。Body: `{path}` |

#### MCP 工具管理
| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/mcp/tools` | GET | 获取所有注册的 MCP 工具和服务器 |
| `/api/mcp/add` | POST | 添加 MCP 服务器（批量）。Body: `{mcpServers:{...}}` |
| `/api/mcp/remove` | POST | 删除 MCP 服务器。Body: `{url/mcpServerUrl}` |
| `/api/mcp/rename` | POST | 重命名 MCP 服务器。Body: `{url/mcpServerUrl, name}` |
| `/api/tools/execute` | POST | 执行工具。Body: `{tool_name, arguments, [mcpServerUrl]}` |

#### HuggingFace 搜索
| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/hf/search` | GET | 搜索 HuggingFace 模型。Query: `query`(必填)、`limit`(30)、`timeoutSeconds`(20)、`startPage`、`maxPages`、`base`(镜像) |
| `/api/hf/gguf` | GET | 获取模型的 GGUF 文件列表。Query: `model/repoId/input`(必填)、`timeoutSeconds`、`base` |

#### Easy-Chat 状态同步
| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/easy-chat/state` | GET | 加载聊天状态（含 revision 版本号、对话列表、文件路径） |
| `/api/easy-chat/state/revision` | GET | 获取当前 revision 字符串 |
| `/api/easy-chat/conversation` | GET | 加载指定对话。Query: `id` |
| `/api/easy-chat/sync` | POST | 同步状态（乐观并发控制）。Body: `{state, currentConversation, baseRevision}` |

#### EasyRP 角色管理
| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/chat/completion/list` | GET | 列出所有角色（slim 列表，按更新时间倒序） |
| `/api/chat/completion/create` | POST | 创建角色。Body: `{title}`。自动分配 ID |
| `/api/chat/completion/get` | GET | 获取角色。Query: `name`(数字 ID) |
| `/api/chat/completion/save` | POST | 保存角色。Query: `name`。Body: 完整的 `CharactorDataStruct` JSON |
| `/api/chat/completion/delete` | DELETE | 删除角色。Query: `name` |
| `/api/chat/completion/file/upload` | POST | 上传聊天文件（multipart，最大 16MB） |
| `/api/chat/completion/file/download` | GET | 下载聊天文件。Query: `name` |
| `/api/chat/completion/avatar/upload` | POST | 上传头像（multipart，最大 1MB）。Query: `name` |
| `/api/chat/completion/avatar/get` | GET | 获取头像（内联返回）。Query: `name` |

#### 系统设置
| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/sys/setting` | GET | 获取全部系统设置 |
| `/api/sys/setting` | POST | 保存系统设置。Body 支持：ollama/lmstudio port、日志开关、webPort、anthropicPort、apiKey、HTTPS、downloadDirectory |
| `/api/sys/version` | GET | 构建版本信息（tag/version/createdTime） |
| `/api/sys/compat/status` | GET | 兼容服务状态（Ollama/LMStudio/MCP/日志） |
| `/api/sys/ollama` | POST | 启用/禁用 Ollama。Body: `{enable, port}` |
| `/api/sys/lmstudio` | POST | 启用/禁用 LM Studio。Body: `{enable, port}` |
| `/api/sys/mcp` | POST | 启用/禁用 MCP 服务器。Body: `{enable}` |
| `/api/sys/gpu/info` | GET | GPU 初始化快照 |
| `/api/sys/gpu/status` | GET | GPU 实时状态（温度/利用率/内存/功耗/风扇） |
| `/api/sys/console` | GET | 控制台日志缓冲区文本 |
| `/api/sys/fs/list` | GET | 文件系统浏览。Query: `path`。返回目录（最多 500）+ 文件（最多 10），阻止符号链接 |
| `/api/search/setting` | POST | 保存搜索设置（智谱 API Key）。Body: `{zhipu_search_apikey}` |
| `/api/shutdown` | POST | 优雅关闭（停止所有模型 → `System.exit(0)`） |

#### 采样参数设置
| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/sys/model/sampling/setting/list` | GET | 列出所有采样预设 |
| `/api/sys/model/sampling/setting/get` | GET | 获取模型的采样绑定。Query: `modelId` |
| `/api/sys/model/sampling/setting/set` | POST | 绑定采样到模型。Body: `{modelId, configName}` |
| `/api/sys/model/sampling/setting/add` | POST | 添加/更新采样预设。Body: `{configName, temperature, top_p, ...}` |
| `/api/sys/model/sampling/setting/delete` | POST | 删除采样预设。Body: `{configName}` |

#### 设备 / VRAM
| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/model/device/list` | GET | 列出 GPU 设备。Query: `llamaBinPath`、`nodeId` |
| `/api/models/vram/estimate` | POST | VRAM 估算。Body: `{modelId, cmd, device, mg, llamaBinPath, ...}` |

#### 参数列表
| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/models/param/server/list` | GET | 从 `server-params.json` 资源文件加载可用服务端参数定义 |
| `/api/models/param/benchmark/list` | GET | 从 `benchmark-params.json` 资源文件加载基准测试参数定义 |

#### 节点管理
| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/node/list` | GET | 列出所有远程节点 |
| `/api/node/add` | POST | 添加节点。Body: `{nodeId, name, baseUrl, apiKey, tags, enabled}` |
| `/api/node/remove` | POST | 删除节点。Body: `{nodeId}` |
| `/api/node/update` | POST | 更新节点。Body: `{nodeId, name, baseUrl, apiKey, tags, enabled}` |
| `/api/node/test` | POST | 测试节点连通性。Body: `{nodeId}`。测量延迟 |
| `/api/node/status` | GET | 全部节点状态（status/lastHeartbeat/enabled） |
| `/api/node/info` | GET | Hub 节点信息 |

#### 基准测试
| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/models/benchmark` | POST | V1 基准测试（直接运行 `llama-bench`）。Body: `{modelId, cmd, llamaBinPath}` |
| `/api/models/benchmark/list` | GET | 列出 V1 测试结果文件。Query: `modelId` |
| `/api/models/benchmark/get` | GET | 获取 V1 测试结果。Query: `fileName` |
| `/api/models/benchmark/delete` | POST | 删除 V1 测试结果。Query: `fileName` |
| `/api/v2/models/benchmark` | POST | V2 基准测试（通过 `BenchmarkService`）。Body: `{modelId, promptTokens, maxTokens}` |
| `/api/v2/models/benchmark/get` | GET | 获取 V2 测试记录。Query: `modelId` |
| `/api/v2/models/benchmark/delete` | POST | 删除 V2 测试记录。Body: `{modelId, lineNumber}` |

#### 代理端点
| 端点 | 方法 | 说明 |
|------|------|------|
| `/tokenize` | POST | 代理到模型子进程的 `/tokenize`。Body: `{content, add_special, parse_special, with_pieces, modelId}` |
| `/apply-template` | POST | 代理到模型子进程的 `/apply-template`。Body: `{messages, modelId}`。返回 `{prompt}` |
| `/infill` | POST | 代理到模型子进程的 `/infill`。透明转发所有请求头+请求体 |

---

## WebSocket

**路径：** `/ws`（仅 8080 端口）

**客户端（`js/websocket.js`）：**
- 自动连接，1 秒重连间隔
- 首次连接后触发 `triggerModelListLoad()`
- 心跳确认：发送 `{"type":"connect"}` 后等待 `{"type":"confirm"}`

**事件类型：**
| 事件 | 方向 | 说明 | 负载 |
|------|------|------|------|
| `modelLoadStart` | 服务端→客户端 | 模型开始加载 | `{modelId, port, message}` |
| `modelLoad` | 服务端→客户端 | 模型加载成功/失败 | `{modelId, success, message, port}` |
| `modelStop` | 服务端→客户端 | 模型已停止 | `{modelId, success, message}` |
| `model_status` | 服务端→客户端 | 模型状态更新 | - |
| `model_slots` | 服务端→客户端 | Slots 状态更新 | `{modelId, slots: [{id, speculative, is_processing}]}` |
| `console` | 服务端→客户端 | 控制台日志行（base64 编码），远程节点事件含 `nodeId` 字段，前端按 `timestamp` 排序 | `{modelId, line64, nodeId?, timestamp}` |
| `notification` | 服务端→客户端 | 通用通知 | - |
| `download_update` | 服务端→客户端 | 下载状态变更 | `{taskId, state, ...}` |
| `download_progress` | 服务端→客户端 | 下载进度 | `{taskId, bytes, speed, ...}` |
| `systemMonitor` | 服务端→客户端 | 系统监控（仅 Linux） | `{cpu, memory, gpu, load, processes, network}` |

**心跳：** 服务端 30 秒一次 Ping，60 秒一次系统状态广播（Linux 下执行 `system_monitor_json.sh` 脚本）

**控制台缓冲区：** 最大 2MB（`CONSOLE_BUFFER_MAX_BYTES`），UTF-8 安全截断。从 `logs/app.log` 尾部预加载。

**远程节点日志：** 远程节点的日志通过 `RemoteWebSocketClient` 中继，WebSocket 事件带有 `nodeId` 和 `timestamp` 字段。远程日志**不写入**本地 CONSOLE_BUFFER（避免 snapshot 与 WS 推送重复），而是由前端 `console-modal.js` 的 `remoteLinesBuffer` 缓存，在 `fetchConsole()` snapshot 替换后恢复。`websocket.js` 传递 `data.timestamp` 给 `appendLogLine`，`flushPendingLogs()` 按 `timestamp` 排序后写入 DOM，保证跨节点日志按生成时间排列。

---

## MCP 服务器（端口 8075）

### 内置 MCP 服务器（`DefaultMcpServiceImpl`）
自定义 Streamable HTTP MCP 服务器实现，提供以下工具：

| 工具名 | 说明 | 参数 |
|--------|------|------|
| `get_models` | 列出所有可用模型 | - |
| `get_model_path` | 获取模型路径 | `modelId` |
| `get_param_info` | 获取参数信息 | `paramName` |
| `get_llamacpp_info` | 获取 llama.cpp 版本信息 | - |
| `get_mcp_service_info` | 获取 MCP 服务信息 | - |
| `read_static_image` | 读取静态图片文件 | `path` |

另有时间和文件操作相关 MCP 工具（在 `org.mark.test.mcp.tools` 下）。

### MCP 客户端（`McpClientService`）
管理注册到本服务的外部 MCP 服务器：

**配置存储：** `config/mcp-tools.json`

**支持的传输协议：**
- `sse` — Server-Sent Events（长连接）
- `streamable-http` — Streamable HTTP

**注册流程：**
1. `initialize()` 握手：`initialize` → `notifications/initialized`
2. 工具索引：`tools/list` → 构建 `toolToUrl` 映射
3. 工具调用：`tools/call`（JSON-RPC 2.0）
4. 环境变量占位符：`${VAR_NAME}` 自动解析

### 内置工具
- `get_current_time` — 获取指定时区当前时间
- `convert_time` — 时区时间转换
- `builtin_web_search` — 智谱 AI 网络搜索（需要 API Key）

---

## 配置系统

所有配置文件在 `config/` 目录下，**此目录被 `.gitignore` 忽略**。首次启动自动创建 `application.json` + `models/` + `llamacpp/` 目录。

### `config/application.json`
```json
{
  "nodeRole": "master",
  "server": { "webPort": 8080, "anthropicPort": 8070 },
  "download": { "directory": "downloads" },
  "security": { "apiKeyEnabled": false, "apiKey": "" },
  "compat": {
    "ollama": { "enabled": false, "port": 11434 },
    "lmstudio": { "enabled": false, "port": 1234 },
    "mcpServer": { "enabled": false }
  },
  "logging": { "logRequestUrl": false, "logRequestHeader": false, "logRequestBody": false },
  "https": { "enabled": false, "keystorePath": "ssl/keystore.p12", "keystorePassword": "changeit" }
}
```
所有字段可通过 `PATCH /api/sys/setting` 动态修改，修改后立即写入磁盘。

**节点角色（`nodeRole`）：**
- `"master"` — 主节点，主动连接所有子节点的 WebSocket 并执行健康检查
- `"slave"` — 子节点，跳过远程节点 WebSocket 连接和健康检查，避免循环依赖
- **默认值为 `null`**（等同于 slave）。`application.json` 中无此字段时不视为 master。
- 判断方法：`LlamaServer.isMasterNode()` 检查 `nodeRole != null && "master".equalsIgnoreCase(nodeRole)`
- **`NodeManager.listEnabledNodes()`** 返回空列表，所有依赖该方法的代码路径（模型列表合并、API 转发、聊天路由）自动对 slave 节点禁用。

### `config/modelpaths.json`
```json
{ "items": [{ "path": "D:\\Models", "name": "我的模型", "description": "" }] }
```
模型搜索路径列表。每个路径下的子目录如果有 `.gguf` 文件则视为模型。

### `config/models.json`
```json
[{ "modelId": "Qwen3-0.6B-GGUF", "alias": "轻量小模型", "favourite": true }]
```
已发现的模型元数据：别名和收藏标记。

### `config/launch_config.json`
```json
{
  "Qwen3-0.6B-GGUF": {
    "selectedConfig": "默认配置",
    "configs": {
      "默认配置": { "llamaBinPath": "llamacpp/win-vulkan", "device": ["GPU"], "cmd": "--temp 0.7", ... },
      "高性能": { ... }
    }
  }
}
```
多配置支持。旧版单配置格式在读取时自动升级为 `{selectedConfig, configs}` 格式。删除全部配置后自动回填"默认配置"。

### `config/model-sampling.json`
采样参数预设：
```json
{
  "Qwen3.5-RP": { "temperature": 1.2, "top_p": 0.95, "top_k": 40, "repeat_penalty": 1.1, ... },
  "Qwen3.5-Genera": { "temperature": 0.7, "top_p": 0.9, ... }
}
```
支持的参数：seed, temperature, samplers, top_p, min_p, top_n_sigma, repeat_penalty, top_k, presence_penalty, frequency_penalty, dry_multiplier, dry_base, dry_allowed_length, dry_penalty_last_n, dry_sequence_breakers, force_enable_thinking, enable_thinking

### `config/model-sampling-settings.json`
```json
{ "Qwen3-0.6B-GGUF": "Qwen3.5-Genera" }
```
模型→采样预设的绑定。

### `config/llamacpp.json`
```json
{ "items": [{ "name": "win-vulkan", "path": "llamacpp/win-vulkan", "description": "" }] }
```
llama.cpp 二进制目录列表。默认目录 `llamacpp/` 自动扫描。

### `config/nodes.json`
```json
{ "nodes": [{ "nodeId": "server2", "name": "远程服务器", "baseUrl": "https://10.8.0.6:8080", "apiKey": "...", "enabled": true }] }
```
远程节点配置。对应 POJO `NodesConfigData.java`（`List<LlamaHubNode> nodes`），通过 Gson 直接读写，避免手动 Map 转换。

### `config/mcp-tools.json`
MCP 客户端注册的外部工具服务器。

### `config/capabilities/{modelId}.json`
```json
{ "modelId": "Qwen3-0.6B-GGUF", "tools": true, "thinking": true, "rerank": false, "embedding": false, "vision": false, "audio": false }
```
每模型能力标记，首次检测后自动生成。

### `config/zhipu_search.json`
```json
{ "apiKey": "..." }
```
智谱 AI 网络搜索 API Key。

### `config/model-chat-template-kwargs.json`
```json
{ "modelId": { "enable_thinking": true, "thinking_budget_tokens": 1024 } }
```
每模型 `chat_template_kwargs` 配置。

### 文件写入
所有配置文件通过 `writeJsonFileAtomic()` 原子写入：先写 `.tmp` 文件 → `ATOMIC_MOVE` → 重命名。非原子文件系统降级为普通 move。

---

## 日志系统

**配置：** `src/main/resources/log4j2.xml`
- 输出到 `logs/app.log`，每日滚动（`app-{yyyy-MM-dd}.log.gz`），7 天保留
- 格式：`%d{yyyy-MM-dd HH:mm:ss.SSS} - %msg%n`
- 级别：INFO

**辅助日志器：**
- `STDOUT` — System.out 捕获
- `STDERR` — System.err 捕获
- `LLAMA_CPP_RAW` — 模型子进程原始输出（过滤 `update_slots` 和 `log_server_r` 行）
- `CONSOLE_BUFFER` — 控制台缓冲区日志器

**调试模式：**
`application.json` 中设置 `logging.logRequestUrl/Header/Body` 为 `true`，可在日志中查看请求详情。

---

## SSL/HTTPS

**配置：** `application.json.https`
```json
{ "enabled": true, "keystorePath": "ssl/keystore.p12", "keystorePassword": "changeit" }
```

**支持的存储类型：** PKCS12（`.p12`/`.pfx`）、JKS（`.jks`/`.keystore`）

**自动扫描：** 如果 `keystorePath` 是目录，自动查找目录中的证书文件（优先 `.p12`）。

**详细指南：** `ssl/HTTPS_SETUP.md`（含 keytool 自签名证书生成和客户端信任配置）

**注意：** `ssl/` 目录被 `.gitignore` 忽略，HTTPS 需要手动设置。

---

## 下载系统

### 架构
`DownloadManager`（单例）管理下载任务，使用 `BasicDownloader` 执行 HTTP 下载。

### 功能
- HTTP/HTTPS 下载
- 断点续传（基于 ETag + Range）
- 多部分并行下载（使用虚拟线程，分区粒度 8MB，并行度 = CPU 核数 ≤ 8）
- 重试机制（最多 5 次，指数退避）
- 下载限速？无
- 最大并发：4
- 进度通知：每秒轮询通过 WebSocket 广播
- 持久化：任务列表保存到 `downloads/tasks.json`

### 下载状态机
`IDLE` → `PREPARING` → `DOWNLOADING` → `MERGING` → `VERIFYING` → `COMPLETED`
所有阶段都可能 → `FAILED`。`DOWNLOADING` 可 → `PAUSED` 再 → `RESUMED`。

### 暂停/恢复
暂停时保存 `downloadedBytes`、`parts`、`etag`、`finalUri`。恢复时验证 ETag/size 一致。

### 文件命名
- 下载中的文件：`{fileName}.downloading`
- 完成后重命名为 `{fileName}`
- 自动从 URL 提取文件名

---

## 前端

**技术栈：** 纯原生 JS，无框架、无 bundler、无 npm/`package.json`。JS 通过 `<script src>` 直接加载。
**第三方库：** Font Awesome（图标，`css/all.min.css`）、Highlight.js（代码高亮，`js/highlight.min.js`）、Marked（Markdown，`js/marked.min.js`）、MathJax（LaTeX，动态加载）、lz-string（压缩，`js/lz-string.js`）、Google Fonts Inter（`css/css2.css`）

### 页面架构

#### `index.html` — 桌面 SPA 管理面板
**7 个页面视图（`<main>` 切换）：**
| 视图 ID | 导航名 | 功能 |
|---------|--------|------|
| `main-model-list` | 模型列表 | 模型卡片（icon/架构/量化/大小/状态/Slots/端口）、搜索、排序（名/大小/参数）、收藏、加载/停止/详情/基准测试按钮 |
| `main-download` | 下载管理 | 状态统计（活跃/等待/完成/总数）、下载列表（URL/文件名/进度条/速度/状态）、暂停/恢复/删除操作 |
| `main-llamacpp-setting` | llama.cpp 路径 | 路径列表、添加（含目录浏览）、编辑、测试（版本+设备）、删除 |
| `main-model-path-setting` | 模型路径 | 路径列表、添加/编辑/删除 |
| `main-settings` | 设置 | 6 个标签页：Server（webPort/anthropicPort）、Compatibility（Ollama/LMStudio 开关+端口、MCP 开关）、Security（API Key 开关+值）、HTTPS（开关+证书路径+密码）、Logging（请求 URL/Header/Body 日志开关）、Download（下载目录） |
| `main-device-status` | 设备状态 | GPU 实时状态（1 秒轮询）、JSON 展示 |
| `main-hf` | HuggingFace | 搜索框（query/limit/base 镜像选择）、模型结果网格（名称/下载量/收藏/参数）、Load more、GGUF 文件详情弹窗（分卷分组/复制下载链接/创建下载任务） |
| `main-console` | 控制台 | 控制台日志、自动刷新、刷新间隔设置 |

**弹窗：**
- 加载模型弹窗（`loadModelModal`）：左侧（modelId/配置选择/llama.cpp 版本/能力勾选/视觉开关/GPU 选择/设备勾选）+ 右侧（可折叠参数分组：basic/sampling/performance/features/template/multimodal/default + extraParams 文本框）+ 底部按钮（估算 VRAM/重置/保存/加载/停止）
- 添加 llama.cpp 弹窗：路径（含目录浏览）、名称、描述
- 添加模型路径弹窗：名称、路径（含目录浏览）、描述
- HF GGUF 弹窗：仓库名、复制全部链接、GGUF 文件分组列表（分卷检测）、下载按钮
- 创建下载弹窗：URL、路径、文件名
- 目录浏览弹窗（`directoryBrowserModal`）：双面板（文件夹/文件）、根目录导航、选择按钮
- 别名弹窗（动态创建）：原名（只读）、别名输入

**样式（`css/index.css`）：**
- 300px 侧边栏 + 主区域布局
- 模型卡片：图标 48px、状态徽章、Slots 方块（绿色=空闲/黄色=正在处理/灰色=推测）
- 模态框：半透明背景模糊
- Toast 通知：右上角固定、自动消失
- 断点：768px / 900px / 1000px

#### `index-mobile.html` — 移动端 SPA
**导航：** 底部导航栏（模型列表 / HF 搜索 / 下载 / 设置）

**页面：** 模型列表（搜索、排序、聊天按钮、并发测试按钮、统计）、HF 搜索、下载管理、设置（通用/网络搜索/llama.cpp 路径/模型路径/控制台/MCP 管理/关机）

**JS 文件命名规范：** 移动端 JS 以 `-mobile.js` 后缀标识。

#### `chat/index.html` — 完整聊天界面（5730 行）
**API：** OpenAI `/v1/chat/completions`

**布局：** 侧边栏（品牌/新建对话/搜索/对话列表）+ 主区域（顶部状态栏/消息滚动区/撰写器）

**功能完整清单：**
- **对话管理：** 创建、切换、搜索、删除（含确认）、对话星级/删除菜单
- **助手配置：** 创建/删除/切换助手、每助手独立设置（system prompt、采样参数、MCP 工具绑定）
- **模型选择：** 下拉列表、刷新按钮、能力感应（有音频能力的模型启用录音按钮）
- **多模态输入：** 文本、图片（最多 6 张、每张最大 10MB）、文本文件（最大 512KB）、音频文件（最大 25MB）
- **音频录制：** ScriptProcessorNode WAV 录音机、麦克风权限管理、自动转换、计时器
- **流式推送：** SSE 消费（`consumeSse()` 函数）
- **Markdown 渲染：** Marked + Highlight.js 代码高亮
- **LaTeX 数学公式：** MathJax 渲染 `$` / `$$` / `\(` / `\[`
- **代码复制按钮：** 每个 `<pre><code>` 块自动添加
- **图片灯箱：** 全屏图片预览
- **消息编辑：** 内联编辑 + 附件管理（图片/文本文件/音频的增删）
- **消息版本：** 多版本回复，前后切换，每版本时间戳
- **推理/思考：** 可折叠思考块
- **工具调用：** 可折叠工具调用显示 + 结果渲染
- **性能指标：** Token 统计、推理时间
- **隐私模式：** 隐藏对话标题和助手名称
- **导出对话：** JSON 下载
- **MCP 集成：** 服务器列表、工具开关、JSON 添加对话框（Streamable HTTP / SSE 模板）、移除服务器
- **设置抽屉：** 3 个标签页 — General（助手选择/名字/system prompt/流式开关）、Sampling（max_tokens/temperature/top_p/top_k/min_p/presence_penalty/repeat_penalty/frequency_penalty/stop sequences）、MCP（添加服务器/服务器列表/工具启用）
- **状态同步：** 服务端持久化、revision 冲突检测和重试

#### `easyRP/easy-chat.html` + 5 个 `completion-*.js` — 角色扮演聊天
**API：** `/v1/completions` + `/v1/chat/completions`

**JS 文件分工：**
- `completion-dom-state.js`（290 行）：DOM 元素引用（`els` 对象）、状态对象（`state`）
- `completion-net-tools.js`（465 行）：`fetchJson()` 封装、MCP 工具刷新、文件附件处理、localStorage 备份
- `completion-ui-render.js`（1297 行）：消息气泡渲染（头像/元数据/内容/推理/工具调用/附件/版本切换/操作按钮）、面板管理、编辑/头像/KV Cache/MCP 弹窗、HTML 预览
- `completion-runtime.js`（1637 行）：`buildPrompt()` 纯文本提示词构建、`buildContent()` 消息数组构建、生成循环（最多 3 轮工具调用）、流式/非流式、MCP 工具调用、KV Cache 保存/加载、自动保存防抖
- `completion-init.js`（517 行）：事件绑定、抽屉标签切换、粘贴处理、快捷键（Ctrl+Enter 保存编辑）

**功能特点：**
- 角色（Character）系统：system prompt / role prompt / 名称 / 头像
- Session 管理：创建、切换、删除
- Topic 管理：每个 session 多个 topic
- KV Cache 插槽保存/加载
- API 模型切换（`/v1/completions` vs `/v1/chat/completions`）
- 文件/图片上传
- 消息版本历史
- 用户/助手前缀后缀模板

#### `tools/benchmark-v2.html` — 基准测试工具
**双面板布局：** 左侧（模型选择、prompt tokens 默认 8192、output max tokens 默认 256、并发数默认 1、运行/刷新按钮、所有模型列表+加载状态）+ 右侧（记录表格：时间/模型/prompt tokens/prefill 速度/output tokens/decode 速度/删除）

**功能：** 单次和并发基准测试、AbortController 取消、记录追加/替换模式、硬件信息缓存、单条删除

#### `tools/mcp-manager.html` — MCP 管理工具
**双面板布局：** 左侧（MCP 服务器列表：名称/URL/工具数）+ 右侧（选中服务器详情+工具列表：名称/描述/输入 schema JSON）

**功能：** JSON 添加服务器、重命名、删除、工具浏览

#### `audio-transcription.html` — 音频转写
**标签页：** 上传（拖拽+文件选择，WAV/MP3/M4A/OGG/FLAC/WEBM/OPUS，最大 25MB）+ 录音（麦克风按钮、计时器、预览播放）

**参数：** 模型选择（自动/已加载音频模型）、语言选择（auto/zh/en/yue/ar/de/fr/es/pt/id/it/ko/ru/th/vi/ja/tr/hi/ms/nl/sv/da/fi/pl/cs/fil/fa/el/hu/mk/ro）

### JS 文件详表

| 文件 | 行数 | 用途 |
|------|------|------|
| `js/i18n.js` | 127 | 国际化：自动检测 `?lang=` → localStorage → 浏览器语言 → `zh-CN` 兜底。设置 `window.I18N`、派发 `i18n:ready` 事件、DOM 属性翻译 |
| `js/websocket.js` | 175 | WebSocket 客户端：1s 重连、事件分发（`modelLoadStart`/`modelLoad`/`modelStop`/`notification`/`model_status`/`model_slots`/`console`/`download_update`/`download_progress`）。与 `model-list.js` 紧耦合调用其内部函数 |
| `js/model-icon.js` | 29 | 架构名到图标 Font Awesome CSS class 映射表 |
| `js/model-list.js` | 476 | 模型列表渲染（`sortAndRenderModels()`/`updateModelSlotsDom()`）、搜索、排序（名称/大小/参数）、收藏、加载状态 |
| `js/model-detail.js` | 1312 | 模型详情弹窗：标签页（概览/采样/template/token 计数/kwargs/slots）、能力编辑、chat template kwargs 编辑、采样参数绑定、tokenize 测试 |
| `js/model-action-modal.js` | 1599 | 加载/基准测试/停止模型弹窗：动态参数表单（从 `/api/models/param/server/list` 加载配置定义，渲染分组可折叠字段）、设备列表、VRAM 估算、启动配置管理（保存/删除/切换） |
| `js/model-benchmark.js` | 709 | V1 基准测试：`llama-bench` 参数表单、结果展示、文件浏览/删除 |
| `js/settings.js` | 397 | 桌面设置页面：6 个标签页的读写/保存、配置加载/保存 |
| `js/settings-mobile.js` | 208 | 移动端设置：通用设置（兼容开关+端口）、网络搜索设置（zhipu API key） |
| `js/hf.js` | 651 | HuggingFace 搜索（桌面）：搜索、分页加载、模型结果网格、GGUF 文件弹窗、创建下载 |
| `js/hf-mobile.js` | 463 | HuggingFace 搜索（移动）：搜索、加载更多、GGUF 文件弹窗、复制/下载 |
| `js/download.js` | 690 | 下载管理（桌面）：列表/统计/进度条/暂停/恢复/删除、创建下载弹窗、路径设置 |
| `js/download-mobile.js` | 531 | 下载管理（移动）：列表/统计/创建/暂停/恢复/删除、路径设置 |
| `js/console-modal.js` | 124 | 控制台弹窗（桌面）：`remoteLinesBuffer` 缓存远程行，snapshot 恢复；按 `timestamp` 排序避免乱序 |
| `js/console-modal-mobile.js` | 128 | 控制台弹窗（移动）：自动刷新、间隔设置 |
| `js/index-mobile-nav.js` | 80 | 移动端底部导航切换（show/hide 对应 `<main>`） |
| `js/llamacpp-setting-mobile.js` | 349 | 移动端 llama.cpp 路径管理 |
| `js/model-path-setting-mobile.js` | 246 | 移动端模型路径管理 |
| `js/model-llamacpp-setting.js` | 335 | llama.cpp 路径设置渲染（桌面） |

### 动态参数表单系统（`server-params.json` + `model-action-modal.js`）

#### `server-params.json` 参数结构
每个参数是一个 JSON 对象，字段说明：

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | string | i18n 翻译 key，用于 `t(param.name, param.fullName\|abbr\|name)` 取显示名 |
| `fullName` | string | 长格式 CLI flag，如 `--ctx-size`。为空时参数字段将使用缩写或回退到 `unnamed_` 方案 |
| `abbreviation` | string | 短格式或别名，如 `-c`。为空不影响参数工作 |
| `type` | enum | `STRING` / `INTEGER` / `FLOAT` / `LOGIC` / `BOOLEAN`。决定表单控件类型和命令行序列化逻辑 |
| `defaultValue` | string | 默认值，用于初始状态和配置回读 |
| `values` | array | 可选项列表。元素可以是简单字符串或 `{value, label}` 对象 |
| `uiType` | string | 特殊控件标记：`ordered-multiselect`（排序多选控件） |
| `delimiter` | string | `ordered-multiselect` 的分隔符，默认 `;` |
| `defaultEnabled` | bool | 是否默认启用该参数（勾选框默认勾选） |
| `sort` | number | 排序权重，越小越靠前 |
| `group` | string | i18n 分组名，如 `page.params.group.basic` |
| `groupOrder` | number | 分组排序权重 |
| `groupCollapsed` | bool | 分组默认是否折叠 |

#### 纯值/裸标记参数模式（如 `param.server.directio`）
某些参数没有 flag 名，其值本身就是 CLI flag（`-dio` / `-ndio`）。这种参数的 `fullName` 和 `abbreviation` 均为空字符串，`type` 为 `STRING`，`values` 为可选 flag 列表，`defaultValue` 为默认 flag。

处理上与其他参数不同：
- **构建命令行：** `buildLoadModelPayload()` 中走 `type==='STRING' && !fullName && !abbr` 分支，直接将 `values` 中的选中值（如 `-ndio`）作为裸 token 追加到 `cmd`，不加 flag 前缀
- **解析命令行：** `applyCmdToDynamicFields()` 第三阶段通过 `buildAllowedBareTokenSetFromParamConfig()` 将此类参数的值注册到 `allowedBareTokens` 集合；`sanitizeExtraParamTokens()` 据此放行这些裸 token，防止被过滤
- **启用/禁用：** 当 cmd 中包含对应值时勾选框自动启用；为空时按照 `defaultEnabled` 决定初始状态

#### 参数管道：表单 ↔ 命令行字符串
```
renderDynamicParams()          ← 从 /api/models/param/server/list 获取 paramConfig
  → renderParamGroup()
    → renderParamField()       ← 生成 HTML：勾选框 + 控件（select/number/ordered-multiselect 等）
    → initOrderedMultiSelects()

applyCmdToDynamicFields()      ← 将已有的 cmd 字符串回填到表单
  第一遍 (342-381): 按 optionLookup 匹配已知 flag，设置值和启用状态
  第二遍 (383-417): 处理 bare token 类型参数（directio 模式），匹配 values 列表
  第三遍 (419-428): 未设置的 LOGIC 参数初始化为 "0"

buildLoadModelPayload()        ← 将表单状态序列化为 cmd 字符串
  STRING+空fullName+空abbr:    直接输出选中值作为裸 token
  其他 STRING/INTEGER/FLOAT:   输出 "fullName 值"
  LOGIC:                      如果值为真则输出 "fullName"
  ordered-multiselect:        输出 "fullName 分号连接值"
  extraParams:                原样透传
```

#### 关键辅助函数
| 函数 | 位置 | 作用 |
|------|------|------|
| `fieldNameFromParamConfig(p)` | `model-action-modal.js:133` | 从 fullName → abbreviation → name 逐步 fallback，生成字段名（如 `mlock`、`unnamed_param_server_directio_name_25`） |
| `fieldNameFromFullName(s)` | `model-action-modal.js:122` | 去除 `--`/`-` 前缀，如 `--ctx-size` → `ctx_size` |
| `buildOptionLookupFromParamConfig()` | `model-action-modal.js:201` | 将非空 fullName/abbreviation 注册到 lookup map，用于快速判断 token 是否为已知 flag |
| `buildAllowedBareTokenSetFromParamConfig()` | `model-action-modal.js:235` | 收集 bare token 参数中以 `-` 开头的值，用于 `sanitizeExtraParamTokens()` 放行 |
| `getParamUiType(p)` | `model-action-modal.js:214` | 获取 uiType（如 `ordered-multiselect`） |
| `getParamOptionValues(p)` | `model-action-modal.js:219` | 抽取 `values` 数组中的值列表（支持简单值和 `{value,label}` 对象） |
| `getParamOptionItems(param)` | `index.html:1452` / `index-mobile.html:906` | 渲染端抽取 values，对 `{value,label}` 对象做 i18n 翻译 |
| `isLoadModelParamEnabled()` | `model-action-modal.js:144` | 检查 `param_enable_{fieldName}` 勾选框是否选中 |
| `setParamEnabled()` | `model-action-modal.js:288` | 设置 `param_enable_{fieldName}` 勾选框状态 |

#### 关于 `fullName`/`abbreviation` 为空的重要约定
- 当 `fullName` 为空时，`fieldNameFromParamConfig()` 会尝试 `abbreviation`，再回退到 `unnamed_{sanitizedName}_{sort}` 方案
- `buildOptionLookupFromParamConfig()` **不会**向 lookup 注册空字符串，所以 bare token 参数不被视为"已知 flag"，需要走 `buildAllowedBareTokenSetFromParamConfig()` 白名单放行
- `buildLoadModelPayload()` 中 `if (!fullNameTrimmed && !abbrTrimmed) continue;` 确保只有两者都空才跳过（修复前是 `if (!fullNameTrimmed) continue;`）

### i18n 国际化
- 语言检测顺序：`?lang=` 查询参数 → `localStorage.getItem('lang')` → `navigator.language`
- 兜底：`zh-CN`
- 翻译文件：`i18n/en-US.json`、`i18n/zh-CN.json`
- 每个 JS 文件定义自己的 `t(key, fallback)` 简写函数
- DOM 自动翻译：`data-i18n`（文本内容）、`data-i18n-attr`（属性值）
- `window.setLang(lang)` 函数切换语言

### API 调用模式
- **老文件：** `fetch(url).then(r => r.json()).then(data => {...})`
- **新文件：** `async function fetchJson(url, options) {...}`
- **无中心化 API 模块**，每个文件自行调用

### 全局状态
大量使用 `window.*` 全局变量：`window.I18N`、`window.paramConfig`、`window.currentModelsData`、`window.__modelDetail*`、`window.__loadModel*`、`window.__capabilities*`、`window.showModal`、`window.showToast`

### WebSocket 紧耦合
`websocket.js` 直接调用 `model-list.js` 导出的函数：
- `window.currentModelsData`（模型数据缓存）
- `sortAndRenderModels()`（重渲染列表）
- `updateModelSlotsDom()`（更新 slots 方块）
- `showModelLoadingState()`（显示加载状态）

---

## CI/CD（GitHub Actions）

### `.github/workflows/build-and-release.yml`
- 触发：`v*.*.*` tag
- 步骤：
  1. `mvn clean package` 构建 fat JAR
  2. 下载 mini JRE（Linux + Windows，Liberica NIK）
  3. 下载 llama.cpp 预编译二进制（CUDA 12/13、Vulkan、HIP、ROCm 7.2）
  4. 打包多种分发包（不同后端组合）
  5. 创建 GitHub Release

### `.github/workflows/docker-image.yml`
- 触发：每个 push
- 构建两个镜像：`build-vulkan`（tag: `vulkan`、`latest`、`sha-*`、版本）和 `build-rocm`（tag: `rocm`、`sha-*`、版本）
- 推送到 `ghcr.io/[owner]/llamasp`

### Dockerfile
多阶段构建：
- `builder`：`eclipse-temurin:21-jdk-jammy` 编译
- `runtime-base`：`eclipse-temurin:21-jre-jammy` 运行
- `runtime-vulkan`：+ Vulkan 驱动
- `runtime-rocm`：`rocm/dev-ubuntu-24.04:7.0-complete`

---

## git 配置

### `.gitignore`
```
/target/  /config/  /logs/  /releases/  /.settings/
/benchmarks/  /.project  /.classpath  /cache/
/downloads/  /models/  /llama.cpp/  /build/  /llamacpp/  /ssl/
```

**重要：** `config/` 不提交到 git。clone 后首次启动自动生成 `application.json`，但其他配置（`modelpaths.json`、`llamacpp.json` 等）需要手动补充。

---

## 新手陷阱

1. **pom.xml 不管理依赖** — 改依赖需要手动替换 `lib/` 的 JAR 并更新 `.classpath`，永远不碰 pom.xml
2. **所有模型作为 OS 子进程运行** — 每个加载的模型是一个独立的 `llama-server.exe` 进程，在各自的端口上运行。不是 JVM 内嵌
3. **`BuildInfo.java` 的占位符在 CI 中被替换** — 本地开发时显示原始 `{tag}` `{version}` `{createdTime}`
4. **无测试/无 lint** — 需要手动验证改动
5. **`config/` 被 gitignore** — 首次启动自动创建 `application.json`，但不会自动创建 `modelpaths.json` 等其他配置
6. **`modelpaths.json` 不存默认路径** — 即使没有该文件，也会自动扫描 `models/` 目录
7. **`llamacpp.json` 和默认扫描** — 即使没有该文件，也会自动扫描 `llamacpp/` 目录
8. **所有请求使用虚拟线程** — `Executors.newVirtualThreadPerTaskExecutor()`，但模型加载使用单虚拟线程（顺序加载）
9. **OpenAI API 通过 `HttpURLConnection` 转发** — 不是 Netty 直连。每个请求新建 HTTP 连接到 `localhost:{modelPort}`
10. **前端无 bundler** — 所有 JS 文件独立加载，用 `<script>` 标签引入，无模块系统
11. **前端全局变量泛滥** — 大量使用 `window.*` 而非模块导入
12. **WebSocket 紧耦合** — websocket.js 直接调用 model-list.js 的内部函数，改名或修改需要两端同步

---

## 远程节点代理机制

### REST API 代理（`ModelInfoController`）

`proxyGetRemote()` / `proxyPostRemote()` 将请求转发到远程节点。**转发时会自动移除 `nodeId` 参数**（GET 从查询参数中去掉，POST 从 JSON body 中移除），避免远程节点收到后再次尝试代理形成回环。

**其他控制器中的直接代理调用也需手动处理 `nodeId` 移除：**
- `ModelActionController.loadRemoteModel()` — `body.remove("nodeId")`
- `SystemController.handleVramEstimateRemote()` — `body.remove("nodeId")`
- `ModelActionController.stopRemoteModel()` 创建新 body 不含 `nodeId`，安全
- GET 类代理请求无 body，通过 URL path 转发，不存在回环风险

**聊天 API 的远程路由：** 前端在 `/v1/chat/completions` / `/v1/completions` / `/api/chat`（Ollama）请求体中添加 `nodeId` 字段，后端 `ChatStreamSession`（流式）和 `OpenAIService`（非流式）检测到 `nodeId` 后直接调用 `resolveRemoteModelUrl()` 将请求转发到远程节点，不再回退到遍历全部节点的兜底路径。`ChatRequestStreamingTransformer` 会在写入子进程输出前自动剥离 `nodeId` 字段。

### WebSocket 事件转发（后端中继）

`RemoteWebSocketClient.java` 作为 WebSocket 客户端从后端连接到每个远程节点的 `/ws` 端点，将远程事件中继到本地前端，避免前端直接连接多个远程 WS。

**连接机制：**
- `NodeManager.initialize()` 遍历已启用节点，为每个节点创建 `RemoteWebSocketClient` 并 `start()`
- URL 转换：`https://host:port` → `wss://host:port/ws`，`http://host:port` → `ws://host:port/ws`（注意 scheme 映射：检查 `"https"` 而非 `"wss"`）
- 使用 `java.net.http.HttpClient` + `WebSocket.Builder`（JDK 内置，零额外依赖）
- SSL：`createTrustAllSSLContext()` 信任所有证书（自签名兼容）
- 连接超时：10 秒

**重连策略：**
- 指数退避：1s → 2s → 4s → ... → 最大 30s
- `scheduleReconnect()` 捕获 `RejectedExecutionException`scheduler shutdown 竞争
- `onClose`/`onError` 触发重连，`stopped=true` 时停止

**中继逻辑（`relayMessage()`）：**
- 过滤过滤 `heartbeat`/`connect_ack`/`welcome`（远程节点内部消息，不转发）
- 注入 `nodeId` 字段到所有事件 JSON
- `console` 事件：将 `line` 统一转为 `line64`（base64），**不写入本地 CONSOLE_BUFFER**（避免 snapshot 与 WS 推送重复）
- 其余字段原样透传，通过 `WebSocketManager.broadcast()` 广播到本地前端

**生命周期管理：**
- `NodeManager.addNode()` → 创建并启动 WS 客户端
- `NodeManager.removeNode()` → 停止并清理
- `NodeManager.updateNode()` → URL/状态变化时重启
- `onNodeStatusChanged()` → OFFLINE→ONLINE 时启动，ONLINE→OFFLINE 时停止

**需要新增文件时留意：**
- `javac-win.bat` / `javac-linux.sh` 需要手工添加新 `.java` 文件到编译列表
- 实际上编译脚本使用 `for /r` 递归扫描全部源文件，新增文件自动包含

**前端模型列表配套修改（`model-list.js` / `websocket.js` / `index.html`）：**
- `applyModelPatch(modelId, patch, nodeId)` 新增 `nodeId` 参数，存在时按 `id + nodeId` 联合查找 `currentModelsData`
- 所有事件处理器（`handleModelLoad*`/`handleModelStopEvent`/`handleModelStatusUpdate`/`handleModelSlotsUpdate`）传入 `data.nodeId`
- 控制台日志：远程节点行显示 `[nodeId/modelId] 前缀`
- **复合键函数 `modelCompositeKey(id, nodeId)`** — 格式 `id::nodeId`，用于 `loadModels()` 匹配已加载状态、`renderModelsList()` slots DOM ID、`updateModelSlotsDom()` 查 DOM、`toggleFavouriteModel()` 定位模型
- **节点筛选下拉框**（`#modelNodeFilter`）— 位于排序下拉框旁边，三个选项：全部（默认）、本机、远程。与排序联动，筛选后的结果再排序
- **空状态适配** — 筛选到远程但无节点时显示"没有远程模型"，本机无模型时显示"本机没有发现模型"
