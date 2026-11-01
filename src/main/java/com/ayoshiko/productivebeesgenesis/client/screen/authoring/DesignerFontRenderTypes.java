package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterShadersEvent;

import java.io.IOException;

/** 专用文字 shader 与资源重载注册；不接管 Minecraft 的其它界面。 */
@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public abstract class DesignerFontRenderTypes extends RenderType {
	private static volatile ShaderInstance distanceShader;

	private DesignerFontRenderTypes() { super("designer_font_types",DefaultVertexFormat.POSITION_TEX_COLOR,VertexFormat.Mode.QUADS,256,false,false,()->{},()->{}); }

	/** 注册编辑器字形资源，失败时由常规栅格路径保留可读界面。 */
	@SubscribeEvent
	public static void shaders(RegisterShadersEvent event) {
		distanceShader = null;
		try {
			event.registerShader(new ShaderInstance(event.getResourceProvider(),
					new ResourceLocation(ProductiveBeesGenesis.MOD_ID, "designer_text"),
					DefaultVertexFormat.POSITION_TEX_COLOR), shader -> distanceShader = shader);
		} catch (IOException failure) {
			ProductiveBeesGenesis.LOGGER.error("编辑器距离场着色器加载失败，使用栅格字体", failure);
		}
	}

	/** 字体文件随客户端资源重载，不从绘制热路径读取文件。 */
	@SubscribeEvent
	public static void reload(RegisterClientReloadListenersEvent event) {
		event.registerReloadListener(DesignerFonts.reloadListener());
	}

	static boolean available() {
		return distanceShader != null;
	}

	static RenderType create(ResourceLocation texture, boolean distance) {
		return create(texture, distance, distance);
	}

	static RenderType raster(ResourceLocation texture, boolean smooth) {
		return create(texture, false, smooth);
	}

	private static RenderType create(ResourceLocation texture, boolean distance, boolean smooth) {
		var shader = distance ? new RenderStateShard.ShaderStateShard(() -> distanceShader)
				: new RenderStateShard.ShaderStateShard(GameRenderer::getPositionTexColorShader);
		return RenderType.create("designer_text", DefaultVertexFormat.POSITION_TEX_COLOR,
				VertexFormat.Mode.QUADS, 16384, false, false, RenderType.CompositeState.builder()
						.setShaderState(shader)
						.setTextureState(new RenderStateShard.TextureStateShard(texture, smooth, false))
						.setTransparencyState(TRANSLUCENT_TRANSPARENCY)
						.setDepthTestState(LEQUAL_DEPTH_TEST)
						.setCullState(NO_CULL)
						.setWriteMaskState(COLOR_WRITE)
						.createCompositeState(false));
	}
}
