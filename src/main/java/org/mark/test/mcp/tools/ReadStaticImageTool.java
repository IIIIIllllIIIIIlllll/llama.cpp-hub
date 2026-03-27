package org.mark.test.mcp.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import org.mark.test.mcp.IMCPTool;
import org.mark.test.mcp.struct.McpMessage;
import org.mark.test.mcp.struct.McpToolInputSchema;
// import org.slf4j.Logger;
// import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;


/**
 * 	用来测试读图的。
 */
public class ReadStaticImageTool implements IMCPTool {

	// private static final Logger logger = LoggerFactory.getLogger(ReadStaticImageTool.class);
	private static final long MAX_IMAGE_BYTES = 2L * 1024L * 1024L;

	private final Path imagePath;

	public ReadStaticImageTool(Path imagePath) {
		this.imagePath = imagePath == null ? Path.of("screenshot", "index.png") : imagePath;
	}

	@Override
	public String getMcpName() {
		return "read_static_image";
	}

	@Override
	public String getMcpTitle() {
		return "读取静态图片";
	}

	@Override
	public String getMcpDescription() {
		return "读取服务端固定图片并返回base64内容，用于多模态能力测试";
	}

	@Override
	public McpToolInputSchema getInputSchema() {
		return new McpToolInputSchema();
	}

	@Override
	public McpMessage execute(String serviceKey, JsonObject arguments) {
		JsonObject imageResult = this.readImage(serviceKey);
		McpMessage content = new McpMessage();
		if (!imageResult.has("success") || !imageResult.get("success").getAsBoolean()) {
			return content.addText("图片读取失败: " + imageResult.get("error").getAsString());
		}
		String summary = "图片已加载: " + imageResult.get("fileName").getAsString() + ", size=" + imageResult.get("byteSize").getAsLong() + " bytes";
		return content.addImage(imageResult.get("base64").getAsString(), imageResult.get("mimeType").getAsString()).addText(summary);
	}

	private JsonObject readImage(String serviceKey) {
		JsonObject result = new JsonObject();
		Path absolutePath = this.imagePath.toAbsolutePath().normalize();
		// logger.info("MCP工具执行: name={}, serviceKey={}, imagePath={}", this.getMcpName(), serviceKey, absolutePath);
		if (!Files.exists(absolutePath) || !Files.isRegularFile(absolutePath)) {
			result.addProperty("success", false);
			result.addProperty("error", "图片文件不存在: " + absolutePath);
			return result;
		}
		try {
			long size = Files.size(absolutePath);
			if (size > MAX_IMAGE_BYTES) {
				result.addProperty("success", false);
				result.addProperty("error", "图片过大，当前限制为2MB");
				result.addProperty("byteSize", size);
				return result;
			}
			byte[] bytes = Files.readAllBytes(absolutePath);
			String mimeType = this.detectMimeType(absolutePath);
			String base64 = Base64.getEncoder().encodeToString(bytes);
			result.addProperty("success", true);
			result.addProperty("fileName", absolutePath.getFileName().toString());
			result.addProperty("imagePath", absolutePath.toString());
			result.addProperty("mimeType", mimeType);
			result.addProperty("byteSize", bytes.length);
			result.addProperty("base64", base64);
			return result;
		} catch (IOException e) {
			result.addProperty("success", false);
			result.addProperty("error", "读取图片失败: " + e.getMessage());
			return result;
		}
	}

	private String detectMimeType(Path path) {
		try {
			String mimeType = Files.probeContentType(path);
			if (mimeType != null && !mimeType.isBlank()) {
				return mimeType;
			}
		} catch (IOException e) {
			// logger.info("识别图片MIME失败: path={}, error={}", path, e.getMessage());
		}
		String fileName = path.getFileName().toString().toLowerCase();
		if (fileName.endsWith(".png")) {
			return "image/png";
		}
		if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
			return "image/jpeg";
		}
		if (fileName.endsWith(".webp")) {
			return "image/webp";
		}
		if (fileName.endsWith(".gif")) {
			return "image/gif";
		}
		return "application/octet-stream";
	}
}
