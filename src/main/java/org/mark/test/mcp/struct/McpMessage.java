package org.mark.test.mcp.struct;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class McpMessage {

	private final List<McpMessageItem> items = new ArrayList<>();

	public McpMessage addText(String text) {
		if (text == null || text.isBlank()) {
			return this;
		}
		this.items.add(McpMessageItem.text(text));
		return this;
	}

	public McpMessage addImage(String data, String mimeType) {
		if (data == null || data.isBlank()) {
			return this;
		}
		this.items.add(McpMessageItem.image(data, mimeType));
		return this;
	}

	public boolean isEmpty() {
		return this.items.isEmpty();
	}

	public JsonArray toJsonArray() {
		JsonArray content = new JsonArray();
		for (McpMessageItem item : this.items) {
			content.add(item.toJsonObject());
		}
		return content;
	}

	public static class McpMessageItem {

		private final String type;
		private final String text;
		private final String data;
		private final String mimeType;

		private McpMessageItem(String type, String text, String data, String mimeType) {
			this.type = type;
			this.text = text;
			this.data = data;
			this.mimeType = mimeType;
		}

		public static McpMessageItem text(String text) {
			return new McpMessageItem("text", text, null, null);
		}

		public static McpMessageItem image(String data, String mimeType) {
			String actualMimeType = (mimeType == null || mimeType.isBlank()) ? "application/octet-stream" : mimeType;
			return new McpMessageItem("image", null, data, actualMimeType);
		}

		public JsonObject toJsonObject() {
			JsonObject item = new JsonObject();
			item.addProperty("type", this.type);
			if ("text".equals(this.type)) {
				item.addProperty("text", this.text);
			}
			if ("image".equals(this.type)) {
				item.addProperty("data", this.data);
				item.addProperty("mimeType", this.mimeType);
			}
			return item;
		}
	}
}
