package com.ayoshiko.productivebeesgenesis.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * 一组按 {@link FactoryTierKey} 索引的整数配置值。
 * <p>
 * 负责统一注册键名、翻译键、取值范围和可选 EM 等级，调用方只需提供各等级默认值。
 */
public final class FactoryTierConfigValues {

	private final Map<String, ModConfigSpec.ConfigValue<List<? extends Integer>>> values;
	private final ToIntFunction<FactoryTierKey> defaults;

	private FactoryTierConfigValues(
			Map<String, ModConfigSpec.ConfigValue<List<? extends Integer>>> values,
			ToIntFunction<FactoryTierKey> defaults) {
		this.values = Collections.unmodifiableMap(values);
		this.defaults = defaults;
	}

	/**
	 * 注册一组工厂等级配置。
	 *
	 * @param builder NeoForge 配置构建器
	 * @param translationPrefix 翻译键前缀
	 * @param defaults 各等级默认值提供器
	 * @return 已注册的等级配置集合
	 */
	public static FactoryTierConfigValues register(
			ModConfigSpec.Builder builder,
			String translationPrefix,
			ToIntFunction<FactoryTierKey> defaults) {
		Map<String, ModConfigSpec.ConfigValue<List<? extends Integer>>> values =
				new LinkedHashMap<>();
		for (String group : FactoryTierKey.configGroups()) {
			List<FactoryTierKey> tiers = FactoryTierKey.groupTiers(group);
			List<Integer> groupDefaults = tiers.stream()
					.map(defaults::applyAsInt)
					.toList();
			values.put(group, builder
					.translation(translationPrefix + "." + group)
					.defineList(
							List.of(group),
							() -> groupDefaults,
							() -> 1,
							FactoryTierConfigValues::isValidMultiplier,
							ModConfigSpec.Range.of(tiers.size(), tiers.size())));
		}
		return new FactoryTierConfigValues(values, defaults);
	}

	static boolean isValidMultiplier(Object value) {
		return value instanceof Integer integer && integer >= 1;
	}

	/**
	 * 读取指定等级的当前值。未注册的可选等级回退到其默认值，避免可选依赖缺失时出现空指针。
	 *
	 * @param tier 工厂等级
	 * @return 当前配置值或默认值
	 */
	public int get(FactoryTierKey tier) {
		ModConfigSpec.ConfigValue<List<? extends Integer>> value = values.get(tier.configGroup());
		if (value == null) return defaults.applyAsInt(tier);
		List<? extends Integer> groupValues = value.get();
		if (groupValues == null || tier.groupIndex() >= groupValues.size()) {
			return defaults.applyAsInt(tier);
		}
		Integer configured = groupValues.get(tier.groupIndex());
		return configured == null || configured < 1 ? defaults.applyAsInt(tier) : configured;
	}

	/**
	 * 返回该配置组需要提供的全部翻译键，包含可选兼容等级。
	 *
	 * @param translationPrefix 翻译键前缀
	 * @return 稳定顺序的翻译键集合
	 */
	public static Set<String> translationKeys(String translationPrefix) {
		Set<String> keys = new LinkedHashSet<>();
		for (String group : FactoryTierKey.configGroups()) {
			keys.add(translationPrefix + "." + group);
		}
		return Collections.unmodifiableSet(keys);
	}
}
