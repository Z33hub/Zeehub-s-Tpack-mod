package dev.itemstudio.build;

import dev.itemstudio.ItemStudioClient;
import dev.itemstudio.StudioConfig;
import dev.itemstudio.pack.Resources;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.PackRepository;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/** Writes the generated pack into the resourcepacks folder, turns it on above other packs and reloads. */
public final class PackWriter {
	private static final String PACK_MCMETA = """
		{
		  "pack": {
		    "description": "Made by Item Studio. Change it in the Item Studio menu.",
		    "pack_format": 75,
		    "min_format": 65,
		    "max_format": 9999
		  }
		}
		""";

	private PackWriter() {
	}

	public static Path folder() {
		return Minecraft.getInstance().getResourcePackDirectory().resolve(Resources.GENERATED_FOLDER);
	}

	public enum Result { FAILED, UNCHANGED, CHANGED }

	/** Files written or deleted since the last reload, as pack paths. Collected so the reload can be a quick one. */
	private static final java.util.Set<String> changedSinceReload = new java.util.HashSet<>();
	/** The pack's files after the last write. */
	private static volatile java.util.Set<String> current = java.util.Set.of();
	private static volatile boolean lastReloadQuick;

	private static synchronized void noteChanged(String path) {
		changedSinceReload.add(path);
	}

	private static synchronized java.util.Set<String> takeChanges() {
		java.util.Set<String> copy = new java.util.HashSet<>(changedSinceReload);
		changedSinceReload.clear();
		return copy;
	}

	/** True when the last Apply only reloaded what changed (sounds, single textures). */
	public static boolean lastReloadQuick() {
		return lastReloadQuick;
	}

	/** Replaces the generated pack's files. Returns false if writing failed. */
	public static boolean write(Map<String, byte[]> files) {
		return writeChanges(files) != Result.FAILED;
	}

	/**
	 * Makes the generated pack folder hold exactly these files, touching only what differs: files that are already
	 * the same are left alone and leftovers are deleted. UNCHANGED means nothing on disk changed, so the game
	 * doesn't need to reload.
	 */
	public static Result writeChanges(Map<String, byte[]> files) {
		Path dir = folder();
		try {
			if (files.isEmpty()) {
				boolean existed = Files.exists(dir);
				deleteTree(dir);
				current = java.util.Set.of();
				if (existed) noteChanged("pack.mcmeta");
				return existed ? Result.CHANGED : Result.UNCHANGED;
			}
			Map<String, byte[]> all = new java.util.LinkedHashMap<>(files);
			all.put("pack.mcmeta", PACK_MCMETA.getBytes(StandardCharsets.UTF_8));
			try (InputStream icon = PackWriter.class.getResourceAsStream("/assets/itemstudio/icon.png")) {
				if (icon != null) all.put("pack.png", icon.readAllBytes());
			}
			java.util.Set<Path> leftover = new java.util.HashSet<>();
			if (Files.isDirectory(dir)) {
				try (Stream<Path> walk = Files.walk(dir)) {
					walk.filter(Files::isRegularFile).forEach(p -> leftover.add(p.normalize()));
				}
			}
			boolean changed = false;
			Files.createDirectories(dir);
			for (Map.Entry<String, byte[]> entry : all.entrySet()) {
				Path target = dir.resolve(entry.getKey()).normalize();
				if (!target.startsWith(dir.normalize())) continue; // never write outside the pack folder
				leftover.remove(target);
				byte[] bytes = entry.getValue();
				if (Files.isRegularFile(target) && Files.size(target) == bytes.length
					&& java.util.Arrays.equals(Files.readAllBytes(target), bytes)) {
					continue;
				}
				Files.createDirectories(target.getParent());
				Files.write(target, bytes);
				noteChanged(entry.getKey());
				changed = true;
			}
			for (Path old : leftover) {
				Files.deleteIfExists(old);
				noteChanged(dir.normalize().relativize(old).toString().replace('\\', '/'));
				changed = true;
			}
			current = java.util.Set.copyOf(all.keySet());
			if (changed) removeEmptyFolders(dir);
			return changed ? Result.CHANGED : Result.UNCHANGED;
		} catch (IOException e) {
			ItemStudioClient.LOG.error("Could not write the generated pack", e);
			return Result.FAILED;
		}
	}

