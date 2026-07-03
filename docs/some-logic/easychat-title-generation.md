# AI 自动标题生成方案

## 概述

当用户发送首条消息时，AI 在正式回复之前，先调用模型生成一个简短准确的会话标题，并更新侧边栏和头部标题。

---

## 完整流程

```
用户输入第一条消息 → 点击发送
  │
  ├─ 触发条件：enableTitleGen && 当前会话无历史 user 消息
  │
  ├─ 禁用发送按钮，显示"正在生成标题…"
  │
  ├─ generateTitleViaApi(prompt, signal)
  │     POST /api/chat/generate-title
  │     Body: { conversationId, model, prompt, nodeId? }
  │
  ├─ EasyChatController → EasyChatService.handleGenerateTitle()
  │     ├─ 解析请求参数 (conversationId, model, prompt, nodeId)
  │     ├─ resolveModelTarget() → 别名解析 / 自动加载模型
  │     └─ 异步 worker 线程执行：
  │           ├─ buildTitleRequestJson(modelId, prompt)
  │           ├─ 本地模型：requestTitleFromLocal()
  │           │     └─ POST localhost:{port}/v1/chat/completions
  │           └─ 远程节点：requestTitleFromRemoteNode()
  │                 └─ NodeManager.callRemoteApi()
  │
  ├─ 解析响应 -> parseTitleFromResponse()
  └─ 前端应用标题
        ├─ conversation.title = 返回的标题
        ├─ conversation.titleGenerated = true
        ├─ renderConversationList()  → 侧边栏列表更新
        ├─ syncConversationMeta()    → 头部标题更新
        └─ 恢复发送按钮 → 继续正常发送消息
```

---

## 配置 / 设置

| UI 元素 | DOM ID | 类型 | 说明 |
|---------|--------|------|------|
| 开启标题生成 | `titleGenToggle` | checkbox | 全局开关，默认关闭 |
| 标题生成模型 | `titleGenModelSelect` | select | 指定模型，留空则使用当前对话模型 |

状态持久化字段：

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `enableTitleGen` | boolean | `false` | 是否启用自动标题生成 |
| `titleGenModel` | string | `''` | 指定模型 ID，空字符串表示使用当前对话模型 |

---

## 触发条件

**文件**: `index.html:7690-7692`

```javascript
const hasFirstUserMessage = !!(getCurrentConversation()?.messages?.some(m => m.role === 'user' && hasMessageBody(m)));
const shouldGenerateTitle = state.enableTitleGen && !hasFirstUserMessage;
```

同时满足以下条件时触发：
1. `state.enableTitleGen === true`
2. 当前会话 **不存在** 任何 user 消息（仅首条消息触发）

**双重保护**：`state.isSending || state.isGeneratingTitle` 会阻止重复触发。

---

## 前端 API 调用

**端点**: `POST /api/chat/generate-title`

**函数**: `generateTitleViaApi(prompt, signal)` — `index.html:7235-7271`

**请求体**：
```json
{
  "conversationId": "<conversationId>",
  "model": "<modelId>",
  "prompt": "用户的首条消息文本",
  "systemPrompt": "<当前助手的 system prompt>"  // 可选，仅当助手有配置 system prompt 时存在
  "nodeId": "<nodeId>"     // 仅在使用远程节点时存在
}
```

**模型解析逻辑**：
1. 优先使用 `state.titleGenModel`（设置中指定的专用模型）
2. 否则回退到 `state.model`（当前对话模型）
3. 从复合格式 `modelId::nodeId` 中拆分出 bare modelId 和 nodeId

**响应**：
```json
{
  "success": true,
  "data": {
    "title": "生成的标题文本"
  }
}
```

---

## 后端处理

### 路由

**文件**: `EasyChatController.java:60-63`

```java
if (uri.startsWith(PATH_GENERATE_TITLE)) {
    this.handleGenerateTitleRequest(ctx, request);
    return true;
}
```

仅接受 `POST` 方法，委托给 `EasyChatService.handleGenerateTitle()`。

### 请求解析

**文件**: `EasyChatService.java:834-916`

提取字段：`conversationId`（必填）、`model`（必填）、`prompt`（必填）、`systemPrompt`（可选）、`nodeId`（可选）。

### 模型解析

```java
ModelTarget modelTarget = resolveModelTarget(modelId, nodeId);
```

- 有 `nodeId` → 标记为远程节点，后续调用 `requestTitleFromRemoteNode()`
- 本地模型 → 检查是否已加载，否则尝试别名解析和自动加载，获取端口后调用 `requestTitleFromLocal()`

