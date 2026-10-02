package com.yunfang.entitycapture.capture;

/** A single rendered vertex in model space, captured after the pose stack transform. */
public final class Vertex {
	public float x;
	public float y;
	public float z;
	public float u;
	public float v;
	public float r = 1.0F;
	public float g = 1.0F;
	public float b = 1.0F;
	public float a = 1.0F;
	/** Model-provided normal (already pose-transformed); zero when unspecified. */
	public float nx;
	public float ny;
	public float nz;

	public Vertex() {
	}

	public Vertex(Vertex other) {
		this.x = other.x;
		this.y = other.y;
		this.z = other.z;
		this.u = other.u;
		this.v = other.v;
		this.r = other.r;
		this.g = other.g;
		this.b = other.b;
		this.a = other.a;
		this.nx = other.nx;
		this.ny = other.ny;
		this.nz = other.nz;
	}
}
