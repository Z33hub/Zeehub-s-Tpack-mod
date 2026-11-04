package dev.itemstudio.pack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Custom skyboxes in the OptiFine / MCPatcher format (assets/minecraft/optifine/sky/world0, world-1, world1, or the older
 * assets/minecraft/mcpatcher/sky/). The game can't draw these by itself: they show with a sky mod (Skyboxify, or
 * Nuit with Nuit Interop). The chosen pack's whole sky folder for that world is copied to the same place, plus any
 * pictures its layers point to elsewhere (source=...).
 */
public enum SkyElement implements TexturePart {
	OVERWORLD("Overworld skybox", "world0", "environment/celestial/sun", "environment/sun"),
	NETHER("Nether skybox", "world-1", "block/netherrack"),
	END("End skybox", "world1", "environment/end_sky", "environment/celestial/end_sky");

	public static final String KEY_PREFIX = "sky:";
	private static final String OPTIFINE = "assets/minecraft/optifine/";
	private static final String MCPATCHER = "assets/minecraft/mcpatcher/";
	private static final String[] ROOTS = {OPTIFINE + "sky/", MCPATCHER + "sky/"};

	private final String label;
	private final String world;
	private final String[] iconFiles;

	SkyElement(String label, String world, String... icons) {
		this.label = label;
		this.world = world;
		this.iconFiles = new String[icons.length];
		for (int i = 0; i < icons.length; i++) iconFiles[i] = "assets/minecraft/textures/" + icons[i] + ".png";
	}

	@Override
	public String key() {
		return KEY_PREFIX + name().toLowerCase(Locale.ROOT);
	}

	@Override
	public String label() {
		return label;
	}

	/** Usual name of the first layer's picture; packs may name it differently (see firstPicture). */
	@Override
	public String preview() {
		return ROOTS[0] + world + "/sky1.png";
	}

	@Override
	public String kind() {
		return "Custom sky";
	}

	/** Folder name of this world in the sky folder: world0, world-1, world1. */
	public String world() {
		return world;
	}

	/**
	 * Small game textures to use as this skybox's icon in the menu (sun, netherrack, End sky), first one that
	 * exists. Newer game versions moved the sun and moon into environment/celestial/.
	 */
	public String[] iconFiles() {
		return iconFiles.clone();
	}

	/** The skybox a pack file belongs to, or null. path is lower case. */
	static SkyElement of(String path) {
		for (String root : ROOTS) {
			if (!path.startsWith(root)) continue;
			int slash = path.indexOf('/', root.length());
			if (slash < 0) return null;
			String w = path.substring(root.length(), slash);
			for (SkyElement e : values()) {
				if (e.world.equals(w)) return e;
			}
			return null;
		}
		return null;
	}

	static boolean isSkyPath(String path) {
		return path.startsWith(ROOTS[0]) || path.startsWith(ROOTS[1]);
	}

	/** True for a sky layer's settings file (sky1.properties...), not the sun / moon ones. */
	public static boolean isLayer(String file) {
		String name = file.substring(file.lastIndexOf('/') + 1);
		return name.endsWith(".properties") && !name.startsWith("sun") && !name.startsWith("moon");
	}

	/** The picture to show for a pack's skybox: the first layer's picture (sky1.png...), else the first picture. */
	public static String firstPicture(List<String> files) {
		String first = null;
		for (String f : files) {
			if (!f.endsWith(".png")) continue;
			String name = f.substring(f.lastIndexOf('/') + 1);
			if (name.startsWith("sky")) return f;
			if (first == null && !name.startsWith("sun") && !name.startsWith("moon")) first = f;
		}
		if (first != null) return first;
		for (String f : files) {
			if (f.endsWith(".png")) return f;
		}
		return null;
	}

	/**
	 * Pictures a sky settings file points to (source=...), as pack paths to try, lower case. OptiFine paths: "./x"
	 * is next to the settings file, "~/x" is in the optifine (or mcpatcher) folder, "ns:path" is in that namespace,
	 * anything else is from assets/minecraft/ (and, to be safe, also tried next to the settings file).
	 */
	public static List<String> sources(String propertiesFile, String text) {
		List<String> list = new ArrayList<>();
		String folder = propertiesFile.substring(0, propertiesFile.lastIndexOf('/') + 1);
		String home = propertiesFile.startsWith(MCPATCHER) ? MCPATCHER : OPTIFINE;
		for (String raw : text.split("\\R")) {
			String line = raw.trim();
			if (line.startsWith("#") || line.startsWith("!")) continue;
			// key and value are split by the first '=' or ':' (Java properties), so "source=minecraft:x" works
			int eq = -1;
			for (int i = 0; i < line.length(); i++) {
				char ch = line.charAt(i);
				if (ch == '=' || ch == ':') {
					eq = i;
					break;
				}
			}
			if (eq < 0) continue;
			if (!line.substring(0, eq).trim().equalsIgnoreCase("source")) continue;
			String v = line.substring(eq + 1).trim().replace('\\', '/').toLowerCase(Locale.ROOT);
			if (v.isEmpty()) continue;
			if (!v.endsWith(".png")) v = v + ".png";
			if (v.startsWith("./")) {
				list.add(folder + v.substring(2));
			} else if (v.startsWith("~/")) {
				list.add(home + v.substring(2));
			} else if (v.startsWith("assets/")) {
				list.add(v);
			} else if (v.indexOf(':') > 0) {
				int c = v.indexOf(':');
				list.add("assets/" + v.substring(0, c) + "/" + v.substring(c + 1));
			} else {
				list.add("assets/minecraft/" + v);
				list.add(folder + v);
			}
		}
		return list;
	}
}
