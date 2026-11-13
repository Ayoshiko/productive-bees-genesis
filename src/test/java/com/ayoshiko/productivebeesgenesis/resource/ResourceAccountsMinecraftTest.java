package com.ayoshiko.productivebeesgenesis.resource;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Tag("minecraft")
class ResourceAccountsMinecraftTest {
	static final UUID OWNER = UUID.fromString("986297bc-b893-44bf-82b7-3e9541e8d95d");
	static final ResourceKey HYDROGEN = ResourceKey.chemical(ResourceLocation.parse("mekanism:hydrogen"));
	static final ResourceAccount.Binding BINDING = new ResourceAccount.Binding(
			ResourceLocation.parse("minecraft:overworld"), 123, ResourceLocation.parse("productivebees:advanced_hive"));

	static ResourceAccount account(ResourceAccounts accounts) { return accounts.create(OWNER, BINDING); }

	static void produce(ResourceAccount account, long amount) {
		var ticket = account.reserve(account.reference(), 7, new CompoundTag(), Map.of(HYDROGEN, amount));
		account.start(ticket);
		account.freeze(ticket, Map.of(HYDROGEN, amount));
		account.commit(ticket);
	}

    @Test void safelyRetiredEmptyAccountsDoNotConsumeHistoricalCapacityOrReplay() {
        var store = new ResourceAccounts(() -> {});
        for (int i = 0; i < 1025; i++) {
            var account = account(store);
            var old = account.reference();
            var retired = account.retire(old, BINDING);
            assertTrue(store.retireEmpty(retired));
            assertEquals(0, store.size());
            assertNull(store.find(old.sourceId()));
            assertThrows(IllegalStateException.class, () -> account.claim(OWNER, retired, BINDING));
            assertThrows(IllegalStateException.class, () -> store.bound(old, BINDING));
        }
        var loaded = ResourceAccounts.load(store.save(), () -> {});
        assertEquals(0, loaded.size());
        assertNotNull(account(loaded));
    }

    @Test void retirementRefusesBalancesWorkAndUncertainRequests() {
        for (int kind = 0; kind < 4; kind++) {
            var store = new ResourceAccounts(() -> {});
            var account = account(store);
            if (kind == 0) produce(account, 1);
            if (kind == 1) account.centrifugeWork.put(0, new CompoundTag());
            if (kind == 2) account.reserve(account.reference(), 1, new CompoundTag(), Map.of(HYDROGEN, 1L));
            if (kind == 3) { produce(account, 1); account.beginTransfer(account.reference(), HYDROGEN, 1); }
            account.retire(account.reference(), BINDING);
            var before = store.save();
            assertFalse(store.retireEmpty(account.reference()));
            assertEquals(before, store.save());
        }
    }

	@Test void storageReservationRejectsBeforeMutationAndCoversRecovery() {
		var measured = new ResourceAccounts(() -> {});
		var account = account(measured);
		var context = new CompoundTag();
		context.putString("host", "resource_centrifuging");
		context.putInt("lane", 0);
		context.putString("evidence", "x".repeat(2048));
		long baseline = measured.reservedStorage();
		var ticket = account.reserve(account.reference(), 1, context, Map.of(HYDROGEN, 12L));
		long reserved = measured.reservedStorage();
		assertTrue(reserved > baseline);
		account.start(ticket);
		account.freeze(ticket, Map.of(HYDROGEN, 12L));
		account.suspendProduction(ticket, "x".repeat(512));
		assertFalse(account.canRelocate(account.reference()));
		var snapshot = new CompoundTag();
		snapshot.put("data", measured.save());
		net.minecraft.nbt.NbtUtils.addCurrentDataVersion(snapshot);
		assertTrue(snapshot.sizeInBytes() <= reserved);
		var restored = ResourceAccounts.load(measured.save(), () -> {});
		assertFalse(restored.hasInvalidData());
		assertTrue(restored.reservedStorage() <= reserved);
		account.resolveProduction(OWNER, ticket, true);
		assertEquals(12L, account.available().get(HYDROGEN));
		assertTrue(measured.reservedStorage() < reserved);

		var limited = new ResourceAccounts(() -> {}, reserved - 1);
		var rejected = account(limited);
		var before = limited.save();
		assertThrows(IllegalStateException.class, () -> rejected.reserve(rejected.reference(), 1, context, Map.of(HYDROGEN, 12L)));
		assertEquals(before, limited.save());
		assertNull(rejected.productionTicket());
	}

	@Test void productionFreezesOnceAndCommitCannotBeRepeated() {
		var store = new ResourceAccounts(() -> {});
		var account = account(store);
		var context = new CompoundTag();
		context.putString("bee", "example:hydrogen");
		var ticket = account.reserve(account.reference(), 8, context, Map.of(HYDROGEN, 300L));
		context.putString("bee", "changed");
		account.start(ticket);
		assertThrows(IllegalStateException.class, () -> account.cancel(ticket));
		account.productionContext(ticket).putString("bee", "external mutation");
		account.freeze(ticket, Map.of(HYDROGEN, 200L));
		var frozen = store.save();
		assertThrows(IllegalStateException.class, () -> account.freeze(ticket, Map.of(HYDROGEN, 100L)));
		assertEquals(frozen, store.save());
		assertTrue(account.available().isEmpty());
		account.commit(ticket);
		assertEquals(200L, account.available().get(HYDROGEN));
		assertThrows(IllegalStateException.class, () -> account.commit(ticket));
		assertEquals("example:hydrogen", frozen.getList("accounts", 10).getCompound(0)
				.getCompound("production").getCompound("context").getString("bee"));
	}

