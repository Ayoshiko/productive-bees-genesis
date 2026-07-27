package com.ayoshiko.productivebeesgenesis.compat.emextras;

import io.github.masyumero.emextras.common.block.attribute.EMExtraAttribute;
import io.github.masyumero.emextras.common.tier.EMExtraFactoryTier;

import mekanism.api.tier.BaseTier;
import mekanism.common.tier.FactoryTier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import com.ayoshiko.productivebeesgenesis.apiary.ApiarySlotManager;
import com.ayoshiko.productivebeesgenesis.apiary.FactoryApiaryConfig;
import com.ayoshiko.productivebeesgenesis.apiary.FeederSlotManager;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiaryFactory;

/**
 * EME 扩展版工厂蜂箱方块实体
 * <br/>
 * 继承 {@link TileEntityMekApiaryFactory}，复用蜜蜂生产逻辑（ApiaryTickHandler）。
 * 不继承 EME 工厂基类（{@code TileEntityEMExtraFactory}），因为蜂箱不走 Mekanism CachedRecipe 管线。
 * <p>
 * 关键设计：
 * 1. 仅用 {@link EMExtraFactoryTier} 标记等级（通过 {@link EMExtraAttribute#getEMExtraTier} 从 BlockType 读取）
 * 2. 父类的 {@code tier} 字段（FactoryTier）在 EME 版本中为 null，所有等级逻辑通过 {@code emeTier} 字段处理
 * 3. 槽位配置使用 {@link FactoryApiaryConfig#forEMETier(EMExtraFactoryTier)} 获取 EME 等级参数
 * 4. GUI 与原版工厂共用 {@code ModMenuTypes.MEK_APIARY_FACTORY}
 * <p>
 * EME 工厂蜂箱参数表（{@link FactoryApiaryConfig}）：
 * <ul>
 *   <li>Absolute Overclocked: 45 蜜蜂(9×5)/33 输出(3×11)/45 喂食(3×15)/2,304,000 mB</li>
 *   <li>Supreme Quantum: 51 蜜蜂(17×3)/36 输出(3×12)/51 喂食(3×17)/2,560,000 mB</li>
 *   <li>Cosmic Dense: 55 蜜蜂(11×5)/39 输出(3×13)/57 喂食(3×19)/2,816,000 mB</li>
 *   <li>Infinite Multiversal: 60 蜜蜂(12×5)/42 输出(3×14)/60 喂食(3×20)/3,072,000 mB</li>
 * </ul>
 */
public class TileEntityEMExtraMekApiaryFactory extends TileEntityMekApiaryFactory {

	/** EME 工厂等级 — 在 presetVariables() 中从 BlockType 的 EMExtraAttributeTier 读取 */
	protected EMExtraFactoryTier emeTier;

	public TileEntityEMExtraMekApiaryFactory(Holder<Block> blockProvider, BlockPos pos, BlockState state) {
		super(blockProvider, pos, state);
	}

	/**
	 * 重写 presetVariables — 从 BlockType 读取 EME 等级
	 * <br/>
	 * 调用父类 presetVariables()（会将 tier 设为 null，因为 EME 方块无 AttributeTier&lt;FactoryTier&gt;），
	 * 然后通过 {@link EMExtraAttribute#getEMExtraTier} 读取 EMExtraAttributeTier&lt;EMExtraFactoryTier&gt;。
	 * 调用时机：在 TileEntityMekanism 构造函数中，早于 getInitialInventory() 和 getInitialFluidTanks()，
	 * 因此 emeTier 字段在 createSlotManager() 被调用时已初始化。
	 */
	@Override
	protected void presetVariables() {
		super.presetVariables();
		// 父类 presetVariables 会将 tier（FactoryTier）设为 null（EME 方块无 AttributeTier<FactoryTier>）
		// 从 BlockType 读取 EME 等级
		emeTier = EMExtraAttribute.getEMExtraTier(getBlockHolder(), EMExtraFactoryTier.class);
	}

