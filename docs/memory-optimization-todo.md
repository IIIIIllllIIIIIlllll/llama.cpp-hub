# 内存优化待办清单

> 目标：将 JVM 堆压缩到 **64MB** 运行（启动配置由用户自行处理，不在开发环境内）。
> 本文档用于跨会话交接，记录了已完成的工作、待办事项及验证方法。
> 最近更新：2026-07-21

## 背景

项目为 Netty + Java 21 的 llama.cpp 管理端。原始分析报告（第一轮会话）识别的五大问题：

1. ~~聚合上限 16MB 与 JVM 配额错配（最现实 OOM 路径）~~ → 已做路由级限额，剩余收尾见待办 1~3
2. ~~静态 Map 缓慢泄漏（4 处 + 下载器重试风暴）~~ → 已全部修复
3. ~~GGUF 元数据 64MB 内存映射堆外泄漏~~ → 已改流式
4. ~~Netty 架构（EventLoopGroup 分散、死代码）~~ → 已共享线程组
5. JVM 启动参数 → 用户自行处理，不碰

另：Ollama / LMStudio 兼容层已整体删除（包外触点已同步清理）。

## 运行基线

- gc.log：稳定后堆占用 ~9MB，空闲状态 64MB 宽裕，风险全在**瞬时尖刺**。
- 64MB 下的最坏路径（待办 1~3 做完前）：2MB 聚合 × 并发 + 每请求若干份堆拷贝。
- 建议配套参数（用户处理配置时参考）：`-Xms64m -Xmx64m -XX:MaxDirectMemorySize=32m~48m`，
  保留 `-Dio.netty.allocator.numDirectArenas=2 -Dio.netty.allocator.numHeapArenas=2`。
  2MB 级数组在 64MB 堆下走 G1 humongous 分配，属预期行为。

## 已完成（勿重复）

| 项 | 关键文件 | 说明 |
|---|---|---|
| 路由级请求体限额 + 413 | `server/channel/HttpContentLimitHandler.java`（新增） | 默认 2MB；白名单：`/v1/audio/transcriptions`、`/api/chat/sync`（16MB）、`/api/chat/background/upload`（4MB）。接在 `HttpHttpsUnificationHandler.addCommonHttpHandlers` 聚合器前 |
| 共享 EventLoopGroup | `server/NettySharedGroups.java`（新增） | boss 1 + worker 4，daemon 线程；shutdown hook 统一 `shutdownAll()` |
| GGUF 流式读取 | `gguf/GgufReader.java`（新增）、`GGUFMetaDataReader`、`MtpHelper` | mmap + 反射 unmap 已移除 |
| 静态 Map 修复 | `EasyChatService`（64 分片锁）、`WebSocketServerHandler/Manager`、`BuildTaskManager`、`LlamaRecordService` | 见下两行 |
| BuildTask 输出零拷贝 | `BuildTaskManager`、`BuildController`、前端 `llama-build.html` | 输出存 `<id>.log` 文件，`GET /api/build/output?taskId=` 用 ChunkedFile 下发；`/api/build/status` **不再含 output 字段**（API 契约变化！）；旧 JSON 自动迁移 |
| logMap 句柄空闲关闭 | `LlamaRecordService` | LogEntry（lastUsed/closed），10min 空闲关闭、写入透明重开，5min 清扫 |
| 下载失败退避 | `DownloadTaskManager` | 30s→2min→10min，最多 3 次；手动/成功/删除时重置 |
| 兼容层删除 | ollama/、lmstudio/ 包（16 文件） | LlamaServer、SystemController、前端、i18n 已同步清理 |
| SystemMonitorService 删除 | `server/SystemMonitorService.java`（单文件，无 Java 调用方） | 死代码：仅自引用，`docs/API.md` 的 `systemMonitor` WS 消息类型文档同步删除；前端、脚本（system_monitor_json.sh）均无引用 |
| LlamaRecordService 优雅关闭 | `LlamaRecordService.shutdown()`（新增）、`LlamaServer` shutdown hook | sweeper 提为字段并在退出时 shutdown + 关闭全部日志句柄（顺带消除 IDE 的 resource-leak 误报） |
| content().toString 拷贝消除（待办1） | `JsonUtil`（新增 `readRequestBytes`/`parseRequestBody`/`fromJson(byte[],…)`/公开 `isBlank(byte[])`）、18 个调用方文件 | 公共入口 `parseFullHttpRequestToJsonObject` 改字节流解析；全项目 65 处已消除 57 处。**有意保留 7 处**：`BasicRouterHandler:165`（logRequestBody，属待办4）、`:522`、`LlamaRouterHandler:324`、`AnthropicService:215`、`LlamacppController:933`（infill）均因字符串要转发上游；`DefaultMcpServiceImpl:142`（日志需要）；`SystemController:1875`（注释死代码）。已知微差：纯空白 body 在部分端点由 BODY_EMPTY 变为解析/参数错误（仍 400）；`proxyPostRemote` 非法 JSON 仍走 REMOTE_CALL_FAILED（已验证）。冒烟 13 组 curl 回归通过 |
| 非流式代理响应流式下发（待办3） | `OpenAIService.streamRawProxyResponse`（新增，替代 `readConnectionBodyBytes`/`recordRawProxyStats`/`writeRawProxyResponse` 三方法）、`LlamaRecordService.handleStreamTail`（新增） | 响应边收边 chunked 下发（每 8KB 一块），只保留尾部 16KB 环形缓冲提取 timings/usage；单响应从 3 份全量拷贝降到 ~16KB+单块。覆盖 `/v1/completions`、`/v1/responses`、`/v1/embeddings`、`/v1/rerank`、音频转录远程代理。**行为变化：非流式代理响应由 Content-Length 改为 Transfer-Encoding: chunked**（标准 HTTP，客户端无感）。冒烟：256KB 响应与上游逐字节一致、500 错误透传、timings/usage 统计落盘数值精确（见 `cache/temp/FakeUpstream.java` 假上游，可复用） |
| 小项打包（待办4，全部） | 见下 | ① `BasicRouterHandler` logRequestBody 截断 8KB（带"已截断"标记，冒烟实测 9034B→8268 字符）；② `ReadStaticImageTool` 改流式 base64（省 2MB 原始 byte[]）；③ 子进程输出尾部保留：新增 `util/TailCharBuffer`（容量 64KB，线程安全），替换 `PerplexityService`、`CommandLineRunner`（stdout/stderr）、`ModelActionController` 基准输出三处无界 StringBuilder——**注意：困惑度/基准结果文件与响应里的 rawOutput 不再是完整输出，只有尾部 64KB**（结果行都在末尾，解析不受影响）；④ `StaticFileGzipCache` fileLocks 改固定 64 分片锁；⑤ `updateTokenSummary` 改 entry 内 synchronized，50 并发冒烟 recordCount 50/50 精确 |

