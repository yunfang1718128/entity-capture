package com.yunfang.entitycapture.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.yunfang.entitycapture.EntityCapture;
import com.yunfang.entitycapture.capture.CaptureManager;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;

/**
 * Searchable entity picker: browse {@link BuiltInRegistries#ENTITY_TYPE} (mods
 * included), preview the selected entity and queue a capture through
 * {@link CaptureManager}. The GUI is the primary capture entry; the keybind and
 * {@code /capturemob} remain as shortcuts.
 *
 * <p>Multi-select mode lets the player tick any number of mobs (with
 * select-all / clear over the currently filtered rows) and capture the whole
 * set with one press.
 */
public class EntityPickerScreen extends Screen {
	private static final int MARGIN = 24;
	private static final int SEARCH_HEIGHT = 18;
	private static final int ROW_HEIGHT = 22;
	private static final int PREVIEW_MIN_WIDTH = 520;

	private final java.util.List<EntityType<?>> allTypes = new ArrayList<>();
	private final Set<EntityType<?>> checked = new LinkedHashSet<>();
	private final Map<EntityType<?>, Boolean> checkableCache = new HashMap<>();

	private EditBox searchBox;
	private EntityList list;
	private Button captureButton;
	private Button multiButton;
	private Button selectAllButton;
	private Button clearAllButton;
	private Checkbox showMiscBox;

	private boolean showMisc;
	private boolean multiSelect;
	private int matched;

	private boolean previewVisible;
	private int panelX;
	private int panelW;

	private EntityType<?> previewType;
	private Entity previewEntity;
	private float previewSpin;

	public EntityPickerScreen() {
		super(Component.translatable("gui.entity-capture.title"));
		BuiltInRegistries.ENTITY_TYPE.stream().forEach(this.allTypes::add);
		this.allTypes.sort(Comparator.comparing(type -> type.getDescription().getString(), String.CASE_INSENSITIVE_ORDER));
	}

	@Override
	protected void init() {
		super.init();
		this.checkableCache.clear();

		boolean wantPreview = this.width >= PREVIEW_MIN_WIDTH
				&& this.minecraft != null && this.minecraft.level != null;
		int reserved = wantPreview ? Math.min(150, this.width / 3) + 12 : 0;
		int listWidth = this.width - MARGIN * 2 - reserved;

		int searchY = 30;
		int searchWidth = Math.max(80, listWidth - 108);
		this.searchBox = new EditBox(this.font, MARGIN, searchY, searchWidth, SEARCH_HEIGHT,
				Component.translatable("gui.entity-capture.search"));
		this.searchBox.setHint(Component.translatable("gui.entity-capture.search.hint"));
		this.searchBox.setMaxLength(64);
		this.searchBox.setResponder(value -> rebuild());
		this.addRenderableWidget(this.searchBox);

		// 1.20.1 has no Checkbox builder / value-change callback, so subclass and
		// react to onPress. The label is drawn by the checkbox itself (showLabel).
		this.showMiscBox = new Checkbox(MARGIN + listWidth - 104, searchY + 1, 110, 20,
				Component.translatable("gui.entity-capture.show_misc"), this.showMisc) {
			@Override
			public void onPress() {
				super.onPress();
				EntityPickerScreen.this.showMisc = this.selected();
				EntityPickerScreen.this.rebuild();
			}
		};
		this.addRenderableWidget(this.showMiscBox);

		int listTop = searchY + SEARCH_HEIGHT + 6;
		int listHeight = this.height - listTop - 56;
		this.list = new EntityList(this.minecraft, listWidth, Math.max(40, listHeight), listTop, ROW_HEIGHT);
		this.list.setLeftPos(MARGIN);
		this.addRenderableWidget(this.list);

		this.previewVisible = wantPreview;
		this.panelX = MARGIN + listWidth + 12;
		this.panelW = wantPreview ? this.width - MARGIN - this.panelX : 0;

		int buttonY = this.height - 42;
		this.captureButton = Button.builder(Component.translatable("gui.entity-capture.capture"), button -> captureAction())
				.bounds(MARGIN, buttonY, 70, 20)
				.build();
		this.addRenderableWidget(this.captureButton);

		this.multiButton = Button.builder(Component.translatable("gui.entity-capture.multi"), button -> setMultiSelect(!this.multiSelect))
				.bounds(MARGIN + 76, buttonY, 48, 20)
				.build();
		this.addRenderableWidget(this.multiButton);

		this.selectAllButton = Button.builder(Component.translatable("gui.entity-capture.select_all"), button -> selectAllVisible())
				.bounds(MARGIN + 130, buttonY, 48, 20)
				.build();
		this.addRenderableWidget(this.selectAllButton);

		this.clearAllButton = Button.builder(Component.translatable("gui.entity-capture.deselect_all"), button -> {
					this.checked.clear();
					updateCaptureButton();
				})
				.bounds(MARGIN + 184, buttonY, 56, 20)
				.build();
		this.addRenderableWidget(this.clearAllButton);

		Button closeButton = Button.builder(Component.translatable("gui.cancel"), button -> this.onClose())
				.bounds(this.width - MARGIN - 70, buttonY, 70, 20)
				.build();
		this.addRenderableWidget(closeButton);

		this.setInitialFocus(this.searchBox);
		applyMultiSelectVisibility();
		rebuild();
	}

