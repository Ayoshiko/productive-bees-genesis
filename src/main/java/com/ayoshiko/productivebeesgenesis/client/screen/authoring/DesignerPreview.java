package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument;
import com.google.gson.JsonObject;
import cy.jdkdigital.productivebees.common.entity.bee.ConfigurableBee;
import cy.jdkdigital.productivebees.init.ModEntities;
import cy.jdkdigital.productivebees.util.BeeCreator;
import cy.jdkdigital.productivebees.client.render.entity.layers.BeeBodyLayer;
import net.neoforged.fml.ModList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** 预览覆盖仅属于未加入世界的实体实例；不临时替换 PB 全局蜂种表。 */
final class DesignerPreview {
	private ConfigurableBee bee;
	private JsonObject definition;
	private String beeId = "";
	private String problem = "";
	private float rotation = 140;
	private float pitch = 18;
	private double sensitivity = DesignerPreferences.number("preview_sensitivity");

	void update(JsonObject document) {
		Minecraft mc = Minecraft.getInstance();
		if (document == null || mc.level == null) {
			clear();
			return;
		}
		JsonObject source = document.getAsJsonObject("definition");
		String id = document.get("id").getAsString();
		if (bee != null && bee.level() == mc.level && id.equals(beeId) && source.equals(definition)) {
			return;
		}
		definition = source.deepCopy();
		beeId = id;
		try {
			CompoundTag parsed = BeeCreator.create(ResourceLocation.parse(document.get("id").getAsString()),
					document.getAsJsonObject("definition").deepCopy());
			if (bee == null || bee.level() != mc.level) {
				bee = ModEntities.CONFIGURABLE_BEE.get().create(mc.level);
				if (bee == null) {
					throw new IllegalStateException("PB configurable bee factory returned no entity");
				}
				bee.setNoAi(true);
				bee.setNoGravity(true);
			}
			((DesignerPreviewData) bee).productivebeesgenesis$setPreviewData(parsed);
			bee.setBeeType(document.get("id").getAsString());
			bee.refreshDimensions();
			String renderer = bee.getRenderer().isEmpty() ? "default" : bee.getRenderer();
			if (ModList.get().isLoaded("geckolib")) {
				ResourceLocation model = parsed.contains("model") ? ResourceLocation.parse(parsed.getString("model"))
						: ResourceLocation.fromNamespaceAndPath("productivebees", "geo/entity/" + renderer + ".geo.json");
				if (mc.getResourceManager().getResource(model).isEmpty()) {
					throw new IllegalArgumentException("designer.error.preview_resource|" + model);
				}
			}
			String texture = bee.hasBeeTexture() ? bee.getBeeTexture() : "productivebees:textures/entity/bee/base/" + renderer + "/bee";
			ResourceLocation location = ResourceLocation.parse(texture + ".png");
			if (mc.getResourceManager().getResource(location).isEmpty()) {
				throw new IllegalArgumentException("designer.error.preview_resource|" + location);
			}
			if (!ModList.get().isLoaded("geckolib") && bee.isColored()) {
				var layers = BeeBodyLayer.baseTextures.get(renderer);
				if (layers == null || !layers.containsKey("primary") || !layers.containsKey("abdomen")) {
					throw new IllegalArgumentException("designer.error.preview_texture|" + renderer);
				}
			}
			problem = "";
		} catch (RuntimeException exception) {
			problem = exception.getMessage() == null ? "Invalid preview" : exception.getMessage();
		}
	}

