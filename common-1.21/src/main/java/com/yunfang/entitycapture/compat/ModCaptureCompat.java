package com.yunfang.entitycapture.compat;

import java.lang.reflect.Method;
import java.util.Set;

import com.yunfang.entitycapture.EntityCapture;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

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

	/**
	 * Mod namespaces whose identity can be transferred onto a freshly created
	 * entity. Tried before {@link #LIVE_NAMESPACES}: a fresh instance has no
	 * animation history, so it keeps the neutral pose the capture is meant to
	 * produce and repeat captures give the same file.
	 */
	private static final Set<String> IDENTITY_NAMESPACES = Set.of("cobblemon");

	private ModCaptureCompat() {
	}

	/** Whether the entity belongs to a mod that requires live capture. */
	public static boolean shouldRenderLive(Entity entity) {
		ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
		return key != null && LIVE_NAMESPACES.contains(key.getNamespace());
	}

	/**
	 * Whether entities of this type carry per-creature identity that a type-based
	 * capture cannot reproduce — i.e. {@code /capturemob <type>} and the batch GUI
	 * cannot tell you <em>which</em> one you got, so aiming is the only faithful
	 * entry point.
	 */
	public static boolean needsLiveEntityForIdentity(EntityType<?> type) {
		ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
		return key != null && IDENTITY_NAMESPACES.contains(key.getNamespace());
	}

	/** Whether this mod's entities can be re-created fresh and given the looked-at
	 * identity. Gated on the entity actually exposing the accessors, not just on its
	 * namespace: Cobblemon has 29 entity classes and only {@code PokemonEntity}
	 * carries a Pokémon, so a namespace check alone would send the other 28 down a
	 * path that can only fail.
	 */
	public static boolean wantsIdentityTransfer(Entity entity) {
		ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
		if (key == null || !IDENTITY_NAMESPACES.contains(key.getNamespace())) {
			return false;
		}
		return identityGetter(entity.getClass()) != null && identitySetter(entity.getClass(), null) != null;
	}

	private static Method identityGetter(Class<?> entityClass) {
		try {
			return entityClass.getMethod("getPokemon");
		} catch (NoSuchMethodException exception) {
			return null;
		}
	}

	/** @param identityClass which argument must be accepted, or {@code null} for "any single-argument setter" */
	private static Method identitySetter(Class<?> entityClass, Class<?> identityClass) {
		for (Method method : entityClass.getMethods()) {
			if (!method.getName().equals("setPokemon") || method.getParameterCount() != 1) {
				continue;
			}
			if (identityClass == null || method.getParameterTypes()[0].isAssignableFrom(identityClass)) {
				return method;
			}
		}
		return null;
	}

	/**
	 * Gives a freshly created entity the identity of the looked-at one, so the
	 * capture shows the right creature without going through a lossy NBT round-trip.
	 *
	 * <p>Cobblemon only: {@code getPokemon}/{@code setPokemon} are public, so no
	 * private access is needed. {@code Pokemon.clone} is called instead of passing
	 * the live object over because {@code Pokemon} holds a back-reference to the
	 * entity that owns it — handing it over would point the player's own Pokémon at
	 * the throwaway copy. {@code clone} round-trips through the full {@code CODEC}
	 * on both ends, which is precisely what the entity-level round-trip fails to do.
	 *
	 * <p>Everything is reflective so this mod keeps compiling, and keeps working,
	 * when Cobblemon is absent or has changed shape; failure means the caller falls
	 * back to rendering the live entity.
	 *
	 * @return whether {@code copy} now carries {@code source}'s identity
	 */
	public static boolean applyIdentity(Entity source, Entity copy, RegistryAccess registryAccess) {
		try {
			Method getter = identityGetter(source.getClass());
			if (getter == null) {
				return false;
			}
			Object identity = getter.invoke(source);
			if (identity == null) {
				return false;
			}
			Object detached = identity.getClass()
					.getMethod("clone", boolean.class, RegistryAccess.class)
					.invoke(identity, false, registryAccess);
			Method setter = identitySetter(copy.getClass(), detached.getClass());
			if (setter == null) {
				EntityCapture.LOGGER.warn("Cobblemon identity has no setter on {}", copy.getClass().getName());
				return false;
			}
			setter.invoke(copy, detached);
			return true;
		} catch (Throwable throwable) {
			EntityCapture.LOGGER.warn("Could not transfer identity from {}; falling back to live capture",
					source.getClass().getName(), throwable);
			return false;
		}
	}
}
