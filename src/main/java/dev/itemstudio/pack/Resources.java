package dev.itemstudio.pack;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Reads files from the game's currently loaded resources. */
public final class Resources {
	/** Folder name of the pack Item Studio writes into resourcepacks. */
	public static final String GENERATED_FOLDER = "ItemStudio-Generated";
	public static final String GENERATED_PACK_ID = "file/" + GENERATED_FOLDER;

	private Resources() {
	}

	/** "assets/ns/some/path" -> Identifier(ns, "some/path"). Returns null for anything else. */
	public static Identifier toId(String path) {
		if (!path.startsWith("assets/")) return null;
		int slash = path.indexOf('/', 7);
		if (slash < 0) return null;
		return Identifier.tryParse(path.substring(7, slash), path.substring(slash + 1));
	}

	private static boolean isGenerated(Resource resource) {
		String packId = resource.getPackId();
		return packId != null && packId.contains(GENERATED_FOLDER);
	}

	/** The file as the game sees it without Item Studio's pack: the top-most other pack, or vanilla. */
	public static byte[] readBelow(String path) {
		Identifier id = toId(path);
		if (id == null) return null;
		try {
			List<Resource> all = MinecraftClient.getInstance().getResourceManager().getAllResources(id);
			for (int i = all.size() - 1; i >= 0; i--) {
				Resource resource = all.get(i);
				if (isGenerated(resource)) continue;
				try (InputStream in = resource.getInputStream()) {
					return in.readAllBytes();
				}
			}
		} catch (Exception ignored) {
		}
		return null;
	}

	/**
	 * True when the game reads this file from Item Studio's pack. Files the game reads are listed per folder name
	 * (namespace) when resources load, so this also says whether a new file in that folder would be seen without a
	 * full reload.
	 */
	public static boolean fromGenerated(String path) {
		Identifier id = toId(path);
		if (id == null) return false;
		try {
			for (Resource resource : MinecraftClient.getInstance().getResourceManager().getAllResources(id)) {
				if (isGenerated(resource)) return true;
			}
		} catch (Exception ignored) {
		}
		return false;
	}

	/** The file from the game's own built-in assets only. */
	public static byte[] readVanilla(String path) {
		Identifier id = toId(path);
		if (id == null) return null;
		try {
			for (Resource resource : MinecraftClient.getInstance().getResourceManager().getAllResources(id)) {
				if ("vanilla".equals(resource.getPackId())) {
					try (InputStream in = resource.getInputStream()) {
						return in.readAllBytes();
					}
				}
			}
		} catch (Exception ignored) {
		}
		return null;
	}

	/** PNG files the game knows under assets/minecraft/<folder> (vanilla and turned-on packs), as pack paths. */
	public static List<String> listGame(String folder) {
		List<String> list = new java.util.ArrayList<>();
		try {
			for (Identifier id : MinecraftClient.getInstance().getResourceManager()
				.findResources(folder, i -> i.getPath().endsWith(".png")).keySet()) {
				if (id.getNamespace().equals("minecraft")) list.add("assets/minecraft/" + id.getPath());
			}
		} catch (Exception ignored) {
		}
		return list;
	}

	public static JsonElement parse(byte[] bytes) {
		if (bytes == null) return null;
		try {
			String text = new String(bytes, StandardCharsets.UTF_8);
			if (text.startsWith("﻿")) text = text.substring(1);
			return JsonParser.parseString(text);
		} catch (Exception e) {
			return null;
		}
	}
}