两种路径都使用 **60 秒超时**（`TITLE_GEN_TIMEOUT_MS`）。

### 请求体构造

**文件**: `EasyChatService.java:919-945`

```java
private JsonObject buildTitleRequestJson(String modelId, String userPrompt, String systemPrompt) {
    String titlePrompt = "你是一个对话标题生成助手。\n"
        + "请根据下面的用户首条消息，生成一个简短准确的会话标题。\n"
        + "要求：\n"
        + "1. 只输出标题本身，不要加引号、标签或额外说明\n"
        + "2. 标题语言与用户输入的语言保持一致\n"
        + "3. 标题尽量简短，中文控制在 18 个汉字以内，其他语言控制在 8 个词以内\n"
        + "\n"
        + "[用户首条消息]\n"
        + userPrompt;

    JsonArray messages = new JsonArray();
    if (systemPrompt != null && !systemPrompt.isBlank()) {
        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        systemMessage.addProperty("content", systemPrompt);
        messages.add(systemMessage);
    }
    JsonObject userMessage = new JsonObject();
    userMessage.addProperty("role", "user");
    userMessage.addProperty("content", titlePrompt);
    messages.add(userMessage);
    // ...
}
```

**关键设计**：

| 属性 | 值 | 说明 |
|------|-----|------|
| `messages` | 1~2 条消息 | 可选 system message（当前助手配置）+ 1 条 user 消息，标题指令嵌入到 user content 中 |
| `stream` | `false` | 非流式调用，一次返回完整结果 |
| `temperature` | `0.3` | 低温度，保证输出稳定确定 |
| `max_tokens` | `30` | 标题长度限制 |
| `chat_template_kwargs.enable_thinking` | `false` | 关闭思维链 |
| 多模态内容 | 无 | 仅文本，不发送图片 |

### 响应解析

**文件**: `EasyChatService.java:975-1006`

```java
private String parseTitleFromResponse(String responseBody) {
    // 解析 OpenAI 兼容格式的 /v1/chat/completions 响应
    // 提取 choices[0].message.content
    // 返回 content 中第一行非空文本（trimmed）
    // 解析失败或内容为空时返回 null
}
```

### 响应给前端

```java
Map<String, Object> data = new HashMap<>();
data.put("title", title);
LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
```

---

## 标题应用

### 成功

```javascript
// index.html:7701-7710
if (title) {
    const titleConv = state.conversations.find(c => c.id === titleConvId);
    if (titleConv) {
        titleConv.title = title;
        titleConv.titleGenerated = true;
        if (state.currentConversationId === titleConvId) {
            renderConversationList();  // 侧边栏列表
            syncConversationMeta();    // 头部标题
        }
    }
}
```

### 失败

| 场景 | 行为 |
|------|------|
| HTTP 错误 | 打印警告日志，**不阻断**正常消息发送流程 |
| 返回 `success: false` | 同上，继续发送 |
| 返回空标题 | 同上，不保存空标题 |
| `AbortError` | 用户主动取消时完全终止流程（不再发送消息） |

### 标题保护机制

**文件**: `index.html:2703-2724`

```javascript
function updateConversationTitle(conversation) {
    if (conversation.titleGenerated && conversation.title) {
        return;  // 不覆盖 AI 已生成的标题
    }
    // ...
}
```

AI 生成的标题不会被后续手动截断或自动更新覆盖。清空对话时会重置 `titleGenerated = false`。

---

## 关键文件位置

| 组件 | 文件 | 行号 |
|------|------|------|
| 设置 UI | `index.html` | 399-419 |
| 状态默认值 | `index.html` | 595-596 |
| 端点常量 | `index.html` | 556 |
| `generateTitleViaApi()` | `index.html` | 7235-7271 |
| 触发逻辑 | `index.html` | 7690-7726 |
| 标题保护 | `index.html` | 2703-2724 |
| 后端路由 | `EasyChatController.java` | 60-63, 105-109 |
| `handleGenerateTitle()` | `EasyChatService.java` | 834-916 |
| `buildTitleRequestJson()` | `EasyChatService.java` | 919-945 |
| `requestTitleFromLocal()` | `EasyChatService.java` | 947-973 |
| `requestTitleFromRemoteNode()` | `EasyChatService.java` | 975-985 |
| `parseTitleFromResponse()` | `EasyChatService.java` | 987-1017 |
| `resolveModelTarget()` | `EasyChatService.java` | 755-799 |
