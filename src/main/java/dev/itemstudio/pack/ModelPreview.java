package dev.itemstudio.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Draws a picture of an item the way it looks in the inventory, for 3D models made of boxes ("elements").
 * The model's boxes are turned with the model's "gui" display settings and its textures are wrapped onto them,
 * so a 3D mace shows as a mace instead of as its flattened texture sheet.
 *
 * Plain Java (no Minecraft rendering), so it works the same on every game version. Returns null for flat items
 * and special built-in models; callers then show the item's texture as before.
 */
public final class ModelPreview {
	private static final String[] FACES = {"north", "south", "west", "east", "up", "down"};

	private ModelPreview() {
	}

	/**
	 * @param itemId   item id, used to find the item definition
	 * @param fallback model ids to use when no item definition is found (the vanilla ones)
	 * @param read     reads a file ("assets/...") from the source being previewed, or null if missing
	 * @param size     output size in pixels (square)
	 * @return PNG bytes, or null if the item is flat or can't be drawn
	 */
	public static byte[] render(String itemId, List<String> fallback, Function<String, byte[]> read, int size) {
		try {
			List<String> refs = new ArrayList<>();
			JsonElement def = Resources.parse(read.apply(Refs.itemDefFile(itemId)));
			if (def != null) Refs.collectDefModels(def, refs);
			if (refs.isEmpty()) refs.addAll(fallback);
			if (refs.isEmpty()) return null;
			return renderModel(refs.get(0), read, size);
		} catch (Exception e) {
			return null;
		}
	}

	private static byte[] renderModel(String modelRef, Function<String, byte[]> read, int size) {
		// the model and its parents, child first
		List<JsonObject> chain = new ArrayList<>();
		String ref = modelRef;
		for (int depth = 0; ref != null && depth < 32; depth++) {
			if (ref.startsWith("minecraft:builtin/")) {
				if (ref.endsWith("/entity")) return null; // shields, chests...: drawn by special code
				break;
			}
			JsonElement json = Resources.parse(read.apply(Refs.modelFile(ref)));
			if (json == null || !json.isJsonObject()) break;
			chain.add(json.getAsJsonObject());
			ref = Refs.parentOf(json.getAsJsonObject());
		}
		if (chain.isEmpty()) return null;

		JsonArray elements = null;
		JsonObject gui = null;
		String guiLight = null;
		for (JsonObject model : chain) {
			if (elements == null && model.has("elements") && model.get("elements").isJsonArray()) {
				elements = model.getAsJsonArray("elements");
			}
			if (gui == null && model.has("display") && model.get("display").isJsonObject()
				&& model.getAsJsonObject("display").has("gui")) {
				gui = model.getAsJsonObject("display").getAsJsonObject("gui");
			}
			if (guiLight == null && model.has("gui_light")) guiLight = model.get("gui_light").getAsString();
		}
		if (elements == null || elements.isEmpty()) return null; // flat item: the texture already looks right

		// texture variables, parents first so children override
		Map<String, JsonElement> textures = new HashMap<>();
		for (int i = chain.size() - 1; i >= 0; i--) {
			JsonObject model = chain.get(i);
			if (model.has("textures") && model.get("textures").isJsonObject()) {
				for (Map.Entry<String, JsonElement> e : model.getAsJsonObject("textures").entrySet()) {
					textures.put(e.getKey(), e.getValue());
				}
			}
		}

		float[] rot = vec(gui, "rotation", 0f);
		float[] tr = vec(gui, "translation", 0f);
		float[] sc = vec(gui, "scale", 1f);
		float[][] m = rotationXYZ(rot[0], rot[1], rot[2]);
		boolean flatLight = "front".equals(guiLight);

		int[] out = new int[size * size];
		float[] depth = new float[size * size];
		Arrays.fill(depth, Float.NEGATIVE_INFINITY);
		Map<String, Png.Image> loaded = new HashMap<>();
		boolean drewAnything = false;

		for (JsonElement el : elements) {
			if (!el.isJsonObject()) continue;
			JsonObject e = el.getAsJsonObject();
			float[] from = vec(e, "from", 0f);
			float[] to = vec(e, "to", 16f);
			boolean shade = !e.has("shade") || e.get("shade").getAsBoolean();
			JsonObject elRot = e.has("rotation") && e.get("rotation").isJsonObject() ? e.getAsJsonObject("rotation") : null;
			if (!e.has("faces") || !e.get("faces").isJsonObject()) continue;
			JsonObject faces = e.getAsJsonObject("faces");
			for (String dir : FACES) {
				if (!faces.has(dir) || !faces.get(dir).isJsonObject()) continue;
				JsonObject face = faces.getAsJsonObject(dir);
				Png.Image tex = texture(face, textures, read, loaded);
				if (tex == null) continue;

				float[][] corners = corners(dir, from, to);
				float[] uv = face.has("uv") ? vec4(face.getAsJsonArray("uv")) : defaultUv(dir, from, to);
				float[][] uvCorners = {{uv[0], uv[1]}, {uv[2], uv[1]}, {uv[2], uv[3]}, {uv[0], uv[3]}};
				int steps = face.has("rotation") ? ((face.get("rotation").getAsInt() / 90) % 4 + 4) % 4 : 0;

				float[][] v = new float[4][];
				for (int i = 0; i < 4; i++) {
					float[] p = corners[i].clone();
					if (elRot != null) rotateElement(p, elRot);
					float[] s = project(p, sc, m, tr, size);
					float[] t = uvCorners[(i + 4 - steps) % 4];
					v[i] = new float[]{s[0], s[1], s[2], t[0], t[1]};
				}
				float light = !shade || flatLight ? 1f : switch (dir) {
					case "up" -> 1f;
					case "down" -> 0.5f;
					case "north", "south" -> 0.8f;
					default -> 0.6f;
				};
				drewAnything |= triangle(out, depth, size, v[0], v[1], v[2], tex, light);
				drewAnything |= triangle(out, depth, size, v[0], v[2], v[3], tex, light);
			}
		}
		if (!drewAnything) return null;
		return Png.encode(size, size, out);
	}

