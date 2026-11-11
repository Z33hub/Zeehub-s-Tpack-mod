package dev.itemstudio.build;

import dev.itemstudio.ItemStudioClient;
import dev.itemstudio.pack.Resources;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourceReloader;
import net.minecraft.resource.SimpleResourceReload;
import net.minecraft.util.Unit;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Quick Apply: when an Apply only changed sounds or single textures that the game keeps on their own (glint,
 * screen overlays, rain and snow, XP orbs), only the game's sound list or its single textures are reloaded, the same
 * way a full reload does it, so there is no loading screen. Everything else (items, blocks, HUD, particles and other
 * textures that live in the game's big texture sheets, models, fonts, skies) needs the full reload, and gets it.
 */
public final class FastReload {
	private static final String TEXTURES = "assets/minecraft/textures/";
	private static final String SOUNDS = "assets/minecraft/sounds/";
	private static final String SOUND_LIST = "assets/minecraft/sounds.json";
	/** Textures the game loads one by one, not into a texture sheet (checked against the game's atlas list). */
	private static final Set<String> SINGLE_TEXTURES = Set.of(
		"misc/enchanted_glint_item", "misc/enchanted_glint_armor",
		"misc/vignette", "misc/pumpkinblur", "misc/powder_snow_outline", "misc/spyglass_scope", "misc/nausea",
		"environment/rain", "environment/snow",
		"entity/experience_orb");

	private FastReload() {
	}

	/** What a quick reload would have to reload for these changed files, or null when they need a full one. */
	static Set<String> kinds(Collection<String> changed) {
		if (changed.isEmpty()) return null;
		Set<String> kinds = new java.util.HashSet<>();
		for (String raw : changed) {
			String path = raw.toLowerCase(Locale.ROOT);
			if (path.equals(SOUND_LIST) || (path.startsWith(SOUNDS) && path.endsWith(".ogg"))) {
				kinds.add("sounds");
				continue;
			}
			if (path.startsWith(TEXTURES)) {
				String name = path.substring(TEXTURES.length());
				if (name.endsWith(".mcmeta")) name = name.substring(0, name.length() - 7);
				if (name.endsWith(".png") && SINGLE_TEXTURES.contains(name.substring(0, name.length() - 4))) {
					kinds.add("textures");
					continue;
				}
			}
			return null;
		}
		return kinds;
	}

	/**
	 * Starts a quick reload for these changed files, or returns null when they need a full reload. files = the pack's
	 * files now. If the quick reload fails, a full one runs instead; the future finishes when either is done.
	 */
	public static CompletableFuture<Void> start(Collection<String> changed, Collection<String> files) {
		Set<String> kinds = kinds(changed);
		if (kinds == null) return null;
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.getOverlay() != null) return null; // a reload is already running
		// The game only sees new files of the pack in folders it saw at the last full reload.
		String probe = null;
		for (String f : files) {
			if (f.startsWith("assets/minecraft/")) {
				probe = f;
				break;
			}
		}
		if (probe == null || !Resources.fromGenerated(probe)) return null;
		List<ResourceReloader> reloaders = new ArrayList<>();
		if (kinds.contains("textures")) reloaders.add(client.getTextureManager());
		if (kinds.contains("sounds")) reloaders.add(client.getSoundManager());
		CompletableFuture<Void> done = new CompletableFuture<>();
		try {
			SimpleResourceReload.create(client.getResourceManager(), reloaders, Util.getMainWorkerExecutor(), client,
					CompletableFuture.completedFuture(Unit.INSTANCE))
				.whenComplete()
				.whenComplete((result, error) -> {
					if (error == null) {
						done.complete(null);
						return;
					}
					ItemStudioClient.LOG.warn("Quick reload failed, doing a full one", error);
					client.execute(() -> client.reloadResources().whenComplete((a, b) -> done.complete(null)));
				});
		} catch (Throwable t) {
			ItemStudioClient.LOG.warn("Quick reload could not start, doing a full one", t);
			return null;
		}
		ItemStudioClient.LOG.info("Item Studio: quick reload of {}", kinds);
		return done;
	}
}
