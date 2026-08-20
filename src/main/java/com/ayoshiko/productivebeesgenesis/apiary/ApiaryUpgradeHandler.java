package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.mek.MekUpgradeSupport;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import mekanism.api.Upgrade;
import mekanism.common.config.MekanismConfig;
import mekanism.common.util.MekanismUtils;

import java.util.HashSet;
import java.util.Set;

/**
	 * 蜂箱升级效果计算处理器
	 * <br/>
	 * 统一管理 MEK 原版升级与 PB 专属升级的效果计算，供 {@link BeeProduceProcessor} 和
	 * {@link ApiaryTickHandler} 在生产流程中应用。
	 * <p>
	 * MEK 升级（通过 {@link mekanism.common.tile.component.TileComponentUpgrade}）：
	 * <ul>
	 *   <li>速度升级（Upgrade.SPEED）：减少蜜蜂生产时间，增加每 tick 能耗</li>
	 *   <li>能量升级（Upgrade.ENERGY）：增加能量容量（由 MachineEnergyContainer 自动处理）</li>
	 * </ul>
	 * <p>
	 * PB 升级（由 {@link ApiaryPbUpgradeHandler} 管理 EnumMap 存储，通过
	 * {@link TileEntityMekApiary#getPbUpgradeCount} 暴露）：
	 * <ul>
	 *   <li>生产力升级 α/β/γ/Ω（PRODUCTIVITY/2/3/4）：每级按对应系数累加产出倍率，
	 *       系数运行时读取 PB 原版配置（默认 1.2/1.5/2.0/2.6）</li>
	 *   <li>时间升级（UPGRADE_TIME/2）：减少生产时间（比例运行时读取 PB 原版 timeBonus，默认 15%）</li>
	 *   <li>蜜脾块升级（BLOCK）：将蜜脾产出转换为蜜脾块形式</li>
	 *   <li>模拟升级：机械蜂箱内置，始终启用</li>
	 *   <li>Ω 升级自带蜜脾块效果（{@link #hasCombBlockUpgrade}，BLOCK 或 Ω 任一即生效）</li>
	 * </ul>
	 * <p>
	 * Bug 5：产量升级按等级独立加权，{@link #getProductivityMultiplier} 使用
	 * {@link PbUpgradeType#getProductivityFactor()} 求和。
	 * <p>
	 * Bug 6：从"槽位遍历"重构为"EnumMap 数量查询"，服务端直接读
	 * {@link ApiaryPbUpgradeHandler} 的 EnumMap，客户端通过 SyncableInt 同步至其 clientUpgradeCounts。
	 * <p>
	 * Bug 2修复：速度升级和能耗倍率改用 MEK 原版公式，与 MEK 机器加速效果一致。
	 * 公式由 {@link MekanismUtils} 在运行时计算；这会自动承接 Mekanism
	 * Unleashed 与 MekanismEmpowered 对升级公式的 mixin 修改。
	 * <p>
	 * 设计原则：
	 * <ul>
	 *   <li>单一职责：仅负责升级倍率计算，不管理升级物品存储</li>
	 *   <li>开闭原则：新增升级类型仅需扩展映射方法，调用方不变</li>
	 * </ul>
	 */
public class ApiaryUpgradeHandler {

	/** 所属方块实体引用 — 用于查询 MEK 升级组件和 PB 升级数量 */
	private final TileEntityMekApiary tile;

	/** MEK 升级查询异常日志冷却器（ms 模式，避免高频异常刷屏） */
	private final LogThrottle upgradeErrorThrottle = new LogThrottle();

	/**
	 * 升级倍率缓存 — 100 tick 刷新一次，避免每 tick 重复 Math.pow 与 EnumMap 查询。
	 * <p>
	 * 7 个公共 getter 委托到此缓存；缓存刷新时调用 package-private 的
	 * {@code compute*} 方法获取最新值，避免 handler → cache → handler 递归。
	 */
	private final ApiaryUpgradeCache upgradeCache;

	/**
	 * 构造升级效果计算器
	 *
	 * @param tile 所属方块实体
	 */
	public ApiaryUpgradeHandler(TileEntityMekApiary tile) {
		this.tile = tile;
		this.upgradeCache = new ApiaryUpgradeCache(this);
	}

