package com.ayoshiko.productivebeesgenesis.client.render.cosmic;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * Cosmic 渲染类型定义
 * <br/>
 * 定义 COSMIC 与 COSMIC_ARMOR 两套渲染类型，参数与新版 Re:Avaritia 一致。
 */
public class CosmicRenderTypes {

	/** 2MB 顶点缓冲区大小 */
	private static final int BUFFER_SIZE_2MB = 0x200000;

	public static final RenderType COSMIC = RenderType.create(
			ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, "cosmic").toString(),
			DefaultVertexFormat.NEW_ENTITY,
			VertexFormat.Mode.QUADS,
			BUFFER_SIZE_2MB,
			true,
			false,
			RenderType.CompositeState.builder()
					.setShaderState(new RenderStateShard.ShaderStateShard(() -> CosmicShaders.COSMIC_SHADER))
					.setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
					.setLightmapState(RenderStateShard.LIGHTMAP)
					.setWriteMaskState(RenderStateShard.COLOR_WRITE)
					.setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
					.setTextureState(RenderUtils.COSMIC_TEXTURE_ISOLATED)
					.setLayeringState(RenderUtils.POLYGON_OFFSET_LAYERING)
					.createCompositeState(true));

	public static final RenderType COSMIC_ARMOR = RenderType.create(
			// 名称使用 "cosmic_armor" 区别于 COSMIC，便于调试与 profiler 阶段区分两套渲染类型
			ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, "cosmic_armor").toString(),
			DefaultVertexFormat.NEW_ENTITY,
			VertexFormat.Mode.QUADS,
			BUFFER_SIZE_2MB,
			true,
			false,
			RenderType.CompositeState.builder()
					.setShaderState(new RenderStateShard.ShaderStateShard(() -> CosmicShaders.COSMIC_ARMOR_SHADER))
					.setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
					.setLightmapState(RenderStateShard.LIGHTMAP)
					.setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
					.setWriteMaskState(RenderStateShard.COLOR_WRITE)
					.setCullState(RenderStateShard.NO_CULL)
					.setLayeringState(RenderType.VIEW_OFFSET_Z_LAYERING)
					.setTextureState(RenderStateShard.BLOCK_SHEET)
					.createCompositeState(true));

	public static final RenderType HELL = RenderType.create(
			ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, "hell").toString(),
			DefaultVertexFormat.NEW_ENTITY,
			VertexFormat.Mode.QUADS,
			BUFFER_SIZE_2MB,
			true,
			false,
			RenderType.CompositeState.builder()
					.setShaderState(new RenderStateShard.ShaderStateShard(() -> CosmicShaders.HELL_SHADER))
					.setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
					.setLightmapState(RenderStateShard.LIGHTMAP)
					.setWriteMaskState(RenderStateShard.COLOR_WRITE)
					.setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
					.setTextureState(RenderUtils.COSMIC_TEXTURE_ISOLATED)
					.setLayeringState(RenderUtils.POLYGON_OFFSET_LAYERING)
					.createCompositeState(true));
}
