package dev.itemstudio;

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

	@Override
	public void onInitializeClient() {
		StudioConfig.get(); // load early so problems show up in the log at startup

		openKey = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.itemstudio.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, CATEGORY));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openKey.consumeClick()) {
				if (client.gui.screen() == null) {
					client.gui.setScreen(new StudioScreen(null));
				}
			}
		});

		// Small button in the top-left corner of the pause menu.
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (screen instanceof PauseScreen) {
				Screens.getButtons(screen).add(Button.builder(Component.literal("Item Studio"),
						b -> Minecraft.getInstance().gui.setScreen(new StudioScreen(screen)))
					.bounds(6, 6, 80, 20)
					.build());
			}
		});
	}
}
