package com.ayoshiko.productivebeesgenesis.multiblock.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;

/** 无纹理合金表面、透明翅膀、双层金色拖尾和蜂房／面缘发光；均保留深度测试。 */
final class MachineSceneRenderTypes extends RenderType {
	// 原版 ADDITIVE 使用 ONE/ONE，会忽略顶点 alpha；明确指定 SRC_ALPHA 保证尾端真正渐隐。
	private static final TransparencyStateShard ALPHA_ADDITIVE = new TransparencyStateShard("pbg_alpha_additive", () -> {
		RenderSystem.enableBlend(); RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
	}, () -> { RenderSystem.disableBlend(); RenderSystem.defaultBlendFunc(); });
	static final RenderType CORE = create("productivebeesgenesis_honey_core", DefaultVertexFormat.POSITION_COLOR,
			VertexFormat.Mode.QUADS, 32768, false, false, CompositeState.builder()
					.setShaderState(POSITION_COLOR_SHADER).setDepthTestState(LEQUAL_DEPTH_TEST)
					.setWriteMaskState(COLOR_DEPTH_WRITE).setCullState(NO_CULL).createCompositeState(false));
	static final RenderType TRAIL = create("productivebeesgenesis_honey_trail", DefaultVertexFormat.POSITION_COLOR,
			VertexFormat.Mode.QUADS, 32768, false, true, CompositeState.builder()
					.setShaderState(POSITION_COLOR_SHADER).setTransparencyState(TRANSLUCENT_TRANSPARENCY)
					.setDepthTestState(LEQUAL_DEPTH_TEST).setWriteMaskState(COLOR_WRITE).setCullState(NO_CULL).createCompositeState(false));
	static final RenderType GLOW = create("productivebeesgenesis_honey_edges", DefaultVertexFormat.POSITION_COLOR,
			VertexFormat.Mode.QUADS, 8192, false, false, CompositeState.builder()
					.setShaderState(POSITION_COLOR_SHADER).setTransparencyState(ALPHA_ADDITIVE)
					.setDepthTestState(LEQUAL_DEPTH_TEST).setWriteMaskState(COLOR_WRITE).setCullState(NO_CULL).createCompositeState(false));
	private MachineSceneRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int size,
			boolean crumbling, boolean sorted, Runnable setup, Runnable clear) {
		super(name, format, mode, size, crumbling, sorted, setup, clear);
	}
}
