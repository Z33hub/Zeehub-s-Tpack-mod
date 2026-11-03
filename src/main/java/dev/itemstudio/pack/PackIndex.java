package dev.itemstudio.pack;

import dev.itemstudio.ItemStudioClient;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.core.registries.BuiltInRegistries;

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
	private final Map<String, Provided> providedCache = new java.util.concurrent.ConcurrentHashMap<>();
	private final Map<String, String> fingerprintCache = new java.util.concurrent.ConcurrentHashMap<>();
	private final Map<String, Boolean> sameAsVanillaCache = new java.util.concurrent.ConcurrentHashMap<>();
	private final Map<String, Boolean> sameAsBelowCache = new java.util.concurrent.ConcurrentHashMap<>();
	/** Per pack: texture part (HUD, particle) -> its files in that pack (lower-case paths, .png only). */
	private final Map<String, Map<TexturePart, List<String>>> partFiles = new HashMap<>();
	private final Map<String, String> hudFingerprintCache = new java.util.concurrent.ConcurrentHashMap<>();
	private final Map<String, Boolean> hudSameAsBelowCache = new java.util.concurrent.ConcurrentHashMap<>();
	private final Map<TexturePart, Boolean> hudBelowIsVanillaCache = new java.util.concurrent.ConcurrentHashMap<>();
	/** Texture part -> files the game itself has for it (vanilla and turned-on packs). Replaced as a whole. */
	private volatile Map<TexturePart, List<String>> gameParts = new HashMap<>();
	private final Map<TexturePart, List<String>> partAllCache = new java.util.concurrent.ConcurrentHashMap<>();
	private final Map<String, List<FilePackSource>> packsForCache = new java.util.concurrent.ConcurrentHashMap<>();

	/** The files a source has for one item. */
	/**
	 * The files a source has for one item. parentModels are shared parent models the pack changes (like
	 * item/handheld, which sets how big tools look in your hand); they count for comparing and copying, and make
	 * the pack show up for the item only when the parent is clearly about that item (item/handheld_mace for a mace).
	 */
	public record Provided(boolean itemDef, List<String> models, List<String> parentModels, boolean ownParent,
	                       List<String> textures) {
		public boolean any() {
			return itemDef || !models.isEmpty() || ownParent || !textures.isEmpty();
		}
	}

	public static PackIndex scan() {
		PackIndex index = new PackIndex();
		index.byId.put(VanillaSource.ID, index.vanilla);
		Path dir = Minecraft.getInstance().getResourcePackDirectory();
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
		for (Item item : BuiltInRegistries.ITEM) {
			if (BuiltInRegistries.ITEM.getKey(item).getNamespace().equals("minecraft")) {
				itemPaths.add(BuiltInRegistries.ITEM.getKey(item).getPath());
			}
		}
		for (FilePackSource pack : index.packs) {
			index.namedTextures.put(pack.id(), matchNamedTextures(pack, itemPaths));
			Map<TexturePart, List<String>> parts = new HashMap<>();
			for (String path : pack.paths()) {
				String lower = path.toLowerCase();
				TexturePart part = TexturePart.of(lower);
				if (part != null) parts.computeIfAbsent(part, k -> new ArrayList<>()).add(lower);
			}
			parts.values().forEach(java.util.Collections::sort);
			index.partFiles.put(pack.id(), parts);
		}
		index.gameParts = listGameParts();
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
		List<String> parents = new ArrayList<>();
		boolean ownParent = false;
		String itemName = Refs.path(info.id);
		for (String model : info.chainModels) {
			if (info.models.contains(model) || !source.has(Refs.modelFile(model))) continue;
			parents.add(model);
			String last = Refs.path(model).substring(Refs.path(model).lastIndexOf('/') + 1);
			if (last.contains(itemName)) ownParent = true;
		}
		Provided provided = new Provided(def, models, parents, ownParent, new ArrayList<>(textures));
		providedCache.put(key, provided);
		return provided;
	}

	/** Packs that change this item, in name order. */
	public List<FilePackSource> packsFor(ItemInfo info) {
		return packsForCache.computeIfAbsent(info.id, k -> {
			List<FilePackSource> list = new ArrayList<>();
			for (FilePackSource pack : packs) {
				if (provided(pack, info).any()) list.add(pack);
			}
			return List.copyOf(list);
		});
	}

	/**
	 * A hash of every file a pack would use for an item: its item definition, its models (with the pack's own
	 * parent models) and all their textures. Two packs with the same hash look exactly the same for that item.
	 */
	public String fingerprint(PackSource source, ItemInfo info) {
		String key = source.id() + "|" + info.id;
		String cached = fingerprintCache.get(key);
		if (cached != null) return cached;

		java.util.TreeSet<String> files = filesFor(source, info);

		String result;
		try {
			java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-1");
			for (String file : files) {
				digest.update(file.toLowerCase().getBytes(java.nio.charset.StandardCharsets.UTF_8));
				digest.update((byte) 0);
				byte[] bytes = source.read(file);
				digest.update(bytes);
				byte[] meta = source.has(file + ".mcmeta") ? source.read(file + ".mcmeta") : new byte[0];
				digest.update(meta);
				digest.update((byte) 1);
			}
			result = java.util.HexFormat.of().formatHex(digest.digest());
		} catch (Exception e) {
			result = "unique:" + source.id(); // can't read it: never merge with anything
		}
		fingerprintCache.put(key, result);
		return result;
	}

	/** Every file a pack would use for an item, sorted. */
	private java.util.TreeSet<String> filesFor(PackSource source, ItemInfo info) {
		Provided provided = provided(source, info);
		java.util.TreeSet<String> files = new java.util.TreeSet<>();
		if (provided.itemDef()) {
			String def = Refs.itemDefFile(info.id);
			files.add(def);
			List<String> defModels = new ArrayList<>();
			try {
				Refs.collectDefModels(Resources.parse(source.read(def)), defModels);
			} catch (Exception ignored) {
			}
			for (String model : defModels) addModelFiles(source, model, files, 0);
		}
		for (String model : provided.models()) addModelFiles(source, model, files, 0);
		for (String model : provided.parentModels()) addModelFiles(source, model, files, 0);
		files.addAll(provided.textures());
		return files;
	}

	/**
	 * True when a pack's files for an item are byte-for-byte what the game already uses without Item Studio,
	 * usually because that pack is already turned on. Its card would look the same as "Default".
	 */
	public boolean sameAsBelow(PackSource source, ItemInfo info) {
		return sameAsBelowCache.computeIfAbsent(source.id() + "|" + info.id, k -> {
			java.util.TreeSet<String> files = filesFor(source, info);
			if (files.isEmpty()) return false;
			try {
				for (String file : files) {
					if (!java.util.Arrays.equals(source.read(file), Resources.readBelow(file))) return false;
				}
				return true;
			} catch (Exception e) {
				return false;
			}
		});
	}

	/** True when the item currently looks exactly like vanilla (no other turned-on pack changes its files). */
	public boolean belowIsVanilla(ItemInfo info) {
		return sameAsVanillaCache.computeIfAbsent(info.id, k -> {
			List<String> files = new ArrayList<>();
			files.add(Refs.itemDefFile(info.id));
			for (String model : info.models) files.add(Refs.modelFile(model));
			files.addAll(info.textures);
			for (String file : files) {
				if (!java.util.Arrays.equals(Resources.readBelow(file), Resources.readVanilla(file))) return false;
			}
			return true;
		});
	}

	private void addModelFiles(PackSource source, String modelRef, Set<String> files, int depth) {
		String file = Refs.modelFile(modelRef);
		if (depth > 32 || !source.has(file) || !files.add(file)) return;
		try {
			com.google.gson.JsonElement json = Resources.parse(source.read(file));
			if (json == null || !json.isJsonObject()) return;
			com.google.gson.JsonObject model = json.getAsJsonObject();
			if (model.has("textures") && model.get("textures").isJsonObject()) {
				com.google.gson.JsonObject textures = model.getAsJsonObject("textures");
				for (String k : textures.keySet()) {
					String ref = Refs.textureRef(textures.get(k));
					if (ref != null && source.has(Refs.textureFile(ref))) files.add(Refs.textureFile(ref));
				}
			}
			String parent = Refs.parentOf(model);
			if (parent != null) addModelFiles(source, parent, files, depth + 1);
		} catch (Exception ignored) {
		}
	}

	// ---------------------------------------------------------------- texture parts (HUD, particles)

	/** Every file name the game or any scanned pack uses for a part, plus its preview file. */
	public List<String> partAllFiles(TexturePart element) {
		return partAllCache.computeIfAbsent(element, this::collectPartFiles);
	}

	private List<String> collectPartFiles(TexturePart element) {
		Set<String> all = new java.util.TreeSet<>();
		all.add(element.preview());
		List<String> game = gameParts.get(element);
		if (game != null) all.addAll(game);
		for (Map<TexturePart, List<String>> map : partFiles.values()) {
			List<String> files = map.get(element);
			if (files != null) all.addAll(files);
		}
		return List.copyOf(all);
	}

	/** The files a source has for a HUD part. For vanilla: the game's own versions of every known file. */
	public List<String> partFiles(PackSource source, TexturePart element) {
		if (source instanceof VanillaSource) {
			List<String> list = new ArrayList<>();
			for (String file : partAllFiles(element)) {
				if (source.has(file)) list.add(file);
			}
			return list;
		}
		Map<TexturePart, List<String>> map = partFiles.get(source.id());
		List<String> files = map == null ? null : map.get(element);
		return files == null ? List.of() : files;
	}

	/** The blockstate and block model file of a part's block (see TexturePart.modelBlock). */
	public static List<String> blockModelFiles(String block) {
		return List.of("assets/minecraft/blockstates/" + block + ".json", "assets/minecraft/models/block/" + block + ".json");
	}

	/** The block model files a pack has for a part (cobweb outlines): its blockstate and/or block model. */
	public List<String> partModelFiles(PackSource source, TexturePart element) {
		String block = element.modelBlock();
		if (block == null || source instanceof VanillaSource) return List.of();
		List<String> list = new ArrayList<>(2);
		for (String file : blockModelFiles(block)) {
			if (source.has(file)) list.add(file);
		}
		return list;
	}

	public List<FilePackSource> packsForPart(TexturePart element) {
		return packsForCache.computeIfAbsent(element.key(), k -> {
			List<FilePackSource> list = new ArrayList<>();
			for (FilePackSource pack : packs) {
				if (!partFiles(pack, element).isEmpty()) list.add(pack);
			}
			return List.copyOf(list);
		});
	}

	/** Same idea as fingerprint(): packs with identical files for a HUD part look the same. */
	public String partFingerprint(PackSource source, TexturePart element) {
		String key = source.id() + "|" + element.key();
		String cached = hudFingerprintCache.get(key);
		if (cached != null) return cached;
		String result;
		try {
			java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-1");
			for (String file : partFiles(source, element)) {
				digest.update(file.getBytes(java.nio.charset.StandardCharsets.UTF_8));
				digest.update((byte) 0);
				digest.update(source.read(file));
				if (source.has(file + ".mcmeta")) digest.update(source.read(file + ".mcmeta"));
				digest.update((byte) 1);
			}
			// a cobweb with an outline model doesn't look like the same texture without one
			for (String file : partModelFiles(source, element)) {
				digest.update(file.getBytes(java.nio.charset.StandardCharsets.UTF_8));
				digest.update((byte) 2);
				digest.update(source.read(file));
				for (String tex : modelTextureFiles(source, file)) {
					digest.update(tex.getBytes(java.nio.charset.StandardCharsets.UTF_8));
					digest.update(source.read(tex));
				}
				digest.update((byte) 3);
			}
			result = java.util.HexFormat.of().formatHex(digest.digest());
		} catch (Exception e) {
			result = "unique:" + source.id();
		}
		hudFingerprintCache.put(key, result);
		return result;
	}

	/** True when a pack's files for a HUD part are exactly what the game already uses. */
	public boolean partSameAsBelow(PackSource source, TexturePart element) {
		return hudSameAsBelowCache.computeIfAbsent(source.id() + "|" + element.key(), k -> {
			List<String> files = partFiles(source, element);
			if (files.isEmpty()) return false;
			try {
				for (String file : files) {
					if (!java.util.Arrays.equals(source.read(file), Resources.readBelow(file))) return false;
				}
				for (String file : partModelFiles(source, element)) {
					if (!java.util.Arrays.equals(source.read(file), Resources.readBelow(file))) return false;
				}
				return true;
			} catch (Exception e) {
				return false;
			}
		});
	}

	/** Texture files a pack has that a block model file of that pack points to. */
	private static List<String> modelTextureFiles(PackSource source, String modelFile) {
		List<String> list = new ArrayList<>();
		try {
			com.google.gson.JsonElement json = Resources.parse(source.read(modelFile));
			if (json == null || !json.isJsonObject() || !json.getAsJsonObject().has("textures")) return list;
			com.google.gson.JsonElement textures = json.getAsJsonObject().get("textures");
			if (!textures.isJsonObject()) return list;
			for (String key : textures.getAsJsonObject().keySet()) {
				String ref = Refs.textureRef(textures.getAsJsonObject().get(key));
				if (ref != null && source.has(Refs.textureFile(ref))) list.add(Refs.textureFile(ref));
			}
		} catch (Exception ignored) {
			// unreadable model: only its bytes count
		}
		java.util.Collections.sort(list);
		return list;
	}

	/** True when no turned-on pack changes this HUD part. */
	public boolean partBelowIsVanilla(TexturePart element) {
		return hudBelowIsVanillaCache.computeIfAbsent(element, e -> {
			for (String file : partAllFiles(e)) {
				if (!java.util.Arrays.equals(Resources.readBelow(file), Resources.readVanilla(file))) return false;
			}
			return true;
		});
	}

	/** Closes open pack files. Everything found stays, so the index can be used again later. */
	public void close() {
		packs.forEach(FilePackSource::close);
	}

	/**
	 * The turned-on packs changed: forget everything that compared against them and list the game's own part
	 * files again. What each pack contains is kept.
	 */
	public synchronized void refreshBelow() {
		sameAsVanillaCache.clear();
		sameAsBelowCache.clear();
		hudSameAsBelowCache.clear();
		hudBelowIsVanillaCache.clear();
		gameParts = listGameParts();
		partAllCache.clear();
	}

	/** Part files the game has itself, from vanilla and the turned-on packs. */
	private static Map<TexturePart, List<String>> listGameParts() {
		Map<TexturePart, List<String>> map = new HashMap<>();
		for (String path : Resources.listGame("textures")) {
			String lower = path.toLowerCase();
			TexturePart part = TexturePart.of(lower);
			if (part != null) map.computeIfAbsent(part, k -> new ArrayList<>()).add(lower);
		}
		return map;
	}
}
