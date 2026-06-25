package com.ayoshiko.productivebeesgenesis.client.render.cosmic;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix3fc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Iris 延迟渲染队列
 * <br/>
 * 当 Iris 启用光影包时，手持视角的 cosmic 光晕被入队，在 RenderLevelStageEvent.AFTER_LEVEL 阶段统一恢复矩阵后执行。
 * <br/>
 * 线程安全：使用 {@link CopyOnWriteArrayList} 保证入队与渲染的并发安全；
 * 队列大小上限 {@link #MAX_QUEUE_SIZE}，超出时丢弃最旧条目并记录警告，防止内存溢出。
 */
public final class CosmicRenderQueue {

	/** 队列大小上限，防止异常时无限增长导致内存溢出 */
	private static final int MAX_QUEUE_SIZE = 256;

	/** 使用 CopyOnWriteArrayList 保证入队（物品渲染线程）与渲染（世界渲染线程）的并发安全 */
	private static final List<CosmicRenderCall> QUEUE = new CopyOnWriteArrayList<>();

	/** 渲染线程复用的投影矩阵快照，避免每帧分配 Matrix4f */
	private static final Matrix4f REUSABLE_OLD_PROJECTION = new Matrix4f();
	/** 渲染线程复用的模型视图矩阵快照，避免每帧分配 Matrix4f */
	private static final Matrix4f REUSABLE_OLD_MODEL_VIEW = new Matrix4f();

	private CosmicRenderQueue() {
	}

	/**
	 * 入队一个 cosmic 渲染调用
	 * <br/>
	 * 当队列已满时丢弃最旧条目并记录警告，防止内存溢出。
	 *
	 * @param call	渲染调用
	 */
	public static void enqueue(CosmicRenderCall call) {
		if (QUEUE.size() >= MAX_QUEUE_SIZE) {
			ProductiveBeesGenesis.LOGGER.warn("CosmicRenderQueue 已达上限 {}，丢弃最旧条目", MAX_QUEUE_SIZE);
			QUEUE.remove(0);
		}
		QUEUE.add(call);
	}

	/**
	 * 渲染队列中所有 cosmic 调用并清空队列
	 * <br/>
	 * 使用 try-finally 确保队列必被清空，防止异常时旧快照堆积。
	 */
	public static void renderAll() {
		if (QUEUE.isEmpty()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		MultiBufferSource.BufferSource source = mc.renderBuffers().bufferSource();
		Matrix4f oldProjection = REUSABLE_OLD_PROJECTION.set((Matrix4fc) RenderSystem.getProjectionMatrix());
		Matrix4f oldModelView = REUSABLE_OLD_MODEL_VIEW.set((Matrix4fc) RenderSystem.getModelViewMatrix());
		try {
			for (CosmicRenderCall call : QUEUE) {
				RenderSystem.setProjectionMatrix((Matrix4f) call.projection, (VertexSorting) RenderSystem.getVertexSorting());
				RenderSystem.getModelViewStack().set((Matrix4fc) call.modelView);
				RenderSystem.applyModelViewMatrix();
				PoseStack poseStack = new PoseStack();
				poseStack.last().pose().set((Matrix4fc) call.pose);
				poseStack.last().normal().set((Matrix3fc) call.normal);
				call.model.renderCosmicLayer(call.stack, call.context, poseStack, source, call.light, call.overlay);
			}
			source.endBatch();
			RenderSystem.setProjectionMatrix((Matrix4f) oldProjection, (VertexSorting) RenderSystem.getVertexSorting());
			RenderSystem.getModelViewStack().set((Matrix4fc) oldModelView);
			RenderSystem.applyModelViewMatrix();
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.error("CosmicRenderQueue 渲染异常", e);
		} finally {
			QUEUE.clear();
		}
	}
}
