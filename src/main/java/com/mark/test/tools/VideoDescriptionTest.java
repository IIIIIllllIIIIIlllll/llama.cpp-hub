package com.mark.test.tools;

import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class VideoDescriptionTest {

	private static final Gson gson = new Gson();

	public static void main(String[] args) throws Exception {
		testVideoDescription("https://10.8.0.10:8080", "Qwen3-Omni-30B-A3B-Instruct-Q4_K_M", "C:\\Users\\Mark\\e5d4a4169c60dc362e4f90ddb4715c4f.mp4", "");
	}

	/**
	 * 向指定服务器发送视频描述请求（OpenAI /v1/chat/completions 协议）
	 *
	 * @param serverUrl 服务器地址，例如 http://localhost:8080
	 * @param model     模型名称，例如 gpt-4o
	 * @param videoPath 本地视频文件路径
	 * @param apiKey    API Key（可为 null）
	 */
	public static void testVideoDescription(String serverUrl, String model, String videoPath, String apiKey) throws Exception {
		TrustManager[] trustAll = new TrustManager[] { new X509TrustManager() {
			public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
			public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
			public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
		} };
		SSLContext sc = SSLContext.getInstance("TLS");
		sc.init(null, trustAll, new SecureRandom());
		HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
		HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);

		Path videoFile = Path.of(videoPath);
		if (!Files.exists(videoFile)) {
			System.err.println("视频文件不存在: " + videoPath);
			return;
		}

		byte[] videoBytes = Files.readAllBytes(videoFile);
		String base64Video = Base64.getEncoder().encodeToString(videoBytes);
		String mimeType = getMimeType(videoFile);

		String endpoint = serverUrl.endsWith("/") ? serverUrl + "v1/chat/completions" : serverUrl + "/v1/chat/completions";

		JsonObject requestBody = new JsonObject();
		requestBody.addProperty("model", model);
		requestBody.addProperty("max_tokens", -1);

		JsonArray messages = new JsonArray();
		JsonObject userMsg = new JsonObject();
		userMsg.addProperty("role", "user");

		JsonArray content = new JsonArray();

		JsonObject videoPart = new JsonObject();
		videoPart.addProperty("type", "video_url");
		JsonObject videoUrlObj = new JsonObject();
		videoUrlObj.addProperty("url", "data:" + mimeType + ";base64," + base64Video);
		videoUrlObj.addProperty("detail", "high");
		videoPart.add("video_url", videoUrlObj);
		content.add(videoPart);

		JsonObject textPart = new JsonObject();
		textPart.addProperty("type", "text");
		textPart.addProperty("text", "请详细描述这个视频的内容，包括画面中发生的主要事件、场景变化、人物动作等。请用中文回答。");
		content.add(textPart);

		userMsg.add("content", content);
		messages.add(userMsg);
		requestBody.add("messages", messages);

		String jsonBody = gson.toJson(requestBody);

		System.out.println("请求地址: " + endpoint);
		System.out.println("请求体: " + jsonBody);
		System.out.println("视频大小: " + (videoBytes.length / 1024 / 1024) + " MB");
		System.out.println("--- 开始发送请求 ---");

		URL url = URI.create(endpoint).toURL();
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestMethod("POST");
		conn.setDoOutput(true);
		conn.setConnectTimeout(30_000);
		conn.setReadTimeout(300_000);
		conn.setRequestProperty("Content-Type", "application/json");
		conn.setRequestProperty("Accept", "application/json");
		if (apiKey != null && !apiKey.isEmpty()) {
			conn.setRequestProperty("Authorization", "Bearer " + apiKey);
		}

		try (var os = conn.getOutputStream()) {
			os.write(jsonBody.getBytes("UTF-8"));
		}

		int responseCode = conn.getResponseCode();
		System.out.println("响应码: " + responseCode);

		if (responseCode == 200) {
			String response = readStream(conn.getInputStream());
			System.out.println("--- 响应内容 ---");
			System.out.println(response);
		} else {
			System.err.println("--- 错误响应 ---");
			System.err.println(readStream(conn.getErrorStream()));
		}

		conn.disconnect();
	}

	private static String getMimeType(Path file) {
		String ext = file.getFileName().toString().toLowerCase();
		if (ext.endsWith(".mp4")) return "video/mp4";
		if (ext.endsWith(".mov")) return "video/quicktime";
		if (ext.endsWith(".avi")) return "video/x-msvideo";
		if (ext.endsWith(".mkv")) return "video/x-matroska";
		if (ext.endsWith(".webm")) return "video/webm";
		if (ext.endsWith(".gif")) return "image/gif";
		return "video/mp4";
	}

	private static String readStream(java.io.InputStream is) throws Exception {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		byte[] buf = new byte[4096];
		int n;
		while ((n = is.read(buf)) > 0) {
			baos.write(buf, 0, n);
		}
		return baos.toString("UTF-8");
	}
}
