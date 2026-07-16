# id_slot 转发注入方案（已搁置）

> 状态：**搁置**。本文件只记录方案，未实施。
> 起因：希望在 `/v1/chat/completions` 和 `/api/chat/stream-chat` 转发到**本地** llama.cpp 时，按下述规则注入 `id_slot` 字段，并在 Java 侧维护每个 slot 的空闲/忙碌状态以便展示。
> 搁置原因：发现可用更轻的「投机取巧」方案——只统计当前正在进行的请求数量、对比可用 slot 数（`slotNum`），按数量差展示忙碌状态即可，用户无感知差别。该投机方案不做真正的 slot 绑定。

## 1. 关键代码位置调研结论

- 配置与槽位状态：`LlamaCppProcess` 持有 `int slotNum` 与 `int[] slotStatus`，slotStatus 在 `setSlotNum` 时 `new int[slotNum]` 初始化（`LlamaCppProcess.java:63, 96-99`）。当前 slotStatus **永远是全 0**（grep `slotStatus\[` 无任何写入），get 拼写错误为 `getSlotStaus()`（line 105，少一个 t，已被外部 UI 引用，不可改名）。`setSlotNum` 由 `LlamaServerManager.java:2122` 在模型加载流程调用。
- `/v1/chat/completions`：入口 `OpenAIChatStreamingHandler` → `ChatStreamSession.run()`（`service/ChatStreamSession.java:198`）。本地/远程判别在 `openConnectionForModel`（`ChatStreamSession.java:288-325`）：**本地判据 = `routingNodeId == null && resolvedModelId != null && connection != null`**。本地 URL = `http://localhost:<port>/v1/chat/completions`。Body 经 `StreamingForwarder.streamBody` → `UnifiedBodyBuffer.streamInjected`，在最后一个 `}` 前注入字段（当前已注入 sampling + timing，见 `StreamingForwarder.java:184-222`）。`finally` 块在 `ChatStreamSession.java:258-265`。
- `/api/chat/stream-chat`：入口 `EasyChatController` → `EasyChatService.handleStreamChat`。路由 `resolveModelTarget`（`EasyChatService.java:1078-1142`）返回 `ModelTarget.isRemoteNode`。本地连接 `openTrackedConnection`（`EasyChatService.java:1365-1377`）。worker 主流程 `EasyChatService.java:516-693`：本地分支 `line 550-584`，远程分支 `line 544-548`。Body 由 `EasyChatRequestWriter.writeRequestBody`（`EasyChatRequestWriter.java:30`）逐字段拼装，采样/kwargs 在 `writeExtraFields`（`line 118-149`）以 `requestOptions` + `writeObjectFields` 输出。worker `finally` 块 `line 681-692`。远程标题生成 `requestTitleFromLocal`（`:1266`）等其它本地转发**不在本方案范围**。

## 2. 决策（已与用户确认）

1. 锁放 `LlamaCppProcess` 内（slotStatus 是进程私有），新增 `reserveChatSlot()` / `releaseChatSlot(int)`。
2. 释放时机 = 响应流结束 / 出错 / 取消时（挂接到两条路径的 `finally` 块，覆盖 channelInactive / cancelled 路径）。
3. 所有 slot 忙 → 无限等待（阻塞虚拟线程）。
4. 注入范围仅主聊天两条路径；不含 control、标题生成、Benchmark、Ollama、LMStudio 等。
5. UI 展示不在本任务范围，由用户自行接 `getSlotStaus()`。
6. slotStatus 语义只用 0/1。

## 3. 待实施改动清单

### 3.1 `LlamaCppProcess.java`
新增：
```java
private final Object slotLock = new Object();

public int reserveChatSlot() {
    synchronized (slotLock) {
        while (true) {
            if (slotStatus == null || slotStatus.length == 0) return -1;
            for (int i = 0; i < slotStatus.length; i++) {
                if (slotStatus[i] == 0) { slotStatus[i] = 1; return i; }
            }
            try { slotLock.wait(); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); return -1;
            }
        }
    }
}

public void releaseChatSlot(int index) {
    if (index < 0) return;
    synchronized (slotLock) {
        if (slotStatus != null && index < slotStatus.length && slotStatus[index] != 0) {
            slotStatus[index] = 0;
            slotLock.notifyAll();
        }
    }
}
```
注意：进程 `stop()` 不会唤醒等待者；本方案不在 `onProcessExited` 里清空等待，依赖客户端断开 → IOException → finally release。长期可考虑在 `onProcessExit()` 加一次 `synchronized(slotLock) slotLock.notifyAll()`。

