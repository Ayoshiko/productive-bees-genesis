package com.ayoshiko.productivebeesgenesis.client.render.cosmic;

import java.util.ArrayDeque;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3f;
import org.joml.Matrix3fc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Iris 延迟渲染调用快照
 * <br/>
 * 保存一次 cosmic 光晕渲染所需的模型、物品栈、展示上下文、光照/覆盖层以及 pose/normal/projection/modelView 矩阵。
 * <p>
 * 性能优化：使用对象池避免每次入队分配 4 个 Matrix 对象（3 个 Matrix4f + 1 个 Matrix3f）。
 * obtain() 从池中获取或新建实例，release() 清空引用后归还池中复用。
 * 矩阵对象为 final 预分配字段，通过 set() 覆写内容而非重新分配。
 * <p>
 * 线程安全：入队（物品渲染）与消费（世界渲染）均在客户端渲染线程执行，
 * 池的 synchronized 为防御性保护，无实际争用开销（无竞争锁极快）。
 */
public class CosmicRenderCall {

	/** 对象池大小上限，防止异常场景下无限增长 */
	private static final int MAX_POOL_SIZE = 64;
	/** 对象池 — 复用 CosmicRenderCall 实例避免矩阵对象重复分配 */
	private static final ArrayDeque<CosmicRenderCall> POOL = new ArrayDeque<>();

	public CosmicRenderable model;
	public ItemStack stack;
	public ItemDisplayContext context;
	public int light;
	public int overlay;
	/** 预分配的 pose 矩阵 — 通过 set() 覆写，避免每次入队 new Matrix4f */
	public final Matrix4f pose = new Matrix4f();
	/** 预分配的 normal 矩阵 — 通过 set() 覆写 */
	public final Matrix3f normal = new Matrix3f();
	/** 预分配的 projection 矩阵 — 通过 set() 覆写 */
	public final Matrix4f projection = new Matrix4f();
	/** 预分配的 modelView 矩阵 — 通过 set() 覆写 */
	public final Matrix4f modelView = new Matrix4f();

	private CosmicRenderCall() {
	}

	/**
	 * 从池中获取实例并填充快照数据
	 * <br/>
	 * 池为空时新建实例。矩阵通过 set() 覆写预分配字段，避免堆分配。
	 *
	 * @param model			cosmic 渲染模型
	 * @param stack			物品栈
	 * @param context		展示上下文
	 * @param poseStack		当前 PoseStack（取 last() 快照）
	 * @param light			光照
	 * @param overlay		覆盖层
	 * @param projection	投影矩阵快照
	 * @param modelView		模型视图矩阵快照
	 * @return 已填充的 CosmicRenderCall 实例
	 */
	public static CosmicRenderCall obtain(CosmicRenderable model, ItemStack stack, ItemDisplayContext context,
			PoseStack poseStack, int light, int overlay,
			Matrix4f projection, Matrix4f modelView) {
		CosmicRenderCall call;
		synchronized (POOL) {
			call = POOL.poll();
		}
		if (call == null) {
			call = new CosmicRenderCall();
		}
		call.model = model;
		call.stack = stack;
		call.context = context;
		call.light = light;
		call.overlay = overlay;
		PoseStack.Pose pose = poseStack.last();
		call.pose.set((Matrix4fc) pose.pose());
		call.normal.set((Matrix3fc) pose.normal());
		call.projection.set((Matrix4fc) projection);
		call.modelView.set((Matrix4fc) modelView);
		return call;
	}

	/**
	 * 清空引用并归还到池中复用
	 * <br/>
	 * 必须在渲染消费完毕后调用，否则实例无法复用。
	 * 清空 model/stack/context 引用避免内存泄漏（防止池中持有已失效对象引用）。
	 * 矩阵对象保留不清空（下次 obtain 会 set() 覆写）。
	 *
	 * @param call 已消费完毕的实例
	 */
	public static void release(CosmicRenderCall call) {
		call.model = null;
		call.stack = null;
		call.context = null;
		synchronized (POOL) {
			if (POOL.size() < MAX_POOL_SIZE) {
				POOL.offer(call);
			}
		}
	}
}
