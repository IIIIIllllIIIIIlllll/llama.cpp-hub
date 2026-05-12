# llama.cpp-hub — 代理指南

本项目是 llama.cpp 的 WebUI + API 兼容层。后端使用 Java 21 + 纯 Netty，前端为纯 vanilla JS（无框架）。它将 `llama-server` 作为子进程启动并管理其生命周期，向前端和外部客户端提供 OpenAI/Anthropic/Ollama/LM Studio 兼容 API。

---

## 构建

### Maven
```
mvn clean package
```
使用 `maven-shade-plugin` 构建 fat JAR。主类：`org.mark.llamacpp.server.LlamaServer`。**pom.xml 中零 `<dependency>` 条目** — 所有依赖在 `lib/` 中手动管理，classpath 由 Eclipse `.classpath` 维护。

### 手动编译
- Windows：`javac-win.bat`
- Linux：`javac-linux.sh`

均使用 `for /r` / `find` 递归扫描所有源文件 — 新 `.java` 文件自动包含。流程：`--release 21` 编译所有源码 → 复制 `lib/*.jar` 到 `build/lib/` → 复制 `src/main/resources/` 到 `build/classes/` → 生成启动脚本。运行参数：`javaw.exe -Xms512m -Xmx512m -XX:MaxDirectMemorySize=256m`。

### 运行时依赖（`lib/`）
| JAR | 版本 | 用途 |
|-----|------|------|
| gson | 2.8.9 | JSON 解析 |
| netty-all | 4.1.35.Final | HTTP/WebSocket 服务器 |
| log4j-api | 2.22.1 | 日志 API |
| log4j-core | 2.22.1 | 日志核心 |
| log4j-slf4j2-impl | 2.22.1 | SLF4J 绑定 |
| slf4j-api | 2.0.9 | SLF4J API |

**修改依赖：** 替换 `lib/` 中的 JAR → 更新 `.classpath` → **永远不碰 pom.xml**。

### 版本信息
`BuildInfo.java` 包含构建常量。Maven 构建时在原位替换 `{tag}`/`{version}`/`{createdTime}` 占位符。当前值为 `"v0.8.2"`。

---

## 测试 / Lint / 类型检查 / 格式化

`src/test/java/com/mark/test/tools/` 下有诊断性测试工具（非单元测试）：
- `GGUFMetaReaderTest.java` — 读取 GGUF 文件元数据并打印 KV 对
- `GpuStatusTest.java` — 打印 `GpuService` 检测的 GPU 信息
- `UsageReportTest.java` — 测试用量报告 API 并格式化输出
- `VideoDescriptionTest.java` — 向 `/v1/chat/completions` 发送视频描述请求

**无测试框架、无 linter、无格式化工具、无 pre-commit hooks。**

---

## 启动流程（`LlamaServer.main()`）

1. 重定向 `System.out/err` 到 `ConsoleBroadcastOutputStream`（发送到 WebSocket 控制台 + 日志记录器）
2. 安装 `ConsoleBufferLogAppender`（自定义 Log4j2 appender，将日志行路由到 WebSocket）
3. 预加载 `logs/app.log` 最后 2MB 到控制台缓冲区
4. 创建 `cache/` 目录
5. 读取 `config/application.json`（如果缺失则自动创建默认配置）
6. 初始化 `ConfigManager`（单例）：加载模型配置和启动配置
7. 初始化 `LlamaServerManager`（单例）：扫描模型目录，构建模型列表
8. 初始化 `ModelSamplingService`（单例）：加载采样预设
9. 初始化 `McpClientService`（单例）：从 `config/mcp-tools.json` 注册 MCP 客户端工具
10. 初始化 `NodeManager`（单例）：加载远程节点。主节点模式连接所有远程 WS 并启动 30 秒健康检查；从节点模式跳过
11. 初始化 `ChatTemplateKwargsService`（单例）：加载每模型 kwargs
12. 初始化 `GpuService`（单例）：启动时检测 GPU 厂商
13. 加载 HTTPS 上下文（`initHttpsContext()`）
14. 在独立线程上启动 WebUI 服务器（`bindOpenAI(webPort)`，默认 8080）
15. 可选启动 LM Studio 兼容服务（默认 1234）
16. 可选启动 Ollama 兼容服务（默认 11434）
17. 可选启动 MCP 服务器（默认 8075）
18. 创建 Windows 系统托盘
19. 如果提供 CLI 参数，自动加载该模型

---

## 端口布局

| 端口 | 服务 | 默认 | 配置键 |
|------|------|------|--------|
| 8080 | 主服务：OpenAI/Anthropic API + WebUI + 内部 API + WebSocket | 开 | `application.json` → `server.webPort` |
| 8075 | MCP 服务器（Streamable HTTP） | 关 | `compat.mcpServer.enabled` |
| 11434 | Ollama 兼容 API | 关 | `compat.ollama.enabled/port` |
| 1234 | LM Studio 兼容 API | 关 | `compat.lmstudio.enabled/port` |
| 8070 | Anthropic（遗留 — **不再绑定**） | 关 | `application.json` → `server.anthropicPort`（存储但未使用） |
| 8081+ | 每个已加载的 llama-server 子进程 | — | 自动分配（`PortChecker` 三重检查） |

---

## 管道（8080 端口）

```
SslHandler（可选）
  → HttpServerCodec
  → OpenAIChatStreamingHandler（拦截 /v1/chat/completions，在聚合前流式处理请求体）
  → HttpObjectAggregator（最大 16MB）
  → ChunkedWriteHandler
  → WebSocketServerProtocolHandler（路径 /ws）
  → WebSocketServerHandler
  ─────────────────────────────────
  → BasicRouterHandler（静态文件 + /api/* 控制器链）
  → CompletionRouterHandler（EasyRP 角色/文件/头像管理）
  → FileDownloadRouterHandler（下载管理）
  → LlamaRouterHandler（/v1/* OpenAI + Anthropic API + API 密钥验证）
```

**Anthropic 协议已合并到 8080 端口。** `LlamaRouterHandler`（原名 `OpenAIRouterHandler`）处理两种协议。Anthropic 端点（`/v1/messages`、`/v1/complete`、`/v1/messages/count_tokens`）在 OpenAI 路由之后匹配。`/v1/models` 通过 `anthropic-version` 或 `x-api-key` 请求头自动检测客户端类型。`bindAnthropic()` 和 `AnthropicRouterHandler` 是**死代码** — 不再绑定到任何端口。

**Ollama 端口管道** 与 8080 类似，包含 `OpenAIChatStreamingHandler` + `OllamaRouterHandler`。

**LM Studio 端口管道** 额外包含 `LMStudioWsPathSelectHandler`（拦截 `/llm` 和 `/system` 路径的 WebSocket 升级）。

---

## `BasicRouterHandler` 控制器链（严格顺序）

URI 以 `/api/`、`/v1`（非 HTML）、`/session`、`/tokenize`、`/apply-template`、`/infill`、`/models`、`/chat/completion`、`/completions`、`/embeddings`、`/rerank`、`/responses` 开头的请求进入该链。迭代控制器；在第一个返回 `true` 的控制器处停止。

1. **`EasyChatController`** — `/api/easy-chat/*`（乐观并发控制的状态同步系统）
2. **`HuggingFaceController`** — `/api/hf/*`
3. **`LlamacppController`** — `/api/llamacpp/*` + 代理端点 `/tokenize`、`/apply-template`、`/infill`
4. **`ModelActionController`** — `/api/models/load/stop/list/loaded/refresh/benchmark/metrics/props`
5. **`ModelInfoController`** — `/api/models/{config, alias, favourite, details, capabilities, template, kwargs, slots, record, openai}`
6. **`ModelPathController`** — `/api/model/path/*`
7. **`NodeController`** — `/api/node/*`
8. **`ParamController`** — `/api/models/param/server/list`、`/api/models/param/benchmark/list`
9. **`ToolController`** — `/api/mcp/*`、`/api/tools/execute`
10. **`SystemController`** — `/api/sys/*`、`/api/shutdown`、`/api/models/vram/estimate`、`/api/model/device/list`
11. **`UsageReportController`** — `/api/report/token-summary`、`/api/report/request-logs`

非 API 请求 → 静态文件服务：移动端检测（`Sec-CH-UA-Mobile` + User-Agent），根路径重定向到 `index.html` 或 `index-mobile.html`，从 `/web/` classpath 资源提供。

所有处理器使用 `retainedDuplicate()` + 虚拟线程（`Executors.newVirtualThreadPerTaskExecutor()`），在 `finally` 块中显式调用 `ReferenceCountUtil.release()`。

---

## 后端核心组件

