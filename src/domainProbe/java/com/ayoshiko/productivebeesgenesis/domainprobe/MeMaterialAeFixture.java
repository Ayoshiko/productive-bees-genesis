package com.ayoshiko.productivebeesgenesis.domainprobe;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.*;
import appeng.api.storage.*;
import com.ayoshiko.productivebeesgenesis.apiculture.bridge.MeBridgeBlockEntity;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2.MeBridgeNode;
import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.ClientTerminalStockFixture;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import java.util.*;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 复用双玩家桥接场景，只从真实菜单入口验证外部数量、失败保管与存档。 */
final class MeMaterialAeFixture {
	static boolean verified;
	private static NetworkCoreBlockEntity core;
	private static MeBridgeBlockEntity bridge;
	private static ServerPlayer player;
	private static IGrid grid;
	private static int step, mode, calls, held;
	private static long nextTick, sequence = 10_000;
	private static Collection<RecipeHolder<?>> recipes;
	private static final ResourceLocation HYBRID = ResourceLocation.fromNamespaceAndPath("productivebeesgenesis", "probe_material_hybrid");
	private static final ResourceLocation SIMPLE = ResourceLocation.fromNamespaceAndPath("productivebeesgenesis", "probe_material_simple");
	private static final KeyCounter stock = new KeyCounter();
	private static AEItemKey diamond;
	private static final MEStorage storage = new MEStorage() {
		public Component getDescription() { return Component.literal("Finite material source with controlled failures"); }
		public void getAvailableStacks(KeyCounter output) { output.addAll(stock); }
		public long extract(AEKey key, long amount, Actionable action, IActionSource source) {
			long taken = Math.min(amount, stock.get(key));
			if (action == Actionable.MODULATE) {
				calls++;
				if (mode == 2) { long lost = Math.min(2, taken); stock.remove(key, lost); held += (int) lost; throw new IllegalStateException("Intentional unknown material receipt"); }
				if (mode == 1) taken = Math.min(3, taken);
				stock.remove(key, taken);
			}
			return taken;
		}
	};
	private static final IStorageProvider provider = mounts -> mounts.mount(storage, 0);
	static void prepare(NetworkCoreBlockEntity host, MeBridgeBlockEntity link, List<ServerPlayer> players) {
		core = host; bridge = link; player = players.getFirst(); grid = ((MeBridgeNode) bridge.link()).grid();
		for (var p : players) p.getInventory().clearContent();
		var named = new ItemStack(Items.DIAMOND); named.set(DataComponents.CUSTOM_NAME, Component.literal("ME exact components")); diamond = AEItemKey.of(named);
		var key = ProductKeyCodec.item(new ItemStack(Items.RAW_IRON), player.registryAccess());
		ClientTerminalStockFixture.seed(core.ownership().readyAuthority(), Map.of(key, ProductAmount.of(9)));
		require(core.setProductionRunning(true) && bridge.toggleAutomation(player), "Cannot mount material ledger");
		((MeBridgeNode) bridge.link()).tick();
		for (int i = 0; i < 128; i++) bridge.link().storageStep();
		stock.add(AEItemKey.of(Items.RAW_IRON), 9); stock.add(diamond, 18); player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 18));
		grid.getStorageService().addGlobalStorageProvider(provider); grid.getStorageService().invalidateCache();
		recipes = List.copyOf(player.serverLevel().getRecipeManager().getRecipes()); var updated = new ArrayList<>(recipes);
		updated.add(new RecipeHolder<>(HYBRID, recipe(Items.RAW_IRON, Items.DIAMOND, Items.OAK_LOG)));
		updated.add(new RecipeHolder<>(SIMPLE, recipe(Items.DIAMOND)));
		player.serverLevel().getRecipeManager().replaceRecipes(updated);
	}
	static boolean advance() {
		if (verified) return true;
		long now = player.server.overworld().getGameTime(); if (now < nextTick) return false; nextTick = now + 21;
		var account = CraftingProbe.account(core, false);
		switch (step++) {
			case 0 -> {
				var before = account.state();
				var fake = new TerminalRecipeRequest(player.containerMenu.containerId, UUID.randomUUID(), ++sequence, HYBRID, true);
				require(((NetworkCoreMenu) player.containerMenu).terminalRecipe(player, fake) == null && account.state() == before && calls == 0, "Forged session extracted materials");
				require(fill(HYBRID).status() == TerminalReply.Status.MOVED, "Hybrid ME fill rejected");
				var contents = account.state().grid();
				require(contents.get(0).is(Items.RAW_IRON) && contents.get(0).getCount() == 18 && ItemStack.isSameItemSameComponents(contents.get(1), diamond.toStack()) && contents.get(1).getCount() == 18
						&& contents.get(2).is(Items.OAK_LOG) && contents.get(2).getCount() == 18, "Hybrid fill double-counted own ledger or lost components");
				require(stock.get(AEItemKey.of(Items.RAW_IRON)) == 0 && stock.get(diamond) == 0 && core.ownership().readyAuthority().checkpoint().ledger().available(ProductKeyCodec.item(new ItemStack(Items.RAW_IRON), player.registryAccess())).isZero(), "Hybrid sources did not debit exact deficits");
			}
			case 1 -> { resetGrid(); stock.add(diamond, 8); mode = 1; calls = 0; grid.getStorageService().invalidateCache();
				require(fill(SIMPLE).status() == TerminalReply.Status.MISSING_INGREDIENTS && account.state().grid().getFirst().getCount() == 3
						&& stock.get(diamond) == 5 && account.state().materialRequest() == null && calls == 1, "Partial extraction lost actual receipt"); }
			case 2 -> { mode = 0; grid.getStorageService().invalidateCache(); require(fill(SIMPLE).status() == TerminalReply.Status.MOVED
					&& account.state().grid().getFirst().getCount() == 8 && stock.get(diamond) == 0 && calls == 2, "Partial retry duplicated or lost materials"); }
			case 3 -> {
				resetGrid(); player.containerMenu.slots.get(36).container.setItem(0, diamond.toStack(1));
				stock.reset(); stock.add(diamond, 8); mode = 2; calls = 0; grid.getStorageService().invalidateCache();
				require(fill(SIMPLE).status() == TerminalReply.Status.UNAVAILABLE && account.state().materialRequest() != null
						&& account.state().grid().getFirst().getCount() == 1 && calls == 1 && held == 2 && stock.get(diamond) == 6, "Unknown receipt was minted or discarded");
			}
			case 4 -> {
				var state = account.state(); require(fill(SIMPLE).status() == TerminalReply.Status.UNAVAILABLE && account.state() == state && calls == 1, "Unknown material request retried");
				var menu = (NetworkCoreMenu) player.containerMenu;
				menu.clicked(36, 0, net.minecraft.world.inventory.ClickType.QUICK_MOVE, player);
				require(account.state().grid().stream().allMatch(ItemStack::isEmpty) && account.state().materialRequest() != null
						&& player.getInventory().items.stream().filter(s -> ItemStack.isSameItemSameComponents(s, diamond.toStack())).mapToInt(ItemStack::getCount).sum() == 1, "Known inputs could not be returned during isolation");
				var saved = account.save(new CompoundTag(), player.registryAccess());
				var decoded = TerminalCraftingAccount.load(saved, player.registryAccess());
				require(decoded.available() && saved.equals(decoded.save(new CompoundTag(), player.registryAccess())) && saved.getInt("schema") == 3, "Material request codec lost data");
				var legacy = saved.copy(); legacy.putInt("schema", 1); legacy.remove("materialRequest");
				require(TerminalCraftingAccount.load(legacy, player.registryAccess()).available(), "Legacy crafting account rejected");
				grid.getStorageService().removeGlobalStorageProvider(provider); grid.getStorageService().invalidateCache();
				player.serverLevel().getRecipeManager().replaceRecipes(recipes); require(core.setProductionRunning(false), "Cannot restore paused host"); verified = true;
			}
			default -> throw new IllegalStateException("Unexpected material stage");
		}
		return verified;
	}
	private static TerminalReply fill(ResourceLocation recipe) {
		var menu = (NetworkCoreMenu) player.containerMenu;
		var reply = menu.terminalRecipe(player, new TerminalRecipeRequest(menu.containerId, menu.terminalSession(), ++sequence, recipe, true));
		require(reply != null, "Material request had no reply"); return reply;
	}
	private static void resetGrid() {
		var container = player.containerMenu.slots.get(36).container;
		for (int i = 0; i < 9; i++) container.setItem(i, ItemStack.EMPTY);
		container.setChanged(); player.getInventory().clearContent();
	}
	private static ShapelessRecipe recipe(Item... items) {
		var ingredients = NonNullList.<Ingredient>create(); for (var item : items) ingredients.add(Ingredient.of(item));
		return new ShapelessRecipe("", CraftingBookCategory.MISC, new ItemStack(Items.STICK), ingredients);
	}
	private MeMaterialAeFixture() { }
}