	/**
	 * 失效升级倍率缓存 — 升级组件变更时由外部调用方触发
	 * <br/>
	 * 调用后下次 getter 访问将重新调用 {@code compute*} 方法计算最新值。
	 * <p>
	 * 调用方：{@link ApiaryPbUpgradeHandler#installPbUpgrade} /
	 * {@link ApiaryPbUpgradeHandler#installPbUpgradeBulk} /
	 * {@link ApiaryPbUpgradeHandler#removePbUpgrade}（PB 升级变更）。
	 * MEK 升级（SPEED/ENERGY）变更通过 100-tick 自动刷新延迟反映（≤5 秒）。
	 */
	public void invalidateUpgradeCache() {
		upgradeCache.invalidate();
	}

	/**
	 * 驱动缓存按需刷新 — 由 tick 处理器每 tick 调用
	 * <br/>
	 * 内部基于调用计数器按 {@code REFRESH_INTERVAL = 100} 间隔判断是否需要刷新。
	 * <p>
	 * <b>JDTE 适配</b>：不传入 gameTime，避免 tick 加速模组（多次调用 tick 但
	 * getGameTime 不变）导致缓存守卫失效。内部计数器每次调用递增，正确反映实际 tick 次数。
	 */
	public void tickRefresh() {
		upgradeCache.refreshIfNeeded();
	}

	/**
	 * 判断当前是否在客户端
	 * <br/>
	 * 客户端/服务端的 PB 升级数量区分由
	 * {@link ApiaryPbUpgradeHandler#getPbUpgradeCount} 内部处理。
	 *
	 * @return true 如果在客户端
	 */
	private boolean isClientSide() {
		return tile != null && tile.getLevel() != null && tile.getLevel().isClientSide();
	}

	/**
	 * 节流记录 MEK 升级查询异常（避免高频异常刷屏）
	 *
	 * @param context 异常上下文描述
	 * @param e       异常
	 */
	private void logUpgradeError(String context, Exception e) {
		upgradeErrorThrottle.tryLogMs(System.currentTimeMillis(), suppressed -> {
			ProductiveBeesGenesis.LOGGER.warn("{}异常{}", context, suppressed > 0 ? " (抑制 " + suppressed + " 次)" : "", e);
		});
	}

	// ===== MEK 升级查询 =====

	/**
	 * 获取 MEK 速度升级已安装数量
	 *
	 * @return 速度升级数量（0-8），tile 未就绪时返回 0
	 */
	public int getMekSpeedUpgrades() {
		if (tile == null) return 0;
		try {
			var component = tile.getComponent();
			if (component == null) return 0;
			return component.getUpgrades(Upgrade.SPEED);
		} catch (Exception e) {
			logUpgradeError("查询 MEK 速度升级数量", e);
			return 0;
		}
	}

	/**
	 * 获取 MEK 能量升级已安装数量
	 *
	 * @return 能量升级数量（0-8），tile 未就绪时返回 0
	 */
	int getMekEnergyUpgrades() {
		if (tile == null) return 0;
		try {
			var component = tile.getComponent();
			if (component == null) return 0;
			return component.getUpgrades(Upgrade.ENERGY);
		} catch (Exception e) {
			logUpgradeError("查询 MEK 能量升级数量", e);
			return 0;
		}
	}

	/**
	 * 获取 MEKExtras 堆叠升级已安装数量
	 * <br/>
	 * 蜂箱不支持STACK升级，此方法始终返回0。保留方法签名以维持兼容性。
	 *
	 * @return 始终返回 0
	 */
	private int getMekStackUpgrades() {
		return 0;
	}

	/**
	 * 是否安装了 MEKExtras 创造升级（不走缓存，直接查询）
	 * <br/>
	 * 委托 {@link MekUpgradeSupport#hasCreativeUpgrade}（MEKExtras 未加载时安全返回 false）。
	 * CREATIVE 安装后：能耗经 MEKExtras Mixin 自动归零（MachineEnergyContainer.getEnergyPerTick=0），
	 * 生产周期由 {@code ApiaryProgressAdvancer} 缩短为每 tick 产出。
	 *
	 * @return true 如果安装了 CREATIVE 升级
	 */
	private boolean hasMekCreativeUpgrade() {
		return MekUpgradeSupport.hasCreativeUpgrade(tile);
	}

	/**
	 * 计算是否安装了 CREATIVE 升级（不走缓存）— 供 {@link ApiaryUpgradeCache#doRefresh} 调用
	 *
	 * @return true 如果安装了 CREATIVE 升级
	 */
	boolean computeHasCreativeUpgrade() {
		return hasMekCreativeUpgrade();
	}