	@Test void overflowAndOverReservationDoNotPartiallyChangeState() {
		var store = new ResourceAccounts(() -> {});
		var account = account(store);
		produce(account, Long.MAX_VALUE - 1);
		var saved = store.save();
		assertThrows(ArithmeticException.class, () ->
				account.reserve(account.reference(), 7, new CompoundTag(), Map.of(HYDROGEN, 2L)));
		assertEquals(saved, store.save());
		var ticket = account.reserve(account.reference(), 7, new CompoundTag(), Map.of(HYDROGEN, 1L));
		account.start(ticket);
		var started = store.save();
		assertThrows(IllegalArgumentException.class, () -> account.freeze(ticket, Map.of(HYDROGEN, 2L)));
		assertEquals(started, store.save());
		account.freeze(ticket, Map.of(HYDROGEN, 1L));
		account.commit(ticket);
		assertEquals(Long.MAX_VALUE, account.available().get(HYDROGEN));
	}

	@Test void partialTransferKeepsRemainderAndRejectsReentry() {
		var store = new ResourceAccounts(() -> {});
		var account = account(store);
		produce(account, 1000);
		var ticket = account.beginTransfer(account.reference(), HYDROGEN, 600);
		assertEquals(400L, account.available().get(HYDROGEN));
		var saved = store.save();
		assertThrows(IllegalStateException.class, () -> account.beginTransfer(account.reference(), HYDROGEN, 1));
		assertThrows(IllegalStateException.class, () ->
				account.reserve(account.reference(), 9, new CompoundTag(), Map.of(HYDROGEN, 1L)));
		assertEquals(saved, store.save());
		account.completeTransfer(ticket, 250);
		assertEquals(750L, account.available().get(HYDROGEN));
		assertThrows(IllegalStateException.class, () -> account.completeTransfer(ticket, 250));
	}

	@Test void uncertainTransferStaysUnavailableAcrossReloadAndRequiresOwnerResolution() {
		var store = new ResourceAccounts(() -> {});
		var account = account(store);
		produce(account, 1000);
		var ticket = account.beginTransfer(account.reference(), HYDROGEN, 600);
		account.suspendTransfer(ticket, "Receiver threw after mutation");
		var reloaded = ResourceAccounts.load(store.save(), () -> {});
		var restored = reloaded.find(account.reference().sourceId());
		assertEquals(400L, restored.available().get(HYDROGEN));
		assertThrows(IllegalStateException.class, () -> restored.completeTransfer(ticket, 0));
		assertThrows(SecurityException.class, () -> restored.resolveTransfer(UUID.randomUUID(), ticket, 300));
		restored.resolveTransfer(OWNER, ticket, 300);
		assertEquals(700L, restored.available().get(HYDROGEN));
		assertThrows(IllegalStateException.class, () -> restored.resolveTransfer(OWNER, ticket, 300));
	}

	@Test void invalidAcceptedAmountQuarantinesWholeRequestedAmount() {
		for (long invalid : new long[]{-1, 601}) {
			var store = new ResourceAccounts(() -> {});
			var account = account(store);
			produce(account, 1000);
			var ticket = account.beginTransfer(account.reference(), HYDROGEN, 600);
			assertThrows(IllegalArgumentException.class, () -> account.completeTransfer(ticket, invalid));
			assertEquals(400L, account.available().get(HYDROGEN));
			assertFalse(account.suspension().isEmpty());
			account.resolveTransfer(OWNER, ticket, 0);
			assertEquals(1000L, account.available().get(HYDROGEN));
		}
	}

	@Test void restartNeverAutomaticallyCommitsOrCancelsPendingProduction() {
		for (var phase : new ResourceAccount.Phase[]{ResourceAccount.Phase.RESERVED,
				ResourceAccount.Phase.STARTED, ResourceAccount.Phase.FROZEN}) {
			var store = new ResourceAccounts(() -> {});
			var account = account(store);
			var ticket = account.reserve(account.reference(), 8, new CompoundTag(), Map.of(HYDROGEN, 100L));
			if (phase != ResourceAccount.Phase.RESERVED) account.start(ticket);
			if (phase == ResourceAccount.Phase.FROZEN) account.freeze(ticket, Map.of(HYDROGEN, 70L));
			var restored = ResourceAccounts.load(store.save(), () -> {}).find(account.reference().sourceId());
			assertFalse(restored.suspension().isEmpty());
			assertTrue(restored.available().isEmpty());
			if (phase == ResourceAccount.Phase.FROZEN) {
				restored.resolveProduction(OWNER, ticket, true);
				assertEquals(70L, restored.available().get(HYDROGEN));
			} else {
				assertThrows(IllegalStateException.class, () -> restored.resolveProduction(OWNER, ticket, true));
				restored.resolveProduction(OWNER, ticket, false);
				assertTrue(restored.available().isEmpty());
			}
		}
	}

	@Test void copiedSourceReferenceCannotClaimTwiceOrUseOldBinding() {
		var store = new ResourceAccounts(() -> {});
		var account = account(store);
		produce(account, 90);
		var old = account.reference();
		var portable = account.detach(OWNER, old);
		assertThrows(IllegalStateException.class, () -> store.bound(old, BINDING));
		assertThrows(SecurityException.class, () -> account.claim(UUID.randomUUID(), portable, BINDING));
		var moved = new ResourceAccount.Binding(BINDING.dimension(), 456, BINDING.hostKind());
		var active = account.claim(OWNER, portable, moved);
		assertThrows(IllegalStateException.class, () -> account.claim(OWNER, portable, BINDING));
		assertThrows(IllegalStateException.class, () -> account.beginTransfer(old, HYDROGEN, 1));
		assertSame(account, store.bound(active, moved));
		assertEquals(90L, account.available().get(HYDROGEN));
		var other = store.create(OWNER, BINDING);
		var otherPortable = other.detach(OWNER, other.reference());
		assertThrows(IllegalStateException.class, () -> other.claim(OWNER, otherPortable, moved));
	}

