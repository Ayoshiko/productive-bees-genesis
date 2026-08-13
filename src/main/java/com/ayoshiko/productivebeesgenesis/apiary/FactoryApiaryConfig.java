package com.ayoshiko.productivebeesgenesis.apiary;

import mekanism.common.tier.FactoryTier;

/**
	 * 工厂版通用机械蜂箱参数配置
	 * <br/>
	 * 集中管理工厂等级的槽位数量和流体罐容量参数（spec.md 表 2.1）。
	 * <p>
	 * 设计原则：单一职责，本类仅负责参数数据封装，不包含逻辑。
	 * 使用 switch 而非 Map，避免运行时查找开销。
	 * <p>
	 * 布局策略（所有等级统一走智能矩形布局）：
	 * <ul>
	 *   <li>蜜蜂槽：列数由 {@link #calculateOptimalBeeCols} 动态计算，寻找精确因数对使布局接近正方形且行数≤5</li>
	 *   <li>喂食器：低等级（蜂数&lt;9）保持 3×3=9 槽（原版行为）；高等级（蜂数≥9）使用 5 列严格矩形，
	 *       槽位数 = ceil(蜂数/5)*5。高等级 GUI 通过 {@link com.ayoshiko.productivebeesgenesis.apiary.client.GuiFeederWindow}
	 *       的"5×6=30 翻页"机制查看完整槽位（蜂数&gt;30 时启用翻页，每页 30 槽）。</li>
	 * </ul>
	 * <p>
	 * 基础4级参数表（智能布局，蜂槽视觉与原固定5列一致；喂食器匹配蜂数）：
	 * <ul>
	 *   <li>Basic: 5 蜂蜂(1×5)/9 输出(3×3)/9 喂食(3×3 低等级)/256,000 mB</li>
	 *   <li>Advanced: 10 蜂蜂(2×5)/12 输出(3×4)/10 喂食(5×2)/512,000 mB</li>
	 *   <li>Elite: 15 蜂蜂(3×5)/15 输出(3×5)/15 喂食(5×3)/768,000 mB</li>
	 *   <li>Ultimate: 20 蜂蜂(4×5)/18 输出(3×6)/20 喂食(5×4)/1,024,000 mB</li>
	 * </ul>
	 * <p>
	 * 扩展等级参数表（蜂槽精确因数对布局；喂食器=ceil(N/5)*5 严格矩形，>30 槽启用翻页）：
	 * <ul>
	 *   <li>26蜂: 9列3行蜂/30喂食(5×6,1页)</li>
	 *   <li>30蜂: 6列5行蜂/30喂食(5×6,1页)</li>
	 *   <li>36蜂: 9列4行蜂/40喂食(5×8,2页:30+10)</li>
	 *   <li>42蜂: 7列6行蜂/45喂食(5×9,2页:30+15)</li>
	 *   <li>45蜂: 9列5行蜂/45喂食(5×9,2页:30+15)</li>
	 *   <li>51蜂: 17列3行蜂/55喂食(5×11,2页:30+25)</li>
	 *   <li>55蜂: 11列5行蜂/55喂食(5×11,2页:30+25)</li>
	 *   <li>60蜂: 10列6行蜂/60喂食(5×12,2页:30+30) — 最高等级</li>
	 * </ul>
	 */
public final class FactoryApiaryConfig {

	/** 基础等级蜜蜂固定 5 列 */
	static final int FACTORY_BEE_COLS = 5;
	/** 输出固定 3 行 */
	static final int FACTORY_OUTPUT_ROWS = 3;
	/** 基础等级喂食槽固定 3 行 */
	static final int FACTORY_FEEDER_ROWS = 3;
	/** 智能布局阈值：蜜蜂数量超过此值时启用智能布局（设为0使所有等级均走智能路径） */
	private static final int EXTENDED_THRESHOLD = 0;
	/** 喂食器最小槽位数（低等级蜂数<9 时保持 3×3=9 槽） */
	private static final int FEEDER_MIN_SLOTS = 9;
	/** 低等级喂食器列数（蜂数<9 时使用，保持原版 3×3=9） */
	private static final int FEEDER_LOW_TIER_COLS = 3;
	/** 低等级喂食器行数（蜂数<9 时使用） */
	private static final int FEEDER_LOW_TIER_ROWS = 3;
	/** 高等级喂食器列数（蜂数≥9 时使用，配合翻页每页 5×6=30） */
	private static final int FEEDER_HIGH_TIER_COLS = 5;

