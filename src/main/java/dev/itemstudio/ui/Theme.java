package dev.itemstudio.ui;

/**
 * Colour themes for the menu. Background colours get the chosen opacity so the game shows through. A theme sets the
 * background tint and a matching accent; the accent can be swapped for any of {@link #ACCENTS} on top of it.
 * This file has no Minecraft code in it.
 */
public enum Theme {
	PURPLE("Purple", 0x121022, 0x8B6CFF, 0x6E4FF0, 0xC9BCFF),
	OCEAN("Ocean", 0x0A1422, 0x3BA3FF, 0x1F6FD1, 0xA9D6FF),
	MINT("Mint", 0x0A1A18, 0x2FD3A0, 0x0B7A58, 0x9FF0D4),
	CRIMSON("Crimson", 0x1E0C12, 0xFF5C7A, 0xC8284B, 0xFFB3C1),
	GOLD("Gold", 0x1C140A, 0xFFB547, 0x9A5B00, 0xFFD89A),
	MONO("Mono", 0x101116, 0xD6D9E4, 0x3A3F55, 0xFFFFFF),
	SAKURA("Sakura", 0x1E0F1A, 0xFF8AC8, 0xC2387F, 0xFFC6E3),
	SUNSET("Sunset", 0x1E120C, 0xFF8A4C, 0xC24E1C, 0xFFC9A8),
	FOREST("Forest", 0x0C160E, 0x6BCB5A, 0x2E7D32, 0xB9EBAE),
	ICE("Ice", 0x0B1620, 0x7FE3FF, 0x1C8DB3, 0xC9F3FF),
	ROYAL("Royal", 0x0D0F24, 0x6E86FF, 0x3346C9, 0xC0CBFF),
	SLATE("Slate", 0x0F141B, 0x8FA3BF, 0x3F5573, 0xD3DEEC);

	/** Accent colours that can replace a theme's own (RGB), with their names. */
	public static final int[] ACCENTS = {
		0x8B6CFF, 0x3BA3FF, 0x2FD3A0, 0xFF5C7A, 0xFFB547, 0xFF8AC8,
		0xFF8A4C, 0x6BCB5A, 0x7FE3FF, 0x6E86FF, 0xC5E04A, 0xE4E7F0};
	public static final String[] ACCENT_NAMES = {
		"Violet", "Blue", "Teal", "Rose", "Amber", "Pink", "Orange", "Green", "Cyan", "Indigo", "Lime", "Silver"};

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

	/** "RRGGBB" or "#RRGGBB" to an RGB number, or -1 when it isn't one. */
	public static int parseAccent(String hex) {
		if (hex == null) return -1;
		String h = hex.trim();
		if (h.startsWith("#")) h = h.substring(1);
		if (h.length() != 6) return -1;
		try {
			return Integer.parseInt(h, 16);
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	public static String accentHex(int rgb) {
		return String.format("%06X", rgb & 0xFFFFFF);
	}

	/** The colours one frame of the menu uses. */
	public record Palette(int panel, int panelBorder, int header, int rail, int surface, int surfaceHover,
	                      int surfacePressed, int surfaceBorder, int surfaceBorderHover, int line, int line2, int well,
	                      int track, int soft, int softHover, int cardOn, int glow, int accent, int strong,
	                      int strongHover, int strongPressed, int accentText, int base) {
	}

	/** accentRgb is a colour from ACCENTS (or any RGB), or -1 to use the theme's own. */
	public Palette palette(float opacity, int accentRgb) {
		int acc = accentRgb >= 0 ? accentRgb & 0xFFFFFF : accent;
		int strongRgb = accentRgb >= 0 ? darkened(acc) : strong;
		int textRgb = accentRgb >= 0 ? lightened(acc) : accentText;
		return new Palette(
			argb(base, opacity),
			0x24FFFFFF,
			0x26000000,
			0x30000000,
			0x0DFFFFFF,
			0x1AFFFFFF,
			0x26FFFFFF,
			0x17FFFFFF,
			0x33FFFFFF,
			0x12FFFFFF,
			0x21FFFFFF,
			0x52000000,
			0x21FFFFFF,
			argb(acc, 0.20f),
			argb(acc, 0.30f),
			argb(acc, 0.14f),
			argb(acc, 0.35f),
			0xFF000000 | acc,
			0xFF000000 | strongRgb,
			0xFF000000 | mix(strongRgb, 0xFFFFFF, 0.14f),
			0xFF000000 | mix(strongRgb, 0x000000, 0.22f),
			0xFF000000 | textRgb,
			base);
	}

	public Palette palette(float opacity) {
		return palette(opacity, -1);
	}

	/** Perceived brightness, 0..255. */
	private static int luma(int rgb) {
		return (299 * ((rgb >> 16) & 255) + 587 * ((rgb >> 8) & 255) + 114 * (rgb & 255)) / 1000;
	}

	/** A deeper version of the colour that white text can sit on. */
	private static int darkened(int rgb) {
		int y = luma(rgb);
		if (y <= 104) return rgb;
		return mix(rgb, 0x000000, 1f - 104f / y);
	}

	/** A light version of the colour for text on the dark background. */
	private static int lightened(int rgb) {
		int y = luma(rgb);
		if (y >= 190) return rgb;
		float t = Math.min(0.75f, (190f - y) / (255f - y));
		return mix(rgb, 0xFFFFFF, t);
	}

	public static int mix(int a, int b, float t) {
		int r = Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
		int g = Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
		int bl = Math.round((a & 255) * (1 - t) + (b & 255) * t);
		return (r << 16) | (g << 8) | bl;
	}
}
