package dev.itemstudio.pack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sounds, in groups that matter in PvP (hits, bows, totems...). A pack changes a sound by having a file with the same
 * name under assets/minecraft/sounds/, or by pointing the game's sound events at its own files in its sounds.json.
 * Which files belong to a group comes from the game's own sounds.json: every file the group's events play. The chosen
 * pack's files are copied to the same place, and the group's events are written into Item Studio's sounds.json so they
 * play what that pack plays, even when a turned-on pack points them somewhere else.
 */
public enum SoundElement implements TexturePart {
	HITS("Hits & hurt", "damage/hit1", null, "entity.player.hurt*", "entity.player.death", "entity.player.*_fall"),
	ATTACKS("Attack swings", "entity/player/attack/strong1", "minecraft:diamond_sword", "entity.player.attack.*"),
	BOWS("Bows & arrows", "random/bow", "minecraft:bow", "entity.arrow.*", "item.crossbow.*"),
	EXPLOSIONS("Explosions & anchors", "random/explode1", "minecraft:end_crystal", "entity.generic.explode",
		"block.respawn_anchor.*"),
	TOTEM("Totem pop", "item/totem/use_totem", "minecraft:totem_of_undying", "item.totem.use"),
	SHIELD("Shield", "item/shield/block1", "minecraft:shield", "item.shield.*"),
	PEARLS("Pearls & teleport", "mob/endermen/portal", "minecraft:ender_pearl", "entity.ender_pearl.throw",
		"entity.player.teleport", "entity.enderman.teleport", "item.chorus_fruit.teleport"),
	POTIONS("Splash potions", "random/glass1", "minecraft:splash_potion", "entity.splash_potion.*",
		"entity.lingering_potion.*"),
	EATING("Eating & drinking", "random/eat1", "minecraft:golden_apple", "entity.generic.eat", "entity.player.burp",
		"entity.generic.drink", "item.honey_bottle.drink"),
	XP("XP & level up", "random/orb", "minecraft:experience_bottle", "entity.experience_orb.pickup",
		"entity.player.levelup"),
	MACE("Mace & wind charges", "item/mace/smash_ground1", "minecraft:mace", "item.mace.*", "entity.wind_charge.*"),
	ARMOR("Armor equip", "item/armor/equip_diamond1", "minecraft:diamond_chestplate", "item.armor.equip_*"),
	FLYING("Elytra & fireworks", "fireworks/launch1", "minecraft:firework_rocket", "item.elytra.flying",
		"entity.firework_rocket.*"),
	FOOTSTEPS("Footsteps", "step/stone1", "minecraft:leather_boots", "block.*.step"),
	CLICKS("Menu clicks", "random/click_stereo", "minecraft:stone_button", "ui.button.click");

	public static final String KEY_PREFIX = "sound:";
	public static final String ROOT = "assets/minecraft/sounds/";
	public static final String SOUNDS_JSON = "assets/minecraft/sounds.json";
	/** Older packs' file names for a sound the game now plays from another file. */
	private static final Map<String, String> RENAMED = Map.of("random/click", "random/click_stereo");

	private final String label;
	private final String preview;
	private final String iconItem;
	private final String[] events;

	SoundElement(String label, String preview, String iconItem, String... events) {
		this.label = label;
		this.preview = ROOT + preview + ".ogg";
		this.iconItem = iconItem;
		this.events = events;
	}

	@Override
	public String key() {
		return KEY_PREFIX + name().toLowerCase(Locale.ROOT);
	}

	@Override
	public String label() {
		return label;
	}

	/** The sound played (and drawn) for this group. */
	@Override
	public String preview() {
		return preview;
	}

	@Override
	public String kind() {
		return "Sound";
	}

	@Override
	public String iconItem() {
		return iconItem;
	}

	@Override
	public String[] iconFiles() {
		return this == HITS ? new String[]{"assets/minecraft/textures/gui/sprites/hud/heart/full.png"} : null;
	}

	/** Which other sounds change along with this group, because the game plays the same files for them. */
	public String note() {
		return switch (this) {
			case HITS -> "Mobs use the same hit sounds.";
			case BOWS -> "The bow sound is also the throw sound of pearls, potions, snowballs and wind charges.";
			case EXPLOSIONS -> "Every explosion: end crystals, respawn anchors, TNT and creepers.";
			case SHIELD -> "The shield breaking sound is also used when a tool breaks.";
			case PEARLS -> "Throwing a pearl plays the bow sound (see Bows & arrows).";
			case POTIONS -> "The breaking sound is also used when glass breaks.";
			case FOOTSTEPS -> "The classic step sounds (stone, grass, wood, sand, gravel, wool, snow, ladders).";
			case CLICKS -> "The click of menu buttons.";
			default -> null;
		};
	}

	/** True for an event of this group, like "entity.player.hurt". */
	public boolean hasEvent(String event) {
		for (String pattern : events) {
			if (Glob.matches(pattern, event)) return true;
		}
		return false;
	}

	/** Footsteps only take the classic step/ files, not every block's own step sound. */
	private boolean takes(String name) {
		return this != FOOTSTEPS || name.startsWith("step/");
	}

	public static boolean isSoundPath(String path) {
		return path.startsWith(ROOT);
	}

	/** Which group a pack file belongs to, or null. path is lower case. */
	public static SoundElement of(String path) {
		if (!path.startsWith(ROOT) || !path.endsWith(".ogg")) return null;
		Catalog catalog = Catalog.get();
		if (catalog == null) return null;
		String name = path.substring(ROOT.length(), path.length() - 4);
		SoundElement owner = catalog.owner.get(name);
		if (owner != null) return owner;
		String renamed = RENAMED.get(name);
		return renamed == null ? null : catalog.owner.get(renamed);
	}

