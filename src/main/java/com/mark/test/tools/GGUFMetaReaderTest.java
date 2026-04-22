package com.mark.test.tools;

import java.util.Map.Entry;
import java.util.Map;

import org.mark.llamacpp.gguf.GGUFMetaDataReader;

public class GGUFMetaReaderTest {

	public static void main(String[] args) {
		String filePath = "C:\\Users\\Mark\\Models\\mmproj-Qwen3-ASR-1.7B-bf16.gguf";
		Map<String, Object> metadata = GGUFMetaDataReader.read(new java.io.File(filePath));

		if (metadata.isEmpty()) {
			System.err.println("无法读取GGUF文件元数据，请检查文件路径是否正确。");
			return;
		}

		for (Entry<String, Object> entry : metadata.entrySet()) {
			Object value = entry.getValue();
			if (value instanceof java.util.List) {
				java.util.List<?> list = (java.util.List<?>) value;
				System.out.println(entry.getKey() + " (array[" + list.size() + "]): " + list);
			} else {
				System.out.println(entry.getKey() + ": " + value);
			}
		}
	}
}
