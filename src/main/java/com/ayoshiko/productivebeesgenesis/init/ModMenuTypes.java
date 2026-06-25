package com.ayoshiko.productivebeesgenesis.init;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.menu.EMExtraMekCentrifugeFactoryContainer;
import com.ayoshiko.productivebeesgenesis.menu.ExtraMekCentrifugeFactoryContainer;
import com.ayoshiko.productivebeesgenesis.menu.MekCentrifugeContainer;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityEMExtraMekCentrifugeFactory;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityExtraMekCentrifugeFactory;
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
 */
public final class ModMenuTypes {

    private static final ContainerTypeDeferredRegister MENU_TYPES =
            new ContainerTypeDeferredRegister(ProductiveBeesGenesis.MOD_ID);

    /** 基础MEK离心机MenuType */
    public static final ContainerTypeRegistryObject<MekanismTileContainer<TileEntityMekCentrifuge>> MEK_CENTRIFUGE =
            registerMachineContainer("mek_centrifuge", TileEntityMekCentrifuge.class);

    /** 工厂版MEK离心机MenuType（所有等级共用） */
    public static final ContainerTypeRegistryObject<MekanismTileContainer<TileEntityFactory<?>>> MEK_CENTRIFUGE_FACTORY =
            registerFactoryContainer();

    /** ME扩展版离心机工厂MenuType（ABSOLUTE/SUPREME/COSMIC/INFINITE共用） */
    public static final ContainerTypeRegistryObject<MekanismTileContainer<TileEntityExtraMekCentrifugeFactory>> EXTRA_MEK_CENTRIFUGE_FACTORY =
            registerExtraFactoryContainer();

    /** EME扩展版离心机工厂MenuType（ABSOLUTE_OVERCLOCKED/SUPREME_QUANTUM/COSMIC_DENSE/INFINITE_MULTIVERSAL共用） */
    public static final ContainerTypeRegistryObject<MekanismTileContainer<TileEntityEMExtraMekCentrifugeFactory>> EMEXTRA_MEK_CENTRIFUGE_FACTORY =
            registerEMExtraFactoryContainer();

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

    /** 注册工厂Container — 使用TileEntityFactory.class作为通用类型 */
    private static ContainerTypeRegistryObject<MekanismTileContainer<TileEntityFactory<?>>> registerFactoryContainer() {
        String name = "mek_centrifuge_factory";
        ContainerTypeRegistryObject<MekanismTileContainer<TileEntityFactory<?>>> holder = new ContainerTypeRegistryObject<>(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, name));
        MENU_TYPES.registerMenu(name, () -> MekanismContainerType.tile(TileEntityFactory.class,
                (id, inv, tile) -> new MekCentrifugeContainer<>(holder, id, inv, tile)));
        return holder;
    }

    /** 注册ME扩展版工厂Container — 使用TileEntityExtraMekCentrifugeFactory.class */
    private static ContainerTypeRegistryObject<MekanismTileContainer<TileEntityExtraMekCentrifugeFactory>> registerExtraFactoryContainer() {
        String name = "extra_mek_centrifuge_factory";
        ContainerTypeRegistryObject<MekanismTileContainer<TileEntityExtraMekCentrifugeFactory>> holder = new ContainerTypeRegistryObject<>(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, name));
        MENU_TYPES.registerMenu(name, () -> MekanismContainerType.tile(TileEntityExtraMekCentrifugeFactory.class,
                (id, inv, tile) -> new ExtraMekCentrifugeFactoryContainer(holder, id, inv, tile)));
        return holder;
    }

    /** 注册EME扩展版工厂Container — 使用TileEntityEMExtraMekCentrifugeFactory.class */
    private static ContainerTypeRegistryObject<MekanismTileContainer<TileEntityEMExtraMekCentrifugeFactory>> registerEMExtraFactoryContainer() {
        String name = "emextra_mek_centrifuge_factory";
        ContainerTypeRegistryObject<MekanismTileContainer<TileEntityEMExtraMekCentrifugeFactory>> holder = new ContainerTypeRegistryObject<>(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, name));
        MENU_TYPES.registerMenu(name, () -> MekanismContainerType.tile(TileEntityEMExtraMekCentrifugeFactory.class,
                (id, inv, tile) -> new EMExtraMekCentrifugeFactoryContainer(holder, id, inv, tile)));
        return holder;
    }

    /** 注册到事件总线 */
    public static void register(IEventBus eventBus) {
        MENU_TYPES.register(eventBus);
    }
}
