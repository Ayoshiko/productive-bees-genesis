package com.ayoshiko.productivebeesgenesis.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/** 预览与晚绘制的局部状态保管；所有采集和恢复都在渲染线程执行。 */
public final class RenderStateSnapshot implements AutoCloseable {
	private final Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
	private final Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix());
	private final Matrix4f textureMatrix = new Matrix4f(RenderSystem.getTextureMatrix());
	private final VertexSorting sorting = RenderSystem.getVertexSorting();
	private final ShaderInstance shader = RenderSystem.getShader();
	private final float[] shaderColor = RenderSystem.getShaderColor().clone();
	private final int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
	private final int[] shaderTextures = new int[3];
	private final int[] textureBindings = new int[3];
	private final boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
	private final boolean depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
	private final int depthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
	private final boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
	private final int blendSourceRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
	private final int blendDestRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
	private final int blendSourceAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
	private final int blendDestAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
	private final int blendEquationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
	private final int blendEquationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
	private final boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
	private final boolean polygonOffset = GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL);
	private final float polygonFactor = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_FACTOR);
	private final float polygonUnits = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_UNITS);
	private final boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
	private final int[] scissorBox = new int[4];
	private final int[] viewport = new int[4];
	private final int[] colorWrite = new int[4];
	private final int drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
	private final int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);

	public RenderStateSnapshot() {
		RenderSystem.assertOnRenderThread();
		GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox);
		GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
		GL11.glGetIntegerv(GL11.GL_COLOR_WRITEMASK, colorWrite);
		// 当前两类消费者只使用实体纹理、overlay 和 lightmap 三个纹理单元。
		for (int unit = 0; unit < textureBindings.length; unit++) {
			shaderTextures[unit] = RenderSystem.getShaderTexture(unit);
			RenderSystem.activeTexture(GL13.GL_TEXTURE0 + unit);
			textureBindings[unit] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		}
		RenderSystem.activeTexture(activeTexture);
	}

	@Override
	public void close() {
		BufferUploader.reset();
		ShaderInstance current = RenderSystem.getShader();
		if (current != null) {
			current.clear();
		}
		RenderSystem.setProjectionMatrix(projection, sorting);
		RenderSystem.getModelViewStack().set(modelView);
		RenderSystem.applyModelViewMatrix();
		RenderSystem.setTextureMatrix(textureMatrix);
		RenderSystem.setShaderColor(shaderColor[0], shaderColor[1], shaderColor[2], shaderColor[3]);
		for (int unit = 0; unit < shaderTextures.length; unit++) {
			RenderSystem.setShaderTexture(unit, shaderTextures[unit]);
		}
		RenderSystem.setShader(() -> shader);
		if (shader != null) {
			shader.apply();
		}
		for (int unit = 0; unit < textureBindings.length; unit++) {
			RenderSystem.activeTexture(GL13.GL_TEXTURE0 + unit);
			RenderSystem.bindTexture(textureBindings[unit]);
		}
		RenderSystem.activeTexture(activeTexture);
		GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
		GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
		RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
		RenderSystem.depthFunc(depthFunction);
		RenderSystem.depthMask(depthWrite);
		if (depth) {
			RenderSystem.enableDepthTest();
		} else {
			RenderSystem.disableDepthTest();
		}
		RenderSystem.blendFuncSeparate(blendSourceRgb, blendDestRgb, blendSourceAlpha, blendDestAlpha);
		GL20.glBlendEquationSeparate(blendEquationRgb, blendEquationAlpha);
		if (blend) {
			RenderSystem.enableBlend();
		} else {
			RenderSystem.disableBlend();
		}
		if (cull) {
			RenderSystem.enableCull();
		} else {
			RenderSystem.disableCull();
		}
		RenderSystem.polygonOffset(polygonFactor, polygonUnits);
		if (polygonOffset) {
			RenderSystem.enablePolygonOffset();
		} else {
			RenderSystem.disablePolygonOffset();
		}
		RenderSystem.colorMask(colorWrite[0] != 0, colorWrite[1] != 0, colorWrite[2] != 0, colorWrite[3] != 0);
		if (scissor) {
			RenderSystem.enableScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
		} else {
			RenderSystem.disableScissor();
		}
	}
}
