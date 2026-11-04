package dev.itemstudio;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.util.Locale;

/**
 * Custom skyboxes (OptiFine format) only show with a sky mod: Skyboxify, or Nuit (formerly FabricSkyBoxes) together
 * with Nuit Interop. This checks which one is installed, once.
 */
public final class SkyMods {
	private static boolean checked;
	private static String shownBy;
	private static boolean nuitWithoutInterop;

	private SkyMods() {
	}

	/** Name of the installed mod that shows OptiFine-format skies, or null when there is none. */
	public static String shownBy() {
		check();
		return shownBy;
	}

	/** Nuit is installed but not Nuit Interop, which it needs for OptiFine-format skies. */
	public static boolean nuitWithoutInterop() {
		check();
		return nuitWithoutInterop;
	}

	private static void check() {
		if (checked) return;
		checked = true;
		String skyboxify = null;
		boolean nuit = false, interop = false;
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			String id = mod.getMetadata().getId().toLowerCase(Locale.ROOT);
			if (id.contains("skyboxify")) skyboxify = mod.getMetadata().getName();
			if (id.equals("nuit") || id.equals("fabricskyboxes")) nuit = true;
			if (id.contains("interop") && (id.contains("nuit") || id.contains("skyboxes") || id.contains("fsb"))) interop = true;
		}
		if (skyboxify != null) shownBy = skyboxify;
		else if (nuit && interop) shownBy = "Nuit + Nuit Interop";
		nuitWithoutInterop = nuit && !interop && skyboxify == null;
	}
}