	@Test void invalidDataPreservesWholeRawStoreAndBlocksNewAssets() {
		var source = new ResourceAccounts(() -> {});
		produce(account(source), 12);
		var raw = source.save();
		var account = raw.getList("accounts", 10).getCompound(0);
		account.getList("available", 10).getCompound(0).putLong("amount", -1);
		var restored = ResourceAccounts.load(raw, () -> {});
		assertTrue(restored.hasInvalidData());
		assertEquals(raw, restored.save());
		assertThrows(IllegalStateException.class, () -> restored.create(OWNER, BINDING));
		restored.save().putInt("version", 99);
		assertEquals(raw, restored.save());
	}

	@Test void unknownVersionAndDuplicateAccountIdentityAreNotSilentlyDropped() {
		var source = new ResourceAccounts(() -> {});
		account(source);
		var raw = source.save();
		raw.putInt("version", 2);
		assertEquals(raw, ResourceAccounts.load(raw, () -> {}).save());
		raw.putInt("version", 1);
		ListTag entries = raw.getList("accounts", 10);
		entries.add(entries.getCompound(0).copy());
		var restored = ResourceAccounts.load(raw, () -> {});
		assertTrue(restored.hasInvalidData());
		assertEquals(raw, restored.save());
	}

	@Test void accountAndIdentityBudgetsRejectWithoutEvictingAssets() {
		var store = new ResourceAccounts(() -> {});
		var first = account(store);
		produce(first, 10);
		for (int i = 1; i < ResourceAccounts.MAX_ACCOUNTS; i++) {
			store.create(OWNER, new ResourceAccount.Binding(BINDING.dimension(), 1000L + i, BINDING.hostKind()));
		}
		var before = store.save();
		assertThrows(IllegalStateException.class, () -> store.create(OWNER,
				new ResourceAccount.Binding(BINDING.dimension(), 9999, BINDING.hostKind())));
		assertEquals(before, store.save());
		assertEquals(10L, first.available().get(HYDROGEN));
		var payload = new CompoundTag();
		payload.putByteArray("oversized", new byte[2048]);
		assertThrows(IllegalArgumentException.class, () -> new ResourceKey(HYDROGEN.kind(), 1, payload));
		var many = new java.util.LinkedHashMap<ResourceKey, Long>();
		for (int i = 0; i <= ResourceAmounts.MAX_KEYS; i++) {
			many.put(ResourceKey.chemical(ResourceLocation.parse("example:c" + i)), 1L);
		}
		assertThrows(IllegalArgumentException.class,
				() -> first.reserve(first.reference(), 0, new CompoundTag(), many));
		assertEquals(before, store.save());
	}

	@Test void removedHostKeepsFrozenOutputAcrossRestartAndInvalidatesOldTickets() {
		var store = new ResourceAccounts(() -> {});
		var account = account(store);
		produce(account, 90);
		var ticket = account.reserve(account.reference(), 7, new CompoundTag(), Map.of(HYDROGEN, 100L));
		account.start(ticket);
		account.freeze(ticket, Map.of(HYDROGEN, 70L));
		var detached = account.retire(account.reference(), BINDING);
		assertNull(account.binding());
		assertFalse(account.suspension().isEmpty());
		assertThrows(IllegalStateException.class, () -> account.resolveProduction(OWNER, ticket, true));
		assertThrows(IllegalStateException.class, () -> account.claim(OWNER, detached, BINDING));
		var restored = ResourceAccounts.load(store.save(), () -> {});
		assertFalse(restored.hasInvalidData());
		var recovered = restored.find(detached.sourceId());
		assertEquals(90L, recovered.available().get(HYDROGEN));
		assertEquals(70L, recovered.frozenProduction().get(HYDROGEN));
		recovered.resolveProduction(OWNER, recovered.productionTicket(), true);
		recovered.claim(OWNER, detached, BINDING);
		assertEquals(160L, recovered.available().get(HYDROGEN));
		assertThrows(IllegalStateException.class, () -> recovered.claim(OWNER, detached, BINDING));
	}

	@Test void removedHostDoesNotTurnUncertainTransferBackIntoSpendableBalance() {
		var store = new ResourceAccounts(() -> {});
		var account = account(store);
		produce(account, 1000);
		var old = account.beginTransfer(account.reference(), HYDROGEN, 600);
		account.retire(account.reference(), BINDING);
		var restored = ResourceAccounts.load(store.save(), () -> {}).find(account.reference().sourceId());
		assertEquals(400L, restored.available().get(HYDROGEN));
		assertEquals(600L, restored.uncertainTransfer().get(HYDROGEN));
		assertThrows(IllegalStateException.class, () -> restored.completeTransfer(old, 0));
		restored.resolveTransfer(OWNER, restored.transferTicket(), 250);
		assertEquals(750L, restored.available().get(HYDROGEN));
		assertNull(restored.binding());
	}


