package com.storageguide.mixin;

import com.storageguide.StorageGuideClient;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {
    protected PauseScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void storageguide$addSettingsButton(CallbackInfo ci) {
        if (!((PauseScreen) (Object) this).showsPauseMenu()) {
            return;
        }

        Button button = Button.builder(
                        Component.literal("StorageGuide"),
                        clicked -> StorageGuideClient.openClientSettings((Screen) (Object) this)
                )
                .bounds(this.width / 2 - 102, Math.max(58, this.height / 4 + 24), 204, 20)
                .build();
        button.setTooltip(Tooltip.create(Component.literal("Open StorageGuide client settings, colors, and keybinds.")));
        this.addRenderableWidget(button);
    }
}
