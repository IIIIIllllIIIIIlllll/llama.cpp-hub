# Extra Notes

## Multimodal

By default, the app auto-detects mmproj files in the same directory as the GGUF file (e.g., `Qwen3.6-27B/Qwen3.6-27B.gguf` will look for mmproj in the same directory). If found, the frontend marks the model as multimodal with vision capabilities.

However, if you have multiple fine-tuned variants sharing the same architecture (e.g., `Qwen3.6-27B-PowerEdition.gguf`), they can reuse the same mmproj file. The "same directory" approach would require copying the mmproj for each variant. That's why there's an additional mmproj picker in the load parameters — you can point to any mmproj file manually.

## MTP

llama.cpp lets you do Multi-Token Prediction in two ways:

1. **Models with MTP layers baked in** — load and go, zero fuss
2. **Loading MTP layers separately** — point the "draft model" parameter at a standalone GGUF file containing only MTP layer weights

Why bother with option 2? Because most fine-tune authors don't bother quantizing MTP-enabled versions. So if you want MTP on your favorite shiny variant, you'll have to extract the layers from the original and load them alongside. Whether a variant + original MTP layers actually plays nice together? High-quality answer: beats me.

## System Info Tool

The system info page uses gpu-info to collect hardware information (CPU, memory, disks, GPUs, etc.). The binary is located in the `classes/tools/easy-tools/` directory.

The source code is available at: https://github.com/IIIIIllllIIIIIlllll/Easy-GPU-Tools
The binary is built via GitHub CLI — theoretically no security risk (you can inspect the code). I ship the precompiled binary in this project to avoid mixing Java + C compilation in the build pipeline.
However, false positives and antivirus flags on Windows are still unavoidable. I have no control over that — if you have any suggestions, please let me know.

## Auto-Load Models

When enabled, if an API request (e.g., `/v1/chat/completions`) targets a model that is not currently loaded, the server will automatically execute the following steps:

**① Policy Check:** Confirm the model's auto-load policy is set to "allowed". If not, return 404 immediately.

**② Hardware Resource Check:** Fetch available system memory via `gpu-info`, estimate model VRAM requirements via `llama-fit-params`. Tensor parallel mode only checks VRAM; other modes check the combined total of RAM + VRAM. Requires available memory ≥ estimated value × 1.1 (10% headroom). Any check failure will reject the load.

> ⚠ **Important Warning:** `llama-fit-params` cannot account for the memory usage of the following components:
> - Multimodal (mmproj): visual projector models
> - Draft models (Speculative Decoding)
> - MTP (Multi-Token Prediction)
>
> If any of the above features are enabled, the hardware resource check results will be significantly inaccurate. Actual memory usage will exceed the estimate, potentially causing OOM. Use these features with extreme caution and always manually reserve sufficient memory.

**③ Auto-Load:** Asynchronously load the model using the saved launch config (the config matching `selectedConfig` in `launch_config.json`).

**④ Blocking Wait:** Poll until the model finishes loading, with a max timeout of `autoLoadTimeoutMs` (default 120 seconds). On timeout, the current request is abandoned, but the background load process is not interrupted.

**⑤ Forward Request:** Once the model loads successfully, forward the original API request to it.

- **Duplicate Request Handling:** When multiple requests trigger auto-load for the same model simultaneously, only the first one submits the load task; the rest wait for the same load to complete.
- **Models are never auto-unloaded.** Unloading must always be done manually by the user.

> **Note:** You need to set a valid launch config for the model to enable auto-load mode, otherwise the system won't find a usable config when trying to auto-load.

Use at your own risk.

## Model Paths

1. By default, the program auto-scans the `models/` directory at the root — no need to add it manually
2. Don't put different models in the same folder. Give each model its own folder. For multimodal models, keep the mmproj file in the same folder too
3. **Unique model folder names**: The program uses each model's folder name as its unique identifier (modelId). Avoid having identical folder names under different paths. For example, `D:\Models\Qwen3.6-27B\model.gguf` and `D:\llama.cpp-hub\models\Qwen3.6-27B\aaa.gguf` will cause modelId conflicts and break external client calls. Ensure every model folder name is unique across all search paths.
4. That's it for now.

## Usage Statistics

