package com.ayoshiko.productivebeesgenesis.apiculture.core;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.*;

/** 网络内容单独注册，避免给机器等级注册器增加新的职责。 */
public final class NetworkContent {
	private static final String MOD = "productivebeesgenesis";
	private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MOD);
	private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD);
	private static final DeferredRegister<BlockEntityType<?>> TILES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MOD);
	private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, MOD);
	public static final DeferredBlock<NetworkCoreBlock> CORE = BLOCKS.register("bee_network_core", NetworkCoreBlock::new);
	public static final DeferredItem<BlockItem> CORE_ITEM = ITEMS.register("bee_network_core", () -> new BlockItem(CORE.get(), new Item.Properties()));
	public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<NetworkCoreBlockEntity>> CORE_TILE = TILES.register("bee_network_core", () -> BlockEntityType.Builder.of(NetworkCoreBlockEntity::new, CORE.get()).build(null));
	public static final DeferredHolder<MenuType<?>, MenuType<NetworkCoreMenu>> CORE_MENU = MENUS.register("bee_network_core", () -> IMenuTypeExtension.create(NetworkCoreMenu::new));
	public static void register(IEventBus bus) { BLOCKS.register(bus); ITEMS.register(bus); TILES.register(bus); MENUS.register(bus); }
	private NetworkContent() { }
}
