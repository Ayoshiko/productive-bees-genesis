package com.ayoshiko.productivebeesgenesis.config;

import java.util.List;

/**
 * 工厂等级配置键。
 * <p>
 * 集中维护各兼容模组的稳定配置键、默认容量倍率和 ordinal 映射，避免配置定义与运行时解析
 * 分别维护同一套等级表。枚举顺序不参与持久化，TOML 始终使用 {@link #configKey()}。
 */
public enum FactoryTierKey {
	BASIC("basic", "mekanism", 0, false, 3, 65_536, 16_384, 1, 1),
	ADVANCED("advanced", "mekanism", 1, false, 5, 327_680, 81_920, 2, 2),
	ELITE("elite", "mekanism", 2, false, 7, 458_752, 114_688, 4, 4),
	ULTIMATE("ultimate", "mekanism", 3, false, 9, 589_824, 147_456, 8, 8),
	ME_ABSOLUTE("meAbsolute", "mekanism_extras", 0, false, 11, 720_896, 180_224, 16, 16),
	ME_SUPREME("meSupreme", "mekanism_extras", 1, false, 13, 851_968, 212_992, 32, 32),
	ME_COSMIC("meCosmic", "mekanism_extras", 2, false, 15, 983_040, 245_760, 64, 64),
	ME_INFINITE("meInfinite", "mekanism_extras", 3, false, 17, 1_114_112, 278_528, 128, 128),
	EM_OVERCLOCKED("emOverclocked", "evolved_mekanism", 0, true, 11, 720_896, 180_224, 256, 16),
	EM_QUANTUM("emQuantum", "evolved_mekanism", 1, true, 13, 851_968, 212_992, 512, 32),
	EM_DENSE("emDense", "evolved_mekanism", 2, true, 15, 983_040, 245_760, 1_024, 64),
	EM_MULTIVERSAL("emMultiversal", "evolved_mekanism", 3, true, 17, 1_114_112, 278_528, 2_048, 128),
	EM_CREATIVE("emCreative", "evolved_mekanism", 4, true, 19, 1_245_184, 311_296, 4_096, 256),
	EME_ABSOLUTE_OVERCLOCKED("emeAbsoluteOverclocked", "evolved_mekanism_extras", 0,
			false, 12, 786_432, 196_608, 4_096, 256),
	EME_SUPREME_QUANTUM("emeSupremeQuantum", "evolved_mekanism_extras", 1,
			false, 14, 917_504, 229_376, 8_192, 512),
	EME_COSMIC_DENSE("emeCosmicDense", "evolved_mekanism_extras", 2,
			false, 16, 1_048_576, 262_144, 16_384, 1_024),
	EME_INFINITE_MULTIVERSAL("emeInfiniteMultiversal", "evolved_mekanism_extras", 3,
			false, 18, 1_179_648, 294_912, 32_768, 4_096);

	private static final List<String> CONFIG_GROUPS = List.of(
			"mekanism",
			"mekanism_extras",
			"evolved_mekanism",
			"evolved_mekanism_extras");

	private final String configKey;
	private final String configGroup;
	private final int groupIndex;
	private final boolean evolvedMekanism;
	private final int parallelProcesses;
	private final int centrifugeOutputStackDefault;
	private final int centrifugeInputStackDefault;
	private final int centrifugeFluidTankDefault;
	private final int apiaryOutputStackDefault;

	FactoryTierKey(
			String configKey,
			String configGroup,
			int groupIndex,
			boolean evolvedMekanism,
			int parallelProcesses,
			int centrifugeOutputStackDefault,
			int centrifugeInputStackDefault,
			int centrifugeFluidTankDefault,
			int apiaryOutputStackDefault) {
		this.configKey = configKey;
		this.configGroup = configGroup;
		this.groupIndex = groupIndex;
		this.evolvedMekanism = evolvedMekanism;
		this.parallelProcesses = parallelProcesses;
		this.centrifugeOutputStackDefault = centrifugeOutputStackDefault;
		this.centrifugeInputStackDefault = centrifugeInputStackDefault;
		this.centrifugeFluidTankDefault = centrifugeFluidTankDefault;
		this.apiaryOutputStackDefault = apiaryOutputStackDefault;
	}

	/** 返回稳定的 TOML 叶子键。 */
	public String configKey() {
		return configKey;
	}

	/** 返回容量矩阵中的稳定模组分组键。 */
	public String configGroup() {
		return configGroup;
	}

	/** 返回该等级在所属分组数组中的固定索引。 */
	public int groupIndex() {
		return groupIndex;
	}

	/** 返回全部稳定分组键，顺序即 TOML 展示顺序。 */
	public static List<String> configGroups() {
		return CONFIG_GROUPS;
	}

	/** 返回指定分组内按固定索引排序的等级。 */
	public static List<FactoryTierKey> groupTiers(String group) {
		return java.util.Arrays.stream(values())
				.filter(tier -> tier.configGroup.equals(group))
				.sorted(java.util.Comparator.comparingInt(FactoryTierKey::groupIndex))
				.toList();
	}

	/** 返回该等级是否由 Evolved Mekanism 动态扩展。 */
	public boolean requiresEvolvedMekanism() {
		return evolvedMekanism;
	}

	/** 返回该等级机器的并行处理进程数，用于容量矩阵顺序校验。 */
	public int parallelProcesses() {
		return parallelProcesses;
	}

	/** 返回离心机输出槽默认倍率。 */
	public int centrifugeOutputStackDefault() {
		return centrifugeOutputStackDefault;
	}

	/** 返回离心机输入槽默认倍率。 */
	public int centrifugeInputStackDefault() {
		return centrifugeInputStackDefault;
	}

	/** 返回离心机流体罐默认倍率。 */
	public int centrifugeFluidTankDefault() {
		return centrifugeFluidTankDefault;
	}

	/** 返回机械蜂箱输出槽默认倍率。 */
	public int apiaryOutputStackDefault() {
		return apiaryOutputStackDefault;
	}

	/** 返回原版 Mekanism 工厂 ordinal 对应的等级。 */
	public static FactoryTierKey vanillaFactory(int ordinal) {
		return switch (ordinal) {
			case 1 -> ADVANCED;
			case 2 -> ELITE;
			case 3 -> ULTIMATE;
			default -> BASIC;
		};
	}

	/** 返回 Mekanism Extras 工厂 ordinal 对应的等级。 */
	public static FactoryTierKey mekanismExtrasFactory(int ordinal) {
		return switch (ordinal) {
			case 1 -> ME_SUPREME;
			case 2 -> ME_COSMIC;
			case 3 -> ME_INFINITE;
			default -> ME_ABSOLUTE;
		};
	}

	/** 返回 Evolved Mekanism 相对 ordinal 对应的等级。 */
	public static FactoryTierKey evolvedMekanismFactory(int ordinal) {
		return switch (ordinal) {
			case 1 -> EM_QUANTUM;
			case 2 -> EM_DENSE;
			case 3 -> EM_MULTIVERSAL;
			case 4 -> EM_CREATIVE;
			default -> EM_OVERCLOCKED;
		};
	}

	/** 返回 Evolved Mekanism Extras 工厂 ordinal 对应的等级。 */
	public static FactoryTierKey evolvedMekanismExtrasFactory(int ordinal) {
		return switch (ordinal) {
			case 1 -> EME_SUPREME_QUANTUM;
			case 2 -> EME_COSMIC_DENSE;
			case 3 -> EME_INFINITE_MULTIVERSAL;
			default -> EME_ABSOLUTE_OVERCLOCKED;
		};
	}
}