	@Test void uncertainLanePreservesTicketAndAllowsOtherProductionAcrossReload() {
		var store = new ResourceAccounts(() -> {});
		var account = account(store);
		var context = new CompoundTag();
		context.putString("host", "resource_centrifuging");
		context.putInt("lane", 1);
		var ticket = account.reserve(account.reference(), 7, context, Map.of(HYDROGEN, 100L));
		account.start(ticket);
		account.freeze(ticket, Map.of(HYDROGEN, 70L));
		account.suspendProduction(ticket, "incomplete input debit");
		assertTrue(account.laneSuspended(1));
		assertFalse(account.laneSuspended(0));
		assertTrue(account.canExtract(account.reference()));
		assertThrows(IllegalStateException.class, () -> account.reserve(account.reference(), 7, context, Map.of(HYDROGEN, 1L)));
		produce(account, 50);
		var restored = ResourceAccounts.load(store.save(), () -> {});
		assertFalse(restored.hasInvalidData());
		var next = restored.find(ticket.source().sourceId());
		assertTrue(next.laneSuspended(1));
		assertEquals(ticket, next.productionTicket());
		assertEquals(50L, next.available().get(HYDROGEN));
		assertEquals(70L, next.frozenProduction().get(HYDROGEN));
		assertThrows(IllegalStateException.class, () -> next.commit(ticket));
		produce(next, 20);
		next.resolveProduction(OWNER, ticket, true);
		assertEquals(140L, next.available().get(HYDROGEN));
		assertFalse(next.laneSuspended(1));
		assertThrows(IllegalStateException.class, () -> next.resolveProduction(OWNER, ticket, true));
	}

	@Test void suspendedMaximumPreventsLaterOverflowAndRetirement() {
		var store = new ResourceAccounts(() -> {});
		var account = account(store);
		var context = new CompoundTag();
		context.putString("host", "resource_centrifuging");
		context.putInt("lane", 1);
		var ticket = account.reserve(account.reference(), 7, context, Map.of(HYDROGEN, Long.MAX_VALUE));
		account.start(ticket);
		account.freeze(ticket, Map.of(HYDROGEN, Long.MAX_VALUE));
		account.suspendProduction(ticket, "uncertain");
		assertFalse(account.canReserve(account.reference(), Map.of(HYDROGEN, 1L)));
		assertThrows(ArithmeticException.class, () -> produce(account, 1));
		account.retire(account.reference(), BINDING);
		assertFalse(store.retireEmpty(account.reference()));
		var restored = ResourceAccounts.load(store.save(), () -> {}).find(account.reference().sourceId());
		assertEquals(Long.MAX_VALUE, restored.frozenProduction().get(HYDROGEN));
	}

	@Test void legacyUncertainBeeSlotKeepsOtherSlotsRunningAndResolvesOnce() {
		var store = new ResourceAccounts(() -> {});
		var account = account(store);
		var context = new CompoundTag();
		context.putString("host", "mek_apiary");
		context.putInt("slot", 3);
		var ticket = account.reserve(account.reference(), 7, context, Map.of(HYDROGEN, 100L));
		account.start(ticket);
		account.freeze(ticket, Map.of(HYDROGEN, 70L));
		var restored = ResourceAccounts.load(store.save(), () -> {});
		assertFalse(restored.hasInvalidData());
		var next = restored.find(ticket.source().sourceId());
		assertTrue(next.laneSuspended(3));
		assertTrue(next.canExtract(next.reference()));
		assertThrows(IllegalStateException.class, () -> next.reserve(next.reference(), 7, context, Map.of(HYDROGEN, 1L)));
		var healthyContext = context.copy();
		healthyContext.putInt("slot", 4);
		var healthy = next.reserve(next.reference(), 7, healthyContext, Map.of(HYDROGEN, 50L));
		next.start(healthy);
		next.freeze(healthy, Map.of(HYDROGEN, 50L));
		next.commit(healthy);
		assertEquals(50L, next.available().get(HYDROGEN));
		assertEquals(150L, next.reservedCapacity().get(HYDROGEN).longValueExact());
		var loaded = ResourceAccounts.load(restored.save(), () -> {}).find(ticket.source().sourceId());
		assertTrue(loaded.laneSuspended(3));
		assertEquals(70L, loaded.frozenProduction().get(HYDROGEN));
		loaded.resolveProduction(OWNER, ticket, true);
		assertEquals(120L, loaded.available().get(HYDROGEN));
		assertThrows(IllegalStateException.class, () -> loaded.resolveProduction(OWNER, ticket, true));
	}


	@Test void waitingOutputUsesFrozenAmountAndPreservesLegacyMaximumReservation() {
		var store = new ResourceAccounts(() -> {});
		var account = account(store);
		var first = frozenLane(account, 0, Long.MAX_VALUE, 7);
		var completion = account.deferCentrifuge(first, 0, null);
		assertEquals(7L, account.reservedCapacity().get(HYDROGEN).longValueExact());
		assertTrue(account.available().isEmpty());
		assertTrue(account.canReserve(account.reference(), Map.of(HYDROGEN, Long.MAX_VALUE - 7)));
		produce(account, Long.MAX_VALUE - 7);
		assertEquals(Long.MAX_VALUE, account.reservedCapacity().get(HYDROGEN).longValueExact());
		var restored = ResourceAccounts.load(store.save(), () -> {});
		assertFalse(restored.hasInvalidData());
		assertEquals(Long.MAX_VALUE, restored.find(first.source().sourceId()).reservedCapacity().get(HYDROGEN).longValueExact());
		completion.complete();
		assertEquals(Long.MAX_VALUE, account.available().get(HYDROGEN));
		assertFalse(account.laneSuspended(0));
		assertThrows(IllegalStateException.class, completion::complete);

		var legacyStore = new ResourceAccounts(() -> {});
		var legacy = account(legacyStore);
		var old = frozenLane(legacy, 0, Long.MAX_VALUE, 7);
		legacy.suspendProduction(old, "Input debit remains unknown");
		assertEquals(Long.MAX_VALUE, legacy.reservedCapacity().get(HYDROGEN).longValueExact());
		assertFalse(legacy.canReserve(legacy.reference(), Map.of(HYDROGEN, 1L)));

		var zeroStore = new ResourceAccounts(() -> {});
		var zero = account(zeroStore);
		var empty = frozenLane(zero, 0, Long.MAX_VALUE, 0);
		var emptyCompletion = zero.deferCentrifuge(empty, 0, null);
		assertTrue(zero.reservedCapacity().isEmpty());
		produce(zero, Long.MAX_VALUE);
		emptyCompletion.complete();
		assertEquals(Long.MAX_VALUE, zero.available().get(HYDROGEN));
	}

