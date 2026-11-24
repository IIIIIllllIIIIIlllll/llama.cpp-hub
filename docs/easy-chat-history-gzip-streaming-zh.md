# Easy Chat 会话历史 gzip + chunked 流式响应设计

## 1. 文档状态

- 状态：研究结论与实施设计
- 目标接口：`GET /api/chat/stream-chat?conversationId=...`
- 不在范围内：`POST /api/chat/stream-chat` 的 SSE/非 SSE 聊天响应、错误响应、其他 API、静态资源、图片及文件下载
- 当前阶段：仅形成设计文档，尚未实施代码修改

## 2. 已确认的目标边界

本任务要压缩的是一次成功的会话历史响应所代表的**完整 JSON 内容**，包括：

- JSON 外壳；
- `message`、`totalSize`、`recordCount`、`variantCount`、`nextSeq` 等元数据；
- 从会话碎片 `.bin` 文件中读取的各消息和各变体 payload；
- JSON 数组、对象之间的分隔符及结尾。

完整 JSON 在服务端按顺序送入同一条 gzip 数据流，再以 HTTP chunked 方式增量写给客户端。目标响应形态为：

```http
HTTP/1.1 200 OK
Content-Type: application/json; charset=UTF-8
Content-Encoding: gzip
Transfer-Encoding: chunked
Vary: Accept-Encoding
Cache-Control: no-store
```

这里不存在“JSON 元数据明文、只有 payload 压缩”的混合协议，也不引入 multipart 或自定义二进制帧。浏览器按标准 HTTP 语义自动解 gzip，前端继续调用 `response.json()`。

压缩必须严格限制在上述成功的 GET 会话历史响应中。即使客户端声明支持 gzip，以下响应也不得设置 `Content-Encoding: gzip`：

- 同路径 POST 请求产生的 SSE；
- 同路径 POST 请求产生的非 SSE JSON；
- 会话历史参数错误、锁冲突、读取失败等错误响应；
- 其他任意 API 或资源响应。

## 3. 当前实现

### 3.1 路由分流

`EasyChatController.handleRequest(...)` 使用同一路径承载两种业务：

- GET 调用 `handleStreamChatHistory(...)`，读取并返回持久化会话历史；
- 其他方法进入 `handleStreamChatRequest(...)`，其中 POST 可返回 SSE。

因此，压缩条件不能只根据 URI 判断，必须至少同时检查请求方法和历史响应类型。

### 3.2 会话碎片格式

会话历史位于：

```text
cache/easy-chat/fragments/<conversationId>/<16位seq>.bin
```

单个消息碎片文件由两部分组成：

1. 固定 160 字节的二进制头；
2. 一个或多个连续存储的 JSON payload。

二进制头记录 seq、变体数量、当前变体和每个 payload 的长度。历史响应不会把这个二进制头发送给客户端；服务端通过头部计算每个 payload 在文件中的 offset 和 length，只发送 payload 切片。

### 3.3 现有历史响应链路

`EasyChatService.handleStreamChatHistory(...)` 当前执行：

1. 获取 Easy Chat 全局锁和会话锁；
2. 预扫描碎片头，计算 `totalSize`、记录数、变体数和 `nextSeq`；
3. 写入历史 JSON 前缀；
4. 为每条消息写入消息元数据；
5. 通过 `FragmentSlice` 和 `ChunkedFile` 从 `.bin` 中输出 payload；
6. 写入消息、数组和根对象的 JSON 后缀；
7. 写入 `LastHttpContent` 并关闭连接。

当前成功响应已经使用：

```http
Content-Type: application/json; charset=UTF-8
Transfer-Encoding: chunked
```

所以现状已经是“磁盘切片流式读取 + HTTP chunked”，但内容仍为 identity 编码，没有 gzip。

### 3.4 前端读取方式

Easy Chat 前端通过 `fetch()` 请求历史接口，成功后直接执行：

```javascript
const historyJson = await historyResp.json();
```

标准浏览器会在 Fetch 层自动处理 `Content-Encoding: gzip`，解压后的字节再交给 JSON 解析。因此采用标准 HTTP gzip 后不需要修改会话数据结构或前端 JSON 解析流程。

### 3.5 SSE 链路

