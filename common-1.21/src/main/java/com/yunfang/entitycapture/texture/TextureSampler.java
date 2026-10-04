package com.yunfang.entitycapture.texture;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Samples raw (unlit) pixels straight out of the resource pack PNGs, so capture
 * colour comes from the artist's texture and never from baked-in lighting.
 *
 * <p>Textures that do not live in a resource pack — mods that stream their models
 * and PNGs from their own pack format and register them straight with the
 * {@code TextureManager} (e.g. Touhou Little Maid) — are read back from the GPU
 * texture instead, so those captures still get colour.
 */
public final class TextureSampler {
	private static final Logger LOGGER = LoggerFactory.getLogger("entity-capture/TextureSampler");

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
		if (resource.isPresent()) {
			TextureImage image = loadFromResource(resource.get());
			if (image != null) {
				return image;
			}
		}

		// Not a resource-pack file: some mods register their textures directly
		// with the TextureManager from a custom model pack. Read the bound GPU
		// texture back so colours are still available.
		return loadFromTextureManager(id);
	}

	private TextureImage loadFromResource(Resource resource) {
		try (InputStream in = resource.open(); NativeImage nativeImage = NativeImage.read(in)) {
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

	/**
	 * Best-effort read of an already-registered GPU texture. Uses the
	 * side-effect-free {@code getTexture(id, null)} overload so a genuinely
	 * missing texture is not turned into a placeholder.
	 */
	private TextureImage loadFromTextureManager(ResourceLocation id) {
		AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(id, null);
		if (texture == null) {
			return null;
		}
		int glId = texture.getId();
		if (glId == AbstractTexture.NOT_ASSIGNED || glId <= 0) {
			return null;
		}

		try {
			GlStateManager._bindTexture(glId);
			int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
			int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
			if (width <= 0 || height <= 0) {
				return null;
			}

			ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
			GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);

			int[] pixels = new int[width * height];
			for (int i = 0; i < pixels.length; i++) {
				// GPU readback is already RGBA; pack straight to ARGB.
				int r = buffer.get(i * 4) & 0xFF;
				int g = buffer.get(i * 4 + 1) & 0xFF;
				int b = buffer.get(i * 4 + 2) & 0xFF;
				int a = buffer.get(i * 4 + 3) & 0xFF;
				pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
			}
			LOGGER.info("Sampled texture {} from the GPU ({}x{})", id, width, height);
			return new TextureImage(width, height, pixels);
		} catch (Throwable throwable) {
			LOGGER.warn("Could not read texture {} back from the GPU; its colours will be missing", id, throwable);
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
