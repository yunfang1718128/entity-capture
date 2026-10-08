package com.yunfang.entitycapture.voxel;

import java.util.ArrayList;
import java.util.List;

import com.yunfang.entitycapture.capture.Quad;
import com.yunfang.entitycapture.capture.Vertex;
import com.yunfang.entitycapture.texture.TextureImage;
import com.yunfang.entitycapture.texture.TextureSampler;
import net.minecraft.resources.ResourceLocation;

/**
 * Rasterises captured quads into a native-resolution voxel grid. Each quad is
 * split into two triangles; for every grid cell in the triangle's projected
 * bounding box we test barycentric containment, resolve the missing axis via
 * the barycentric weights, and pull the surface colour from the texture.
 *
 * <p>Two details matter for clean, symmetric output:
 * <ul>
 *   <li>The grid origin is snapped to an integer model pixel, so a symmetric
 *       model (mirrored about render {@code x = 0}) lands on a voxel boundary.</li>
 *   <li>A face plane is mapped to the voxel on its <em>interior</em> side (half a
 *       voxel along the inward normal), which keeps both extremes symmetric and
 *       prevents the loss of shared edges/ridges.</li>
 * </ul>
 *
 * Model geometry is emitted in blocks (16 model pixels per block), so
 * {@code unitsPerBlock = 16} yields 1 voxel per model pixel.
 */
public final class Voxelizer {
	private static final float ALPHA_CUTOFF = 0.1F;
	private static final double EPSILON = 1.0e-9;
	/**
	 * Block-space tolerance for deciding a model is mirrored across x = 0.
	 * Posed limbs (a piglin's bent arms) can differ by a hair (< 0.03) on the
	 * two sides; a too-tight tolerance wrongly declares such a mob asymmetric
	 * and skips the mirror repair, leaving one side of the back jutting out.
	 */
	private static final double SYMMETRY_TOLERANCE = 0.05D;
	/**
	 * A face plane whose depth lands within this many voxels of an integer is
	 * snapped to it. Sub-pixel pose tilt (a zombie leg is ~0.29&deg; rotated)
	 * otherwise pushes a face a fraction past a boundary and the rasteriser
	 * eats an extra layer along the whole edge.
	 */
	private static final double PLANE_SNAP = 0.15D;
	/** Guard against accidentally capturing something enormous. */
	private static final int MAX_DIM = 1024;
	/** Largest integer supersample applied when a texture is finer than the grid. */
	private static final int MAX_SUPERSAMPLE = 4;
	/** Supersample is dropped back to 1 when the resulting grid would exceed this. */
	private static final long MAX_SUPERSAMPLE_VOXELS = 8_000_000L;

	private Voxelizer() {
	}

	public static VoxelGrid voxelize(List<Quad> quads, TextureSampler sampler, int unitsPerBlock) {
		if (quads.isEmpty()) {
			return new VoxelGrid(1, 1, 1);
		}

		// Match the voxel pitch to the texture pitch so one texel owns one voxel:
		//  - density < 1: the model renders larger than its texture (a scaled-up
		//    entity, e.g. a husk at 17/16 or a 6x giant), so pull the geometry
		//    back to the texture pitch; otherwise every face gains a whole extra
		//    layer and joints grow notches.
		//  - density > 1: the model renders smaller than its texture. An integral
		//    ratio (a cat's face squeezed into half as many pixels) is worth a real
		//    supersample, which keeps fine features such as the gap between two
		//    eyes. A merely fractional one is not: a player is drawn at 15/16 (see
		//    PlayerRenderer#scale) while its skin is 1 texel per model pixel, so
		//    rounding 1.067 up to 2 doubles the whole grid — and, because the 15/16
		//    geometry then sits off the voxel grid, makes the outline ripple. Scale
		//    the geometry to the texture pitch instead, exactly as the density < 1
		//    case does in the other direction. (The same trap catches every model
		//    drawn at a fractional scale: illagers, villagers, witches and
		//    wandering traders all use 15/16 too.)
		double density = maxTexelDensity(quads, sampler, unitsPerBlock);
		int factor = 1;
		double geometryScale = 1.0D;
		if (density > 1.0D + 1.0e-3) {
			if (Math.round(density) >= 2) {
				factor = Math.min(MAX_SUPERSAMPLE, (int) Math.ceil(density - 1.0e-3));
			} else {
				geometryScale = density;
			}
		} else if (density > 1.0e-6 && density < 1.0D - 1.0e-3) {
			// Guard the lower bound: density stays 0 when no texture could be read,
			// and we must not collapse the geometry in that case.
			geometryScale = density;
		}
		if (geometryScale != 1.0D) {
			quads = scaleAll(quads, geometryScale);
		}

		double[] min = { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE };
		double[] max = { -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };
		for (Quad quad : quads) {
			if (quad.texture() == null) {
				continue;
			}
			include(min, max, quad.v0());
			include(min, max, quad.v1());
			include(min, max, quad.v2());
			include(min, max, quad.v3());
		}
		for (int axis = 0; axis < 3; axis++) {
			if (min[axis] > max[axis]) {
				return new VoxelGrid(1, 1, 1);
			}
		}

		double scale = (double) unitsPerBlock * factor;
		double[] origin = new double[3];
		int[] size = new int[3];
		computeBounds(min, max, scale, origin, size);
		while (factor > 1 && (long) size[0] * size[1] * size[2] > MAX_SUPERSAMPLE_VOXELS) {
			factor--;
			scale = (double) unitsPerBlock * factor;
			computeBounds(min, max, scale, origin, size);
		}

		VoxelGrid grid = new VoxelGrid(size[0], size[1], size[2]);
		RasterContext context = new RasterContext(grid, sampler, scale, origin, size);

		for (Quad quad : quads) {
			if (quad.texture() == null) {
				continue;
			}
			rasterize(context, quad, quad.v0(), quad.v1(), quad.v2());
			rasterize(context, quad, quad.v0(), quad.v2(), quad.v3());
		}

		// Many mob models are mirror-symmetric about x = 0; rounding at the
		// sub-pixel level (overlapping legs, fur layers, tilted arms) can still
		// leave a one-voxel lopsided result, so mirror the +x half onto the -x
		// half for those models.
		if (isMirrorSymmetricX(quads)) {
			grid.enforceXMirrorSymmetry();
		}
		return grid;
	}

