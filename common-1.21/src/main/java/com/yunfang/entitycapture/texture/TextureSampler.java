package com.yunfang.entitycapture.texture;

import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Samples raw (unlit) pixels straight out of the resource pack PNGs, so capture
 * colour comes from the artist's texture and never from baked-in lighting.
 */
public final class TextureSampler {
	private final Map<ResourceLocation, TextureImage> cache = new HashMap<>();
	private final Set<ResourceLocation> missing = new HashSet<>();

	/** Decoded image for a texture, or {@code null} when it is absent/unreadable. */
	public TextureImage image(ResourceLocation id) {
		if (this.cache.containsKey(id)) {
			return this.cache.get(id);
		}
		if (this.missing.contains(id)) {
			return null;
		}

		TextureImage image = this.load(id);
		if (image == null) {
			this.missing.add(id);
		} else {
			this.cache.put(id, image);
		}
		return image;
	}

	/**
	 * Samples a normalised UV into packed ARGB. Out-of-range UVs are clamped.
	 * Returns an alpha of 0 when the texture cannot be read.
	 */
	public int sample(ResourceLocation id, float u, float v) {
		TextureImage image = this.image(id);
		if (image == null) {
			return 0;
		}

		int x = clamp((int) (u * image.width), 0, image.width - 1);
		int y = clamp((int) (v * image.height), 0, image.height - 1);
		return image.pixels[y * image.width + x];
	}

	private TextureImage load(ResourceLocation id) {
		ResourceManager manager = Minecraft.getInstance().getResourceManager();
		Optional<Resource> resource = manager.getResource(id);
		if (resource.isEmpty()) {
			return null;
		}

		try (InputStream in = resource.get().open(); NativeImage nativeImage = NativeImage.read(in)) {
			int width = nativeImage.getWidth();
			int height = nativeImage.getHeight();
			int[] pixels = new int[width * height];
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					// NativeImage stores ABGR; repack to ARGB for our own use.
					int abgr = nativeImage.getPixelRGBA(x, y);
					int r = abgr & 0xFF;
					int g = (abgr >> 8) & 0xFF;
					int b = (abgr >> 16) & 0xFF;
					int a = (abgr >> 24) & 0xFF;
					pixels[y * width + x] = (a << 24) | (r << 16) | (g << 8) | b;
				}
			}
			return new TextureImage(width, height, pixels);
		} catch (Exception exception) {
			return null;
		}
	}

	private static int clamp(int value, int min, int max) {
		if (value < min) {
			return min;
		}
		return Math.min(value, max);
	}
}
