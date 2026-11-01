package dev.itemstudio.pack;

import dev.itemstudio.ItemStudioClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** A resource pack from the resourcepacks folder, either a .zip or an unzipped folder. */
public final class FilePackSource implements PackSource {
	private final String id;
	private final String name;
	private final String tag;
	private final ZipFile zip;
	private final Path folder;
	/** Lower-case path -> real path, because some packs use odd capitalisation in zips. */
	private final Map<String, String> files = new HashMap<>();

	private FilePackSource(String id, ZipFile zip, Path folder) {
		this.id = id;
		this.zip = zip;
		this.folder = folder;
		String shown = id.endsWith(".zip") ? id.substring(0, id.length() - 4) : id;
		this.name = shown.replaceAll("§.", "").replace('_', ' ').trim();
		this.tag = "p" + Integer.toHexString(id.hashCode());
	}

	/** Opens a pack, or returns null if the file is not a valid pack. */
	public static FilePackSource open(Path path) {
		String fileName = path.getFileName().toString();
		try {
			if (Files.isDirectory(path)) {
				if (!Files.exists(path.resolve("pack.mcmeta"))) return null;
				FilePackSource source = new FilePackSource(fileName, null, path);
				try (Stream<Path> walk = Files.walk(path)) {
					walk.filter(Files::isRegularFile).forEach(p -> {
						String rel = path.relativize(p).toString().replace('\\', '/');
						source.files.put(rel.toLowerCase(Locale.ROOT), rel);
					});
				}
				return source;
			}
			if (fileName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
				ZipFile zip = new ZipFile(path.toFile());
				if (zip.getEntry("pack.mcmeta") == null) {
					zip.close();
					return null;
				}
				FilePackSource source = new FilePackSource(fileName, zip, null);
				zip.stream().filter(e -> !e.isDirectory()).forEach(e ->
					source.files.put(e.getName().toLowerCase(Locale.ROOT), e.getName()));
				return source;
			}
		} catch (Exception e) {
			ItemStudioClient.LOG.warn("Skipping pack {}: {}", fileName, e.toString());
		}
		return null;
	}

	@Override
	public String id() {
		return id;
	}

	@Override
	public String displayName() {
		return name;
	}

	@Override
	public String tag() {
		return tag;
	}

	@Override
	public boolean has(String path) {
		return files.containsKey(path.toLowerCase(Locale.ROOT));
	}

	@Override
	public byte[] read(String path) throws IOException {
		String real = files.get(path.toLowerCase(Locale.ROOT));
		if (real == null) throw new IOException("Not in pack: " + path);
		if (zip != null) {
			ZipEntry entry = zip.getEntry(real);
			try (InputStream in = zip.getInputStream(entry)) {
				return in.readAllBytes();
			}
		}
		return Files.readAllBytes(folder.resolve(real));
	}

	@Override
	public Collection<String> paths() {
		return Collections.unmodifiableCollection(files.values());
	}

	@Override
	public void close() {
		if (zip != null) {
			try {
				zip.close();
			} catch (IOException ignored) {
			}
		}
	}
}
