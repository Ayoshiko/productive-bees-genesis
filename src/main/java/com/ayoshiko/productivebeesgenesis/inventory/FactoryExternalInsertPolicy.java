package com.ayoshiko.productivebeesgenesis.inventory;

import mekanism.api.Action;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.world.item.ItemStack;

import java.util.function.IntSupplier;

/**
 * 工厂输入槽的外部插入策略：限制单个槽位的<b>缓冲深度</b>，但不限制可用车道。
 * <p>
 * <b>为什么需要策略：</b>输入槽的真实上限是 {@code 64 × 输入堆叠倍率}（最高等级可达数百万），
 * 若原样暴露给外部自动化，AE2 外部存储正面/物流模组的第一次请求就会把整条产线的物料全部
 * 搬进机器内部，既看不见又难取回。策略把「外部一次能填多深」压到约 4 个真实刻的消耗量
 * （{@link #recommendedWorkingSet}），机器照常满速运行，多余物料留在网络里。
 * <p>
 * <b>为什么<em>不能</em>限制车道（历史实现的问题）：</b>旧实现按「每种物品每游戏刻只允许一个
 * 输入槽接收」做准入，其它槽位一律返回 0。这对异步规划型物流模组是灾难：
 * 它们先在<b>快照</b>上规划（快照里每个空槽都能收 64 个），再在主线程逐条提交；
 * 提交时被我们拒绝就会出现「已提交 &lt; 已规划」，触发对端的同步补救路径
 * （重新抓取整网络 + 全量同步搬运，是其最贵的代码路径），每次操作都白烧一遍 MSPT。
 * 同时旧实现持有 {@code HashMap} + 非原子的 tick 判定，会被这类模组的读取时序放大成竞态。
 * <p>
 * 现在的实现是<b>无状态纯函数</b>：模拟与执行必然一致，也不存在并发问题。
 * 内部机器互转（蜂箱直连、AE2 拉取）走 {@code AutomationType.INTERNAL}，本策略完全不介入。
 */
public final class FactoryExternalInsertPolicy implements ExternalInsertPolicy {

	/** 最小工作集：至少保证一个原版堆叠，避免物流模组按 64 规划却只被接收一部分 */
	static final int MIN_WORKING_SET = 64;

	/** 工作集覆盖的真实刻数 */
	static final int BUFFER_TICKS = 4;

	/** 工作集供应商（按当前操作数 × 加速倍率 × 产量并行度计算） */
	private final IntSupplier workingSetSupplier;

	/**
	 * @param workingSetSupplier 单槽缓冲深度供应商，通常传
	 *                           {@link #recommendedWorkingSet(int, int, int)} 的惰性计算
	 */
	public FactoryExternalInsertPolicy(IntSupplier workingSetSupplier) {
		this.workingSetSupplier = workingSetSupplier;
	}

	/**
	 * 把本策略挂到一个输入槽上。
	 *
	 * @param slot 工厂输入槽
	 */
	public void register(BasicInventorySlot slot) {
		((TieredInputSlot) slot).productivebeesgenesis$setExternalInsertPolicy(this);
	}

	@Override
	public int getInsertLimit(BasicInventorySlot slot, ItemStack stack, int normalLimit,
			Action action) {
		if (normalLimit <= 0 || stack.isEmpty()) return 0;
		long limit = effectiveSlotLimit(normalLimit, slot.getStack().getCount(),
				workingSetSupplier.getAsInt());
		return (int) Math.min(Integer.MAX_VALUE, limit);
	}

	/**
	 * 计算本次外部插入可见的槽位上限。
	 * <p>
	 * 取「工作集」与「槽位真实上限」的较小值；若槽内现有数量已超过该值（机器内部写入不受策略约束），
	 * 则返回现有数量，使外部插入自然得到 0 空间，而不是出现负数空间或缩容语义。
	 *
	 * @param normalLimit  槽位真实上限（{@code 64 × 倍率}）
	 * @param currentCount 槽内现有数量
	 * @param workingSet   期望的缓冲深度
	 * @return 本次外部插入使用的上限
	 */
	static long effectiveSlotLimit(int normalLimit, int currentCount, int workingSet) {
		long cap = Math.min(Math.max(0, normalLimit), Math.max(MIN_WORKING_SET, workingSet));
		return Math.max(cap, Math.max(0, currentCount));
	}

	/**
	 * 四个真实刻的消耗量：既能喂饱被加速的车道，又不会把整个待处理量暴露给外部存储。
	 *
	 * @param operationsPerTick      每刻操作数
	 * @param accelerationMultiplier 时间加速批量倍率
	 * @param productivityParallel   产量升级并行度
	 * @return 建议的单槽缓冲深度（>= {@value #MIN_WORKING_SET}）
	 */
	public static int recommendedWorkingSet(int operationsPerTick, int accelerationMultiplier,
			int productivityParallel) {
		long operations = Math.max(1, operationsPerTick);
		long acceleration = Math.max(1, accelerationMultiplier);
		long productivity = Math.max(1, productivityParallel);
		long demand = saturatedMultiply(operations, acceleration);
		demand = saturatedMultiply(demand, productivity);
		long buffered = saturatedMultiply(demand, BUFFER_TICKS);
		return (int) Math.min(Integer.MAX_VALUE, Math.max(MIN_WORKING_SET, buffered));
	}

	private static long saturatedMultiply(long left, long right) {
		if (left > Long.MAX_VALUE / right) return Long.MAX_VALUE;
		return left * right;
	}
}
