package dev.itemstudio;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Profiles as short text codes that can be pasted to a friend: "ISP1." + compressed JSON in base64. */
public final class ProfileCodes {
	private static final String PREFIX = "ISP1.";
	private static final Gson GSON = new Gson();
	private static final int MAX_SIZE = 4 * 1024 * 1024;

	private ProfileCodes() {
	}

	public record Decoded(String name, StudioConfig.Profile profile) {
	}

	public static String encode(String name, StudioConfig.Profile profile) {
		JsonObject json = GSON.toJsonTree(profile).getAsJsonObject();
		json.addProperty("name", name);
		byte[] raw = json.toString().getBytes(StandardCharsets.UTF_8);
		Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
		deflater.setInput(raw);
		deflater.finish();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buf = new byte[4096];
		while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf));
		deflater.end();
		return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray());
	}

	/** Returns null when the text is not a profile code. */
	public static Decoded decode(String text) {
		if (text == null) return null;
		String code = text.replaceAll("\\s+", "");
		int at = code.indexOf(PREFIX);
		if (at < 0) return null;
		try {
			byte[] packed = Base64.getUrlDecoder().decode(code.substring(at + PREFIX.length()));
			Inflater inflater = new Inflater();
			inflater.setInput(packed);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[4096];
			while (!inflater.finished()) {
				int n = inflater.inflate(buf);
				if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
				out.write(buf, 0, n);
				if (out.size() > MAX_SIZE) return null;
			}
			inflater.end();
			JsonElement json = JsonParser.parseString(out.toString(StandardCharsets.UTF_8));
			if (!json.isJsonObject()) return null;
			StudioConfig.Profile profile = GSON.fromJson(json, StudioConfig.Profile.class);
			if (profile == null) return null;
			if (profile.styles == null) profile.styles = new java.util.TreeMap<>();
			if (profile.adjust == null) profile.adjust = new java.util.TreeMap<>();
			if (profile.partSize == null) profile.partSize = new java.util.TreeMap<>();
			String name = json.getAsJsonObject().has("name") ? json.getAsJsonObject().get("name").getAsString() : "Imported";
			name = name.replaceAll("[\\p{Cntrl}]", "").trim();
			if (name.isEmpty()) name = "Imported";
			if (name.length() > 24) name = name.substring(0, 24).trim();
			return new Decoded(name, profile);
		} catch (Exception e) {
			return null;
		}
	}
}
