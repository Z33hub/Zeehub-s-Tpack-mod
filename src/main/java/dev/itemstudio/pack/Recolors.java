package dev.itemstudio.pack;

import dev.itemstudio.Recolor;

/** Applies a {@link Recolor} to PNG images. */
public final class Recolors {
	private Recolors() {
	}

	/** Returns the recoloured PNG, or the original bytes if it can't be read. */
	public static byte[] apply(byte[] png, Recolor rc) {
		if (png == null || rc == null || rc.isDefault()) return png;
		Png.Image img = Png.decode(png);
		if (img == null) return png;
		int[] px = img.argb().clone();
		for (int i = 0; i < px.length; i++) px[i] = pixel(px[i], rc);
		return Png.encode(img.width(), img.height(), px);
	}

	public static int pixel(int argb, Recolor rc) {
		int a = argb >>> 24;
		if (a == 0) return argb;
		float r = ((argb >> 16) & 255) / 255f, g = ((argb >> 8) & 255) / 255f, b = (argb & 255) / 255f;
		float h = rc.hue / 360f, s = clamp(rc.sat);
		if (Recolor.TINT.equals(rc.mode)) {
			float[] c = hsv(h, s, 1f);
			r *= c[0];
			g *= c[1];
			b *= c[2];
		} else {
			float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
			float l = (max + min) / 2f;
			float[] c = hsl(h, s, l);
			r = c[0];
			g = c[1];
			b = c[2];
		}
		float k = Math.max(-0.95f, Math.min(0.95f, rc.bright));
		if (k > 0) {
			r += (1 - r) * k;
			g += (1 - g) * k;
			b += (1 - b) * k;
		} else if (k < 0) {
			r *= 1 + k;
			g *= 1 + k;
			b *= 1 + k;
		}
		return a << 24 | to255(r) << 16 | to255(g) << 8 | to255(b);
	}

	/** An ARGB colour for drawing swatches and slider tracks. */
	public static int swatch(float hue, float sat, float bright) {
		float[] c = hsl(hue / 360f, clamp(sat), clamp(0.5f + bright * 0.5f));
		return 0xFF000000 | to255(c[0]) << 16 | to255(c[1]) << 8 | to255(c[2]);
	}

	private static float[] hsl(float h, float s, float l) {
		if (s <= 0f) return new float[]{l, l, l};
		float q = l < 0.5f ? l * (1 + s) : l + s - l * s;
		float p = 2 * l - q;
		return new float[]{hue(p, q, h + 1f / 3f), hue(p, q, h), hue(p, q, h - 1f / 3f)};
	}

	private static float hue(float p, float q, float t) {
		if (t < 0) t += 1;
		if (t > 1) t -= 1;
		if (t < 1f / 6f) return p + (q - p) * 6 * t;
		if (t < 0.5f) return q;
		if (t < 2f / 3f) return p + (q - p) * (2f / 3f - t) * 6;
		return p;
	}

	private static float[] hsv(float h, float s, float v) {
		float hh = (h - (float) Math.floor(h)) * 6f;
		int i = (int) hh;
		float f = hh - i, p = v * (1 - s), q = v * (1 - s * f), t = v * (1 - s * (1 - f));
		return switch (i) {
			case 0 -> new float[]{v, t, p};
			case 1 -> new float[]{q, v, p};
			case 2 -> new float[]{p, v, t};
			case 3 -> new float[]{p, q, v};
			case 4 -> new float[]{t, p, v};
			default -> new float[]{v, p, q};
		};
	}

	private static float clamp(float v) {
		return Math.max(0f, Math.min(1f, v));
	}

	private static int to255(float v) {
		return Math.max(0, Math.min(255, Math.round(v * 255f)));
	}
}
