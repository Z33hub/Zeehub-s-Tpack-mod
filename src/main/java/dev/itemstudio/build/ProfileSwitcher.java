package dev.itemstudio.build;

import dev.itemstudio.ItemStudioClient;
import dev.itemstudio.StudioConfig;
import dev.itemstudio.pack.ItemInfo;
import dev.itemstudio.pack.PackIndex;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

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
			message(Component.literal("Only one profile. Make more in the Item Studio menu.").withStyle(ChatFormatting.GRAY));
			return;
		}
		if (busy) return;
		int i = names.indexOf(config.activeProfile);
		String next = names.get((i + 1) % names.size());
		config.storeActive();
		config.loadProfile(next);
		config.save();
		message(Component.literal("Profile: ").append(Component.literal(next).withStyle(ChatFormatting.AQUA)));
		rebuild();
	}

	/** Builds the generated pack from the current choices off the main thread, then turns it on and reloads. */
	public static void rebuild() {
		Minecraft client = Minecraft.getInstance();
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
				message(Component.literal("Item Studio: could not build the pack - see the game log").withStyle(ChatFormatting.RED));
				return;
			}
			PackWriter.enableAndReload(result == 1);
		}, client);
	}

	private static void message(Component text) {
		Minecraft client = Minecraft.getInstance();
		if (client.gui != null && client.gui.hud != null) client.gui.hud.setOverlayMessage(text, false);
	}
}
