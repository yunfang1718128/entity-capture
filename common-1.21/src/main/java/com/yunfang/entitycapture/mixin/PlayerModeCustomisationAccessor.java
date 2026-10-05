package com.yunfang.entitycapture.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@link Player}'s model-part customisation byte so a capture clone can
 * be given the same skin-layer visibility (hat, jacket, sleeves, pants) as the
 * source player. Copying only this value — instead of the whole synced state —
 * keeps the clone's pose flags at their defaults, which is the neutral stance the
 * self-capture wants.
 */
@Mixin(Player.class)
public interface PlayerModeCustomisationAccessor {
	@Accessor("DATA_PLAYER_MODE_CUSTOMISATION")
	static EntityDataAccessor<Byte> getModeCustomisation() {
		throw new AssertionError();
	}
}
