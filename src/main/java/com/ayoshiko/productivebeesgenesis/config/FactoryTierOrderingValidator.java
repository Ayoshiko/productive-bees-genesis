package com.ayoshiko.productivebeesgenesis.config;

import java.util.List;
import java.util.Optional;

/**
 * 校验工厂容量矩阵的固定顺序和并行缩放关系。
 * <p>
 * 配置数组的索引由 {@link FactoryTierKey#groupIndex()} 固定，不能通过交换元素改变等级含义。
 * 对于同一组，容量与并行进程数的比值必须保持不下降；这样允许整合包使用自己的基准值，
 * 同时避免高等级机器出现比低等级机器更小的有效堆叠容量。
 */
public final class FactoryTierOrderingValidator {

	private FactoryTierOrderingValidator() {
	}

	/**
	 * 返回配置组的校验结果。空结果表示合法，非空结果为面向日志的原因。
	 */
	public static Optional<String> validateGroup(String group, List<?> values) {
		List<FactoryTierKey> tiers = FactoryTierKey.groupTiers(group);
		if (tiers.isEmpty()) {
			return Optional.of("未知配置组 " + group);
		}
		if (values == null || values.size() != tiers.size()) {
			return Optional.of("数组长度应为 " + tiers.size() + "，实际为 "
					+ (values == null ? "null" : values.size()));
		}

		long previousValue = 0;
		int previousProcesses = 0;
		for (int index = 0; index < tiers.size(); index++) {
			Object raw = values.get(index);
			if (!(raw instanceof Number number)
					|| number.longValue() < 1
					|| number.longValue() > Integer.MAX_VALUE
					|| number.doubleValue() != number.longValue()) {
				return Optional.of("索引 " + index + " 必须是正整数");
			}
			long value = number.longValue();
			int processes = tiers.get(index).parallelProcesses();
			if (previousProcesses > 0
					&& value * previousProcesses < previousValue * processes) {
				return Optional.of("索引 " + index + " 的容量 " + value
						+ " 未随并行进程数 " + processes + " 递增");
			}
			previousValue = value;
			previousProcesses = processes;
		}
		return Optional.empty();
	}

}
