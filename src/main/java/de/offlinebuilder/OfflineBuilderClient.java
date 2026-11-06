package de.offlinebuilder;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;

/**
 * OfflineBuilder client entry point.
 *
 * All world changes are sent through Minecraft's normal block-use interaction. The mod
 * does not write blocks directly into the client world and does not bypass server rules.
 */
public final class OfflineBuilderClient implements ClientModInitializer {
    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("offlinebuilder", "main"));
    private static KeyMapping openMenuKey;

    // Read the actual block registry: the material selector is not limited to a hand-picked list.
    private static final List<Block> MATERIALS = BuiltInRegistries.BLOCK.stream()
            .filter(block -> !block.defaultBlockState().isAir())
            .filter(block -> block.asItem() != net.minecraft.world.item.Items.AIR)
            .toList();
    private static int materialIndex = 0;
    private static String materialSearch = "";

    private static Shape shape = Shape.WALL;
    private static int width = 8;
    private static int length = 8;
    private static int height = 4;
    private static boolean hollow = false;
    private static String uiStatus = "Bereit. B druecken, um OfflineBuilder zu oeffnen.";

    @Override
    public void onInitializeClient() {
        openMenuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.offlinebuilder.open", InputConstants.KEY_B, CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openMenuKey.consumeClick()) {
                if (client.gui.screen() == null) {
                    client.gui.setScreen(new BuilderScreen());
                }
            }
            BuildEngine.tick(client);
        });

        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            if (!message.startsWith("+")) return true;
            handleBuilderCommand(message.substring(1));
            return false;
        });
    }


    /** Local chat commands use '+' so they do not collide with common blueprint mods. */
    private static void handleBuilderCommand(String raw) {
        Minecraft client = Minecraft.getInstance();
        String trimmed = raw.trim();
        String[] args = trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            tell(client, "Befehl: +menu | +build wall length 20 height 4 stone_bricks | +build floor 20x20 oak_planks | +build sphere radius 8 glass | +build pause/resume/cancel");
            return;
        }
        if (args[0].equalsIgnoreCase("menu")) {
            if (client.gui.screen() == null) client.gui.setScreen(new BuilderScreen());
            return;
        }
        if (!args[0].equalsIgnoreCase("build")) {
            tell(client, "Unbekannter OfflineBuilder-Befehl. Nutze +help.");
            return;
        }
        if (args.length < 2) {
            tell(client, "Nutzung: +build <wall|floor|cuboid|sphere|dome|cylinder|pyramid> ...");
            return;
        }
        String operation = args[1].toLowerCase(Locale.ROOT);
        if (operation.equals("pause")) { BuildEngine.pause(); tell(client, BuildEngine.status()); return; }
        if (operation.equals("resume")) { BuildEngine.resume(); tell(client, BuildEngine.status()); return; }
        if (operation.equals("cancel")) { BuildEngine.cancel(); tell(client, BuildEngine.status()); return; }
        if (operation.equals("undo")) {
            tell(client, "Rueckgaengig machen ist noch nicht in dieser Testversion enthalten.");
            return;
        }
        if (operation.equals("preview")) {
            BlockHitResult hit = currentBlockHit(client);
            if (hit == null || client.player == null) { tell(client, "Schau zuerst auf einen Block."); return; }
            List<BlockPos> preview = ShapePlanner.create(hit.getBlockPos().relative(hit.getDirection()),
                    client.player.getDirection());
            tell(client, "Vorschau: " + preview.size() + " Bloecke. Es wurde noch nichts gesetzt.");
            return;
        }

        switch (operation) {
            case "wall" -> shape = Shape.WALL;
            case "floor" -> shape = Shape.FLOOR;
            case "cuboid", "box", "quader" -> shape = Shape.CUBOID;
            case "sphere", "kugel" -> shape = Shape.SPHERE;
            case "dome", "kuppel" -> shape = Shape.DOME;
            case "cylinder", "zylinder" -> shape = Shape.CYLINDER;
            case "pyramid", "pyramide" -> shape = Shape.PYRAMID;
            case "house", "haus" -> { tell(client, "Der Hausgenerator folgt in einer spaeteren Ausbaustufe."); return; }
            default -> { tell(client, "Unbekannte Form: " + operation + ". Nutze +help."); return; }
        }

        int parsedWidth = numberAfter(args, "width", width);
        int parsedLength = numberAfter(args, "length", length);
        int parsedHeight = numberAfter(args, "height", height);
        int radius = numberAfter(args, "radius", Math.max(1, width / 2));
        for (String token : args) {
            if (token.matches("\\d{1,2}x\\d{1,2}")) {
                String[] pair = token.toLowerCase(Locale.ROOT).split("x");
                parsedWidth = Integer.parseInt(pair[0]);
                parsedLength = Integer.parseInt(pair[1]);
            }
        }
        if (shape == Shape.WALL) parsedWidth = numberAfter(args, "length", parsedWidth);
        if (shape == Shape.FLOOR && parsedLength == length) parsedLength = parsedWidth;
        if (shape == Shape.SPHERE || shape == Shape.DOME || shape == Shape.CYLINDER) {
            parsedWidth = radius * 2 + 1;
            parsedLength = parsedWidth;
        }
        if (shape == Shape.PYRAMID) parsedLength = parsedWidth;
        width = clamp(parsedWidth, 1, 48);
        length = clamp(parsedLength, 1, 48);
        height = clamp(parsedHeight, 1, 48);

        for (String token : args) {
            int found = materialIndex(token);
            if (found >= 0) { materialIndex = found; break; }
        }
        hollow = false;
        if (trimmed.toLowerCase(Locale.ROOT).contains(" hollow") || trimmed.toLowerCase(Locale.ROOT).contains(" hohl")) {
            hollow = true;
        }
        BuildEngine.start(client);
        tell(client, BuildEngine.status());
    }

    private static int numberAfter(String[] args, String key, int fallback) {
        for (int i = 0; i + 1 < args.length; i++) {
            if (args[i].equalsIgnoreCase(key)) {
                try { return Integer.parseInt(args[i + 1]); }
                catch (NumberFormatException ignored) { return fallback; }
            }
        }
        return fallback;
    }

    private static int materialIndex(String token) {
        String key = token.toLowerCase(Locale.ROOT).replace('-', '_');
        for (int i = 0; i < MATERIALS.size(); i++) {
            var id = BuiltInRegistries.BLOCK.getKey(MATERIALS.get(i));
            if (id != null && (id.toString().equals(key) || id.getPath().equals(key))) return i;
        }
        return -1;
    }

    private static void tell(Minecraft client, String message) {
        uiStatus = message;
        BuildEngine.state = message;
        if (client.player != null) client.player.sendSystemMessage(Component.literal("[OfflineBuilder] " + message));
    }

    private enum Shape {
        WALL("Wand"),
        FLOOR("Boden"),
        CUBOID("Quader"),
        SPHERE("Kugel"),
        DOME("Kuppel"),
        CYLINDER("Zylinder"),
        PYRAMID("Pyramide");

        final String label;
        Shape(String label) { this.label = label; }
        Shape next() { return values()[(ordinal() + 1) % values().length]; }
    }


    private static final class MaterialScreen extends Screen {
        private final Screen parent;
        private EditBox search;
        private int page = 0;
        private static final int PAGE_SIZE = 8;
        MaterialScreen(Screen parent) { super(Component.literal("OfflineBuilder - Material")); this.parent = parent; }
        private List<Block> matches() {
            String q = search == null ? materialSearch : search.getValue().toLowerCase(Locale.ROOT).trim();
            return MATERIALS.stream().filter(block -> {
                var id = BuiltInRegistries.BLOCK.getKey(block);
                String key = id == null ? "" : id.toString();
                return q.isEmpty() || key.contains(q) || block.getName().getString().toLowerCase(Locale.ROOT).contains(q);
            }).toList();
        }
        @Override protected void init() {
            int w = Math.max(160, Math.min(360, width - 24)), left = (width - w) / 2;
            search = new EditBox(font, left, 34, w, 20, Component.literal("Block suchen"));
            search.setValue(materialSearch);
            search.setResponder(value -> { materialSearch = value; page = 0; rebuildWidgets(); });
            addRenderableWidget(search);
            List<Block> found = matches();
            int pages = Math.max(1, (found.size() + PAGE_SIZE - 1) / PAGE_SIZE);
            page = Math.min(page, pages - 1);
            for (int i = page * PAGE_SIZE; i < Math.min(found.size(), (page + 1) * PAGE_SIZE); i++) {
                Block block = found.get(i); int index = MATERIALS.indexOf(block);
                String id = String.valueOf(BuiltInRegistries.BLOCK.getKey(block));
                addRenderableWidget(Button.builder(Component.literal(block.getName().getString() + " [" + id + "]"), b -> {
                    materialIndex = index; minecraft.gui.setScreen(parent);
                }).bounds(left, 62 + (i - page * PAGE_SIZE) * 26, w, 22).build());
            }
            addRenderableWidget(Button.builder(Component.literal("<"), b -> { page = Math.max(0,page-1); rebuildWidgets(); }).bounds(left, height-55, 40, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Seite " + (page+1) + "/" + pages + " - " + found.size() + " Treffer"), b -> {}).bounds(left+44,height-55,w-88,20).build());
            addRenderableWidget(Button.builder(Component.literal(">"), b -> { page = Math.min(pages-1,page+1); rebuildWidgets(); }).bounds(left+w-40,height-55,40,20).build());
            addRenderableWidget(Button.builder(Component.literal("Zurueck"), b -> minecraft.gui.setScreen(parent)).bounds(left,height-28,w,20).build());
            setInitialFocus(search);
        }
        @Override public void extractRenderState(GuiGraphicsExtractor graphics,int mouseX,int mouseY,float delta) {
            super.extractRenderState(graphics,mouseX,mouseY,delta);
            graphics.centeredText(font,title,width/2,12,0xFFFFFFFF);
        }
        @Override public boolean isPauseScreen() { return false; }
    }

    private static final class BuilderScreen extends Screen {
        private int panelWidth;
        private int panelLeft;

        BuilderScreen() {
            super(Component.literal("OfflineBuilder"));
        }

        @Override
        protected void init() {
            // Keep every widget inside the visible screen, including narrow GUI scales.
            panelWidth = Math.max(150, Math.min(330, this.width - 20));
            panelLeft = Math.max(10, (this.width - panelWidth) / 2);
            int left = panelLeft;
            int y = Math.max(32, this.height / 2 - 122);

            addRenderableWidget(Button.builder(Component.literal("Form: " + shape.label + "  ›"), b -> {
                shape = shape.next();
                rebuildWidgets();
            }).bounds(left, y, panelWidth, 20).build());
            y += 28;

            addDimensionRow(left, y, "Breite", 0);
            y += 27;
            addDimensionRow(left, y, "Laenge", 1);
            y += 27;
            addDimensionRow(left, y, "Hoehe", 2);
            y += 30;

            addRenderableWidget(Button.builder(
                    Component.literal("Material suchen / auswaehlen  (" + MATERIALS.size() + ")"),
                    b -> this.minecraft.gui.setScreen(new MaterialScreen(this))).bounds(left, y, panelWidth, 20).build());
            y += 27;

            addRenderableWidget(Button.builder(
                    Component.literal(hollow ? "Bauweise: Hohl" : "Bauweise: Gefuellt"),
                    b -> {
                        hollow = !hollow;
                        rebuildWidgets();
                    }).bounds(left, y, panelWidth, 20).build());
            y += 30;

            addRenderableWidget(Button.builder(Component.literal("Vorschau berechnen"), b -> {
                if (this.minecraft == null || this.minecraft.player == null) {
                    uiStatus = "Dazu musst du in einer Welt sein.";
                } else {
                    BlockHitResult hit = currentBlockHit(this.minecraft);
                    if (hit == null) {
                        uiStatus = "Schau auf einen Block, an den gebaut werden kann.";
                    } else {
                        List<BlockPos> plan = ShapePlanner.create(
                                hit.getBlockPos().relative(hit.getDirection()),
                                this.minecraft.player.getDirection());
                        uiStatus = "Vorschau: " + plan.size() + " Bloecke. Noch nichts gesetzt.";
                    }
                }
            }).bounds(left, y, panelWidth, 20).build());
            y += 27;

            addRenderableWidget(Button.builder(Component.literal("Bau starten"), b -> {
                BuildEngine.start(this.minecraft);
            }).bounds(left, y, panelWidth, 20).build());
            y += 27;

            int gap = 5;
            int half = (panelWidth - gap) / 2;
            addRenderableWidget(Button.builder(Component.literal("Pause / Weiter"), b -> {
                BuildEngine.togglePause();
            }).bounds(left, y, half, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Abbrechen"), b -> {
                BuildEngine.cancel();
            }).bounds(left + half + gap, y, panelWidth - half - gap, 20).build());
        }

        private void addDimensionRow(int left, int y, String label, int which) {
            int value = which == 0 ? width : which == 1 ? length : height;
            int third = panelWidth / 3;
            addRenderableWidget(Button.builder(Component.literal(label + "  −"), b -> {
                changeDimension(which, -1);
                rebuildWidgets();
            }).bounds(left, y, third - 3, 20).build());
            addRenderableWidget(Button.builder(Component.literal(value + "  +"), b -> {
                changeDimension(which, 1);
                rebuildWidgets();
            }).bounds(left + third, y, third - 3, 20).build());
            addRenderableWidget(Button.builder(Component.literal(" + " + label), b -> {
                changeDimension(which, 1);
                rebuildWidgets();
            }).bounds(left + third * 2, y, panelWidth - third * 2, 20).build());
        }

        private void changeDimension(int which, int delta) {
            if (which == 0) width = clamp(width + delta, 1, 48);
            if (which == 1) length = clamp(length + delta, 1, 48);
            if (which == 2) height = clamp(height + delta, 1, 48);
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            super.extractRenderState(graphics, mouseX, mouseY, delta);
            graphics.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
            graphics.centeredText(this.font,
                    Component.literal("Breite: " + width + "   Laenge: " + length + "   Hoehe: " + height),
                    this.width / 2, this.height / 2 - 94, 0xFFB9DCE8);
            graphics.centeredText(this.font,
                    Component.literal("Block: " + MATERIALS.get(Math.floorMod(materialIndex, MATERIALS.size())).getName().getString()),
                    this.width / 2, this.height / 2 + 48, 0xFFB9DCE8);
            graphics.centeredText(this.font,
                    Component.literal(BuildEngine.status()),
                    this.width / 2, this.height - 24, 0xFFFFFFFF);
            graphics.centeredText(this.font,
                    Component.literal("Der passende Block muss in deiner Hotbar liegen."),
                    this.width / 2, this.height - 38, 0xFFAAAAAA);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static BlockHitResult currentBlockHit(Minecraft client) {
        if (client == null || !(client.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK) return null;
        return hit;
    }

    /** Creates connected block shapes and orders them from an anchor outwards. */
    private static final class ShapePlanner {
        static List<BlockPos> create(BlockPos base, Direction facing) {
            Direction forward = facing.getAxis().isHorizontal() ? facing : Direction.SOUTH;
            Direction right = forward.getClockWise();
            Set<BlockPos> all = new HashSet<>();

            int r = Math.max(1, width / 2);
            switch (shape) {
                case WALL -> {
                    for (int y = 0; y < height; y++)
                        for (int x = 0; x < width; x++)
                            add(all, base, right, forward, x, y, 0);
                }
                case FLOOR -> {
                    for (int z = 0; z < length; z++)
                        for (int x = 0; x < width; x++)
                            add(all, base, right, forward, x, 0, z);
                }
                case CUBOID -> {
                    for (int y = 0; y < height; y++)
                        for (int z = 0; z < length; z++)
                            for (int x = 0; x < width; x++) {
                                boolean edge = x == 0 || x == width - 1 || z == 0
                                        || z == length - 1 || y == 0 || y == height - 1;
                                if (!hollow || edge) add(all, base, right, forward, x, y, z);
                            }
                }
                case SPHERE -> {
                    for (int y = 0; y <= 2 * r; y++)
                        for (int z = -r; z <= r; z++)
                            for (int x = -r; x <= r; x++) {
                                int dx = x, dy = y - r, dz = z;
                                int d2 = dx * dx + dy * dy + dz * dz;
                                boolean edge = d2 >= (r - 1) * (r - 1) || y == 0 || y == 2 * r;
                                if (d2 <= r * r && (!hollow || edge))
                                    add(all, base, right, forward, x, y, z);
                            }
                }
                case DOME -> {
                    for (int y = 0; y <= r; y++)
                        for (int z = -r; z <= r; z++)
                            for (int x = -r; x <= r; x++) {
                                int d2 = x * x + y * y + z * z;
                                boolean edge = d2 >= (r - 1) * (r - 1) || y == 0;
                                if (d2 <= r * r && (!hollow || edge))
                                    add(all, base, right, forward, x, y, z);
                            }
                }
                case CYLINDER -> {
                    for (int y = 0; y < height; y++)
                        for (int z = -r; z <= r; z++)
                            for (int x = 0; x <= 2 * r; x++) {
                                int dx = x - r;
                                int d2 = dx * dx + z * z;
                                boolean edge = d2 >= (r - 1) * (r - 1)
                                        || y == 0 || y == height - 1;
                                if (d2 <= r * r && (!hollow || edge))
                                    add(all, base, right, forward, x - r, y, z);
                            }
                }
                case PYRAMID -> {
                    for (int y = 0; y < height; y++) {
                        int inset = hollow ? y : y;
                        int xMax = Math.max(0, width - 1 - inset * 2);
                        int zMax = Math.max(0, length - 1 - inset * 2);
                        for (int z = 0; z <= zMax; z++)
                            for (int x = 0; x <= xMax; x++) {
                                boolean edge = x == 0 || z == 0 || x == xMax || z == zMax;
                                if (!hollow || edge)
                                    add(all, base, right, forward, x + inset, y, z + inset);
                            }
                    }
                }
            }

            // Build a breadth-first path. Every block after the first touches an earlier block.
            Queue<BlockPos> queue = new ArrayDeque<>();
            Set<BlockPos> seen = new LinkedHashSet<>();
            if (!all.contains(base)) return List.of();
            queue.add(base);
            seen.add(base);
            List<BlockPos> ordered = new ArrayList<>(all.size());
            while (!queue.isEmpty()) {
                BlockPos current = queue.remove();
                ordered.add(current);
                for (Direction direction : Direction.values()) {
                    BlockPos next = current.relative(direction);
                    if (all.contains(next) && seen.add(next)) queue.add(next);
                }
            }
            return ordered.size() == all.size() ? ordered : List.of();
        }

        private static void add(Set<BlockPos> positions, BlockPos base, Direction right,
                                Direction forward, int x, int y, int z) {
            int dx = right.getStepX() * x + forward.getStepX() * z;
            int dz = right.getStepZ() * x + forward.getStepZ() * z;
            positions.add(base.offset(dx, y, dz));
        }
    }

    /** Places one block every few ticks, exclusively through vanilla block interaction. */
    private static final class BuildEngine {
        private static final Queue<BlockPos> remaining = new ArrayDeque<>();
        private static final Set<BlockPos> placed = new LinkedHashSet<>();
        private static BlockPos originalAnchor;
        private static Direction originalFace;
        private static Block selectedBlock = Blocks.STONE_BRICKS;
        private static int total;
        private static int tickDelay;
        private static int originalSelectedSlot;
        private static boolean paused;
        private static String state = "Bereit";

        static void start(Minecraft client) {
            if (client == null || client.player == null || client.level == null || client.gameMode == null) {
                state = "Du musst in einer Welt sein.";
                return;
            }
            if (!remaining.isEmpty()) {
                state = "Es laeuft bereits ein Bauvorgang.";
                return;
            }
            BlockHitResult hit = currentBlockHit(client);
            if (hit == null) {
                state = "Schau zuerst auf einen festen Block.";
                return;
            }
            BlockPos base = hit.getBlockPos().relative(hit.getDirection());
            if (!client.level.getBlockState(base).isAir()) {
                state = "Der Startplatz ist nicht frei.";
                return;
            }

            selectedBlock = MATERIALS.get(Math.floorMod(materialIndex, MATERIALS.size()));
            List<BlockPos> plan = ShapePlanner.create(base, client.player.getDirection());
            if (plan.isEmpty()) {
                state = "Diese Form konnte nicht geplant werden.";
                return;
            }
            if (plan.size() > 12000) {
                state = "Zu gross: maximal 12.000 Bloecke pro Bau.";
                return;
            }
            remaining.clear();
            remaining.addAll(plan);
            placed.clear();
            originalAnchor = hit.getBlockPos();
            originalFace = hit.getDirection();
            total = plan.size();
            originalSelectedSlot = client.player.getInventory().getSelectedSlot();
            tickDelay = 0;
            paused = false;
            state = "Bau laeuft: 0 / " + total;
        }

        static void tick(Minecraft client) {
            if (remaining.isEmpty() || paused || client == null || client.player == null
                    || client.level == null || client.gameMode == null) return;
            if (++tickDelay < 2) return;
            tickDelay = 0;

            BlockPos target = remaining.peek();
            if (target == null) return;
            int y = target.getY();
            if (y < client.level.getMinY() || y >= client.level.getMaxY()) {
                cancelWith("Abgebrochen: ausserhalb der Bauhoehe.");
                return;
            }
            if (!client.level.getBlockState(target).isAir()) {
                cancelWith("Abgebrochen: Zielbereich ist nicht frei bei " + target.toShortString());
                return;
            }

            int materialSlot = findMaterialHotbarSlot(client);
            if (materialSlot < 0) {
                paused = true;
                state = "Pausiert: " + selectedBlock.getName().getString()
                        + " fehlt in der Hotbar. Lege den Block dort ab und druecke Weiter.";
                return;
            }
            if (materialSlot != client.player.getInventory().getSelectedSlot()) {
                client.player.getInventory().setSelectedSlot(materialSlot);
            }

            BlockPos anchor = null;
            Direction face = null;
            if (placed.isEmpty()) {
                anchor = originalAnchor;
                face = originalFace;
            } else {
                for (Direction candidateFace : Direction.values()) {
                    BlockPos candidateAnchor = target.relative(candidateFace.getOpposite());
                    if (placed.contains(candidateAnchor)) {
                        anchor = candidateAnchor;
                        face = candidateFace;
                        break;
                    }
                }
            }
            if (anchor == null || face == null) {
                cancelWith("Abgebrochen: Form ist nicht zusammenhaengend platzierbar.");
                return;
            }

            Vec3 hitLocation = Vec3.atCenterOf(anchor).add(
                    face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            BlockHitResult placementHit = new BlockHitResult(hitLocation, face, anchor, false);
            InteractionResult result = client.gameMode.useItemOn(
                    client.player, InteractionHand.MAIN_HAND, placementHit);

            if (!result.consumesAction()) {
                cancelWith("Der Server hat das Setzen abgelehnt. Bau gestoppt.");
                return;
            }

            placed.add(target);
            remaining.remove();
            int done = placed.size();
            state = "Bau laeuft: " + done + " / " + total;
            if (remaining.isEmpty()) {
                state = "Fertig: " + total + " Bloecke gesetzt.";
                restoreSelectedSlot(client);
            }
        }

        static void togglePause() {
            if (paused) resume(); else pause();
        }

        static void pause() {
            if (remaining.isEmpty()) { state = "Kein Bauvorgang aktiv."; return; }
            paused = true;
            state = "Pausiert: " + placed.size() + " / " + total;
        }

        static void resume() {
            if (remaining.isEmpty()) { state = "Kein pausierter Bauvorgang."; return; }
            paused = false;
            state = "Bau laeuft: " + placed.size() + " / " + total;
        }

        static void cancel() {
            if (remaining.isEmpty()) {
                state = "Kein Bauvorgang aktiv.";
                return;
            }
            remaining.clear();
            paused = false;
            state = "Bau abgebrochen nach " + placed.size() + " von " + total + " Bloecken.";
            restoreSelectedSlot(Minecraft.getInstance());
        }

        private static int findMaterialHotbarSlot(Minecraft client) {
            for (int slot = 0; slot < 9; slot++) {
                var stack = client.player.getInventory().getItem(slot);
                if (!stack.isEmpty() && stack.getItem() == selectedBlock.asItem()) return slot;
            }
            return -1;
        }

        private static void restoreSelectedSlot(Minecraft client) {
            if (client != null && client.player != null && originalSelectedSlot >= 0
                    && client.player.getInventory().getSelectedSlot() != originalSelectedSlot) {
                client.player.getInventory().setSelectedSlot(originalSelectedSlot);
            }
        }

        private static void cancelWith(String message) {
            remaining.clear();
            paused = false;
            state = message;
            restoreSelectedSlot(Minecraft.getInstance());
        }

        static String status() {
            return state;
        }
    }
}
