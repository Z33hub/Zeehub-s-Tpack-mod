# Item Studio

A Fabric client mod for Minecraft 26.2 (the `main` branch is the 1.21.11 version). Pick a texture and model for each item from the
resource packs you already have, and change each item's size, position and rotation.

## Use

1. Put the PvP packs you like (zips or folders) in your `resourcepacks` folder. You don't need to turn them on.
2. Press **Right Shift** in game, or click **Item Studio** in the pause menu.
3. Pick a group on the left (Swords, Armor, Pots & Pearls...), click an item, then click a pack card.
   - **Default** leaves the item alone. **Vanilla** forces the normal look.
   - **One pack for all** uses one pack for every item in the group that the pack has.
   - **Size & Position** has sliders for first person, third person, inventory, dropped, item frame and on head.
     Right-click a slider to reset it.
4. Press **Apply**. Changes are also applied when you close the menu.

Item Studio writes a pack called `ItemStudio-Generated` into your resourcepacks folder and keeps it at the top.
Your choices are saved in `config/itemstudio.json`.

Requires Fabric API.

## Build

`./gradlew build` with Java 25. The jar is in `build/libs`. Pushing to GitHub also builds it (Actions tab).
