package org.mark.test.mcp.struct;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class McpToolInputSchema {

	private final List<McpToolInputField> fields = new ArrayList<>();

	public McpToolInputSchema addProperty(String name, String type, String description, boolean required) {
		if (name == null || name.isBlank()) {
			return this;
		}
		String actualType = (type == null || type.isBlank()) ? "string" : type;
		this.fields.add(new McpToolInputField(name, actualType, description, required));
		return this;
	}

	public List<McpToolInputField> getFields() {
		return List.copyOf(this.fields);
	}

	public JsonObject toJsonObject() {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		JsonObject properties = new JsonObject();
		JsonArray required = new JsonArray();
		for (McpToolInputField field : this.fields) {
			JsonObject property = new JsonObject();
			property.addProperty("type", field.getType());
			if (field.getDescription() != null && !field.getDescription().isBlank()) {
				property.addProperty("description", field.getDescription());
			}
			properties.add(field.getName(), property);
			if (field.isRequired()) {
				required.add(field.getName());
			}
		}
		schema.add("properties", properties);
		schema.add("required", required);
		return schema;
	}

	public static class McpToolInputField {

		private final String name;
		private final String type;
		private final String description;
		private final boolean required;

		public McpToolInputField(String name, String type, String description, boolean required) {
			this.name = name;
			this.type = type;
			this.description = description;
			this.required = required;
		}

		public String getName() {
			return name;
		}

		public String getType() {
			return type;
		}

		public String getDescription() {
			return description;
		}

		public boolean isRequired() {
			return required;
		}
	}
}
