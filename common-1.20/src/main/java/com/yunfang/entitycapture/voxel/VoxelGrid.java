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
	private final short[] rank;

	public VoxelGrid(int sizeX, int sizeY, int sizeZ) {
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		int count = sizeX * sizeY * sizeZ;
		this.occupancy = new byte[(count + 7) / 8];
		this.colors = new byte[count * 4];
		this.rank = new short[count];
	}

	private int index(int x, int y, int z) {
		return x + z * this.sizeX + y * this.sizeX * this.sizeZ;
	}

	/** Packs the layer (outer wins) and face orientation into one comparable value. */
	private static int rank(int layer, int facePriority) {
		return ((layer & 0x3FF) << 6) | (facePriority & 0x3F);
	}

	public boolean inBounds(int x, int y, int z) {
		return x >= 0 && y >= 0 && z >= 0 && x < this.sizeX && y < this.sizeY && z < this.sizeZ;
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
			int existingRank = this.rank[index] & 0xFFFF;
			if (incomingRank < existingRank) {
				return;
			}
			if (incomingRank == existingRank && luminance(this.colors, offset) <= r + g + b) {
				return;
			}
		}

		this.rank[index] = (short) incomingRank;
		this.colors[offset] = (byte) r;
		this.colors[offset + 1] = (byte) g;
		this.colors[offset + 2] = (byte) b;
		this.colors[offset + 3] = (byte) a;
	}

	private static int luminance(byte[] colors, int offset) {
		return (colors[offset] & 0xFF) + (colors[offset + 1] & 0xFF) + (colors[offset + 2] & 0xFF);
	}

	/**
	 * Enforce left/right symmetry for a model that is mirror-symmetric about
	 * {@code x = 0}. Occupancy becomes the union so a voxel dropped on one side
	 * is restored; colour is only copied into a side that has none — when both
	 * sides carry a colour they are left alone, so a bright one-sided feature
	 * (a white eye, for instance) is never painted over by the other side. The
	 * caller checks that the model is actually symmetric first.
	 */
	public void enforceXMirrorSymmetry() {
		int half = this.sizeX / 2;
		for (int x = 0; x < half; x++) {
			int mirror = this.sizeX - 1 - x;
			for (int y = 0; y < this.sizeY; y++) {
				for (int z = 0; z < this.sizeZ; z++) {
					this.mergeMirrorPair(this.index(mirror, y, z), this.index(x, y, z));
				}
			}
		}
	}

	private void mergeMirrorPair(int a, int b) {
		boolean occupiedA = (this.occupancy[a >> 3] & (1 << (a & 7))) != 0;
		boolean occupiedB = (this.occupancy[b >> 3] & (1 << (b & 7))) != 0;
		if (!occupiedA && !occupiedB) {
			return;
		}

		// Union the occupancy so the silhouette stays symmetric.
		this.occupancy[a >> 3] |= (byte) (1 << (a & 7));
		this.occupancy[b >> 3] |= (byte) (1 << (b & 7));

		boolean coloredA = (this.colors[a * 4 + 3] & 0xFF) > 0;
		boolean coloredB = (this.colors[b * 4 + 3] & 0xFF) > 0;

		if (coloredA && coloredB) {
			int rankA = this.rank[a] & 0xFFFF;
			int rankB = this.rank[b] & 0xFFFF;
			if (rankA == rankB) {
				// Same window on both sides: keep each side's own colour so a
				// feature present on one side is not erased by the other.
				return;
			}
			this.copyColor(rankA > rankB ? a : b, a, b);
		} else if (coloredA) {
			this.copyColor(a, a, b);
		} else if (coloredB) {
			this.copyColor(b, a, b);
		}
	}

	private void copyColor(int source, int a, int b) {
		short sourceRank = this.rank[source];
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
