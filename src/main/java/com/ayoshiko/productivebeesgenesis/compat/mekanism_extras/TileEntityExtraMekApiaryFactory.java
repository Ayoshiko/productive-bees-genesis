package com.ayoshiko.productivebeesgenesis.compat.mekanism_extras;

import com.ayoshiko.productivebeesgenesis.apiary.ApiarySlotManager;
import com.ayoshiko.productivebeesgenesis.apiary.FactoryApiaryConfig;
import com.ayoshiko.productivebeesgenesis.apiary.FeederSlotManager;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiaryFactory;
import com.jerry.mekextras.common.block.attribute.ExtraAttribute;
import com.jerry.mekextras.common.tier.ExtraFactoryTier;
import mekanism.api.tier.BaseTier;
import mekanism.common.tier.FactoryTier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
	 * ME 扩展版工厂蜂箱方块实体
	 * <br/>
	 * 继承 {@link TileEntityMekApiaryFactory}（不是 ME 的工厂基类），因为蜂箱不走 Mekanism CachedRecipe 管线，
	 * 蜜蜂生产逻辑完全复用父类的 {@link ApiaryTickHandler}。
	 * <p>
	 * 关键设计：
	 * 1. 仅用 {@link ExtraAttributeTier} 标记 ME 等级（ABSOLUTE/SUPREME/COSMIC/INFINITE），
	 *    不使用 Mekanism 的 {@code AttributeTier}/{@code FactoryTier}。
	 * 2. {@link ExtraAttributeUpgradeable} 支持升级链（由 {@link MekApiaryMEBlockType} 配置）。
	 * 3. 父类的 {@code tier} 字段（{@link FactoryTier} 类型）在 ME 版本中为 null，
	 *    所有等级相关逻辑通过 {@code meTier} 字段处理。
	 * <p>
	 * 模板方法模式：通过重写 {@link #createSlotManager()} 和 {@link #createFeederSlotManager()}
	 * 返回 ME 等级参数的槽位管理器，父类核心逻辑无需修改。
	 */
public class TileEntityExtraMekApiaryFactory extends TileEntityMekApiaryFactory {

	/** ME 工厂等级 — 在 presetVariables() 中从 BlockType 的 ExtraAttributeTier 读取 */
	protected ExtraFactoryTier meTier;

	public TileEntityExtraMekApiaryFactory(Holder<Block> blockProvider, BlockPos pos, BlockState state) {
		super(blockProvider, pos, state);
	}

	/**
	 * 重写 presetVariables — 在 super() 构造期间从 BlockType 读取 ExtraFactoryTier
	 * <br/>
	 * 调用 super 会执行 {@link TileEntityMekApiaryFactory#presetVariables()}，由于 ME 蜂箱 BlockType
	 * 没有 {@code AttributeTier}（而是 {@code ExtraAttributeTier}），父类设置的 {@code tier} 为 null，
	 * 符合预期（ME 版本不使用 FactoryTier）。
	 * <p>
	 * 调用时机：在 {@code TileEntityMekanism} 构造函数中，早于 {@code getInitialInventory()} 和
	 * {@code getInitialFluidTanks()}。因此 {@code meTier} 字段在 {@link #createSlotManager()} 被调用时已初始化。
	 */
	@Override
	protected void presetVariables() {
		super.presetVariables();
		// 从 BlockType 的 ExtraAttributeTier 读取 ME 工厂等级
		meTier = ExtraAttribute.getAdvancedTier(getBlockHolder(), ExtraFactoryTier.class);
	}

	/**
	 * 重写 createSlotManager — 返回 ME 等级参数的 ApiarySlotManager
	 * <br/>
	 * 使用 {@link FactoryApiaryConfig#forMETier(ExtraFactoryTier)} 获取 ME 等级的槽位配置，
	 * 而非父类的 {@link FactoryApiaryConfig#forTier(FactoryTier)}（因父类 tier 为 null 会回退到 Basic）。
	 * 调用时机：super() 构造期间通过 slotManager() → createSlotManager() 调用，
	 * 此时 meTier 已通过 presetVariables() 初始化。
	 */
	@Override
	protected ApiarySlotManager createSlotManager() {
		FactoryApiaryConfig config = FactoryApiaryConfig.forMETier(meTier);
		return new ApiarySlotManager(this,
				config.beeSlotCount, config.beeCols, config.beeRows,
				config.outputSlotsPerPage, config.outputCols, config.outputRows, config.outputPageCount,
				config.fluidTankCapacity);
	}

	/**
	 * 重写 createFeederSlotManager — 返回 ME 等级参数的 FeederSlotManager
	 * <br/>
	 * ME 等级喂食槽数量由 {@link FactoryApiaryConfig#forMETier} 按 ceil(max(蜂蜂数,9)/3)*3 计算。
	 */
	@Override
	protected FeederSlotManager createFeederSlotManager() {
		FactoryApiaryConfig config = FactoryApiaryConfig.forMETier(meTier);
		return new FeederSlotManager(config.feederSlotCount, config.feederCols, config.feederRows);
	}

	// ===== ITier 接口实现 =====

	/**
	 * 重写 getBaseTier — ME 等级不使用原版 BaseTier 体系
	 * <br/>
	 * {@link BaseTier} 枚举只有 BASIC/ADVANCED/ELITE/ULTIMATE/CREATIVE，无 ME 对应值
	 * （ABSOLUTE/SUPREME/COSMIC/INFINITE）。ME 等级均高于 ULTIMATE：
	 * <ul>
	 *   <li>ABSOLUTE/SUPREME/COSMIC：返回 ULTIMATE（仍可被 tier installer 升级）</li>
	 *   <li>INFINITE（最高级）：返回 CREATIVE，使 maximum tier installer 识别为最高级不再尝试升级，
	 *       避免 upgradeResult 返回自身但 baseTier != maxTier 导致的无限循环假死</li>
	 * </ul>
	 * meTier 为 null 时（异常情况）返回 BASIC。
	 */
	@Override
	public BaseTier getBaseTier() {
		if (meTier == null) return BaseTier.BASIC;
		return meTier == ExtraFactoryTier.INFINITE ? BaseTier.CREATIVE : BaseTier.ULTIMATE;
	}

	/**
	 * 重写 getTier — ME 等级不使用 FactoryTier
	 * <br/>
	 * ME 版本的等级通过 {@link #meTier} 字段（{@link ExtraFactoryTier} 类型）处理，
	 * 父类的 {@code tier} 字段为 null，此方法返回 null 以避免误用。
	 */
	@Override
	public FactoryTier getTier() {
		return null;
	}

	/**
	 * 获取 ME 工厂等级 — 供 GUI/BlockItem 使用
	 *
	 * @return ME 工厂等级（ABSOLUTE/SUPREME/COSMIC/INFINITE）
	 */
	public ExtraFactoryTier getMETier() {
		return meTier;
	}

	// ===== GUI 布局参数 getter（供客户端 Screen 使用） =====
	// 重写父类方法，使用 forMETier(meTier) 而非 forTier(tier)（因父类 tier 为 null 会回退到 Basic）

	@Override
	public int getBeeCols() {
		return FactoryApiaryConfig.forMETier(meTier).beeCols;
	}

	@Override
	public int getBeeRows() {
		return FactoryApiaryConfig.forMETier(meTier).beeRows;
	}

	@Override
	public int getOutputCols() {
		return FactoryApiaryConfig.forMETier(meTier).outputCols;
	}

	@Override
	public int getOutputRows() {
		return FactoryApiaryConfig.forMETier(meTier).outputRows;
	}
}
