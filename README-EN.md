# llama.cpp-hub

[🇬🇧 English](./README-EN.md) | [🇨🇳 中文](./README.md)

> **Please do not store important content in Easy Chat.**
> **Place each model in its own separate folder; if you are concerned about the `mmproj` file, place it in a separate folder as well, and simply select it via the startup parameters.**

Slapping a web UI on llama.cpp — a graphical interface to wrangle models and llama.cpp. Packed with way too many bells and whistles.

---

![image](./screenshot/laika.jpg)

---

> 💡 **PWA Supported**: This is a Progressive Web App (PWA). You can install it to your desktop/taskbar directly from the browser for a native-like experience. A standalone desktop application will not be developed.

---

## Features

### Model Management

- Load / unload GGUF models
- Multiple launch configs and sampling presets per model, swap on the fly
- Auto-detects mmproj files in the same directory, lets you pick your own draft model
- Configure multiple llama.cpp versions, pick which one to load with
- Set chat templates, customize kwargs
- Embedding and reranking models need to be manually enabled on the load page

### The Janky Chat

- A very crude chat frontend. Gets the job done. Barely.
- MCP support is there. Nobody's actually gonna use it though... right? Right?? **RIGHT?!**
- It can also display llama.cpp performance metrics
- You can set background images and assistant avatars
- Oh, it can also show hardware status
- You can input audio and video — truly full of bells and whistles

### Multi-Protocol API

One backend, four compatible APIs (OpenAI, Anthropic, Ollama, LM Studio). Point your favorite SDK at it and you're off. [Ports and compatibility details](./NOTES-EN.md#multi-protocol-api)

**⚠️Considering removing Ollama and LM Studio compat APIs — I don't think they're useful.**

### Web Admin Panel

Desktop is the main squeeze — fully featured. Mobile ⚠️ chronically under-maintained, expect UI jank.

- Model list + realtime status
- Parameter tuning (sampling presets, chat templates, slot states)
- WebSocket live logs
- Usage stats (token consumption, inference speed)
- Quick & dirty benchmark — brute-force shove a ton of context in and see when it finally surrenders
- llama-bench benchmark — you'll actually need to know what you're doing for this one. Good luck.
- Model cloning — run two instances of the same model (identical GGUF file) with different parameters, somewhat useful
- Model search — due to China's network environment it's not very practical, but works fine if you have a good connection
- System info — check your hardware resources, nothing much to say about it

### Remote Nodes (Service Aggregation)

