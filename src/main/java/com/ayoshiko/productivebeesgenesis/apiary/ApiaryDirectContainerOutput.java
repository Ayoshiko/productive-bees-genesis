package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2PushBackoff;
import net.minecraft.world.item.ItemStack;

/**
 * 蜂箱产物直通（相邻容器）的缓冲区排空通道。
 * <p>
 * <b>为什么需要：</b>产出阶段的直通只覆盖"新产物"（见 {@code BeeProduceProcessor}），
 * 而输出槽满时剩余产物会落进 {@link ApiaryOutputBuffer}。缓冲区不在任何槽位体系内，
 * Mekanism 弹出器只看输出槽，所以缓冲内容原本必须先回注输出槽才能被送走。
 * 本通道让缓冲区物品直接进相邻容器，与「缓冲区直推 AE」完全对称
 * （见 {@code ApiaryAe2HostAdapter.pushOutputs}）。
 * <p>
 * <b>为什么单独一个类（SRP）：</b>蜂箱主类与 AE2 适配器都已接近拆分阈值，
 * 而这里只有"要不要试、失败怎么降频"两件事，独立成类便于单独调整节流策略。
 * <p>
 * <b>节流：</b>复用 {@link Ae2PushBackoff}（纯 {@code nanoTime} 单调时钟实现，
 * 不引用任何 AE2 类型，对时间加速免疫）：相邻容器塞满时从"每刻全量遍历缓冲组"
 * 降为 50ms→1s 的指数退避；任何一次成功立即复位。
 * <p>
 * <b>重入约束：</b>推送回调在缓冲区锁内执行。回调只写<b>相邻</b>方块的物品能力，
 * 不会回调本缓冲区的入队/排空方法（相邻方块的槽位变更只会触碰它自己的缓冲区），
 * 因此不存在自死锁或快照被清空的问题；这一约束与 {@code pushToAe} 完全相同。
 * <p>
 * 线程安全：仅服务端 tick 线程调用；缓冲区自身方法为 {@code synchronized}。
 */
final class ApiaryDirectContainerOutput {

	private final TileEntityMekApiary tile;

	/** 相邻容器拒收时的墙钟指数退避（50ms 起步、1s 封顶）。 */
	private final Ae2PushBackoff backoff = new Ae2PushBackoff();

	ApiaryDirectContainerOutput(TileEntityMekApiary tile) {
		this.tile = tile;
	}

	/**
	 * 尝试把缓冲区积压产物直接送进相邻容器。
	 * <br/>
	 * 待离心蜜脾（{@code shouldHoldForCentrifuge}）被 hold 过滤保留，
	 * 避免相邻箱子抢走本该进离心机的蜜脾；全部组被 hold 或真实尝试被拒时进入退避。
	 */
	void drainBuffer() {
		if (!tile.isDirectContainerOutputEnabled()) return;
		ApiaryOutputBuffer buffer = tile.getOutputBuffer();
		// O(1) 短路：缓冲区为空时不进入 synchronized 遍历
		if (buffer.getBufferedGroupCount() <= 0) return;
		long now = System.nanoTime();
		if (backoff.shouldSkip(now)) return;
		ApiaryOutputBuffer.AeBufferPushResult result =
				buffer.pushToSink(this::pushOne, tile::shouldHoldForCentrifuge);
		if (result.pushed() > 0) {
			backoff.recordSuccess();
			// 缓冲腾出空间：解除缓冲区自身的回注退避，下 tick 立刻重试注入输出槽
			buffer.resetBackoff();
		} else {
			backoff.recordFailure(now);
		}
	}

	/** 单组推送回调 — 返回相邻容器实际接收数量（0 表示完全拒收）。 */
	private int pushOne(ItemStack stack) {
		return tile.pushGeneratedItemToNeighbors(stack);
	}

	/** 配置/侧面变化后立即恢复满速重试。 */
	void clearBackoff() {
		backoff.reset();
	}
}
