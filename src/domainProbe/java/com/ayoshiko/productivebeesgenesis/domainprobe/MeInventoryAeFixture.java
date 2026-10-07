package com.ayoshiko.productivebeesgenesis.domainprobe;

import appeng.api.config.Actionable;
import appeng.api.crafting.*;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.*;
import appeng.api.storage.*;
import com.ayoshiko.productivebeesgenesis.apiculture.bridge.*;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2.MeBridgeNode;
import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.me.*;
import java.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.*;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

final class MeInventoryAeFixture {
	static boolean verified;
	private static IGrid grid;
	private static MeTerminalBackend backend;
	private static MeTerminalView view;
	private static AEItemKey diamond;
	private static final KeyCounter stock = new KeyCounter();
	private static final List<IPatternDetails> patterns = new ArrayList<>();
	private static int insertLimit = 64, calls, unknownHeld;
	private static boolean failExtract, failInsert, closedReceipt;
	private static final MEStorage storage = new MEStorage() {
		public Component getDescription() { return Component.literal("Finite inventory failure fixture"); }
		public void getAvailableStacks(KeyCounter output) { output.addAll(stock); }
		public long extract(AEKey key, long amount, Actionable action, IActionSource source) {
			long actual = Math.min(amount, stock.get(key));
			if (action == Actionable.MODULATE) {
				calls++;
				if (failExtract) { long taken = Math.min(2, actual); stock.remove(key, taken); unknownHeld += (int) taken; throw new IllegalStateException("Intentional unknown extraction"); }
				stock.remove(key, actual);
			}
			return actual;
		}
		public long insert(AEKey key, long amount, Actionable action, IActionSource source) {
			long actual = Math.min(amount, insertLimit);
			if (action == Actionable.MODULATE) {
				calls++; if (failInsert) { stock.add(key, Math.min(2, actual)); throw new IllegalStateException("Intentional unknown insertion"); }
				stock.add(key, actual);
			}
			return actual;
		}
	};
	private static final IStorageProvider provider = mounts -> mounts.mount(storage, 0);
	private static final ICraftingProvider crafting = new ICraftingProvider() {
		public List<IPatternDetails> getAvailablePatterns() { return patterns; }
		public boolean isBusy() { return true; }
		public boolean pushPattern(IPatternDetails pattern, KeyCounter[] input) { return false; }
	};
	static void seed(NetworkCoreBlockEntity core, MeBridgeBlockEntity bridge, List<ServerPlayer> players) {
		for (var player : players) { player.closeContainer(); player.getInventory().clearContent(); }
		CraftingProbe.open(core, players, false);
		var named = new ItemStack(Items.DIAMOND); named.set(DataComponents.CUSTOM_NAME, Component.literal("ME component diamond")); diamond = AEItemKey.of(named);
		grid = ((MeBridgeNode) bridge.link()).grid(); stock.reset(); stock.add(diamond, 10);
		int count = 64;
		for (var item : List.of(Items.IRON_INGOT, Items.COPPER_INGOT, Items.GOLD_INGOT, Items.COAL, Items.REDSTONE, Items.LAPIS_LAZULI, Items.QUARTZ, Items.EMERALD, Items.COBBLESTONE)) stock.add(AEItemKey.of(item), count--);
		stock.add(AEFluidKey.of(net.minecraft.world.level.material.Fluids.WATER), 1000);
		for (var output : List.of(diamond, AEItemKey.of(Items.STICK))) {
			var encoded = PatternDetailsHelper.encodeProcessingPattern(List.of(new GenericStack(AEItemKey.of(Items.COBBLESTONE), 1)), List.of(new GenericStack(output, 1)));
			patterns.add(Objects.requireNonNull(PatternDetailsHelper.decodePattern(encoded, core.getLevel())));
		}
		grid.getStorageService().addGlobalStorageProvider(provider); grid.getCraftingService().addGlobalCraftingProvider(crafting); grid.getStorageService().invalidateCache();
		backend = bridge.link().terminal(players.getFirst());
	}
	static int advance(NetworkCoreBlockEntity core, List<ServerPlayer> players, int stage) {
		var owner = players.getFirst(); var guest = players.get(1);
		switch (stage) {
			case 854 -> require(owner.containerMenu.getCarried().getCount() == 1 && ItemStack.isSameItemSameComponents(owner.containerMenu.getCarried(), diamond.toStack()) && stock.get(diamond) == 9, "Right-click extraction lost exact components");
			case 855 -> require(owner.containerMenu.getCarried().isEmpty() && stock.get(diamond) == 10, "Cursor insertion did not return exact quantity");
			case 856 -> { require(owner.containerMenu.getCarried().isEmpty() && inventory(owner) == 10 && stock.get(diamond) == 0, "Inventory extraction lost items"); insertLimit = 4; }
			case 857 -> require(owner.containerMenu.getCarried().getCount() == 6 && stock.get(diamond) == 4 && inventory(owner) == 0, "Partial insertion failed remainder custody");
			case 858 -> { insertLimit = 64; }
			case 859 -> require(owner.containerMenu.getCarried().isEmpty() && stock.get(diamond) == 10, "Remainder insertion lost items");
			case 861 -> {
				if (!closedReceipt) {
					var old = guest.containerMenu;
					var retained = TerminalCursorExchange.exchange(guest, old, new ItemStack(Items.GOLD_INGOT, 3), false, false, "closed receiver fixture", stack -> { guest.closeContainer(); return 3; });
					require(retained.outcome() == TerminalCursorExchange.Outcome.RETAINED, "Closed menu discarded known receipt");
					CraftingProbe.open(core, players, false);
					require(guest.containerMenu.getCarried().is(Items.GOLD_INGOT) && guest.containerMenu.getCarried().getCount() == 3, "Reopen did not recover known receipt");
					guest.containerMenu.clicked(27, 0, ClickType.PICKUP, guest); closedReceipt = true;
				}
				view = backend.request(request(owner, MeTerminalRequest.Action.STORAGE, 0, -1, 0));
				if (view.status() == MeTerminalView.Status.BUSY) return stage;
				require(view.rows().size() == 1 && view.rows().getFirst().amount() == 10, "Failure fixture did not select exact stock");
				var stale = backend.request(request(owner, MeTerminalRequest.Action.TAKE, view.revision() + 100, 0, 1));
				require(stale.status() == MeTerminalView.Status.STALE && stock.get(diamond) == 10, "Stale stock row changed assets");
			}
			case 862 -> {
				failExtract = true; calls = 0;
				view = backend.request(request(owner, MeTerminalRequest.Action.TAKE, view.revision(), 0, 64));
				if (view.status() == MeTerminalView.Status.BUSY) return stage;
				require(view.status() == MeTerminalView.Status.TRANSFER_UNKNOWN && owner.containerMenu.getCarried().isEmpty() && stock.get(diamond) == 8 && unknownHeld == 2 && calls == 1, "Unknown extraction was delivered or replayed");
			}
			case 863 -> {
				var reply = backend.request(request(owner, MeTerminalRequest.Action.TAKE, view.revision(), 0, 64));
				if (reply.status() == MeTerminalView.Status.BUSY) return stage;
				require(reply.status() == MeTerminalView.Status.TRANSFER_UNKNOWN && calls == 1 && TerminalCursorExchange.unknown(owner), "Unresolved extraction retried");
				failInsert = true; insertLimit = 64;
				guest.getInventory().setItem(0, new ItemStack(Items.GOLD_INGOT, 5)); guest.containerMenu.clicked(27, 0, ClickType.PICKUP, guest);
				require(guest.containerMenu.getCarried().getCount() == 5, "Cannot prepare deposit custody");
				int beforeCalls = calls;
				var deposited = TerminalCursorExchange.exchange(guest, guest.containerMenu, new ItemStack(Items.GOLD_INGOT, 5), true, false, "controlled ME insertion",
						stack -> (int) StorageHelper.poweredInsert(grid.getEnergyService(), grid.getStorageService().getInventory(), AEItemKey.of(stack), stack.getCount(), IActionSource.ofPlayer(guest)));
				require(deposited.outcome() == TerminalCursorExchange.Outcome.UNKNOWN && guest.containerMenu.getCarried().isEmpty() && calls == beforeCalls + 1 && TerminalCursorExchange.unknown(guest), "Unknown insertion restored transferable originals");
				var blocked = TerminalCursorExchange.exchange(guest, guest.containerMenu, new ItemStack(Items.GOLD_INGOT, 5), true, false, "controlled ME insertion", stack -> { throw new AssertionError("Unknown deposit retried"); });
				require(blocked.outcome() == TerminalCursorExchange.Outcome.UNKNOWN, "Unknown deposit lost lock");
			}
			case 864 -> {
				for (var player : players) {
					var original = TerminalCursor.SERIALIZER.write(player.getData(NetworkContent.TERMINAL_CURSOR), player.registryAccess());
					var decoded = TerminalCursor.SERIALIZER.read(player, original, player.registryAccess());
					require(decoded.available() && original.equals(TerminalCursor.SERIALIZER.write(decoded, player.registryAccess())) && ((CompoundTag) original).getInt("schema") == 2, "Cursor exchange codec lost request");
				}
				backend.close(); grid.getStorageService().removeGlobalStorageProvider(provider); grid.getCraftingService().removeGlobalCraftingProvider(crafting);
				grid.getStorageService().invalidateCache(); verified = true; return -1;
			}
			default -> { }
		}
		return stage + 1;
	}
	private static MeTerminalRequest request(ServerPlayer player, MeTerminalRequest.Action action, long revision, int row, long amount) {
		var menu = (NetworkCoreMenu) player.containerMenu; return new MeTerminalRequest(menu.containerId, menu.terminalSession(), 100_000 + action.ordinal(), action, revision, row, 0, amount, "diamond");
	}
	private static int inventory(ServerPlayer player) { return player.getInventory().items.stream().filter(s -> ItemStack.isSameItemSameComponents(s, diamond.toStack())).mapToInt(ItemStack::getCount).sum(); }
	private MeInventoryAeFixture() { }
}