	private static void removeEmptyFolders(Path dir) throws IOException {
		try (Stream<Path> walk = Files.walk(dir)) {
			for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
				if (p.equals(dir) || !Files.isDirectory(p)) continue;
				try (Stream<Path> inside = Files.list(p)) {
					if (inside.findAny().isEmpty()) Files.deleteIfExists(p);
				}
			}
		}
	}

	/** True if a generated pack from an earlier Apply is on disk. */
	public static boolean hasPack() {
		return Files.isRegularFile(folder().resolve("pack.mcmeta"));
	}

	/**
	 * Makes sure the generated pack is on (or off when empty or when the mod is turned off) and at the top,
	 * then reloads resources. The returned future finishes when the reload is done.
	 */
	public static CompletableFuture<Void> enableAndReload(boolean hasFiles) {
		return enableAndReload(hasFiles, true);
	}

	/** Same, but skips the (slow) reload when no file changed and the pack list is the same. */
	public static CompletableFuture<Void> enableAndReload(boolean hasFiles, boolean filesChanged) {
		java.util.Set<String> changes = takeChanges();
		lastReloadQuick = false;
		Minecraft client = Minecraft.getInstance();
		PackRepository manager = client.getResourcePackRepository();
		// Rescanning opens every pack in the resourcepacks folder (slow with many packs, on the game thread).
		// Only needed when the game doesn't know the generated pack yet (first Apply); its files are read fresh
		// by the reload anyway.
		if (hasFiles && !manager.getAvailableIds().contains(Resources.GENERATED_PACK_ID)) manager.reload();

		List<String> enabled = new ArrayList<>(manager.getSelectedIds());
		enabled.remove(Resources.GENERATED_PACK_ID);
		if (hasFiles && StudioConfig.get().enabled && manager.getAvailableIds().contains(Resources.GENERATED_PACK_ID)) {
			enabled.add(Resources.GENERATED_PACK_ID); // last = highest priority
		}
		manager.setSelected(enabled);

		List<String> before = new ArrayList<>(client.options.resourcePacks);
		client.options.updateResourcePacks(manager); // saves options and reloads if the list changed
		if (before.equals(client.options.resourcePacks)) {
			if (!filesChanged) return CompletableFuture.completedFuture(null);
			if (hasFiles && StudioConfig.get().fastApply) {
				CompletableFuture<Void> quick = FastReload.start(changes, current);
				if (quick != null) {
					lastReloadQuick = true;
					return quick;
				}
			}
			return client.reloadResourcePacks();
		}
		// refreshResourcePacks already started a reload; wait for it by queueing behind it.
		return CompletableFuture.completedFuture(null);
	}

	/** Folder exported packs go to: .minecraft/itemstudio-exports. */
	public static Path exportFolder() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("itemstudio-exports");
	}

	/**
	 * Saves files as a normal resource pack zip that works without Item Studio. Returns the zip, or null if it
	 * could not be written.
	 */
	public static Path exportZip(Map<String, byte[]> files, String name) {
		String safe = name.replaceAll("[^A-Za-z0-9 _.-]", "").trim();
		if (safe.isEmpty()) safe = "Pack";
		String date = java.time.LocalDate.now().toString();
		Path dir = exportFolder();
		try {
			Files.createDirectories(dir);
			Path zip = dir.resolve("ItemStudio-" + safe + "-" + date + ".zip");
			for (int i = 2; Files.exists(zip); i++) zip = dir.resolve("ItemStudio-" + safe + "-" + date + "-" + i + ".zip");
			String mcmeta = PACK_MCMETA.replace("Made by Item Studio. Change it in the Item Studio menu.",
				safe.replace("\\", "") + " - made with Item Studio");
			try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) {
				out.putNextEntry(new java.util.zip.ZipEntry("pack.mcmeta"));
				out.write(mcmeta.getBytes(StandardCharsets.UTF_8));
				out.closeEntry();
				try (InputStream icon = PackWriter.class.getResourceAsStream("/assets/itemstudio/icon.png")) {
					if (icon != null) {
						out.putNextEntry(new java.util.zip.ZipEntry("pack.png"));
						out.write(icon.readAllBytes());
						out.closeEntry();
					}
				}
				for (Map.Entry<String, byte[]> entry : new java.util.TreeMap<>(files).entrySet()) {
					if (entry.getKey().contains("..")) continue;
					out.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));
					out.write(entry.getValue());
					out.closeEntry();
				}
			}
			return zip;
		} catch (IOException e) {
			ItemStudioClient.LOG.error("Could not export the pack", e);
			return null;
		}
	}

	private static void deleteTree(Path dir) throws IOException {
		if (!Files.exists(dir)) return;
		try (Stream<Path> walk = Files.walk(dir)) {
			for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(p);
			}
		}
	}
}