	// ---------------------------------------------------------------- geometry

	/** Face corners in the order top-left, top-right, bottom-right, bottom-left as seen from outside. */
	private static float[][] corners(String dir, float[] f, float[] t) {
		return switch (dir) {
			case "north" -> new float[][]{{t[0], t[1], f[2]}, {f[0], t[1], f[2]}, {f[0], f[1], f[2]}, {t[0], f[1], f[2]}};
			case "south" -> new float[][]{{f[0], t[1], t[2]}, {t[0], t[1], t[2]}, {t[0], f[1], t[2]}, {f[0], f[1], t[2]}};
			case "west" -> new float[][]{{f[0], t[1], f[2]}, {f[0], t[1], t[2]}, {f[0], f[1], t[2]}, {f[0], f[1], f[2]}};
			case "east" -> new float[][]{{t[0], t[1], t[2]}, {t[0], t[1], f[2]}, {t[0], f[1], f[2]}, {t[0], f[1], t[2]}};
			case "up" -> new float[][]{{f[0], t[1], f[2]}, {t[0], t[1], f[2]}, {t[0], t[1], t[2]}, {f[0], t[1], t[2]}};
			default -> new float[][]{{f[0], f[1], t[2]}, {t[0], f[1], t[2]}, {t[0], f[1], f[2]}, {f[0], f[1], f[2]}};
		};
	}

	/** The texture area Minecraft uses for a face that has no "uv". */
	private static float[] defaultUv(String dir, float[] f, float[] t) {
		return switch (dir) {
			case "down" -> new float[]{f[0], 16 - t[2], t[0], 16 - f[2]};
			case "up" -> new float[]{f[0], f[2], t[0], t[2]};
			case "north" -> new float[]{16 - t[0], 16 - t[1], 16 - f[0], 16 - f[1]};
			case "south" -> new float[]{f[0], 16 - t[1], t[0], 16 - f[1]};
			case "west" -> new float[]{f[2], 16 - t[1], t[2], 16 - f[1]};
			default -> new float[]{16 - t[2], 16 - t[1], 16 - f[2], 16 - f[1]};
		};
	}

	private static void rotateElement(float[] p, JsonObject r) {
		float[] o = vec(r, "origin", 8f);
		float angle = r.has("angle") ? r.get("angle").getAsFloat() : 0f;
		String axis = r.has("axis") ? r.get("axis").getAsString() : "y";
		double a = Math.toRadians(angle);
		float c = (float) Math.cos(a), s = (float) Math.sin(a);
		float x = p[0] - o[0], y = p[1] - o[1], z = p[2] - o[2];
		switch (axis) {
			case "x" -> {
				float ny = y * c - z * s, nz = y * s + z * c;
				y = ny;
				z = nz;
			}
			case "z" -> {
				float nx = x * c - y * s, ny = x * s + y * c;
				x = nx;
				y = ny;
			}
			default -> {
				float nx = x * c + z * s, nz = -x * s + z * c;
				x = nx;
				z = nz;
			}
		}
		p[0] = x + o[0];
		p[1] = y + o[1];
		p[2] = z + o[2];
	}

	/** Model pixels (0..16) to image pixels, through the model's inventory (gui) transform. */
	private static float[] project(float[] p, float[] sc, float[][] m, float[] tr, int size) {
		float x = (p[0] / 16f - 0.5f) * sc[0];
		float y = (p[1] / 16f - 0.5f) * sc[1];
		float z = (p[2] / 16f - 0.5f) * sc[2];
		float rx = m[0][0] * x + m[0][1] * y + m[0][2] * z + tr[0] / 16f;
		float ry = m[1][0] * x + m[1][1] * y + m[1][2] * z + tr[1] / 16f;
		float rz = m[2][0] * x + m[2][1] * y + m[2][2] * z + tr[2] / 16f;
		return new float[]{(rx + 0.5f) * size, (0.5f - ry) * size, rz};
	}

