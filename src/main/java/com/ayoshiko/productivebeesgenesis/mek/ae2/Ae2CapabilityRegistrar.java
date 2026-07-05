package com.ayoshiko.productivebeesgenesis.mek.ae2;

import net.minecraft.world.level.block.entity.BlockEntityType;

import appeng.api.AECapabilities;
import appeng.api.networking.IInWorldGridNodeHost;

import com.ayoshiko.productivebeesgenesis.init.ModBlockEntities;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeBlockType;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeEMEBlockType;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeMEBlockType;
import com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks;

import mekanism.common.registration.impl.TileEntityTypeRegistryObject;

import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * AE2 capability 注册器
 * <br/>
 * 单一职责：在 {@link RegisterCapabilitiesEvent} 中为全部离心机 BlockEntityType 注册
 * {@link AECapabilities#IN_WORLD_GRID_NODE_HOST} capability，使 AE2 线缆能通过 NeoForge
 * 的 BlockCapability 机制发现并连接离心机。
 * <p>
 * <b>设计原则（SRP）</b>：本类仅负责 capability 注册，不处理网格节点的生命周期
 * （由 {@link Ae2GridNodeManager} / {@link MekAe2LifecycleHandler} 负责）。
 * 将注册逻辑独立成类避免主类 {@code ProductiveBeesGenesis} 膨胀。
 * <p>
 * <b>覆盖范围</b>：全部 18 个离心机 BlockEntityType：
 * <ul>
 *   <li>基础 1 个：{@code MEK_CENTRIFUGE}</li>
 *   <li>原版 4 个：BASIC/ADVANCED/ELITE/ULTIMATE {@code _MEK_CENTRIFUGE_FACTORY}</li>
 *   <li>EM 5 个：从 {@link MekCentrifugeBlockType.ModBlockEntitiesHolder#EM_FACTORY_TILES} 遍历</li>
 *   <li>ME 4 个：从 {@link MekCentrifugeMEBlockType#ME_FACTORY_TILES} 遍历（ME 已加载时）</li>
 *   <li>EME 4 个：从 {@link MekCentrifugeEMEBlockType#EME_FACTORY_TILES} 遍历（EME 已加载时）</li>
 * </ul>
 * <p>
 * <b>线程安全</b>：注册在 mod 事件总线 {@link RegisterCapabilitiesEvent} 中执行，单线程，无需同步。
 * capability provider lambda 无状态（仅做类型转换），跨线程调用安全。
 * <p>
 * <b>AE2 未安装</b>：本类不会被加载（调用方 {@code ProductiveBeesGenesis} 已通过
 * {@link Ae2IntegrationLoader#isAe2Loaded()} 守卫跳过），即使被加载也会因 AE2 类引用触发
 * {@code NoClassDefFoundError}——这是预期的，因为本类是 AE2 集成的一部分。
 *
 * @since 1.7.0
 * @author Ayoshiko
 */
public final class Ae2CapabilityRegistrar {

	private Ae2CapabilityRegistrar() {}

	/**
	 * 注册全部离心机 BlockEntityType 的 AE2 IN_WORLD_GRID_NODE_HOST capability
	 * <br/>
	 * 在 {@link RegisterCapabilitiesEvent} 中调用，遍历所有离心机 BlockEntityType 并注册
	 * capability provider。provider 通过类型检查确保只有实现了 {@link IAe2OutputHost}
	 * 的 BlockEntity 才返回 {@link IInWorldGridNodeHost} 实例。
	 * <p>
	 * <b>动态工厂安全</b>：EM/ME/EME 的工厂 BlockEntityType 在对应模组加载时才注册。
	 * 本方法在 {@link RegisterCapabilitiesEvent} 触发时（晚于 BlockEntityType 注册）执行，
	 * 此时动态 Map 已就位。通过 {@code isXxxLoaded()} 守卫避免访问未加载模组的类。
	 *
	 * @param event NeoForge capability 注册事件
	 */
	public static void register(RegisterCapabilitiesEvent event) {
		// 1. 基础离心机
		registerForTileEntity(event, ModBlockEntities.MEK_CENTRIFUGE.get());

		// 2. 原版 4 等级工厂
		registerForTileEntity(event, ModBlockEntities.BASIC_MEK_CENTRIFUGE_FACTORY.get());
		registerForTileEntity(event, ModBlockEntities.ADVANCED_MEK_CENTRIFUGE_FACTORY.get());
		registerForTileEntity(event, ModBlockEntities.ELITE_MEK_CENTRIFUGE_FACTORY.get());
		registerForTileEntity(event, ModBlockEntities.ULTIMATE_MEK_CENTRIFUGE_FACTORY.get());

		// 3. EM 5 等级工厂 — ModBlockEntitiesHolder.EM_FACTORY_TILES 使用 Mekanism 的 FactoryTier 作为 key，
		// 可安全直接遍历（Map 在 EM 未加载时为空）
		for (TileEntityTypeRegistryObject<?> tile : MekCentrifugeBlockType.ModBlockEntitiesHolder.EM_FACTORY_TILES.values()) {
			registerForTileEntity(event, tile.get());
		}

		// 4. ME 4 等级工厂 — 仅在 ME 已加载时访问 MekCentrifugeMEBlockType 类（避免 NoClassDefFoundError）
		if (MekCompatHooks.isMekanismExtrasLoaded()) {
			for (TileEntityTypeRegistryObject<?> tile : MekCentrifugeMEBlockType.ME_FACTORY_TILES.values()) {
				registerForTileEntity(event, tile.get());
			}
		}

		// 5. EME 4 等级工厂 — 仅在 EME 已加载时访问 MekCentrifugeEMEBlockType 类（避免 NoClassDefFoundError）
		if (MekCompatHooks.isEvolvedMekanismExtrasLoaded()) {
			for (TileEntityTypeRegistryObject<?> tile : MekCentrifugeEMEBlockType.EME_FACTORY_TILES.values()) {
				registerForTileEntity(event, tile.get());
			}
		}
	}

	/**
	 * 为单个 BlockEntityType 注册 AE2 capability
	 * <br/>
	 * capability provider lambda 返回 {@code (blockEntity, context) -> }：
	 * <ul>
	 *   <li>若 blockEntity 实现了 {@link IAe2OutputHost}，则强转为 {@link IInWorldGridNodeHost} 返回
	 *       （{@link IAe2OutputHost} 继承 {@link IInWorldGridNodeHost}，离心机 BlockEntity 实现该接口）</li>
	 *   <li>否则返回 null（不应发生，但防御性检查）</li>
	 * </ul>
	 * <p>
	 * <b>无状态 lambda</b>：provider 仅做类型转换，无副作用，跨线程调用安全。
	 *
	 * @param event       NeoForge capability 注册事件
	 * @param tileEntityType 离心机 BlockEntityType
	 */
	private static void registerForTileEntity(RegisterCapabilitiesEvent event, BlockEntityType<?> tileEntityType) {
		if (tileEntityType == null) return;
		event.registerBlockEntity(
				AECapabilities.IN_WORLD_GRID_NODE_HOST,
				tileEntityType,
				(blockEntity, context) -> blockEntity instanceof IAe2OutputHost host
						? (IInWorldGridNodeHost) host
						: null
		);
	}
}