	/**
	 * Snap the grid to whole pixels at the given scale. Using floor/ceil (rather
	 * than round) keeps fractional, symmetric extents (e.g. a fur layer) from
	 * being clipped; the epsilon absorbs float noise at exact integer pixels.
	 */
	private static void computeBounds(double[] min, double[] max, double scale, double[] origin, int[] size) {
		for (int axis = 0; axis < 3; axis++) {
			int originPx = (int) Math.floor(min[axis] * scale + 1.0e-3);
			int endPx = (int) Math.ceil(max[axis] * scale - 1.0e-3);
			origin[axis] = originPx / scale;
			size[axis] = clampDim(endPx - originPx);
		}
	}

	/**
	 * Largest texture-texels-per-voxel ratio over all quad edges. Exactly 1 for a
	 * model whose texture matches its render size; greater than 1 when the
	 * texture is finer (needs supersampling); less than 1 when the model renders
	 * larger than its texture (needs normalising down).
	 */
	private static double maxTexelDensity(List<Quad> quads, TextureSampler sampler, int unitsPerBlock) {
		double density = 0.0D;
		for (Quad quad : quads) {
			if (quad.texture() == null) {
				continue;
			}
			TextureImage image = sampler.image(quad.texture());
			if (image == null) {
				continue;
			}
			density = Math.max(density, texelDensity(quad.v0(), quad.v1(), image, unitsPerBlock));
			density = Math.max(density, texelDensity(quad.v1(), quad.v2(), image, unitsPerBlock));
		}
		return density;
	}

	/** Uniformly scales every vertex position (UV, tint and normal untouched). */
	private static List<Quad> scaleAll(List<Quad> quads, double factor) {
		List<Quad> scaled = new ArrayList<>(quads.size());
		for (Quad quad : quads) {
			scaled.add(new Quad(
					scaleVertex(quad.v0(), factor),
					scaleVertex(quad.v1(), factor),
					scaleVertex(quad.v2(), factor),
					scaleVertex(quad.v3(), factor),
					quad.texture(),
					quad.layer()));
		}
		return scaled;
	}

	private static Vertex scaleVertex(Vertex vertex, double factor) {
		Vertex scaled = new Vertex(vertex);
		scaled.x = (float) (vertex.x * factor);
		scaled.y = (float) (vertex.y * factor);
		scaled.z = (float) (vertex.z * factor);
		return scaled;
	}

	/** Texture texels spanned by a quad edge per base voxel along that edge. */
	private static double texelDensity(Vertex a, Vertex b, TextureImage image, int unitsPerBlock) {
		double du = (double) (b.u - a.u) * image.width;
		double dv = (double) (b.v - a.v) * image.height;
		double texels = Math.sqrt(du * du + dv * dv);
		double dx = b.x - a.x;
		double dy = b.y - a.y;
		double dz = b.z - a.z;
		double voxels = Math.sqrt(dx * dx + dy * dy + dz * dz) * unitsPerBlock;
		if (voxels < 1.0e-6) {
			return 1.0D;
		}
		return texels / voxels;
	}

