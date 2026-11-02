package dev.itemstudio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Saved choices. Lives in config/itemstudio.json.
 *
 * styles: item id -> source id. Source id is "vanilla" or the file/folder name of a pack in resourcepacks.
 *         An item that is not in the map is left alone ("Default").
 * adjust: item id -> view name -> size/position/rotation change.
 */
public final class StudioConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static StudioConfig instance;

	public Map<String, String> styles = new TreeMap<>();
	public Map<String, Map<String, Adjust>> adjust = new TreeMap<>();
	public boolean applyInstantly = false;
	/** Off = the generated pack is taken out of the active packs. Picks stay saved. */
	public boolean enabled = true;

	// Look of the menu
	public String theme = "PURPLE";
	public float opacity = 0.78f;
	/** Old setting (before 1.5): on = Poppins. Still read so existing configs keep their font. */
	public boolean customFont = false;
	/** Menu font id (see ui.MenuFont). Null = decided by customFont. */
	public String font = null;
	/** Style card size: 0 small, 1 medium, 2 large. */
	public int cardSize = 1;
	/** Particle size per part key ("particle:crit"): 1 = normal size. */
	public Map<String, Float> partSize = new TreeMap<>();

	/** A saved set of choices: what is styled, size changes and particle sizes. */
	public static final class Profile {
		public Map<String, String> styles = new TreeMap<>();
		public Map<String, Map<String, Adjust>> adjust = new TreeMap<>();
		public Map<String, Float> partSize = new TreeMap<>();

		public int count() {
			java.util.Set<String> keys = new java.util.HashSet<>(styles.keySet());
			keys.addAll(adjust.keySet());
			keys.addAll(partSize.keySet());
			return keys.size();
		}
	}

	/**
	 * Saved setups by name, in the order they were made. The active one is also what styles, adjust and partSize
	 * hold; it is copied back here every time the config is saved.
	 */
	public Map<String, Profile> profiles = new java.util.LinkedHashMap<>();
	public String activeProfile = "Main";

	// Style browsing
	public java.util.Set<String> favorites = new java.util.TreeSet<>();
	/** Most recently picked packs first. */
	public java.util.List<String> recent = new java.util.ArrayList<>();
	public boolean sortZtoA = false;

	public static StudioConfig get() {
		if (instance == null) {
			instance = load();
			instance.storeActive(); // a fresh config starts with one profile
		}
		return instance;
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("itemstudio.json");
	}

	private static StudioConfig load() {
		Path path = file();
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				StudioConfig config = GSON.fromJson(reader, StudioConfig.class);
				if (config != null) {
					if (config.styles == null) config.styles = new TreeMap<>();
					if (config.adjust == null) config.adjust = new TreeMap<>();
					if (config.partSize == null) config.partSize = new TreeMap<>();
					config.partSize = new TreeMap<>(config.partSize);
					config.styles = new TreeMap<>(config.styles);
					config.adjust = new TreeMap<>(config.adjust);
					if (config.profiles == null) config.profiles = new java.util.LinkedHashMap<>();
					config.profiles = new java.util.LinkedHashMap<>(config.profiles);
					config.profiles.values().removeIf(java.util.Objects::isNull);
					if (config.activeProfile == null || config.activeProfile.isBlank()) config.activeProfile = "Main";
					config.storeActive();
					return config;
				}
			} catch (Exception e) {
				ItemStudioClient.LOG.warn("Could not read {}, starting fresh", path, e);
			}
		}
		return new StudioConfig();
	}

	public void save() {
		storeActive();
		Path path = file();
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			ItemStudioClient.LOG.warn("Could not save {}", path, e);
		}
	}

	/** Remembers a pack as recently used (newest first, at most 30). */
	public void markRecent(String sourceId) {
		if (sourceId == null) return;
		recent.remove(sourceId);
		recent.add(0, sourceId);
		while (recent.size() > 30) recent.remove(recent.size() - 1);
	}

	public Adjust getAdjust(String itemId, View view) {
		Map<String, Adjust> views = adjust.get(itemId);
		if (views == null) return null;
		return views.get(view.key);
	}

	/** Returns the adjust for editing, creating it if needed. */
	public Adjust editAdjust(String itemId, View view) {
		return adjust.computeIfAbsent(itemId, k -> new TreeMap<>()).computeIfAbsent(view.key, k -> new Adjust());
	}

	/** A copy of the current choices. */
	public Profile currentProfile() {
		Profile p = new Profile();
		p.styles = new TreeMap<>(styles);
		p.adjust = copyAdjust(adjust);
		p.partSize = new TreeMap<>(partSize);
		return p;
	}

	/** Copies the current choices into the active profile. */
	public void storeActive() {
		profiles.put(activeProfile, currentProfile());
	}

	/** Makes a saved profile the current choices. Doesn't save or apply. */
	public void loadProfile(String name) {
		Profile p = profiles.get(name);
		if (p == null) return;
		activeProfile = name;
		styles = new TreeMap<>(p.styles == null ? Map.of() : p.styles);
		adjust = copyAdjust(p.adjust == null ? Map.of() : p.adjust);
		partSize = new TreeMap<>(p.partSize == null ? Map.of() : p.partSize);
	}

	/** base, or "base 2", "base 3"... whichever is free. */
	public String uniqueProfileName(String base) {
		String name = base;
		for (int i = 2; profiles.containsKey(name); i++) name = base + " " + i;
		return name;
	}

	/** Renames a profile, keeping its place in the list. */
	public void renameProfile(String from, String to) {
		Map<String, Profile> renamed = new java.util.LinkedHashMap<>();
		profiles.forEach((k, v) -> renamed.put(k.equals(from) ? to : k, v));
		profiles = renamed;
		if (activeProfile.equals(from)) activeProfile = to;
	}

	private static Map<String, Map<String, Adjust>> copyAdjust(Map<String, Map<String, Adjust>> from) {
		Map<String, Map<String, Adjust>> copy = GSON.fromJson(GSON.toJson(from),
			new com.google.gson.reflect.TypeToken<TreeMap<String, TreeMap<String, Adjust>>>() {}.getType());
		return copy == null ? new TreeMap<>() : new TreeMap<>(copy);
	}

	public String fontId() {
		return font != null ? font : customFont ? "poppins" : "minecraft";
	}

	public void setFont(String id) {
		font = id;
		customFont = !"minecraft".equals(id);
	}

	public float partSizeOf(String key) {
		Float size = partSize.get(key);
		return size == null ? 1f : size;
	}

	public void setPartSize(String key, float size) {
		if (Math.abs(size - 1f) < 1e-3f) partSize.remove(key);
		else partSize.put(key, size);
	}

	/** Drops empty entries so the file stays tidy and unchanged items are not rebuilt. */
	public void cleanUp() {
		partSize.values().removeIf(v -> v == null || Math.abs(v - 1f) < 1e-3f);
		adjust.values().forEach(views -> views.values().removeIf(Adjust::isDefault));
		adjust.values().removeIf(Map::isEmpty);
	}

	public boolean hasAdjust(String itemId) {
		Map<String, Adjust> views = adjust.get(itemId);
		if (views == null) return false;
		for (Adjust a : views.values()) {
			if (!a.isDefault()) return true;
		}
		return false;
	}
}
