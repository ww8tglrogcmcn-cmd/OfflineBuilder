package de.offlinebuilder;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

public final class OfflineBuilderClient implements ClientModInitializer {
    private static KeyMapping openMenuKey;

    @Override
    public void onInitializeClient() {
        openMenuKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.offlinebuilder.open",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_B,
            "category.offlinebuilder"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openMenuKey.consumeClick()) {
                if (client.screen == null) {
                    client.setScreen(new BuilderScreen());
                }
            }
        });
    }

    private static final class BuilderScreen extends Screen {
        protected BuilderScreen() {
            super(Component.literal("OfflineBuilder"));
        }

        @Override
        protected void init() {
            int buttonWidth = 150;
            int left = (this.width - buttonWidth) / 2;
            int top = this.height / 2 - 10;
            this.addRenderableWidget(net.minecraft.client.gui.components.Button.builder(
                Component.literal("Schließen"),
                button -> this.onClose()
            ).bounds(left, top, buttonWidth, 20).build());
        }

        @Override
        public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            this.renderBackground(graphics, mouseX, mouseY, delta);
            graphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 55, 0xFFFFFF);
            graphics.drawCenteredString(this.font,
                Component.literal("Offline-Bauwerkzeuge – Grundversion"),
                this.width / 2, this.height / 2 - 30, 0xA8DCE8);
            super.render(graphics, mouseX, mouseY, delta);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}
