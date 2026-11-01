package dev.itemstudio.build;

import dev.itemstudio.ItemStudioClient;
import dev.itemstudio.pack.Resources;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourcePackManager;

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
		return MinecraftClient.getInstance().getResourcePackDir().resolve(Resources.GENERATED_FOLDER);
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

	/**
	 * Makes sure the generated pack is on (or off when empty) and at the top, then reloads resources.
	 * The returned future finishes when the reload is done.
	 */
	public static CompletableFuture<Void> enableAndReload(boolean hasFiles) {
		MinecraftClient client = MinecraftClient.getInstance();
		ResourcePackManager manager = client.getResourcePackManager();
		manager.scanPacks();

		List<String> enabled = new ArrayList<>(manager.getEnabledIds());
		enabled.remove(Resources.GENERATED_PACK_ID);
		if (hasFiles && manager.getIds().contains(Resources.GENERATED_PACK_ID)) {
			enabled.add(Resources.GENERATED_PACK_ID); // last = highest priority
		}
		manager.setEnabledProfiles(enabled);

		List<String> before = new ArrayList<>(client.options.resourcePacks);
		client.options.refreshResourcePacks(manager); // saves options and reloads if the list changed
		if (before.equals(client.options.resourcePacks)) {
			return client.reloadResources();
		}
		// refreshResourcePacks already started a reload; wait for it by queueing behind it.
		return CompletableFuture.completedFuture(null);
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
