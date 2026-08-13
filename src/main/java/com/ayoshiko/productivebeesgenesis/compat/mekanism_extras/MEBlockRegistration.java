package com.ayoshiko.productivebeesgenesis.compat.mekanism_extras;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.apiary.MekApiaryBlock;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeBlock;
import com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks;
import com.jerry.mekextras.common.content.blocktype.ExtraMachine;
import com.jerry.mekextras.common.tier.ExtraFactoryTier;
import mekanism.common.content.blocktype.BlockTypeTile;
import net.neoforged.neoforge.registries.DeferredBlock;

/**
	 * Mekanism Extras (ME) 方块注册隔离类
	 * <br/>
	 * 将 ME 工厂方块（离心机 + 蜂箱）的注册逻辑从 {@link ModBlocks} 中抽取至此，
	 * 使 {@link ModBlocks} 不再直接 import ME 的类。
	 * <br/>
	 * 本类直接引用 ME 的 {@link ExtraFactoryTier}、{@link ExtraMachine} 等类，
	 * 因为仅在 ME 加载时由 {@link MECompatLoader} 调用，不会触发 ME 未加载时的类加载问题。
	 * <p>
	 * 注册结果填充到 {@link ModBlocks#ME_FACTORIES} 和 {@link ModBlocks#ME_APIARY_FACTORIES}，
	 * 保持与原 ModBlocks 相同的 Map 键值类型。
	 */
public final class MEBlockRegistration {

	private MEBlockRegistration() {}

	/**
	 * 注册 ME 等级的离心机工厂方块
	 * <br/>
	 * 遍历 4 个 {@link ExtraFactoryTier}（ABSOLUTE/SUPREME/COSMIC/INFINITE），
	 * 为每个 tier 注册一个 MekCentrifugeBlock。
	 * 注册名格式：{tier.getAdvanceTier().getLowerName()}_extra_mek_centrifuge_factory，
	 * 与 MekCentrifugeMEBlockType 中的命名约定一致，确保 ExtraAttributeUpgradeable 的 DeferredHolder 能正确解析。
	 */
	public static void registerFactories() {
		if (!MekCompatHooks.isMekanismExtrasLoaded()) {
			return;
		}
		for (ExtraFactoryTier tier : ExtraFactoryTier.values()) {
			String registryName = tier.getAdvanceTier().getLowerName() + "_extra_mek_centrifuge_factory";
			ExtraMachine.ExtraFactoryMachine<TileEntityExtraMekCentrifugeFactory> blockType = MekCentrifugeMEBlockType.getMEFactoryType(tier);
			if (blockType == null) {
				ProductiveBeesGenesis.LOGGER.warn("ME工厂BlockType未初始化，跳过方块注册: {}", tier.name());
				continue;
			}
			DeferredBlock<MekCentrifugeBlock<TileEntityExtraMekCentrifugeFactory,
				ExtraMachine.ExtraFactoryMachine<TileEntityExtraMekCentrifugeFactory>>> deferredBlock =
					ModBlocks.BLOCKS.register(registryName, () -> new MekCentrifugeBlock<>(blockType));
			ModBlocks.ME_FACTORIES.put(tier, deferredBlock);
		}
	}

	/**
	 * 注册 ME 等级的蜂箱工厂方块
	 * <br/>
	 * 遍历 4 个 {@link ExtraFactoryTier}（ABSOLUTE/SUPREME/COSMIC/INFINITE），
	 * 为每个 tier 注册一个 MekApiaryBlock。
	 * 注册名格式：{tier.getAdvanceTier().getLowerName()}_extra_mek_apiary_factory，
	 * 与 MekApiaryMEBlockType 中的命名约定一致，确保 ExtraAttributeUpgradeable 的 DeferredHolder 能正确解析。
	 */
	public static void registerApiaryFactories() {
		if (!MekCompatHooks.isMekanismExtrasLoaded()) {
			return;
		}
		for (ExtraFactoryTier tier : ExtraFactoryTier.values()) {
			String registryName = tier.getAdvanceTier().getLowerName() + "_extra_mek_apiary_factory";
			BlockTypeTile<TileEntityExtraMekApiaryFactory> blockType = MekApiaryMEBlockType.getMEApiaryFactoryType(tier);
			if (blockType == null) {
				ProductiveBeesGenesis.LOGGER.warn("ME 蜂箱工厂 BlockType 未初始化，跳过方块注册: {}", tier.name());
				continue;
			}
			DeferredBlock<MekApiaryBlock<TileEntityExtraMekApiaryFactory,
				BlockTypeTile<TileEntityExtraMekApiaryFactory>>> deferredBlock =
					ModBlocks.BLOCKS.register(registryName, () -> new MekApiaryBlock<>(blockType));
			ModBlocks.ME_APIARY_FACTORIES.put(tier, deferredBlock);
		}
	}
}