### 单例管理器
| 类 | 职责 |
|-----|---------------|
| `ConfigManager` | 读写 `config/models.json`、`launch_config.json`、`nodes.json`。原子写入（`.tmp` + `ATOMIC_MOVE`）。`models.json` 基于时间戳的缓存。自动升级旧的扁平启动配置格式。每文件锁。 |
| `LlamaServerManager` | 模型发现、加载/停止、端口分配、能力检测、设备列表、VRAM 估算、槽管理。单个虚拟线程执行器用于顺序模型加载。 |
| `NodeManager` | 远程节点 CRUD + 30 秒健康检查 + API 代理。管理每个节点的 `RemoteWebSocketClient` 生命周期。HTTPS 节点信任所有 SSL。 |
| `WebSocketManager` | 8080 WebSocket 连接管理。通过 `ScheduledThreadPool(2)` 进行 30 秒心跳 + 60 秒系统状态广播。 |
| `LMStudioWebSocketManager` | LM Studio 端口的独立 WS 连接管理器。与 `WebSocketManager` 结构相同的心跳/状态广播。 |
| `ModelSamplingService` | 采样预设管理。两个配置文件：`model-sampling.json`（预设值）+ `model-sampling-settings.json`（模型→预设绑定）。基于 mtime 的缓存。`cmd` 字段的 CLI 参数注入解析。 |
| `ChatTemplateKwargsService` | 每模型 `chat_template_kwargs`（例如 `enable_thinking`、`thinking_budget_tokens`）。存储在 `config/model-chat-template-kwargs.json`。基于 mtime 的缓存。 |
| `McpClientService` | MCP 客户端：注册 SSE/Streamable HTTP 服务器、工具索引、工具调用。两种传输：短寿命 SSE（每次请求握手）和持久 Streamable HTTP（会话管理）。环境变量占位符解析（`${VAR_NAME}`）。120 秒工具调用超时。 |
| `GpuService` | 启动时 GPU 检测快照 + 实时状态查询。通过 OS 命令检测 NVIDIA/AMD/Apple 厂商。 |
| `DownloadTaskManager` | 下载任务管理（**HTTP 路由中的活跃系统**）。`HttpURLConnection` 分块下载，回调驱动进度通知。通过 Java 序列化持久化到 `cache/tasks.cache`。状态机：PENDING→RUNNING→COMPLETED/FAILED/PAUSED。 |
| `DownloadManager` | **遗留下载管理器**，`org.mark.llamacpp.download`。使用 `BasicDownloader`（`java.net.http.HttpClient`），未接入任何 HTTP 路由。状态机：IDLE→PREPARING→DOWNLOADING→MERGING→VERIFYING→COMPLETED。 |
| `LlamaRecordService` | 累积推理性能记录。每模型累积 `Timing` + 在 `cache/record/{modelId}.{json,log,requests.log}` 的逐请求 JSONL 日志。 |
| `ModelRequestTracker` | 活跃推理请求状态机。广播 `model_busy` WebSocket 事件。跟踪 PREFILL→GENERATION 阶段转换。 |
| `AnthropicService` | Anthropic ↔ OpenAI 消息格式转换 + 转发。支持 `nodeId` 远程路由 + 全节点回退。HTTPS 远程节点信任所有 SSL。 |
| `OpenAIService` | OpenAI API 转发到模型子进程。处理 chat/completions/embeddings/rerank/responses/audio/transcriptions。流式响应 SSE 解析 + tool_call_id 修复。通过 `/v1/models` 查询合并远程模型。 |
| `NodeProxyService` | 流式和非流式代理到远程节点。SSE 块转发，包含 `tool_call_id` 修复。 |
| `BenchmarkService` | V2 基准测试：通过二分搜索 + `/tokenize` + `/apply-template` 生成精确 token 计数的提示。运行 `/v1/chat/completions` 并将计时记录到 `benchmarks/{modelId}_V2.jsonl`。 |
| `DownloadService` | **死代码**。`DownloadManager`（遗留）的 REST API 门面，未注册到任何路由。 |
| `ToolExecutionService` | 执行工具调用。路由到 MCP 工具（通过 `McpClientService`）或内置的 `zhipu_web_search`。 |
| `UsageReportService` | 读取 `cache/record/` 文件以生成 token 使用摘要和逐请求日志列表。 |
| `CompletionService` | EasyRP 角色 CRUD + 头像/聊天文件持久化。文件存储在 `cache/charactors/`、`cache/chat/`、`cache/avatars/`。遗留 `cache/completions/` 回退。 |
| `ComputerService` | 静态硬件信息工具：CPU 型号、物理内存、核心数、JVM 指标。由 `BenchmarkService` 使用。 |
| `ZhipuWebSearchService` | 通过智谱 AI API 的内置网页搜索。15 秒连接 / 60 秒读取超时。每次调用重新读取 API 密钥。 |
| `TimeServer` | 内置时间工具：`get_current_time`（时区感知）、`convert_time`（时区转换）。DST 间隙处理。 |
| `SystemMonitorService` | 定时执行 `system_monitor_json.sh` 脚本并通过 WebSocket 广播系统指标。仅 Linux。 |
| `LetsUpdate` | OTA 更新编排：下载 ZIP、解压、备份旧文件、替换文件、回滚。管理 `cache/update.zip`。 |
| `UpdateDownloader` | 低级 HTTP 下载引擎，用于 `LetsUpdate`。8KB 分块下载，500ms 进度广播。 |
| `GitHubTagFetcherNative` | 从 GitHub API 获取最新 release 标签，与本地 `BuildInfo.getTag()` 比较。 |

### 线程模型
- 所有请求处理器：通过 `Executors.newVirtualThreadPerTaskExecutor()` 使用虚拟线程
- 模型加载：单个虚拟线程执行器（`llama-loader-`），顺序执行
- WebSocket 心跳/系统状态：`ScheduledThreadPool(2)`
- 节点健康检查：`ScheduledExecutorService` 守护线程（`node-health-check`）
- 系统监控：`ScheduledThreadPool(1)` 守护线程
- 下载管理器：固定线程池（4 个工作线程）
- 远程 WS 客户端重连：每个节点使用单线程 `ScheduledExecutorService`
- LM Studio WS 心跳/状态：`ScheduledExecutorService`，2 线程

### 模型工作状态机（`ModelRequestTracker`）

跟踪每个模型的活跃推理请求。WebSocket 广播 `model_busy` 事件。

**数据结构：**
- `modelActiveRequests`：`ConcurrentHashMap<String, Set<String>>`（modelId → requestId 集合）
- `allActiveRequests`：`ConcurrentHashMap<String, ActiveRequest>`（requestId → ActiveRequest）

**状态（`RequestStatus`）：** `CREATED` → `PROXYING` → `COMPLETED` / `FAILED`

**阶段（`Phase`，仅内部）：** `PREFILL`（等待 llama.cpp HTTP 200）、`GENERATION`（流式 token）

**核心方法：**
| 方法 | 描述 |
|--------|-------------|
| `createRequest(modelId, endpoint) → requestId` | 创建记录返回 UUID，初始阶段=PREFILL，广播 busy=true |
| `removeRequest(requestId)` | 移除记录。在广播 busy=false 前检查同一模型是否有其他请求。如果 `Timing` 存在则保存到 `LlamaRecordService`。 |
| `updatePhase(requestId, Phase)` | 更新推理阶段（PREFILL→GENERATION） |
| `updateTiming(requestId, Timing)` | 回填计时数据 |
| `isModelBusy(modelId) → boolean` | 查询活跃请求 |
| `getModelAggregatedPhase(modelId) → String` | 如果任何请求处于 GENERATION 阶段则返回 `"generation"`，否则返回 `"prefill"` |
| `getBusyModels() → Set<String>` | 繁忙模型 ID 的不可修改集合 |

**集成点（每个在 `getResponseCode()` == 200 后调用 `updatePhase(GENERATION)`）：**
1. `OpenAIService.forwardRequestToLlamaCpp()` — chat/completions/embeddings/rerank/responses/audio
2. `ChatStreamSession.run()` — 流式 chat
3. `OllamaChatService.handleChat()` — Ollama chat
4. `OllamaEmbedService.handleEmbed()` — Ollama embed

在 `finally` 或 `cancel()` 中调用 `removeRequest()` 清理请求。

**WebSocket 事件：**
```json
{"type":"model_busy","modelId":"Qwen3-0.6B-GGUF","busy":true,"activeCount":2}
```

**持久化：** 带非空 `Timing` 的 `removeRequest()` 调用 `LlamaRecordService.recordRequest()`，追加到 `cache/record/{modelId}.requests.log`（JSONL 格式）。

---

## 模型管理

### 模型发现
- 从 `config/modelpaths.json` + 默认 `models/` 目录扫描路径
- 递归遍历；每个包含 `.gguf` 文件的子目录 = 一个模型
- 过滤掉 `.` 前缀的目录

### GGUF 文件处理（`GGUFBundle.java`）
- 自动检测分割卷：`*-00001-of-*.gguf` 模式
- **性能优化：** 仅从第一个文件读取元数据（跳过所有分割部分）
- 自动关联 mmproj 文件（3 种启发式）：`mmproj-{baseName}.gguf` → `{baseName}-mmproj.gguf` → 任何包含 `mmproj` 的 `.gguf`
- 种子文件选择：优先选择第一个分割卷 → 非 mmproj 文件 → 第一个文件

### GGUF 元数据（`GGUFMetaData.java`）
通过 `BufferedInputStream` + `DataInputStream` 从 GGUF 头部解析的字段：
- `general.architecture`、`general.basename`、`general.name`、`general.size_label`
- `general.file_type`（通过 `fileTypeToQuantizationName()` 映射到 30+ 量化名称）
- `*.context_length`
- `clip.has_audio_encoder`、`clip.has_vision_encoder`

`GGUFMetaDataReader.java` 使用内存映射文件（最大 64MB）获取完整 KV 元数据（用于模型详情 UI）。

### 量化名称映射（`fileTypeToQuantizationName()`）
将 GGUF `file_type` 整数映射到名称：`F32`、`F16`、`Q4_0`、`Q4_1`、`Q8_0`、`Q2_K`…`Q6_K`、`IQ2_XXS`、`IQ3_XXS`、`IQ1_S`、`IQ4_NL`、`IQ3_M`、`IQ2_M`、`IQ4_XS`、`IQ1_M`、`BF16`、`MXFP4`（两个 ID）、`UNKNOWN(N)`。

### MTP 检测（`MtpHelper.java`）
多 Token 预测（MTP）检测、提取和合并工具。支持 `qwen35` 和 `qwen35moe` 架构。可从完整模型提取 MTP 张量到轻量级 donor 文件，或将 donor 合并回完整模型。

