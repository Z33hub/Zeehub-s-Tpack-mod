package dev.itemstudio;

import dev.itemstudio.build.PackWriter;
import dev.itemstudio.ui.StudioScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ItemStudioClient implements ClientModInitializer {
	public static final String MOD_ID = "itemstudio";
	public static final Logger LOG = LoggerFactory.getLogger("Item Studio");

	private static final KeyBinding.Category CATEGORY = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
	private static KeyBinding openKey;
	private static KeyBinding toggleKey;

	@Override
	public void onInitializeClient() {
		StudioConfig.get(); // load early so problems show up in the log at startup

		openKey = KeyBindingHelper.registerKeyBinding(
			new KeyBinding("key.itemstudio.open", GLFW.GLFW_KEY_RIGHT_SHIFT, CATEGORY));
		// No key by default, so it can't clash with anything. Set it in Mod Menu or Options > Controls.
		toggleKey = KeyBindingHelper.registerKeyBinding(
			new KeyBinding("key.itemstudio.toggle", GLFW.GLFW_KEY_UNKNOWN, CATEGORY));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openKey.wasPressed()) {
				if (client.currentScreen == null) {
					client.setScreen(new StudioScreen(null));
				}
			}
			boolean toggle = false;
			while (toggleKey.wasPressed()) {
				toggle = true; // one toggle per tick, however many presses piled up
			}
			if (toggle) setEnabled(!StudioConfig.get().enabled);
		});

		// Small button in the top-left corner of the pause menu.
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (screen instanceof GameMenuScreen) {
				Screens.getButtons(screen).add(ButtonWidget.builder(Text.literal("Item Studio"),
						b -> MinecraftClient.getInstance().setScreen(new StudioScreen(screen)))
					.dimensions(6, 6, 80, 20)
					.build());
			}
		});
	}

	public static KeyBinding openKey() {
		return openKey;
	}

	public static KeyBinding toggleKey() {
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
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.inGameHud != null) {
			client.inGameHud.setOverlayMessage(Text.literal("Item Studio: ")
				.append(on ? Text.literal("ON").formatted(Formatting.GREEN) : Text.literal("OFF").formatted(Formatting.RED)), false);
		}
	}
}