POST 流式聊天响应使用：

```http
Content-Type: text/event-stream; charset=UTF-8
Cache-Control: no-cache
```

SSE 的及时送达依赖持续写入和刷新。它必须保持 identity 编码，不能因为客户端请求头带有 `Accept-Encoding: gzip` 而进入历史压缩流程。

## 4. 推荐实现

### 4.1 压缩协商

Controller 在 GET 历史分支读取 `Accept-Encoding`，将“是否允许 gzip”显式传给历史服务方法，或者直接把必要的请求上下文传入该方法。

gzip 只在以下条件全部满足时启用：

```text
请求方法为 GET
且路径命中 /api/chat/stream-chat
且即将返回成功的会话历史 representation
且 Accept-Encoding 允许 gzip
```

协商逻辑必须正确处理质量参数：

- `gzip`：允许；
- `gzip;q=1`：允许；
- `gzip;q=0`：禁止；
- `identity`：不启用 gzip；
- 未携带 `Accept-Encoding`：不启用 gzip；
- `*`：按 HTTP 协商规则决定是否允许 gzip。

不能仅使用 `acceptEncoding.contains("gzip")`，否则会错误压缩 `gzip;q=0` 请求。

成功历史响应无论最终选择 gzip 还是 identity，都应携带：

```http
Vary: Accept-Encoding
Cache-Control: no-store
```

gzip 响应不得设置 `Content-Length`，必须保留 `Transfer-Encoding: chunked`。

### 4.2 单一连续 gzip 数据流

整个历史 JSON 必须使用一个 gzip stream：

```text
gzip header
  JSON 根对象前缀
  消息 0 元数据
  消息 0 / 变体 0 payload
  消息 0 / 其他变体 payload
  ...
  JSON 根对象后缀
gzip trailer
```

不能为每个消息、每个 `.bin` payload 或每个 HTTP chunk 单独创建 gzip stream。HTTP chunk 只是压缩后字节流的传输分块，不是 gzip 成员边界。

### 4.3 推荐的数据通路

建议新增一个仅服务于会话历史的 `NettyChunkedOutputStream`，其职责是：

1. 接收 `GZIPOutputStream` 产生的压缩字节；
2. 在固定大小缓冲区达到阈值时，写出一个 `DefaultHttpContent`；
3. 等待对应 ChannelFuture 完成，形成背压；
4. `close()` 时只收尾输出缓冲，不关闭 Netty Channel；
5. 客户端断开或写入失败时立即抛出异常，停止磁盘读取和 gzip 计算。

推荐缓冲区大小为 16 KB 至 32 KB。现有碎片读取缓冲为 8 KB，可以继续复用 `EasyChatStorage.streamSlice(...)`，让原始 payload 直接从文件切片流入 gzip，而不进入完整 byte array。

示意代码：

```java
sendHistoryHeaders(ctx, true);

try (NettyChunkedOutputStream nettyOut =
         new NettyChunkedOutputStream(ctx, 32 * 1024);
     GZIPOutputStream gzip =
         new GZIPOutputStream(nettyOut, 32 * 1024)) {

    gzip.write(prefixBytes);

    for (HistoryMessage message : messages) {
        gzip.write(messagePrefixBytes);

        for (FragmentSlice slice : message.variantSlices()) {
            gzip.write(variantPrefixBytes);
            storage.streamSlice(slice, gzip);
            gzip.write(variantSuffixBytes);
        }

        gzip.write(messageSuffixBytes);
    }

    gzip.write(dataSuffixBytes);
    gzip.finish();
}

ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
```

以上是结构示意，不要求在实现时先构造完整的 `HistoryMessage` 集合。为了维持低内存，可以继续逐条扫描并立即写出。

### 4.4 线程与背压

`BasicRouterHandler` 当前把请求处理派发到虚拟线程。gzip 压缩和磁盘切片读取应继续在这个虚拟线程中执行，不应把大量压缩计算放到 Netty EventLoop。

每个压缩输出块写入后应通过项目现有的阻塞写入辅助逻辑或等价机制等待完成。这样可以限制待发送 ByteBuf 数量，避免客户端接收缓慢时在堆外内存中累计完整响应。

