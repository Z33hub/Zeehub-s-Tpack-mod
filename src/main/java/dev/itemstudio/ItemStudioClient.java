package dev.itemstudio;

import dev.itemstudio.build.PackWriter;
import dev.itemstudio.ui.StudioScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ItemStudioClient implements ClientModInitializer {
	public static final String MOD_ID = "itemstudio";
	public static final Logger LOG = LoggerFactory.getLogger("Item Studio");

	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
	private static KeyMapping openKey;
	private static KeyMapping toggleKey;

	@Override
	public void onInitializeClient() {
		StudioConfig.get(); // load early so problems show up in the log at startup

		openKey = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.itemstudio.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, CATEGORY));
		// No key by default, so it can't clash with anything. Set it in Mod Menu or Options > Controls.
		toggleKey = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.itemstudio.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openKey.consumeClick()) {
				if (client.gui.screen() == null) {
					client.gui.setScreen(new StudioScreen(null));
				}
			}
			boolean toggle = false;
			while (toggleKey.consumeClick()) {
				toggle = true; // one toggle per tick, however many presses piled up
			}
			if (toggle) setEnabled(!StudioConfig.get().enabled);
		});

		// Small button in the top-left corner of the pause menu.
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (screen instanceof PauseScreen) {
				Screens.getWidgets(screen).add(Button.builder(Component.literal("Item Studio"),
						b -> Minecraft.getInstance().gui.setScreen(new StudioScreen(screen)))
					.bounds(6, 6, 80, 20)
					.build());
			}
		});
	}

	public static KeyMapping openKey() {
		return openKey;
	}

	public static KeyMapping toggleKey() {
		return toggleKey;
	}

	/**
	 * Turns the mod on or off. Off takes the generated pack out of the active packs, so the game shows
	 * the player's normal packs again. Picks stay saved and come back when it is turned on.
	 */
	public static void setEnabled(boolean on) {
		StudioConfig config = StudioConfig.get();
		if (config.enabled == on) return;
		config.enabled = on;
		config.save();
		PackWriter.enableAndReload(PackWriter.hasPack());
		Minecraft client = Minecraft.getInstance();
		if (client.gui != null && client.gui.hud != null) {
			client.gui.hud.setOverlayMessage(Component.literal("Item Studio: ")
				.append(on ? Component.literal("ON").withStyle(ChatFormatting.GREEN) : Component.literal("OFF").withStyle(ChatFormatting.RED)), false);
		}
	}
}
