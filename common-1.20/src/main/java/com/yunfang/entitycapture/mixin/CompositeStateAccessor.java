package com.yunfang.entitycapture.mixin;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the package-private {@code textureState} held by a composite render state.
 *
 * <p>{@code RenderType.CompositeState} is public in 1.20.1, and Forge's Mixin
 * annotation processor requires public targets to be passed via {@code value}
 * rather than {@code targets}.
 */
@Mixin(RenderType.CompositeState.class)
public interface CompositeStateAccessor {
	@Accessor("textureState")
	RenderStateShard.EmptyTextureStateShard getTextureState();
}
