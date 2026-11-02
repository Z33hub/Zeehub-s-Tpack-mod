package dev.itemstudio.pack;

import net.minecraft.client.MinecraftClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Keeps the pack scan for the whole game session, so the menu opens instantly after the first time. It is started
 * in the background once the game has loaded, scanned again when the resourcepacks folder changes, and partly
 * refreshed when the turned-on packs change.
 */
public final class PackIndexCache {
	private static CompletableFuture<PackIndex> current;
	private static String folderSignature;
	private static String belowSignature;
	/** Goes up whenever pictures made from the old scan may be out of date. */
	private static int generation;

	private PackIndexCache() {
	}

	public static synchronized CompletableFuture<PackIndex> get() {
		String folder = folderSignature();
		if (current == null || current.isCompletedExceptionally() || !folder.equals(folderSignature)) {
			if (current != null) current.thenAccept(PackIndex::close);
			folderSignature = folder;
			belowSignature = belowSignature();
			generation++;
			current = CompletableFuture.supplyAsync(PackIndex::scan, dev.itemstudio.Workers.BACKGROUND);
			return current;
		}
		String below = belowSignature();
		if (!below.equals(belowSignature)) {
			belowSignature = below;
			generation++;
			current = current.thenApply(index -> {
				index.refreshBelow();
				return index;
			});
		}
		return current;
	}

	/** Starts the scan in the background if it hasn't run yet. */
	public static void prewarm() {
		get();
	}

	/** Throws the scan away (Rescan button). */
	public static synchronized void invalidate() {
		if (current != null) current.thenAccept(PackIndex::close);
		current = null;
		generation++;
	}

	public static synchronized int generation() {
		return generation;
	}

	/** Names, sizes and change times of everything in the resourcepacks folder. */
	private static String folderSignature() {
		Path dir = MinecraftClient.getInstance().getResourcePackDir();
		List<String> parts = new ArrayList<>();
		if (Files.isDirectory(dir)) {
			try (Stream<Path> list = Files.list(dir)) {
				list.filter(p -> !p.getFileName().toString().equals(Resources.GENERATED_FOLDER)).forEach(p -> {
					try {
						Path stamp = Files.isDirectory(p) && Files.exists(p.resolve("pack.mcmeta")) ? p.resolve("pack.mcmeta") : p;
						parts.add(p.getFileName() + ":" + Files.size(stamp) + ":" + Files.getLastModifiedTime(p).toMillis()
							+ ":" + Files.getLastModifiedTime(stamp).toMillis());
					} catch (Exception e) {
						parts.add(p.getFileName().toString());
					}
				});
			} catch (Exception ignored) {
			}
		}
		parts.sort(null);
		return String.join("|", parts);
	}

	/** The turned-on packs, apart from Item Studio's own. */
	private static String belowSignature() {
		try {
			List<String> ids = new ArrayList<>(MinecraftClient.getInstance().getResourcePackManager().getEnabledIds());
			ids.remove(Resources.GENERATED_PACK_ID);
			return String.join("|", ids);
		} catch (Exception e) {
			return "";
		}
	}
}