## 待办（建议顺序）

### 1. ~~`content().toString()` 整串拷贝消除~~（已完成，见上表）

- 后续可选：4 处转发场景（`BasicRouterHandler:522`、`LlamaRouterHandler:324`、`AnthropicService:215`、`LlamacppController:933`）如需消除 String 拷贝，要把转发方法签名从 `String content` 改为 `byte[]`。

### 2. 大 body 白名单端点收尾

- `/v1/audio/transcriptions` 流式化：当前 16MB 聚合 + `OpenAIService.java:1296` byte[] 复制 + HttpURLConnection 内部全量缓冲 ≈ 3×body。改为聚合器前拦截落盘（参考 `EasyChatStreamingHandler`），转发用 `setFixedLengthStreamingMode` + 8KB 缓冲。注意 `resolveAudioTranscriptionModel`（`OpenAIService.java:1358`）需从临时文件头部解析 multipart model 字段（通常在前几 KB）。
- `/api/chat/sync`（16MB 白名单）：本来就整体写盘（`ChatStateController.java:311`），降限额或流式化。64MB 下 16MB 聚合 + 拷贝是致命路径。
- `/api/chat/background/upload`（4MB）可保留。

### 3. ~~非流式代理响应全量缓冲~~（已完成，见上表）

- 注意：`/v1/chat/completions` 走的是 `OpenAIChatStreamingHandler`/`ChatStreamSession`，不在本次范围内；如需类似优化需单独评估。

### 4. ~~小项打包~~（已完成，见上表）

### 5. 可选

- Netty 4.1.35 → 新版 4.1.x（换 `lib/` 下 jar，API 兼容；开发期可开 `-Dio.netty.leakDetectionLevel=paranoid`）。
- 功能 bug（非内存）：`ComputerService.execAndRead` 不消费 stderr 可能管道死锁（:111）。
- `ProxyFrontendHandler.java:37` `bufferedChunks` 无界（独立代理工具，主程序不启用）。

## 工作环境备忘（新会话必读）

- **JDK 21 路径**：`C:\Program Files\jdk-21.0.8\bin`（系统默认 javac 是 1.8，不能用）。
- **编译验证**（不污染 `build/`）：
  ```bash
  find src/main/java -name "*.java" > /tmp/src.txt && mkdir -p /tmp/ck && \
  "/c/Program Files/jdk-21.0.8/bin/javac" --release 21 -encoding UTF-8 -d /tmp/ck -cp "lib/*" @/tmp/src.txt
  ```
- **冒烟方法**：`cache/temp/smoke-run/` 复制 `config/` 改端口（webPort 18888、mcpServer 可关），以该目录为 cwd 启动
  `java -Xms96m -Xmx96m -cp "<编译输出>;<项目>/lib/*" org.mark.llamacpp.server.LlamaServer`，
  验证后 `taskkill //F //PID <pid>` 并删除 smoke 目录。**不要动正在运行的正式实例**（先 netstat 查 8888/8075）。
- **Write 工具的 /tmp 陷阱**：Write 写 `/tmp/x` 会落到 `C:\tmp\x`，而 Git Bash 的 `/tmp` 是 `%LOCALAPPDATA%\Temp`——两边不一致，引用时注意。
- **冒烟看不到应用日志**：编译输出只含 class、不含资源，log4j2 回退默认配置（只输出 ERROR 到控制台）。需要应用日志时把 `src/main/resources/log4j2.xml` 拷进编译输出目录再启动，日志即写到 smoke cwd 的 `logs/app.log`。
- 用户的正式实例可能正在运行（8888/8075/1234），任何冒烟都用副本配置 + 独立端口。
- 所有改动均未提交 git；`config/application.json` 是实时配置，勿直接改。
- 语言：与用户的交流用中文；代码注释跟随项目现有中文风格。
