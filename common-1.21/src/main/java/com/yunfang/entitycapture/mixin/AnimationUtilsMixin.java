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
}
