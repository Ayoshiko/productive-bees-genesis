package com.ayoshiko.productivebeesgenesis.client.render.cosmic;

import java.util.List;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;

/**
 * Hell 烘焙模型
 * <br/>
 * 继承 {@link AbstractBakedModelCosmic}，仅提供 hell 着色器 uniform 与 {@link CosmicRenderTypes#HELL} 渲染类型。
 * 公共渲染流程由基类统一管理，红色地狱星空效果由 hell 着色器实现。
 */
public class BakedModelHell extends AbstractBakedModelCosmic {

	public BakedModelHell(BakedModel wrapped, List<ResourceLocation> maskSprites) {
		super(wrapped, maskSprites);
	}

	@Override
	protected void setupShaderUniforms(float time, float yaw, float pitch, float scale) {
		CosmicShaders.hellTime.set(time);
		CosmicShaders.hellYaw.set(yaw);
		CosmicShaders.hellPitch.set(pitch);
		CosmicShaders.hellExternalScale.set(scale);
		CosmicShaders.hellOpacity.set(1.0F);
		if (CosmicShaders.hellUVs != null) {
			CosmicShaders.hellUVs.set(CosmicShaders.getCosmicUvs());
		}
	}

	@Override
	protected RenderType getRenderType() {
		return CosmicRenderTypes.HELL;
	}

	@Override
	protected void endBatch(MultiBufferSource.BufferSource bufferSource) {
		bufferSource.endBatch(CosmicRenderTypes.HELL);
	}
}
