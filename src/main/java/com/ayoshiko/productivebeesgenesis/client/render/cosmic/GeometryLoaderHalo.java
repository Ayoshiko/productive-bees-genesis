package com.ayoshiko.productivebeesgenesis.client.render.cosmic;

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
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
import net.neoforged.neoforge.client.model.geometry.IGeometryLoader;
import net.neoforged.neoforge.client.model.geometry.IUnbakedGeometry;
import org.jetbrains.annotations.NotNull;

import java.util.function.Function;

/**
	 * Halo 光晕几何加载器
	 * <br/>
	 * 解析 loader: "productivebeesgenesis:halo"，提取 type/alpha/pulse 参数，
	 * 委托 baseModel 解析父模型，烘焙为 BakedModelHalo。
	 */
public class GeometryLoaderHalo implements IGeometryLoader<GeometryLoaderHalo.HaloGeometry> {

	@Override
	public HaloGeometry read(JsonObject jsonObject, JsonDeserializationContext deserializationContext) throws JsonParseException {
		JsonObject clean = jsonObject.deepCopy();
		clean.remove("loader");
		clean.remove("halo");
		BlockModel blockModel = BlockModel.fromString(clean.toString());
		JsonObject halo = jsonObject.getAsJsonObject("halo");
		int type = halo != null && halo.has("type") ? halo.get("type").getAsInt() : 0;
		float alpha = halo != null && halo.has("alpha") ? halo.get("alpha").getAsFloat() : 1.0F;
		boolean pulse = halo != null && halo.has("pulse") ? halo.get("pulse").getAsBoolean() : false;
		return new HaloGeometry(blockModel, type, alpha, pulse);
	}

	public static class HaloGeometry implements IUnbakedGeometry<HaloGeometry> {

		private final BlockModel baseModel;
		private final int type;
		private final float alpha;
		private final boolean pulse;

		public HaloGeometry(BlockModel baseModel, int type, float alpha, boolean pulse) {
			this.baseModel = baseModel;
			this.type = type;
			this.alpha = alpha;
			this.pulse = pulse;
		}

		@Override
		public void resolveParents(@NotNull Function<ResourceLocation, UnbakedModel> modelGetter, @NotNull IGeometryBakingContext context) {
			this.baseModel.resolveParents(modelGetter);
		}

		@NotNull
		@Override
		public BakedModel bake(@NotNull IGeometryBakingContext context, @NotNull ModelBaker baker, @NotNull Function<Material, TextureAtlasSprite> spriteGetter, @NotNull ModelState modelState, @NotNull ItemOverrides overrides) {
			BakedModel bakedModel = this.baseModel.bake(baker, this.baseModel, spriteGetter, modelState, true);
			return new BakedModelHalo(bakedModel, this.type, this.alpha, this.pulse);
		}
	}
}
