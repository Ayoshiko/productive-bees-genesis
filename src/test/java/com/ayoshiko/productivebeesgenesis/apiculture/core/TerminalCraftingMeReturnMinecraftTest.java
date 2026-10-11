package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.bridge.MeBridgeBlockEntity;
import com.ayoshiko.productivebeesgenesis.apiculture.bridge.MeBridgeLink;
import com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalBudget;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.config.TerminalPreferenceConfigSection;
import java.util.*;
import java.util.function.ToIntFunction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.crafting.*;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalRequest.Operation.*;
import static com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalReply.Status.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class TerminalCraftingMeReturnMinecraftTest {
	private static final class Rig implements AutoCloseable {
		final UUID owner = UUID.randomUUID(), session = UUID.randomUUID();
		final MinecraftServer server = mock(MinecraftServer.class);
		final ServerLevel level = mock(ServerLevel.class);
		final ServerPlayer player = mock(ServerPlayer.class);
		final PlayerList players = mock(PlayerList.class);
		final AbstractContainerMenu menu = mock(AbstractContainerMenu.class, withSettings().extraInterfaces(TerminalCraftingMenu.Host.class));
		final TerminalCraftingMenu.Host host = (TerminalCraftingMenu.Host) menu;
		final Inventory inventory = spy(new Inventory(player));
		final TerminalCursor cursor = new TerminalCursor();
		final RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		final MeBridgeBlockEntity bridge = mock(MeBridgeBlockEntity.class);
		final MeBridgeLink link = mock(MeBridgeLink.class);
		final TerminalMaterialSource source = mock(TerminalMaterialSource.class);
		final List<ItemStack> offered = new ArrayList<>();
		final TerminalCraftingMenu crafting;
		TerminalCraftingAccount account;
		ToIntFunction<ItemStack> insert = ItemStack::getCount;
		boolean online = true, bridgePresent = true;
		long tick = 20, sequence;
		Rig(List<ItemStack> grid) throws Exception {
			var field = ServerPlayer.class.getField("server"); field.setAccessible(true); field.set(player, server);
			player.connection = mock(ServerGamePacketListenerImpl.class); player.containerMenu = menu;
			when(server.isSameThread()).thenReturn(true); when(server.overworld()).thenReturn(level); when(server.getPlayerList()).thenReturn(players);
			when(players.getPlayers()).thenReturn(List.of(player)); when(level.getGameTime()).thenAnswer(call -> tick); when(level.getServer()).thenReturn(server);
			when(player.getUUID()).thenReturn(owner); when(player.getInventory()).thenReturn(inventory); when(player.level()).thenReturn(level);
			when(player.serverLevel()).thenReturn(level); when(player.registryAccess()).thenReturn(registries);
			when(player.getData(NetworkContent.TERMINAL_CURSOR)).thenReturn(cursor);
			when(level.enabledFeatures()).thenReturn(FeatureFlags.DEFAULT_FLAGS);
			var recipes = mock(RecipeManager.class); when(level.getRecipeManager()).thenReturn(recipes);
			when(recipes.getRecipeFor(eq(RecipeType.CRAFTING), any(CraftingInput.class), eq(level))).thenReturn(Optional.empty());
			when(menu.stillValid(player)).thenReturn(true); when(menu.getCarried()).thenReturn(ItemStack.EMPTY);
			when(host.craftingMenu()).thenReturn(menu); when(host.craftingPlayer()).thenReturn(player); when(host.craftingSession()).thenReturn(session);
			when(host.craftingAccount(player)).thenAnswer(call -> account);
			when(host.craftingBridge(player)).thenAnswer(call -> bridgePresent ? bridge : null);
			when(bridge.link()).thenReturn(link); when(link.materials(player, null)).thenReturn(source);
			when(source.valid()).thenAnswer(call -> online); when(source.description()).thenReturn("minecraft:overworld 1 2 3");
			when(source.insert(any())).thenAnswer(call -> {
				ItemStack wanted = call.getArgument(0); offered.add(wanted.copy());
				assertTrue(account.busy()); assertTrue(cursor.containerBusy);
				assertNotNull(cursor.request); assertTrue(cursor.request.insert()); assertTrue(ItemStack.matches(wanted, cursor.request.item()));
				return insert.applyAsInt(wanted);
			});
			account = account(grid); crafting = new TerminalCraftingMenu(host); subscribe();
		}
		TerminalCraftingAccount account(List<ItemStack> values) {
			var tag = new CompoundTag(); tag.putInt("schema", 1); tag.putUUID("owner", owner);
			tag.putString("dimension", "minecraft:overworld"); tag.putLong("position", 0); tag.putLong("revision", 0); tag.putBoolean("uncertain", false);
			var grid = new ListTag(); for (int i = 0; i < 9; i++) grid.add((i < values.size() ? values.get(i) : ItemStack.EMPTY).saveOptional(registries));
			tag.put("grid", grid); tag.put("pending", new CompoundTag());
			var result = TerminalCraftingAccount.load(tag, registries); assertTrue(result.available()); return result;
		}
		void subscribe() { assertEquals(OK, crafting.handle(player, new TerminalRequest(0, session, ++sequence, CRAFTING, 0, -1, -1, -1, 0)).status()); }
		TerminalReply send(long generation, int row, int target, int slot, int amount) {
			return crafting.handle(player, new TerminalRequest(0, session, ++sequence, CRAFT_STORE_ME_ON_CLOSE, generation, row, target, slot, amount));
		}
		TerminalReply store() { return send(crafting.generation(), -1, -1, -1, 0); }
		int count(ItemStack kind) { return inventory.items.stream().filter(stack -> ItemStack.isSameItemSameComponents(kind, stack)).mapToInt(ItemStack::getCount).sum(); }
		void full() { for (int i = 0; i < 36; i++) inventory.items.set(i, new ItemStack(Items.GOLD_INGOT, 64)); }
		TerminalCursor restoredCursor() { return TerminalCursor.SERIALIZER.read(player, TerminalCursor.SERIALIZER.write(cursor, registries), registries); }
		@Override public void close() {
			crafting.pause(); var stopped = new ServerStoppedEvent(server); TerminalSubscriptionService.stopped(stopped); MeTerminalBudget.stopped(stopped);
		}
	}
	private static ItemStack iron(int count) { return new ItemStack(Items.IRON_INGOT, count); }
	private static ItemStack diamond(int count) { return new ItemStack(Items.DIAMOND, count); }

	@Test void defaultsStayDisabledAndInventoryWhileMeIsSelectable() {
		var config = ModConfig.CLIENT.terminalPreferences;
		assertFalse(config.returnCraftingOnClose.getDefault());
		assertEquals(TerminalPreferenceConfigSection.CraftingReturnTarget.INVENTORY, config.craftingReturnTarget.getDefault());
		assertEquals(TerminalPreferenceConfigSection.CraftingReturnTarget.ME_NETWORK,
				TerminalPreferenceConfigSection.CraftingReturnTarget.valueOf("ME_NETWORK"));
	}
	@Test void fullComponentsTransferOnceAndMouseAndPaidResultAreUntouched() throws Exception {
		var named = iron(17); named.set(DataComponents.CUSTOM_NAME, Component.literal("材料原名"));
		var box = new ItemStack(Items.SHULKER_BOX); box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(diamond(3))));
		try (var r = new Rig(List.of(iron(64), named, box))) {
			r.cursor.set(diamond(2)); when(r.menu.getCarried()).thenReturn(diamond(2));
			var before = r.account.state(); r.account.publish(before, before.grid(), diamond(1), false);
			r.account.requestMaterial(r.account.state(), 3, iron(1), "existing:extraction"); r.subscribe();
			r.insert = wanted -> { assertTrue(r.account.state().grid().get(r.offered.size() - 1).isEmpty()); return wanted.getCount(); };
			long generation = r.crafting.generation(); var result = r.store();
			assertEquals(MOVED, result.status()); assertEquals(82, result.moved());
			assertTrue(ItemStack.listMatches(List.of(iron(64), named, box), r.offered));
			assertTrue(r.account.state().grid().stream().allMatch(ItemStack::isEmpty));
			assertTrue(ItemStack.matches(diamond(2), r.cursor.item())); assertTrue(ItemStack.matches(diamond(2), r.menu.getCarried()));
			assertTrue(ItemStack.matches(diamond(1), r.account.state().pending())); assertNotNull(r.account.state().materialRequest());
			assertNull(r.cursor.request); assertTrue(r.cursor.pending.isEmpty());
			assertEquals(STALE, r.send(generation, -1, -1, -1, 0).status()); assertEquals(3, r.offered.size());
			var restored = TerminalCraftingAccount.load(r.account.save(new CompoundTag(), r.registries), r.registries);
			assertTrue(restored.available()); assertNotNull(restored.state().materialRequest()); assertTrue(ItemStack.matches(diamond(1), restored.state().pending()));
			verify(r.host, never()).craftingLedger(any()); verify(r.source, never()).candidates(any(), anyInt());
		}
	}
	@Test void partialAcceptanceFallsBackToInventoryAndFullRemainderStaysInOriginalGrid() throws Exception {
		for (boolean full : new boolean[]{false, true}) try (var r = new Rig(List.of(iron(10), diamond(3)))) {
			if (full) { r.full(); r.inventory.items.set(35, iron(62)); }
			r.insert = item -> item.is(Items.IRON_INGOT) ? 4 : 0;
			var result = r.store(); assertEquals(MOVED, result.status()); assertEquals(full ? 6 : 13, result.moved());
			assertEquals(full ? 64 : 6, r.count(iron(1))); assertEquals(full ? 0 : 3, r.count(diamond(1)));
			assertEquals(full ? 4 : 0, r.account.state().grid().getFirst().getCount()); assertEquals(full ? 3 : 0, r.account.state().grid().get(1).getCount());
			assertNull(r.cursor.request); assertTrue(r.cursor.pending.isEmpty()); assertEquals(2, r.offered.size());
		}
	}
	@Test void absentBridgeLinkSourceOfflineAndExhaustedBudgetUseInventoryWithoutExternalCalls() throws Exception {
		for (int mode = 0; mode < 5; mode++) try (var r = new Rig(List.of(iron(10)))) {
			if (mode == 0) r.bridgePresent = false;
			if (mode == 1) when(r.bridge.link()).thenReturn(null);
			if (mode == 2) when(r.link.materials(r.player, null)).thenReturn(null);
			if (mode == 3) r.online = false;
			if (mode == 4) assertTrue(MeTerminalBudget.expensive(r.server));
			assertEquals(MOVED, r.store().status()); assertEquals(10, r.count(iron(1))); assertTrue(r.offered.isEmpty());
			verify(r.host, never()).craftingLedger(any());
		}
	}
	@Test void oneBudgetedCloseCanProcessAllNineSlotsButCannotClaimAnotherTokenInSameTick() throws Exception {
		try (var r = new Rig(Collections.nCopies(9, iron(64)))) {
			assertEquals(576, r.store().moved()); assertEquals(9, r.offered.size());
			r.account = r.account(List.of(diamond(7))); r.subscribe();
			assertEquals(7, r.store().moved()); assertEquals(9, r.offered.size()); assertEquals(7, r.count(diamond(1)));
			r.tick += 20; r.account = r.account(List.of(iron(2))); r.subscribe();
			assertEquals(2, r.store().moved()); assertEquals(10, r.offered.size());
		}
	}
	@Test void exceptionsAndImpossibleAmountsRetainUnknownRequestAcrossSerializationAndNeverReplay() throws Exception {
		for (int mode = 0; mode < 4; mode++) try (var r = new Rig(List.of(iron(10), diamond(3)))) {
			int scenario = mode;
			r.insert = wanted -> {
				if (scenario == 0) throw new IllegalStateException("Unknown provider result");
				if (scenario == 1) throw new NoClassDefFoundError("Optional provider disappeared");
				return scenario == 2 ? -1 : wanted.getCount() + 1;
			};
			assertEquals(UNAVAILABLE, r.store().status()); assertTrue(r.account.state().grid().getFirst().isEmpty());
			assertTrue(ItemStack.matches(diamond(3), r.account.state().grid().get(1))); assertEquals(0, r.count(iron(1)));
			var restored = r.restoredCursor(); assertTrue(restored.available()); assertNotNull(restored.request); assertTrue(restored.request.insert());
			assertTrue(ItemStack.matches(iron(10), restored.request.item())); assertEquals("minecraft:overworld 1 2 3", restored.request.source());
			r.tick += 20; r.subscribe(); assertEquals(UNAVAILABLE, r.store().status()); assertEquals(1, r.offered.size());
			assertTrue(r.cursor.pending.isEmpty()); assertFalse(r.cursor.containerBusy); assertFalse(r.account.busy());
		}
	}
	@Test void unknownAfterProviderSideEffectDoesNotRefundOrProcessTheNextSlot() throws Exception {
		try (var r = new Rig(List.of(iron(10), diamond(3)))) {
			int[] received = {0}; r.insert = wanted -> { received[0] += 4; throw new IllegalStateException("Provider failed after accepting"); };
			assertEquals(UNAVAILABLE, r.store().status()); assertEquals(4, received[0]); assertNotNull(r.cursor.request);
			r.tick += 20; assertEquals(UNAVAILABLE, r.store().status()); assertEquals(4, received[0]);
			assertEquals(0, r.count(iron(1))); assertTrue(ItemStack.matches(diamond(3), r.account.state().grid().get(1)));
		}
	}
	@Test void callbackCloseRetainsKnownRemainderForOfflineRecoveryWithoutOverwritingGrid() throws Exception {
		try (var r = new Rig(List.of(iron(10), diamond(3)))) {
			r.insert = wanted -> { r.player.containerMenu = mock(AbstractContainerMenu.class); return 4; };
			assertEquals(UNAVAILABLE, r.store().status()); assertNull(r.cursor.request); assertTrue(ItemStack.matches(iron(6), r.cursor.pending));
			assertTrue(r.account.state().grid().getFirst().isEmpty()); assertTrue(ItemStack.matches(diamond(3), r.account.state().grid().get(1)));
			assertEquals(1, r.offered.size()); assertTrue(ItemStack.matches(iron(6), r.restoredCursor().pending));
			r.player.containerMenu = r.menu; r.online = false;
			TerminalCursorExchange.recover(r.player, r.menu, true);
			assertEquals(6, r.count(iron(1))); assertTrue(r.cursor.pending.isEmpty()); assertEquals(1, r.offered.size());
		}
	}
	@Test void callbackAccountReplacementOrRootChangeKeepsKnownRemainderAndStops() throws Exception {
		for (boolean replace : new boolean[]{false, true}) try (var r = new Rig(List.of(iron(10), diamond(3)))) {
			r.insert = wanted -> {
				if (replace) r.account = r.account(List.of(diamond(9)));
				else { var state = r.account.state(); var grid = TerminalCraftingPlan.copy(state.grid()); grid.set(0, diamond(9)); r.account.publish(state, grid, state.pending(), false); }
				return 4;
			};
			assertEquals(UNAVAILABLE, r.store().status()); assertEquals(1, r.offered.size()); assertNull(r.cursor.request);
			assertTrue(ItemStack.matches(iron(6), r.cursor.pending)); assertTrue(ItemStack.matches(diamond(9), r.account.state().grid().getFirst()));
		}
	}
	@Test void notificationFailureAfterKnownReceiptCannotRecreateUnknownOrReplayTransfer() throws Exception {
		try (var r = new Rig(List.of(iron(10), diamond(3)))) {
			r.insert = wanted -> 4; long generation = r.crafting.generation();
			doThrow(new IllegalStateException("Notification failure")).when(r.menu).broadcastFullState();
			assertEquals(UNAVAILABLE, r.store().status()); assertNull(r.cursor.request); assertTrue(r.cursor.pending.isEmpty());
			assertTrue(ItemStack.matches(iron(6), r.account.state().grid().getFirst())); assertTrue(ItemStack.matches(diamond(3), r.account.state().grid().get(1)));
			assertEquals(STALE, r.send(generation, -1, -1, -1, 0).status()); assertEquals(1, r.offered.size());
		}
	}
	@Test void sourceOrBridgeInvalidationStopsFurtherExternalCallsThenReturnsKnownMaterials() throws Exception {
		for (boolean replace : new boolean[]{false, true}) try (var r = new Rig(List.of(iron(10), diamond(3)))) {
			r.insert = wanted -> { if (replace) when(r.bridge.link()).thenReturn(mock(MeBridgeLink.class)); else r.online = false; return 4; };
			var result = r.store(); assertEquals(MOVED, result.status()); assertEquals(13, result.moved()); assertEquals(1, r.offered.size());
			assertEquals(6, r.count(iron(1))); assertEquals(3, r.count(diamond(1)));
		}
	}
	@Test void lockedFallbackSlotCannotUndoAcceptedAmountOrOverwriteInventory() throws Exception {
		try (var r = new Rig(List.of(iron(10), diamond(3)))) {
			r.inventory.items.set(0, diamond(1)); when(r.host.lockedNativeStack(any())).thenAnswer(call -> ((ItemStack) call.getArgument(0)).is(Items.DIAMOND));
			r.insert = wanted -> wanted.is(Items.IRON_INGOT) ? 4 : 0;
			assertEquals(UNAVAILABLE, r.store().status()); assertEquals(1, r.count(diamond(1))); assertEquals(0, r.count(iron(1)));
			assertTrue(ItemStack.matches(iron(6), r.account.state().grid().getFirst())); assertTrue(ItemStack.matches(diamond(3), r.account.state().grid().get(1)));
			assertNull(r.cursor.request); assertTrue(r.cursor.pending.isEmpty());
		}
	}
	@Test void previousKnownUnknownOrBusyCustodyBlocksNewGridTransfer() throws Exception {
		for (int mode = 0; mode < 3; mode++) try (var r = new Rig(List.of(iron(10)))) {
			if (mode == 0) r.cursor.pending = diamond(2);
			if (mode == 1) r.cursor.request = new TerminalCursorExchange.Request(diamond(2), false, "previous");
			if (mode == 2) r.cursor.containerBusy = true;
			var before = r.account.state(); assertEquals(UNAVAILABLE, r.store().status()); assertSame(before, r.account.state());
			assertTrue(r.offered.isEmpty()); assertEquals(0, r.count(iron(1)));
		}
	}
	@Test void invalidFramesAndNonExclusiveOwnersNeverCallExternalStorage() throws Exception {
		for (int mode = 0; mode < 7; mode++) try (var r = new Rig(List.of(iron(10)))) {
			if (mode == 5) when(r.player.getUUID()).thenReturn(UUID.randomUUID());
			if (mode == 6) {
				var other = mock(ServerPlayer.class); other.containerMenu = mock(AbstractContainerMenu.class, withSettings().extraInterfaces(TerminalCraftingMenu.Host.class));
				when(((TerminalCraftingMenu.Host) other.containerMenu).craftingAccount(other)).thenReturn(r.account); when(r.players.getPlayers()).thenReturn(List.of(r.player, other));
			}
			var before = r.account.state(); var reply = r.send(mode == 0 ? 0 : r.crafting.generation(), mode == 1 ? 0 : -1, mode == 2 ? 0 : -1, mode == 3 ? 0 : -1, mode == 4 ? 1 : 0);
			assertEquals(mode == 0 ? STALE : mode < 5 ? INVALID : UNAVAILABLE, reply.status());
			assertSame(before, r.account.state()); assertTrue(r.offered.isEmpty());
		}
	}
	@Test void unsupportedTransientComponentsFailBeforeTakingOwnership() throws Exception {
		try (var r = new Rig(List.of(iron(10)))) {
			var before = r.account.state(); var grid = TerminalCraftingPlan.copy(before.grid());
			grid.getFirst().set(DataComponentType.<String>builder().networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8).build(), "not persistent");
			r.account.publish(before, grid, before.pending(), false); r.subscribe(); before = r.account.state();
			assertEquals(UNAVAILABLE, r.store().status()); assertSame(before, r.account.state()); assertNull(r.cursor.request); assertTrue(r.offered.isEmpty());
		}
	}
	@Test void anotherViewerOpenedByCallbackStopsFurtherTransfersAndRetainsKnownRemainder() throws Exception {
		try (var r = new Rig(List.of(iron(10), diamond(3)))) {
			r.insert = wanted -> {
				var other = mock(ServerPlayer.class); other.containerMenu = mock(AbstractContainerMenu.class, withSettings().extraInterfaces(TerminalCraftingMenu.Host.class));
				when(((TerminalCraftingMenu.Host) other.containerMenu).craftingAccount(other)).thenReturn(r.account);
				when(r.players.getPlayers()).thenReturn(List.of(r.player, other)); return 4;
			};
			assertEquals(UNAVAILABLE, r.store().status()); assertEquals(1, r.offered.size());
			assertTrue(ItemStack.matches(iron(6), r.cursor.pending)); assertNull(r.cursor.request);
			assertTrue(r.account.state().grid().getFirst().isEmpty()); assertTrue(ItemStack.matches(diamond(3), r.account.state().grid().get(1)));
		}
	}
	@Test void reentrantCommandsCannotTransferTheReservedSlotAgain() throws Exception {
		try (var r = new Rig(List.of(iron(10)))) {
			r.insert = wanted -> {
				assertEquals(STALE, r.store().status());
				assertEquals(TerminalCursorExchange.Outcome.UNKNOWN, TerminalCursorExchange.exchange(r.player, r.menu, iron(1), true, true, "reentry", item -> fail("Nested external call")).outcome());
				return wanted.getCount();
			};
			assertEquals(MOVED, r.store().status()); assertEquals(1, r.offered.size()); assertTrue(r.account.state().grid().getFirst().isEmpty());
			assertNull(r.cursor.request); assertFalse(r.cursor.containerBusy); assertFalse(r.account.busy());
		}
	}
}
