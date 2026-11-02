package dev.itemstudio.build;

import dev.itemstudio.ItemStudioClient;
import dev.itemstudio.StudioConfig;
import dev.itemstudio.pack.ItemInfo;
import dev.itemstudio.pack.PackIndex;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Switches profiles from the game (the Next profile key): scans packs and builds in the background, then reloads. */
public final class ProfileSwitcher {
	private static boolean busy;

	private ProfileSwitcher() {
	}

	public static void next() {
		StudioConfig config = StudioConfig.get();
		List<String> names = new ArrayList<>(config.profiles.keySet());
		if (names.size() < 2) {
			message(Text.literal("Only one profile. Make more in the Item Studio menu.").formatted(Formatting.GRAY));
			return;
		}
		if (busy) return;
		int i = names.indexOf(config.activeProfile);
		String next = names.get((i + 1) % names.size());
		config.storeActive();
		config.loadProfile(next);
		config.save();
		message(Text.literal("Profile: ").append(Text.literal(next).formatted(Formatting.AQUA)));
		rebuild();
	}

	/** Builds the generated pack from the current choices off the main thread, then turns it on and reloads. */
	public static void rebuild() {
		MinecraftClient client = MinecraftClient.getInstance();
		busy = true;
		CompletableFuture.supplyAsync(() -> {
			PackIndex index = PackIndex.scan();
			try {
				ItemInfo.clearCache();
				PackBuilder builder = new PackBuilder(StudioConfig.get(), index).build();
				if (!PackWriter.write(builder.files())) return -1;
				return builder.files().isEmpty() ? 0 : 1;
			} finally {
				index.close();
			}
		}).whenCompleteAsync((result, error) -> {
			busy = false;
			if (error != null || result == null || result < 0) {
				ItemStudioClient.LOG.warn("Could not switch profile", error);
				message(Text.literal("Item Studio: could not build the pack - see the game log").formatted(Formatting.RED));
				return;
			}
			PackWriter.enableAndReload(result == 1);
		}, client);
	}

	private static void message(Text text) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.inGameHud != null) client.inGameHud.setOverlayMessage(text, false);
	}
}
