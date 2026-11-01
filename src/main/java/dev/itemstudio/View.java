package dev.itemstudio;

/** The places an item is shown, mapped to the "display" keys in item model files. */
public enum View {
	FIRST_PERSON("first_person", "1st person", new String[]{"firstperson_righthand", "firstperson_lefthand"}),
	THIRD_PERSON("third_person", "3rd person", new String[]{"thirdperson_righthand", "thirdperson_lefthand"}),
	INVENTORY("inventory", "Inventory", new String[]{"gui"}),
	GROUND("ground", "Dropped", new String[]{"ground"}),
	FRAME("frame", "Item frame", new String[]{"fixed"}),
	HEAD("head", "On head", new String[]{"head"});

	public final String key;
	public final String label;
	/** Model display keys. Index 1, when present, is the left hand. */
	public final String[] displayKeys;

	View(String key, String label, String[] displayKeys) {
		this.key = key;
		this.label = label;
		this.displayKeys = displayKeys;
	}
}
