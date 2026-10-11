package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.runtime.RuntimeProductPolicies;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.config.TerminalPreferenceConfigSection;
import java.util.*;
import net.minecraft.core.RegistryAccess;
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
import org.mockito.MockedStatic;
import static com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalRequest.Operation.*;
import static com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalReply.Status;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class TerminalCraftingReturnMinecraftTest {
	private static final class Rig implements AutoCloseable {
		final UUID owner = UUID.randomUUID(), session = UUID.randomUUID();
		final MinecraftServer server = mock(MinecraftServer.class);
		final ServerLevel level = mock(ServerLevel.class);
		final ServerPlayer player = mock(ServerPlayer.class);
		final PlayerList players = mock(PlayerList.class);
		final AbstractContainerMenu menu = mock(AbstractContainerMenu.class, withSettings().extraInterfaces(TerminalCraftingMenu.Host.class));
		final TerminalCraftingMenu.Host host = (TerminalCraftingMenu.Host) menu;
		final Inventory inventory = spy(new Inventory(player));
		final RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		final NetworkSavedData data = spy(NetworkSavedData.create(NetworkCheckpoint.empty(CheckpointTestData.identity()), Runnable::run, (path, payload) -> { }));
		final MockedStatic<RuntimeProductPolicies> policies = mockStatic(RuntimeProductPolicies.class);
		final TerminalCraftingMenu crafting;
		TerminalCraftingAccount account;
		ProductPolicySnapshot policy;
		boolean network = true;
		long tick, sequence;
		Rig(List<ItemStack> grid) throws Exception {
			var field = ServerPlayer.class.getField("server"); field.setAccessible(true); field.set(player, server);
			player.connection = mock(ServerGamePacketListenerImpl.class); player.containerMenu = menu;
			when(server.isSameThread()).thenReturn(true); when(server.overworld()).thenReturn(level); when(server.getPlayerList()).thenReturn(players);
			when(players.getPlayers()).thenReturn(List.of(player)); when(level.getGameTime()).thenAnswer(call -> tick); when(level.getServer()).thenReturn(server);
			when(player.getUUID()).thenReturn(owner); when(player.getInventory()).thenReturn(inventory); when(player.level()).thenReturn(level);
			when(player.serverLevel()).thenReturn(level); when(player.registryAccess()).thenReturn(registries);
			when(level.enabledFeatures()).thenReturn(FeatureFlags.DEFAULT_FLAGS);
			var recipes = mock(RecipeManager.class); when(level.getRecipeManager()).thenReturn(recipes);
			when(recipes.getRecipeFor(eq(RecipeType.CRAFTING), any(CraftingInput.class), eq(level))).thenReturn(Optional.empty());
			when(menu.stillValid(player)).thenReturn(true);
			when(host.craftingMenu()).thenReturn(menu); when(host.craftingPlayer()).thenReturn(player); when(host.craftingSession()).thenReturn(session);
			when(host.craftingAccount(player)).thenAnswer(call -> account); when(host.craftingLedger(player)).thenAnswer(call -> network ? data : null);
			account = account(grid, ItemStack.EMPTY, null, false);
			policy = new ProductPolicySnapshot(0, List.of(new AllowedProductDescriptor(key(new ItemStack(Items.IRON_INGOT)), "test", "test:iron")), List.of());
			policies.when(() -> RuntimeProductPolicies.peek(level)).thenAnswer(call -> policy);
			crafting = new TerminalCraftingMenu(host); subscribe();
		}
		TerminalCraftingAccount account(List<ItemStack> values, ItemStack pending, TerminalCraftingAccount.MaterialRequest request, boolean uncertain) {
			var tag = new CompoundTag(); tag.putInt("schema", request == null ? 1 : 3); tag.putUUID("owner", owner);
			tag.putString("dimension", "minecraft:overworld"); tag.putLong("position", 0); tag.putLong("revision", 0); tag.putBoolean("uncertain", uncertain);
			var grid = new ListTag(); for (int i = 0; i < 9; i++) grid.add((i < values.size() ? values.get(i) : ItemStack.EMPTY).saveOptional(registries));
			tag.put("grid", grid); tag.put("pending", pending.saveOptional(registries));
			if (request != null) {
				var raw = new CompoundTag(); raw.putInt("slot", request.slot()); raw.put("item", request.item().save(registries)); raw.putString("source", request.source()); tag.put("materialRequest", raw);
			}
			var result = TerminalCraftingAccount.load(tag, registries); assertTrue(result.available()); return result;
		}
		void subscribe() { assertEquals(Status.OK, send(CRAFTING, 0, -1, -1, -1, 0).status()); }
		TerminalReply send(TerminalRequest.Operation operation, long generation, int row, int target, int slot, int amount) {
			tick += 5; return crafting.handle(player, new TerminalRequest(0, session, ++sequence, operation, generation, row, target, slot, amount));
		}
		TerminalReply store() { return send(CRAFT_STORE_ON_CLOSE, crafting.generation(), -1, -1, -1, 0); }
		ProductKey key(ItemStack stack) { return ProductKeyCodec.item(stack, registries); }
		long balance(ItemStack stack) { return data.checkpoint().ledger().balances().getOrDefault(key(stack), ProductAmount.ZERO).longSaturated(); }
		int inventoryCount(ItemStack kind) { return inventory.items.stream().filter(stack -> ItemStack.isSameItemSameComponents(kind, stack)).mapToInt(ItemStack::getCount).sum(); }
		void fullInventory() { for (int slot = 0; slot < 36; slot++) inventory.items.set(slot, new ItemStack(Items.GOLD_INGOT, 64)); }
		@Override public void close() { policies.close(); crafting.pause(); TerminalSubscriptionService.stopped(new ServerStoppedEvent(server)); }
	}
	private static ItemStack iron(int count) { return new ItemStack(Items.IRON_INGOT, count); }
	private static ItemStack diamond(int count) { return new ItemStack(Items.DIAMOND, count); }

	@Test void defaultStillDisablesAutomaticReturnAndUsesInventory() {
		var preferences = ModConfig.CLIENT.terminalPreferences;
		assertFalse(preferences.returnCraftingOnClose.getDefault());
		assertEquals(TerminalPreferenceConfigSection.CraftingReturnTarget.INVENTORY, preferences.craftingReturnTarget.getDefault());
	}
	@Test void eligibleNamedMaterialsEnterLedgerAndOthersReturnWithFullComponents() throws Exception {
		var named = iron(17); named.set(DataComponents.CUSTOM_NAME, Component.literal("保留名字"));
		var box = new ItemStack(Items.SHULKER_BOX); box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(diamond(3))));
		try (var r = new Rig(List.of(iron(64), named, diamond(5), box))) {
			long generation = r.crafting.generation(); var reply = r.store();
			assertEquals(Status.MOVED, reply.status()); assertEquals(87, reply.moved());
			assertEquals(64, r.balance(iron(1))); assertEquals(17, r.balance(named)); assertEquals(0, r.balance(diamond(1)));
			assertEquals(5, r.inventoryCount(diamond(1))); assertEquals(1, r.inventoryCount(box)); assertEquals(0, r.inventoryCount(iron(1)));
			assertTrue(r.account.state().grid().stream().allMatch(ItemStack::isEmpty));
			assertEquals(Status.STALE, r.send(CRAFT_STORE_ON_CLOSE, generation, -1, -1, -1, 0).status());
			assertEquals(64, r.balance(iron(1))); assertEquals(17, r.balance(named)); verify(r.data, times(1)).publish(any());
		}
	}
	@Test void fullInventoryRetainsRejectedRemainderAndStillAcceptsEligibleMaterials() throws Exception {
		try (var r = new Rig(List.of(iron(64), diamond(10)))) {
			r.fullInventory(); r.inventory.items.set(35, diamond(60));
			var reply = r.store(); assertEquals(Status.MOVED, reply.status()); assertEquals(68, reply.moved());
			assertEquals(64, r.balance(iron(1))); assertEquals(64, r.inventoryCount(diamond(1)));
			assertTrue(r.account.state().grid().getFirst().isEmpty()); assertTrue(ItemStack.matches(diamond(6), r.account.state().grid().get(1)));
			assertEquals(35 * 64, r.inventoryCount(new ItemStack(Items.GOLD_INGOT)));
		}
	}
	@Test void absentNetworkUnreadyPolicyAndRejectedPolicyUseInventoryFallback() throws Exception {
		for (int mode = 0; mode < 3; mode++) try (var r = new Rig(List.of(iron(10)))) {
			if (mode == 0) r.network = false;
			if (mode == 1) r.policy = null;
			if (mode == 2) r.policy = new ProductPolicySnapshot(0, List.of(), List.of());
			assertEquals(Status.MOVED, r.store().status()); assertEquals(10, r.inventoryCount(iron(1))); assertEquals(0, r.balance(iron(1)));
			verify(r.data, never()).publish(any());
		}
		try (var r = new Rig(List.of(diamond(10)))) {
			r.fullInventory(); var before = r.account.state();
			assertEquals(Status.NO_SPACE, r.store().status()); assertSame(before, r.account.state()); verify(r.data, never()).publish(any());
		}
	}
	@Test void originalInventoryReturnDoesNotQueryOrPublishNetworkPolicy() throws Exception {
		try (var r = new Rig(List.of(iron(10)))) {
			assertEquals(Status.MOVED, r.send(CRAFT_RETURN_ON_CLOSE, r.crafting.generation(), -1, -1, -1, 0).status());
			assertEquals(10, r.inventoryCount(iron(1))); assertEquals(0, r.balance(iron(1))); r.policies.verifyNoInteractions();
			verify(r.host, never()).craftingLedger(r.player); verify(r.data, never()).publish(any());
		}
	}
	@Test void nonOwnersAndSharedAccountsCannotAutomaticallyReturnMaterials() throws Exception {
		for (boolean shared : new boolean[]{false, true}) try (var r = new Rig(List.of(iron(10)))) {
			if (shared) {
				var other = mock(ServerPlayer.class);
				other.containerMenu = mock(AbstractContainerMenu.class, withSettings().extraInterfaces(TerminalCraftingMenu.Host.class));
				when(((TerminalCraftingMenu.Host) other.containerMenu).craftingAccount(other)).thenReturn(r.account);
				when(r.players.getPlayers()).thenReturn(List.of(r.player, other));
			} else when(r.player.getUUID()).thenReturn(UUID.randomUUID());
			var before = r.account.state(); assertEquals(Status.UNAVAILABLE, r.store().status()); assertSame(before, r.account.state());
			assertEquals(0, r.balance(iron(1))); assertEquals(0, r.inventoryCount(iron(1)));
		}
	}
	@Test void invalidFramesAndQuarantinedResultsNeverPublish() throws Exception {
		for (int mode = 0; mode < 5; mode++) try (var r = new Rig(List.of(iron(10)))) {
			var before = r.account.state();
			var result = r.send(CRAFT_STORE_ON_CLOSE, mode == 0 ? 0 : r.crafting.generation(), mode == 1 ? 0 : -1, mode == 2 ? 0 : -1, mode == 3 ? 0 : -1, mode == 4 ? 1 : 0);
			assertEquals(mode == 0 ? Status.STALE : Status.INVALID, result.status()); assertSame(before, r.account.state());
			verify(r.data, never()).publish(any());
		}
		try (var r = new Rig(List.of(iron(10)))) {
			r.account = r.account(List.of(iron(10)), diamond(1), null, true); r.subscribe();
			var before = r.account.state(); assertEquals(Status.UNAVAILABLE, r.store().status()); assertSame(before, r.account.state());
		}
	}
	@Test void stateChangesDuringPlanningRejectBothPreparedDestinations() throws Exception {
		for (int mode = 0; mode < 6; mode++) try (var r = new Rig(List.of(iron(10), diamond(3)))) {
			int scenario = mode; boolean[] changed = {false}; var before = r.account.state();
			when(r.host.lockedNativeStack(any())).thenAnswer(call -> {
				if (!changed[0]) {
					changed[0] = true;
					switch (scenario) {
						case 0 -> r.inventory.items.set(35, new ItemStack(Items.GOLD_INGOT));
						case 1 -> r.policy = new ProductPolicySnapshot(0, List.of(), List.of());
						case 2 -> { var checkpoint = r.data.checkpoint(); r.data.publish(checkpoint.insertProduct(checkpoint.ledger().revision(), r.key(iron(1)), 1, r.policy)); }
						case 3 -> r.account = r.account(List.of(diamond(7)), ItemStack.EMPTY, null, false);
						case 4 -> r.player.containerMenu = mock(AbstractContainerMenu.class);
						case 5 -> when(r.menu.stillValid(r.player)).thenReturn(false);
					}
				}
				return false;
			});
			assertEquals(Status.STALE, r.store().status(), "mode=" + mode);
			assertEquals(mode == 2 ? 1 : 0, r.balance(iron(1))); assertEquals(0, r.inventoryCount(iron(1))); assertEquals(0, r.inventoryCount(diamond(1)));
			if (mode != 3) assertSame(before, r.account.state());
			else assertTrue(ItemStack.matches(diamond(7), r.account.state().grid().getFirst()));
		}
	}
	@Test void lockedInventoryTargetRejectsNetworkAndFallbackTogether() throws Exception {
		try (var r = new Rig(List.of(iron(10), diamond(3)))) {
			r.inventory.items.set(0, diamond(1));
			when(r.host.lockedNativeStack(any())).thenAnswer(call -> ((ItemStack) call.getArgument(0)).is(Items.DIAMOND));
			var before = r.account.state(); assertEquals(Status.UNAVAILABLE, r.store().status()); assertSame(before, r.account.state());
			assertEquals(0, r.balance(iron(1))); assertEquals(1, r.inventoryCount(diamond(1)));
		}
	}
	@Test void publicationFailuresPreserveOwnershipAndCannotReplayCommittedCredit() throws Exception {
		for (boolean after : new boolean[]{false, true}) try (var r = new Rig(List.of(iron(10), diamond(3)))) {
			long generation = r.crafting.generation(); var before = r.account.state();
			doAnswer(call -> { if (after) call.callRealMethod(); throw new IllegalStateException("Injected authority publication failure"); }).when(r.data).publish(any());
			assertEquals(Status.UNAVAILABLE, r.store().status());
			assertEquals(after ? 10 : 0, r.balance(iron(1))); assertEquals(after ? 3 : 0, r.inventoryCount(diamond(1)));
			if (after) {
				assertTrue(r.account.state().grid().stream().allMatch(ItemStack::isEmpty));
				assertEquals(Status.STALE, r.send(CRAFT_STORE_ON_CLOSE, generation, -1, -1, -1, 0).status());
			} else assertSame(before, r.account.state());
			verify(r.data, times(1)).publish(any());
		}
	}
	@Test void inventoryNotificationFailureAfterCommitDoesNotRestoreOrDuplicateAssets() throws Exception {
		try (var r = new Rig(List.of(iron(10), diamond(3)))) {
			long generation = r.crafting.generation(); doThrow(new IllegalStateException("Inventory notification failed")).when(r.inventory).setChanged();
			assertEquals(Status.UNAVAILABLE, r.store().status()); assertEquals(10, r.balance(iron(1))); assertEquals(3, r.inventoryCount(diamond(1)));
			assertTrue(r.account.state().grid().stream().allMatch(ItemStack::isEmpty));
			assertEquals(Status.STALE, r.send(CRAFT_STORE_ON_CLOSE, generation, -1, -1, -1, 0).status());
		}
	}
	@Test void knownMaterialsReturnWhilePaidResultAndUnknownExtractionSurviveSerialization() throws Exception {
		try (var r = new Rig(List.of(iron(10)))) {
			var unresolved = new TerminalCraftingAccount.MaterialRequest(0, iron(2), "test:unknown-source");
			r.account = r.account(List.of(iron(10)), diamond(1), unresolved, false); r.subscribe();
			assertEquals(Status.MOVED, r.store().status()); assertEquals(10, r.balance(iron(1)));
			assertTrue(ItemStack.matches(diamond(1), r.account.state().pending())); assertNotNull(r.account.state().materialRequest());
			var restored = TerminalCraftingAccount.load(r.account.save(new CompoundTag(), r.registries), r.registries);
			assertTrue(restored.available()); assertTrue(restored.state().grid().stream().allMatch(ItemStack::isEmpty)); assertTrue(ItemStack.matches(diamond(1), restored.state().pending()));
			assertEquals(0, restored.state().materialRequest().slot()); assertTrue(ItemStack.matches(iron(2), restored.state().materialRequest().item()));
			assertEquals("test:unknown-source", restored.state().materialRequest().source());
			var checkpoint = new NetworkCheckpointCodec(key -> ProductKeyCodec.validatePersisted(key, r.registries)).decode(NetworkCheckpointCodec.encode(r.data.checkpoint()));
			assertEquals(r.data.checkpoint(), checkpoint);
		}
	}
}
