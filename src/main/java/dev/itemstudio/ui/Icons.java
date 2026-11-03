package dev.itemstudio.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Small pixel icons for the menu. Each one is drawn from a few filled rectangles, so it stays sharp at every GUI
 * scale and needs no texture. This file has no Minecraft code in it: the screen reads {@link #rects} and fills them.
 */
public enum Icons {
	SEARCH(
		".####...",
		"#....#..",
		"#....#..",
		"#....#..",
		"#....#..",
		".####...",
		"......#.",
		".......#"),
	CLOSE(
		"#.....#",
		".#...#.",
		"..#.#..",
		"...#...",
		"..#.#..",
		".#...#.",
		"#.....#"),
	GEAR(
		"....#....",
		".##.#.##.",
		".#######.",
		"..##.##..",
		"###...###",
		"..##.##..",
		".#######.",
		".##.#.##.",
		"....#...."),
	CHECK(
		".....##",
		"....##.",
		"#..##..",
		"##.##..",
		".###...",
		"..#...."),
	STAR(
		"....#....",
		"....#....",
		"...###...",
		"#########",
		".#######.",
		"..#####..",
		"..##.##..",
		".##...##.",
		".#.....#."),
	CHEVRON_RIGHT(
		"#...",
		".#..",
		"..#.",
		"...#",
		"..#.",
		".#..",
		"#..."),
	CHEVRON_LEFT(
		"...#",
		"..#.",
		".#..",
		"#...",
		".#..",
		"..#.",
		"...#"),
	CARET_DOWN(
		"#####",
		".###.",
		"..#.."),
	FOLDER(
		"###......",
		"#########",
		"#.......#",
		"#.......#",
		"#.......#",
		"#.......#",
		"#########"),
	REFRESH(
		"..####...",
		".#....#..",
		"#.....###",
		"#......#.",
		"#........",
		"#.......#",
		".#.....#.",
		"..#####.."),
	UNDO(
		"..#......",
		".##......",
		"########.",
		".##....##",
		"..#.....#",
		".......##",
		"..#####.."),
	PLUS(
		"...#...",
		"...#...",
		"...#...",
		"#######",
		"...#...",
		"...#...",
		"...#..."),
	COPY(
		"#####...",
		"#...#...",
		"#...#####",
		"#...#...#",
		"#####...#",
		"....#...#",
		"....#####"),
	PASTE(
		".#####.",
		"#######",
		"#.....#",
		"#.....#",
		"#.....#",
		"#.....#",
		"#######"),
	PENCIL(
		"......#",
		".....##",
		"....###",
		"...###.",
		"..###..",
		".###...",
		"##....."),
	TRASH(
		"..###..",
		"#######",
		".#.#.#.",
		".#.#.#.",
		".#.#.#.",
		".#.#.#.",
		".#####."),
	EXPORT(
		"....#....",
		"...###...",
		"..#.#.#..",
		"....#....",
		"#.......#",
		"#.......#",
		"#.......#",
		"#########"),
	DOT(
		".###.",
		"#####",
		"#####",
		"#####",
		".###."),
	CLOCK(
		"..###..",
		".#...#.",
		"#..#..#",
		"#..##.#",
		"#.....#",
		".#...#.",
		"..###.."),
	SORT_UP(
		"...#...",
		"..###..",
		".#.#.#.",
		"...#...",
		"...#...",
		"...#..."),
	SORT_DOWN(
		"...#...",
		"...#...",
		"...#...",
		".#.#.#.",
		"..###..",
		"...#..."),
	PALETTE(
		"..###..",
		".#####.",
		"#######",
		"###.###",
		"#######",
		".#####.",
		"..###.."),
	LAYOUT(
		"#######",
		"#######",
		"#######",
		".......",
		"###.###",
		"###.###",
		"###.###"),
	SLIDERS(
		"..#....",
		"#####..",
		"..#....",
		"..#.###",
		"###.#..",
		"..#.###",
		"..#....");

	/** Width and height in pixels. */
	public final int w, h;
	/** Filled rectangles as x1, y1, x2, y2 groups, with neighbouring pixels merged to keep the fill count low. */
	public final int[] rects;

	Icons(String... rows) {
		int width = 0;
		for (String r : rows) width = Math.max(width, r.length());
		this.w = width;
		this.h = rows.length;
		this.rects = merge(rows, width);
	}

	private static int[] merge(String[] rows, int width) {
		// runs per row, then rows with identical runs are stacked into one taller rectangle
		List<int[]> out = new ArrayList<>();
		List<int[]> open = new ArrayList<>();
		for (int y = 0; y <= rows.length; y++) {
			List<int[]> runs = new ArrayList<>();
			if (y < rows.length) {
				String r = rows[y];
				int x = 0;
				while (x < width) {
					if (x < r.length() && r.charAt(x) != '.') {
						int s = x;
						while (x < r.length() && r.charAt(x) != '.') x++;
						runs.add(new int[]{s, y, x, y + 1});
					} else {
						x++;
					}
				}
			}
			List<int[]> next = new ArrayList<>();
			for (int[] run : runs) {
				int[] same = null;
				for (int[] o : open) {
					if (o[0] == run[0] && o[2] == run[2] && o[3] == run[1]) {
						same = o;
						break;
					}
				}
				if (same != null) {
					open.remove(same);
					same[3] = run[3];
					next.add(same);
				} else {
					next.add(run);
				}
			}
			out.addAll(open);
			open = next;
		}
		out.addAll(open);
		int[] flat = new int[out.size() * 4];
		for (int i = 0; i < out.size(); i++) System.arraycopy(out.get(i), 0, flat, i * 4, 4);
		return flat;
	}
}