### 模型加载（`LlamaServerManager.loadModelAsyncFromCmd()`）
1. 检查是否已加载/正在加载，验证 `modelId`
2. `PortChecker.findNextAvailablePort()`（三重检查，从 8081 开始）
3. `buildCommandStr()` 构造 `llama-server` CLI
4. 创建 `LlamaCppProcess`，设置输出处理器
5. 启动子进程，等待 `"srv  update_slots: all slots are idle"`（最长 10 分钟）
6. 超时 → 停止 → 错误
7. 成功 → 查询 `/slots` 获取 `n_ctx` → 查询 `/v1/models` 缓存模型信息
8. 更新 `loadedProcesses`、`modelPorts`、`loadedModelInfos`

### `buildCommandStr()` 构造的 CLI
```
{exe} -m {modelFile} --port {port} [--mmproj {mmprojFile}] [-sm none --device {device}] [--main-gpu {mg}]
  {cmd} {extraParams} [--chat-template-file {file}]
  --metrics [--slot-save-path {cacheDir}] --cache-ram -1 --alias {modelId} --timeout 36000 --host 0.0.0.0
```
- 自动避免来自 `cmd`/`extraParams` 的重复标志
- 仅当 `enableVision=true` 且文件存在时使用 mmproj
- 单 GPU：`-sm none --device`，多 GPU：`--device` 逗号分隔
- 根据操作系统检测可执行文件名（llama-server / llama-server.exe）

### 子进程管理（`LlamaCppProcess.java`）
- 命令行拆分支持双引号/单引号/反斜杠转义
- Windows：自动将 ROCm `bin/`、`bin/rocblas/`、`bin/hipblaslt/` 添加到 PATH
- Linux：设置 `LD_LIBRARY_PATH` 包含二进制目录和 ROCm 库路径
- 输出读取器使用虚拟线程，过滤 `update_slots` 和 `log_server_r` 行
- 停止：`process.destroy()` → 等待 5 秒 → `destroyForcibly()` → 中断读取器线程

### 模型停止（`stopModel()`）
- `process.stop()` → `Process.destroy()` → 等待 5 秒 → `destroyForcibly()` → 中断读取器线程
- `LlamaCppProcess.stop()` 对已退出的进程有错误处理
- 清理：`loadedProcesses`、`modelPorts`、`loadedModelInfos`、`loadingProcesses`、`loadingTasks`、`loadingModels`、`canceledLoadingModels`

### 能力检测（`resolveModelType()`）
从文件名、架构、chat_template 自动检测。缓存到 `config/capabilities/{modelId}.json`：
- `rerank` — 文件名包含 rerank/re-rank/reranker/ranker/cross-encoder
- `embedding` — 文件名包含 embedding/e5/gte/jina/nomic/mxbai/bge/arctic-embed，或架构为 bert/roberta
- `tools` — chat_template 包含 tool_call/tools/function/mcp
- `thinking` — chat_template 包含 enable_thinking/thinking
- `vision` — mmproj `clip.has_vision_encoder=true`
- `audio` — mmproj `clip.has_audio_encoder=true`
- rerank/embedding 互斥；tools/thinking 与 rerank/embedding 互斥

### 端口分配（`PortChecker`）
三重检查：
1. `ServerSocket.bind()` — 快速预留
2. `Socket.connect("localhost", port)` — 检查监听器
3. `netstat -ano`（Windows）/ `ss -tuln`（Linux）— OS 级别

---

## 完整 API 端点文档

### OpenAI 兼容 API（`LlamaRouterHandler` → `OpenAIService`）
| 端点 | 方法 | 描述 | 备注 |
|------|------|------|------|
| `/v1/models` | GET | 列出已加载模型（附带能力标志） | 根据请求头自动检测 Anthropic 客户端 |
| `/v1/chat/completions` | POST | 聊天补全（流式/非流式） | 标准 OpenAI 格式 + 可选 `nodeId` |
| `/v1/completions` | POST | 文本补全 | `{model, prompt, stream, max_tokens, ...}` |
| `/v1/embeddings` | POST | 文本嵌入 | `{model, input, ...}` |
| `/v1/responses` | POST | Responses API | `{model, input, ...}` |
| `/v1/rerank` | POST | 重排序 | `{model, query, documents, ...}` |
| `/v1/audio/transcriptions` | POST | 音频转录（multipart） | `model, file, language` |

**API 密钥验证：** 读取 `Authorization: Bearer {key}`（OpenAI）或 `x-api-key: {key}`（Anthropic）。两者使用 `application.json` 中相同的密钥。仅当 `security.apiKeyEnabled=true` 时应用。仅适用于 `/v1/*` 路径。

**流式聊天流程（`OpenAIChatStreamingHandler` → `ChatStreamSession`）：**

1. `OpenAIChatStreamingHandler` 在 `HttpObjectAggregator` **之前**拦截
2. 创建 `ChatStreamSession`，使用 `BoundedQueueInputStream` 处理分块请求体
3. `ChatRequestStreamingTransformer` **流式解析** JSON — 提取 `model` + `nodeId`，将 `messages` 直接路由到输出（避免 base64 解码图片）
4. 三次注入：`applyThinkingInjection()`（思考参数）、`applyChatTemplateKwargsInjection()`（模型 kwargs）、`applySamplingInjection()`（采样预设）
5. `nodeId` 在转发到子进程前从输出中剥离；`TransformResult` 携带 `modelName` + `nodeId`
6. 路由：`nodeId` → 直接远程节点；否则本地 → 全远程节点回退
7. `DeferredConnectionOutputStream`：在内存中缓冲（最大 1MB），然后溢出到临时文件；连接建立后重放
8. 通过 SSE 响应客户端

### Anthropic 兼容 API（`LlamaRouterHandler` → `AnthropicService`）
| 端点 | 方法 | 描述 |
|------|------|------|
| `/v1/models` | GET | 列出模型（通过 `anthropic-version`/`x-api-key` 自动选择 Anthropic 与 OpenAI 格式） |
| `/v1/messages` | POST | 聊天消息（转换 Anthropic↔OpenAI，支持 `nodeId` + 全回退） |
| `/v1/messages/count_tokens` | POST | Token 计数 |
| `/v1/complete` | POST | 遗留文本补全 |

**格式转换：**
- 请求：`convertAnthropicToOai()` — system/messages/tools/tool_choice/stop_sequences/max_tokens/thinking（支持 `thinking.type:"disabled"`）
- 响应：`convertOaiResponseToAnthropic()` — reasoning_content→thinking、content→text、tool_calls→tool_use
- 流式：`convertOaiStreamChunkToAnthropicSse()` — 状态机管理 content_block_start/delta/stop 事件

### Ollama 兼容 API（端口 11434，独立 Netty 服务器）
| 端点 | 方法 | 描述 | 实现 |
|------|------|------|------|
| `/api/tags` | GET | 列出所有模型 | `OllamaTagsService.handleModelList()` |
| `/api/ps` | GET | 列出已加载模型 | `OllamaTagsService.handleLoadedModel()` |
| `/api/show` | GET/POST | 模型详情 + 元数据 + 张量 + 能力 | `OllamaShowService` |
| `/api/chat` | POST | 聊天（流式 + 工具，支持 `nodeId`） | `OllamaChatService` |
| `/api/embed` | POST | 文本嵌入 | `OllamaEmbedService` |
| `/v1/*` | * | OpenAI 兼容端点 | `OpenAIService` |
| `/` | GET | "Ollama is running" | 纯文本 |
| `/api/copy`/`/api/delete`/`/api/pull`/`/api/push`/`/api/generate` | * | 返回 404 | 明确不支持 |

**Ollama 聊天转换管道：**
1. `OllamaApiTool.normalizeOllamaMessagesForOpenAI()` — 转换消息（images→image_url、tool_calls→OpenAI 格式、function_call→tool_calls）
2. `applyOllamaOptionsToOpenAI()` — 映射 `options`：temperature/top_p/top_k/repeat_penalty/frequency_penalty/presence_penalty/seed/num_predict/stop
3. `applyOllamaToolsToOpenAI()` — 复制 tools/tool_choice
4. `ChatTemplateKwargsService` + `ModelSamplingService` 注入
5. 从 Ollama `think` 字段提取 `enable_thinking`
6. 响应：提取 reasoning_content→thinking，通过 `OllamaApiTool.extractToolCallsFromOpenAIMessage()` → `toOllamaToolCalls()` 提取 tool_calls，通过 `buildOllamaTimingFields()` 构建计时信息

**远程路由（3 层）：**
1. 请求体中的 `nodeId` → 直接路由（剥离 nodeId）
2. 通过 `LlamaServerManager.getLoadedProcesses()` 的本地模型
3. 通过 `resolveFromRemoteNodes()` 的全节点回退 — 迭代已启用的节点，查询 `/v1/models`

### LM Studio 兼容 API（端口 1234，独立 Netty 服务器）
| 端点 | 方法 | 描述 |
|------|------|------|
| `/api/v0/models` | GET | 列出已加载模型（量化、架构、能力） |
| `/api/v0/models/{modelId}` | GET | 单个模型详情 |
| `/api/v0/chat/completions` | POST | 聊天补全（流式 + stats/model_info/runtime 字段） |
| `/api/v0/completions` | POST | 文本补全 |
| `/api/v0/embeddings` | POST | 文本嵌入 |
| `/v1/*` | * | OpenAI 兼容端点 |
| `/health` | GET | 健康检查 |
| `/echo` | ALL | 回声测试 |

**WebSocket RPC（`LMStudioWebSocketHandler`）：** 路径 `/llm` 和 `/system`
- `connect` — 连接确认
- `listDownloadedModels` — 所有 GGUF 模型，包含架构/量化信息
- `listLoaded` — 已加载的 LLM（过滤掉嵌入模型），附带持久化 `instanceReference`
- `getModelInfo` — 通过 `instanceReference` 获取详情

**LM Studio 响应增强：** `buildLmStudioCompletion()` 添加 `stats`（tokens_per_second、time_to_first_token、generation_time、stop_reason）、`model_info`（arch、quant、format、context_length）、`runtime` 字段。支持 `@` 后缀模型名映射（如 `model@Q4_K_M`）。

