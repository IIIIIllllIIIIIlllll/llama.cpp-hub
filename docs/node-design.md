# LlamaHub 节点功能 — 详细设计文档

## 1. 概述

### 1.1 问题背景

当前项目是一个 llama.cpp 集成平台，每台机器独立部署。用户需要在不同机器的 Web 界面间切换，操作繁琐。目标是实现"节点"功能，将多台机器通过 HTTP 协议聚合到一个 Hub 节点统一管理。

### 1.2 设计决策

| 决策项 | 选择 | 理由 |
|--------|------|------|
| 通信架构 | Hub 代理模式 | 前端只需连接一个地址，实现简单，体验一致 |
| 发现方式 | 手动配置 | 局域网场景足够，避免引入 mDNS 等复杂机制 |
| 远程节点改造 | 零改造 | 直接复用现有 API，远程节点无需任何代码变更 |

### 1.3 核心概念

```
Hub 节点：配置了远程节点的实例，作为统一入口
远程节点：被 Hub 管理的其他实例，无需特殊配置
本地资源：Hub 自身运行的模型/llama.cpp
远程资源：远程节点上的模型/llama.cpp
```

---

## 2. 数据结构设计

### 2.1 `LlamaHubNode.java` — 节点数据模型

```java
package org.mark.llamacpp.server;

public class LlamaHubNode {
    String nodeId;           // 唯一标识，如 "server-gpu-a"
    String name;             // 显示名称，如 "GPU 服务器 A"
    String baseUrl;          // 远程节点地址，如 "http://192.168.1.100:8080"
    String apiKey;           // 远程节点的 API Key（可选）
    List<String> tags;       // 标签，如 ["A100", "production"]
    NodeStatus status;       // ONLINE / OFFLINE / PENDING
    long lastHeartbeat;      // 最后心跳时间戳
    long createdAt;          // 创建时间
    boolean enabled;         // 是否启用
    Map<String, Object> metadata; // 缓存的元信息（GPU、模型数等）
}

enum NodeStatus {
    ONLINE,    // 健康检查通过
    OFFLINE,   // 健康检查失败
    PENDING    // 刚添加，尚未检查
}
```

### 2.2 配置文件 `config/nodes.json`

复用 `ConfigManager` 的 JSON 持久化模式，新增 `NODES_CONFIG_FILE = "config/nodes.json"`：

```json
{
    "nodes": [
        {
            "nodeId": "server-gpu-a",
            "name": "GPU 服务器 A",
            "baseUrl": "http://192.168.1.100:8080",
            "apiKey": "",
            "tags": ["A100", "production"],
            "enabled": true
        },
        {
            "nodeId": "server-gpu-b",
            "name": "GPU 服务器 B",
            "baseUrl": "http://192.168.1.101:8080",
            "apiKey": "sk-xxx",
            "tags": ["4090"],
            "enabled": true
        }
    ]
}
```

---

## 3. 组件设计

### 3.1 `NodeManager.java` — 节点管理单例

**职责**：节点 CRUD、配置持久化、健康检查调度、元信息缓存

```
类结构：
├── 单例模式（同 ConfigManager、LlamaServerManager）
├── ConcurrentHashMap<String, LlamaHubNode> nodes  // nodeId -> 节点
├── ScheduledExecutorService scheduler              // 健康检查定时任务
├── ConcurrentHashMap<String, Object> nodeLocks     // 节点级锁
│
├── 生命周期方法
│   ├── initialize()     // 从 config/nodes.json 加载，启动定时任务
│   └── shutdown()       // 停止定时任务
│
├── CRUD 方法
│   ├── addNode(node)    // 添加节点，校验 nodeId 唯一性
│   ├── removeNode(nodeId) // 移除节点
│   ├── updateNode(nodeId, node) // 更新节点
│   ├── getNode(nodeId)  // 查询节点
│   └── listNodes()      // 列出所有节点
│
├── 健康检查
│   ├── startHealthCheck() // 启动 30s 间隔的定时任务
│   ├── healthCheck(nodeId) // 对单个节点发 GET /api/sys/version
│   └── getNodeStatus(nodeId) // 获取节点状态
│
├── 远程 API 调用
│   ├── callRemoteApi(nodeId, method, path, body) // 通用 HTTP 调用
│   ├── fetchRemoteModels(nodeId) // 调用远程 /api/models/list
│   ├── fetchRemoteLoadedModels(nodeId) // 调用远程 /api/models/loaded
│   └── fetchRemoteGpuStatus(nodeId) // 调用远程 /api/sys/gpu/status
│
└── 持久化
    └── saveNodesConfig() // 写入 config/nodes.json（复用 ConfigManager 的原子写入方式）
```

