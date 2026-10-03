package com.yunfang.entitycapture.capture;

import java.util.List;

import net.minecraft.nbt.CompoundTag;

/**
 * Strips transient <em>state</em> (pose, motion, overlays, effects) from a
 * freshly cloned entity's NBT while leaving genuine <em>variant</em> data
 * (colour, breed, pattern, age, …) intact.
 *
 * <p>Without this, cloning the looked-at entity would treat a sitting cat, a
 * sleeping fox, a sheared sheep or a burning mob as its own "variant": the NBT
 * round-trip restores those flags and the captured model differs from the same
 * mob in a neutral state. Pose is reset to standing; {@code Age} is deliberately
 * kept so babies remain valid captures.
 */
public final class VariantSanitizer {
	/**
	 * Position, motion, overlays, effects and posture. Removing these leaves the
	 * entity in its default neutral state; {@code SleepingX/Y/Z} must go too, as
	 * {@code LivingEntity.getPose()} derives {@code SLEEPING} from it and the mob
	 * would otherwise be rendered lying down.
	 */
	private static final List<String> TRANSIENT = List.of(
			"Pos", "Motion", "Rotation", "FallDistance", "Air", "OnGround", "PortalCooldown",
			"Fire", "HasVisualFire", "TicksFrozen", "Glowing", "Silent", "NoGravity",
			"CustomName", "CustomNameVisible", "Passengers",
			"Health", "HurtTime", "DeathTime", "HurtByTimestamp", "FallFlying", "active_effects",
			"SleepingX", "SleepingY", "SleepingZ", "Brain", "memories", "Team", "freezing", "powder_snow",
			"Sitting", "Crouching", "Sleeping", "Sheared", "EatingHaystack", "Tame", "Temper", "Bred",
			"InLove", "ForcedAge", "CannotEnterLoveMode");

	/** Equipment and decorations; only removed when equipment capture is off. */
	private static final List<String> EQUIPMENT = List.of(
			"ArmorItems", "HandItems", "SaddleItem", "Saddle", "DecorItem", "CollarColor",
			"ArmorDropChances", "HandDropChances", "body_armor_item", "carriedBlockState");

	private VariantSanitizer() {
	}

	public static void sanitize(CompoundTag tag, boolean keepEquipment) {
		for (String key : TRANSIENT) {
			tag.remove(key);
		}
		if (!keepEquipment) {
			for (String key : EQUIPMENT) {
				tag.remove(key);
			}
		}
	}
}
