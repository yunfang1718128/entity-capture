package com.yunfang.entitycapture.mixin;

import java.util.Optional;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Invokes the protected {@code cutoutTexture()} accessor on a texture state shard.
 *
 * <p>{@code EmptyTextureStateShard} is public in 1.20.1, and Forge's Mixin
 * annotation processor requires public targets to be passed via {@code value}.
 */
@Mixin(RenderStateShard.EmptyTextureStateShard.class)
public interface EmptyTextureStateShardAccessor {
	@Invoker("cutoutTexture")
	Optional<ResourceLocation> invokeCutoutTexture();
}
