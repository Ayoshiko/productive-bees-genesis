package com.ayoshiko.productivebeesgenesis.compat.mekanism_extras;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.init.ModBlockEntities;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks;
import com.jerry.mekextras.common.tier.ExtraFactoryTier;
import mekanism.common.capabilities.Capabilities;
import mekanism.common.registration.impl.TileEntityTypeRegistryObject;
import mekanism.common.tile.base.TileEntityMekanism;
import net.neoforged.neoforge.registries.DeferredBlock;

/**
	 * Mekanism Extras (ME) 方块实体注册隔离类
	 * <br/>
	 * 将 ME 工厂 BlockEntityType（离心机 + 蜂箱）的注册逻辑从 {@link ModBlockEntities} 中抽取至此，
	 * 使 {@link ModBlockEntities} 不再直接 import ME 的类。
	 * <br/>
	 * 本类直接引用 ME 的 {@link ExtraFactoryTier} 等类，
	 * 因为仅在 ME 加载时由 {@link MECompatLoader} 调用。
	 * <p>
	 * 注册结果填充到 {@link MekCentrifugeMEBlockType#ME_FACTORY_TILES} 和
	 * {@link MekApiaryMEBlockType#ME_APIARY_FACTORY_TILES}，供 BlockType 的懒加载 Supplier 使用。
	 * <p>
	 * 适配说明：{@link ModBlocks#getMEFactoryBlock(Object)} 返回通配类型 {@code DeferredBlock<?>}，
	 * 本类调用 mekBuilder 时由 Mekanism API 接受 {@code DeferredHolder<Block, ?>}，无需强制类型转换。
	 */
public final class MEBlockEntityRegistration {

	private MEBlockEntityRegistration() {}

	/**
	 * 注册 ME 等级的离心机工厂 BlockEntityType
	 * <br/>
	 * 遍历 4 个 {@link ExtraFactoryTier}（ABSOLUTE/SUPREME/COSMIC/INFINITE），
	 * 为每个 tier 注册独立的 BlockEntityType。
	 * 使用 TileEntityExtraMekCentrifugeFactory 作为 TileEntity 类，配置 server/client ticker 和 CONFIG_CARD。
	 * 注册结果填充到 {@link MekCentrifugeMEBlockType#ME_FACTORY_TILES}，
	 * 供 MekCentrifugeMEBlockType.createMEFactoryBlockType() 的懒加载 Supplier 使用。
	 */
	public static void registerFactoryTiles() {
		if (!MekCompatHooks.isMekanismExtrasLoaded()) {
			return;
		}
		for (ExtraFactoryTier tier : ExtraFactoryTier.values()) {
			DeferredBlock<?> deferredBlock = ModBlocks.getMEFactoryBlock(tier);
			if (deferredBlock == null) {
				ProductiveBeesGenesis.LOGGER.warn("ME工厂方块未注册，跳过TileEntity注册: {}", tier.name());
				continue;
			}
			TileEntityTypeRegistryObject<TileEntityExtraMekCentrifugeFactory> tileType =
					ModBlockEntities.BLOCK_ENTITIES.mekBuilder(deferredBlock,
							(pos, state) -> new TileEntityExtraMekCentrifugeFactory(deferredBlock, pos, state))
							.serverTicker((level, pos, state, tile) -> TileEntityMekanism.tickServer(level, pos, state, tile))
							.clientTicker((level, pos, state, tile) -> TileEntityMekanism.tickClient(level, pos, state, tile))
							.withSimple(Capabilities.CONFIG_CARD)
							.build();
			MekCentrifugeMEBlockType.ME_FACTORY_TILES.put(tier, tileType);
		}
	}

	/**
	 * 注册 ME 等级的蜂箱工厂 BlockEntityType
	 * <br/>
	 * 遍历 4 个 {@link ExtraFactoryTier}（ABSOLUTE/SUPREME/COSMIC/INFINITE），
	 * 为每个 tier 注册独立的 BlockEntityType。
	 * 使用 TileEntityExtraMekApiaryFactory 作为 TileEntity 类，配置 server/client ticker 和 CONFIG_CARD。
	 * 注册结果填充到 {@link MekApiaryMEBlockType#ME_APIARY_FACTORY_TILES}，
	 * 供 MekApiaryMEBlockType.createMEApiaryFactoryBlockType() 的懒加载 Supplier 使用。
	 */
	public static void registerApiaryFactoryTiles() {
		if (!MekCompatHooks.isMekanismExtrasLoaded()) {
			return;
		}
		for (ExtraFactoryTier tier : ExtraFactoryTier.values()) {
			DeferredBlock<?> deferredBlock = ModBlocks.getMEApiaryFactoryBlock(tier);
			if (deferredBlock == null) {
				ProductiveBeesGenesis.LOGGER.warn("ME 蜂箱工厂方块未注册，跳过 TileEntity 注册: {}", tier.name());
				continue;
			}
			TileEntityTypeRegistryObject<TileEntityExtraMekApiaryFactory> tileType =
					ModBlockEntities.BLOCK_ENTITIES.mekBuilder(deferredBlock,
							(pos, state) -> new TileEntityExtraMekApiaryFactory(deferredBlock, pos, state))
							.serverTicker((level, pos, state, tile) -> TileEntityMekanism.tickServer(level, pos, state, tile))
							.clientTicker((level, pos, state, tile) -> TileEntityMekanism.tickClient(level, pos, state, tile))
							.withSimple(Capabilities.CONFIG_CARD)
							.build();
			MekApiaryMEBlockType.ME_APIARY_FACTORY_TILES.put(tier, tileType);
		}
	}
}
