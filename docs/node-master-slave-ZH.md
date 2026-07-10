# 主从节点（Master / Slave）开发参考

> 本文档基于代码现状整理，供后续开发直接参照，无需重新分析代码。
> 适用版本：nodeRole 已接入前端设置页，运行时仅持久化、需重启生效。

---

## 1. 概念一句话

本程序有两种节点角色，由顶层配置 `application.json` 的 `nodeRole` 字段控制：

- **主节点（master）**：聚合多个远程 llama.cpp-hub 节点到统一入口，负责远程节点的健康检查、WebSocket 订阅、远程 API 代理转发。
- **从节点（slave）**：普通单机节点。不参与远程节点的连接/调度，但其自身可被别的主节点聚合。
- 关键事实：**任何非 `"master"` 的值（null / 空串 / `"slave"` / 其它）一律视为从节点**。

判定逻辑见 `LlamaServer.isMasterNode()`：`nodeRole != null && "master".equalsIgnoreCase(nodeRole)`。

---

## 2. 配置文件：`config/application.json`

`nodeRole` 是 JSON 顶层字符串字段，例：

```json
{
  "nodeRole": "master",
  "server": { "webPort": 8080 },
  ...
}
```

- 由 `LlamaServer.loadApplicationConfig()`（启动时）读取到 `private static volatile String nodeRole`。
- 由 `LlamaServer.saveApplicationConfig()` 持久化（仅当 `nodeRole != null` 时写顶层 `nodeRole`）。
- 节点列表本身**不**存在 application.json，而是独立文件，由 `ConfigManager.loadNodesConfig()/saveNodesConfig()` 管理。

---

## 3. 后端核心代码位置

### 3.1 `org.mark.llamacpp.server.LlamaServer`
- 字段：`private static volatile String nodeRole = null;`
- 读取：`loadApplicationConfig()` 中 `if (root.has("nodeRole")) nodeRole = root.get("nodeRole").getAsString();`
- 持久化：`saveApplicationConfig()` 中 `if (nodeRole != null) root.addProperty("nodeRole", nodeRole);`
- 判定：`public static boolean isMasterNode()`
- 读写 API：
  - `public static String getNodeRole()` —— 返回原始值（可能为 null）
  - `public static void updateNodeRole(String role)` —— 规范化（空/null → `"slave"`），加 `APPLICATION_CONFIG_LOCK`，调 `saveApplicationConfig()`。⚠ **仅写配置，不改 NodeManager 运行时状态**，需重启服务生效。
- 暴露经 `/api/sys/setting`（见下）。

### 3.2 `org.mark.llamacpp.server.NodeManager`（单例）
职责：节点 CRUD、持久化、健康检查调度、远程 WebSocket 客户端管理、远程 API 调用。

**受角色控制的行为**（全部以 `LlamaServer.isMasterNode()` 为准）：
- `initialize()`：启动时若 `isMasterNode()` → 主动连各节点 WebSocket（`startAndWaitWebSocketClient`）+ 启动 30s 健康检查调度器；否则「本节点为 slave 模式，跳过远程节点连接和健康检查」。
- `listEnabledNodes()`：非 master 直接返回空 list。
- `addNode(node)` / `updateNode(...)`：仅 master 且 `enabled && baseUrl!=null` 时才 `startWebSocketClient`。
- `onNodeStatusChanged(node, old)`：非 master 直接 return；master 且 OFFLINE→ONLINE 时重连 WS。
- `shutdown()`：停所有 WS 客户端 + 停调度器。

**不受角色控制**（任何角色可用，供只读/代理）：
- `listNodes()`：返回全部节点。
- `getNode(nodeId)`、`callRemoteApi(...)`、`callRemoteApiStreaming(...)`、`fetchRemoteModels/GpuStatus/Version` 等远程调用方法本身**不判角色**（角色控制发生在调用方 Controller / 业务层）。