### 内部 API（`/api/*`）

#### 模型管理
| 端点 | 方法 | 描述 |
|------|------|------|
| `/api/models/list` | GET | 所有模型（本地 + 远程），可选 `?nodeId=` |
| `/api/models/loaded` | GET | 已加载模型：id/name/status/port/pid/size/path/node/busy |
| `/api/models/refresh` | GET | 强制刷新本地 + 所有远程节点 |
| `/api/models/load` | POST | 加载模型。请求体：`{modelId, cmd, extraParams, enableVision, llamaBinPathSelect, device, mg, nodeId}` |
| `/api/models/stop` | POST | 停止模型。请求体：`{modelId, nodeId}` |
| `/api/models/favourite` | POST | 切换收藏。请求体：`{modelId}` |
| `/api/models/alias/set` | POST | 设置别名。请求体：`{modelId, alias, nodeId}` |
| `/api/models/openai/list` | GET | OpenAI 格式模型列表（合并 `models` + `data` 数组） |
| `/api/models/details` | GET | 模型详情。查询参数：`modelId`、`nodeId` |
| `/api/models/record` | GET | 推理性能记录。查询参数：`modelId` |
| `/api/models/metrics` | GET | 代理到子进程 `/metrics`。查询参数：`modelId` |
| `/api/models/props` | GET | 代理到子进程 `/props`。查询参数：`modelId` |

#### 模型配置
| 端点 | 方法 | 描述 |
|------|------|------|
| `/api/models/config/get` | GET | 启动配置包。查询参数：`modelId`、`nodeId` |
| `/api/models/config/set` | POST | 保存启动配置。请求体：`{modelId, configName, setSelected, config:{...}, nodeId}`。也支持批量格式。 |
| `/api/models/config/delete` | POST | 删除配置。请求体：`{modelId, configName, nodeId}` |

#### 模型能力
| 端点 | 方法 | 描述 |
|------|------|------|
| `/api/models/capabilities/get` | GET | 获取能力。查询参数：`modelId`、`nodeId` |
| `/api/models/capabilities/set` | POST | 设置能力。请求体：`{modelId, capabilities:{tools,thinking,...}, nodeId}` |

#### 聊天模板
| 端点 | 方法 | 描述 |
|------|------|------|
| `/api/model/template/get` | GET | 获取自定义聊天模板。查询参数：`modelId` |
| `/api/model/template/set` | POST | 设置自定义模板。请求体：`{modelId, chatTemplate}` |
| `/api/model/template/delete` | POST | 删除自定义模板。请求体：`{modelId}` |
| `/api/model/template/default` | GET | 从 GGUF 读取默认模板。查询参数：`modelId` |

#### 聊天模板 Kwargs
| 端点 | 方法 | 描述 |
|------|------|------|
| `/api/model/chat_template_kwargs/get` | GET | 获取 kwargs。查询参数：`modelId` |
| `/api/model/chat_template_kwargs/set` | POST | 设置 kwargs。请求体：`{modelId, chat_template_kwargs:{...}}` |
| `/api/model/chat_template_kwargs/delete` | POST | 删除 kwargs。请求体：`{modelId}` |

#### KV 缓存槽
| 端点 | 方法 | 描述 |
|------|------|------|
| `/api/models/slots/get` | GET | 获取槽信息。查询参数：`modelId` |
| `/api/models/slots/save` | POST | 保存槽。请求体：`{modelId, slotId, fileName}` |
| `/api/models/slots/load` | POST | 加载槽。请求体：`{modelId, slotId, fileName}` |

#### 模型路径管理
| 端点 | 方法 | 描述 |
|------|------|------|
| `/api/model/path/list` | GET | 列出搜索目录 |
| `/api/model/path/add` | POST | 添加目录。验证存在性，无符号链接 |
| `/api/model/path/update` | POST | 更新目录。请求体：`{originalPath, path, name, description}` |
| `/api/model/path/remove` | POST | 移除目录。请求体：`{path}` |

#### llama.cpp 路径管理
| 端点 | 方法 | 描述 |
|------|------|------|
| `/api/llamacpp/list` | GET | 列出所有 llama.cpp 目录（已保存 + 自动扫描） |
| `/api/llamacpp/add` | POST | 添加目录。验证包含 `llama-server` |
| `/api/llamacpp/remove` | POST | 移除目录。请求体：`{path}` |
| `/api/llamacpp/test` | POST | 测试目录：运行 `llama-cli --version` + `--list-devices` |

#### 下载管理
| 端点 | 方法 | 描述 |
|------|------|------|
| `/api/downloads/list` | GET | 列出所有任务 |
| `/api/downloads/create` | POST | 创建下载。请求体：`{url, path, fileName}` |
| `/api/downloads/model/create` | POST | 创建模型下载。请求体：`{author, modelId, downloadUrl[], path, name}` |
| `/api/downloads/pause` | POST | 暂停任务。请求体：`{taskId}`。保存恢复信息 |
| `/api/downloads/resume` | POST | 恢复任务。请求体：`{taskId}`。验证 ETag/大小 |
| `/api/downloads/delete` | POST | 删除任务。请求体：`{taskId, deleteFile}` |
| `/api/downloads/stats` | GET | 下载统计（活跃/待处理/完成/失败/总计） |
| `/api/downloads/path/get` | GET | 获取下载目录 |
| `/api/downloads/path/set` | POST | 设置下载目录。请求体：`{path}` |

#### MCP 工具管理
| 端点 | 方法 | 描述 |
|------|------|------|
| `/api/mcp/tools` | GET | 所有已注册的 MCP 服务器和工具 |
| `/api/mcp/add` | POST | 添加 MCP 服务器。请求体：`{mcpServers:{...}}` |
| `/api/mcp/remove` | POST | 移除 MCP 服务器。请求体：`{url/mcpServerUrl}` |
| `/api/mcp/rename` | POST | 重命名 MCP 服务器。请求体：`{url/mcpServerUrl, name}` |
| `/api/tools/execute` | POST | 执行工具。请求体：`{tool_name, arguments, [mcpServerUrl]}` |

#### HuggingFace 搜索
| 端点 | 方法 | 描述 |
|------|------|------|
| `/api/hf/search` | GET | 搜索 HF 模型。查询参数：`query`（必填）、`limit`（30）、`timeoutSeconds`（20）、`startPage`、`maxPages`、`base`（镜像） |
| `/api/hf/gguf` | GET | 获取 GGUF 文件。查询参数：`model`/`repoId`/`input`（必填）、`timeoutSeconds`、`base` |

#### Easy-Chat 状态同步
| 端点 | 方法 | 描述 |
|------|------|------|
| `/api/easy-chat/state` | GET | 加载聊天状态（修订版、对话、文件路径） |
| `/api/easy-chat/state/revision` | GET | 获取当前修订版字符串 |
| `/api/easy-chat/conversation` | GET | 加载对话。查询参数：`id` |
| `/api/easy-chat/sync` | POST | 同步状态（乐观并发控制）。请求体：`{state, currentConversation, baseRevision}` |

#### EasyRP 角色管理（`CompletionRouterHandler`）
| 端点 | 方法 | 描述 |
|------|------|------|
| `/api/chat/completion/list` | GET | 列出所有角色（精简，按 updatedAt 降序） |
| `/api/chat/completion/create` | POST | 创建角色。请求体：`{title}` |
| `/api/chat/completion/get` | GET | 获取角色。查询参数：`name`（数字 ID） |
| `/api/chat/completion/save` | POST | 保存角色。查询参数：`name`。请求体：完整 `CharactorDataStruct` JSON |
| `/api/chat/completion/delete` | DELETE | 删除角色。查询参数：`name` |
| `/api/chat/completion/file/upload` | POST | 上传聊天文件（multipart，最大 16MB） |
| `/api/chat/completion/file/download` | GET | 下载聊天文件。查询参数：`name` |
| `/api/chat/completion/avatar/upload` | POST | 上传头像（multipart，最大 1MB）。查询参数：`name` |
| `/api/chat/completion/avatar/get` | GET | 获取头像（内联）。查询参数：`name` |

#### 用量报告
| 端点 | 方法 | 描述 |
|------|------|------|
| `/api/report/token-summary` | GET | 按模型的 Token 用量摘要 |
| `/api/report/request-logs` | GET | 逐请求推理日志 |

#### 系统设置
| 端点 | 方法 | 描述 |
|------|------|------|
| `/api/sys/setting` | GET | 所有系统设置 |
| `/api/sys/setting` | POST | 保存系统设置 |
| `/api/sys/version` | GET | 构建版本信息（tag/version/createdTime） |
| `/api/sys/compat/status` | GET | 兼容服务状态（Ollama/LMStudio/MCP/日志） |
| `/api/sys/ollama` | POST | 启用/禁用 Ollama。请求体：`{enable, port}` |
| `/api/sys/lmstudio` | POST | 启用/禁用 LM Studio。请求体：`{enable, port}` |
| `/api/sys/mcp` | POST | 启用/禁用 MCP 服务器。请求体：`{enable}` |
| `/api/sys/gpu/info` | GET | GPU 初始化快照 |
| `/api/sys/gpu/status` | GET | 实时 GPU 状态（温度/利用率/内存/功耗/风扇） |
| `/api/sys/console` | GET | 控制台日志缓冲区文本 |
| `/api/sys/fs/list` | GET | 文件系统浏览器。查询参数：`path`。最多 500 个目录 + 10 个文件。阻止符号链接。 |
| `/api/sys/update/check` | GET | 检查 GitHub 更新 |
| `/api/sys/update/download` | POST | 下载更新包（仅 GitHub Release URL） |
| `/api/sys/update/apply` | POST | 应用下载的更新 |
| `/api/sys/update/status` | GET | 查询更新状态 |
| `/api/sys/update/cancel` | POST | 取消下载 |
| `/api/shutdown` | POST | 优雅关闭（停止所有模型 → NodeManager.shutdown() → System.exit(0)） |

