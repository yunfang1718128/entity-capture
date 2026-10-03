package com.yunfang.entitycapture.capture;

/**
 * Holds the capturing buffer source for the duration of a single capture so that
 * renderer code which reaches for the global {@code MultiBufferSource} (instead
 * of the one passed down by {@code dispatcher.render}) is still intercepted.
 *
 * <p>Most entity layers use the buffer source they are handed, but some renderers
 * — notably GeckoLib's {@code GeoArmorRenderer} — ignore it and pull
 * {@code Minecraft.getInstance().levelRenderer.renderBuffers.bufferSource()}
 * directly. {@link com.yunfang.entitycapture.mixin.BufferSourceMixin} redirects
 * those calls here while a capture is in progress. Outside of a capture the
 * session is empty and behaviour is untouched.
 */
public final class CaptureSession {
	private static volatile CapturingMultiBufferSource active;

	private CaptureSession() {
	}

	public static void begin(CapturingMultiBufferSource buffers) {
		active = buffers;
	}

	public static void end() {
		active = null;
	}

	public static CapturingMultiBufferSource active() {
		return active;
	}
}