	private void setMultiSelect(boolean value) {
		this.multiSelect = value;
		if (!value) {
			this.checked.clear();
		}
		applyMultiSelectVisibility();
		updateCaptureButton();
	}

	private void applyMultiSelectVisibility() {
		if (this.multiButton != null) {
			this.multiButton.setMessage(Component.translatable(this.multiSelect ? "gui.entity-capture.multi_on" : "gui.entity-capture.multi"));
		}
		if (this.captureButton != null) {
			this.captureButton.setMessage(Component.translatable(this.multiSelect
					? "gui.entity-capture.capture_selected"
					: "gui.entity-capture.capture"));
		}
		if (this.selectAllButton != null) {
			this.selectAllButton.visible = this.multiSelect;
		}
		if (this.clearAllButton != null) {
			this.clearAllButton.visible = this.multiSelect;
		}
	}

	private void updateCaptureButton() {
		if (this.captureButton == null) {
			return;
		}
		this.captureButton.active = this.multiSelect
				? !this.checked.isEmpty()
				: this.list != null && this.list.getSelected() != null;
	}

	private void rebuild() {
		if (this.list == null) {
			return;
		}
		this.list.clearRows();
		String query = this.searchBox == null ? "" : this.searchBox.getValue().trim().toLowerCase(Locale.ROOT);
		int count = 0;
		for (EntityType<?> type : this.allTypes) {
			if (!this.showMisc && !isMob(type)) {
				continue;
			}
			Component name = type.getDescription();
			String id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
			if (!query.isEmpty()
					&& !name.getString().toLowerCase(Locale.ROOT).contains(query)
					&& !id.toLowerCase(Locale.ROOT).contains(query)) {
				continue;
			}
			this.list.addRow(this.list.new Entry(type, name, id));
			count++;
		}
		this.matched = count;
		this.list.setSelected(null);
		updateCaptureButton();
	}

	private void selectAllVisible() {
		for (EntityList.Entry entry : this.list.rows()) {
			if (isMob(entry.type)) {
				this.checked.add(entry.type);
			}
		}
		updateCaptureButton();
	}

	/**
	 * Whether a row can be ticked. {@code EntityType#getBaseClass()} is a stub in
	 * 1.21.1 (always returns {@code Entity.class}), so the concrete type is
	 * resolved by instantiating once and testing {@code instanceof Mob}, cached
	 * per type. Non-mobs (boats, arrows, armor stands, …) stay unselectable.
	 */
	private boolean isMob(EntityType<?> type) {
		return this.checkableCache.computeIfAbsent(type, t -> {
			// Every non-MISC category in vanilla is a Mob; only MISC needs the
			// concrete check because villager / iron_golem / snow_golem are MISC
			// yet are real mobs, unlike armor stands or projectiles.
			if (t.getCategory() != MobCategory.MISC) {
				return true;
			}
			if (this.minecraft == null || this.minecraft.level == null) {
				return false;
			}
			try {
				return t.create(this.minecraft.level) instanceof Mob;
			} catch (Throwable throwable) {
				return false;
			}
		});
	}

	private void captureAction() {
		if (this.multiSelect) {
			if (this.checked.isEmpty()) {
				return;
			}
			CaptureManager.requestBatch(new ArrayList<>(this.checked));
			this.onClose();
		} else {
			captureSelected();
		}
	}

	private void captureSelected() {
		EntityList.Entry selected = this.list == null ? null : this.list.getSelected();
		if (selected != null) {
			startCapture(selected.type);
		}
	}