> 关键设计：角色对 NodeManager 的影响集中在**启动时**（`initialize`）和**单次动作前**（`addNode/updateNode/listEnabledNodes`）。`nodeRole` 改了之后，已启动的健康检查调度器和已建立的 WS 客户端**不会因运行时改 nodeRole 而启停**——这是"需重启生效"的根因。

### 3.3 `org.mark.llamacpp.server.controller.NodeController`（`/api/node/*`）
| 端点 | 方法 | 角色限制 |
|---|---|---|
| `/api/node/list` | GET | 无限制（任何角色可读，供前端只读展示） |
| `/api/node/info` | GET | 无限制；返回 `isMaster = LlamaServer.isMasterNode()`、`connectedNodes`、`onlineNodes`、`selfNode` |
| `/api/node/add` | POST | 非 master → `api.error.node.master.only` |
| `/api/node/update` | POST | 非 master → `api.error.node.master.only` |
| `/api/node/remove` | POST | 非 master → `api.error.node.master.only` |
| `/api/node/test` | POST | 非 master → `api.error.node.master.only`；返回 `{connected,version,latency,statusCode}` |
| `/api/node/status` | GET | 无限制；返回各节点 `{nodeId,name,status,lastHeartbeat,enabled}` |

写操作的 `isMaster` 守卫是后端**强校验**，前端任何绕过都无法实际落库。

### 3.4 `org.mark.llamacpp.server.controller.SystemController`（`/api/sys/setting`）
- GET（`handleSysSettingGetRequest`）：响应顶层加入 `data.put("nodeRole", nodeRole != null ? nodeRole : "slave")`。
- POST（`handleSysSettingRequest`）：解析 `nodeRole` 字段，进入变更判空集合，调 `LlamaServer.updateNodeRole(nodeRole)`。payload 例 `{ "nodeRole": "master" }`，可单独提交。

---

## 4. 数据模型 `org.mark.llamacpp.server.LlamaHubNode`

```
nodeId        唯一标识，如 "server-gpu-a"
name          显示名
baseUrl       远程节点地址，如 "http://192.168.1.100:8080"
apiKey        远程节点的 Bearer key（可选）
tags          标签 List<String>
status        枚举 ONLINE / OFFLINE / PENDING
lastHeartbeat 上次心跳时间戳
createdAt     创建时间
enabled       是否启用
metadata      缓存的远端元信息（GPU、模型数等，健康检查时更新）
```

持久化由 `ConfigManager` 独立管理（`loadNodesConfig` / `saveNodesConfig`），与 `application.json` 分离。

---

## 5. 前端

### 5.1 `src/main/resources/web/index.html` — 节点设置 tab
`<div class="settings-tab-panel" data-tab-panel="nodes">` 内顺序：
1. 角色选择器：`<select id="nodeRoleSelect">`（option: `slave`/`master`）+ `<button id="saveNodeRoleBtn">`
2. 提示 `.form-text`（i18n: `page.settings.nodes.role.hint`，"需重启生效"）
3. `<div id="nodeMasterBanner">`（非 master 提示）
4. `<div id="nodeToolbar">` 内含 `<button id="addNodeBtn" onclick="SettingsPage.openNodeForm()">`
5. `<div class="settings-toggle-list" id="nodeList">` 节点卡片容器
6. `<div id="nodeEmptyState">` 空状态

### 5.2 `src/main/resources/web/js/settings.js` — 节点管理相关
模块级变量（节点管理段）：
- `let _editingNodeId = null;`
- `let _isMaster = false;` —— 后端持久化的真实角色（来自 `/api/node/info`）
- `let _lastNodes = null;` —— 缓存 `/api/node/list` 结果，切角色不重请求

