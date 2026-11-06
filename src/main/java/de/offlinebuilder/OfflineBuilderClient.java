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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.stream.Collectors;

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
    private static String commandPrefix = "+";
    private static int maxBuildBlocks = 12000;
    private static int buildDelayTicks = 2;

    private static Shape shape = Shape.WALL;
    private static int width = 8;
    private static int length = 8;
    private static int height = 4;
    private static boolean hollow = false;
    private static RoofStyle roofStyle = RoofStyle.GABLE;
    private static String uiStatus = "Bereit. B druecken, um OfflineBuilder zu oeffnen.";

    @Override
    public void onInitializeClient() {
        SettingsStore.load(Minecraft.getInstance());
        openMenuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.offlinebuilder.open", InputConstants.KEY_B, CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openMenuKey.consumeClick()) {
                if (client.gui.screen() == null) {
                    client.gui.setScreen(new BuilderScreen());
                }
            }
            BlockBreaker.tick(client);
            BuildEngine.tick(client);
        });

        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            if (!message.startsWith(commandPrefix)) return true;
            handleBuilderCommand(message.substring(commandPrefix.length()));
            return false;
        });
    }


    /** Local chat commands use '+' so they do not collide with common blueprint mods. */
    private static void handleBuilderCommand(String raw) {
        Minecraft client = Minecraft.getInstance();
        String trimmed = raw.trim();
        String[] args = trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            tell(client, "Prefix: " + commandPrefix + " | Befehle: " + commandPrefix
                    + "menu | " + commandPrefix + "break | " + commandPrefix
                    + "build <form> ... | " + commandPrefix + "settings show");
            return;
        }
        if (args[0].equalsIgnoreCase("settings")) {
            tell(client, SettingsStore.handle(client, args));
            return;
        }
        if (args[0].equalsIgnoreCase("menu")) {
            if (client.gui.screen() == null) client.gui.setScreen(new BuilderScreen());
            return;
        }
        if (args[0].equalsIgnoreCase("break")) {
            if (args.length > 1 && (args[1].equalsIgnoreCase("cancel") || args[1].equalsIgnoreCase("stop"))) {
                BlockBreaker.cancel(client);
            } else {
                BlockBreaker.start(client);
            }
            tell(client, BlockBreaker.status());
            return;
        }
        if (!args[0].equalsIgnoreCase("build")) {
            tell(client, "Unbekannter OfflineBuilder-Befehl. Nutze +help.");
            return;
        }
        if (args.length < 2) {
            tell(client, "Nutzung: +build <wall|floor|house|pillar|stairs|bridge|arch|circle|ring|cone|arena|tunnel|cuboid|sphere|dome|cylinder|pyramid> ...");
            return;
        }
        String operation = args[1].toLowerCase(Locale.ROOT);
        if (operation.equals("pause")) { BuildEngine.pause(); tell(client, BuildEngine.status()); return; }
        if (operation.equals("resume")) { BuildEngine.resume(); tell(client, BuildEngine.status()); return; }
        if (operation.equals("cancel")) { BuildEngine.cancel(); tell(client, BuildEngine.status()); return; }
        if (operation.equals("undo")) {
            BuildEngine.undo(client);
            tell(client, BuildEngine.status());
            return;
        }
        if (operation.equals("redo")) {
            BuildEngine.redo(client);
            tell(client, BuildEngine.status());
            return;
        }
        if (operation.equals("save") || operation.equals("export")) {
            tell(client, args.length > 2
                    ? BlueprintStore.save(client, args[2])
                    : "Nutzung: +build save <name>");
            return;
        }
        if (operation.equals("load") || operation.equals("import")) {
            tell(client, args.length > 2
                    ? BlueprintStore.load(client, args[2])
                    : "Nutzung: +build load <name>");
            return;
        }
        if (operation.equals("delete")) {
            tell(client, args.length > 2
                    ? BlueprintStore.delete(client, args[2])
                    : "Nutzung: +build delete <name>");
            return;
        }
        if (operation.equals("list")) {
            tell(client, BlueprintStore.list(client));
            return;
        }
        if (operation.equals("saveblocks") || operation.equals("exportblocks")) {
            tell(client, args.length > 2
                    ? BlueprintStore.saveStructure(client, args[2])
                    : "Nutzung: " + commandPrefix + "build saveblocks <name>");
            return;
        }
        if (operation.equals("loadblocks") || operation.equals("importblocks")) {
            tell(client, args.length > 2
                    ? BlueprintStore.loadStructure(client, args[2])
                    : "Nutzung: " + commandPrefix + "build loadblocks <name>");
            return;
        }
        if (operation.equals("listblocks")) {
            tell(client, BlueprintStore.listStructures(client));
            return;
        }
        if (operation.equals("deleteblocks")) {
            tell(client, args.length > 2
                    ? BlueprintStore.deleteStructure(client, args[2])
                    : "Nutzung: " + commandPrefix + "build deleteblocks <name>");
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
            case "house", "haus" -> shape = Shape.HOUSE;
            case "pillar", "saeule", "säule" -> shape = Shape.PILLAR;
            case "stairs", "treppe", "treppen" -> shape = Shape.STAIRS;
            case "bridge", "bruecke", "brücke" -> shape = Shape.BRIDGE;
            case "arch", "bogen" -> shape = Shape.ARCH;
            case "circle", "kreis" -> shape = Shape.CIRCLE;
            case "ring" -> shape = Shape.RING;
            case "cone", "kegel" -> shape = Shape.CONE;
            case "arena" -> shape = Shape.ARENA;
            case "tunnel" -> shape = Shape.TUNNEL;
            default -> { tell(client, "Unbekannte Form: " + operation + ". Nutze +help."); return; }
        }

        for (int i = 0; i + 1 < args.length; i++) {
            if (args[i].equalsIgnoreCase("roof")) {
                RoofStyle parsedRoof = RoofStyle.parse(args[i + 1]);
                if (parsedRoof == null) {
                    tell(client, "Dachform: flat, gable, hip oder shed.");
                    return;
                }
                roofStyle = parsedRoof;
            }
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
        if (shape == Shape.SPHERE || shape == Shape.DOME || shape == Shape.CYLINDER
                || shape == Shape.CIRCLE || shape == Shape.RING || shape == Shape.CONE
                || shape == Shape.ARENA) {
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
        PYRAMID("Pyramide"),
        HOUSE("Haus"),
        PILLAR("Saeule"),
        STAIRS("Treppe"),
        BRIDGE("Bruecke"),
        ARCH("Bogen"),
        CIRCLE("Kreis"),
        RING("Ring"),
        CONE("Kegel"),
        ARENA("Arena"),
        TUNNEL("Tunnel");

        final String label;
        Shape(String label) { this.label = label; }
        Shape next() { return values()[(ordinal() + 1) % values().length]; }
    }

    private enum RoofStyle {
        FLAT("Flachdach"),
        GABLE("Satteldach"),
        HIP("Walmdach"),
        SHED("Pultdach");

        final String label;
        RoofStyle(String label) { this.label = label; }
        RoofStyle next() { return values()[(ordinal() + 1) % values().length]; }

        static RoofStyle parse(String value) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "flat", "flach" -> FLAT;
                case "gable", "sattel" -> GABLE;
                case "hip", "walm" -> HIP;
                case "shed", "pult" -> SHED;
                default -> null;
            };
        }
    }


    private static final class MaterialScreen extends Screen {
        private final Screen parent;
        private EditBox search;
        private int page = 0;
        private int pageSize = 8;
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
            int w = Math.max(1, Math.min(360, width - 24));
            int left = Math.max(0, (width - w) / 2);
            pageSize = Math.max(1, Math.min(8, (height - 150) / 26));
            search = new EditBox(font, left, 34, w, 20, Component.literal("Block suchen"));
            search.setValue(materialSearch);
            search.setResponder(value -> { materialSearch = value; page = 0; rebuildWidgets(); });
            addRenderableWidget(search);
            List<Block> found = matches();
            int pages = Math.max(1, (found.size() + pageSize - 1) / pageSize);
            page = Math.min(page, pages - 1);
            for (int i = page * pageSize; i < Math.min(found.size(), (page + 1) * pageSize); i++) {
                Block block = found.get(i); int index = MATERIALS.indexOf(block);
                String id = String.valueOf(BuiltInRegistries.BLOCK.getKey(block));
                addRenderableWidget(Button.builder(Component.literal(block.getName().getString() + " [" + id + "]"), b -> {
                    materialIndex = index; minecraft.gui.setScreen(parent);
                }).bounds(left, 62 + (i - page * pageSize) * 26, w, 22).build());
            }
            int sideWidth = Math.min(40, Math.max(1, w / 4));
            int middleWidth = Math.max(1, w - sideWidth * 2 - 8);
            int navY = Math.max(90, height - 55);
            addRenderableWidget(Button.builder(Component.literal("<"), b -> { page = Math.max(0,page-1); rebuildWidgets(); }).bounds(left, navY, sideWidth, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Seite " + (page+1) + "/" + pages + " · " + found.size()), b -> {}).bounds(left+sideWidth+4,navY,middleWidth,20).build());
            addRenderableWidget(Button.builder(Component.literal(">"), b -> { page = Math.min(pages-1,page+1); rebuildWidgets(); }).bounds(left+w-sideWidth,navY,sideWidth,20).build());
            addRenderableWidget(Button.builder(Component.literal("Zurueck"), b -> minecraft.gui.setScreen(parent)).bounds(left,Math.max(navY+25,height-28),w,20).build());
            setInitialFocus(search);
        }
        @Override public void extractRenderState(GuiGraphicsExtractor graphics,int mouseX,int mouseY,float delta) {
            super.extractRenderState(graphics,mouseX,mouseY,delta);
            graphics.centeredText(font,title,width/2,12,0xFFFFFFFF);
        }
        @Override public boolean isPauseScreen() { return false; }
    }

    private static final class SettingsScreen extends Screen {
        private final Screen parent;
        private EditBox prefixField;
        private EditBox maxBlocksField;
        private EditBox speedField;
        private int panelLeft;
        private int panelWidth;
        private String feedback = "";

        SettingsScreen(Screen parent) {
            super(Component.literal("OfflineBuilder - Einstellungen"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            panelWidth = Math.max(1, Math.min(330, width - 20));
            panelLeft = Math.max(0, (width - panelWidth) / 2);
            int y = 48;
            prefixField = new EditBox(font, panelLeft, y, panelWidth, 20, Component.literal("Befehls-Prefix"));
            prefixField.setValue(commandPrefix);
            addRenderableWidget(prefixField);
            y += 46;
            maxBlocksField = new EditBox(font, panelLeft, y, panelWidth, 20, Component.literal("Maximale Blockzahl"));
            maxBlocksField.setValue(Integer.toString(maxBuildBlocks));
            addRenderableWidget(maxBlocksField);
            y += 46;
            speedField = new EditBox(font, panelLeft, y, panelWidth, 20, Component.literal("Bauintervall"));
            speedField.setValue(Integer.toString(buildDelayTicks));
            addRenderableWidget(speedField);

            int gap = 5;
            int half = Math.max(1, (panelWidth - gap) / 2);
            addRenderableWidget(Button.builder(Component.literal("Speichern"), b -> save())
                    .bounds(panelLeft, height - 52, half, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Zurueck"), b -> minecraft.gui.setScreen(parent))
                    .bounds(panelLeft + half + gap, height - 52, panelWidth - half - gap, 20).build());
            setInitialFocus(prefixField);
        }

        private void save() {
            if (minecraft == null) return;
            String prefix = prefixField.getValue().trim();
            int maxBlocks;
            int speed;
            try {
                maxBlocks = Integer.parseInt(maxBlocksField.getValue().trim());
                speed = Integer.parseInt(speedField.getValue().trim());
            } catch (NumberFormatException exception) {
                feedback = "Blocklimit und Geschwindigkeit muessen Zahlen sein.";
                return;
            }

            String prefixResult = SettingsStore.handle(minecraft,
                    new String[]{"settings", "prefix", prefix});
            if (!prefixResult.startsWith("Einstellungen gespeichert.")) {
                feedback = prefixResult;
                return;
            }
            SettingsStore.handle(minecraft,
                    new String[]{"settings", "maxblocks", Integer.toString(maxBlocks)});
            SettingsStore.handle(minecraft,
                    new String[]{"settings", "speed", Integer.toString(speed)});
            tell(minecraft, "Einstellungen gespeichert.");
            minecraft.gui.setScreen(parent);
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            graphics.fill(panelLeft - 10, 24, panelLeft + panelWidth + 10, height - 20, 0xD91A222C);
            graphics.fill(panelLeft - 10, 24, panelLeft + panelWidth + 10, 26, 0xFF27A9C4);
            super.extractRenderState(graphics, mouseX, mouseY, delta);
            graphics.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);
            graphics.centeredText(font, Component.literal("Befehls-Prefix"), width / 2, 34, 0xFFB9DCE8);
            graphics.centeredText(font, Component.literal("Maximale Bloecke pro Bau (100-50000)"),
                    width / 2, 80, 0xFFB9DCE8);
            graphics.centeredText(font, Component.literal("Bauintervall in Ticks (1-20)"),
                    width / 2, 126, 0xFFB9DCE8);
            if (!feedback.isEmpty()) {
                graphics.centeredText(font, Component.literal(feedback), width / 2, height - 70, 0xFFFFCC66);
            }
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }

    /**
     * Rebuilt main editor screen.
     *
     * The old screen used three tiny +/- buttons for every dimension and rebuilt the
     * complete widget tree on every click. This version uses stable numeric fields and
     * computes every column from the usable width, never creating a negative button size.
     */
    private static final class BuilderScreen extends Screen {
        private static final int ACCENT = 0xFF27B7C9;
        private static final int TEXT = 0xFFE8F2F5;
        private static final int MUTED = 0xFF9BB0B9;
        private int panelLeft;
        private int panelWidth;
        private int panelTop;
        private int panelBottom;
        private EditBox widthInput;
        private EditBox lengthInput;
        private EditBox heightInput;
        private String screenStatus = "";

        BuilderScreen() {
            super(Component.literal("OfflineBuilder"));
        }

        @Override
        protected void init() {
            dimensionLabels.clear();
            screenStatus = "";
            // All coordinates are GUI-scaled screen coordinates. Keep a consistent
            // centered panel and clamp every computed control width to at least 1 px.
            panelWidth = Math.max(1, Math.min(380, this.width - 24));
            panelLeft = Math.max(0, (this.width - panelWidth) / 2);
            panelTop = Math.max(10, (this.height - 238) / 2);

            int gap = 6;
            int halfA = Math.max(1, (panelWidth - gap) / 2);
            int halfB = Math.max(1, panelWidth - halfA - gap);
            int rowY = panelTop + 36;

            addRenderableWidget(Button.builder(Component.literal("×"), b -> {
                if (minecraft != null) minecraft.gui.setScreen(null);
            }).bounds(panelLeft + Math.max(0, panelWidth - 24), panelTop + 7, 20, 20).build());

            // Row 1: shape and roof profile.
            addRenderableWidget(Button.builder(Component.literal("FORM  ·  " + shape.label + "  ▾"), b -> {
                commitDimensions();
                shape = shape.next();
                rebuildWidgets();
            }).bounds(panelLeft, rowY, halfA, 22).build());
            addRenderableWidget(Button.builder(Component.literal("DACH  ·  " + roofStyle.label + "  ▾"), b -> {
                commitDimensions();
                roofStyle = roofStyle.next();
                rebuildWidgets();
            }).bounds(panelLeft + halfA + gap, rowY, halfB, 22).build());

            // Row 2: proper editable numeric inputs, not mouse-sensitive +/- strips.
            int dimLabelY = rowY + 31;
            int inputY = dimLabelY + 13;
            int colGap = 8;
            int colWidth = Math.max(1, (panelWidth - colGap * 2) / 3);
            int x0 = panelLeft;
            int x1 = panelLeft + colWidth + colGap;
            int x2 = panelLeft + (colWidth + colGap) * 2;
            addDimensionInput("BREITE", width, x0, dimLabelY, colWidth, inputY, 0);
            addDimensionInput("LÄNGE", length, x1, dimLabelY, colWidth, inputY, 1);
            addDimensionInput("HÖHE", height, x2, dimLabelY, Math.max(1, panelWidth - (colWidth + colGap) * 2),
                    inputY, 2);

            int materialY = inputY + 31;
            addRenderableWidget(Button.builder(
                    Component.literal("MATERIAL SUCHEN  ·  " + MATERIALS.get(Math.floorMod(materialIndex, MATERIALS.size())).getName().getString()),
                    b -> {
                        commitDimensions();
                        this.minecraft.gui.setScreen(new MaterialScreen(this));
                    }).bounds(panelLeft, materialY, panelWidth, 22).build());

            int optionY = materialY + 29;
            addRenderableWidget(Button.builder(Component.literal(hollow ? "HOHLBAU  ·  AKTIV" : "HOHLBAU  ·  AUS"), b -> {
                commitDimensions();
                hollow = !hollow;
                rebuildWidgets();
            }).bounds(panelLeft, optionY, halfA, 22).build());
            addRenderableWidget(Button.builder(Component.literal("EINSTELLUNGEN"), b -> {
                commitDimensions();
                this.minecraft.gui.setScreen(new SettingsScreen(this));
            }).bounds(panelLeft + halfA + gap, optionY, halfB, 22).build());

            int actionY = optionY + 31;
            addRenderableWidget(Button.builder(Component.literal("VORSCHAU"), b -> showPreview())
                    .bounds(panelLeft, actionY, halfA, 24).build());
            addRenderableWidget(Button.builder(Component.literal("BAU STARTEN"), b -> startBuild())
                    .bounds(panelLeft + halfA + gap, actionY, halfB, 24).build());

            int controlY = actionY + 31;
            addRenderableWidget(Button.builder(Component.literal("PAUSE / WEITER"), b -> {
                BuildEngine.togglePause();
                screenStatus = BuildEngine.status();
            }).bounds(panelLeft, controlY, halfA, 22).build());
            addRenderableWidget(Button.builder(Component.literal("ABBRECHEN"), b -> {
                BuildEngine.cancel();
                screenStatus = BuildEngine.status();
            }).bounds(panelLeft + halfA + gap, controlY, halfB, 22).build());

            int footerY = controlY + 29;
            panelBottom = Math.min(this.height - 4, footerY + 22);
            if (panelBottom < controlY + 22) panelBottom = Math.min(this.height, controlY + 24);

            setInitialFocus(widthInput);
        }

        private void addDimensionInput(String label, int value, int x, int labelY,
                                       int fieldWidth, int fieldY, int which) {
            graphicsLabel(label, x, labelY);
            EditBox field = new EditBox(font, x, fieldY, Math.max(1, fieldWidth), 22,
                    Component.literal(label));
            field.setValue(Integer.toString(value));
            field.setMaxLength(2);
            field.setFilter(text -> text.isEmpty() || text.matches("\\d{0,2}"));
            if (which == 0) widthInput = field;
            else if (which == 1) lengthInput = field;
            else heightInput = field;
            addRenderableWidget(field);
        }

        private void graphicsLabel(String label, int x, int y) {
            // Labels are drawn in extractRenderState below so they remain crisp and
            // independent of widget focus and mouse state.
            dimensionLabels.add(new DimensionLabel(label, x, y));
        }

        private final List<DimensionLabel> dimensionLabels = new ArrayList<>();

        private record DimensionLabel(String text, int x, int y) {}

        private boolean commitDimensions() {
            Integer newWidth = parseDimension(widthInput, "Breite");
            Integer newLength = parseDimension(lengthInput, "Länge");
            Integer newHeight = parseDimension(heightInput, "Höhe");
            if (newWidth == null || newLength == null || newHeight == null) return false;
            width = newWidth;
            length = newLength;
            height = newHeight;
            return true;
        }

        private Integer parseDimension(EditBox input, String label) {
            if (input == null) return null;
            try {
                int value = Integer.parseInt(input.getValue());
                if (value < 1 || value > 48) {
                    screenStatus = label + " muss zwischen 1 und 48 liegen.";
                    return null;
                }
                return value;
            } catch (NumberFormatException exception) {
                screenStatus = "Bitte für " + label + " eine Zahl von 1 bis 48 eingeben.";
                return null;
            }
        }

        private void showPreview() {
            if (!commitDimensions()) return;
            if (this.minecraft == null || this.minecraft.player == null) {
                screenStatus = "Öffne eine Welt, bevor du eine Vorschau berechnest.";
                return;
            }
            BlockHitResult hit = currentBlockHit(this.minecraft);
            if (hit == null) {
                screenStatus = "Schau auf einen festen Block als Startpunkt.";
                return;
            }
            List<BlockPos> plan = ShapePlanner.create(
                    hit.getBlockPos().relative(hit.getDirection()),
                    this.minecraft.player.getDirection());
            screenStatus = plan.isEmpty()
                    ? "Diese Form konnte nicht geplant werden."
                    : "Vorschau: " + plan.size() + " Blöcke. Noch nichts gesetzt.";
        }

        private void startBuild() {
            if (!commitDimensions()) return;
            BuildEngine.start(this.minecraft);
            screenStatus = BuildEngine.status();
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            // Panel first, then vanilla widgets.
            graphics.fill(0, 0, this.width, this.height, 0x99070D13);
            graphics.fill(panelLeft - 8, panelTop + 2, panelLeft + panelWidth + 8, panelBottom,
                    0xF018222B);
            graphics.fill(panelLeft - 8, panelTop + 2, panelLeft + panelWidth + 8, panelTop + 4,
                    ACCENT);
            graphics.fill(panelLeft - 8, panelTop + 2, panelLeft - 6, panelBottom, ACCENT);
            graphics.fill(panelLeft + panelWidth + 6, panelTop + 2, panelLeft + panelWidth + 8,
                    panelBottom, ACCENT);

            super.extractRenderState(graphics, mouseX, mouseY, delta);

            graphics.centeredText(font, Component.literal("OFFLINE BUILDER"),
                    width / 2, panelTop + 10, TEXT);
            graphics.centeredText(font, Component.literal("Formen bauen  ·  lokal  ·  ohne Cloud"),
                    width / 2, panelTop + 24, MUTED);
            for (DimensionLabel label : dimensionLabels) {
                graphics.drawString(font, label.text(), label.x(), label.y(), MUTED);
            }

            int footerY = Math.min(height - 17, panelBottom - 15);
            String activeStatus = screenStatus.isEmpty() ? BuildEngine.status() : screenStatus;
            graphics.centeredText(font, Component.literal(activeStatus), width / 2, footerY, TEXT);
            graphics.centeredText(font,
                    Component.literal("Bauen läuft auch bei geschlossenem Menü weiter  ·  Esc = Schließen"),
                    width / 2, height - 8, MUTED);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }

    /** Persisted local settings; no network or cloud access. */
    private static final class SettingsStore {
        private static Path file(Minecraft client) {
            return client.gameDirectory.toPath().resolve("config")
                    .resolve("offlinebuilder").resolve("settings.properties");
        }

        static void load(Minecraft client) {
            if (client == null || !Files.isRegularFile(file(client))) return;
            try (InputStream input = Files.newInputStream(file(client))) {
                Properties data = new Properties();
                data.load(input);
                commandPrefix = data.getProperty("prefix", "+");
                if (commandPrefix.isBlank() || commandPrefix.length() > 3
                        || commandPrefix.contains("/") || commandPrefix.chars().anyMatch(Character::isWhitespace)) {
                    commandPrefix = "+";
                }
                maxBuildBlocks = clamp(Integer.parseInt(data.getProperty("maxBlocks", "12000")), 100, 50000);
                buildDelayTicks = clamp(Integer.parseInt(data.getProperty("buildDelayTicks", "2")), 1, 20);
            } catch (IOException | NumberFormatException exception) {
                commandPrefix = "+";
                maxBuildBlocks = 12000;
                buildDelayTicks = 2;
            }
        }

        static String handle(Minecraft client, String[] args) {
            if (args.length < 2 || args[1].equalsIgnoreCase("show")) {
                return "Einstellungen: Prefix='" + commandPrefix + "', max. " + maxBuildBlocks
                        + " Bloecke, Bauintervall " + buildDelayTicks + " Tick(s). "
                        + "Aendern: " + commandPrefix + "settings prefix <zeichen> | maxblocks <zahl> | speed <1-20>.";
            }
            try {
                switch (args[1].toLowerCase(Locale.ROOT)) {
                    case "prefix" -> {
                        if (args.length < 3 || args[2].isBlank() || args[2].length() > 3
                                || args[2].contains("/") || args[2].chars().anyMatch(Character::isWhitespace)) {
                            return "Das Praefix muss 1-3 Zeichen haben, darf keine Leerzeichen oder '/' enthalten.";
                        }
                        commandPrefix = args[2];
                    }
                    case "maxblocks" -> {
                        if (args.length < 3) return "Nutzung: " + commandPrefix + "settings maxblocks <100-50000>";
                        maxBuildBlocks = clamp(Integer.parseInt(args[2]), 100, 50000);
                    }
                    case "speed" -> {
                        if (args.length < 3) return "Nutzung: " + commandPrefix + "settings speed <1-20>";
                        buildDelayTicks = clamp(Integer.parseInt(args[2]), 1, 20);
                    }
                    default -> {
                        return "Optionen: prefix, maxblocks, speed oder show.";
                    }
                }
                save(client);
                return "Einstellungen gespeichert. " + handle(client, new String[]{"settings", "show"});
            } catch (NumberFormatException exception) {
                return "Bitte eine gueltige Zahl eingeben.";
            }
        }

        private static void save(Minecraft client) {
            if (client == null) return;
            try {
                Files.createDirectories(file(client).getParent());
                Properties data = new Properties();
                data.setProperty("prefix", commandPrefix);
                data.setProperty("maxBlocks", Integer.toString(maxBuildBlocks));
                data.setProperty("buildDelayTicks", Integer.toString(buildDelayTicks));
                try (OutputStream output = Files.newOutputStream(file(client))) {
                    data.store(output, "OfflineBuilder local settings");
                }
            } catch (IOException ignored) {
                // The current session keeps the setting even when the file cannot be written.
            }
        }
    }

    /** Local, offline presets for the current shape, dimensions and material. */
    private static final class BlueprintStore {
        private static Path directory(Minecraft client) {
            return client.gameDirectory.toPath().resolve("config")
                    .resolve("offlinebuilder").resolve("blueprints");
        }

        private static boolean validName(String name) {
            return name != null && name.matches("[A-Za-z0-9_-]{1,32}");
        }

        private static Path file(Minecraft client, String name) {
            return directory(client).resolve(name + ".obp").normalize();
        }

        static String save(Minecraft client, String name) {
            if (client == null || !validName(name)) {
                return "Ungueltiger Name. Erlaubt: 1-32 Zeichen (A-Z, 0-9, _ oder -).";
            }
            try {
                Files.createDirectories(directory(client));
                Block material = MATERIALS.get(Math.floorMod(materialIndex, MATERIALS.size()));
                var id = BuiltInRegistries.BLOCK.getKey(material);
                Properties data = new Properties();
                data.setProperty("shape", shape.name());
                data.setProperty("width", Integer.toString(width));
                data.setProperty("length", Integer.toString(length));
                data.setProperty("height", Integer.toString(height));
                data.setProperty("hollow", Boolean.toString(hollow));
                data.setProperty("roof", roofStyle.name());
                data.setProperty("material", id == null ? "minecraft:stone_bricks" : id.toString());
                try (OutputStream output = Files.newOutputStream(file(client, name))) {
                    data.store(output, "OfflineBuilder local blueprint preset");
                }
                return "Preset '" + name + "' lokal gespeichert.";
            } catch (IOException exception) {
                return "Speichern fehlgeschlagen: " + exception.getMessage();
            }
        }

        static String load(Minecraft client, String name) {
            if (client == null || !validName(name)) {
                return "Ungueltiger Name. Erlaubt: 1-32 Zeichen (A-Z, 0-9, _ oder -).";
            }
            Path path = file(client, name);
            if (!Files.isRegularFile(path)) return "Preset '" + name + "' wurde nicht gefunden.";
            try (InputStream input = Files.newInputStream(path)) {
                Properties data = new Properties();
                data.load(input);
                Shape loadedShape = Shape.valueOf(data.getProperty("shape", "WALL"));
                RoofStyle loadedRoof = RoofStyle.valueOf(data.getProperty("roof", "GABLE"));
                int loadedMaterial = materialIndex(data.getProperty("material", "minecraft:stone_bricks"));
                if (loadedMaterial < 0) return "Das gespeicherte Material ist in dieser Minecraft-Installation nicht verfuegbar.";
                int loadedWidth = clamp(Integer.parseInt(data.getProperty("width", "8")), 1, 48);
                int loadedLength = clamp(Integer.parseInt(data.getProperty("length", "8")), 1, 48);
                int loadedHeight = clamp(Integer.parseInt(data.getProperty("height", "4")), 1, 48);
                shape = loadedShape;
                roofStyle = loadedRoof;
                materialIndex = loadedMaterial;
                width = loadedWidth;
                length = loadedLength;
                height = loadedHeight;
                hollow = Boolean.parseBoolean(data.getProperty("hollow", "false"));
                return "Preset '" + name + "' geladen: " + shape.label + " "
                        + width + "x" + length + "x" + height + ".";
            } catch (IOException | IllegalArgumentException exception) {
                return "Laden fehlgeschlagen: " + exception.getMessage();
            }
        }

        static String list(Minecraft client) {
            if (client == null) return "Minecraft ist noch nicht bereit.";
            Path dir = directory(client);
            if (!Files.isDirectory(dir)) return "Noch keine lokalen Presets gespeichert.";
            try (var files = Files.list(dir)) {
                String names = files.filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .filter(name -> name.endsWith(".obp"))
                        .map(name -> name.substring(0, name.length() - 4))
                        .sorted().limit(20).collect(Collectors.joining(", "));
                return names.isEmpty() ? "Noch keine lokalen Presets gespeichert."
                        : "Lokale Presets: " + names;
            } catch (IOException exception) {
                return "Liste konnte nicht gelesen werden: " + exception.getMessage();
            }
        }

        static String delete(Minecraft client, String name) {
            if (client == null || !validName(name)) {
                return "Ungueltiger Name. Erlaubt: 1-32 Zeichen (A-Z, 0-9, _ oder -).";
            }
            try {
                return Files.deleteIfExists(file(client, name))
                        ? "Preset '" + name + "' geloescht."
                        : "Preset '" + name + "' wurde nicht gefunden.";
            } catch (IOException exception) {
                return "Loeschen fehlgeschlagen: " + exception.getMessage();
            }
        }

        private static Path structureFile(Minecraft client, String name) {
            return directory(client).resolve(name + ".obs").normalize();
        }

        static String saveStructure(Minecraft client, String name) {
            if (client == null || !validName(name)) {
                return "Ungueltiger Name. Erlaubt: 1-32 Zeichen (A-Z, 0-9, _ oder -).";
            }
            List<BlockPos> offsets = BuildEngine.lastStructureOffsets();
            if (offsets.isEmpty()) {
                return "Es gibt noch keinen fertig gebauten Bauplan. Baue zuerst eine Form.";
            }
            Block material = BuildEngine.lastStructureMaterial();
            var id = BuiltInRegistries.BLOCK.getKey(material);
            if (id == null) return "Das verwendete Material konnte nicht gespeichert werden.";
            try {
                Files.createDirectories(directory(client));
                Properties data = new Properties();
                data.setProperty("format", "offlinebuilder-structure-v1");
                data.setProperty("count", Integer.toString(offsets.size()));
                data.setProperty("material", id.toString());
                for (int i = 0; i < offsets.size(); i++) {
                    BlockPos pos = offsets.get(i);
                    data.setProperty("p." + i, pos.getX() + "," + pos.getY() + "," + pos.getZ());
                }
                try (OutputStream output = Files.newOutputStream(structureFile(client, name))) {
                    data.store(output, "OfflineBuilder relative block structure");
                }
                return "Struktur '" + name + "' mit " + offsets.size()
                        + " Blockpositionen lokal gespeichert (.obs).";
            } catch (IOException exception) {
                return "Struktur konnte nicht gespeichert werden: " + exception.getMessage();
            }
        }

        static String loadStructure(Minecraft client, String name) {
            if (client == null || !validName(name)) {
                return "Ungueltiger Name. Erlaubt: 1-32 Zeichen (A-Z, 0-9, _ oder -).";
            }
            Path path = structureFile(client, name);
            if (!Files.isRegularFile(path)) return "Struktur '" + name + "' wurde nicht gefunden.";
            try (InputStream input = Files.newInputStream(path)) {
                Properties data = new Properties();
                data.load(input);
                if (!"offlinebuilder-structure-v1".equals(data.getProperty("format"))) {
                    return "Unbekanntes Strukturformat.";
                }
                int count = Integer.parseInt(data.getProperty("count", "0"));
                if (count < 1 || count > maxBuildBlocks) {
                    return "Die Struktur hat eine ungueltige oder zu grosse Blockzahl (" + count + ").";
                }
                int material = materialIndex(data.getProperty("material", ""));
                if (material < 0) return "Das gespeicherte Material ist hier nicht verfuegbar.";
                List<BlockPos> offsets = new ArrayList<>(count);
                Set<BlockPos> unique = new HashSet<>();
                for (int i = 0; i < count; i++) {
                    String[] xyz = data.getProperty("p." + i, "").split(",");
                    if (xyz.length != 3) return "Die Strukturdatei ist unvollstaendig (Position " + i + ").";
                    int x = Integer.parseInt(xyz[0].trim());
                    int y = Integer.parseInt(xyz[1].trim());
                    int z = Integer.parseInt(xyz[2].trim());
                    if (Math.abs(x) > 256 || Math.abs(y) > 256 || Math.abs(z) > 256) {
                        return "Die Struktur enthaelt ungueltig grosse Koordinaten.";
                    }
                    BlockPos offset = new BlockPos(x, y, z);
                    if (!unique.add(offset)) return "Die Struktur enthaelt doppelte Blockpositionen.";
                    offsets.add(offset);
                }
                if (!offsets.get(0).equals(BlockPos.ZERO)) {
                    return "Die Strukturdatei hat keinen gueltigen Startpunkt.";
                }
                return BuildEngine.startStructure(client, offsets, MATERIALS.get(material));
            } catch (IOException | NumberFormatException exception) {
                return "Struktur konnte nicht geladen werden: " + exception.getMessage();
            }
        }

        static String listStructures(Minecraft client) {
            if (client == null) return "Minecraft ist noch nicht bereit.";
            Path dir = directory(client);
            if (!Files.isDirectory(dir)) return "Noch keine Blockstrukturen gespeichert.";
            try (var files = Files.list(dir)) {
                String names = files.filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .filter(name -> name.endsWith(".obs"))
                        .map(name -> name.substring(0, name.length() - 4))
                        .sorted().limit(20).collect(Collectors.joining(", "));
                return names.isEmpty() ? "Noch keine Blockstrukturen gespeichert."
                        : "Gespeicherte Blockstrukturen: " + names;
            } catch (IOException exception) {
                return "Strukturliste konnte nicht gelesen werden: " + exception.getMessage();
            }
        }

        static String deleteStructure(Minecraft client, String name) {
            if (client == null || !validName(name)) {
                return "Ungueltiger Name. Erlaubt: 1-32 Zeichen (A-Z, 0-9, _ oder -).";
            }
            try {
                return Files.deleteIfExists(structureFile(client, name))
                        ? "Struktur '" + name + "' geloescht."
                        : "Struktur '" + name + "' wurde nicht gefunden.";
            } catch (IOException exception) {
                return "Struktur konnte nicht geloescht werden: " + exception.getMessage();
            }
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

    /**
     * Moves an item from the main inventory into an empty hotbar slot using a normal
     * inventory SWAP transaction. No existing hotbar item is overwritten.
     */
    private static int moveInventoryItemToFreeHotbar(Minecraft client, int sourceSlot) {
        if (client == null || client.player == null || client.gameMode == null
                || client.player.containerMenu != client.player.inventoryMenu) return -1;
        for (int hotbarSlot = 0; hotbarSlot < 9; hotbarSlot++) {
            if (client.player.getInventory().getItem(hotbarSlot).isEmpty()) {
                client.gameMode.handleContainerInput(
                        client.player.inventoryMenu.containerId,
                        sourceSlot,
                        hotbarSlot,
                        net.minecraft.world.inventory.ContainerInput.SWAP,
                        client.player);
                return hotbarSlot;
            }
        }
        return -1;
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
                        int inset = y;
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
                case CIRCLE -> {
                    int radius = Math.max(1, width / 2);
                    for (int z = -radius; z <= radius; z++)
                        for (int x = 0; x <= radius * 2; x++) {
                            int dx = x - radius;
                            int d2 = dx * dx + z * z;
                            boolean edge = d2 >= (radius - 1) * (radius - 1);
                            if (d2 <= radius * radius && (!hollow || edge))
                                add(all, base, right, forward, x, 0, z);
                        }
                }
                case RING -> {
                    int radius = Math.max(1, width / 2);
                    int inner = Math.max(0, radius - 2);
                    for (int z = -radius; z <= radius; z++)
                        for (int x = 0; x <= radius * 2; x++) {
                            int dx = x - radius;
                            int d2 = dx * dx + z * z;
                            if (d2 <= radius * radius && d2 >= inner * inner)
                                add(all, base, right, forward, x, 0, z);
                        }
                }
                case CONE -> {
                    int radius = Math.max(1, width / 2);
                    for (int y = 0; y < height; y++) {
                        int layerRadius = Math.max(0, radius - (int) ((long) y * (radius + 1) / height));
                        for (int z = -layerRadius; z <= layerRadius; z++)
                            for (int x = 0; x <= layerRadius * 2; x++) {
                                int dx = x - layerRadius;
                                int d2 = dx * dx + z * z;
                                boolean edge = d2 >= Math.max(0, layerRadius - 1) * Math.max(0, layerRadius - 1);
                                if (d2 <= layerRadius * layerRadius && (!hollow || edge || y == 0))
                                    add(all, base, right, forward, x - layerRadius, y, z);
                            }
                    }
                }
                case ARENA -> {
                    int radius = Math.max(1, width / 2);
                    int inner = Math.max(0, radius - 2);
                    for (int y = 0; y < height; y++)
                        for (int z = -radius; z <= radius; z++)
                            for (int x = 0; x <= radius * 2; x++) {
                                int dx = x - radius;
                                int d2 = dx * dx + z * z;
                                if (d2 <= radius * radius && d2 >= inner * inner)
                                    add(all, base, right, forward, x - radius, y, z);
                            }
                }
                case TUNNEL -> {
                    int radius = Math.max(1, (width - 1) / 2);
                    int center = (width - 1) / 2;
                    int springLine = Math.max(1, height - radius - 1);
                    for (int z = 0; z < length; z++) {
                        for (int x = 0; x < width; x++) {
                            if (x == 0 || x == width - 1) {
                                for (int y = 0; y <= springLine; y++)
                                    add(all, base, right, forward, x, y, z);
                            }
                            int dx = x - center;
                            int arcY = springLine + (int) Math.round(Math.sqrt(
                                    Math.max(0, radius * radius - dx * dx)));
                            add(all, base, right, forward, x, arcY, z);
                        }
                    }
                }
                case HOUSE -> {
                    // Hollow rectangular house shell with selectable roof geometry.
                    int hipRise = Math.max(0, Math.min((width - 1) / 2, (length - 1) / 2));
                    int shedRise = Math.max(1, width / 3);
                    for (int z = 0; z < length; z++) {
                        for (int x = 0; x < width; x++) {
                            add(all, base, right, forward, x, 0, z);
                            for (int y = 1; y < height - 1; y++) {
                                if (x == 0 || x == width - 1 || z == 0 || z == length - 1) {
                                    boolean doorway = z == 0 && x >= width / 2 - 1
                                            && x <= width / 2 && y <= 2;
                                    if (!doorway) add(all, base, right, forward, x, y, z);
                                }
                            }

                            switch (roofStyle) {
                                case FLAT -> add(all, base, right, forward, x, height - 1, z);
                                case GABLE -> {
                                    int rise = Math.min(x, width - 1 - x);
                                    int roofY = height - 1 + rise;
                                    add(all, base, right, forward, x, roofY, z);
                                    if (z == 0 || z == length - 1) {
                                        for (int y = height - 1; y < roofY; y++)
                                            add(all, base, right, forward, x, y, z);
                                    }
                                }
                                case SHED -> {
                                    int roofY = height - 1 + (x * shedRise) / Math.max(1, width - 1);
                                    add(all, base, right, forward, x, roofY, z);
                                }
                                case HIP -> {
                                    for (int level = 0; level <= hipRise; level++) {
                                        if (x >= level && x < width - level
                                                && z >= level && z < length - level) {
                                            add(all, base, right, forward, x, height - 1 + level, z);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                case PILLAR -> {
                    for (int y = 0; y < height; y++)
                        for (int z = 0; z < length; z++)
                            for (int x = 0; x < width; x++) {
                                boolean edge = x == 0 || x == width - 1
                                        || z == 0 || z == length - 1;
                                if (!hollow || edge)
                                    add(all, base, right, forward, x, y, z);
                            }
                }
                case STAIRS -> {
                    for (int z = 0; z < length; z++) {
                        int level = Math.min(height - 1, (int) ((long) z * height / Math.max(1, length)));
                        for (int y = 0; y <= level; y++)
                            for (int x = 0; x < width; x++) {
                                boolean edge = y == level || x == 0 || x == width - 1 || z == 0;
                                if (!hollow || edge)
                                    add(all, base, right, forward, x, y, z);
                            }
                    }
                }
                case BRIDGE -> {
                    for (int z = 0; z < length; z++) {
                        for (int x = 0; x < width; x++) {
                            add(all, base, right, forward, x, 0, z);
                            if (height > 1 && (x == 0 || x == width - 1)) {
                                for (int y = 1; y < Math.min(height, 4); y++)
                                    add(all, base, right, forward, x, y, z);
                            }
                        }
                    }
                }
                case ARCH -> {
                    int radius = Math.max(1, (width - 1) / 2);
                    int springLine = Math.max(1, height - radius - 1);
                    int center = (width - 1) / 2;
                    for (int x = 0; x < width; x++) {
                        for (int y = 0; y <= springLine; y++) {
                            if (x == 0 || x == width - 1)
                                add(all, base, right, forward, x, y, 0);
                        }
                        int dx = x - center;
                        int arcY = springLine + (int) Math.round(Math.sqrt(
                                Math.max(0, radius * radius - dx * dx)));
                        add(all, base, right, forward, x, arcY, 0);
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

    /**
     * Optional one-block mining helper. It uses the normal client game-mode mining calls,
     * so server permissions, reach and mining rules remain authoritative.
     */
    private static final class BlockBreaker {
        private static BlockPos target;
        private static Direction face;
        private static int previousSlot = -1;
        private static boolean active;
        private static String state = "Abbau bereit";

        static void start(Minecraft client) {
            if (client == null || client.player == null || client.level == null || client.gameMode == null) {
                state = "Du musst in einer Welt sein.";
                return;
            }
            if (active) {
                state = "Ein Abbau laeuft bereits. Nutze +break cancel zum Stoppen.";
                return;
            }
            if (BuildEngine.isBusy()) {
                state = "Erst den laufenden Bau oder Undo/Redo beenden.";
                return;
            }
            BlockHitResult hit = currentBlockHit(client);
            if (hit == null) {
                state = "Schau zuerst auf einen Block.";
                return;
            }
            BlockState blockState = client.level.getBlockState(hit.getBlockPos());
            if (blockState.isAir()) {
                state = "Dort ist kein abbaubarer Block.";
                return;
            }

            previousSlot = client.player.getInventory().getSelectedSlot();
            int toolSlot = bestPickaxeSlot(client, blockState);
            if (toolSlot >= 0 && toolSlot != previousSlot) {
                client.player.getInventory().setSelectedSlot(toolSlot);
            }
            target = hit.getBlockPos().immutable();
            face = hit.getDirection();
            boolean started = client.gameMode.startDestroyBlock(target, face);
            if (!started) {
                restoreSlot(client);
                state = "Der Abbau konnte nicht gestartet werden.";
                return;
            }
            active = true;
            state = toolSlot >= 0
                    ? "Abbau gestartet. Passende Spitzhacke ausgewaehlt."
                    : "Abbau gestartet. Keine Spitzhacke in der Hotbar gefunden.";
        }

        static void tick(Minecraft client) {
            if (!active) return;
            if (client == null || client.player == null || client.level == null || client.gameMode == null) {
                active = false;
                return;
            }
            if (!client.level.hasChunkAt(target) || client.level.getBlockState(target).isAir()) {
                finish(client, "Block abgebaut.");
                return;
            }
            client.gameMode.continueDestroyBlock(target, face);
            if (client.level.getBlockState(target).isAir()) {
                finish(client, "Block abgebaut.");
            }
        }

        static void cancel(Minecraft client) {
            if (!active) {
                state = "Kein Abbau aktiv.";
                return;
            }
            if (client != null && client.gameMode != null) client.gameMode.stopDestroyBlock();
            active = false;
            restoreSlot(client);
            state = "Abbau gestoppt.";
        }

        private static int bestPickaxeSlot(Minecraft client, BlockState state) {
            int bestHotbar = -1;
            float bestHotbarSpeed = 0.0F;
            for (int slot = 0; slot < 9; slot++) {
                var stack = client.player.getInventory().getItem(slot);
                if (stack.isEmpty()) continue;
                var itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (itemId == null || !itemId.getPath().endsWith("_pickaxe")) continue;
                float speed = stack.getDestroySpeed(state);
                if (speed > bestHotbarSpeed) {
                    bestHotbarSpeed = speed;
                    bestHotbar = slot;
                }
            }

            if (client.player.containerMenu == client.player.inventoryMenu) {
                int bestInventory = -1;
                float bestInventorySpeed = bestHotbarSpeed;
                for (int slot = 9; slot < 36; slot++) {
                    var stack = client.player.getInventory().getItem(slot);
                    if (stack.isEmpty()) continue;
                    var itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
                    if (itemId == null || !itemId.getPath().endsWith("_pickaxe")) continue;
                    float speed = stack.getDestroySpeed(state);
                    if (speed > bestInventorySpeed) {
                        bestInventorySpeed = speed;
                        bestInventory = slot;
                    }
                }
                if (bestInventory >= 0) {
                    int moved = moveInventoryItemToFreeHotbar(client, bestInventory);
                    if (moved >= 0) return moved;
                }
            }
            return bestHotbar;
        }

        private static void finish(Minecraft client, String message) {
            if (client.gameMode != null) client.gameMode.stopDestroyBlock();
            active = false;
            restoreSlot(client);
            state = message;
        }

        private static void restoreSlot(Minecraft client) {
            if (client != null && client.player != null && previousSlot >= 0) {
                client.player.getInventory().setSelectedSlot(previousSlot);
            }
            previousSlot = -1;
        }

        static boolean isActive() {
            return active;
        }

        static String status() {
            return state;
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
        private enum HistoryMode { NONE, UNDO }
        private static HistoryMode historyMode = HistoryMode.NONE;
        private static final Queue<BlockPos> historyQueue = new ArrayDeque<>();
        private static List<BlockPos> lastCompleted = List.of();
        private static Block lastMaterial = Blocks.STONE_BRICKS;
        private static BlockPos lastAnchor;
        private static Direction lastFace;
        private static boolean redoAvailable;
        private static String state = "Bereit";

        static void start(Minecraft client) {
            if (client == null || client.player == null || client.level == null || client.gameMode == null) {
                state = "Du musst in einer Welt sein.";
                return;
            }
            if (BlockBreaker.isActive()) {
                state = "Stoppe zuerst den laufenden Block-Abbau.";
                return;
            }
            if (!remaining.isEmpty() || historyMode != HistoryMode.NONE) {
                state = "Es laeuft bereits ein Bau- oder Undo/Redo-Vorgang.";
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
            if (plan.size() > maxBuildBlocks) {
                state = "Zu gross: maximal " + maxBuildBlocks + " Bloecke pro Bau. Aendere das Limit mit "
                        + commandPrefix + "settings maxblocks <zahl>.";
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
            if (client == null || client.player == null || client.level == null || client.gameMode == null) return;
            if (historyMode != HistoryMode.NONE) {
                tickHistory(client);
                return;
            }
            if (remaining.isEmpty() || paused) return;
            if (++tickDelay < buildDelayTicks) return;
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
                        + " fehlt in der Hotbar oder es ist kein freier Hotbar-Slot vorhanden. "
                        + "Fuege Material hinzu oder mache einen Slot frei und druecke Weiter.";
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
                lastCompleted = List.copyOf(placed);
                lastMaterial = selectedBlock;
                lastAnchor = originalAnchor;
                lastFace = originalFace;
                redoAvailable = false;
                restoreSelectedSlot(client);
            }
        }

        static List<BlockPos> lastStructureOffsets() {
            if (lastCompleted.isEmpty()) return List.of();
            BlockPos origin = lastCompleted.get(0);
            List<BlockPos> offsets = new ArrayList<>(lastCompleted.size());
            for (BlockPos pos : lastCompleted) {
                offsets.add(new BlockPos(pos.getX() - origin.getX(),
                        pos.getY() - origin.getY(), pos.getZ() - origin.getZ()));
            }
            return List.copyOf(offsets);
        }

        static Block lastStructureMaterial() {
            return lastMaterial;
        }

        static String startStructure(Minecraft client, List<BlockPos> offsets, Block material) {
            if (client == null || client.player == null || client.level == null || client.gameMode == null) {
                return "Du musst in einer Welt sein.";
            }
            if (BlockBreaker.isActive() || !remaining.isEmpty() || historyMode != HistoryMode.NONE) {
                return "Beende zuerst den laufenden Bau-, Abbau- oder Undo/Redo-Vorgang.";
            }
            if (offsets == null || offsets.isEmpty() || offsets.size() > maxBuildBlocks) {
                return "Die Struktur hat keine oder zu viele Blockpositionen.";
            }
            BlockHitResult hit = currentBlockHit(client);
            if (hit == null) return "Schau auf einen festen Block als neuen Startpunkt.";
            BlockPos base = hit.getBlockPos().relative(hit.getDirection());
            if (!offsets.get(0).equals(BlockPos.ZERO)) return "Die Struktur hat keinen gueltigen Startpunkt.";

            List<BlockPos> translated = new ArrayList<>(offsets.size());
            Set<BlockPos> unique = new HashSet<>();
            for (BlockPos offset : offsets) {
                BlockPos target = new BlockPos(base.getX() + offset.getX(),
                        base.getY() + offset.getY(), base.getZ() + offset.getZ());
                if (target.getY() < client.level.getMinY() || target.getY() >= client.level.getMaxY()) {
                    return "Struktur liegt ausserhalb der Bauhoehe.";
                }
                if (!client.level.getBlockState(target).isAir()) {
                    return "Strukturziel ist nicht frei bei " + target.toShortString();
                }
                if (!unique.add(target)) return "Die Struktur enthaelt doppelte Positionen.";
                translated.add(target);
            }

            selectedBlock = material;
            remaining.clear();
            remaining.addAll(translated);
            placed.clear();
            originalAnchor = hit.getBlockPos();
            originalFace = hit.getDirection();
            total = translated.size();
            originalSelectedSlot = client.player.getInventory().getSelectedSlot();
            tickDelay = 0;
            paused = false;
            redoAvailable = false;
            state = "Struktur-Bau laeuft: 0 / " + total
                    + ". Material: " + material.getName().getString() + ".";
            return state;
        }

        static void undo(Minecraft client) {
            if (client == null || client.player == null || client.level == null || client.gameMode == null) {
                state = "Du musst in einer Welt sein.";
                return;
            }
            if (!client.player.getAbilities().instabuild) {
                state = "Undo ist in dieser Version zum Schutz deiner Survival-Welt nur im Kreativmodus verfuegbar.";
                return;
            }
            if (!remaining.isEmpty() || historyMode != HistoryMode.NONE) {
                state = "Beende zuerst den laufenden Bau- oder Rueckgaengig-Vorgang.";
                return;
            }
            if (lastCompleted.isEmpty()) {
                state = "Es gibt noch keinen abgeschlossenen Bau zum Rueckgaengigmachen.";
                return;
            }
            historyQueue.clear();
            for (int i = lastCompleted.size() - 1; i >= 0; i--) historyQueue.add(lastCompleted.get(i));
            historyMode = HistoryMode.UNDO;
            redoAvailable = false;
            tickDelay = 0;
            state = "Undo laeuft: " + historyQueue.size() + " Bloecke.";
        }

        static void redo(Minecraft client) {
            if (client == null || client.player == null || client.level == null || client.gameMode == null) {
                state = "Du musst in einer Welt sein.";
                return;
            }
            if (!client.player.getAbilities().instabuild) {
                state = "Redo ist in dieser Version zum Schutz deiner Survival-Welt nur im Kreativmodus verfuegbar.";
                return;
            }
            if (!remaining.isEmpty() || historyMode != HistoryMode.NONE) {
                state = "Beende zuerst den laufenden Bau- oder Rueckgaengig-Vorgang.";
                return;
            }
            if (!redoAvailable || lastCompleted.isEmpty()) {
                state = "Es gibt keinen Bau, den du wiederholen kannst.";
                return;
            }
            for (BlockPos pos : lastCompleted) {
                if (!client.level.getBlockState(pos).isAir()) {
                    state = "Redo nicht moeglich: " + pos.toShortString() + " ist nicht frei.";
                    return;
                }
            }
            remaining.clear();
            remaining.addAll(lastCompleted);
            placed.clear();
            selectedBlock = lastMaterial;
            originalAnchor = lastAnchor;
            originalFace = lastFace;
            total = lastCompleted.size();
            originalSelectedSlot = client.player.getInventory().getSelectedSlot();
            tickDelay = 0;
            paused = false;
            redoAvailable = false;
            state = "Redo laeuft: 0 / " + total + ". Das Material muss in der Hotbar liegen.";
        }

        private static void tickHistory(Minecraft client) {
            if (++tickDelay < buildDelayTicks) return;
            tickDelay = 0;
            BlockPos pos = historyQueue.poll();
            if (pos == null) {
                historyMode = HistoryMode.NONE;
                redoAvailable = true;
                state = "Undo abgeschlossen. Mit +build redo kannst du den Bau wiederherstellen.";
                return;
            }
            if (!client.level.getBlockState(pos).isAir()) {
                boolean removed = client.gameMode.destroyBlock(pos);
                if (!removed && !client.level.getBlockState(pos).isAir()) {
                    historyQueue.clear();
                    historyMode = HistoryMode.NONE;
                    state = "Undo gestoppt: Server hat das Entfernen bei " + pos.toShortString() + " abgelehnt.";
                    return;
                }
            }
            state = "Undo laeuft: " + (lastCompleted.size() - historyQueue.size())
                    + " / " + lastCompleted.size();
            if (historyQueue.isEmpty()) {
                historyMode = HistoryMode.NONE;
                redoAvailable = true;
                state = "Undo abgeschlossen. Mit +build redo kannst du den Bau wiederherstellen.";
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
            if (historyMode != HistoryMode.NONE) {
                historyQueue.clear();
                historyMode = HistoryMode.NONE;
                state = "Undo abgebrochen.";
                return;
            }
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
            if (client.player.containerMenu == client.player.inventoryMenu) {
                for (int slot = 9; slot < 36; slot++) {
                    var stack = client.player.getInventory().getItem(slot);
                    if (!stack.isEmpty() && stack.getItem() == selectedBlock.asItem()) {
                        return moveInventoryItemToFreeHotbar(client, slot);
                    }
                }
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

        static boolean isBusy() {
            return !remaining.isEmpty() || historyMode != HistoryMode.NONE;
        }

        static String status() {
            return state;
        }
    }
}