**`callRemoteApi` 方法设计**（参考 `LlamaServerManager.callLocalModelEndpoint`）：

```
输入：nodeId, HTTP method, API path, request body (JsonObject)
流程：
  1. 根据 nodeId 获取节点的 baseUrl
  2. 构建完整 URL: baseUrl + path
  3. 创建 HttpURLConnection
  4. 如果节点配置了 apiKey，添加 Authorization header
  5. 设置超时（connect: 5s, read: 30s）
  6. 发送请求，读取响应
  7. 返回 HttpResult(statusCode, body)
```

### 3.2 `NodeController.java` — 节点管理 API

遵循现有 `BaseController` 接口模式，注册到 `BasicRouterHandler` 的 pipeline 中。

| 路径 | Method | 请求体 | 响应 | 说明 |
|------|--------|--------|------|------|
| `/api/node/list` | GET | - | `ApiResponse.success(List<LlamaHubNode>)` | 列出所有节点 |
| `/api/node/add` | POST | `nodeId, name, baseUrl, apiKey?, tags?` | `ApiResponse` | 添加节点，校验 nodeId 唯一、baseUrl 格式 |
| `/api/node/remove` | POST | `nodeId` | `ApiResponse` | 移除节点 |
| `/api/node/update` | POST | `nodeId, name?, baseUrl?, apiKey?, tags?, enabled?` | `ApiResponse` | 更新节点 |
| `/api/node/test` | POST | `nodeId` | `ApiResponse.success({connected: bool, version: str, latency: int})` | 测试连通性 |
| `/api/node/status` | GET | - | `ApiResponse.success(Map<nodeId, NodeStatusInfo>)` | 批量状态查询 |
| `/api/node/info` | GET | - | `ApiResponse.success({selfNode: {...}, connectedNodes: N})` | 当前节点信息 |

### 3.3 `NodeProxyService.java` — 请求代理转发

**职责**：将客户端请求代理转发到远程节点，处理流式/非流式响应

```
类结构：
├── 单例模式
│
├── 非流式代理
│   └── proxyRequest(ctx, nodeId, method, path, body)
│       流程：
│       1. 通过 NodeManager.callRemoteApi 发送请求到远程节点
│       2. 将远程节点的响应原样返回给客户端
│       3. 保留响应状态码和 Content-Type
│
└── 流式代理（SSE）
    └── proxyStreamRequest(ctx, nodeId, method, path, body)
        流程：
        1. 打开到远程节点的 HttpURLConnection
        2. 设置 Chunked 传输
        3. 逐 chunk 读取远程响应
        4. 通过 Netty 的 DefaultHttpContent 逐块写入客户端
        5. 保持 Connection: keep-alive
        6. 远程连接断开后关闭客户端连接
```

**流式代理参考现有 `OpenAIService.forwardRequestToLlamaCpp` 的 `handleProxyResponse` 逻辑**，将 `localhost:port` 替换为远程节点的 `baseUrl`。

---

## 4. 现有 API 的聚合改造

### 4.1 改造原则

- **向后兼容**：所有现有 API 在不传 `nodeId` 参数时行为不变
- **聚合模式**：不传 `nodeId` 时，聚合本地 + 所有远程节点的数据
- **标注来源**：聚合结果中每条记录增加 `nodeId` 和 `nodeName` 字段

### 4.2 具体改造点

#### 4.2.1 `ModelActionController` — 模型管理

**`/api/models/list`**
```
改造前：只返回本地扫描的 GGUF 模型列表
改造后：
  - 带 ?nodeId=xxx：调用远程节点的 /api/models/list，结果中每条加 nodeId/nodeName
  - 不带 nodeId：聚合本地 + 所有启用节点的模型列表
  - 前端可根据 nodeId 进行分组/筛选
```

**`/api/models/loaded`**
```
改造前：只返回本地已加载的模型进程
改造后：
  - 带 ?nodeId=xxx：调用远程节点的 /api/models/loaded
  - 不带 nodeId：聚合本地 + 所有启用节点的已加载模型
  - 每条记录新增 nodeId、nodeName 字段
```

**`/api/models/load`**
```
改造前：只在本地加载模型
改造后：
  - 新增 nodeId 参数
  - 带 nodeId：通过 NodeProxyService 转发 POST 到远程节点的 /api/models/load
  - 不带 nodeId：走现有本地加载逻辑
```

**`/api/models/stop`**
```
同理，支持 nodeId 参数，转发到远程节点
```

