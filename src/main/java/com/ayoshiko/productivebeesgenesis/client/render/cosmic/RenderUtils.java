package com.ayoshiko.productivebeesgenesis.client.render.cosmic;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Transformation;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockElement;
import net.minecraft.client.renderer.block.model.BlockElementFace;
import net.minecraft.client.renderer.block.model.FaceBakery;
import net.minecraft.client.renderer.block.model.ItemModelGenerator;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.world.inventory.InventoryMenu;
import net.neoforged.neoforge.client.model.SimpleModelState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;

/**
	 * 渲染工具类
	 * <br/>
	 * 提供物品模型烘焙功能，将 TextureAtlasSprite 转换为 BakedQuad 列表。
	 * 用于 cosmic 渲染系统中生成 mask 纹理的方块面四边形。
	 * <p>
	 * 设计原则：单一职责（SRP），仅负责物品模型烘焙工具方法与 RenderStateShard 常量。
	 * 所有 bakeItem 实现统一在此类，其他类（如 {@link WrappedItemModel}）通过此类调用。
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
	 * 使用默认变换烘焙物品模型（变长参数版本）
	 */
	public static List<BakedQuad> bakeItem(TextureAtlasSprite... sprites) {
		return bakeItem(Transformation.identity(), sprites);
	}

	/**
	 * 使用默认变换烘焙物品模型（List 版本）
	 * <br/>
	 * 为兼容 {@code WrappedItemModel.bakeItem(List)} 旧调用方提供重载，
	 * 内部委托给 {@link #bakeItem(Transformation, TextureAtlasSprite...)}，
	 * 使用 identity 变换，行为与原实现等价。
	 *
	 * @param sprites 各图层精灵图列表
	 * @return 烘焙后的四边形列表
	 */
	public static List<BakedQuad> bakeItem(List<TextureAtlasSprite> sprites) {
		return bakeItem(Transformation.identity(), sprites.toArray(new TextureAtlasSprite[0]));
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
		List<BakedQuad> quads = new ArrayList<>();

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