关键函数：
- `getRoleMaster()` —— **优先读 `nodeRoleSelect` 当前 UI 值**，select 不存在时回退 `_isMaster`。这是"切换未保存即生效（视觉冻结）"的依据。
- `renderNodeList()` —— 用 `_lastNodes` + `getRoleMaster()` 调 `buildNodeRow(n, isMaster)` 渲染并切换 `nodeEmptyState`。
- `applyNodeRoleState()` —— 下拉 `change` 时调用：切换 `nodeToolbar`/`nodeMasterBanner`/`addNodeBtn.disabled`，并**调 `renderNodeList()` 即时降级/恢复卡片**。
- `loadNodes()` —— 拉 `/api/node/info`（得 `_isMaster`）→ 切 toolbar/banner/addBtn → 拉 `/api/node/list` → 缓存 `_lastNodes` → `renderNodeList()`。**不再用 `_isMaster` 直接渲染**，统一走 `renderNodeList()`。
- `buildNodeRow(node, isMaster)` —— `isMaster=true`：渲染「测试/编辑/删除/启用开关」操作栏；`isMaster=false`：`actionsHtml=''` → **只读卡片**（仅名称/URL/状态/标签）。
- `saveNodeRole()` —— POST `/api/sys/setting {nodeRole}`，成功后 `loadSettings()` + `loadNodes()`（保存后用后端真实角色校正）。
- `openNodeForm(data)` —— 入口加守卫：`nodeRoleSelect.value !== 'master'` 时 toast 报错并 return（最后防线）。

绑定：
- `nodesTab`（顶部 tab）`click` → `loadNodes()`
- `nodeRoleSelect` `change` → `applyNodeRoleState()`
- `saveNodeRoleBtn` `click` → `saveNodeRole()`

导出：`window.SettingsPage = { ..., saveNodeRole, ... }`。

### 5.3 冻结语义（重要）
> 切到从节点（哪怕未保存）：已有节点卡片**即时降级为只读**——操作入口（编辑/删除/测试/启用开关）全部消失，配置原样保留可见。因后端 NodeManager 在非 master 时跳过 WS/健康检查，故"不参与工作"（冻结，但不删除）。
> 切回主节点（未保存）：操作按钮即时恢复。
> 保存后：后端 `_isMaster` 更新，`loadNodes` 校正。

---

## 6. i18n key（`/web/i18n/zh-CN.json`、`en-US.json`）

节点 tab 相关（节选，新增/改动的标注）：
- `page.settings.tab.nodes` — tab 标题
- `page.settings.nodes.hint` — tab 顶部说明
- `page.settings.nodes.list_title` —「已配置节点」
- `page.settings.nodes.empty` — 空状态
- `page.settings.nodes.not_master` — ⚠ **已改**：去掉「请在 application.json 中设置…」，改为引导用上方角色选择器切到主节点（需重启生效）
- `page.settings.nodes.role_label` —【新增】「本节点角色」
- `page.settings.nodes.role.master` —【新增】「主节点」
- `page.settings.nodes.role.slave` —【新增】「普通节点」
- `page.settings.nodes.role.hint` —【新增】「主节点聚合远程节点到统一入口，更换后需重启服务生效。」
- `page.settings.nodes.status.online/offline/pending` — 状态文案
- `page.settings.nodes.test_connectivity`、`test_success`、`test_failed`
- `modal.node.add_title` / `edit_title` / `error.*` / `confirm.node.delete`
- `api.error.node.master.only` —「当前节点不是 master 模式，无法管理远程节点」（前后端共用 key）

---

## 7. 端到端数据流

### 启动
1. `main` → `loadApplicationConfig()` 读 `nodeRole`
2. `NodeManager.initialize()` 按 `isMasterNode()` 启/跳过 WS + 健康检查

### 前端读取（进节点 tab / `load()`）
1. `/api/sys/setting` GET → `nodeRole` 回填 `nodeRoleSelect`
2. `/api/node/info` GET → `_isMaster`（banner/工具条依据）
3. `/api/node/list` GET → `_lastNodes` → `renderNodeList()`

### 改角色（未保存）
- 下拉 `change` → `applyNodeRoleState()` → 切 banner/工具条/addBtn + `renderNodeList()` 即时冻结/恢复卡片（纯前端，不碰后端）

### 保存角色
- `saveNodeRole()` → POST `/api/sys/setting {nodeRole}` → `LlamaServer.updateNodeRole` 持久化 → 前端 `loadSettings()` 回填 + `loadNodes()` 校正
- ⚠ 配置已落盘但 **NodeManager 的调度器/WS 不会运行时切换** → 需重启服务，调度器与 WS 才按新角色重新建立