### 3.2 `ChatStreamSession.java`（`/v1/chat/completions`）
- 类成员新增 `private int reservedSlot = -1;`
- `run()` 在 `openConnectionForModel` 之后、`streamBody` 之前 reserve：
  ```java
  if (this.routingNodeId == null && this.resolvedModelId != null && this.connection != null) {
      LlamaCppProcess proc = LlamaServerManager.getInstance()
          .getLoadedProcesses().get(this.resolvedModelId);
      if (proc != null) {
          this.reservedSlot = proc.reserveChatSlot();
          if (this.reservedSlot >= 0) this.forwarder.setIdSlot(this.reservedSlot);
      }
  }
  ```
- `finally` 块（258-265）追加 release（判 `reservedSlot >= 0`）。

### 3.3 `StreamingForwarder.java`
- 新增 `private volatile Integer idSlot;` + `setIdSlot(Integer)`。
- `streamBody` 的本地分支（else，`nodeId == null`）内，把 `id_slot` 拼入 `injection`：
  ```java
  if (this.idSlot != null) {
      String idSlotField = "\"id_slot\":" + this.idSlot.intValue();
      injection = (injection.isEmpty() ? "" : injection + ",") + idSlotField;
  }
  ```
  之后再与 `timingInjection` 合并保持原逻辑。远程分支不注入。

### 3.4 `EasyChatRequestWriter.java` + `EasyChatService.java`（`/api/chat/stream-chat`）
- `RequestSpec` 新增 `Integer idSlot`，构造器加形参。
- `writeExtraFields` 中：仅当 `spec.idSlot != null`，`requestOptions.addProperty("id_slot", spec.idSlot)`，**不**加入 `writeObjectFields` 排除名单（让其随其他采样参数一起写出）。实施前需先读 `writeObjectFields` 实现确认重复字段语义。
- `EasyChatService.writeRequestBody(...)` 签名（`EasyChatService.java:1446`）加 `Integer idSlot`，透传给 `new RequestSpec(..., idSlot)`；远程调用点（`EasyChatService.java:2291`）传 `null`。
- worker（`516-693`）：
  - 顶部 `int reservedSlot = -1;`
  - 本地分支在 `openTrackedConnection` 之前 reserve，把 `reservedSlot >= 0 ? Integer.valueOf(reservedSlot) : null` 透传给 `writeRequestBody`。
  - `finally` 块（681-692）追加 release（判 `reservedSlot >= 0 && finalModelId != null`）。
  - 远程分支不 reserve。

## 4. 行为汇总
- 本地 /v1/chat/completions：reserve → 在 body 末尾 `}` 前注入 `"id_slot":N` → 转发 → 流结束后 release。
- 本地 /api/chat/stream-chat：reserve → writeRequestBody 写入 `id_slot` 字段 → 转发 → finally release。
- 远程两条路径：不 reserve、不注入。
- 所有 slot 忙：虚拟线程阻塞等待任意 slot 释放。
- slotNum == 0 / 进程未配置 `--parallel`：`reserveChatSlot` 返回 -1，跳过注入，行为等同现状。

## 5. 备选：投机方案（当前倾向）

不再真正给 llama.cpp 传 `id_slot`，仅在 Java 侧维护「正在进行的本地请求数」`activeCount`：
- 在 `ModelRequestTracker` 或 `LlamaCppProcess` 上加 AtomicInteger 自增/自减，两条聊天路径的本地分支 enter 时 +1、finally 时 -1。
- 展示时：`busy = min(activeCount, slotNum)`，逐个 slot 渲染前 `busy` 个为忙碌、其余空闲（或整体用「N/M 槽位忙碌」文本展示），用户无法分辨真假。
- 优点：无锁等待、无 body 注入、无需改 `StreamingForwarder`/`EasyChatRequestWriter`、远程路径天然不受影响（只统计本地）。改动面远小。
- 缺点：与 llama.cpp 真实 slot 调度状态解耦，理论上可能出现 Java 侧显示空闲但 llama.cpp 内部 slot 全忙（不过这种情况只会在并发超量时，本身就是瓶颈，可接受）。

## 6. 验证
- 真方案：启动 `--parallel 2` 模型，并发 3 个 `/v1/chat/completions` 请求，第 3 个应在前 2 个完成后才发出；`getSlotStaus()` 实时反映 0/1。
- 投机方案：同样并发，`activeCount` 应瞬时 = 3，展示为 `min(3, slotNum)=2` 槽忙碌。
- 回归：`mvnw compile` + `mvnw test -DskipITs`（命令待用户确认后被加入 `AGENTS.md`）。

---

## 7. 最终采纳的投机方案（已实施 2026-07-16）

复用旧版被隐藏的 slot 小方块 UI，不向 llama.cpp 注入 `id_slot`，改由 `ModelRequestTracker.activeCount` × `LlamaCppProcess.slotNum` 实时合成为伪 slots 数组，前端画 10×10 小方块：忙绿/空闲黑（沿用原样式）。

