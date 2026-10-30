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
	private Component detail;
	private boolean edited;
	private boolean deleted;
	private int actionSpace;
	private boolean selected;
	private int catalogIndex = -1;
	private boolean selectionMode;
	private final DesignerMotionValue selectionFeedback = new DesignerMotionValue();
	private long selectionFrame;

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

	DesignerBeeButton detail(Component value) {
		detail = value;
		setHeight(34);
		setTooltip(Tooltip.create(name.copy().append("\n" + id).append("\n").append(value)));
		return this;
	}

	DesignerBeeButton fitted() {
		setWidth(Math.min(getWidth(), Math.max(104, Math.min(260, Minecraft.getInstance().font.width(name) + 38))));
		return this;
	}

	DesignerBeeButton selected(boolean value) {
		selected = value;
		if (selectionFrame == 0) {
			selectionFeedback.snap(value ? 1 : 0);
		}
		return this;
	}

	DesignerBeeButton selectionMode(boolean value) {
		selectionMode = value;
		return this;
	}

	DesignerBeeButton catalogIndex(int index) {
		catalogIndex = index;
		return this;
	}

	int catalogIndex() {
		return catalogIndex;
	}

	String beeId() {
		return id;
	}

	@Override
	protected boolean clicked(double x, double y) {
		return super.clicked(x, y) && isMouseOver(x, y);
	}

	@Override
	public boolean isMouseOver(double x, double y) {
		return super.isMouseOver(x, y) && x < getX() + width - actionSpace;
	}

	@Override
	protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		DesignerTheme.panel(graphics, isHoveredOrFocused() ? "button_hover" : "card", getX(), getY(), width, height);
		long now = net.minecraft.Util.getMillis();
		selectionFeedback.advance(selected ? 1 : 0, selectionFrame == 0 ? 0 : (now - selectionFrame) / 1000.0, 0.12,
				DesignerPreferences.animate() && DesignerPreferences.enabled("motion_selection"));
		selectionFrame = now;
		if (selectionFeedback.position() > 0.01) {
			DesignerTheme.surface(graphics, getX(), getY(), width, height,
					DesignerPalette.mix(DesignerTheme.colors().card(), DesignerTheme.colors().selected(), (float) selectionFeedback.position()),
					DesignerPalette.mix(DesignerTheme.colors().border(), DesignerTheme.colors().accent(), (float) selectionFeedback.position() * 0.6F));
		}
		if (pending) {
			graphics.fill(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1,
					DesignerPalette.mix(DesignerTheme.colors().card(), DesignerTheme.colors().accent(), 0.1F));
			graphics.fill(getX() + width - 7, getY() + 8, getX() + width - 3, getY() + 12, DesignerTheme.colors().accent());
		}
		DesignerTheme.panel(graphics, "slot", getX() + 1, getY() + 1, 18, 18);
		graphics.renderItem(icon, getX() + 2, getY() + 2);
		var font = Minecraft.getInstance().font;
		graphics.drawString(font, font.plainSubstrByWidth(name.getString(), Math.max(10, width - (pending ? 38 : 30) - actionSpace - (selectionMode ? 16 : 0))),
				getX() + 24, getY() + 6, DesignerFormScreen.FOREGROUND, false);
		if (selectionMode) {
			int x = getX() + width - 15;
			int y = getY() + 6;
			int color = selected ? DesignerTheme.colors().accent() : DesignerTheme.colors().border();
			DesignerTheme.surface(graphics, x, y, 10, 10, selected ? color : DesignerTheme.colors().input(), color);
			if (selected) {
				int ink = DesignerPalette.contrast(color);
				graphics.fill(x + 2, y + 4, x + 4, y + 6, ink);
				graphics.fill(x + 4, y + 5, x + 6, y + 7, ink);
				graphics.fill(x + 6, y + 2, x + 8, y + 5, ink);
			}
		}
		if (detail != null) {
			graphics.drawString(font, font.plainSubstrByWidth(detail.getString(), width - 12), getX() + 6, getY() + 22,
					DesignerTheme.colors().muted(), false);
		} else if (!origin.isBlank()) {
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
