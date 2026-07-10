# Easy Chat 上下文用量（context-usage）功能

## 概述

每条 AI 回复的头部（`.message-head`）尾部、消息时间戳前面，会显示一个上下文用量徽章，形如 `12.3K / 32K`，表示本次回复**已使用 / 模型可用**的上下文 token 数。该徽章**只挂在当前会话里最后一条 assistant 消息上**；切换会话、切换回复变体或新回复开始时，会从旧消息上移除并挂到新的"最后一条 assistant"上。

徽章仅在最后一条 assistant 消息出现是设计使然，不是 bug——它代表"会话进行到此刻累计占用上下文"，所以只关心最新进度。

---

## 数据来源

### 已使用（used）

来自 assistant 消息（或其 active response）的 `timings` 字段，计算口径为：

```js
used = timings.prompt_n + timings.predicted_n
```

- `prompt_n`：本次请求加载进上下文的 prompt token 数（已含缓存命中部分）。
- `predicted_n`：本次回复生成的 token 数。

二者相加即为"本次回复结束时上下文窗口里已经装了多少 token"。这是 llama.cpp 兼容 OpenAI 接口在响应里返回的 `usage` / `timings` 字段，由前端经 `normalizeTimings()` 归一化后保存。

**关键**：`timings` 仅在一条回复**完成**后才存在。流式过程中的 assistant 消息 `timings` 为 `null`。

### 可用总量（total）

来自 `state.modelAvailableContext`，由 `syncAvailableContext()` 通过 `getModelRuntimeCtx(state.model)` 从当前模型的运行时信息（`modelOption.runtimeCtx`）取得。模型未加载或运行时上下文未知时为 `null` / `0`，此时徽章退化为 `'-'`。

---

## 涉及的函数（全在 `index.html`）

| 函数 | 行号（修复后，约） | 职责 |
|------|------|------|
| `normalizeTimings(value)` | 3379 | 把后端 timings 对象归一化成稳定结构；空/非法输入返回 `null`。保留 `prompt_n`、`predicted_n` 等字段。 |
| `normalizeConversationMessage(message)` | 3594 | 归一化消息；assistant 消息会把 active response 的 `timings` 同步到消息顶层 `message.timings`。 |
| `syncAssistantMessageState(message)` | 3682 | 把 active response 的 `timings`、`isStreaming` 等同步到消息顶层，供外部直接读取。 |
| `getModelRuntimeCtx(value)` | 4554 | 从当前模型选项取 `runtimeCtx`（可用上下文总量）。 |
| `createMessageElement(message, index)` | 5873 | 创建消息 DOM。只在"最后一条 assistant"的 head 里插入 `<span class="context-usage">-</span>` 占位。 |
| `syncConversationMeta()` | 6279 | 同步标题/计数器，并调用 `updateLastAssistantContextUsage()`。在 `appendLatestMessage`、`renderMessages`、标题生成等路径上频发触发。 |
| `computeUsedContext()` | 6286 | **核心**：计算要展示的 used 值，含流式回退逻辑。 |
| `formatNumberCompact(n)` | 6301 | 数值压缩显示（`12.3K` / `1.2M`）。 |
| `updateLastAssistantContextUsage()` | 6308 | **核心**：决定把徽章挂到哪条 assistant 上、清理旧徽章、写入 computed 文本。 |
| `syncAvailableContext()` | 6347 | 切换模型后更新 `state.modelAvailableContext` 并刷新徽章。 |
| `appendLatestMessage()` | 6369 | 追加新消息 DOM，随后调用 `syncConversationMeta()`。 |
| `updateAssistantMessageElement(article, message, index)` | 6493 | 流式增量更新**已存在** article。只改 head 里的 `<strong>` 和最后一个 `.message-time`，**不会重建 head、不触碰 `.context-usage`**。 |
| `setAssistantResponseStreaming(index, isStreaming)` | 6782 | 翻转 assistant 消息的流式标志（开始/停止流式），随后刷新徽章。 |
| `updateAssistantMessage(...)` | ~6700 | 每条流式 chunk 调用，写入 timings、reasoning、content；仅 `isFinished=true` 时调用 `updateLastAssistantContextUsage()`。 |

---

