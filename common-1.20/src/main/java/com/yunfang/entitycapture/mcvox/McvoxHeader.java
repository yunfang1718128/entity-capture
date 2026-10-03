package com.yunfang.entitycapture.mcvox;

/** Header JSON of a `.mcvox` file. Field names must match the studio's reader. */
public final class McvoxHeader {
	public int formatVersion;
	public String entityId;
	public String entityName;
	public String mcVersion;
	public String modLoader;
	public int unitsPerBlock;
	public int[] dimensions;
	public boolean solid;
	public int animatedTick;
	public String generatedAt;

	public McvoxHeader(
			int formatVersion,
			String entityId,
			String entityName,
			String mcVersion,
			String modLoader,
			int unitsPerBlock,
			int[] dimensions,
			boolean solid,
			int animatedTick,
			String generatedAt) {
		this.formatVersion = formatVersion;
		this.entityId = entityId;
		this.entityName = entityName;
		this.mcVersion = mcVersion;
		this.modLoader = modLoader;
		this.unitsPerBlock = unitsPerBlock;
		this.dimensions = dimensions;
		this.solid = solid;
		this.animatedTick = animatedTick;
		this.generatedAt = generatedAt;
	}
}
