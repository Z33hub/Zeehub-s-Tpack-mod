package dev.itemstudio.ui;

/**
 * Cheap animation values for the menu: a small fixed table of floats that ease toward a target, keyed by a number
 * the caller picks for each thing (a button, a card...). No boxing and no growth, so it adds nothing to the
 * garbage the game collects. Two things sharing a slot only share a fade, which nobody can see.
 * This file has no Minecraft code in it.
 */
public final class Anim {
	private static final int SIZE = 4096;
	private final float[] value = new float[SIZE];
	private final int[] owner = new int[SIZE];
	/** Seconds since the last frame, set once per frame by the screen. */
	public float dt = 0.016f;
	/** Off: every value jumps straight to its target. */
	public boolean enabled = true;

	/** Finds the slot of a key: its own, or the one next to it, or (if both belong to others) the first is taken over. */
	private int slot(int key) {
		int k = key == 0 ? 1 : key;
		int h = k * 0x9E3779B1;
		int s = (h ^ (h >>> 15)) & (SIZE - 1);
		if (owner[s] == k || owner[s] == 0) return s;
		int t = s ^ 1;
		if (owner[t] == k || owner[t] == 0) return t;
		return s;
	}

	/** Moves the value for key toward target (0..1 range or any range) and returns it. speed ~ 12 is quick, 6 is slow. */
	public float to(int key, float target, float speed) {
		int k = key == 0 ? 1 : key;
		int s = slot(k);
		if (owner[s] != k) {
			owner[s] = k;
			value[s] = target;
			return target;
		}
		if (!enabled) {
			value[s] = target;
			return target;
		}
		float v = value[s];
		float diff = target - v;
		if (Math.abs(diff) < 0.004f) {
			v = target;
		} else {
			// frame-rate independent exponential easing
			v += diff * (1f - (float) Math.exp(-speed * dt));
		}
		value[s] = v;
		return v;
	}

	/** Fade between 0 and 1 for something that is on or off. */
	public float fade(int key, boolean on, float speed) {
		return to(key, on ? 1f : 0f, speed);
	}

	/** The last value without moving it. */
	public float peek(int key, float fallback) {
		int k = key == 0 ? 1 : key;
		int s = slot(k);
		return owner[s] == k ? value[s] : fallback;
	}

	/** Puts a value in place at once (a restored scroll position, for example). */
	public void set(int key, float v) {
		int k = key == 0 ? 1 : key;
		int s = slot(k);
		owner[s] = k;
		value[s] = v;
	}

	/** Mixes two ARGB colours; t is 0..1. */
	public static int mix(int a, int b, float t) {
		if (t <= 0f) return a;
		if (t >= 1f) return b;
		int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
		int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
		return (Math.round(aa + (ba - aa) * t) << 24) | (Math.round(ar + (br - ar) * t) << 16)
			| (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
	}

	/** A key made from a few numbers, for Anim.to and friends. */
	public static int key(int a, int b) {
		return a * 31 + b + 0x5F3759DF;
	}

	public static int key(int a, int b, int c) {
		return (a * 31 + b) * 31 + c + 0x2545F491;
	}
}
