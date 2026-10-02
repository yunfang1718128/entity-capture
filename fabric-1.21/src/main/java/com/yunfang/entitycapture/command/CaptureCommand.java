package com.yunfang.entitycapture.command;

import com.mojang.brigadier.CommandDispatcher;
import com.yunfang.entitycapture.capture.CaptureManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

/** Registers {@code /capturemob [entity]} on the client. */
public final class CaptureCommand {
	private CaptureCommand() {
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(ClientCommandManager.literal("capturemob")
				.executes(context -> {
					CaptureManager.requestLookedAt();
					context.getSource().sendFeedback(Component.literal("正在捕获准星实体…"));
					return 1;
				})
				.then(ClientCommandManager.argument("entity", ResourceLocationArgument.id())
						.suggests((context, builder) -> SharedSuggestionProvider.suggestResource(
								BuiltInRegistries.ENTITY_TYPE.keySet(), builder))
						.executes(context -> {
							ResourceLocation id = context.getArgument("entity", ResourceLocation.class);
							EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
							if (type == null) {
								context.getSource().sendError(Component.literal("未知实体类型：" + id));
								return 0;
							}
							CaptureManager.requestType(type);
							context.getSource().sendFeedback(Component.literal("正在捕获 " + id + " …"));
							return 1;
						})));
	}
}
