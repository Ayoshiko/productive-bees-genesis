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
 * <p>
 * 复用对象线程安全：原 static 复用的 {@link Matrix4f}/{@link PoseStack}/{@link List} 在 Iris 并行渲染时
 * 可能被多个渲染线程并发访问，导致矩阵数据损坏。改为 {@link ThreadLocal}，每线程持有独立实例，
 * 既避免每帧分配又保证线程隔离。线程销毁时 ThreadLocal 自动清理，无内存泄漏风险。
 */
public final class CosmicRenderQueue {

	/** 队列大小上限，防止异常时无限增长导致内存溢出 */
	private static final int MAX_QUEUE_SIZE = 256;

	/** 使用 CopyOnWriteArrayList 保证入队与渲染的并发安全（读多写少场景，快照迭代无锁） */
	private static final List<CosmicRenderCall> QUEUE = new CopyOnWriteArrayList<>();

	/** 渲染线程复用的投影矩阵快照（ThreadLocal 隔离 Iris 并行渲染线程） */
	private static final ThreadLocal<Matrix4f> REUSABLE_OLD_PROJECTION = ThreadLocal.withInitial(Matrix4f::new);
	/** 渲染线程复用的模型视图矩阵快照（ThreadLocal 隔离 Iris 并行渲染线程） */
	private static final ThreadLocal<Matrix4f> REUSABLE_OLD_MODEL_VIEW = ThreadLocal.withInitial(Matrix4f::new);

	/** 渲染线程复用的快照列表（ThreadLocal 隔离 Iris 并行渲染线程） */
	private static final ThreadLocal<List<CosmicRenderCall>> RENDER_SNAPSHOT = ThreadLocal.withInitial(ArrayList::new);
	/** 渲染线程复用的 PoseStack（ThreadLocal 隔离 Iris 并行渲染线程；push/pop 保证栈状态隔离） */
	private static final ThreadLocal<PoseStack> REUSABLE_POSE_STACK = ThreadLocal.withInitial(PoseStack::new);

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
				// 修复对象池泄漏：被丢弃的条目必须 release() 归还对象池并清空 model/stack/context 引用。
				// 原实现仅 remove(0) 丢弃引用，未调用 release()：
				//   1) 持续高压下池（上限 64）被耗尽，新实例不断 new，违背对象池复用初衷；
				//   2) 丢弃实例仍持有 ItemStack/model 引用直到 GC，存在短期内存压力。
				CosmicRenderCall discarded = QUEUE.remove(0);
				if (discarded != null) {
					CosmicRenderCall.release(discarded);
				}
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
	 *   <li>finally：无条件恢复矩阵与全局渲染状态（shader color/blend/depth/polygon offset），并将 CosmicRenderCall 归还对象池</li>
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
			List<CosmicRenderCall> localSnapshot = RENDER_SNAPSHOT.get();
			localSnapshot.clear();
			localSnapshot.addAll(QUEUE);
			QUEUE.clear();
			snapshot = localSnapshot;
		}
		Minecraft mc = Minecraft.getInstance();
		MultiBufferSource.BufferSource source = mc.renderBuffers().bufferSource();
		// 保存当前矩阵状态作为快照（ThreadLocal 复用 Matrix4f，避免每帧分配且隔离 Iris 并行渲染线程）
		Matrix4f savedProjection = REUSABLE_OLD_PROJECTION.get().set((Matrix4fc) RenderSystem.getProjectionMatrix());
		Matrix4f savedModelView = REUSABLE_OLD_MODEL_VIEW.get().set((Matrix4fc) RenderSystem.getModelViewMatrix());
		PoseStack localPoseStack = REUSABLE_POSE_STACK.get();
		try {
			for (CosmicRenderCall call : snapshot) {
				RenderSystem.setProjectionMatrix((Matrix4f) call.projection, (VertexSorting) RenderSystem.getVertexSorting());
				RenderSystem.getModelViewStack().set((Matrix4fc) call.modelView);
				RenderSystem.applyModelViewMatrix();
				// 复用 ThreadLocal PoseStack 避免每次循环分配；push/pop 隔离状态，try/finally 保证异常时栈平衡
				localPoseStack.pushPose();
				try {
					localPoseStack.last().pose().set((Matrix4fc) call.pose);
					localPoseStack.last().normal().set((Matrix3fc) call.normal);
					call.model.renderCosmicLayer(call.stack, call.context, localPoseStack, source, call.light, call.overlay);
				} finally {
					localPoseStack.popPose();
				}
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
			// 修复 polygon offset 状态泄漏：COSMIC 渲染类型的 begin 回调会启用 POLYGON_OFFSET_LAYERING
			// （见 RenderUtils.POLYGON_OFFSET_LAYERING：RenderSystem.polygonOffset(-1.0F, -10.0F) + enablePolygonOffset()）。
			// 正常流程下 endBatch() 会触发 RenderType 的 end 回调关闭 polygon offset；
			// 但若 renderCosmicLayer 抛异常，endBatch() 不执行，end 回调不触发，GL_POLYGON_OFFSET 将保持启用，
			// 导致后续世界/方块渲染出现深度偏移异常。finally 块无条件重置，确保 GL 状态清洁。
			RenderSystem.disablePolygonOffset();
			RenderSystem.polygonOffset(0.0F, 0.0F);
			// 归还 CosmicRenderCall 实例到对象池，复用矩阵对象避免后续入队的堆分配
			for (CosmicRenderCall call : snapshot) {
				CosmicRenderCall.release(call);
			}
		}
	}
}
