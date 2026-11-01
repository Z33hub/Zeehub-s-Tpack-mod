package dev.itemstudio.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/** Helpers for the ids and file paths used inside item definitions and model files. */
public final class Refs {
	private Refs() {
	}

	/** "item/x" -> "minecraft:item/x". */
	public static String full(String ref) {
		String r = ref.trim();
		return r.indexOf(':') >= 0 ? r : "minecraft:" + r;
	}

	public static String namespace(String ref) {
		String f = full(ref);
		return f.substring(0, f.indexOf(':'));
	}

	public static String path(String ref) {
		String f = full(ref);
		return f.substring(f.indexOf(':') + 1);
	}

	public static String modelFile(String ref) {
		return "assets/" + namespace(ref) + "/models/" + path(ref) + ".json";
	}

	public static String textureFile(String ref) {
		return "assets/" + namespace(ref) + "/textures/" + path(ref) + ".png";
	}

	public static String itemDefFile(String itemId) {
		return "assets/" + namespace(itemId) + "/items/" + path(itemId) + ".json";
	}

	/** Makes a string safe to use inside a resource id. */
	public static String clean(String s) {
		StringBuilder b = new StringBuilder(s.length());
		for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
			boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '/' || c == '.' || c == '_' || c == '-';
			b.append(ok ? c : '_');
		}
		return b.toString();
	}

	// ---- item definitions (assets/<ns>/items/<id>.json) ----

	private static boolean isSpecial(JsonObject o) {
		JsonElement type = o.get("type");
		return type != null && type.isJsonPrimitive() && type.getAsString().endsWith("special");
	}

	/** Adds every model id an item definition points at. */
	public static void collectDefModels(JsonElement def, List<String> out) {
		rewriteDefModels(def, ref -> {
			out.add(ref);
			return null;
		});
	}

	/**
	 * Visits every model id in an item definition. If the function returns a non-null value the id is replaced.
	 * Handles plain models, special models (their "base" model) and anything nested in conditions, selects,
	 * range dispatches and composites.
	 */
	public static void rewriteDefModels(JsonElement element, Function<String, String> fn) {
		if (element == null) return;
		if (element.isJsonArray()) {
			for (JsonElement e : element.getAsJsonArray()) rewriteDefModels(e, fn);
			return;
		}
		if (!element.isJsonObject()) return;
		JsonObject o = element.getAsJsonObject();
		for (Map.Entry<String, JsonElement> entry : o.entrySet()) {
			String key = entry.getKey();
			JsonElement value = entry.getValue();
			boolean isRef = value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
				&& (key.equals("model") || (key.equals("base") && isSpecial(o)));
			if (isRef) {
				String replaced = fn.apply(full(value.getAsString()));
				if (replaced != null) entry.setValue(new JsonPrimitive(replaced));
			} else {
				rewriteDefModels(value, fn);
			}
		}
	}

	// ---- model files ----

	/** Texture id stored in a model's "textures" entry, or null for "#var" links. */
	public static String textureRef(JsonElement value) {
		String s = null;
		if (value.isJsonPrimitive()) {
			s = value.getAsString();
		} else if (value.isJsonObject() && value.getAsJsonObject().has("sprite")) {
			s = value.getAsJsonObject().get("sprite").getAsString();
		}
		if (s == null || s.isEmpty() || s.startsWith("#")) return null;
		return full(s);
	}

	public static void setTextureRef(JsonObject textures, String key, String newRef) {
		JsonElement value = textures.get(key);
		if (value.isJsonObject()) {
			value.getAsJsonObject().addProperty("sprite", newRef);
		} else {
			textures.addProperty(key, newRef);
		}
	}

	public static String parentOf(JsonObject model) {
		JsonElement p = model.get("parent");
		return p != null && p.isJsonPrimitive() ? full(p.getAsString()) : null;
	}

	/** Models referenced by old-style "overrides" lists (ignored by 1.21.4+, but kept consistent). */
	public static JsonArray overrides(JsonObject model) {
		JsonElement o = model.get("overrides");
		return o != null && o.isJsonArray() ? o.getAsJsonArray() : null;
	}
}
