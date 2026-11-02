package dev.itemstudio.ui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.itemstudio.ItemStudioClient;
import dev.itemstudio.StudioConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Settings screen for Mod Menu (and the Keybinds button in the Item Studio menu):
 * turn the mod on or off and change its keys.
 */
public final class StudioConfigScreen extends Screen {
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GRAY = 0xFFA0A0A0;

	private final Screen parent;
	private KeyMapping listening;
	private Button onOffButton;
	private Button openKeyButton;
	private Button toggleKeyButton;
	private Button nextProfileButton;
	private int top;

	public StudioConfigScreen(Screen parent) {
		super(Component.literal("Item Studio"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int w = 240;
		int x = width / 2 - w / 2;
		top = Math.max(34, height / 2 - 96);
		int y = top;

		onOffButton = addRenderableWidget(Button.builder(onOffText(), b -> {
				ItemStudioClient.setEnabled(!StudioConfig.get().enabled);
				b.setMessage(onOffText());
			})
			.bounds(x, y, w, 20)
			.build());

		y += 46;
		openKeyButton = addRenderableWidget(Button.builder(Component.empty(), b -> listen(ItemStudioClient.openKey()))
			.bounds(x, y, w, 20)
			.build());
		y += 24;
		toggleKeyButton = addRenderableWidget(Button.builder(Component.empty(), b -> listen(ItemStudioClient.toggleKey()))
			.bounds(x, y, w, 20)
			.build());
		y += 24;
		nextProfileButton = addRenderableWidget(Button.builder(Component.empty(), b -> listen(ItemStudioClient.nextProfileKey()))
			.bounds(x, y, w, 20)
			.build());

		y += 40;
		boolean fromStudio = parent instanceof StudioScreen;
		if (!fromStudio) {
			addRenderableWidget(Button.builder(Component.literal("Open Item Studio"), b -> minecraft.gui.setScreen(new StudioScreen(this)))
				.bounds(x, y, w / 2 - 2, 20)
				.build());
		}
		addRenderableWidget(Button.builder(Component.literal("All controls..."), b -> minecraft.gui.setScreen(new KeyBindsScreen(this, minecraft.options)))
			.bounds(fromStudio ? x : x + w / 2 + 2, y, fromStudio ? w : w / 2 - 2, 20)
			.build());

		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
			.bounds(width / 2 - 100, height - 28, 200, 20)
			.build());

		refreshKeys();
	}

	private static Component onOffText() {
		boolean on = StudioConfig.get().enabled;
		return Component.literal("Item Studio: ")
			.append(on ? Component.literal("ON").withStyle(ChatFormatting.GREEN) : Component.literal("OFF").withStyle(ChatFormatting.RED));
	}

	private void listen(KeyMapping key) {
		listening = key;
		refreshKeys();
	}

	private void bind(InputConstants.Key key) {
		listening.setKey(key);
		KeyMapping.resetMapping();
		minecraft.options.save();
		listening = null;
		refreshKeys();
	}

	private void refreshKeys() {
		keyButton(openKeyButton, ItemStudioClient.openKey(), "Open menu");
		keyButton(toggleKeyButton, ItemStudioClient.toggleKey(), "Turn on/off");
		keyButton(nextProfileButton, ItemStudioClient.nextProfileKey(), "Next profile");
	}

	private void keyButton(Button button, KeyMapping key, String label) {
		if (button == null) return;
		MutableComponent text = Component.literal(label + ": ");
		if (key == listening) {
			text.append(Component.literal("> press a key <").withStyle(ChatFormatting.YELLOW));
			button.setTooltip(null);
		} else {
			String clash = clashWith(key);
			MutableComponent keyName = key.getTranslatedKeyMessage().copy();
			text.append(clash != null ? keyName.withStyle(ChatFormatting.RED) : keyName);
			button.setTooltip(clash != null ? Tooltip.create(Component.literal("Also used by: " + clash)) : null);
		}
		button.setMessage(text);
	}

	/** Name of another control on the same key, or null. */
	private String clashWith(KeyMapping key) {
		if (key.isUnbound()) return null;
		for (KeyMapping other : minecraft.options.keyMappings) {
			if (other != key && key.same(other)) {
				return Component.translatable(other.getName()).getString();
			}
		}
		return null;
	}

	@Override
	public boolean keyPressed(KeyEvent input) {
		if (listening != null) {
			bind(input.isEscape() ? InputConstants.UNKNOWN : InputConstants.getKey(input));
			return true;
		}
		return super.keyPressed(input);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
		if (listening != null) {
			bind(InputConstants.Type.MOUSE.getOrCreate(click.button()));
			return true;
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
		super.extractRenderState(ctx, mouseX, mouseY, delta);
		center(ctx, title, top - 20, WHITE);
		center(ctx, Component.literal("Off puts your normal packs back. Your picks stay saved."), top + 25, GRAY);
		ctx.text(font, Component.literal("Keys"), width / 2 - 120, top + 34, WHITE, true);
		center(ctx, Component.literal("Click a key, then press a key or mouse button. Esc = no key."), top + 121, GRAY);
	}

	private void center(GuiGraphicsExtractor ctx, Component text, int y, int color) {
		ctx.text(font, text, (width - font.width(text)) / 2, y, color, true);
	}

	@Override
	public void onClose() {
		listening = null;
		minecraft.gui.setScreen(parent);
	}
}
