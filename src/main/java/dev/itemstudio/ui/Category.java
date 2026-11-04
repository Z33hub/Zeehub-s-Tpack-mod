package dev.itemstudio.ui;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Item groups shown in the sidebar. */
public enum Category {
	SWORDS("Swords", Items.DIAMOND_SWORD, p -> p.endsWith("_sword")),
	AXES("Axes", Items.DIAMOND_AXE, p -> p.endsWith("_axe")),
	PICKAXES("Pickaxes", Items.DIAMOND_PICKAXE, p -> p.endsWith("_pickaxe")),
	WEAPONS("Mace & Spears", Items.MACE, p -> p.equals("mace") || p.equals("trident") || p.endsWith("_spear")),
	ARMOR("Armor", Items.DIAMOND_CHESTPLATE, p -> p.endsWith("_helmet") || p.endsWith("_chestplate")
		|| p.endsWith("_leggings") || p.endsWith("_boots") || p.equals("elytra")),
	BOWS("Bows", Items.BOW, p -> p.equals("bow") || p.equals("crossbow") || p.endsWith("arrow")),
	DEFENSE("Shields & Totems", Items.TOTEM_OF_UNDYING, p -> p.equals("shield") || p.equals("totem_of_undying")),
	FOOD("Food", Items.GOLDEN_APPLE, p -> Set.of("golden_apple", "enchanted_golden_apple", "golden_carrot",
		"cooked_beef", "cooked_porkchop", "bread", "chorus_fruit", "apple", "cooked_chicken", "cooked_mutton",
		"baked_potato", "pumpkin_pie", "honey_bottle", "milk_bucket").contains(p)),
	THROWABLES("Pots & Pearls", Items.ENDER_PEARL, p -> p.endsWith("potion") || Set.of("ender_pearl",
		"wind_charge", "snowball", "egg", "experience_bottle", "fire_charge", "firework_rocket").contains(p)),
	CRYSTAL("Crystal PvP", Items.END_CRYSTAL, p -> Set.of("end_crystal", "respawn_anchor", "glowstone",
		"obsidian", "crying_obsidian", "tnt", "tnt_minecart", "flint_and_steel", "lava_bucket",
		"water_bucket", "fishing_rod", "rail", "detector_rail", "activator_rail", "powered_rail").contains(p)),
	TOOLS("Other tools", Items.DIAMOND_SHOVEL, p -> p.endsWith("_shovel") || p.endsWith("_hoe")
		|| Set.of("shears", "brush", "spyglass", "clock", "compass", "recovery_compass").contains(p)),
	BLOCKS("Blocks", Items.GRASS_BLOCK, p -> false),
	HUD("HUD", Items.PAPER, p -> false),
	PARTICLES("Particles", Items.BLAZE_POWDER, p -> false),
	EFFECTS("Glint & effects", Items.EXPERIENCE_BOTTLE, p -> false),
	SKY("Sky", Items.SUNFLOWER, p -> false),
	ALL("All items", Items.CHEST, p -> true);

	public final String label;
	public final Item icon;
	private final Predicate<String> match;
	private List<Item> cached;

	Category(String label, Item icon, Predicate<String> match) {
		this.label = label;
		this.icon = icon;
		this.match = match;
	}

	/** Sun & moon, clouds and rain & snow live in the Sky tab, next to the custom skyboxes. */
	private static final List<dev.itemstudio.pack.EffectElement> SKY_EFFECTS = List.of(dev.itemstudio.pack.EffectElement.SKY,
		dev.itemstudio.pack.EffectElement.CLOUDS, dev.itemstudio.pack.EffectElement.WEATHER);

	/** Texture parts shown instead of items (blocks, HUD, particles, effects); empty for item categories. */
	private List<dev.itemstudio.pack.TexturePart> parts;

	public List<dev.itemstudio.pack.TexturePart> parts() {
		if (parts == null) parts = makeParts();
		return parts;
	}

	private List<dev.itemstudio.pack.TexturePart> makeParts() {
		return switch (this) {
			case BLOCKS -> List.of(dev.itemstudio.pack.BlockElement.values());
			case HUD -> List.of(dev.itemstudio.pack.HudElement.values());
			case PARTICLES -> List.of(dev.itemstudio.pack.ParticleElement.values());
			case EFFECTS -> java.util.Arrays.stream(dev.itemstudio.pack.EffectElement.values())
				.filter(e -> !SKY_EFFECTS.contains(e)).map(e -> (dev.itemstudio.pack.TexturePart) e).toList();
			case SKY -> {
				List<dev.itemstudio.pack.TexturePart> list = new ArrayList<>(List.of(dev.itemstudio.pack.SkyElement.values()));
				list.addAll(SKY_EFFECTS);
				yield List.copyOf(list);
			}
			default -> List.of();
		};
	}

	public List<Item> items() {
		if (cached == null) {
			List<Item> list = new ArrayList<>();
			for (Item item : BuiltInRegistries.ITEM) {
				if (item == Items.AIR) continue;
				if (match.test(BuiltInRegistries.ITEM.getKey(item).getPath())) list.add(item);
			}
			cached = list;
		}
		return cached;
	}
}
