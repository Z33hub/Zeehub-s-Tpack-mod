package dev.itemstudio;

/** A change to one display view of an item, on top of what its model already does. */
public final class Adjust {
	public float scale = 1f;
	public float x = 0f;
	public float y = 0f;
	public float z = 0f;
	public float rotX = 0f;
	public float rotY = 0f;
	public float rotZ = 0f;

	public boolean isDefault() {
		return Math.abs(scale - 1f) < 1e-4f
			&& Math.abs(x) < 1e-4f && Math.abs(y) < 1e-4f && Math.abs(z) < 1e-4f
			&& Math.abs(rotX) < 1e-4f && Math.abs(rotY) < 1e-4f && Math.abs(rotZ) < 1e-4f;
	}

	public void reset() {
		scale = 1f;
		x = y = z = 0f;
		rotX = rotY = rotZ = 0f;
	}
}