1. Only **successful responses** are counted. If a request is interrupted, those tokens won't be recorded
2. Remote node usage is NOT tracked. If you burn through 1M tokens on node A, then aggregate node A under node B, node B will show 0 usage for those tokens
3. That's it for now.

## Disk & Memory Usage

### Memory

This app is built with Java, which means JVM — the notorious memory hog — is unavoidable. Memory usage has been optimized as much as possible. The JVM default heap is only 96MB (will be reduced further in the future; the author personally runs on 64MB).

The trade-off: when request payloads get too large, they spill to disk. In other words, if someone decides to be creative and submits some ridiculously oversized requests, the app will buffer them to disk rather than let JVM memory explode.

### Disk

The app itself isn't that big, but llama.cpp can be — especially the CUDA and ROCm prebuilt packages, which take up nearly 1GB once extracted. Add in the disk buffering mentioned above, and you'll want to install this app somewhere with at least **2GB** of free disk space.

**Usage statistics** don't take up much space — each request record is only 55 bytes. A million request logs come out to about 55MB. For a locally deployed model, reaching a million requests would take forever, so this is not a concern.

**System logs** may consume a bit more space, but they're retained for a maximum of 7 days. If that's too long, you can adjust it in `log4j.xml`.

## Remote Nodes

Aggregate multiple llama.cpp-hub instances into one unified management view. Master-slave architecture merges model lists and running status from multiple servers into the master node's frontend.

**Node Roles:**
- **Master**: Manages all slave nodes' WebSocket connections, runs 30s health checks, relays logs and events
- **Slave**: A standalone ordinary node — it does not initiate remote connections or health checks, but can itself be aggregated by another master node. Default (unset `nodeRole`) = slave

**Remote Routing (3-layer lookup):**
1. Explicit `nodeId` in request body → direct route
2. Locally loaded model → handle locally
3. Full-node fallback → iterate all enabled remote nodes, query `/v1/models`

**WebSocket Event Relaying:**
- Console logs, model load/stop events, model busy state from remote nodes are automatically relayed to the master frontend
- Remote log lines show `[nodeId/modelId]` prefix
- Frontend model list supports node filter dropdown (All / Local / Remote)

**Configuration (all done via the web UI):**
- **Switch role**: WebUI → System Settings → Nodes tab → "Node Role" dropdown at the top → pick Master / Slave → click **Save** → **restart the service to apply**
  - When switched to Slave, any existing remote node configs are **frozen** — kept read-only and visible, but no WebSocket connections are established and they don't participate in health checks or routing; switching back to Master restores them
  - The role choice only writes the `"nodeRole"` field in `application.json`; the runtime health-check scheduler and WebSocket clients are not started/stopped on the fly, hence the restart requirement
- **Add / edit / remove nodes**: same Nodes tab in settings — requires this node's role to be Master
- **Node info**: `nodeId`, `name`, `baseUrl`, optional `apiKey` — all added via the panel
- Node list is stored separately in `config/nodes.json`, decoupled from role config

## Multi-Protocol API

| Protocol | Port | Status |
|----------|------|--------|
| OpenAI | 8080 | ✅ |
| Anthropic | 8080 | ✅ |
| Ollama | 11434 | ⚠️ Off by default, enable manually |
| LM Studio | 1234 | ⚠️ Off by default, enable manually |

**LM Studio compatibility note:** Only the following endpoints are implemented:

| Status | Endpoint | Description |
|--------|----------|-------------|
| ✅ | `/api/v0/models` | Model list |
| ✅ | `/api/v0/chat/completions` | Chat completions |
| ✅ | `/api/v0/completions` | Text completions |
| ✅ | `/api/v0/embeddings` | Embeddings |
| ✅ | `/v1/models` | OpenAI format model list |
| ✅ | `/v1/chat/completions` | OpenAI format chat completions |
| ✅ | `/v1/completions` | OpenAI format text completions |
| ✅ | `/v1/embeddings` | OpenAI format embeddings |
| ✅ | `/v1/rerank` | OpenAI format reranking |

The following LM Studio proprietary endpoints are **not compatible** and no longer maintained:

| Endpoint | Method |
|----------|--------|
| `/api/v1/chat` | POST |
| `/api/v1/models` | GET |
| `/api/v1/models/load` | POST |
| `/api/v1/models/unload` | POST |
| `/api/v1/models/download` | POST |
| `/api/v1/models/download/status` | GET |
