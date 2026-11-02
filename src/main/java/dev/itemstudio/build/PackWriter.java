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

	/** Replaces the generated pack's files. Returns false if writing failed. */
	public static boolean write(Map<String, byte[]> files) {
		Path dir = folder();
		try {
			deleteTree(dir);
			if (files.isEmpty()) return true;
			Files.createDirectories(dir);
			Files.writeString(dir.resolve("pack.mcmeta"), PACK_MCMETA, StandardCharsets.UTF_8);
			try (InputStream icon = PackWriter.class.getResourceAsStream("/assets/itemstudio/icon.png")) {
				if (icon != null) Files.write(dir.resolve("pack.png"), icon.readAllBytes());
			}
			for (Map.Entry<String, byte[]> entry : files.entrySet()) {
				Path target = dir.resolve(entry.getKey());
				if (!target.normalize().startsWith(dir)) continue; // never write outside the pack folder
				Files.createDirectories(target.getParent());
				Files.write(target, entry.getValue());
			}
			return true;
		} catch (IOException e) {
			ItemStudioClient.LOG.error("Could not write the generated pack", e);
			return false;
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
		Minecraft client = Minecraft.getInstance();
		PackRepository manager = client.getResourcePackRepository();
		manager.reload();

		List<String> enabled = new ArrayList<>(manager.getSelectedIds());
		enabled.remove(Resources.GENERATED_PACK_ID);
		if (hasFiles && StudioConfig.get().enabled && manager.getAvailableIds().contains(Resources.GENERATED_PACK_ID)) {
			enabled.add(Resources.GENERATED_PACK_ID); // last = highest priority
		}
		manager.setSelected(enabled);

		List<String> before = new ArrayList<>(client.options.resourcePacks);
		client.options.updateResourcePacks(manager); // saves options and reloads if the list changed
		if (before.equals(client.options.resourcePacks)) {
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
