package dev.itemstudio.pack;

import java.util.Locale;

/**
 * Glint, fire and other textures the game finds by name that are not items, blocks, HUD or particles.
 * Patterns are relative to assets/minecraft/textures/.
 */
public enum EffectElement implements TexturePart {
	GLINT("Enchant glint", "misc/enchanted_glint_item", "misc/enchanted_glint*"),
	FIRE("Fire (low fire)", "block/fire_1", "block/fire_0", "block/fire_1", "block/soul_fire_0", "block/soul_fire_1"),
	OVERLAYS("Screen overlays", "misc/vignette", "misc/vignette", "misc/pumpkinblur", "misc/powder_snow_outline",
		"misc/spyglass_scope", "misc/nausea"),
	EFFECT_ICONS("Effect icons", "mob_effect/speed", "mob_effect/*", "gui/sprites/mob_effect/*"),
	FONT("Game font", "font/ascii", "font/*"),
	SKY("Sun, moon & sky", "environment/sun", "environment/sun", "environment/moon*", "environment/end_sky",
		"environment/celestial/*"),
	CLOUDS("Clouds", "environment/clouds", "environment/clouds"),
	WEATHER("Rain & snow", "environment/rain", "environment/rain", "environment/snow"),
	XP_ORBS("XP orbs", "entity/experience_orb", "entity/experience_orb");

	public static final String KEY_PREFIX = "effect:";

	private final String label;
	private final String preview;
	private final String[] patterns;

	EffectElement(String label, String preview, String... patterns) {
		this.label = label;
		this.preview = "assets/minecraft/textures/" + preview + ".png";
		this.patterns = patterns;
	}

	@Override
	public String key() {
		return KEY_PREFIX + name().toLowerCase(Locale.ROOT);
	}

	@Override
	public String label() {
		return label;
	}

	@Override
	public String preview() {
		return preview;
	}

	@Override
	public String kind() {
		return "Effect";
	}

	public static EffectElement of(String path) {
		for (EffectElement e : values()) {
			if (Glob.texture(path, e.patterns)) return e;
		}
		return null;
	}
}
