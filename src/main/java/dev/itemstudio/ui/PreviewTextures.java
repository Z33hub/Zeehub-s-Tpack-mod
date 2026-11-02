package dev.itemstudio.ui;

import dev.itemstudio.ItemStudioClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Loads pack textures into the game so the menu can show them on style cards. The pictures are made on background
 * threads (reading packs, drawing 3D models); only the last step, turning the finished picture into a texture,
 * happens on the game thread, a few per frame, so opening a crowded item never freezes the menu.
 */
public final class PreviewTextures {
	/** A loaded texture and its size. Only the top square is drawn, so animated strips show their first frame. */
	public record Preview(Identifier id, int width, int height) {
	}

	private static final Preview FAILED = new Preview(null, 0, 0);
	private final Map<String, Preview> loaded = new HashMap<>();
	private final Map<String, java.util.concurrent.CompletableFuture<byte[]>> pending = new HashMap<>();
	private int uploadsLeft;
	private final List<Identifier> registered = new ArrayList<>();
	private int counter;

	/** Call once per frame. */
	public void beginFrame() {
		uploadsLeft = 8;
	}

	/** Forgets pictures that couldn't be made, so they are tried again (the menu was opened again). */
	public void newSession() {
		loaded.values().removeIf(p -> p == FAILED);
	}

	/** True while a picture is still being made in the background. */
	public boolean isLoading(String key) {
		return pending.containsKey(key);
	}

	/**
	 * key identifies the source and file; bytes runs once, on a background thread. Returns null until the picture
	 * is ready (and when there is none).
	 */
	public Preview get(String key, Supplier<byte[]> bytes) {
		Preview preview = loaded.get(key);
		if (preview != null) return preview == FAILED ? null : preview;
		java.util.concurrent.CompletableFuture<byte[]> job = pending.get(key);
		if (job == null) {
			pending.put(key, java.util.concurrent.CompletableFuture.supplyAsync(bytes, dev.itemstudio.Workers.PREVIEWS));
			return null;
		}
		if (!job.isDone() || uploadsLeft <= 0) return null;
		uploadsLeft--;
		pending.remove(key);
		preview = FAILED;
		try {
			byte[] data = job.join();
			if (data != null && data.length > 0) {
				NativeImage image = NativeImage.read(new ByteArrayInputStream(data));
				int w = image.getWidth();
				int h = image.getHeight();
				Identifier id = Identifier.of(ItemStudioClient.MOD_ID, "preview/" + (counter++));
				NativeImageBackedTexture texture = new NativeImageBackedTexture(() -> "itemstudio preview " + key, image);
				MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);
				registered.add(id);
				preview = new Preview(id, w, h);
			}
		} catch (Exception e) {
			ItemStudioClient.LOG.debug("No preview for {}: {}", key, e.toString());
		}
		loaded.put(key, preview);
		return preview == FAILED ? null : preview;
	}

	public int size() {
		return loaded.size();
	}

	public void clear() {
		for (Identifier id : registered) {
			MinecraftClient.getInstance().getTextureManager().destroyTexture(id);
		}
		registered.clear();
		loaded.clear();
		pending.clear();
	}
}