	/**
	 * True when every quad has a counterpart mirrored across x = 0 (its centroid
	 * points to {@code (-x, y, z)}). Tolerance absorbs float noise in tilted parts.
	 */
	private static boolean isMirrorSymmetricX(List<Quad> quads) {
		double[][] centroids = new double[quads.size()][3];
		for (int i = 0; i < quads.size(); i++) {
			Quad quad = quads.get(i);
			centroids[i][0] = (quad.v0().x + quad.v1().x + quad.v2().x + quad.v3().x) / 4.0D;
			centroids[i][1] = (quad.v0().y + quad.v1().y + quad.v2().y + quad.v3().y) / 4.0D;
			centroids[i][2] = (quad.v0().z + quad.v1().z + quad.v2().z + quad.v3().z) / 4.0D;
		}

		for (int i = 0; i < centroids.length; i++) {
			boolean matched = false;
			for (int j = 0; j < centroids.length; j++) {
				if (Math.abs(centroids[j][0] + centroids[i][0]) < SYMMETRY_TOLERANCE
						&& Math.abs(centroids[j][1] - centroids[i][1]) < SYMMETRY_TOLERANCE
						&& Math.abs(centroids[j][2] - centroids[i][2]) < SYMMETRY_TOLERANCE) {
					matched = true;
					break;
				}
			}
			if (!matched) {
				return false;
			}
		}
		return true;
	}

	private static void include(double[] min, double[] max, Vertex vertex) {
		min[0] = Math.min(min[0], vertex.x);
		min[1] = Math.min(min[1], vertex.y);
		min[2] = Math.min(min[2], vertex.z);
		max[0] = Math.max(max[0], vertex.x);
		max[1] = Math.max(max[1], vertex.y);
		max[2] = Math.max(max[2], vertex.z);
	}

	private static void rasterize(RasterContext context, Quad quad, Vertex a, Vertex b, Vertex c) {
		double nx = (double) (b.y - a.y) * (c.z - a.z) - (double) (b.z - a.z) * (c.y - a.y);
		double ny = (double) (b.z - a.z) * (c.x - a.x) - (double) (b.x - a.x) * (c.z - a.z);
		double nz = (double) (b.x - a.x) * (c.y - a.y) - (double) (b.y - a.y) * (c.x - a.x);
		double nxAbs = Math.abs(nx);
		double nyAbs = Math.abs(ny);
		double nzAbs = Math.abs(nz);
		if (nxAbs < EPSILON && nyAbs < EPSILON && nzAbs < EPSILON) {
			return;
		}

		int dominant;
		if (nxAbs >= nyAbs && nxAbs >= nzAbs) {
			dominant = 0;
		} else if (nyAbs >= nzAbs) {
			dominant = 1;
		} else {
			dominant = 2;
		}

		// Prefer the model-provided normal for the outward direction: MC's
		// `mirror` cubes reverse the vertex winding, which would otherwise flip
		// the sign we derive from the cross product.
		double pnx = a.nx + b.nx + c.nx;
		double pny = a.ny + b.ny + c.ny;
		double pnz = a.nz + b.nz + c.nz;
		double fnx;
		double fny;
		double fnz;
		if (pnx * pnx + pny * pny + pnz * pnz > 1.0e-4) {
			fnx = pnx;
			fny = pny;
			fnz = pnz;
		} else {
			fnx = nx;
			fny = ny;
			fnz = nz;
		}
		double normalLength = Math.sqrt(fnx * fnx + fny * fny + fnz * fnz);
		if (normalLength > EPSILON) {
			fnx /= normalLength;
			fny /= normalLength;
			fnz /= normalLength;
		}
		double dominantNormal = dominant == 0 ? fnx : (dominant == 1 ? fny : fnz);

		// A face at plane P joins the solid on the side opposite its outward
		// normal: a +normal face owns the cell just below P (ceil(P)-1), while a
		// -normal face owns the cell just above P (floor(P)). In voxel units that
		// is a half-voxel shift only for +normal faces.
		double inward = dominantNormal > 0.0 ? -0.5 : 0.0;
		int facePriority = facePriority(fnx, fny, fnz);

		int axisA = (dominant == 0) ? 1 : 0;
		int axisB = (dominant == 2) ? 1 : 2;

		double ax = component(a, axisA);
		double ay = component(a, axisB);
		double bx = component(b, axisA);
		double by = component(b, axisB);
		double cx = component(c, axisA);
		double cy = component(c, axisB);

		double denominator = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy);
		if (Math.abs(denominator) < EPSILON) {
			return;
		}