### 重启后
- `loadApplicationConfig` 读最新 `nodeRole` → `NodeManager.initialize` 按新角色启动健康检查/WS（或跳过）

---

## 8. 扩展指引

### 8.1 新增第三种角色（如 "observer"）
- `LlamaServer.isMasterNode()` 是唯一判定点，若新角色有独立语义，需新增 `isXxxNode()` 并在 `NodeManager` 各 master 分支按需放开。
- 前端 `nodeRoleSelect` 加 option，`getRoleMaster`/`renderNodeList` 的二值逻辑需泛化（当前只 master/slave 二态）。
- `buildNodeRow` 的 `isMaster` 参数需改为角色权限对象。

### 8.2 想让角色运行时动态生效（无需重启）
当前不支持。要实现需在 `NodeManager` 暴露：
- `startHealthCheck()` / `stopHealthCheck()`（调度器 `scheduler` 现为 private 且只在 `initialize` 启一次）
- `startAllRemoteWebSocketClients()` / `stopAllWebSocketClients()`（已有 `stopAllWebSocketClients`，缺对应的 all-start）
- 在 `LlamaServer.updateNodeRole` 末尾按新角色调用上述启停
- 注意并发（调度器已启动时勿重复 start）、WebSocket 重连抖动
- 健康检查调度器用 `scheduleAtFixedRate`，停止用 `scheduler.shutdown()`（当前在 `shutdown()` 调），动态场景需可重启的调度器模型

### 8.3 新增远程代理端点（透传到从节点）
Pattern（参考已有的 perplexity 等）：
1. 在某 Controller 路由里拿到 `nodeId`
2. `NodeManager.getInstance().callRemoteApi(nodeId, "POST", "api/...", body)` 或 `callRemoteApiStreaming(...)`（流式 SSE 透了用）
3. 写回：成功用 `NodeManager.writeHttpResultToChannel(ctx, result, logTag)` 透传 JSON+CORS；失败按 `HttpResult.getStatusCode()` 返回错误
4. 非 master 由调用方自行拦截（看业务是否允许从节点直连远端）

### 8.4 前端给某功能加"节点选择"
- 参考 `populateSettingsNodeSelect(selectId)`：拉 `/api/node/list`，过滤 `nodeId!=='local' && enabled!==false` 填 `<select>`
- `appendNodeId(url, nodeId)`：拼 `?nodeId=xxx` 给后端做路由分发

---

## 9. 注意事项 / 已踩坑

1. **settings.js 非 UTF-8**：该文件含 GBK 编码的中文 fallback 字符串。`edit` 工具按字节读写，**编辑时优先匹配 ASCII 上下文，避免改动非 ASCII 的 fallback 文本**；只读校验用 `node --check`、`Get-Content` 但中文会乱码（属正常）。i18n JSON 是 UTF-8，可正常编辑。

2. **isMasterNode 的大小写**：`"master".equalsIgnoreCase(nodeRole)` → `"Master"`/`"MASTER"` 也算 master。`updateNodeRole` 会把空/null 规范成 `"slave"` 持久化，因此 `saveApplicationConfig` 的 `if (nodeRole != null)` 恒成立，会写入。

3. **运行时改 role 的局限**：`updateNodeRole` 改的是 `volatile nodeRole`，调用方 `isMasterNode()` 立即变（影响 banner、`listEnabledNodes`、新 `addNode` 等），但**已运行的 `scheduler` 和已连接的 `RemoteWebSocketClient` 不会自动启停**。前端 UI 提示「需重启生效」就是为了对齐这个真相。

4. **前端冻结 = 不删除**：非 master 时已有节点配置原样保留可见（只读卡片），后端节点文件不动；后端 NodeManager 在非 master 时跳 WS/健康检查 =「不参与工作」。这套语义靠 `getRoleMaster()`（读 UI 下拉框）+ `renderNodeList()` 即时重渲染实现。