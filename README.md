# OfflineBuilder

Offline-first Fabric client mod for Minecraft Java 26.3.

## Current test build

- Press **B** to open the menu (the key can be rebound in Options → Controls).
- Choose a wall, floor, cuboid, sphere, dome, cylinder or pyramid.
- Adjust width, length and height; select a block type and choose filled or hollow.
- Use the preview button to calculate the block count. This count preview does not place blocks.
- Start building while looking at a solid block. The selected material must be in the main hand.
- Building uses normal Minecraft block-use interactions. The server remains authoritative; protection, reach, inventory and permission checks are not bypassed.
- Pause/resume or cancel the active build from the menu.

## Chat commands

Commands use the default **+** prefix. Ordinary chat messages without that prefix are left alone.

- `+help`
- `+menu`
- `+build wall length 20 height 4 stone_bricks`
- `+build floor 20x20 oak_planks`
- `+build sphere radius 8 glass`
- `+build dome radius 10 stone_bricks`
- `+build cylinder radius 5 height 12 cobblestone`
- `+build pyramid width 15 height 10 stone`
- `+build pause`, `+build resume`, `+build cancel`, `+build preview`

## Current limitations

This is the first functional test version, not the full feature list from the design brief. It does not yet include a 3D ghost preview, house/building generator, roof editor, blueprint library, undo/redo, configurable prefix, settings persistence, material replacement or a complete Survival inventory pre-check. The currently supported placement requires the chosen block in the main hand. The build stops when the server rejects a placement or the target space is obstructed.

The mod runs locally and makes no AI, cloud or runtime internet requests.
