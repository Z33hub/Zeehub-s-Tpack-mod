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
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
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
	/** Packs are read on a background thread so opening the menu never freezes the game. */
	private java.util.concurrent.CompletableFuture<PackIndex> loading;
	private String searchCacheQuery;
	private List<Item> searchCache = List.of();
	private Category category = Category.SWORDS;
	private Item selected;
	private Tab tab = Tab.STYLES;
	private View view = View.FIRST_PERSON;
	private boolean bulkMode;
	private String query = "";
	/** Number of items that differ from what was last applied. */
	private int pending;
	/** Item id -> its saved choices at the last apply, to work out what is still waiting. */
	private Map<String, String> applied;
	private static final com.google.gson.Gson SNAPSHOT_GSON = new com.google.gson.Gson();
	private String status = "";
	private int statusColor = MUTED;
	private Slider dragging;
	private EditBox search;
	/** Filters the style cards by pack name. */
	private EditBox styleSearch;
	private String styleQuery = "";

	// layout
	private int px, py, pw, ph, bodyY, bodyH, sideW, gridX, gridW, detailX, detailW, footerY;

	public StudioScreen(Screen parent) {
		super(Component.translatable("itemstudio.title"));
		this.parent = parent;
	}

	// ------------------------------------------------------------------ setup

	@Override
	protected void init() {
		if (index == null && loading == null) {
			startScan("Loading packs...");
		}
		if (applied == null) applied = snapshot();
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
		search = new EditBox(font, px + pw - searchW - 26, py + 8, searchW - 8, 10, Component.literal("Search"));
		search.setBordered(false);
		search.setMaxLength(50);
		search.setValue(query);
		search.setHint(Component.literal("Search all items"));
		search.setResponder(text -> {
			query = text.trim().toLowerCase(Locale.ROOT);
			scroll.put("grid", 0);
			bulkMode = false;
		});
		addRenderableWidget(search);

		styleSearch = new EditBox(font, detailX + 10, bodyY + 74, detailW - 24, 10, Component.literal("Search packs"));
		styleSearch.setBordered(false);
		styleSearch.setMaxLength(50);
		styleSearch.setValue(styleQuery);
		styleSearch.setHint(Component.literal("Search packs"));
		styleSearch.setResponder(text -> {
			styleQuery = text.trim().toLowerCase(Locale.ROOT);
			scroll.put("detail", 0);
		});
		styleSearch.visible = false;
		addRenderableWidget(styleSearch);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		if (pending > 0) apply();
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void removed() {
		previews.clear();
		if (index != null) {
			index.close();
		} else if (loading != null) {
			loading.thenAccept(PackIndex::close); // close it once the background scan finishes
		}
		index = null;
		loading = null;
		ItemInfo.clearCache();
	}

	private void startScan(String message) {
		setStatus(message, MUTED);
		loading = java.util.concurrent.CompletableFuture.supplyAsync(PackIndex::scan);
	}

	/** Picks up the background scan when it has finished. Returns true once packs are ready. */
	private boolean ready() {
		if (index != null) return true;
		if (loading == null || !loading.isDone()) return false;
		try {
			index = loading.join();
			int count = index.packs().size();
			setStatus(count == 0 ? "No packs found. Put PvP packs in your resourcepacks folder."
				: "Found " + count + " pack" + (count == 1 ? "" : "s"), count == 0 ? WARN : MUTED);
		} catch (Exception e) {
			dev.itemstudio.ItemStudioClient.LOG.error("Pack scan failed", e);
			setStatus("Could not read your packs - see the game log", WARN);
			loading = null;
			return false;
		}
		return true;
	}

	/** For actions that can't wait: finishes the scan now if it is still running. */
	private PackIndex indexNow() {
		if (index == null) {
			index = loading != null ? loading.join() : PackIndex.scan();
		}
		return index;
	}

	// ------------------------------------------------------------------ actions

	private void setStatus(String text, int color) {
		status = text;
		statusColor = color;
	}

	/** Each item's current choices (style + size/position) as text, so two states can be compared. */
	private Map<String, String> snapshot() {
		config.cleanUp();
		Map<String, String> map = new HashMap<>();
		java.util.Set<String> ids = new java.util.HashSet<>(config.styles.keySet());
		ids.addAll(config.adjust.keySet());
		for (String id : ids) {
			map.put(id, config.styles.get(id) + "|" + SNAPSHOT_GSON.toJson(config.adjust.get(id)));
		}
		return map;
	}

	/** Counts items whose choices differ from the last apply. Picking A, then B, then C for one item is 1. */
	private int countPending() {
		Map<String, String> now = snapshot();
		java.util.Set<String> ids = new java.util.HashSet<>(now.keySet());
		ids.addAll(applied.keySet());
		int count = 0;
		for (String id : ids) {
			if (!java.util.Objects.equals(now.get(id), applied.get(id))) count++;
		}
		return count;
	}

	private void changed(boolean finished) {
		config.cleanUp();
		config.save();
		pending = countPending();
		if (pending == 0) {
			setStatus("Nothing waiting - matches what's applied", MUTED);
		} else if (finished && config.applyInstantly) {
			apply();
		} else {
			setStatus(pending + " item" + (pending == 1 ? "" : "s") + " changed - press Apply", WARN);
		}
	}

	private void apply() {
		config.cleanUp();
		config.save();
		PackBuilder builder = new PackBuilder(config, indexNow()).build();
		if (!PackWriter.write(builder.files())) {
			setStatus("Could not write the pack - see the game log", WARN);
			return;
		}
		pending = 0;
		applied = snapshot();
		if (builder.problems().isEmpty()) {
			setStatus("Applied", GOOD);
		} else {
			setStatus("Applied with " + builder.problems().size() + " problem(s): " + builder.problems().get(0), WARN);
		}
		PackWriter.enableAndReload(!builder.files().isEmpty());
	}

	private void rescan() {
		if (index == null) return; // a scan is already running
		previews.clear();
		index.close();
		index = null;
		ItemInfo.clearCache();
		startScan("Rescanning packs...");
	}

	private void setStyle(Item item, String sourceId) {
		String id = BuiltInRegistries.ITEM.getKey(item).toString();
		if (sourceId == null) config.styles.remove(id);
		else config.styles.put(id, sourceId);
	}

	private String styleOf(Item item) {
		return config.styles.get(BuiltInRegistries.ITEM.getKey(item).toString());
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
	}

	private ItemStack stack(Item item) {
		return stacks.computeIfAbsent(item, ItemStack::new);
	}

	private List<Item> gridItems() {
		if (query.isEmpty()) return category.items();
		if (query.equals(searchCacheQuery)) return searchCache; // only search again when the text changes
		List<Item> list = new ArrayList<>();
		for (Item item : Category.ALL.items()) {
			String id = BuiltInRegistries.ITEM.getKey(item).getPath();
			String name = stack(item).getHoverName().getString().toLowerCase(Locale.ROOT);
			if (id.contains(query.replace(' ', '_')) || name.contains(query)) list.add(item);
		}
		searchCacheQuery = query;
		searchCache = list;
		return list;
	}

	// ------------------------------------------------------------------ rendering

	@Override
	public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
		hits.clear();
		scrollAreas.clear();

		roundRect(ctx, px, py, px + pw, py + ph, PANEL);
		outline(ctx, px, py, px + pw, py + ph, LINE);

		drawHeader(ctx, mouseX, mouseY);
		drawSidebar(ctx, mouseX, mouseY);
		drawGrid(ctx, mouseX, mouseY);
		styleSearch.visible = false; // shown again below if the current panel has it
		if (bulkMode && query.isEmpty() && category != Category.ALL) {
			drawBulk(ctx, mouseX, mouseY);
		} else {
			drawDetail(ctx, mouseX, mouseY);
		}
		drawFooter(ctx, mouseX, mouseY);

		super.extractRenderState(ctx, mouseX, mouseY, delta); // the search box

		for (int i = hits.size() - 1; i >= 0; i--) {
			Hit hit = hits.get(i);
			if (hit.tooltip != null && hit.contains(mouseX, mouseY)) {
				ctx.setTooltipForNextFrame(Component.literal(hit.tooltip), mouseX, mouseY);
				break;
			}
		}
	}

	private void drawHeader(GuiGraphicsExtractor ctx, int mouseX, int mouseY) {
		fill(ctx, px + 10, py + 8, px + 13, py + 17, ACCENT);
		ctx.text(font, "Item Studio", px + 18, py + 9, TEXT, false);
		int titleW = font.width("Item Studio");
		if (pw > 360) {
			ctx.text(font, "pick a look for every item", px + 26 + titleW, py + 9, MUTED, false);
		}

		// search box frame
		int sx1 = search.getX() - 5, sy1 = search.getY() - 4, sx2 = search.getX() + search.getWidth() + 3, sy2 = search.getY() + 12;
		roundRect(ctx, sx1, sy1, sx2, sy2, search.isFocused() ? CARD_HOVER : CARD);
		outline(ctx, sx1, sy1, sx2, sy2, search.isFocused() ? ACCENT : LINE);

		// close button
		int cx = px + pw - 20, cy = py + 5;
		boolean hover = inside(mouseX, mouseY, cx, cy, cx + 15, cy + 15);
		roundRect(ctx, cx, cy, cx + 15, cy + 15, hover ? 0xFF3A2030 : CARD);
		ctx.text(font, "x", cx + 5, cy + 3, hover ? 0xFFFF7A90 : MUTED, false);
		hits.add(new Hit(cx, cy, cx + 15, cy + 15, this::onClose, "Close (changes are applied)", null));

		fill(ctx, px + 1, bodyY - 2, px + pw - 1, bodyY - 1, LINE);
	}

	private void drawSidebar(GuiGraphicsExtractor ctx, int mouseX, int mouseY) {
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
			ctx.fakeItem(stack(cat.icon), x1 + 5, y + 2);
			if (sideW > 40) {
				ctx.text(font, font.plainSubstrByWidth(cat.label, sideW - 30), x1 + 25, y + 6,
					active ? TEXT : MUTED, false);
			}
			final Category target = cat;
			addHit(x1, y, x2, y + rowH, y1, y2, () -> {
				category = target;
				query = "";
				search.setValue("");
				bulkMode = false;
				scroll.put("grid", 0);
				if (!target.items().isEmpty()) selected = target.items().get(0);
			}, sideW > 40 ? null : cat.label);
			y += rowH;
		}
		ctx.disableScissor();
	}

	private void drawGrid(GuiGraphicsExtractor ctx, int mouseX, int mouseY) {
		List<Item> items = gridItems();
		boolean searching = !query.isEmpty();
		String title = searching ? "Search: " + items.size() + " found" : category.label;
		ctx.text(font, title, gridX, bodyY + 6, TEXT, false);
		if (!searching) {
			ctx.text(font, items.size() + " items", gridX + font.width(title) + 6, bodyY + 6, MUTED, false);
		}

		if (!searching && category != Category.ALL) {
			String label = bulkMode ? "Back to item" : "One pack for all";
			int bw = font.width(label) + 12;
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
			ctx.text(font, "Nothing matches", gridX, top + 4, MUTED, false);
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
			ctx.fakeItem(stack(item), x + 3, y + 3);
			String id = BuiltInRegistries.ITEM.getKey(item).toString();
			if (config.styles.containsKey(id) || config.hasAdjust(id)) {
				fill(ctx, x + cell - 6, y + 2, x + cell - 4, y + 4, ACCENT);
			}
			addHit(x, y, x + cell - 2, y + cell - 2, top, bottom, () -> {
				selected = item;
				bulkMode = false;
				scroll.put("detail", 0);
			}, stack(item).getHoverName().getString());
		}
		ctx.disableScissor();
	}

	private void drawDetail(GuiGraphicsExtractor ctx, int mouseX, int mouseY) {
		int x1 = detailX, y1 = bodyY + 2, x2 = detailX + detailW, y2 = footerY - 2;
		roundRect(ctx, x1, y1, x2, y2, SIDE);
		if (selected == null) {
			ctx.text(font, "Pick an item", x1 + 8, y1 + 8, MUTED, false);
			return;
		}
		if (!ready()) {
			ctx.text(font, "Loading packs...", x1 + 8, y1 + 8, MUTED, false);
			return;
		}
		ItemInfo info = ItemInfo.of(selected);

		// big icon + name
		roundRect(ctx, x1 + 6, y1 + 6, x1 + 42, y1 + 42, CARD);
		Matrix3x2fStack m = ctx.pose();
		m.pushMatrix();
		m.translate(x1 + 8, y1 + 8);
		m.scale(2f, 2f);
		ctx.fakeItem(stack(selected), 0, 0);
		m.popMatrix();
		int textW = detailW - 54;
		ctx.text(font, font.plainSubstrByWidth(stack(selected).getHoverName().getString(), textW), x1 + 48, y1 + 10, TEXT, false);
		ctx.text(font, font.plainSubstrByWidth(info.id, textW), x1 + 48, y1 + 22, MUTED, false);
		String style = styleOf(selected);
		String styleName = style == null ? "Default" : index.get(style) == null ? style + " (missing)" : index.get(style).displayName();
		ctx.text(font, font.plainSubstrByWidth(styleName, textW), x1 + 48, y1 + 32, style == null ? MUTED : ACCENT, false);

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

	/**
	 * One style card. sourceId is what gets saved when it is clicked; ids lists every source that looks exactly
	 * the same, so identical packs show as one card.
	 */
	private record Card(String sourceId, List<String> ids, String name, String tooltip, Supplier<byte[]> image,
	                    String imageKey) {
	}

	private static String names(List<FilePackSource> packs) {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < packs.size(); i++) {
			if (i > 0) b.append(", ");
			b.append(packs.get(i).displayName());
		}
		return b.toString();
	}

	private void drawStyles(GuiGraphicsExtractor ctx, ItemInfo info, int x1, int top, int x2, int y2, int mouseX, int mouseY) {
		List<Card> cards = new ArrayList<>();
		String main = info.mainTexture;
		String current = styleOf(selected);
		List<FilePackSource> packs = index.packsFor(info);

		// Packs whose files are exactly what the game already shows fold into "Default".
		List<FilePackSource> likeDefault = new ArrayList<>();
		Map<String, List<FilePackSource>> groups = new java.util.LinkedHashMap<>();
		for (FilePackSource pack : packs) {
			if (!pack.id().equals(current) && index.sameAsBelow(pack, info)) {
				likeDefault.add(pack);
			} else {
				groups.computeIfAbsent(index.fingerprint(pack, info), k -> new ArrayList<>()).add(pack);
			}
		}

		String defaultTip = "Don't change this item (keeps whatever your other packs do)"
			+ (likeDefault.isEmpty() ? "" : ". Same look as: " + names(likeDefault));
		cards.add(new Card(null, List.of(), "Default", defaultTip,
			() -> main == null ? null : Resources.readBelow(main), "below|" + main));
		// Vanilla is only worth showing when something else changes the item.
		if (!index.belowIsVanilla(info) || VanillaSource.ID.equals(current)) {
			cards.add(new Card(VanillaSource.ID, List.of(VanillaSource.ID), "Vanilla",
				"The normal Minecraft look, even if another pack changes it",
				() -> main == null ? null : Resources.readVanilla(main), "vanilla|" + main));
		}
		for (List<FilePackSource> group : groups.values()) {
			FilePackSource shown = group.get(0);
			List<String> ids = new ArrayList<>();
			for (FilePackSource pack : group) {
				ids.add(pack.id());
				if (pack.id().equals(current)) shown = pack;
			}
			final FilePackSource preview = shown;
			String name = group.size() == 1 ? shown.displayName() : shown.displayName() + " +" + (group.size() - 1);
			String tip = group.size() == 1 ? shown.displayName()
				: "Same look in " + group.size() + " packs: " + names(group);
			cards.add(new Card(shown.id(), ids, name, tip, () -> previewBytes(preview, info), shown.id() + "|" + info.id));
		}

		top = drawStyleSearch(ctx, x1, top, x2);
		cards = filterCards(cards);
		boolean noMatch = cards.isEmpty();

		int hintH = 0;
		if (packs.isEmpty() || noMatch) {
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
			boolean on = current == null ? card.sourceId == null : card.ids.contains(current);
			boolean hover = inside(mouseX, mouseY, cx, Math.max(cy, top), cx + cardW, Math.min(cy + cardH, bottom));
			roundRect(ctx, cx, cy, cx + cardW, cy + cardH, hover ? CARD_HOVER : CARD);
			if (on) outline(ctx, cx, cy, cx + cardW, cy + cardH, ACCENT);

			int size = cardW - 8;
			if (!drawPreview(ctx, card.imageKey, card.image, cx + 4, cy + 4, size)) {
				Matrix3x2fStack m = ctx.pose();
				m.pushMatrix();
				float scale = size / 16f;
				m.translate(cx + 4, cy + 4);
				m.scale(scale, scale);
				ctx.fakeItem(stack(selected), 0, 0);
				m.popMatrix();
			}
			ctx.text(font, font.plainSubstrByWidth(card.name, cardW - 6), cx + 3, cy + cardW - 1,
				on ? TEXT : MUTED, false);
			if (on) {
				fill(ctx, cx + cardW - 9, cy + 3, cx + cardW - 3, cy + 9, ACCENT);
			}
			final String sourceId = card.sourceId;
			final boolean alreadyOn = on;
			addHit(cx, cy, cx + cardW, cy + cardH, top, bottom, () -> {
				if (alreadyOn) return;
				setStyle(selected, sourceId);
				changed(true);
			}, card.tooltip);
		}
		int hy = top + rows * (cardH + gap) - off + 2;
		if (noMatch) {
			ctx.text(font, "No packs match \"" + styleQuery + "\"", x1 + 6, hy, MUTED, false);
		} else if (packs.isEmpty()) {
			ctx.text(font, "None of your packs change this item.", x1 + 6, hy, MUTED, false);
			ctx.text(font, "Add packs and press Rescan.", x1 + 6, hy + 10, MUTED, false);
		} else if (info.hasWornLook()) {
			ctx.text(font, font.plainSubstrByWidth("Also changes how it looks when worn/used", innerW), x1 + 6, hy, MUTED, false);
		}
		ctx.disableScissor();
	}

	/** Bytes of the texture that best shows a pack's version of an item. */
	private static byte[] previewBytes(PackSource pack, ItemInfo info) {
		try {
			if (info.mainTexture != null && pack.has(info.mainTexture)) return pack.read(info.mainTexture);
		} catch (Exception ignored) {
		}
		String own = "assets/minecraft/textures/item/" + net.minecraft.resources.Identifier.parse(info.id).getPath() + ".png";
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

	/** Places and frames the pack search box at y. Returns the y where content below it starts. */
	private int drawStyleSearch(GuiGraphicsExtractor ctx, int x1, int y, int x2) {
		styleSearch.visible = true;
		styleSearch.setX(x1 + 10);
		styleSearch.setY(y + 4);
		styleSearch.setWidth(x2 - x1 - 22);
		int bx1 = x1 + 6, by1 = y, bx2 = x2 - 6, by2 = y + 16;
		boolean focused = styleSearch.isFocused();
		roundRect(ctx, bx1, by1, bx2, by2, focused ? CARD_HOVER : CARD);
		outline(ctx, bx1, by1, bx2, by2, focused ? ACCENT : LINE);
		return y + 20;
	}

	/** Cards whose pack names match the pack search. */
	private List<Card> filterCards(List<Card> cards) {
		if (styleQuery.isEmpty()) return cards;
		List<Card> list = new ArrayList<>();
		for (Card card : cards) {
			String text = (card.name + " " + card.tooltip).toLowerCase(Locale.ROOT);
			if (text.contains(styleQuery)) list.add(card);
		}
		return list;
	}

	/** Draws the top square of a texture scaled to size. Returns false if there is no image. */
	private boolean drawPreview(GuiGraphicsExtractor ctx, String key, Supplier<byte[]> bytes, int x, int y, int size) {
		PreviewTextures.Preview p = previews.get(key, bytes);
		if (p == null) return false;
		int square = Math.min(p.width(), p.height());
		ctx.blit(RenderPipelines.GUI_TEXTURED, p.id(), x, y, 0f, 0f, size, size, square, square, p.width(), p.height());
		return true;
	}

	private void drawAdjust(GuiGraphicsExtractor ctx, ItemInfo info, int x1, int top, int x2, int y2, int mouseX, int mouseY) {
		// view chips, wrapping onto more rows if needed
		int cx = x1 + 6, cy = top;
		for (View v : View.values()) {
			int w = font.width(v.label) + 10;
			if (cx + w > x2 - 6) {
				cx = x1 + 6;
				cy += 16;
			}
			boolean on = v == view;
			boolean edited = config.getAdjust(info.id, v) != null && !config.getAdjust(info.id, v).isDefault();
			boolean hover = inside(mouseX, mouseY, cx, cy, cx + w, cy + 13);
			roundRect(ctx, cx, cy, cx + w, cy + 13, on ? ACCENT : hover ? CARD_HOVER : CARD);
			ctx.text(font, v.label, cx + 5, cy + 3, on ? 0xFFFFFFFF : edited ? ACCENT : MUTED, false);
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
			ctx.text(font, font.plainSubstrByWidth("Right-click a slider to reset it", x2 - x1 - 12),
				x1 + 6, y + 22, MUTED, false);
		}
	}

	private int slider(GuiGraphicsExtractor ctx, String label, String unit, float min, float max, float step, float def,
	                   Supplier<Float> get, Consumer<Float> set, int barX1, int barX2, int y, int mouseX, int mouseY,
	                   float displayMul) {
		float value = get.get();
		boolean changedValue = Math.abs(value - def) > 1e-4f;
		ctx.text(font, label, barX1, y, changedValue ? TEXT : MUTED, false);
		String shown = displayMul != 1f ? Math.round(value * displayMul) + unit
			: (step >= 1f ? String.valueOf(Math.round(value)) : String.format(Locale.ROOT, "%.2f", value)) + unit;
		ctx.text(font, shown, barX2 - font.width(shown), y, changedValue ? ACCENT : MUTED, false);

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

	private void drawBulk(GuiGraphicsExtractor ctx, int mouseX, int mouseY) {
		int x1 = detailX, y1 = bodyY + 2, x2 = detailX + detailW, y2 = footerY - 2;
		roundRect(ctx, x1, y1, x2, y2, SIDE);
		if (!ready()) {
			ctx.text(font, "Loading packs...", x1 + 8, y1 + 8, MUTED, false);
			return;
		}
		ctx.text(font, font.plainSubstrByWidth("One pack for all " + category.label, detailW - 12), x1 + 8, y1 + 8, TEXT, false);
		ctx.text(font, font.plainSubstrByWidth("MouseButtonEvent a pack to use it for every", detailW - 12), x1 + 8, y1 + 20, MUTED, false);
		ctx.text(font, font.plainSubstrByWidth("item in this group that it has.", detailW - 12), x1 + 8, y1 + 30, MUTED, false);

		List<Card> cards = new ArrayList<>();
		Item first = category.items().isEmpty() ? null : category.items().get(0);
		ItemInfo firstInfo = first == null ? null : ItemInfo.of(first);
		String main = firstInfo == null ? null : firstInfo.mainTexture;
		cards.add(new Card(null, List.of(), "Default", "Undo: stop changing every item in this group",
			() -> main == null ? null : Resources.readBelow(main), "below|" + main));
		cards.add(new Card(VanillaSource.ID, List.of(VanillaSource.ID), "Vanilla", "Normal Minecraft look for the whole group",
			() -> main == null ? null : Resources.readVanilla(main), "vanilla|" + main));
		Map<String, Integer> counts = new HashMap<>();
		Map<String, List<FilePackSource>> groups = new java.util.LinkedHashMap<>();
		Map<String, ItemInfo> previewInfos = new HashMap<>();
		for (FilePackSource pack : index.packs()) {
			int count = 0;
			ItemInfo shownInfo = null;
			StringBuilder look = new StringBuilder();
			for (Item item : category.items()) {
				ItemInfo info = ItemInfo.of(item);
				if (index.provided(pack, info).any()) {
					count++;
					if (shownInfo == null) shownInfo = info;
					look.append(info.id).append('=').append(index.fingerprint(pack, info)).append(';');
				}
			}
			if (count == 0) continue;
			counts.put(pack.id(), count);
			previewInfos.put(pack.id(), shownInfo);
			// packs that are identical for every item in the group become one card
			groups.computeIfAbsent(look.toString(), k -> new ArrayList<>()).add(pack);
		}
		for (List<FilePackSource> group : groups.values()) {
			FilePackSource pack = group.get(0);
			int count = counts.get(pack.id());
			final ItemInfo previewInfo = previewInfos.get(pack.id());
			List<String> ids = new ArrayList<>();
			for (FilePackSource p : group) ids.add(p.id());
			String name = group.size() == 1 ? pack.displayName() : pack.displayName() + " +" + (group.size() - 1);
			String tip = (group.size() == 1 ? pack.displayName() : "Same look in " + group.size() + " packs: " + names(group))
				+ " (" + count + " item" + (count == 1 ? "" : "s") + ")";
			cards.add(new Card(pack.id(), ids, name, tip, () -> previewBytes(pack, previewInfo), pack.id() + "|" + previewInfo.id));
		}

		boolean noPacks = cards.size() == 2;
		int top = drawStyleSearch(ctx, x1, y1 + 42, x2), bottom = y2 - 4;
		cards = filterCards(cards);
		boolean noMatch = cards.isEmpty();
		int innerW = x2 - x1 - 12;
		int cols = Math.max(2, innerW / 58);
		int gap = 4;
		int cardW = (innerW - gap * (cols - 1)) / cols;
		int cardH = cardW + 22;
		int rows = (cards.size() + cols - 1) / cols;
		int content = rows * (cardH + gap) + (noPacks || noMatch ? 24 : 0);
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
				ctx.fakeItem(stack(first), cx + cardW / 2 - 8, cy + cardW / 2 - 8);
			}
			ctx.text(font, font.plainSubstrByWidth(card.name, cardW - 6), cx + 3, cy + cardW - 1, TEXT, false);
			String sub = card.sourceId == null ? "reset" : card.sourceId.equals(VanillaSource.ID) ? "all items"
				: counts.get(card.sourceId) + " item" + (counts.get(card.sourceId) == 1 ? "" : "s");
			ctx.text(font, sub, cx + 3, cy + cardW + 9, MUTED, false);
			final String sourceId = card.sourceId;
			addHit(cx, cy, cx + cardW, cy + cardH, top, bottom, () -> useForCategory(sourceId), card.tooltip);
		}
		if (noMatch) {
			int hy = top + rows * (cardH + gap) - off + 2;
			ctx.text(font, "No packs match \"" + styleQuery + "\"", x1 + 6, hy, MUTED, false);
		} else if (noPacks) {
			int hy = top + rows * (cardH + gap) - off + 2;
			ctx.text(font, "None of your packs change", x1 + 6, hy, MUTED, false);
			ctx.text(font, "items in this group.", x1 + 6, hy + 10, MUTED, false);
		}
		ctx.disableScissor();
	}

	private void drawFooter(GuiGraphicsExtractor ctx, int mouseX, int mouseY) {
		fill(ctx, px + 1, footerY, px + pw - 1, footerY + 1, LINE);
		int y = footerY + 4;
		int right = px + pw - 6;

		String applyLabel = pending > 0 ? "Apply (" + pending + ")" : "Apply";
		int w = font.width(applyLabel) + 16;
		right -= w;
		button(ctx, right, y, w, 15, applyLabel, true, mouseX, mouseY, this::apply,
			"Build the pack and reload textures");

		String auto = "Auto-apply: " + (config.applyInstantly ? "On" : "Off");
		w = font.width(auto) + 12;
		right -= w + 4;
		button(ctx, right, y, w, 15, auto, false, mouseX, mouseY, () -> {
			config.applyInstantly = !config.applyInstantly;
			config.save();
			if (config.applyInstantly && pending > 0) apply();
		}, "Apply every change right away (each one reloads textures)");

		boolean small = pw < 520;
		String rescan = small ? "Rescan" : "Rescan packs";
		w = font.width(rescan) + 12;
		right -= w + 4;
		button(ctx, right, y, w, 15, rescan, false, mouseX, mouseY, this::rescan,
			"Look for new packs in the resourcepacks folder");

		String folder = small ? "Folder" : "Packs folder";
		w = font.width(folder) + 12;
		right -= w + 4;
		button(ctx, right, y, w, 15, folder, false, mouseX, mouseY,
			() -> Util.getPlatform().openUri(minecraft.getResourcePackDirectory().toUri()), "Open your resourcepacks folder");

		int statusW = right - (px + 10) - 6;
		if (statusW > 20) {
			ctx.text(font, font.plainSubstrByWidth(status, statusW), px + 10, y + 4, statusColor, false);
		}
	}

	// ------------------------------------------------------------------ small drawing helpers

	private void button(GuiGraphicsExtractor ctx, int x, int y, int w, int h, String label, boolean primary,
	                    int mouseX, int mouseY, Runnable action, String tooltip) {
		boolean hover = inside(mouseX, mouseY, x, y, x + w, y + h);
		int bg = primary ? (hover ? 0xFF9C82FF : ACCENT) : (hover ? CARD_HOVER : CARD);
		roundRect(ctx, x, y, x + w, y + h, bg);
		if (!primary) outline(ctx, x, y, x + w, y + h, LINE);
		int tw = font.width(label);
		ctx.text(font, label, x + (w - tw) / 2, y + (h - 8) / 2, primary ? 0xFFFFFFFF : TEXT, false);
		hits.add(new Hit(x, y, x + w, y + h, action, tooltip, null));
	}

	private void tabButton(GuiGraphicsExtractor ctx, int x, int y, int w, String label, boolean on,
	                       int mouseX, int mouseY, Runnable action) {
		boolean hover = inside(mouseX, mouseY, x, y, x + w, y + 15);
		roundRect(ctx, x, y, x + w, y + 15, on ? CARD_HOVER : hover ? CARD : SIDE);
		if (on) fill(ctx, x + 4, y + 13, x + w - 4, y + 15, ACCENT);
		String text = font.plainSubstrByWidth(label, w - 6);
		ctx.text(font, text, x + (w - font.width(text)) / 2, y + 4, on ? TEXT : MUTED, false);
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

	private static void fill(GuiGraphicsExtractor ctx, int x1, int y1, int x2, int y2, int color) {
		if (x2 > x1 && y2 > y1) ctx.fill(x1, y1, x2, y2, color);
	}

	/** Rectangle with 2px cut corners, drawn without overlapping fills so see-through colours stay even. */
	private static void roundRect(GuiGraphicsExtractor ctx, int x1, int y1, int x2, int y2, int color) {
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

	private static void outline(GuiGraphicsExtractor ctx, int x1, int y1, int x2, int y2, int color) {
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
	public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
		double x = click.x(), y = click.y();
		if (search.isMouseOver(x, y) || styleSearch.isMouseOver(x, y)) {
			return super.mouseClicked(click, doubled);
		}
		setFocused(null);
		search.setFocused(false);
		styleSearch.setFocused(false);
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
				hit.action.run();
				return true;
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent click, double offsetX, double offsetY) {
		if (dragging != null) {
			dragging.setFromMouse(click.x());
			return true;
		}
		return super.mouseDragged(click, offsetX, offsetY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent click) {
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
	public boolean keyPressed(KeyEvent input) {
		if (input.isEscape()) {
			onClose();
			return true;
		}
		return super.keyPressed(input);
	}
}
