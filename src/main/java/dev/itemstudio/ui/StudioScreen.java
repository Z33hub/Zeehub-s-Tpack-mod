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
import dev.itemstudio.pack.SoundElement;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
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
 *
 * Look: one spacing scale (2, 4, 6, 8, 12), one corner style picked in the settings, hover fades and a sliding
 * highlight that are cheap (see {@link Anim}), and small pixel icons (see {@link Icons}).
 */
public final class StudioScreen extends Screen {
	// Text colours (the backgrounds come from the theme)
	private static final int TEXT = 0xFFF2F3F8;
	private static final int TEXT2 = 0xFFC4C8D8;
	private static final int MUTED = 0xFF9AA0B8;
	private static final int WARN = 0xFFF5B83D;
	private static final int GOOD = 0xFF5BE49B;
	private static final int GOLD = 0xFFF5B83D;
	private static final int WHITE = 0xFFFFFFFF;

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
			set.accept(clampStep(Math.round(raw / step) * step));
		}

		/** One step up or down (the little arrows next to a slider). Off the step grid, it first moves onto it. */
		void nudge(int steps) {
			float q = get.get() / step;
			boolean onGrid = Math.abs(q - Math.round(q)) < 1e-3f;
			float target = onGrid ? Math.round(q) + steps : steps > 0 ? (float) Math.ceil(q) : (float) Math.floor(q);
			set.accept(clampStep(target * step));
		}

		private float clampStep(float v) {
			return Math.max(min, Math.min(max, v));
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
	/** Card pictures, kept between menu visits; cleared when the pack scan changes. */
	private static final PreviewTextures previews = new PreviewTextures();
	private static int previewGeneration = -1;
	private final Map<Item, ItemStack> stacks = new HashMap<>();
	private final List<Hit> hits = new ArrayList<>();
	private final List<ScrollArea> scrollAreas = new ArrayList<>();
	/** Where each scroll area is heading (scroll) and where it is drawn right now (scrollShown). */
	private final Map<String, Integer> scroll = new HashMap<>();
	private final Map<String, Float> scrollShown = new HashMap<>();
	private static final com.google.gson.Gson SNAPSHOT_GSON = new com.google.gson.Gson();
	private static final java.lang.reflect.Type ADJUST_TYPE =
		new com.google.gson.reflect.TypeToken<java.util.TreeMap<String, java.util.TreeMap<String, Adjust>>>() {}.getType();

	/** Everything the user picks, for undo. */
	private record State(Map<String, String> styles, String adjust, Map<String, Float> partSize, String crosshair) {
	}

	private final java.util.ArrayDeque<State> history = new java.util.ArrayDeque<>();
	/** Smooth movement of the highlighted category in the sidebar. */
	private float railHighlight = Float.NaN;
	private final Anim anim = new Anim();
	private long lastFrame = System.nanoTime();
	/** Seconds the menu has been open, for loading dots and the like. */
	private float clock;
	/** Seconds the last frame took (at most 0.1). */
	private float dt = 0.016f;

	private PackIndex index;
	private CompletableFuture<PackIndex> loading;
	private Category category = Category.SWORDS;
	private Item selected;
	/** The selected part in each texture-part category (HUD, particles). */
	private final Map<Category, TexturePart> selectedPart = new HashMap<>();
	/** Scroll the sidebar / item strip once so the restored category and item are in view. */
	private boolean revealRail, revealTile;
	private View view = View.FIRST_PERSON;
	private Filter filter = Filter.ALL;
	private boolean bulkMode;
	private boolean settingsOpen;
	/** Which page of the settings is showing (0 look, 1 layout, 2 general). Kept while the game runs. */
	private static int lastSettingsTab;
	private int settingsTab = lastSettingsTab;
	/** A key control waiting for the next key press in the settings, or null. */
	private net.minecraft.client.option.KeyBinding listening;
	private String query = "";
	private String styleQuery = "";
	private String searchCacheQuery;
	private List<Item> searchCache = List.of();
	private int pending;
	private Map<String, String> applied;
	private String status = "";
	private int statusColor = MUTED;
	private Slider dragging;
	private TextFieldWidget search;
	private TextFieldWidget styleSearch;
	/** Name box shown while renaming a profile. */
	private TextFieldWidget profileName;
	private boolean profilesOpen;
	/** The profile being renamed is in view (the list can scroll). */
	private boolean renameRowShown;
	private boolean renamingProfile;
	private int profileChipX;
	private long deleteArmedUntil;
	/** Crosshair editor grid, as last drawn. */
	private int crossX, crossY, crossCell, crossTop, crossBottom;
	private boolean crossShown, painting, paintOn;
	private String seedKey, seedPattern;
	private Theme.Palette pal;
	/** The mouse button is down on something: its rectangle looks pressed. */
	private boolean mouseDown;
	private int pressX1, pressY1, pressX2, pressY2;
	/** Tooltips wait a moment: this is what the mouse rests on and since when. */
	private int tipKey;
	private long tipSince;

	// layout (all recomputed every frame by layout(), so changing a setting shows at once)
	private int px, py, pw, ph, bodyY, footerY, railW, sideX, sideW, centerX, centerW;
	private boolean railFull, compact;
	/** Control height, header and footer heights, panel padding, grid gap, tile and rail row size, preview stage height. */
	private int ctl, headerH, footerH, pad, gap, tile, railRow, stageH;
	private String layoutKey = "";

	public StudioScreen(Screen parent) {
		super(Text.translatable("itemstudio.title"));
		this.parent = parent;
		restoreView();
	}

	/** Opens with the settings panel showing (the Mod Menu button uses this). */
	public static StudioScreen withSettings(Screen parent) {
		StudioScreen screen = new StudioScreen(parent);
		screen.settingsOpen = true;
		return screen;
	}

	// Where the menu was left, kept while the game runs (a restart opens on Swords again)
	private static Category lastCategory;
	private static Item lastItem;
	private static final Map<Category, TexturePart> lastParts = new HashMap<>();

	/** Opens on the category, item and parts the menu was left on last time (this session). */
	private void restoreView() {
		if (lastCategory != null) category = lastCategory;
		// part categories (Blocks, HUD...) have no items: the item picked in another category stays unselected
		if (lastItem != null && category.items().contains(lastItem)) selected = lastItem;
		selectedPart.putAll(lastParts);
		revealRail = true;
		revealTile = true;
	}

	/** Keeps the category, item and parts the menu is on, for the next time it opens. */
	private void rememberView() {
		Category open = category;
		if (!query.isEmpty() && selected != null && !open.items().contains(selected)) {
			// closed while showing search results: next time, open on the picked item's own category
			for (Category c : Category.values()) {
				if (c != Category.ALL && c.items().contains(selected)) {
					open = c;
					break;
				}
			}
		}
		lastCategory = open;
		lastItem = selected;
		lastParts.clear();
		lastParts.putAll(selectedPart);
	}

	@Override
	protected void init() {
		closed = false;
		applyMenuScale();
		if (index == null && loading == null) startScan("Loading packs...");
		if (applied == null) applied = snapshot();
		if (history.isEmpty()) pushHistory();
		previews.newSession();
		if (selected == null && !category.items().isEmpty()) selected = category.items().get(0);
		layout();
		// the placeholder texts are chosen to fit by drawHeader / drawCenter, once per size
		searchVariant = -1;
		packVariant = -1;

		// The text boxes are moved to their place every frame (see placeFields), so these sizes are only a start.
		search = new TextFieldWidget(textRenderer, px + pw - 150, py + 8, 100, 10, Text.literal("Search all items"));
		search.setDrawsBackground(false);
		search.setMaxLength(50);
		search.setText(query);
		search.setChangedListener(text -> {
			query = text.trim().toLowerCase(Locale.ROOT);
			scroll.put("items", 0);
			bulkMode = false;
		});
		addDrawableChild(search);

		styleSearch = new TextFieldWidget(textRenderer, centerX + 16, bodyY, 100, 10, Text.literal("Search packs"));
		styleSearch.setDrawsBackground(false);
		styleSearch.setMaxLength(50);
		styleSearch.setText(styleQuery);
		styleSearch.setChangedListener(text -> {
			styleQuery = text.trim().toLowerCase(Locale.ROOT);
			scroll.put("cards", 0);
		});
		addDrawableChild(styleSearch);

		String typed = profileName == null ? null : profileName.getText();
		profileName = new TextFieldWidget(textRenderer, 0, 0, 100, 10, Text.literal("Profile name"));
		profileName.setDrawsBackground(false);
		profileName.setMaxLength(24);
		if (typed != null) profileName.setText(typed);
		profileName.visible = false;
		addDrawableChild(profileName);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	@Override
	public void close() {
		if (renamingProfile) commitRename();
		if (pending > 0) apply();
		client.setScreen(parent);
	}

	@Override
	public void resize(int width, int height) {
		super.resize(width, height);
		applyMenuScale();
	}

	/**
	 * The menu's own GUI scale (Settings > Layout > Menu size): while the menu is open the game's GUI scale is set to
	 * it, and the game's own is put back when the menu closes. Everything (text boxes, mouse, clipping) then works
	 * in the same units, and whole-number scales keep the text sharp.
	 */
	private void applyMenuScale() {
		var window = client.getWindow();
		boolean unicode = client.forcesUnicodeFont();
		int game = window.calculateScaleFactor(client.options.getGuiScale().getValue(), unicode);
		int target = config.menuScale <= 0 ? game : window.calculateScaleFactor(config.menuScale >= 5 ? 0 : config.menuScale, unicode);
		if (window.getScaleFactor() != target) window.setScaleFactor(target);
		width = window.getScaledWidth();
		height = window.getScaledHeight();
	}

	/** Puts the game's own GUI scale back (the menu may have used its own). */
	private void restoreGameScale() {
		var window = client.getWindow();
		int game = window.calculateScaleFactor(client.options.getGuiScale().getValue(), client.forcesUnicodeFont());
		if (window.getScaleFactor() != game) window.setScaleFactor(game);
	}

	@Override
	public void removed() {
		restoreGameScale();
		SoundPreview.stop();
		rememberView();
		closed = true;
		// Comparisons queued for cards that are no longer on screen: drop the ones that haven't started, so an
		// Apply or the next visit doesn't wait behind them. They start again when needed.
		WARM.values().forEach(job -> {
			if (!job.isDone()) job.cancel(false);
		});
		WARM.values().removeIf(CompletableFuture::isCancelled);
		// The scan and pictures are kept for next time; only the open pack files are closed. While an Apply
		// is still building (closing the menu applies), they stay open until it is done.
		if (index != null && !applying) index.close();
		index = null;
		loading = null;
	}

	// ------------------------------------------------------------------ layout

	/**
	 * Works out where everything goes from the screen size and the look settings. Runs every frame (a handful of
	 * integer sums), so a changed setting shows up at once without reopening the menu.
	 */
	private void layout() {
		// Auto density: tighter when the screen is small (GUI scale 4 on a 1080p screen is only 480x270)
		compact = config.density == 1 || (config.density == 0 && (height < 300 || width < 500));
		int margin = width >= 700 && height >= 400 ? 10 : width >= 460 && height >= 260 ? 6 : 4;
		px = margin;
		py = margin;
		pw = width - margin * 2;
		ph = height - margin * 2;
		ctl = compact ? 16 : 18;
		headerH = compact ? 24 : 28;
		footerH = compact ? 24 : 28;
		pad = compact ? 8 : 10;
		gap = compact ? 6 : 8;
		tile = compact ? 22 : 24;
		railRow = compact ? 19 : 22;
		stageH = compact ? 52 : 76;
		bodyY = py + headerH;
		footerY = py + ph - footerH;
		railFull = config.sidebar == 2 || (config.sidebar == 0 && pw >= 500);
		railW = railFull ? Math.max(100, Math.min(pw >= 780 ? 132 : 124, pw / 6)) : compact ? 26 : 30;
		int[] low = {128, 142, 168};
		int[] high = {160, 190, 236};
		float[] share = {0.24f, 0.28f, 0.34f};
		int w = Math.max(0, Math.min(2, config.panelWidth));
		sideW = Math.max(low[w], Math.min(high[w], (int) (pw * share[w])));
		centerX = px + railW + pad;
		// never let the middle get squeezed below what the cards and the toolbar need
		int room = px + pw - sideW - pad - centerX;
		if (room < 150) sideW = Math.max(112, sideW - (150 - room));
		sideX = px + pw - sideW;
		centerW = sideX - centerX - pad;
	}

	// ------------------------------------------------------------------ text with the menu font

	private final Map<String, StyleSpriteSource.Font> fontCache = new HashMap<>();

	/** The menu font for this frame, so it isn't looked up for every piece of text. */
	private MenuFont frameFont;
	/** Text widths and shortened texts, for the current font. Cleared when the font changes. */
	private final Map<String, Integer> widthCache = new HashMap<>();
	private final Map<String, String> trimCache = new HashMap<>();
	/** Ready-made text objects, so drawing a label doesn't build a new one every frame. */
	private final Map<String, MutableText> plainText = new HashMap<>();
	private final Map<String, MutableText> boldText = new HashMap<>();

	private MenuFont menuFont() {
		return frameFont != null ? frameFont : MenuFont.byId(config.fontId());
	}

	private StyleSpriteSource.Font fontOf(MenuFont f, boolean bold) {
		String path = f.id + (bold ? "_semibold" : "");
		return fontCache.computeIfAbsent(path, p -> new StyleSpriteSource.Font(Identifier.of(ItemStudioClient.MOD_ID, p)));
	}

	/** Text in a given menu font; Minecraft's font uses the game's own bold. */
	private MutableText styledIn(String s, MenuFont f, boolean bold) {
		if (f.pixel()) {
			return bold ? Text.literal(s).setStyle(Style.EMPTY.withBold(true)) : Text.literal(s);
		}
		return Text.literal(s).setStyle(Style.EMPTY.withFont(fontOf(f, bold)));
	}

	/** Shared text object for the menu font. Never change it (no withColor); build one with styledIn for that. */
	private MutableText styled(String s, boolean bold) {
		Map<String, MutableText> cache = bold ? boldText : plainText;
		MutableText t = cache.get(s);
		if (t == null) {
			t = styledIn(s, menuFont(), bold);
			cache.put(s, t);
		}
		return t;
	}

	private int textW(String s) {
		Integer w = widthCache.get(s);
		if (w == null) {
			w = textRenderer.getWidth(styled(s, false));
			widthCache.put(s, w);
		}
		return w;
	}

	private int boldW(String s) {
		String key = "\u0001" + s;
		Integer w = widthCache.get(key);
		if (w == null) {
			w = textRenderer.getWidth(styled(s, true));
			widthCache.put(key, w);
		}
		return w;
	}

	/** Shortens text with "..." to fit max pixels. */
	private String trim(String s, int max) {
		if (textW(s) <= max) return s;
		String key = max + "\u0000" + s;
		String cached = trimCache.get(key);
		if (cached != null) return cached;
		int lo = 0, hi = s.length();
		while (lo < hi) {
			int mid = (lo + hi + 1) / 2;
			if (textW(s.substring(0, mid) + "...") <= max) lo = mid;
			else hi = mid - 1;
		}
		String result = s.substring(0, lo).trim() + "...";
		trimCache.put(key, result);
		return result;
	}

	private void text(DrawContext ctx, String s, int x, int y, int color) {
		ctx.drawText(textRenderer, styled(s, false), x, y, color, false);
	}

	private void bold(DrawContext ctx, String s, int x, int y, int color) {
		ctx.drawText(textRenderer, styled(s, true), x, y, color, false);
	}

	/** Text with its right end at x. */
	private void textRight(DrawContext ctx, String s, int x, int y, int color) {
		text(ctx, s, x - textW(s), y, color);
	}

	/** Text centred between x1 and x2. */
	private void textCentered(DrawContext ctx, String s, int x1, int x2, int y, int color) {
		text(ctx, s, x1 + (x2 - x1 - textW(s)) / 2, y, color);
	}

	private void boldCentered(DrawContext ctx, String s, int x1, int x2, int y, int color) {
		bold(ctx, s, x1 + (x2 - x1 - boldW(s)) / 2, y, color);
	}

	/** True when the menu uses Minecraft's pixel font (the default). Pixel text is never shrunk: it turns blotchy. */
	private boolean pixelFont() {
		return menuFont().pixel();
	}

	/** Titles are a little bigger than body text with the custom fonts; Minecraft's pixel font stays at its own size. */
	private float headingScale() {
		return pixelFont() ? 1f : 1.2f;
	}

	private void heading(DrawContext ctx, String s, int x, int y, int color) {
		float k = headingScale();
		if (k == 1f) {
			bold(ctx, s, x, y, color);
			return;
		}
		Matrix3x2fStack m = ctx.getMatrices();
		m.pushMatrix();
		m.translate(x, y - 1);
		m.scale(k, k);
		ctx.drawText(textRenderer, styled(s, true), 0, 0, color, false);
		m.popMatrix();
	}

	private int headingW(String s) {
		return Math.round(boldW(s) * headingScale());
	}

	/** Hints and sub-labels: 75% with the custom font, full size with Minecraft's font. */
	private void small(DrawContext ctx, String s, int x, int y, int color) {
		if (pixelFont()) {
			ctx.drawText(textRenderer, styled(s, false), x, y, color, false);
			return;
		}
		Matrix3x2fStack m = ctx.getMatrices();
		m.pushMatrix();
		m.translate(x, y);
		m.scale(0.75f, 0.75f);
		ctx.drawText(textRenderer, styled(s, false), 0, 0, color, false);
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
	private int smallWrapped(DrawContext ctx, String s, int x, int y, int maxW, int color) {
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

	/** How tall smallWrapped would be, without drawing. */
	private int smallWrappedHeight(String s, int maxW) {
		int lines = 0;
		StringBuilder line = new StringBuilder();
		for (String word : s.split(" ")) {
			String next = line.length() == 0 ? word : line + " " + word;
			if (line.length() > 0 && smallW(next) > maxW) {
				lines++;
				line.setLength(0);
				line.append(word);
			} else {
				line.setLength(0);
				line.append(next);
			}
		}
		if (line.length() > 0) lines++;
		return lines * smallH();
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
			case BLOCKING -> "Block";
		};
	}

	// ------------------------------------------------------------------ packs / state

	private void startScan(String message) {
		setStatus(message, MUTED);
		loading = dev.itemstudio.pack.PackIndexCache.get();
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
		ids.addAll(config.partSize.keySet());
		for (String id : ids) {
			map.put(id, config.styles.get(id) + "|" + SNAPSHOT_GSON.toJson(config.adjust.get(id)) + "|" + config.partSize.get(id));
		}
		if (config.crosshair != null) map.put("crosshair:drawn", config.crosshair);
		return map;
	}

	private State captureState() {
		return new State(new java.util.TreeMap<>(config.styles), SNAPSHOT_GSON.toJson(config.adjust),
			new java.util.TreeMap<>(config.partSize), config.crosshair);
	}

	private void pushHistory() {
		State now = captureState();
		if (!history.isEmpty() && history.peekLast().equals(now)) return;
		history.addLast(now);
		while (history.size() > 60) history.removeFirst();
	}

	/** Goes back to the choices before the last change (Ctrl+Z). */
	private void undo() {
		if (history.size() < 2) {
			setStatus("Nothing to undo", MUTED);
			return;
		}
		history.removeLast();
		State back = history.peekLast();
		config.styles = new java.util.TreeMap<>(back.styles());
		java.util.Map<String, java.util.Map<String, Adjust>> adjust = SNAPSHOT_GSON.fromJson(back.adjust(), ADJUST_TYPE);
		config.adjust = adjust == null ? new java.util.TreeMap<>() : new java.util.TreeMap<>(adjust);
		config.partSize = new java.util.TreeMap<>(back.partSize());
		config.crosshair = back.crosshair();
		changed(false);
		setStatus("Undone" + (pending > 0 ? " - " + pending + " item" + (pending == 1 ? "" : "s") + " changed" : ""), pending > 0 ? WARN : MUTED);
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
		changeCount++;
		config.cleanUp();
		config.save();
		if (finished) pushHistory();
		pending = countPending();
		if (pending == 0) {
			setStatus("Nothing waiting - matches what's applied", MUTED);
		} else if (finished && config.applyInstantly) {
			apply();
		} else {
			setStatus(pending + " item" + (pending == 1 ? "" : "s") + " changed", WARN);
		}
	}

	/** An apply is building in the background / another one was asked for meanwhile. */
	private boolean applying, applyAgain;
	/** The menu was closed (removed); a finishing Apply then closes the pack files it kept open. */
	private boolean closed;
	/** Shown instead of "Applied" when the apply finishes (e.g. "Switched to Crystal"). */
	private String applyNote;
	private int applyNoteColor = GOOD;

	private record Built(PackBuilder builder, PackWriter.Result written) {
	}

	private java.util.concurrent.Executor gameThread() {
		return client;
	}

	/**
	 * Builds the pack in the background (from a copy of the choices, so the menu stays usable), writes only what
	 * changed, then turns it on and reloads on the game thread. Skips the reload when nothing changed.
	 */
	private void apply() {
		config.cleanUp();
		config.save();
		if (applying) {
			applyAgain = true;
			return;
		}
		// The pack scan may still be running: wait for it on the build thread, not the game thread.
		CompletableFuture<PackIndex> scan = index != null ? CompletableFuture.completedFuture(index)
			: loading != null ? loading : dev.itemstudio.pack.PackIndexCache.get();
		java.util.concurrent.atomic.AtomicReference<PackIndex> used = new java.util.concurrent.atomic.AtomicReference<>();
		StudioConfig choices = config.copyForBuild();
		Map<String, String> applying_ = snapshot();
		String note = applyNote;
		int noteColor = applyNoteColor;
		applyNote = null;
		applyNoteColor = GOOD;
		applying = true;
		setStatus("Applying...", MUTED);
		scan.thenApplyAsync(idx -> {
			used.set(idx);
			PackBuilder builder = new PackBuilder(choices, idx).build();
			return new Built(builder, PackWriter.writeChanges(builder.files()));
		}, dev.itemstudio.Workers.BUILD).whenCompleteAsync((built, error) -> {
			applying = false;
			if (closed && used.get() != null && !applyAgain) used.get().close();
			if (error != null || built.written() == PackWriter.Result.FAILED) {
				if (error != null) ItemStudioClient.LOG.error("Could not apply", error);
				setStatus("Could not write the pack - see the game log", WARN);
			} else {
				applied = applying_;
				pending = countPending();
				PackBuilder builder = built.builder();
				if (!config.enabled) {
					setStatus("Saved - Item Studio is off, turn it on in Settings to see it", WARN);
				} else if (builder.problems().isEmpty()) {
					if (note != null) setStatus(note, noteColor);
					else setStatus(built.written() == PackWriter.Result.UNCHANGED ? "Applied - nothing to reload" : "Applied", GOOD);
				} else {
					setStatus("Applied with " + builder.problems().size() + " problem(s): " + builder.problems().get(0), WARN);
				}
				PackWriter.enableAndReload(!builder.files().isEmpty(), built.written() == PackWriter.Result.CHANGED);
			}
			if (applyAgain) {
				applyAgain = false;
				apply();
			}
		}, gameThread());
	}

	private void rescan() {
		if (index == null) return;
		dev.itemstudio.pack.PackIndexCache.invalidate();
		previews.clear();
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

	private final Map<Item, Target> itemTargets = new HashMap<>();
	private final Map<TexturePart, Target> partTargets = new HashMap<>();
	private String tilesKey;
	private List<Target> tilesCache = List.of();

	private Target itemTarget(Item item) {
		return itemTargets.computeIfAbsent(item, i -> new Target(Registries.ITEM.getId(i).toString(), i, null));
	}

	private Target partTarget(TexturePart element) {
		return partTargets.computeIfAbsent(element, e -> new Target(e.key(), null, e));
	}

	private final Map<TexturePart, Item> partItems = new java.util.concurrent.ConcurrentHashMap<>();

	/** The item that shows a part in 3D (a block's item), or null. */
	private Item partItem(TexturePart part) {
		Item item = partItems.computeIfAbsent(part, p -> {
			Identifier id = p.itemId() == null ? null : Identifier.tryParse(p.itemId());
			if (id == null || !Registries.ITEM.containsId(id)) return Items.AIR;
			return Registries.ITEM.get(id);
		});
		return item == Items.AIR ? null : item;
	}

	private final Map<TexturePart, Item> iconItems = new java.util.concurrent.ConcurrentHashMap<>();

	/** The item drawn as a part's icon when it has no picture of its own (sounds), or null. */
	private Item iconItem(TexturePart part) {
		Item item = iconItems.computeIfAbsent(part, p -> {
			Identifier id = p.iconItem() == null ? null : Identifier.tryParse(p.iconItem());
			if (id == null || !Registries.ITEM.containsId(id)) return Items.AIR;
			return Registries.ITEM.get(id);
		});
		return item == Items.AIR ? null : item;
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
		String key = category + "|" + query;
		if (key.equals(tilesKey)) return tilesCache;
		List<Target> list = new ArrayList<>();
		if (partMode()) {
			for (TexturePart e : category.parts()) list.add(partTarget(e));
		} else {
			for (Item item : gridItems()) list.add(itemTarget(item));
		}
		tilesKey = key;
		tilesCache = list;
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
		return t.isPart() ? t.part.label() : stack(t.item).getName().getString();
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
			if (t.part instanceof dev.itemstudio.pack.SkyElement) {
				int layers = 0;
				for (String f : index.partFiles(p, t.part)) {
					if (dev.itemstudio.pack.SkyElement.isLayer(f)) layers++;
				}
				return Math.max(1, layers) + " sky layer" + (layers > 1 ? "s" : "");
			}
			if (t.part instanceof SoundElement) {
				int n = 0;
				for (String f : index.partFiles(p, t.part)) {
					if (f.endsWith(".ogg")) n++;
				}
				return n + " sound" + (n == 1 ? "" : "s");
			}
			int n = index.partFiles(p, t.part).size();
			String textures = n + " texture" + (n == 1 ? "" : "s");
			return index.partModelFiles(p, t.part).isEmpty() ? textures : textures + " + model";
		}
		PackIndex.Provided provided = index.provided(p, ItemInfo.of(t.item));
		boolean model = provided.itemDef() || !provided.models().isEmpty() || !provided.parentModels().isEmpty();
		boolean tex = !provided.textures().isEmpty();
		return model && tex ? "Texture + model" : model ? "Model" : "Texture";
	}

	/** Size of rendered 3D item pictures, in pixels (2x an inventory slot). */
	private static final int MODEL_PREVIEW_SIZE = 32;
	/** Skybox previews are shrunk to at most this many pixels wide or high. */
	private static final int SKY_PREVIEW = 192;

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
		if (isSound(t)) return null; // sounds have waveforms instead (firstSound)
		java.util.function.Function<String, byte[]> read = vanilla ? Resources::readVanilla : Resources::readBelow;
		String main = mainFile(t);
		byte[] flat = main == null ? null : read.apply(main);
		if (t.isPart() && t.part instanceof dev.itemstudio.pack.SkyElement) {
			return flat == null ? null : dev.itemstudio.pack.Png.shrink(flat, SKY_PREVIEW);
		}
		if (t.isPart()) {
			Item block = partItem(t.part);
			return block == null ? onBlock(t.part, flat) : itemPicture(ItemInfo.of(block), read, flat);
		}
		return itemPicture(ItemInfo.of(t.item), read, flat);
	}

	/** Bytes of the picture for a pack's version of a target. */
	private byte[] previewOf(PackSource p, Target t) {
		if (!t.isPart()) {
			ItemInfo info = ItemInfo.of(t.item);
			return itemPicture(info, path -> readWithBelow(p, path), previewBytes(p, info));
		}
		if (isSound(t)) return null; // sounds have waveforms instead (firstSound)
		byte[] flat;
		try {
			if (t.part instanceof dev.itemstudio.pack.SkyElement) {
				// skybox pictures can be thousands of pixels wide: shrink them here, off the game thread
				String file = dev.itemstudio.pack.SkyElement.firstPicture(index.partFiles(p, t.part));
				return file == null ? null : dev.itemstudio.pack.Png.shrink(p.read(file), SKY_PREVIEW);
			}
			if (p.has(t.part.preview())) flat = p.read(t.part.preview());
			else {
				List<String> files = index.partFiles(p, t.part);
				// cracks: the last stage has the most of them
				int pick = t.part == dev.itemstudio.pack.BlockElement.BREAKING ? files.size() - 1 : 0;
				flat = files.isEmpty() ? null : p.read(files.get(pick));
			}
		} catch (Exception e) {
			flat = null;
		}
		Item block = partItem(t.part);
		return block == null ? onBlock(t.part, flat) : itemPicture(ItemInfo.of(block), path -> readWithBelow(p, path), flat);
	}

	private static final String STONE = "assets/minecraft/textures/block/stone.png";

	/** Breaking cracks are dark and see-through: show them on stone, like on a block being mined. */
	private static byte[] onBlock(TexturePart part, byte[] flat) {
		if (part != dev.itemstudio.pack.BlockElement.BREAKING || flat == null) return flat;
		return dev.itemstudio.pack.Png.cracks(flat, Resources.readBelow(STONE));
	}

	// ------------------------------------------------------------------ sounds

	private static boolean isSound(Target t) {
		return t.isPart() && t.part instanceof SoundElement;
	}

	/**
	 * The sound files a choice has for a sound group, the group's own sound first. src null means what the game
	 * plays now: the game's own file names, read through the turned-on packs.
	 */
	private List<String> soundFiles(PackSource src, TexturePart part) {
		SoundElement sound = (SoundElement) part;
		if (src == null || src instanceof VanillaSource || index == null) return sound.vanillaFiles();
		List<String> files = new ArrayList<>();
		for (String f : index.partFiles(src, part)) {
			if (f.endsWith(".ogg")) files.add(f);
		}
		if (files.remove(part.preview())) files.add(0, part.preview());
		return files;
	}

	/** One sound file of a choice: from the pack, from vanilla, or what the game plays now (src null). */
	private static byte[] soundBytes(PackSource src, String file) {
		if (src == null) return Resources.readBelow(file);
		if (src instanceof VanillaSource) return Resources.readVanilla(file);
		try {
			return src.read(file);
		} catch (Exception e) {
			return null;
		}
	}

	/** The sound a choice's card shows and plays first. */
	private byte[] firstSound(PackSource src, TexturePart part) {
		List<String> files = soundFiles(src, part);
		return soundBytes(src, files.isEmpty() ? part.preview() : files.get(0));
	}

	/** What isPlaying and the waveforms call a choice of a sound group. */
	private static String soundKey(PackSource src, Target t) {
		return (src == null ? "below" : src.id()) + "|" + t.key;
	}

	/** Plays a choice's sound; playing it again plays its next file, so every hit sound of a pack can be heard. */
	private void playSound(PackSource src, Target t) {
		List<String> files = soundFiles(src, t.part);
		String key = soundKey(src, t);
		String file = files.isEmpty() ? t.part.preview() : files.get(SoundPreview.nextTurn(key, files.size()));
		SoundPreview.play(key, () -> soundBytes(src, file));
	}

	/** A sound's waveform as bars in a size x size box. False until it is worked out, and when it can't be read. */
	private boolean drawWave(DrawContext ctx, String key, Supplier<byte[]> ogg, int x, int y, int size, int color) {
		float[] bars = SoundPreview.wave(waveKey(key), ogg);
		if (bars == null) return false;
		int n = Math.max(1, Math.min(bars.length, size / 2));
		int slot = Math.max(1, size / n);
		int barW = slot > 2 ? slot - 1 : 1;
		int x0 = x + (size - n * slot) / 2;
		int mid = y + size / 2;
		for (int i = 0; i < n; i++) {
			float v = 0;
			int from = i * bars.length / n, to = Math.max(from + 1, (i + 1) * bars.length / n);
			for (int j = from; j < to; j++) v = Math.max(v, bars[j]);
			int h = Math.max(1, Math.round(v * size * 0.8f));
			int top = mid - h / 2;
			fill(ctx, x0 + i * slot, top, x0 + i * slot + barW, top + h, color);
		}
		return true;
	}

	private static String waveKey(String key) {
		return dev.itemstudio.pack.PackIndexCache.generation() + "|" + key;
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
		return styleOf(Registries.ITEM.getId(item).toString());
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
	private void drawTargetIcon(DrawContext ctx, Target t, int x, int y, int size) {
		if (t.isPart() && partItem(t.part) != null) {
			t = itemTarget(partItem(t.part));
		} else if (t.isPart() && iconItem(t.part) != null) {
			t = itemTarget(iconItem(t.part));
		}
		if (t.isPart()) {
			String[] icons = t.part.iconFiles();
			if (icons != null) {
				if (drawPreview(ctx, "icon|" + t.part.key(), () -> {
					for (String icon : icons) {
						byte[] b = Resources.readBelow(icon);
						if (b != null) return b;
					}
					return null;
				}, x, y, size)) return;
				text(ctx, t.part.label().substring(0, 1), x + size / 2 - 3, y + size / 2 - 4, TEXT2);
				return;
			}
			String file = t.part.preview();
			TexturePart part = t.part;
			if (drawPreview(ctx, "below|" + file, () -> onBlock(part, Resources.readBelow(file)), x, y, size)) return;
			text(ctx, t.part.label().substring(0, 1), x + size / 2 - 3, y + size / 2 - 4, TEXT2);
			return;
		}
		// whole-number sizes keep pixel art sharp: 16, 32, 48...
		int k = size / 16;
		if (k <= 1) {
			ctx.drawItem(stack(t.item), x + Math.max(0, (size - 16) / 2), y + Math.max(0, (size - 16) / 2));
			return;
		}
		int d = k * 16;
		Matrix3x2fStack m = ctx.getMatrices();
		m.pushMatrix();
		m.translate(x + (size - d) / 2, y + (size - d) / 2);
		m.scale(k, k);
		ctx.drawItem(stack(t.item), 0, 0);
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
			String id = Registries.ITEM.getId(item).getPath();
			String name = item.getName().getString().toLowerCase(Locale.ROOT);
			if (id.contains(query.replace(' ', '_')) || name.contains(query)) list.add(item);
		}
		searchCacheQuery = query;
		searchCache = list;
		return list;
	}

	/** The theme of this frame (looked up once in render). */
	private Theme frameTheme;

	private Theme theme() {
		return frameTheme != null ? frameTheme : Theme.byName(config.theme);
	}

	// ------------------------------------------------------------------ rendering

	private int palKey = Integer.MIN_VALUE;
	private int accentRgb() {
		return Theme.parseAccent(config.accent);
	}

	/** What changed since the last frame decides whether the category dots and tile dots are worked out again. */
	private int editedSig = Integer.MIN_VALUE;
	private int changeCount;
	private final java.util.Set<String> editedKeys = new java.util.HashSet<>();
	private static final Category[] CATS = Category.values();
	private final boolean[] categoryEdited = new boolean[CATS.length];

	private void refreshEdited() {
		int sig = System.identityHashCode(config.styles) * 31 + System.identityHashCode(config.adjust) * 17
			+ System.identityHashCode(config.partSize) * 7 + changeCount + (config.crosshair == null ? 0 : 1);
		if (sig == editedSig) return;
		editedSig = sig;
		editedKeys.clear();
		editedKeys.addAll(config.styles.keySet());
		editedKeys.addAll(config.partSize.keySet());
		for (Map.Entry<String, Map<String, Adjust>> e : config.adjust.entrySet()) {
			if (config.hasAdjust(e.getKey())) editedKeys.add(e.getKey());
		}
		if (config.crosshair != null) editedKeys.add(dev.itemstudio.pack.HudElement.CROSSHAIR.key());
		Category[] cats = CATS;
		for (int i = 0; i < cats.length; i++) {
			Category c = cats[i];
			boolean any = false;
			if (c == Category.ALL) {
				any = !editedKeys.isEmpty();
			} else if (!c.parts().isEmpty()) {
				for (TexturePart p : c.parts()) {
					if (editedKeys.contains(p.key())) {
						any = true;
						break;
					}
				}
			} else {
				for (Item item : c.items()) {
					if (editedKeys.contains(itemTarget(item).key)) {
						any = true;
						break;
					}
				}
			}
			categoryEdited[i] = any;
		}
	}

	private boolean isEdited(Target t) {
		return editedKeys.contains(t.key);
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		hits.clear();
		scrollAreas.clear();
		crossShown = false;
		previews.beginFrame();
		long now = System.nanoTime();
		dt = Math.max(0.0005f, Math.min(0.1f, (now - lastFrame) / 1e9f));
		lastFrame = now;
		clock += dt;
		anim.dt = dt;
		anim.enabled = config.animations;
		MenuFont font = MenuFont.byId(config.fontId());
		if (font != frameFont || widthCache.size() > 20000 || trimCache.size() > 20000 || plainText.size() > 6000
			|| boldText.size() > 6000) {
			widthCache.clear();
			trimCache.clear();
			plainText.clear();
			boldText.clear();
			frameFont = font;
			cardLinesFor = null;
			searchVariant = -1;
			packVariant = -1;
		}
		frameTheme = Theme.byName(config.theme);
		int key = frameTheme.ordinal() * 1000003 + Float.floatToIntBits(config.opacity) * 31 + (accentRgb() + 7) * 17;
		if (pal == null || key != palKey) {
			palKey = key;
			pal = frameTheme.palette(config.opacity, accentRgb());
		}
		layout();
		int generation = dev.itemstudio.pack.PackIndexCache.generation();
		if (generation != previewGeneration || previews.size() > 3000) {
			previews.clear();
			SoundPreview.clearWaves();
			previewGeneration = generation;
		}
		ready();
		refreshEdited();

		hoverBlocked = profilesOpen;
		drawWindow(ctx);
		drawHeader(ctx, mouseX, mouseY);
		drawRail(ctx, mouseX, mouseY);
		styleSearch.visible = false;
		drawCenter(ctx, mouseX, mouseY);
		if (settingsOpen) drawSettings(ctx, mouseX, mouseY);
		else drawSide(ctx, mouseX, mouseY);
		drawFooter(ctx, mouseX, mouseY);
		renameRowShown = false;
		if (profilesOpen) {
			// Text boxes are drawn after everything else, so one under the list would show through it.
			styleSearch.visible = false;
			hoverBlocked = false;
			drawProfiles(ctx, mouseX, mouseY);
		}
		profileName.visible = profilesOpen && renamingProfile && renameRowShown;

		super.render(ctx, mouseX, mouseY, delta); // the search boxes (and the profile name box)

		drawTooltip(ctx, mouseX, mouseY);
	}

	/** Only the top-most thing under the mouse may show a tooltip, so nothing covered (by the profile list) shows one. */
	private void drawTooltip(DrawContext ctx, int mouseX, int mouseY) {
		int shown = 0;
		String tip = null;
		if (config.tooltips && dragging == null && !painting) {
			for (int i = hits.size() - 1; i >= 0; i--) {
				Hit hit = hits.get(i);
				if (!hit.contains(mouseX, mouseY)) continue;
				tip = hit.tooltip;
				shown = Anim.key(hit.x1 * 4099 + hit.y1, hit.x2 * 4099 + hit.y2, tip == null ? 0 : tip.hashCode());
				break;
			}
		}
		if (tip == null) {
			tipKey = 0;
			return;
		}
		long nowMs = System.currentTimeMillis();
		if (shown != tipKey) {
			tipKey = shown;
			tipSince = nowMs;
		}
		// a short wait, so sweeping the mouse across the menu doesn't flash a tooltip on everything
		if (nowMs - tipSince >= (config.animations ? 350 : 150)) ctx.drawTooltip(Text.literal(tip), mouseX, mouseY);
	}

	/** The window itself: a soft shadow, the panel and its border. */
	private void drawWindow(DrawContext ctx) {
		int[] lg = shapeLg();
		if (px >= 6) {
			fill(ctx, px - 1, py + 2, px, py + ph - 2, 0x30000000);
			fill(ctx, px + pw, py + 2, px + pw + 1, py + ph - 2, 0x30000000);
			fill(ctx, px + 2, py - 1, px + pw - 2, py, 0x30000000);
			fill(ctx, px + 2, py + ph, px + pw - 2, py + ph + 1, 0x30000000);
			fill(ctx, px - 2, py + 4, px - 1, py + ph - 3, 0x18000000);
			fill(ctx, px + pw + 1, py + 4, px + pw + 2, py + ph - 3, 0x18000000);
			fill(ctx, px + 4, py - 2, px + pw - 3, py - 1, 0x18000000);
			fill(ctx, px + 4, py + ph + 1, px + pw - 3, py + ph + 2, 0x18000000);
		}
		box(ctx, px, py, px + pw, py + ph, pal.panel(), pal.panelBorder(), lg);
	}

	private int placeholderFor(int width) {
		return width >= 100 ? 0 : width >= 70 ? 1 : 2;
	}

	private int searchVariant = -1, packVariant = -1;
	private static final String[] ALL_PLACEHOLDERS = {"Search all items", "Search items", "Search"};
	private static final String[] PACK_PLACEHOLDERS = {"Search packs", "Search", "Find"};

	private void drawHeader(DrawContext ctx, int mouseX, int mouseY) {
		bandTop(ctx, px, py, px + pw, bodyY, pal.header(), shapeLg());
		fill(ctx, px + 1, bodyY - 1, px + pw - 1, bodyY, pal.line());
		int cy = py + (headerH - ctl) / 2;
		int textY = py + (headerH - 8) / 2;
		int x = px + 8;

		// logo
		int ls = compact ? 18 : 20;
		int ly = py + (headerH - ls) / 2;
		round(ctx, x, ly, x + ls, ly + ls, pal.strong(), shapeSm());
		fill(ctx, x + 2, ly + 1, x + ls - 2, ly + 2, 0x26FFFFFF);
		ctx.drawItem(stack(Items.DIAMOND_SWORD), x + (ls - 16) / 2, ly + (ls - 16) / 2);
		x += ls + 7;
		String title = "Item Studio";
		if (pw >= 380) {
			heading(ctx, title, x, textY, TEXT);
			x += headingW(title) + 10;
		}

		// the right end first, so the middle knows how much room it has: close, settings, search
		int rx = px + pw - 8;
		int closeX = rx - ctl;
		rx = closeX - 4;
		int gearX = rx - ctl;
		rx = gearX - 8;
		int sw = Math.max(compact ? 84 : 96, Math.min(176, pw / 4));
		int sx2 = rx, sx1 = rx - sw;

		// profile switcher
		String profile = trim(config.activeProfile, compact ? 64 : 84);
		int chipW = 8 + 5 + 5 + textW(profile) + 6 + 5 + 7;
		int chipX = x;
		boolean chipHover = hot(mouseX, mouseY, chipX, cy, chipX + chipW, cy + ctl);
		float ct = anim.fade(Anim.key(0x7001, 0), chipHover, 16f);
		box(ctx, chipX, cy, chipX + chipW, cy + ctl,
			profilesOpen ? pal.soft() : Anim.mix(pal.surface(), pal.surfaceHover(), ct),
			profilesOpen ? pal.accent() : Anim.mix(pal.surfaceBorder(), pal.surfaceBorderHover(), ct), shapeSm());
		icon(ctx, Icons.DOT, chipX + 8, cy + (ctl - 5) / 2, pal.accent());
		text(ctx, profile, chipX + 8 + 5 + 5, textY, TEXT);
		icon(ctx, Icons.CARET_DOWN, chipX + chipW - 7 - 5, cy + (ctl - 3) / 2, profilesOpen ? pal.accentText() : MUTED);
		profileChipX = chipX;
		hits.add(new Hit(chipX, cy, chipX + chipW, cy + ctl, () -> {
			if (renamingProfile) commitRename();
			profilesOpen = !profilesOpen;
			if (profilesOpen) styleSearch.setFocused(false);
		}, null, profilesOpen ? null : "Profiles: save and switch whole setups", null));

		// breadcrumb: Category > Item
		int crumbX = chipX + chipW + 8;
		int roomEnd = sx1 - 8;
		dev.itemstudio.UpdateCheck.Update update = dev.itemstudio.UpdateCheck.found();
		if (update != null) {
			String label = "Update " + update.version();
			int uw = 6 + 5 + 4 + textW(label) + 7;
			int ux = sx1 - 6 - uw;
			if (ux > crumbX + 8) {
				boolean uh = hot(mouseX, mouseY, ux, cy, ux + uw, cy + ctl);
				float ut = anim.fade(Anim.key(0x7002, 0), uh, 16f);
				round(ctx, ux, cy, ux + uw, cy + ctl, Anim.mix(pal.strong(), pal.strongHover(), ut), shapeSm());
				icon(ctx, Icons.DOT, ux + 6, cy + (ctl - 5) / 2, 0xFFFFFFFF);
				text(ctx, label, ux + 6 + 5 + 4, textY, WHITE);
				hits.add(new Hit(ux, cy, ux + uw, cy + ctl, () -> {
					java.net.URI releasePage = java.net.URI.create(update.url());
					Util.getOperatingSystem().open(releasePage);
				}, null, "Item Studio " + update.version() + " is out (you have " + dev.itemstudio.UpdateCheck.currentVersion()
					+ "). Click to open the download page.", null));
				roomEnd = ux - 8;
			}
		}
		if (roomEnd - crumbX > 56) {
			fill(ctx, crumbX, cy + 3, crumbX + 1, cy + ctl - 3, pal.line2());
			crumbX += 8;
			String where = !query.isEmpty() ? "Search" : category.label;
			Target shownTarget = current();
			String item = shownTarget == null ? "" : nameOf(shownTarget);
			int room = roomEnd - crumbX;
			if (room >= 130 || item.isEmpty()) {
				// Category > Item
				String whereShown = trim(where, Math.max(24, room / 2 - 6));
				text(ctx, whereShown, crumbX, textY, MUTED);
				int cx = crumbX + textW(whereShown) + 5;
				if (!item.isEmpty() && roomEnd - cx > 22) {
					icon(ctx, Icons.CHEVRON_RIGHT, cx, cy + (ctl - 7) / 2, MUTED);
					cx += Icons.CHEVRON_RIGHT.w + 5;
					text(ctx, trim(item, roomEnd - cx), cx, textY, TEXT2);
				}
			} else {
				// not much room: just what is picked
				text(ctx, trim(item, room), crumbX, textY, TEXT2);
			}
		}

		// search box
		boolean focused = search.isFocused();
		boolean sh = hot(mouseX, mouseY, sx1, cy, sx2, cy + ctl);
		inputFrame(ctx, sx1, cy, sx2, cy + ctl, focused, sh, 0x7010);
		icon(ctx, Icons.SEARCH, sx1 + 6, cy + (ctl - 8) / 2, focused ? pal.accentText() : MUTED);
		hits.add(new Hit(sx1, cy, sx2, cy + ctl, () -> focusField(search), null, null, null));
		int clearW = query.isEmpty() ? 0 : 14;
		int variant = placeholderFor(sw - 19 - 6 - clearW);
		if (variant != searchVariant) {
			search.setPlaceholder(styledIn(ALL_PLACEHOLDERS[variant], menuFont(), false).withColor(MUTED));
			searchVariant = variant;
		}
		search.setX(sx1 + 19);
		search.setY(cy + (ctl - 8) / 2);
		search.setWidth(sw - 19 - 6 - clearW);
		if (query.isEmpty() && !focused && sx1 + 19 + textW(ALL_PLACEHOLDERS[variant]) + 10 < sx2 - 6 - smallW("Ctrl F")) {
			small(ctx, "Ctrl F", sx2 - smallW("Ctrl F") - 6, cy + (ctl - smallH()) / 2 + (pixelFont() ? 0 : 1), 0x55FFFFFF);
		}
		if (!query.isEmpty()) {
			int clx = sx2 - 6 - Icons.CLOSE.w;
			boolean clh = inside(mouseX, mouseY, clx - 3, cy, sx2, cy + ctl);
			icon(ctx, Icons.CLOSE, clx, cy + (ctl - 7) / 2, clh ? WHITE : MUTED);
			hits.add(new Hit(clx - 3, cy, sx2, cy + ctl, () -> search.setText(""), null, "Clear the search", null));
		}

		// settings and close
		iconButton(ctx, gearX, cy, ctl, Icons.GEAR, settingsOpen, mouseX, mouseY, () -> settingsOpen = !settingsOpen,
			settingsOpen ? "Close settings" : "Settings: look, layout and keys");
		iconButton(ctx, closeX, cy, ctl, Icons.CLOSE, false, mouseX, mouseY, this::close, "Close (changes are applied)");
	}

	private static String[] countText;
	private final int[] railYs = new int[CATS.length];

	private void drawRail(DrawContext ctx, int mouseX, int mouseY) {
		int x1 = px + 1, y1 = bodyY, x2 = px + railW, y2 = footerY;
		fill(ctx, x1, y1, x2, y2, pal.rail());
		fill(ctx, x2, y1, x2 + 1, y2, pal.line());
		Category[] cats = CATS;
		if (countText == null) {
			countText = new String[cats.length];
			for (int i = 0; i < cats.length; i++) {
				Category c = cats[i];
				countText[i] = c == Category.ALL ? "" : String.valueOf(c.parts().isEmpty() ? c.items().size() : c.parts().size());
			}
		}
		int top = y1 + 6;
		int step = railRow + 2;
		int sectionGap = 8;
		// where each row goes, with a caption (wide rail) or a line (icon rail) between the groups
		int cursor = 0;
		for (int i = 0; i < cats.length; i++) {
			Category c = cats[i];
			if (c == Category.SWORDS && railFull) cursor += smallH() + 4;
			if (c == Category.BLOCKS || c == Category.ALL) cursor += railFull && c == Category.BLOCKS ? sectionGap + smallH() + 2 : sectionGap;
			railYs[i] = cursor;
			cursor += step;
		}
		int content = cursor;
		int view = y2 - top - 4;
		if (revealRail) {
			// first frame: centre the restored category if the list has to scroll
			jumpScroll("rail", Math.max(0, railYs[category.ordinal()] - view / 2 + railRow / 2));
			revealRail = false;
		}
		int off = scrollOf("rail", content, view);
		scrollAreas.add(new ScrollArea("rail", x1, top, x2, y2, Math.max(0, content - view)));

		ctx.enableScissor(x1, top, x2, y2);
		int bx1 = x1 + 5, bx2 = x2 - 5;
		if (query.isEmpty()) {
			// the highlight slides to the chosen category instead of jumping
			float hc = anim.to(Anim.key(0x7100, 0), railYs[category.ordinal()], 16f);
			int hy = top - off + Math.round(hc);
			round(ctx, bx1, hy, bx2, hy + railRow, pal.soft(), shapeSm());
			round(ctx, bx1 + 2, hy + 4, bx1 + 4, hy + railRow - 4, pal.accent(), CORNERS_1);
		}
		for (int i = 0; i < cats.length; i++) {
			Category cat = cats[i];
			int y = top - off + railYs[i];
			if (railFull && (cat == Category.SWORDS || cat == Category.BLOCKS)) {
				small(ctx, cat == Category.SWORDS ? "ITEMS" : "TEXTURES", x1 + 10, y - smallH() - 3, MUTED);
			}
			if ((cat == Category.BLOCKS && !railFull) || cat == Category.ALL) {
				fill(ctx, bx1 + 2, y - sectionGap / 2 - 1, bx2 - 2, y - sectionGap / 2, pal.line());
			}
			if (y + railRow < top || y > y2) continue;
			boolean active = cat == category && query.isEmpty();
			boolean hover = hot(mouseX, mouseY, x1, Math.max(y, top), x2, Math.min(y + railRow, y2));
			float t = anim.fade(Anim.key(0x7101, i), hover && !active, 16f);
			if (t > 0.01f) round(ctx, bx1, y, bx2, y + railRow, Anim.mix(0, pal.surface(), t), shapeSm());
			int iconX = railFull ? bx1 + 5 : x1 + (railW - 1 - 16) / 2;
			boolean partIcon = !cat.parts().isEmpty() && cat != Category.SOUNDS;
			drawTargetIcon(ctx, partIcon ? partTarget(cat.parts().get(0)) : itemTarget(cat.icon), iconX, y + (railRow - 16) / 2, 16);
			boolean edited = categoryEdited[i] && cat != Category.ALL;
			boolean cut = false;
			if (railFull) {
				String count = countText[i];
				int countW = smallW(count);
				int right = bx2 - 5;
				if (edited) {
					icon(ctx, Icons.DOT, right - countW - (countW > 0 ? 3 : 0) - 5, y + (railRow - 5) / 2, pal.accent());
				}
				int labelX = iconX + 16 + 5;
				int labelMax = bx2 - 5 - countW - (edited ? 12 : 4) - labelX;
				String shownLabel = trim(cat.label, labelMax);
				cut = !shownLabel.equals(cat.label);
				text(ctx, shownLabel, labelX, y + (railRow - 8) / 2, active ? WHITE : Anim.mix(TEXT2, WHITE, t));
				if (countW > 0) small(ctx, count, right - countW, y + (railRow - smallH()) / 2 + (pixelFont() ? 0 : 1), active ? pal.accentText() : MUTED);
			} else if (edited) {
				icon(ctx, Icons.DOT, bx2 - 4, y + 2, pal.accent());
			}
			final Category target = cat;
			addHit(bx1, y, bx2, y + railRow, top, y2, () -> {
				category = target;
				query = "";
				search.setText("");
				bulkMode = false;
				resetScroll("items");
				resetScroll("cards");
				if (!target.items().isEmpty()) selected = target.items().get(0);
			}, !railFull || cut ? cat.label : null);
		}
		ctx.disableScissor();
		scrollBar(ctx, x2, top, y2, off, Math.max(0, content - view), false);
	}

	/** How far one wheel notch moves the item strip: exactly one row. */
	private int itemsStep = 28;
	private static final String[] FILTER_NAMES = {"All", "Favorites", "Recent"};
	private static final Icons[] FILTER_ICONS = {null, Icons.STAR, Icons.CLOCK};
	private static final String[] FILTER_TIPS = {"Every pack", "Only the packs you starred (right-click a card to star it)", "The packs you used last"};

	private void drawCenter(DrawContext ctx, int mouseX, int mouseY) {
		List<Target> items = tiles();
		Target cur = current();
		boolean searching = !query.isEmpty();
		int x = centerX, w = centerW;
		int y = bodyY + (compact ? 6 : 10);

		// title row: the category, how many things are in it, and the "one pack for all" switch
		String title = searching ? "Search" : category.label;
		int rowH = ctl;
		heading(ctx, title, x, y + (rowH - 8) / 2, TEXT);
		String count = items.size() + (partMode() ? " part" : " item") + (items.size() == 1 ? "" : "s");
		int titleW = headingW(title);
		int bw = 0;
		String bulkLabel = null;
		if (!searching && category != Category.ALL) {
			bulkLabel = bulkMode ? "Back to " + (cur == null ? "item" : trim(nameOf(cur), 56)) : "One pack for all";
			bw = buttonW(bulkLabel, null) + 4;
			// narrow middle: a shorter name for the same button
			if (titleW + 12 + bw > w) {
				bulkLabel = bulkMode ? "Back" : "For all";
				bw = buttonW(bulkLabel, null) + 4;
			}
		}
		if (titleW + 8 + textW(count) + 8 + 12 + bw < w) {
			badge(ctx, count, x + titleW + 8, y + (rowH - 12) / 2, 12, pal.surface(), MUTED);
		}
		if (bulkLabel != null) {
			button(ctx, x + w - bw, y, bw, rowH, bulkLabel, null, bulkMode ? BTN_SOFT : BTN_NORMAL, mouseX, mouseY, () -> {
				bulkMode = !bulkMode;
				resetScroll("cards");
			}, bulkMode ? null : "Use one pack for every " + category.label.toLowerCase(Locale.ROOT) + " item it has");
		}
		y += rowH + (compact ? 5 : 7);

		// item tiles: up to 2 rows, scrolls when there are more
		int tgap = 4;
		int cols = Math.max(1, (w + tgap) / (tile + tgap));
		int rows = Math.max(1, (items.size() + cols - 1) / cols);
		int maxRows = footerY - bodyY >= 180 ? 2 : 1;
		int shownRows = Math.min(rows, maxRows);
		int areaH = shownRows * (tile + tgap) - tgap;
		int content = rows * (tile + tgap) - tgap;
		if (revealTile) {
			// first frame: if the restored item is below the visible rows, scroll down to it
			if (cur != null && !searching) {
				for (int i = 0; i < items.size(); i++) {
					if (items.get(i).key.equals(cur.key)) {
						int row = i / cols;
						if (row >= shownRows) jumpScroll("items", (row - shownRows + 1) * (tile + tgap));
						break;
					}
				}
			}
			revealTile = false;
		}
		itemsStep = tile + tgap;
		int off = scrollOf("items", content, areaH);
		scrollAreas.add(new ScrollArea("items", x, y, x + w, y + areaH, Math.max(0, content - areaH)));
		if (items.isEmpty()) {
			text(ctx, "Nothing matches", x, y + 6, MUTED);
		} else {
			ctx.enableScissor(x, y, x + w, y + areaH);
			int[] sm = shapeSm();
			for (int i = 0; i < items.size(); i++) {
				int tx = x + (i % cols) * (tile + tgap);
				int ty = y + (i / cols) * (tile + tgap) - off;
				if (ty + tile < y || ty > y + areaH) continue;
				Target t = items.get(i);
				boolean isSelected = cur != null && t.key.equals(cur.key);
				boolean hover = hot(mouseX, mouseY, tx, Math.max(ty, y), tx + tile, Math.min(ty + tile, y + areaH));
				float ht = anim.fade(Anim.key(0x7200, i), hover && !isSelected, 16f);
				float st = anim.fade(Anim.key(0x7201, i), isSelected, 16f);
				if (ht < 0.02f && st < 0.02f) {
					round(ctx, tx, ty, tx + tile, ty + tile, pal.surface(), sm);
				} else {
					int fillColor = Anim.mix(Anim.mix(pal.surface(), pal.surfaceHover(), ht), pal.soft(), st);
					int borderColor = Anim.mix(Anim.mix(pal.surfaceBorder(), pal.surfaceBorderHover(), ht), pal.accent(), st);
					box(ctx, tx, ty, tx + tile, ty + tile, fillColor, borderColor, sm);
				}
				drawTargetIcon(ctx, t, tx + (tile - 16) / 2, ty + (tile - 16) / 2, 16);
				if (isEdited(t)) round(ctx, tx + tile - 6, ty + 2, tx + tile - 2, ty + 6, pal.accent(), CORNERS_1);
				addHit(tx, ty, tx + tile, ty + tile, y, y + areaH, () -> {
					if (t.isPart()) selectedPart.put(category, t.part);
					else selected = t.item;
					bulkMode = false;
					settingsOpen = false;
					resetScroll("cards");
					resetScroll("side");
				}, nameOf(t));
			}
			ctx.disableScissor();
			scrollBar(ctx, x + w + 4, y, y + areaH, off, Math.max(0, content - areaH), false);
		}
		y += areaH + (compact ? 6 : 8);

		// toolbar: pack search, filter, sort
		int h = ctl;
		String[] filterNames = FILTER_NAMES;
		Icons[] filterIcons = FILTER_ICONS;
		boolean zToA = config.sortZtoA;
		String sortFull = zToA ? "Name Z-A" : "Name A-Z";
		String sortShort = zToA ? "Z-A" : "A-Z";
		int fullSeg = 4, tightSeg = 4;
		int[] fullW = new int[3], tightW = new int[3];
		for (int i = 0; i < 3; i++) {
			fullW[i] = textW(filterNames[i]) + (filterIcons[i] == null ? 0 : filterIcons[i].w + 3) + 12;
			tightW[i] = i == 0 ? textW(filterNames[0]) + 12 : filterIcons[i].w + 12;
			fullSeg += fullW[i];
			tightSeg += tightW[i];
		}
		int sortFullW = buttonW(sortFull, Icons.SORT_UP);
		int sortTightW = buttonW(sortShort, Icons.SORT_UP);
		int smallGap = 6;
		boolean tight = w - fullSeg - sortFullW - 2 * smallGap < 96;
		boolean tighter = tight && w - tightSeg - sortTightW - 2 * smallGap < 60;
		int segW = tight ? tightSeg : fullSeg;
		int sortW = tight ? sortTightW : sortFullW;
		int searchW = Math.max(40, w - segW - sortW - 2 * smallGap);
		if (tighter) {
			// very narrow: the search box gets the room, the filter and sort shrink to the bare minimum
			segW = tightSeg;
			sortW = Math.max(h, Icons.SORT_UP.w + 10);
			searchW = Math.max(40, w - segW - sortW - 2 * smallGap);
		}
		int sx1 = x, sx2 = x + searchW;
		boolean sfocus = styleSearch.isFocused();
		inputFrame(ctx, sx1, y, sx2, y + h, sfocus, hot(mouseX, mouseY, sx1, y, sx2, y + h), 0x7020);
		icon(ctx, Icons.SEARCH, sx1 + 6, y + (h - 8) / 2, sfocus ? pal.accentText() : MUTED);
		hits.add(new Hit(sx1, y, sx2, y + h, () -> focusField(styleSearch), null, null, null));
		int clearW = styleQuery.isEmpty() ? 0 : 14;
		int fieldW = searchW - 19 - 6 - clearW;
		int variant = placeholderFor(fieldW);
		if (variant != packVariant) {
			styleSearch.setPlaceholder(styledIn(PACK_PLACEHOLDERS[variant], menuFont(), false).withColor(MUTED));
			packVariant = variant;
		}
		styleSearch.visible = true;
		styleSearch.setX(sx1 + 19);
		styleSearch.setY(y + (h - 8) / 2);
		styleSearch.setWidth(fieldW);
		if (!styleQuery.isEmpty()) {
			int clx = sx2 - 6 - Icons.CLOSE.w;
			boolean clh = inside(mouseX, mouseY, clx - 3, y, sx2, y + h);
			icon(ctx, Icons.CLOSE, clx, y + (h - 7) / 2, clh ? WHITE : MUTED);
			hits.add(new Hit(clx - 3, y, sx2, y + h, () -> styleSearch.setText(""), null, "Clear the search", null));
		}

		int fx = sx2 + smallGap;
		String[] labels = new String[3];
		Icons[] icons = new Icons[3];
		int[] widths = tight ? tightW : fullW;
		for (int i = 0; i < 3; i++) {
			labels[i] = tight && i > 0 ? null : filterNames[i];
			icons[i] = filterIcons[i];
		}
		String[] tips = FILTER_TIPS;
		segmented(ctx, 0x7030, fx, y, segW, h, labels, icons, widths, filter.ordinal(), mouseX, mouseY, i -> {
			filter = Filter.values()[i];
			resetScroll("cards");
		}, tips);
		button(ctx, x + w - sortW, y, sortW, h, tighter ? null : tight ? sortShort : sortFull, zToA ? Icons.SORT_DOWN : Icons.SORT_UP,
			BTN_NORMAL, mouseX, mouseY, () -> {
				config.sortZtoA = !config.sortZtoA;
				config.save();
			}, "Change the order of the packs (" + sortFull + ")");
		y += h + (compact ? 6 : 8);

		if (!ready()) {
			text(ctx, "Loading packs...", x, y + 4, MUTED);
			drawLoading(ctx, x + textW("Loading packs...") + 12, y + 7);
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

	/** Background comparisons already started, by scan generation and target. */
	private static final Map<String, CompletableFuture<Void>> WARM = new java.util.concurrent.ConcurrentHashMap<>();
	private String cardsKey;
	private List<Card> cardsCache = List.of();

	/**
	 * Comparing packs means reading and hashing their files, which can take a moment when many packs change the
	 * same item. That runs in the background; this says whether it's finished.
	 */
	private boolean warmed(String key, List<Target> targets) {
		String k = dev.itemstudio.pack.PackIndexCache.generation() + "|" + key;
		CompletableFuture<Void> job = WARM.get(k);
		if (job == null) {
			PackIndex idx = index;
			List<FilePackSource> all = idx.packs();
			job = CompletableFuture.runAsync(() -> {
				for (Target t : targets) {
					for (FilePackSource p : all) {
						if (t.isPart()) {
							if (idx.partFiles(p, t.part).isEmpty()) continue;
							idx.partFingerprint(p, t.part);
							idx.partSameAsBelow(p, t.part);
						} else {
							ItemInfo info = ItemInfo.of(t.item);
							if (!idx.provided(p, info).any()) continue;
							idx.fingerprint(p, info);
							idx.sameAsBelow(p, info);
						}
					}
				}
			}, dev.itemstudio.Workers.BACKGROUND);
			WARM.put(k, job);
		}
		return job.isDone();
	}

	private void drawCards(DrawContext ctx, Target t, int x, int top, int w, int bottom, int mouseX, int mouseY) {
		String current = styleOf(t.key);
		List<FilePackSource> packs = packsFor(t);
		if (packs.size() > 4 && !warmed(t.key, List.of(t))) {
			String message = "Comparing " + packs.size() + " packs...";
			text(ctx, message, x, top + 4, MUTED);
			drawLoading(ctx, x + textW(message) + 12, top + 7);
			return;
		}
		String cacheKey = t.key + "|" + current + "|" + styleQuery + "|" + filter + "|" + config.sortZtoA + "|"
			+ config.favorites.hashCode() + "|" + config.recent.hashCode() + "|" + dev.itemstudio.pack.PackIndexCache.generation();
		List<Card> cards;
		if (cacheKey.equals(cardsKey)) {
			cards = cardsCache;
		} else {
			cards = buildCards(t, current, packs);
			cardsKey = cacheKey;
			cardsCache = cards;
		}
		drawCardGrid(ctx, t, cards, packs, current, x, top, w, bottom, mouseX, mouseY);
	}

	private static final int[] MIN_CARD_COMPACT = {44, 54, 76};
	private static final int[] MIN_CARD_COMFY = {64, 78, 104};
	/** Card names cut to the card width, made once per list and width instead of every frame. */
	private List<Card> cardLinesFor;
	private int cardLinesW, cardLinesN;
	private String[][] cardLines;
	private String[] cardSubs;

	private void drawCardGrid(DrawContext ctx, Target t, List<Card> cards, List<FilePackSource> packs, String current,
	                          int x, int top, int w, int bottom, int mouseX, int mouseY) {
		boolean soundCards = isSound(t);
		int cgap = gap;
		int minCard = (compact ? MIN_CARD_COMPACT : MIN_CARD_COMFY)[Math.max(0, Math.min(2, config.cardSize))];
		int cols = Math.max(2, (w + cgap) / (minCard + cgap));
		int cardW = (w - cgap * (cols - 1)) / cols;
		int cp = compact ? 4 : 5;
		int prev = cardW - cp * 2;
		// on small screens the picture box is a little shorter than wide, so more cards fit
		int wellH = compact ? Math.max(24, prev * 3 / 4) : prev;
		int nameLines = cardW >= 48 ? 2 : 1;
		int cardH = cp + wellH + 4 + nameLines * 10 + smallH() + cp;
		int rows = (cards.size() + cols - 1) / cols;
		boolean worn = !t.isPart() && ItemInfo.of(t.item).hasWornLook();
		int hintH = cards.isEmpty() || packs.isEmpty() ? 28 : worn ? 14 : 0;
		int content = rows * (cardH + cgap) + hintH;
		int off = scrollOf("cards", content, bottom - top);
		scrollAreas.add(new ScrollArea("cards", x, top, x + w, bottom, Math.max(0, content - (bottom - top))));

		if (cards != cardLinesFor || cardW != cardLinesW || nameLines != cardLinesN) {
			cardLinesFor = cards;
			cardLinesW = cardW;
			cardLinesN = nameLines;
			cardLines = new String[cards.size()][];
			cardSubs = new String[cards.size()];
			for (int i = 0; i < cards.size(); i++) {
				Card c = cards.get(i);
				cardLines[i] = nameLines == 2 ? twoLines(c.name, cardW - cp * 2) : new String[]{trim(c.name, cardW - cp * 2), ""};
				cardSubs[i] = smallTrim(c.sub, cardW - cp * 2);
			}
		}

		int[] lg = shapeSm(); // cards use the small corner shape: cheaper to draw, and they are many
		int[] sm = shapeSm();
		ctx.enableScissor(x - 2, top, x + w + 2, bottom);
		for (int i = 0; i < cards.size(); i++) {
			Card card = cards.get(i);
			int cx = x + (i % cols) * (cardW + cgap);
			int cy = top + (i / cols) * (cardH + cgap) - off;
			if (cy + cardH + 2 < top || cy > bottom) continue;
			boolean on = current == null ? card.sourceId == null : card.ids.contains(current);
			int hy1 = Math.max(cy, top), hy2 = Math.min(cy + cardH, bottom);
			boolean hover = hot(mouseX, mouseY, cx, hy1, cx + cardW, hy2);
			boolean down = pressed(cx, hy1, cx + cardW, hy2);
			float ht = anim.fade(Anim.key(0x7300, i), hover && !on, 16f);
			float st = anim.fade(Anim.key(0x7301, i), on, 14f);
			int lift = ht > 0.5f && !down ? 1 : 0;
			int y0 = cy - lift;
			if (lift == 1) dropShadow(ctx, cx, y0 + cardH, cx + cardW, y0 + cardH, 0x50);
			int fillColor = down ? pal.surfacePressed() : Anim.mix(Anim.mix(pal.surface(), pal.surfaceHover(), ht), pal.cardOn(), st);
			int borderColor = Anim.mix(Anim.mix(pal.surfaceBorder(), pal.surfaceBorderHover(), ht), pal.accent(), st);
			box(ctx, cx, y0, cx + cardW, y0 + cardH, fillColor, borderColor, lg);
			if (st > 0.5f) box(ctx, cx - 1, y0 - 1, cx + cardW + 1, y0 + cardH + 1, 0, pal.glow(), lg);
			round(ctx, cx + cp, y0 + cp, cx + cp + prev, y0 + cp + wellH, pal.well(), sm);
			int img = pictureSize(Math.min(prev, wellH) - 8);
			int imgX = cx + cp + (prev - img) / 2, imgY = y0 + cp + (wellH - img) / 2;
			PackSource cardSource = soundCards && card.sourceId != null && index != null ? index.get(card.sourceId) : null;
			boolean playing = soundCards && SoundPreview.isPlaying(soundKey(cardSource, t));
			boolean pictured = soundCards
				? drawWave(ctx, card.imageKey, card.image, imgX, imgY, img, playing ? WHITE : pal.accent())
				: drawPreview(ctx, card.imageKey, card.image, imgX, imgY, img);
			if (!pictured) {
				boolean loading = soundCards ? SoundPreview.waveLoading(waveKey(card.imageKey)) : previews.isLoading(card.imageKey);
				if (loading) drawLoading(ctx, cx + cp + prev / 2, y0 + cp + wellH / 2);
				else drawTargetIcon(ctx, t, imgX, imgY, img);
			}
			// name on up to two lines, then the kind of change
			String[] lines = cardLines[i];
			int ny = y0 + cp + wellH + 4;
			text(ctx, lines[0], cx + cp, ny, on ? WHITE : TEXT);
			if (!lines[1].isEmpty()) text(ctx, lines[1], cx + cp, ny + 10, on ? WHITE : TEXT);
			small(ctx, cardSubs[i], cx + cp, ny + (lines[1].isEmpty() ? 10 : 20), on ? pal.accentText() : MUTED);

			boolean fav = card.sourceId != null && !VanillaSource.ID.equals(card.sourceId) && isFavorite(card.ids);
			if (on) {
				round(ctx, cx + cardW - cp - 14, y0 + cp + 3, cx + cardW - cp - 3, y0 + cp + 14, pal.strong(), shapePill(11));
				icon(ctx, Icons.CHECK, cx + cardW - cp - 14 + 2, y0 + cp + 3 + 3, WHITE);
			}
			final List<String> ids = card.ids;
			final String sourceId = card.sourceId;
			final boolean alreadyOn = on;
			Runnable favToggle = () -> toggleFavorite(ids);
			addHit(cx, cy, cx + cardW, cy + cardH, top, bottom, () -> {
				if (soundCards) playSound(cardSource, t);
				if (alreadyOn) return;
				setStyle(t.key, sourceId);
				changed(true);
			}, favToggle, card.tooltip);
			if (card.sourceId != null && !VanillaSource.ID.equals(card.sourceId) && (fav || hover)) {
				int sx = cx + cp + 3, sy = y0 + cp + 3;
				boolean starHover = inside(mouseX, mouseY, sx - 2, sy - 2, sx + 11, sy + 11);
				icon(ctx, Icons.STAR, sx, sy, fav ? GOLD : starHover ? WHITE : 0xB0FFFFFF);
				addHit(sx - 2, sy - 2, sx + 11, sy + 11, top, bottom, favToggle, favToggle,
					fav ? "Remove from favorites" : "Add to favorites");
			}
			if (soundCards && (hover || playing)) {
				// listen without picking it
				int pb = 13;
				int px = cx + cp + prev - pb - 3, py = y0 + cp + wellH - pb - 3;
				boolean playHover = inside(mouseX, mouseY, px, py, px + pb, py + pb);
				round(ctx, px, py, px + pb, py + pb, playHover ? pal.strongHover() : pal.strong(), shapePill(pb));
				icon(ctx, Icons.PLAY, px + (pb - Icons.PLAY.w) / 2 + 1, py + (pb - Icons.PLAY.h) / 2, WHITE);
				addHit(px, py, px + pb, py + pb, top, bottom, () -> playSound(cardSource, t), "Play without picking it");
			}
		}
		int hy = top + rows * (cardH + cgap) - off + 2;
		if (cards.isEmpty()) {
			text(ctx, styleQuery.isEmpty() ? "No packs here yet." : "No packs match \"" + styleQuery + "\"", x, hy, MUTED);
			if (filter == Filter.FAVORITES) small(ctx, "Right-click a style card to add it to favorites.", x, hy + 12, MUTED);
		} else if (packs.isEmpty()) {
			text(ctx, "None of your packs change this.", x, hy, MUTED);
			small(ctx, "Add packs to your resourcepacks folder and press Rescan.", x, hy + 12, MUTED);
		} else if (worn) {
			small(ctx, "Packs here also change how it looks when worn or used.", x, hy, MUTED);
		}
		ctx.disableScissor();
		scrollBar(ctx, x + w + 6, top, bottom, off, Math.max(0, content - (bottom - top)), false);
	}

	private List<Card> buildCards(Target t, String current, List<FilePackSource> packs) {
		// Packs whose files are exactly what the game already shows fold into "Default";
		// packs with identical files for this item become one card.
		List<FilePackSource> likeDefault = new ArrayList<>();
		Map<String, List<FilePackSource>> groups = new LinkedHashMap<>();
		for (FilePackSource pack : packs) {
			if (!pack.id().equals(current) && sameAsBelow(pack, t)) likeDefault.add(pack);
			else groups.computeIfAbsent(fingerprint(pack, t), k -> new ArrayList<>()).add(pack);
		}

		List<Card> cards = new ArrayList<>();
		boolean sound = isSound(t);
		String same = sound ? "Same sound" : "Same look";
		boolean showFixed = styleQuery.isEmpty() && filter == Filter.ALL;
		if (showFixed) {
			cards.add(new Card(null, List.of(), "Default", sound ? "What the game plays now" : "What the game shows now",
				"Don't change this" + (likeDefault.isEmpty() ? "" : ". " + same + " as: " + names(likeDefault)),
				sound ? () -> firstSound(null, t.part) : () -> basePreview(t, false), "below|" + t.key));
			if (!belowIsVanilla(t) || VanillaSource.ID.equals(current)) {
				cards.add(new Card(VanillaSource.ID, List.of(VanillaSource.ID), "Vanilla",
					sound ? "Normal Minecraft sound" : "Normal Minecraft look",
					sound ? "The normal Minecraft sound, even if another pack changes it"
						: "The normal Minecraft look, even if another pack changes it",
					sound ? () -> firstSound(index.vanilla(), t.part) : () -> basePreview(t, true), "vanilla|" + t.key));
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
			String sub = group.size() > 1 ? same + " in " + group.size() + " packs" : kindOf(shown, t);
			String tip = group.size() == 1 ? shown.displayName() : same + " in: " + names(group);
			cards.add(new Card(shown.id(), ids, shown.displayName(), sub, tip + " - right-click to favorite",
				sound ? () -> firstSound(preview, t.part) : () -> previewOf(preview, t), shown.id() + "|" + t.key));
		}
		return cards;
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
	private String bulkKey;
	private List<List<FilePackSource>> bulkRows = List.of();
	private Map<String, int[]> bulkCounts = Map.of();

	private void drawBulk(DrawContext ctx, int x, int top, int w, int bottom, int mouseX, int mouseY) {
		List<Target> items = groupTargets();
		if (!warmed("bulk:" + category, items)) {
			text(ctx, "Comparing your packs for every " + category.label.toLowerCase(Locale.ROOT) + " item...", x, top + 4, MUTED);
			return;
		}
		String cacheKey = category + "|" + styleQuery + "|" + filter + "|" + config.sortZtoA + "|" + config.favorites.hashCode()
			+ "|" + config.recent.hashCode() + "|" + dev.itemstudio.pack.PackIndexCache.generation();
		if (!cacheKey.equals(bulkKey)) {
			buildBulkRows(items);
			bulkKey = cacheKey;
		}
		List<List<FilePackSource>> rows = bulkRows;
		Map<String, int[]> counts = bulkCounts;
		drawBulkRows(ctx, items, rows, counts, x, top, w, bottom, mouseX, mouseY);
	}

	private void buildBulkRows(List<Target> items) {
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
		bulkRows = arrange(new ArrayList<>(groups.values()), g -> g);
		bulkCounts = counts;
	}

	private void drawBulkRows(DrawContext ctx, List<Target> items, List<List<FilePackSource>> rows, Map<String, int[]> counts,
	                          int x, int top, int w, int bottom, int mouseX, int mouseY) {
		int rowH = compact ? 32 : 36, rgap = compact ? 4 : 5;
		int headH = ctl + 6;
		int content = rows.size() * (rowH + rgap) + headH;
		int off = scrollOf("cards", content, bottom - top);
		scrollAreas.add(new ScrollArea("cards", x, top, x + w, bottom, Math.max(0, content - (bottom - top))));

		ctx.enableScissor(x - 2, top, x + w + 2, bottom);
		int y = top - off;
		int[] lg = shapeLg();
		int[] sm = shapeSm();
		// reset row
		boolean anyStyled = items.stream().anyMatch(t -> styleOf(t.key) != null);
		String reset = "Reset all " + category.label.toLowerCase(Locale.ROOT) + " to Default";
		int bh = ctl - 2;
		if (anyStyled) {
			int rw = buttonW(reset, Icons.UNDO);
			if (y + bh > top && y < bottom) {
				button(ctx, x + w - rw, y, rw, bh, reset, Icons.UNDO, BTN_NORMAL, mouseX, mouseY, () -> useForCategory(null), null);
			}
		}
		small(ctx, smallTrim("Faded tiles = that pack doesn't change this item.", w - (anyStyled ? buttonW(reset, Icons.UNDO) + 8 : 0)),
			x, y + (bh - smallH()) / 2 + (pixelFont() ? 0 : 1), MUTED);
		y += headH;

		int tile = compact ? 18 : 20;
		int buttonW = compact ? 66 : 74;
		for (List<FilePackSource> group : rows) {
			if (y + rowH < top || y > bottom) {
				y += rowH + rgap;
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
			box(ctx, x, y, x + w, y + rowH, inUse ? pal.cardOn() : pal.surface(), inUse ? pal.accent() : pal.surfaceBorder(), lg);

			int nameW = Math.max(70, w / 3);
			bold(ctx, trim(pack.displayName() + (group.size() > 1 ? " +" + (group.size() - 1) : ""), nameW - 12), x + 8, y + (rowH - 19) / 2, TEXT);
			String sub = (inUse ? "In use - " : "") + "Changes " + count + " of " + items.size();
			small(ctx, smallTrim(sub, nameW - 12), x + 8, y + (rowH - 19) / 2 + 11, inUse ? pal.accentText() : MUTED);

			int tx = x + nameW;
			int tilesEnd = x + w - buttonW - 12;
			int fit = Math.max(0, (tilesEnd - tx + 3) / (tile + 3));
			int ty = y + (rowH - tile) / 2;
			for (int k = 0; k < items.size() && k < fit; k++) {
				Target t = items.get(k);
				boolean has = provides(pack, t);
				int ox = tx + k * (tile + 3);
				if (k == fit - 1 && items.size() > fit) {
					round(ctx, ox, ty, ox + tile, ty + tile, pal.surface(), sm);
					String more = "+" + (items.size() - fit + 1);
					small(ctx, more, ox + (tile - smallW(more)) / 2, ty + (tile - smallH()) / 2 + (pixelFont() ? 0 : 1), MUTED);
					break;
				}
				round(ctx, ox, ty, ox + tile, ty + tile, has ? pal.well() : pal.surface(), sm);
				if (has) {
					if (!drawPreview(ctx, pack.id() + "|" + t.key, () -> previewOf(pack, t), ox + (tile - 16) / 2, ty + (tile - 16) / 2, 16)) {
						drawTargetIcon(ctx, t, ox + (tile - 16) / 2, ty + (tile - 16) / 2, 16);
					}
				} else {
					drawTargetIcon(ctx, t, ox + (tile - 16) / 2, ty + (tile - 16) / 2, 16);
					fill(ctx, ox, ty, ox + tile, ty + tile, 0x99000000 | (theme().base & 0xFFFFFF));
				}
				addHit(ox, ty, ox + tile, ty + tile, top, bottom, null, null,
					nameOf(t) + (has ? "" : " (not in this pack)"));
			}

			String label = inUse ? "In use" : "Use for " + count;
			int by = y + (rowH - bh) / 2;
			if (by >= top && by + bh <= bottom) {
				button(ctx, x + w - buttonW - 8, by, buttonW, bh, label, inUse ? Icons.CHECK : null, inUse ? BTN_SOFT : BTN_PRIMARY,
					mouseX, mouseY, inUse ? () -> { } : () -> useForCategory(pack.id()),
					inUse ? null : "Use " + pack.displayName() + " for the " + count + " item(s) it changes");
			}
			y += rowH + rgap;
		}
		if (rows.isEmpty()) {
			text(ctx, styleQuery.isEmpty() ? "None of your packs change items in this group." : "No packs match \"" + styleQuery + "\"",
				x, y + 2, MUTED);
		}
		ctx.disableScissor();
		scrollBar(ctx, x + w + 6, top, bottom, off, Math.max(0, content - (bottom - top)), false);
	}

	private static final String[] TWO_DECIMALS = new String[3601];
	private static final String[] WHOLE = new String[721];

	/** A slider value as text: "105%", "-3.25", "15\u00B0". Texts are remembered, so no formatting happens per frame. */
	private static String valueText(float value, String unit, float step, float displayMul) {
		if (displayMul != 1f) {
			int v = Math.round(value * displayMul);
			return v + unit;
		}
		if (step >= 1f) {
			int v = Math.round(value);
			int idx = v + 360;
			if (idx < 0 || idx >= WHOLE.length) return v + unit;
			String s = WHOLE[idx];
			if (s == null) WHOLE[idx] = s = v + unit;
			return s;
		}
		int v = Math.round(value * 100);
		int idx = v + 1800;
		if (idx < 0 || idx >= TWO_DECIMALS.length) return String.format(Locale.ROOT, "%.2f", value) + unit;
		String s = TWO_DECIMALS[idx];
		if (s == null) TWO_DECIMALS[idx] = s = String.format(Locale.ROOT, "%.2f", v / 100.0) + unit;
		return s;
	}

	/**
	 * A size for texture pictures that suits 16 px and 32 px packs alike: a multiple of 32, so both are scaled by a
	 * whole number and come out equally big (16 px at 2x or 4x, 32 px at 1x or 2x).
	 */
	private static int pictureSize(int room) {
		return room >= 64 ? room / 32 * 32 : room >= 32 ? 32 : Math.max(8, room);
	}

	/** Height one slider row takes. */
	private int sliderStep() {
		return compact ? 22 : 25;
	}

	/**
	 * A labelled slider: name on the left, value on the right (click it to put the value back), a track you can drag
	 * and small arrows that move it one step. Right-click the track to reset it too. Returns the y below the row.
	 */
	private int slider(DrawContext ctx, String label, String unit, float min, float max, float step, float def,
	                   Supplier<Float> get, Consumer<Float> set, int barX1, int barX2, int y, int mouseX, int mouseY,
	                   float displayMul) {
		float value = get.get();
		boolean changedValue = Math.abs(value - def) > 1e-4f;
		boolean settingsSlider = label.equals("Opacity");
		String shown = valueText(value, unit, step, displayMul);
		boolean beingDragged = dragging != null && dragging.label.equals(label);

		// label and value
		text(ctx, trim(label, barX2 - barX1 - textW(shown) - 14), barX1, y, changedValue ? TEXT : TEXT2);
		int vw = textW(shown);
		if (changedValue) {
			int rx1 = barX2 - vw - Icons.UNDO.w - 8, rx2 = barX2 + 2;
			boolean rh = hot(mouseX, mouseY, rx1, y - 2, rx2, y + 10);
			float rt = anim.fade(fadeKey(label, barX1) + 3, rh, 16f);
			round(ctx, rx1, y - 2, rx2, y + 10, Anim.mix(0, pal.soft(), rt), shapeSm());
			icon(ctx, Icons.UNDO, rx1 + 3, y + 0, Anim.mix(pal.accentText(), WHITE, rt));
			text(ctx, shown, barX2 - vw, y, Anim.mix(pal.accentText(), WHITE, rt));
			hits.add(new Hit(rx1, y - 2, rx2, y + 10, () -> {
				set.accept(def);
				if (settingsSlider) config.save();
				else changed(true);
			}, null, "Put it back to " + valueText(def, unit, step, displayMul), null));
		} else {
			text(ctx, shown, barX2 - vw, y, MUTED);
		}

		// track with arrows at both ends
		int cy = y + 16;
		int arrow = 10;
		int tx1 = barX1 + arrow + 3, tx2 = barX2 - arrow - 3;
		float t = (value - min) / (max - min);
		float t0 = (def - min) / (max - min);
		int kx = tx1 + Math.round(t * (tx2 - tx1));
		int zx = tx1 + Math.round(t0 * (tx2 - tx1));
		boolean hover = inside(mouseX, mouseY, tx1 - 4, cy - 6, tx2 + 4, cy + 6) && (dragging == null || beingDragged);
		float gt = anim.fade(fadeKey(label, barX1) + 5, hover || beingDragged, 16f);
		round(ctx, tx1, cy - 2, tx2, cy + 2, pal.track(), shapePill(4));
		if (kx != zx) round(ctx, Math.min(kx, zx), cy - 2, Math.max(kx, zx), cy + 2, pal.accent(), CORNERS_1);
		if (t0 > 0.02f && t0 < 0.98f) fill(ctx, zx, cy - 4, zx + 1, cy + 4, kx == zx ? 0x00000000 : 0x66FFFFFF);
		if (gt > 0.02f) round(ctx, kx - 6, cy - 6, kx + 6, cy + 6, Anim.mix(0, pal.glow(), gt), shapePill(12));
		round(ctx, kx - 4, cy - 4, kx + 4, cy + 4, beingDragged ? pal.accentText() : WHITE, shapePill(8));

		Slider slider = new Slider(label, min, max, step, def, get, set, tx1, tx2);
		hits.add(new Hit(tx1 - 4, cy - 7, tx2 + 4, cy + 7, null, null, null, slider));
		// arrows
		for (int dir = -1; dir <= 1; dir += 2) {
			int ax1 = dir < 0 ? barX1 : barX2 - arrow;
			boolean atEnd = dir < 0 ? value <= min + 1e-4f : value >= max - 1e-4f;
			boolean ah = hot(mouseX, mouseY, ax1, cy - 7, ax1 + arrow, cy + 7) && !atEnd;
			float at = anim.fade(fadeKey(label, barX1) + (dir < 0 ? 11 : 13), ah, 16f);
			Icons ai = dir < 0 ? Icons.CHEVRON_LEFT : Icons.CHEVRON_RIGHT;
			if (at > 0.02f) round(ctx, ax1, cy - 7, ax1 + arrow, cy + 7, Anim.mix(0, pal.surfaceHover(), at), shapeSm());
			icon(ctx, ai, ax1 + (arrow - ai.w) / 2, cy - 3, atEnd ? 0x33FFFFFF : Anim.mix(MUTED, WHITE, at));
			final int d = dir;
			if (!atEnd) {
				hits.add(new Hit(ax1, cy - 7, ax1 + arrow, cy + 7, () -> {
					slider.nudge(d);
					if (!settingsSlider) changed(true);
				}, null, d < 0 ? "A little less" : "A little more", null));
			}
		}
		return y + sliderStep();
	}

	/** Small caption above a group of sliders. */
	private int caption(DrawContext ctx, String s, int x, int y) {
		small(ctx, s.toUpperCase(Locale.ROOT), x, y, MUTED);
		return y + smallH() + 3;
	}

	/** A section line with a link on its right that does something (Reset). Returns the y below it. */
	private int sectionAction(DrawContext ctx, String title, String action, boolean enabled, int x1, int x2, int y,
	                          int mouseX, int mouseY, Runnable run, String tooltip) {
		String caps = title.toUpperCase(Locale.ROOT);
		small(ctx, caps, x1, y, MUTED);
		int tx = x1 + smallW(caps) + 5;
		// when the title and the link don't both fit, the link is just its icon
		boolean iconOnly = smallW(caps) + 8 + smallW(action) + Icons.UNDO.w + 4 > x2 - x1;
		int aw = iconOnly ? Icons.UNDO.w : smallW(action) + (enabled ? Icons.UNDO.w + 4 : 0);
		int ax = x2 - aw;
		if (ax - 5 > tx) fill(ctx, tx, y + smallH() / 2 - 1, ax - 5, y + smallH() / 2, pal.line());
		if (enabled) {
			boolean h = hot(mouseX, mouseY, ax - 3, y - 3, x2 + 2, y + smallH() + 3);
			float t = anim.fade(fadeKey(action, x1) + 1, h, 16f);
			int c = Anim.mix(pal.accentText(), WHITE, t);
			icon(ctx, Icons.UNDO, ax, y + (smallH() - Icons.UNDO.h) / 2 + (pixelFont() ? 0 : 1), c);
			if (!iconOnly) small(ctx, action, ax + Icons.UNDO.w + 4, y, c);
			hits.add(new Hit(ax - 3, y - 3, x2 + 2, y + smallH() + 3, run, null, tooltip, null));
		} else if (iconOnly) {
			icon(ctx, Icons.UNDO, ax, y + (smallH() - Icons.UNDO.h) / 2 + (pixelFont() ? 0 : 1), 0x44FFFFFF);
		} else {
			small(ctx, action, ax, y, 0x55FFFFFF);
		}
		return y + smallH() + 5;
	}

	/** The picture box at the top of the side panel. Returns the y below it. */
	private int drawStage(DrawContext ctx, int ix1, int ix2, int y, boolean edited) {
		int[] lg = shapeLg();
		box(ctx, ix1, y, ix2, y + stageH, pal.well(), pal.line(), lg);
		// a faint floor line gives the item something to stand on
		fill(ctx, ix1 + 8, y + stageH - 14, ix2 - 8, y + stageH - 13, 0x0DFFFFFF);
		small(ctx, "PREVIEW", ix1 + 6, y + stageH - smallH() - 3, 0x66FFFFFF);
		if (edited) {
			String s = "Edited";
			int bw = smallW(s) + 14;
			round(ctx, ix2 - bw - 4, y + 4, ix2 - 4, y + 4 + smallH() + 3, pal.soft(), shapePill(smallH() + 3));
			icon(ctx, Icons.DOT, ix2 - bw, y + 4 + (smallH() + 3 - 5) / 2, pal.accent());
			small(ctx, s, ix2 - bw + 8, y + 5 + (pixelFont() ? 0 : 0), pal.accentText());
		}
		return y + stageH + (compact ? 6 : 8);
	}

	private void drawSide(DrawContext ctx, int mouseX, int mouseY) {
		int x1 = sideX, x2 = px + pw - 1, top = bodyY, bottom = footerY;
		fill(ctx, x1, top, x2, bottom, pal.rail());
		fill(ctx, x1 - 1, top, x1, bottom, pal.line());
		Target sideTarget = current();
		if (sideTarget != null && sideTarget.isPart()) {
			drawPartSide(ctx, sideTarget, x1, x2, top, bottom, mouseX, mouseY);
			return;
		}
		if (selected == null) {
			text(ctx, "Pick an item", x1 + pad, top + pad, MUTED);
			return;
		}
		ItemInfo info = ItemInfo.of(selected);
		int ix1 = x1 + pad, ix2 = x2 - pad, iw = ix2 - ix1;

		int off = scrollOf("side", sideContent, bottom - top);
		ctx.enableScissor(x1, top, x2, bottom);
		int firstHit = hits.size();
		int y = top + pad - off;
		String itemId = info.id;
		Target selectedTarget = itemTarget(selected);

		// preview stage: the chosen style's texture, big
		String style = ready() ? styleOf(selected) : null;
		int stageY = y;
		y = drawStage(ctx, ix1, ix2, y, isEdited(selectedTarget));
		int picture = pictureSize(stageH - 12);
		int picX = ix1 + (iw - picture) / 2, picY = stageY + (stageH - 12 - picture) / 2;
		boolean drawn = false;
		if (ready() && style != null && index.get(style) != null) {
			PackSource src = index.get(style);
			drawn = drawPreview(ctx, src.id() + "|" + info.id, () -> previewOf(src, selectedTarget), picX, picY, picture);
		}
		if (!drawn) drawTargetIcon(ctx, selectedTarget, picX, picY, picture);

		heading(ctx, trim(selected.getName().getString(), iw), ix1, y, TEXT);
		y += 12;
		small(ctx, smallTrim(info.id, iw), ix1, y, MUTED);
		y += smallH() + 3;
		text(ctx, "Style", ix1, y + 2, MUTED);
		String styleName = style == null ? "Default" : index != null && index.get(style) != null ? index.get(style).displayName() : style + " (missing)";
		int chipX = ix1 + textW("Style") + 7;
		String chip = trim(styleName, ix2 - chipX - 12);
		round(ctx, chipX, y, chipX + textW(chip) + 10, y + 13, style == null ? pal.surface() : pal.soft(), shapeSm());
		text(ctx, chip, chipX + 5, y + 3, style == null ? TEXT2 : pal.accentText());
		y += 19;

		// which view the sliders change
		List<View> views = new ArrayList<>(List.of(View.values()));
		if (!info.hasBlockingModel()) views.remove(View.BLOCKING);
		if (!views.contains(view)) view = View.FIRST_PERSON;
		int editedViews = 0;
		for (View v : views) {
			Adjust adj = config.getAdjust(itemId, v);
			if (adj != null && !adj.isDefault()) editedViews++;
		}
		y = section(ctx, "View", editedViews > 0 ? editedViews + " edited" : null, ix1, ix2, y);
		int vgap = 3;
		int vw = (iw - vgap * 2) / 3;
		int vh = compact ? 14 : 16;
		for (int i = 0; i < views.size(); i++) {
			View v = views.get(i);
			int vx = ix1 + (i % 3) * (vw + vgap);
			int vy = y + (i / 3) * (vh + vgap);
			boolean on = v == view;
			Adjust adj = config.getAdjust(itemId, v);
			boolean edited = adj != null && !adj.isDefault();
			boolean hover = hot(mouseX, mouseY, vx, vy, vx + vw, vy + vh);
			boolean down = pressed(vx, vy, vx + vw, vy + vh);
			float ht = anim.fade(Anim.key(0x7400, v.ordinal()), hover && !on, 16f);
			float st = anim.fade(Anim.key(0x7401, v.ordinal()), on, 16f);
			int bg = down ? pal.strongPressed() : Anim.mix(Anim.mix(pal.surface(), pal.surfaceHover(), ht), pal.strong(), st);
			int border = Anim.mix(Anim.mix(pal.surfaceBorder(), pal.surfaceBorderHover(), ht), 0, st);
			box(ctx, vx, vy, vx + vw, vy + vh, bg, border, shapeSm());
			String l = pixelFont() ? shortLabel(v) : v.label;
			if (smallW(l) > vw - 6) l = shortLabel(v);
			l = smallTrim(l, vw - 4);
			small(ctx, l, vx + (vw - smallW(l)) / 2, vy + (vh - smallH()) / 2 + (pixelFont() ? 0 : 1) + (down ? 1 : 0),
				on ? WHITE : edited ? pal.accentText() : Anim.mix(TEXT2, WHITE, ht));
			if (edited) round(ctx, vx + vw - 5, vy + 2, vx + vw - 2, vy + 5, on ? WHITE : pal.accent(), CORNERS_NONE);
			final View target = v;
			hits.add(new Hit(vx, vy, vx + vw, vy + vh, () -> view = target, null,
				v == View.BLOCKING ? "First person while you hold right-click" : v.label, null));
		}
		y += ((views.size() + 2) / 3) * (vh + vgap) + 4;
		if (view == View.BLOCKING) {
			y = smallWrapped(ctx, "Only while you hold right-click. Make it smaller or move it down to see more"
				+ " of the screen.", ix1, y, iw, MUTED) + 4;
		}

		Adjust current = config.getAdjust(itemId, view);
		Adjust shown = current == null ? new Adjust() : current;
		boolean viewEdited = current != null && !current.isDefault();
		y = sectionAction(ctx, "Size & position", "Reset", viewEdited, ix1, ix2, y, mouseX, mouseY, () -> {
			Adjust a = config.getAdjust(itemId, view);
			if (a != null && !a.isDefault()) {
				a.reset();
				changed(true);
			}
		}, "Put this view back to normal");
		y = slider(ctx, "Size", "%", 0.1f, 3f, 0.05f, 1f, () -> shown.scale,
			v -> config.editAdjust(itemId, view).scale = v, ix1, ix2, y, mouseX, mouseY, 100f);
		y = caption(ctx, "Position", ix1, y + 1);
		y = slider(ctx, "Left / right", "", -16f, 16f, 0.25f, 0f, () -> shown.x,
			v -> config.editAdjust(itemId, view).x = v, ix1, ix2, y, mouseX, mouseY, 1f);
		y = slider(ctx, "Up / down", "", -16f, 16f, 0.25f, 0f, () -> shown.y,
			v -> config.editAdjust(itemId, view).y = v, ix1, ix2, y, mouseX, mouseY, 1f);
		y = slider(ctx, "Forward / back", "", -16f, 16f, 0.25f, 0f, () -> shown.z,
			v -> config.editAdjust(itemId, view).z = v, ix1, ix2, y, mouseX, mouseY, 1f);
		y = caption(ctx, "Rotation", ix1, y + 1);
		y = slider(ctx, "Tilt", "°", -180f, 180f, 5f, 0f, () -> shown.rotX,
			v -> config.editAdjust(itemId, view).rotX = v, ix1, ix2, y, mouseX, mouseY, 1f);
		y = slider(ctx, "Turn", "°", -180f, 180f, 5f, 0f, () -> shown.rotY,
			v -> config.editAdjust(itemId, view).rotY = v, ix1, ix2, y, mouseX, mouseY, 1f);
		y = slider(ctx, "Roll", "°", -180f, 180f, 5f, 0f, () -> shown.rotZ,
			v -> config.editAdjust(itemId, view).rotZ = v, ix1, ix2, y, mouseX, mouseY, 1f);
		y += 2;
		boolean anyEdited = config.hasAdjust(itemId);
		int rh = ctl - 2;
		button(ctx, ix1, y, iw, rh, "Reset all views", anyEdited ? Icons.UNDO : null, anyEdited ? BTN_NORMAL : BTN_FLAT, mouseX, mouseY, () -> {
			if (config.hasAdjust(itemId)) {
				config.adjust.remove(itemId);
				changed(true);
			}
		}, anyEdited ? "Put every view of this item back to normal" : null);
		y += rh + 6;
		y = smallWrapped(ctx, "Right-click a slider to reset it. The arrows move it one step.", ix1, y, iw, MUTED);
		ctx.disableScissor();
		clipHits(firstHit, top, bottom);

		sideContent = y + off - top + pad;
		int max = Math.max(0, sideContent - (bottom - top));
		scroll.put("side", Math.max(0, Math.min(max, scroll.getOrDefault("side", 0))));
		scrollAreas.add(new ScrollArea("side", x1, top, x2, bottom, max));
		scrollBar(ctx, x2, top, bottom, scroll.getOrDefault("side", 0), max, false);
	}

	/** Height of the side panel's content as last drawn, so scrolling knows how far it can go. */
	private int sideContent = 400;

	private void drawPartSide(DrawContext ctx, Target t, int x1, int x2, int top, int bottom, int mouseX, int mouseY) {
		int ix1 = x1 + pad, ix2 = x2 - pad, iw = ix2 - ix1;
		int off = scrollOf("side", sideContent, bottom - top);
		ctx.enableScissor(x1, top, x2, bottom);
		int firstHit = hits.size();
		int y = top + pad - off;
		String key = t.key;
		boolean resizable = t.part.resizable();
		float size = resizable ? config.partSizeOf(key) : 1f;
		int stageY = y;
		y = drawStage(ctx, ix1, ix2, y, isEdited(t));
		String style = ready() ? styleOf(key) : null;
		PackSource src = style == null || index == null ? null : index.get(style);
		// particles show at their chosen size (bigger than normal can't be shown bigger than the box)
		int full = pictureSize(stageH - 12);
		int box = resizable ? Math.max(6, Math.round(full * Math.min(1f, size))) : full;
		int bx = ix1 + iw / 2 - box / 2, by = stageY + (stageH - 12) / 2 - box / 2;
		boolean drawn = false;
		if (isSound(t)) {
			int color = SoundPreview.isPlaying(soundKey(src, t)) ? WHITE : pal.accent();
			drawn = drawWave(ctx, soundKey(src, t), () -> firstSound(src, t.part), bx, by, box, color);
			if (!drawn && SoundPreview.waveLoading(waveKey(soundKey(src, t)))) {
				drawLoading(ctx, bx + box / 2, by + box / 2);
				drawn = true;
			}
		} else if (t.part == dev.itemstudio.pack.HudElement.CROSSHAIR && config.crosshair != null) {
			String drawnCross = config.crosshair;
			drawn = drawPreview(ctx, "cross|" + drawnCross, () -> crosshairPng(drawnCross), bx, by, box);
		} else if (t.part.heightAdjustable() && config.partSize.containsKey(key)) {
			// show the lowered fire
			float height = config.partSizeOf(key);
			String file = t.part.preview();
			drawn = drawPreview(ctx, "low|" + style + "|" + Math.round(height * 100), () -> {
				byte[] png = src != null ? readWithBelow(src, file) : Resources.readBelow(file);
				byte[] meta = src != null ? readWithBelow(src, file + ".mcmeta") : Resources.readBelow(file + ".mcmeta");
				byte[] low = png == null ? null : dev.itemstudio.pack.TextureScaler.lower(png, meta, height);
				return low == null ? png : low;
			}, bx, by, box);
		} else if (src != null) {
			drawn = drawPreview(ctx, src.id() + "|" + key, () -> previewOf(src, t), bx, by, box);
		}
		if (!drawn) drawTargetIcon(ctx, t, bx, by, box);
		heading(ctx, trim(t.part.label(), iw), ix1, y, TEXT);
		y += 12;
		small(ctx, t.part.kind(), ix1, y, MUTED);
		y += smallH() + 3;
		text(ctx, "Style", ix1, y + 2, MUTED);
		String styleName = style == null ? "Default" : src != null ? src.displayName() : style + " (missing)";
		int chipX = ix1 + textW("Style") + 7;
		String chip = trim(styleName, ix2 - chipX - 12);
		round(ctx, chipX, y, chipX + textW(chip) + 10, y + 13, style == null ? pal.surface() : pal.soft(), shapeSm());
		text(ctx, chip, chipX + 5, y + 3, style == null ? TEXT2 : pal.accentText());
		y += 19;
		if (resizable) {
			boolean changedSize = config.partSize.containsKey(key);
			y = sectionAction(ctx, "Size", "Reset", changedSize, ix1, ix2, y, mouseX, mouseY, () -> {
				if (config.partSize.containsKey(key)) {
					config.setPartSize(key, 1f);
					changed(true);
				}
			}, "Back to normal size");
			y = slider(ctx, "Particle size", "%", 0.1f, 2f, 0.05f, 1f, () -> config.partSizeOf(key),
				v -> config.setPartSize(key, v), ix1, ix2, y, mouseX, mouseY, 100f);
			y += 2;
			int rh = ctl - 2;
			button(ctx, ix1, y, iw, rh, "Use this size for all particles", null, BTN_NORMAL, mouseX, mouseY, () -> {
				float chosen = config.partSizeOf(key);
				for (TexturePart p : Category.PARTICLES.parts()) config.setPartSize(p.key(), chosen);
				changed(true);
			}, "Every particle gets " + Math.round(size * 100) + "%");
			y += rh + 6;
			y = smallWrapped(ctx, "Smaller works on every particle. Above 100% a particle only grows into empty space around"
				+ " its picture; ones that fill their picture get their edges cut.", ix1, y, iw, MUTED);
		} else if (t.part.heightAdjustable()) {
			boolean lowered = config.partSize.containsKey(key);
			y = sectionAction(ctx, "Height", "Reset", lowered, ix1, ix2, y, mouseX, mouseY, () -> {
				if (config.partSize.containsKey(key)) {
					config.setPartSize(key, 1f);
					changed(true);
				}
			}, "Back to normal fire");
			y = slider(ctx, "Fire height", "%", 0.1f, 1f, 0.05f, 1f, () -> config.partSizeOf(key),
				v -> config.setPartSize(key, v), ix1, ix2, y, mouseX, mouseY, 100f);
			y = smallWrapped(ctx, "Lower = less fire on your screen when you burn. Fire on burning blocks and mobs gets"
				+ " lower too, because the game uses the same picture.", ix1, y + 2, iw, MUTED);
		} else if (t.part == dev.itemstudio.pack.HudElement.CROSSHAIR) {
			y = drawCrosshairEditor(ctx, ix1, ix2, y, top, bottom, mouseX, mouseY);
		} else if (t.part == dev.itemstudio.pack.BlockElement.BREAKING) {
			y = smallWrapped(ctx, "The cracks on a block while you mine it. Shown here on stone.", ix1, y, iw, MUTED);
		} else if (t.part instanceof dev.itemstudio.pack.BlockElement) {
			y = smallWrapped(ctx, "Changes placed blocks and the block item together.", ix1, y, iw, MUTED);
		} else if (t.part instanceof dev.itemstudio.pack.SkyElement) {
			String mod = dev.itemstudio.SkyMods.shownBy();
			if (mod != null) {
				y = smallWrapped(ctx, "Shown in game by " + mod + ". Copies the pack's whole skybox, every layer.", ix1, y, iw, MUTED);
			} else if (dev.itemstudio.SkyMods.nuitWithoutInterop()) {
				y = smallWrapped(ctx, "Nuit needs Nuit Interop to show these skies. Add it to your mods folder.", ix1, y, iw, WARN);
			} else {
				y = smallWrapped(ctx, "Custom skies need a sky mod to show in game: Skyboxify, or Nuit with Nuit Interop."
					+ " None is installed, so your pick is saved but you won't see it yet.", ix1, y, iw, WARN);
			}
		} else if (t.part instanceof SoundElement sound) {
			int rh = ctl - 2;
			PackSource playFrom = src;
			button(ctx, ix1, y, iw, rh, "Play", Icons.PLAY, BTN_NORMAL, mouseX, mouseY, () -> playSound(playFrom, t),
				"Hear the picked sound. Press again for its next one");
			y += rh + 6;
			if (sound.note() != null) y = smallWrapped(ctx, sound.note(), ix1, y, iw, MUTED) + 2;
			y = smallWrapped(ctx, "Click a card to hear it and pick it, or its play button to only hear it.", ix1, y, iw, MUTED);
		} else {
			y = smallWrapped(ctx, "These use the pack's textures as they are.", ix1, y, iw, MUTED);
		}
		ctx.disableScissor();
		clipHits(firstHit, top, bottom);
		sideContent = y + off - top + pad;
		int max = Math.max(0, sideContent - (bottom - top));
		scroll.put("side", Math.max(0, Math.min(max, scroll.getOrDefault("side", 0))));
		scrollAreas.add(new ScrollArea("side", x1, top, x2, bottom, max));
		scrollBar(ctx, x2, top, bottom, scroll.getOrDefault("side", 0), max, false);
	}

	private static final String[] CROSS_PRESETS = {"Plus", "Gap", "Dot", "Circle", "X", "Clear"};

	/** A 15x15 grid to draw a crosshair on, with a few ready-made shapes. Returns the y below it. */
	private int drawCrosshairEditor(DrawContext ctx, int ix1, int ix2, int y, int top, int bottom, int mouseX, int mouseY) {
		int iw = ix2 - ix1;
		y = sectionAction(ctx, "Draw your own", "Turn off", config.crosshair != null, ix1, ix2, y, mouseX, mouseY, () -> {
			config.crosshair = null;
			changed(true);
		}, "Go back to the picked crosshair style");
		int cell = Math.max(4, Math.min(9, (iw - 8) / 15));
		int size = cell * 15;
		int gx = ix1 + (iw - size) / 2;
		box(ctx, gx - 4, y - 1, gx + size + 4, y + size + 3, pal.well(), pal.line(), shapeSm());
		y += 1;
		String pattern = config.crosshair != null ? config.crosshair : crosshairSeed();
		boolean drawing = config.crosshair != null;
		// the middle row and column show where the game aims; the rest of the grid is just the dark box
		fill(ctx, gx, y + 7 * cell, gx + size, y + 8 * cell, 0x0DFFFFFF);
		fill(ctx, gx + 7 * cell, y, gx + 8 * cell, y + size, 0x0DFFFFFF);
		fill(ctx, gx + 7 * cell, y + 7 * cell, gx + 8 * cell, y + 8 * cell, pal.soft());
		int hoverC = (int) Math.floor((mouseX - gx) / (double) cell), hoverR = (int) Math.floor((mouseY - y) / (double) cell);
		boolean overGrid = hoverC >= 0 && hoverC < 15 && hoverR >= 0 && hoverR < 15 && mouseY >= top && mouseY < bottom;
		int gap = cell > 4 ? 1 : 0;
		for (int r = 0; r < 15; r++) {
			for (int c = 0; c < 15; c++) {
				boolean on = pattern.charAt(r * 15 + c) == '1';
				if (!on) continue;
				int cx = gx + c * cell, cy = y + r * cell;
				fill(ctx, cx, cy, cx + cell - gap, cy + cell - gap, drawing ? WHITE : 0x99FFFFFF);
			}
		}
		if (overGrid && !profilesOpen) {
			int cx = gx + hoverC * cell, cy = y + hoverR * cell;
			box(ctx, cx - 1, cy - 1, cx + cell + 1 - gap, cy + cell + 1 - gap, 0x22FFFFFF, pal.accent(), CORNERS_NONE);
		}
		crossX = gx;
		crossY = y;
		crossCell = cell;
		crossTop = top;
		crossBottom = bottom;
		crossShown = true;
		y += size + 9;
		int bw = (iw - 8) / 3;
		int bh = ctl - 2;
		for (int i = 0; i < CROSS_PRESETS.length; i++) {
			String name = CROSS_PRESETS[i];
			button(ctx, ix1 + (i % 3) * (bw + 4), y + (i / 3) * (bh + 3), bw, bh, name, null, BTN_NORMAL, mouseX, mouseY, () -> {
				config.crosshair = crosshairPreset(name);
				changed(true);
			}, name.equals("Clear") ? "Start from an empty grid" : "Start from a " + name.toLowerCase(Locale.ROOT) + " shape");
		}
		y += 2 * (bh + 3) + 3;
		return smallWrapped(ctx, config.crosshair == null
			? "This is the crosshair you have now. Click a square or a shape to start drawing."
			: "Click to draw, right-click to erase. The game flips the colours behind the crosshair, so it stays easy"
			+ " to see.", ix1, y, iw, MUTED);
	}

	private int lastPaintC = -1, lastPaintR = -1;

	/**
	 * Paints the crosshair square under the mouse, and the squares between it and the last one painted, so a fast
	 * drag leaves no gaps. Returns false when the mouse isn't on the grid.
	 */
	private boolean paintCross(double mx, double my) {
		if (!crossShown || my < crossTop || my >= crossBottom) {
			lastPaintC = -1;
			return false;
		}
		int c = (int) Math.floor((mx - crossX) / crossCell), r = (int) Math.floor((my - crossY) / crossCell);
		if (c < 0 || c >= 15 || r < 0 || r >= 15) {
			lastPaintC = -1;
			return false;
		}
		char[] p = (config.crosshair != null ? config.crosshair : crosshairSeed()).toCharArray();
		char value = paintOn ? '1' : '0';
		int c0 = lastPaintC < 0 ? c : lastPaintC, r0 = lastPaintC < 0 ? r : lastPaintR;
		int dc = Math.abs(c - c0), dr = Math.abs(r - r0);
		int stepC = c0 < c ? 1 : -1, stepR = r0 < r ? 1 : -1;
		int err = dc - dr;
		int x = c0, y = r0;
		while (true) {
			p[y * 15 + x] = value;
			if (x == c && y == r) break;
			int e2 = 2 * err;
			if (e2 > -dr) {
				err -= dr;
				x += stepC;
			}
			if (e2 < dc) {
				err += dc;
				y += stepR;
			}
		}
		lastPaintC = c;
		lastPaintR = r;
		config.crosshair = new String(p);
		return true;
	}

	/** The crosshair the game shows now (or the picked style's), as 225 '0'/'1' characters. */
	private String crosshairSeed() {
		String style = styleOf(dev.itemstudio.pack.HudElement.CROSSHAIR.key());
		String k = String.valueOf(style) + "|" + (index == null);
		if (k.equals(seedKey) && seedPattern != null) return seedPattern;
		PackSource src = style == null || index == null ? null : index.get(style);
		byte[] png = src != null ? readWithBelow(src, PackBuilder.CROSSHAIR_FILE) : Resources.readBelow(PackBuilder.CROSSHAIR_FILE);
		dev.itemstudio.pack.Png.Image img = dev.itemstudio.pack.Png.decode(png);
		StringBuilder b = new StringBuilder();
		for (int r = 0; r < 15; r++) {
			for (int c = 0; c < 15; c++) {
				boolean on = img != null && (img.get(c * img.width() / 15, r * img.height() / 15) >>> 24) > 0;
				b.append(on ? '1' : '0');
			}
		}
		seedKey = k;
		seedPattern = b.toString();
		return seedPattern;
	}

	private static byte[] crosshairPng(String pattern) {
		int[] px = new int[225];
		for (int i = 0; i < 225; i++) px[i] = pattern.charAt(i) == '1' ? 0xFFFFFFFF : 0;
		return dev.itemstudio.pack.Png.encode(15, 15, px);
	}

	private static String crosshairPreset(String name) {
		char[] p = "0".repeat(225).toCharArray();
		java.util.function.BiConsumer<Integer, Integer> set = (r, c) -> p[r * 15 + c] = '1';
		switch (name) {
			case "Plus" -> {
				for (int i = 3; i <= 11; i++) {
					set.accept(7, i);
					set.accept(i, 7);
				}
			}
			case "Gap" -> {
				for (int i : new int[]{2, 3, 4, 5, 9, 10, 11, 12}) {
					set.accept(7, i);
					set.accept(i, 7);
				}
			}
			case "Dot" -> {
				set.accept(7, 7);
				set.accept(6, 7);
				set.accept(8, 7);
				set.accept(7, 6);
				set.accept(7, 8);
			}
			case "Circle" -> {
				for (int r = 0; r < 15; r++) {
					for (int c = 0; c < 15; c++) {
						double d = Math.hypot(r - 7, c - 7);
						if (Math.abs(d - 4.5) < 0.6) set.accept(r, c);
					}
				}
			}
			case "X" -> {
				for (int i = 0; i <= 8; i++) {
					set.accept(3 + i, 3 + i);
					set.accept(3 + i, 11 - i);
				}
			}
			default -> {
			}
		}
		return new String(p);
	}

	// ------------------------------------------------------------------ settings

	private static String versionText;

	private static String versionText() {
		if (versionText == null) versionText = dev.itemstudio.UpdateCheck.currentVersion();
		return versionText;
	}

	private static final String[] SETTINGS_TABS = {"Look", "Layout", "General"};

	private void drawSettings(DrawContext ctx, int mouseX, int mouseY) {
		int x1 = sideX, x2 = px + pw - 1, top = bodyY, bottom = footerY;
		fill(ctx, x1, top, x2, bottom, pal.rail());
		fill(ctx, x1 - 1, top, x1, bottom, pal.line());
		int ix1 = x1 + pad, ix2 = x2 - pad, iw = ix2 - ix1;
		int y = top + pad;

		// title row with the on/off switch of the whole mod
		heading(ctx, "Settings", ix1, y + 1, TEXT);
		boolean modOn = config.enabled;
		String onLabel = modOn ? "On" : "Off";
		int swW = 20 + 5 + textW(onLabel);
		int swX = ix2 - swW;
		boolean hoverMod = hot(mouseX, mouseY, swX - 4, y - 3, ix2 + 3, y + 13);
		float mt = anim.fade(Anim.key(0x7600, 0), hoverMod, 16f);
		round(ctx, swX - 4, y - 3, ix2 + 3, y + 13, Anim.mix(0, pal.surface(), mt), shapeSm());
		drawSwitch(ctx, swX, y, modOn, Anim.key(0x7601, 0));
		text(ctx, onLabel, swX + 25, y + 1, modOn ? TEXT2 : WARN);
		hits.add(new Hit(swX - 4, y - 3, ix2 + 3, y + 13, () -> {
			ItemStudioClient.setEnabled(!config.enabled);
			setStatus(config.enabled ? "Item Studio turned on" : "Item Studio turned off - your normal packs are back", config.enabled ? GOOD : WARN);
		}, null, "Turn Item Studio on or off. Off puts your normal packs back; your picks stay saved.", null));
		y += 20;

		// tabs
		int[] tabW = new int[SETTINGS_TABS.length];
		Icons[] tabIcons = {Icons.PALETTE, Icons.LAYOUT, Icons.SLIDERS};
		boolean showTabIcons = iw >= 150;
		for (int i = 0; i < tabW.length; i++) tabW[i] = textW(SETTINGS_TABS[i]) + (showTabIcons ? tabIcons[i].w + 4 : 0) + 12;
		segmented(ctx, 0x7610, ix1, y, iw, ctl, SETTINGS_TABS, showTabIcons ? tabIcons : null, tabW, settingsTab, mouseX, mouseY, i -> {
			settingsTab = i;
			lastSettingsTab = i;
			resetScroll("settings");
			listening = null;
		}, null);
		y += ctl + 6;
		fill(ctx, x1, y - 3, x2, y - 2, pal.line());

		// the bar at the bottom stays put: reset the look, or be done
		int barH = ctl + 10;
		int contentTop = y - 2, contentBottom = bottom - barH;
		int off = scrollOf("settings", settingsContent, contentBottom - contentTop);
		ctx.enableScissor(x1, contentTop, x2, contentBottom);
		int firstHit = hits.size();
		y = contentTop + 7 - off;
		switch (settingsTab) {
			case 0 -> y = settingsLook(ctx, ix1, ix2, y, mouseX, mouseY);
			case 1 -> y = settingsLayout(ctx, ix1, ix2, y, mouseX, mouseY);
			default -> y = settingsGeneral(ctx, ix1, ix2, y, mouseX, mouseY);
		}
		ctx.disableScissor();
		clipHits(firstHit, contentTop, contentBottom);
		settingsContent = y + off - contentTop + 4;
		int max = Math.max(0, settingsContent - (contentBottom - contentTop));
		scroll.put("settings", Math.max(0, Math.min(max, scroll.getOrDefault("settings", 0))));
		scrollAreas.add(new ScrollArea("settings", x1, contentTop, x2, contentBottom, max));
		scrollBar(ctx, x2, contentTop, contentBottom, scroll.getOrDefault("settings", 0), max, false);

		fill(ctx, x1, contentBottom, x2, bottom, pal.rail());
		fill(ctx, x1, contentBottom, x2, contentBottom + 1, pal.line());
		int by = contentBottom + (barH - ctl) / 2;
		int half = (iw - 4) / 2;
		String resetLabel = buttonW("Reset look", Icons.UNDO) <= half ? "Reset look" : "Reset";
		button(ctx, ix1, by, half, ctl, resetLabel, Icons.UNDO, BTN_NORMAL, mouseX, mouseY, () -> {
			config.resetLook();
			config.save();
			setStatus("Menu look put back to how it came", MUTED);
		}, "Theme, accent, opacity, font, spacing, corners and motion back to the defaults");
		button(ctx, ix1 + half + 4, by, iw - half - 4, ctl, "Done", Icons.CHECK, BTN_PRIMARY, mouseX, mouseY,
			() -> settingsOpen = false, "Close the settings (Esc)");
	}

	private int settingsContent = 400;

	private int settingsGap() {
		return compact ? 7 : 10;
	}

	private int settingsLook(DrawContext ctx, int ix1, int ix2, int y, int mouseX, int mouseY) {
		int iw = ix2 - ix1;
		int[] sm = shapeSm();

		// theme: a tiny picture of each theme, 6 to a row
		y = section(ctx, "Theme", theme().label, ix1, ix2, y);
		Theme[] themes = Theme.values();
		int perRow = 6, sg = 4;
		int sw = (iw - sg * (perRow - 1)) / perRow;
		int sh = compact ? 16 : 18;
		for (int i = 0; i < themes.length; i++) {
			Theme t = themes[i];
			int tx = ix1 + (i % perRow) * (sw + sg);
			int ty = y + (i / perRow) * (sh + sg);
			boolean on = t == theme();
			boolean hover = hot(mouseX, mouseY, tx, ty, tx + sw, ty + sh);
			float ht = anim.fade(Anim.key(0x7500, i), hover && !on, 16f);
			if (on) box(ctx, tx - 2, ty - 2, tx + sw + 2, ty + sh + 2, 0, WHITE, sm);
			else if (ht > 0.02f) box(ctx, tx - 2, ty - 2, tx + sw + 2, ty + sh + 2, 0, Anim.mix(0, 0x99FFFFFF, ht), sm);
			round(ctx, tx, ty, tx + sw, ty + sh, 0xFF000000 | t.base, sm);
			round(ctx, tx + 3, ty + 3, tx + sw / 2 + 1, ty + 6, 0xFF000000 | t.strong, CORNERS_1);
			round(ctx, tx + 3, ty + sh - 7, tx + sw - 3, ty + sh - 3, 0xFF000000 | t.accent, CORNERS_1);
			hits.add(new Hit(tx, ty, tx + sw, ty + sh, () -> {
				config.theme = t.name();
				config.save();
			}, null, t.label, null));
		}
		y += ((themes.length + perRow - 1) / perRow) * (sh + sg) + settingsGap() - sg;

		// accent: the theme's own, or any of these
		int accentNow = accentRgb();
		String accentName = "From theme";
		for (int i = 0; i < Theme.ACCENTS.length; i++) if (Theme.ACCENTS[i] == accentNow) accentName = Theme.ACCENT_NAMES[i];
		if (accentNow >= 0 && accentName.equals("From theme")) accentName = "#" + Theme.accentHex(accentNow);
		y = section(ctx, "Accent", accentName, ix1, ix2, y);
		int dots = Theme.ACCENTS.length + 1;
		int dperRow = 7;
		int ds = Math.max(12, Math.min(16, (iw - 4 * (dperRow - 1)) / dperRow));
		int dg = (iw - ds * dperRow) / (dperRow - 1);
		for (int i = 0; i < dots; i++) {
			int dx = ix1 + (i % dperRow) * (ds + dg);
			int dy = y + (i / dperRow) * (ds + 5);
			boolean auto = i == 0;
			int rgb = auto ? theme().accent : Theme.ACCENTS[i - 1];
			boolean on = auto ? accentNow < 0 : accentNow == rgb;
			boolean hover = hot(mouseX, mouseY, dx, dy, dx + ds, dy + ds);
			float ht = anim.fade(Anim.key(0x7510, i), hover && !on, 16f);
			int[] pill = shapePill(ds);
			if (on) box(ctx, dx - 2, dy - 2, dx + ds + 2, dy + ds + 2, 0, WHITE, shapePill(ds + 4));
			else if (ht > 0.02f) box(ctx, dx - 2, dy - 2, dx + ds + 2, dy + ds + 2, 0, Anim.mix(0, 0x99FFFFFF, ht), shapePill(ds + 4));
			if (auto) {
				box(ctx, dx, dy, dx + ds, dy + ds, pal.surface(), 0xFF000000 | rgb, pill);
				boldCentered(ctx, "A", dx, dx + ds, dy + (ds - 8) / 2, 0xFF000000 | rgb);
			} else {
				round(ctx, dx, dy, dx + ds, dy + ds, 0xFF000000 | rgb, pill);
			}
			final int pick = rgb;
			hits.add(new Hit(dx, dy, dx + ds, dy + ds, () -> {
				config.accent = auto ? null : Theme.accentHex(pick);
				config.save();
			}, null, auto ? "The theme's own accent (" + theme().label + ")" : Theme.ACCENT_NAMES[i - 1], null));
		}
		y += ((dots + dperRow - 1) / dperRow) * (ds + 5) + settingsGap() - 5;

		// opacity
		y = section(ctx, "Window", null, ix1, ix2, y);
		y = slider(ctx, "Opacity", "%", 0.4f, 1f, 0.05f, 0.78f, () -> config.opacity, v -> {
			config.opacity = v;
			config.save();
		}, ix1, ix2, y, mouseX, mouseY, 100f);
		y += 3;
		text(ctx, "Corners", ix1, y + 2, TEXT2);
		String[] corners = {"Sharp", "Soft", "Round"};
		int cw = Math.min(iw - textW("Corners") - 8, 3 * 44);
		segmented(ctx, 0x7620, ix2 - cw, y - 1, cw, ctl, corners, null, null, config.corners, mouseX, mouseY, i -> {
			config.corners = i;
			config.save();
		}, new String[]{"Square corners", "Slightly rounded corners", "Rounded corners"});
		y += ctl + settingsGap();

		// fonts: each name is shown in its own font
		y = section(ctx, "Text font", menuFont().label, ix1, ix2, y);
		MenuFont[] fonts = MenuFont.values();
		int fg = 4;
		int fw = (iw - fg) / 2;
		int fh = compact ? 16 : 18;
		MenuFont currentFont = menuFont();
		for (int i = 0; i < fonts.length; i++) {
			MenuFont f = fonts[i];
			int fx = ix1 + (i % 2) * (fw + fg);
			int fy = y + (i / 2) * (fh + fg);
			boolean on = f == currentFont;
			boolean hover = hot(mouseX, mouseY, fx, fy, fx + fw, fy + fh);
			float ht = anim.fade(Anim.key(0x7520, i), hover && !on, 16f);
			float st = anim.fade(Anim.key(0x7521, i), on, 16f);
			box(ctx, fx, fy, fx + fw, fy + fh, Anim.mix(Anim.mix(pal.surface(), pal.surfaceHover(), ht), pal.soft(), st),
				Anim.mix(Anim.mix(pal.surfaceBorder(), pal.surfaceBorderHover(), ht), pal.accent(), st), sm);
			MutableText label = styledIn(f.label, f, false);
			int lw = textRenderer.getWidth(label);
			if (lw > fw - 6) {
				label = styledIn(f.label.substring(0, Math.min(f.label.length(), 9)) + ".", f, false);
				lw = textRenderer.getWidth(label);
			}
			ctx.drawText(textRenderer, label, fx + (fw - lw) / 2, fy + (fh - 8) / 2, on ? WHITE : Anim.mix(TEXT2, WHITE, ht), false);
			hits.add(new Hit(fx, fy, fx + fw, fy + fh, () -> {
				config.setFont(f.id);
				config.save();
			}, null, f.label, null));
		}
		y += ((fonts.length + 1) / 2) * (fh + fg) - fg + 4;
		return y;
	}

	private int settingsLayout(DrawContext ctx, int ix1, int ix2, int y, int mouseX, int mouseY) {
		int iw = ix2 - ix1;
		int sg = settingsGap();

		y = section(ctx, "Menu size", null, ix1, ix2, y);
		segmented(ctx, 0x762F, ix1, y, iw, ctl, new String[]{"Game", "1", "2", "3", "4", "Max"}, null, null, config.menuScale,
			mouseX, mouseY, i -> {
				if (config.menuScale == i) return;
				config.menuScale = i;
				config.save();
				clearAndInit(); // lay the menu out again at the new scale
			}, new String[]{"Same GUI scale as the game", "GUI scale 1: smallest", "GUI scale 2", "GUI scale 3",
				"GUI scale 4", "The biggest that fits the window"});
		y += ctl + 4;
		y = smallWrapped(ctx, "The menu's own GUI scale. Your game's GUI scale doesn't change; sizes that don't fit the"
			+ " window use the biggest that does.", ix1, y, iw, MUTED) + sg - 4;

		y = section(ctx, "Spacing", null, ix1, ix2, y);
		segmented(ctx, 0x7630, ix1, y, iw, ctl, new String[]{"Auto", "Compact", "Roomy"}, null, null, config.density, mouseX, mouseY, i -> {
			config.density = i;
			config.save();
		}, new String[]{"Tighter on small screens", "Always tight: more fits on the screen", "Always airy: bigger buttons and rows"});
		y += ctl + 4;
		y = smallWrapped(ctx, "Auto uses compact spacing when the screen is small (GUI scale 4 on a 1080p screen is).", ix1, y, iw, MUTED) + sg - 4;

		y = section(ctx, "Style cards", null, ix1, ix2, y);
		segmented(ctx, 0x7631, ix1, y, iw, ctl, new String[]{"Small", "Medium", "Large"}, null, null, config.cardSize, mouseX, mouseY, i -> {
			config.cardSize = i;
			config.save();
		}, new String[]{"Smaller cards: more at once", "Size of the style cards", "Bigger cards: easier to see"});
		y += ctl + sg;

		y = section(ctx, "Category list", null, ix1, ix2, y);
		segmented(ctx, 0x7632, ix1, y, iw, ctl, new String[]{"Auto", "Icons", "Names"}, null, null, config.sidebar, mouseX, mouseY, i -> {
			config.sidebar = i;
			config.save();
		}, new String[]{"Names when there is room", "Only icons: leaves more room for the cards", "Always show the names"});
		y += ctl + sg;

		y = section(ctx, "Side panel", null, ix1, ix2, y);
		segmented(ctx, 0x7633, ix1, y, iw, ctl, new String[]{"Narrow", "Normal", "Wide"}, null, null, config.panelWidth, mouseX, mouseY, i -> {
			config.panelWidth = i;
			config.save();
		}, new String[]{"Less room for the sliders", "Width of the preview and sliders", "More room for the sliders"});
		y += ctl + sg;

		y = section(ctx, "Motion", null, ix1, ix2, y);
		y = switchRow(ctx, "Animations", "Fades, sliding highlights, smooth scrolling", config.animations, ix1, ix2, y, mouseX, mouseY, () -> {
			config.animations = !config.animations;
			config.save();
		}, "Turn off for a menu that changes at once");
		return y;
	}

	private int settingsGeneral(DrawContext ctx, int ix1, int ix2, int y, int mouseX, int mouseY) {
		int iw = ix2 - ix1;
		int sg = settingsGap();

		y = section(ctx, "Behaviour", null, ix1, ix2, y);
		y = switchRow(ctx, "Check for updates", "Looks on GitHub once per game", config.checkUpdates, ix1, ix2, y, mouseX, mouseY, () -> {
			config.checkUpdates = !config.checkUpdates;
			config.save();
		}, "Look for a newer Item Studio on GitHub once per game session");
		y = switchRow(ctx, "Tooltips", "Hints when the mouse rests on something", config.tooltips, ix1, ix2, y, mouseX, mouseY, () -> {
			config.tooltips = !config.tooltips;
			config.save();
		}, "Turn the little hint boxes on or off");
		dev.itemstudio.UpdateCheck.Update update = dev.itemstudio.UpdateCheck.found();
		if (update != null) {
			int rh = ctl;
			button(ctx, ix1, y + 2, iw, rh, "Get " + update.version(), Icons.EXPORT, BTN_SOFT, mouseX, mouseY, () -> {
				java.net.URI releasePage = java.net.URI.create(update.url());
				Util.getOperatingSystem().open(releasePage);
			}, "Item Studio " + update.version() + " is out (you have " + versionText() + "). Opens the download page.");
			y += rh + 6;
		}
		y += sg - 2;

		y = section(ctx, "Keys", null, ix1, ix2, y);
		y = keyRow(ctx, "Open menu", ItemStudioClient.openKey(), ix1, ix2, y, mouseX, mouseY);
		y = keyRow(ctx, "Turn on/off", ItemStudioClient.toggleKey(), ix1, ix2, y, mouseX, mouseY);
		y = keyRow(ctx, "Next profile", ItemStudioClient.nextProfileKey(), ix1, ix2, y, mouseX, mouseY);
		y = smallWrapped(ctx, listening != null ? "Press a key now. Esc clears it. Click anywhere to cancel."
			: "Click a key, then press the new one. Esc clears it. Middle and side mouse buttons work too.", ix1, y + 1, iw, MUTED) + sg - 2;

		y = section(ctx, "Shortcuts", null, ix1, ix2, y);
		String[][] shortcuts = {{"Ctrl+Z", "Undo"}, {"Ctrl+F", "Search every item"}, {"Ctrl+S", "Apply"}, {"Esc", "Close"}};
		int keyW = 0;
		for (String[] s : shortcuts) keyW = Math.max(keyW, smallW(s[0]));
		keyW += 8;
		for (String[] s : shortcuts) {
			int rowH = smallH() + 4;
			round(ctx, ix1, y, ix1 + keyW, y + rowH, pal.surface(), shapeSm());
			small(ctx, s[0], ix1 + 4, y + 2, TEXT2);
			small(ctx, smallTrim(s[1], iw - keyW - 6), ix1 + keyW + 6, y + 2, MUTED);
			y += rowH + 3;
		}
		y = smallWrapped(ctx, "Right-click a style card to star it, or a slider to put it back.", ix1, y + 1, iw, MUTED);
		y += sg - 3;

		y = section(ctx, "About", null, ix1, ix2, y);
		text(ctx, "Item Studio " + versionText(), ix1, y, TEXT2);
		y += 12;
		y = smallWrapped(ctx, "Everything here is saved by itself. Your picks live in config/itemstudio.json.", ix1, y, iw, MUTED);
		return y;
	}

	/** One key setting: its name and a button showing the key; click it, then press the new key. */
	private int keyRow(DrawContext ctx, String label, net.minecraft.client.option.KeyBinding key, int ix1, int ix2, int y,
	                   int mouseX, int mouseY) {
		int iw = ix2 - ix1;
		int h = ctl;
		boolean waiting = listening == key;
		String name = key.getBoundKeyLocalizedText().getString();
		String clash = waiting ? null : clashWith(key);
		String shown = waiting ? "Press a key..." : key.isUnbound() ? "None" : name;
		int bw = Math.max(54, Math.min(iw - textW(label) - 10, textW(shown) + 16));
		int bx = ix2 - bw;
		boolean hover = hot(mouseX, mouseY, bx, y, ix2, y + h);
		float ht = anim.fade(fadeKey(label, ix1), hover, 16f);
		float pulse = waiting ? (float) (0.5 + 0.5 * Math.sin(clock * 6)) : 0f;
		int fillColor = waiting ? pal.soft() : Anim.mix(pal.surface(), pal.surfaceHover(), ht);
		int border = waiting ? Anim.mix(pal.accent() & 0x80FFFFFF, pal.accent(), pulse)
			: clash != null ? 0xFFE5646E : Anim.mix(pal.surfaceBorder(), pal.surfaceBorderHover(), ht);
		text(ctx, trim(label, iw - bw - 8), ix1, y + (h - 8) / 2, TEXT2);
		box(ctx, bx, y, ix2, y + h, fillColor, border, shapeSm());
		String t = trim(shown, bw - 8);
		textCentered(ctx, t, bx, ix2, y + (h - 8) / 2, waiting ? WARN : clash != null ? 0xFFFF8C94 : key.isUnbound() ? MUTED : WHITE);
		hits.add(new Hit(bx, y, ix2, y + h, () -> listening = waiting ? null : key, null,
			clash != null ? "Also used by: " + clash : "Click, then press the key you want", null));
		return y + h + 4;
	}

	/** Name of another control on the same key, or null. */
	private String clashWith(net.minecraft.client.option.KeyBinding key) {
		if (key.isUnbound()) return null;
		for (net.minecraft.client.option.KeyBinding other : client.options.allKeys) {
			if (other != key && key.equals(other)) {
				return Text.translatable(other.getId()).getString();
			}
		}
		return null;
	}

	private void bindListening(net.minecraft.client.util.InputUtil.Key key) {
		if (listening == null) return;
		listening.setBoundKey(key);
		net.minecraft.client.option.KeyBinding.updateKeysByCode();
		client.options.write();
		listening = null;
	}

	// ------------------------------------------------------------------ profiles

	private void drawProfiles(DrawContext ctx, int mouseX, int mouseY) {
		// a click anywhere outside the list closes it
		hits.add(new Hit(0, 0, width, height, this::closeProfiles, this::closeProfiles, null, null));
		// the rest of the menu steps back a little
		fill(ctx, px + 1, bodyY, px + pw - 1, py + ph - 1, 0x38000000);
		List<String> names = new ArrayList<>(config.profiles.keySet());
		int w = compact ? 196 : 212, rowH = compact ? 16 : 18;
		int x = Math.min(profileChipX, px + pw - w - 4), y = bodyY + 3;
		int bh = ctl - 2;
		int hintH = 2 * smallH() + 2;
		int fixed = 26 + 8 + 4 * (bh + 4) + hintH + 8;
		int room = Math.max(rowH * 2, (height - 6 - y - fixed) / rowH * rowH);
		int listH = Math.min(names.size() * rowH, room);
		int h = fixed + listH;
		int[] lg = shapeLg();
		// a soft shadow, then the opaque pop-up
		box(ctx, x + 2, y + 4, x + w + 2, y + h + 4, 0x44000000, 0, lg);
		box(ctx, x, y, x + w, y + h, 0xFF000000 | Theme.mix(theme().base, 0xFFFFFF, 0.07f), pal.surfaceBorderHover(), lg);
		hits.add(new Hit(x, y, x + w, y + h, () -> { }, () -> { }, null, null)); // clicks on empty parts do nothing
		heading(ctx, "Profiles", x + 10, y + 8, TEXT);
		String count = names.size() + " saved";
		small(ctx, count, x + w - 10 - smallW(count), y + 9, MUTED);
		int listTop = y + 26, listBottom = listTop + listH;
		int content = names.size() * rowH;
		int listOff = scrollOf("profiles", content, listH);
		scrollAreas.add(new ScrollArea("profiles", x, listTop, x + w, listBottom, Math.max(0, content - listH)));
		ctx.enableScissor(x + 1, listTop, x + w - 1, listBottom);
		int ry = listTop - listOff;
		int[] sm = shapeSm();
		for (String name : names) {
			boolean active = name.equals(config.activeProfile);
			boolean hover = inside(mouseX, mouseY, x + 5, Math.max(ry, listTop), x + w - 5, Math.min(ry + rowH, listBottom));
			float ht = anim.fade(Anim.key(0x7700, name.hashCode()), hover && !active, 16f);
			if (active) round(ctx, x + 5, ry, x + w - 5, ry + rowH - 1, pal.soft(), sm);
			else if (ht > 0.02f) round(ctx, x + 5, ry, x + w - 5, ry + rowH - 1, Anim.mix(0, pal.surfaceHover(), ht), sm);
			if (active) icon(ctx, Icons.CHECK, x + 10, ry + (rowH - Icons.CHECK.h) / 2 - 1, pal.accentText());
			StudioConfig.Profile saved = config.profiles.get(name);
			int n = active ? config.currentCount() : saved == null ? 0 : saved.count();
			String changes = n == 0 ? "empty" : n + " changed";
			int cw = smallW(changes);
			int textY = ry + (rowH - 8) / 2 - 1;
			if (active && renamingProfile) {
				profileName.setX(x + 24);
				profileName.setY(textY);
				profileName.setWidth(w - 36);
				box(ctx, x + 21, ry, x + w - 8, ry + rowH - 1, pal.surface(), pal.accent(), sm);
				renameRowShown = ry >= listTop && ry + rowH <= listBottom;
			} else {
				text(ctx, trim(name, w - 40 - cw), x + 24, textY, active ? WHITE : Anim.mix(TEXT2, WHITE, ht));
				small(ctx, changes, x + w - 11 - cw, ry + (rowH - smallH()) / 2 - 1 + (pixelFont() ? 0 : 1), active ? pal.accentText() : MUTED);
			}
			final String target = name;
			if (!active) {
				addHit(x + 5, ry, x + w - 5, ry + rowH, listTop, listBottom, () -> switchProfile(target),
					"Switch to " + name + " (applies right away)");
			}
			ry += rowH;
		}
		ctx.disableScissor();
		scrollBar(ctx, x + w - 1, listTop, listBottom, listOff, Math.max(0, content - listH), false);
		ry = listBottom;
		fill(ctx, x + 8, ry + 3, x + w - 8, ry + 4, pal.line2());
		ry += 8;
		int bw = (w - 24) / 2;
		int b2 = x + 10 + bw + 4;
		button(ctx, x + 10, ry, bw, bh, "New", Icons.PLUS, BTN_NORMAL, mouseX, mouseY, () -> newProfile(false), "Start an empty profile");
		button(ctx, b2, ry, bw, bh, "Duplicate", Icons.COPY, BTN_NORMAL, mouseX, mouseY, () -> newProfile(true), "Copy this profile");
		ry += bh + 4;
		button(ctx, x + 10, ry, bw, bh, "Rename", Icons.PENCIL, BTN_NORMAL, mouseX, mouseY, this::startRename, "Rename this profile");
		boolean armed = System.currentTimeMillis() < deleteArmedUntil;
		button(ctx, b2, ry, bw, bh, armed ? "Sure?" : "Delete", Icons.TRASH, armed ? BTN_DANGER : BTN_NORMAL, mouseX, mouseY, this::deleteProfile,
			names.size() > 1 ? "Delete this profile" : "You need at least one profile");
		ry += bh + 4;
		button(ctx, x + 10, ry, bw, bh, "Copy code", Icons.COPY, BTN_NORMAL, mouseX, mouseY, this::copyProfileCode,
			"Copy a code for this profile to send to a friend");
		button(ctx, b2, ry, bw, bh, "Paste code", Icons.PASTE, BTN_NORMAL, mouseX, mouseY, this::pasteProfileCode,
			"Add a profile from a code you copied");
		ry += bh + 4;
		button(ctx, x + 10, ry, w - 20, bh, "Export as pack", Icons.EXPORT, BTN_SOFT, mouseX, mouseY, this::exportPack,
			"Save this profile as a normal resource pack zip that works without the mod");
		ry += bh + 6;
		smallWrapped(ctx, "Switch in game with the Next profile key (set it in Settings > General).", x + 10, ry, w - 20, MUTED);
	}

	private void exportPack() {
		if (!ready()) {
			setStatus("Still reading your packs...", WARN);
			return;
		}
		config.cleanUp();
		PackIndex idx = indexNow();
		StudioConfig choices = config.copyForBuild();
		String name = config.activeProfile;
		setStatus("Exporting " + name + "...", MUTED);
		CompletableFuture.supplyAsync(() -> {
			PackBuilder builder = new PackBuilder(choices, idx).build();
			return builder.files().isEmpty() ? null : java.util.Optional.ofNullable(PackWriter.exportZip(builder.files(), name));
		}, dev.itemstudio.Workers.BUILD).whenCompleteAsync((zip, error) -> {
			if (error == null && zip == null) {
				setStatus("Nothing to export - " + name + " doesn't change anything", WARN);
			} else if (error != null || zip.isEmpty()) {
				setStatus("Could not export - see the game log", WARN);
			} else {
				setStatus("Exported " + zip.get().getFileName() + " - works without the mod", GOOD);
				java.nio.file.Path exportDir = PackWriter.exportFolder();
				Util.getOperatingSystem().open(exportDir);
			}
		}, gameThread());
	}

	private void closeProfiles() {
		if (renamingProfile) commitRename();
		profilesOpen = false;
	}

	/** Saves the current choices into the profile being left, loads the other one and applies it. */
	private void switchProfile(String name) {
		if (!config.profiles.containsKey(name) || name.equals(config.activeProfile)) return;
		if (renamingProfile) commitRename();
		config.storeActive();
		config.loadProfile(name);
		config.save();
		history.clear();
		pushHistory();
		if (applyNote == null) {
			applyNote = "Switched to " + name;
			applyNoteColor = GOOD;
		}
		apply();
	}

	private void newProfile(boolean copy) {
		if (renamingProfile) commitRename();
		config.storeActive();
		String name = config.uniqueProfileName(copy ? config.activeProfile + " copy" : "Profile " + (config.profiles.size() + 1));
		if (name.length() > 24) name = config.uniqueProfileName("Profile");
		if (copy) {
			// same choices, so nothing needs rebuilding
			config.profiles.put(name, config.currentProfile());
			config.activeProfile = name;
			config.save();
			setStatus("Made " + name, GOOD);
		} else {
			config.profiles.put(name, new StudioConfig.Profile());
			switchProfile(name);
		}
		startRename();
	}

	private void startRename() {
		int at = new ArrayList<>(config.profiles.keySet()).indexOf(config.activeProfile);
		if (at >= 0) scroll.put("profiles", Math.max(0, at * 14 - 14));
		renamingProfile = true;
		profileName.setText(config.activeProfile);
		setFocused(profileName);
		profileName.setFocused(true);
	}

	private void commitRename() {
		renamingProfile = false;
		profileName.setFocused(false);
		String name = profileName.getText().trim();
		if (name.isEmpty() || name.equals(config.activeProfile)) return;
		if (config.profiles.containsKey(name)) {
			setStatus("There's already a profile called " + name, WARN);
			return;
		}
		config.renameProfile(config.activeProfile, name);
		config.save();
	}

	private void deleteProfile() {
		if (renamingProfile) commitRename();
		if (config.profiles.size() <= 1) {
			setStatus("Keep at least one profile", WARN);
			return;
		}
		long now = System.currentTimeMillis();
		if (now > deleteArmedUntil) {
			deleteArmedUntil = now + 3000;
			setStatus("Click Delete again to remove " + config.activeProfile, WARN);
			return;
		}
		deleteArmedUntil = 0;
		String gone = config.activeProfile;
		config.profiles.remove(gone);
		String next = config.profiles.keySet().iterator().next();
		config.loadProfile(next);
		config.save();
		history.clear();
		pushHistory();
		applyNote = "Deleted " + gone + ", now using " + next;
		applyNoteColor = GOOD;
		apply();
	}

	private void copyProfileCode() {
		String code = dev.itemstudio.ProfileCodes.encode(config.activeProfile, config.currentProfile());
		client.keyboard.setClipboard(code);
		setStatus("Code for " + config.activeProfile + " copied - paste it to a friend", GOOD);
	}

	private void pasteProfileCode() {
		dev.itemstudio.ProfileCodes.Decoded decoded = dev.itemstudio.ProfileCodes.decode(client.keyboard.getClipboard());
		if (decoded == null) {
			setStatus("No profile code on your clipboard", WARN);
			return;
		}
		List<String> missing = new ArrayList<>();
		if (index != null) {
			for (String pack : new java.util.TreeSet<>(decoded.profile().styles.values())) {
				if (!VanillaSource.ID.equals(pack) && index.get(pack) == null) missing.add(pack);
			}
		}
		config.storeActive();
		String name = config.uniqueProfileName(decoded.name());
		config.profiles.put(name, decoded.profile());
		if (missing.isEmpty()) {
			applyNote = "Added " + name + " from the code";
			applyNoteColor = GOOD;
		} else {
			applyNote = "Added " + name + " - " + missing.size() + " pack" + (missing.size() == 1 ? "" : "s")
				+ " not in your folder: " + String.join(", ", missing);
			applyNoteColor = WARN;
		}
		switchProfile(name);
	}

	private int pendingTextFor = -1;
	private String pendingText;

	private void drawFooter(DrawContext ctx, int mouseX, int mouseY) {
		bandBottom(ctx, px, footerY, px + pw, py + ph, pal.header(), shapeLg());
		fill(ctx, px + 1, footerY, px + pw - 1, footerY + 1, pal.line());
		int y = footerY + (footerH - ctl) / 2 + 0;
		int textY = y + (ctl - 8) / 2;
		int right = px + pw - 8;

		// Apply: the main action. A pulse around it while changes are waiting.
		String applyLabel = "Apply changes";
		if (pending != pendingTextFor) {
			pendingTextFor = pending;
			pendingText = pending > 0 ? String.valueOf(pending) : null;
		}
		int pillW = pendingText != null ? textW(pendingText) + 9 : 0;
		int aw = boldW(applyLabel) + 18 + (pending > 0 ? pillW + 6 : 0);
		right -= aw;
		boolean aHover = hot(mouseX, mouseY, right, y, right + aw, y + ctl);
		boolean aDown = pressed(right, y, right + aw, y + ctl);
		float at = anim.fade(Anim.key(0x7800, 0), aHover, 16f);
		if (pending > 0 && !applying) {
			float pulse = config.animations ? (float) (0.5 + 0.5 * Math.sin(clock * 4)) : 0.6f;
			box(ctx, right - 2, y - 2, right + aw + 2, y + ctl + 2, 0, Theme.argb(pal.accent() & 0xFFFFFF, 0.15f + 0.35f * pulse), shapeSm());
		}
		round(ctx, right, y, right + aw, y + ctl, aDown ? pal.strongPressed() : Anim.mix(pal.strong(), pal.strongHover(), at), shapeSm());
		bold(ctx, applyLabel, right + 9, textY + (aDown ? 1 : 0), WHITE);
		if (pendingText != null) {
			int pxl = right + 9 + boldW(applyLabel) + 6;
			round(ctx, pxl, y + (ctl - 12) / 2, pxl + pillW, y + (ctl - 12) / 2 + 12, 0x40FFFFFF, shapePill(12));
			text(ctx, pendingText, pxl + 4, textY, WHITE);
		}
		hits.add(new Hit(right, y, right + aw, y + ctl, this::apply, null, "Build the pack and reload textures (Ctrl+S)", null));
		right -= 10;

		// auto-apply switch
		boolean short1 = pw < 540;
		String auto = short1 ? "Auto" : "Auto-apply";
		int sw = 20 + 6 + textW(auto);
		right -= sw;
		boolean autoHover = hot(mouseX, mouseY, right - 3, y, right + sw + 3, y + ctl);
		float tt = anim.fade(Anim.key(0x7801, 0), autoHover, 16f);
		round(ctx, right - 4, y, right + sw + 3, y + ctl, Anim.mix(0, pal.surface(), tt), shapeSm());
		drawSwitch(ctx, right, y + (ctl - 10) / 2, config.applyInstantly, Anim.key(0x7802, 0));
		text(ctx, auto, right + 26, textY, Anim.mix(TEXT2, WHITE, tt));
		hits.add(new Hit(right - 4, y, right + sw + 3, y + ctl, () -> {
			config.applyInstantly = !config.applyInstantly;
			config.save();
			if (config.applyInstantly && pending > 0) apply();
		}, null, "Apply every change right away (each one reloads textures)", null));
		right -= 10;
		fill(ctx, right, y + 3, right + 1, y + ctl - 3, pal.line2());
		right -= 8;

		// Undo, rescan and the folder: names when there is room, icons when there isn't
		boolean names = pw >= 640;
		String[] labels = {"Undo", "Rescan", names && pw >= 720 ? "Packs folder" : "Folder"};
		Icons[] icons = {Icons.UNDO, Icons.REFRESH, Icons.FOLDER};
		Runnable[] actions = {this::undo, this::rescan, () -> Util.getOperatingSystem().open(client.getResourcePackDir())};
		String[] tips = {"Undo the last change (Ctrl+Z)", "Look for new packs in the resourcepacks folder", "Open your resourcepacks folder"};
		for (int i = 0; i < 3; i++) {
			int bw = names ? buttonW(labels[i], icons[i]) : ctl;
			right -= bw;
			button(ctx, right, y, bw, ctl, names ? labels[i] : null, icons[i], BTN_FLAT, mouseX, mouseY, actions[i], tips[i]);
			right -= 2;
		}

		// status
		int sx = px + 12;
		boolean busy = applying || (loading != null && !loading.isDone());
		int dot = statusColor == WARN ? WARN : statusColor == GOOD ? GOOD : MUTED;
		if (busy) dot = Anim.mix(MUTED, pal.accentText(), (float) (0.5 + 0.5 * Math.sin(clock * 7)));
		round(ctx, sx, y + (ctl - 6) / 2, sx + 6, y + (ctl - 6) / 2 + 6, dot, shapePill(6));
		String found = index != null ? "  -  " + index.packs().size() + " packs found" : "";
		int room = right - sx - 18;
		if (room > 20) {
			String st = trim(status, room);
			text(ctx, st, sx + 12, textY, statusColor == MUTED ? TEXT2 : statusColor);
			int used = textW(st);
			if (!found.isEmpty() && used + textW(found) < room) {
				text(ctx, found, sx + 12 + used, textY, MUTED);
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
		String own = "assets/minecraft/textures/item/" + Identifier.of(info.id).getPath() + ".png";
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
	private boolean drawPreview(DrawContext ctx, String key, Supplier<byte[]> bytes, int x, int y, int size) {
		PreviewTextures.Preview p = previews.get(key, bytes);
		if (p == null) return false;
		// Whole-number scales keep pixel art sharp; only pictures bigger than the box are shrunk.
		if (p.width() > p.height()) {
			// wide picture (hotbar, XP bar, buttons): show all of it, centred
			int k = size / p.width();
			int w = k >= 1 ? p.width() * k : size;
			int h = k >= 1 ? p.height() * k : Math.max(1, size * p.height() / p.width());
			ctx.drawTexture(RenderPipelines.GUI_TEXTURED, p.id(), x + (size - w) / 2, y + (size - h) / 2, 0f, 0f, w, h,
				p.width(), p.height(), p.width(), p.height());
			return true;
		}
		// square or tall (animated strip): show the first square frame
		int square = p.width();
		int k = size / square;
		int d = k >= 1 ? square * k : size;
		ctx.drawTexture(RenderPipelines.GUI_TEXTURED, p.id(), x + (size - d) / 2, y + (size - d) / 2, 0f, 0f, d, d,
			square, square, p.width(), p.height());
		return true;
	}

	// ------------------------------------------------------------------ small drawing helpers

	private static final int[] CORNERS_NONE = {};
	/** Rows cut from the top and bottom, as how many pixels each row is pulled in from the sides. */
	private static final int[] CORNERS_1 = {1};
	private static final int[] CORNERS_2 = {2, 1};
	private static final int[] CORNERS_3 = {3, 1, 1};

	/** Corners of buttons, chips and inputs. */
	private int[] shapeSm() {
		return config.corners == 0 ? CORNERS_NONE : config.corners == 1 ? CORNERS_1 : CORNERS_2;
	}

	/** Corners of cards, panels and pop-ups: one step rounder than the small ones. */
	private int[] shapeLg() {
		return config.corners == 0 ? CORNERS_NONE : config.corners == 1 ? CORNERS_2 : CORNERS_3;
	}

	/** Fully rounded ends for switches, slider knobs and tracks of this height. */
	private int[] shapePill(int h) {
		if (config.corners == 0) return CORNERS_NONE;
		return h <= 5 ? CORNERS_1 : h <= 9 ? CORNERS_2 : CORNERS_3;
	}

	private static void fill(DrawContext ctx, int x1, int y1, int x2, int y2, int color) {
		if (x2 > x1 && y2 > y1 && (color >>> 24) != 0) ctx.fill(x1, y1, x2, y2, color);
	}

	/** Filled rectangle with rounded corners, drawn without overlapping fills so see-through colours stay even. */
	private static void round(DrawContext ctx, int x1, int y1, int x2, int y2, int color, int[] shape) {
		int k = shape.length;
		if ((color >>> 24) == 0) return;
		if (k == 0 || x2 - x1 < 2 * k + 1 || y2 - y1 < 2 * k + 1) {
			fill(ctx, x1, y1, x2, y2, color);
			return;
		}
		int r = 0;
		while (r < k) {
			// rows that are pulled in by the same amount are one fill
			int end = r + 1;
			while (end < k && shape[end] == shape[r]) end++;
			fill(ctx, x1 + shape[r], y1 + r, x2 - shape[r], y1 + end, color);
			fill(ctx, x1 + shape[r], y2 - end, x2 - shape[r], y2 - r, color);
			r = end;
		}
		fill(ctx, x1, y1 + k, x2, y2 - k, color);
	}

	/**
	 * Rounded box with a 1px border. The border and the inside never overlap, so a see-through fill and a
	 * see-through border each keep their own colour. Pass 0 for either to leave it out.
	 */
	private static void box(DrawContext ctx, int x1, int y1, int x2, int y2, int fillColor, int border, int[] shape) {
		int k = shape.length;
		int w = x2 - x1, h = y2 - y1;
		if (k == 0 || w < 2 * k + 3 || h < 2 * k + 3) {
			fill(ctx, x1, y1, x2, y1 + 1, border);
			fill(ctx, x1, y2 - 1, x2, y2, border);
			fill(ctx, x1, y1 + 1, x1 + 1, y2 - 1, border);
			fill(ctx, x2 - 1, y1 + 1, x2, y2 - 1, border);
			fill(ctx, x1 + 1, y1 + 1, x2 - 1, y2 - 1, fillColor);
			return;
		}
		int in0 = shape[0];
		fill(ctx, x1 + in0, y1, x2 - in0, y1 + 1, border);
		fill(ctx, x1 + in0, y2 - 1, x2 - in0, y2, border);
		for (int r = 1; r < k; r++) {
			int in = shape[r];
			int s = Math.max(in + 1, shape[r - 1]);
			fill(ctx, x1 + in, y1 + r, x1 + s, y1 + r + 1, border);
			fill(ctx, x2 - s, y1 + r, x2 - in, y1 + r + 1, border);
			fill(ctx, x1 + s, y1 + r, x2 - s, y1 + r + 1, fillColor);
			fill(ctx, x1 + in, y2 - r - 1, x1 + s, y2 - r, border);
			fill(ctx, x2 - s, y2 - r - 1, x2 - in, y2 - r, border);
			fill(ctx, x1 + s, y2 - r - 1, x2 - s, y2 - r, fillColor);
		}
		int top = Math.max(k, 1);
		fill(ctx, x1, y1 + top, x1 + 1, y2 - top, border);
		fill(ctx, x2 - 1, y1 + top, x2, y2 - top, border);
		fill(ctx, x1 + 1, y1 + top, x2 - 1, y2 - top, fillColor);
	}

	/** A band across the top of a rounded box (the header), inside its 1px border. */
	private static void bandTop(DrawContext ctx, int x1, int y1, int x2, int y2, int color, int[] shape) {
		int k = shape.length;
		for (int r = 1; r < k && y1 + r < y2; r++) {
			int s = Math.max(shape[r] + 1, shape[r - 1]);
			fill(ctx, x1 + s, y1 + r, x2 - s, y1 + r + 1, color);
		}
		fill(ctx, x1 + 1, y1 + Math.max(k, 1), x2 - 1, y2, color);
	}

	/** A band along the bottom of a rounded box (the footer), inside its 1px border. */
	private static void bandBottom(DrawContext ctx, int x1, int y1, int x2, int y2, int color, int[] shape) {
		int k = shape.length;
		for (int r = 1; r < k && y2 - r - 1 >= y1; r++) {
			int s = Math.max(shape[r] + 1, shape[r - 1]);
			fill(ctx, x1 + s, y2 - r - 1, x2 - s, y2 - r, color);
		}
		fill(ctx, x1 + 1, y1, x2 - 1, y2 - Math.max(k, 1), color);
	}

	/** A soft shadow under something that floats (a hovered card, a pop-up). */
	private static void dropShadow(DrawContext ctx, int x1, int y1, int x2, int y2, int strength) {
		int a1 = Math.min(255, strength), a2 = Math.min(255, strength / 2);
		fill(ctx, x1 + 2, y2, x2 - 2, y2 + 1, a1 << 24);
		fill(ctx, x1 + 3, y2 + 1, x2 - 3, y2 + 2, a2 << 24);
	}

	private static boolean inside(double x, double y, int x1, int y1, int x2, int y2) {
		return x >= x1 && x < x2 && y >= y1 && y < y2;
	}

	/** Under the pop-up the menu doesn't react to the mouse: set while everything below the profile list is drawn. */
	private boolean hoverBlocked;

	/** The mouse is over this area and nothing (a drag, the profile list) takes the hover away. */
	private boolean hot(double mouseX, double mouseY, int x1, int y1, int x2, int y2) {
		return !hoverBlocked && dragging == null && inside(mouseX, mouseY, x1, y1, x2, y2);
	}

	/** Draws a pixel icon with its top-left corner at x, y. */
	private static void icon(DrawContext ctx, Icons ic, int x, int y, int color) {
		int[] r = ic.rects;
		for (int i = 0; i < r.length; i += 4) fill(ctx, x + r[i], y + r[i + 1], x + r[i + 2], y + r[i + 3], color);
	}

	/** An icon centred in a box. */
	private static void iconIn(DrawContext ctx, Icons ic, int x1, int y1, int x2, int y2, int color) {
		icon(ctx, ic, x1 + (x2 - x1 - ic.w) / 2, y1 + (y2 - y1 - ic.h) / 2, color);
	}

	private int fadeKey(String label, int x) {
		return Anim.key(label.hashCode(), x);
	}

	/** True while the mouse button is held down on exactly this rectangle. */
	private boolean pressed(int x1, int y1, int x2, int y2) {
		return mouseDown && pressX1 == x1 && pressY1 == y1 && pressX2 == x2 && pressY2 == y2;
	}

	private static final int BTN_PRIMARY = 0, BTN_NORMAL = 1, BTN_FLAT = 2, BTN_SOFT = 3, BTN_DANGER = 4;

	/** Width a button needs for its label and optional icon. */
	private int buttonW(String label, Icons ic) {
		return (label == null ? 0 : textW(label)) + (ic == null ? 0 : ic.w + (label == null ? 0 : 4)) + 12;
	}

	/**
	 * The one button of the menu. Styles: primary (solid accent), normal (raised), flat (no background until the
	 * mouse is over it) and soft (accent tint, for a switched-on toggle).
	 */
	private void button(DrawContext ctx, int x, int y, int w, int h, String label, Icons ic, int style,
	                    int mouseX, int mouseY, Runnable action, String tooltip) {
		boolean hover = hot(mouseX, mouseY, x, y, x + w, y + h);
		boolean down = pressed(x, y, x + w, y + h);
		float t = anim.fade(Anim.key(label != null ? label.hashCode() : 0x5000 + (ic == null ? 0 : ic.ordinal()), x), hover, 16f);
		int bg, border, fg;
		switch (style) {
			case BTN_PRIMARY -> {
				bg = down ? pal.strongPressed() : Anim.mix(pal.strong(), pal.strongHover(), t);
				border = 0;
				fg = WHITE;
			}
			case BTN_FLAT -> {
				bg = down ? pal.surfacePressed() : Anim.mix(0, pal.surfaceHover(), t);
				border = 0;
				fg = Anim.mix(TEXT2, WHITE, t);
			}
			case BTN_DANGER -> {
				bg = down ? 0x88B0323B : Anim.mix(0x66D6454F, 0x88D6454F, t);
				border = 0xFFE5646E;
				fg = WHITE;
			}
			case BTN_SOFT -> {
				bg = down ? pal.soft() : Anim.mix(pal.soft(), pal.softHover(), t);
				border = Anim.mix(Theme.argb(pal.accent() & 0xFFFFFF, 0.55f), pal.accent(), t);
				fg = pal.accentText();
			}
			default -> {
				bg = down ? pal.surfacePressed() : Anim.mix(pal.surface(), pal.surfaceHover(), t);
				border = Anim.mix(pal.surfaceBorder(), pal.surfaceBorderHover(), t);
				fg = Anim.mix(TEXT, WHITE, t);
			}
		}
		box(ctx, x, y, x + w, y + h, bg, border, shapeSm());
		int tw = label == null ? 0 : textW(label);
		int iw = ic == null ? 0 : ic.w + (label == null ? 0 : 4);
		int cx = x + (w - tw - iw) / 2;
		int ty = y + (h - 8) / 2 + (down ? 1 : 0);
		if (ic != null) {
			icon(ctx, ic, cx, y + (h - ic.h) / 2 + (down ? 1 : 0), fg);
			cx += ic.w + 4;
		}
		if (label != null) text(ctx, label, cx, ty, fg);
		hits.add(new Hit(x, y, x + w, y + h, action, null, tooltip, null));
	}

	/** A square button with just an icon. */
	private void iconButton(DrawContext ctx, int x, int y, int size, Icons ic, boolean on, int mouseX, int mouseY,
	                        Runnable action, String tooltip) {
		boolean hover = hot(mouseX, mouseY, x, y, x + size, y + size);
		boolean down = pressed(x, y, x + size, y + size);
		float t = anim.fade(Anim.key(ic.ordinal(), x), hover, 16f);
		int bg = on ? (down ? pal.strongPressed() : pal.strong())
			: down ? pal.surfacePressed() : Anim.mix(pal.surface(), pal.surfaceHover(), t);
		int border = on ? 0 : Anim.mix(pal.surfaceBorder(), pal.surfaceBorderHover(), t);
		box(ctx, x, y, x + size, y + size, bg, border, shapeSm());
		iconIn(ctx, ic, x, y + (down ? 1 : 0), x + size, y + size + (down ? 1 : 0), on ? WHITE : Anim.mix(TEXT2, WHITE, t));
		hits.add(new Hit(x, y, x + size, y + size, action, null, tooltip, null));
	}

	/** A text input frame: the actual text box is drawn by the game on top of it. */
	private void inputFrame(DrawContext ctx, int x1, int y1, int x2, int y2, boolean focused, boolean hover, int key) {
		float t = anim.fade(key, focused, 14f);
		float h = anim.fade(key + 1, hover && !focused, 14f);
		int bg = Anim.mix(Anim.mix(pal.surface(), pal.surfaceHover(), h), pal.surfaceHover(), t);
		int border = Anim.mix(Anim.mix(pal.surfaceBorder(), pal.surfaceBorderHover(), h), pal.accent(), t);
		box(ctx, x1, y1, x2, y2, bg, border, shapeSm());
	}

	/** An on/off switch, 20 x 10. The knob slides. */
	private void drawSwitch(DrawContext ctx, int x, int y, boolean on, int key) {
		float t = anim.fade(key, on, 16f);
		round(ctx, x, y, x + 20, y + 10, Anim.mix(pal.line2(), pal.strong(), t), shapePill(10));
		int kx = x + 1 + Math.round(t * 10);
		round(ctx, kx, y + 1, kx + 8, y + 9, Anim.mix(0xFFB8BDD0, WHITE, t), shapePill(8));
	}

	/** A row with a label on the left and a switch on the right; the whole row is clickable. */
	private int switchRow(DrawContext ctx, String label, String hint, boolean on, int x1, int x2, int y, int mouseX, int mouseY,
	                      Runnable action, String tooltip) {
		int hintH = hint == null ? 0 : smallWrappedHeight(hint, x2 - x1);
		int h = 16 + hintH;
		boolean hover = hot(mouseX, mouseY, x1 - 3, y - 2, x2 + 3, y + h);
		float t = anim.fade(fadeKey(label, x1), hover, 14f);
		round(ctx, x1 - 3, y - 2, x2 + 3, y + h, Anim.mix(0, pal.surface(), t), shapeSm());
		text(ctx, trim(label, x2 - x1 - 26), x1, y + 2, Anim.mix(TEXT2, WHITE, t));
		drawSwitch(ctx, x2 - 20, y + 1, on, fadeKey(label, x1) + 7);
		if (hint != null) smallWrapped(ctx, hint, x1, y + 14, x2 - x1, MUTED);
		hits.add(new Hit(x1 - 3, y - 2, x2 + 3, y + h, action, null, tooltip, null));
		return y + h + 3;
	}

	/**
	 * A row of choices where one is selected; the highlight slides to the new choice. Returns nothing: it draws
	 * into the box x, y, w, h. labels[i] may be null for an icon-only choice (icons[i] then shows).
	 */
	private final int[] segmentXs = new int[16];
	private final int[] segmentWs = new int[16];

	private void segmented(DrawContext ctx, int key, int x, int y, int w, int h, String[] labels, Icons[] icons, int[] widths,
	                       int selected, int mouseX, int mouseY, java.util.function.IntConsumer onSelect, String[] tooltips) {
		int n = labels.length;
		box(ctx, x, y, x + w, y + h, pal.surface(), pal.surfaceBorder(), shapeSm());
		int inner = w - 4;
		int[] xs = segmentXs;
		int[] ws = segmentWs;
		// every choice gets the same share, unless a name is wider than that: then shares follow the names
		boolean equal = widths == null;
		if (equal) {
			for (int i = 0; i < n && equal; i++) {
				int natural = (labels[i] == null ? 0 : textW(labels[i])) + (icons == null || icons[i] == null ? 0 : icons[i].w + 3) + 8;
				if (natural > inner / n) equal = false;
			}
		}
		int total = 0;
		for (int i = 0; i < n; i++) {
			ws[i] = equal ? 1 : widths != null ? widths[i]
				: (labels[i] == null ? 0 : textW(labels[i])) + (icons == null || icons[i] == null ? 0 : icons[i].w + 3) + 8;
			total += ws[i];
		}
		int acc = 0;
		for (int i = 0; i < n; i++) {
			xs[i] = x + 2 + inner * acc / Math.max(1, total);
			acc += ws[i];
		}
		xs[n] = x + 2 + inner;
		float ax1 = anim.to(Anim.key(key, 1000), xs[selected], 16f);
		float ax2 = anim.to(Anim.key(key, 1001), xs[selected + 1], 16f);
		round(ctx, Math.round(ax1), y + 2, Math.round(ax2), y + h - 2, pal.soft(), shapeSm());
		for (int i = 0; i < n; i++) {
			int sx1 = xs[i], sx2 = xs[i + 1];
			boolean on = i == selected;
			boolean hover = hot(mouseX, mouseY, sx1, y, sx2, y + h);
			float t = anim.fade(Anim.key(key, i + 2), hover && !on, 16f);
			int color = on ? WHITE : Anim.mix(MUTED, TEXT, t);
			if (!on && t > 0.01f) round(ctx, sx1, y + 2, sx2, y + h - 2, Anim.mix(0, pal.surface(), t), shapeSm());
			Icons ic = icons == null ? null : icons[i];
			int iw = ic == null ? 0 : ic.w + (labels[i] == null ? 0 : 3);
			String label = labels[i] == null ? null : trim(labels[i], Math.max(8, sx2 - sx1 - 4 - iw));
			int lw = label == null ? 0 : textW(label);
			int cx = sx1 + (sx2 - sx1 - lw - iw) / 2;
			if (ic != null) {
				icon(ctx, ic, cx, y + (h - ic.h) / 2, on ? pal.accentText() : color);
				cx += ic.w + 3;
			}
			if (label != null) text(ctx, label, cx, y + (h - 8) / 2, color);
			final int choice = i;
			hits.add(new Hit(sx1, y, sx2, y + h, () -> onSelect.accept(choice), null, tooltips == null ? null : tooltips[i], null));
		}
	}

	/** Three dots that light up in turn, for pictures still being made. */
	private void drawLoading(DrawContext ctx, int cx, int cy) {
		int lit = (int) (clock * 4) % 3;
		for (int i = 0; i < 3; i++) {
			int dx = cx - 6 + i * 5;
			fill(ctx, dx, cy - 1, dx + 3, cy + 2, i == lit ? pal.accentText() : pal.line2());
		}
	}

	/** A thin scroll bar along the right edge of a scrolling area, only when there is more to see. */
	private void scrollBar(DrawContext ctx, int x2, int y1, int y2, int offset, int max, boolean active) {
		int view = y2 - y1;
		if (max <= 0 || view <= 0) return;
		int thumb = Math.max(10, view * view / (view + max));
		int ty = y1 + (view - thumb) * offset / max;
		round(ctx, x2 - 3, ty, x2 - 1, ty + thumb, active ? Theme.argb(pal.accentText() & 0xFFFFFF, 0.6f) : 0x40FFFFFF, CORNERS_1);
	}

	/** A small round number badge ("3 items changed" and the like). */
	private int badge(DrawContext ctx, String s, int x, int y, int h, int bg, int fg) {
		int w = textW(s) + 8;
		round(ctx, x, y, x + w, y + h, bg, shapePill(h));
		text(ctx, s, x + 4, y + (h - 8) / 2, fg);
		return w;
	}

	/** A heading for a group of controls: small caps-style text and a hairline that runs to the right edge. */
	private int section(DrawContext ctx, String title, String rightText, int x1, int x2, int y) {
		String caps = title.toUpperCase(Locale.ROOT);
		small(ctx, caps, x1, y, MUTED);
		int tx = x1 + smallW(caps) + 5;
		int end = x2;
		if (rightText != null) {
			int rw = smallW(rightText);
			small(ctx, rightText, x2 - rw, y, pal.accentText());
			end = x2 - rw - 5;
		}
		if (end > tx) fill(ctx, tx, y + smallH() / 2 - 1, end, y + smallH() / 2, pal.line());
		return y + smallH() + 4;
	}

	/** Cuts the click areas added since index first down to a scrolled panel's visible part. */
	private void clipHits(int first, int top, int bottom) {
		for (int i = hits.size() - 1; i >= first; i--) {
			Hit h = hits.get(i);
			int y1 = Math.max(h.y1, top), y2 = Math.min(h.y2, bottom);
			if (y2 <= y1) hits.remove(i);
			else if (y1 != h.y1 || y2 != h.y2) hits.set(i, new Hit(h.x1, y1, h.x2, y2, h.action, h.rightAction, h.tooltip, h.slider));
		}
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

	/**
	 * Where a scrolling area is drawn: it eases toward the target the wheel set. Returns whole pixels so text
	 * and pictures stay sharp.
	 */
	private int scrollOf(String key, int content, int view) {
		int max = Math.max(0, content - view);
		int target = Math.max(0, Math.min(max, scroll.getOrDefault(key, 0)));
		scroll.put(key, target);
		float shown = scrollShown.getOrDefault(key, (float) target);
		if (!config.animations || Math.abs(target - shown) < 0.5f) {
			shown = target;
		} else {
			shown += (target - shown) * (1f - (float) Math.exp(-18f * dt));
		}
		shown = Math.max(0f, Math.min(max, shown));
		scrollShown.put(key, shown);
		return Math.round(shown);
	}

	/** Sets a scroll position that is shown at once, with no easing. */
	private void jumpScroll(String key, int value) {
		scroll.put(key, value);
		scrollShown.put(key, (float) value);
	}

	private void resetScroll(String key) {
		jumpScroll(key, 0);
	}

	// ------------------------------------------------------------------ input

	private void focusField(TextFieldWidget field) {
		setFocused(field);
		field.setFocused(true);
		if (field != search) search.setFocused(false);
		if (field != styleSearch) styleSearch.setFocused(false);
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		double x = click.x(), y = click.y();
		if (listening != null) {
			// a key setting is waiting: a middle or side mouse button becomes the key, any other click cancels
			if (click.button() >= 2) bindListening(net.minecraft.client.util.InputUtil.Type.MOUSE.createFromCode(click.button()));
			else listening = null;
			return true;
		}
		if (search.isMouseOver(x, y) || (styleSearch.visible && styleSearch.isMouseOver(x, y))
			|| (profileName.visible && profileName.isMouseOver(x, y))) {
			return super.mouseClicked(click, doubled);
		}
		if (renamingProfile) commitRename();
		if (crossShown && !profilesOpen && !settingsOpen) {
			int c = (int) Math.floor((x - crossX) / crossCell), r = (int) Math.floor((y - crossY) / crossCell);
			if (c >= 0 && c < 15 && r >= 0 && r < 15 && y >= crossTop && y < crossBottom) {
				String now = config.crosshair != null ? config.crosshair : crosshairSeed();
				paintOn = click.button() == 0 && now.charAt(r * 15 + c) != '1';
				lastPaintC = -1;
				painting = paintCross(x, y);
				return true;
			}
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
				mouseDown = true;
				pressX1 = hit.x1;
				pressY1 = hit.y1;
				pressX2 = hit.x2;
				pressY2 = hit.y2;
				hit.action.run();
				return true;
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(Click click, double offsetX, double offsetY) {
		if (painting) {
			paintCross(click.x(), click.y());
			return true;
		}
		if (dragging != null) {
			dragging.setFromMouse(click.x());
			return true;
		}
		return super.mouseDragged(click, offsetX, offsetY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		mouseDown = false;
		if (painting) {
			painting = false;
			lastPaintC = -1;
			changed(true);
			return true;
		}
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
		for (int i = scrollAreas.size() - 1; i >= 0; i--) {
			ScrollArea area = scrollAreas.get(i);
			if (profilesOpen && !area.key.equals("profiles")) continue;
			if (inside(mouseX, mouseY, area.x1, area.y1, area.x2, area.y2)) {
				int step = area.key.equals("items") ? itemsStep : 24;
				int value = scroll.getOrDefault(area.key, 0) - (int) Math.round(verticalAmount * step);
				scroll.put(area.key, Math.max(0, Math.min(area.max, value)));
				return true;
			}
		}
		if (profilesOpen) return true;
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public boolean keyPressed(KeyInput input) {
		if (listening != null) {
			// the next key becomes the setting; Escape clears it
			bindListening(input.isEscape() ? net.minecraft.client.util.InputUtil.UNKNOWN_KEY
				: net.minecraft.client.util.InputUtil.fromKeyCode(input));
			return true;
		}
		if (renamingProfile && profileName.isFocused()) {
			if (input.getKeycode() == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || input.getKeycode() == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
				commitRename();
				return true;
			}
			if (input.isEscape()) {
				renamingProfile = false;
				profileName.setFocused(false);
				return true;
			}
			return super.keyPressed(input);
		}
		if (profilesOpen && input.isEscape()) {
			profilesOpen = false;
			return true;
		}
		boolean typing = search.isFocused() || styleSearch.isFocused();
		if (input.hasCtrlOrCmd() && input.getKeycode() == org.lwjgl.glfw.GLFW.GLFW_KEY_Z && !typing) {
			undo();
			return true;
		}
		if (input.hasCtrlOrCmd() && input.getKeycode() == org.lwjgl.glfw.GLFW.GLFW_KEY_S) {
			apply();
			return true;
		}
		if (input.hasCtrlOrCmd() && input.getKeycode() == org.lwjgl.glfw.GLFW.GLFW_KEY_F) {
			focusField(search);
			return true;
		}
		if (input.isEscape()) {
			if (settingsOpen) {
				settingsOpen = false;
				return true;
			}
			close();
			return true;
		}
		return super.keyPressed(input);
	}
}
