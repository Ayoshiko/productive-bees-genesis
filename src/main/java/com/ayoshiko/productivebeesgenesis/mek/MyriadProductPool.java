package com.ayoshiko.productivebeesgenesis.mek;

import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import javax.annotation.ParametersAreNonnullByDefault;

import java.util.List;

/**
	 * 万象创世概率池 — 简化为委托 WeightedTypeSelector 选型（Task 3）
	 * <p>
	 * <b>历史设计（已废弃）</b>：v1 在 effectiveOps ≥ 1024 时启用 200-tick 固定 3 类型锁定窗口，
	 * 是 STACK 升级下多蜜脾阻塞的直接根因。v2 移除该机制，改为动态权重委托。
	 * <p>
	 * <b>当前设计</b>：
	 * <ul>
	 *   <li>effectiveOps &lt; {@link #EFFECTIVE_OPS_THRESHOLD}：原样返回入参 allTypes（保留原版语义）</li>
	 *   <li>effectiveOps ≥ 阈值：委托 {@link WeightedTypeSelector#selectWeighted} 按动态权重选型</li>
	 * </ul>
	 * <p>
	 * <b>跨进程共享优化</b>：MyriadCreationsHandler 在高 STACK 路径下直接调用
	 * {@code WeightedTypeSelector.selectWeighted(processCount × 3, ...)} 实现 19 进程共享一次选型，
	 * 绕过本类。本类保留为兼容入口与低 STACK 路径使用。
	 * <p>
	 * <b>线程安全</b>：服务端单线程执行，无需同步。factoryKey 为工厂实例弱引用。
	 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
public final class MyriadProductPool {

	/** 概率池启用阈值：effectiveOps ≥ 此值时启用概率池模式 */
	static final int EFFECTIVE_OPS_THRESHOLD = 1024;

	/** 概率池大小 — 固定 3 种蜜蜂类型，与 {@link MyriadCreationsHandler#OUTPUT_SLOT_COUNT} 一致 */
	static final int POOL_SIZE = 3;

	/** 工厂实例（作为 WeightedTypeSelector 的 WeakHashMap 弱引用 key） */
	private final Object factoryKey;

	/**
	 * 构造概率池
	 *
	 * @param factoryKey 工厂实例（作为 WeightedTypeSelector 弱引用 key，工厂卸载时自动清理缓存）
	 */
	public MyriadProductPool(Object factoryKey) {
		this.factoryKey = factoryKey;
	}

	/**
	 * 获取当前概率池，必要时委托 WeightedTypeSelector 选型
	 * <br/>
	 * 行为分支：
	 * <ul>
	 *   <li>effectiveOps &lt; {@link #EFFECTIVE_OPS_THRESHOLD}：返回 allTypes（保留原版语义）</li>
	 *   <li>effectiveOps ≥ 阈值：委托 {@link WeightedTypeSelector#selectWeighted} 选 {@link #POOL_SIZE} 种类型</li>
	 * </ul>
	 *
	 * @param allTypes     候选蜜蜂类型列表
	 * @param currentTick  当前游戏刻（保留参数兼容性，WeightedTypeSelector 内部从 level 读取）
	 * @param effectiveOps 当前 tick 的有效操作数
	 * @param level        世界实例
	 * @return 概率池类型列表（size ≤ {@link #POOL_SIZE}）；低 STACK 时原样返回 allTypes
	 */
	public List<ResourceLocation> getOrRefresh(
			List<ResourceLocation> allTypes, long currentTick, int effectiveOps, Level level) {
		if (effectiveOps < EFFECTIVE_OPS_THRESHOLD) {
			return allTypes;
		}
		if (allTypes.isEmpty()) {
			return allTypes;
		}
		return WeightedTypeSelector.getInstance().selectWeighted(
				Math.min(POOL_SIZE, allTypes.size()), level, allTypes, factoryKey);
	}
}
