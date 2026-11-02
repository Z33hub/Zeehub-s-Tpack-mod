package dev.itemstudio.build;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.itemstudio.Adjust;
import dev.itemstudio.ItemStudioClient;
import dev.itemstudio.StudioConfig;
import dev.itemstudio.View;
import dev.itemstudio.pack.TexturePart;
import dev.itemstudio.pack.TextureScaler;
import dev.itemstudio.pack.ItemInfo;
import dev.itemstudio.pack.PackIndex;
import dev.itemstudio.pack.PackSource;
import dev.itemstudio.pack.Refs;
import dev.itemstudio.pack.Resources;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the saved choices into the files of the generated resource pack.
 *
 * For each item with a chosen style, the chosen pack's item definition, models and textures are copied. Models and
 * textures that come from a pack are copied under a folder unique to that pack (itemstudio:.../p1a2b3c/...), so two
 * items styled from two different packs never overwrite each other's shared files (for example a pack's own
 * "item/handheld"). Textures that the game looks up by fixed name (the item's own texture when the pack has no model,
 * worn armor, shield, crystal) are copied to their normal place.
 *
 * Size and position changes are done with a small wrapper model per item model: it uses the real model as its parent
 * and only sets "display", based on the values the real model already has.
 */
public final class PackBuilder {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final String NS = ItemStudioClient.MOD_ID;
	public static final String CROSSHAIR_FILE = "assets/minecraft/textures/gui/sprites/hud/crosshair.png";

	private final StudioConfig config;
	private final PackIndex index;
	/** Output: path inside the generated pack -> bytes. */
	private final Map<String, byte[]> out = new LinkedHashMap<>();
	private final List<String> problems = new ArrayList<>();

	public PackBuilder(StudioConfig config, PackIndex index) {
		this.config = config;
		this.index = index;
	}

	public Map<String, byte[]> files() {
		return out;
	}

	public List<String> problems() {
		return problems;
	}

	public PackBuilder build() {
		Set<String> items = new LinkedHashSet<>();
		items.addAll(config.styles.keySet());
		items.addAll(config.adjust.keySet());
		items.addAll(config.partSize.keySet());
		for (String itemId : items) {
			if (TexturePart.byKey(itemId) != null) {
				buildPart(itemId);
				continue;
			}
			Identifier id = Identifier.tryParse(itemId);
			if (id == null || !Registries.ITEM.containsId(id)) continue;
			Item item = Registries.ITEM.get(id);
			try {
				buildItem(ItemInfo.of(item));
			} catch (Exception e) {
				problems.add(itemId + ": " + e.getMessage());
				ItemStudioClient.LOG.warn("Could not build {}", itemId, e);
			}
		}
		if (config.crosshair != null && config.crosshair.length() == 225) {
			// a hand-drawn crosshair replaces whatever crosshair style is picked
			int[] px = new int[225];
			for (int i = 0; i < 225; i++) px[i] = config.crosshair.charAt(i) == '1' ? 0xFFFFFFFF : 0;
			out.put(CROSSHAIR_FILE, dev.itemstudio.pack.Png.encode(15, 15, px));
			out.remove(CROSSHAIR_FILE + ".mcmeta");
		}
		return this;
	}

