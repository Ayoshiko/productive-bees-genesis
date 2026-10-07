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
	private static final DeferredRegister<net.neoforged.neoforge.attachment.AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, MOD);
	public static final DeferredHolder<net.neoforged.neoforge.attachment.AttachmentType<?>, net.neoforged.neoforge.attachment.AttachmentType<TerminalCursor>> TERMINAL_CURSOR = ATTACHMENTS.register("terminal_cursor",
			() -> net.neoforged.neoforge.attachment.AttachmentType.builder(TerminalCursor::new).serialize(TerminalCursor.SERIALIZER).copyOnDeath().build());
	private static final DeferredRegister<net.minecraft.world.item.crafting.RecipeSerializer<?>> RECIPES = DeferredRegister.create(Registries.RECIPE_SERIALIZER, MOD);
	public static final DeferredBlock<com.ayoshiko.productivebeesgenesis.apiculture.bridge.MeBridgeBlock> ME_BRIDGE = BLOCKS.register("bee_network_me_bridge", com.ayoshiko.productivebeesgenesis.apiculture.bridge.MeBridgeBlock::new);
	public static final DeferredItem<BlockItem> ME_BRIDGE_ITEM = ITEMS.register("bee_network_me_bridge", () -> new BlockItem(ME_BRIDGE.get(), new Item.Properties()));
	public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.ayoshiko.productivebeesgenesis.apiculture.bridge.MeBridgeBlockEntity>> ME_BRIDGE_TILE = TILES.register("bee_network_me_bridge", () -> BlockEntityType.Builder.of(com.ayoshiko.productivebeesgenesis.apiculture.bridge.MeBridgeBlockEntity::new, ME_BRIDGE.get()).build(null));
	public static final DeferredBlock<NetworkCoreBlock> CORE = BLOCKS.register("bee_network_core", NetworkCoreBlock::new);
	public static final DeferredItem<BlockItem> CORE_ITEM = ITEMS.register("bee_network_core", () -> new BlockItem(CORE.get(), new Item.Properties()));
	public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<NetworkCoreBlockEntity>> CORE_TILE = TILES.register("bee_network_core", () -> BlockEntityType.Builder.of(NetworkCoreBlockEntity::new, CORE.get()).build(null));
	public static final DeferredHolder<MenuType<?>, MenuType<NetworkCoreMenu>> CORE_MENU = MENUS.register("bee_network_core", () -> IMenuTypeExtension.create(NetworkCoreMenu::new));
	public static final DeferredBlock<NetworkTerminalBlock> BEE_TERMINAL = BLOCKS.register("bee_network_terminal", () -> new NetworkTerminalBlock(com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalScope.APIARY));
	public static final DeferredBlock<NetworkTerminalBlock> CENTRIFUGE_TERMINAL = BLOCKS.register("centrifuge_network_terminal", () -> new NetworkTerminalBlock(com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalScope.CENTRIFUGE));
	public static final DeferredItem<BlockItem> BEE_TERMINAL_ITEM = ITEMS.register("bee_network_terminal", () -> new BlockItem(BEE_TERMINAL.get(), new Item.Properties()));
	public static final DeferredItem<BlockItem> CENTRIFUGE_TERMINAL_ITEM = ITEMS.register("centrifuge_network_terminal", () -> new BlockItem(CENTRIFUGE_TERMINAL.get(), new Item.Properties()));
	public static final DeferredBlock<NetworkTerminalBlock> COMBINED_TERMINAL = BLOCKS.register("combined_network_terminal", () -> new NetworkTerminalBlock(com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalScope.APIARY, true));
	public static final DeferredItem<BlockItem> COMBINED_TERMINAL_ITEM = ITEMS.register("combined_network_terminal", () -> new BlockItem(COMBINED_TERMINAL.get(), new Item.Properties()));
	public static final DeferredItem<WirelessTerminalItem> WIRELESS_BEE = ITEMS.register("wireless_bee_terminal", () -> new WirelessTerminalItem(com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalScope.APIARY, false));
	public static final DeferredItem<WirelessTerminalItem> WIRELESS_CENTRIFUGE = ITEMS.register("wireless_centrifuge_terminal", () -> new WirelessTerminalItem(com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalScope.CENTRIFUGE, false));
	public static final DeferredItem<WirelessTerminalItem> WIRELESS_COMBINED = ITEMS.register("wireless_combined_terminal", () -> new WirelessTerminalItem(com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalScope.APIARY, true));
	public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<NetworkTerminalBlockEntity>> TERMINAL_TILE = TILES.register("bee_network_terminal", () -> BlockEntityType.Builder.of(NetworkTerminalBlockEntity::new, BEE_TERMINAL.get(), CENTRIFUGE_TERMINAL.get(), COMBINED_TERMINAL.get()).build(null));
	public static final DeferredHolder<MenuType<?>, MenuType<NetworkCoreMenu>> BEE_MENU = MENUS.register("bee_network_terminal", () -> IMenuTypeExtension.create((id, inventory, buffer) -> new NetworkCoreMenu(id, inventory, buffer, com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalScope.APIARY)));
	public static final DeferredHolder<MenuType<?>, MenuType<NetworkCoreMenu>> CENTRIFUGE_MENU = MENUS.register("centrifuge_network_terminal", () -> IMenuTypeExtension.create((id, inventory, buffer) -> new NetworkCoreMenu(id, inventory, buffer, com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalScope.CENTRIFUGE)));
	public static final DeferredHolder<MenuType<?>, MenuType<NetworkCoreMenu>> COMBINED_MENU = MENUS.register("combined_network_terminal", () -> IMenuTypeExtension.create((id, inventory, buffer) -> new NetworkCoreMenu(id, inventory, buffer, com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalScope.APIARY, true)));
	public static final DeferredHolder<net.minecraft.world.item.crafting.RecipeSerializer<?>, net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer<CombinedTerminalRecipe>> COMBINE_RECIPE = RECIPES.register("combine_network_terminals", () -> new net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer<>(CombinedTerminalRecipe::new));
	public static final DeferredHolder<net.minecraft.world.item.crafting.RecipeSerializer<?>, net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer<WirelessTerminalRecipe>> WIRELESS_RECIPE = RECIPES.register("wireless_network_terminal", () -> new net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer<>(WirelessTerminalRecipe::new));
	static MenuType<NetworkCoreMenu> menu(com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalScope scope) {
		return switch (scope) { case ALL -> CORE_MENU.get(); case APIARY -> BEE_MENU.get(); case CENTRIFUGE -> CENTRIFUGE_MENU.get(); };
	}
	static MenuType<NetworkCoreMenu> menu(com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalScope scope, boolean combined) {
		return combined ? COMBINED_MENU.get() : menu(scope);
	}
	public static void register(IEventBus bus) {
		bus.addListener(com.ayoshiko.productivebeesgenesis.apiculture.bridge.MeBridgeIntegration::register);
		BLOCKS.register(bus); ITEMS.register(bus); TILES.register(bus); MENUS.register(bus); RECIPES.register(bus); ATTACHMENTS.register(bus);
		bus.addListener((net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent event) -> event.registerItem(
				net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.ITEM, (stack, context) -> WirelessTerminalItem.energyStorage(stack),
				WIRELESS_BEE.get(), WIRELESS_CENTRIFUGE.get(), WIRELESS_COMBINED.get()));
	}
	private NetworkContent() { }
}