	public final int beeSlotCount;
	public final int beeCols;
	public final int beeRows;
	public final int outputSlotCount;
	public final int outputCols;
	public final int outputRows;
	public final int feederSlotCount;
	public final int feederCols;
	public final int feederRows;
	public final int fluidTankCapacity;

	private FactoryApiaryConfig(int beeSlotCount, int beeRowsHint,
			int outputSlotCount, int outputCols,
			int feederSlotCount, int feederCols,
			int fluidTankCapacity) {
		this.beeSlotCount = beeSlotCount;
		this.outputSlotCount = outputSlotCount;
		this.outputCols = outputCols;
		this.outputRows = FACTORY_OUTPUT_ROWS;
		this.fluidTankCapacity = fluidTankCapacity;

		if (beeSlotCount > EXTENDED_THRESHOLD) {
			// 智能矩形居中布局（所有等级均走此路径）
			this.beeCols = calculateOptimalBeeCols(beeSlotCount);
			this.beeRows = (int) Math.ceil((double) beeSlotCount / this.beeCols);
			// 喂食器槽位匹配蜜蜂数量：
			// - 低等级（蜂数<9）：保持 3×3=9 槽（原版行为，单页无翻页）
			// - 高等级（蜂数≥9）：5 列严格矩形，槽位数 = ceil(蜂数/5)*5
			//   蜂数>30 时启用翻页，每页 5×6=30 槽（如 60 蜂→2 页）
			if (beeSlotCount < FEEDER_MIN_SLOTS) {
				this.feederCols = FEEDER_LOW_TIER_COLS;
				this.feederRows = FEEDER_LOW_TIER_ROWS;
			} else {
				this.feederCols = FEEDER_HIGH_TIER_COLS;
				this.feederRows = (int) Math.ceil((double) beeSlotCount / this.feederCols);
			}
			this.feederSlotCount = this.feederRows * this.feederCols;
		} else {
			// 兜底：beeSlotCount<=0 时回退到固定5列布局
			this.beeCols = FACTORY_BEE_COLS;
			this.beeRows = beeRowsHint;
			this.feederSlotCount = feederSlotCount;
			this.feederCols = feederCols;
			this.feederRows = FACTORY_FEEDER_ROWS;
		}
	}

	/**
	 * 蜜蜂槽列数上限 — 确保 imageWidth = 20×cols + 120 ≤ 480px（scale=4@1080p）
	 */
	private static final int MAX_BEE_COLS = 18;

	/**
	 * 蜜蜂槽行数上限 — 紧凑模式下5行可在scale=4@1080p完整显示
	 */
	private static final int MAX_BEE_ROWS = 3;

	/**
	 * 扩展行数上限 — 蜂数超过54时使用，启用5行紧凑布局适配56/60蜂
	 */
	private static final int EXTENDED_MAX_BEE_ROWS = 5;