	/**
	 * Blocks, HUD parts, particles and effects: the game finds these by name, so the chosen pack's files are copied
	 * to the same place. Resized particles are rebuilt from every file of the particle: the pack's version where it
	 * has one, otherwise what the game uses now.
	 */
	private void buildPart(String key) {
		TexturePart element = TexturePart.byKey(key);
		if (element == null) return;
		String styleId = config.styles.get(key);
		float size = config.partSizeOf(key);
		boolean resized = element.resizable() && Math.abs(size - 1f) > 1e-3f;
		boolean lowered = element.heightAdjustable() && Math.abs(size - 1f) > 1e-3f;
		boolean rebuild = resized || lowered;
		if (styleId == null && !rebuild) return;
		PackSource source = styleId == null ? null : index.get(styleId);
		if (styleId != null && source == null) {
			problems.add(element.label() + ": pack \"" + styleId + "\" is gone");
			if (!rebuild) return;
		}
		if (!rebuild) {
			for (String file : index.partFiles(source, element)) {
				try {
					out.put(file, source.read(file));
					if (source.has(file + ".mcmeta")) out.put(file + ".mcmeta", source.read(file + ".mcmeta"));
				} catch (Exception e) {
					problems.add(source.displayName() + ": could not read " + file);
				}
			}
			return;
		}
		for (String file : index.partAllFiles(element)) {
			try {
				byte[] png;
				byte[] meta;
				if (source != null && source.has(file)) {
					png = source.read(file);
					meta = source.has(file + ".mcmeta") ? source.read(file + ".mcmeta") : null;
				} else {
					png = Resources.readBelow(file);
					meta = Resources.readBelow(file + ".mcmeta");
				}
				if (png == null) continue;
				if (lowered) {
					byte[] low = TextureScaler.lower(png, meta, size);
					out.put(file, low == null ? png : low);
					if (meta != null) out.put(file + ".mcmeta", meta);
					continue;
				}
				TextureScaler.Result scaled = TextureScaler.scale(png, meta, size);
				out.put(file, scaled == null ? png : scaled.png());
				byte[] outMeta = scaled == null ? meta : scaled.mcmeta();
				if (outMeta != null) out.put(file + ".mcmeta", outMeta);
			} catch (Exception e) {
				problems.add(element.label() + ": could not resize " + file);
			}
		}
	}

	private void buildItem(ItemInfo info) throws Exception {
		String styleId = config.styles.get(info.id);
		PackSource source = index.get(styleId);
		if (styleId != null && source == null) {
			problems.add(info.id + ": pack \"" + styleId + "\" is gone");
		}
		boolean adjusted = config.hasAdjust(info.id);
		if (source == null && !adjusted) return;

		String defFile = Refs.itemDefFile(info.id);
		JsonElement def;
		boolean defChanged = false;
		Copier copier = source == null ? null : new Copier(source);

		if (source != null && source.has(defFile)) {
			def = Resources.parse(source.read(defFile));
			defChanged = true;
		} else {
			def = Resources.parse(Resources.readBelow(defFile));
		}
		if (def == null) {
			problems.add(info.id + ": no item definition found");
			return;
		}

		if (copier != null) {
			// Point the definition at this pack's copies of any models it has.
			final boolean[] changed = {false};
			Refs.rewriteDefModels(def, ref -> {
				String copied = copier.model(ref);
				if (copied != null) changed[0] = true;
				return copied;
			});
			defChanged |= changed[0];

			// Textures the game finds by name: the item's own textures and entity/armor textures.
			// Skip ones already copied for this pack's own models, so the same image isn't stored twice.
			PackIndex.Provided provided = index.provided(source, info);
			for (String texture : provided.textures()) {
				if (copier.alreadyCopied(texture) && !info.extraTextures.contains(texture)) continue;
				copier.textureInPlace(texture);
			}
		}

		if (adjusted) {
			int[] n = {0};
			Refs.rewriteDefModels(def, ref -> wrap(info, ref, n[0]++));
			defChanged = true;
		}

		if (defChanged) {
			put(defFile, def);
		}
	}

	// ---------------------------------------------------------------- size / position

	private String wrap(ItemInfo info, String modelRef, int n) {
		String wrapperRef = NS + ":wrap/" + Refs.clean(Refs.namespace(info.id) + "_" + Refs.path(info.id)) + "_" + n;
		JsonObject wrapper = new JsonObject();
		wrapper.addProperty("parent", modelRef);
		JsonObject display = new JsonObject();
		boolean blockingModel = Refs.path(modelRef).endsWith("_blocking");
		for (View view : View.values()) {
			if (view == View.BLOCKING) continue; // goes in place of first person, below
			Adjust adjust = config.getAdjust(info.id, view);
			if (view == View.FIRST_PERSON && blockingModel) {
				// the shield's blocking model: "While blocking" wins over plain first person
				Adjust blocking = config.getAdjust(info.id, View.BLOCKING);
				if (blocking != null && !blocking.isDefault()) adjust = blocking;
			}
			if (adjust == null || adjust.isDefault()) continue;
			for (int i = 0; i < view.displayKeys.length; i++) {
				String key = view.displayKeys[i];
				JsonObject base = findDisplay(modelRef, key);
				if (base == null && i == 1) {
					// The game uses the right-hand values for the left hand when a model has none.
					base = findDisplay(modelRef, view.displayKeys[0]);
				}
				// Same change for both hands: the game already mirrors the left hand when drawing it.
				display.add(key, applyAdjust(base, adjust));
			}
		}
		wrapper.add("display", display);
		put(Refs.modelFile(wrapperRef), wrapper);
		return wrapperRef;
	}

