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

## Menu settings

Click the gear in the top right corner of the menu (or the settings button in Mod Menu):

- **Look**: theme, accent colour, window opacity, corner style (sharp, soft or round) and menu font.
- **Layout**: spacing (auto, compact or roomy), style card size, category list (names or icons), side panel
  width and animations.
- **General**: turn the mod on or off, check for updates, tooltips, the three keys (open menu, turn on/off, next
  profile) and a list of shortcuts. **Reset look** puts the look back to how it came.

Item Studio writes a pack called `ItemStudio-Generated` into your resourcepacks folder and keeps it at the top.
Your choices are saved in `config/itemstudio.json`.

Requires Fabric API.

## Build

`./gradlew build` with Java 25. The jar is in `build/libs`. Pushing to GitHub also builds it (Actions tab).