	// ===== 倍率计算 =====

	/**
	 * 获取生产力倍率
	 * <br/>
	 * 委托到 {@link ApiaryUpgradeCache}，命中缓存时不调用 {@code Math.pow} 与 EnumMap 查询。
	 * 实际计算逻辑见 {@link #computeProductivityMultiplier()}。
	 *
	 * @return 生产力倍率（≥1.0）
	 */
	public float getProductivityMultiplier() {
		return upgradeCache.getProductivityMultiplier();
	}

	/**
	 * 计算生产力倍率（不走缓存）— 供 {@link ApiaryUpgradeCache#doRefresh} 调用
	 * <br/>
	 * Bug 5：按等级加权求和。公式：{@code 1.0 + Σ(factor_i × count_i)}，i ∈ {α, β, γ, Ω}。
	 * <ul>
	 *   <li>α（PRODUCTIVITY）：每级 +1.2</li>
	 *   <li>β（PRODUCTIVITY_2）：每级 +1.5</li>
	 *   <li>γ（PRODUCTIVITY_3）：每级 +2.0</li>
	 *   <li>Ω（PRODUCTIVITY_4）：每级 +2.6，自带蜜脾块效果</li>
	 * </ul>
	 * <p>
	 * Task 1.2 修复：移除 STACK 升级的生产力倍率影响。
	 * <p>
	 * v2.0.9: 移除 1.22f 补偿系数
	 * 原补偿用于 processBatchProduce 聚合取整损失，但被升级放大导致满级加成过高（87×）
	 * 移除后满级加成 71.28×（降 18%），与原版 PB 一致
	 *
	 * @return 生产力倍率(基础 1.0)
	 */
	float computeProductivityMultiplier() {
		double mod = 1.0D;
		mod += (double) PbUpgradeType.PRODUCTIVITY.getProductivityFactor() * getInstalledUpgrades(PbUpgradeType.PRODUCTIVITY);
		mod += (double) PbUpgradeType.PRODUCTIVITY_2.getProductivityFactor() * getInstalledUpgrades(PbUpgradeType.PRODUCTIVITY_2);
		mod += (double) PbUpgradeType.PRODUCTIVITY_3.getProductivityFactor() * getInstalledUpgrades(PbUpgradeType.PRODUCTIVITY_3);
		mod += (double) PbUpgradeType.PRODUCTIVITY_4.getProductivityFactor() * getInstalledUpgrades(PbUpgradeType.PRODUCTIVITY_4);
		return SaturatingMath.positiveFiniteFloat(mod, 1.0f);
	}

	/**
	 * 获取 MEK 速度升级时间倍率
	 * <br/>
	 * 公式委托 {@link ApiaryUpgradeMath#computeMekSpeedTimeMultiplier}（纯公式），
	 * 运行时入口语义由 {@code MekanismUtils} 承接可选模组 mixin。
	 */
	public float getMekSpeedTimeMultiplier() {
		int speedUpgrades = getMekSpeedUpgrades();
		int maxSpeed = Upgrade.SPEED.getMax();
		if (maxSpeed <= 0 || speedUpgrades <= 0) return 1.0f;
		float maxMultiplier = MekanismConfig.general.maxUpgradeMultiplier.get();
		return ApiaryUpgradeMath.computeMekSpeedTimeMultiplier(speedUpgrades, maxSpeed, maxMultiplier);
	}

	/**
	 * 获取 MEK 速度升级的能耗倍率 — 委托运行时
	 * {@code MekanismUtils#getEnergyPerTick}，包括 ENERGY、Unleashed 和 MekEmp 修改。
	 *
	 * @return MEK 原版速度/能量组合的能耗倍率（>0）
	 */
	private float getMekSpeedEnergyMultiplier() {
		// 使用较大的基数保留 ENERGY 减速带来的小数倍率；
		// getEnergyPerTick 返回 long，基数 1,000,000 可将舍入误差压到百万分之一。
		final long sampleBase = 1_000_000L;
		long scaled = MekanismUtils.getEnergyPerTick(tile, sampleBase);
		return scaled > 0 ? (float) scaled / sampleBase : 1.0f;
	}

