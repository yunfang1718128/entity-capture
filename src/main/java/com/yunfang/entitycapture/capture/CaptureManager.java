package com.yunfang.entitycapture.capture;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;

import com.yunfang.entitycapture.EntityCaptureClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

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
				// Capture a fresh instance of the looked-at type so we never mutate
				// the live entity's pose. NBT/variant capture lands in a later milestone.
				spawnAndRun(lookedAt.getType());
			}
		}

		if (typeRequested != null) {
			EntityType<?> type = typeRequested;
			typeRequested = null;
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
			run(entity);
		}
	}

	/** Silent variant used by the batch: failures are counted, not chatted. */
	private static boolean spawnAndRunQuietly(EntityType<?> type) {
		Minecraft client = Minecraft.getInstance();
		Entity entity;
		try {
			entity = type.create(client.level);
		} catch (Throwable throwable) {
			EntityCaptureClient.LOGGER.warn("Batch capture could not create {}", id(type), throwable);
			return false;
		}
		if (entity == null) {
			EntityCaptureClient.LOGGER.warn("Batch capture could not create {}", id(type));
			return false;
		}
		try {
			RenderCaptureService.capture(entity);
			return true;
		} catch (Exception exception) {
			EntityCaptureClient.LOGGER.error("Batch capture failed for {}", id(type), exception);
			return false;
		}
	}

	private static void run(Entity entity) {
		try {
			RenderCaptureService.CaptureResult result = RenderCaptureService.capture(entity);
			filenameFeedback(result);
		} catch (Exception exception) {
			EntityCaptureClient.LOGGER.error("Entity capture failed", exception);
			feedback("捕获失败：" + exception.getMessage());
		}
	}

	private static String id(EntityType<?> type) {
		ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
		return key == null ? String.valueOf(type) : key.toString();
	}

	private static void filenameFeedback(RenderCaptureService.CaptureResult result) {
		String name = result.path().getFileName().toString();
		feedback(String.format("已捕获 %dx%dx%d，%d 个体素 → %s",
				result.sizeX(), result.sizeY(), result.sizeZ(), result.voxels(), name));
	}

	private static void feedback(String message) {
		Minecraft client = Minecraft.getInstance();
		if (client.player != null) {
			client.player.displayClientMessage(Component.literal(message), false);
		}
		EntityCaptureClient.LOGGER.info(message);
	}

	private static void actionbar(String message) {
		Minecraft client = Minecraft.getInstance();
		if (client.player != null) {
			client.player.displayClientMessage(Component.literal(message), true);
		}
	}
}