### 旧 UI 复活要点
- 旧定时轮询 `startSlotsPolling`（`LlamaServerManager.java:259-310` 已整段注释，永不恢复）——当年卡死源头，不动。
- `WebSocketManager.sendModelSlotsEvent`（`:242`）保留但无调用方。
- 前端管线现成：`websocket.js:204 handleModelSlotsUpdate` → `model-list.js:470 updateModelSlotsDom` → `renderSlotsSquaresInner`，期望 `{id, is_processing, speculative}`。
- **唯一隐藏点**：`index.css:593 .model-slots { display: none !important; }`（已删除）。`model-list.js:398` 那个 span 上还带 `style="display:none;"` 内联（已删除）。

### 改动清单（共 5 处）

#### A. `ModelRequestTracker.java` `broadcastBusy`（line 129）
广播事件里追加 `slotNum`：从 `LlamaServerManager.getInstance().getLoadedProcesses().get(modelId)` 取本地进程的 `getSlotNum()`；远程/未加载的 modelId 查不到 → 0，前端据此不渲染任何方块（天然隔离远程）。触发点不变：create / remove / updatePhase。

#### B. `ModelActionController.java` `buildLocalLoadedModels`（line 720 附近）
`/api/models/loaded` 响应每条加 `modelData.put("slotNum", process.getSlotNum())`，供页面初次加载拿到。

#### C. `websocket.js` `handleModelBusyEvent`（line 221）
把 `{activeCount, slotNum}` 合成为伪 slots 数组喂给 `updateModelSlotsDom`，同时 patch `currentModelsData` 的 `slotNum`/`slots` 字段：
```js
const busyCount = Math.min(active, slotNum);
const slots = [];
for (let i = 0; i < slotNum; i++) slots.push({ id: i, is_processing: i < busyCount });
updateModelSlotsDom(data.modelId, slots, data.nodeId);
applyModelPatch(data.modelId, { busy: !!data.busy, slotNum, slots }, data.nodeId);
```
无需向 `broadcastBusy` 加 `nodeId`：`applyModelPatch` 的本地匹配条件是 `!m.nodeId || m.nodeId === 'local'`，远程模型因 slotNum=0 不渲染方块即可。

#### D. `model-list.js`
- `modelsWithStatus` map 显式拷 `slotNum`，并首次即合成 `initialSlots` 全空闲数组，让卡片在首屏（首个 WS 事件到来前）就能显示空槽方块。
- 删除 `slotsAndNode` span 上的 `style="display:none;"` 内联。
- `updateModelSlotsDom` 把 `visibility:hidden/visible` 改为 `display:none/''`，避免空槽位仍占 10px 空白。

#### E. `index.css:593`
删除 `.model-slots { display: none !important; }`——保留 `.model-slots` 默认 `display:flex`（line 547）和 `.model-slot-square` 现有样式。

### 远程污染分析（确认不污染 UI）
- `/api/chat/stream-chat` 远程分支已 `!finalIsRemoteNode` 守卫，不计数。
- `/v1/chat/completions` 流式（`ChatStreamSession`）、`OllamaChatService`、`LMStudioService`、`OpenAIService.forwardRaw/NonStream/Stream` 等**远程请求也会触发 `createRequest`**——这是事实，无法轻松改。
- 但远程 modelId 多以 `nodeId: modelName` 或独立 namespace 形式存在，本地 `loadedProcesses.get(modelId)` 查不到 → slotNum=0 → 前端不渲染任何方块。
- 唯一边界：`ChatStreamSession.resolveFromRemoteNodes`（`ChatStreamSession.java:381-419`）和 `OllamaChatService` 远程回退分支**不做 key 前缀化**，若远程节点上恰好有同名模型，会让本地槽位出现"虚高"（计数 +1 但本地未必真在跑）。罕见情况，且 `activeCount > slotNum` 时被 `Math.min` 截断，最多全是灰，可接受。

### 行为总结
- 模型加载完成 → 前端 `loadModels` → `/api/models/loaded` 返回含 `slotNum` → 卡片首次渲染即出现 slotNum 个空闲方块。
- 任意聊天 create/phase/remove → `broadcastBusy` 广播 `model_busy { activeCount, slotNum }` → 前端合成并渲染，忙绿/空闲黑实时切换。
- 远程模型卡片永远没有方块（slotNum=0）。
- `model-detail.js` 的"Slots状态"弹窗标签页（走真实 `/api/models/slots/get`，`LlamaServerManager.handleModelSlotsGet` → llama.cpp `/slots`，手动按需触发）保留不动，不是卡死源。
- 未配置 `--parallel` 时 llama-server 默认 slotNum=1，画 1 颗方块，请求中变灰。