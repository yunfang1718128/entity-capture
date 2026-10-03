package com.yunfang.entitycapture.capture;

import java.util.Optional;

import com.yunfang.entitycapture.mixin.CompositeRenderTypeAccessor;
import com.yunfang.entitycapture.mixin.CompositeStateAccessor;
import com.yunfang.entitycapture.mixin.EmptyTextureStateShardAccessor;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Recovers the texture bound to a {@link RenderType} by walking the composite
 * state. This works for the entity layers vanilla uses ({@code entitySolid},
 * {@code entityCutout}, {@code entityTranslucent}, …) and for any mod layer that
 * is built the same way.
 */
public final class RenderTypeTextures {
	private static final Logger LOGGER = LoggerFactory.getLogger("entity-capture/RenderTypeTextures");

	private RenderTypeTextures() {
	}

	public static Optional<ResourceLocation> resolve(RenderType type) {
		if (!(type instanceof CompositeRenderTypeAccessor accessor)) {
			LOGGER.warn("resolve: {} is not a CompositeRenderType ({})", type, type.getClass().getName());
			return Optional.empty();
		}

		RenderType.CompositeState state = accessor.getState();
		if (state == null) {
			LOGGER.warn("resolve: {} composite state is null", type);
			return Optional.empty();
		}

		RenderStateShard.EmptyTextureStateShard textureState = ((CompositeStateAccessor) (Object) state).getTextureState();
		if (textureState == null) {
			LOGGER.warn("resolve: {} has no texture state", type);
			return Optional.empty();
		}

		Optional<ResourceLocation> texture = ((EmptyTextureStateShardAccessor) (Object) textureState).invokeCutoutTexture();
		if (texture.isEmpty()) {
			LOGGER.warn("resolve: {} texture state has no cutout texture ({})", type, textureState.getClass().getName());
		}
		return texture;
	}
}
