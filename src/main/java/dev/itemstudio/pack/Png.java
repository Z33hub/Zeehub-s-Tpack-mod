package dev.itemstudio.pack;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Small PNG reader and writer in plain Java, used to build item previews without depending on Minecraft or AWT
 * image classes. Reads 8-bit and paletted/grey images at 1, 2, 4 or 8 bits (16-bit is read by its high byte),
 * which covers resource pack textures. Interlaced PNGs are not supported and return null.
 */
public final class Png {
	private Png() {
	}

	/** Pixels as ARGB ints, row by row. */
	public record Image(int width, int height, int[] argb) {
		public int get(int x, int y) {
			return argb[y * width + x];
		}
	}

	public static Image decode(byte[] data) {
		try {
			return decodeOrThrow(data);
		} catch (Exception e) {
			return null;
		}
	}

	private static Image decodeOrThrow(byte[] data) throws Exception {
		if (data == null || data.length < 33) return null;
		ByteBuffer buf = ByteBuffer.wrap(data);
		long signature = buf.getLong();
		if (signature != 0x89504E470D0A1A0AL) return null;
		int width = 0, height = 0, bitDepth = 0, colorType = 0, interlace = 0;
		int[] palette = null;
		byte[] alphaTable = null;
		int[] transparentGrey = null;
		ByteArrayOutputStream idat = new ByteArrayOutputStream();
		while (buf.remaining() >= 12) {
			int length = buf.getInt();
			byte[] typeBytes = new byte[4];
			buf.get(typeBytes);
			String type = new String(typeBytes, StandardCharsets.US_ASCII);
			if (length < 0 || length > buf.remaining() - 4) return null;
			byte[] chunk = new byte[length];
			buf.get(chunk);
			buf.getInt(); // crc
			switch (type) {
				case "IHDR" -> {
					ByteBuffer h = ByteBuffer.wrap(chunk);
					width = h.getInt();
					height = h.getInt();
					bitDepth = h.get() & 255;
					colorType = h.get() & 255;
					h.get();
					h.get();
					interlace = h.get() & 255;
				}
				case "PLTE" -> {
					palette = new int[length / 3];
					for (int i = 0; i < palette.length; i++) {
						palette[i] = 0xFF000000 | (chunk[i * 3] & 255) << 16 | (chunk[i * 3 + 1] & 255) << 8 | (chunk[i * 3 + 2] & 255);
					}
				}
				case "tRNS" -> {
					if (colorType == 3) alphaTable = chunk;
					else if (colorType == 0 && length >= 2) transparentGrey = new int[]{((chunk[0] & 255) << 8) | (chunk[1] & 255)};
				}
				case "IDAT" -> idat.write(chunk);
				case "IEND" -> {
				}
				default -> {
				}
			}
			if (type.equals("IEND")) break;
		}
		if (width <= 0 || height <= 0 || width > 4096 || height > 8192 || interlace != 0) return null;
		int channels = switch (colorType) {
			case 0, 3 -> 1;
			case 2 -> 3;
			case 4 -> 2;
			case 6 -> 4;
			default -> -1;
		};
		if (channels < 0) return null;
		int bitsPerPixel = channels * bitDepth;
		int bpp = Math.max(1, bitsPerPixel / 8);
		int stride = (width * bitsPerPixel + 7) / 8;

		Inflater inflater = new Inflater();
		inflater.setInput(idat.toByteArray());
		byte[] raw = new byte[(stride + 1) * height];
		int read = 0;
		while (read < raw.length && !inflater.finished()) {
			int n = inflater.inflate(raw, read, raw.length - read);
			if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
			read += n;
		}
		inflater.end();
		if (read < raw.length) return null;

		byte[] prev = new byte[stride];
		byte[] line = new byte[stride];
		int[] argb = new int[width * height];
		for (int y = 0; y < height; y++) {
			int base = y * (stride + 1);
			int filter = raw[base] & 255;
			for (int i = 0; i < stride; i++) {
				int x = raw[base + 1 + i] & 255;
				int a = i >= bpp ? line[i - bpp] & 255 : 0;
				int b = prev[i] & 255;
				int c = i >= bpp ? prev[i - bpp] & 255 : 0;
				int v = switch (filter) {
					case 0 -> x;
					case 1 -> x + a;
					case 2 -> x + b;
					case 3 -> x + ((a + b) >> 1);
					case 4 -> x + paeth(a, b, c);
					default -> x;
				};
				line[i] = (byte) v;
			}
			for (int px = 0; px < width; px++) {
				argb[y * width + px] = pixel(line, px, colorType, bitDepth, palette, alphaTable, transparentGrey);
			}
			byte[] t = prev;
			prev = line;
			line = t;
		}
		return new Image(width, height, argb);
	}

