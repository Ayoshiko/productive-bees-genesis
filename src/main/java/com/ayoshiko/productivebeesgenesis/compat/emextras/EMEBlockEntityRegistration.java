package com.ayoshiko.productivebeesgenesis.compat.emextras;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.init.ModBlockEntities;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks;
import io.github.masyumero.emextras.common.tier.EMExtraFactoryTier;
import mekanism.common.capabilities.Capabilities;
import mekanism.common.registration.impl.TileEntityTypeRegistryObject;
import mekanism.common.tile.base.TileEntityMekanism;
import net.neoforged.neoforge.registries.DeferredBlock;

/**
	 * EvolvedMekanismExtras (EME) 方块实体注册隔离类
	 * <br/>
	 * 将 EME 工厂 BlockEntityType（离心机 + 蜂箱）的注册逻辑从 {@link ModBlockEntities} 中抽取至此，
	 * 使 {@link ModBlockEntities} 不再直接 import EME 的类。
	 * <br/>
	 * 本类直接引用 EME 的 {@link EMExtraFactoryTier} 等类，
	 * 因为仅在 EME 加载时由 {@link EMECompatLoader} 调用。
	 * <p>
	 * 注册结果填充到 {@link MekCentrifugeEMEBlockType#EME_FACTORY_TILES} 和
	 * {@link MekApiaryEMEBlockType#EME_APIARY_FACTORY_TILES}，供 BlockType 的懒加载 Supplier 使用。
	 * <p>
	 * 适配说明：{@link ModBlocks#getEMEFactoryBlock(Object)} 返回通配类型 {@code DeferredBlock<?>}，
	 * 本类调用 mekBuilder 时由 Mekanism API 接受 {@code DeferredHolder<Block, ?>}，无需强制类型转换。
	 */
public final class EMEBlockEntityRegistration {

	private EMEBlockEntityRegistration() {}

	/**
	 * 注册 EME 等级的离心机工厂 BlockEntityType
	 * <br/>
	 * 遍历 4 个 {@link EMExtraFactoryTier}（ABSOLUTE_OVERCLOCKED/SUPREME_QUANTUM/COSMIC_DENSE/INFINITE_MULTIVERSAL），
	 * 为每个 tier 注册独立的 BlockEntityType。
	 * 使用 TileEntityEMExtraMekCentrifugeFactory 作为 TileEntity 类，配置 server/client ticker 和 CONFIG_CARD。
	 * 注册结果填充到 {@link MekCentrifugeEMEBlockType#EME_FACTORY_TILES}，
	 * 供 MekCentrifugeEMEBlockType.createEMEFactoryBlockType() 的懒加载 Supplier 使用。
	 */
	public static void registerFactoryTiles() {
		if (!MekCompatHooks.isEvolvedMekanismExtrasLoaded()) {
			return;
		}
		for (EMExtraFactoryTier tier : EMExtraFactoryTier.values()) {
			DeferredBlock<?> deferredBlock = ModBlocks.getEMEFactoryBlock(tier);
			if (deferredBlock == null) {
				ProductiveBeesGenesis.LOGGER.warn("EME工厂方块未注册，跳过TileEntity注册: {}", tier.name());
				continue;
			}
			TileEntityTypeRegistryObject<TileEntityEMExtraMekCentrifugeFactory> tileType =
					ModBlockEntities.BLOCK_ENTITIES.mekBuilder(deferredBlock,
							(pos, state) -> new TileEntityEMExtraMekCentrifugeFactory(deferredBlock, pos, state))
							.serverTicker((level, pos, state, tile) -> TileEntityMekanism.tickServer(level, pos, state, tile))
							.clientTicker((level, pos, state, tile) -> TileEntityMekanism.tickClient(level, pos, state, tile))
							.withSimple(Capabilities.CONFIG_CARD)
							.build();
			MekCentrifugeEMEBlockType.EME_FACTORY_TILES.put(tier, tileType);
		}
	}

	/**
	 * 注册 EME 等级的蜂箱工厂 BlockEntityType
	 * <br/>
	 * 遍历 4 个 {@link EMExtraFactoryTier}（ABSOLUTE_OVERCLOCKED/SUPREME_QUANTUM/COSMIC_DENSE/INFINITE_MULTIVERSAL），
	 * 为每个 tier 注册独立的 BlockEntityType。
	 * 使用 TileEntityEMExtraMekApiaryFactory 作为 TileEntity 类，配置 server/client ticker 和 CONFIG_CARD。
	 * 注册结果填充到 {@link MekApiaryEMEBlockType#EME_APIARY_FACTORY_TILES}，
	 * 供 MekApiaryEMEBlockType.createEMEApiaryFactoryBlockType() 的懒加载 Supplier 使用。
	 */
	public static void registerApiaryFactoryTiles() {
		if (!MekCompatHooks.isEvolvedMekanismExtrasLoaded()) {
			return;
		}
		for (EMExtraFactoryTier tier : EMExtraFactoryTier.values()) {
			DeferredBlock<?> deferredBlock = ModBlocks.getEMEApiaryFactoryBlock(tier);
			if (deferredBlock == null) {
				ProductiveBeesGenesis.LOGGER.warn("EME 蜂箱工厂方块未注册，跳过 TileEntity 注册: {}", tier.name());
				continue;
			}
			TileEntityTypeRegistryObject<TileEntityEMExtraMekApiaryFactory> tileType =
					ModBlockEntities.BLOCK_ENTITIES.mekBuilder(deferredBlock,
							(pos, state) -> new TileEntityEMExtraMekApiaryFactory(deferredBlock, pos, state))
							.serverTicker((level, pos, state, tile) -> TileEntityMekanism.tickServer(level, pos, state, tile))
							.clientTicker((level, pos, state, tile) -> TileEntityMekanism.tickClient(level, pos, state, tile))
							.withSimple(Capabilities.CONFIG_CARD)
							.build();
			MekApiaryEMEBlockType.EME_APIARY_FACTORY_TILES.put(tier, tileType);
		}
	}
}
