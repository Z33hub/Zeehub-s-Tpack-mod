package dev.itemstudio.pack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.item.Item;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** What files make up an item in vanilla: its definition, its models and the textures they use. */
public final class ItemInfo {
	private static final Map<String, ItemInfo> CACHE = new HashMap<>();

	public final Item item;
	public final String id;
	/** Models the vanilla item definition points at, e.g. minecraft:item/bow and minecraft:item/bow_pulling_0. */
	public final List<String> models = new ArrayList<>();
	/** Every model in the vanilla chains, parents included (item/handheld, item/handheld_mace...). */
	public final Set<String> chainModels = new LinkedHashSet<>();
	/** Texture files (assets/...png) used by the vanilla models, plus entity textures that belong to the item. */
	public final Set<String> textures = new LinkedHashSet<>();
	/** Entity/armor textures that are not in the item models but belong to this item. */
	public final List<String> extraTextures = new ArrayList<>();
	/** The texture that best shows the item, for preview cards. */
	public String mainTexture;

	private ItemInfo(Item item) {
		this.item = item;
		this.id = BuiltInRegistries.ITEM.getKey(item).toString();
		JsonElement def = Resources.parse(Resources.readVanilla(Refs.itemDefFile(id)));
		Refs.collectDefModels(def, models);

		Set<String> seen = new HashSet<>();
		for (String model : models) {
			collectTextures(model, seen, 0);
		}
		for (String extra : extras(Refs.path(id))) {
			String file = Refs.textureFile(extra);
			extraTextures.add(file);
			textures.add(file);
		}
		if (!hasMainLayer && !extraTextures.isEmpty()) {
			mainTexture = extraTextures.get(0);
		}
	}

	public static ItemInfo of(Item item) {
		String key = BuiltInRegistries.ITEM.getKey(item).toString();
		return CACHE.computeIfAbsent(key, k -> new ItemInfo(item));
	}

	public static void clearCache() {
		CACHE.clear();
	}

	private void collectTextures(String modelRef, Set<String> seen, int depth) {
		if (depth > 16 || !seen.add(modelRef)) return;
		chainModels.add(modelRef);
		JsonElement json = Resources.parse(Resources.readVanilla(Refs.modelFile(modelRef)));
		if (json == null || !json.isJsonObject()) return;
		JsonObject model = json.getAsJsonObject();
		if (model.has("textures") && model.get("textures").isJsonObject()) {
			JsonObject tex = model.getAsJsonObject("textures");
			// layer0 first so the preview shows the main look
			if (tex.has("layer0")) addTexture(Refs.textureRef(tex.get("layer0")), true);
			for (String key : tex.keySet()) {
				if (key.equals("particle")) continue; // only used for break particles
				addTexture(Refs.textureRef(tex.get(key)), false);
			}
		}
		String parent = Refs.parentOf(model);
		if (parent != null && !parent.startsWith("minecraft:builtin/")) {
			collectTextures(parent, seen, depth + 1);
		}
	}

	private void addTexture(String ref, boolean main) {
		if (ref == null) return;
		String file = Refs.textureFile(ref);
		textures.add(file);
		if (main && !hasMainLayer) {
			mainTexture = file;
			hasMainLayer = true;
		} else if (mainTexture == null) {
			mainTexture = file;
		}
	}

	private boolean hasMainLayer;

	/** Textures the game uses for an item outside its model (worn armor, shield, crystal...). */
	private static List<String> extras(String path) {
		List<String> list = new ArrayList<>();
		switch (path) {
			case "shield" -> {
				list.add("entity/shield_base");
				list.add("entity/shield_base_nopattern");
			}
			case "end_crystal" -> {
				list.add("entity/end_crystal/end_crystal");
				list.add("entity/end_crystal/end_crystal_beam");
			}
			case "trident" -> list.add("entity/trident");
			case "elytra" -> list.add("entity/equipment/wings/elytra");
			case "turtle_helmet" -> list.add("entity/equipment/humanoid/turtle_scute");
			case "respawn_anchor" -> {
				for (int i = 0; i <= 4; i++) list.add("block/respawn_anchor_side" + i);
				list.add("block/respawn_anchor_top");
				list.add("block/respawn_anchor_top_off");
				list.add("block/respawn_anchor_bottom");
			}
			default -> {
				String material = null;
				boolean legs = false;
				if (path.endsWith("_chestplate")) {
					material = path.substring(0, path.length() - "_chestplate".length());
				} else if (path.endsWith("_leggings")) {
					material = path.substring(0, path.length() - "_leggings".length());
					legs = true;
				}
				if (material != null) {
					if (material.equals("golden")) material = "gold";
					String folder = legs ? "entity/equipment/humanoid_leggings/" : "entity/equipment/humanoid/";
					list.add(folder + material);
					if (material.equals("leather")) list.add(folder + "leather_overlay");
				}
			}
		}
		return list;
	}

	public boolean hasWornLook() {
		return !extraTextures.isEmpty();
	}
}