	/**
	 * 计算蜜蜂槽位的最优列数
	 * <br/>
	 * 约束：cols ≤ {@value #MAX_BEE_COLS} 且 rows ≤ {@value #MAX_BEE_ROWS}，确保 GUI 在 scale=4@1080p 下完整显示。
	 * 蜂数超过 54（MAX_BEE_COLS×MAX_BEE_ROWS）时强制使用5行布局，触发紧凑模式（行高20px），
	 * GUI高度=260px，适配scale=4@1080p的270px限制。
	 * 算法在可行解中优先选择空格子最少的布局，空格相同时选行数较多者（更紧凑）。
	 *
	 * @param beeSlotCount 蜜蜂槽位总数
	 * @return 最优列数（≥{@link #FACTORY_BEE_COLS}）
	 */
	private static int calculateOptimalBeeCols(int beeSlotCount) {
		// 蜂数超过54时强制5行布局，触发紧凑模式适配scale=4@1080p
		if (beeSlotCount > MAX_BEE_COLS * MAX_BEE_ROWS) {
			int cols = (int) Math.ceil((double) beeSlotCount / EXTENDED_MAX_BEE_ROWS);
			return Math.min(Math.max(cols, FACTORY_BEE_COLS), MAX_BEE_COLS);
		}
		int bestCols = Integer.MAX_VALUE;
		int bestEmptySlots = Integer.MAX_VALUE;
		int bestRows = Integer.MAX_VALUE;
		// 遍历所有可行行数，找空格子最少的紧凑布局
		for (int rows = 1; rows <= MAX_BEE_ROWS; rows++) {
			int cols = (int) Math.ceil((double) beeSlotCount / rows);
			if (cols < FACTORY_BEE_COLS) cols = FACTORY_BEE_COLS;
			if (cols > MAX_BEE_COLS) continue;
			int totalSlots = cols * rows;
			int emptySlots = totalSlots - beeSlotCount;
			// 优先空格子少，空格相同选行数多（更紧凑）
			if (emptySlots < bestEmptySlots
					|| (emptySlots == bestEmptySlots && rows > bestRows)) {
				bestEmptySlots = emptySlots;
				bestCols = cols;
				bestRows = rows;
			}
		}
		// 兜底：无任何可行解时，强制最多列数
		if (bestCols == Integer.MAX_VALUE) {
			return MAX_BEE_COLS;
		}
		return bestCols;
	}

	/**
	 * 根据工厂等级获取对应参数配置
	 *
	 * @param tier 工厂等级（BASIC/ADVANCED/ELITE/ULTIMATE，或 EM 扩展的 OVERCLOCKED/QUANTUM/DENSE/MULTIVERSAL/CREATIVE）
	 * @return 对应等级的参数配置，null 时回退到 Basic
	 */
	public static FactoryApiaryConfig forTier(FactoryTier tier) {
		if (tier == null) {
			return forBasic();
		}
		return switch (tier) {
			case BASIC -> forBasic();
			case ADVANCED -> forAdvanced();
			case ELITE -> forElite();
			case ULTIMATE -> forUltimate();
			default -> forEMTier(tier);
		};
	}

	/**
	 * EM (EvolvedMekanism) 等级参数配置
	 * <br/>
	 * EM 通过 Mixin 在运行时扩展 FactoryTier 枚举，添加 OVERCLOCKED/QUANTUM/DENSE/MULTIVERSAL/CREATIVE。
	 * 编译时这些枚举值不存在，通过 tier.name() 字符串匹配。
	 * 参数递增模式与 ME 一致（每级 +5 蜜蜂 / +3 输出 / +256K mB），喂食槽按 ceil(max(N,9)/3)*3 计算。
	 */
	private static FactoryApiaryConfig forEMTier(FactoryTier tier) {
		String name = tier.name();
		return switch (name) {
			case "OVERCLOCKED" -> forEMOverclocked();
			case "QUANTUM" -> forEMQuantum();
			case "DENSE" -> forEMDense();
			case "MULTIVERSAL" -> forEMMultiversal();
			case "CREATIVE" -> forEMCreative();
			default -> forBasic();
		};
	}

	/** EM Overclocked 工厂：26 蜂蜂/21 输出(3×7)/27 喂食(3×9)/1,280,000 mB */
	private static FactoryApiaryConfig forEMOverclocked() {
		return new FactoryApiaryConfig(26, 5, 21, 7, 15, 5, 1_280_000);
	}

	/** EM Quantum 工厂：30 蜂蜂(6×5)/24 输出(3×8)/30 喂食(3×10)/1,536,000 mB */
	private static FactoryApiaryConfig forEMQuantum() {
		return new FactoryApiaryConfig(30, 6, 24, 8, 15, 5, 1_536_000);
	}