	/**
	 * 获取 STACK 升级的产出次数倍率
	 * <br/>
	 * 蜂箱不支持STACK升级，此方法始终返回1。
	 * 保留方法签名以维持与调用方（ApiaryTickHandler）的兼容性。
	 *
	 * @return 始终返回 1
	 */
	public int getStackProductionCount() {
		return 1;
	}

	/**
	 * 判断是否安装了 MEKExtras CREATIVE 升级
	 * <br/>
	 * 委托到 {@link ApiaryUpgradeCache}，命中缓存时不调用 EnumMap 查询
	 * （调用方 {@code ApiaryProgressAdvancer.advance} 每 tick 每蜜蜂执行一次，必须走缓存）。
	 * CREATIVE 安装/移除时由 {@code TileEntityMekApiary#recalculateUpgrades} 主动失效缓存，立即生效。
	 *
	 * @return true 如果安装了 CREATIVE 升级
	 */
	public boolean hasCreativeUpgrade() {
		return upgradeCache.hasCreativeUpgrade();
	}

	/**
	 * 获取时间倍率
	 * <br/>
	 * 委托到 {@link ApiaryUpgradeCache}，命中缓存时不调用 {@code Math.pow} 与 EnumMap 查询。
	 * 实际计算逻辑见 {@link #computeTimeMultiplier()}。
	 *
	 * @return 时间倍率（>0，越小越快）
	 */
	public float getTimeMultiplier() {
		return upgradeCache.getTimeMultiplier();
	}

	/**
	 * 计算时间倍率（不走缓存）— 供 {@link ApiaryUpgradeCache#doRefresh} 调用
	 * <br/>
	 * Bug 2修复：MEK 速度升级改用 MEK 原版公式：
	 * {@code maxUpgradeMultiplier ^ (-speedUpgrades / maxUpgrades)}
	 * <br/>
	 * Bug 3：区分 TIME（单倍）与 TIME_2（双倍）：{@code effectiveCount = timeCount + time_2Count * 2}
	 * 综合公式：{@code mekTimeMultiplier / (1 + timeBonus * effectiveTimeUpgrades)}
	 * 其中 {@code timeBonus} 运行时读取 PB 原版配置 {@code ProductiveBeesConfig.Upgrades.timeBonus}。
	 *
	 * @return 时间倍率（>0，越小越快）
	 */
	float computeTimeMultiplier() {
		float mekTimeMultiplier = getMekSpeedTimeMultiplier();
		float pbTimeDivisor = ApiaryUpgradeMath.getPbTimeDivisor(this);
		return SaturatingMath.positiveFiniteFloat((double) mekTimeMultiplier / pbTimeDivisor, 1.0f);
	}

	/**
	 * 获取能耗倍率
	 * <br/>
	 * 委托到 {@link ApiaryUpgradeCache}，命中缓存时不调用 {@code Math.pow}。
	 * 实际计算逻辑见 {@link #computeEnergyMultiplier()}。
	 *
	 * @return 能耗倍率（>0）
	 */
	public float getEnergyMultiplier() {
		return upgradeCache.getEnergyMultiplier();
	}

	/**
	 * 计算能耗倍率（不走缓存）— 供 {@link ApiaryUpgradeCache#doRefresh} 调用
	 * <br/>
	 * Bug 2修复：改用 MEK 原版公式
	 * {@code maxUpgradeMultiplier ^ (2 * speedFraction - energyFraction)}。
	 * 这与 MekanismUtils.getEnergyPerTick 一致，ENERGY 升级会抵消速度升级带来的额外能耗。
	 * CREATIVE 升级的零能耗由 MEKExtras Mixin 在 MachineEnergyContainer.getEnergyPerTick
	 * 层面处理，此倍率不参与实际能耗扣减（BeeSlotTickProcessor 直接读 getEnergyPerTick）。
	 *
	 * @return 能耗倍率（>0）
	 */
	float computeEnergyMultiplier() {
		return getMekSpeedEnergyMultiplier();
	}

	// ===== PB 升级查询接口（供GUI渲染） =====

	/**
	 * 获取指定类型的已安装数量
	 * <br/>
	 * 委托给 {@link TileEntityMekApiary#getPbUpgradeCount}，由
	 * {@link ApiaryPbUpgradeHandler#getPbUpgradeCount} 内部区分客户端/服务端读取路径。
	 * 内置升级（SIMULATION）始终返回 1，无需同步。
	 *
	 * @param type 升级类型
	 * @return 已安装数量（0 表示未安装）
	 */
	public int getInstalledUpgrades(PbUpgradeType type) {
		if (type == null) return 0;
		if (type.isBuiltin()) return 1; // 内置升级无需同步
		return tile.getPbUpgradeCount(type);
	}

