package dev.itemstudio.pack;

import java.io.IOException;
import java.util.Collection;

/**
 * Somewhere item files can come from: a pack in the resourcepacks folder, or the built-in vanilla assets.
 * Paths look like "assets/minecraft/textures/item/diamond_sword.png".
 */
public interface PackSource extends AutoCloseable {
	/** Stable id saved in the config: "vanilla" or the pack's file/folder name. */
	String id();

	/** Name shown in the menu. */
	String displayName();

	boolean has(String path);

	byte[] read(String path) throws IOException;

	/** All file paths in the pack. Empty for sources that can't be listed. */
	Collection<String> paths();

	/** Short, file-name-safe tag used when copying this source's files so packs never overwrite each other. */
	String tag();

	@Override
	default void close() {
	}
}
