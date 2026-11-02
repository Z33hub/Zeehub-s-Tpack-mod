package dev.itemstudio.ui;

/** Fonts the menu can use. Everything except Minecraft's own font ships with the mod (see font/fonts-license.txt). */
public enum MenuFont {
	MINECRAFT("minecraft", "Minecraft"),
	POPPINS("poppins", "Poppins"),
	INTER("inter", "Inter"),
	MONTSERRAT("montserrat", "Montserrat"),
	NUNITO("nunito", "Nunito"),
	RUBIK("rubik", "Rubik"),
	OUTFIT("outfit", "Outfit"),
	SPACE_GROTESK("spacegrotesk", "Space Grotesk"),
	JETBRAINS_MONO("jetbrainsmono", "JetBrains Mono");

	/** Font file name in assets/itemstudio/font/ (the bold one adds "_semibold"). */
	public final String id;
	public final String label;

	MenuFont(String id, String label) {
		this.id = id;
		this.label = label;
	}

	/** Minecraft's pixel font: never shrunk, uses the game's own bold. */
	public boolean pixel() {
		return this == MINECRAFT;
	}

	public static MenuFont byId(String id) {
		for (MenuFont f : values()) {
			if (f.id.equals(id)) return f;
		}
		return MINECRAFT;
	}
}