	/**
	 * 重写 createSlotManager — 使用 EME 等级参数
	 * <br/>
	 * 通过 {@link FactoryApiaryConfig#forEMETier(EMExtraFactoryTier)} 获取 EME 等级的
	 * 蜜蜂槽/输出槽数量和流体罐容量，替代父类的 {@link FactoryApiaryConfig#forTier(FactoryTier)}。
	 */
	@Override
	protected ApiarySlotManager createSlotManager() {
		FactoryApiaryConfig config = FactoryApiaryConfig.forEMETier(emeTier);
		return new ApiarySlotManager(this,
				config.beeSlotCount, config.beeCols, config.beeRows,
				config.outputSlotCount, config.outputCols, config.outputRows,
				config.fluidTankCapacity);
	}

	/**
	 * 重写 createFeederSlotManager — 使用 EME 等级参数
	 * <br/>
	 * EME 等级喂食槽数量由 {@link FactoryApiaryConfig#forEMETier} 按 ceil(max(蜂蜂数,9)/3)*3 计算。
	 */
	@Override
	protected FeederSlotManager createFeederSlotManager() {
		FactoryApiaryConfig config = FactoryApiaryConfig.forEMETier(emeTier);
		return new FeederSlotManager(config.feederSlotCount, config.feederCols, config.feederRows);
	}

	// ===== ITier 接口实现 =====

	/**
	 * 重写 getBaseTier — EME 等级不使用 BaseTier 体系
	 * <br/>
	 * {@link EMExtraFactoryTier} 仅实现 {@code IEMExtraTier}（无 getBaseTier() 方法），
	 * EME 等级高于 ULTIMATE：
	 * <ul>
	 *   <li>ABSOLUTE_OVERCLOCKED/SUPREME_QUANTUM/COSMIC_DENSE：返回 ULTIMATE（仍可被 tier installer 升级）</li>
	 *   <li>INFINITE_MULTIVERSAL（最高级）：返回 CREATIVE，使 maximum tier installer 识别为最高级不再尝试升级，
	 *       避免 upgradeResult 返回自身但 baseTier != maxTier 导致的无限循环假死</li>
	 * </ul>
	 * emeTier 为 null 时返回 BASIC（与父类行为一致）。
	 */
	@Override
	public BaseTier getBaseTier() {
		if (emeTier == null) return BaseTier.BASIC;
		return emeTier == EMExtraFactoryTier.INFINITE_MULTIVERSAL ? BaseTier.CREATIVE : BaseTier.ULTIMATE;
	}

	/**
	 * 重写 getTier — EME 等级不使用 FactoryTier
	 * <br/>
	 * 返回 null，所有等级相关逻辑通过 emeTier 字段处理。
	 * 父类的 tier 字段在 EME 版本中为 null（presetVariables 未找到 AttributeTier&lt;FactoryTier&gt;）。
	 */
	@Override
	public FactoryTier getTier() {
		return null;
	}

	/**
	 * 获取 EME 工厂等级 — 供外部隔离类（如 ApiaryTierMultiplierResolverDelegate）使用
	 * <br/>
	 * 通过 getter 访问避免外部类直接读取 {@code emeTier} 字段，
	 * 使隔离类（apiary 包内、不引用 EME 类）无需直接字段访问。
	 *
	 * @return EME 工厂等级（ABSOLUTE_OVERCLOCKED/SUPREME_QUANTUM/COSMIC_DENSE/INFINITE_MULTIVERSAL）
	 */
	public EMExtraFactoryTier getEMETier() {
		return emeTier;
	}

	// ===== GUI 布局参数 getter（供客户端 Screen 使用） =====
	// 重写父类 getter，使用 emeTier 而非 tier（tier 在 EME 版本中为 null，
	// 父类 getter 会回退到 Basic 配置，导致 GUI 布局错误）

	@Override
	public int getBeeCols() {
		return FactoryApiaryConfig.forEMETier(emeTier).beeCols;
	}

	@Override
	public int getBeeRows() {
		return FactoryApiaryConfig.forEMETier(emeTier).beeRows;
	}

	@Override
	public int getOutputCols() {
		return FactoryApiaryConfig.forEMETier(emeTier).outputCols;
	}

	@Override
	public int getOutputRows() {
		return FactoryApiaryConfig.forEMETier(emeTier).outputRows;
	}
}