	private void startCapture(EntityType<?> type) {
		if (type == null) {
			return;
		}
		CaptureManager.requestType(type);
		this.onClose();
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		super.render(guiGraphics, mouseX, mouseY, partialTick);

		guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
		guiGraphics.drawString(this.font,
				Component.translatable("gui.entity-capture.count", this.allTypes.size(), this.matched),
				MARGIN, this.height - 54, 0xA0A0A0, false);
		if (this.multiSelect) {
			guiGraphics.drawString(this.font,
					Component.translatable("gui.entity-capture.selected_count", this.checked.size()),
					MARGIN + 170, this.height - 54, 0xFFFF80, false);
		}

		if (this.previewVisible) {
			renderPreview(guiGraphics, partialTick);
		}
	}

	private void renderPreview(GuiGraphics guiGraphics, float partialTick) {
		int x = this.panelX;
		int y = this.list.listTop();
		int w = this.panelW;
		int h = this.list.listHeight();
		guiGraphics.fill(x, y, x + w, y + h, 0x50000000);
		guiGraphics.fill(x, y, x + 1, y + h, 0x60FFFFFF);
		guiGraphics.fill(x, y, x + w, y + 1, 0x60FFFFFF);
		guiGraphics.fill(x + w - 1, y, x + w, y + h, 0x60FFFFFF);
		guiGraphics.fill(x, y + h - 1, x + w, y + h, 0x60FFFFFF);

		EntityType<?> type = selectedType();
		if (type == null) {
			guiGraphics.drawCenteredString(this.font, Component.translatable("gui.entity-capture.select"),
					x + w / 2, y + h / 2 - 4, 0x808080);
			return;
		}

		if (this.previewType != type) {
			this.previewType = type;
			this.previewEntity = createPreviewEntity(type);
			this.previewSpin = 0.0F;
		}

		int boxHeight = (int) (h * 0.55F);
		renderPreviewEntity(guiGraphics, x, y, w, boxHeight, partialTick);

		int textX = x + 6;
		int textY = y + boxHeight + 6;
		String id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
		guiGraphics.drawString(this.font, type.getDescription(), textX, textY, 0xFFFFFF, false);
		guiGraphics.drawString(this.font, Component.translatable("gui.entity-capture.id", id), textX, textY + 12, 0x9A9A9A, false);
		guiGraphics.drawString(this.font, Component.translatable("gui.entity-capture.category", type.getCategory().getName()), textX, textY + 24, 0x9A9A9A, false);
		guiGraphics.drawString(this.font, Component.translatable("gui.entity-capture.size",
				String.format(Locale.ROOT, "%.1f x %.1f", type.getWidth(), type.getHeight())), textX, textY + 36, 0x9A9A9A, false);
		guiGraphics.drawString(this.font, Component.translatable("gui.entity-capture.source",
				BuiltInRegistries.ENTITY_TYPE.getKey(type).getNamespace()), textX, textY + 48, 0x9A9A9A, false);
		Component status = this.previewEntity == null
				? Component.translatable("gui.entity-capture.status.uncapturable")
				: Component.translatable("gui.entity-capture.status.capturable");
		guiGraphics.drawString(this.font, status, textX, textY + 60, this.previewEntity == null ? 0xFF8080 : 0x80FF80, false);
	}

	private void renderPreviewEntity(GuiGraphics guiGraphics, int x, int y, int w, int h, float partialTick) {
		Entity entity = this.previewEntity;
		if (entity == null) {
			guiGraphics.drawCenteredString(this.font, Component.translatable("gui.entity-capture.create_failed"),
					x + w / 2, y + h / 2 - 4, 0xFF8080);
			return;
		}
		this.previewSpin += partialTick * 2.0F;
		try {
			float size = Math.max(0.6F, Math.max(entity.getBbWidth(), entity.getBbHeight()));
			float scale = (h * 0.8F) / size;
			PoseStack pose = guiGraphics.pose();
			pose.pushPose();
			pose.translate(x + w / 2.0F, y + h / 2.0F, 100.0F);
			pose.scale(-scale, scale, scale);
			pose.mulPose(Axis.YP.rotationDegrees(this.previewSpin));
			pose.translate(0.0F, -entity.getBbHeight() / 2.0F, 0.0F);

			EntityRenderDispatcher dispatcher = this.minecraft.getEntityRenderDispatcher();
			dispatcher.setRenderShadow(false);
			dispatcher.render(entity, 0.0D, 0.0D, 0.0D, 0.0F, partialTick, pose, guiGraphics.bufferSource(), LightTexture.FULL_BRIGHT);
			dispatcher.setRenderShadow(true);
			guiGraphics.flush();
			pose.popPose();
		} catch (Throwable throwable) {
			EntityCapture.LOGGER.warn("Entity preview failed for {}", entity.getType(), throwable);
			guiGraphics.drawCenteredString(this.font, Component.translatable("gui.entity-capture.preview_failed"),
					x + w / 2, y + h / 2 - 4, 0xFF8080);
		}
	}

