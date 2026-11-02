package dev.itemstudio.pack;

import java.util.Locale;

/** Particle textures, grouped the way PvP players think of them. Files live in assets/minecraft/textures/particle/. */
public enum ParticleElement implements TexturePart {
	CRIT("Crits", "critical_hit.png", "critical_hit"),
	SHARPNESS("Sharpness crits", "enchanted_hit.png", "enchanted_hit"),
	SWEEP("Sweep attack", "sweep_0.png", "sweep_"),
	DAMAGE("Damage hearts", "damage.png", "damage"),
	TOTEM("Totem pop", "glitter_0.png", "glitter_"),
	EXPLOSION("Explosions", "explosion_0.png", "explosion_"),
	SMOKE("Smoke", "generic_0.png", "generic_", "big_smoke_"),
	POTION("Potion swirls", "effect_0.png", "effect_", "spell_"),
	LOVE("Love hearts", "heart.png", "heart", "angry"),
	FLAME("Flames", "flame.png", "flame", "soul_fire_flame", "lava"),
	WATER("Splash & bubbles", "splash_0.png", "splash_", "bubble", "drip_"),
	FIREWORK("Firework sparks", "spark_0.png", "spark_", "flash"),
	GUST("Wind charge gusts", "gust_0.png", "gust_", "small_gust_"),
	ENCHANT("Enchant glyphs", "sga_a.png", "sga_"),
	SONIC("Sonic boom", "sonic_boom_0.png", "sonic_boom_"),
	SNOW("Snowflakes", "snowflake.png", "snowflake");

	public static final String BASE = "assets/minecraft/textures/particle/";
	public static final String KEY_PREFIX = "particle:";

	private final String label;
	private final String preview;
	private final String[] prefixes;

	ParticleElement(String label, String preview, String... prefixes) {
		this.label = label;
		this.preview = BASE + preview;
		this.prefixes = prefixes;
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
		return "Particle";
	}

	/** Which part a pack file belongs to, or null. path is lower case. */
	public static ParticleElement of(String path) {
		if (!path.startsWith(BASE) || !path.endsWith(".png")) return null;
		String rest = path.substring(BASE.length());
		for (ParticleElement e : values()) {
			for (String prefix : e.prefixes) {
				if (rest.startsWith(prefix)) return e;
			}
		}
		return null;
	}
}
