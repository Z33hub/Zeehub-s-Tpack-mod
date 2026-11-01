package dev.itemstudio;

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
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ItemStudioClient implements ClientModInitializer {
	public static final String MOD_ID = "itemstudio";
	public static final Logger LOG = LoggerFactory.getLogger("Item Studio");

	private static final KeyBinding.Category CATEGORY = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
	private static KeyBinding openKey;

	@Override
	public void onInitializeClient() {
		StudioConfig.get(); // load early so problems show up in the log at startup

		openKey = KeyBindingHelper.registerKeyBinding(
			new KeyBinding("key.itemstudio.open", GLFW.GLFW_KEY_RIGHT_SHIFT, CATEGORY));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openKey.wasPressed()) {
				if (client.currentScreen == null) {
					client.setScreen(new StudioScreen(null));
				}
			}
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
}
