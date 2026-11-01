package dev.itemstudio.ui;

import dev.itemstudio.Adjust;
import dev.itemstudio.ItemStudioClient;
import dev.itemstudio.StudioConfig;
import dev.itemstudio.View;
import dev.itemstudio.build.PackBuilder;
import dev.itemstudio.build.PackWriter;
import dev.itemstudio.pack.FilePackSource;
import dev.itemstudio.pack.TexturePart;
import dev.itemstudio.pack.ItemInfo;
import dev.itemstudio.pack.ModelPreview;
import dev.itemstudio.pack.PackIndex;
import dev.itemstudio.pack.PackSource;
import dev.itemstudio.pack.Refs;
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
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.joml.Matrix3x2fStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The Item Studio menu: categories on the left, the items of a category as a row of tiles, a large gallery of
 * style cards, and a preview + size/position panel on the right. Everything except the two search boxes is drawn
 * by hand; each frame registers its clickable areas in {@link #hits}, and clicks are matched against them.
 */
public final class StudioScreen extends Screen {
	// Text colours (the backgrounds come from the theme)
	private static final int TEXT = 0xFFF2F3F8;
	private static final int TEXT2 = 0xFFC4C8D8;
	private static final int MUTED = 0xFF9AA0B8;
	private static final int WARN = 0xFFF5B83D;
	private static final int GOOD = 0xFF5BE49B;
	private static final int GOLD = 0xFFF5B83D;

	private static final FontDescription.Resource POPPINS = new FontDescription.Resource(Identifier.fromNamespaceAndPath(ItemStudioClient.MOD_ID, "poppins"));
	private static final FontDescription.Resource POPPINS_BOLD = new FontDescription.Resource(Identifier.fromNamespaceAndPath(ItemStudioClient.MOD_ID, "poppins_semibold"));

	private static final int HEADER = 24;
	private static final int FOOTER = 26;

	private enum Filter { ALL, FAVORITES, RECENT }

	private record Hit(int x1, int y1, int x2, int y2, Runnable action, Runnable rightAction, String tooltip, Slider slider) {
		boolean contains(double x, double y) {
			return x >= x1 && x < x2 && y >= y1 && y < y2;
		}
	}

	private record ScrollArea(String key, int x1, int y1, int x2, int y2, int max) {
	}

	/** A draggable value row. */
	private record Slider(String label, float min, float max, float step, float defaultValue,
	                      Supplier<Float> get, Consumer<Float> set, int barX1, int barX2) {
		void setFromMouse(double mouseX) {
			double t = (mouseX - barX1) / Math.max(1, barX2 - barX1);
			t = Math.max(0, Math.min(1, t));
			float raw = (float) (min + t * (max - min));
			set.accept(Math.round(raw / step) * step);
		}
	}

	/**
	 * One style card. sourceId is what gets saved when it is clicked; ids lists every source that looks exactly
	 * the same, so identical packs show as one card.
	 */
	private record Card(String sourceId, List<String> ids, String name, String sub, String tooltip,
	                    Supplier<byte[]> image, String imageKey) {
	}

	private final Screen parent;
	private final StudioConfig config = StudioConfig.get();
	private final PreviewTextures previews = new PreviewTextures();
	private final Map<Item, ItemStack> stacks = new HashMap<>();
	private final List<Hit> hits = new ArrayList<>();
	private final List<ScrollArea> scrollAreas = new ArrayList<>();
	private final Map<String, Integer> scroll = new HashMap<>();
	private static final com.google.gson.Gson SNAPSHOT_GSON = new com.google.gson.Gson();

	private PackIndex index;
	private CompletableFuture<PackIndex> loading;
	private Category category = Category.SWORDS;
	private Item selected;
	/** The selected part in each texture-part category (HUD, particles). */
	private final Map<Category, TexturePart> selectedPart = new HashMap<>();
	private View view = View.FIRST_PERSON;
	private Filter filter = Filter.ALL;
	private boolean bulkMode;
	private boolean settingsOpen;
	private String query = "";
	private String styleQuery = "";
	private String searchCacheQuery;
	private List<Item> searchCache = List.of();
	private int pending;
	private Map<String, String> applied;
	private String status = "";
	private int statusColor = MUTED;
	private Slider dragging;
	private EditBox search;
	private EditBox styleSearch;
	private Theme.Palette pal;

	// layout
	private int px, py, pw, ph, bodyY, footerY, railW, sideX, sideW, centerX, centerW;

	public StudioScreen(Screen parent) {
		super(Component.translatable("itemstudio.title"));
		this.parent = parent;
	}

	// ------------------------------------------------------------------ setup

	@Override
	protected void init() {
		if (index == null && loading == null) startScan("Loading packs...");
		if (applied == null) applied = snapshot();
		if (selected == null && !category.items().isEmpty()) selected = category.items().get(0);

		px = 6;
		py = 6;
		pw = width - 12;
		ph = height - 12;
		bodyY = py + HEADER;
		footerY = py + ph - FOOTER;
		railW = width >= 520 ? 100 : 24;
		sideW = Math.max(140, Math.min(180, (int) (pw * 0.28)));
		sideX = px + pw - sideW;
		centerX = px + railW + 10;
		centerW = sideX - centerX - 10;

		int searchW = Math.max(70, Math.min(150, pw / 4));
		search = new EditBox(font, px + pw - searchW - 44, py + 8, searchW - 18, 10, Component.literal("Search all items"));
		search.setBordered(false);
		search.setMaxLength(50);
		search.setValue(query);
		search.setHint(styled("Search all items", false).withColor(MUTED));
		search.setResponder(text -> {
			query = text.trim().toLowerCase(Locale.ROOT);
			scroll.put("items", 0);
			bulkMode = false;
		});
		addRenderableWidget(search);

		styleSearch = new EditBox(font, centerX + 16, bodyY, 100, 10, Component.literal("Search packs"));
		styleSearch.setBordered(false);
		styleSearch.setMaxLength(50);
		styleSearch.setValue(styleQuery);
		styleSearch.setHint(styled("Search packs", false).withColor(MUTED));
		styleSearch.setResponder(text -> {
			styleQuery = text.trim().toLowerCase(Locale.ROOT);
			scroll.put("cards", 0);
		});
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
			loading.thenAccept(PackIndex::close);
		}
		index = null;
		loading = null;
		ItemInfo.clearCache();
	}

	// ------------------------------------------------------------------ text with the menu font

	private MutableComponent styled(String s, boolean bold) {
		if (!config.customFont) {
			return bold ? Component.literal(s).setStyle(Style.EMPTY.withBold(true)) : Component.literal(s);
		}
		return Component.literal(s).setStyle(Style.EMPTY.withFont(bold ? POPPINS_BOLD : POPPINS));
	}

	private int textW(String s) {
		return font.width(styled(s, false));
	}

	private int boldW(String s) {
		return font.width(styled(s, true));
	}

	private String trim(String s, int max) {
		if (textW(s) <= max) return s;
		String dots = "...";
		int end = s.length();
		while (end > 0 && textW(s.substring(0, end) + dots) > max) end--;
		return s.substring(0, end).trim() + dots;
	}

	private void text(GuiGraphicsExtractor ctx, String s, int x, int y, int color) {
		ctx.text(font, styled(s, false), x, y, color, false);
	}

	private void bold(GuiGraphicsExtractor ctx, String s, int x, int y, int color) {
		ctx.text(font, styled(s, true), x, y, color, false);
	}

	/** True when the menu uses Minecraft's pixel font (the default). Pixel text is never shrunk: it turns blotchy. */
	private boolean pixelFont() {
		return !config.customFont;
	}

	/** Hints and sub-labels: 75% with the custom font, full size with Minecraft's font. */
	private void small(GuiGraphicsExtractor ctx, String s, int x, int y, int color) {
		if (pixelFont()) {
			ctx.text(font, styled(s, false), x, y, color, false);
			return;
		}
		Matrix3x2fStack m = ctx.pose();
		m.pushMatrix();
		m.translate(x, y);
		m.scale(0.75f, 0.75f);
		ctx.text(font, styled(s, false), 0, 0, color, false);
		m.popMatrix();
	}

	private int smallW(String s) {
		return pixelFont() ? textW(s) : Math.round(textW(s) * 0.75f);
	}

	private String smallTrim(String s, int max) {
		return pixelFont() ? trim(s, max) : trim(s, Math.round(max / 0.75f));
	}

	/** Height of one line of small text. */
	private int smallH() {
		return pixelFont() ? 10 : 8;
	}

	/** Small text wrapped to a width. Returns the y below the last line. */
	private int smallWrapped(GuiGraphicsExtractor ctx, String s, int x, int y, int maxW, int color) {
		StringBuilder line = new StringBuilder();
		for (String word : s.split(" ")) {
			String next = line.length() == 0 ? word : line + " " + word;
			if (line.length() > 0 && smallW(next) > maxW) {
				small(ctx, line.toString(), x, y, color);
				y += smallH();
				line.setLength(0);
				line.append(word);
			} else {
				line.setLength(0);
				line.append(next);
			}
		}
		if (line.length() > 0) {
			small(ctx, smallTrim(line.toString(), maxW), x, y, color);
			y += smallH();
		}
		return y;
	}

	/** Short names for the view buttons, used with the wider pixel font. */
	private static String shortLabel(View v) {
		return switch (v) {
			case FIRST_PERSON -> "1st";
			case THIRD_PERSON -> "3rd";
			case INVENTORY -> "Inv";
			case GROUND -> "Drop";
			case FRAME -> "Frame";
			case HEAD -> "Head";
		};
	}

	// ------------------------------------------------------------------ packs / state

	private void startScan(String message) {
		setStatus(message, MUTED);
		loading = CompletableFuture.supplyAsync(PackIndex::scan);
	}

	private boolean ready() {
		if (index != null) return true;
		if (loading == null || !loading.isDone()) return false;
		try {
			index = loading.join();
			int count = index.packs().size();
			setStatus(count == 0 ? "No packs found - put PvP packs in your resourcepacks folder"
				: "Ready", count == 0 ? WARN : MUTED);
		} catch (Exception e) {
			ItemStudioClient.LOG.error("Pack scan failed", e);
			setStatus("Could not read your packs - see the game log", WARN);
			loading = null;
			return false;
		}
		return true;
	}

	private PackIndex indexNow() {
		if (index == null) index = loading != null ? loading.join() : PackIndex.scan();
		return index;
	}

	private void setStatus(String text, int color) {
		status = text;
		statusColor = color;
	}

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

	/** Items whose choices differ from the last apply. Picking A, then B, then C for one item is 1. */
	private int countPending() {
		Map<String, String> now = snapshot();
		java.util.Set<String> ids = new java.util.HashSet<>(now.keySet());
		ids.addAll(applied.keySet());
		int count = 0;
		for (String id : ids) {
			if (!Objects.equals(now.get(id), applied.get(id))) count++;
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
			setStatus(pending + " item" + (pending == 1 ? "" : "s") + " changed", WARN);
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
		if (!config.enabled) {
			setStatus("Saved - Item Studio is off, turn it on in Settings to see it", WARN);
		} else if (builder.problems().isEmpty()) {
			setStatus("Applied", GOOD);
		} else {
			setStatus("Applied with " + builder.problems().size() + " problem(s): " + builder.problems().get(0), WARN);
		}
		PackWriter.enableAndReload(!builder.files().isEmpty());
	}

	private void rescan() {
		if (index == null) return;
		previews.clear();
		index.close();
		index = null;
		ItemInfo.clearCache();
		startScan("Rescanning packs...");
	}

	/** Something that can be styled: an item, or a part of the HUD. */
	private record Target(String key, Item item, TexturePart part) {
		boolean isPart() {
			return part != null;
		}
	}

	private Target itemTarget(Item item) {
		return new Target(BuiltInRegistries.ITEM.getKey(item).toString(), item, null);
	}

	private Target partTarget(TexturePart element) {
		return new Target(element.key(), null, element);
	}

	private boolean partMode() {
		return !category.parts().isEmpty() && query.isEmpty();
	}

	/** The item or HUD part shown in the gallery and on the right. */
	private Target current() {
		if (partMode()) return partTarget(selectedPart.getOrDefault(category, category.parts().get(0)));
		return selected == null ? null : itemTarget(selected);
	}

	/** The tiles in the row at the top. */
	private List<Target> tiles() {
		List<Target> list = new ArrayList<>();
		if (partMode()) {
			for (TexturePart e : category.parts()) list.add(partTarget(e));
		} else {
			for (Item item : gridItems()) list.add(itemTarget(item));
		}
		return list;
	}

	/** Everything "One pack for all" works on. */
	private List<Target> groupTargets() {
		List<Target> list = new ArrayList<>();
		if (!category.parts().isEmpty()) {
			for (TexturePart e : category.parts()) list.add(partTarget(e));
		} else {
			for (Item item : category.items()) list.add(itemTarget(item));
		}
		return list;
	}

	private String nameOf(Target t) {
		return t.isPart() ? t.part.label() : stack(t.item).getHoverName().getString();
	}

	private List<FilePackSource> packsFor(Target t) {
		return t.isPart() ? index.packsForPart(t.part) : index.packsFor(ItemInfo.of(t.item));
	}

	private boolean provides(PackSource p, Target t) {
		if (p instanceof VanillaSource) return true;
		return t.isPart() ? !index.partFiles(p, t.part).isEmpty() : index.provided(p, ItemInfo.of(t.item)).any();
	}

	private String fingerprint(PackSource p, Target t) {
		return t.isPart() ? index.partFingerprint(p, t.part) : index.fingerprint(p, ItemInfo.of(t.item));
	}

	private boolean sameAsBelow(PackSource p, Target t) {
		return t.isPart() ? index.partSameAsBelow(p, t.part) : index.sameAsBelow(p, ItemInfo.of(t.item));
	}

	private boolean belowIsVanilla(Target t) {
		return t.isPart() ? index.partBelowIsVanilla(t.part) : index.belowIsVanilla(ItemInfo.of(t.item));
	}

	/** What a pack changes for a target, shown under the pack name. */
	private String kindOf(PackSource p, Target t) {
		if (t.isPart()) {
			int n = index.partFiles(p, t.part).size();
			return n + " texture" + (n == 1 ? "" : "s");
		}
		PackIndex.Provided provided = index.provided(p, ItemInfo.of(t.item));
		boolean model = provided.itemDef() || !provided.models().isEmpty() || !provided.parentModels().isEmpty();
		boolean tex = !provided.textures().isEmpty();
		return model && tex ? "Texture + model" : model ? "Model" : "Texture";
	}

	/** Size of rendered 3D item pictures, in pixels (2x an inventory slot). */
	private static final int MODEL_PREVIEW_SIZE = 32;

	/** Reads a file from a pack, or from the game's current resources when the pack doesn't have it. */
	private static byte[] readWithBelow(PackSource p, String path) {
		try {
			if (p.has(path)) return p.read(path);
		} catch (Exception ignored) {
		}
		return Resources.readBelow(path);
	}

	/**
	 * Picture of the item as it looks in the inventory: 3D models are drawn from their boxes, flat items show
	 * their texture.
	 */
	private static byte[] itemPicture(ItemInfo info, java.util.function.Function<String, byte[]> read, byte[] flat) {
		byte[] model = ModelPreview.render(info.id, info.models, read, MODEL_PREVIEW_SIZE);
		return model != null ? model : flat;
	}

	/** Picture for the Default (what the game shows now) or Vanilla card. */
	private byte[] basePreview(Target t, boolean vanilla) {
		java.util.function.Function<String, byte[]> read = vanilla ? Resources::readVanilla : Resources::readBelow;
		String main = mainFile(t);
		byte[] flat = main == null ? null : read.apply(main);
		return t.isPart() ? flat : itemPicture(ItemInfo.of(t.item), read, flat);
	}

	/** Bytes of the picture for a pack's version of a target. */
	private byte[] previewOf(PackSource p, Target t) {
		if (!t.isPart()) {
			ItemInfo info = ItemInfo.of(t.item);
			return itemPicture(info, path -> readWithBelow(p, path), previewBytes(p, info));
		}
		try {
			if (p.has(t.part.preview())) return p.read(t.part.preview());
			List<String> files = index.partFiles(p, t.part);
			return files.isEmpty() ? null : p.read(files.get(0));
		} catch (Exception e) {
			return null;
		}
	}

	private String mainFile(Target t) {
		return t.isPart() ? t.part.preview() : ItemInfo.of(t.item).mainTexture;
	}

	private void setStyle(String key, String sourceId) {
		if (sourceId == null) config.styles.remove(key);
		else {
			config.styles.put(key, sourceId);
			if (!VanillaSource.ID.equals(sourceId)) config.markRecent(sourceId);
		}
	}

	private String styleOf(String key) {
		return config.styles.get(key);
	}

	private String styleOf(Item item) {
		return styleOf(BuiltInRegistries.ITEM.getKey(item).toString());
	}

	private void useForCategory(String sourceId) {
		PackSource source = sourceId == null ? null : index.get(sourceId);
		for (Target t : groupTargets()) {
			if (sourceId == null) setStyle(t.key, null);
			else if (source != null && provides(source, t)) setStyle(t.key, sourceId);
		}
		changed(true);
	}

	/** Draws an item icon, or a HUD part's picture, in a size x size box. */
	private void drawTargetIcon(GuiGraphicsExtractor ctx, Target t, int x, int y, int size) {
		if (t.isPart()) {
			String file = t.part.preview();
			if (drawPreview(ctx, "below|" + file, () -> Resources.readBelow(file), x, y, size)) return;
			text(ctx, t.part.label().substring(0, 1), x + size / 2 - 3, y + size / 2 - 4, TEXT2);
			return;
		}
		// whole-number sizes keep pixel art sharp: 16, 32, 48...
		int k = size / 16;
		if (k <= 1) {
			ctx.fakeItem(stack(t.item), x + Math.max(0, (size - 16) / 2), y + Math.max(0, (size - 16) / 2));
			return;
		}
		int d = k * 16;
		Matrix3x2fStack m = ctx.pose();
		m.pushMatrix();
		m.translate(x + (size - d) / 2, y + (size - d) / 2);
		m.scale(k, k);
		ctx.fakeItem(stack(t.item), 0, 0);
		m.popMatrix();
	}

	private void toggleFavorite(List<String> ids) {
		if (ids.isEmpty() || VanillaSource.ID.equals(ids.get(0))) return;
		boolean fav = isFavorite(ids);
		if (fav) ids.forEach(config.favorites::remove);
		else config.favorites.add(ids.get(0));
		config.save();
	}

	private boolean isFavorite(List<String> ids) {
		for (String id : ids) if (config.favorites.contains(id)) return true;
		return false;
	}

	private ItemStack stack(Item item) {
		return stacks.computeIfAbsent(item, ItemStack::new);
	}

	private List<Item> gridItems() {
		if (query.isEmpty()) return category.items();
		if (query.equals(searchCacheQuery)) return searchCache;
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

	private Theme theme() {
		return Theme.byName(config.theme);
	}

	// ------------------------------------------------------------------ rendering

	@Override
	public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
		hits.clear();
		scrollAreas.clear();
		pal = theme().palette(config.opacity);
		ready();

		roundRect(ctx, px, py, px + pw, py + ph, pal.panel());
		outline(ctx, px, py, px + pw, py + ph, pal.panelBorder());

		drawHeader(ctx, mouseX, mouseY);
		drawRail(ctx, mouseX, mouseY);
		styleSearch.visible = false;
		drawCenter(ctx, mouseX, mouseY);
		if (settingsOpen) drawSettings(ctx, mouseX, mouseY);
		else drawSide(ctx, mouseX, mouseY);
		drawFooter(ctx, mouseX, mouseY);

		super.extractRenderState(ctx, mouseX, mouseY, delta); // the two search boxes

		for (int i = hits.size() - 1; i >= 0; i--) {
			Hit hit = hits.get(i);
			if (hit.tooltip != null && hit.contains(mouseX, mouseY)) {
				ctx.setTooltipForNextFrame(Component.literal(hit.tooltip), mouseX, mouseY);
				break;
			}
		}
	}

	private void drawHeader(GuiGraphicsExtractor ctx, int mouseX, int mouseY) {
		int x = px + 8, y = py + 5;
		roundRect(ctx, x, y - 1, x + 18, y + 17, pal.strong());
		ctx.fakeItem(stack(Items.DIAMOND_SWORD), x + 1, y);
		bold(ctx, "Item Studio", x + 24, py + 8, TEXT);

		int crumbX = x + 30 + boldW("Item Studio");
		fill(ctx, crumbX, py + 7, crumbX + 1, py + 17, pal.line2());
		String where = !query.isEmpty() ? "Search" : category.label;
		Target shownTarget = current();
		String item = shownTarget == null ? "" : nameOf(shownTarget);
		int roomEnd = search.getX() - 12;
		if (roomEnd - crumbX > 60) {
			String crumb = trim(where + "  /  " + item, roomEnd - crumbX - 8);
			text(ctx, crumb, crumbX + 8, py + 8, MUTED);
		}

		// search box frame
		int sx1 = search.getX() - 16, sy1 = py + 4, sx2 = search.getX() + search.getWidth() + 4, sy2 = py + 20;
		roundRect(ctx, sx1, sy1, sx2, sy2, search.isFocused() ? pal.surfaceHover() : pal.surface());
		outline(ctx, sx1, sy1, sx2, sy2, search.isFocused() ? pal.accent() : pal.surfaceBorder());
		drawSearchIcon(ctx, sx1 + 5, sy1 + 4, MUTED);

		// settings and close
		int gx = px + pw - 40, cx = px + pw - 21;
		iconButton(ctx, gx, py + 4, settingsOpen ? "Close settings" : "Settings: theme, opacity, font", mouseX, mouseY,
			settingsOpen, () -> settingsOpen = !settingsOpen);
		drawGear(ctx, gx + 4, py + 8, settingsOpen ? 0xFFFFFFFF : TEXT2);
		iconButton(ctx, cx, py + 4, "Close (changes are applied)", mouseX, mouseY, false, this::onClose);
		drawCross(ctx, cx + 5, py + 9, TEXT2);

		fill(ctx, px + 1, bodyY - 1, px + pw - 1, bodyY, pal.line());
	}

	private void drawRail(GuiGraphicsExtractor ctx, int mouseX, int mouseY) {
		int x1 = px + 1, y1 = bodyY, x2 = px + railW, y2 = footerY;
		fill(ctx, x1, y1, x2, y2, pal.rail());
		fill(ctx, x2, y1, x2 + 1, y2, pal.line());
		Category[] cats = Category.values();
		int rowH = 20;
		int top = y1 + 6;
		if (railW > 40) {
			small(ctx, "CATEGORIES", x1 + 10, top, MUTED);
			top += 10;
		}
		int content = cats.length * (rowH + 1);
		int off = scrollOf("rail", content, y2 - top - 4);
		scrollAreas.add(new ScrollArea("rail", x1, top, x2, y2, Math.max(0, content - (y2 - top - 4))));

		ctx.enableScissor(x1, top, x2, y2);
		int y = top - off;
		for (Category cat : cats) {
			boolean active = cat == category && query.isEmpty();
			boolean hover = inside(mouseX, mouseY, x1, Math.max(y, top), x2, Math.min(y + rowH, y2));
			int bx1 = x1 + 5, bx2 = x2 - 5;
			if (active) roundRect(ctx, bx1, y, bx2, y + rowH, pal.soft());
			else if (hover) roundRect(ctx, bx1, y, bx2, y + rowH, pal.surface());
			drawTargetIcon(ctx, !cat.parts().isEmpty() ? partTarget(cat.parts().get(0)) : itemTarget(cat.icon), bx1 + 2, y + 2, 16);
			if (railW > 40) {
				String count = cat == Category.ALL ? "" : String.valueOf(cat.parts().isEmpty() ? cat.items().size() : cat.parts().size());
				int countW = smallW(count);
				text(ctx, trim(cat.label, bx2 - bx1 - 27 - countW), bx1 + 21, y + 6, active ? 0xFFFFFFFF : TEXT2);
				small(ctx, count, bx2 - 4 - countW, y + 6, active ? pal.accentText() : MUTED);
			}
			final Category target = cat;
			addHit(bx1, y, bx2, y + rowH, top, y2, () -> {
				category = target;
				query = "";
				search.setValue("");
				bulkMode = false;
				scroll.put("items", 0);
				scroll.put("cards", 0);
				if (!target.items().isEmpty()) selected = target.items().get(0);
			}, railW > 40 ? null : cat.label);
			y += rowH + 1;
		}
		ctx.disableScissor();
	}

	private void drawCenter(GuiGraphicsExtractor ctx, int mouseX, int mouseY) {
		List<Target> items = tiles();
		Target cur = current();
		boolean searching = !query.isEmpty();
		int x = centerX, w = centerW;
		int y = bodyY + 8;

		// title row
		String title = searching ? "Search" : category.label;
		bold(ctx, title, x, y + 3, TEXT);
		text(ctx, items.size() + (partMode() ? " part" : " item") + (items.size() == 1 ? "" : "s"), x + boldW(title) + 6, y + 3, MUTED);
		if (!searching && category != Category.ALL) {
			String label = bulkMode ? "Back to " + (cur == null ? "item" : trim(nameOf(cur), 60))
				: "One pack for all";
			int bw = textW(label) + 14;
			button(ctx, x + w - bw, y, bw, 15, label, bulkMode, false, mouseX, mouseY, () -> {
				bulkMode = !bulkMode;
				scroll.put("cards", 0);
			}, bulkMode ? null : "Use one pack for every " + category.label.toLowerCase(Locale.ROOT) + " item it has");
		}
		y += 20;

		// item tiles: up to 2 rows, scrolls when there are more
		int tile = 22, gap = 4;
		int cols = Math.max(1, (w + gap) / (tile + gap));
		int rows = Math.max(1, (items.size() + cols - 1) / cols);
		int shownRows = Math.min(rows, 2);
		int areaH = shownRows * (tile + gap) - gap;
		int content = rows * (tile + gap) - gap;
		int off = scrollOf("items", content, areaH);
		scrollAreas.add(new ScrollArea("items", x, y, x + w, y + areaH, Math.max(0, content - areaH)));
		if (items.isEmpty()) {
			text(ctx, "Nothing matches", x, y + 6, MUTED);
		} else {
			ctx.enableScissor(x, y, x + w, y + areaH);
			for (int i = 0; i < items.size(); i++) {
				int tx = x + (i % cols) * (tile + gap);
				int ty = y + (i / cols) * (tile + gap) - off;
				if (ty + tile < y || ty > y + areaH) continue;
				Target t = items.get(i);
				boolean isSelected = cur != null && t.key.equals(cur.key);
				boolean hover = inside(mouseX, mouseY, tx, Math.max(ty, y), tx + tile, Math.min(ty + tile, y + areaH));
				roundRect(ctx, tx, ty, tx + tile, ty + tile, isSelected ? pal.soft() : hover ? pal.surfaceHover() : pal.surface());
				outline(ctx, tx, ty, tx + tile, ty + tile, isSelected ? pal.accent() : pal.surfaceBorder());
				drawTargetIcon(ctx, t, tx + 3, ty + 3, 16);
				String id = t.key;
				if (config.styles.containsKey(id) || config.hasAdjust(id)) {
					fill(ctx, tx + tile - 5, ty + 2, tx + tile - 2, ty + 5, pal.accent());
				}
				addHit(tx, ty, tx + tile, ty + tile, y, y + areaH, () -> {
					if (t.isPart()) selectedPart.put(category, t.part);
					else selected = t.item;
					bulkMode = false;
					settingsOpen = false;
					scroll.put("cards", 0);
					scroll.put("side", 0);
				}, nameOf(t));
			}
			ctx.disableScissor();
		}
		y += areaH + 8;

		// toolbar: pack search, filter, sort
		int sortW = textW(config.sortZtoA ? "Name Z-A" : "Name A-Z") + 14;
		String[] filterNames = {"All", "Favorites", "Recent"};
		int segW = 6;
		for (String f : filterNames) segW += textW(f) + 12;
		int searchW = Math.max(60, w - sortW - segW - 12);
		int sx1 = x, sx2 = x + searchW;
		roundRect(ctx, sx1, y, sx2, y + 16, styleSearch.isFocused() ? pal.surfaceHover() : pal.surface());
		outline(ctx, sx1, y, sx2, y + 16, styleSearch.isFocused() ? pal.accent() : pal.surfaceBorder());
		drawSearchIcon(ctx, sx1 + 5, y + 4, MUTED);
		styleSearch.visible = true;
		styleSearch.setX(sx1 + 16);
		styleSearch.setY(y + 4);
		styleSearch.setWidth(searchW - 20);

		int fx = sx2 + 6;
		roundRect(ctx, fx, y, fx + segW, y + 16, pal.surface());
		outline(ctx, fx, y, fx + segW, y + 16, pal.surfaceBorder());
		int cx = fx + 3;
		for (int i = 0; i < filterNames.length; i++) {
			String f = filterNames[i];
			int fw = textW(f) + 10;
			boolean on = filter.ordinal() == i;
			boolean hover = inside(mouseX, mouseY, cx, y + 2, cx + fw, y + 14);
			if (on) roundRect(ctx, cx, y + 2, cx + fw, y + 14, pal.soft());
			else if (hover) roundRect(ctx, cx, y + 2, cx + fw, y + 14, pal.surface());
			text(ctx, f, cx + 5, y + 4, on ? 0xFFFFFFFF : MUTED);
			final Filter target = Filter.values()[i];
			hits.add(new Hit(cx, y + 2, cx + fw, y + 14, () -> {
				filter = target;
				scroll.put("cards", 0);
			}, null, null, null));
			cx += fw + 2;
		}
		button(ctx, x + w - sortW, y, sortW, 16, config.sortZtoA ? "Name Z-A" : "Name A-Z", false, false, mouseX, mouseY, () -> {
			config.sortZtoA = !config.sortZtoA;
			config.save();
		}, "Change the order of the packs");
		y += 22;

		if (!ready()) {
			text(ctx, "Loading packs...", x, y + 4, MUTED);
			return;
		}
		if (bulkMode && !searching && category != Category.ALL) {
			drawBulk(ctx, x, y, w, footerY - 6, mouseX, mouseY);
		} else if (cur != null) {
			drawCards(ctx, cur, x, y, w, footerY - 6, mouseX, mouseY);
		}
	}

	/** Sorts pack groups by name and applies the favorites / recent filter and the pack search. */
	private <T> List<T> arrange(List<T> groups, java.util.function.Function<T, List<FilePackSource>> packsOf) {
		List<T> list = new ArrayList<>();
		for (T g : groups) {
			List<FilePackSource> packs = packsOf.apply(g);
			List<String> ids = new ArrayList<>();
			StringBuilder names = new StringBuilder();
			for (FilePackSource p : packs) {
				ids.add(p.id());
				names.append(p.displayName().toLowerCase(Locale.ROOT)).append(' ');
			}
			if (!styleQuery.isEmpty() && !names.toString().contains(styleQuery)) continue;
			if (filter == Filter.FAVORITES && !isFavorite(ids)) continue;
			if (filter == Filter.RECENT && ids.stream().noneMatch(config.recent::contains)) continue;
			list.add(g);
		}
		if (filter == Filter.RECENT) {
			list.sort(java.util.Comparator.comparingInt(g -> packsOf.apply(g).stream()
				.mapToInt(p -> config.recent.indexOf(p.id())).filter(i -> i >= 0).min().orElse(999)));
		} else {
			java.util.Comparator<T> byName = java.util.Comparator.comparing(
				g -> packsOf.apply(g).get(0).displayName().toLowerCase(Locale.ROOT));
			list.sort(config.sortZtoA ? byName.reversed() : byName);
		}
		return list;
	}

	private static String names(List<FilePackSource> packs) {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < packs.size(); i++) {
			if (i > 0) b.append(", ");
			b.append(packs.get(i).displayName());
		}
		return b.toString();
	}

	private void drawCards(GuiGraphicsExtractor ctx, Target t, int x, int top, int w, int bottom, int mouseX, int mouseY) {
		String main = mainFile(t);
		String current = styleOf(t.key);
		List<FilePackSource> packs = packsFor(t);

		// Packs whose files are exactly what the game already shows fold into "Default";
		// packs with identical files for this item become one card.
		List<FilePackSource> likeDefault = new ArrayList<>();
		Map<String, List<FilePackSource>> groups = new LinkedHashMap<>();
		for (FilePackSource pack : packs) {
			if (!pack.id().equals(current) && sameAsBelow(pack, t)) likeDefault.add(pack);
			else groups.computeIfAbsent(fingerprint(pack, t), k -> new ArrayList<>()).add(pack);
		}

		List<Card> cards = new ArrayList<>();
		boolean showFixed = styleQuery.isEmpty() && filter == Filter.ALL;
		if (showFixed) {
			cards.add(new Card(null, List.of(), "Default", "What the game shows now",
				"Don't change this" + (likeDefault.isEmpty() ? "" : ". Same look as: " + names(likeDefault)),
				() -> basePreview(t, false), "below|" + t.key));
			if (!belowIsVanilla(t) || VanillaSource.ID.equals(current)) {
				cards.add(new Card(VanillaSource.ID, List.of(VanillaSource.ID), "Vanilla", "Normal Minecraft look",
					"The normal Minecraft look, even if another pack changes it",
					() -> basePreview(t, true), "vanilla|" + t.key));
			}
		}
		for (List<FilePackSource> group : arrange(new ArrayList<>(groups.values()), g -> g)) {
			FilePackSource shown = group.get(0);
			List<String> ids = new ArrayList<>();
			for (FilePackSource pack : group) {
				ids.add(pack.id());
				if (pack.id().equals(current)) shown = pack;
			}
			final FilePackSource preview = shown;
			String sub = group.size() > 1 ? "Same look in " + group.size() + " packs" : kindOf(shown, t);
			String tip = group.size() == 1 ? shown.displayName() : "Same look in: " + names(group);
			cards.add(new Card(shown.id(), ids, shown.displayName(), sub, tip + " - right-click to favorite",
				() -> previewOf(preview, t), shown.id() + "|" + t.key));
		}

		int gap = 6;
		int cols = Math.max(2, (w + gap) / (58 + gap));
		int cardW = (w - gap * (cols - 1)) / cols;
		int prev = cardW - 8;
		int cardH = 4 + prev + 4 + 20 + smallH() + 1;
		int rows = (cards.size() + cols - 1) / cols;
		boolean worn = !t.isPart() && ItemInfo.of(t.item).hasWornLook();
		int hintH = cards.isEmpty() || packs.isEmpty() ? 26 : worn ? 12 : 0;
		int content = rows * (cardH + gap) + hintH;
		int off = scrollOf("cards", content, bottom - top);
		scrollAreas.add(new ScrollArea("cards", x, top, x + w, bottom, Math.max(0, content - (bottom - top))));

		ctx.enableScissor(x, top, x + w, bottom);
		for (int i = 0; i < cards.size(); i++) {
			Card card = cards.get(i);
			int cx = x + (i % cols) * (cardW + gap);
			int cy = top + (i / cols) * (cardH + gap) - off;
			if (cy + cardH < top || cy > bottom) continue;
			boolean on = current == null ? card.sourceId == null : card.ids.contains(current);
			boolean hover = inside(mouseX, mouseY, cx, Math.max(cy, top), cx + cardW, Math.min(cy + cardH, bottom));
			roundRect(ctx, cx, cy, cx + cardW, cy + cardH, on ? pal.cardOn() : hover ? pal.surfaceHover() : pal.surface());
			if (on) {
				outline(ctx, cx, cy, cx + cardW, cy + cardH, pal.accent());
				outline(ctx, cx + 1, cy + 1, cx + cardW - 1, cy + cardH - 1, pal.accent());
			}
			roundRect(ctx, cx + 4, cy + 4, cx + 4 + prev, cy + 4 + prev, pal.well());
			int img = prev - 10;
			if (!drawPreview(ctx, card.imageKey, card.image, cx + 9, cy + 9, img)) {
				drawTargetIcon(ctx, t, cx + 9, cy + 9, img);
			}
			// name on up to two lines, then the kind of change
			String[] lines = twoLines(card.name, cardW - 8);
			int ny = cy + 4 + prev + 4;
			text(ctx, lines[0], cx + 4, ny, TEXT);
			if (!lines[1].isEmpty()) text(ctx, lines[1], cx + 4, ny + 10, TEXT);
			small(ctx, smallTrim(card.sub, cardW - 8), cx + 4, ny + (lines[1].isEmpty() ? 10 : 20), MUTED);

			boolean fav = card.sourceId != null && !VanillaSource.ID.equals(card.sourceId) && isFavorite(card.ids);
			if (on) {
				roundRect(ctx, cx + cardW - 15, cy + 6, cx + cardW - 6, cy + 15, pal.strong());
				drawCheck(ctx, cx + cardW - 13, cy + 8, 0xFFFFFFFF);
			}
			final List<String> ids = card.ids;
			final String sourceId = card.sourceId;
			final boolean alreadyOn = on;
			Runnable favToggle = () -> toggleFavorite(ids);
			addHit(cx, cy, cx + cardW, cy + cardH, top, bottom, () -> {
				if (alreadyOn) return;
				setStyle(t.key, sourceId);
				changed(true);
			}, favToggle, card.tooltip);
			if (card.sourceId != null && !VanillaSource.ID.equals(card.sourceId) && (fav || hover)) {
				drawStar(ctx, cx + 7, cy + 7, fav ? GOLD : 0x99FFFFFF);
				addHit(cx + 5, cy + 5, cx + 15, cy + 15, top, bottom, favToggle, favToggle,
					fav ? "Remove from favorites" : "Add to favorites");
			}
		}
		int hy = top + rows * (cardH + gap) - off + 2;
		if (cards.isEmpty()) {
			text(ctx, styleQuery.isEmpty() ? "No packs here yet." : "No packs match \"" + styleQuery + "\"", x, hy, MUTED);
			if (filter == Filter.FAVORITES) small(ctx, "Right-click a style card to add it to favorites.", x, hy + 11, MUTED);
		} else if (packs.isEmpty()) {
			text(ctx, "None of your packs change this.", x, hy, MUTED);
			small(ctx, "Add packs to your resourcepacks folder and press Rescan.", x, hy + 11, MUTED);
		} else if (worn) {
			small(ctx, "Packs here also change how it looks when worn or used.", x, hy, MUTED);
		}
		ctx.disableScissor();
	}

	/** Splits a name into two lines that fit, ending the second with "..." if needed. */
	private String[] twoLines(String s, int max) {
		if (textW(s) <= max) return new String[]{s, ""};
		String[] words = s.split(" ");
		StringBuilder first = new StringBuilder();
		int i = 0;
		while (i < words.length) {
			String next = first.length() == 0 ? words[i] : first + " " + words[i];
			if (textW(next) > max) break;
			first.setLength(0);
			first.append(next);
			i++;
		}
		if (first.length() == 0) {
			// one long word: cut it
			String a = trim(s, max);
			String cut = a.endsWith("...") ? a.substring(0, a.length() - 3) : a;
			return new String[]{cut, trim(s.substring(cut.length()), max)};
		}
		StringBuilder rest = new StringBuilder();
		for (; i < words.length; i++) {
			if (rest.length() > 0) rest.append(' ');
			rest.append(words[i]);
		}
		return new String[]{first.toString(), trim(rest.toString(), max)};
	}

	/** One pack for all: a row per pack showing which items of the group it changes. */
	private void drawBulk(GuiGraphicsExtractor ctx, int x, int top, int w, int bottom, int mouseX, int mouseY) {
		List<Target> items = groupTargets();
		Map<String, List<FilePackSource>> groups = new LinkedHashMap<>();
		Map<String, int[]> counts = new HashMap<>();
		for (FilePackSource pack : index.packs()) {
			int count = 0;
			StringBuilder look = new StringBuilder();
			for (Target t : items) {
				if (provides(pack, t)) {
					count++;
					look.append(t.key).append('=').append(fingerprint(pack, t)).append(';');
				}
			}
			if (count == 0) continue;
			counts.put(pack.id(), new int[]{count});
			groups.computeIfAbsent(look.toString(), k -> new ArrayList<>()).add(pack);
		}
		List<List<FilePackSource>> rows = arrange(new ArrayList<>(groups.values()), g -> g);

		int rowH = 34, gap = 5;
		int content = rows.size() * (rowH + gap) + 22;
		int off = scrollOf("cards", content, bottom - top);
		scrollAreas.add(new ScrollArea("cards", x, top, x + w, bottom, Math.max(0, content - (bottom - top))));

		ctx.enableScissor(x, top, x + w, bottom);
		int y = top - off;
		// reset row
		boolean anyStyled = items.stream().anyMatch(t -> styleOf(t.key) != null);
		String reset = "Reset all " + category.label.toLowerCase(Locale.ROOT) + " to Default";
		if (anyStyled) {
			int rw = textW(reset) + 14;
			if (y + 15 > top && y < bottom) {
				button(ctx, x + w - rw, y, rw, 15, reset, false, false, mouseX, mouseY, () -> useForCategory(null), null);
			}
		}
		small(ctx, "Faded tiles = that pack doesn't change this item.", x, y + 5, MUTED);
		y += 22;

		int tile = 20;
		int buttonW = 74;
		for (List<FilePackSource> group : rows) {
			if (y + rowH < top || y > bottom) {
				y += rowH + gap;
				continue;
			}
			FilePackSource pack = group.get(0);
			int count = counts.get(pack.id())[0];
			boolean inUse = true;
			for (Target t : items) {
				if (provides(pack, t)) {
					String s = styleOf(t.key);
					if (s == null || group.stream().noneMatch(p -> p.id().equals(s))) {
						inUse = false;
						break;
					}
				}
			}
			roundRect(ctx, x, y, x + w, y + rowH, inUse ? pal.cardOn() : pal.surface());
			if (inUse) outline(ctx, x, y, x + w, y + rowH, pal.accent());

			int nameW = Math.max(70, w / 3);
			bold(ctx, trim(pack.displayName() + (group.size() > 1 ? " +" + (group.size() - 1) : ""), nameW - 10), x + 8, y + 7, TEXT);
			String sub = (inUse ? "In use - " : "") + "Changes " + count + " of " + items.size();
			small(ctx, smallTrim(sub, nameW - 10), x + 8, y + 19, inUse ? pal.accentText() : MUTED);

			int tx = x + nameW;
			int tilesEnd = x + w - buttonW - 10;
			int fit = Math.max(0, (tilesEnd - tx + 3) / (tile + 3));
			for (int k = 0; k < items.size() && k < fit; k++) {
				Target t = items.get(k);
				boolean has = provides(pack, t);
				int ty = y + 7;
				int ox = tx + k * (tile + 3);
				if (k == fit - 1 && items.size() > fit) {
					roundRect(ctx, ox, ty, ox + tile, ty + tile, pal.surface());
					String more = "+" + (items.size() - fit + 1);
					small(ctx, more, ox + (tile - smallW(more)) / 2, ty + 7, MUTED);
					break;
				}
				roundRect(ctx, ox, ty, ox + tile, ty + tile, has ? pal.well() : pal.surface());
				if (has) {
					if (!drawPreview(ctx, pack.id() + "|" + t.key, () -> previewOf(pack, t), ox + 2, ty + 2, tile - 4)) {
						drawTargetIcon(ctx, t, ox + 2, ty + 2, 16);
					}
				} else {
					drawTargetIcon(ctx, t, ox + 2, ty + 2, 16);
					fill(ctx, ox, ty, ox + tile, ty + tile, 0x99000000 | (theme().base & 0xFFFFFF));
				}
				addHit(ox, ty, ox + tile, ty + tile, top, bottom, null, null,
					nameOf(t) + (has ? "" : " (not in this pack)"));
			}

			String label = inUse ? "In use" : "Use for " + count;
			if (y + 9 >= top && y + 25 <= bottom) {
				button(ctx, x + w - buttonW - 6, y + 9, buttonW, 16, label, !inUse, inUse, mouseX, mouseY,
					inUse ? () -> { } : () -> useForCategory(pack.id()),
					inUse ? null : "Use " + pack.displayName() + " for the " + count + " item(s) it changes");
			}
			y += rowH + gap;
		}
		if (rows.isEmpty()) {
			text(ctx, styleQuery.isEmpty() ? "None of your packs change items in this group." : "No packs match \"" + styleQuery + "\"",
				x, y + 2, MUTED);
		}
		ctx.disableScissor();
	}

	private void drawSide(GuiGraphicsExtractor ctx, int mouseX, int mouseY) {
		int x1 = sideX, x2 = px + pw - 1, top = bodyY, bottom = footerY;
		fill(ctx, x1, top, x2, bottom, pal.rail());
		fill(ctx, x1 - 1, top, x1, bottom, pal.line());
		Target sideTarget = current();
		if (sideTarget != null && sideTarget.isPart()) {
			drawPartSide(ctx, sideTarget, x1, x2, top);
			return;
		}
		if (selected == null) {
			text(ctx, "Pick an item", x1 + 10, top + 10, MUTED);
			return;
		}
		ItemInfo info = ItemInfo.of(selected);
		int ix1 = x1 + 8, ix2 = x2 - 8, iw = ix2 - ix1;

		int content = 400;
		int off = scrollOf("side", content, bottom - top);
		ctx.enableScissor(x1, top, x2, bottom);
		int y = top + 8 - off;

		// preview stage: the chosen style's texture, big
		roundRect(ctx, ix1, y, ix2, y + 56, pal.well());
		outline(ctx, ix1, y, ix2, y + 56, pal.line());
		String style = ready() ? styleOf(selected) : null;
		boolean drawn = false;
		if (ready() && style != null && index.get(style) != null) {
			PackSource src = index.get(style);
			Target shownItem = itemTarget(selected);
			drawn = drawPreview(ctx, src.id() + "|" + info.id, () -> previewOf(src, shownItem), ix1 + iw / 2 - 20, y + 8, 40);
		}
		if (!drawn) drawTargetIcon(ctx, itemTarget(selected), ix1 + iw / 2 - 20, y + 8, 40);
		small(ctx, "Preview", ix1 + 5, y + 46, MUTED);
		y += 62;

		bold(ctx, trim(stack(selected).getHoverName().getString(), iw), ix1, y, TEXT);
		y += 11;
		small(ctx, smallTrim(info.id, iw), ix1, y, MUTED);
		y += smallH();
		text(ctx, "Style", ix1, y + 2, MUTED);
		String styleName = style == null ? "Default" : index != null && index.get(style) != null ? index.get(style).displayName() : style + " (missing)";
		int chipX = ix1 + textW("Style") + 6;
		String chip = trim(styleName, ix2 - chipX - 10);
		roundRect(ctx, chipX, y, chipX + textW(chip) + 10, y + 12, pal.soft());
		text(ctx, chip, chipX + 5, y + 2, pal.accentText());
		y += 18;
		fill(ctx, ix1, y, ix2, y + 1, pal.line());
		y += 7;

		bold(ctx, "Size & position", ix1, y, TEXT);
		String resetLabel = "Reset";
		int rw = textW(resetLabel);
		boolean resetHover = inside(mouseX, mouseY, ix2 - rw - 2, y - 2, ix2, y + 10);
		text(ctx, resetLabel, ix2 - rw, y, resetHover ? 0xFFFFFFFF : pal.accentText());
		String itemId = info.id;
		hits.add(new Hit(ix2 - rw - 2, y - 2, ix2, y + 10, () -> {
			Adjust a = config.getAdjust(itemId, view);
			if (a != null && !a.isDefault()) {
				a.reset();
				changed(true);
			}
		}, null, "Reset this view", null));
		y += 14;

		// view buttons, 3 per row
		View[] views = View.values();
		int vgap = 3;
		int vw = (iw - vgap * 2) / 3;
		for (int i = 0; i < views.length; i++) {
			View v = views[i];
			int vx = ix1 + (i % 3) * (vw + vgap);
			int vy = y + (i / 3) * 16;
			boolean on = v == view;
			Adjust adj = config.getAdjust(itemId, v);
			boolean edited = adj != null && !adj.isDefault();
			boolean hover = inside(mouseX, mouseY, vx, vy, vx + vw, vy + 13);
			roundRect(ctx, vx, vy, vx + vw, vy + 13, on ? pal.strong() : hover ? pal.surfaceHover() : pal.surface());
			if (!on) outline(ctx, vx, vy, vx + vw, vy + 13, pal.surfaceBorder());
			String l = smallTrim(pixelFont() ? shortLabel(v) : v.label, vw - 4);
			small(ctx, l, vx + (vw - smallW(l)) / 2, vy + (pixelFont() ? 3 : 4), on ? 0xFFFFFFFF : edited ? pal.accentText() : TEXT2);
			final View target = v;
			hits.add(new Hit(vx, vy, vx + vw, vy + 13, () -> view = target, null, v.label, null));
		}
		y += 2 * 16 + 4;

		Adjust current = config.getAdjust(itemId, view);
		Adjust shown = current == null ? new Adjust() : current;
		y = slider(ctx, "Size", "%", 0.1f, 3f, 0.05f, 1f, () -> shown.scale,
			v -> config.editAdjust(itemId, view).scale = v, ix1, ix2, y, mouseX, mouseY, 100f);
		y = slider(ctx, "Left / right", "", -16f, 16f, 0.25f, 0f, () -> shown.x,
			v -> config.editAdjust(itemId, view).x = v, ix1, ix2, y, mouseX, mouseY, 1f);
		y = slider(ctx, "Up / down", "", -16f, 16f, 0.25f, 0f, () -> shown.y,
			v -> config.editAdjust(itemId, view).y = v, ix1, ix2, y, mouseX, mouseY, 1f);
		y = slider(ctx, "Forward / back", "", -16f, 16f, 0.25f, 0f, () -> shown.z,
			v -> config.editAdjust(itemId, view).z = v, ix1, ix2, y, mouseX, mouseY, 1f);
		y = slider(ctx, "Tilt", "°", -180f, 180f, 5f, 0f, () -> shown.rotX,
			v -> config.editAdjust(itemId, view).rotX = v, ix1, ix2, y, mouseX, mouseY, 1f);
		y = slider(ctx, "Turn", "°", -180f, 180f, 5f, 0f, () -> shown.rotY,
			v -> config.editAdjust(itemId, view).rotY = v, ix1, ix2, y, mouseX, mouseY, 1f);
		y = slider(ctx, "Roll", "°", -180f, 180f, 5f, 0f, () -> shown.rotZ,
			v -> config.editAdjust(itemId, view).rotZ = v, ix1, ix2, y, mouseX, mouseY, 1f);
		y += 2;
		String resetAll = "Reset all views";
		boolean raHover = inside(mouseX, mouseY, ix1, y - 2, ix1 + textW(resetAll), y + 10);
		text(ctx, resetAll, ix1, y, raHover ? 0xFFFFFFFF : pal.accentText());
		hits.add(new Hit(ix1, y - 2, ix1 + textW(resetAll), y + 10, () -> {
			if (config.hasAdjust(itemId)) {
				config.adjust.remove(itemId);
				changed(true);
			}
		}, null, null, null));
		y += 12;
		y = smallWrapped(ctx, "Right-click a slider to reset it", ix1, y, iw, MUTED);
		ctx.disableScissor();

		int realContent = y + off - top + 6;
		scroll.put("side", Math.max(0, Math.min(Math.max(0, realContent - (bottom - top)), scroll.getOrDefault("side", 0))));
		scrollAreas.add(new ScrollArea("side", x1, top, x2, bottom, Math.max(0, realContent - (bottom - top))));
	}

	private void drawPartSide(GuiGraphicsExtractor ctx, Target t, int x1, int x2, int top) {
		int ix1 = x1 + 8, ix2 = x2 - 8, iw = ix2 - ix1;
		int y = top + 8;
		roundRect(ctx, ix1, y, ix2, y + 56, pal.well());
		outline(ctx, ix1, y, ix2, y + 56, pal.line());
		String style = ready() ? styleOf(t.key) : null;
		PackSource src = style == null || index == null ? null : index.get(style);
		boolean drawn = false;
		if (src != null) {
			drawn = drawPreview(ctx, src.id() + "|" + t.key, () -> previewOf(src, t), ix1 + iw / 2 - 20, y + 8, 40);
		}
		if (!drawn) drawTargetIcon(ctx, t, ix1 + iw / 2 - 20, y + 8, 40);
		small(ctx, "Preview", ix1 + 5, y + 46, MUTED);
		y += 62;
		bold(ctx, trim(t.part.label(), iw), ix1, y, TEXT);
		y += 11;
		small(ctx, t.part.kind(), ix1, y, MUTED);
		y += smallH();
		text(ctx, "Style", ix1, y + 2, MUTED);
		String styleName = style == null ? "Default" : src != null ? src.displayName() : style + " (missing)";
		int chipX = ix1 + textW("Style") + 6;
		String chip = trim(styleName, ix2 - chipX - 10);
		roundRect(ctx, chipX, y, chipX + textW(chip) + 10, y + 12, pal.soft());
		text(ctx, chip, chipX + 5, y + 2, pal.accentText());
		y += 18;
		fill(ctx, ix1, y, ix2, y + 1, pal.line());
		y += 8;
		smallWrapped(ctx, "These use the pack's textures as they are. Size and position are for items only.", ix1, y, iw, MUTED);
	}

	private int slider(GuiGraphicsExtractor ctx, String label, String unit, float min, float max, float step, float def,
	                   Supplier<Float> get, Consumer<Float> set, int barX1, int barX2, int y, int mouseX, int mouseY,
	                   float displayMul) {
		float value = get.get();
		boolean changedValue = Math.abs(value - def) > 1e-4f;
		text(ctx, label, barX1, y, changedValue ? TEXT : TEXT2);
		String shown = displayMul != 1f ? Math.round(value * displayMul) + unit
			: (step >= 1f ? String.valueOf(Math.round(value)) : String.format(Locale.ROOT, "%.2f", value)) + unit;
		text(ctx, shown, barX2 - textW(shown), y, changedValue ? pal.accentText() : MUTED);

		int by = y + 12;
		roundRect(ctx, barX1, by, barX2, by + 3, pal.track());
		float t = (value - min) / (max - min);
		float t0 = (def - min) / (max - min);
		int kx = barX1 + Math.round(t * (barX2 - barX1));
		int zx = barX1 + Math.round(t0 * (barX2 - barX1));
		fill(ctx, Math.min(kx, zx), by, Math.max(kx, zx), by + 3, pal.accent());
		boolean hover = inside(mouseX, mouseY, barX1 - 3, y, barX2 + 3, y + 17) || dragging != null && dragging.label.equals(label);
		if (hover) roundRect(ctx, kx - 5, by - 3, kx + 5, by + 6, pal.glow());
		roundRect(ctx, kx - 3, by - 2, kx + 3, by + 5, 0xFFFFFFFF);

		Slider slider = new Slider(label, min, max, step, def, get, set, barX1, barX2);
		hits.add(new Hit(barX1 - 3, y, barX2 + 3, y + 17, null, null, null, slider));
		return y + 20;
	}

	private void drawSettings(GuiGraphicsExtractor ctx, int mouseX, int mouseY) {
		int x1 = sideX, x2 = px + pw - 1, top = bodyY, bottom = footerY;
		fill(ctx, x1, top, x2, bottom, pal.rail());
		fill(ctx, x1 - 1, top, x1, bottom, pal.line());
		int ix1 = x1 + 8, ix2 = x2 - 8, iw = ix2 - ix1;
		int y = top + 10;
		bold(ctx, "Settings", ix1, y, TEXT);
		// mod on/off switch, right side of the title row
		boolean modOn = config.enabled;
		String onLabel = modOn ? "Mod on" : "Mod off";
		int swX = ix2 - 18 - 5 - textW(onLabel);
		int trackY = y - 1;
		roundRect(ctx, swX, trackY, swX + 18, trackY + 10, modOn ? pal.strong() : pal.line2());
		int knobX = modOn ? swX + 9 : swX + 1;
		roundRect(ctx, knobX, trackY + 1, knobX + 8, trackY + 9, 0xFFFFFFFF);
		text(ctx, onLabel, swX + 23, y, modOn ? TEXT2 : WARN);
		hits.add(new Hit(swX - 2, y - 3, ix2, y + 11, () -> {
			ItemStudioClient.setEnabled(!config.enabled);
			setStatus(config.enabled ? "Item Studio turned on" : "Item Studio turned off - your normal packs are back", config.enabled ? GOOD : WARN);
		}, null, "Turn Item Studio on or off. Off puts your normal packs back; your picks stay saved.", null));
		y += 16;

		text(ctx, "Theme", ix1, y, TEXT2);
		y += 12;
		Theme[] themes = Theme.values();
		int gap = 4;
		int tw = (iw - gap) / 2;
		for (int i = 0; i < themes.length; i++) {
			Theme t = themes[i];
			int tx = ix1 + (i % 2) * (tw + gap);
			int ty = y + (i / 2) * 18;
			boolean on = t == theme();
			boolean hover = inside(mouseX, mouseY, tx, ty, tx + tw, ty + 15);
			roundRect(ctx, tx, ty, tx + tw, ty + 15, on ? pal.soft() : hover ? pal.surfaceHover() : pal.surface());
			outline(ctx, tx, ty, tx + tw, ty + 15, on ? pal.accent() : pal.surfaceBorder());
			roundRect(ctx, tx + 4, ty + 4, tx + 11, ty + 11, 0xFF000000 | t.accent);
			text(ctx, t.label, tx + 15, ty + 4, on ? 0xFFFFFFFF : TEXT2);
			hits.add(new Hit(tx, ty, tx + tw, ty + 15, () -> {
				config.theme = t.name();
				config.save();
			}, null, null, null));
		}
		y += 3 * 18 + 6;

		y = slider(ctx, "Opacity", "%", 0.4f, 1f, 0.05f, 0.78f, () -> config.opacity,
			v -> {
				config.opacity = v;
				config.save();
			}, ix1, ix2, y, mouseX, mouseY, 100f);
		y += 4;

		text(ctx, "Text font", ix1, y, TEXT2);
		y += 12;
		String[] fonts = {"Minecraft", "Poppins"};
		for (int i = 0; i < 2; i++) {
			int fx = ix1 + i * (tw + gap);
			boolean on = config.customFont == (i == 1);
			boolean hover = inside(mouseX, mouseY, fx, y, fx + tw, y + 15);
			roundRect(ctx, fx, y, fx + tw, y + 15, on ? pal.soft() : hover ? pal.surfaceHover() : pal.surface());
			outline(ctx, fx, y, fx + tw, y + 15, on ? pal.accent() : pal.surfaceBorder());
			text(ctx, fonts[i], fx + (tw - textW(fonts[i])) / 2, y + 4, on ? 0xFFFFFFFF : TEXT2);
			final boolean custom = i == 1;
			hits.add(new Hit(fx, y, fx + tw, y + 15, () -> {
				config.customFont = custom;
				config.save();
			}, null, null, null));
		}
		y += 24;
		String keyName = ItemStudioClient.openKey().getTranslatedKeyMessage().getString();
		y = smallWrapped(ctx, "Menu key: " + keyName, ix1, y, iw, MUTED) + 6;
		int half = (iw - gap) / 2;
		button(ctx, ix1, y, half, 16, "Keybinds", false, false, mouseX, mouseY,
			() -> minecraft.gui.setScreen(new StudioConfigScreen(this)), "Change the menu key and the on/off key");
		button(ctx, ix1 + half + gap, y, iw - half - gap, 16, "Done", true, false, mouseX, mouseY, () -> settingsOpen = false, null);
	}

	private void drawFooter(GuiGraphicsExtractor ctx, int mouseX, int mouseY) {
		fill(ctx, px + 1, footerY, px + pw - 1, footerY + 1, pal.line());
		int y = footerY + 5;
		int right = px + pw - 8;

		String applyLabel = "Apply changes";
		int pillW = pending > 0 ? textW(String.valueOf(pending)) + 8 : 0;
		int aw = textW(applyLabel) + 16 + (pending > 0 ? pillW + 6 : 0);
		right -= aw;
		boolean aHover = inside(mouseX, mouseY, right, y, right + aw, y + 16);
		roundRect(ctx, right, y, right + aw, y + 16, aHover ? pal.strongHover() : pal.strong());
		bold(ctx, applyLabel, right + 8, y + 4, 0xFFFFFFFF);
		if (pending > 0) {
			int pxl = right + 8 + textW(applyLabel) + 6;
			roundRect(ctx, pxl, y + 3, pxl + pillW, y + 13, 0x38FFFFFF);
			text(ctx, String.valueOf(pending), pxl + 4, y + 4, 0xFFFFFFFF);
		}
		hits.add(new Hit(right, y, right + aw, y + 16, this::apply, null, "Build the pack and reload textures", null));

		// auto-apply switch
		String auto = "Auto-apply";
		int sw = 18 + 5 + textW(auto);
		right -= sw + 12;
		int trackY = y + 3;
		roundRect(ctx, right, trackY, right + 18, trackY + 10, config.applyInstantly ? pal.strong() : pal.line2());
		int knobX = config.applyInstantly ? right + 9 : right + 1;
		roundRect(ctx, knobX, trackY + 1, knobX + 8, trackY + 9, 0xFFFFFFFF);
		text(ctx, auto, right + 23, y + 4, TEXT2);
		hits.add(new Hit(right, y, right + sw, y + 16, () -> {
			config.applyInstantly = !config.applyInstantly;
			config.save();
			if (config.applyInstantly && pending > 0) apply();
		}, null, "Apply every change right away (each one reloads textures)", null));
		right -= 8;
		fill(ctx, right, y + 2, right + 1, y + 14, pal.line2());

		boolean small = pw < 520;
		String rescan = "Rescan";
		int rw = textW(rescan) + 12;
		right -= rw + 6;
		flatButton(ctx, right, y, rw, 16, rescan, mouseX, mouseY, this::rescan, "Look for new packs in the resourcepacks folder");
		String folder = small ? "Folder" : "Packs folder";
		int fw = textW(folder) + 12;
		right -= fw + 2;
		flatButton(ctx, right, y, fw, 16, folder, mouseX, mouseY,
			() -> Util.getPlatform().openUri(minecraft.getResourcePackDirectory().toUri()), "Open your resourcepacks folder");

		int sx = px + 12;
		roundRect(ctx, sx, y + 5, sx + 5, y + 10, statusColor == WARN ? WARN : statusColor == GOOD ? GOOD : MUTED);
		String found = index != null ? "  -  " + index.packs().size() + " packs found" : "";
		int room = right - sx - 14;
		if (room > 20) {
			String st = trim(status, room);
			text(ctx, st, sx + 10, y + 4, statusColor == MUTED ? TEXT2 : statusColor);
			int used = textW(st);
			if (!found.isEmpty() && used + textW(found) < room) {
				text(ctx, found, sx + 10 + used, y + 4, MUTED);
			}
		}
	}

	// ------------------------------------------------------------------ previews

	/** Bytes of the texture that best shows a pack's version of an item. */
	private static byte[] previewBytes(PackSource pack, ItemInfo info) {
		try {
			if (info.mainTexture != null && pack.has(info.mainTexture)) return pack.read(info.mainTexture);
		} catch (Exception ignored) {
		}
		String own = "assets/minecraft/textures/item/" + Identifier.parse(info.id).getPath() + ".png";
		try {
			if (pack.has(own)) return pack.read(own);
		} catch (Exception ignored) {
		}
		for (String model : info.chainModels) {
			try {
				String file = Refs.modelFile(model);
				if (!pack.has(file)) continue;
				com.google.gson.JsonElement json = Resources.parse(pack.read(file));
				if (json == null || !json.isJsonObject() || !json.getAsJsonObject().has("textures")) continue;
				com.google.gson.JsonObject textures = json.getAsJsonObject().getAsJsonObject("textures");
				for (String key : textures.keySet()) {
					String ref = Refs.textureRef(textures.get(key));
					if (ref == null) continue;
					String tex = Refs.textureFile(ref);
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
	private boolean drawPreview(GuiGraphicsExtractor ctx, String key, Supplier<byte[]> bytes, int x, int y, int size) {
		PreviewTextures.Preview p = previews.get(key, bytes);
		if (p == null) return false;
		// Whole-number scales keep pixel art sharp; only pictures bigger than the box are shrunk.
		if (p.width() > p.height()) {
			// wide picture (hotbar, XP bar, buttons): show all of it, centred
			int k = size / p.width();
			int w = k >= 1 ? p.width() * k : size;
			int h = k >= 1 ? p.height() * k : Math.max(1, size * p.height() / p.width());
			ctx.blit(RenderPipelines.GUI_TEXTURED, p.id(), x + (size - w) / 2, y + (size - h) / 2, 0f, 0f, w, h,
				p.width(), p.height(), p.width(), p.height());
			return true;
		}
		// square or tall (animated strip): show the first square frame
		int square = p.width();
		int k = size / square;
		int d = k >= 1 ? square * k : size;
		ctx.blit(RenderPipelines.GUI_TEXTURED, p.id(), x + (size - d) / 2, y + (size - d) / 2, 0f, 0f, d, d,
			square, square, p.width(), p.height());
		return true;
	}

	// ------------------------------------------------------------------ small drawing helpers

	private void button(GuiGraphicsExtractor ctx, int x, int y, int w, int h, String label, boolean primary, boolean outlined,
	                    int mouseX, int mouseY, Runnable action, String tooltip) {
		boolean hover = inside(mouseX, mouseY, x, y, x + w, y + h);
		int bg = primary ? (hover ? pal.strongHover() : pal.strong()) : outlined ? pal.cardOn() : (hover ? pal.surfaceHover() : pal.surface());
		roundRect(ctx, x, y, x + w, y + h, bg);
		if (!primary) outline(ctx, x, y, x + w, y + h, outlined ? pal.accent() : pal.surfaceBorder());
		int tw = textW(label);
		text(ctx, label, x + (w - tw) / 2, y + (h - 8) / 2, primary ? 0xFFFFFFFF : outlined ? pal.accentText() : TEXT);
		hits.add(new Hit(x, y, x + w, y + h, action, null, tooltip, null));
	}

	private void flatButton(GuiGraphicsExtractor ctx, int x, int y, int w, int h, String label, int mouseX, int mouseY,
	                        Runnable action, String tooltip) {
		boolean hover = inside(mouseX, mouseY, x, y, x + w, y + h);
		if (hover) roundRect(ctx, x, y, x + w, y + h, pal.surfaceHover());
		text(ctx, label, x + 6, y + 4, hover ? 0xFFFFFFFF : TEXT2);
		hits.add(new Hit(x, y, x + w, y + h, action, null, tooltip, null));
	}

	private void iconButton(GuiGraphicsExtractor ctx, int x, int y, String tooltip, int mouseX, int mouseY, boolean on, Runnable action) {
		boolean hover = inside(mouseX, mouseY, x, y, x + 16, y + 16);
		roundRect(ctx, x, y, x + 16, y + 16, on ? pal.strong() : hover ? pal.surfaceHover() : pal.surface());
		if (!on) outline(ctx, x, y, x + 16, y + 16, pal.surfaceBorder());
		hits.add(new Hit(x, y, x + 16, y + 16, action, null, tooltip, null));
	}

	private static void drawSearchIcon(GuiGraphicsExtractor ctx, int x, int y, int color) {
		fill(ctx, x + 1, y, x + 5, y + 1, color);
		fill(ctx, x + 1, y + 5, x + 5, y + 6, color);
		fill(ctx, x, y + 1, x + 1, y + 5, color);
		fill(ctx, x + 5, y + 1, x + 6, y + 5, color);
		fill(ctx, x + 5, y + 5, x + 6, y + 6, color);
		fill(ctx, x + 6, y + 6, x + 7, y + 7, color);
		fill(ctx, x + 7, y + 7, x + 8, y + 8, color);
	}

	private static void drawCross(GuiGraphicsExtractor ctx, int x, int y, int color) {
		for (int i = 0; i < 6; i++) {
			fill(ctx, x + i, y + i, x + i + 1, y + i + 1, color);
			fill(ctx, x + 5 - i, y + i, x + 6 - i, y + i + 1, color);
		}
	}

	private static void drawGear(GuiGraphicsExtractor ctx, int x, int y, int color) {
		fill(ctx, x + 2, y + 1, x + 6, y + 7, color);
		fill(ctx, x + 1, y + 2, x + 7, y + 6, color);
		fill(ctx, x + 3, y, x + 5, y + 8, color);
		fill(ctx, x, y + 3, x + 8, y + 5, color);
		fill(ctx, x + 3, y + 3, x + 5, y + 5, 0xFF101010);
	}

	private static void drawCheck(GuiGraphicsExtractor ctx, int x, int y, int color) {
		fill(ctx, x, y + 2, x + 1, y + 3, color);
		fill(ctx, x + 1, y + 3, x + 2, y + 4, color);
		fill(ctx, x + 2, y + 4, x + 3, y + 5, color);
		fill(ctx, x + 3, y + 3, x + 4, y + 4, color);
		fill(ctx, x + 4, y + 2, x + 5, y + 3, color);
		fill(ctx, x + 5, y + 1, x + 6, y + 2, color);
	}

	private static void drawStar(GuiGraphicsExtractor ctx, int x, int y, int color) {
		fill(ctx, x + 2, y, x + 3, y + 1, color);
		fill(ctx, x + 1, y + 1, x + 4, y + 2, color);
		fill(ctx, x, y + 2, x + 5, y + 3, color);
		fill(ctx, x + 1, y + 3, x + 4, y + 4, color);
		fill(ctx, x, y + 4, x + 2, y + 5, color);
		fill(ctx, x + 3, y + 4, x + 5, y + 5, color);
	}

	/** Adds a click area clipped to a scrolling region's visible part. */
	private void addHit(int x1, int y1, int x2, int y2, int clipTop, int clipBottom, Runnable action, Runnable right, String tooltip) {
		int top = Math.max(y1, clipTop);
		int bottom = Math.min(y2, clipBottom);
		if (bottom <= top) return;
		hits.add(new Hit(x1, top, x2, bottom, action, right, tooltip, null));
	}

	private void addHit(int x1, int y1, int x2, int y2, int clipTop, int clipBottom, Runnable action, String tooltip) {
		addHit(x1, y1, x2, y2, clipTop, clipBottom, action, null, tooltip);
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
		if (search.isMouseOver(x, y) || (styleSearch.visible && styleSearch.isMouseOver(x, y))) {
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
			if (click.button() == 1) {
				if (hit.rightAction != null) {
					hit.rightAction.run();
					return true;
				}
				continue;
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
			boolean setting = dragging.label.equals("Opacity");
			dragging = null;
			if (!setting) changed(true);
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
			if (settingsOpen) {
				settingsOpen = false;
				return true;
			}
			onClose();
			return true;
		}
		return super.keyPressed(input);
	}
}
