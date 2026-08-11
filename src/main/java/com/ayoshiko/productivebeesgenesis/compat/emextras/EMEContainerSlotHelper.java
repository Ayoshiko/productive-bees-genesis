package com.ayoshiko.productivebeesgenesis.compat.emextras;

import com.ayoshiko.productivebeesgenesis.apiary.FactoryApiaryConfig;
import io.github.masyumero.emextras.common.block.attribute.EMExtraAttributeTier;
import io.github.masyumero.emextras.common.tier.EMExtraFactoryTier;
import mekanism.common.block.attribute.Attribute;
import net.minecraft.world.level.block.Block;

/**
	 * EvolvedMekanismExtras (EME) 容器槽位查询辅助类
	 * <br/>
	 * 封装 EME 相关的进程数/流体容量/输出槽数查询逻辑，
	 * 避免调用方直接 import EME 类，实现软依赖完全隔离。
	 * <p>
	 * 调用约定：仅在 {@code MekCompatHooks.isEvolvedMekanismExtrasLoaded()} 为 true 时调用本类方法。
	 * 本类内部直接引用 EME 类，但仅在 EME 已加载时被调用，不会触发 NoClassDefFoundError。
	 */
public final class EMEContainerSlotHelper {

	private EMEContainerSlotHelper() {}

	/**
	 * 获取 EME 工厂的进程数
	 * <br/>
	 * 通过 {@link EMExtraAttributeTier} 识别 EME 工厂，返回对应等级的进程数。
	 *
	 * @param block 方块实例
	 * @return EME 工厂的进程数，非 EME 工厂返回 0
	 */
	public static int getProcesses(Block block) {
		EMExtraAttributeTier<?> emeAttrTier = Attribute.get(block, EMExtraAttributeTier.class);
		if (emeAttrTier != null && emeAttrTier.tier() instanceof EMExtraFactoryTier emeft) {
			return emeft.processes;
		}
		return 0;
	}

	/**
	 * 获取 EME 工厂的流体罐容量
	 * <br/>
	 * 通过 {@link EMExtraAttributeTier} 识别 EME 工厂，查询 {@link FactoryApiaryConfig#forEMETier} 获取容量。
	 *
	 * @param block 方块实例
	 * @return EME 工厂的流体罐容量，非 EME 工厂返回 -1（调用方使用默认值）
	 */
	public static int getFluidCapacity(Block block) {
		EMExtraAttributeTier<?> emeAttrTier = Attribute.get(block, EMExtraAttributeTier.class);
		if (emeAttrTier != null && emeAttrTier.tier() instanceof EMExtraFactoryTier emeft) {
			return FactoryApiaryConfig.forEMETier(emeft).fluidTankCapacity;
		}
		return -1;
	}

	/**
	 * 获取 EME 工厂的输出槽数量
	 * <br/>
	 * 通过 {@link EMExtraAttributeTier} 识别 EME 工厂，查询 {@link FactoryApiaryConfig#forEMETier} 获取槽位数。
	 *
	 * @param block 方块实例
	 * @return EME 工厂的输出槽数量，非 EME 工厂返回 -1（调用方使用默认值）
	 */
	public static int getOutputSlotCount(Block block) {
		EMExtraAttributeTier<?> emeAttrTier = Attribute.get(block, EMExtraAttributeTier.class);
		if (emeAttrTier != null && emeAttrTier.tier() instanceof EMExtraFactoryTier emeft) {
			return FactoryApiaryConfig.forEMETier(emeft).outputSlotCount;
		}
		return -1;
	}

	/**
	 * 获取 EME 工厂蜂箱的蜜蜂槽位数量
	 * <br/>
	 * 通过 {@link EMExtraAttributeTier} 识别 EME 工厂蜂箱，查询 {@link FactoryApiaryConfig#forEMETier} 获取蜜蜂槽数量。
	 * <p>
	 * 用途：合成升级配方（ApiaryShapedRecipe）合并多输入蜜蜂时确定目标容量上限。
	 *
	 * @param block 方块实例
	 * @return EME 工厂蜂箱的蜜蜂槽数量，非 EME 工厂蜂箱返回 0（调用方使用默认值）
	 */
	public static int getBeeSlotCount(Block block) {
		EMExtraAttributeTier<?> emeAttrTier = Attribute.get(block, EMExtraAttributeTier.class);
		if (emeAttrTier != null && emeAttrTier.tier() instanceof EMExtraFactoryTier emeft) {
			return FactoryApiaryConfig.forEMETier(emeft).beeSlotCount;
		}
		return 0;
	}
}
