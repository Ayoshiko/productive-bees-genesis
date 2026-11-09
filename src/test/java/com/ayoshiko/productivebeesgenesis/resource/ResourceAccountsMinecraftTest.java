package com.ayoshiko.productivebeesgenesis.resource;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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

	@Test void rawIdentityIsImmutableAndPreservesMissingModIdentity() {
		var payload = new CompoundTag();
		payload.putString("variant", "high_voltage");
		var key = new ResourceKey(ResourceLocation.parse("uninstalled:lightning"), 3, payload);
		payload.putString("variant", "changed");
		key.payload().putString("variant", "changed_again");
		assertEquals("high_voltage", key.payload().getString("variant"));
		assertEquals(key, ResourceKey.load(key.save()));
		assertNotEquals(key, new ResourceKey(key.kind(), 4, key.payload()));
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

	@Test void zeroResultAndSafePreflightCancellationCreateNoAssets() {
		var store = new ResourceAccounts(() -> {});
		var account = account(store);
		var first = account.reserve(account.reference(), 0, new CompoundTag(), Map.of(HYDROGEN, 1L));
		account.cancel(first);
		var second = account.reserve(account.reference(), 0, new CompoundTag(), Map.of(HYDROGEN, 1L));
		assertTrue(second.sequence() > first.sequence());
		account.start(second);
		account.freeze(second, Map.of());
		account.commit(second);
		assertTrue(account.available().isEmpty());
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
		for (var phase : ResourceAccount.Phase.values()) {
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

	@Test void detachedAccountAndUnknownResourceIdentitySurviveRoundTrip() {
		var source = new ResourceAccounts(() -> {});
		var account = account(source);
		var payload = new CompoundTag();
		payload.putString("variant", "external");
		var unknown = new ResourceKey(ResourceLocation.parse("absent:resource"), 9, payload);
		var ticket = account.reserve(account.reference(), 4, new CompoundTag(), Map.of(unknown, 5L));
		account.start(ticket);
		account.freeze(ticket, Map.of(unknown, 5L));
		account.commit(ticket);
		var portable = account.detach(OWNER, account.reference());
		var restored = ResourceAccounts.load(source.save(), () -> {}).find(portable.sourceId());
		assertEquals(portable, restored.reference());
		assertNull(restored.binding());
		assertEquals(5L, restored.available().get(unknown));
	}

	@Test void bindingUniquenessIgnoresHostKindAndRecoveryViewsRemainReadOnly() {
		var store = new ResourceAccounts(() -> {});
		var account = account(store);
		var differentKind = new ResourceAccount.Binding(BINDING.dimension(), BINDING.packedPosition(),
				ResourceLocation.parse("productivebeesgenesis:mek_apiary"));
		assertThrows(IllegalStateException.class, () -> store.create(OWNER, differentKind));
		produce(account, 50);
		var ticket = account.beginTransfer(account.reference(), HYDROGEN, 30);
		account.suspendTransfer(ticket, "Awaiting external confirmation");
		var restored = ResourceAccounts.load(store.save(), () -> {}).find(ticket.source().sourceId());
		assertEquals(ticket, restored.transferTicket());
		assertEquals(OWNER, restored.owner());
		assertEquals(Map.of(HYDROGEN, 30L), restored.uncertainTransfer());
		assertThrows(UnsupportedOperationException.class, () -> restored.uncertainTransfer().clear());
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

	@Test void invalidSuspensionReasonDoesNotChangeRevisionOrFrozenProduction() {
		var store = new ResourceAccounts(() -> {});
		var account = account(store);
		var ticket = account.reserve(account.reference(), 0, new CompoundTag(), Map.of(HYDROGEN, 2L));
		account.start(ticket);
		account.freeze(ticket, Map.of(HYDROGEN, 2L));
		var before = store.save();
		assertThrows(IllegalArgumentException.class, () -> account.suspendProduction(ticket, ""));
		assertEquals(before, store.save());
		assertEquals(ticket, account.productionTicket());
		assertEquals(Map.of(HYDROGEN, 2L), account.frozenProduction());
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

	@Test void staleOrMismatchedRemovalCannotRetireAnotherIncarnation() {
		var store = new ResourceAccounts(() -> {});
		var account = account(store);
		produce(account, 90);
		var previous = account.reference();
		var wrong = new ResourceAccount.Binding(BINDING.dimension(), 456, BINDING.hostKind());
		var saved = store.save();
		assertThrows(IllegalStateException.class, () -> account.retire(previous, wrong));
		assertEquals(saved, store.save());
		var detached = account.retire(previous, BINDING);
		account.claim(OWNER, detached, BINDING);
		var after = store.save();
		assertThrows(IllegalStateException.class, () -> account.retire(previous, BINDING));
		assertEquals(after, store.save());
		assertThrows(UnsupportedOperationException.class, () -> store.snapshot().clear());
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

	@Test void legacyUnfinishedLaneMigratesWithoutSuspendingHealthyAccount() {
		var store = new ResourceAccounts(() -> {});
		var account = account(store);
		var context = new CompoundTag();
		context.putString("host", "resource_centrifuging");
		context.putInt("lane", 2);
		var ticket = account.reserve(account.reference(), 7, context, Map.of(HYDROGEN, 100L));
		account.start(ticket);
		account.freeze(ticket, Map.of(HYDROGEN, 70L));
		var loaded = ResourceAccounts.load(store.save(), () -> {});
		assertFalse(loaded.hasInvalidData());
		var restored = loaded.find(ticket.source().sourceId());
		assertTrue(restored.laneSuspended(2));
		assertTrue(restored.canExtract(restored.reference()));
		assertEquals(ticket, restored.productionTicket());
		assertEquals(context, restored.productionContext(ticket));
		assertThrows(IllegalStateException.class, () -> restored.commit(ticket));
		produce(restored, 50);
		assertEquals(50L, restored.available().get(HYDROGEN));
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

	@Test void invalidIsolatedLanePreservesOriginalData() {
		var store = new ResourceAccounts(() -> {});
		var account = account(store);
		var context = new CompoundTag();
		context.putString("host", "resource_centrifuging");
		context.putInt("lane", 1);
		var ticket = account.reserve(account.reference(), 7, context, Map.of(HYDROGEN, 100L));
		account.start(ticket);
		account.freeze(ticket, Map.of(HYDROGEN, 70L));
		account.suspendProduction(ticket, "uncertain debit");
		for (int invalid : new int[]{-1, 256}) {
			var raw = store.save();
			var lanes = raw.getList("accounts", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0).getCompound("suspended_lanes");
			var pending = lanes.getCompound("1");
			lanes.remove("1");
			pending.getCompound("production").getCompound("context").putInt("lane", invalid);
			lanes.put(Integer.toString(invalid), pending);
			var restored = ResourceAccounts.load(raw, () -> {});
			assertTrue(restored.hasInvalidData());
			assertEquals(raw, restored.save());
		}
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
