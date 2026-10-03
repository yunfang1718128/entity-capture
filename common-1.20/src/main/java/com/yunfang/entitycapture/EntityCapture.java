package com.yunfang.entitycapture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loader-agnostic identity and platform metadata shared by every target.
 *
 * <p>The common core must not depend on any loader entry class, so the mod id,
 * logger and the {@code mcVersion}/{@code modLoader} written into the
 * {@code .mcvox} header all live here. Each loader's entry point calls
 * {@link #setPlatform(String, String)} during initialisation.
 */
public final class EntityCapture {
	public static final String MOD_ID = "entity-capture";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static volatile String mcVersion = "1.20.1";
	private static volatile String loader = "unknown";

	private EntityCapture() {
	}

	public static void setPlatform(String minecraftVersion, String modLoader) {
		mcVersion = minecraftVersion;
		loader = modLoader;
	}

	public static String mcVersion() {
		return mcVersion;
	}

	public static String loader() {
		return loader;
	}
}
