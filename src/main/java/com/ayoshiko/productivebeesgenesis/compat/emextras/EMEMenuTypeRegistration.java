package com.ayoshiko.productivebeesgenesis.compat.emextras;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.init.ModMenuTypes;
import com.ayoshiko.productivebeesgenesis.menu.EMExtraMekCentrifugeFactoryContainer;

import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.inventory.container.type.MekanismContainerType;
import mekanism.common.registration.impl.ContainerTypeRegistryObject;

/**
 * EvolvedMekanismExtras (EME) MenuType 注册隔离类
 * <br/>
 * 将 EME 扩展版离心机工厂的 MenuType 注册逻辑从 {@link ModMenuTypes} 中抽取至此，
 * 使 {@link ModMenuTypes} 不再直接 import EME 的 {@link TileEntityEMExtraMekCentrifugeFactory}，
 * 避免未安装 EME 时触发类加载导致 {@link NoClassDefFoundError}（修复 v2.0.0 依赖缺失崩溃）。
 * <p>
 * 本类直接引用 EME 的类（{@link TileEntityEMExtraMekCentrifugeFactory} 等），
 * 因为仅在 EME 加载时由 {@link EMECompatLoader} 调用。
 * <p>
 * 注册结果存放在 {@link #EME_CENTRIFUGE_FACTORY} 公开静态字段，
 * 供 {@link MekCentrifugeEMEBlockType} 的 withGui() 引用，以及客户端 Screen 注册使用。
 */
public final class EMEMenuTypeRegistration {

	/** EME 扩展版离心机工厂 MenuType（ABSOLUTE_OVERCLOCKED/SUPREME_QUANTUM/COSMIC_DENSE/INFINITE_MULTIVERSAL 共用） */
	public static ContainerTypeRegistryObject<MekanismTileContainer<TileEntityEMExtraMekCentrifugeFactory>> EME_CENTRIFUGE_FACTORY;

	private EMEMenuTypeRegistration() {}

	/**
	 * 注册 EME 扩展版离心机工厂 MenuType
	 * <br/>
	 * 使用 {@link ModMenuTypes#MENU_TYPES} 延迟注册器注册，
	 * 必须在 {@link ModMenuTypes#register(net.neoforged.bus.api.IEventBus)} 之前调用。
	 */
	public static void registerFactoryMenuType() {
		String name = "emextra_mek_centrifuge_factory";
		EME_CENTRIFUGE_FACTORY = new ContainerTypeRegistryObject<>(
				net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, name));
		ModMenuTypes.MENU_TYPES.registerMenu(name, () -> MekanismContainerType.tile(TileEntityEMExtraMekCentrifugeFactory.class,
				(id, inv, tile) -> new EMExtraMekCentrifugeFactoryContainer(EME_CENTRIFUGE_FACTORY, id, inv, tile)));
	}
}
