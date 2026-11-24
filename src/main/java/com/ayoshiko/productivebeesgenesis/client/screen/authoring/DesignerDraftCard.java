package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerEggs;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.time.Duration;

/** 草稿图标随可见卡片创建；独立勾选和操作按钮共用同一摘要卡片。 */
final class DesignerDraftCard extends Button {
	private final ItemStack icon;
	private final String name;
	private final String id;
	private final boolean pending;
	private final boolean problem;
	private boolean selected;
	private final int selectionIndex;

	DesignerDraftCard(int x, int y, int width, int height, String name, String id, boolean pending, boolean problem, boolean selected, Component details, int selectionIndex, Runnable clicked) {
		super(x, y, width, height, Component.literal(name + " · " + id), ignored -> clicked.run(), DEFAULT_NARRATION);
		icon = problem ? new ItemStack(Items.BEE_SPAWN_EGG) : BeeDesignerEggs.egg(id);
		this.name = name;
		this.id = id;
		this.pending = pending;
		this.problem = problem;
		this.selected = selected;
		this.selectionIndex = selectionIndex;
		setTooltip(Tooltip.create(details));
		setTooltipDelay(Duration.ofMillis((long) DesignerPreferences.number("tooltip_ms")));
	}

	String draftId() {
		return id;
	}

	int selectionIndex() {
		return selectionIndex;
	}

	void selected(boolean selected) {
		this.selected = selected;
	}

	@Override
	protected boolean clicked(double x, double y) {
		return super.clicked(x, y) && isMouseOver(x, y);
	}

	@Override
	public boolean isMouseOver(double x, double y) {
		return super.isMouseOver(x, y) && x >= getX() + 23 && x < getX() + width - 44;
	}

	@Override
	protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		var palette = DesignerTheme.colors();
		int fill = selected ? DesignerPalette.mix(palette.card(), palette.selected(), 0.55F) : palette.card();
		if (isHoveredOrFocused()) {
			fill = DesignerPalette.mix(fill, palette.hover(), 0.65F);
		}
		int border = selected ? palette.accent() : DesignerPalette.mix(fill, palette.border(), 0.3F);
		DesignerTheme.surface(graphics, getX(), getY(), width, height, fill, border);
		Font font = DesignerFonts.body();
		graphics.renderItem(icon, getX() + 23, getY() + (height - 16) / 2);
		int textX = getX() + 42;
		int stateColor = palette.muted();
		DesignerTheme.Symbol symbol = DesignerTheme.Symbol.CHECK;
		if (problem) {
			stateColor = palette.error();
			symbol = DesignerTheme.Symbol.WARNING;
		} else if (pending) {
			stateColor = palette.accent();
			symbol = DesignerTheme.Symbol.DOT;
		}
		DesignerTheme.statusIcon(graphics, getX() + width - 54, getY() + 4, symbol, stateColor);
		graphics.drawString(font, fitted(font, name, width - 98), textX, getY() + 3,
				active ? palette.text() : palette.muted(), false);
		graphics.drawString(font, fitted(font, id, width - 88), textX, getY() + height - 12, palette.muted(), false);
	}

	private static String fitted(Font font, String text, int width) {
		if (font.width(text) <= width) {
			return text;
		}
		return font.plainSubstrByWidth(text, Math.max(1, width - font.width("…"))) + "…";
	}

	static final class Check extends Button {
		private boolean selected;
		private final String draftId;
		private final String name;

		Check(int x, int y, boolean selected, Component description, Runnable clicked) {
			this(x, y, selected, description, clicked, "", "");
		}

		Check(int x, int y, boolean selected, Component description, Runnable clicked, String draftId, String name) {
			super(x, y, 20, 20, description, ignored -> clicked.run(), DEFAULT_NARRATION);
			this.selected = selected;
			this.draftId = draftId;
			this.name = name;
			setTooltip(Tooltip.create(description));
			setTooltipDelay(Duration.ofMillis((long) DesignerPreferences.number("tooltip_ms")));
		}

		String draftId() {
			return draftId;
		}

		void selected(boolean selected) {
			if (this.selected == selected) {
				return;
			}
			this.selected = selected;
			if (!draftId.isEmpty()) {
				Component description = Component.translatable(selected ? "designer.collection_unselect_named" : "designer.collection_select_named", name);
				setMessage(description);
				setTooltip(Tooltip.create(description));
			}
		}

		@Override
		protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
			var palette = DesignerTheme.colors();
			int border = palette.border();
			if (selected) {
				border = palette.accent();
			} else if (isHoveredOrFocused()) {
				border = palette.text();
			}
			int fill = selected ? palette.accent() : palette.input();
			DesignerTheme.surface(graphics, getX() + 3, getY() + 3, 14, 14, fill, border);
			if (selected) {
				graphics.pose().pushPose();
				graphics.pose().translate(getX() + 10, getY() + 10, 0);
				graphics.pose().scale(0.6F, 0.6F, 1);
				DesignerGlyphs.draw(graphics, DesignerTheme.Symbol.CHECK, DesignerPalette.contrast(fill));
				graphics.pose().popPose();
			}
		}
	}
}