	/** EM Dense 工厂：36 蜂蜂/27 输出(3×9)/36 喂食(3×12)/1,792,000 mB */
	private static FactoryApiaryConfig forEMDense() {
		return new FactoryApiaryConfig(36, 7, 27, 9, 15, 5, 1_792_000);
	}

	/** EM Multiversal 工厂：42 蜂蜂/30 输出(3×10)/42 喂食(3×14)/2,048,000 mB */
	private static FactoryApiaryConfig forEMMultiversal() {
		return new FactoryApiaryConfig(42, 8, 30, 10, 15, 5, 2_048_000);
	}

	/** EM Creative 工厂：45 蜂蜂(9×5)/33 输出(3×11)/45 喂食(3×15)/2,304,000 mB */
	private static FactoryApiaryConfig forEMCreative() {
		return new FactoryApiaryConfig(45, 9, 33, 11, 15, 5, 2_304_000);
	}

	/** Basic 工厂：5 蜂蜂(1×5)/9 输出(3×3)/9 喂食(3×3)/256,000 mB */
	private static FactoryApiaryConfig forBasic() {
		return new FactoryApiaryConfig(5, 1, 9, 3, 9, 3, 256_000);
	}

	/** Advanced 工厂：10 蜂蜂(2×5)/12 输出(3×4)/12 喂食(3×4)/512,000 mB */
	private static FactoryApiaryConfig forAdvanced() {
		return new FactoryApiaryConfig(10, 2, 12, 4, 9, 3, 512_000);
	}

	/** Elite 工厂：15 蜂蜂(3×5)/15 输出(3×5)/15 喂食(3×5)/768,000 mB */
	private static FactoryApiaryConfig forElite() {
		return new FactoryApiaryConfig(15, 3, 15, 5, 12, 4, 768_000);
	}

	/** Ultimate 工厂：20 蜂蜂(4×5)/18 输出(3×6)/21 喂食(3×7)/1,024,000 mB */
	private static FactoryApiaryConfig forUltimate() {
		return new FactoryApiaryConfig(20, 4, 18, 6, 15, 5, 1_024_000);
	}

	// ===== Task 6 准备：ME（Mekanism Extras）工厂等级配置 =====

	/**
	 * Task 6 准备：根据 ME 工厂等级获取对应参数配置
	 * <br/>
	 * ME 等级延续原版递增模式：每级 +5 蜜蜂 / +3 输出 / +256K mB，喂食槽按 ceil(max(N,9)/3)*3 计算。
	 * 调用方需确保 ME 已加载（通过 {@link com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks#isMekanismExtrasLoaded()} 守卫）。
	 * <p>
	 * 修复 v15 软依赖隔离：方法签名改为 {@code Object}，避免本类直接 import ME 的 {@code ExtraFactoryTier}。
	 * 内部通过 {@code tier.toString()} 匹配枚举名（Java 枚举默认 toString 返回 name()），
	 * 性能影响可忽略（配置查询不在热路径）。调用方传入 {@code ExtraFactoryTier} 实例自动向上转型为 Object。
	 *
	 * @param tier ME 工厂等级（ABSOLUTE/SUPREME/COSMIC/INFINITE），运行时类型为 ExtraFactoryTier
	 * @return 对应等级的参数配置，未知等级返回 Basic 配置
	 */
	public static FactoryApiaryConfig forMETier(Object tier) {
		String name = tier.toString();
		return switch (name) {
			case "ABSOLUTE" -> forMEAbsolute();
			case "SUPREME" -> forMESupreme();
			case "COSMIC" -> forMECosmic();
			case "INFINITE" -> forMEInfinite();
			default -> forBasic();
		};
	}

	/** ME Absolute 工厂：26 蜂蜂/21 输出(3×7)/27 喂食(3×9)/1,280,000 mB */
	private static FactoryApiaryConfig forMEAbsolute() {
		return new FactoryApiaryConfig(26, 5, 21, 7, 15, 5, 1_280_000);
	}

