# OfflineBuilder

Offline-first Fabric client mod for Minecraft Java 26.3 (Java 25).

## Implemented test features

- Press **B** to open the builder menu (rebindable in Options → Controls).
- Build walls, floors, cuboids, spheres, domes, cylinders, pyramids, simple house shells, pillars, stairs, bridges, arches, circles, rings, cones, arenas and tunnels.
- House roofs: flat, gable, hip and shed roofs.
- Adjust width, length and height; choose filled or hollow where supported.
- Search the registered placeable block list by display name or registry ID.
- Build gradually; pause, resume, cancel and calculate a block-count preview.
- Material lookup covers the full 36-slot player inventory. When needed, a stack is moved into a free hotbar slot through Minecraft's normal container-input transaction. Existing occupied hotbar slots are not overwritten.
- Start a one-block mining action with the command prefix followed by `break`. The helper searches for a suitable pickaxe in the hotbar and main inventory, moves it to an empty hotbar slot if available, selects it and restores the previously selected slot when finished. Stop with `break cancel`.
- Undo the last completed build with `build undo` and repeat it with `build redo`. This first implementation is Creative-only and processes blocks gradually.
- Local build presets: `build save <name>`, `build load <name>`, `build list`, `build delete <name>`. A preset stores shape, dimensions, material, hollow mode and roof style.
- Local relative structure files: `build saveblocks <name>`, `build loadblocks <name>`, `build listblocks`, `build deleteblocks <name>`. These save the last completed build's relative block positions and one material in a local `.obs` file, then rebuild it at the block currently being targeted. They do not yet preserve multiple materials, block-state properties, inventories or block-entity data.
- Local settings: configurable command prefix, maximum block count and build interval. Values are stored in `config/offlinebuilder/settings.properties`.
- The builder screen is non-pausing, so a running build continues when the menu is closed.

## Chat commands

The default prefix is **+**. It can be changed in the settings screen or with `+settings prefix !`. Ordinary chat without the active prefix is left alone.

- `+help`, `+menu`, `+break`, `+break cancel`
- `+build wall length 20 height 4 stone_bricks`
- `+build floor 20x20 oak_planks`
- `+build sphere radius 8 glass`
- `+build dome radius 10 stone_bricks`
- `+build cylinder radius 5 height 12 cobblestone`
- `+build pyramid width 15 height 10 stone`
- `+build house width 12 length 16 height 8 oak_planks roof gable`
- `+build house width 12 length 16 height 8 oak_planks roof hip`
- `+build pillar width 3 length 3 height 12 quartz_block`
- `+build stairs width 3 length 12 height 6 stone_bricks`
- `+build bridge width 5 length 20 height 3 stone_bricks`
- `+build arch width 15 height 9 stone_bricks`
- `+build circle radius 8 stone`, `+build ring radius 8 stone`
- `+build cone radius 6 height 12 stone`, `+build arena radius 14 height 6 stone_bricks`
- `+build tunnel width 9 length 24 height 10 stone_bricks`
- `+build pause`, `+build resume`, `+build cancel`, `+build undo`, `+build redo`, `+build preview`
- `+build save castle`, `+build load castle`, `+build list`, `+build delete castle`
- `+settings show`, `+settings prefix !`, `+settings maxblocks 20000`, `+settings speed 3`

## Known limitations — not the complete design brief

This remains an in-progress test implementation, not the complete mod requested in the original brief.

- The preview is currently a block-count calculation, not a 3D ghost preview with movable placement controls.
- The house option is a simple one-material shell with four roof profiles; it is not yet a full building generator with rooms, windows, doors as separate blocks, interiors, styles or multiple materials.
- Relative structure files can reproduce the last completed single-material build at a new anchor, but they are not full Minecraft structure files: multi-material blocks, block-state properties, block entities, orientation editing and arbitrary imported structure formats are not preserved.
- Undo/redo is Creative-only. Survival undo, persistent multi-step history and restoration of replaced blocks are not implemented.
- The mining helper targets one block at a time. It is not a continuous mining assistant.
- There is no multi-material palette, global block replacement, random-material tool, garden/interior/decorations generator, or complete building catalogue.
- Build progress is shown as counts, but there is no ETA estimate, automatic restart recovery or persistent build queue.
- The UI is improved and responsive, but it is still a compact control panel rather than the full multi-sidebar workspace and 3D editor originally described.

## Offline and server behaviour

The mod makes no AI, cloud or runtime internet requests. Build, mining and inventory moves use Minecraft's normal client interactions. The server remains authoritative: protection, reach, inventory, tool effectiveness and permissions are not bypassed.

A successful Gradle build confirms compilation only. The mod still needs in-game testing on Minecraft 26.3 with the matching Fabric Loader and Fabric API.
