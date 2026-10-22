package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerEggs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** 蜂种目录复用真实蜂蛋，草稿只覆盖此卡片的名称和编辑标记。 */
final class DesignerBeeButton extends Button {
	private final ItemStack icon;
	private final String id;
	private final Component name;
	private final boolean pending;
	private String origin = "";
	private boolean edited;
	private boolean deleted;
	private int actionSpace;

	DesignerBeeButton(int x, int y, int width, String id, Runnable clicked) {
		this(x, y, width, id, null, false, clicked);
	}

	DesignerBeeButton(int x, int y, int width, String id, Component name, boolean pending, Runnable clicked) {
		super(x, y, width, 20, Component.empty(), ignored -> clicked.run(), DEFAULT_NARRATION);
		this.id = id;
		icon = BeeDesignerEggs.egg(id);
		this.name = name == null ? BeeDesignerClient.beeName(id) : name;
		this.pending = pending;
		setMessage(this.name.copy().append(" · " + id));
		Component tooltip = this.name.copy().append("\n" + id);
		if (pending) {
			tooltip = tooltip.copy().append("\n").append(DesignerFormScreen.tr("unapplied_changes"));
		}
		setTooltip(Tooltip.create(tooltip));
	}

	DesignerBeeButton origin(String kind, boolean edited, boolean deleted, String source) {
		origin = kind;
		this.edited = edited;
		this.deleted = deleted;
		setHeight(34);
		var details = name.copy().append("\n" + id).append("\n").append(DesignerFormScreen.tr("origin." + kind));
		if (!source.isBlank()) {
			details.append("\n" + source);
		}
		if (edited) {
			details.append("\n").append(DesignerFormScreen.tr("origin.edited"));
		}
		if (pending) {
			details.append("\n").append(DesignerFormScreen.tr("unapplied_changes"));
		}
		if (deleted) {
			details.append("\n").append(DesignerFormScreen.tr("delete_pending"));
		}
		setTooltip(Tooltip.create(details));
		return this;
	}

	DesignerBeeButton actionSpace(int width) {
		actionSpace = width;
		return this;
	}

	@Override
	protected boolean clicked(double x, double y) {
		return super.clicked(x, y) && x < getX() + width - actionSpace;
	}

	@Override
	protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		DesignerTheme.panel(graphics, isHoveredOrFocused() ? "button_hover" : "card", getX(), getY(), width, height);
		if (pending) {
			graphics.fill(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1,
					DesignerPalette.mix(DesignerTheme.colors().card(), DesignerTheme.colors().accent(), 0.1F));
			graphics.fill(getX() + width - 7, getY() + 8, getX() + width - 3, getY() + 12, DesignerTheme.colors().accent());
		}
		DesignerTheme.panel(graphics, "slot", getX() + 1, getY() + 1, 18, 18);
		graphics.renderItem(icon, getX() + 2, getY() + 2);
		var font = Minecraft.getInstance().font;
		graphics.drawString(font, font.plainSubstrByWidth(name.getString(), Math.max(10, width - (pending ? 38 : 30) - actionSpace)),
				getX() + 24, getY() + 6, DesignerFormScreen.FOREGROUND, false);
		if (!origin.isBlank()) {
			Component badge = DesignerFormScreen.tr("origin." + origin);
			if (deleted) {
				badge = badge.copy().append(" · ").append(DesignerFormScreen.tr("delete_pending"));
			} else if (edited) {
				badge = badge.copy().append(" · ").append(DesignerFormScreen.tr("origin.edited"));
			}
			graphics.drawString(font, font.plainSubstrByWidth(badge.getString(), width - 12), getX() + 6, getY() + 22,
				DesignerTheme.colors().muted(), false);
		}
	}
}
