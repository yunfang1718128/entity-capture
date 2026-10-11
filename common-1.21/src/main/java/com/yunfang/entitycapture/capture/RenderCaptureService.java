package com.yunfang.entitycapture.capture;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yunfang.entitycapture.EntityCapture;
import com.yunfang.entitycapture.config.EntityCaptureConfig;
import com.yunfang.entitycapture.mcvox.McvoxHeader;
import com.yunfang.entitycapture.mcvox.McvoxWriter;
import com.yunfang.entitycapture.mixin.PlayerModeCustomisationAccessor;
import com.yunfang.entitycapture.texture.TextureSampler;
import com.yunfang.entitycapture.voxel.VoxelGrid;
import com.yunfang.entitycapture.voxel.Voxelizer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
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
	private static final int DUMP_QUAD_LIMIT = 4000;
	/**
	 * Diagnostic quad dump is opt-in: set {@code dumpQuads = true} in
	 * {@code entity-capture.toml}, or set {@code ENTITYCAPTURE_DUMP_QUADS} in the
	 * environment for a one-off run. Left off, a capture writes only the
	 * {@code .mcvox} and never a side-car {@code .quads.txt}.
	 */
	private static boolean dumpQuadsEnabled() {
		if (EntityCaptureConfig.get().dumpQuads) {
			return true;
		}
		String value = System.getenv("ENTITYCAPTURE_DUMP_QUADS");
		return value != null && (value.equalsIgnoreCase("true") || value.equals("1"));
	}
	private static final EntityDataAccessor<Byte> PLAYER_MODEL_PARTS = PlayerModeCustomisationAccessor.getModeCustomisation();
	/** {@code PlayerModelPart.CAPE} owns bit 0 of the model-part byte; bits 1-6 are the skin's outer layers. */
	private static final int PLAYER_MODEL_PART_CAPE_BIT = 0x01;

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
			List<Quad> base;
			List<Quad> overlay = List.of();
			CapturingMultiBufferSource stats;
			int modelParts = entity instanceof Player ? entity.getEntityData().get(PLAYER_MODEL_PARTS) : 0;
			if ((modelParts & ~PLAYER_MODEL_PART_CAPE_BIT) != 0) {
				// A skin's outer layer renders as a shell a fraction of a pixel
				// outside the body, which the voxeliser must treat as a decal rather
				// than solid geometry (see Voxelizer). Isolate it by rendering once
				// with the six outer parts hidden and once with them back, then
				// diffing: the delta is exactly the shell. The cape keeps its bit in
				// the inner pass — it is a real hanging mesh, not an inflated shell,
				// and must stay solid geometry.
				entity.getEntityData().set(PLAYER_MODEL_PARTS, (byte) (modelParts & PLAYER_MODEL_PART_CAPE_BIT));
				CapturingMultiBufferSource inner;
				try {
					inner = renderPass(entity, dispatcher);
				} finally {
					entity.getEntityData().set(PLAYER_MODEL_PARTS, (byte) modelParts);
				}
				CapturingMultiBufferSource full = renderPass(entity, dispatcher);
				base = inner.quads();
				overlay = notIn(inner.quads(), full.quads());
				stats = full;
				LOGGER.info("two-pass player capture: modelParts=0x{} innerQuads={} fullQuads={} overlayQuads={}",
						Integer.toHexString(modelParts), base.size(), full.quads().size(), overlay.size());
			} else {
				CapturingMultiBufferSource buffers = renderPass(entity, dispatcher);
				base = buffers.quads();
				stats = buffers;
				if (entity instanceof Player) {
					LOGGER.info("single-pass player capture: modelParts=0x{} quads={}",
							Integer.toHexString(modelParts), base.size());
				}
			}
			Voxelizer.Stats voxelStats = new Voxelizer.Stats();
			VoxelGrid grid = Voxelizer.voxelize(base, overlay, sampler, UNITS_PER_BLOCK, voxelStats);

			McvoxHeader header = new McvoxHeader(
					McvoxWriter.FORMAT_VERSION,
					entityId(entity),
					entityName(entity),
					EntityCapture.mcVersion(),
					EntityCapture.loader(),
					UNITS_PER_BLOCK,
					new int[] { grid.sizeX, grid.sizeY, grid.sizeZ },
					true,
					0,
					DateTimeFormatter.ISO_INSTANT.format(Instant.now()));

			File directory = new File(client.gameDirectory, "entity-capture");
			Path path = McvoxWriter.write(directory.toPath(), header, grid, true);

			List<Quad> quads = stats.quads();
			logSummary(entity, stats, quads, grid, voxelStats, path, (System.nanoTime() - startedAt) / 1_000_000.0D);
			if (dumpQuadsEnabled()) {
				dumpQuads(path, quads);
			}

			return new CaptureResult(path, quads.size(), grid.nonEmptyVoxels(), grid.sizeX, grid.sizeY, grid.sizeZ);
		} finally {
			CapturePose.setNeutralPose(false);
			dispatcher.setRenderShadow(true);
		}
	}

	/** One off-screen render of the entity into a capturing buffer. */
	private static CapturingMultiBufferSource renderPass(Entity entity, EntityRenderDispatcher dispatcher) {
		CapturingMultiBufferSource buffers = new CapturingMultiBufferSource();
		PoseStack poseStack = new PoseStack();
		// Let renderers that grab the global buffer source (e.g. GeckoLib
		// armour) be captured too; see BufferSourceMixin.
		CaptureSession.begin(buffers);
		try {
			dispatcher.render(entity, 0.0D, 0.0D, 0.0D, 0.0F, 0.0F, poseStack, buffers, LightTexture.FULL_BRIGHT);
			buffers.flush();
		} finally {
			CaptureSession.end();
		}
		return buffers;
	}

	/** Quads present in {@code full} but not matched one-for-one by {@code inner}. */
	private static List<Quad> notIn(List<Quad> inner, List<Quad> full) {
		Map<String, Integer> remaining = new HashMap<>();
		for (Quad quad : inner) {
			remaining.merge(quadKey(quad), 1, Integer::sum);
		}
		List<Quad> overlay = new ArrayList<>();
		for (Quad quad : full) {
			String key = quadKey(quad);
			Integer count = remaining.get(key);
			if (count == null || count == 0) {
				overlay.add(quad);
			} else {
				remaining.put(key, count - 1);
			}
		}
		return overlay;
	}

	/** Identity of a quad for the two-pass diff: texture plus quantised geometry. */
	private static String quadKey(Quad quad) {
		StringBuilder key = new StringBuilder().append(quad.texture());
		for (Vertex vertex : new Vertex[] { quad.v0(), quad.v1(), quad.v2(), quad.v3() }) {
			key.append('|').append(Math.round(vertex.x * 1.0e5F)).append(',')
					.append(Math.round(vertex.y * 1.0e5F)).append(',')
					.append(Math.round(vertex.z * 1.0e5F)).append(',')
					.append(Math.round(vertex.u * 1.0e5F)).append(',')
					.append(Math.round(vertex.v * 1.0e5F));
		}
		return key.toString();
	}

	/**
	 * Captures an entity that is <em>live in the world</em> by temporarily
	 * relocating and re-posing it for the render, then restoring its exact
	 * position, rotation, motion and tick count. Used for mods whose entities do
	 * not survive a client-side NBT round-trip (see
	 * {@link com.yunfang.entitycapture.compat.ModCaptureCompat}), where cloning
	 * the entity would silently produce a different variant.
	 *
	 * <p>The entity is restored in a {@code finally} block, so an exception during
	 * rendering never leaves it displaced.
	 */
	public static CaptureResult captureLive(Entity entity) throws IOException {
		double x = entity.getX();
		double y = entity.getY();
		double z = entity.getZ();
		float xRot = entity.getXRot();
		float yRot = entity.getYRot();
		Vec3 motion = entity.getDeltaMovement();
		int tickCount = entity.tickCount;
		float yHeadRot = yRot;
		float yHeadRotO = yRot;
		float yBodyRot = yRot;
		float yBodyRotO = yRot;
		LivingEntity living = entity instanceof LivingEntity livingEntity ? livingEntity : null;
		if (living != null) {
			yHeadRot = living.yHeadRot;
			yHeadRotO = living.yHeadRotO;
			yBodyRot = living.yBodyRot;
			yBodyRotO = living.yBodyRotO;
		}
		try {
			return capture(entity);
		} finally {
			entity.setPos(x, y, z);
			entity.setYRot(yRot);
			entity.setXRot(xRot);
			entity.setYHeadRot(yHeadRot);
			entity.setYBodyRot(yBodyRot);
			entity.setDeltaMovement(motion);
			entity.tickCount = tickCount;
			if (living != null) {
				living.yHeadRot = yHeadRot;
				living.yHeadRotO = yHeadRotO;
				living.yBodyRot = yBodyRot;
				living.yBodyRotO = yBodyRotO;
			}
			entity.setOldPosAndRot();
		}
	}

	private static void logSummary(Entity entity, CapturingMultiBufferSource buffers, List<Quad> quads, VoxelGrid grid, Voxelizer.Stats voxelStats, Path path, double millis) {
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
		if (dumpQuadsEnabled()) {
			// Which layers were asked for at all — distinguishes "that layer never drew"
			// from "it drew but we could not read its texture".
			LOGGER.info("capture {}: requested render types -> {}", entityId(entity), buffers.requestedRenderTypes());
		}
		logMirrorStatus(entity, voxelStats);
	}

	/**
	 * Reports the left/right verdict for this capture. The rasteriser is
	 * equivariant by construction, so a mirror-symmetric model must come out
	 * symmetric; anything else is either a model whose own geometry is asymmetric
	 * (a mod's animated pose, a one-sided part) or a pipeline regression. These
	 * lines are what tells the two apart without a debugger or a hex editor.
	 */
	private static void logMirrorStatus(Entity entity, Voxelizer.Stats voxelStats) {
		if (voxelStats.unmatchedMirrorQuads < 0) {
			return;
		}
		if (voxelStats.unmatchedMirrorQuads > 0) {
			LOGGER.info("capture {}: source is not mirror-symmetric ({} quads have no mirror partner) — "
							+ "the model's own geometry is asymmetric, so left/right output may legitimately differ",
					entityId(entity), voxelStats.unmatchedMirrorQuads);
			return;
		}
		if (voxelStats.mirrorCellsAdded > 0) {
			LOGGER.warn("capture {}: mirror-symmetric source needed {} silhouette cells patched — "
							+ "pipeline defect, please report it", entityId(entity), voxelStats.mirrorCellsAdded);
			return;
		}
		// Colour is a property of the model's own artwork as well as of the pipeline:
		// a skin whose left and right sides are not mirrored copies reports a count
		// here and is not a defect. Reported, not asserted.
		LOGGER.info("capture {}: mirror check ok (symmetric source, no patching; {} mirror pairs coloured differently; "
						+ "density={} factor={} geometryScale={})",
				entityId(entity), voxelStats.mirrorColourMismatches, String.format("%.4f", voxelStats.densityUsed),
				voxelStats.supersampleFactor, String.format("%.4f", voxelStats.geometryScale));
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
