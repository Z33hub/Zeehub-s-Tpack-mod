package dev.itemstudio.pack;

import java.util.Locale;

/**
 * Parts of the HUD and other UI that can be styled from packs. Each part is a set of texture files under
 * assets/minecraft/textures/gui/ that the game finds by name, so styling one just copies those files.
 */
public enum HudElement {
	HEARTS("Hearts", "sprites/hud/heart/full.png", "sprites/hud/heart/"),
	HUNGER("Hunger", "sprites/hud/food_full.png", "sprites/hud/food_"),
	ARMOR("Armor bar", "sprites/hud/armor_full.png", "sprites/hud/armor_"),
	AIR("Air bubbles", "sprites/hud/air.png", "sprites/hud/air"),
	HOTBAR("Hotbar", "sprites/hud/hotbar_selection.png", "sprites/hud/hotbar"),
	CROSSHAIR("Crosshair", "sprites/hud/crosshair.png", "sprites/hud/crosshair"),
	XP_BAR("XP bar", "sprites/hud/experience_bar_progress.png", "sprites/hud/experience_bar"),
	INVENTORY("Inventory", "container/inventory.png", "container/inventory.png", "container/creative_inventory/"),
	BUTTONS("Buttons", "sprites/widget/button.png", "sprites/widget/button", "sprites/widget/slider");

	/** Folder all of these live in. */
	public static final String BASE = "assets/minecraft/textures/gui/";
	/** Prefix for config keys, so HUD parts and items can share the styles map. */
	public static final String KEY_PREFIX = "hud:";

	public final String label;
	/** File used for the preview picture. */
	public final String preview;
	private final String[] prefixes;

	HudElement(String label, String preview, String... prefixes) {
		this.label = label;
		this.preview = BASE + preview;
		this.prefixes = prefixes;
	}

	public String key() {
		return KEY_PREFIX + name().toLowerCase(Locale.ROOT);
	}

	public static HudElement byKey(String key) {
		if (key == null || !key.startsWith(KEY_PREFIX)) return null;
		for (HudElement e : values()) {
			if (e.key().equals(key)) return e;
		}
		return null;
	}

	/** Which part a pack file belongs to, or null. path is lower case, like "assets/minecraft/textures/gui/...png". */
	public static HudElement of(String path) {
		if (!path.startsWith(BASE) || !path.endsWith(".png")) return null;
		String rest = path.substring(BASE.length());
		for (HudElement e : values()) {
			for (String prefix : e.prefixes) {
				if (rest.startsWith(prefix)) return e;
			}
		}
		return null;
	}
}
