package dev.itemstudio.pack;

import java.util.Locale;

/**
 * Blocks, styled by copying the pack's block textures to the same place, so placed blocks and the block item change
 * together. Patterns are relative to assets/minecraft/textures/; the first block that matches a file owns it.
 */
public enum BlockElement implements TexturePart {
	OBSIDIAN("Obsidian", "minecraft:obsidian", "block/obsidian"),
	CRYING_OBSIDIAN("Crying obsidian", "minecraft:crying_obsidian", "block/crying_obsidian"),
	RESPAWN_ANCHOR("Respawn anchor", "minecraft:respawn_anchor", "block/respawn_anchor_*"),
	GLOWSTONE("Glowstone", "minecraft:glowstone", "block/glowstone"),
	COBWEB("Cobweb", "minecraft:cobweb", "block/cobweb"),
	TNT("TNT", "minecraft:tnt", "block/tnt_*"),
	RAILS("Rails", "minecraft:rail", "block/rail", "block/rail_corner", "block/*_rail", "block/*_rail_on"),
	END_STONE("End stone", "minecraft:end_stone", "block/end_stone", "block/end_stone_bricks"),
	DEEPSLATE("Deepslate", "minecraft:deepslate", "block/*deepslate*"),
	STONE("Stone & ores", "minecraft:stone", "block/stone", "block/cobblestone", "block/mossy_cobblestone",
		"block/smooth_stone*", "block/*stone_bricks", "block/andesite", "block/diorite", "block/granite",
		"block/polished_andesite", "block/polished_diorite", "block/polished_granite", "block/*_ore"),
	GRASS("Grass & dirt", "minecraft:grass_block", "block/grass_block_top", "block/grass_block_side",
		"block/grass_block_side_overlay", "block/dirt", "block/coarse_dirt", "block/dirt_path_*", "block/short_grass",
		"block/tall_grass_*", "block/fern", "block/large_fern_*"),
	COLORS("Grass & leaf colors", null, "colormap/*"),
	SNOW("Snow & ice", "minecraft:snow_block", "block/snow", "block/grass_block_snow", "block/powder_snow",
		"block/ice", "block/packed_ice", "block/blue_ice", "block/frosted_ice_*"),
	SAND("Sand & gravel", "minecraft:sand", "block/sand", "block/red_sand", "block/gravel", "block/*sandstone*"),
	WOOD("Planks & logs", "minecraft:oak_planks", "block/*_planks", "block/*_log", "block/*_log_top"),
	WOOL("Wool", "minecraft:white_wool", "block/*_wool"),
	GLASS("Glass", "minecraft:glass", "block/glass", "block/glass_pane_top", "block/*_stained_glass",
		"block/*_stained_glass_pane_top", "block/tinted_glass"),
	WATER_LAVA("Water & lava", null, "block/water_*", "block/lava_*");

	public static final String KEY_PREFIX = "block:";

	private final String label;
	/** The block's item, used to draw a 3D preview. Null when there is no matching item. */
	private final String itemId;
	private final String[] patterns;

	BlockElement(String label, String itemId, String... patterns) {
		this.label = label;
		this.itemId = itemId;
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
		String first = patterns[0];
		String name = switch (this) {
			case RESPAWN_ANCHOR -> "block/respawn_anchor_top";
			case TNT -> "block/tnt_side";
			case COLORS -> "colormap/grass";
			case GRASS -> "block/grass_block_side";
			case DEEPSLATE -> "block/deepslate";
			case WATER_LAVA -> "block/water_still";
			case WOOD -> "block/oak_planks";
			case WOOL -> "block/white_wool";
			default -> first;
		};
		return "assets/minecraft/textures/" + name + ".png";
	}

	@Override
	public String kind() {
		return "Block";
	}

	@Override
	public String itemId() {
		return itemId;
	}

	/** Cobweb outline packs draw the outline with their own block model, so that comes along with the texture. */
	@Override
	public String modelBlock() {
		return this == COBWEB ? "cobweb" : null;
	}

	String[] patterns() {
		return patterns;
	}

	public static BlockElement of(String path) {
		for (BlockElement e : values()) {
			if (Glob.texture(path, e.patterns)) return e;
		}
		return null;
	}
}
