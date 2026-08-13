package com.ayoshiko.productivebeesgenesis.compat.emextras;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.apiary.MekApiaryBlock;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeBlock;
import com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks;
import io.github.masyumero.emextras.common.content.blocktype.EMExtraMachine;
import io.github.masyumero.emextras.common.tier.EMExtraFactoryTier;
import mekanism.common.content.blocktype.BlockTypeTile;
import net.neoforged.neoforge.registries.DeferredBlock;

/**
	 * EvolvedMekanismExtras (EME) 方块注册隔离类
	 * <br/>
	 * 将 EME 工厂方块（离心机 + 蜂箱）的注册逻辑从 {@link ModBlocks} 中抽取至此，
	 * 使 {@link ModBlocks} 不再直接 import EME 的类。
	 * <br/>
	 * 本类直接引用 EME 的 {@link EMExtraFactoryTier}、{@link EMExtraMachine} 等类，
	 * 因为仅在 EME 加载时由 {@link EMECompatLoader} 调用，不会触发 EME 未加载时的类加载问题。
	 * <p>
	 * 注册结果填充到 {@link ModBlocks#EME_FACTORIES} 和 {@link ModBlocks#EME_APIARY_FACTORIES}，
	 * 保持与原 ModBlocks 相同的 Map 键值类型。
	 */
public final class EMEBlockRegistration {

	private EMEBlockRegistration() {}

	/**
	 * 注册 EME 等级的离心机工厂方块
	 * <br/>
	 * 遍历 4 个 {@link EMExtraFactoryTier}（ABSOLUTE_OVERCLOCKED/SUPREME_QUANTUM/COSMIC_DENSE/INFINITE_MULTIVERSAL），
	 * 为每个 tier 注册一个 MekCentrifugeBlock。
	 * 注册名格式：{tier.getEMExtraTier().getLowerName()}_emextra_mek_centrifuge_factory，
	 * 与 MekCentrifugeEMEBlockType 中的命名约定一致，确保 EMExtraAttributeUpgradeable 的 DeferredHolder 能正确解析。
	 */
	public static void registerFactories() {
		if (!MekCompatHooks.isEvolvedMekanismExtrasLoaded()) {
			return;
		}
		for (EMExtraFactoryTier tier : EMExtraFactoryTier.values()) {
			String registryName = tier.getEMExtraTier().getLowerName() + "_emextra_mek_centrifuge_factory";
			EMExtraMachine.EMExtraFactoryMachine<TileEntityEMExtraMekCentrifugeFactory> blockType = MekCentrifugeEMEBlockType.getEMEFactoryType(tier);
			if (blockType == null) {
				ProductiveBeesGenesis.LOGGER.warn("EME工厂BlockType未初始化，跳过方块注册: {}", tier.name());
				continue;
			}
			DeferredBlock<MekCentrifugeBlock<TileEntityEMExtraMekCentrifugeFactory,
				EMExtraMachine.EMExtraFactoryMachine<TileEntityEMExtraMekCentrifugeFactory>>> deferredBlock =
					ModBlocks.BLOCKS.register(registryName, () -> new MekCentrifugeBlock<>(blockType));
			ModBlocks.EME_FACTORIES.put(tier, deferredBlock);
		}
	}

	/**
	 * 注册 EME 等级的蜂箱工厂方块
	 * <br/>
	 * 遍历 4 个 {@link EMExtraFactoryTier}（ABSOLUTE_OVERCLOCKED/SUPREME_QUANTUM/COSMIC_DENSE/INFINITE_MULTIVERSAL），
	 * 为每个 tier 注册一个 MekApiaryBlock。
	 * 注册名格式：{tier.getEMExtraTier().getLowerName()}_emextra_mek_apiary_factory，
	 * 与 MekApiaryEMEBlockType 中的命名约定一致，确保 EMExtraAttributeUpgradeable 的 DeferredHolder 能正确解析。
	 */
	public static void registerApiaryFactories() {
		if (!MekCompatHooks.isEvolvedMekanismExtrasLoaded()) {
			return;
		}
		for (EMExtraFactoryTier tier : EMExtraFactoryTier.values()) {
			String registryName = tier.getEMExtraTier().getLowerName() + "_emextra_mek_apiary_factory";
			BlockTypeTile<TileEntityEMExtraMekApiaryFactory> blockType = MekApiaryEMEBlockType.getEMEApiaryFactoryType(tier);
			if (blockType == null) {
				ProductiveBeesGenesis.LOGGER.warn("EME 蜂箱工厂 BlockType 未初始化，跳过方块注册: {}", tier.name());
				continue;
			}
			DeferredBlock<MekApiaryBlock<TileEntityEMExtraMekApiaryFactory,
				BlockTypeTile<TileEntityEMExtraMekApiaryFactory>>> deferredBlock =
					ModBlocks.BLOCKS.register(registryName, () -> new MekApiaryBlock<>(blockType));
			ModBlocks.EME_APIARY_FACTORIES.put(tier, deferredBlock);
		}
	}
}