	/**
	 * 获取所有已安装的 PB 升级类型集合
	 * <br/>
	 * 遍历所有 PbUpgradeType，返回安装数量 > 0 的类型。包含内置的 SIMULATION。
	 *
	 * @return 已安装升级类型集合
	 */
	public Set<PbUpgradeType> getInstalledTypes() {
		Set<PbUpgradeType> types = new HashSet<>();
		for (PbUpgradeType type : PbUpgradeType.values()) {
			if (getInstalledUpgrades(type) > 0) {
				types.add(type);
			}
		}
		return types;
	}

	/**
	 * 是否安装了蜜脾块升级（BLOCK 或 Ω 产量升级）
	 * <br/>
	 * 委托到 {@link ApiaryUpgradeCache}，命中缓存时不触发 EnumMap 查询。
	 * 实际计算逻辑见 {@link #computeHasCombBlockUpgrade()}。
	 *
	 * @return true 如果已安装 BLOCK 升级或 Ω 产量升级
	 */
	public boolean hasCombBlockUpgrade() {
		return upgradeCache.hasCombBlockUpgrade();
	}

	/**
	 * 计算是否安装蜜脾块升级（不走缓存）— 供 {@link ApiaryUpgradeCache#doRefresh} 调用
	 * <br/>
	 * BLOCK 为独立的蜜脾块升级类型，Ω（PRODUCTIVITY_4）亦自带蜜脾块效果。
	 * 任一安装即视为蜜脾块升级，会将蜜脾产出转换为蜜脾块。
	 * <p>
	 * Bug 10：机械蜂箱绕过 BeeHelperMixin 注入，需在 BeeProduceProcessor 中
	 * 复刻蜜脾块升级判断，决定追加随机蜜脾（无升级）或蜜脾块（有升级）。
	 *
	 * @return true 如果已安装 BLOCK 升级或 Ω 产量升级
	 */
	boolean computeHasCombBlockUpgrade() {
		if (isClientSide()) return false; // 客户端无此数据
		return getInstalledUpgrades(PbUpgradeType.BLOCK) > 0
				|| getInstalledUpgrades(PbUpgradeType.PRODUCTIVITY_4) > 0;
	}

	/**
	 * 获取基因采样器已安装数量
	 * <br/>
	 * 委托到 {@link ApiaryUpgradeCache}，命中缓存时不触发 EnumMap 查询。
	 * 实际计算逻辑见 {@link #computeGeneSamplerCount()}。
	 *
	 * @return 基因采样器安装数量（0 表示未安装）
	 */
	public int getGeneSamplerCount() {
		return upgradeCache.getGeneSamplerCount();
	}

	/**
	 * 计算基因采样器已安装数量（不走缓存）— 供 {@link ApiaryUpgradeCache#doRefresh} 调用
	 * <br/>
	 * 数量直接影响基因产出概率：{@code baseChance × count}。
	 * 服务端从 tile 的 EnumMap 直查，客户端从同步缓存读取（供 GUI 显示用）。
	 *
	 * @return 基因采样器安装数量（0 表示未安装）
	 */
	int computeGeneSamplerCount() {
		return getInstalledUpgrades(PbUpgradeType.GENE_SAMPLER);
	}

	/**
	 * 是否安装了基因采样器升级
	 * <br/>
	 * 委托到 {@link ApiaryUpgradeCache}，命中缓存时不触发 EnumMap 查询。
	 * 实际计算逻辑见 {@link #computeHasGeneSamplerUpgrade()}。
	 *
	 * @return true 如果已安装至少一个基因采样器
	 */
	public boolean hasGeneSamplerUpgrade() {
		return upgradeCache.hasGeneSamplerUpgrade();
	}

	/**
	 * 计算是否安装基因采样器升级（不走缓存）— 供 {@link ApiaryUpgradeCache#doRefresh} 调用
	 * <br/>
	 * 仅有基因采样器时才会触发基因产出逻辑，避免无效概率计算。
	 *
	 * @return true 如果已安装至少一个基因采样器
	 */
	boolean computeHasGeneSamplerUpgrade() {
		return computeGeneSamplerCount() > 0;
	}
}
