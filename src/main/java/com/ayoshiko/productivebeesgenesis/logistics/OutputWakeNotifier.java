package com.ayoshiko.productivebeesgenesis.logistics;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * 输出「空→非空」边沿唤醒器。
 * <p>
 * <b>要解决的问题：</b>基于「睡眠/唤醒时间轮 + 失败退避」调度的物流模组（如物流网络
 * LogisticsNetworks）在连续几次抽空我们的输出槽后会把重试间隔按指数放大（该模组上限
 * 40 tick = 2 秒）。离心机工厂在这 2 秒里已经又堆满了输出槽，于是产线因「输出满」停机，
 * 表现为吞吐忽高忽低。这类模组通常监听方块更新事件（NeighborNotifyEvent）作为立即唤醒入口。
 * <p>
 * <b>做法：</b>只在输出<em>从空变为非空</em>的那一刻调用
 * {@link Level#updateNeighborsAt(BlockPos, net.minecraft.world.level.block.Block)}，
 * 并施加最小间隔节流。持续满载时输出一直非空，不会重复触发；输出一直空时也不会触发。
 * 因此正常运行下每个生产周期最多一次方块更新，开销可忽略，却能把对端退避立刻清零。
 * <p>
 * <b>为什么不用其他手段：</b>能力失效（{@code invalidateCapabilities}）会让对端丢弃
 * {@code BlockCapabilityCache} 并重新解析，代价远高于一次邻居更新，且不保证唤醒调度器。
 * <p>
 * 线程安全：每台机器一个实例，仅由服务端 tick 线程访问。
 *
 * @since 2.1.0
 */
public final class OutputWakeNotifier {

	/** 上一次观察到的「输出是否非空」状态 */
	private boolean lastHadOutput;

	/** 上次唤醒的游戏刻（用于最小间隔节流） */
	private long lastWakeTick = Long.MIN_VALUE;

	/**
	 * 每刻调用一次；仅在上升沿且通过节流时触发一次邻居更新。
	 *
	 * @param level     世界（客户端/null 直接跳过）
	 * @param pos       机器坐标
	 * @param hasOutput 当前输出槽/罐是否有内容（要求 O(1) 读取，避免遍历）
	 * @return true 表示本次真的发出了邻居更新
	 */
	public boolean onOutputStateTick(@Nullable Level level, BlockPos pos, boolean hasOutput) {
		if (level == null || level.isClientSide) return false;
		boolean rising = hasOutput && !lastHadOutput;
		lastHadOutput = hasOutput;
		if (!rising) return false;

		long gameTime = level.getGameTime();
		if (lastWakeTick != Long.MIN_VALUE
				&& gameTime - lastWakeTick < ExternalLogisticsSettings.NEIGHBOR_WAKE_MIN_INTERVAL) {
			return false;
		}
		lastWakeTick = gameTime;

		// 只做一次「本方块发生变化」的邻居通知：物流网络据此把该网络移出睡眠队列。
		// 不改变任何方块状态，因此对红石/观察者是幂等的。
		level.updateNeighborsAt(pos, level.getBlockState(pos).getBlock());
		return true;
	}
}