	/** The "display" entry a model ends up with for one key, following its parents. Null means none. */
	private JsonObject findDisplay(String modelRef, String key) {
		String ref = modelRef;
		for (int depth = 0; ref != null && depth < 32; depth++) {
			JsonObject model = loadModel(ref);
			if (model == null) return null;
			JsonElement display = model.get("display");
			if (display != null && display.isJsonObject() && display.getAsJsonObject().has(key)) {
				JsonElement entry = display.getAsJsonObject().get(key);
				return entry.isJsonObject() ? entry.getAsJsonObject() : null;
			}
			ref = Refs.parentOf(model);
		}
		return null;
	}

	/** A model from the generated pack if we wrote it, otherwise as the game currently has it. */
	private JsonObject loadModel(String ref) {
		String file = Refs.modelFile(ref);
		byte[] bytes = out.get(file);
		if (bytes == null) bytes = Resources.readBelow(file);
		JsonElement json = Resources.parse(bytes);
		return json != null && json.isJsonObject() ? json.getAsJsonObject() : null;
	}

	private static float[] vec(JsonObject transform, String key, float fallback) {
		float[] v = {fallback, fallback, fallback};
		if (transform != null && transform.has(key) && transform.get(key).isJsonArray()) {
			JsonArray a = transform.getAsJsonArray(key);
			for (int i = 0; i < 3 && i < a.size(); i++) v[i] = a.get(i).getAsFloat();
		}
		return v;
	}

	private static JsonArray arr(float a, float b, float c) {
		JsonArray array = new JsonArray();
		array.add(round(a));
		array.add(round(b));
		array.add(round(c));
		return array;
	}

	private static float round(float f) {
		return Math.round(f * 1000f) / 1000f;
	}

	static JsonObject applyAdjust(JsonObject base, Adjust a) {
		float[] rot = vec(base, "rotation", 0f);
		float[] tr = vec(base, "translation", 0f);
		float[] sc = vec(base, "scale", 1f);
		JsonObject t = new JsonObject();
		t.add("rotation", arr(rot[0] + a.rotX, rot[1] + a.rotY, rot[2] + a.rotZ));
		t.add("translation", arr(clamp(tr[0] + a.x, 80f), clamp(tr[1] + a.y, 80f), clamp(tr[2] + a.z, 80f)));
		t.add("scale", arr(Math.min(sc[0] * a.scale, 4f), Math.min(sc[1] * a.scale, 4f), Math.min(sc[2] * a.scale, 4f)));
		return t;
	}

	private static float clamp(float v, float limit) {
		return Math.max(-limit, Math.min(limit, v));
	}

	// ---------------------------------------------------------------- copying from packs

	private void put(String path, JsonElement json) {
		out.put(path, GSON.toJson(json).getBytes(StandardCharsets.UTF_8));
	}

	/** Copies models and textures out of one pack, renaming them so they can't clash with other packs. */
	private final class Copier {
		private final PackSource source;
		private final Map<String, String> copiedModels = new HashMap<>();
		private final Map<String, String> copiedTextures = new HashMap<>();

		Copier(PackSource source) {
			this.source = source;
		}

		/** Copies a model (and its parents and textures) if this pack has it. Returns the new id, or null. */
		String model(String ref) {
			return model(ref, 0);
		}