	/** Rotation by x, then y, then z degrees, like Minecraft's display rotations: Rx * Ry * Rz. */
	private static float[][] rotationXYZ(float dx, float dy, float dz) {
		float[][] rx = rot(0, dx), ry = rot(1, dy), rz = rot(2, dz);
		return mul(mul(rx, ry), rz);
	}

	private static float[][] rot(int axis, float degrees) {
		double a = Math.toRadians(degrees);
		float c = (float) Math.cos(a), s = (float) Math.sin(a);
		return switch (axis) {
			case 0 -> new float[][]{{1, 0, 0}, {0, c, -s}, {0, s, c}};
			case 1 -> new float[][]{{c, 0, s}, {0, 1, 0}, {-s, 0, c}};
			default -> new float[][]{{c, -s, 0}, {s, c, 0}, {0, 0, 1}};
		};
	}

	private static float[][] mul(float[][] a, float[][] b) {
		float[][] r = new float[3][3];
		for (int i = 0; i < 3; i++)
			for (int j = 0; j < 3; j++)
				r[i][j] = a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j];
		return r;
	}

	// ---------------------------------------------------------------- drawing

	/** Fills one triangle with texture, keeping the nearest surface. v = {x, y, depth, u, v}. */
	private static boolean triangle(int[] out, float[] depth, int size, float[] a, float[] b, float[] c, Png.Image tex, float light) {
		float area = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
		if (Math.abs(area) < 1e-6f) return false;
		int minX = Math.max(0, (int) Math.floor(Math.min(a[0], Math.min(b[0], c[0]))));
		int maxX = Math.min(size - 1, (int) Math.ceil(Math.max(a[0], Math.max(b[0], c[0]))));
		int minY = Math.max(0, (int) Math.floor(Math.min(a[1], Math.min(b[1], c[1]))));
		int maxY = Math.min(size - 1, (int) Math.ceil(Math.max(a[1], Math.max(b[1], c[1]))));
		int frameH = tex.height() > tex.width() ? tex.width() : tex.height(); // animated strips: first frame
		boolean any = false;
		for (int y = minY; y <= maxY; y++) {
			for (int x = minX; x <= maxX; x++) {
				float px = x + 0.5f, py = y + 0.5f;
				float w0 = ((b[0] - px) * (c[1] - py) - (b[1] - py) * (c[0] - px)) / area;
				float w1 = ((c[0] - px) * (a[1] - py) - (c[1] - py) * (a[0] - px)) / area;
				float w2 = 1f - w0 - w1;
				if (w0 < -1e-4f || w1 < -1e-4f || w2 < -1e-4f) continue;
				float z = w0 * a[2] + w1 * b[2] + w2 * c[2];
				int i = y * size + x;
				if (z <= depth[i]) continue;
				float u = w0 * a[3] + w1 * b[3] + w2 * c[3];
				float v = w0 * a[4] + w1 * b[4] + w2 * c[4];
				int tx = clamp((int) Math.floor(u / 16f * tex.width()), tex.width() - 1);
				int ty = clamp((int) Math.floor(v / 16f * frameH), frameH - 1);
				int color = tex.get(tx, ty);
				int alpha = color >>> 24;
				if (alpha < 26) continue;
				depth[i] = z;
				int r = Math.min(255, Math.round(((color >> 16) & 255) * light));
				int g = Math.min(255, Math.round(((color >> 8) & 255) * light));
				int bl = Math.min(255, Math.round((color & 255) * light));
				out[i] = alpha << 24 | r << 16 | g << 8 | bl;
				any = true;
			}
		}
		return any;
	}

	private static int clamp(int v, int max) {
		return Math.max(0, Math.min(max, v));
	}

	private static Png.Image texture(JsonObject face, Map<String, JsonElement> textures, Function<String, byte[]> read,
	                                 Map<String, Png.Image> loaded) {
		if (!face.has("texture")) return null;
		String ref = face.get("texture").getAsString();
		for (int i = 0; i < 10 && ref != null && ref.startsWith("#"); i++) {
			JsonElement value = textures.get(ref.substring(1));
			if (value == null) return null;
			ref = value.isJsonPrimitive() ? value.getAsString()
				: value.isJsonObject() && value.getAsJsonObject().has("sprite") ? value.getAsJsonObject().get("sprite").getAsString() : null;
		}
		if (ref == null || ref.startsWith("#")) return null;
		String file = Refs.textureFile(ref);
		if (loaded.containsKey(file)) return loaded.get(file);
		Png.Image image = Png.decode(read.apply(file));
		loaded.put(file, image);
		return image;
	}

	private static float[] vec(JsonObject o, String key, float fallback) {
		float[] v = {fallback, fallback, fallback};
		if (o != null && o.has(key) && o.get(key).isJsonArray()) {
			JsonArray a = o.getAsJsonArray(key);
			for (int i = 0; i < 3 && i < a.size(); i++) v[i] = a.get(i).getAsFloat();
		}
		return v;
	}

	private static float[] vec4(JsonArray a) {
		float[] v = {0, 0, 16, 16};
		for (int i = 0; i < 4 && i < a.size(); i++) v[i] = a.get(i).getAsFloat();
		return v;
	}
}
