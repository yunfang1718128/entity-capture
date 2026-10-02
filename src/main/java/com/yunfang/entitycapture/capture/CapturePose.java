package com.yunfang.entitycapture.capture;

/**
 * Global flag marking that an entity is currently being captured. While set,
 * model mixins may neutralise poses that are awkward to voxelise (e.g. the
 * tilted, always-forward zombie arms produced by
 * {@code AnimationUtils.animateZombieArms}). It is only ever set while
 * {@link RenderCaptureService} runs on the render thread, so normal in-game
 * rendering is unaffected.
 */
public final class CapturePose {
	private static volatile boolean neutralPose;

	private CapturePose() {
	}

	public static void setNeutralPose(boolean value) {
		neutralPose = value;
	}

	public static boolean isNeutralPose() {
		return neutralPose;
	}
}
