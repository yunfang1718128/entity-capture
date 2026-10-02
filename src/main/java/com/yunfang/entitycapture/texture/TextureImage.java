package com.yunfang.entitycapture.texture;

/** Decoded texture pixels as packed ARGB, row-major, origin top-left. */
public final class TextureImage {
	public final int width;
	public final int height;
	public final int[] pixels;

	public TextureImage(int width, int height, int[] pixels) {
		this.width = width;
		this.height = height;
		this.pixels = pixels;
	}
}
