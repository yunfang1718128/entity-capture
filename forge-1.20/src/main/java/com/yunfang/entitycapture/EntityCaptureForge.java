package com.yunfang.entitycapture;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Forge 1.20.1's {@code FMLModContainer.constructMod} only recognises a
 * constructor taking {@link FMLJavaModLoadingContext} or a no-arg constructor
 * (it does not support {@code IEventBus} injection, unlike NeoForge/newer
 * Forge). The context branch is the non-deprecated way to reach the mod bus.
 */
@Mod(EntityCaptureForge.MOD_ID)
public class EntityCaptureForge {
	/** Forge mod ids must be lowercase letters, digits and underscores only. */
	public static final String MOD_ID = "entity_capture";

	public EntityCaptureForge(FMLJavaModLoadingContext context) {
		// Client-only mod: never touch client classes on a dedicated server.
		DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
				() -> () -> EntityCaptureForgeClient.init(context.getModEventBus()));
	}
}
