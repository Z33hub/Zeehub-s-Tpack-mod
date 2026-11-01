package dev.itemstudio.ui;

import dev.itemstudio.Adjust;
import dev.itemstudio.StudioConfig;
import dev.itemstudio.View;
import dev.itemstudio.build.PackBuilder;
import dev.itemstudio.build.PackWriter;
import dev.itemstudio.pack.FilePackSource;
import dev.itemstudio.pack.ItemInfo;
import dev.itemstudio.pack.PackIndex;
import dev.itemstudio.pack.PackSource;
import dev.itemstudio.pack.Resources;
import dev.itemstudio.pack.VanillaSource;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.joml.Matrix3x2fStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The Item Studio menu. Everything except the search box is drawn by hand so it can look clean and custom:
 * each frame registers its clickable areas in {@link #hits}, and clicks are matched against them.
 */
public final class StudioScreen extends Screen {
	// Colours (ARGB)
	private static final int PANEL = 0xF0101320;
	private static final int SIDE = 0xFF151927;
	private static final int CARD = 0xFF1C2033;
	private static final int CARD_HOVER = 0xFF262B44;
	private static final int LINE = 0xFF2B3150;
	private static final int ACCENT = 0xFF8B6CFF;
	private static final int ACCENT_SOFT = 0x408B6CFF;
	private static final int TEXT = 0xFFF2F3F8;
	private static final int MUTED = 0xFF8D93AB;
	private static final int GOOD = 0xFF5BE49B;
	private static final int WARN = 0xFFFFC857;

	private enum Tab { STYLES, ADJUST }

	private record Hit(int x1, int y1, int x2, int y2, Runnable action, String tooltip, Slider slider) {
		boolean contains(double x, double y) {
			return x >= x1 && x < x2 && y >= y1 && y < y2;
		}
	}

	private record ScrollArea(String key, int x1, int y1, int x2, int y2, int max) {
	}

	/** A draggable value row on the Size & Position tab. */
	private record Slider(String label, float min, float max, float step, float defaultValue,
	                      Supplier<Float> get, Consumer<Float> set, int barX1, int barX2) {
		void setFromMouse(double mouseX) {
			double t = (mouseX - barX1) / Math.max(1, barX2 - barX1);
			t = Math.max(0, Math.min(1, t));
			float raw = (float) (min + t * (max - min));
			set.accept(Math.round(raw / step) * step);
		}
	}

	private final Screen parent;
	private final StudioConfig config = StudioConfig.get();
	private final PreviewTextures previews = new PreviewTextures();
	private final Map<Item, ItemStack> stacks = new HashMap<>();
	private final List<Hit> hits = new ArrayList<>();
	private final List<ScrollArea> scrollAreas = new ArrayList<>();
	private final Map<String, Integer> scroll = new HashMap<>();

	private PackIndex index;
	private Category category = Category.SWORDS;
	private Item selected;
	private Tab tab = Tab.STYLES;
	private View view = View.FIRST_PERSON;
	private boolean bulkMode;
	private String query = "";
	private int pending;
	private String status = "";
	private int statusColor = MUTED;
	private Slider dragging;
	private TextFieldWidget search;

	// layout
	private int px, py, pw, ph, bodyY, bodyH, sideW, gridX, gridW, detailX, detailW, footerY;

	public StudioScreen(Screen parent) {
		super(Text.translatable("itemstudio.title"));
		this.parent = parent;
	}

	// ------------------------------------------------------------------ setup

	@Override
	protected void init() {
		if (index == null) {
			index = PackIndex.scan();
			int count = index.packs().size();
			setStatus(count == 0 ? "No packs found. Put PvP packs in your resourcepacks folder."
				: "Found " + count + " pack" + (count == 1 ? "" : "s"), count == 0 ? WARN : MUTED);
		}
		if (selected == null && !category.items().isEmpty()) selected = category.items().get(0);

		px = 6;
		py = 6;
		pw = width - 12;
		ph = height - 12;
		bodyY = py + 24;
		footerY = py + ph - 22;
		bodyH = footerY - bodyY;
		sideW = width >= 600 ? 110 : 26;
		detailW = Math.max(156, Math.min(240, (int) ((pw - sideW) * 0.46)));
		detailX = px + pw - detailW - 4;
		gridX = px + sideW + 8;
		gridW = detailX - gridX - 6;

		int searchW = Math.min(150, Math.max(70, pw / 4));
		search = new TextFieldWidget(textRenderer, px + pw - searchW - 26, py + 8, searchW - 8, 10, Text.literal("Search"));
		search.setDrawsBackground(false);
		search.setMaxLength(50);
		search.setText(query);
		search.setPlaceholder(Text.literal("Search all items"));
		search.setChangedListener(text -> {
			query = text.trim().toLowerCase(Locale.ROOT);
			scroll.put("grid", 0);
			bulkMode = false;
		});
		addDrawableChild(search);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	@Override
	public void close() {
		if (pending > 0) apply();
		client.setScreen(parent);
	}

	@Override
	public void removed() {
		previews.clear();
		if (index != null) index.close();
		index = null;
		ItemInfo.clearCache();
	}

	// ------------------------------------------------------------------ actions

	private void setStatus(String text, int color) {
		status = text;
		statusColor = color;
	}

	private void changed(boolean finished) {
		pending++;
		config.cleanUp();
		config.save();
		if (finished && config.applyInstantly) {
			apply();
		} else {
			setStatus(pending + " change" + (pending == 1 ? "" : "s") + " waiting - press Apply", WARN);
		}
	}

	private void apply() {
		config.cleanUp();
		config.save();
		PackBuilder builder = new PackBuilder(config, index).build();
		if (!PackWriter.write(builder.files())) {
			setStatus("Could not write the pack - see the game log", WARN);
			return;
		}
		pending = 0;
		if (builder.problems().isEmpty()) {
			setStatus("Applied", GOOD);
		} else {
			setStatus("Applied with " + builder.problems().size() + " problem(s): " + builder.problems().get(0), WARN);
		}
		PackWriter.enableAndReload(!builder.files().isEmpty());
	}

	private void rescan() {
		previews.clear();
		if (index != null) index.close();
		ItemInfo.clearCache();
		index = PackIndex.scan();
		setStatus("Rescanned: " + index.packs().size() + " pack(s)", MUTED);
	}

	private void setStyle(Item item, String sourceId) {
		String id = Registries.ITEM.getId(item).toString();
		if (sourceId == null) config.styles.remove(id);
		else config.styles.put(id, sourceId);
	}

	private String styleOf(Item item) {
		return config.styles.get(Registries.ITEM.getId(item).toString());
	}

	private void useForCategory(String sourceId) {
		int count = 0;
		for (Item item : category.items()) {
			if (sourceId == null) {
				if (styleOf(item) != null) {
					setStyle(item, null);
					count++;
				}
				continue;
			}
			PackSource source = index.get(sourceId);
			if (source == null) continue;
			if (source instanceof VanillaSource || index.provided(source, ItemInfo.of(item)).any()) {
				setStyle(item, sourceId);
				count++;
			}
		}
		changed(true);
		if (!config.applyInstantly) {
			setStatus(count + " item(s) changed - press Apply", WARN);
		}
	}

	private ItemStack stack(Item item) {
		return stacks.computeIfAbsent(item, ItemStack::new);
	}

	private List<Item> gridItems() {
		if (query.isEmpty()) return category.items();
		List<Item> list = new ArrayList<>();
		for (Item item : Category.ALL.items()) {
			String id = Registries.ITEM.getId(item).getPath();
			String name = item.getName().getString().toLowerCase(Locale.ROOT);
			if (id.contains(query.replace(' ', '_')) || name.contains(query)) list.add(item);
		}
		return list;
	}

	// ------------------------------------------------------------------ rendering

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		hits.clear();
		scrollAreas.clear();

		roundRect(ctx, px, py, px + pw, py + ph, PANEL);
		outline(ctx, px, py, px + pw, py + ph, LINE);

		drawHeader(ctx, mouseX, mouseY);
		drawSidebar(ctx, mouseX, mouseY);
		drawGrid(ctx, mouseX, mouseY);
		if (bulkMode && query.isEmpty() && category != Category.ALL) {
			drawBulk(ctx, mouseX, mouseY);
		} else {
			drawDetail(ctx, mouseX, mouseY);
		}
		drawFooter(ctx, mouseX, mouseY);

		super.render(ctx, mouseX, mouseY, delta); // the search box

		for (int i = hits.size() - 1; i >= 0; i--) {
			Hit hit = hits.get(i);
			if (hit.tooltip != null && hit.contains(mouseX, mouseY)) {
				ctx.drawTooltip(Text.literal(hit.tooltip), mouseX, mouseY);
				break;
			}
		}
	}

	private void drawHeader(DrawContext ctx, int mouseX, int mouseY) {
		fill(ctx, px + 10, py + 8, px + 13, py + 17, ACCENT);
		ctx.drawText(textRenderer, "Item Studio", px + 18, py + 9, TEXT, false);
		int titleW = textRenderer.getWidth("Item Studio");
		if (pw > 360) {
			ctx.drawText(textRenderer, "pick a look for every item", px + 26 + titleW, py + 9, MUTED, false);
		}

		// search box frame
		int sx1 = search.getX() - 5, sy1 = search.getY() - 4, sx2 = search.getX() + search.getWidth() + 3, sy2 = search.getY() + 12;
		roundRect(ctx, sx1, sy1, sx2, sy2, search.isFocused() ? CARD_HOVER : CARD);
		outline(ctx, sx1, sy1, sx2, sy2, search.isFocused() ? ACCENT : LINE);

		// close button
		int cx = px + pw - 20, cy = py + 5;
		boolean hover = inside(mouseX, mouseY, cx, cy, cx + 15, cy + 15);
		roundRect(ctx, cx, cy, cx + 15, cy + 15, hover ? 0xFF3A2030 : CARD);
		ctx.drawText(textRenderer, "x", cx + 5, cy + 3, hover ? 0xFFFF7A90 : MUTED, false);
		hits.add(new Hit(cx, cy, cx + 15, cy + 15, this::close, "Close (changes are applied)", null));

		fill(ctx, px + 1, bodyY - 2, px + pw - 1, bodyY - 1, LINE);
	}

	private void drawSidebar(DrawContext ctx, int mouseX, int mouseY) {
		int x1 = px + 4, y1 = bodyY + 2, x2 = px + 4 + sideW, y2 = footerY - 2;
		roundRect(ctx, x1, y1, x2, y2, SIDE);
		Category[] cats = Category.values();
		int rowH = 20;
		int content = cats.length * rowH + 4;
		int off = scrollOf("side", content, y2 - y1);
		scrollAreas.add(new ScrollArea("side", x1, y1, x2, y2, Math.max(0, content - (y2 - y1))));

		ctx.enableScissor(x1, y1, x2, y2);
		int y = y1 + 2 - off;
		for (Category cat : cats) {
			boolean active = cat == category && query.isEmpty();
			boolean hover = inside(mouseX, mouseY, x1, Math.max(y, y1), x2, Math.min(y + rowH, y2));
			if (active) {
				fill(ctx, x1 + 2, y, x2 - 2, y + rowH - 1, ACCENT_SOFT);
				fill(ctx, x1 + 2, y + 3, x1 + 4, y + rowH - 4, ACCENT);
			} else if (hover) {
				fill(ctx, x1 + 2, y, x2 - 2, y + rowH - 1, CARD_HOVER);
			}
			ctx.drawItem(stack(cat.icon), x1 + 5, y + 2);
			if (sideW > 40) {
				ctx.drawText(textRenderer, textRenderer.trimToWidth(cat.label, sideW - 30), x1 + 25, y + 6,
					active ? TEXT : MUTED, false);
			}
			final Category target = cat;
			addHit(x1, y, x2, y + rowH, y1, y2, () -> {
				category = target;
				query = "";
				search.setText("");
				bulkMode = false;
				scroll.put("grid", 0);
				if (!target.items().isEmpty()) selected = target.items().get(0);
			}, sideW > 40 ? null : cat.label);
			y += rowH;
		}
		ctx.disableScissor();
	}

	private void drawGrid(DrawContext ctx, int mouseX, int mouseY) {
		List<Item> items = gridItems();
		boolean searching = !query.isEmpty();
		String title = searching ? "Search: " + items.size() + " found" : category.label;
		ctx.drawText(textRenderer, title, gridX, bodyY + 6, TEXT, false);
		if (!searching) {
			ctx.drawText(textRenderer, items.size() + " items", gridX + textRenderer.getWidth(title) + 6, bodyY + 6, MUTED, false);
		}

		if (!searching && category != Category.ALL) {
			String label = bulkMode ? "Back to item" : "One pack for all";
			int bw = textRenderer.getWidth(label) + 12;
			button(ctx, gridX + gridW - bw, bodyY + 2, bw, 15, label, bulkMode, mouseX, mouseY, () -> {
				bulkMode = !bulkMode;
				scroll.put("detail", 0);
			}, "Use one pack for every " + category.label.toLowerCase(Locale.ROOT) + " item it has");
		}

		int top = bodyY + 22, bottom = footerY - 2;
		int cell = 24;
		int cols = Math.max(1, gridW / cell);
		int rows = (items.size() + cols - 1) / cols;
		int content = rows * cell;
		int off = scrollOf("grid", content, bottom - top);
		scrollAreas.add(new ScrollArea("grid", gridX, top, gridX + gridW, bottom, Math.max(0, content - (bottom - top))));

		if (items.isEmpty()) {
			ctx.drawText(textRenderer, "Nothing matches", gridX, top + 4, MUTED, false);
			return;
		}
		ctx.enableScissor(gridX, top, gridX + gridW, bottom);
		for (int i = 0; i < items.size(); i++) {
			int x = gridX + (i % cols) * cell;
			int y = top + (i / cols) * cell - off;
			if (y + cell < top || y > bottom) continue;
			Item item = items.get(i);
			boolean isSelected = item == selected && !bulkMode;
			boolean hover = inside(mouseX, mouseY, x, Math.max(y, top), x + cell - 2, Math.min(y + cell - 2, bottom));
			roundRect(ctx, x, y, x + cell - 2, y + cell - 2, hover ? CARD_HOVER : CARD);
			if (isSelected) outline(ctx, x, y, x + cell - 2, y + cell - 2, ACCENT);
			ctx.drawItem(stack(item), x + 3, y + 3);
			String id = Registries.ITEM.getId(item).toString();
			if (config.styles.containsKey(id) || config.hasAdjust(id)) {
				fill(ctx, x + cell - 6, y + 2, x + cell - 4, y + 4, ACCENT);
			}
			addHit(x, y, x + cell - 2, y + cell - 2, top, bottom, () -> {
				selected = item;
				bulkMode = false;
				scroll.put("detail", 0);
			}, item.getName().getString());
		}
		ctx.disableScissor();
	}

	private void drawDetail(DrawContext ctx, int mouseX, int mouseY) {
		int x1 = detailX, y1 = bodyY + 2, x2 = detailX + detailW, y2 = footerY - 2;
		roundRect(ctx, x1, y1, x2, y2, SIDE);
		if (selected == null) {
			ctx.drawText(textRenderer, "Pick an item", x1 + 8, y1 + 8, MUTED, false);
			return;
		}
		ItemInfo info = ItemInfo.of(selected);

		// big icon + name
		roundRect(ctx, x1 + 6, y1 + 6, x1 + 42, y1 + 42, CARD);
		Matrix3x2fStack m = ctx.getMatrices();
		m.pushMatrix();
		m.translate(x1 + 8, y1 + 8);
		m.scale(2f, 2f);
		ctx.drawItem(stack(selected), 0, 0);
		m.popMatrix();
		int textW = detailW - 54;
		ctx.drawText(textRenderer, textRenderer.trimToWidth(selected.getName().getString(), textW), x1 + 48, y1 + 10, TEXT, false);
		ctx.drawText(textRenderer, textRenderer.trimToWidth(info.id, textW), x1 + 48, y1 + 22, MUTED, false);
		String style = styleOf(selected);
		String styleName = style == null ? "Default" : index.get(style) == null ? style + " (missing)" : index.get(style).displayName();
		ctx.drawText(textRenderer, textRenderer.trimToWidth(styleName, textW), x1 + 48, y1 + 32, style == null ? MUTED : ACCENT, false);

		// tabs
		int ty = y1 + 48;
		int half = (detailW - 16) / 2;
		tabButton(ctx, x1 + 6, ty, half, "Styles", tab == Tab.STYLES, mouseX, mouseY, () -> tab = Tab.STYLES);
		tabButton(ctx, x1 + 10 + half, ty, half, "Size & Position", tab == Tab.ADJUST, mouseX, mouseY, () -> tab = Tab.ADJUST);

		int top = ty + 20;
		if (tab == Tab.STYLES) {
			drawStyles(ctx, info, x1, top, x2, y2, mouseX, mouseY);
		} else {
			drawAdjust(ctx, info, x1, top, x2, y2, mouseX, mouseY);
		}
	}

	private record Card(String sourceId, String name, String tooltip, Supplier<byte[]> image, String imageKey) {
	}

	private void drawStyles(DrawContext ctx, ItemInfo info, int x1, int top, int x2, int y2, int mouseX, int mouseY) {
		List<Card> cards = new ArrayList<>();
		String main = info.mainTexture;
		cards.add(new Card(null, "Default", "Don't change this item (keeps whatever your other packs do)",
			() -> main == null ? null : Resources.readBelow(main), "below|" + main));
		cards.add(new Card(VanillaSource.ID, "Vanilla", "The normal Minecraft look, even if another pack changes it",
			() -> main == null ? null : Resources.readVanilla(main), "vanilla|" + main));
		List<FilePackSource> packs = index.packsFor(info);
		for (FilePackSource pack : packs) {
			cards.add(new Card(pack.id(), pack.displayName(), pack.displayName(), () -> previewBytes(pack, info),
				pack.id() + "|" + info.id));
		}

		String current = styleOf(selected);
		int hintH = 0;
		if (packs.isEmpty()) {
			hintH = 22;
		} else if (info.hasWornLook()) {
			hintH = 12;
		}

		int innerW = x2 - x1 - 12;
		int cols = Math.max(2, innerW / 58);
		int gap = 4;
		int cardW = (innerW - gap * (cols - 1)) / cols;
		int cardH = cardW + 12;
		int rows = (cards.size() + cols - 1) / cols;
		int content = rows * (cardH + gap) + hintH;
		int bottom = y2 - 4;
		int off = scrollOf("detail", content, bottom - top);
		scrollAreas.add(new ScrollArea("detail", x1, top, x2, bottom, Math.max(0, content - (bottom - top))));

		ctx.enableScissor(x1, top, x2, bottom);
		for (int i = 0; i < cards.size(); i++) {
			Card card = cards.get(i);
			int cx = x1 + 6 + (i % cols) * (cardW + gap);
			int cy = top + (i / cols) * (cardH + gap) - off;
			if (cy + cardH < top || cy > bottom) continue;
			boolean on = current == null ? card.sourceId == null : current.equals(card.sourceId);
			boolean hover = inside(mouseX, mouseY, cx, Math.max(cy, top), cx + cardW, Math.min(cy + cardH, bottom));
			roundRect(ctx, cx, cy, cx + cardW, cy + cardH, hover ? CARD_HOVER : CARD);
			if (on) outline(ctx, cx, cy, cx + cardW, cy + cardH, ACCENT);

			int size = cardW - 8;
			if (!drawPreview(ctx, card.imageKey, card.image, cx + 4, cy + 4, size)) {
				Matrix3x2fStack m = ctx.getMatrices();
				m.pushMatrix();
				float scale = size / 16f;
				m.translate(cx + 4, cy + 4);
				m.scale(scale, scale);
				ctx.drawItem(stack(selected), 0, 0);
				m.popMatrix();
			}
			ctx.drawText(textRenderer, textRenderer.trimToWidth(card.name, cardW - 6), cx + 3, cy + cardW - 1,
				on ? TEXT : MUTED, false);
			if (on) {
				fill(ctx, cx + cardW - 9, cy + 3, cx + cardW - 3, cy + 9, ACCENT);
			}
			final String sourceId = card.sourceId;
			addHit(cx, cy, cx + cardW, cy + cardH, top, bottom, () -> {
				if (sourceId == null ? styleOf(selected) == null : sourceId.equals(styleOf(selected))) return;
				setStyle(selected, sourceId);
				changed(true);
			}, card.tooltip);
		}
		int hy = top + rows * (cardH + gap) - off + 2;
		if (packs.isEmpty()) {
			ctx.drawText(textRenderer, "None of your packs change this item.", x1 + 6, hy, MUTED, false);
			ctx.drawText(textRenderer, "Add packs and press Rescan.", x1 + 6, hy + 10, MUTED, false);
		} else if (info.hasWornLook()) {
			ctx.drawText(textRenderer, textRenderer.trimToWidth("Also changes how it looks when worn/used", innerW), x1 + 6, hy, MUTED, false);
		}
		ctx.disableScissor();
	}

	/** Bytes of the texture that best shows a pack's version of an item. */
	private static byte[] previewBytes(PackSource pack, ItemInfo info) {
		try {
			if (info.mainTexture != null && pack.has(info.mainTexture)) return pack.read(info.mainTexture);
		} catch (Exception ignored) {
		}
		String own = "assets/minecraft/textures/item/" + net.minecraft.util.Identifier.of(info.id).getPath() + ".png";
		try {
			if (pack.has(own)) return pack.read(own);
		} catch (Exception ignored) {
		}
		// a model-only style: use the first layer texture of the pack's own model if it has one
		for (String model : info.models) {
			try {
				String file = dev.itemstudio.pack.Refs.modelFile(model);
				if (!pack.has(file)) continue;
				com.google.gson.JsonElement json = Resources.parse(pack.read(file));
				if (json == null || !json.isJsonObject() || !json.getAsJsonObject().has("textures")) continue;
				com.google.gson.JsonObject textures = json.getAsJsonObject().getAsJsonObject("textures");
				for (String key : textures.keySet()) {
					String ref = dev.itemstudio.pack.Refs.textureRef(textures.get(key));
					if (ref == null) continue;
					String tex = dev.itemstudio.pack.Refs.textureFile(ref);
					if (pack.has(tex)) return pack.read(tex);
					byte[] below = Resources.readBelow(tex);
					if (below != null) return below;
				}
			} catch (Exception ignored) {
			}
		}
		return null;
	}

	/** Draws the top square of a texture scaled to size. Returns false if there is no image. */
	private boolean drawPreview(DrawContext ctx, String key, Supplier<byte[]> bytes, int x, int y, int size) {
		PreviewTextures.Preview p = previews.get(key, bytes);
		if (p == null) return false;
		int square = Math.min(p.width(), p.height());
		ctx.drawTexture(RenderPipelines.GUI_TEXTURED, p.id(), x, y, 0f, 0f, size, size, square, square, p.width(), p.height());
		return true;
	}

	private void drawAdjust(DrawContext ctx, ItemInfo info, int x1, int top, int x2, int y2, int mouseX, int mouseY) {
		// view chips, wrapping onto more rows if needed
		int cx = x1 + 6, cy = top;
		for (View v : View.values()) {
			int w = textRenderer.getWidth(v.label) + 10;
			if (cx + w > x2 - 6) {
				cx = x1 + 6;
				cy += 16;
			}
			boolean on = v == view;
			boolean edited = config.getAdjust(info.id, v) != null && !config.getAdjust(info.id, v).isDefault();
			boolean hover = inside(mouseX, mouseY, cx, cy, cx + w, cy + 13);
			roundRect(ctx, cx, cy, cx + w, cy + 13, on ? ACCENT : hover ? CARD_HOVER : CARD);
			ctx.drawText(textRenderer, v.label, cx + 5, cy + 3, on ? 0xFFFFFFFF : edited ? ACCENT : MUTED, false);
			final View target = v;
			hits.add(new Hit(cx, cy, cx + w, cy + 13, () -> view = target, null, null));
			cx += w + 3;
		}

		int y = cy + 20;
		String itemId = info.id;
		Adjust current = config.getAdjust(itemId, view);
		Adjust shown = current == null ? new Adjust() : current;
		int barX1 = x1 + 10, barX2 = x2 - 10;

		y = slider(ctx, "Size", "%", 0.1f, 3f, 0.05f, 1f, () -> shown.scale,
			v -> config.editAdjust(itemId, view).scale = v, barX1, barX2, y, mouseX, mouseY, 100f);
		y = slider(ctx, "Move left/right", "", -16f, 16f, 0.25f, 0f, () -> shown.x,
			v -> config.editAdjust(itemId, view).x = v, barX1, barX2, y, mouseX, mouseY, 1f);
		y = slider(ctx, "Move up/down", "", -16f, 16f, 0.25f, 0f, () -> shown.y,
			v -> config.editAdjust(itemId, view).y = v, barX1, barX2, y, mouseX, mouseY, 1f);
		y = slider(ctx, "Move forward/back", "", -16f, 16f, 0.25f, 0f, () -> shown.z,
			v -> config.editAdjust(itemId, view).z = v, barX1, barX2, y, mouseX, mouseY, 1f);
		y = slider(ctx, "Tilt (X)", "°", -180f, 180f, 5f, 0f, () -> shown.rotX,
			v -> config.editAdjust(itemId, view).rotX = v, barX1, barX2, y, mouseX, mouseY, 1f);
		y = slider(ctx, "Turn (Y)", "°", -180f, 180f, 5f, 0f, () -> shown.rotY,
			v -> config.editAdjust(itemId, view).rotY = v, barX1, barX2, y, mouseX, mouseY, 1f);
		y = slider(ctx, "Roll (Z)", "°", -180f, 180f, 5f, 0f, () -> shown.rotZ,
			v -> config.editAdjust(itemId, view).rotZ = v, barX1, barX2, y, mouseX, mouseY, 1f);

		int bw = (x2 - x1 - 16) / 2;
		if (y + 15 <= y2 - 2) {
			button(ctx, x1 + 6, y + 2, bw, 14, "Reset " + view.label, false, mouseX, mouseY, () -> {
				Adjust a = config.getAdjust(itemId, view);
				if (a != null && !a.isDefault()) {
					a.reset();
					changed(true);
				}
			}, null);
			button(ctx, x1 + 10 + bw, y + 2, bw, 14, "Reset all views", false, mouseX, mouseY, () -> {
				if (config.hasAdjust(itemId)) {
					config.adjust.remove(itemId);
					changed(true);
				}
			}, null);
		}
		if (y + 34 <= y2 - 2) {
			ctx.drawText(textRenderer, textRenderer.trimToWidth("Right-click a slider to reset it", x2 - x1 - 12),
				x1 + 6, y + 22, MUTED, false);
		}
	}

	private int slider(DrawContext ctx, String label, String unit, float min, float max, float step, float def,
	                   Supplier<Float> get, Consumer<Float> set, int barX1, int barX2, int y, int mouseX, int mouseY,
	                   float displayMul) {
		float value = get.get();
		boolean changedValue = Math.abs(value - def) > 1e-4f;
		ctx.drawText(textRenderer, label, barX1, y, changedValue ? TEXT : MUTED, false);
		String shown = displayMul != 1f ? Math.round(value * displayMul) + unit
			: (step >= 1f ? String.valueOf(Math.round(value)) : String.format(Locale.ROOT, "%.2f", value)) + unit;
		ctx.drawText(textRenderer, shown, barX2 - textRenderer.getWidth(shown), y, changedValue ? ACCENT : MUTED, false);

		int by = y + 11;
		fill(ctx, barX1, by, barX2, by + 3, CARD_HOVER);
		float t = (value - min) / (max - min);
		float t0 = (def - min) / (max - min);
		int kx = barX1 + Math.round(t * (barX2 - barX1));
		int zx = barX1 + Math.round(t0 * (barX2 - barX1));
		fill(ctx, Math.min(kx, zx), by, Math.max(kx, zx), by + 3, ACCENT);
		fill(ctx, zx, by - 1, zx + 1, by + 4, MUTED);
		boolean hover = inside(mouseX, mouseY, barX1 - 3, y, barX2 + 3, y + 17);
		fill(ctx, kx - 2, by - 3, kx + 2, by + 6, hover || dragging != null && dragging.label.equals(label) ? 0xFFFFFFFF : 0xFFD8D2FF);

		Slider slider = new Slider(label, min, max, step, def, get, set, barX1, barX2);
		hits.add(new Hit(barX1 - 3, y, barX2 + 3, y + 17, null, null, slider));
		return y + 20;
	}

	private void drawBulk(DrawContext ctx, int mouseX, int mouseY) {
		int x1 = detailX, y1 = bodyY + 2, x2 = detailX + detailW, y2 = footerY - 2;
		roundRect(ctx, x1, y1, x2, y2, SIDE);
		ctx.drawText(textRenderer, textRenderer.trimToWidth("One pack for all " + category.label, detailW - 12), x1 + 8, y1 + 8, TEXT, false);
		ctx.drawText(textRenderer, textRenderer.trimToWidth("Click a pack to use it for every", detailW - 12), x1 + 8, y1 + 20, MUTED, false);
		ctx.drawText(textRenderer, textRenderer.trimToWidth("item in this group that it has.", detailW - 12), x1 + 8, y1 + 30, MUTED, false);

		List<Card> cards = new ArrayList<>();
		Item first = category.items().isEmpty() ? null : category.items().get(0);
		ItemInfo firstInfo = first == null ? null : ItemInfo.of(first);
		String main = firstInfo == null ? null : firstInfo.mainTexture;
		cards.add(new Card(null, "Default", "Undo: stop changing every item in this group",
			() -> main == null ? null : Resources.readBelow(main), "below|" + main));
		cards.add(new Card(VanillaSource.ID, "Vanilla", "Normal Minecraft look for the whole group",
			() -> main == null ? null : Resources.readVanilla(main), "vanilla|" + main));
		Map<String, Integer> counts = new HashMap<>();
		for (FilePackSource pack : index.packs()) {
			int count = 0;
			ItemInfo shownInfo = null;
			for (Item item : category.items()) {
				ItemInfo info = ItemInfo.of(item);
				if (index.provided(pack, info).any()) {
					count++;
					if (shownInfo == null) shownInfo = info;
				}
			}
			if (count == 0) continue;
			counts.put(pack.id(), count);
			final ItemInfo previewInfo = shownInfo;
			cards.add(new Card(pack.id(), pack.displayName(), pack.displayName() + " (" + count + " item" + (count == 1 ? "" : "s") + ")",
				() -> previewBytes(pack, previewInfo), pack.id() + "|" + previewInfo.id));
		}

		int top = y1 + 44, bottom = y2 - 4;
		int innerW = x2 - x1 - 12;
		int cols = Math.max(2, innerW / 58);
		int gap = 4;
		int cardW = (innerW - gap * (cols - 1)) / cols;
		int cardH = cardW + 22;
		int rows = (cards.size() + cols - 1) / cols;
		int content = rows * (cardH + gap) + (cards.size() == 2 ? 24 : 0);
		int off = scrollOf("detail", content, bottom - top);
		scrollAreas.add(new ScrollArea("detail", x1, top, x2, bottom, Math.max(0, content - (bottom - top))));

		ctx.enableScissor(x1, top, x2, bottom);
		for (int i = 0; i < cards.size(); i++) {
			Card card = cards.get(i);
			int cx = x1 + 6 + (i % cols) * (cardW + gap);
			int cy = top + (i / cols) * (cardH + gap) - off;
			if (cy + cardH < top || cy > bottom) continue;
			boolean hover = inside(mouseX, mouseY, cx, Math.max(cy, top), cx + cardW, Math.min(cy + cardH, bottom));
			roundRect(ctx, cx, cy, cx + cardW, cy + cardH, hover ? CARD_HOVER : CARD);
			int size = cardW - 8;
			if (!drawPreview(ctx, card.imageKey, card.image, cx + 4, cy + 4, size) && first != null) {
				ctx.drawItem(stack(first), cx + cardW / 2 - 8, cy + cardW / 2 - 8);
			}
			ctx.drawText(textRenderer, textRenderer.trimToWidth(card.name, cardW - 6), cx + 3, cy + cardW - 1, TEXT, false);
			String sub = card.sourceId == null ? "reset" : card.sourceId.equals(VanillaSource.ID) ? "all items"
				: counts.get(card.sourceId) + " item" + (counts.get(card.sourceId) == 1 ? "" : "s");
			ctx.drawText(textRenderer, sub, cx + 3, cy + cardW + 9, MUTED, false);
			final String sourceId = card.sourceId;
			addHit(cx, cy, cx + cardW, cy + cardH, top, bottom, () -> useForCategory(sourceId), card.tooltip);
		}
		if (cards.size() == 2) {
			int hy = top + rows * (cardH + gap) - off + 2;
			ctx.drawText(textRenderer, "None of your packs change", x1 + 6, hy, MUTED, false);
			ctx.drawText(textRenderer, "items in this group.", x1 + 6, hy + 10, MUTED, false);
		}
		ctx.disableScissor();
	}

	private void drawFooter(DrawContext ctx, int mouseX, int mouseY) {
		fill(ctx, px + 1, footerY, px + pw - 1, footerY + 1, LINE);
		int y = footerY + 4;
		int right = px + pw - 6;

		String applyLabel = pending > 0 ? "Apply (" + pending + ")" : "Apply";
		int w = textRenderer.getWidth(applyLabel) + 16;
		right -= w;
		button(ctx, right, y, w, 15, applyLabel, true, mouseX, mouseY, this::apply,
			"Build the pack and reload textures");

		String auto = "Auto-apply: " + (config.applyInstantly ? "On" : "Off");
		w = textRenderer.getWidth(auto) + 12;
		right -= w + 4;
		button(ctx, right, y, w, 15, auto, false, mouseX, mouseY, () -> {
			config.applyInstantly = !config.applyInstantly;
			config.save();
			if (config.applyInstantly && pending > 0) apply();
		}, "Apply every change right away (each one reloads textures)");

		boolean small = pw < 520;
		String rescan = small ? "Rescan" : "Rescan packs";
		w = textRenderer.getWidth(rescan) + 12;
		right -= w + 4;
		button(ctx, right, y, w, 15, rescan, false, mouseX, mouseY, this::rescan,
			"Look for new packs in the resourcepacks folder");

		String folder = small ? "Folder" : "Packs folder";
		w = textRenderer.getWidth(folder) + 12;
		right -= w + 4;
		button(ctx, right, y, w, 15, folder, false, mouseX, mouseY,
			() -> Util.getOperatingSystem().open(client.getResourcePackDir()), "Open your resourcepacks folder");

		int statusW = right - (px + 10) - 6;
		if (statusW > 20) {
			ctx.drawText(textRenderer, textRenderer.trimToWidth(status, statusW), px + 10, y + 4, statusColor, false);
		}
	}

	// ------------------------------------------------------------------ small drawing helpers

	private void button(DrawContext ctx, int x, int y, int w, int h, String label, boolean primary,
	                    int mouseX, int mouseY, Runnable action, String tooltip) {
		boolean hover = inside(mouseX, mouseY, x, y, x + w, y + h);
		int bg = primary ? (hover ? 0xFF9C82FF : ACCENT) : (hover ? CARD_HOVER : CARD);
		roundRect(ctx, x, y, x + w, y + h, bg);
		if (!primary) outline(ctx, x, y, x + w, y + h, LINE);
		int tw = textRenderer.getWidth(label);
		ctx.drawText(textRenderer, label, x + (w - tw) / 2, y + (h - 8) / 2, primary ? 0xFFFFFFFF : TEXT, false);
		hits.add(new Hit(x, y, x + w, y + h, action, tooltip, null));
	}

	private void tabButton(DrawContext ctx, int x, int y, int w, String label, boolean on,
	                       int mouseX, int mouseY, Runnable action) {
		boolean hover = inside(mouseX, mouseY, x, y, x + w, y + 15);
		roundRect(ctx, x, y, x + w, y + 15, on ? CARD_HOVER : hover ? CARD : SIDE);
		if (on) fill(ctx, x + 4, y + 13, x + w - 4, y + 15, ACCENT);
		String text = textRenderer.trimToWidth(label, w - 6);
		ctx.drawText(textRenderer, text, x + (w - textRenderer.getWidth(text)) / 2, y + 4, on ? TEXT : MUTED, false);
		hits.add(new Hit(x, y, x + w, y + 15, action, null, null));
	}

	/** Adds a click area clipped to a scrolling region's visible part. */
	private void addHit(int x1, int y1, int x2, int y2, int clipTop, int clipBottom, Runnable action, String tooltip) {
		int top = Math.max(y1, clipTop);
		int bottom = Math.min(y2, clipBottom);
		if (bottom <= top) return;
		hits.add(new Hit(x1, top, x2, bottom, action, tooltip, null));
	}

	private int scrollOf(String key, int content, int view) {
		int max = Math.max(0, content - view);
		int value = Math.max(0, Math.min(max, scroll.getOrDefault(key, 0)));
		scroll.put(key, value);
		return value;
	}

	private static boolean inside(double x, double y, int x1, int y1, int x2, int y2) {
		return x >= x1 && x < x2 && y >= y1 && y < y2;
	}

	private static void fill(DrawContext ctx, int x1, int y1, int x2, int y2, int color) {
		if (x2 > x1 && y2 > y1) ctx.fill(x1, y1, x2, y2, color);
	}

	/** Rectangle with 2px cut corners, drawn without overlapping fills so see-through colours stay even. */
	private static void roundRect(DrawContext ctx, int x1, int y1, int x2, int y2, int color) {
		if (x2 - x1 < 5 || y2 - y1 < 5) {
			fill(ctx, x1, y1, x2, y2, color);
			return;
		}
		fill(ctx, x1 + 2, y1, x2 - 2, y1 + 1, color);
		fill(ctx, x1 + 1, y1 + 1, x2 - 1, y1 + 2, color);
		fill(ctx, x1, y1 + 2, x2, y2 - 2, color);
		fill(ctx, x1 + 1, y2 - 2, x2 - 1, y2 - 1, color);
		fill(ctx, x1 + 2, y2 - 1, x2 - 2, y2, color);
	}

	private static void outline(DrawContext ctx, int x1, int y1, int x2, int y2, int color) {
		fill(ctx, x1 + 2, y1, x2 - 2, y1 + 1, color);
		fill(ctx, x1 + 2, y2 - 1, x2 - 2, y2, color);
		fill(ctx, x1, y1 + 2, x1 + 1, y2 - 2, color);
		fill(ctx, x2 - 1, y1 + 2, x2, y2 - 2, color);
		fill(ctx, x1 + 1, y1 + 1, x1 + 2, y1 + 2, color);
		fill(ctx, x2 - 2, y1 + 1, x2 - 1, y1 + 2, color);
		fill(ctx, x1 + 1, y2 - 2, x1 + 2, y2 - 1, color);
		fill(ctx, x2 - 2, y2 - 2, x2 - 1, y2 - 1, color);
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		double x = click.x(), y = click.y();
		if (search.isMouseOver(x, y)) {
			return super.mouseClicked(click, doubled);
		}
		setFocused(null);
		search.setFocused(false);
		for (int i = hits.size() - 1; i >= 0; i--) {
			Hit hit = hits.get(i);
			if (!hit.contains(x, y)) continue;
			if (hit.slider != null) {
				if (click.button() == 1) {
					hit.slider.set.accept(hit.slider.defaultValue);
					changed(true);
				} else {
					dragging = hit.slider;
					dragging.setFromMouse(x);
				}
				return true;
			}
			if (hit.action != null) {
				client.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.UI_BUTTON_CLICK, 1.0f));
				hit.action.run();
				return true;
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(Click click, double offsetX, double offsetY) {
		if (dragging != null) {
			dragging.setFromMouse(click.x());
			return true;
		}
		return super.mouseDragged(click, offsetX, offsetY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		if (dragging != null) {
			dragging = null;
			changed(true);
			return true;
		}
		return super.mouseReleased(click);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		for (ScrollArea area : scrollAreas) {
			if (inside(mouseX, mouseY, area.x1, area.y1, area.x2, area.y2)) {
				int value = scroll.getOrDefault(area.key, 0) - (int) Math.round(verticalAmount * 24);
				scroll.put(area.key, Math.max(0, Math.min(area.max, value)));
				return true;
			}
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public boolean keyPressed(KeyInput input) {
		if (input.isEscape()) {
			close();
			return true;
		}
		return super.keyPressed(input);
	}
}