	private static int paeth(int a, int b, int c) {
		int p = a + b - c;
		int pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
		if (pa <= pb && pa <= pc) return a;
		return pb <= pc ? b : c;
	}

	private static int sample(byte[] line, int index, int bitDepth) {
		if (bitDepth == 8) return line[index] & 255;
		if (bitDepth == 16) return line[index * 2] & 255;
		int perByte = 8 / bitDepth;
		int b = line[index / perByte] & 255;
		int shift = 8 - bitDepth * (index % perByte + 1);
		return (b >> shift) & ((1 << bitDepth) - 1);
	}

	private static int pixel(byte[] line, int x, int colorType, int bitDepth, int[] palette, byte[] alphaTable, int[] tGrey) {
		switch (colorType) {
			case 3 -> {
				int i = sample(line, x, bitDepth);
				if (palette == null || i >= palette.length) return 0;
				int a = alphaTable != null && i < alphaTable.length ? alphaTable[i] & 255 : 255;
				return (a << 24) | (palette[i] & 0xFFFFFF);
			}
			case 0 -> {
				int g = sample(line, x, bitDepth);
				int max = bitDepth >= 8 ? 255 : (1 << bitDepth) - 1;
				int v = g * 255 / max;
				int a = tGrey != null && g == tGrey[0] ? 0 : 255;
				return (a << 24) | v << 16 | v << 8 | v;
			}
			case 2 -> {
				int s = bitDepth == 16 ? 2 : 1;
				int r = line[x * 3 * s] & 255, g = line[(x * 3 + 1) * s] & 255, b = line[(x * 3 + 2) * s] & 255;
				return 0xFF000000 | r << 16 | g << 8 | b;
			}
			case 4 -> {
				int s = bitDepth == 16 ? 2 : 1;
				int v = line[x * 2 * s] & 255, a = line[(x * 2 + 1) * s] & 255;
				return a << 24 | v << 16 | v << 8 | v;
			}
			default -> {
				int s = bitDepth == 16 ? 2 : 1;
				int r = line[x * 4 * s] & 255, g = line[(x * 4 + 1) * s] & 255;
				int b = line[(x * 4 + 2) * s] & 255, a = line[(x * 4 + 3) * s] & 255;
				return a << 24 | r << 16 | g << 8 | b;
			}
		}
	}

	/** Writes an 8-bit RGBA PNG. */
	public static byte[] encode(int width, int height, int[] argb) {
		ByteArrayOutputStream raw = new ByteArrayOutputStream();
		for (int y = 0; y < height; y++) {
			raw.write(0);
			for (int x = 0; x < width; x++) {
				int c = argb[y * width + x];
				raw.write((c >> 16) & 255);
				raw.write((c >> 8) & 255);
				raw.write(c & 255);
				raw.write((c >>> 24) & 255);
			}
		}
		Deflater deflater = new Deflater();
		deflater.setInput(raw.toByteArray());
		deflater.finish();
		ByteArrayOutputStream compressed = new ByteArrayOutputStream();
		byte[] tmp = new byte[8192];
		while (!deflater.finished()) {
			int n = deflater.deflate(tmp);
			compressed.write(tmp, 0, n);
		}
		deflater.end();

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
		ByteBuffer ihdr = ByteBuffer.allocate(13);
		ihdr.putInt(width).putInt(height).put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0);
		chunk(out, "IHDR", ihdr.array());
		chunk(out, "IDAT", compressed.toByteArray());
		chunk(out, "IEND", new byte[0]);
		return out.toByteArray();
	}

	private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
		byte[] t = type.getBytes(StandardCharsets.US_ASCII);
		ByteBuffer len = ByteBuffer.allocate(4).putInt(data.length);
		out.writeBytes(len.array());
		out.writeBytes(t);
		out.writeBytes(data);
		CRC32 crc = new CRC32();
		crc.update(t);
		crc.update(data);
		out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
	}
}
