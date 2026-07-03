# SSE Replay Buffer & continue_final_message

## 概述

llama.cpp server 提供两个独立但可配合使用的功能：

| 功能 | 场景 | 用户感知 |
|------|------|----------|
| SSE Replay Buffer | 网络断开后自动续传 | 无感 |
| continue_final_message | 手动停止后继续生成 | 需点按钮 |

两者底层机制完全不同，下文分别说明。

---

# 一、SSE Replay Buffer (Resumable SSE Streaming)

## 1.1 原理

客户端 HTTP 连接断开后，服务端**继续在后台执行生成**，SSE 字节写入**有界环形缓冲区**（默认 4MB）。客户端可随时重新连接到同一个会话，从断点处继续接收。

核心源码：`tools/server/server-stream.h` / `server-stream.cpp`

## 1.2 服务端 API

### POST /v1/chat/completions — 启动可续传流

当请求头携带 `X-Conversation-Id` 时启用 Replay Buffer，不带则走标准 OAI 行为（连接断 = 生成停）。

```
POST /v1/chat/completions
X-Conversation-Id: <conv_id>
Content-Type: application/json

{ "messages": [...], "stream": true, ... }
```

**conv_id 的编码规则**：`<bare_conv_id>[::<model_name>]`

- 单模型模式：`conv_abc123`
- 多模型/路由模式：`conv_abc123::gpt-4` 确保不同模型的 stream 不冲突

### GET /v1/stream/<conv_id>?from=N — 续传流

```
GET /v1/stream/conv_abc123?from=12345
```

- 返回 `text/event-stream`
- `from=N` 指定字节偏移，客户端从断点继续接收
- 如果 `from < dropped_prefix`（数据已被新数据覆盖），返回 400
- 如果 conv_id 不存在或已过期，返回 404
- 返回 200 后持续推送 SSE，直到生成完毕或被取消

### POST /v1/streams/lookup — 查询活跃会话

```
POST /v1/streams/lookup
Content-Type: application/json

{ "conversation_ids": ["conv_abc123", "conv_def456::gpt-4"] }
```

响应示例：

```json
[
  {
    "conversation_id": "conv_abc123::gpt-4",
    "is_done": false,
    "total_bytes": 23456,
    "started_at": 1712345678,
    "completed_at": 0
  }
]
```

- `is_done` — 生成是否已完成
- `total_bytes` — 缓冲区累计字节数
- `started_at` / `completed_at` — unix 秒时间戳，`completed_at` 为 0 表示仍在运行
- 支持前缀匹配：查询 `"conv_abc123"` 可以匹配 `"conv_abc123::gpt-4"`

### DELETE /v1/stream/<conv_id> — 停止生成（显式 Stop）

```
DELETE /v1/stream/conv_abc123
```

- 幂等操作，不管 session 是否存在都返回 204
- 会触发 `cancel()` → 服务端停止生成 + 清空缓冲区

### 服务端常量（server-stream.cpp:11-13）

```cpp
constexpr int64_t STREAM_SESSION_TTL_SECONDS         = 300;  // 已完成会话 5 分钟后 GC
constexpr size_t  STREAM_SESSION_MAX_BYTES           = 4 * 1024 * 1024; // 环形缓冲 4MB
constexpr int64_t STREAM_SESSION_GC_INTERVAL_SECONDS = 60;   // GC 扫描间隔 60 秒
```

## 1.3 客户端实现指南

### 数据结构

客户端需要维护每个 conv 的流状态：

```typescript
interface ResumableStreamState {
  bytesReceived: number;   // 已从服务端接收的字节数（偏移）
  updatedAt: number;       // 最后更新时间
  model: string | null;    // 发送请求时使用的模型名
}
```

存储方式：localStorage，key 为 `resumable_stream_<conv_id>`。

### 发送请求时

```typescript
headers['X-Conversation-Id'] = streamIdentity(conversationId, model);
```

收到 SSE 数据时，每个 SSE 行解析后更新偏移量：

```typescript
const tailBytes = encoder.encode(partialLine).byteLength;
bytesParsed = segmentStartOffset + segmentBytesRead - tailBytes;
saveStreamState(conversationId, bytesParsed, model);
```

### 页面加载 / 切回时

```typescript
// 1. 探针查询
const resp = await fetch('/v1/streams/lookup', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ conversation_ids: [convId] })
});
const sessions = await resp.json();
const session = sessions.find(s => !s.is_done); // 只取还在运行的

// 2. 如果有活跃会话，attach 流
if (session) {
  const state = getStreamState(convId); // 从 localStorage 获取偏移
  const from = state?.bytesReceived ?? 0;
  const url = `/v1/stream/${encodeURIComponent(session.conversation_id)}?from=${from}`;
  const streamResp = await fetch(url);
  // 用 streamResp.body.getReader() 读取 SSE，同正常流程
}
```

### 自动重连（网络断开时）

当 `reader.read()` 返回 `done` 或抛出异常时，不要立即结束：

