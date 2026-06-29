package com.storageguide.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.storageguide.StorageGuideClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Hud.class)
public abstract class HudMixin {
    @Redirect(
            method = "extractItemHotbar",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V",
                    ordinal = 1
            )
    )
    private void storageguide$replaceSelectedSlotDuringStatus(
            GuiGraphicsExtractor graphics,
            RenderPipeline pipeline,
            Identifier sprite,
            int x,
            int y,
            int width,
            int height
    ) {
        graphics.blitSprite(
                pipeline,
                sprite,
                x,
                y,
                width,
                height,
                StorageGuideClient.hotbarSelectionTint()
        );
    }
}
