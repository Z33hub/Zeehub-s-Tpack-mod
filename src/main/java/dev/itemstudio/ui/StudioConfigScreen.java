package dev.itemstudio.ui;

import dev.itemstudio.ItemStudioClient;
import dev.itemstudio.StudioConfig;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.KeybindsScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Settings screen for Mod Menu (and the Keybinds button in the Item Studio menu):
 * turn the mod on or off and change its keys.
 */
public final class StudioConfigScreen extends Screen {
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GRAY = 0xFFA0A0A0;

	private final Screen parent;
	private KeyBinding listening;
	private ButtonWidget onOffButton;
	private ButtonWidget openKeyButton;
	private ButtonWidget toggleKeyButton;
	private ButtonWidget nextProfileButton;
	private int top;

	public StudioConfigScreen(Screen parent) {
		super(Text.literal("Item Studio"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int w = 240;
		int x = width / 2 - w / 2;
		top = Math.max(34, height / 2 - 96);
		int y = top;

		onOffButton = addDrawableChild(ButtonWidget.builder(onOffText(), b -> {
				ItemStudioClient.setEnabled(!StudioConfig.get().enabled);
				b.setMessage(onOffText());
			})
			.dimensions(x, y, w, 20)
			.build());

		y += 46;
		openKeyButton = addDrawableChild(ButtonWidget.builder(Text.empty(), b -> listen(ItemStudioClient.openKey()))
			.dimensions(x, y, w, 20)
			.build());
		y += 24;
		toggleKeyButton = addDrawableChild(ButtonWidget.builder(Text.empty(), b -> listen(ItemStudioClient.toggleKey()))
			.dimensions(x, y, w, 20)
			.build());
		y += 24;
		nextProfileButton = addDrawableChild(ButtonWidget.builder(Text.empty(), b -> listen(ItemStudioClient.nextProfileKey()))
			.dimensions(x, y, w, 20)
			.build());

		y += 40;
		boolean fromStudio = parent instanceof StudioScreen;
		if (!fromStudio) {
			addDrawableChild(ButtonWidget.builder(Text.literal("Open Item Studio"), b -> client.setScreen(new StudioScreen(this)))
				.dimensions(x, y, w / 2 - 2, 20)
				.build());
		}
		addDrawableChild(ButtonWidget.builder(Text.literal("All controls..."), b -> client.setScreen(new KeybindsScreen(this, client.options)))
			.dimensions(fromStudio ? x : x + w / 2 + 2, y, fromStudio ? w : w / 2 - 2, 20)
			.build());

		addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, b -> close())
			.dimensions(width / 2 - 100, height - 28, 200, 20)
			.build());

		refreshKeys();
	}

	private static Text onOffText() {
		boolean on = StudioConfig.get().enabled;
		return Text.literal("Item Studio: ")
			.append(on ? Text.literal("ON").formatted(Formatting.GREEN) : Text.literal("OFF").formatted(Formatting.RED));
	}

	private void listen(KeyBinding key) {
		listening = key;
		refreshKeys();
	}

	private void bind(InputUtil.Key key) {
		listening.setBoundKey(key);
		KeyBinding.updateKeysByCode();
		client.options.write();
		listening = null;
		refreshKeys();
	}

	private void refreshKeys() {
		keyButton(openKeyButton, ItemStudioClient.openKey(), "Open menu");
		keyButton(toggleKeyButton, ItemStudioClient.toggleKey(), "Turn on/off");
		keyButton(nextProfileButton, ItemStudioClient.nextProfileKey(), "Next profile");
	}

	private void keyButton(ButtonWidget button, KeyBinding key, String label) {
		if (button == null) return;
		MutableText text = Text.literal(label + ": ");
		if (key == listening) {
			text.append(Text.literal("> press a key <").formatted(Formatting.YELLOW));
			button.setTooltip(null);
		} else {
			String clash = clashWith(key);
			MutableText keyName = key.getBoundKeyLocalizedText().copy();
			text.append(clash != null ? keyName.formatted(Formatting.RED) : keyName);
			button.setTooltip(clash != null ? Tooltip.of(Text.literal("Also used by: " + clash)) : null);
		}
		button.setMessage(text);
	}

	/** Name of another control on the same key, or null. */
	private String clashWith(KeyBinding key) {
		if (key.isUnbound()) return null;
		for (KeyBinding other : client.options.allKeys) {
			if (other != key && key.equals(other)) {
				return Text.translatable(other.getId()).getString();
			}
		}
		return null;
	}

	@Override
	public boolean keyPressed(KeyInput input) {
		if (listening != null) {
			bind(input.isEscape() ? InputUtil.UNKNOWN_KEY : InputUtil.fromKeyCode(input));
			return true;
		}
		return super.keyPressed(input);
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (listening != null) {
			bind(InputUtil.Type.MOUSE.createFromCode(click.button()));
			return true;
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		center(ctx, title, top - 20, WHITE);
		center(ctx, Text.literal("Off puts your normal packs back. Your picks stay saved."), top + 25, GRAY);
		ctx.drawText(textRenderer, Text.literal("Keys"), width / 2 - 120, top + 34, WHITE, true);
		center(ctx, Text.literal("Click a key, then press a key or mouse button. Esc = no key."), top + 121, GRAY);
	}

	private void center(DrawContext ctx, Text text, int y, int color) {
		ctx.drawText(textRenderer, text, (width - textRenderer.getWidth(text)) / 2, y, color, true);
	}

	@Override
	public void close() {
		listening = null;
		client.setScreen(parent);
	}
}
