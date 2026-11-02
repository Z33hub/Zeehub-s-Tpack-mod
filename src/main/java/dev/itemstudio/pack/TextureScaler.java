package dev.itemstudio.pack;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;

/**
 * Makes a particle look bigger or smaller. A particle always fills the same square in the world, whatever its
 * texture's resolution, so:
 * - smaller: the picture gets empty space around it (the canvas grows, the pixels stay);
 * - bigger: the canvas shrinks around the drawn pixels, using up empty space first. A picture that already fills
 *   its whole square loses its edges.
 * Animated strips (with a .mcmeta) are done frame by frame.
 */
public final class TextureScaler {
	public record Result(byte[] png, byte[] mcmeta) {
	}

	private static final Gson GSON = new Gson();

	private TextureScaler() {
	}

	/**
	 * Low fire: moves what is drawn in every frame down, so only the top "height" part of the flames is left at the
	 * bottom of the picture. Returns null when the picture can't be read.
	 */
	public static byte[] lower(byte[] pngBytes, byte[] mcmeta, float height) {
		Png.Image img = Png.decode(pngBytes);
		if (img == null) return null;
		int[] frame = frameSize(img.width(), img.height(), mcmeta);
		if (frame == null) return null;
		int fw = frame[0], fh = frame[1], w = img.width(), h = img.height();
		int shift = Math.round(fh * (1f - Math.max(0f, Math.min(1f, height))));
		if (shift <= 0) return pngBytes;
		int[] out = new int[w * h];
		for (int oy = 0; oy < h; oy += fh) {
			for (int ox = 0; ox < w; ox += fw) {
				for (int y = shift; y < fh; y++) {
					for (int x = 0; x < fw; x++) {
						out[(oy + y) * w + ox + x] = img.get(ox + x, oy + y - shift);
					}
				}
			}
		}
		return Png.encode(w, h, out);
	}

	/** Frame width and height the way the game works them out, or null if they don't fit the picture. */
	private static int[] frameSize(int w, int h, byte[] mcmeta) {
		int fw = w, fh = h;
		JsonElement json = mcmeta == null ? null : Resources.parse(mcmeta);
		if (json != null && json.isJsonObject() && json.getAsJsonObject().has("animation")
			&& json.getAsJsonObject().get("animation").isJsonObject()) {
			JsonObject anim = json.getAsJsonObject().getAsJsonObject("animation");
			if (anim.has("width")) {
				fw = anim.get("width").getAsInt();
				fh = anim.has("height") ? anim.get("height").getAsInt() : h;
			} else if (anim.has("height")) {
				fh = anim.get("height").getAsInt();
			} else {
				fw = fh = Math.min(w, h);
			}
		}
		if (fw <= 0 || fh <= 0 || w % fw != 0 || h % fh != 0) return null;
		return new int[]{fw, fh};
	}

	/** Returns null when the picture can't be read; the caller then keeps the file as it is. */
	public static Result scale(byte[] pngBytes, byte[] mcmeta, float size) {
		Png.Image img = Png.decode(pngBytes);
		if (img == null || size <= 0f) return null;
		int w = img.width(), h = img.height();

		JsonObject meta = null, anim = null;
		if (mcmeta != null) {
			JsonElement json = Resources.parse(mcmeta);
			if (json != null && json.isJsonObject()) {
				meta = json.getAsJsonObject();
				if (meta.has("animation") && meta.get("animation").isJsonObject()) anim = meta.getAsJsonObject("animation");
			}
		}
		// frame size, the way the game works it out
		int fw = w, fh = h;
		boolean explicitW = false, explicitH = false;
		if (anim != null) {
			explicitW = anim.has("width");
			explicitH = anim.has("height");
			if (explicitW) {
				fw = anim.get("width").getAsInt();
				fh = explicitH ? anim.get("height").getAsInt() : h;
			} else if (explicitH) {
				fh = anim.get("height").getAsInt();
			} else {
				fw = fh = Math.min(w, h);
			}
		}
		if (fw <= 0 || fh <= 0 || w % fw != 0 || h % fh != 0) return null;
		int cols = w / fw, rows = h / fh;

		int nw, nh, srcX, srcY, dstX, dstY, copyW, copyH;
		if (size <= 1f) {
			nw = Math.max(fw, Math.round(fw / size));
			nh = Math.max(fh, Math.round(fh / size));
			srcX = 0;
			srcY = 0;
			copyW = fw;
			copyH = fh;
			dstX = (nw - fw) / 2;
			dstY = (nh - fh) / 2;
		} else {
			nw = Math.max(1, Math.round(fw / size));
			nh = Math.max(1, Math.round(fh / size));
			// centre the window on what is actually drawn, across all frames
			int minX = fw, minY = fh, maxX = -1, maxY = -1;
			for (int f = 0; f < cols * rows; f++) {
				int ox = (f % cols) * fw, oy = (f / cols) * fh;
				for (int y = 0; y < fh; y++) {
					for (int x = 0; x < fw; x++) {
						if ((img.get(ox + x, oy + y) >>> 24) == 0) continue;
						minX = Math.min(minX, x);
						maxX = Math.max(maxX, x);
						minY = Math.min(minY, y);
						maxY = Math.max(maxY, y);
					}
				}
			}
			if (maxX < 0) return null; // nothing drawn
			int cx = (minX + maxX + 1) / 2, cy = (minY + maxY + 1) / 2;
			srcX = Math.max(0, Math.min(fw - nw, cx - nw / 2));
			srcY = Math.max(0, Math.min(fh - nh, cy - nh / 2));
			copyW = nw;
			copyH = nh;
			dstX = 0;
			dstY = 0;
		}

		int outW = cols * nw, outH = rows * nh;
		int[] out = new int[outW * outH];
		for (int f = 0; f < cols * rows; f++) {
			int ox = (f % cols) * fw, oy = (f / cols) * fh;
			int tx = (f % cols) * nw, ty = (f / cols) * nh;
			for (int y = 0; y < copyH; y++) {
				for (int x = 0; x < copyW; x++) {
					out[(ty + dstY + y) * outW + tx + dstX + x] = img.get(ox + srcX + x, oy + srcY + y);
				}
			}
		}

		byte[] newMeta = mcmeta;
		if (anim != null && (explicitW || explicitH)) {
			if (explicitW) anim.addProperty("width", nw);
			if (explicitH) anim.addProperty("height", nh);
			newMeta = GSON.toJson(meta).getBytes(StandardCharsets.UTF_8);
		}
		return new Result(Png.encode(outW, outH, out), newMeta);
	}
}
