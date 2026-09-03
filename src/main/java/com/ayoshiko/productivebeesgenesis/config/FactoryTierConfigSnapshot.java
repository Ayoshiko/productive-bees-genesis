package com.ayoshiko.productivebeesgenesis.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.EnumMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToIntFunction;

/** 工厂等级倍率的不可变运行时快照。 */
public final class FactoryTierConfigSnapshot {

	private static final Logger LOGGER = LoggerFactory.getLogger(
			"ProductiveBeesGenesis/FactoryTierConfig");

	private static final FactoryTierConfigSnapshot DEFAULTS = create(
			FactoryTierKey::centrifugeOutputStackDefault,
			FactoryTierKey::centrifugeInputStackDefault,
			FactoryTierKey::centrifugeFluidTankDefault,
			FactoryTierKey::apiaryOutputStackDefault);

	private final Map<FactoryTierKey, Integer> centrifugeOutputStack;
	private final Map<FactoryTierKey, Integer> centrifugeInputStack;
	private final Map<FactoryTierKey, Integer> centrifugeFluidTank;
	private final Map<FactoryTierKey, Integer> apiaryOutputStack;

	private FactoryTierConfigSnapshot(
			Map<FactoryTierKey, Integer> centrifugeOutputStack,
			Map<FactoryTierKey, Integer> centrifugeInputStack,
			Map<FactoryTierKey, Integer> centrifugeFluidTank,
			Map<FactoryTierKey, Integer> apiaryOutputStack) {
		this.centrifugeOutputStack = centrifugeOutputStack;
		this.centrifugeInputStack = centrifugeInputStack;
		this.centrifugeFluidTank = centrifugeFluidTank;
		this.apiaryOutputStack = apiaryOutputStack;
	}

	static FactoryTierConfigSnapshot from(ServerConfig config) {
		Objects.requireNonNull(config, "config");
		return create(
				config.centrifuge().stackMultiplier.outputStack::get,
				config.centrifuge().stackMultiplier.inputStack::get,
				config.centrifuge().fluidTankMultiplier.values::get,
				config.apiary().stackMultiplier::get);
	}

	static FactoryTierConfigSnapshot create(
			ToIntFunction<FactoryTierKey> centrifugeOutputStack,
			ToIntFunction<FactoryTierKey> centrifugeInputStack,
			ToIntFunction<FactoryTierKey> centrifugeFluidTank,
			ToIntFunction<FactoryTierKey> apiaryOutputStack) {
		return new FactoryTierConfigSnapshot(
				readGroup("centrifuge output stack", centrifugeOutputStack,
						FactoryTierKey::centrifugeOutputStackDefault),
				readGroup("centrifuge input stack", centrifugeInputStack,
						FactoryTierKey::centrifugeInputStackDefault),
				readGroup("centrifuge fluid tank", centrifugeFluidTank,
						FactoryTierKey::centrifugeFluidTankDefault),
				readGroup("apiary output stack", apiaryOutputStack,
						FactoryTierKey::apiaryOutputStackDefault));
	}

	static FactoryTierConfigSnapshot defaults() {
		return DEFAULTS;
	}

	private static Map<FactoryTierKey, Integer> readGroup(
			String groupName,
			ToIntFunction<FactoryTierKey> source,
			ToIntFunction<FactoryTierKey> defaultSource) {
		Objects.requireNonNull(source, groupName);
		Objects.requireNonNull(defaultSource, groupName + " defaults");
		EnumMap<FactoryTierKey, Integer> values = new EnumMap<>(FactoryTierKey.class);
		for (FactoryTierKey tier : FactoryTierKey.values()) {
			int value = source.applyAsInt(tier);
			if (value < 1) {
				throw new IllegalArgumentException(
						groupName + "." + tier.configKey() + " must be positive: " + value);
			}
			values.put(tier, value);
		}
		for (String group : FactoryTierKey.configGroups()) {
			List<Integer> groupValues = new ArrayList<>();
			for (FactoryTierKey tier : FactoryTierKey.groupTiers(group)) {
				groupValues.add(values.get(tier));
			}
			FactoryTierOrderingValidator.validateGroup(group, groupValues).ifPresent(reason -> {
				LOGGER.warn(
						"{} 配置组的固定顺序/并行缩放校验未通过：{}；"
								+ "仅本次运行回退该组默认值，原配置文件保持不变",
						groupName + "." + group, reason);
				for (FactoryTierKey tier : FactoryTierKey.groupTiers(group)) {
					int fallback = defaultSource.applyAsInt(tier);
					if (fallback < 1) {
						throw new IllegalArgumentException(
								groupName + " default for " + tier.configKey()
										+ " must be positive: " + fallback);
					}
					values.put(tier, fallback);
				}
			});
		}
		return Collections.unmodifiableMap(values);
	}

	public int centrifugeOutputStack(FactoryTierKey tier) {
		return centrifugeOutputStack.get(Objects.requireNonNull(tier, "tier"));
	}

	public int centrifugeInputStack(FactoryTierKey tier) {
		return centrifugeInputStack.get(Objects.requireNonNull(tier, "tier"));
	}

	public int centrifugeFluidTank(FactoryTierKey tier) {
		return centrifugeFluidTank.get(Objects.requireNonNull(tier, "tier"));
	}

	public int apiaryOutputStack(FactoryTierKey tier) {
		return apiaryOutputStack.get(Objects.requireNonNull(tier, "tier"));
	}
}