不建议通过大量细粒度 `writeAndFlush()` 输出 JSON 标点。应由 gzip 和底层输出缓冲聚合为较大的压缩块，再 flush 到 Channel。

### 4.5 identity 回退

客户端不允许 gzip 时，继续使用现有 identity + chunked 路径。两条路径必须生成语义一致的 JSON：

- 字段和值一致；
- 消息顺序一致；
- 变体顺序一致；
- `nextSeq` 一致；
- `.bin` payload 字节一致。

后续实施时，宜把“生成逻辑 JSON 字节流”抽成一个共享写入过程，分别连接 identity 输出和 gzip 输出，避免两套循环逐渐产生行为差异。

## 5. 严格隔离策略

### 5.1 不使用全局 HttpContentCompressor

公共 Netty Pipeline 同时承载：

- Easy Chat 历史；
- Easy Chat SSE；
- OpenAI 流式接口；
- 普通 JSON API；
- 静态资源；
- 文件上传与下载；
- WebSocket。

在公共 Pipeline 中加入全局 `HttpContentCompressor` 会扩大压缩适用面，并使“其他任何数据都不压缩”依赖复杂的排除条件。这里应采用历史方法内部的 endpoint-local gzip 输出，不改变公共 Pipeline。

### 5.2 POST 和 SSE 不读取历史压缩开关

压缩协商只发生在 Controller 的 GET 历史分支。POST 分支不应接收或保存这个布尔值，也不应动态修改 Channel Pipeline。

需要特别验证如下请求：

```http
POST /api/chat/stream-chat
Accept-Encoding: gzip
```

其 SSE 响应中仍然不得出现 `Content-Encoding`。

### 5.3 错误响应保持 identity

在响应头发出前发生的错误继续调用现有错误响应方法，不进入 gzip。包括：

- `conversationId` 缺失；
- 全局锁占用；
- 会话目录或索引读取失败；
- 预扫描失败。

在 gzip 响应头已经发出后出现错误时，不能再切换成明文 JSON 错误响应，只能：

1. 关闭当前 gzip/file 资源；
2. 释放锁；
3. 关闭 Channel；
4. 记录包含 conversationId、seq、variantIndex 的日志。

## 6. 数据语义

### 6.1 `totalSize`

`totalSize` 继续表示所有原始 fragment payload 的字节总数，不改成 gzip 后的网络传输大小。否则 gzip 与 identity 响应中的业务字段会不一致。

如需观测压缩效果，应使用日志或指标记录：

- `historyRawBytes`；
- `historyCompressedBytes`；
- `compressionRatio`；
- `compressionMillis`。

这些是服务端观测数据，不新增到现有前端响应契约中。

### 6.2 `.bin` 文件

本任务只改变网络响应编码，不改变磁盘持久化格式：

- 不把 fragment 文件改成 `.gz`；
- 不压缩 `index.bin`；
- 不压缩 `tools.bin`；
- 不修改 fragment 160 字节头；
- 不修改 payload 的落盘内容。

这样不会影响历史回放、消息编辑、变体切换、重新生成、继续生成和模型请求构造。

## 7. 资源与异常处理

实现必须保证：

- 内存占用为 O(固定缓冲区)，不能使用 `readAllBytes()` 聚合历史；
- 每个打开的 `RandomAccessFile`、InputStream 和 gzip stream 最终关闭；
- gzip 正常完成时写出 CRC32 和未压缩长度 trailer；
- 只有 gzip 完整 `finish()` 后才发送 `LastHttpContent`；
- 客户端断开后停止继续扫描 `.bin`；
- 任意退出路径都释放 `EasyChatGlobalLock.Lease`；
- HTTP 与 HTTPS 使用同一逻辑；
- 不产生临时 `.gz` 文件，也不复用静态文件 gzip 缓存。

gzip 会增加 CPU 消耗，并使原有文件内容不再具备直接透传的性质，这是网络压缩的必然代价。收益应通过真实大会话数据测量，不能只依据 JSON 文本的理论压缩率判断。

## 8. 预计代码影响范围

主要修改点预计为：

1. `EasyChatController`
   - 在 GET 历史分支解析 `Accept-Encoding`；
   - 将协商结果传给 Service。

