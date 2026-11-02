package dev.itemstudio.pack;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * File-name patterns where "*" stands for any text, like "block/*_wool". Matched with plain string checks (no
 * regular expressions): every pack file is checked against every pattern during a scan, so this has to be cheap.
 */
final class Glob {
	private static final String BASE = "assets/minecraft/textures/";
	private static final Map<String, String[]> PARTS = new ConcurrentHashMap<>();

	private Glob() {
	}

	static boolean matches(String pattern, String text) {
		if (pattern.indexOf('*') < 0) return pattern.equals(text);
		return matchPieces(PARTS.computeIfAbsent(pattern, p -> p.split("\\*", -1)), text);
	}

	/** parts = the pattern split at its stars. */
	static boolean matchPieces(String[] parts, String text) {
		String first = parts[0], last = parts[parts.length - 1];
		if (text.length() < first.length() + last.length()) return false;
		if (!text.startsWith(first) || !text.endsWith(last)) return false;
		int at = first.length();
		int end = text.length() - last.length();
		for (int i = 1; i < parts.length - 1; i++) {
			int found = text.indexOf(parts[i], at);
			if (found < 0 || found + parts[i].length() > end) return false;
			at = found + parts[i].length();
		}
		return true;
	}

	/** Matches "assets/minecraft/textures/" + one of the patterns + ".png". path is lower case. */
	static boolean texture(String path, String[] patterns) {
		if (!path.startsWith(BASE) || !path.endsWith(".png")) return false;
		String rest = path.substring(BASE.length(), path.length() - 4);
		for (String p : patterns) {
			if (matches(p, rest)) return true;
		}
		return false;
	}
}
