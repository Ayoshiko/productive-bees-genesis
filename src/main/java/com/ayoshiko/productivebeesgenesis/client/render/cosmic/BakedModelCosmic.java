package com.ayoshiko.productivebeesgenesis.client.render.cosmic;

import java.util.List;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;

/**
 * Cosmic 烘焙模型
 * <br/>
 * 继承 {@link AbstractBakedModelCosmic}，仅提供 cosmic 着色器 uniform 与 {@link CosmicRenderTypes#COSMIC} 渲染类型。
 * 公共渲染流程由基类统一管理。
 */
public class BakedModelCosmic extends AbstractBakedModelCosmic {

	public BakedModelCosmic(BakedModel wrapped, List<ResourceLocation> maskSprites) {
		super(wrapped, maskSprites);
	}

	@Override
	protected void setupShaderUniforms(float time, float yaw, float pitch, float scale) {
		CosmicShaders.cosmicTime.set(time);
		CosmicShaders.cosmicYaw.set(yaw);
		CosmicShaders.cosmicPitch.set(pitch);
		CosmicShaders.cosmicExternalScale.set(scale);
		CosmicShaders.cosmicOpacity.set(1.0F);
		if (CosmicShaders.cosmicUVs != null) {
			CosmicShaders.cosmicUVs.set(CosmicShaders.getCosmicUvs());
		}
	}

	@Override
	protected RenderType getRenderType() {
		return CosmicRenderTypes.COSMIC;
	}

	@Override
	protected void endBatch(MultiBufferSource.BufferSource bufferSource) {
		bufferSource.endBatch(CosmicRenderTypes.COSMIC);
	}
}
