package com.ayoshiko.productivebeesgenesis.client.render.cosmic;

import java.util.ArrayList;
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
 * 数据结构选型：保留 {@link CopyOnWriteArrayList} 而非改用 ArrayDeque，原因：
 * <ul>
 *   <li>队列通常很小（每帧 &lt; 10 条），写时复制 O(n) 开销可忽略</li>
 *   <li>renderAll 的快照迭代器无锁，GPU 渲染期间不阻塞 enqueue</li>
 *   <li>ArrayDeque 迭代需持锁，渲染耗时较长会阻塞入队线程</li>
 * </ul>
 * 线程安全：enqueue 与 renderAll 的快照+清空均在 synchronized(CosmicRenderQueue.class) 内原子完成；
 * 队列大小上限 {@link #MAX_QUEUE_SIZE}，超出时丢弃最旧条目并记录警告，防止内存溢出。
 */
public final class CosmicRenderQueue {

	/** 队列大小上限，防止异常时无限增长导致内存溢出 */
	private static final int MAX_QUEUE_SIZE = 256;

	/** 使用 CopyOnWriteArrayList 保证入队与渲染的并发安全（读多写少场景，快照迭代无锁） */
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
	 * <br/>
	 * 线程安全：使用 synchronized(CosmicRenderQueue.class) 包裹「检查+删除+添加」完整操作，
	 * 确保并发入队时队列大小检查与后续添加的原子性，避免多个线程同时通过上限检查导致超限。
	 *
	 * @param call	渲染调用（应通过 {@link CosmicRenderCall#obtain} 获取以复用矩阵对象）
	 */
	public static void enqueue(CosmicRenderCall call) {
		synchronized (CosmicRenderQueue.class) {
			if (QUEUE.size() >= MAX_QUEUE_SIZE) {
				ProductiveBeesGenesis.LOGGER.warn("CosmicRenderQueue 已达上限 {}，丢弃最旧条目", MAX_QUEUE_SIZE);
				QUEUE.remove(0);
			}
			QUEUE.add(call);
		}
	}

	/**
	 * 渲染队列中所有 cosmic 调用并清空队列
	 * <br/>
	 * 原子快照+清空：在 synchronized 块内复制引用并清空队列，确保快照期间入队的条目不会被
	 * 随后的 clear() 误删（修复「迭代与清空非原子」的潜在竞态）。渲染在锁外执行，避免长时间持锁阻塞入队。
	 * <br/>
	 * 使用 try-catch-finally：
	 * <ul>
	 *   <li>try：执行渲染与 endBatch</li>
	 *   <li>catch：记录异常日志，防止单帧异常导致整体崩溃</li>
	 *   <li>finally：无条件恢复矩阵与全局渲染状态（shader color/blend/depth），并将 CosmicRenderCall 归还对象池</li>
	 * </ul>
	 * 矩阵恢复必须放在 finally，否则 renderCosmicLayer 或 endBatch 抛异常时矩阵会卡在被 cosmic 修改的状态，
	 * 导致后续世界/实体渲染使用错误的投影矩阵。
	 */
	public static void renderAll() {
		// 原子快照+清空：防止「迭代期间入队 → 随后 clear 误删」的竞态
		List<CosmicRenderCall> snapshot;
		synchronized (CosmicRenderQueue.class) {
			if (QUEUE.isEmpty()) {
				return;
			}
			snapshot = new ArrayList<>(QUEUE);
			QUEUE.clear();
		}
		Minecraft mc = Minecraft.getInstance();
		MultiBufferSource.BufferSource source = mc.renderBuffers().bufferSource();
		// 保存当前矩阵状态作为快照（REUSABLE_OLD_* 为复用 Matrix4f，避免每帧分配）
		Matrix4f savedProjection = REUSABLE_OLD_PROJECTION.set((Matrix4fc) RenderSystem.getProjectionMatrix());
		Matrix4f savedModelView = REUSABLE_OLD_MODEL_VIEW.set((Matrix4fc) RenderSystem.getModelViewMatrix());
		try {
			for (CosmicRenderCall call : snapshot) {
				RenderSystem.setProjectionMatrix((Matrix4f) call.projection, (VertexSorting) RenderSystem.getVertexSorting());
				RenderSystem.getModelViewStack().set((Matrix4fc) call.modelView);
				RenderSystem.applyModelViewMatrix();
				PoseStack poseStack = new PoseStack();
				poseStack.last().pose().set((Matrix4fc) call.pose);
				poseStack.last().normal().set((Matrix3fc) call.normal);
				call.model.renderCosmicLayer(call.stack, call.context, poseStack, source, call.light, call.overlay);
			}
			source.endBatch();
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.error("CosmicRenderQueue 渲染异常", e);
		} finally {
			// 无条件恢复矩阵与渲染状态，防止状态泄漏到后续渲染阶段
			RenderSystem.setProjectionMatrix(savedProjection, (VertexSorting) RenderSystem.getVertexSorting());
			RenderSystem.getModelViewStack().set((Matrix4fc) savedModelView);
			RenderSystem.applyModelViewMatrix();
			// 重置可能被 cosmic 层修改的全局渲染状态（默认 shader color/blend/depth）
			RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
			RenderSystem.defaultBlendFunc();
			RenderSystem.enableDepthTest();
			// 归还 CosmicRenderCall 实例到对象池，复用矩阵对象避免后续入队的堆分配
			for (CosmicRenderCall call : snapshot) {
				CosmicRenderCall.release(call);
			}
		}
	}
}