#### 采样参数
| 端点 | 方法 | 描述 |
|------|------|------|
| `/api/sys/model/sampling/setting/list` | GET | 列出所有采样预设 |
| `/api/sys/model/sampling/setting/get` | GET | 获取模型的采样绑定。查询参数：`modelId` |
| `/api/sys/model/sampling/setting/set` | POST | 绑定采样到模型。请求体：`{modelId, configName}` |
| `/api/sys/model/sampling/setting/add` | POST | 添加/更新预设。请求体：`{configName, temperature, top_p, ...}` |
| `/api/sys/model/sampling/setting/delete` | POST | 删除预设。请求体：`{configName}` |

#### 搜索设置
| 端点 | 方法 | 描述 |
|------|------|------|
| `/api/search/setting` | POST | 保存智谱搜索 API 密钥。请求体：`{zhipu_search_apikey}` |

#### 设备 / VRAM
| 端点 | 方法 | 描述 |
|------|------|------|
| `/api/model/device/list` | GET | 列出 GPU 设备。查询参数：`llamaBinPath`、`nodeId` |
| `/api/models/vram/estimate` | POST | VRAM 估算。请求体：`{modelId, cmd, device, mg, llamaBinPath, ...}` |

#### 参数列表
| 端点 | 方法 | 描述 |
|------|------|------|
| `/api/models/param/server/list` | GET | 从 classpath 资源 `server-params.json` 获取服务器参数定义 |
| `/api/models/param/benchmark/list` | GET | 从 classpath 资源 `benchmark-params.json` 获取基准测试参数 |

#### 节点管理
| 端点 | 方法 | 描述 |
|------|------|------|
| `/api/node/list` | GET | 列出所有远程节点 |
| `/api/node/add` | POST | 添加节点。请求体：`{nodeId, name, baseUrl, apiKey, tags, enabled}`。仅主节点。 |
| `/api/node/remove` | POST | 移除节点。请求体：`{nodeId}`。仅主节点。 |
| `/api/node/update` | POST | 更新节点。请求体：`{nodeId, name, baseUrl, apiKey, tags, enabled}`。仅主节点。 |
| `/api/node/test` | POST | 测试连通性。请求体：`{nodeId}`。测量延迟。 |
| `/api/node/status` | GET | 所有节点状态（status/lastHeartbeat/enabled） |
| `/api/node/info` | GET | Hub 节点信息（本地 + 已连接节点） |

#### 基准测试
| 端点 | 方法 | 描述 |
|------|------|------|
| `/api/models/benchmark` | POST | V1 基准测试（运行 `llama-bench`）。请求体：`{modelId, cmd, llamaBinPath, [nodeId]}` |
| `/api/models/benchmark/list` | GET | 列出 V1 结果文件。查询参数：`modelId, [nodeId]` |
| `/api/models/benchmark/get` | GET | 获取 V1 结果。查询参数：`fileName, [nodeId]` |
| `/api/models/benchmark/delete` | POST | 删除 V1 结果。查询参数：`fileName, [nodeId]` |
| `/api/v2/models/benchmark` | POST | V2 基准测试（通过 BenchmarkService）。请求体：`{modelId, promptTokens, maxTokens, [nodeId]}` |
| `/api/v2/models/benchmark/get` | GET | 获取 V2 记录。查询参数：`modelId, [nodeId]` |
| `/api/v2/models/benchmark/delete` | POST | 删除 V2 记录。请求体：`{modelId, lineNumber, [nodeId]}` |

#### 代理端点
| 端点 | 方法 | 描述 |
|------|------|------|
| `/tokenize` | POST | 代理到子进程 `/tokenize`。请求体：`{content, add_special, parse_special, with_pieces, modelId}` |
| `/apply-template` | POST | 代理到子进程 `/apply-template`。请求体：`{messages, modelId}`。返回 `{prompt}` |
| `/infill` | POST | 代理到子进程 `/infill`。透明转发所有请求头 |

---

## WebSocket（端口 8080，路径 `/ws`）

**客户端（`js/websocket.js`）：** 自动连接，1 秒重连间隔。连接时发送 `{"type":"connect"}`，等待 `{"type":"connect_ack"}`。

**事件（服务器→客户端）：**
| 事件 | 描述 | 负载字段 |
|------|-------|---------|
| `modelLoadStart` | 模型加载开始 | `modelId, port, message` |
| `modelLoad` | 模型加载成功/失败 | `modelId, success, message, port` |
| `modelStop` | 模型停止 | `modelId, success, message` |
| `model_status` | 模型状态更新 | — |
| `model_slots` | 槽状态 | `modelId, slots: [{id, speculative, is_processing}]` |
| `console` | 控制台日志（base64） | `modelId, line64, nodeId?, timestamp` |
| `notification` | 通用通知 | — |
| `download_update` | 下载状态变更 | `taskId, state, ...` |
| `download_progress` | 下载进度 | `taskId, bytes, speed, ...` |
| `model_busy` | 模型繁忙状态 | `modelId, busy, activeCount` |
| `app_update` | 应用更新进度 | `status, downloadedBytes, totalBytes, progressRatio, version, errorMessage` |
| `systemMonitor` | 系统指标（仅 Linux） | `cpu, memory, gpu, load, processes, network` |

**心跳：** 服务器每 30 秒 ping，每 60 秒广播系统状态（Linux：`system_monitor_json.sh` 脚本）。

**控制台缓冲区：** 最大 2MB，UTF-8 安全截断。从 `logs/app.log` 尾部预加载。

**远程节点日志中继：** `RemoteWebSocketClient` 连接到每个远程节点的 `/ws`。过滤 `heartbeat`/`connect_ack`/`welcome`。注入 `nodeId` 字段。控制台事件将 `line` 转换为 `line64`（base64）。远程日志不写入本地 CONSOLE_BUFFER（避免快照重复）。

**LM Studio WebSocket（端口 1234，路径 `/llm` 和 `/system`）：** `LMStudioWebSocketManager` 管理连接。30 秒心跳、60 秒系统状态、RPC 调用（listDownloadedModels/listLoaded/getModelInfo）。

---

## MCP 服务器（端口 8075）

### 内置 MCP 服务器（`DefaultMcpServiceImpl`）
自定义 Streamable HTTP MCP 服务器实现。JSON-RPC 2.0，协议版本 `2024-11-05`。

**支持的 JSON-RPC 方法：** `initialize`、`ping`、`notifications/ping`、`notifications/initialized`、`prompts/list`、`resources/list`、`resources/templates/list`、`tools/list`、`tools/call`

**Netty 管道：** `HttpServerCodec` → `HttpObjectAggregator`（2MB）→ `ChunkedWriteHandler` → `McpRouterHandler`

**路由：** 所有路径在 `/mcp/{serviceKey}` 下：
| 模式 | 方法 | 处理器 |
|------|------|--------|
| `/mcp/{serviceKey}` | GET | Streamable HTTP SSE 连接 |
| `/mcp/{serviceKey}` | POST | Streamable HTTP 请求 |
| `/mcp/{serviceKey}` | DELETE | Streamable HTTP 会话删除 |
| `/mcp/{serviceKey}/sse` | GET | 遗留 SSE 连接 |
| `/mcp/{serviceKey}/message` | POST | 遗留 SSE 消息 |

**按 serviceKey 的内置工具：**
| serviceKey | 工具 |
|------------|------|
| `llama_hub_info` | `GetModelsTool`、`GetModelPathTool`、`GetLlamaCppInfoTool`、`GetParamInfoTool`、`GetMcpServiceInfoTool`、`ExperienceLogTool`、`ExperienceListTool`、`ExperienceGetTool`、`ExperienceMatchTool`、`GetTimeTool` |
| `llama_hub_image` | `ReadStaticImageTool`（最大 2MB，base64） |
| `llama_hub_context` | `ContextSummaryTool`（持久化到 `cache/context-summary/`） |
| `llama_hub_file` | `WriteTextFileTool`（路径遍历保护） |

**会话管理：** `McpSession` 支持两种传输。Streamable HTTP 使用 `MCP-Session-Id` 头标识会话。遗留 SSE 使用 URL 路径中的 sessionId。

**经验知识库：** `FileExperienceRepository` 存储到 `{user.dir}/experience/`（JSON，`exp_{seq}.json`）。`ExperienceMatcher` 按任务类型（+5）+ 上下文术语命中（各 +1.5）+ 历史权重评分匹配。

### MCP 客户端（`McpClientService`）
管理已注册的外部 MCP 服务器。

**配置：** `config/mcp-tools.json`

**传输：**
- `sse` — 服务器发送事件（短寿命：每次请求握手，1 秒读取超时）
- `streamable-http` — Streamable HTTP（会话管理，POST→sessionId→重用）

**注册流程：** `initialize()` 握手 → `tools/list` → 构建 `toolToUrl` 索引。工具去重：`filterNewTools()` 移除服务器内重复和跨服务器名称冲突。

**工具调用：** `callTool(name, args)` → 查找 URL → SSE：短寿命握手（initialize→initialized→tools/call→读取 SSE 响应）。Streamable HTTP：会话初始化 → tools/call → 响应 → 会话关闭。

### 内置实用工具
- `get_current_time` — 时区感知的当前时间
- `convert_time` — 时区转换（DST 间隙处理）
- `builtin_web_search` — 智谱 AI 搜索（需要在 `config/zhipu_search.json` 中配置 API 密钥）

---

## 配置系统

