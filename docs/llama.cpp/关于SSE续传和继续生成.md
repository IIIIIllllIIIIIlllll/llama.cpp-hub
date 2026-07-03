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

## 2.4 思维链 (reasoning_content) 的继续生成

`continue_final_message` 不仅能续写正文，也能续写**思维链**部分。两者底层是同一套机制，区别只在于服务端在 `generation_prompt` 中是否闭合思维结束标签（如 `</think>`）。

### 三种模式的本质区别

核心实现在 `common/chat-auto-parser-generator.cpp:53-70`：

```cpp
if (mode != NONE) {
    // 找到 reasoning 起始标签（如 ildi），把生成起点重置到那里
    data.generation_prompt = ... + autoparser.reasoning.start + msg.reasoning_content;

    if (mode == CONTENT) {
        data.generation_prompt += autoparser.reasoning.end;   // 追加闭合标签
    }
    if (mode == CONTENT) {
        data.generation_prompt += msg.render_content();       // 追加已有正文
    }
}
```

| 模式 | 生成提示的形态 | 模型接下来产出 |
|------|---------------|---------------|
| `"reasoning_content"` | `ildi已有思维`（不闭合，不写正文） | 继续输出 reasoning -> 流式进 `delta.reasoning_content` |
| `"content"` | `ildi已有思维` + 闭合标签 + `已有正文` | 跳过思维，直接续写正文 -> 流式进 `delta.content` |
| `true` (AUTO) | 由服务端自动判定走上面哪一种 | 见下文 |

### AUTO 的自动判定逻辑

实现在 `common/chat.cpp:2502-2510`：

```cpp
if (AUTO && !messages.empty()) {
    mode = CONTENT;                       // 默认续写正文
    if (!continue_msg.reasoning_content.empty()
        && continue_msg.content.empty()
        && continue_msg.content_parts.empty()) {
        mode = REASONING;                 // 只有思维、没正文 -> 续思维
    }
}
```

**判定规则一句话**：最后一条 assistant 消息如果"有思维、无正文" -> 续思维；否则 -> 续正文。

### 客户端请求体设计

#### 场景 A：思维链已完成，续写正文（最常见）

```json
POST /v1/chat/completions
{
  "messages": [
    {"role": "user", "content": "证明哥德巴赫猜想"},
    {
      "role": "assistant",
      "reasoning_content": "首先假设...然后...因此...",   // 完整思维
      "content": "由上述分析可得"                          // 已写出的部分正文
    }
  ],
  "stream": true,
  "continue_final_message": true,        // 或 "content"
  "add_generation_prompt": false
}
```

流式响应里 `delta.content` 会持续追加，`delta.reasoning_content` 一般为空。

#### 场景 B：思维被中途打断，续写思维

```json
{
  "messages": [
    {"role": "user", "content": "证明哥德巴赫猜想"},
    {
      "role": "assistant",
      "reasoning_content": "首先假设...",   // 思维片段
      "content": ""                          // 必须为空，触发 AUTO 走 REASONING
    }
  ],
  "stream": true,
  "continue_final_message": true,           // 或显式 "reasoning_content"
  "add_generation_prompt": false
}
```

流式响应里 `delta.reasoning_content` 持续追加；当模型自己输出思维结束标签后，后续 token 会切换到 `delta.content`。客户端拼接逻辑：

```
finalReasoning = originalReasoning + appendedReasoning
finalContent    = (模型后续输出的 content)
```

#### 场景 C：显式指定模式

即使 `content` 非空，也可以用 `"content"` 强制只续正文；用 `"reasoning_content"` 时则**应当**让 `content` 为空，否则行为不符合语义。

### 关键约束与坑

1. **不带 think 标签**：`reasoning_content` 字段里只放**纯思维文本**，不要包含 `ildi`/`</think>`，服务端会自动加。
2. **最后一条必须是 assistant**：和普通 continue 一样。
3. **`add_generation_prompt` 必须为 false**：`server-common.cpp:1056` 会校验，否则报错。
4. **拼接方向**：客户端展示/保存永远是 `originalReasoning + appendedReasoning` 和 `originalContent + appendedContent`，只能 append，不能覆盖。
5. **与 SSE Replay 的关系**：和本节前面一致 - 网络断线由 Replay Buffer 自动续；用户主动 Stop 后才需要这种"继续生成"重发请求。Stop 时应分别保存 `reasoning_content` 和 `content` 两个 partial 到 DB，Continue 时按上面场景 A/B 组装 messages。
6. **`reasoning_format` 字段**：建议请求里显式带上（如 `"reasoning_format": "auto"` 或与首轮一致），避免不同轮次的思维解析格式不一致。

### 客户端实现指南（含思维）

#### 停止时分别保存两段 partial

```typescript
function stopGeneration(convId: string, partialReasoning: string, partialContent: string) {
  // 1. 取消服务端流
  await fetch(`/v1/stream/${encodeURIComponent(convId)}`, { method: 'DELETE' });
  // 2. 把两段 partial 内容分别写入本地 DB
  database.updateMessage(lastMessageId, {
    reasoning_content: partialReasoning,
    content: partialContent,
  });
  // 3. 清除流状态
  clearStreamState(convId);
}
```

#### 继续生成时按状态选择模式

```typescript
async function continueGeneration(convId: string, messageId: string) {
  const messages = await database.getConversationMessages(convId);
  const targetMsg = messages.find(m => m.id === messageId);
  const contextWithContinue = messages.slice(0, messages.indexOf(targetMsg) + 1);

  // 根据当前 partial 状态决定续写哪一段：
  //   正文为空而思维有内容 -> 续思维；否则续正文
  const mode = (!targetMsg.content && targetMsg.reasoning_content)
    ? 'reasoning_content'
    : 'content';

  let appendedReasoning = '';
  let appendedContent   = '';
  const response = await fetch('/v1/chat/completions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      messages: contextWithContinue,
      stream: true,
      continue_final_message: mode,        // 或 true 让服务端自动判
      add_generation_prompt: false,
    })
  });

  const reader = response.body.getReader();
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    const chunks = parseSSE(value);
    for (const chunk of chunks) {
      appendedReasoning += chunk.delta?.reasoning_content ?? '';
      appendedContent   += chunk.delta?.content ?? '';
      // 在 UI 显示：targetMsg.reasoning_content + appendedReasoning,
      //            targetMsg.content + appendedContent
      updateUI(
        targetMsg.reasoning_content + appendedReasoning,
        targetMsg.content + appendedContent,
      );
    }
  }
  // 最终保存
  database.updateMessage(messageId, {
    reasoning_content: targetMsg.reasoning_content + appendedReasoning,
    content:           targetMsg.content + appendedContent,
  });
}
```

### 小结

思维链的续写与正文续写底层是同一套 `continue_final_message` 机制，区别仅在于**服务端是否在 `generation_prompt` 中闭合思维结束标签** - 闭合则续正文，不闭合则续思维。客户端只需按"已有思维/已有正文"哪部分为空来决定模式即可。

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