	@Test void deferredRequestsSettleTheirOriginalLanesAndKeepPaidWorkAcrossRecovery() {
		var store = new ResourceAccounts(() -> {});
		var account = account(store);
		var work = new CompoundTag();
		work.putInt("paid_ticks", 4);
		var oldWork = new CompoundTag();
		oldWork.putInt("paid_ticks", 2);
		account.centrifugeWork.put(0, oldWork);
		var first = frozenLane(account, 0, 100, 7, work);
		var firstCompletion = account.deferCentrifuge(first, 0, work);
		work.putInt("paid_ticks", 999);
		assertNull(account.centrifugeWork(0));
		assertEquals(4, account.requestData(first).getCompound("next_work").getInt("paid_ticks"));
		var second = frozenLane(account, 1, 100, 9);
		var secondCompletion = account.deferCentrifuge(second, 1, null);
		assertThrows(IllegalStateException.class, () -> account.resolveProduction(OWNER, first, true));
		assertThrows(IllegalStateException.class, () -> account.validateProductionResolution(first, false));
		assertThrows(SecurityException.class, () -> account.resolveProduction(UUID.randomUUID(), first, true));
		firstCompletion.uncertain("Combined item receipt unknown");
		assertThrows(IllegalStateException.class, firstCompletion::complete);
		secondCompletion.complete();
		assertTrue(account.laneSuspended(0));
		assertFalse(account.laneSuspended(1));
		assertEquals(9L, account.available().get(HYDROGEN));
		assertEquals(16L, account.reservedCapacity().get(HYDROGEN).longValueExact());
		var saved = store.save();
		var restoredStore = ResourceAccounts.load(saved, () -> {});
		assertFalse(restoredStore.hasInvalidData());
		var restored = restoredStore.find(first.source().sourceId());
		assertEquals(first, restored.productionTicket());
		assertEquals(4, restored.requestData(first).getCompound("next_work").getInt("paid_ticks"));
		assertThrows(IllegalStateException.class, () -> restored.commit(first));
		assertThrows(SecurityException.class, () -> restored.resolveProduction(UUID.randomUUID(), first, true));
		restored.resolveProduction(OWNER, first, true);
		assertEquals(16L, restored.available().get(HYDROGEN));
		assertEquals(4, restored.centrifugeWork(0).getInt("paid_ticks"));
		assertThrows(IllegalStateException.class, () -> restored.resolveProduction(OWNER, first, true));

		var discarded = ResourceAccounts.load(saved, () -> {}).find(first.source().sourceId());
		discarded.resolveProduction(OWNER, first, false);
		assertEquals(9L, discarded.available().get(HYDROGEN));
		assertEquals(4, discarded.centrifugeWork(0).getInt("paid_ticks"));
	}