所有配置在 `config/` 中（`.gitignore`）。首次启动自动创建 `application.json` + `models/` + `llamacpp/`。

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
所有字段可通过 `POST /api/sys/setting` 动态修改（立即写入磁盘）。

**nodeRole：** `"master"` 或 `null`（默认，视为从节点）。
- 主节点：连接到所有从节点的 WebSocket，运行健康检查，管理 WS 客户端生命周期
- 从节点：`NodeManager.listEnabledNodes()` 返回空 → 无远程操作
- 检查：`LlamaServer.isMasterNode()` — `nodeRole != null && "master".equalsIgnoreCase(nodeRole)`

### `config/modelpaths.json`
```json
{ "items": [{ "path": "D:\\Models", "name": "我的模型", "description": "" }] }
```
模型搜索目录。每个包含 `.gguf` 的子目录 = 一个模型。

### `config/models.json`
```json
[{ "modelId": "Qwen3-0.6B-GGUF", "alias": "轻量小模型", "favourite": true }]
```
已发现的模型元数据：别名、收藏标志。

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
多配置支持。旧的扁平格式在读取时自动升级。空配置自动补充 "默认配置"。

### `config/model-sampling.json` + `config/model-sampling-settings.json`
预设：`{"presetName": {"temperature": 0.7, ...}}`。设置：`{"modelId": "presetName"}`。
支持的参数：seed、temperature、samplers、top_p、min_p、top_n_sigma、repeat_penalty、top_k、presence_penalty、frequency_penalty、dry_*、force_enable_thinking、enable_thinking。

### `config/llamacpp.json`
```json
{ "items": [{ "name": "win-vulkan", "path": "llamacpp/win-vulkan", "description": "" }] }
```
llama.cpp 二进制目录。默认自动扫描 `llamacpp/`。

### `config/nodes.json`
```json
{ "nodes": [{ "nodeId": "server2", "name": "远程服务器", "baseUrl": "https://10.8.0.6:8080", "apiKey": "...", "enabled": true }] }
```
包装在包含 `List<LlamaHubNode>` 的 `NodesConfigData` POJO 中。

### `config/mcp-tools.json`
MCP 客户端注册的外部工具服务器。格式：`{ "mcpServers": { "serverName": { "baseUrl": "...", "transportType": "sse|streamable-http", "headers": {...} } } }`。

### `config/capabilities/{modelId}.json`
```json
{ "modelId": "...", "tools": true, "thinking": true, "rerank": false, "embedding": false, "vision": false, "audio": false }
```
每模型能力标志，自动生成于发现时。

### `config/zhipu_search.json`
```json
{ "apiKey": "..." }
```

### `config/model-chat-template-kwargs.json`
```json
{ "modelId": { "enable_thinking": true, "thinking_budget_tokens": 1024 } }
```

### 原子文件写入
`ConfigManager.writeJsonFileAtomic()`：写入 `.tmp` → `ATOMIC_MOVE` → 重命名。非原子文件系统回退到普通移动。`ChatTemplateFileTool`、`FileContextSummaryRepository`、`FileExperienceRepository` 等都使用此模式。

---

## 日志

**配置：** `src/main/resources/log4j2.xml`
- 输出到 `logs/app.log`，每日滚动（`app-{yyyy-MM-dd}.log.gz`），7 天保留
- 格式：`%d{yyyy-MM-dd HH:mm:ss.SSS} - %msg%n`
- 级别：INFO

**辅助日志记录器：** `STDOUT`（System.out 捕获）、`STDERR`（System.err 捕获）、`LLAMA_CPP_RAW`（子进程输出，过滤 `update_slots` 和 `log_server_r`）、`CONSOLE_BUFFER`（控制台缓冲区日志记录器）。

**调试日志：** `application.json` → `logging.logRequestUrl/Header/Body` = `true`。路由器根据这些标志在请求处理前记录 URI、所有头信息和请求体。

**ConsoleBufferLogAppender：** 启动时安装的自定义 Log4j2 appender。捕获除 STDOUT/STDERR/LLAMA_CPP_RAW 之外的所有日志事件，将每行转发到 WebSocket 控制台。使用 PatternLayout `%d{yyyy-MM-dd HH:mm:ss.SSS} - %msg%n`。

**ConsoleBroadcastOutputStream：** 捕获 `System.out`/`System.err`，缓冲直到换行，将每个完整行广播到 WebSocket 控制台和日志记录器。

**子进程日志过滤：** `LlamaCppProcess.startOutputReaders()` 在输出处理器中过滤包含 `update_slots` 和 `log_server_r` 的行，避免控制台污染。

---

## SSL/HTTPS

**配置：** `application.json.https`
```json
{ "enabled": true, "keystorePath": "ssl/keystore.p12", "keystorePassword": "changeit" }
```

**支持的存储类型：** PKCS12（`.p12`/`.pfx`）、JKS（`.jks`/`.keystore`）

**自动扫描：** 如果 `keystorePath` 是目录，则查找证书文件（优先选择 `.p12`）。

**详细指南：** `ssl/HTTPS_SETUP.md`（中英文双语）

**注意：** `ssl/` 在 `.gitignore` 中，HTTPS 需要手动设置。

---

## 下载系统

有两个并行的下载包，`org.mark.file.downloader` 是当前 HTTP 路由中的**活跃系统**，`org.mark.llamacpp.download` 是**遗留系统**（未接入路由）。

### 活跃系统（`org.mark.file.downloader`）

#### 架构
`FileDownloadRouterHandler` → `DownloadTaskManager` → `SimpleHttpDownloader`（`HttpURLConnection` 分块下载）。

#### 核心类
| 类 | 职责 |
|-----|----------|
| `FileDownloadRouterHandler` | Netty 管道中 `BasicRouterHandler` 链的第 3 个控制器，路由 `/api/downloads/*` |
| `DownloadTaskManager` | 任务生命周期管理、持久化（`ObjectOutputStream` → `cache/tasks.cache`）、WebSocket 通知 |
| `SimpleHttpDownloader` | 实际 HTTP 下载：先 `probe()` 发 HEAD 请求获取 Content-Length，再分块 Range 并行下载 |
| `DownloadTaskInfo` | 数据模型。`totalBytes` 初始化为 `-1L`，probe 完成后更新为真实值 |
| `DownloadTaskStatus` | 枚举：`PENDING`→`RUNNING`→`COMPLETED`/`FAILED`/`PAUSED` |
| `DownloadWebSocketListener` | 回调监听器，通过 `WebSocketManager` 广播状态/进度事件 |

#### 特性
- HTTP/HTTPS 下载
- `probe()` 通过 HEAD 请求获取文件大小（`Content-Length` 或 `Content-Range`）
- 并行分块下载（`HttpURLConnection` + `Range` 头）
- 重试：`probe()` 默认 3 次重试
- 最大并发：4（`DownloadTaskManager` 的固定线程池）
- 进度：回调驱动（`DownloadProgressListener`），非轮询
- 持久化：Java 原生序列化（`ObjectOutputStream`）写入 `cache/tasks.cache`
- 暂停/恢复：中断线程 + `.downloading.meta` 文件记录 Range 状态

#### 状态机
```
PENDING → RUNNING → COMPLETED
               ↘  ↗   ↘
              PAUSED    FAILED
```

#### WebSocket 事件
| 事件 | 触发时机 | JS 处理器 |
|------|---------|-----------|
| `download_update` | 创建/暂停/完成/失败 | `updateDownloadItem()`→`renderDownloadsList()` |
| `download_progress` | 每次进度回调 | `updateDownloadProgress()`（直接操作 DOM） |

### 遗留系统（`org.mark.llamacpp.download`）

`DownloadManager`（单例）→ `BasicDownloader`（`java.net.http.HttpClient`，8MB 分块，虚拟线程 `PartDownloadTask`）。通过 `TaskRepository`（Gson JSON）持久化到 `cache/tasks.json`。1 秒 `ScheduledExecutorService` 轮询进度。**未接入任何 HTTP 路由，属于死代码。**

状态机：`IDLE → PREPARING → DOWNLOADING → MERGING → VERIFYING → COMPLETED`

### 文件命名（两个系统共用）
- 下载中：`{fileName}.downloading`
- 完成后：重命名为 `{fileName}`
- 从 URL 自动提取文件名

---

## 更新系统（`org.mark.llamacpp.update`）

### 架构
`SystemController` → `LetsUpdate`（编排）→ `UpdateDownloader`（下载引擎）→ `GitHubTagFetcherNative`（版本检测）

### 完整 OTA 更新流程
1. **检查更新：** `GET /api/sys/update/check` → `GitHubTagFetcherNative.check()` → 调用 GitHub Releases API → 解析 `ReleasesStruct` → 与 `BuildInfo.getTag()` 比较 → 返回 `CheckResult`
2. **下载：** `POST /api/sys/update/download` → `LetsUpdate.download()` → 验证 URL 仅限 GitHub Release → `UpdateDownloader.downloadAsync()` → 8KB 分块写入 `cache/update.zip` → 每 500ms WebSocket 广播 `app_update` 事件
3. **查询状态：** `GET /api/sys/update/status` → `LetsUpdate.getStatus()` 合并自身状态 + `UpdateDownloader` 状态
4. **应用更新：** `POST /api/sys/update/apply` → `LetsUpdate.doUpdate()` → 解压 ZIP（路径遍历防护）→ 备份 `classes/`→`cache/old-classes/` → 移动新文件 → 失败自动回滚 `ROLLBACK`
5. **取消下载：** `POST /api/sys/update/cancel` → 删除 `cache/update.zip` → 重置为 `IDLE`

### 更新 API 端点

