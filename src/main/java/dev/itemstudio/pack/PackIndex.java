package dev.itemstudio.pack;

import dev.itemstudio.ItemStudioClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** The packs found in the resourcepacks folder and which items each of them changes. */
public final class PackIndex {
	private final VanillaSource vanilla = new VanillaSource();
	private final List<FilePackSource> packs = new ArrayList<>();
	private final Map<String, PackSource> byId = new LinkedHashMap<>();
	/** Per pack: item id -> loose item textures named after that item (bow_pulling_0.png -> minecraft:bow). */
	private final Map<String, Map<String, List<String>>> namedTextures = new HashMap<>();
	private final Map<String, Provided> providedCache = new HashMap<>();

	/** The files a source has for one item. */
	public record Provided(boolean itemDef, List<String> models, List<String> textures) {
		public boolean any() {
			return itemDef || !models.isEmpty() || !textures.isEmpty();
		}
	}

	public static PackIndex scan() {
		PackIndex index = new PackIndex();
		index.byId.put(VanillaSource.ID, index.vanilla);
		Path dir = MinecraftClient.getInstance().getResourcePackDir();
		if (Files.isDirectory(dir)) {
			try (Stream<Path> list = Files.list(dir)) {
				list.sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
					.filter(p -> !p.getFileName().toString().equals(Resources.GENERATED_FOLDER))
					.forEach(p -> {
						FilePackSource source = FilePackSource.open(p);
						if (source != null) {
							index.packs.add(source);
							index.byId.put(source.id(), source);
						}
					});
			} catch (Exception e) {
				ItemStudioClient.LOG.warn("Could not list resource packs", e);
			}
		}
		Set<String> itemPaths = new HashSet<>();
		for (Item item : Registries.ITEM) {
			if (Registries.ITEM.getId(item).getNamespace().equals("minecraft")) {
				itemPaths.add(Registries.ITEM.getId(item).getPath());
			}
		}
		for (FilePackSource pack : index.packs) {
			index.namedTextures.put(pack.id(), matchNamedTextures(pack, itemPaths));
		}
		return index;
	}

	/**
	 * Finds loose textures in textures/item/ and pairs each with the item it is named after, using the longest
	 * matching item name: "diamond_sword_x" belongs to diamond_sword, not diamond.
	 */
	private static Map<String, List<String>> matchNamedTextures(FilePackSource pack, Set<String> itemPaths) {
		Map<String, List<String>> result = new HashMap<>();
		String prefix = "assets/minecraft/textures/item/";
		for (String path : pack.paths()) {
			String lower = path.toLowerCase();
			if (!lower.startsWith(prefix) || !lower.endsWith(".png")) continue;
			String name = lower.substring(prefix.length(), lower.length() - 4);
			if (name.contains("/")) continue;
			String owner = null;
			if (itemPaths.contains(name)) {
				owner = name;
			} else {
				int cut = name.lastIndexOf('_');
				while (cut > 0) {
					String candidate = name.substring(0, cut);
					if (itemPaths.contains(candidate)) {
						owner = candidate;
						break;
					}
					cut = candidate.lastIndexOf('_');
				}
			}
			if (owner != null) {
				result.computeIfAbsent("minecraft:" + owner, k -> new ArrayList<>()).add(prefix + name + ".png");
			}
		}
		return result;
	}

	public List<FilePackSource> packs() {
		return packs;
	}

	public VanillaSource vanilla() {
		return vanilla;
	}

	public PackSource get(String id) {
		return id == null ? null : byId.get(id);
	}

	/** What a source has for an item. The vanilla source counts as having everything. */
	public Provided provided(PackSource source, ItemInfo info) {
		String key = source.id() + "|" + info.id;
		Provided cached = providedCache.get(key);
		if (cached != null) return cached;

		boolean def = source.has(Refs.itemDefFile(info.id));
		List<String> models = new ArrayList<>();
		for (String model : info.models) {
			if (source.has(Refs.modelFile(model))) models.add(model);
		}
		Set<String> textures = new LinkedHashSet<>();
		for (String texture : info.textures) {
			if (source.has(texture)) textures.add(texture);
		}
		Map<String, List<String>> named = namedTextures.get(source.id());
		if (named != null && named.containsKey(info.id)) {
			textures.addAll(named.get(info.id));
		}
		Provided provided = new Provided(def, models, new ArrayList<>(textures));
		providedCache.put(key, provided);
		return provided;
	}

	/** Packs that change this item, in name order. */
	public List<FilePackSource> packsFor(ItemInfo info) {
		List<FilePackSource> list = new ArrayList<>();
		for (FilePackSource pack : packs) {
			if (provided(pack, info).any()) list.add(pack);
		}
		return list;
	}

	public void close() {
		packs.forEach(FilePackSource::close);
	}
}
