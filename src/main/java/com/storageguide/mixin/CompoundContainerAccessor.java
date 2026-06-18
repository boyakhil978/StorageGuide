package com.storageguide.mixin;

import net.minecraft.world.Container;
import net.minecraft.world.CompoundContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(CompoundContainer.class)
public interface CompoundContainerAccessor {
    @Accessor("container1")
    Container storageguide$container1();

    @Accessor("container2")
    Container storageguide$container2();
}
