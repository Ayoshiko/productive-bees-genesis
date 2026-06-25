package com.ayoshiko.productivebeesgenesis.client.render.cosmic;

import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import org.jetbrains.annotations.NotNull;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * 万象创世蜜脾块方块 BakedModel 包装器
 * <br/>
 * 包装 PB 的 configurable_comb 方块模型，在 getQuads 中通过 ModelData 获取
 * CombBlockBlockEntity 的 combType：
 * <ul>
 *   <li>combType = myriadcreations：返回无尽创世蜜脾块模型的 quads（彩色纹理）</li>
 *   <li>其他蜜蜂：返回 PB 原始模型的 quads（灰度纹理 + tintIndex 着色）</li>
 * </ul>
 * <p>
 * 本类不实现 PerspectiveModel（方块渲染不需要自定义 renderItem），
 * 所有非 getQuads 方法委托给原始 PB 模型。
 */
public class BakedModelMyriadCombBlockBlock implements BakedModel {

	/** 万象创世蜜蜂类型 ID */
	private static final ResourceLocation MYRIADCREATIONS_TYPE =
			ResourceLocation.fromNamespaceAndPath("productivebees", "myriadcreations");

	/** PB 原始 configurable_comb 方块模型 */
	private final BakedModel originalPbModel;

	/** 无尽创世蜜脾块方块模型 */
	private final BakedModel infinityModel;

	public BakedModelMyriadCombBlockBlock(BakedModel originalPbModel, BakedModel infinityModel) {
		this.originalPbModel = originalPbModel;
		this.infinityModel = infinityModel;
	}

	@Override
	@NotNull
	public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, @NotNull RandomSource rand) {
		return getQuads(state, side, rand, ModelData.EMPTY, null);
	}

	@Override
	@NotNull
	public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, @NotNull RandomSource rand,
									@Nullable ModelData data, @Nullable RenderType renderType) {
		ResourceLocation combType = data.get(MyriadCombModelData.COMB_TYPE);
		if (MYRIADCREATIONS_TYPE.equals(combType)) {
			return infinityModel.getQuads(state, side, rand, data, renderType);
		}
		return originalPbModel.getQuads(state, side, rand, data, renderType);
	}

	@Override
	public boolean useAmbientOcclusion() {
		return originalPbModel.useAmbientOcclusion();
	}

	@Override
	public boolean isGui3d() {
		return originalPbModel.isGui3d();
	}

	@Override
	public boolean usesBlockLight() {
		return originalPbModel.usesBlockLight();
	}

	@Override
	public boolean isCustomRenderer() {
		return false;
	}

	@Override
	@NotNull
	public TextureAtlasSprite getParticleIcon() {
		return originalPbModel.getParticleIcon();
	}

	@Override
	@NotNull
	public TextureAtlasSprite getParticleIcon(@NotNull ModelData data) {
		ResourceLocation combType = data.get(MyriadCombModelData.COMB_TYPE);
		if (MYRIADCREATIONS_TYPE.equals(combType)) {
			return infinityModel.getParticleIcon(data);
		}
		return originalPbModel.getParticleIcon(data);
	}

	@Override
	@NotNull
	public ItemOverrides getOverrides() {
		return originalPbModel.getOverrides();
	}

	@Override
	@NotNull
	public BakedModel applyTransform(@NotNull ItemDisplayContext context, @NotNull PoseStack poseStack, boolean leftFlip) {
		return originalPbModel.applyTransform(context, poseStack, leftFlip);
	}

	@Override
	@NotNull
	public List<BakedModel> getRenderPasses(@NotNull ItemStack stack, boolean fabulous) {
		return originalPbModel.getRenderPasses(stack, fabulous);
	}

	@Override
	@NotNull
	public List<RenderType> getRenderTypes(@NotNull ItemStack stack, boolean fabulous) {
		return originalPbModel.getRenderTypes(stack, fabulous);
	}
}
