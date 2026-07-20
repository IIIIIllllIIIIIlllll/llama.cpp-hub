package org.mark.llamacpp.gguf;

/**
 * GGUF 元数据读取器。
 * <p>
 * 基于 {@link GgufReader} 流式读取，替代旧的 64MB 内存映射方案
 * （内存映射在 JDK 17+ 下无法可靠 unmap，频繁调用会累积堆外内存）。
 */
public class GGUFMetaDataReader {

    public static java.util.Map<String, Object> read(java.io.File file) {
        if (file == null || !file.exists() || !file.isFile()) {
            return java.util.Collections.emptyMap();
        }
        try (GgufReader reader = new GgufReader(file)) {
            String magic = reader.readMagic();
            if (!"GGUF".equals(magic)) {
                return java.util.Collections.emptyMap();
            }
            reader.readUInt32(); // version
            reader.readUInt64(); // tensor count
            long kvCount = reader.readUInt64();
            java.util.Map<String, Object> metadata = new java.util.HashMap<>();
            for (long i = 0; i < kvCount; i++) {
                String key = reader.readString();
                int type = reader.readUInt32();
                if ("tokenizer.ggml.tokens".equals(key) && type == GgufReader.ARRAY) {
                    int elemType = reader.readUInt32();
                    long len = reader.readUInt64();
                    for (long j = 0; j < len; j++) {
                        reader.skipValue(elemType);
                    }
                    metadata.put(key + ".size", len);
                } else {
                    Object value = reader.readValue(type);
                    metadata.put(key, value);
                }
            }
            metadata.put("file.name", file.getName());
            metadata.put("file.path", file.getAbsolutePath());
            return metadata;
        } catch (Exception e) {
            return java.util.Collections.emptyMap();
        }
    }
}
