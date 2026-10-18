package com.ayoshiko.productivebeesgenesis.client.resource;

import com.ayoshiko.productivebeesgenesis.resource.ResourceKey;
import com.ayoshiko.productivebeesgenesis.resource.ResourceTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** 原生资源图标只引用已加载资源或物品模型，不依赖可选模组的 Java 类型。 */
final class ResourceNativeIcons {
	interface Icon { void draw(GuiGraphics graphics); }
	private ResourceNativeIcons() {}

	static Icon resolve(ResourceKey key) {
		if (ResourceTypes.FE.equals(key)) return texture("mekanism:liquid/energy", 16, 512);
		if (ResourceTypes.EXPERIENCE.equals(key)) return texture("experiencelib:gui/experience", 16, 256);
		if (ResourceTypes.SOURCE.equals(key)) return texture("ars_nouveau:block/source_still", 16, 320);
		if (ResourceTypes.SOUL.equals(key)) return texture("minecraft:particle/sculk_soul_0", 16, 16);
		if (ResourceTypes.AIR.equals(key)) return texture("pneumaticcraft:particle/air_particle", 32, 32);
		if (ResourceTypes.HIGH_VOLTAGE.equals(key)) return texture("ae2lt:item/high_voltage_lightning", 16, 96);
		if (ResourceTypes.EXTREME_HIGH_VOLTAGE.equals(key)) return texture("ae2lt:item/extreme_high_voltage_lightning", 16, 96);
		if (ResourceTypes.EMC.equals(key)) {
			var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse("projecte:klein_star_ein"));
			if (item != Items.AIR) {
				var stack = new ItemStack(item);
				return graphics -> graphics.renderItem(stack, 0, 0);
			}
		}
		return null;
	}

	/** 优先使用图集中的动画帧；未加入图集的 GUI 贴图使用原生首帧。 */
	private static Icon texture(String id, int width, int height) {
		var spriteId = ResourceLocation.parse(id);
		var texture = spriteId.withPath(path -> "textures/" + path + ".png");
		var mc = Minecraft.getInstance();
		if (mc.getResourceManager().getResource(texture).isEmpty()) return null;
		return graphics -> {
			var sprite = mc.getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(spriteId);
			com.mojang.blaze3d.systems.RenderSystem.enableBlend();
			com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
			graphics.setColor(1, 1, 1, 1);
			try {
				if (!sprite.contents().name().equals(MissingTextureAtlasSprite.getLocation())) {
					graphics.blit(0, 0, 0, 16, 16, sprite);
				} else {
					graphics.blit(texture, 0, 0, 16, 16, 0, 0, width, width, width, height);
				}
			} finally {
				graphics.flush();
				com.mojang.blaze3d.systems.RenderSystem.disableBlend();
			}
		};
	}
}
