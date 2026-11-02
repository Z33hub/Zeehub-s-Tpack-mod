package dev.itemstudio.pack;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.resources.Identifier;

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
		return Identifier.tryBuild(path.substring(7, slash), path.substring(slash + 1));
	}

	private static boolean isGenerated(Resource resource) {
		String packId = resource.sourcePackId();
		return packId != null && packId.contains(GENERATED_FOLDER);
	}

	/** The file as the game sees it without Item Studio's pack: the top-most other pack, or vanilla. */
	public static byte[] readBelow(String path) {
		Identifier id = toId(path);
		if (id == null) return null;
		try {
			List<Resource> all = Minecraft.getInstance().getResourceManager().getResourceStack(id);
			for (int i = all.size() - 1; i >= 0; i--) {
				Resource resource = all.get(i);
				if (isGenerated(resource)) continue;
				try (InputStream in = resource.open()) {
					return in.readAllBytes();
				}
			}
		} catch (Exception ignored) {
		}
		return null;
	}

	/** The file from the game's own built-in assets only. */
	public static byte[] readVanilla(String path) {
		Identifier id = toId(path);
		if (id == null) return null;
		try {
			for (Resource resource : Minecraft.getInstance().getResourceManager().getResourceStack(id)) {
				if ("vanilla".equals(resource.sourcePackId())) {
					try (InputStream in = resource.open()) {
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
			for (Identifier id : Minecraft.getInstance().getResourceManager()
				.listResources(folder, i -> i.getPath().endsWith(".png")).keySet()) {
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
