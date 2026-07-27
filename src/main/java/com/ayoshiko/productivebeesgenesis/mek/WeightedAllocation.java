package com.ayoshiko.productivebeesgenesis.mek;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.ParametersAreNonnullByDefault;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.RandomHoneycombSelector;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;

import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.resources.ResourceLocation;

/**
 * 按权重比例分配产物的静态工具（Task 2）
 * <p>
 * 设计动机：替代 {@link RandomHoneycombSelector#allocateEvenly} 的均匀分配，
 * 与 {@link WeightedTypeSelector} 的动态权重策略联动 — 权重高的类型获得较多产出，
 * 权重低（近期产出少）的类型获得较少但下次权重升高被优先选中。
 * <p>
 * <b>分配算法</b>：
 * <ol>
 *   <li>归一化权重：{@code weight[i] / sum(weights)}</li>
 *   <li>初始分配：{@code floor(total × weight[i] / sum(weights))}</li>
 *   <li>余数分配：剩余数量按权重从高到低分配 1 个，直到分配完</li>
 * </ol>
 * <p>
 * 保证：所有类型分配数量 ≥ 0 且总和严格等于 {@code total}。
 * <p>
 * <b>退化安全</b>：异常时退化为 {@link RandomHoneycombSelector#allocateEvenly}，记录 WARN 日志（限流）。
 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
public final class WeightedAllocation {

	/** 降级日志冷却器（静态共享，5 秒冷却） */
	private static final LogThrottle DEGRADE_THROTTLE = new LogThrottle();

	private WeightedAllocation() {
	}

	/**
	 * 按权重比例分配总数到各类型
	 * <p>
	 * 示例：{@code total=120, types=[A,B,C], weights=[2.0, 1.0, 0.5]}
	 * <br/>
	 * 归一化权重 [0.571, 0.286, 0.143]，分配数量 [69, 34, 17]，总和 = 120。
	 *
	 * @param total   总数量
	 * @param types   蜜蜂类型列表
	 * @param weights 权重数组（长度必须等于 types.size()，元素 ≥ 0）
	 * @return 类型→数量的映射（0 分配的类型会被跳过）
	 */
	public static Map<ResourceLocation, Integer> allocateByWeight(
			int total, List<ResourceLocation> types, double[] weights) {
		Map<ResourceLocation, Integer> fallback = RandomHoneycombSelector.allocateEvenly(total, types);
		try {
			return allocateByWeightInternal(total, types, weights);
		} catch (Exception e) {
			DEGRADE_THROTTLE.tryLogMs(System.currentTimeMillis(), suppressed -> {
				ProductiveBeesGenesis.LOGGER.warn(
						"按权重分配异常，退化为均匀分配" + (suppressed > 0 ? " (抑制 " + suppressed + " 次)" : ""), e);
			});
			return fallback;
		}
	}

	private static Map<ResourceLocation, Integer> allocateByWeightInternal(
			int total, List<ResourceLocation> types, double[] weights) {
		Map<ResourceLocation, Integer> result = new HashMap<>(types.size() * 2);
		int n = types.size();
		if (n <= 0 || total <= 0) return result;
		if (n == 1) {
			result.put(types.get(0), total);
			return result;
		}
		// weights 长度不匹配或全 0 时退化为均匀分配
		if (weights == null || weights.length != n) {
			return RandomHoneycombSelector.allocateEvenly(total, types);
		}
		double sumWeights = 0.0;
		for (double w : weights) sumWeights += Math.max(0.0, w);
		if (sumWeights <= 0.0) {
			return RandomHoneycombSelector.allocateEvenly(total, types);
		}

		// 初始分配：floor(total × effectiveWeight / sumWeights)
		int[] allocated = new int[n];
		int totalAllocated = 0;
		for (int i = 0; i < n; i++) {
			double effectiveWeight = Math.max(0.0, weights[i]);
			allocated[i] = (int) Math.floor(total * effectiveWeight / sumWeights);
			totalAllocated += allocated[i];
		}

		// 余数按权重从高到低分配（每个 +1 直到分配完）
		int remainder = total - totalAllocated;
		if (remainder > 0) {
			Integer[] indices = new Integer[n];
			for (int i = 0; i < n; i++) indices[i] = i;
			// 按权重降序排序（权重相同则按索引升序，保证稳定性）
			Arrays.sort(indices, (a, b) -> {
				int cmp = Double.compare(weights[b], weights[a]);
				return cmp != 0 ? cmp : Integer.compare(a, b);
			});
			for (int i = 0; i < remainder; i++) {
				allocated[indices[i % n]]++;
			}
		}

		// 构建结果（0 分配的类型跳过，避免空 entry）
		for (int i = 0; i < n; i++) {
			if (allocated[i] > 0) {
				result.put(types.get(i), allocated[i]);
			}
		}
		return result;
	}
}
