# 命令行模式：环境变量支持方案

## 1. 背景

### 1.1 新增功能：命令行模式

前端 `loadModelModal` 弹窗右侧栏新增了一个开关按钮组，允许用户在 **表单配置**（现有的动态参数表）和 **命令行**（自由输入文本框）之间切换：

- **表单模式**：后端遍历所有启用的表单字段，逐一拼接 `--flag value`
- **命令行模式**：用户直接输入或粘贴完整命令行参数，`payload.cmd` 原样提交

### 1.2 问题

用户实际使用的命令行经常带有**前置环境变量**，例如：

```
CUDA_SCALE_LAUNCH_QUEUES=4 TURBO_AUTO_ASYMMETRIC=0 LD_LIBRARY_PATH=/mnt/data/llama.cpp-MTP-TurboQuant/build/bin /mnt/data/llama.cpp-MTP-TurboQuant/build/bin/llama-server -m ...
```

当前后端的处理链路不支持这类语法——`KEY=VALUE` 会被当成普通参数传给进程，而不是设置为环境变量。

---

## 2. 需求描述

### 2.1 功能目标

允许用户在命令行模式文本框内，在参数前写入零个或多个 `KEY=VALUE` 格式的环境变量声明，后端在启动 llama-server 进程时将这些变量注入到进程环境变量中。

### 2.2 输入示例

```
CUDA_SCALE_LAUNCH_QUEUES=4 TURBO_AUTO_ASYMMETRIC=0 --ctx-size 262144 --mlock -np 1 --port 8081
```

预期行为：

| Token | 处理方式 |
|-------|----------|
| `CUDA_SCALE_LAUNCH_QUEUES=4` | 识别为环境变量，注入进程环境，从 args 中移除 |
| `TURBO_AUTO_ASYMMETRIC=0` | 同上 |
| `--ctx-size 262144 ...` | 保持不变，仍作为命令行参数 |

### 2.3 特殊场景：`LD_LIBRARY_PATH`

后端已有逻辑在 `LlamaCppProcess.start()` 中自动构造 `LD_LIBRARY_PATH`（包含 `llamaBinPath` + ROCm 库路径等）。如果用户在命令行中也指定了 `LD_LIBRARY_PATH`，应**合并**而非覆盖：

- 用户值在前，后端值在后，用系统路径分隔符拼接
- Linux：`{user_value}:{backend_ld_path}`
- Windows：`{user_value};{backend_path}`

### 2.4 优先级规则

| 环境变量 | 来源 | 优先级 |
|----------|------|--------|
| `LD_LIBRARY_PATH` | 用户 + 后端合并 | 用户前缀 + 后端追加 |
| `PATH` (Windows) | 后端自动处理 | 不受用户影响 |
| 其他 (CUDA_SCALE_LAUNCH_QUEUES 等) | 用户 | 用户值直接覆盖 |

### 2.5 边界情况

- **无环境变量**：行为不变
- **值含空格**：如 `MY_VAR=some value with spaces`，需配合引号解析。`splitCommandLineArgs()` 已支持引号
- **含有等号但不是环境变量**：如 `--spec-type=ngram-mod`（以 `--` 开头，不应被识别为环境变量）
- **环境变量出现在参数中间或结尾**：只处理**开头连续**的 `KEY=VALUE` tokens，遇到第一个非环境变量 token 后停止扫描。这是为了与 shell 惯例一致（环境变量通常放在命令前）

---

## 3. 当前代码链路分析

### 3.1 前端 → 后端完整路径

```
前端 payload.cmd ("--ctx-size 4096 ...")
    │
    ▼
ModelActionController.java  line 1017
    JsonUtil.getJsonString(obj, "cmd", "")
    │
    ▼
LlamaServerManager.java  line 1059
    loadModelAsyncFromCmd(modelId, llamaBinPath, device, mg,
        enableVision, cmd, extraParams, chatTemplateFilePath, sourceModelId)
    │
    ▼  executorService.submit() — 异步
    │
LlamaServerManager.java  line 1972
    loadModelInBackgroundFromCmd()
        │
        ├── buildCommandStr()          ← 拼接完整命令字符串
        │    line 2204-2297
        │
        ├── new LlamaCppProcess(...)
        │
        ├── process.start()            ← 关键修改点
        │    line 2028
        │
        └── latch.await(10min)         ← 等待就绪
```

### 3.2 `buildCommandStr()`（LlamaServerManager.java:2204）

将 `cmd` 原样追加到框架参数之后，不做任何 env var 处理：

```java
// line 2248-2253
if (cmd != null && !cmd.trim().isEmpty()) {
    String processed = splitSpecType(cmd.trim());
    processed = ParamTool.stripFlagWithValue(processed, "--alias");
    sb.append(' ');
    sb.append(processed);
}
```

生成的完整命令字符串示例：

```
/path/llama-server -m model.gguf --port 8081 --device 0 CUDA_SCALE_LAUNCH_QUEUES=4 TURBO_AUTO_ASYMMETRIC=0 --ctx-size 262144 --mlock ... --host 0.0.0.0
```

注意：`CUDA_SCALE_LAUNCH_QUEUES=4` 排在 `-m`、`--port` 之后，但**不会影响识别**，因为 env var 提取是在 `splitCommandLineArgs()` 之后、`ProcessBuilder` 之前，扫描 args 列表即可。

