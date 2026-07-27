| 参数 | 说明 | 推荐值（示例） |
|------|------|----------------|
| `-m, --model FNAME` | **必需**：本地 GGUF 模型文件路径（离线加载） | `-m ./models/llama-3.1-8b-instruct.Q4_K_M.gguf` |
| `-c, --ctx-size N` | 上下文窗口长度（影响内存占用和长文本能力） | `-c 8192`（根据显存调整） |
| `-n, --predict N` | 单次生成最大 token 数（-1 = 无限制） | `-n 2048`（避免无限生成） |
| `-b, --batch-size N` | 逻辑批处理大小（影响吞吐量） | `-b 2048`（默认可接受） |
| `-ub, --ubatch-size N` | 物理批处理大小（影响并发响应速度） | `-ub 512`（推荐 ≤ 1/4 batch） |
| `-ngl, --gpu-layers N` | **关键**：加载到 GPU 的层数（-1 = 全部） | `-ngl 99`（NVIDIA 显卡）或 `-ngl 40`（低显存） |
| `-dev, --device <dev1,dev2,..>` | 指定 GPU 设备（如 `cuda:0`、`metal:0`） | `-dev cuda:0`（Linux/Windows）或 `-dev metal:0`（Mac） |
| `-sm, --split-mode {none,layer,row}` | 多 GPU 拆分模式（单卡用 `none`） | `-sm layer`（多卡）或 `-sm none`（单卡） |
| `-ts, --tensor-split N0,N1,N2,...` | 多 GPU 间张量分配比例（如 2:1） | `-ts 3,1`（4GB 显存分 3:1） |
| `-mg, --main-gpu INDEX` | 主 GPU 索引（用于 KV 缓存或单卡） | `-mg 0` |
| `-ctk, --cache-type-k TYPE` | KV 缓存 K 的数据类型（节省显存） | `-ctk q4_0`（推荐）或 `f16`（精度高） |
| `-ctv, --cache-type-v TYPE` | KV 缓存 V 的数据类型 | `-ctv q4_0`（与 K 一致） |
| `--mlock` | 锁定模型到物理内存，防止交换（已并入 `--load-mode` 的 `mlock` / `mmap+mlock`） | `--load-mode mlock`（或 `--load-mode mmap+mlock`） |
| `--load-mode` | 模型加载模式：none / mmap / mlock / mmap+mlock / dio | `--load-mode mmap`（默认；异常时尝试 `none` 或 `dio`） |
| `-t, --threads N` | CPU 推理线程数（建议设为物理核数） | `-t 8`（8核CPU） |
| `-tb, --threads-batch N` | 批处理/提示处理线程数（可与 `--threads` 相同） | `-tb 8` |
| `-np, --parallel N` | 并发请求数（同时服务的对话数） | `-np 4`（根据显存调整） |
| `--kv-unified, -kvu` | 使用统一 KV 缓存（节省显存，适合多用户） | `--kv-unified`（推荐） |
| `--swa-full` | 启用完整 SWA 缓存（长上下文优化，需更多显存） | `--swa-full`（如 ctx > 8k 且显存足够） |
| `--rope-scaling {none,linear,yarn}` | RoPE 缩放方式（用于扩展上下文） | `--rope-scaling linear`（如模型支持） |
| `--rope-scale N` | RoPE 上下文扩展因子（如模型训练于 4k，扩展到 32k） | `--rope-scale 8`（4k → 32k） |
| `--rope-freq-base N` | RoPE 基频（通常无需修改） | 保留默认（从模型加载） |
| `--lora FNAME` | 加载本地 LoRA 适配器（离线使用） | `-lora ./loras/my-lora.gguf`（如有） |
| `--lora-scaled FNAME SCALE` | 带缩放的 LoRA（如用于微调控制） | `-lora-scaled ./loras/my-lora.gguf 0.7` |
| `--control-vector FNAME` | 加载本地控制向量（如有） | `--control-vector ./cv/my-cv.gguf` |
| `--control-vector-layer-range START END` | 控制向量应用层范围 | `--control-vector-layer-range 10 25` |
| `--cpu-moe` | 将 MoE 模型所有专家权重保留在 CPU（节省显存） | `--cpu-moe`（适用于 MoE 模型如 Mixtral） |
| `--n-cpu-moe N` | 仅将前 N 层 MoE 保留在 CPU | `--n-cpu-moe 8` |
| `--no-kv-offload` | 禁用 KV 缓存卸载（避免性能波动） | `--no-kv-offload`（推荐在稳定环境使用） |
| `--no-repack` | 禁用权重重打包（节省启动时间，可能降低性能） | `--no-repack`（可选，调试用） |
| `--no-host` | 绕过主机缓冲区（高级优化，一般不用） | 保留默认 |
| `--check-tensors` | 检查模型文件完整性（首次部署建议启用） | `--check-tensors`（仅首次运行） |