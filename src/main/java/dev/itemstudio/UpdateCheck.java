package dev.itemstudio;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Looks once per game session at the GitHub download release for a newer jar for this Minecraft version.
 * One small request to api.github.com; turned off with "Check for updates" in the settings.
 */
public final class UpdateCheck {
	public static final String RELEASE_PAGE = "https://github.com/Z33hub/Zeehub-s-Tpack-mod/releases/tag/latest";
	private static final String API = "https://api.github.com/repos/Z33hub/Zeehub-s-Tpack-mod/releases/tags/latest";

	/** A newer version: its number and where to get it. */
	public record Update(String version, String url) {
	}

	private static CompletableFuture<Update> future;

	private UpdateCheck() {
	}

	/** Starts the check (once). The result is null when there is nothing newer or the check failed. */
	public static synchronized CompletableFuture<Update> start() {
		if (future == null) future = CompletableFuture.supplyAsync(UpdateCheck::check);
		return future;
	}

	/** The update found so far, or null. Never waits. */
	public static Update found() {
		CompletableFuture<Update> f = future;
		if (f == null || !f.isDone() || f.isCompletedExceptionally()) return null;
		return f.getNow(null);
	}

	/** Version of this jar without the Minecraft part: "1.8.0". */
	public static String currentVersion() {
		return FabricLoader.getInstance().getModContainer(ItemStudioClient.MOD_ID)
			.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("0").split("\\+")[0];
	}

	/** Minecraft version this jar is for, from its version ("1.8.0+mc26.2" -> "26.2"). */
	private static String minecraftPart() {
		String full = FabricLoader.getInstance().getModContainer(ItemStudioClient.MOD_ID)
			.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("");
		int at = full.indexOf("+mc");
		return at < 0 ? "" : full.substring(at + 3);
	}

	private static Update check() {
		if (!StudioConfig.get().checkUpdates) return null;
		try {
			HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
			HttpRequest request = HttpRequest.newBuilder(URI.create(API)).timeout(Duration.ofSeconds(10))
				.header("Accept", "application/vnd.github+json").header("User-Agent", "ItemStudio-mod").GET().build();
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) return null;
			JsonElement json = JsonParser.parseString(response.body());
			Pattern jar = Pattern.compile("item-studio-([0-9.]+)\\+mc" + Pattern.quote(minecraftPart()) + "\\.jar");
			String best = null;
			for (JsonElement asset : json.getAsJsonObject().getAsJsonArray("assets")) {
				Matcher m = jar.matcher(asset.getAsJsonObject().get("name").getAsString());
				if (m.matches() && (best == null || newer(m.group(1), best))) best = m.group(1);
			}
			if (best != null && newer(best, currentVersion())) return new Update(best, RELEASE_PAGE);
		} catch (Exception e) {
			ItemStudioClient.LOG.debug("Update check failed: {}", e.toString());
		}
		return null;
	}

	/** True if version a is higher than b ("1.10.0" > "1.9.2"). */
	static boolean newer(String a, String b) {
		String[] x = a.split("\\."), y = b.split("\\.");
		for (int i = 0; i < Math.max(x.length, y.length); i++) {
			int p = i < x.length ? parse(x[i]) : 0, q = i < y.length ? parse(y[i]) : 0;
			if (p != q) return p > q;
		}
		return false;
	}

	private static int parse(String s) {
		try {
			return Integer.parseInt(s.replaceAll("\\D", ""));
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
