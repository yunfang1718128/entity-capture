package com.yunfang.entitycapture.capture;

import net.minecraft.resources.ResourceLocation;

/**
 * A captured render quad: four vertices, the texture of its render layer and the
 * layer's render order (0 = base model, higher = an overlay drawn later, so an
 * outer layer outranks an inner one when two quads fight over a voxel).
 */
public record Quad(Vertex v0, Vertex v1, Vertex v2, Vertex v3, ResourceLocation texture, int layer) {
}
