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

	/**
	 * Builds the generated pack from the current choices in the background (using the pack scan the menu keeps),
	 * then turns it on and reloads.
	 */
	public static void rebuild() {
		Minecraft client = Minecraft.getInstance();
		busy = true;
		StudioConfig choices = StudioConfig.get().copyForBuild();
		dev.itemstudio.pack.PackIndexCache.get().thenApplyAsync(index -> {
			try {
				PackBuilder builder = new PackBuilder(choices, index).build();
				PackWriter.Result written = PackWriter.writeChanges(builder.files());
				return new int[]{written.ordinal(), builder.files().isEmpty() ? 0 : 1};
			} finally {
				index.close();
			}
		}, dev.itemstudio.Workers.BACKGROUND).whenCompleteAsync((result, error) -> {
			busy = false;
			if (error != null || result == null || result[0] == PackWriter.Result.FAILED.ordinal()) {
				ItemStudioClient.LOG.warn("Could not switch profile", error);
				message(Component.literal("Item Studio: could not build the pack - see the game log").withStyle(ChatFormatting.RED));
				return;
			}
			PackWriter.enableAndReload(result[1] == 1, result[0] == PackWriter.Result.CHANGED.ordinal());
		}, client);
	}

	private static void message(Component text) {
		Minecraft client = Minecraft.getInstance();
		if (client.gui != null && client.gui.hud != null) client.gui.hud.setOverlayMessage(text, false);
	}
}