	@Test void writeFailureBetweenDebitAndDeferralKeepsFrozenPaidPlan(@TempDir Path directory) {
		var path = directory.resolve("accounts.dat");
		var queued = new ArrayList<Runnable>();
		var data = ResourceAccountsSavedData.open(path, (snapshot, target) -> {
			throw new IOException("injected disk failure");
		}, queued::add);
		var store = data.accounts();
		var account = account(store);
		var oldWork = new CompoundTag();
		oldWork.putInt("paid_ticks", 12);
		oldWork.putLong("seed", 17L);
		account.centrifugeWork.put(0, oldWork.copy());
		var nextWork = new CompoundTag();
		nextWork.putInt("paid_ticks", 4);
		nextWork.putLong("seed", 93L);
		var expectedNextWork = nextWork.copy();
		var ticket = frozenLane(account, 0, 100, 7, nextWork);
		var frozen = account.requestData(ticket);
		assertTrue(frozen.getBoolean("next_work_captured"));
		assertEquals(expectedNextWork, frozen.getCompound("next_work"));
		assertEquals(100L, ResourceExactAmounts.load(frozen.get("maximum")).get(HYDROGEN).longValueExact());
		assertEquals(100L, account.production.capacityReservation().get(HYDROGEN).longValueExact());
		assertFalse(account.canReserve(account.reference(), Map.of(HYDROGEN, 1L)));
		data.save(path.toFile(), RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
		assertEquals(1, queued.size());

		// 按扣费扣料后、下一次账户查询前的边界投递 IO 完成信号。
		queued.removeFirst().run();
		assertTrue(store.persistenceFailure().isEmpty());
		assertSame(account, data.accounts().bound(account.reference(), BINDING));
		assertTrue(store.persistenceFailure().contains("injected disk failure"));
		var isolated = store.save();
		var refusal = assertThrows(IllegalStateException.class,
				() -> account.deferCentrifuge(ticket, 0, nextWork));
		assertTrue(refusal.getMessage().startsWith("Resource accounts are isolated:"));
		assertThrows(IllegalStateException.class, () -> account.suspendProduction(ticket, "debit receipt unavailable"));
		assertEquals(isolated, store.save());
		assertEquals(frozen, account.requestData(ticket));
		assertEquals(oldWork, account.centrifugeWork(0));
		assertTrue(account.available().isEmpty());
		assertEquals(100L, account.production.capacityReservation().get(HYDROGEN).longValueExact());

		var restoredStore = ResourceAccounts.load(isolated, () -> {});
		assertFalse(restoredStore.hasInvalidData());
		var restored = restoredStore.find(ticket.source().sourceId());
		assertTrue(restored.laneSuspended(0));
		assertEquals("FROZEN", restored.productionPhase(ticket));
		assertEquals(expectedNextWork, restored.requestData(ticket).getCompound("next_work"));
		assertEquals(oldWork, restored.centrifugeWork(0));
		assertTrue(restored.available().isEmpty());
		assertEquals(100L, restored.reservedCapacity().get(HYDROGEN).longValueExact());
		restored.resolveProduction(OWNER, ticket, true);
		assertEquals(expectedNextWork, restored.centrifugeWork(0));
		assertEquals(93L, restored.centrifugeWork(0).getLong("seed"));
		assertEquals(7L, restored.available().get(HYDROGEN));
		assertFalse(restored.laneSuspended(0));

		var discardedStore = ResourceAccounts.load(isolated, () -> {});
		assertFalse(discardedStore.hasInvalidData());
		var discarded = discardedStore.find(ticket.source().sourceId());
		discarded.resolveProduction(OWNER, ticket, false);
		assertNull(discarded.centrifugeWork(0));
		assertTrue(discarded.available().isEmpty());
		assertFalse(discarded.laneSuspended(0));
	}

	@Test void frozenPlanRejectsProgressAndSeedReplacementWithoutMutation() {
		var store = new ResourceAccounts(() -> {});
		var account = account(store);
		var work = new CompoundTag();
		work.putInt("paid_ticks", 4);
		work.putLong("seed", 93L);
		var ticket = frozenLane(account, 0, 100, 7, work);
		var saved = store.save();
		var differentProgress = work.copy();
		differentProgress.putInt("paid_ticks", 5);
		var differentSeed = work.copy();
		differentSeed.putLong("seed", 94L);
		for (var changed : new CompoundTag[]{differentProgress, differentSeed}) {
			assertThrows(IllegalStateException.class, () -> account.deferCentrifuge(ticket, 0, changed));
			assertThrows(IllegalStateException.class, () -> account.commitCentrifuge(ticket, 0, changed));
			assertEquals(saved, store.save());
			assertEquals("FROZEN", account.productionPhase(ticket));
			assertEquals(work, account.requestData(ticket).getCompound("next_work"));
		}
		var completion = account.deferCentrifuge(ticket, 0, work.copy());
		completion.complete();
		assertEquals(work, account.centrifugeWork(0));
		assertEquals(7L, account.available().get(HYDROGEN));
	}

	@Test void capturedEmptyPlanAndLegacyFrozenRequestsRemainDistinctAcrossReload() {
		var store = new ResourceAccounts(() -> {});
		var account = account(store);
		var ticket = frozenLane(account, 0, 10, 7);
		var request = account.requestData(ticket);
		assertTrue(request.getBoolean("next_work_captured"));
		assertEquals(0, request.getCompound("context").getInt("next_ticks"));
		assertFalse(request.contains("next_work"));
		var saved = store.save();
		var restoredStore = ResourceAccounts.load(saved, () -> {});
		assertFalse(restoredStore.hasInvalidData());
		var restored = restoredStore.find(ticket.source().sourceId());
		assertTrue(restored.requestData(ticket).getBoolean("next_work_captured"));
		assertNull(restored.centrifugeWork(0));
		restored.resolveProduction(OWNER, ticket, true);
		assertNull(restored.centrifugeWork(0));
		assertEquals(7L, restored.available().get(HYDROGEN));

		var legacyStore = new ResourceAccounts(() -> {});
		var legacy = account(legacyStore);
		var context = new CompoundTag();
		context.putString("host", "resource_centrifuging");
		context.putInt("lane", 0);
		var oldWork = new CompoundTag();
		oldWork.putInt("paid_ticks", 12);
		oldWork.putLong("seed", 17L);
		legacy.centrifugeWork.put(0, oldWork.copy());
		var legacyTicket = legacy.reserve(legacy.reference(), 7, context, Map.of(HYDROGEN, 10L));
		legacy.start(legacyTicket);
		legacy.freeze(legacyTicket, Map.of(HYDROGEN, 7L));
		assertFalse(legacy.requestData(legacyTicket).contains("next_work_captured"));
		assertFalse(legacy.requestData(legacyTicket).contains("next_work"));
		var legacyReloaded = ResourceAccounts.load(legacyStore.save(), () -> {});
		assertFalse(legacyReloaded.hasInvalidData());
		var loadedLegacy = legacyReloaded.find(legacyTicket.source().sourceId());
		assertEquals(oldWork, loadedLegacy.centrifugeWork(0));
		assertFalse(loadedLegacy.requestData(legacyTicket).contains("next_work_captured"));
		loadedLegacy.resolveProduction(OWNER, legacyTicket, true);
		assertNull(loadedLegacy.centrifugeWork(0));
		assertEquals(7L, loadedLegacy.available().get(HYDROGEN));
	}

	@Test void malformedFrozenPlanPreservesOriginalStoreWithoutRestoringWork() {
		var store = new ResourceAccounts(() -> {});
		var account = account(store);
		var work = new CompoundTag();
		work.putInt("paid_ticks", 4);
		work.putLong("seed", 93L);
		frozenLane(account, 0, 100, 7, work);
		for (int invalid = 0; invalid < 5; invalid++) {
			var raw = store.save();
			var production = raw.getList("accounts", net.minecraft.nbt.Tag.TAG_COMPOUND)
					.getCompound(0).getCompound("production");
			if (invalid == 0) {
				production.remove("next_work");
			} else if (invalid == 1) {
				production.putBoolean("next_work_captured", false);
			} else if (invalid == 2) {
				production.putInt("next_work_captured", 1);
			} else if (invalid == 3) {
				production.getCompound("next_work").putInt("paid_ticks", 5);
			} else {
				production.getCompound("context").remove("next_ticks");
			}
			var rejected = ResourceAccounts.load(raw, () -> {});
			assertTrue(rejected.hasInvalidData());
			assertEquals(raw, rejected.save());
			assertThrows(IllegalStateException.class, () -> account(rejected));
		}
	}

	@Test void waitingOutputWithoutFrozenProgressFieldsPreservesTheOriginalStore() {
		var store = new ResourceAccounts(() -> {});
		var account = account(store);
		var work = new CompoundTag();
		work.putInt("paid_ticks", 4);
		work.putLong("seed", 93L);
		var ticket = frozenLane(account, 0, 100, 7, work);
		account.deferCentrifuge(ticket, 0, work);
		for (int missing = 0; missing < 3; missing++) {
			var raw = store.save();
			var pending = raw.getList("accounts", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0)
					.getCompound("suspended_lanes").getCompound("0").getCompound("production");
			if (missing != 1) pending.remove("next_work_captured");
			if (missing != 0) pending.getCompound("context").remove("next_ticks");
			var restoredStore = ResourceAccounts.load(raw, () -> {});
			assertTrue(restoredStore.hasInvalidData());
			assertEquals(raw, restoredStore.save());
			assertNull(restoredStore.find(ticket.source().sourceId()));
			assertThrows(IllegalStateException.class, () -> account(restoredStore));
		}
	}

	@Test void frozenPlanAndOriginalWorkShareMachineBudgetBeforeDebitAndOnReload() {
		var work = new CompoundTag();
		work.putInt("paid_ticks", 3);
		work.putByteArray("payload", new byte[60 * 1024]);
		int fittingPlans = (int) (ResourceAccount.MAX_WORK_BYTES / work.sizeInBytes());
		var store = new ResourceAccounts(() -> {});
		var account = account(store);
		for (int lane = 0; lane < fittingPlans - 1; lane++) {
			account.centrifugeWork.put(lane, work.copy());
		}
		var ticket = frozenLane(account, 0, 10, 7, work);
		var saved = store.save();
		var restoredStore = ResourceAccounts.load(saved, () -> {});
		assertFalse(restoredStore.hasInvalidData());
		var restored = restoredStore.find(ticket.source().sourceId());
		assertEquals(work, restored.centrifugeWork(0));
		assertEquals(work, restored.requestData(ticket).getCompound("next_work"));
		var malformed = saved.copy();
		malformed.getList("accounts", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0)
				.getCompound("centrifuge_work").put(Integer.toString(fittingPlans - 1), work.copy());
		var rejected = ResourceAccounts.load(malformed, () -> {});
		assertTrue(rejected.hasInvalidData());
		assertEquals(malformed, rejected.save());

		var fullStore = new ResourceAccounts(() -> {});
		var full = account(fullStore);
		for (int lane = 0; lane < fittingPlans; lane++) {
			full.centrifugeWork.put(lane, work.copy());
		}
		var context = new CompoundTag();
		context.putString("host", "resource_centrifuging");
		context.putInt("lane", 0);
		context.putInt("next_ticks", 3);
		var fullTicket = full.reserve(full.reference(), 7, context, Map.of(HYDROGEN, 10L));
		full.start(fullTicket);
		var before = fullStore.save();
		assertThrows(IllegalArgumentException.class,
				() -> full.freezeCentrifuge(fullTicket, Map.of(HYDROGEN, 7L), work));
		assertEquals(before, fullStore.save());
		assertEquals("STARTED", full.productionPhase(fullTicket));
		assertFalse(full.requestData(fullTicket).contains("next_work_captured"));
	}

	@Test void deferredHandleRejectsConcurrentRequestsRemovalAndOldLifecycle() {
		var callback = new AtomicReference<Runnable>();
		var store = new ResourceAccounts(() -> {
			var action = callback.get();
			if (action != null) action.run();
		});
		var account = account(store);
		var ticket = frozenLane(account, 0, 10, 8);
		var completion = account.deferCentrifuge(ticket, 0, null);
		var other = account.reserve(account.reference(), 7, new CompoundTag(), Map.of(HYDROGEN, 1L));
		assertThrows(IllegalStateException.class, completion::complete);
		account.cancel(other);
		callback.set(() -> {
			assertThrows(IllegalStateException.class, completion::complete);
			assertThrows(IllegalStateException.class, () -> account.resolveProduction(OWNER, ticket, true));
		});
		completion.complete();
		callback.set(null);
		assertEquals(8L, account.available().get(HYDROGEN));

		var removed = frozenLane(account, 1, 10, 5);
		var removedCompletion = account.deferCentrifuge(removed, 1, null);
		account.retire(account.reference(), BINDING);
		assertThrows(IllegalStateException.class, removedCompletion::complete);
		assertThrows(IllegalStateException.class, () -> removedCompletion.uncertain("stale callback"));
		var currentTicket = new ResourceAccount.Ticket(account.reference(), removed.sequence());
		account.resolveProduction(OWNER, currentTicket, true);
		assertEquals(13L, account.available().get(HYDROGEN));

		var activeStore = new ResourceAccounts(() -> {});
		var active = account(activeStore);
		var activeTicket = frozenLane(active, 0, 10, 6);
		var activeCompletion = active.deferCentrifuge(activeTicket, 0, null);
		var loaded = ResourceAccounts.load(activeStore.save(), () -> {});
		activeStore.close();
		assertThrows(IllegalStateException.class, activeCompletion::complete);
		var loadedAccount = loaded.find(activeTicket.source().sourceId());
		assertTrue(loadedAccount.available().isEmpty());
		loadedAccount.resolveProduction(OWNER, activeTicket, true);
		assertEquals(6L, loadedAccount.available().get(HYDROGEN));
	}

	@Test void deferredNextWorkSharesMachineBudgetAndRejectsCorruptRecovery() {
		var store = new ResourceAccounts(() -> {});
		var account = account(store);
		var work = new CompoundTag();
		work.putByteArray("payload", new byte[60 * 1024]);
		work.putInt("paid_ticks", 3);
		int fittingLanes = (int) (ResourceAccount.MAX_WORK_BYTES / work.sizeInBytes());
		var completions = new java.util.ArrayList<ResourceAccount.DeferredCentrifuge>();
		for (int lane = 0; lane < fittingLanes; lane++) {
			account.validateCentrifugeWork(lane, work);
			var ticket = frozenLane(account, lane, 2, 1, work);
			completions.add(account.deferCentrifuge(ticket, lane, work));
		}
		var before = store.save();
		assertThrows(IllegalArgumentException.class, () -> account.validateCentrifugeWork(fittingLanes, work));
		assertEquals(before, store.save());
		long reserved = store.reservedStorage();
		var file = new CompoundTag();
		file.put("data", before);
		net.minecraft.nbt.NbtUtils.addCurrentDataVersion(file);
		assertTrue(file.sizeInBytes() <= reserved);
		var restored = ResourceAccounts.load(before, () -> {});
		assertFalse(restored.hasInvalidData());
		assertTrue(restored.reservedStorage() <= reserved);

		var malformed = before.copy();
		var row = malformed.getList("accounts", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0);
		var lanes = row.getCompound("suspended_lanes");
		var duplicate = lanes.getCompound("0").copy();
		duplicate.getCompound("production").putLong("sequence", row.getLong("next_sequence"));
		duplicate.getCompound("production").getCompound("context").putInt("lane", fittingLanes);
		row.putLong("next_sequence", row.getLong("next_sequence") + 1);
		lanes.put(Integer.toString(fittingLanes), duplicate);
		var rejected = ResourceAccounts.load(malformed, () -> {});
		assertTrue(rejected.hasInvalidData());
		assertEquals(malformed, rejected.save());

		for (int index = completions.size() - 1; index >= 0; index--) completions.get(index).complete();
		assertEquals(fittingLanes, account.centrifugeWork.size());
		assertEquals((long) fittingLanes, account.available().get(HYDROGEN));
		var finished = ResourceAccounts.load(store.save(), () -> {});
		assertFalse(finished.hasInvalidData());
		assertEquals(account.centrifugeWork, finished.find(account.reference().sourceId()).centrifugeWork);
	}

	@Test void deferredWorkCannotAlsoAppearAsRunnableOrOnLegacyProduction() {
		var store = new ResourceAccounts(() -> {});
		var account = account(store);
		var work = new CompoundTag();
		work.putInt("paid_ticks", 3);
		var context = new CompoundTag();
		context.putString("host", "resource_centrifuging");
		context.putInt("lane", 0);
		context.putInt("next_ticks", 3);
		var ticket = account.reserve(account.reference(), 7, context, Map.of(HYDROGEN, 10L));
		account.start(ticket);
		account.freezeCentrifuge(ticket, Map.of(HYDROGEN, 4L), work);
		account.deferCentrifuge(ticket, 0, work);
		for (int invalid = 0; invalid < 7; invalid++) {
			var raw = store.save();
			var row = raw.getList("accounts", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0);
			var pending = row.getCompound("suspended_lanes").getCompound("0").getCompound("production");
			if (invalid == 0) {
				var duplicate = new CompoundTag();
				duplicate.put("0", work.copy());
				row.put("centrifuge_work", duplicate);
			} else if (invalid == 1) {
				pending.putString("phase", "FROZEN");
				pending.remove("next_work_captured");
			} else if (invalid == 2) {
				pending.putString("next_work", "unreadable");
			} else if (invalid == 3) {
				row.remove("suspended_lanes");
				row.put("production", pending);
			} else if (invalid == 4) {
				pending.remove("next_work");
			} else if (invalid == 5) {
				pending.getCompound("next_work").putInt("paid_ticks", 4);
			} else {
				pending.getCompound("context").putString("next_ticks", "unknown");
			}
			var rejected = ResourceAccounts.load(raw, () -> {});
			assertTrue(rejected.hasInvalidData());
			assertEquals(raw, rejected.save());
		}
	}

	private static ResourceAccount.Ticket frozenLane(ResourceAccount account, int lane, long maximum, long result) {
		return frozenLane(account, lane, maximum, result, null);
	}

	private static ResourceAccount.Ticket frozenLane(ResourceAccount account, int lane, long maximum, long result,
			CompoundTag nextWork) {
		var context = new CompoundTag();
		context.putString("host", "resource_centrifuging");
		context.putInt("lane", lane);
		context.putInt("next_ticks", nextWork == null ? 0 : nextWork.getInt("paid_ticks"));
		var ticket = account.reserve(account.reference(), 7, context, Map.of(HYDROGEN, maximum));
		account.start(ticket);
		account.freezeCentrifuge(ticket, result == 0 ? Map.of() : Map.of(HYDROGEN, result), nextWork);
		return ticket;
	}

	@Test void foreignThreadAndClosedLifecycleCannotMutateAccounts() throws InterruptedException {
		var source = new ResourceAccounts(() -> {});
		var account = account(source);
		var failure = new AtomicReference<Throwable>();
		var thread = new Thread(() -> {
			try { produce(account, 3); } catch (Throwable error) { failure.set(error); }
		});
		thread.start();
		thread.join();
		assertInstanceOf(IllegalStateException.class, failure.get());
		assertTrue(account.available().isEmpty());
		source.close();
		assertThrows(IllegalStateException.class, () -> produce(account, 3));
	}
}
