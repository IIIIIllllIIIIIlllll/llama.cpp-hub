package org.mark.llamacpp.server.channel;

import java.util.Set;

public class LlamaApiPathRegistry {

    /**
     * 所有已知的固定 API 路径（不含 /api/ 和 /v1 开头路径）。主要是用来判断是不是llama.cpp相关的API。
     * 新增固定端点时需要同步添加到此清单。
     */
    public static final Set<String> EXACT_PATHS = Set.of(
        "/infill",
        "/tokenize",
        "/apply-template",
        "/session",
        "/llama.cpp/props",
        "/llama.cpp/tools",
        "/llama.cpp/models/load",
        "/llama.cpp/models/unload",
        "/models",
        "/chat/completion",
        "/completions",
        "/embeddings",
        "/rerank",
        "/responses",
        "/slots"
    );

    private LlamaApiPathRegistry() {}
}
