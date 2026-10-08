package com.ayoshiko.productivebeesgenesis.domainprobe;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.*;
import appeng.api.storage.*;
import com.ayoshiko.productivebeesgenesis.apiculture.bridge.*;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2.MeBridgeNode;
import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.me.*;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.*;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;
import static com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalCursorExchange.Outcome.*;

final class MeFluidAeFixture {
	static boolean verified;
	private static IGrid grid;
	private static MeBridgeBlockEntity bridge;
	private static final AEFluidKey water = AEFluidKey.of(Fluids.WATER);
	private static final KeyCounter stock = new KeyCounter();
	private static int extractLimit = Integer.MAX_VALUE, insertLimit = Integer.MAX_VALUE;
	private static final MEStorage storage = new MEStorage() {
		public Component getDescription() { return Component.literal("Finite fluid transfer fixture"); }
		public void getAvailableStacks(KeyCounter output) { output.addAll(stock); }
		public long extract(AEKey key, long requested, Actionable mode, IActionSource source) {
			long actual = Math.min(requested, stock.get(key));
			if (mode == Actionable.MODULATE) { actual = Math.min(actual, extractLimit); stock.remove(key, actual); }
			return actual;
		}
		public long insert(AEKey key, long requested, Actionable mode, IActionSource source) {
			long actual = mode == Actionable.SIMULATE ? requested : Math.min(requested, insertLimit);
			if (mode == Actionable.MODULATE) stock.add(key, actual); return actual;
		}
	};
	private static final IStorageProvider provider = mounts -> mounts.mount(storage, 0);
	static void seed(NetworkCoreBlockEntity core, MeBridgeBlockEntity value, List<ServerPlayer> players) {
		bridge = value; grid = ((MeBridgeNode) value.link()).grid(); stock.add(water, 5000);
		grid.getStorageService().addGlobalStorageProvider(provider); grid.getStorageService().invalidateCache();
		for (var player : players) { player.closeContainer(); player.getInventory().clearContent(); }
		players.getFirst().getInventory().setItem(0, new ItemStack(Items.BUCKET));
		players.getFirst().getInventory().setItem(1, new ItemStack(Items.PAPER));
		CraftingProbe.open(core, players, false);
	}
	static int advance(NetworkCoreBlockEntity core, List<ServerPlayer> players, int stage) {
		var owner = players.getFirst();
		switch (stage) {
			case 871 -> require(owner.containerMenu.getCarried().is(Items.WATER_BUCKET) && stock.get(water) == 4000, "Filled bucket or ME balance incorrect");
			case 872 -> { require(owner.containerMenu.getCarried().is(Items.BUCKET) && stock.get(water) == 5000, "Bucket emptying lost fluid"); extractLimit = 400; }
			case 873 -> { require(owner.containerMenu.getCarried().is(Items.BUCKET) && receipt(owner).retained() == 400 && stock.get(water) == 4600, "Partial fill was lost or fabricated a bucket"); extractLimit = Integer.MAX_VALUE; }
			case 874 -> { require(owner.containerMenu.getCarried().is(Items.WATER_BUCKET) && receipt(owner).retained() == 0 && stock.get(water) == 4000, "Fill did not consume existing credit first"); insertLimit = 300; }
			case 875 -> { require(owner.containerMenu.getCarried().is(Items.BUCKET) && receipt(owner).retained() == 700 && stock.get(water) == 4300, "Partial deposit lost remainder"); MeBridgeAeFixture.overload(bridge); }
			case 876 -> { require(owner.containerMenu.getCarried().is(Items.PAPER) && contained(owner.containerMenu.getCarried()).getAmount() == 700 && receipt(owner).retained() == 0 && stock.get(water) == 4300, "Offline recovery touched ME or lost fluid"); MeBridgeAeFixture.clear(); }
			case 877 -> { if (bridge.status() != MeBridgeStatus.ONLINE) return stage; failureCases(core, players); }
			case 878 -> {
				for (var p : players) {
					stashCursor(p);
					var raw = TerminalCursor.SERIALIZER.write(p.getData(NetworkContent.TERMINAL_CURSOR), p.registryAccess());
					var decoded = TerminalCursor.SERIALIZER.read(p, raw, p.registryAccess());
					require(decoded.available() && raw.equals(TerminalCursor.SERIALIZER.write(decoded, p.registryAccess())) && ((CompoundTag) raw).getInt("schema") == 3, "Fluid custody codec lost state");
				}
				legacyAndCorrupt(owner); grid.getStorageService().removeGlobalStorageProvider(provider); verified = true; return -1;
			}
			default -> { }
		}
		return stage + 1;
	}
	private static void failureCases(NetworkCoreBlockEntity core, List<ServerPlayer> players) {
		var owner = players.getFirst(); var guest = players.get(1); var waterStack = new FluidStack(Fluids.WATER, 1000);
		// NeoForge 原生单罐能力保留 FluidStack 组件；部分接受会装回余量。
		giveHeld(guest, new ItemStack(Items.PAPER)); var named = waterStack.copyWithAmount(900); named.set(DataComponents.CUSTOM_NAME, Component.literal("component-exact water"));
		var result = TerminalFluidExchange.fill(guest, guest.containerMenu, named, false, "component fixture", (fluid, insert, simulate) -> 900);
		require(result.outcome() == MOVED && contained(guest.containerMenu.getCarried()).getAmount() == 900 && FluidStack.isSameFluidSameComponents(named, contained(guest.containerMenu.getCarried())), "Container lost fluid components");
		result = TerminalFluidExchange.empty(guest, guest.containerMenu, false, "component fixture", (fluid, insert, simulate) -> simulate ? fluid.getAmount() : 450);
		require(result.amount() == 450 && contained(guest.containerMenu.getCarried()).getAmount() == 450 && receipt(guest).retained() == 0, "Refill did not preserve rejected components");
		guest.containerMenu.clicked(28, 0, ClickType.PICKUP, guest);
		giveHeld(guest, new ItemStack(Items.BUCKET, 2)); var saved = guest.getInventory().items.stream().map(ItemStack::copy).toList();
		for (int i = 0; i < 36; i++) guest.getInventory().items.set(i, new ItemStack(Items.STONE, 64));
		result = TerminalFluidExchange.fill(guest, guest.containerMenu, waterStack, false, "full inventory", (fluid, insert, simulate) -> { throw new AssertionError("Full receiver called ME"); });
		require(result.outcome() == NO_SPACE && guest.containerMenu.getCarried().is(Items.BUCKET) && guest.containerMenu.getCarried().getCount() == 2, "Full inventory changed container");
		for (int i = 0; i < 36; i++) guest.getInventory().items.set(i, saved.get(i)); guest.containerMenu.clicked(29, 0, ClickType.PICKUP, guest);
		giveHeld(guest, new ItemStack(Items.BUCKET)); var old = guest.containerMenu;
		result = TerminalFluidExchange.fill(guest, old, waterStack, false, "closed fluid receiver", (fluid, insert, simulate) -> { guest.closeContainer(); return 1000; });
		require(result.outcome() == RETAINED && receipt(guest).retained() == 1000, "Closed menu discarded received fluid");
		CraftingProbe.open(core, List.of(guest), false); pick(guest, Items.BUCKET);
		result = TerminalFluidExchange.recover(guest, guest.containerMenu, false);
		int filledBuckets = guest.getInventory().items.stream().filter(s -> s.is(Items.WATER_BUCKET)).mapToInt(ItemStack::getCount).sum()
				+ (guest.containerMenu.getCarried().is(Items.WATER_BUCKET) ? guest.containerMenu.getCarried().getCount() : 0);
		require(result.outcome() == MOVED && receipt(guest).retained() == 0 && filledBuckets == 1, "Reopened menu lost paid fluid");
		if (!guest.containerMenu.getCarried().is(Items.WATER_BUCKET)) {
			var filledSlot = guest.containerMenu.slots.stream().filter(s -> s.index < 36 && s.getItem().is(Items.WATER_BUCKET)).findFirst().orElseThrow();
			guest.containerMenu.clicked(filledSlot.index, 0, ClickType.PICKUP, guest);
		}
		// 存入已排空的流体后异常：空桶是已知结果，请求数量不是可再次倒出的水。
		int[] calls = {0}; result = TerminalFluidExchange.empty(guest, guest.containerMenu, false, "unknown deposit", (fluid, insert, simulate) -> {
			if (simulate) return fluid.getAmount(); calls[0]++; stock.add(water, 200); throw new IllegalStateException("Intentional unknown fluid deposit");
		});
		require(result.outcome() == UNKNOWN && guest.containerMenu.getCarried().is(Items.BUCKET) && receipt(guest).uncertain() == 1000, "Unknown deposit restored full container");
		result = TerminalFluidExchange.empty(guest, guest.containerMenu, false, "unknown deposit retry", (fluid, insert, simulate) -> { calls[0]++; return 0; });
		require(result.outcome() == UNKNOWN && calls[0] == 1, "Unknown fluid deposit retried");
		pick(owner, Items.BUCKET);
		result = TerminalFluidExchange.fill(owner, owner.containerMenu, waterStack, false, "known partial", (fluid, insert, simulate) -> { stock.remove(water, 500); return 500; });
		require(result.outcome() == RETAINED && receipt(owner).retained() == 500, "Known partial disappeared");
		result = TerminalFluidExchange.fill(owner, owner.containerMenu, waterStack, false, "unknown extraction", (fluid, insert, simulate) -> {
			owner.containerMenu.clicked(28, 0, ClickType.PICKUP, owner);
			require(owner.containerMenu.getCarried().is(Items.BUCKET) && TerminalFluidExchange.recover(owner, owner.containerMenu, false).outcome() == RETAINED && receipt(owner).retained() == 500, "Reentrant callback consumed fluid credit");
			stock.remove(water, 200); throw new IllegalStateException("Intentional unknown fluid extraction");
		});
		require(result.outcome() == UNKNOWN && receipt(owner).retained() == 500 && receipt(owner).uncertain() == 500 && owner.containerMenu.getCarried().is(Items.BUCKET), "Unknown extraction became owned fluid");
		result = TerminalFluidExchange.fill(owner, owner.containerMenu, waterStack, false, "unknown extraction retry", (fluid, insert, simulate) -> { throw new AssertionError("Unknown fill retried"); });
		require(result.outcome() == UNKNOWN, "Unknown fill lost its lock");
	}
	static void recovered(List<ServerPlayer> players) {
		var owner = players.getFirst(); require(receipt(owner).retained() == 500 && receipt(owner).uncertain() == 500 && receipt(players.get(1)).uncertain() == 1000, "Player file did not recover fluid ownership");
		pick(owner, Items.PAPER); require(contained(owner.containerMenu.getCarried()).getAmount() == 700, "Player container components lost during restart");
		var menu = (NetworkCoreMenu) owner.containerMenu;
		menu.meRequest(owner, new MeTerminalRequest(menu.containerId, menu.terminalSession(), 1, MeTerminalRequest.Action.RECOVER_FLUID, 0, -1, 0, 0, ""));
		require(contained(menu.getCarried()).getAmount() == 1000 && receipt(owner).retained() == 200 && receipt(owner).uncertain() == 500, "Recovery retried unknown extraction or lost partial capacity");
		stashCursor(owner);
		verified = true;
	}
	private static void legacyAndCorrupt(ServerPlayer player) {
		var legacy = new CompoundTag(); legacy.putInt("schema", 1); legacy.put("item", new CompoundTag());
		require(TerminalCursor.SERIALIZER.read(player, legacy, player.registryAccess()).available(), "Legacy cursor rejected");
		legacy.putInt("schema", 2); legacy.put("pending", new CompoundTag()); var itemRequest = new CompoundTag(); itemRequest.put("item", new ItemStack(Items.STONE).save(player.registryAccess())); itemRequest.putBoolean("insert", false); itemRequest.putString("source", "legacy fixture"); legacy.put("request", itemRequest);
		var decoded = TerminalCursor.SERIALIZER.read(player, legacy, player.registryAccess()); require(decoded.available() && legacy.equals(TerminalCursor.SERIALIZER.write(decoded, player.registryAccess())), "Schema 2 request changed");
		var corrupt = ((CompoundTag) TerminalCursor.SERIALIZER.write(player.getData(NetworkContent.TERMINAL_CURSOR), player.registryAccess())).copy(); corrupt.putString("fluid", "preserve invalid fluid");
		decoded = TerminalCursor.SERIALIZER.read(player, corrupt, player.registryAccess()); require(!decoded.available() && corrupt.equals(TerminalCursor.SERIALIZER.write(decoded, player.registryAccess())), "Invalid fluid data was discarded");
	}
	private static MeTerminalView.Receipt receipt(ServerPlayer player) { return TerminalFluidExchange.receipt(player); }
	private static void stashCursor(ServerPlayer player) {
		var carried = player.containerMenu.getCarried(); if (carried.isEmpty()) return;
		var target = player.containerMenu.slots.stream().filter(s -> s.index < 36 && (!s.hasItem()
				|| ItemStack.isSameItemSameComponents(s.getItem(), carried) && s.getItem().getCount() + carried.getCount() <= s.getMaxStackSize(carried))).findFirst().orElseThrow();
		player.containerMenu.clicked(target.index, 0, ClickType.PICKUP, player);
		require(player.containerMenu.getCarried().isEmpty(), "Could not settle known cursor before persistence snapshot");
	}
	private static FluidStack contained(ItemStack stack) { var handler = stack.getCapability(Capabilities.FluidHandler.ITEM); return handler == null ? FluidStack.EMPTY : handler.getFluidInTank(0); }
	private static void giveHeld(ServerPlayer player, ItemStack stack) { require(player.containerMenu.getCarried().isEmpty(), "Fixture would overwrite cursor"); player.getInventory().setItem(0, stack); player.containerMenu.clicked(27, 0, ClickType.PICKUP, player); }
	private static void pick(ServerPlayer player, Item item) { require(player.containerMenu.getCarried().isEmpty(), "Fixture cursor occupied"); var slot = player.containerMenu.slots.stream().filter(s -> s.index < 36 && s.getItem().is(item)).findFirst().orElseThrow(); player.containerMenu.clicked(slot.index, 0, ClickType.PICKUP, player); }
	private MeFluidAeFixture() { }
}