### 3.3 `LlamaCppProcess.start()`（LlamaCppProcess.java:117-213）

当前代码（简化）：

```java
// line 123
List<String> args = splitCommandLineArgs(cmd);

// line 124
ProcessBuilder pb = new ProcessBuilder(args);

// line 133-182: 设置 LD_LIBRARY_PATH / PATH
Map<String, String> env = pb.environment();
// ... 构造 ldPathBuilder ...
env.put("LD_LIBRARY_PATH", ldPathBuilder.toString());

// line 184
this.process = pb.start();
```

### 3.4 `splitCommandLineArgs()`（LlamaCppProcess.java:616-690）

自定义解析器，非 shell：

| 支持 | 不支持 |
|------|--------|
| 空格分隔 | Shell 环境变量 `$VAR` |
| 双引号 `"..."` 分组 | 管道 `\|`、重定向 `>` |
| 单引号 `'...'` 分组 | 命令替换 `` `...` `` |
| 反斜杠转义 `\"` | 通配符 `*`、`?` |
| `=` 号包含在 token 内 | 环境变量赋值语法 |

### 3.5 现有环境变量处理（LlamaCppProcess.java:126-182）

```
Linux: LD_LIBRARY_PATH = {llamaBinPath} + ":" + ROCm路径 + ":" + 原有值
Windows: PATH 前缀 = {llamaBinPath} + ";" + ROCm路径 + ";" + CUDA路径
```

---

## 4. 修改方案建议

### 4.1 唯一修改点：`LlamaCppProcess.start()`

**文件：** `src/main/java/org/mark/llamacpp/server/LlamaCppProcess.java`
**位置：** `start()` 方法内，第 123~124 行之后、ProcessBuilder 启动之前

伪代码逻辑：

```
function start():
    // 1. 分割命令
    List<String> args = splitCommandLineArgs(cmd)

    // 2. [新增] 提取前置环境变量
    while (args 非空 && 第一个 token 包含 "=" && 不以 "-" 开头):
        token = args.remove(0)
        eq = token.indexOf("=")
        key = token.substring(0, eq)
        val = token.substring(eq + 1)

        if key == "LD_LIBRARY_PATH" && 后端已有 LD_LIBRARY_PATH:
            val = val + pathSeparator + 后端现有值
        else if key == "PATH" && platform == Windows:
            跳过（由后端统一管理）
        else:
            直接覆盖

        env.put(key, val)

    // 3. 创建 ProcessBuilder（用剩下的 args）
    ProcessBuilder pb = new ProcessBuilder(args)
    // 4. 注入所有环境变量（包括之前已有的 LD_LIBRARY_PATH 等）
    // 5. 启动
    process = pb.start()
```

### 4.2 不改的文件

| 文件 | 理由 |
|------|------|
| `index.html` | 前端已实现，无需改动 |
| `index.css` | 样式已实现，无需改动 |
| `model-action-modal.js` | 前端逻辑已实现，无需改动 |
| `LlamaServerManager.java` | `buildCommandStr()` 不变，env var 提取在 process start 阶段做 |
| `splitCommandLineArgs()` | 只需要按引号空格拆分 token，不需要感知 env var |
| `ProcessBuilder` 创建时机 | 不改变，只是中间插入 env var 提取步骤 |

### 4.3 注意事项

1. **值中的引号**：`splitCommandLineArgs` 已经帮我们拆好了带引号的 token，所以 `VAR="a b"` 得到的 token 不包含引号（`a b`），直接赋值即可
2. **`=号在值中`**：第一个 `=` 之前的是 key，之后（含后续 `=`）是 value。如 `VAR=a=b=c` → key=`VAR`, val=`a=b=c`
3. **路径分隔符**：Linux 用 `:`，Windows 用 `;`
4. **与后端已有 `LD_LIBRARY_PATH` 合并**：参考 `LlamaCppProcess.java:133-182` 的构建逻辑，在提取用户变量后追加

---

## 5. 相关文件索引

| 文件 | 关键行号 | 说明 |
|------|----------|------|
| `LlamaCppProcess.java` | 123 | `splitCommandLineArgs(cmd)` — 命令拆分 |
| `LlamaCppProcess.java` | 124 | `new ProcessBuilder(args)` — 创建进程 |
| `LlamaCppProcess.java` | 133-182 | 现有 `LD_LIBRARY_PATH` / `PATH` 构建 |
| `LlamaCppProcess.java` | 184 | `pb.start()` — 进程启动 |
| `LlamaCppProcess.java` | 616-690 | `splitCommandLineArgs()` — 分割实现 |
| `LlamaServerManager.java` | 2204-2297 | `buildCommandStr()` — 命令组装（无需改动） |
| `LlamaServerManager.java` | 1954-2144 | `loadModelInBackgroundFromCmd()` — 加载流程 |
| `ModelActionController.java` | 1016-1079 | `loadLocalModel()` — 请求入口 |
| `model-action-modal.js` | 1262-1291 | `switchParamMode()` — 前端模式切换（已实现） |
| `index.html` | 4319-4345 | 命令行模式 UI（已实现） |
