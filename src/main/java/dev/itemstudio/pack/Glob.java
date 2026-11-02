package dev.itemstudio.pack;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** File-name patterns where "*" stands for any text, like "block/*_wool". */
final class Glob {
	private static final Map<String, Pattern> CACHE = new ConcurrentHashMap<>();

	private Glob() {
	}

	static boolean matches(String pattern, String text) {
		if (pattern.indexOf('*') < 0) return pattern.equals(text);
		return CACHE.computeIfAbsent(pattern, p -> {
			StringBuilder regex = new StringBuilder();
			for (String part : p.split("\\*", -1)) {
				if (regex.length() > 0) regex.append(".*");
				regex.append(Pattern.quote(part));
			}
			return Pattern.compile(regex.toString());
		}).matcher(text).matches();
	}

	/** Matches "assets/minecraft/textures/" + one of the patterns + ".png". path is lower case. */
	static boolean texture(String path, String[] patterns) {
		String base = "assets/minecraft/textures/";
		if (!path.startsWith(base) || !path.endsWith(".png")) return false;
		String rest = path.substring(base.length(), path.length() - 4);
		for (String p : patterns) {
			if (matches(p, rest)) return true;
		}
		return false;
	}
}