| 端点 | 方法 | 描述 | 请求体 | 响应 |
|------|------|------|--------|------|
| `/api/sys/update/check` | GET | 检查 GitHub 是否有新版本 | 无 | `{success, release:{tag_name, name, body, html_url, published_at, prerelease, assets}, hasUpdate, error?}` |
| `/api/sys/update/download` | POST | 下载更新包 | `{tagName, [proxyUrl]?}` | `{success, status, version, error?}` |
| `/api/sys/update/apply` | POST | 应用已下载的更新 | 无 | `{success, error?}` |
| `/api/sys/update/status` | GET | 查询更新状态 | 无 | `{status, downloadedBytes, totalBytes, progressRatio, version, error, ...}` |
| `/api/sys/update/cancel` | POST | 取消下载 | 无 | `{success}` |

### 状态机
```
IDLE → DOWNLOADING → READY → APPLYING → IDLE
  ↓        ↓           ↓         ↓
FAILED    FAILED      IDLE     ROLLBACK → IDLE
              (cancel)  (cancel)
```

### WebSocket 事件（`app_update`）
```json
{"type":"app_update","status":"downloading","downloadedBytes":524288,"totalBytes":1048576,"progressRatio":0.5,"version":"v0.9.0"}
{"type":"app_update","status":"completed","downloadedBytes":1048576,"totalBytes":1048576,"progressRatio":1.0,"version":"v0.9.0"}
{"type":"app_update","status":"failed","errorMessage":"下载失败: 连接超时"}
```

### 核心类
| 类 | 职责 |
|-----|----------|
| `GitHubTagFetcherNative` | 调用 GitHub API `/repos/.../releases/latest`，解析 `ReleasesStruct`，与 `BuildInfo.getTag()` 对比。缓存到 `cache/latest.json`。 |
| `ReleasesStruct` | Gson POJO：`tagName`, `name`, `htmlUrl`, `publishedAt`, `body`, `prerelease`, `assets[]`（含 `name`, `size`, `browserDownloadUrl`） |
| `LetsUpdate` | 更新编排。验证下载 URL（强制 GitHub Release）、ZIP 解压（路径遍历防护）、文件备份/回滚。管理 `UpdateStatus` 状态机。 |
| `UpdateDownloader` | 低级 HTTP 下载。`HttpURLConnection` 8KB 分块、500ms WebSocket 进度广播。仅从 `IDLE/READY/FAILED` 开始新下载。`cancel()` 中断下载线程。 |

---

## 前端

**技术：** 纯 vanilla JS（无框架、无打包器、无 `package.json`）。通过 `<script src>` 直接引入 JS。

**第三方：** Font Awesome（`css/all.min.css`）、Highlight.js（`js/highlight.min.js`）、Marked（`js/marked.umd.js`）、MathJax（动态 `tex-svg.js`）、lz-string（`js/lz-string.js`）、Google Fonts Inter（`css/css2.css`）。

### 页面

- **`index.html`** — 桌面 SPA（12 个视图：模型列表、聊天、基准测试 V3、下载、HF 搜索、控制台、llama.cpp 路径、模型路径、用量报告、设置、设备状态）。侧边栏导航 + 主面板切换。
- **`index-mobile.html`** — 移动端 SPA（5 个底部导航视图：模型列表、HF 搜索、下载、设置 + 子页面）。全屏加载模型弹窗。
- **`chat/index.html`** — 完整聊天 UI（约 5700 行，含 4000+ 行内联 JS）。OpenAI `/v1/chat/completions`。会话管理、多助手、多模态、音频录制、流式传输、Markdown/LaTeX（MathJax）、工具调用、MCP 集成、性能统计。
- **`easyRP/easy-chat.html`** — EasyRP 角色扮演聊天（桌面版）。5 个 `completion-*.js` 模块。角色系统、会话/主题、KV 缓存槽、API 模型切换。
- **`easyRP/easy-chat-mobile.html`** — EasyRP 移动版。主题覆盖层、底部设置面板。
- **`easyRP/concurrency.html`** — 并发压力测试页面（N 路并行 API 请求，SSE 流式，聚合统计）。
- **`tools/benchmark-v2.html`** — V2 基准测试面板（llama-server 实时测试 + 记录管理 + 硬件配置文件）。
- **`tools/benchmark-v3.html`** — V3 基准测试布局壳（两个标签页：llama-server 实时测试 + llama-bench CLI）。嵌入 `index.html` 作为 `main-benchmark-v3`。
- **`tools/mcp-manager.html`** — MCP 服务器管理 UI（列出/添加/删除/重命名服务器和工具，`<dialog>` API）。
- **`audio-transcription.html`** — 音频转录（上传 + 浏览器录音）。支持 WAV/MP3/M4A/OGG/FLAC/WEBM/OPUS，25MB 限制。

### 关键 JS 文件
| 文件 | 行数 | 用途 |
|------|------|------|
| `js/websocket.js` | 198 | WS 客户端，事件分发到各 UI 模块 |
| `js/i18n.js` | 127 | 国际化：语言检测、翻译加载、DOM 自动翻译 |
| `js/model-icon.js` | 29 | 架构名 → PNG 图标路径映射（deepseek/qwen/llama 等） |
| `js/model-list.js` | 556 | 模型列表渲染、搜索、排序、收藏、节点过滤 |
| `js/model-detail.js` | 1312 | 模型详情弹窗（6 标签页：概览/采样/模板/Token 计算器/Kwargs/槽） |
| `js/model-action-modal.js` | 1633 | 加载/基准测试/停止弹窗 + 动态参数表单系统 |
| `js/model-benchmark.js` | 709 | V1 基准测试弹窗（llama-bench 执行 + 结果浏览） |
| `js/benchmark-v3.js` | 1450 | V3 基准测试面板（IIFE 隔离，2 标签页，节点过滤，图表） |
| `js/settings.js` | 1049 | 桌面设置（8 标签页：服务器/兼容/安全/HTTPS/日志/下载/节点/更新） |
| `js/settings-mobile.js` | 208 | 移动端设置（IIFE） |
| `js/download.js` | 729 | 桌面下载管理器（IIFE，WebSocket 驱动，EMA 速度平滑） |
| `js/download-mobile.js` | 533 | 移动端下载管理器（IIFE） |
| `js/hf.js` | 651 | HuggingFace 搜索 + GGUF 文件树 + 下载（桌面） |
| `js/hf-mobile.js` | 463 | HuggingFace 搜索（移动端，IIFE） |
| `js/console-modal.js` | 187 | 控制台弹窗（本地 + 远程节点标签页，rAF 批处理） |
| `js/console-modal-mobile.js` | 100 | 移动端控制台弹窗（简化版） |
| `js/usage-report.js` | 313 | 用量报告（柱状图 + 分页请求日志，IIFE） |
| `js/index-mobile-nav.js` | 80 | 移动端底部导航控制器（IIFE） |
| `js/llamacpp-setting-mobile.js` | 349 | 移动端 llama.cpp 路径管理（IIFE） |
| `js/model-path-setting-mobile.js` | 246 | 移动端模型路径管理（IIFE） |
| `js/model-llamacpp-setting.js` | — | 桌面端 llama.cpp 路径管理 |
| `js/completion-dom-state.js` | 290 | EasyRP DOM 引用 + 全局状态（els/state） |
| `js/completion-init.js` | 517 | EasyRP 事件绑定和初始化 |
| `js/completion-net-tools.js` | 465 | EasyRP 网络工具（fetch/MCP/附件/备份） |
| `js/completion-runtime.js` | 1646 | EasyRP 核心运行时代码（消息构建/SSE/工具调用/保存加载） |
| `js/completion-ui-render.js` | 1297 | EasyRP UI 渲染（Markdown/消息气泡/版本/头像/HTML 预览） |
| `chat/audio-recorder.js` | 272 | 音频录制（ScriptProcessorNode + WAV 编码） |
| `chat/chat-mcp.js` | 1065 | 聊天 MCP 管理模块（服务器添加/工具切换/工具执行） |

### 动态参数表单系统（`server-params.json` + `model-action-modal.js`）

`server-params.json` 中的每个参数定义：`name`（i18n 键）、`fullName`（CLI 标志）、`abbreviation`、`type`（STRING/INTEGER/FLOAT/LOGIC/BOOLEAN）、`defaultValue`、`values`（选项）、`uiType`（`ordered-multiselect`）、`delimiter`、`defaultEnabled`、`sort`、`group`、`groupOrder`、`groupCollapsed`。

**管道：** `renderDynamicParams()` ← `applyCmdToDynamicFields()` → `buildLoadModelPayload()`

**裸 token 参数**（无标志名，值本身就是标志）：fullName 和 abbreviation 均为空，type=STRING 带有 values 列表。特殊处理：`buildLoadModelPayload()` 将值作为裸 token 直接输出；`sanitizeExtraParamTokens()` 通过 `buildAllowedBareTokenSetFromParamConfig()` 进行白名单检查。

**`server-params.json` 有 50 个参数，分 7 组：** 基础(6)、采样(10)、采样器链(1)、性能(14)、特性(3)、模板(4)、多模态(1)、推测解码(12)。

**`benchmark-params.json` 有 31 个参数，分 5 组：** 基础(8)、测试数据(5)、性能(10)、缓存存储(3)、高级(7)。

### Benchmark DOM ID 约定
所有基准测试表单 ID 使用 `bench_` 前缀（例如 `param_bench_threads`），以避免与加载模型弹窗的 `getElementById` 冲突。`benchmark-v3.js` 使用 IIFE 隔离以防止函数名与 `model-action-modal.js` 冲突。

### i18n
- 检测：`?lang=` → `localStorage` → `navigator.language` → `zh-CN` 回退
- 翻译文件：`i18n/en-US.json`、`i18n/zh-CN.json`（各 ~640 键）
- DOM 自动翻译：`data-i18n`（文本）、`data-i18n-attr`（属性）
- 每个 JS 文件定义 `t(key, fallback)` 速记

