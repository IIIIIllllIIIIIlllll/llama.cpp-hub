package org.mark.llamacpp.server;

import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.mark.llamacpp.gguf.GGUFMetaData;

/**
 * 决策模型（System One / /v1/systemone）支持的行为测试。
 * <p>
 * 1. GGUF 元数据 {arch}.decision.type 的解析：有该键则识别为决策模型，无则为普通模型；
 * 2. buildModelArchitecture 的输出：capabilities.decision=true 时 output_modalities 必须是 "decisions"，
 *    且不覆盖 embedding / text 的既有行为；
 * 3. buildDefaultCapabilities 默认包含 decision 字段（与 embedding / rerank 同组）。
 */
public class SystemOneTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        testDecisionTypeParsing();
        testArchitectureDecisions();
        testDefaultCapabilities();
        System.out.println("\n===== 结果: " + passed + " 通过, " + failed + " 失败 =====");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  ✅ " + name);
        } else {
            failed++;
            System.out.println("  ❌ " + name);
        }
    }

    /** {arch}.decision.type 解析：决策模型有值，普通模型为 null */
    private static void testDecisionTypeParsing() throws Exception {
        System.out.println("== GGUF decision.type 解析 ==");
        Path decision = writeTestGguf(true);
        Path plain = writeTestGguf(false);
        try {
            GGUFMetaData dm = GGUFMetaData.readFile(decision.toFile());
            check("决策模型元数据可解析", dm != null);
            check("decision.type 读为 openjev", dm != null && "openjev".equals(dm.getDecisionType()));
            check("架构名仍正确解析", dm != null && "qwen4exp-mtp".equals(dm.getArchitecture()));

            GGUFMetaData pm = GGUFMetaData.readFile(plain.toFile());
            check("普通模型元数据可解析", pm != null);
            check("普通模型 decision.type 为 null", pm != null && pm.getDecisionType() == null);
        } finally {
            Files.deleteIfExists(decision);
            Files.deleteIfExists(plain);
        }
    }

    /** output_modalities：decision=true → decisions；不影响既有 embedding / text 分支 */
    private static void testArchitectureDecisions() throws Exception {
        System.out.println("== 模型 architecture 输出 ==");
        java.lang.reflect.Method build = LlamaServerManager.class
                .getDeclaredMethod("buildModelArchitecture", org.mark.llamacpp.gguf.GGUFModel.class, JsonObject.class);
        build.setAccessible(true);

        JsonObject decisionCaps = new JsonObject();
        decisionCaps.addProperty("decision", true);
        JsonObject arch1 = (JsonObject) build.invoke(LlamaServerManager.getInstance(), null, decisionCaps);
        check("decision=true → output_modalities 含 decisions",
                hasOutput(arch1, "decisions"));
        check("decision=true 时不含 text", !hasOutput(arch1, "text"));

        JsonObject embeddingCaps = new JsonObject();
        embeddingCaps.addProperty("embedding", true);
        JsonObject arch2 = (JsonObject) build.invoke(LlamaServerManager.getInstance(), null, embeddingCaps);
        check("embedding=true 仍输出 embedding（无回归）",
                hasOutput(arch2, "embedding"));

        JsonObject plainCaps = new JsonObject();
        JsonObject arch3 = (JsonObject) build.invoke(LlamaServerManager.getInstance(), null, plainCaps);
        check("普通模型输出 text（无回归）", hasOutput(arch3, "text"));

        JsonObject nullArch = (JsonObject) build.invoke(LlamaServerManager.getInstance(), null, (Object) null);
        check("caps 为 null 时输出 text", hasOutput(nullArch, "text"));
    }

    /** 默认能力字段包含 decision */
    private static void testDefaultCapabilities() throws Exception {
        System.out.println("== 默认 capabilities ==");
        java.lang.reflect.Method def = LlamaServerManager.class
                .getDeclaredMethod("buildDefaultCapabilities", String.class);
        def.setAccessible(true);
        JsonObject caps = (JsonObject) def.invoke(null, "some-model");
        check("默认能力含 decision 字段", caps != null && caps.has("decision"));
        check("decision 默认为 false", caps != null && !caps.get("decision").getAsBoolean());
        check("既有字段未受影响（embedding/rerank）",
                caps != null && caps.has("embedding") && caps.has("rerank"));
    }

    private static boolean hasOutput(JsonObject arch, String value) {
        if (arch == null || !arch.has("output_modalities")) {
            return false;
        }
        JsonArray arr = arch.getAsJsonArray("output_modalities");
        for (int i = 0; i < arr.size(); i++) {
            if (value.equals(arr.get(i).getAsString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 生成一个最小可解析的 GGUF：仅含 KV 元数据，无张量。
     *
     * @param withDecision 是否写入 {arch}.decision.type
     */
    private static Path writeTestGguf(boolean withDecision) throws IOException {
        Path p = Files.createTempFile("systemone-test-", ".gguf");
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(p.toFile()))) {
            out.write("GGUF".getBytes(StandardCharsets.US_ASCII));
            writeLE32(out, 3);                       // 版本
            writeLE64(out, 0);                       // 张量数
            writeLE64(out, withDecision ? 3 : 2);    // KV 数
            writeKvString(out, "general.architecture", "qwen4exp-mtp");
            writeKvString(out, "general.name", "SystemOne Test Model");
            if (withDecision) {
                writeKvString(out, "qwen4exp-mtp.decision.type", "openjev");
            }
        }
        return p;
    }

    private static void writeKvString(DataOutputStream out, String key, String value) throws IOException {
        writeString(out, key);
        writeLE32(out, 8);   // GGUF 类型 8 = string
        writeString(out, value);
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        writeLE64(out, b.length);
        out.write(b);
    }

    private static void writeLE32(DataOutputStream out, long v) throws IOException {
        out.write((int) (v & 0xFF));
        out.write((int) ((v >> 8) & 0xFF));
        out.write((int) ((v >> 16) & 0xFF));
        out.write((int) ((v >> 24) & 0xFF));
    }

    private static void writeLE64(DataOutputStream out, long v) throws IOException {
        for (int i = 0; i < 8; i++) {
            out.write((int) ((v >> (8 * i)) & 0xFF));
        }
    }
}
