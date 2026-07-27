package com.ayoshiko.productivebeesgenesis.apiary;

/**
 * PB 升级类型枚举
 * <br/>
 * 定义 Productive Bees 专属升级的类别，用于 {@link ApiaryUpgradeHandler} 管理安装状态
 * 及 {@link com.ayoshiko.productivebeesgenesis.apiary.client.GuiPbUpgradeWindow} 渲染升级列表。
 * <p>
 * 每种升级包含：
 * <ul>
 *   <li>{@code id}：字符串标识，用于 NBT 序列化与翻译键生成</li>
 *   <li>{@code color}：ARGB 颜色，用于 GUI 显示区分</li>
 *   <li>{@code productivityFactor}：产量升级的系数（仅产量系列有值，其他为 0）</li>
 *   <li>{@code maxCount}：该类型可安装的最大数量（功能型=1，叠加型=8）</li>
 * </ul>
 * <p>
 * 设计原则：单一职责，仅定义类型元数据，不包含升级效果计算（效果计算由
 * {@link ApiaryUpgradeHandler} 根据已安装数量完成）。
 * <p>
 * Bug 5：产量升级区分为 α/β/γ/Ω 四个等级，每个等级有独立系数与颜色。
 * BLOCK 为独立的蜜脾块升级类型，Ω（PRODUCTIVITY_4）亦自带蜜脾块效果
 *（由 {@link ApiaryUpgradeHandler#hasCombBlockUpgrade} 判定，二者任一安装即生效）。
 * <p>
 * 按类型差异化上限：GENE_SAMPLER 上限由配置驱动（默认4，最大20），
 * BLOCK 为功能型升级 1 个即满；其他叠加型升级保持 8 个上限。
 */
public enum PbUpgradeType {

	/** 生产力升级 α — 产出数量倍率（每级系数 1.2） */
	PRODUCTIVITY("productivity", 0xFF4CAF50, 1.2f, 8),

	/** 生产力升级 β — 产出数量倍率（每级系数 1.5） */
	PRODUCTIVITY_2("productivity_2", 0xFF66BB6A, 1.5f, 8),

	/** 生产力升级 γ — 产出数量倍率（每级系数 2.0） */
	PRODUCTIVITY_3("productivity_3", 0xFF42A5F5, 2.0f, 8),

	/** 生产力升级 Ω — 产出数量倍率（每级系数 2.6），自带蜜脾块效果 */
	PRODUCTIVITY_4("productivity_4", 0xFFEF5350, 2.6f, 8),

	/** 时间升级 — 生产速度倍率（每级减少 15% 时间，与 PB 原版 timeBonus 一致） */
	TIME("time", 0xFFFF9800, 0f, 8),

	/** Bug 3：时间升级 II — 双倍效果（每级减少 30% 时间，与 PB 原版 time_2 一致） */
	TIME_2("time_2", 0xFFFF6D00, 0f, 8),

	/** 基因采样升级 — 采样蜜蜂基因（上限由配置驱动，枚举值4为配置未加载时的回退默认值） */
	GENE_SAMPLER("gene_sampler", 0xFF9C27B0, 0f, 4),

	/** 蜜脾块升级 — 将蜜脾产出转换为蜜脾块形式（功能型，1 个即满，独立于 Ω） */
	BLOCK("block", 0xFF8D6E63, 0f, 1),

	/** 模拟升级 — 模拟生产（机械蜂箱内置，不占槽位） */
	SIMULATION("simulation", 0xFF607D8B, 0f, 8);

	/** 翻译键前缀 */
	private static final String LANG_KEY_PREFIX = "gui.productivebeesgenesis.pb_upgrade.type.";

	/** 翻译键后缀 — 描述 */
	private static final String LANG_KEY_DESC_SUFFIX = ".desc";

	/** 升级标识（用于 NBT 序列化） */
	private final String id;

	/** ARGB 颜色（GUI 显示用） */
	private final int color;

	/** 产量升级系数（仅产量系列有值，其他为 0） */
	private final float productivityFactor;

	/** 该类型可安装的最大数量（功能型=1，叠加型=8） */
	private final int maxCount;

	PbUpgradeType(String id, int color, float productivityFactor, int maxCount) {
		this.id = id;
		this.color = color;
		this.productivityFactor = productivityFactor;
		this.maxCount = maxCount;
	}

	/** 获取升级标识 */
	public String getId() {
		return id;
	}

	/** 获取 ARGB 颜色 */
	public int getColor() {
		return color;
	}

	/**
	 * 获取产量升级系数
	 * <br/>
	 * 仅产量系列（PRODUCTIVITY/PRODUCTIVITY_2/3/4）返回正数，其他类型返回 0。
	 * 供 {@link ApiaryUpgradeHandler#getProductivityMultiplier} 按等级加权求和。
	 *
	 * @return 产量系数，非产量升级返回 0
	 */
	public float getProductivityFactor() {
		return productivityFactor;
	}

	/**
	 * 获取该类型可安装的最大数量
	 * <br/>
	 * BLOCK 返回 1，叠加型升级返回 8。GENE_SAMPLER 返回枚举默认值 4，
	 * 实际运行时由 {@link ApiaryPbUpgradeHandler#getPbUpgradeLimit} 读取配置覆盖。
	 * 供 GUI 显示 "%d / %d" 及配置未加载时的回退。
	 *
	 * @return 最大安装数量
	 */
	public int getMaxCount() {
		return maxCount;
	}

	/** 获取名称翻译键 */
	public String getNameKey() {
		return LANG_KEY_PREFIX + id;
	}

	/** 获取描述翻译键 */
	public String getDescriptionKey() {
		return LANG_KEY_PREFIX + id + LANG_KEY_DESC_SUFFIX;
	}

	/**
	 * 是否为内置升级（不占槽位）
	 * <br/>
	 * 模拟升级在机械蜂箱中始终内置，无需安装。
	 *
	 * @return true 表示内置
	 */
	public boolean isBuiltin() {
		return this == SIMULATION;
	}

	/**
	 * 通过 id 查找升级类型
	 *
	 * @param id 升级标识
	 * @return 对应的 PbUpgradeType，未找到返回 null
	 */
	public static PbUpgradeType byId(String id) {
		if (id == null) return null;
		for (PbUpgradeType type : values()) {
			if (type.id.equals(id)) return type;
		}
		return null;
	}
}
