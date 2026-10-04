package com.yunfang.entitycapture.compat;

import java.util.Set;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

/**
 * Per-mod capture compatibility rules.
 *
 * <p>Some mods do not survive a client-side NBT round-trip: their entity's
 * {@code saveWithoutId} and its client-side {@code load} use different codecs, so
 * a captured copy silently becomes a different variant. Cobblemon is the
 * canonical example — {@code PokemonEntity.saveWithoutId} writes the creature
 * with the full {@code Pokemon.CODEC}, while client-side {@code load} decodes
 * with the reduced {@code Pokemon.CLIENT_CODEC}; the codecs disagree on required
 * fields, decoding throws, and the fallback is {@code Pokemon()} which picks a
 * <em>random species</em>. The result is that capturing a Pokémon yields some
 * unrelated Pokémon.
 *
 * <p>For namespaces listed in {@link #LIVE_NAMESPACES} the capture service
 * renders the looked-at <em>live</em> entity (relocating/neutralising it for the
 * duration and restoring it afterwards) instead of a clone. Add a namespace here
 * to extend the same treatment to another mod.
 *
 * <p>Touhou Little Maid (车万女仆) is the same class of problem with a different
 * mechanism: its Gecko model only renders when the client-only NeoForge
 * {@code AttachmentType} {@code GeckoMaidEntity.TYPE} is present on the entity.
 * Attachments are not serialised into NBT, so a freshly created clone renders
 * nothing (a zero-voxel "stick"); the live maid does have the attachment.
 */
public final class ModCaptureCompat {
	/** Mod namespaces whose entities must be captured live, never via NBT clone. */
	private static final Set<String> LIVE_NAMESPACES = Set.of("cobblemon", "touhou_little_maid");

	private ModCaptureCompat() {
	}

	/** Whether the entity belongs to a mod that requires live capture. */
	public static boolean shouldRenderLive(Entity entity) {
		ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
		return key != null && LIVE_NAMESPACES.contains(key.getNamespace());
	}
}
