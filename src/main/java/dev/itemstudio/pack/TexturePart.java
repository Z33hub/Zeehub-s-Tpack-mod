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

	/** Which part a pack file belongs to, or null. path is lower case. */
	static TexturePart of(String path) {
		TexturePart part = HudElement.of(path);
		if (part == null) part = ParticleElement.of(path);
		if (part == null) part = EffectElement.of(path);
		if (part == null) part = BlockElement.of(path);
		return part;
	}
}