```typescript
while (true) {
  reader = response.body.getReader();
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    // 正常处理 SSE 数据...
  }
  // 连接断开，尝试续传
  if (aborted || streamFinished) break;
  const resumeResp = await fetch(`/v1/stream/${id}?from=${bytesParsed}`);
  if (resumeResp.status !== 200) break;  // session 已过期
  reader = resumeResp.body.getReader();
  // 继续循环...
}
```

### 停止生成（用户主动 Stop）

```typescript
// 1. 保存已接收的 partial 内容
savePartialResponseToDB(convId, partialContent);
// 2. 通知服务端取消
await fetch(`/v1/stream/${encodeURIComponent(convId)}`, { method: 'DELETE' });
// 3. 断开本地流
abortController.abort();
```

---

# 二、continue_final_message（继续生成）

## 2.1 原理

不是恢复服务端的生成任务，而是**重新发起一次 API 请求**，通过 `continue_final_message: true` 让服务端**跳过插入新的 assistant prompt 头**，直接在已有的 assistant content 末尾继续生成后续 token。

核心源码：`tools/server/server-common.cpp:1044-1058`

## 2.2 API 参数

```
POST /v1/chat/completions
Content-Type: application/json

{
  "messages": [
    {"role": "system",    "content": "你是一个诗人"},
    {"role": "user",      "content": "写一首诗"},
    {"role": "assistant", "content": "窗前明月光，"}   // ← 已有的 partial 内容
  ],
  "stream": true,
  "continue_final_message": true,
  "add_generation_prompt": false     // 必须为 false
}
```

### 参数取值

| 值 | 行为 | 枚举常量 |
|----|------|----------|
| `true` | 自动检测从哪继续（推荐） | `COMMON_CHAT_CONTINUATION_AUTO` |
| `"content"` | 只续写 content | `COMMON_CHAT_CONTINUATION_CONTENT` |
| `"reasoning_content"` | 只续写 reasoning_content | `COMMON_CHAT_CONTINUATION_REASONING` |

### 规则

- 最后一条消息必须是 `role: "assistant"`
- `add_generation_prompt` 和 `continue_final_message` 不能同时为 `true`，否则服务端返回错误

## 2.3 客户端实现指南

### 停止时保存 partial 内容

```typescript
function stopGeneration(convId: string, partialContent: string) {
  // 1. 取消服务端流
  await fetch(`/v1/stream/${encodeURIComponent(convId)}`, { method: 'DELETE' });
  // 2. 把 partial 内容写入本地 DB
  database.updateMessage(lastMessageId, { content: partialContent });
  // 3. 清除流状态
  clearStreamState(convId);
}
```

### 继续生成时

```typescript
async function continueGeneration(convId: string, messageId: string) {
  const messages = await database.getConversationMessages(convId);
  const targetMsg = messages.find(m => m.id === messageId);
  // contextWithContinue 包含了 partial assistant 在内的完整历史
  const contextWithContinue = messages.slice(0, messages.indexOf(targetMsg) + 1);

  let appendedContent = '';
  const response = await fetch('/v1/chat/completions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      messages: contextWithContinue,
      stream: true,
      continue_final_message: true,
      add_generation_prompt: false,
    })
  });

  const reader = response.body.getReader();
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    const chunks = parseSSE(value);
    for (const chunk of chunks) {
      appendedContent += chunk.content ?? '';
      // 在 UI 显示：targetMsg.content + appendedContent
      updateUI(targetMsg.content + appendedContent);
    }
  }
  // 最终保存
  database.updateMessage(messageId, {
    content: targetMsg.content + appendedContent
  });
}
```

### 关键：内容拼接

```
显示 = originalContent + appendedContent
保存 = originalContent + appendedContent
```

不要覆盖 originalContent，只能用 append。

### 注意

`continue_final_message` 和 SSE Replay Buffer 是两个独立功能：

- 如果只是网络断开 → SSE Replay **自动**续传，客户端无感知
- 如果是用户主动 Stop → **不会**自动恢复，需要用户点击 Continue 按钮
- 如果 Stop 后又用 Continue → 新发一个 POST 请求，**不是**续之前的 SSE 流
- 如果已经生成完毕（`[DONE]`） → 也可以用 `continue_final_message` 接着追加

---

# 三、两者关系总结

```
网络断开 (HTTP socket drop)
  └─ 有 X-Conversation-Id → SSE Replay Buffer: 服务端继续生成到环形缓冲
  │     └─ 重连后 GET /v1/stream/<id>?from=N → 无感恢复
  └─ 无 X-Conversation-Id → 生成立即停止（标准 OAI 行为）

用户主动 Stop
  ├─ 取消服务端: DELETE /v1/stream/<id>
  ├─ 保存 partial 内容到 DB
  └─ 后续点 Continue: POST /v1/chat/completions { continue_final_message: true }
       └─ 用已有消息 + partial 内容重新发起请求
```

两种功能可组合使用：网络断线后自动续传，用户中途 Stop 后也可手动 Continue。