		double minA = Math.min(ax, Math.min(bx, cx));
		double maxA = Math.max(ax, Math.max(bx, cx));
		double minB = Math.min(ay, Math.min(by, cy));
		double maxB = Math.max(ay, Math.max(by, cy));

		double scale = context.scale();
		double originA = context.origin()[axisA];
		double originB = context.origin()[axisB];
		int startA = (int) Math.floor((minA - originA) * scale);
		int endA = (int) Math.ceil((maxA - originA) * scale);
		int startB = (int) Math.floor((minB - originB) * scale);
		int endB = (int) Math.ceil((maxB - originB) * scale);

		startA = Math.max(startA, -1);
		startB = Math.max(startB, -1);
		endA = Math.min(endA, context.size()[axisA]);
		endB = Math.min(endB, context.size()[axisB]);

		for (int ia = startA; ia <= endA; ia++) {
			double pa = originA + (ia + 0.5) / scale;
			for (int ib = startB; ib <= endB; ib++) {
				double pb = originB + (ib + 0.5) / scale;

				double wA = ((by - cy) * (pa - cx) + (cx - bx) * (pb - cy)) / denominator;
				double wB = ((cy - ay) * (pa - cx) + (ax - cx) * (pb - cy)) / denominator;
				double wC = 1.0 - wA - wB;

				if (wA < -EPSILON || wB < -EPSILON || wC < -EPSILON) {
					continue;
				}

				double pc = wA * component(a, dominant) + wB * component(b, dominant) + wC * component(c, dominant);
				double off = (pc - context.origin()[dominant]) * scale;
				double nearest = Math.floor(off + 0.5D);
				if (Math.abs(off - nearest) < PLANE_SNAP) {
					off = nearest;
				}
				int ic = (int) Math.floor(off + inward + 1.0e-6);
				if (ic < 0) {
					ic = 0;
				} else if (ic >= context.size()[dominant]) {
					ic = context.size()[dominant] - 1;
				}

				int x = axisValue(dominant, axisA, axisB, 0, ic, ia, ib);
				int y = axisValue(dominant, axisA, axisB, 1, ic, ia, ib);
				int z = axisValue(dominant, axisA, axisB, 2, ic, ia, ib);
				if (!context.grid().inBounds(x, y, z)) {
					continue;
				}

				float u = (float) (wA * a.u + wB * b.u + wC * c.u);
				float v = (float) (wA * a.v + wB * b.v + wC * c.v);
				ResourceLocation texture = quad.texture();
				int argb = context.sampler().sample(texture, u, v);
				int alpha = (argb >>> 24) & 0xFF;
				if (alpha / 255.0F < ALPHA_CUTOFF) {
					continue;
				}

				float tintR = (float) (wA * a.r + wB * b.r + wC * c.r);
				float tintG = (float) (wA * a.g + wB * b.g + wC * c.g);
				float tintB = (float) (wA * a.b + wB * b.b + wC * c.b);
				float tintA = (float) (wA * a.a + wB * b.a + wC * c.a);

				int red = clampByte((int) (((argb >> 16) & 0xFF) * tintR));
				int green = clampByte((int) (((argb >> 8) & 0xFF) * tintG));
				int blue = clampByte((int) ((argb & 0xFF) * tintB));
				int outAlpha = clampByte((int) (alpha * tintA));

				context.grid().set(x, y, z, red, green, blue, outAlpha, facePriority, quad.layer());
			}
		}
	}

	/**
	 * Ranks a face normal so the one facing the model's front ({@code +Z}) wins
	 * colour conflicts, then the top ({@code +Y}), then the sides ({@code |X|}).
	 */
	private static int facePriority(double nx, double ny, double nz) {
		int pz = nz > 0.5 ? 2 : (nz < -0.5 ? 0 : 1);
		int py = ny > 0.5 ? 1 : 0;
		int px = Math.abs(nx) > 0.5 ? 1 : 0;
		return pz * 4 + py * 2 + px;
	}

	private static int axisValue(int dominant, int axisA, int axisB, int axis, int dominantIndex, int indexA, int indexB) {
		if (axis == dominant) {
			return dominantIndex;
		}
		if (axis == axisA) {
			return indexA;
		}
		return indexB;
	}

	private static double component(Vertex vertex, int axis) {
		switch (axis) {
			case 0:
				return vertex.x;
			case 1:
				return vertex.y;
			default:
				return vertex.z;
		}
	}

	private static int clampByte(int value) {
		if (value < 0) {
			return 0;
		}
		return Math.min(value, 255);
	}

	private static int clampDim(int value) {
		if (value < 1) {
			return 1;
		}
		return Math.min(value, MAX_DIM);
	}

	private record RasterContext(VoxelGrid grid, TextureSampler sampler, double scale, double[] origin, int[] size) {
	}
}
