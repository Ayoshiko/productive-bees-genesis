package com.ayoshiko.productivebeesgenesis.init;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.apiary.MekApiaryContainer;
import com.ayoshiko.productivebeesgenesis.apiary.MekApiaryFactoryContainer;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiaryFactory;
import com.ayoshiko.productivebeesgenesis.menu.MekCentrifugeContainer;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifugeFactory;

import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.inventory.container.type.MekanismContainerType;
import mekanism.common.registration.impl.ContainerTypeDeferredRegister;
import mekanism.common.registration.impl.ContainerTypeRegistryObject;
import mekanism.common.tile.factory.TileEntityFactory;
import net.neoforged.bus.api.IEventBus;

/**
 * MenuType注册类
 * <br/>
 * 使用Mekanism的ContainerTypeDeferredRegister注册MenuType。
 * 基础机器和工厂版使用不同的ContainerType，因为它们的Screen不同。
 * <p>
 * ME/EME扩展版离心机工厂的MenuType不在此类直接注册，而是通过
 * {@link com.ayoshiko.productivebeesgenesis.compat.mekanism_extras.MECompatLoader} 和
 * {@link com.ayoshiko.productivebeesgenesis.compat.emextras.EMECompatLoader} 在对应模组加载时
 * 委托至 compat 包下的隔离注册类完成，避免编译期类加载导致 NoClassDefFoundError。
 */
public final class ModMenuTypes {

	/** MenuType 延迟注册器 — public 供 compat 包隔离注册类访问 */
	public static final ContainerTypeDeferredRegister MENU_TYPES =
			new ContainerTypeDeferredRegister(ProductiveBeesGenesis.MOD_ID);

	/** 基础MEK离心机MenuType */
	public static final ContainerTypeRegistryObject<MekanismTileContainer<TileEntityMekCentrifuge>> MEK_CENTRIFUGE =
			registerMachineContainer("mek_centrifuge", TileEntityMekCentrifuge.class);

	/**
	 * MEK通用机械蜂箱MenuType
	 * <br/>
	 * 使用自定义 MekApiaryContainer，重写玩家物品栏偏移以适配蜂箱布局。
	 * 槽位由基类 MekanismTileContainer 自动从方块实体提取。
	 */
	public static final ContainerTypeRegistryObject<MekApiaryContainer> MEK_APIARY =
			registerApiaryContainer("mek_apiary", TileEntityMekApiary.class);

	/**
	 * 工厂版MEK通用机械蜂箱MenuType（所有4等级共用）
	 * <br/>
	 * 使用 MekApiaryFactoryContainer，根据工厂等级动态计算物品栏偏移。
	 * 4个等级工厂（Basic/Advanced/Elite/Ultimate）共用同一 MenuType，
	 * 由 {@link TileEntityMekApiaryFactory#getTier()} 在运行时区分等级。
	 */
	public static final ContainerTypeRegistryObject<MekApiaryFactoryContainer> MEK_APIARY_FACTORY =
			registerApiaryFactoryContainer("mek_apiary_factory", TileEntityMekApiaryFactory.class);

	/** 工厂版MEK离心机MenuType（所有等级共用） */
	public static final ContainerTypeRegistryObject<MekanismTileContainer<TileEntityFactory<?>>> MEK_CENTRIFUGE_FACTORY =
			registerFactoryContainer();

	private ModMenuTypes() {}

	/** 注册基础机器Container */
	private static <TILE extends TileEntityMekCentrifuge> ContainerTypeRegistryObject<MekanismTileContainer<TILE>> registerMachineContainer(
			String name, Class<TILE> tileClass) {
		ContainerTypeRegistryObject<MekanismTileContainer<TILE>> holder = new ContainerTypeRegistryObject<>(
				net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, name));
		MENU_TYPES.registerMenu(name, () -> MekanismContainerType.tile(tileClass,
				(id, inv, tile) -> new MekCentrifugeContainer<>(holder, id, inv, tile)));
		return holder;
	}

	/**
	 * 注册通用机械蜂箱Container
	 * <br/>
	 * 使用 MekApiaryContainer，重写玩家物品栏偏移以适配蜂箱布局。
	 * 槽位由基类 MekanismTileContainer 自动从方块实体提取。
	 */
	private static ContainerTypeRegistryObject<MekApiaryContainer> registerApiaryContainer(
			String name, Class<TileEntityMekApiary> tileClass) {
		ContainerTypeRegistryObject<MekApiaryContainer> holder = new ContainerTypeRegistryObject<>(
				net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, name));
		MENU_TYPES.registerMenu(name, () -> MekanismContainerType.tile(tileClass,
				(id, inv, tile) -> new MekApiaryContainer(holder, id, inv, tile)));
		return holder;
	}

	/**
	 * 注册工厂版通用机械蜂箱Container
	 * <br/>
	 * 4个工厂等级共用同一 ContainerType，由 TileEntityMekApiaryFactory 的 tier 字段在运行时区分。
	 * 物品栏偏移由 MekApiaryFactoryContainer 根据工厂等级动态计算。
	 */
	private static ContainerTypeRegistryObject<MekApiaryFactoryContainer> registerApiaryFactoryContainer(
			String name, Class<TileEntityMekApiaryFactory> tileClass) {
		ContainerTypeRegistryObject<MekApiaryFactoryContainer> holder = new ContainerTypeRegistryObject<>(
				net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, name));
		MENU_TYPES.registerMenu(name, () -> MekanismContainerType.tile(tileClass,
				(id, inv, tile) -> new MekApiaryFactoryContainer(holder, id, inv, tile)));
		return holder;
	}

	/** 注册工厂Container — 使用TileEntityFactory.class作为通用类型 */
	private static ContainerTypeRegistryObject<MekanismTileContainer<TileEntityFactory<?>>> registerFactoryContainer() {
		String name = "mek_centrifuge_factory";
		ContainerTypeRegistryObject<MekanismTileContainer<TileEntityFactory<?>>> holder = new ContainerTypeRegistryObject<>(
				net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, name));
		MENU_TYPES.registerMenu(name, () -> MekanismContainerType.tile(TileEntityFactory.class,
				(id, inv, tile) -> new MekCentrifugeContainer<>(holder, id, inv, tile)));
		return holder;
	}

	/** 注册到事件总线 */
	public static void register(IEventBus eventBus) {
		MENU_TYPES.register(eventBus);
	}
}
