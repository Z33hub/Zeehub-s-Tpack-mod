package dev.itemstudio.pack;

import java.util.ArrayList;
import java.util.List;

/**
 * A group of textures the game finds by name (a HUD part, a particle...), styled by copying the chosen pack's
 * files to the same place.
 */
public interface TexturePart {
	/** Config key, like "hud:hearts" or "particle:crit". */
	String key();

	String label();

	/** File used for the preview picture. */
	String preview();

	/** What the category shows under the name, like "HUD" or "Particle". */
	String kind();

	/** An item that shows this part in 3D (a block's item), or null to show the preview texture flat. */
	default String itemId() {
		return null;
	}

	/** True for particles, which can be made bigger or smaller. */
	default boolean resizable() {
		return this instanceof ParticleElement;
	}

	/** True for fire, which can be pushed down (low fire). Uses the same saved number as particle size. */
	default boolean heightAdjustable() {
		return this == EffectElement.FIRE;
	}

	static List<TexturePart> all() {
		List<TexturePart> list = new ArrayList<>(List.of(HudElement.values()));
		list.addAll(List.of(ParticleElement.values()));
		list.addAll(List.of(BlockElement.values()));
		list.addAll(List.of(EffectElement.values()));
		return list;
	}

	static TexturePart byKey(String key) {
		if (key == null) return null;
		for (TexturePart part : all()) {
			if (part.key().equals(key)) return part;
		}
		return null;
	}

	/**
	 * Which part a pack file belongs to, or null. path is lower case. Runs for every file of every pack during a
	 * scan, so it first looks only at the folder: most files (item textures, models, sounds) are ruled out at once.
	 */
	static TexturePart of(String path) {
		String base = "assets/minecraft/textures/";
		if (!path.startsWith(base) || !path.endsWith(".png")) return null;
		int dirEnd = path.indexOf('/', base.length());
		if (dirEnd < 0) return null;
		String dir = path.substring(base.length(), dirEnd);
		if (dir.equals("gui")) {
			TexturePart hud = HudElement.of(path);
			if (hud != null) return hud;
		} else if (dir.equals("particle")) {
			return ParticleElement.of(path);
		}
		List<Rule> rules = Rules.BY_FOLDER.get(dir);
		if (rules == null) return null;
		String rest = path.substring(base.length(), path.length() - 4);
		for (Rule rule : rules) {
			if (rule.matches(rest)) return rule.part();
		}
		return null;
	}

	/** One file pattern of an effect or block, filed under its first folder ("block", "misc"...). */
	record Rule(String pattern, TexturePart part, String[] pieces) {
		Rule(String pattern, TexturePart part) {
			this(pattern, part, pattern.indexOf('*') < 0 ? null : pattern.split("\\*", -1));
		}

		boolean matches(String text) {
			if (pieces == null) return pattern.equals(text);
			return Glob.matchPieces(pieces, text);
		}
	}

	final class Rules {
		/** Effects before blocks, in their own order, so the first match wins like before. */
		static final java.util.Map<String, List<Rule>> BY_FOLDER = build();

		private Rules() {
		}

		private static java.util.Map<String, List<Rule>> build() {
			java.util.Map<String, List<Rule>> map = new java.util.HashMap<>();
			for (EffectElement e : EffectElement.values()) {
				for (String p : e.patterns()) map.computeIfAbsent(p.substring(0, p.indexOf('/')), k -> new ArrayList<>()).add(new Rule(p, e));
			}
			for (BlockElement b : BlockElement.values()) {
				for (String p : b.patterns()) map.computeIfAbsent(p.substring(0, p.indexOf('/')), k -> new ArrayList<>()).add(new Rule(p, b));
			}
			return map;
		}
	}
}