	private Entity createPreviewEntity(EntityType<?> type) {
		if (this.minecraft == null || this.minecraft.level == null) {
			return null;
		}
		try {
			return type.create(this.minecraft.level);
		} catch (Throwable throwable) {
			return null;
		}
	}

	private EntityType<?> selectedType() {
		if (this.list == null) {
			return null;
		}
		EntityList.Entry selected = this.list.getSelected();
		return selected == null ? null : selected.type;
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == 257 || keyCode == 335) { // ENTER / KP_ENTER
			captureAction();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean isPauseScreen() {
		return true;
	}

	private class EntityList extends ObjectSelectionList<EntityList.Entry> {
		// 1.20.1: ObjectSelectionList/AbstractSelectionList take
		// (client, width, height, top, bottom, itemHeight) — one more int than
		// 1.21.1 — and expose no getX/getY/getWidth/getHeight getters.
		EntityList(Minecraft minecraft, int width, int height, int top, int itemHeight) {
			super(minecraft, width, height, top, top + height, itemHeight);
			this.centerListVertically = false;
		}

		int listTop() {
			return this.y0;
		}

		int listHeight() {
			return this.height;
		}

		@Override
		public int getRowWidth() {
			return Math.max(120, this.width - 24);
		}

		@Override
		protected int getScrollbarPosition() {
			return this.x0 + this.width - 6;
		}

		void clearRows() {
			this.clearEntries();
		}

		void addRow(Entry entry) {
			this.addEntry(entry);
		}

		List<Entry> rows() {
			return this.children();
		}

		private class Entry extends ObjectSelectionList.Entry<Entry> {
			private final EntityType<?> type;
			private final Component name;
			private final String id;
			private long lastClick;

			Entry(EntityType<?> type, Component name, String id) {
				this.type = type;
				this.name = name;
				this.id = id;
			}

			@Override
			public void render(GuiGraphics guiGraphics, int index, int top, int left, int width, int height,
					int mouseX, int mouseY, boolean hovered, float partialTick) {
				boolean marked = checked.contains(this.type);
				int textLeft = left + 4;
				if (multiSelect && isMob(this.type)) {
					int boxX = left + 3;
					int boxY = top + 4;
					int size = 9;
					guiGraphics.fill(boxX, boxY, boxX + size, boxY + size, 0xFF000000);
					guiGraphics.fill(boxX + 1, boxY + 1, boxX + size - 1, boxY + size - 1,
							marked ? 0xFF3FA34D : 0xFF505050);
					textLeft = left + 16;
				}
				boolean selected = EntityList.this.getSelected() == this;
				int nameColor = marked ? 0x80FF80 : (selected ? 0xFFFF80 : 0xFFFFFF);
				guiGraphics.drawString(EntityPickerScreen.this.font, this.name, textLeft, top + 3, nameColor, false);
				guiGraphics.drawString(EntityPickerScreen.this.font, this.id, textLeft, top + 12, 0x9A9A9A, false);
			}

			@Override
			public Component getNarration() {
				return this.name;
			}

			@Override
			public boolean mouseClicked(double mouseX, double mouseY, int button) {
				EntityList.this.setSelected(this);
				if (multiSelect) {
					if (button == 0 && isMob(this.type)) {
						if (checked.contains(this.type)) {
							checked.remove(this.type);
						} else {
							checked.add(this.type);
						}
						updateCaptureButton();
					}
					return true;
				}
				updateCaptureButton();
				if (button == 0) {
					long now = Util.getMillis();
					if (now - this.lastClick <= 250L) {
						startCapture(this.type);
						return true;
					}
					this.lastClick = now;
				}
				return true;
			}
		}
	}
}
