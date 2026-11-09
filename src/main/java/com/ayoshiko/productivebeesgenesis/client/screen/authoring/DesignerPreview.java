package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesisClient;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument;
import com.ayoshiko.productivebeesgenesis.client.render.RenderStateSnapshot;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerNativeBees;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.animal.Bee;
import com.google.gson.JsonObject;
import cy.jdkdigital.productivebees.common.entity.bee.ConfigurableBee;
import cy.jdkdigital.productivebees.init.ModEntities;
import cy.jdkdigital.productivebees.util.BeeCreator;
import cy.jdkdigital.productivebees.client.render.entity.layers.BeeBodyLayer;
import net.neoforged.fml.ModList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.joml.Quaternionf;

/** 预览覆盖仅属于未加入世界的实体实例；不临时替换 PB 全局蜂种表。 */
final class DesignerPreview {
	private Bee bee;
	private ClientLevel level;
	private long resources = -1;
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
		if (mc.getOverlay() != null) {
			return;
		}
		JsonObject source = document.getAsJsonObject("definition");
		String id = document.get("id").getAsString();
		long generation = ProductiveBeesGenesisClient.resourceGeneration();
		if (level == mc.level && resources == generation && id.equals(beeId) && source.equals(definition)) {
			return;
		}
		level = mc.level;
		resources = generation;
		definition = source.deepCopy();
		beeId = id;
		try {
			if (BeeDesignerNativeBees.recipesOnly(document)) {
				var entity = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(id)).create(mc.level);
				if (!(entity instanceof Bee nativeBee)) {
					throw new IllegalStateException("Native bee factory returned no bee: " + id);
				}
				bee = nativeBee;
				bee.setNoAi(true);
				bee.setNoGravity(true);
				problem = "";
				return;
			}
			CompoundTag parsed = BeeCreator.create(ResourceLocation.parse(document.get("id").getAsString()),
					document.getAsJsonObject("definition").deepCopy());
			if (!(bee instanceof ConfigurableBee) || bee.level() != mc.level) {
				bee = ModEntities.CONFIGURABLE_BEE.get().create(mc.level);
				if (bee == null) {
					throw new IllegalStateException("PB configurable bee factory returned no entity");
				}
				bee.setNoAi(true);
				bee.setNoGravity(true);
			}
			ConfigurableBee configurable = (ConfigurableBee) bee;
			((DesignerPreviewData) configurable).productivebeesgenesis$setPreviewData(parsed);
			configurable.setBeeType(document.get("id").getAsString());
			configurable.refreshDimensions();
			String renderer = configurable.getRenderer().isEmpty() ? "default" : configurable.getRenderer();
			if (ModList.get().isLoaded("geckolib")) {
				ResourceLocation model = parsed.contains("model") ? ResourceLocation.parse(parsed.getString("model"))
						: ResourceLocation.fromNamespaceAndPath("productivebees", "geo/entity/" + renderer + ".geo.json");
				ResourceLocation animation = parsed.contains("animation") ? ResourceLocation.parse(parsed.getString("animation"))
						: ResourceLocation.fromNamespaceAndPath("productivebees", "animations/entity/bee.animation.json");
				validateGeckoResources(model, animation);
			}
			String texture = configurable.hasBeeTexture() ? configurable.getBeeTexture() : "productivebees:textures/entity/bee/base/" + renderer + "/bee";
			ResourceLocation location = ResourceLocation.parse(texture + ".png");
			if (mc.getResourceManager().getResource(location).isEmpty()) {
				throw new IllegalArgumentException("designer.error.preview_resource|" + location);
			}
			if (!ModList.get().isLoaded("geckolib") && configurable.isColored()) {
				var layers = BeeBodyLayer.baseTextures.get(renderer);
				if (layers == null || !layers.containsKey("primary") || !layers.containsKey("abdomen")) {
					throw new IllegalArgumentException("designer.error.preview_texture|" + renderer);
				}
			}
			problem = "";
		} catch (RuntimeException exception) {
			failed(exception);
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
			float size = bee instanceof ConfigurableBee configurable ? Math.max(0.01F, configurable.getSizeModifier()) : 1;
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
				renderEntity(graphics, x + width / 2F, displayTop + displayHeight / 2F, scale, orientation, camera);
			} catch (RuntimeException exception) {
				failed(exception);
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
		String name = BeeDesignerDocument.text(names, locale, BeeDesignerDocument.text(names, "en_us", BeeDesignerClient.beeName(document.get("id").getAsString()).getString()));
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

	private void renderEntity(GuiGraphics graphics, float x, float y, float scale, Quaternionf orientation, Quaternionf camera) {
		Minecraft client = Minecraft.getInstance();
		var dispatcher = client.getEntityRenderDispatcher();
		Quaternionf previousCamera = new Quaternionf(dispatcher.cameraOrientation());
		GraphicsStatus previousGraphics = client.options.graphicsMode().get();
		graphics.flush();
		try (RenderStateSnapshot state = new RenderStateSnapshot();
				ByteBufferBuilder buffer = new ByteBufferBuilder(RenderType.SMALL_BUFFER_SIZE)) {
			PoseStack pose = new PoseStack();
			pose.last().pose().set(graphics.pose().last().pose());
			pose.last().normal().set(graphics.pose().last().normal());
			pose.translate(x, y, 50);
			pose.scale(scale, scale, -scale);
			pose.translate(0, bee.getBbHeight() / 2, 0);
			pose.mulPose(orientation);
			Lighting.setupForEntityInInventory();
			dispatcher.overrideCameraOrientation(camera.conjugate(new Quaternionf()).rotateY((float) Math.PI));
			client.options.graphicsMode().set(GraphicsStatus.FANCY);
			MultiBufferSource.BufferSource source = MultiBufferSource.immediate(buffer);
			dispatcher.getRenderer(bee).render(bee, 0, 1, pose, source, 15728880);
			source.endBatch();
		} finally {
			dispatcher.overrideCameraOrientation(previousCamera);
			client.options.graphicsMode().set(previousGraphics);
			Lighting.setupFor3DItems();
		}
	}

	private static void validateGeckoResources(ResourceLocation model, ResourceLocation animation) {
		try {
			Class<?> cache = Class.forName("software.bernie.geckolib.cache.GeckoLibCache");
			var models = (java.util.Map<?, ?>) cache.getMethod("getBakedModels").invoke(null);
			var animations = (java.util.Map<?, ?>) cache.getMethod("getBakedAnimations").invoke(null);
			if (!models.containsKey(model)) {
				throw new IllegalArgumentException("designer.error.preview_resource|" + model);
			}
			if (!animations.containsKey(animation)) {
				throw new IllegalArgumentException("designer.error.preview_resource|" + animation);
			}
		} catch (ReflectiveOperationException | LinkageError failure) {
			throw new IllegalStateException("Gecko preview resources unavailable", failure);
		}
	}

	private void failed(RuntimeException failure) {
		problem = failure.getMessage() == null ? "Invalid preview" : failure.getMessage();
		ProductiveBeesGenesis.LOGGER.warn("Bee preview {} failed in resource generation {}", beeId, resources, failure);
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
		level = null;
		resources = -1;
		definition = null;
		beeId = "";
		problem = "";
	}
}
