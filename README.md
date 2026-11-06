# OfflineBuilder

Offline-first Fabric client mod for Minecraft Java 26.3 (Java 25).

## Current implemented test features

- Press **B** to open the builder menu (rebindable in Options → Controls).
- Build walls, floors, cuboids, spheres, domes, cylinders, pyramids, simple house shells, pillars, stairs, bridges and arches.
- Adjust width, length and height; choose filled or hollow where supported.
- Search the registered placeable block list by block name or registry ID.
- Build gradually, pause/resume, cancel, and show a block-count preview.
- Selects the chosen building material from the hotbar automatically. The material must be available in the hotbar.
- Start a one-block mining action with `+break`. The helper looks for a pickaxe in the hotbar, selects the fastest available pickaxe for the target block, and restores the prior slot when finished. Stop it with `+break cancel`.
- Undo the last completed build with `+build undo` and repeat it with `+build redo`. This initial undo/redo implementation is Creative-only and processes one block at a time.
- The builder screen is non-pausing, so a running build continues when the menu is closed.

## Chat commands

Commands use the default **+** prefix. Ordinary chat messages without that prefix are left alone.

- `+help`
- `+menu`
- `+break` / `+break cancel`
- `+build wall length 20 height 4 stone_bricks`
- `+build floor 20x20 oak_planks`
- `+build sphere radius 8 glass`
- `+build dome radius 10 stone_bricks`
- `+build cylinder radius 5 height 12 cobblestone`
- `+build pyramid width 15 height 10 stone`
- `+build house width 12 length 16 height 8 oak_planks`
- `+build pillar width 3 length 3 height 12 quartz_block`
- `+build stairs width 3 length 12 height 6 stone_bricks`
- `+build bridge width 5 length 20 height 3 stone_bricks`
- `+build arch width 15 height 9 stone_bricks`
- `+build pause`, `+build resume`, `+build cancel`, `+build undo`, `+build redo`, `+build preview`

## Known limitations — not the complete design brief

This is a growing test implementation, not the complete mod requested in the original brief.

- The preview is currently a block-count calculation, not a 3D ghost preview with movable placement controls.
- The house option creates a simple one-material shell; it is not yet a configurable building/room/roof generator.
- Undo/redo is Creative-only. Survival undo, reliable restoration of replaced blocks, and persistent multi-step history are not implemented.
- The mining helper targets one block at a time. It is not a continuous mining assistant and does not manage tools outside the hotbar.
- No blueprint save/load/export/import library, settings persistence, configurable command prefix, global block replacement, multi-material palettes, or complete Survival inventory pre-check yet.
- The UI remains a basic builder panel, not the full multi-sidebar design or polished 3D workspace.

## Offline and server behaviour

The mod makes no AI, cloud or runtime internet requests. Build and mining actions go through Minecraft's normal client game-mode interactions. The server remains authoritative: protection, reach, inventory, tool effectiveness and permissions are not bypassed.

A successful Gradle build confirms compilation only. The mod still requires an in-game test on Minecraft 26.3 with the matching Fabric Loader and Fabric API.
