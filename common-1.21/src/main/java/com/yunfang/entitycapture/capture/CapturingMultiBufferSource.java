package com.yunfang.entitycapture.capture;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * A {@link MultiBufferSource} that captures quads instead of drawing them.
 * One {@link CapturingVertexConsumer} is created per {@link RenderType} so the
 * quads keep the texture of their layer.
 *
 * <p>Also keeps lightweight statistics (per-texture quad counts, unresolved
 * render types) for in-game diagnostics.
 */
public final class CapturingMultiBufferSource implements MultiBufferSource {
	private final Map<RenderType, CapturingVertexConsumer> buffers = new IdentityHashMap<>();
	private final List<Quad> quads = new ArrayList<>();
	private final Map<ResourceLocation, Integer> textureCounts = new HashMap<>();
	private final Set<String> unresolvedRenderTypes = new LinkedHashSet<>();
	private final Set<String> requestedRenderTypes = new LinkedHashSet<>();
	private int nullTextureQuads;
	/** Render order of layers; 0 is the first requested (base model), higher = overlays. */
	private int nextLayer;

	@Override
	public VertexConsumer getBuffer(RenderType renderType) {
		return this.buffers.computeIfAbsent(renderType, type -> {
			this.requestedRenderTypes.add(type.getClass().getSimpleName() + " => " + type);
			ResourceLocation texture = RenderTypeTextures.resolve(type).orElse(null);
			if (texture == null) {
				this.unresolvedRenderTypes.add(type.getClass().getName() + " => " + type);
			}
			return new CapturingVertexConsumer(texture, this.nextLayer++, this::accept);
		});
	}

	private void accept(Quad quad) {
		this.quads.add(quad);
		ResourceLocation texture = quad.texture();
		if (texture == null) {
			this.nullTextureQuads++;
		} else {
			this.textureCounts.merge(texture, 1, Integer::sum);
		}
	}

	/** Flushes any complete-but-unemitted quads from every layer. */
	public void flush() {
		for (CapturingVertexConsumer consumer : this.buffers.values()) {
			consumer.flush();
		}
	}

	public List<Quad> quads() {
		return this.quads;
	}

	/** Quad count per resolved texture, for diagnostics. */
	public Map<ResourceLocation, Integer> quadCountByTexture() {
		return this.textureCounts;
	}

	/** Number of captured quads whose render layer had no resolvable texture. */
	public int nullTextureQuads() {
		return this.nullTextureQuads;
	}

	/** Render types that produced no texture (likely non-composite or unsupported). */
	public Set<String> unresolvedRenderTypes() {
		return this.unresolvedRenderTypes;
	}

	/** Every distinct render type asked for during the capture, in request order. */
	public Set<String> requestedRenderTypes() {
		return this.requestedRenderTypes;
	}
}
