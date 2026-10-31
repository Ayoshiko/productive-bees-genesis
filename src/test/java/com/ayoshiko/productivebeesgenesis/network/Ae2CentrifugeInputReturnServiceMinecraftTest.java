package com.ayoshiko.productivebeesgenesis.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2OutputStateHolder;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2ItemFingerprint;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2InputHost;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import mekanism.api.Action;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("minecraft")
class Ae2CentrifugeInputReturnServiceMinecraftTest {

	@Test
	void oneShotCheckMovesUnprocessableOrdinaryPendingWorkToNetworkOnly() {
		Fixture fixture = new Fixture();
		String fingerprint = Ae2ItemFingerprint.encodeOrLegacy(AEItemKey.of(Items.RAW_IRON),
				RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
		fixture.holder.getPendingItemBuffer().enqueue(fingerprint, 5L, 20L);
		when(fixture.host.productivebeesgenesis$canProcessInput(any(ItemStack.class))).thenReturn(false);

		assertEquals(5L, Ae2CentrifugeInputReturnService.reclassifyUnprocessablePendingInputs(
				fixture.host, fixture.holder));

		var pending = fixture.holder.getPendingItemBuffer().snapshot(20L).getFirst();
		assertEquals(0L, pending.amount());
		assertEquals(5L, pending.returnToNetworkAmount());
		assertEquals(5L, fixture.holder.getPendingItemBuffer().getTotalAmount());
	}

	@Test
	void returnsOnlyInputsRejectedByTheMachinePredicate() {
		Fixture fixture = new Fixture();
		IInventorySlot rawIron = fixture.slot(new ItemStack(Items.RAW_IRON, 4));
		IInventorySlot ironIngot = fixture.slot(new ItemStack(Items.IRON_INGOT, 3));
		AEItemKey key = AEItemKey.of(Items.RAW_IRON);
		MEStorage storage = mock(MEStorage.class);
		stubInsert(storage, key, 4L, 4L);

		CentrifugeAeReturnResult result = Ae2CentrifugeInputReturnService.returnInputSlots(
				fixture.host, fixture.holder, storage, List.of(rawIron, ironIngot),
				stack -> stack.getItem() == Items.RAW_IRON);

		assertEquals(4L, result.returnedToAe());
		assertEquals(0L, result.pending());
		assertTrue(rawIron.isEmpty());
		assertEquals(3, ironIngot.getStack().getCount());
	}

	@Test
	void rejectedSimulationLeavesTheSourceSlotUntouched() {
		Fixture fixture = new Fixture();
		IInventorySlot input = fixture.slot(new ItemStack(Items.RAW_IRON, 5));
		AEItemKey key = AEItemKey.of(Items.RAW_IRON);
		MEStorage storage = mock(MEStorage.class);
		when(storage.insert(eq(key), anyLong(), eq(Actionable.SIMULATE), any())).thenReturn(0L);

		CentrifugeAeReturnResult result = Ae2CentrifugeInputReturnService.returnInputSlots(
				fixture.host, fixture.holder, storage, List.of(input), stack -> true);

		assertEquals(0L, result.returnedToAe());
		assertEquals(0L, result.pending());
		assertEquals(5, input.getStack().getCount());
		verify(storage, never()).insert(eq(key), anyLong(), eq(Actionable.MODULATE), any());
	}

	@Test
	void knownPartialAcceptanceIsPersistedForRetry() {
		Fixture fixture = new Fixture();
		IInventorySlot input = fixture.slot(new ItemStack(Items.RAW_IRON, 5));
		AEItemKey key = AEItemKey.of(Items.RAW_IRON);
		MEStorage storage = mock(MEStorage.class);
		stubInsert(storage, key, 5L, 2L);

		CentrifugeAeReturnResult result = Ae2CentrifugeInputReturnService.returnInputSlots(
				fixture.host, fixture.holder, storage, List.of(input), stack -> true);

		assertEquals(2L, result.returnedToAe());
		assertEquals(3L, result.pending());
		assertTrue(input.isEmpty());
		assertTrue(fixture.holder.getPendingItemBuffer().hasRetryableItems(20L));
		assertEquals(0L, fixture.holder.getPendingItemBuffer().snapshot(20L).getFirst().amount());
		assertEquals(3L,
				fixture.holder.getPendingItemBuffer().snapshot(20L).getFirst().returnToNetworkAmount());
	}

	@Test
	void unknownModulateResultIsPersistedWithoutAutomaticRetry() {
		Fixture fixture = new Fixture();
		IInventorySlot input = fixture.slot(new ItemStack(Items.RAW_IRON, 5));
		AEItemKey key = AEItemKey.of(Items.RAW_IRON);
		MEStorage storage = mock(MEStorage.class);
		when(storage.insert(eq(key), anyLong(), eq(Actionable.SIMULATE), any()))
				.thenAnswer(call -> call.getArgument(1));
		when(storage.insert(eq(key), anyLong(), eq(Actionable.MODULATE), any()))
				.thenThrow(new IllegalStateException("unknown network result"));

		CentrifugeAeReturnResult result = Ae2CentrifugeInputReturnService.returnInputSlots(
				fixture.host, fixture.holder, storage, List.of(input), stack -> true);

		assertEquals(0L, result.returnedToAe());
		assertEquals(5L, result.pending());
		assertTrue(input.isEmpty());
		assertFalse(fixture.holder.getPendingItemBuffer().hasRetryableItems(20L));
		assertTrue(fixture.holder.getPendingItemBuffer().snapshot(20L).isEmpty());
		assertEquals(5L, fixture.holder.getPendingItemBuffer().getTotalAmount());
	}

	@Test
	void pastedOffConfigurationReturnsStuckInputsEvenWithPullingDisabled() {
		var tile = mock(com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge.class);
		var holder = new Ae2OutputStateHolder();
		var source = new Ae2OutputStateHolder();
		var card = new net.minecraft.nbt.CompoundTag();
		source.savePerTileState(card);
		Level level = mock(Level.class);
		when(level.registryAccess()).thenReturn(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
		when(tile.productivebeesgenesis$getAe2Level()).thenReturn(level);
		when(tile.productivebeesgenesis$getAe2StateHolder()).thenReturn(holder);
		when(tile.productivebeesgenesis$canProcessInput(any())).thenAnswer(call ->
				((ItemStack) call.getArgument(0)).is(Items.HONEYCOMB));
		var iron = mekanism.common.inventory.slot.BasicInventorySlot.at(null, 0, 0);
		iron.setStack(new ItemStack(Items.RAW_IRON, 64));
		var comb = mekanism.common.inventory.slot.BasicInventorySlot.at(null, 0, 0);
		comb.setStack(new ItemStack(Items.HONEYCOMB, 32));
		MEStorage storage = mock(MEStorage.class);
		stubInsert(storage, AEItemKey.of(Items.RAW_IRON), 64L, 64L);
		com.ayoshiko.productivebeesgenesis.mek.CentrifugeFactoryCommonLogic.readSustainedData(card, holder);
		assertFalse(holder.isAeItemInputEnabled());
		try (var grid = org.mockito.Mockito.mockStatic(
				com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2GridNodeManager.class)) {
			grid.when(() -> com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2GridNodeManager.getGridNodeState(tile))
					.thenReturn(com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2GridNodeManager.STATE_ONLINE);
			grid.when(() -> com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2GridNodeManager.getCachedMeStorage(holder, tile))
					.thenReturn(storage);
			assertFalse(Ae2CentrifugeInputReturnService.returnUnprocessableInputs(tile, List.of(iron, comb)));
		}
		assertTrue(iron.isEmpty());
		assertEquals(32, comb.getCount());
		verify(storage).insert(eq(AEItemKey.of(Items.RAW_IRON)), eq(64L), eq(Actionable.MODULATE), any());
		assertFalse(holder.isUnprocessableInputReturnCheckPending());
	}

	private static void stubInsert(MEStorage storage, AEItemKey key, long simulated, long modulated) {
		when(storage.insert(eq(key), anyLong(), eq(Actionable.SIMULATE), any()))
				.thenAnswer(call -> Math.min(simulated, call.getArgument(1)));
		when(storage.insert(eq(key), anyLong(), eq(Actionable.MODULATE), any()))
				.thenAnswer(call -> Math.min(modulated, call.getArgument(1)));
	}

	private static final class Fixture {
		private final IAe2InputHost host = mock(IAe2InputHost.class);
		private final Ae2OutputStateHolder holder = new Ae2OutputStateHolder();

		private Fixture() {
			Level level = mock(Level.class);
			when(level.registryAccess()).thenReturn(
					RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
			when(level.getGameTime()).thenReturn(20L);
			when(host.productivebeesgenesis$getAe2Level()).thenReturn(level);
		}

		private IInventorySlot slot(ItemStack initial) {
			IInventorySlot slot = mock(IInventorySlot.class);
			AtomicReference<ItemStack> current = new AtomicReference<>(initial);
			when(slot.getStack()).thenAnswer(call -> current.get());
			when(slot.isEmpty()).thenAnswer(call -> current.get().isEmpty());
			when(slot.shrinkStack(anyInt(), any(Action.class))).thenAnswer(call -> {
				int requested = call.getArgument(0);
				Action action = call.getArgument(1);
				ItemStack stack = current.get();
				int removed = Math.min(requested, stack.getCount());
				if (action == Action.EXECUTE) {
					stack.shrink(removed);
					if (stack.isEmpty()) current.set(ItemStack.EMPTY);
				}
				return removed;
			});
			return slot;
		}
	}
}
