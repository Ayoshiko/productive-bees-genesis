package com.ayoshiko.productivebeesgenesis.client.render.cosmic;

import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;

import com.mojang.math.Transformation;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockElement;
import net.minecraft.client.renderer.block.model.BlockElementFace;
import net.minecraft.client.renderer.block.model.FaceBakery;
import net.minecraft.client.renderer.block.model.ItemModelGenerator;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.world.inventory.InventoryMenu;
import net.neoforged.neoforge.client.model.SimpleModelState;

import com.mojang.blaze3d.systems.RenderSystem;

/**
 * 渲染工具类
 * <br/>
 * 提供物品模型烘焙功能，将 TextureAtlasSprite 转换为 BakedQuad 列表。
 * 用于 cosmic 渲染系统中生成 mask 纹理的方块面四边形。
 */
public class RenderUtils {

	public static final ItemModelGenerator ITEM_MODEL_GENERATOR = new ItemModelGenerator();
	public static final FaceBakery FACE_BAKERY = new FaceBakery();

	public static final RenderStateShard.TextureStateShard COSMIC_TEXTURE_ISOLATED = new RenderStateShard.TextureStateShard(InventoryMenu.BLOCK_ATLAS, false, false);
	public static final RenderStateShard.LayeringStateShard POLYGON_OFFSET_LAYERING = new RenderStateShard.LayeringStateShard("polygon_offset_layering", () -> {
		RenderSystem.polygonOffset(-1.0F, -10.0F);
		RenderSystem.enablePolygonOffset();
	}, () -> {
		RenderSystem.polygonOffset(0.0F, 0.0F);
		RenderSystem.disablePolygonOffset();
	});

	/**
	 * 使用默认变换烘焙物品模型
	 */
	public static List<BakedQuad> bakeItem(TextureAtlasSprite... sprites) {
		return bakeItem(Transformation.identity(), sprites);
	}

	/**
	 * 烘焙物品模型为 BakedQuad 列表
	 * <br/>
	 * 原理：通过 ItemModelGenerator 将精灵图按图层展开为 BlockElement，
	 * 再由 FaceBakery 将每个面烘焙为 BakedQuad。
	 *
	 * @param state   模型变换状态
	 * @param sprites 各图层精灵图
	 * @return 烘焙后的四边形列表
	 */
	public static List<BakedQuad> bakeItem(Transformation state, TextureAtlasSprite... sprites) {
		List<BakedQuad> quads = new LinkedList<>();

		for (int i = 0; i < sprites.length; ++i) {
			TextureAtlasSprite sprite = sprites[i];
			List<BlockElement> unbaked = ITEM_MODEL_GENERATOR.processFrames(i, "layer" + i, sprite.contents());

			for (BlockElement element : unbaked) {
				for (Entry<Direction, BlockElementFace> directionBlockElementFaceEntry : element.faces.entrySet()) {
					quads.add(FACE_BAKERY.bakeQuad(element.from, element.to, directionBlockElementFaceEntry.getValue(), sprite, directionBlockElementFaceEntry.getKey(), new SimpleModelState(state), element.rotation, element.shade));
				}
			}
		}

		return quads;
	}
}
