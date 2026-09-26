package com.ayoshiko.productivebeesgenesis.multiblock.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;

/** 光环只写颜色但仍测试深度，实体核心使用不透明顶点色；共用批次，不逐机 flush。 */
final class MachineSceneRenderTypes extends RenderType {
	static final RenderType CORE = create("productivebeesgenesis_machine_core", DefaultVertexFormat.POSITION_COLOR,
			VertexFormat.Mode.QUADS, 32768, false, false, CompositeState.builder()
					.setShaderState(POSITION_COLOR_SHADER).setDepthTestState(LEQUAL_DEPTH_TEST)
					.setWriteMaskState(COLOR_DEPTH_WRITE).setCullState(NO_CULL).createCompositeState(false));
	static final RenderType GLOW = create("productivebeesgenesis_machine_glow", DefaultVertexFormat.POSITION_COLOR,
			VertexFormat.Mode.QUADS, 4096, false, false, CompositeState.builder()
					.setShaderState(POSITION_COLOR_SHADER).setTransparencyState(ADDITIVE_TRANSPARENCY)
					.setDepthTestState(LEQUAL_DEPTH_TEST).setWriteMaskState(COLOR_WRITE).setCullState(NO_CULL)
					.createCompositeState(false));
	private MachineSceneRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int size,
			boolean crumbling, boolean sorted, Runnable setup, Runnable clear) {
		super(name, format, mode, size, crumbling, sorted, setup, clear);
	}
}
