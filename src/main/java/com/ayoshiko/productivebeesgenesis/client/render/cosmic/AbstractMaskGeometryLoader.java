package com.ayoshiko.productivebeesgenesis.client.render.cosmic;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import net.minecraft.client.renderer.block.model.BlockModel;
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
 * Mask 几何加载器抽象基类
 * <br/>
 * 抽取 {@link GeometryLoaderCosmic} 与 {@link GeometryLoaderHell} 的公共逻辑：
 * <ul>
 *   <li>{@link #clearLoaderKeys}：清除 loader 与 type 键，得到纯 BlockModel JSON</li>
 *   <li>{@link #parseMasks}：解析 mask 字段（字符串或数组），含 ResourceLocation.tryParse null 检查</li>
 *   <li>{@link AbstractMaskGeometry}：持有 baseModel 与 maskTextures 的公共 IUnbakedGeometry 基类</li>
 * </ul>
 * 子类只需实现 {@link IGeometryLoader#read} 构造具体 Geometry，
 * 以及 {@link AbstractMaskGeometry#bake} 返回具体的 BakedModel 类型。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>DRY：消除 clear/parseMasks 的重复实现</li>
 *   <li>OCP：新增 mask 类型 loader 时只需继承基类，不修改基类</li>
 *   <li>SRP：基类仅负责 JSON 解析与公共数据持有，子类负责具体烘焙</li>
 * </ul>
 *
 * @param <T> 具体的 Geometry 子类型
 * @since 1.0.0
 */
public abstract class AbstractMaskGeometryLoader<T extends AbstractMaskGeometryLoader.AbstractMaskGeometry<T>>
		implements IGeometryLoader<T> {

	/**
	 * 清除 JSON 中的 loader 与 type 键，返回可被 BlockModel 反序列化的纯净 JSON
	 * <br/>
	 * 原理：BlockModel 反序列化时会拒绝未知字段，因此需要先 deepCopy 一份并移除
	 * loader 与各 type 段，避免 JsonParseException。
	 *
	 * @param modelContents 原始模型 JSON
	 * @param types         需要移除的 type 键名（如 "cosmic"、"hell"）
	 * @return 仅含 BlockModel 字段的纯净 JSON
	 */
	protected static JsonObject clearLoaderKeys(JsonObject modelContents, String... types) {
		JsonObject clean = modelContents.deepCopy();
		clean.remove("loader");
		for (String type : types) {
			clean.remove(type);
		}
		return clean;
	}

	/**
	 * 解析指定 type 段下的 mask 字段
	 * <br/>
	 * mask 字段支持两种格式：
	 * <ul>
	 *   <li>字符串：单张 mask 纹理</li>
	 *   <li>数组：多张 mask 纹理（按图层顺序）</li>
	 * </ul>
	 * 使用 {@link ResourceLocation#tryParse} 进行安全解析，null 时抛出 JsonParseException。
	 *
	 * @param modelContents 原始模型 JSON
	 * @param type          mask 所在的 type 键名（如 "cosmic"、"hell"）
	 * @return mask 纹理 ResourceLocation 列表（不为 null）
	 * @throws JsonParseException 当 type 段缺失或 ResourceLocation 非法时
	 */
	protected static List<ResourceLocation> parseMasks(JsonObject modelContents, String type) {
		JsonObject section = modelContents.getAsJsonObject(type);
		if (section == null) {
			throw new JsonParseException("Missing " + type + " object.");
		}
		List<ResourceLocation> maskTextures = new ArrayList<>();
		JsonElement maskElement = section.get("mask");
		if (maskElement != null && maskElement.isJsonArray()) {
			JsonArray masks = maskElement.getAsJsonArray();
			for (int i = 0; i < masks.size(); i++) {
				String str = masks.get(i).getAsString();
				ResourceLocation rl = ResourceLocation.tryParse(str);
				if (rl == null) {
					throw new JsonParseException("Invalid ResourceLocation: " + str);
				}
				maskTextures.add(rl);
			}
		} else {
			String str = GsonHelper.getAsString(section, "mask");
			ResourceLocation rl = ResourceLocation.tryParse(str);
			if (rl == null) {
				throw new JsonParseException("Invalid ResourceLocation: " + str);
			}
			maskTextures.add(rl);
		}
		return maskTextures;
	}

	/**
	 * Mask Geometry 公共基类
	 * <br/>
	 * 持有 baseModel 与 maskTextures 公共字段，提供 resolveParents 公共实现。
	 * 子类只需实现 {@link #bake} 返回具体的 BakedModel 类型。
	 *
	 * @param <Self> 自身类型，用于 IUnbakedGeometry 泛型参数
	 */
	protected abstract static class AbstractMaskGeometry<Self extends AbstractMaskGeometry<Self>>
			implements IUnbakedGeometry<Self> {

		/** 父模型，由 BlockModel 反序列化得到 */
		protected final BlockModel baseModel;

		/** mask 纹理 ResourceLocation 列表（顺序对应图层） */
		protected final List<ResourceLocation> maskTextures;

		protected AbstractMaskGeometry(BlockModel baseModel, List<ResourceLocation> maskTextures) {
			this.baseModel = baseModel;
			this.maskTextures = maskTextures;
		}

		@Override
		public void resolveParents(@NotNull Function<ResourceLocation, UnbakedModel> modelGetter,
								   @NotNull IGeometryBakingContext context) {
			this.baseModel.resolveParents(modelGetter);
		}

		/**
		 * 烘焙 baseModel，由子类在 {@link #bake} 中调用以获得基础 BakedModel
		 * <br/>
		 * 封装 {@link BlockModel#bake} 的标准调用模式，避免子类重复样板代码。
		 *
		 * @param baker       模型烘焙器
		 * @param modelState  模型变换状态
		 * @param spriteGetter 纹理精灵获取函数
		 * @return 基础物品/方块 BakedModel
		 */
		@NotNull
		protected BakedModel bakeBaseModel(@NotNull ModelBaker baker, @NotNull ModelState modelState,
										  @NotNull Function<Material, TextureAtlasSprite> spriteGetter) {
			return this.baseModel.bake(baker, this.baseModel, spriteGetter, modelState, true);
		}
	}
}