#### 4.2.2 `ModelInfoController`

**`/api/models/openai/list`**
```
改造后聚合所有节点的 OpenAI 格式模型列表
```

**`/api/models/details`**
```
支持 nodeId 参数，查询远程节点模型详情
```

#### 4.2.3 `SystemController`

**`/api/sys/gpu/status`**
```
改造后：
  - 带 ?nodeId=xxx：查询远程节点 GPU 状态
  - 不带 nodeId：聚合所有节点的 GPU 状态，返回数组
```

### 4.3 聚合响应格式示例

```json
{
    "success": true,
    "data": [
        {
            "modelId": "llama-3-8b.Q4_K_M",
            "name": "Llama 3 8B",
            "size": 4567890123,
            "nodeId": "server-gpu-a",
            "nodeName": "GPU 服务器 A"
        },
        {
            "modelId": "qwen2.5-7b.Q4_K_M",
            "name": "Qwen2.5 7B",
            "size": 4123456789,
            "nodeId": "local",
            "nodeName": "本机"
        },
        {
            "modelId": "mistral-7b.Q4_K_M",
            "name": "Mistral 7B",
            "size": 4234567890,
            "nodeId": "server-gpu-b",
            "nodeName": "GPU 服务器 B"
        }
    ]
}
```

---

## 5. OpenAI 兼容 API 的代理转发

### 5.1 模型路由协议

在 `OpenAIService` 中修改模型查找逻辑，支持 `nodeId:modelName` 格式：

```
请求体中的 model 字段解析规则：
  1. "llama-3-8b" → 本地模型（现有行为）
  2. "server-gpu-a:llama-3-8b" → 转发到 server-gpu-a 节点的 llama-3-8b 模型
```

### 5.2 改造 `OpenAIService.handleOpenAIChatCompletionsRequest`

```
现有流程：
  解析 model → 查本地 loadedProcesses → 获取 port → 转发到 localhost:port

改造后流程：
  解析 model → 判断是否含 ":" 前缀
    ├─ 有前缀：提取 nodeId + modelName
    │   ├─ 通过 NodeManager 获取节点 baseUrl
    │   ├─ 通过 NodeProxyService.proxyStreamRequest 转发到远程节点
    │   └─ 远程节点的响应逐块代理回客户端
    └─ 无前缀：走现有本地逻辑
```

### 5.3 流式代理实现要点

参考 `OpenAIService.handleProxyResponse` 的 SSE 处理逻辑：

```
proxyStreamRequest 关键步骤：
  1. 打开到远程节点的 HttpURLConnection（设置超时、headers）
  2. 发送 POST body
  3. 读取远程响应头，设置 Content-Type: text/event-stream
  4. 向客户端写入初始 HttpResponse（不关闭连接）
  5. 逐 chunk 读取远程 InputStream
  6. 每个 chunk 通过 DefaultHttpContent 写入 Netty ctx
  7. 远程返回完成后写入 LastHttpContent.EMPTY_LAST_CONTENT
  8. 延迟关闭客户端连接
```

### 5.4 改造范围

需要改造的 `OpenAIService` 方法：
- `handleOpenAIChatCompletionsRequest` — Chat 补全（含流式）
- `handleOpenAICompletionsRequest` — 文本补全
- `handleOpenAIEmbeddingsRequest` — 嵌入
- `handleOpenAIRerankRequest` — 重排序
- `handleOpenAIResponsesRequest` — Responses API
- `handleOpenAIModelsRequest` — 模型列表（聚合）

---

## 6. 健康检查机制

### 6.1 定时任务

`NodeManager` 中使用 `ScheduledExecutorService`，每 30 秒执行一轮：

```
healthCheckRound():
  for each enabled node:
    async execute:
      try:
        response = GET node.baseUrl + "/api/sys/version"
        if response.success:
          node.status = ONLINE
          node.lastHeartbeat = now()
          node.metadata = response.data  // 缓存版本信息等
        else:
          node.status = OFFLINE
      catch exception:
        node.status = OFFLINE
```

### 6.2 状态变化事件

节点状态变化时，通过 `WebSocketManager` 广播：

```json
{
    "type": "node_status_changed",
    "nodeId": "server-gpu-a",
    "nodeName": "GPU 服务器 A",
    "status": "ONLINE",
    "timestamp": 1713980000000
}
```

---

## 7. WebSocket 扩展

在 `WebSocketManager` 中新增事件类型：

