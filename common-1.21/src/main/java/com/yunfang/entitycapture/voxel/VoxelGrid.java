package com.yunfang.entitycapture.voxel;

/**
 * Native-resolution voxel grid: a bit-packed occupancy map plus an RGBA surface
 * colour layer. Index layout matches the studio's {@code VoxelModel}:
 * {@code x + z * sizeX + y * sizeX * sizeZ}.
 *
 * <p>Every coloured voxel also stores a rank describing which window owns it:
 * the render layer (outer/overlay beats inner) first, then the face orientation
 * (front {@code +Z} beats top, then sides). Higher rank wins a conflict.
 */
public final class VoxelGrid {
	public final int sizeX;
	public final int sizeY;
	public final int sizeZ;
	private final byte[] occupancy;
	private final byte[] colors;
	/** Packed rank of the winning quad: {@code (layer << 6) | facePriority}. */
	private final int[] rank;

	public VoxelGrid(int sizeX, int sizeY, int sizeZ) {
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		int count = sizeX * sizeY * sizeZ;
		this.occupancy = new byte[(count + 7) / 8];
		this.colors = new byte[count * 4];
		this.rank = new int[count];
	}

	private int index(int x, int y, int z) {
		return x + z * this.sizeX + y * this.sizeX * this.sizeZ;
	}

	/** Packs the layer (outer wins) and face orientation into one comparable value. */
	private static int rank(int layer, int facePriority) {
		return (Math.max(0, layer) << 6) | (facePriority & 0x3F);
	}

	public boolean inBounds(int x, int y, int z) {
		return x >= 0 && y >= 0 && z >= 0 && x < this.sizeX && y < this.sizeY && z < this.sizeZ;
	}

	/** Whether a cell currently holds a surface voxel. */
	public boolean isOccupied(int x, int y, int z) {
		if (!this.inBounds(x, y, z)) {
			return false;
		}
		int index = this.index(x, y, z);
		return (this.occupancy[index >> 3] & (1 << (index & 7))) != 0;
	}

	/**
	 * Marks a voxel occupied and stores its (premultiplied-free) RGBA surface
	 * colour. When several windows meet at one voxel the higher rank wins (outer
	 * layer first, then front-facing orientation); on a full tie the darker
	 * colour wins, which keeps dark features such as eyes that sit on the edge
	 * of a face.
	 */
	public void set(int x, int y, int z, int r, int g, int b, int a, int facePriority, int layer) {
		if (!this.inBounds(x, y, z)) {
			return;
		}
		int index = this.index(x, y, z);
		this.occupancy[index >> 3] |= (byte) (1 << (index & 7));
		int offset = index * 4;
		int incomingRank = rank(layer, facePriority);

		if ((this.colors[offset + 3] & 0xFF) != 0) {
			int existingRank = this.rank[index];
			if (incomingRank < existingRank) {
				return;
			}
			if (incomingRank == existingRank && luminance(this.colors, offset) <= r + g + b) {
				return;
			}
		}

		this.rank[index] = incomingRank;
		this.colors[offset] = (byte) r;
		this.colors[offset + 1] = (byte) g;
		this.colors[offset + 2] = (byte) b;
		this.colors[offset + 3] = (byte) a;
	}

	/**
	 * Paints over an <em>existing</em> surface voxel and never creates a new one:
	 * an unoccupied cell is left untouched. Used for a player skin's outer layer,
	 * which the game draws as a thin shell only a fraction of a voxel outside the
	 * body — at native resolution it must recolour the surface it covers rather
	 * than add a voxel shell (which would fuse sleeves to jackets and bury the
	 * face). Semi-transparent overlay texels are composited over the existing
	 * colour, matching how the game blends them.
	 */
	public void paint(int x, int y, int z, int r, int g, int b, int a, int facePriority, int layer) {
		if (!this.isOccupied(x, y, z)) {
			return;
		}
		this.paintInto(this.index(x, y, z), r, g, b, a, facePriority, layer);
	}

	private void paintInto(int index, int r, int g, int b, int a, int facePriority, int layer) {
		int offset = index * 4;
		int incomingRank = rank(layer, facePriority);
		int existingRank = this.rank[index];
		if (incomingRank < existingRank) {
			return;
		}
		if (incomingRank == existingRank && luminance(this.colors, offset) <= r + g + b) {
			return;
		}
		if (a < 255) {
			int dstA = this.colors[offset + 3] & 0xFF;
			int newA = a + dstA * (255 - a) / 255;
			if (newA == 0) {
				return;
			}
			r = (r * a + (this.colors[offset] & 0xFF) * dstA * (255 - a) / 255) / newA;
			g = (g * a + (this.colors[offset + 1] & 0xFF) * dstA * (255 - a) / 255) / newA;
			b = (b * a + (this.colors[offset + 2] & 0xFF) * dstA * (255 - a) / 255) / newA;
			a = newA;
		}
		this.rank[index] = incomingRank;
		this.colors[offset] = (byte) r;
		this.colors[offset + 1] = (byte) g;
		this.colors[offset + 2] = (byte) b;
		this.colors[offset + 3] = (byte) a;
	}

	private static int luminance(byte[] colors, int offset) {
		return (colors[offset] & 0xFF) + (colors[offset + 1] & 0xFF) + (colors[offset + 2] & 0xFF);
	}

