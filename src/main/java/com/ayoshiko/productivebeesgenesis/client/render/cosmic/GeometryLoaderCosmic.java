package com.ayoshiko.productivebeesgenesis.client.render.cosmic;

import java.util.List;
import java.util.function.Function;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
import org.jetbrains.annotations.NotNull;

/**
 * Cosmic 几何加载器
 * <br/>
 * 解析 loader: "productivebeesgenesis:cosmic"，读取 cosmic.mask（字符串或数组），
 * 委托 baseModel 解析父模型，烘焙为 BakedModelCosmic。
 * <p>
 * 公共 JSON 解析逻辑继承自 {@link AbstractMaskGeometryLoader}，
 * 本类仅提供 type 名与具体 Geometry 的 bake 实现。
 */
public class GeometryLoaderCosmic
		extends AbstractMaskGeometryLoader<GeometryLoaderCosmic.CosmicGeometry> {

	private static final String TYPE = "cosmic";

	@Override
	public CosmicGeometry read(JsonObject modelContents, JsonDeserializationContext deserializationContext) throws JsonParseException {
		BlockModel baseModel = deserializationContext.deserialize(clearLoaderKeys(modelContents, TYPE), BlockModel.class);
		List<ResourceLocation> maskTextures = parseMasks(modelContents, TYPE);
		return new CosmicGeometry(baseModel, maskTextures);
	}

	/**
	 * Cosmic Geometry — 烘焙为 {@link BakedModelCosmic}
	 */
	public static class CosmicGeometry extends AbstractMaskGeometry<CosmicGeometry> {

		public CosmicGeometry(BlockModel baseModel, List<ResourceLocation> maskTextures) {
			super(baseModel, maskTextures);
		}

		@NotNull
		@Override
		public BakedModel bake(@NotNull IGeometryBakingContext context, @NotNull ModelBaker baker,
							   @NotNull Function<Material, TextureAtlasSprite> spriteGetter,
							   @NotNull ModelState modelState, @NotNull ItemOverrides overrides) {
			BakedModel baseBakedModel = bakeBaseModel(baker, modelState, spriteGetter);
			return new BakedModelCosmic(baseBakedModel, this.maskTextures);
		}
	}
}