Aggregate multiple llama.cpp-hub instances deployed on different servers into one unified management view. [Details](./NOTES-EN.md#remote-nodes)

> **Note:** Avoid using identical model names/IDs across different nodes. When external clients call the master node's `/v1/*` APIs (e.g., `/v1/chat/completions`), if multiple nodes have models with the same name, the system cannot determine which node to route to, resulting in call errors. Use distinct model names across nodes, or explicitly specify `nodeId` in the request body.

---

### Built-in MCP 🧪

Use it to test whether your model can actually execute tool calls. The MCP server listens on port **8075** (enable it in settings).

**These tools aren't very useful. Their purpose is questionable. Just pretend they don't exist. Occasionally you can use them to connect an Agent for checking hardware resource info — purely for show.**

#### MCP Client Config Example

```json
{
  "mcpServers": {
    "llama_hub_info": {
      "url": "http://localhost:8075/mcp/llama_hub_info",
      "transportType": "streamable-http"
    }
  }
}
```

---

### Download Manager ⚠️

- HTTP download with resume support. The backend implementation is pretty basic. For bulk downloading, use aria2, IDM, or something that wasn't cobbled together in a weekend.
- I mainly use this thing to shuttle models around the LAN. Occasional online model downloads are just a bonus.
- It's not just for GGUF downloads — it can download anything, so it can serve as a 'file transfer assistant' in certain situations.

---

### Online Updates

Auto-check GitHub Release → download the update package → unzip and replace. Since it pokes GitHub's API, expect random connectivity issues and 403s for... *reasons*.
If the auto-update fails, just download the package manually and overwrite — **remember to restart**.

---

## Target Audience

- SSHing into remote servers is a pain and you want an easier way
- You have multiple machines running llama.cpp and want one unified dashboard
- You enjoy compiling llama.cpp yourself but hate the chaos of managing multiple versions lying around
- You can never remember llama.cpp's mountain of parameters but still want to use them

---

## Quick Start

1. Download a Release package that includes llama.cpp, unzip
2. Each GGUF model goes in its own folder, e.g.: `models/Qwen3.5-27B-Q8_0/Qwen3.5-27B.gguf`. Folder names are up to you.
3. Run the startup script: `.bat` on Windows, `.sh` on Linux
4. Open `http://localhost:8080` in your browser
5. Pick a model on the page → click Load → go nuts
6. If it doesn't start, port 8080 might be in use. Make sure it's available before running.

> **Important**: All models are accessible through port 8080 (default). When using external clients, simply point them to this port — no need to check individual llama.cpp process ports!
>
> **Tip**: You can enable "Auto-Load" for a model. Models with auto-load enabled will appear in `/v1/models` and can be quickly loaded/stopped in Easy Chat. See the [Auto-Load Models](./NOTES-EN.md#auto-load-models) section in Extra Notes for details.

---

## Notes

> **Important**: This app needs file read/write permissions. Without them, the web UI won't load and nothing will work. For example, Windows 11's C: drive root will lock you out.

> **Heads up**: Each model needs its own folder. Keep GGUF files (shards, mmproj, etc.) for one model in one folder — don't mix different models. Models only show up in `/v1/models` after they're loaded.

> **PS**: The UI supports Chinese and English. It auto-switches based on your browser language. You can also force it with `?lang=en` in the URL.

> **Note**: The `/v1/models` API endpoint only returns **running models** and **models with auto-load enabled**. My personal take: if unloaded models showed up in the list, clients would think they're all available, only to find none of them work — and that's just silly. Models with auto-load enabled will attempt to load themselves when called, which is no big deal.



## Extra Notes

- **Multimodal** — [Auto-detects mmproj, manual override available](./NOTES-EN.md#multimodal)
- **MTP** — [Two usage methods explained](./NOTES-EN.md#mtp)
- **System Info Tool** — [gpu-info tool explanation](./NOTES-EN.md#system-info-tool)
- **Auto-Load Models** — [Auto-load mechanism details](./NOTES-EN.md#auto-load-models)
- **Model Paths** — [Model directory conventions](./NOTES-EN.md#model-paths)
- **Usage Statistics** — [Only successful responses counted](./NOTES-EN.md#usage-statistics)
- **Disk & Memory Usage** — [JVM and llama.cpp resource usage](./NOTES-EN.md#disk--memory-usage)

---

## Port Layout

| Port | Purpose |
|------|---------|
| 8080 | WebUI + OpenAI/Anthropic API + WebSocket |
| 8081+ | Inference process for each loaded model (auto-assigned) |
| 11434 | Ollama compatible API (removed) |
| 1234 | LM Studio compatible API (removed) |
| 8075 | MCP server (optional) |

> ## ⚠️ Security Disclaimer
> 
> **This application is a personal tool designed for local area network (LAN) use only. It does NOT provide internet-grade security.**
> 
> The application listens on `0.0.0.0` by default. If you set up port forwarding on your router or use NAT tunneling to expose it to the public internet, please be aware:
> 
> - This application has **no** comprehensive authentication, authorization, or attack prevention mechanisms
> - Even with API key authentication enabled (`security.apiKeyEnabled`), it is only basic access control and insufficient against malicious attacks
> - Exposing it to the public internet may lead to model service abuse/theft, server resource exhaustion, data leakage (chat records, etc.)
> 
> **Strongly recommended:**
> - Use only within a trusted local network
> - If public access is required, use a reverse proxy (e.g., Nginx) with HTTPS, access control, rate limiting, etc.
> - Do not directly expose port 8080 or any other service port to the public internet
> 
> **You assume all responsibility for any loss or damage resulting from port forwarding or public exposure of this application.**

---

## Tech Stack

- Backend: Java 21 + Netty 4.1
- Frontend: Vanilla JS (no frameworks, no bundlers — we like it raw)
- Models: llama.cpp subprocesses (one process per model — they don't share rooms)

---

## AI Tool Usage Acknowledgement

As an individual developer outside the internet industry, I don't have much energy for pure manual development in my spare time. AI solves this problem well — I just need to use the simplest technical solutions with plenty of manual review.

The tech stack here is very simple, so AI-assisted development works just fine. Especially since I'm not aiming for deep functionality — being a shell for launching llama.cpp is good enough.

I've heavily used **Qwen3.6-27B-FP8** for planning and writing code, followed by **DeepSeek V4 Flash**. Early on I also used GPT 5.2 through GPT 5.4, but for a simple project like this, that felt like overkill.

Qwen3.6-27B-FP8 is my savior, it helped me do a tremendous, tremendous, tremendous, tremendous amount of work! Later switched to Q8, vLLM is too much hassle.

The invincible Qwen3.6-27B and its useless master.

Easy Chat was such a bloated mess that I was forced to bring in Kimi K2.7 + GLM 5.2, and also used ChatGPT 5.5 a few times.

---

## Build Instructions

### Manual Build
- Make sure to update the `JAVA_HOME` path in the scripts to your actual path
- Windows: run `javac-win.bat`
- Linux: run `javac-linux.sh`

### Notes

- Make sure `JAVA_HOME` points to JDK 21 or later
- Windows uses CRLF (`\r\n`) line endings, Linux uses LF (`\n`). Watch out when editing scripts across platforms.
- If the build scripts give you trouble, you can also import it as a Maven project in your IDE, or just grab a Release package.

---

## Screenshots
![image](./screenshot/1.png)
![image](./screenshot/2.png)
![image](./screenshot/3.png)
![image](./screenshot/4.png)
![image](./screenshot/5.png)
![image](./screenshot/6.png)
![image](./screenshot/7.png)
![image](./screenshot/8.png)
![image](./screenshot/9.png)
![image](./screenshot/10.png)
![image](./screenshot/11.png)