	/**
	 * Union the two halves of a model that is mirror-symmetric about {@code x = 0}.
	 * Colour is only copied into a side that has none — when both sides carry a
	 * colour they are left alone, so a bright one-sided feature (a white eye, for
	 * instance) is never painted over by the other side.
	 *
	 * <p>The rasteriser is equivariant, so a symmetric model should need nothing
	 * from this method. It reports how many silhouette cells it had to add: zero is
	 * the expected answer, and a non-zero one is a signal for the caller to log
	 * rather than a routine repair to keep quiet about.
	 *
	 * @return silhouette cells added to make the occupancy symmetric
	 */
	public int enforceXMirrorSymmetry() {
		int added = 0;
		int half = this.sizeX / 2;
		for (int x = 0; x < half; x++) {
			int mirror = this.sizeX - 1 - x;
			for (int y = 0; y < this.sizeY; y++) {
				for (int z = 0; z < this.sizeZ; z++) {
					added += this.mergeMirrorPair(this.index(mirror, y, z), this.index(x, y, z));
				}
			}
		}
		return added;
	}

	/**
	 * Silhouette cells that are missing on one side of a mirror pair, i.e. how many
	 * cells {@link #enforceXMirrorSymmetry()} would have to add. Measured without
	 * changing anything, so "faithful" mode can report what it chose not to repair.
	 */
	public int mirrorMissingCells() {
		int missing = 0;
		int half = this.sizeX / 2;
		for (int x = 0; x < half; x++) {
			int mirror = this.sizeX - 1 - x;
			for (int y = 0; y < this.sizeY; y++) {
				for (int z = 0; z < this.sizeZ; z++) {
					boolean a = this.occupied(this.index(x, y, z));
					boolean b = this.occupied(this.index(mirror, y, z));
					if (a != b) {
						missing++;
					}
				}
			}
		}
		return missing;
	}

	/**
	 * Mirror pairs that are both occupied yet coloured differently. A symmetric
	 * model on a symmetric pipeline reports zero; anything else is either artwork
	 * that genuinely differs left to right, or an asymmetry that slipped through.
	 */
	public int mirrorColourMismatches() {
		int mismatches = 0;
		int half = this.sizeX / 2;
		for (int x = 0; x < half; x++) {
			int mirror = this.sizeX - 1 - x;
			for (int y = 0; y < this.sizeY; y++) {
				for (int z = 0; z < this.sizeZ; z++) {
					int a = this.index(x, y, z);
					int b = this.index(mirror, y, z);
					if (!this.occupied(a) || !this.occupied(b)) {
						continue;
					}
					if (this.colors[a * 4] != this.colors[b * 4]
							|| this.colors[a * 4 + 1] != this.colors[b * 4 + 1]
							|| this.colors[a * 4 + 2] != this.colors[b * 4 + 2]) {
						mismatches++;
					}
				}
			}
		}
		return mismatches;
	}

	private boolean occupied(int index) {
		return (this.occupancy[index >> 3] & (1 << (index & 7))) != 0;
	}

	/** @return silhouette cells added for this pair, i.e. 0 when it was already symmetric */
	private int mergeMirrorPair(int a, int b) {
		boolean occupiedA = this.occupied(a);
		boolean occupiedB = this.occupied(b);
		if (!occupiedA && !occupiedB) {
			return 0;
		}
		int added = (occupiedA ? 0 : 1) + (occupiedB ? 0 : 1);

		// Union the occupancy so the silhouette stays symmetric.
		this.occupancy[a >> 3] |= (byte) (1 << (a & 7));
		this.occupancy[b >> 3] |= (byte) (1 << (b & 7));

		boolean coloredA = (this.colors[a * 4 + 3] & 0xFF) > 0;
		boolean coloredB = (this.colors[b * 4 + 3] & 0xFF) > 0;

		if (coloredA && coloredB) {
			int rankA = this.rank[a];
			int rankB = this.rank[b];
			if (rankA == rankB) {
				// Same window on both sides: keep each side's own colour so a
				// feature present on one side is not erased by the other.
				return added;
			}
			this.copyColor(rankA > rankB ? a : b, a, b);
		} else if (coloredA) {
			this.copyColor(a, a, b);
		} else if (coloredB) {
			this.copyColor(b, a, b);
		}
		return added;
	}

	private void copyColor(int source, int a, int b) {
		int sourceRank = this.rank[source];
		byte r = this.colors[source * 4];
		byte g = this.colors[source * 4 + 1];
		byte bl = this.colors[source * 4 + 2];
		byte al = this.colors[source * 4 + 3];

		this.rank[a] = sourceRank;
		this.rank[b] = sourceRank;
		this.colors[a * 4] = r;
		this.colors[a * 4 + 1] = g;
		this.colors[a * 4 + 2] = bl;
		this.colors[a * 4 + 3] = al;
		this.colors[b * 4] = r;
		this.colors[b * 4 + 1] = g;
		this.colors[b * 4 + 2] = bl;
		this.colors[b * 4 + 3] = al;
	}

	public byte[] occupancy() {
		return this.occupancy;
	}

	public byte[] colors() {
		return this.colors;
	}

	public int nonEmptyVoxels() {
		int total = 0;
		for (byte value : this.occupancy) {
			total += Integer.bitCount(value & 0xFF);
		}
		return total;
	}
}
