package dev.itemstudio.ui;

/** Colour themes for the menu. Background colours get the chosen opacity so the game shows through. */
public enum Theme {
	PURPLE("Purple", 0x121022, 0x8B6CFF, 0x6E4FF0, 0xC9BCFF),
	OCEAN("Ocean", 0x0A1422, 0x3BA3FF, 0x1F6FD1, 0xA9D6FF),
	MINT("Mint", 0x0A1A18, 0x2FD3A0, 0x0B7A58, 0x9FF0D4),
	CRIMSON("Crimson", 0x1E0C12, 0xFF5C7A, 0xC8284B, 0xFFB3C1),
	GOLD("Gold", 0x1C140A, 0xFFB547, 0x9A5B00, 0xFFD89A),
	MONO("Mono", 0x101116, 0xD6D9E4, 0x3A3F55, 0xFFFFFF);

	public final String label;
	/** Background tint (RGB). */
	public final int base;
	/** Highlight colour for borders, slider fills and dots (RGB). */
	public final int accent;
	/** Solid colour for main buttons, dark enough for white text (RGB). */
	public final int strong;
	/** Light accent for text on the dark background (RGB). */
	public final int accentText;

	Theme(String label, int base, int accent, int strong, int accentText) {
		this.label = label;
		this.base = base;
		this.accent = accent;
		this.strong = strong;
		this.accentText = accentText;
	}

	public static Theme byName(String name) {
		for (Theme t : values()) {
			if (t.name().equalsIgnoreCase(name) || t.label.equalsIgnoreCase(name)) return t;
		}
		return PURPLE;
	}

	/** RGB plus an alpha between 0 and 1. */
	public static int argb(int rgb, float alpha) {
		int a = Math.max(0, Math.min(255, Math.round(alpha * 255)));
		return (a << 24) | (rgb & 0xFFFFFF);
	}

	/** The colours one frame of the menu uses. */
	public record Palette(int panel, int panelBorder, int rail, int surface, int surfaceHover, int surfaceBorder,
	                      int line, int line2, int well, int track, int soft, int cardOn, int glow,
	                      int accent, int strong, int strongHover, int accentText) {
	}

	public Palette palette(float opacity) {
		return new Palette(
			argb(base, opacity),
			0x1AFFFFFF,
			0x2E000000,
			0x0DFFFFFF,
			0x1AFFFFFF,
			0x17FFFFFF,
			0x12FFFFFF,
			0x21FFFFFF,
			0x4D000000,
			0x21FFFFFF,
			argb(accent, 0.18f),
			argb(accent, 0.14f),
			argb(accent, 0.35f),
			0xFF000000 | accent,
			0xFF000000 | strong,
			0xFF000000 | mix(strong, 0xFFFFFF, 0.12f),
			0xFF000000 | accentText);
	}

	private static int mix(int a, int b, float t) {
		int r = Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
		int g = Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
		int bl = Math.round((a & 255) * (1 - t) + (b & 255) * t);
		return (r << 16) | (g << 8) | bl;
	}
}