	/** Where an older pack's sound file has to go so the game plays it today, or null. path is lower case. */
	public static String renamed(String path) {
		if (!path.startsWith(ROOT) || !path.endsWith(".ogg")) return null;
		String to = RENAMED.get(path.substring(ROOT.length(), path.length() - 4));
		return to == null ? null : ROOT + to + ".ogg";
	}

	/** The game's own files for this group, as pack paths. Empty when the game's sound list can't be read. */
	public List<String> vanillaFiles() {
		Catalog catalog = Catalog.get();
		return catalog == null ? List.of() : catalog.files.getOrDefault(this, List.of());
	}

	/** The game's own events of this group. */
	public List<String> vanillaEvents() {
		Catalog catalog = Catalog.get();
		return catalog == null ? List.of() : catalog.events.getOrDefault(this, List.of());
	}

	/** The game's own sounds.json, or null. Don't change it. */
	public static JsonObject vanillaList() {
		Catalog catalog = Catalog.get();
		return catalog == null ? null : catalog.json;
	}

	/** The entries of a pack's sounds.json for this group's events, in file order. */
	public Map<String, JsonElement> entriesIn(JsonObject packSounds) {
		Map<String, JsonElement> map = new LinkedHashMap<>();
		if (packSounds == null) return map;
		for (Map.Entry<String, JsonElement> e : packSounds.entrySet()) {
			String event = e.getKey().toLowerCase(Locale.ROOT);
			int colon = event.indexOf(':');
			if (colon >= 0) {
				if (!event.substring(0, colon).equals("minecraft")) continue;
				event = event.substring(colon + 1);
			}
			if (hasEvent(event) && e.getValue().isJsonObject()) map.put(e.getKey(), e.getValue());
		}
		return map;
	}

	/** Sound files an entry of a sounds.json plays, as lower-case pack paths. Other events it points to are skipped. */
	public static List<String> filesOf(JsonElement entry) {
		List<String> list = new ArrayList<>();
		if (entry == null || !entry.isJsonObject()) return list;
		JsonElement sounds = entry.getAsJsonObject().get("sounds");
		if (sounds == null || !sounds.isJsonArray()) return list;
		for (JsonElement s : sounds.getAsJsonArray()) {
			String name = null;
			if (s.isJsonPrimitive()) name = s.getAsString();
			else if (s.isJsonObject()) {
				JsonObject o = s.getAsJsonObject();
				JsonElement type = o.get("type");
				if (type != null && type.isJsonPrimitive() && "event".equals(type.getAsString())) continue;
				JsonElement n = o.get("name");
				if (n != null && n.isJsonPrimitive()) name = n.getAsString();
			}
			String file = name == null ? null : fileOf(name);
			if (file != null && !list.contains(file)) list.add(file);
		}
		return list;
	}

	/** "damage/hit1" or "ns:custom/hit" -> its .ogg file in a pack. */
	static String fileOf(String name) {
		String lower = name.toLowerCase(Locale.ROOT).trim();
		if (lower.isEmpty()) return null;
		int colon = lower.indexOf(':');
		String ns = colon < 0 ? "minecraft" : lower.substring(0, colon);
		String path = colon < 0 ? lower : lower.substring(colon + 1);
		if (ns.isEmpty() || path.isEmpty() || path.contains("..")) return null;
		return "assets/" + ns + "/sounds/" + path + ".ogg";
	}

	/** Which group owns each of the game's sound files, worked out once from the game's sounds.json. */
	static final class Catalog {
		final JsonObject json;
		/** File name (like "damage/hit1") -> group. The first group that plays a file owns it. */
		final Map<String, SoundElement> owner = new HashMap<>();
		final Map<SoundElement, List<String>> files = new EnumMap<>(SoundElement.class);
		final Map<SoundElement, List<String>> events = new EnumMap<>(SoundElement.class);

		private static volatile Catalog current;
		private static volatile long failedAt;

		Catalog(JsonObject json) {
			this.json = json;
			for (SoundElement group : values()) {
				List<String> groupFiles = new ArrayList<>();
				List<String> groupEvents = new ArrayList<>();
				for (Map.Entry<String, JsonElement> e : json.entrySet()) {
					if (!group.hasEvent(e.getKey())) continue;
					boolean takesAny = false;
					for (String file : filesOf(e.getValue())) {
						if (!file.startsWith(ROOT)) continue;
						String name = file.substring(ROOT.length(), file.length() - 4);
						if (!group.takes(name)) continue;
						takesAny = true;
						if (owner.putIfAbsent(name, group) == null) groupFiles.add(file);
					}
					if (takesAny) groupEvents.add(e.getKey());
				}
				// the group's own sound first, so it is what plays and shows first
				if (groupFiles.remove(group.preview)) groupFiles.add(0, group.preview);
				files.put(group, Collections.unmodifiableList(groupFiles));
				events.put(group, Collections.unmodifiableList(groupEvents));
			}
		}

		/** The game's list, read from its own assets the first time it is needed; null while it can't be read. */
		static Catalog get() {
			Catalog c = current;
			if (c != null) return c;
			synchronized (Catalog.class) {
				if (current != null) return current;
				if (System.currentTimeMillis() - failedAt < 2000) return null;
				JsonElement json = null;
				try {
					json = Resources.parse(Resources.readVanilla(SOUNDS_JSON));
				} catch (Throwable ignored) {
				}
				if (json == null || !json.isJsonObject()) {
					failedAt = System.currentTimeMillis();
					return null;
				}
				current = new Catalog(json.getAsJsonObject());
				return current;
			}
		}

		/** For tests: use this list instead of the game's. */
		static void use(JsonObject json) {
			current = new Catalog(json);
		}
	}
}
