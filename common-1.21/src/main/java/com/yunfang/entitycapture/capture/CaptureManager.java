package com.yunfang.entitycapture.capture;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;

import com.yunfang.entitycapture.EntityCapture;
import com.yunfang.entitycapture.compat.ModCaptureCompat;
import com.yunfang.entitycapture.config.EntityCaptureConfig;
import com.yunfang.entitycapture.mixin.PlayerModeCustomisationAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Bridges the (command / keybind / picker GUI) entry points to the render-thread
 * capture service. Requests are queued and drained on the client tick, which is
 * the render thread, so rendering an entity into the capturing buffer is safe.
 *
 * <p>Single requests (look-at / one type) run first; a user-picked batch is then
 * drained one entity per tick so a long selection does not hitch a frame.
 */
public final class CaptureManager {
	private static volatile boolean lookRequested;
	private static volatile boolean selfRequested;
	private static volatile EntityType<?> typeRequested;

	private static final Deque<EntityType<?>> batch = new ArrayDeque<>();
	private static int batchTotal;
	private static int batchDone;
	private static int batchFailed;

	private CaptureManager() {
	}

	public static void requestLookedAt() {
		lookRequested = true;
	}

	public static void requestSelf() {
		selfRequested = true;
	}

	public static void requestType(EntityType<?> type) {
		typeRequested = type;
	}

	/** Queues a user-selected set of entities, captured one per tick. */
	public static void requestBatch(Collection<EntityType<?>> types) {
		batch.clear();
		batch.addAll(types);
		batchTotal = batch.size();
		batchDone = 0;
		batchFailed = 0;
	}

	public static void processPending() {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) {
			lookRequested = false;
			selfRequested = false;
			typeRequested = null;
			if (batchTotal > 0) {
				feedback("批量捕获已中止：世界未加载");
				resetBatch();
			}
			return;
		}

		if (lookRequested) {
			lookRequested = false;
			Entity lookedAt = client.crosshairPickEntity;
			if (lookedAt == null) {
				feedback("没有可用准星实体，请瞄准一个生物");
			} else {
				// Capture a copy that carries the looked-at entity's NBT so variants
				// (dragon colour, horse markings, cat colour, …) are preserved. The
				// live entity itself is never mutated.
				spawnCopy(lookedAt);
			}
		}

		if (selfRequested) {
			selfRequested = false;
			Player self = client.player;
			if (self == null) {
				feedback("没有可用玩家，请先进入世界");
			} else {
				captureSelf(self);
			}
		}

		if (typeRequested != null) {
			EntityType<?> type = typeRequested;
			typeRequested = null;
			// A type-based capture has no creature to take identity from, so for mods
			// like Cobblemon the file says "pokemon" but is not the one the player meant.
			if (ModCaptureCompat.needsLiveEntityForIdentity(type)) {
				feedback("按类型捕获拿不到具体是哪一只，请瞄着它按 G");
			}
			spawnAndRun(type);
		}

