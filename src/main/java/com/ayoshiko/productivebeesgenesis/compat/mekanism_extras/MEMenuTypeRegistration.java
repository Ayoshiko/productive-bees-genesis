package com.ayoshiko.productivebeesgenesis.compat.mekanism_extras;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.init.ModMenuTypes;
import com.ayoshiko.productivebeesgenesis.menu.ExtraMekCentrifugeFactoryContainer;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.inventory.container.type.MekanismContainerType;
import mekanism.common.registration.impl.ContainerTypeRegistryObject;

/**
	 * Mekanism Extras (ME) MenuType 注册隔离类
	 * <br/>
	 * 将 ME 扩展版离心机工厂的 MenuType 注册逻辑从 {@link ModMenuTypes} 中抽取至此，
	 * 使 {@link ModMenuTypes} 不再直接 import ME 的 {@link TileEntityExtraMekCentrifugeFactory}，
	 * 避免未安装 ME 时触发类加载导致 {@link NoClassDefFoundError}。
	 * <p>
	 * 本类直接引用 ME 的类（{@link TileEntityExtraMekCentrifugeFactory} 等），
	 * 因为仅在 ME 加载时由 {@link MECompatLoader} 调用。
	 * <p>
	 * 注册结果存放在 {@link #ME_CENTRIFUGE_FACTORY} 公开静态字段，
	 * 供 {@link com.ayoshiko.productivebeesgenesis.compat.mekanism_extras.MekCentrifugeMEBlockType} 的
	 * withGui() 引用，以及客户端 Screen 注册使用。
	 */
public final class MEMenuTypeRegistration {

	/** ME 扩展版离心机工厂 MenuType（ABSOLUTE/SUPREME/COSMIC/INFINITE 共用） */
	public static ContainerTypeRegistryObject<MekanismTileContainer<TileEntityExtraMekCentrifugeFactory>> ME_CENTRIFUGE_FACTORY;

	private MEMenuTypeRegistration() {}

	/**
	 * 注册 ME 扩展版离心机工厂 MenuType
	 * <br/>
	 * 使用 {@link ModMenuTypes#MENU_TYPES} 延迟注册器注册，
	 * 必须在 {@link ModMenuTypes#register(net.neoforged.bus.api.IEventBus)} 之前调用。
	 */
	public static void registerFactoryMenuType() {
		String name = "extra_mek_centrifuge_factory";
		ME_CENTRIFUGE_FACTORY = new ContainerTypeRegistryObject<>(
				net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, name));
		ModMenuTypes.MENU_TYPES.registerMenu(name, () -> MekanismContainerType.tile(TileEntityExtraMekCentrifugeFactory.class,
				(id, inv, tile) -> new ExtraMekCentrifugeFactoryContainer(ME_CENTRIFUGE_FACTORY, id, inv, tile)));
	}
}