	/** ME Supreme 工厂：30 蜂蜂(6×5)/24 输出(3×8)/30 喂食(3×10)/1,536,000 mB */
	private static FactoryApiaryConfig forMESupreme() {
		return new FactoryApiaryConfig(30, 6, 24, 8, 15, 5, 1_536_000);
	}

	/** ME Cosmic 工厂：36 蜂蜂/27 输出(3×9)/36 喂食(3×12)/1,792,000 mB */
	private static FactoryApiaryConfig forMECosmic() {
		return new FactoryApiaryConfig(36, 7, 27, 9, 15, 5, 1_792_000);
	}

	/** ME Infinite 工厂：42 蜂蜂/30 输出(3×10)/42 喂食(3×14)/2,048,000 mB */
	private static FactoryApiaryConfig forMEInfinite() {
		return new FactoryApiaryConfig(42, 8, 30, 10, 15, 5, 2_048_000);
	}

	// ===== Task 6 准备：EME（Evolved Mekanism Extras）工厂等级配置 =====

	/**
	 * Task 6 准备：根据 EME 工厂等级获取对应参数配置
	 * <br/>
	 * EME 等级延续 ME 递增模式：每级 +5 蜜蜂 / +3 输出 / +256K mB，喂食槽按 ceil(max(N,9)/3)*3 计算。
	 * 调用方需确保 EME 已加载（通过 {@link com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks#isEvolvedMekanismExtrasLoaded()} 守卫）。
	 * <p>
	 * 修复 v15 软依赖隔离：方法签名改为 {@code Object}，避免本类直接 import EME 的 {@code EMExtraFactoryTier}。
	 * 内部通过 {@code tier.toString()} 匹配枚举名，性能影响可忽略（配置查询不在热路径）。
	 *
	 * @param tier EME 工厂等级（ABSOLUTE_OVERCLOCKED/SUPREME_QUANTUM/COSMIC_DENSE/INFINITE_MULTIVERSAL），运行时类型为
	 * EMExtraFactoryTier
	 * @return 对应等级的参数配置，未知等级返回 Basic 配置
	 */
	public static FactoryApiaryConfig forEMETier(Object tier) {
		String name = tier.toString();
		return switch (name) {
			case "ABSOLUTE_OVERCLOCKED" -> forEMEAbsoluteOverclocked();
			case "SUPREME_QUANTUM" -> forEMESupremeQuantum();
			case "COSMIC_DENSE" -> forEMECosmicDense();
			case "INFINITE_MULTIVERSAL" -> forEMEInfiniteMultiversal();
			default -> forBasic();
		};
	}

	/** EME Absolute Overclocked 工厂：45 蜂蜂(9×5)/33 输出(3×11)/45 喂食(3×15)/2,304,000 mB */
	private static FactoryApiaryConfig forEMEAbsoluteOverclocked() {
		return new FactoryApiaryConfig(45, 9, 33, 11, 15, 5, 2_304_000);
	}

	/** EME Supreme Quantum 工厂：51 蜜蜂(17×3)/36 输出(3×12)/51 喂食(3×17)/2,560,000 mB */
	private static FactoryApiaryConfig forEMESupremeQuantum() {
		return new FactoryApiaryConfig(51, 17, 36, 12, 15, 5, 2_560_000);
	}

	/** EME Cosmic Dense 工厂：55 蜜蜂(11×5)/39 输出(3×13)/57 喂食(3×19，多2空槽保持矩形)/2,816,000 mB */
	private static FactoryApiaryConfig forEMECosmicDense() {
		return new FactoryApiaryConfig(55, 11, 39, 13, 15, 5, 2_816_000);
	}

	/** EME Infinite Multiversal 工厂：60 蜂蜂(12×5)/42 输出(3×14)/60 喂食(3×20)/3,072,000 mB */
	private static FactoryApiaryConfig forEMEInfiniteMultiversal() {
		return new FactoryApiaryConfig(60, 12, 42, 14, 15, 5, 3_072_000);
	}
}