| 事件 type | 触发时机 | 数据 |
|-----------|----------|------|
| `node_status_changed` | 节点上线/下线 | nodeId, nodeName, status |
| `node_models_update` | 定时刷新远程模型列表 | nodeId, modelCount |
| `systemStatus`（扩展） | 每 60 秒系统状态 | 新增 `nodes` 字段：节点数量和在线数 |

---

## 8. 文件清单

### 8.1 新增文件

| 文件路径 | 说明 | 行数预估 |
|----------|------|----------|
| `server/NodeManager.java` | 节点管理单例 | ~350 |
| `server/controller/NodeController.java` | 节点管理 API | ~250 |
| `server/service/NodeProxyService.java` | 请求代理转发 | ~200 |

### 8.2 修改文件

| 文件路径 | 修改内容 |
|----------|----------|
| `server/LlamaHubNode.java` | 充实为完整的数据模型 + enum |
| `server/ConfigManager.java` | 新增 `NODES_CONFIG_FILE` 常量，新增 `loadNodesConfig()` / `saveNodesConfig()` |
| `server/LlamaServer.java` | `main()` 中初始化 `NodeManager` |
| `server/channel/BasicRouterHandler.java` | pipeline 中新增 `NodeController` |
| `server/service/OpenAIService.java` | 改造模型路由逻辑，支持 `nodeId:` 前缀 |
| `server/controller/ModelActionController.java` | 聚合改造（models/list, loaded, load, stop） |
| `server/controller/ModelInfoController.java` | 聚合改造（openai/list, details） |
| `server/controller/SystemController.java` | 聚合改造（gpu/status） |
| `server/websocket/WebSocketManager.java` | 新增节点事件广播方法 |

---

## 9. 安全考虑

1. **API Key 透传**：Hub 转发请求时，将节点的 `apiKey` 通过 `Authorization: Bearer` header 传递给远程节点
2. **配置加密**：`config/nodes.json` 中的 `apiKey` 明文存储（与现有 `application.json` 中的 `apiKey` 处理方式一致）
3. **HTTPS 支持**：如果远程节点启用 HTTPS，`baseUrl` 直接使用 `https://` 前缀即可
4. **超时控制**：远程 API 调用设置合理的超时时间（connect: 5s, read: 30s），防止阻塞

---

## 10. 实现顺序

| 步骤 | 内容 | 依赖 |
|------|------|------|
| 1 | `LlamaHubNode.java` 数据结构 | 无 |
| 2 | `ConfigManager` 新增节点配置读写 | 1 |
| 3 | `NodeManager.java` 实现 | 1, 2 |
| 4 | `NodeController.java` 实现 | 3 |
| 5 | `BasicRouterHandler` 注册 NodeController | 4 |
| 6 | `LlamaServer.main()` 初始化 NodeManager | 3 |
| 7 | `ModelActionController` 聚合改造 | 3 |
| 8 | `NodeProxyService.java` 流式代理 | 无 |
| 9 | `OpenAIService` 模型路由改造 | 3, 8 |
| 10 | `WebSocketManager` 事件扩展 | 3 |
| 11 | 健康检查定时任务 | 3, 10 |

---

## 11. 架构总览

```
                    ┌─────────────────────────────┐
                    │         Hub 节点 (主)         │
                    │        Port: 8080            │
                    │                              │
                    │  ┌────────────────────────┐  │
                    │  │      NodeManager       │  │
                    │  │  节点注册/心跳/健康检查  │  │
                    │  └───────────┬────────────┘  │
                    │              │               │
                    │  ┌───────────▼────────────┐  │
                    │  │    NodeController      │  │
                    │  │   /api/node/* 管理API   │  │
                    │  └───────────┬────────────┘  │
                    │              │               │
                    │  ┌───────────▼────────────┐  │
                    │  │   NodeProxyService     │  │
                    │  │   请求路由/聚合/转发    │  │
                    │  └───────────┬────────────┘  │
                    └──────────────┼───────────────┘
                                   │ HTTP 代理
              ┌────────────────────┼────────────────────┐
              │                    │                    │
        ┌─────▼────────┐   ┌──────▼────────┐   ┌──────▼────────┐
        │  Remote A     │   │   Remote B    │   │   Remote C    │
        │ 192.168.1.100 │   │ 192.168.1.101 │   │ 10.0.0.50     │
        │ 模型: 7B      │   │ 模型: 70B     │   │ 模型: 8B      │
        │ (零改造)       │   │ (零改造)       │   │ (零改造)       │
        └───────────────┘   └───────────────┘   └───────────────┘
```

---

*设计文档版本：v1.0*
*日期：2026-04-24*
