package com.yunfang.entitycapture;

import com.mojang.blaze3d.platform.InputConstants;
import com.yunfang.entitycapture.capture.CaptureManager;
import com.yunfang.entitycapture.command.CaptureCommand;
import com.yunfang.entitycapture.config.EntityCaptureConfig;
import com.yunfang.entitycapture.gui.EntityPickerScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

public class EntityCaptureClient implements ClientModInitializer {
	private static final KeyMapping CAPTURE_KEY = new KeyMapping(
			"key.entity-capture.capture",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_G,
			"category.entity-capture");

	private static final KeyMapping PICKER_KEY = new KeyMapping(
			"key.entity-capture.picker",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_H,
			"category.entity-capture");

	private static final KeyMapping SELF_KEY = new KeyMapping(
			"key.entity-capture.capture_self",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_J,
			"category.entity-capture");

	@Override
	public void onInitializeClient() {
		EntityCapture.setPlatform("1.21.1", "fabric");
		EntityCaptureConfig.load(FabricLoader.getInstance().getConfigDir());

		KeyBindingHelper.registerKeyBinding(CAPTURE_KEY);
		KeyBindingHelper.registerKeyBinding(PICKER_KEY);
		KeyBindingHelper.registerKeyBinding(SELF_KEY);

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> CaptureCommand.register(dispatcher));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (CAPTURE_KEY.consumeClick()) {
				CaptureManager.requestLookedAt();
			}
			while (PICKER_KEY.consumeClick()) {
				if (client.screen == null && client.level != null) {
					client.setScreen(new EntityPickerScreen());
				}
			}
			while (SELF_KEY.consumeClick()) {
				CaptureManager.requestSelf();
			}
			CaptureManager.processPending();
		});
	}
}
