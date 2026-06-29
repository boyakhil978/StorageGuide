package com.storageguide.mixin;

import com.storageguide.StorageGuideClient;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {
    private static final int STORAGEGUIDE_BUTTON_WIDTH = 204;
    private static final int STORAGEGUIDE_BUTTON_HEIGHT = 20;
    private static final int STORAGEGUIDE_BUTTON_ROW_SPACING = 24;

    protected PauseScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void storageguide$addSettingsButton(CallbackInfo ci) {
        if (!((PauseScreen) (Object) this).showsPauseMenu()) {
            return;
        }

        int x = this.width / 2 - STORAGEGUIDE_BUTTON_WIDTH / 2;
        int y = Math.max(58, this.height / 4 + STORAGEGUIDE_BUTTON_ROW_SPACING);
        int returnToGameY = Integer.MAX_VALUE;

        for (GuiEventListener child : this.children()) {
            if (child instanceof AbstractWidget widget
                    && widget.getWidth() == STORAGEGUIDE_BUTTON_WIDTH
                    && widget.getHeight() == STORAGEGUIDE_BUTTON_HEIGHT
                    && widget.getY() < returnToGameY) {
                x = widget.getX();
                returnToGameY = widget.getY();
            }
        }

        if (returnToGameY != Integer.MAX_VALUE) {
            y = returnToGameY + STORAGEGUIDE_BUTTON_ROW_SPACING;
            for (GuiEventListener child : this.children()) {
                if (child instanceof AbstractWidget widget && widget.getY() > returnToGameY) {
                    widget.setY(widget.getY() + STORAGEGUIDE_BUTTON_ROW_SPACING);
                }
            }
        }

        Button button = Button.builder(
                        Component.literal("StorageGuide"),
                        clicked -> StorageGuideClient.openClientSettings((Screen) (Object) this)
                )
                .bounds(x, y, STORAGEGUIDE_BUTTON_WIDTH, STORAGEGUIDE_BUTTON_HEIGHT)
                .build();
        button.setTooltip(Tooltip.create(Component.literal("Open StorageGuide client settings, colors, and keybinds.")));
        this.addRenderableWidget(button);
    }
}
