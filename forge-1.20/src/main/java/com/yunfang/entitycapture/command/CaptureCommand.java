package com.yunfang.entitycapture.command;

import com.mojang.brigadier.CommandDispatcher;
import com.yunfang.entitycapture.capture.CaptureManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
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

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("capturemob")
				.executes(context -> {
					CaptureManager.requestLookedAt();
					context.getSource().sendSuccess(() -> Component.literal("正在捕获准星实体…"), false);
					return 1;
				})
				.then(Commands.argument("entity", ResourceLocationArgument.id())
						.suggests((context, builder) -> SharedSuggestionProvider.suggestResource(
								BuiltInRegistries.ENTITY_TYPE.keySet(), builder))
						.executes(context -> {
							ResourceLocation id = context.getArgument("entity", ResourceLocation.class);
							EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
							if (type == null) {
								context.getSource().sendFailure(Component.literal("未知实体类型：" + id));
								return 0;
							}
							CaptureManager.requestType(type);
							context.getSource().sendSuccess(() -> Component.literal("正在捕获 " + id + " …"), false);
							return 1;
						})));
	}
}
