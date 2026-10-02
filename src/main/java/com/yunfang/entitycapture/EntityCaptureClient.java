package com.yunfang.entitycapture;

import com.mojang.blaze3d.platform.InputConstants;
import com.yunfang.entitycapture.capture.CaptureManager;
import com.yunfang.entitycapture.command.CaptureCommand;
import com.yunfang.entitycapture.gui.EntityPickerScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EntityCaptureClient implements ClientModInitializer {
	public static final String MOD_ID = "entity-capture";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

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

	@Override
	public void onInitializeClient() {
		KeyBindingHelper.registerKeyBinding(CAPTURE_KEY);
		KeyBindingHelper.registerKeyBinding(PICKER_KEY);

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
			CaptureManager.processPending();
		});
	}
}