	void render(GuiGraphics graphics, JsonObject document, int x, int y, int width, int height, int mouseX, int mouseY) {
		Minecraft mc = Minecraft.getInstance();
		DesignerTheme.panel(graphics, "card", x, y, width, height);
		boolean compact = height < 145;
		if (!compact) {
			graphics.drawString(DesignerFonts.body(), DesignerFormScreen.tr("preview"), x + 10, y + 10, DesignerFormScreen.ACCENT, false);
		}
		if (bee == null || !problem.isBlank()) {
			if (!problem.isBlank()) {
				graphics.drawWordWrap(DesignerFonts.body(), DesignerFormScreen.statusMessage(problem), x + 8, y + 8, width - 16, DesignerTheme.colors().error());
			}
			return;
		}
		int displayHeight = compact ? height - 4 : Math.min(116, height - 75);
		int displayTop = y + (compact ? 2 : 25);
		if (displayHeight >= 16) {
			DesignerTheme.scissor(graphics, x + 5, displayTop, x + width - 5, displayTop + displayHeight);
			float size = Math.max(0.01F, bee.getSizeModifier());
			float scale = compact ? Math.min(width - 12, displayHeight - 4) / size
					: Math.min(width - 24, displayHeight - 12) * 0.72F / size;
			float body = bee.yBodyRot;
			float yaw = bee.getYRot();
			float oldPitch = bee.getXRot();
			float head = bee.yHeadRot;
			float oldHead = bee.yHeadRotO;
			try {
				bee.yBodyRot = rotation;
				bee.setYRot(rotation);
				bee.setXRot(-pitch);
				bee.yHeadRot = rotation;
				bee.yHeadRotO = rotation;
				Quaternionf camera = new Quaternionf().rotateX(pitch * (float) Math.PI / 180);
				Quaternionf orientation = new Quaternionf().rotateZ((float) Math.PI).mul(camera);
				InventoryScreen.renderEntityInInventory(graphics, x + width / 2F, displayTop + displayHeight / 2F,
						scale, new Vector3f(0, bee.getBbHeight() / 2, 0), orientation, camera, bee);
			} finally {
				bee.yBodyRot = body;
				bee.setYRot(yaw);
				bee.setXRot(oldPitch);
				bee.yHeadRot = head;
				bee.yHeadRotO = oldHead;
				graphics.disableScissor();
			}
		}
		if (compact) {
			return;
		}
		int textY = y + 30 + displayHeight;
		if (!problem.isBlank()) {
			graphics.drawString(DesignerFonts.body(), DesignerFonts.body().plainSubstrByWidth(problem, width - 16), x + 8, textY, 0xFFFF8B83, false);
			return;
		}
		JsonObject names = document.getAsJsonObject("names");
		String locale = mc.options.languageCode;
		String name = BeeDesignerDocument.text(names, locale, BeeDesignerDocument.text(names, "en_us", document.get("id").getAsString()));
		graphics.drawString(DesignerFonts.body(), DesignerFonts.body().plainSubstrByWidth(name, width - 16), x + 8, textY, DesignerFormScreen.FOREGROUND, false);
		graphics.drawString(DesignerFonts.body(), DesignerFonts.body().plainSubstrByWidth(document.get("id").getAsString(), width - 16), x + 8, textY + 14, DesignerFormScreen.MUTED, false);
		if (textY + 47 < y + height) {
			Component help = DesignerFormScreen.tr("preview_drag");
			graphics.drawString(DesignerFonts.body(), DesignerFonts.body().plainSubstrByWidth(help.getString(), width - 16), x + 8, textY + 32,
					DesignerFormScreen.MUTED, false);
			if (mouseX >= x + 8 && mouseX < x + width - 8 && mouseY >= textY + 30 && mouseY < textY + 44) {
				graphics.renderTooltip(DesignerFonts.body(), help, mouseX, mouseY);
			}
		}
	}

	void rotate(double dx, double dy) {
		rotation = (rotation - (float) (dx * sensitivity) + 360) % 360;
		pitch = Math.max(-65, Math.min(65, pitch - (float) (dy * sensitivity)));
	}

	void controls(DesignerFormScreen screen, int x, int y, int width, boolean horizontal) {
		int cell = horizontal ? (width - 8) / 3 : width;
		int stepX = horizontal ? cell + 4 : 0;
		int stepY = horizontal ? 0 : 22;
		screen.slider(x, y, cell, rotation / 360.0,
				value -> Component.translatable("designer.preview_yaw", Math.round(value * 360)), value -> rotation = (float) (value * 360));
		screen.slider(x + stepX, y + stepY, cell, (pitch + 65) / 130.0,
				value -> Component.translatable("designer.preview_pitch", Math.round(value * 130 - 65)), value -> pitch = (float) (value * 130 - 65));
		screen.slider(x + stepX * 2, y + stepY * 2, horizontal ? width - (cell + 4) * 2 : width, (sensitivity - 0.15) / 1.35,
				value -> Component.translatable("designer.preview_sensitivity", Math.round((0.15 + value * 1.35) * 100)),
				value -> sensitivity = 0.15 + value * 1.35);
	}

	void clear() {
		bee = null;
		definition = null;
		beeId = "";
		problem = "";
	}
}
