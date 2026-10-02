package com.yunfang.entitycapture.capture;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yunfang.entitycapture.mcvox.McvoxHeader;
import com.yunfang.entitycapture.mcvox.McvoxWriter;
import com.yunfang.entitycapture.texture.TextureSampler;
import com.yunfang.entitycapture.voxel.VoxelGrid;
import com.yunfang.entitycapture.voxel.Voxelizer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives a single entity through the capture pipeline: pose it, render it into a
 * capturing buffer source, rasterise the quads into a native-resolution grid and
 * write the `.mcvox`. Must run on the render thread.
 */
public final class RenderCaptureService {
	public static final int UNITS_PER_BLOCK = 16;
	private static final Logger LOGGER = LoggerFactory.getLogger("entity-capture/RenderCaptureService");
	private static final int DUMP_QUAD_LIMIT = 400;

	private RenderCaptureService() {
	}

	public static CaptureResult capture(Entity entity) throws IOException {
		Minecraft client = Minecraft.getInstance();
		EntityRenderDispatcher dispatcher = client.getEntityRenderDispatcher();
		long startedAt = System.nanoTime();

		// Shadows would inject a flat disc of quads into the capture; disable for
		// the duration of the render. There is no getter, so we restore the default.
		dispatcher.setRenderShadow(false);
		// Neutralise poses that are hard to voxelise (tilted zombie-family arms).
		CapturePose.setNeutralPose(true);
		try {
			preparePose(entity);

			TextureSampler sampler = new TextureSampler();
			CapturingMultiBufferSource buffers = new CapturingMultiBufferSource();
			PoseStack poseStack = new PoseStack();
			dispatcher.render(entity, 0.0D, 0.0D, 0.0D, 0.0F, 0.0F, poseStack, buffers, LightTexture.FULL_BRIGHT);
			buffers.flush();

			List<Quad> quads = buffers.quads();
			VoxelGrid grid = Voxelizer.voxelize(quads, sampler, UNITS_PER_BLOCK);

			McvoxHeader header = new McvoxHeader(
					McvoxWriter.FORMAT_VERSION,
					entityId(entity),
					entityName(entity),
					"1.21.1",
					"fabric",
					UNITS_PER_BLOCK,
					new int[] { grid.sizeX, grid.sizeY, grid.sizeZ },
					true,
					0,
					DateTimeFormatter.ISO_INSTANT.format(Instant.now()));

			File directory = new File(client.gameDirectory, "entity-capture");
			Path path = McvoxWriter.write(directory.toPath(), header, grid, true);

			logSummary(entity, buffers, quads, grid, path, (System.nanoTime() - startedAt) / 1_000_000.0D);
			dumpQuads(path, quads);

			return new CaptureResult(path, quads.size(), grid.nonEmptyVoxels(), grid.sizeX, grid.sizeY, grid.sizeZ);
		} finally {
			CapturePose.setNeutralPose(false);
			dispatcher.setRenderShadow(true);
		}
	}

	private static void logSummary(Entity entity, CapturingMultiBufferSource buffers, List<Quad> quads, VoxelGrid grid, Path path, double millis) {
		LOGGER.info("capture {}: quads={} (nullTex={}) textures={} bounds={} grid={}x{}x{} voxels={} time={}ms file={}",
				entityId(entity),
				quads.size(),
				buffers.nullTextureQuads(),
				buffers.quadCountByTexture(),
				bounds(quads),
				grid.sizeX, grid.sizeY, grid.sizeZ,
				grid.nonEmptyVoxels(),
				String.format("%.1f", millis),
				path.getFileName());
		if (!buffers.unresolvedRenderTypes().isEmpty()) {
			LOGGER.warn("capture {}: unresolved render types -> {}", entityId(entity), buffers.unresolvedRenderTypes());
		}
	}

	/** Human-readable vertex AABB in blocks, or "n/a" when there are no quads. */
	private static String bounds(List<Quad> quads) {
		double[] min = { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE };
		double[] max = { -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };
		for (Quad quad : quads) {
			for (Vertex vertex : new Vertex[] { quad.v0(), quad.v1(), quad.v2(), quad.v3() }) {
				min[0] = Math.min(min[0], vertex.x);
				min[1] = Math.min(min[1], vertex.y);
				min[2] = Math.min(min[2], vertex.z);
				max[0] = Math.max(max[0], vertex.x);
				max[1] = Math.max(max[1], vertex.y);
				max[2] = Math.max(max[2], vertex.z);
			}
		}
		if (min[0] > max[0]) {
			return "n/a";
		}
		return String.format("min=(%.3f,%.3f,%.3f) max=(%.3f,%.3f,%.3f)", min[0], min[1], min[2], max[0], max[1], max[2]);
	}

	private static void dumpQuads(Path mcvoxPath, List<Quad> quads) throws IOException {
		StringBuilder text = new StringBuilder();
		text.append("quads=").append(quads.size()).append(" (showing first ").append(Math.min(quads.size(), DUMP_QUAD_LIMIT)).append(")\n");
		int limit = Math.min(quads.size(), DUMP_QUAD_LIMIT);
		for (int i = 0; i < limit; i++) {
			Quad quad = quads.get(i);
			text.append('#').append(i).append(" tex=").append(quad.texture()).append('\n');
			for (Vertex vertex : new Vertex[] { quad.v0(), quad.v1(), quad.v2(), quad.v3() }) {
				text.append(String.format("  p=(%s,%s,%s) uv=(%s,%s) rgba=(%s,%s,%s,%s) n=(%s,%s,%s)%n",
						vertex.x, vertex.y, vertex.z, vertex.u, vertex.v, vertex.r, vertex.g, vertex.b, vertex.a,
						vertex.nx, vertex.ny, vertex.nz));
			}
		}
		Path dumpPath = mcvoxPath.resolveSibling(mcvoxPath.getFileName().toString() + ".quads.txt");
		Files.writeString(dumpPath, text.toString());
		LOGGER.info("capture quad dump written to {}", dumpPath);
	}

	private static void preparePose(Entity entity) {
		entity.setPos(0.0D, 0.0D, 0.0D);
		entity.setOldPosAndRot();
		entity.setDeltaMovement(0.0D, 0.0D, 0.0D);
		entity.setYRot(0.0F);
		entity.setXRot(0.0F);
		entity.setYHeadRot(0.0F);
		entity.setYBodyRot(0.0F);
		entity.tickCount = 1;

		if (entity instanceof LivingEntity living) {
			living.yBodyRot = 0.0F;
			living.yBodyRotO = 0.0F;
			living.yHeadRot = 0.0F;
			living.yHeadRotO = 0.0F;
			living.setYHeadRot(0.0F);
		}
	}

	private static String entityId(Entity entity) {
		ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
		return key == null ? "unknown" : key.toString();
	}

	private static String entityName(Entity entity) {
		return entity.getType().getDescription().getString();
	}

	/** Summary of a completed capture. */
	public record CaptureResult(Path path, int quads, int voxels, int sizeX, int sizeY, int sizeZ) {
	}
}
