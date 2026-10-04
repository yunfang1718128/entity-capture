package com.yunfang.entitycapture.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import com.yunfang.entitycapture.EntityCapture;

/**
 * Loader-agnostic client config, written as a commented TOML file so both
 * loaders share one format (the same file lives in {@code config/}). Comments
 * are bilingual.
 *
 * <p>The file is written in full only when it does not exist yet. Afterwards
 * this class <em>appends</em>: options a new version adds are written in at the
 * end, with their explanation, while every existing line — the player's values,
 * their comments, their ordering — is left exactly as it is. A player therefore
 * never has to discover a new option by reading the source or the changelog, and
 * an update can never undo their settings.
 */
public final class EntityCaptureConfig {
	private static final String FILE_NAME = "entity-capture.toml";
	private static volatile EntityCaptureConfig instance = new EntityCaptureConfig();

	/** Clone the looked-at entity's NBT so variants (colour, breed, …) survive. */
	public boolean variantCapture = true;
	/** Keep equipment/decorations (saddle, armour, collar, held items, …). */
	public boolean keepEquipment = false;
	/**
	 * Render a mod mob in the pose it happens to be in, instead of a neutral one.
	 * Off (default) captures are reproducible: the same creature always yields the
	 * same file. Only affects mods that can be re-created with their identity
	 * transferred (currently Cobblemon); everything else keeps its own rules.
	 */
	public boolean poseCapture = false;

	/** An option and the comment block that explains it in the file. */
	private record Option(String key, String[] comment, Function<EntityCaptureConfig, Boolean> value) {
		private String line(EntityCaptureConfig config) {
			return key + " = " + value.apply(config);
		}
	}

	private static final String[] HEADER = {
			"# ==========================================================================",
			"# Entity Capture 配置 / Entity Capture configuration",
			"# 修改后需重启游戏生效 / Restart the game for changes to take effect",
			"# ==========================================================================",
			"",
			"[capture]"};

	private static final List<Option> OPTIONS = List.of(
			new Option("variantCapture", new String[] {
					"# 变体捕获（默认开启）/ Variant capture (default: on)",
					"# 克隆准星实体的 NBT，保留颜色、品种、花纹等变体。",
					"# Clones the looked-at entity's NBT to keep colour, breed and pattern variants.",
					"# 警告：部分模组用特殊姿态（坐、睡、自定义动画）实现外观，捕获时会重置为站姿，",
					"# 可能与这些模组不兼容；如捕获异常可关闭本项。",
					"# Warning: some mods implement looks via special poses (sitting, sleeping, custom",
					"# animations); these are reset to a neutral stance and may be incompatible; disable",
					"# this option if a capture misbehaves.", }, c -> c.variantCapture),
			new Option("keepEquipment", new String[] {
					"# 装备捕获（默认关闭）/ Equipment capture (default: off)",
					"# 是否保留鞍、马铠、项圈、手持与护甲等装备及装饰。",
					"# Keeps saddle, horse armour, collar, held and armour items, and other decorations.", },
					c -> c.keepEquipment),
			new Option("poseCapture", new String[] {
					"# 姿态捕获（默认关闭）/ Capture pose (default: off)",
					"# 关闭：模组生物按中性姿态捕获，同一只每次产物完全一致（可复现）。",
					"# 开启：按生物当下姿势捕获（走活体方式），产物会随它的动作变化。",
					"# Off: mod mobs are captured in a neutral pose, so the same creature always yields the same file.",
					"# On: the creature is rendered exactly as it currently poses, so results vary with its animation.",
					"# 目前只影响方块宝可梦（Cobblemon）/ Currently only affects Cobblemon.", }, c -> c.poseCapture));

	private EntityCaptureConfig() {
	}

	public static EntityCaptureConfig get() {
		return instance;
	}

	/**
	 * Reads {@code <configDir>/entity-capture.toml}, creating it if absent and
	 * appending any option this version knows that the file is missing.
	 */
	public static void load(Path configDir) {
		EntityCaptureConfig config = new EntityCaptureConfig();
		Path file = configDir.resolve(FILE_NAME);
		try {
			if (Files.exists(file) && Files.size(file) > 0) {
				config.appendMissing(file, config.read(file));
			} else {
				config.write(file);
			}
		} catch (Throwable throwable) {
			EntityCapture.LOGGER.warn("Could not load config {}; using defaults", file, throwable);
		}
		instance = config;
	}

	/** @return the keys the file already declares, so they can be left alone */
	private Set<String> read(Path file) throws IOException {
		Set<String> present = new HashSet<>();
		for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
			String line = raw.trim();
			if (line.isEmpty() || line.startsWith("#") || line.startsWith("[")) {
				continue;
			}
			int equals = line.indexOf('=');
			if (equals < 0) {
				continue;
			}
			String key = line.substring(0, equals).trim();
			String value = line.substring(equals + 1).trim();
			int comment = value.indexOf('#');
			if (comment >= 0) {
				value = value.substring(0, comment).trim();
			}
			present.add(key);
			boolean flag = value.equalsIgnoreCase("true");
			switch (key) {
				case "variantCapture" -> this.variantCapture = flag;
				case "keepEquipment" -> this.keepEquipment = flag;
				case "poseCapture" -> this.poseCapture = flag;
				default -> {
				}
			}
		}
		return present;
	}

	private void write(Path file) throws IOException {
		if (file.getParent() != null) {
			Files.createDirectories(file.getParent());
		}
		List<String> lines = new ArrayList<>(List.of(HEADER));
		for (Option option : OPTIONS) {
			lines.addAll(List.of(option.comment()));
			lines.add(option.line(this));
			lines.add("");
		}
		Files.writeString(file, String.join("\n", lines), StandardCharsets.UTF_8);
	}

	/**
	 * Writes the options this version knows but the file does not, at the end of
	 * the file. Nothing already in the file is read back out or rewritten.
	 */
	private void appendMissing(Path file, Set<String> present) throws IOException {
		List<Option> missing = OPTIONS.stream().filter(option -> !present.contains(option.key())).toList();
		if (missing.isEmpty()) {
			return;
		}
		String existing = Files.readString(file, StandardCharsets.UTF_8);
		List<String> lines = new ArrayList<>();
		if (!existing.isEmpty() && !existing.endsWith("\n")) {
			lines.add(""); // keep our first appended line off the player's last one
		}
		for (Option option : missing) {
			lines.addAll(List.of(option.comment()));
			lines.add(option.line(this));
			lines.add("");
		}
		Files.writeString(file, String.join("\n", lines), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
		EntityCapture.LOGGER.info("Added {} option(s) to {}: {}", missing.size(), FILE_NAME,
				missing.stream().map(Option::key).toList());
	}
}
