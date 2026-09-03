package com.ayoshiko.productivebeesgenesis.config;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 配置树遍历工具：叶子路径、叶子值以及规格内的 {@link ModConfigSpec.ConfigValue} 收集。
 * <p>
 * 迁移服务与迁移资格判定都需要这些只读遍历，集中在此避免重复实现（SRP）。
 */
final class ConfigTraversal {

	private ConfigTraversal() {
	}

	/** 收集配置中所有叶子键的点分路径，保持声明顺序。 */
	static Set<String> leafPaths(Config config) {
		Set<String> result = new LinkedHashSet<>();
		collectLeafPaths(config, new ArrayList<>(), result);
		return result;
	}

	/** 收集配置中所有叶子键及其值，用于按值比较两份配置。 */
	static Map<String, Object> leafValues(Config config) {
		Map<String, Object> result = new LinkedHashMap<>();
		collectLeafValues(config, new ArrayList<>(), result);
		return result;
	}

	/** 展平规格中的全部 ConfigValue（含嵌套段）。 */
	static List<ModConfigSpec.ConfigValue<?>> configValues(ModConfigSpec spec) {
		List<ModConfigSpec.ConfigValue<?>> result = new ArrayList<>();
		collectConfigValues(spec.getValues(), result);
		return result;
	}

	/** 读取配置中某个 ConfigValue 的原始值，缺失时回退到默认值。 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	static Object readValue(ModConfigSpec.ConfigValue<?> value, Config config) {
		ModConfigSpec.ConfigValue raw = value;
		return raw.getRaw(config, value.getPath(), value::getDefault);
	}

	static String path(List<String> path) {
		return String.join(".", path);
	}

	/**
	 * 按归一化规则比较两个配置值。
	 * <p>
	 * TOML 解析与内存默认值会产生不同的数值包装类型（Integer/Long/Double）和枚举表示，
	 * 因此比较前统一归一化，避免把等价配置误判为被玩家修改过。
	 */
	static boolean sameValue(Object left, Object right) {
		return Objects.equals(normalizeValue(left), normalizeValue(right));
	}

	private static Object normalizeValue(Object value) {
		if (value instanceof Number number) {
			double asDouble = number.doubleValue();
			long asLong = number.longValue();
			return asDouble == asLong ? Long.valueOf(asLong) : Double.valueOf(asDouble);
		}
		if (value instanceof Enum<?> constant) return constant.name();
		if (value instanceof List<?> list) {
			List<Object> result = new ArrayList<>(list.size());
			for (Object element : list) result.add(normalizeValue(element));
			return result;
		}
		return value;
	}

	private static void collectLeafPaths(Config config, List<String> parent, Set<String> output) {
		for (Config.Entry entry : config.entrySet()) {
			List<String> path = new ArrayList<>(parent);
			path.add(entry.getKey());
			if (entry.getValue() instanceof Config child) collectLeafPaths(child, path, output);
			else output.add(path(path));
		}
	}

	private static void collectLeafValues(
			Config config, List<String> parent, Map<String, Object> output) {
		for (Config.Entry entry : config.entrySet()) {
			List<String> path = new ArrayList<>(parent);
			path.add(entry.getKey());
			if (entry.getValue() instanceof Config child) collectLeafValues(child, path, output);
			else output.put(path(path), entry.getValue());
		}
	}

	private static void collectConfigValues(
			UnmodifiableConfig config, List<ModConfigSpec.ConfigValue<?>> output) {
		for (UnmodifiableConfig.Entry entry : config.entrySet()) {
			Object value = entry.getValue();
			if (value instanceof ModConfigSpec.ConfigValue<?> configValue) output.add(configValue);
			else if (value instanceof UnmodifiableConfig child) collectConfigValues(child, output);
		}
	}
}