2. `EasyChatService`
   - 为历史响应增加 gzip/identity 两条输出选择；
   - 复用统一的历史 JSON 字节生成逻辑；
   - 设置正确响应头；
   - 保证关闭、异常和锁释放顺序。

3. `EasyChatStorage`
   - 现有 `streamSlice(FragmentSlice, OutputStream)` 已基本满足需求；
   - 如有必要，只补充便于统一关闭或统计字节数的窄接口，不改变存储格式。

4. 新增 endpoint-local 输出工具
   - 例如 `NettyChunkedOutputStream` 或语义更具体的 `EasyChatHistoryChunkedOutputStream`。

5. 测试与文档
   - 增加 gzip 协商、解压一致性、SSE 隔离、大文件内存和断连清理测试；
   - 实施后更新 Netty HTTP Server 文档中的 Easy Chat 历史响应说明。

前端原则上无代码修改；只需做现有浏览器路径的回归验证。

## 9. 验收矩阵

| 场景 | 预期 Content-Encoding | 预期 Transfer-Encoding | 预期结果 |
| --- | --- | --- | --- |
| GET 历史，`Accept-Encoding: gzip` | `gzip` | `chunked` | 浏览器自动解压，`response.json()` 成功 |
| GET 历史，`Accept-Encoding: gzip;q=1` | `gzip` | `chunked` | 与 identity JSON 语义一致 |
| GET 历史，`Accept-Encoding: gzip;q=0` | 无 | `chunked` | 返回未压缩 JSON |
| GET 历史，`Accept-Encoding: identity` | 无 | `chunked` | 返回未压缩 JSON |
| GET 历史，无 `Accept-Encoding` | 无 | `chunked` | 返回未压缩 JSON |
| GET 历史，缺少 conversationId | 无 | 按现有错误响应 | 错误 JSON 保持 identity |
| POST SSE，客户端支持 gzip | 无 | 按现有 SSE 行为 | SSE 增量到达，不被压缩或聚合 |
| POST 非 SSE，客户端支持 gzip | 无 | 按现有行为 | 普通响应不压缩 |
| 其他 JSON API | 无新增压缩 | 不变 | 行为完全不变 |
| 图片、静态文件、下载 | 无本任务新增压缩 | 不变 | 行为完全不变 |

## 10. 验证步骤

### 10.1 gzip 响应

请求一个包含多条消息和大 payload 的真实会话，检查：

```text
Content-Type: application/json; charset=UTF-8
Content-Encoding: gzip
Transfer-Encoding: chunked
Vary: Accept-Encoding
```

将响应解压后验证：

- gzip 完整性校验通过；
- JSON 解析成功；
- 消息、变体和附件内容完整；
- 与 identity 响应解析后的 JSON 深度相等。

### 10.2 SSE 隔离

在 POST 请求中显式发送 `Accept-Encoding: gzip`，确认：

- 响应类型仍为 `text/event-stream`；
- 没有 `Content-Encoding`；
- 首个事件延迟没有因压缩缓冲增加；
- 后续事件持续增量到达；
- `[DONE]` 和连接关闭行为不变。

### 10.3 大会话与慢客户端

使用包含多 MB 文本、图片 data URL 或音频 data URL 的会话验证：

- JVM heap 不随完整历史大小线性增长；
- Netty pending outbound bytes 不持续无界增长；
- 慢客户端触发背压；
- 客户端中途断开后文件句柄、锁和 Channel 均正常释放；
- 随后仍可继续聊天、编辑和重新加载该会话。

### 10.4 HTTP/HTTPS

分别在 HTTP 和 HTTPS 模式下完成 gzip、identity、SSE 和断连验证，避免只在一种 Pipeline 形态下成立。

## 11. 最终结论

该方案可行。当前实现已经完成会话 `.bin` payload 的磁盘切片读取和 HTTP chunked 输出，新增工作集中在成功 GET 历史响应的 endpoint-local 流式 gzip 编码。

实施时最重要的约束是：

1. 完整历史 JSON 使用同一条 gzip stream；
2. gzip 输出继续采用 chunked 和有界缓冲；
3. 不读取完整会话到内存；
4. 压缩条件由 GET 历史分支显式决定；
5. SSE、错误响应及其他任何数据路径不进入该压缩流程。
