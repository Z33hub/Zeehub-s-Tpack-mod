package dev.itemstudio;

/** A colour change for one item or part. */
public final class Recolor {
	public static final String OFF = "off";
	/** Keeps light and shadow, swaps the colour. */
	public static final String RECOLOUR = "recolour";
	/** Multiplies by the colour: white turns into exactly that colour. */
	public static final String TINT = "tint";

	public String mode = OFF;
	/** 0-360 */
	public float hue = 0f;
	/** 0-1 */
	public float sat = 0.75f;
	/** -0.6 (darker) to 0.6 (lighter) */
	public float bright = 0f;

	public boolean isDefault() {
		return mode == null || OFF.equals(mode);
	}

	/** Short text that changes whenever the result would look different. */
	public String key() {
		return mode + "," + Math.round(hue) + "," + Math.round(sat * 100) + "," + Math.round(bright * 100);
	}
}
