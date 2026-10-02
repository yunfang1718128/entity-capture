package com.yunfang.entitycapture.capture;

import java.util.function.Consumer;

import net.minecraft.resources.ResourceLocation;
import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * A {@link VertexConsumer} that records the quads fed to it. Minecraft's entity
 * renderers always emit quads (four {@code addVertex} calls, each followed by
 * colour/UV/overlay/light/normal setters), so we simply buffer four vertices
 * at a time and hand the finished quad to a sink.
 */
public final class CapturingVertexConsumer implements VertexConsumer {
	private final ResourceLocation texture;
	private final int layer;
	private final Consumer<Quad> sink;

	private final Vertex[] quad = new Vertex[] { new Vertex(), new Vertex(), new Vertex(), new Vertex() };
	private int count;
	private Vertex current;

	public CapturingVertexConsumer(ResourceLocation texture, int layer, Consumer<Quad> sink) {
		this.texture = texture;
		this.layer = layer;
		this.sink = sink;
	}

	@Override
	public VertexConsumer addVertex(float x, float y, float z) {
		if (this.count == 4) {
			this.emit();
		}

		this.current = this.quad[this.count++];
		this.current.x = x;
		this.current.y = y;
		this.current.z = z;
		this.current.u = 0.0F;
		this.current.v = 0.0F;
		this.current.r = 1.0F;
		this.current.g = 1.0F;
		this.current.b = 1.0F;
		this.current.a = 1.0F;
		this.current.nx = 0.0F;
		this.current.ny = 0.0F;
		this.current.nz = 0.0F;
		return this;
	}

	@Override
	public VertexConsumer setColor(int red, int green, int blue, int alpha) {
		if (this.current != null) {
			this.current.r = red / 255.0F;
			this.current.g = green / 255.0F;
			this.current.b = blue / 255.0F;
			this.current.a = alpha / 255.0F;
		}
		return this;
	}

	@Override
	public VertexConsumer setUv(float u, float v) {
		if (this.current != null) {
			this.current.u = u;
			this.current.v = v;
		}
		return this;
	}

	@Override
	public VertexConsumer setUv1(int u, int v) {
		return this;
	}

	@Override
	public VertexConsumer setUv2(int u, int v) {
		return this;
	}

	@Override
	public VertexConsumer setNormal(float x, float y, float z) {
		if (this.current != null) {
			this.current.nx = x;
			this.current.ny = y;
			this.current.nz = z;
		}
		return this;
	}

	/** Emits the final quad if one is complete (four vertices buffered). */
	public void flush() {
		if (this.count == 4) {
			this.emit();
		}
	}

	private void emit() {
		this.sink.accept(new Quad(
				new Vertex(this.quad[0]),
				new Vertex(this.quad[1]),
				new Vertex(this.quad[2]),
				new Vertex(this.quad[3]),
				this.texture,
				this.layer));
		this.count = 0;
		this.current = null;
	}
}