## DOM 结构

```
article.message.assistant[data-index=...]
└─ div.message-head
   ├─ span.avatar ...
   ├─ strong <助手名>
   ├─ span.context-usage          ← 用量徽章（只在最后一条 assistant 上存在）
   └─ span.message-time ...
```

CSS：`index.css` `.message-head .context-usage { ... }`（约 1801 行），并适配深色/背景图模式。

---

## 计算与渲染流程

### 一次完整回复的时序

1. **用户发送** → `pushMessage('assistant', '', { model }, { incremental: true })`
   - 把空的 assistant 消息 push 进 `conversation.messages`，**此时 `message.isStreaming` 尚未设置**。
   - 立即 `appendLatestMessage()` → `createMessageElement()` 在 head 写入占位 `<span class="context-usage">-</span>` → `syncConversationMeta()` → `updateLastAssistantContextUsage()` → `computeUsedContext()`。
2. **`setAssistantResponseStreaming(idx, true)`** 翻转 `isStreaming = true`，随后再次 `updateLastAssistantContextUsage()`（修复后新增），把徽章刷新成**上一条已完成回复的历史用量**。
3. **流式 chunk 到达** → `updateAssistantMessage(..., isFinished=false)`
   - 更新 `activeResponse.content / reasoning / timings`（流式中 timings 通常仍为空）。
   - `updateAssistantMessageElement` 只增量更新 bubble 内容，**不刷新徽章**。
4. **流式结束** → `updateAssistantMessage(..., isFinished=true)`
   - 写入最终 `timings`，`isStreaming=false`，调用 `updateLastAssistantContextUsage()`，徽章变为本次回复的真实用量。

### `computeUsedContext()` 选择 target 的逻辑（修复后）

```js
const lastAssistant = reversed.find(m => m.role === 'assistant');
if (!lastAssistant) return 0;
const hasUsableTimings = (m) => m && m.timings && typeof m.timings === 'object';
const target = (lastAssistant.isStreaming || !hasUsableTimings(lastAssistant))
  ? reversed.find(m => m.role === 'assistant' && hasUsableTimings(m) && !m.isStreaming)
  : lastAssistant;
if (!target) return 0;
const t = target.timings;
if (!t || typeof t !== 'object') return 0;
return (t.prompt_n ?? 0) + (t.predicted_n ?? 0);
```

含义：
- 最后一条 assistant 已完成（有 timings 且非流式）→ 直接用它，显示本次用量。
- 最后一条 assistant 正在流式 **或** 还没有 timings（例如刚创建、`isStreaming` 尚未翻转的窗口期）→ 回退到"最近一条带有效 timings 且非流式的 assistant"，展示历史用量。
- 找不到任何可用 timings（首条回复、或全部仍在流式）→ 返回 0，配合 `total=0` 时显示 `'-'`。

> 设计意图：用户新发送一条消息、AI 正在流式作答时，本次用量尚未产生，界面前临时显示上一条已完成回复的用量，保持徽章非空、避免闪 0；本次回复结束后刷新为真实值。首条回复没有历史可回退，显示 `0 / N` 或 `'-'` 符合预期。

### `updateLastAssistantContextUsage()` 渲染逻辑

1. 求出"最后一条 assistant"在 `conversation.messages` 中的下标 `lastIndex`。
2. 扫描 DOM 里所有 `article.assistant .message-head .context-usage`，凡 `data-index !== lastIndex` 的全部移除（保证徽章只挂在最后一条 assistant）。
3. 找到 `lastIndex` 对应 article 的 head，复用或新建 `.context-usage` span，写到 `.message-time` 之前。
4. 写文本：`total ? formatNumberCompact(used) + ' / ' + formatNumberCompact(total) : '-'`。

---

## 调用点全景

