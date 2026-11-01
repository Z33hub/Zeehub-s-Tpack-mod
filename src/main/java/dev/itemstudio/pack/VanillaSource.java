package dev.itemstudio.pack;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The game's own textures and models, so an item can be forced back to vanilla even when another pack changes it. */
public final class VanillaSource implements PackSource {
	public static final String ID = "vanilla";
	private final Map<String, byte[]> cache = new HashMap<>();
	private static final byte[] MISSING = new byte[0];

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String displayName() {
		return "Vanilla";
	}

	@Override
	public String tag() {
		return "vanilla";
	}

	private byte[] fetch(String path) {
		return cache.computeIfAbsent(path, p -> {
			byte[] bytes = Resources.readVanilla(p);
			return bytes == null ? MISSING : bytes;
		});
	}

	@Override
	public boolean has(String path) {
		return fetch(path) != MISSING;
	}

	@Override
	public byte[] read(String path) throws IOException {
		byte[] bytes = fetch(path);
		if (bytes == MISSING) throw new IOException("Not in vanilla: " + path);
		return bytes;
	}

	@Override
	public Collection<String> paths() {
		return List.of();
	}
}