		private String model(String ref, int depth) {
			if (depth > 32) return null;
			String file = Refs.modelFile(ref);
			String done = copiedModels.get(ref);
			if (done != null) return done;
			// Copy the model if the pack has it, or if the pack changes one of its parents
			// (e.g. no mace.json but its own item/handheld_mace.json that sets the size in hand).
			boolean inPack = source.has(file);
			if (!inPack && !parentInPack(ref, depth)) return null;

			String newRef = NS + ":" + source.tag() + "/" + Refs.clean(Refs.namespace(ref) + "/" + Refs.path(ref));
			copiedModels.put(ref, newRef);
			JsonElement json;
			try {
				json = Resources.parse(inPack ? source.read(file) : Resources.readBelow(file));
			} catch (Exception e) {
				json = null;
			}
			if (json == null || !json.isJsonObject()) {
				copiedModels.remove(ref);
				problems.add(source.displayName() + ": broken model " + ref);
				return null;
			}
			JsonObject model = json.getAsJsonObject();

			String parent = Refs.parentOf(model);
			if (parent != null) {
				String newParent = model(parent, depth + 1);
				if (newParent != null) model.addProperty("parent", newParent);
			}
			if (model.has("textures") && model.get("textures").isJsonObject()) {
				JsonObject textures = model.getAsJsonObject("textures");
				for (String key : new ArrayList<>(textures.keySet())) {
					String texRef = Refs.textureRef(textures.get(key));
					if (texRef == null) continue;
					String newTex = texture(texRef);
					if (newTex != null) Refs.setTextureRef(textures, key, newTex);
				}
			}
			JsonArray overrides = Refs.overrides(model);
			if (overrides != null) {
				for (JsonElement o : overrides) {
					if (o.isJsonObject() && o.getAsJsonObject().has("model")) {
						String m = model(Refs.full(o.getAsJsonObject().get("model").getAsString()), depth + 1);
						if (m != null) o.getAsJsonObject().addProperty("model", m);
					}
				}
			}
			put(Refs.modelFile(newRef), model);
			return newRef;
		}

		/** True if a model's parent chain (as the game has it now) reaches a model this pack changes. */
		private boolean parentInPack(String ref, int depth) {
			String current = ref;
			for (int d = depth; current != null && d < 32; d++) {
				JsonElement json = Resources.parse(Resources.readBelow(Refs.modelFile(current)));
				if (json == null || !json.isJsonObject()) return false;
				current = Refs.parentOf(json.getAsJsonObject());
				if (current == null || current.startsWith("minecraft:builtin/")) return false;
				if (source.has(Refs.modelFile(current))) return true;
			}
			return false;
		}

		/**
		 * Copies a texture used by a copied model. The first folder (item, block, entity...) is kept so the
		 * texture still lands in the same texture atlas as before.
		 */
		private String texture(String ref) {
			String file = Refs.textureFile(ref);
			if (!source.has(file)) return null;
			String done = copiedTextures.get(ref);
			if (done != null) return done;
			String path = Refs.path(ref);
			int slash = path.indexOf('/');
			String folder = slash > 0 ? path.substring(0, slash) : "item";
			String rest = slash > 0 ? path.substring(slash + 1) : path;
			String newRef = NS + ":" + folder + "/" + source.tag() + "/" + Refs.clean(Refs.namespace(ref) + "/" + rest);
			if (copyFile(file, Refs.textureFile(newRef))) {
				copyFile(file + ".mcmeta", Refs.textureFile(newRef) + ".mcmeta");
				copiedTextures.put(ref, newRef);
				return newRef;
			}
			return null;
		}

		/** True if this texture file was already copied (under a new name) for one of this pack's models. */
		boolean alreadyCopied(String file) {
			for (String ref : copiedTextures.keySet()) {
				if (Refs.textureFile(ref).equalsIgnoreCase(file)) return true;
			}
			return false;
		}

		/** Copies a texture to the same path, for textures the game finds by name. */
		void textureInPlace(String file) {
			copyFile(file, file);
			copyFile(file + ".mcmeta", file + ".mcmeta");
		}

		private boolean copyFile(String from, String to) {
			if (!source.has(from)) return false;
			try {
				out.put(cleanPath(to), source.read(from));
				return true;
			} catch (Exception e) {
				problems.add(source.displayName() + ": could not read " + from);
				return false;
			}
		}
	}

	private static String cleanPath(String path) {
		return Refs.clean(path);
	}
}