| 触发场景 | 调用链 | 是否刷新徽章 |
|------|------|------|
| 新消息追加 | `pushMessage(incremental)` → `appendLatestMessage` → `syncConversationMeta` | ✅（首次渲染，此时 `isStreaming` 尚未置位，依赖 `computeUsedContext` 的 timings 回退） |
| 流式开始/停止 | `setAssistantResponseStreaming` → `updateLastAssistantContextUsage` | ✅（修复后新增） |
| 流式 chunk | `updateAssistantMessage(isFinished=false)` | ❌（`updateAssistantMessageElement` 不碰徽章，`isFinished` 守卫未触发） |
| 流式完成 | `updateAssistantMessage(isFinished=true)` → `updateLastAssistantContextUsage` | ✅（写入真实用量） |
| 切换回复变体 | `switchAssistantResponse` → `updateLastAssistantContextUsage` | ✅ |
| 切换模型 | `syncAvailableContext` → `updateLastAssistantContextUsage` | ✅ |
| 切换会话/全量渲染 | `renderMessages` →（间接）`syncConversationMeta` | ✅ |
| 标题生成完成 | `syncConversationMeta` | ✅ |

---

## 历史已知问题与修复记录

### 流式期间显示 0 / '-' （已修复）

**现象**：用户发送新消息，AI 流式作答过程中，用量徽章始终显示 `0` 或 `'-'`，直到回复完成才更新为正确值。

**根因**：时序 bug。`pushMessage(..., { incremental: true })` 会同步走完 `appendLatestMessage()` → `syncConversationMeta()` → `updateLastAssistantContextUsage()` → `computeUsedContext()`，而此时 `setAssistantResponseStreaming(idx, true)` 尚未执行，新创建的 assistant 消息 `isStreaming` 仍为 `undefined`。于是 `computeUsedContext` 原本的流式回退分支 `lastAssistant.isStreaming ? ... : lastAssistant` 走到 else，把一条没有 timings 的新消息当作 target，直接 `return 0` 写进徽章。后续流式 chunk 路径不刷新徽章，该错误值一直停留到流式结束。

**修复**（两处互补）：

1. `computeUsedContext()`（index.html:6286）— 回退条件从"仅看 `isStreaming`"扩展为"`isStreaming` 为真 **或** 最后一条 assistant 没有可用 timings"，并要求回退目标自身必须有有效 timings：

   ```js
   const hasUsableTimings = (m) => m && m.timings && typeof m.timings === 'object';
   const target = (lastAssistant.isStreaming || !hasUsableTimings(lastAssistant))
     ? reversed.find(m => m.role === 'assistant' && hasUsableTimings(m) && !m.isStreaming)
     : lastAssistant;
   ```

   这样即便 `isStreaming` 未及时翻转，一条 timings-less 的新消息也不会导致算出 0，而是回退到最近一条已完成的回复。

2. `setAssistantResponseStreaming()`（index.html:6797）— 在翻转 `isStreaming` 之后追加调用 `updateLastAssistantContextUsage()`，作为流式开始/停止的统一中枢刷新点，覆盖 SSE、继续生成、停止流式等所有路径。

**验证要点**：
- 首条回复（无历史）流式期间仍显示 `0 / N` 或 `'-'`，属预期。
- 第二条及之后回复流式期间显示上一条已完成回复的用量，回复完成时刷新为本次真实值。
- 切换会话、切换回复变体后徽章照常挂在新的"最后一条 assistant"上。

---

## 易错点 / 扩展指南

- **新增任何修改 `conversation.messages` 或 assistant `isStreaming` 的路径**，都应考虑是否需要调用 `updateLastAssistantContextUsage()`；否则徽章可能与状态不同步。
- **徽章只跟"最后一条 assistant"绑定**：若未来需求改为每条 assistant 都显示自身用量，需要改 `updateLastAssistantContextUsage` 里的清理逻辑（6315-6322 行）和 `createMessageElement` 的 `isLastAssistant` 判断（5898-5899 行）。
- **`updateAssistantMessageElement` 不会重建 head**：任何依赖 head 子元素刷新的逻辑都不能寄望于流式增量更新来触发，必须在 `setAssistantResponseStreaming` 或 `updateAssistantMessage(isFinished=true)` 这类显式时点调用刷新。
- **timings 归一化**：新增 timings 字段需同步更新 `normalizeTimings()`（index.html:3379）的 keys 白名单与 `computeUsedContext()` 的计算口径。
- **total 来源**：`state.modelAvailableContext` 只在切换模型 / `syncAvailableContext()` 时更新；若后端模型运行时上下文动态变化，需要主动调用 `syncAvailableContext()`。