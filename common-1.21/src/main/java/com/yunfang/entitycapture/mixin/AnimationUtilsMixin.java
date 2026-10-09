package com.yunfang.entitycapture.mixin;

import com.yunfang.entitycapture.capture.CapturePose;
import net.minecraft.client.model.AnimationUtils;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * During capture, replace the zombie-family arm animation with a straight,
 * axis-aligned horizontal forward pose so the arms voxelise cleanly.
 *
 * <p>Every zombie-family model funnels through
 * {@link AnimationUtils#animateZombieArms} — {@code AbstractZombieModel}
 * (zombie/husk/drowned/giant), {@code ZombieVillagerModel} and {@code PiglinModel}
 * (zombified piglin only) — so a single injection covers all of them. The
 * vanilla tilt ({@code xRot ≈ -π/1.5}) plus the {@code bobArms} sway are skipped
 * entirely by cancelling at HEAD.
 *
 * <p>The idle arm bob is a second, subtler source of arm tilt: every humanoid
 * goes through {@link AnimationUtils#bobModelPart}, which adds a {@code zRot} of
 * up to 0.1 rad (≈5.7°) that swings both arms outward and never reaches an
 * axis-aligned rest pose. A capture renders with {@code tickCount} 1, i.e.
 * almost exactly the maximum tilt, which pushes the hands — and the inflated
 * {@code CubeDeformation} armour sleeves, whose bottom-outer corner lands at
 * {@code 5 + 4·cos θ + 11·sin θ ≈ 10.1} model pixels instead of 9 — one or two
 * voxels outside the model's real silhouette, so a captured humanoid looks like
 * its arms flare away from the body. Cancelling the bob keeps the arms aligned
 * at rest; its only callers are {@code HumanoidModel.setupAnim} and
 * {@code AnimationUtils.bobArms} ({@code animateZombieArms} /
 * {@code swingWeaponDown}), so nothing but arms is touched.
 */
@Mixin(AnimationUtils.class)
public class AnimationUtilsMixin {
	@Inject(method = "animateZombieArms(Lnet/minecraft/client/model/geom/ModelPart;"
			+ "Lnet/minecraft/client/model/geom/ModelPart;ZFF)V", at = @At("HEAD"), cancellable = true)
	private static void entitycapture$neutralArms(ModelPart leftArm, ModelPart rightArm,
			boolean aggressive, float attackTime, float ageInTicks, CallbackInfo callback) {
		if (!CapturePose.isNeutralPose()) {
			return;
		}
		float forward = -(float) (Math.PI / 2.0);
		leftArm.setRotation(forward, 0.0F, 0.0F);
		rightArm.setRotation(forward, 0.0F, 0.0F);
		callback.cancel();
	}

	/**
	 * Drops the vanilla idle arm bob while capturing. It is a {@code zRot} term
	 * (plus a small {@code xRot}) that tilts both arms outward at the hand and
	 * never settles on an axis-aligned rest pose, which the voxel grid turns into
	 * an arm silhouette one or two voxels too wide. Neutral — not merely smaller —
	 * is what a capture needs.
	 */
	@Inject(method = "bobModelPart(Lnet/minecraft/client/model/geom/ModelPart;FF)V",
			at = @At("HEAD"), cancellable = true)
	private static void entitycapture$neutralArmBob(ModelPart arm, float ageInTicks, float multiplier,
			CallbackInfo callback) {
		if (CapturePose.isNeutralPose()) {
			callback.cancel();
		}
	}
}
