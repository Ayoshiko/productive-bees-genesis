package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** 紧凑窗口用独立预览页，不覆盖编辑字段。 */
final class DesignerPreviewScreen extends DesignerFormScreen {
	private final Screen parent;
	private final JsonObject document;
	private final DesignerPreview preview = new DesignerPreview();
	private int previewHeight;

	DesignerPreviewScreen(Screen parent, JsonObject document) {
		super(tr("preview"));
		this.parent = parent;
		editorParent = parent;
		this.document = document;
	}

	@Override
	protected void init() {
		begin();
		button(left, footerY(), 90, Component.translatable("gui.done"), this::onClose);
		preview.update(document);
		boolean horizontal = editorBottom() - contentTop < 130;
		int controlsTop = Math.max(contentTop, editorBottom() - (horizontal ? 20 : 64));
		previewHeight = Math.max(0, controlsTop - contentTop - 4);
		preview.controls(this, left, controlsTop, contentWidth, horizontal);
	}

	@Override
	protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.renderContent(graphics, mouseX, mouseY, partialTick);
		if (previewHeight >= 8) {
			preview.render(graphics, document, left, contentTop, contentWidth, previewHeight, mouseX, mouseY);
		}
	}

	@Override
	protected boolean dragContent(double x, double y, int button, double dx, double dy) {
		if (!isWindowDragging() && !isInventoryDragging() && x >= left && x < left + contentWidth && y >= contentTop && y < contentTop + previewHeight) {
			preview.rotate(dx, dy);
			return true;
		}
		return super.dragContent(x, y, button, dx, dy);
	}

	@Override
	public void removed() {
		super.removed();
		preview.clear();
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
