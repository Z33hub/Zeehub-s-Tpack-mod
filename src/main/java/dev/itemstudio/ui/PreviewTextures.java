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

/** Loads pack textures into the game so the menu can show them on style cards. */
public final class PreviewTextures {
	/** A loaded texture and its size. Only the top square is drawn, so animated strips show their first frame. */
	public record Preview(Identifier id, int width, int height) {
	}

	private static final Preview FAILED = new Preview(null, 0, 0);
	private final Map<String, Preview> loaded = new HashMap<>();
	private final List<Identifier> registered = new ArrayList<>();
	private int counter;

	/** key identifies the source and file; bytes is called only the first time. */
	public Preview get(String key, Supplier<byte[]> bytes) {
		Preview preview = loaded.get(key);
		if (preview != null) return preview == FAILED ? null : preview;
		preview = FAILED;
		try {
			byte[] data = bytes.get();
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

	public void clear() {
		for (Identifier id : registered) {
			MinecraftClient.getInstance().getTextureManager().destroyTexture(id);
		}
		registered.clear();
		loaded.clear();
	}
}
