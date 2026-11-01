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

	public static StudioConfig get() {
		if (instance == null) {
			instance = load();
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
					config.styles = new TreeMap<>(config.styles);
					config.adjust = new TreeMap<>(config.adjust);
					return config;
				}
			} catch (Exception e) {
				ItemStudioClient.LOG.warn("Could not read {}, starting fresh", path, e);
			}
		}
		return new StudioConfig();
	}

	public void save() {
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

	public Adjust getAdjust(String itemId, View view) {
		Map<String, Adjust> views = adjust.get(itemId);
		if (views == null) return null;
		return views.get(view.key);
	}

	/** Returns the adjust for editing, creating it if needed. */
	public Adjust editAdjust(String itemId, View view) {
		return adjust.computeIfAbsent(itemId, k -> new TreeMap<>()).computeIfAbsent(view.key, k -> new Adjust());
	}

	/** Drops empty entries so the file stays tidy and unchanged items are not rebuilt. */
	public void cleanUp() {
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
