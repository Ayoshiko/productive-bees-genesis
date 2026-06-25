package com.ayoshiko.productivebeesgenesis.client.render.cosmic;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonElement;

import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
import net.neoforged.neoforge.client.model.geometry.IGeometryLoader;
import net.neoforged.neoforge.client.model.geometry.IUnbakedGeometry;
import org.jetbrains.annotations.NotNull;

/**
 * Cosmic 几何加载器
 * <br/>
 * 解析 loader: "productivebeesgenesis:cosmic"，读取 cosmic.mask（字符串或数组），
 * 委托 baseModel 解析父模型，烘焙为 BakedModelCosmic。
 */
public class GeometryLoaderCosmic implements IGeometryLoader<GeometryLoaderCosmic.CosmicGeometry> {

	@Override
	public CosmicGeometry read(JsonObject modelContents, JsonDeserializationContext deserializationContext) throws JsonParseException {
		BlockModel baseModel = deserializationContext.deserialize(clear(modelContents, "cosmic"), BlockModel.class);
		List<ResourceLocation> maskTextures = getMasks(modelContents, "cosmic");
		return new CosmicGeometry(baseModel, maskTextures);
	}

	private JsonObject clear(JsonObject modelContents, String... types) {
		JsonObject clean = modelContents.deepCopy();
		clean.remove("loader");
		for (String type : types) {
			clean.remove(type);
		}
		return clean;
	}

	private List<ResourceLocation> getMasks(JsonObject modelContents, String type) {
		JsonObject cosmic = modelContents.getAsJsonObject(type);
		if (cosmic == null) {
			throw new IllegalStateException("Missing " + type + " object.");
		}
		List<ResourceLocation> maskTextures = new ArrayList<>();
		JsonElement maskElement = cosmic.get("mask");
		if (maskElement != null && maskElement.isJsonArray()) {
			JsonArray masks = maskElement.getAsJsonArray();
			for (int i = 0; i < masks.size(); i++) {
				maskTextures.add(ResourceLocation.tryParse(masks.get(i).getAsString()));
			}
		} else {
			maskTextures.add(ResourceLocation.tryParse(GsonHelper.getAsString(cosmic, "mask")));
		}
		return maskTextures;
	}

	public static class CosmicGeometry implements IUnbakedGeometry<CosmicGeometry> {

		private final BlockModel baseModel;
		private final List<ResourceLocation> maskTextures;

		public CosmicGeometry(BlockModel baseModel, List<ResourceLocation> maskTextures) {
			this.baseModel = baseModel;
			this.maskTextures = maskTextures;
		}

		@Override
		public void resolveParents(@NotNull Function<ResourceLocation, UnbakedModel> modelGetter, @NotNull IGeometryBakingContext context) {
			this.baseModel.resolveParents(modelGetter);
		}

		@NotNull
		@Override
		public BakedModel bake(@NotNull IGeometryBakingContext context, @NotNull ModelBaker baker, @NotNull Function<Material, TextureAtlasSprite> spriteGetter, @NotNull ModelState modelState, @NotNull ItemOverrides overrides) {
			BakedModel baseBakedModel = this.baseModel.bake(baker, this.baseModel, spriteGetter, modelState, true);
			return new BakedModelCosmic(baseBakedModel, this.maskTextures);
		}
	}
}
