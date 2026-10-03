package com.yunfang.entitycapture;

import com.mojang.blaze3d.platform.InputConstants;
import com.yunfang.entitycapture.capture.CaptureManager;
import com.yunfang.entitycapture.command.CaptureCommand;
import com.yunfang.entitycapture.config.EntityCaptureConfig;
import com.yunfang.entitycapture.gui.EntityPickerScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.glfw.GLFW;

public final class EntityCaptureForgeClient {
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

	private EntityCaptureForgeClient() {
	}

	public static void init(IEventBus modBus) {
		EntityCapture.setPlatform("1.20.1", "forge");
		EntityCaptureConfig.load(FMLPaths.CONFIGDIR.get());

		modBus.addListener(EntityCaptureForgeClient::registerKeyMappings);
		MinecraftForge.EVENT_BUS.addListener(EntityCaptureForgeClient::registerClientCommands);
		MinecraftForge.EVENT_BUS.addListener(EntityCaptureForgeClient::onClientTick);
	}

	private static void registerKeyMappings(RegisterKeyMappingsEvent event) {
		event.register(CAPTURE_KEY);
		event.register(PICKER_KEY);
	}

	private static void registerClientCommands(RegisterClientCommandsEvent event) {
		CaptureCommand.register(event.getDispatcher());
	}

	private static void onClientTick(TickEvent.ClientTickEvent event) {
		if (event.phase != TickEvent.Phase.END) {
			return;
		}
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
