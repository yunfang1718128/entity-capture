package com.yunfang.entitycapture;

import com.mojang.blaze3d.platform.InputConstants;
import com.yunfang.entitycapture.capture.CaptureManager;
import com.yunfang.entitycapture.command.CaptureCommand;
import com.yunfang.entitycapture.gui.EntityPickerScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

@Mod(value = EntityCaptureNeoForge.MOD_ID, dist = Dist.CLIENT)
public class EntityCaptureNeoForge {
	/** NeoForge mod ids must be lowercase letters, digits and underscores only. */
	public static final String MOD_ID = "entity_capture";

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

	public EntityCaptureNeoForge(IEventBus modBus, ModContainer container) {
		EntityCapture.setPlatform("1.21.1", "neoforge");

		modBus.addListener(this::registerKeyMappings);
		NeoForge.EVENT_BUS.addListener(this::registerClientCommands);
		NeoForge.EVENT_BUS.addListener(this::onClientTick);
	}

	private void registerKeyMappings(RegisterKeyMappingsEvent event) {
		event.register(CAPTURE_KEY);
		event.register(PICKER_KEY);
	}

	private void registerClientCommands(RegisterClientCommandsEvent event) {
		CaptureCommand.register(event.getDispatcher());
	}

	private void onClientTick(ClientTickEvent.Post event) {
		Minecraft client = Minecraft.getInstance();
		while (CAPTURE_KEY.consumeClick()) {
			CaptureManager.requestLookedAt();
		}
		while (PICKER_KEY.consumeClick()) {
			if (client.screen == null && client.level != null) {
				client.setScreen(new EntityPickerScreen());
			}
		}
		CaptureManager.processPending();
	}
}