### 全局状态
大量使用 `window.*`：`window.I18N`、`window.paramConfig`、`window.benchmarkParamConfig`、`window.currentModelsData`、`window.remoteNodes`、`window.__modelDetail*`、`window.__loadModel*`、`window.__capabilities*`、`window.__modelActionMode`、`window.__benchmarkModelId`、`window.showModal`、`window.showToast`。

### WebSocket 耦合
`websocket.js` 直接调用 `model-list.js` 的内部函数：`window.currentModelsData`、`sortAndRenderModels()`、`updateModelSlotsDom()`、`showModelLoadingState()`。

### UI 模式
- **IIFE 隔离：** settings.js, download.js, benchmark-v3.js, console-modal.js, hf-mobile.js, settings-mobile.js, download-mobile.js, llamacpp-setting-mobile.js, model-path-setting-mobile.js, chat-mcp.js
- **rAF 批处理：** 控制台日志刷新、Markdown 渲染、timing 显示
- **防抖自动保存：** 采样设置 400ms、能力设置 350ms、聊天状态 450ms
- **乐观 UI：** 收藏切换立即更新 UI，API 失败时回滚

---

## CI/CD（GitHub Actions）

### `.github/workflows/build-and-release.yml`
触发：`v*.*.*` 标签。步骤：`mvn package` → 下载 mini JRE（Liberica NIK）+ llama.cpp 预构建二进制（CUDA 12/13、Vulkan、HIP、ROCm 7.2）→ 打包多后端包 → 创建 GitHub Release。

### `.github/workflows/docker-image.yml`
触发：每次推送。构建 `build-vulkan`（标签：`vulkan`、`latest`、`sha-*`、版本）和 `build-rocm`（标签：`rocm`、`sha-*`、版本）。推送到 `ghcr.io/[owner]/llamasp`。

### Dockerfile
多阶段：`builder`（eclipse-temurin:21-jdk-jammy）→ `runtime-base`（eclipse-temurin:21-jre-jammy）→ `runtime-vulkan`（+ Vulkan 驱动）→ `runtime-rocm`（rocm/dev-ubuntu-24.04:7.0-complete）。

---

## Git 配置

### `.gitignore`
```
/target/  /config/  /logs/  /releases/  /.settings/  /benchmarks/
/.project  /.classpath  /cache/  /downloads/  /models/
/llama.cpp/  /build/  /llamacpp/  /ssl/
```

**重要：** `config/` 不提交。首次启动自动创建 `application.json`，但其他配置文件需要手动设置。

---

## Windows 系统托盘

`WindowsTray.java` 使用 JDK `SystemTray` API。图标从 `/icon/icon.png` 加载。功能：
- 双击打开主页（`defaultAction`）
- 右键弹出菜单（"Open home page"、"Exit"）
- DPI 感知弹出定位
- 通知气泡消息

---

## 贡献者注意事项

1. **pom.xml 没有依赖** — 通过替换 `lib/` 中的 JAR 和更新 `.classpath` 来更改依赖。**永远不要碰 pom.xml。**
2. **模型作为 OS 子进程运行** — 每个模型 = 独立端口的独立 `llama-server.exe`。`LlamaCppProcess` 管理其生命周期。
3. **`BuildInfo.java` 占位符** — CI 替换 `{tag}`/`{version}`/`{createdTime}`。本地开发显示原始值或 `"v0.8.2"`。
4. **无测试框架** — `src/test/` 下的文件是诊断工具，非单元测试。需要手动验证。
5. **`config/` 被 gitignore** — 首次启动仅创建 `application.json`。其他配置文件不会自动创建。
6. **`modelpaths.json` 是可选的** — 即使没有它，`models/` 目录也会被自动扫描。
7. **`llamacpp.json` 是可选的** — `llamacpp/` 目录会被自动扫描。
8. **请求使用虚拟线程**（`Executors.newVirtualThreadPerTaskExecutor()`），但模型加载使用单个虚拟线程执行器（顺序执行）。
9. **OpenAI API 通过 `HttpURLConnection` 转发** — 不是直接 Netty 代理。每次请求到 `localhost:{modelPort}` 建立新的 HTTP 连接。
10. **前端无打包器** — 所有 JS 文件通过 `<script>` 加载，无模块系统。
11. **到处都是全局变量** — 大量使用 `window.*`。文件间耦合通过全局变量和直接函数调用实现。
12. **WebSocket 耦合** — `websocket.js` 直接调用 `model-list.js` 内部函数；变更需要协调。
13. **Benchmark 与加载弹窗 DOM ID 冲突** — 所有基准测试表单 ID 使用 `bench_` 前缀。新的基准测试参数必须遵循此约定。
14. **benchmark-v3.js 使用 IIFE** — 内部函数（`fieldNameFromParamConfig`、`renderParamField`）不能污染全局作用域，否则会覆盖 `model-action-modal.js` 中的对应函数并破坏 `ordered-multiselect` 控件。
15. **`AnthropicRouterHandler` 是死代码** — `bindAnthropic()` 从未被调用。Anthropic 路由已合并到 8080 的 `LlamaRouterHandler` 中。
16. **`LlamaCppService` 和 `SessionService` 是 `@Deprecated`** — 在生产流程中未使用。
17. **`OllamaService.java` 完全被注释掉** — 已被 `OllamaChatService`/`OllamaShowService`/`OllamaTagsService` 取代。
18. **`LlamaCommandParser.java` 完全被注释掉** — 已被 `ChatStreamSession` + `OpenAIService` 取代。
19. **`LlamaCliProcessor.java` 是 `@Deprecated`** — 旧的交互式 CLI 包装器。
20. **`VramEstimator` 是 `@Deprecated`** — 包含了独立的 GGUF 解析器（`LeReader`），与 `GGUFMetaDataReader` 重复。已被 `CommandLineRunner` + `llama-cli` 方法取代。
21. **存在两个并行的下载包** — `org.mark.file.downloader`（**活跃**，使用 `HttpURLConnection`、`SimpleHttpDownloader`、`DownloadTaskManager`，通过 `FileDownloadRouterHandler` 接入 HTTP 路由）和 `org.mark.llamacpp.download`（**遗留**，使用 `java.net.http.HttpClient`、`BasicDownloader`、`DownloadManager`，未接入任何路由）。
22. **GGUF 解析器有重复实现** — `VramEstimator` 中的 `LeReader` 和 `GGUFMetaDataReader` 是两个独立的 GGUF 解析器。
23. **`GitBranchHelper.java` 是空桩** — 无实现。
24. **`HuggingFaceModelCrawler.java` 支持镜像切换** — 支持 `hf-mirror.com` 和官方 HuggingFace，通过环境变量 `HF_BASE_URL` 或 `base` 参数控制。
25. **`OutputHelper.java` 是构建工具** — 将 classpath 打包成 release ZIP 并生成启动脚本，不参与运行时。

---

## 远程节点代理机制

### REST API 代理

`proxyGetRemote()` / `proxyPostRemote()` 转发请求到远程节点。**自动移除 `nodeId` 参数**（GET：从查询字符串中剥离；POST：从 JSON 体中移除）以防止代理循环。

**其他直接代理调用也处理 `nodeId` 移除：**
- `ModelActionController.loadRemoteModel()` — `body.remove("nodeId")`
- `SystemController.handleVramEstimateRemote()` — `body.remove("nodeId")`
- `ModelActionController.stopRemoteModel()` — 不带 `nodeId` 的新请求体
- 基准测试 V1/V2 运行和 V2 删除 — 使用 `callRemoteApiTracked(ctx, ...)`（将 HTTP 连接注册到 `ChannelHandlerContext` 中以在客户端断开时清理）
- GET 代理请求没有请求体，通过 URL 路径转发 — 无循环风险

**聊天 API 远程路由：** `/v1/chat/completions` / `/v1/completions` / `/api/chat`（Ollama）请求体中的 `nodeId` 字段 → `ChatStreamSession`（流式）或 `OpenAIService`（非流式）检测 `nodeId` 并通过 `resolveRemoteModelUrl()` 直接路由。`ChatRequestStreamingTransformer` 在转发前剥离 `nodeId`。

**Anthropic `/v1/messages` 远程路由：** 通过 `AnthropicService.routeMessagesToNode()` 的 `nodeId` 直接路由，加上通过 `resolveModelOnRemoteNodes()` 的全节点回退。

### WebSocket 事件中继（后端中继）

`RemoteWebSocketClient.java` 作为 WS 客户端连接到每个远程节点的 `/ws`，将事件中继到本地 `WebSocketManager` 供前端消费。

**连接：** URL 转换（`http://`→`ws://`，`https://`→`wss://`），信任所有 SSL，10 秒连接超时。

**重连：** 指数退避 1s→2s→4s→...→30s 最大。`scheduleReconnect()` 捕获 `RejectedExecutionException`。

**中继逻辑（`relayMessage()`）：**
- 过滤：`heartbeat`、`connect_ack`、`welcome`（不转发）
- 将所有事件注入 `nodeId` 字段
- 控制台事件：将 `line`→`line64`（base64），移除原始 `line`
- 通过 `WebSocketManager.getInstance().broadcast()` 广播

**生命周期：** `NodeManager.addNode()` → 启动 WS 客户端；`removeNode()` → 停止；`updateNode()` → 在 URL/状态变更时重启。`onNodeStatusChanged()` → OFFLINE→ONLINE：启动；ONLINE→OFFLINE：停止。

### 前端模型列表变更（`model-list.js` / `websocket.js` / `index.html`）
- `applyModelPatch(modelId, patch, nodeId)` — 使用复合键 `id::nodeId`
- 所有事件处理器传递 `data.nodeId`
- 远程控制台行显示 `[nodeId/modelId]` 前缀
- 复合键函数 `modelCompositeKey(id, nodeId)` — `id::nodeId`
- 节点过滤下拉框（`#modelNodeFilter`）— 全部/本地/远程 + 空状态消息
