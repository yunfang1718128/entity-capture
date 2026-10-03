package com.yunfang.entitycapture.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yunfang.entitycapture.capture.CaptureSession;
import com.yunfang.entitycapture.capture.CapturingMultiBufferSource;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Routes quads drawn through the global world buffer source into the active
 * capture instead of the real screen. During
 * {@code RenderCaptureService.capture} the entity is rendered with a
 * {@link CapturingMultiBufferSource} passed as the buffer source, but some
 * renderers — GeckoLib's {@code GeoArmorRenderer} among them — ignore it and
 * fetch {@code Minecraft.getInstance().levelRenderer.renderBuffers.bufferSource()}
 * directly, so their armour/quads never reach the voxeliser. When a capture is
 * active, this mixin hands those calls the capturing consumer instead.
 */
@Mixin(MultiBufferSource.BufferSource.class)
public class BufferSourceMixin {
	@Inject(method = "getBuffer", at = @At("HEAD"), cancellable = true)
	private void entitycapture$capture(RenderType renderType, CallbackInfoReturnable<VertexConsumer> callback) {
		CapturingMultiBufferSource active = CaptureSession.active();
		if (active != null) {
			callback.setReturnValue(active.getBuffer(renderType));
		}
	}
}