		if (!batch.isEmpty()) {
			processNextBatch(client);
		}
	}

	private static void processNextBatch(Minecraft client) {
		EntityType<?> type = batch.poll();
		boolean ok = spawnAndRunQuietly(type);
		batchDone++;
		if (!ok) {
			batchFailed++;
		}

		if (batch.isEmpty()) {
			feedback(String.format("批量捕获完成：成功 %d / 失败 %d", batchDone - batchFailed, batchFailed));
			resetBatch();
		} else {
			actionbar(String.format("批量捕获 %d/%d · %s", batchDone, batchTotal, id(type)));
		}
	}

	private static void resetBatch() {
		batch.clear();
		batchTotal = 0;
		batchDone = 0;
		batchFailed = 0;
	}

	private static void spawnAndRun(EntityType<?> type) {
		Minecraft client = Minecraft.getInstance();
		Entity entity = type.create(client.level);
		if (entity == null) {
			feedback("无法创建实体：" + type.getDescriptionId());
		} else {
			run(entity, false);
		}
	}

	/**
	 * Captures a copy of the looked-at entity carrying its full NBT, so variant
	 * data (colour, markings, growth stage, …) survives instead of resetting to
	 * the type's default. The live entity is never touched: a fresh instance is
	 * rebuilt from saved NBT. Transient pose/state is stripped (see
	 * {@link VariantSanitizer}) and equipment is stripped unless enabled in the
	 * config. Falls back to the default variant when the NBT cannot be
	 * round-tripped (some mod entities) so a capture never hard-fails.
	 */
	private static void spawnCopy(Entity source) {
		Minecraft client = Minecraft.getInstance();
		EntityCaptureConfig config = EntityCaptureConfig.get();
		if (!config.variantCapture) {
			spawnAndRun(source.getType());
			return;
		}

		// A mod whose entity loses its identity in an NBT round-trip is rebuilt fresh
		// and given that identity directly: a new instance has no animation history,
		// so the capture stays pose-neutral and repeat captures of the same creature
		// produce the same file. poseCapture=yes opts back into rendering the live
		// entity, which follows the creature's current pose and varies per capture.
		if (!config.poseCapture && ModCaptureCompat.wantsIdentityTransfer(source)) {
			Entity fresh = source.getType().create(client.level);
			if (fresh != null && ModCaptureCompat.applyIdentity(source, fresh, client.level.registryAccess())) {
				neutralizeClone(fresh, config.keepEquipment);
				if (run(fresh, true) > 0) {
					return;
				}
			}
			feedback("未能复制该生物的身份数据，改按现场实体捕获（同一只每次产物可能不同）");
			runLive(source);
			return;
		}

		// Some mods cannot survive a client-side NBT round-trip (their save/load
		// codecs disagree and the fallback is a random variant — see
		// ModCaptureCompat). Render those live instead of cloning them.
		if (ModCaptureCompat.shouldRenderLive(source)) {
			runLive(source);
			return;
		}

		Entity copy = null;
		try {
			CompoundTag tag = source.saveWithoutId(new CompoundTag());
			ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(source.getType());
			if (key != null) {
				// EntityType.create(tag, level) resolves the type from "id", which
				// saveWithoutId deliberately omits, so restore it before round-tripping.
				tag.putString("id", key.toString());
				VariantSanitizer.sanitize(tag, config.keepEquipment);
				copy = EntityType.create(tag, client.level).orElse(null);
			}
		} catch (Throwable throwable) {
			EntityCapture.LOGGER.warn("Variant capture could not copy {}; using default", id(source.getType()), throwable);
		}
		if (copy == null) {
			spawnAndRun(source.getType());
		} else {
			neutralizeClone(copy, config.keepEquipment);
			run(copy, true);
		}
	}

	/**
	 * Second line of defence for state a mod may not serialise: resets the known
	 * vanilla posture/overlay flags on the clone. Babies ({@code Age}) are left
	 * untouched on purpose.
	 */
	private static void neutralizeClone(Entity copy, boolean keepEquipment) {
		try {
			copy.setPose(Pose.STANDING);
			copy.clearFire();
			copy.setInvisible(false);
			copy.setGlowingTag(false);
			copy.setSilent(false);
			copy.ejectPassengers();

			if (copy instanceof LivingEntity living) {
				living.stopSleeping();
				living.removeAllEffects();
				living.setHealth(living.getMaxHealth());
			}
			if (copy instanceof TamableAnimal tamable) {
				tamable.setInSittingPose(false);
				tamable.setOrderedToSit(false);
			}
			if (copy instanceof Sheep sheep) {
				sheep.setSheared(false);
			}
			if (copy instanceof AbstractHorse horse) {
				horse.setEating(false);
			}
			if (!keepEquipment && copy instanceof Mob mob) {
				for (EquipmentSlot slot : EquipmentSlot.values()) {
					mob.setItemSlot(slot, ItemStack.EMPTY);
				}
			}
		} catch (Throwable throwable) {
			EntityCapture.LOGGER.warn("Could not fully neutralise clone; capture continues", throwable);
		}
	}

	/** Silent variant used by the batch: failures are counted, not chatted. */
	private static boolean spawnAndRunQuietly(EntityType<?> type) {
		Minecraft client = Minecraft.getInstance();
		Entity entity;
		try {
			entity = type.create(client.level);
		} catch (Throwable throwable) {
			EntityCapture.LOGGER.warn("Batch capture could not create {}", id(type), throwable);
			return false;
		}
		if (entity == null) {
			EntityCapture.LOGGER.warn("Batch capture could not create {}", id(type));
			return false;
		}
		try {
			RenderCaptureService.capture(entity);
			return true;
		} catch (Exception exception) {
			EntityCapture.LOGGER.error("Batch capture failed for {}", id(type), exception);
			return false;
		}
	}

	/** Captures {@code entity} and reports how many voxels came out (0 or less: nothing usable). */
	private static int run(Entity entity, boolean variant) {
		try {
			RenderCaptureService.CaptureResult result = RenderCaptureService.capture(entity);
			filenameFeedback(result, variant);
			return result.voxels();
		} catch (Exception exception) {
			EntityCapture.LOGGER.error("Entity capture failed", exception);
			feedback("捕获失败：" + exception.getMessage());
			return -1;
		}
	}

	/** Captures a live entity in place; used for ModCaptureCompat namespaces. */
	private static void runLive(Entity entity) {
		try {
			RenderCaptureService.CaptureResult result = RenderCaptureService.captureLive(entity);
			filenameFeedback(result, true);
		} catch (Exception exception) {
			EntityCapture.LOGGER.error("Live entity capture failed", exception);
			feedback("捕获失败：" + exception.getMessage());
		}
	}

	/**
	 * Captures the player's own entity. By default the player is rendered in a
	 * neutral standing pose: their skin, worn armour, held items and cape are kept
	 * (the clone reuses the player's profile so {@code PlayerInfo} supplies the real
	 * skin), but the current stance — sneaking, swimming, elytra, riding, mid-stride
	 * — is not. Set {@code poseCapture=yes} to render the live player and keep the
	 * current pose instead. The live player itself is never mutated in the default
	 * path.
	 */
	private static void captureSelf(Player player) {
		if (EntityCaptureConfig.get().poseCapture) {
			captureSelfLive(player);
			return;
		}
		try {
			captureSelfNeutral(player);
		} catch (Throwable throwable) {
			EntityCapture.LOGGER.warn("Neutral self capture failed; falling back to the live pose", throwable);
			captureSelfLive(player);
		}
	}

	/**
	 * Renders a fresh {@link RemotePlayer} carrying the player's identity: synced
	 * data (skin layers / handedness), equipment and profile are copied, while the
	 * pose flags are reset to a standing rest. A new entity has no animation
	 * history, so limbs are at rest — the same neutral result a cloned mob gets.
	 */
	private static void captureSelfNeutral(Player player) throws IOException {
		Minecraft client = Minecraft.getInstance();
		if (!(client.level instanceof ClientLevel level)) {
			captureSelfLive(player);
			return;
		}

		RemotePlayer clone = new RemotePlayer(level, player.getGameProfile());
		// Match the source's skin-layer visibility and handedness. Everything else
		// (pose, flags, animation) is left at the fresh entity's defaults, i.e. a
		// neutral standing rest — unlike the live player, which may be sneaking,
		// swimming, elytra-flying, riding or mid-stride.
		EntityDataAccessor<Byte> customisation = PlayerModeCustomisationAccessor.getModeCustomisation();
		clone.getEntityData().set(customisation, player.getEntityData().get(customisation));
		clone.setMainArm(player.getMainArm());
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			clone.setItemSlot(slot, player.getItemBySlot(slot).copy());
		}

		selfFeedback(RenderCaptureService.capture(clone));
	}

	/**
	 * Renders the live player as-is, preserving the current pose. The player is
	 * briefly forced visible so an invisibility effect does not yield an empty
	 * capture, and is restored afterwards.
	 */
	private static void captureSelfLive(Player player) {
		boolean invisible = player.isInvisible();
		player.setInvisible(false);
		try {
			selfFeedback(RenderCaptureService.captureLive(player));
		} catch (Exception exception) {
			EntityCapture.LOGGER.error("Self capture failed", exception);
			feedback("捕获失败：" + exception.getMessage());
		} finally {
			player.setInvisible(invisible);
		}
	}

	private static void selfFeedback(RenderCaptureService.CaptureResult result) {
		String name = result.path().getFileName().toString();
		feedback(String.format("已捕获玩家自己 %dx%dx%d，%d 个体素 → %s",
				result.sizeX(), result.sizeY(), result.sizeZ(), result.voxels(), name));
	}

	private static String id(EntityType<?> type) {
		ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
		return key == null ? String.valueOf(type) : key.toString();
	}

	private static void filenameFeedback(RenderCaptureService.CaptureResult result, boolean variant) {
		String name = result.path().getFileName().toString();
		String prefix = variant ? "已捕获变体" : "已捕获";
		feedback(String.format("%s %dx%dx%d，%d 个体素 → %s",
				prefix, result.sizeX(), result.sizeY(), result.sizeZ(), result.voxels(), name));
	}

	private static void feedback(String message) {
		Minecraft client = Minecraft.getInstance();
		if (client.player != null) {
			client.player.displayClientMessage(Component.literal(message), false);
		}
		EntityCapture.LOGGER.info(message);
	}

	private static void actionbar(String message) {
		Minecraft client = Minecraft.getInstance();
		if (client.player != null) {
			client.player.displayClientMessage(Component.literal(message), true);
		}
	}
}
