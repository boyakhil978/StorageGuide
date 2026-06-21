package com.storageguide.mixin;

import com.storageguide.StorageGuideServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin {
    @Unique
    private StorageGuideServer.ContainerSnapshot storageguide$beforeClick;

    @Inject(method = "clicked", at = @At("HEAD"))
    private void storageguide$beforeClicked(int slotId, int button, ContainerInput input, Player player, CallbackInfo ci) {
        this.storageguide$beforeClick = StorageGuideServer.captureContainerState((AbstractContainerMenu) (Object) this, player);
    }

    @Inject(method = "clicked", at = @At("RETURN"))
    private void storageguide$afterClicked(int slotId, int button, ContainerInput input, Player player, CallbackInfo ci) {
        StorageGuideServer.afterContainerClick((AbstractContainerMenu) (Object) this, player, this.storageguide$beforeClick);
        this.storageguide$beforeClick = null;
    }
}
