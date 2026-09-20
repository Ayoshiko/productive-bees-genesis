package com.ayoshiko.productivebeesgenesis.apiculture.centrifuge;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CentrifugeWorkTransactionTest {
	private static final ProductKey COMB = key(ProductKey.Kind.ITEM, "comb"), ITEM = key(ProductKey.Kind.ITEM, "item"), FLUID = key(ProductKey.Kind.FLUID, "fluid");
	private static ProductKey key(ProductKey.Kind kind, String name) { return new ProductKey(kind, ResourceLocation.parse("test:" + name), new CompoundTag()); }
	private static ProductPolicyRegistry policy(long revision) {
		return new ProductPolicyRegistry(new ProductPolicySnapshot(revision, List.of(
				new AllowedProductDescriptor(ITEM, "test", "recipe"), new AllowedProductDescriptor(FLUID, "test", "recipe")), List.of()));
	}
	private static CentrifugeRecipePlan plan(int parallel, int ticks, int multiplier, long cost) {
		return new CentrifugeRecipePlan("test:recipe", 1, 1, COMB, ticks, parallel, cost, multiplier, 0,
				List.of(new CentrifugeRecipePlan.Output(ITEM, 2, 2, 1), new CentrifugeRecipePlan.Output(FLUID, 25, 25, 1)));
	}
	private static CentrifugeWorkState state(long energy) { return new CentrifugeWorkState(UUID.randomUUID(), 0, 2, energy, energy, Map.of()); }
	private static LedgerCheckpoint ledger(ProductAmount inputs) { return new LedgerCheckpoint(0, Map.of(COMB, inputs), List.of()); }
	private static ProductAmount amount(long count) { return ProductAmount.of(count); }

	@Test
	void candidatesArePureAndCompetingLanesCannotReuseHeldInputs() {
		var state = state(1000); var ledger = ledger(amount(7)); var plan = plan(4, 10, 1, 1);
		var first = CentrifugeWorkTransaction.assign(state, ledger, policy(1), 0, plan, 4, 15);
		assertNotNull(first); assertTrue(first.matches(state, ledger));
		assertTrue(state.drained()); assertEquals(amount(7), ledger.balances().get(COMB));
		assertEquals(amount(3), first.ledger().balances().get(COMB));
		assertEquals(4, first.state().jobs().get(0).operations());
		assertNull(CentrifugeWorkTransaction.assign(first.state(), first.ledger(), policy(1), 0, plan, 4, 15));
		var second = CentrifugeWorkTransaction.assign(first.state(), first.ledger(), policy(1), 1, plan, 4, 16);
		assertEquals(3, second.state().jobs().get(1).operations()); assertFalse(second.ledger().balances().containsKey(COMB));
		assertFalse(first.matches(second.state(), second.ledger()));
	}

	@Test
	void unstartedCancellationRestoresExactlyOnceButPaidProgressCannotBeCancelled() {
		var start = CentrifugeWorkTransaction.assign(state(1000), ledger(amount(7)), policy(1), 0, plan(4, 10, 1, 1), 4, 15);
		var cancelled = CentrifugeWorkTransaction.cancel(start.state(), start.ledger(), 0, 2);
		assertEquals(amount(7), cancelled.ledger().balances().get(COMB)); assertTrue(cancelled.state().drained());
		assertNull(CentrifugeWorkTransaction.cancel(cancelled.state(), cancelled.ledger(), 0, 2));
		var progress = CentrifugeWorkTransaction.advance(start.state(), start.ledger(), 0, 1, true, true);
		assertEquals(1, progress.state().jobs().get(0).progress());
		assertNull(CentrifugeWorkTransaction.cancel(progress.state(), progress.ledger(), 0, 2));
	}

	@Test
	void fixedParallelDoesNotGrowWhenInputAndUpgradesChangeMidCycle() {
		var start = CentrifugeWorkTransaction.assign(state(40), ledger(amount(100)), policy(1), 0, plan(4, 10, 3, 1), 2, 15);
		var progress = CentrifugeWorkTransaction.advance(start.state(), start.ledger(), 0, 8, true, true);
		assertEquals(24, progress.state().energy());
		assertNull(CentrifugeWorkTransaction.assign(progress.state(), progress.ledger(), policy(1), 0, plan(32, 1, 32, 1), 32, 16));
		assertNull(CentrifugeWorkTransaction.advance(progress.state(), progress.ledger(), 0, 20, false, true));
		assertNull(CentrifugeWorkTransaction.advance(progress.state(), progress.ledger(), 0, 20, true, false));
		var done = CentrifugeWorkTransaction.advance(progress.state(), progress.ledger(), 0, 256, true, true);
		assertEquals(2, done.executedTicks()); assertEquals(20, done.state().energy());
		var frozen = CentrifugeWorkTransaction.freeze(done.state(), done.ledger(), 0);
		var settled = CentrifugeWorkTransaction.settle(frozen.state(), frozen.ledger(), 0, 2);
		assertEquals(amount(12), settled.ledger().balances().get(ITEM));
		assertEquals(amount(150), settled.ledger().balances().get(FLUID));
		assertEquals(amount(98), settled.ledger().balances().get(COMB));
		assertNull(CentrifugeWorkTransaction.settle(settled.state(), settled.ledger(), 0, 2));
		assertFalse(frozen.matches(settled.state(), settled.ledger()));
	}

	@Test
	void energyShortagePausesTheWholePinnedBatchAndReportsOnlyExecutedTicks() {
		var start = CentrifugeWorkTransaction.assign(state(11), ledger(amount(4)), policy(1), 0, plan(4, 10, 1, 1), 4, 15);
		var paid = CentrifugeWorkTransaction.advance(start.state(), start.ledger(), 0, 256, true, true);
		assertEquals(2, paid.executedTicks()); assertEquals(3, paid.state().energy());
		assertEquals(4, paid.state().jobs().get(0).operations()); assertEquals(2, paid.state().jobs().get(0).progress());
		assertNull(CentrifugeWorkTransaction.advance(paid.state(), paid.ledger(), 0, 256, true, true));
		assertNull(CentrifugeWorkTransaction.freeze(paid.state(), paid.ledger(), 0));
		assertNull(CentrifugeWorkTransaction.settle(paid.state(), paid.ledger(), 0, 1));
	}

	@Test
	void frozenRandomResultsAndZeroOutputRemainValidAfterPolicyReload() {
		var zero = new CentrifugeRecipePlan("test:zero", 1, 1, COMB, 1, 8, 1, 1, 0,
				List.of(new CentrifugeRecipePlan.Output(ITEM, 0, 0, 1)));
		var start = CentrifugeWorkTransaction.assign(state(100), ledger(amount(8)), policy(1), 0, zero, 8, 15);
		var paid = CentrifugeWorkTransaction.advance(start.state(), start.ledger(), 0, 1, true, true);
		var frozen = CentrifugeWorkTransaction.freeze(paid.state(), paid.ledger(), 0);
		assertTrue(frozen.state().jobs().get(0).sampled()); assertTrue(frozen.state().jobs().get(0).frozen().isEmpty());
		var settled = CentrifugeWorkTransaction.settle(frozen.state(), frozen.ledger(), 0, 2);
		assertTrue(settled.state().drained()); assertTrue(settled.ledger().balances().isEmpty());
		var randomPlan = new CentrifugeRecipePlan("test:random", 1, 1, COMB, 1, 27, 1, 4, 0.1F,
				List.of(new CentrifugeRecipePlan.Output(ITEM, 1, 8, 0.33F)));
		var job = new CentrifugeJob(UUID.randomUUID(), randomPlan, 27, 1, 88, null).freeze();
		assertSame(job, job.freeze());
		assertEquals(job.frozen(), new CentrifugeJob(job.id(), randomPlan, 27, 1, 88, null).freeze().frozen());
	}

	@Test
	void exactInputAndOutputAmountsAreNeverLimitedByLongProjection() {
		var huge = ProductAmount.of(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO));
		var plan = new CentrifugeRecipePlan("test:huge", 1, 1, COMB, 1, Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 0,
				List.of(new CentrifugeRecipePlan.Output(ITEM, Integer.MAX_VALUE, Integer.MAX_VALUE, 1)));
		var start = CentrifugeWorkTransaction.assign(state(0), ledger(huge), policy(1), 0, plan, Integer.MAX_VALUE, 15);
		var paid = CentrifugeWorkTransaction.advance(start.state(), start.ledger(), 0, 1, true, true);
		var frozen = CentrifugeWorkTransaction.freeze(paid.state(), paid.ledger(), 0);
		var settled = CentrifugeWorkTransaction.settle(frozen.state(), frozen.ledger(), 0, 1);
		assertEquals(BigInteger.valueOf(Integer.MAX_VALUE).pow(3), settled.ledger().balances().get(ITEM).exact());
		assertEquals(huge.subtract(amount(Integer.MAX_VALUE)), settled.ledger().balances().get(COMB));
	}

	@Test
	void admissionFailureLeavesInputsAndEnergyUntouched() {
		var state = state(100); var ledger = ledger(amount(10));
		assertNull(CentrifugeWorkTransaction.assign(state, ledger, policy(2), 0, plan(4, 10, 1, 1), 4, 15));
		var missing = new ProductPolicyRegistry(new ProductPolicySnapshot(1, List.of(new AllowedProductDescriptor(ITEM, "test", "recipe")), List.of()));
		assertNull(CentrifugeWorkTransaction.assign(state, ledger, missing, 0, plan(4, 10, 1, 1), 4, 15));
		assertEquals(amount(10), ledger.balances().get(COMB)); assertEquals(100, state.energy());
		assertThrows(ArithmeticException.class, () -> plan(2, 1, 1, Long.MAX_VALUE).energyPerTick(2));
	}

	@Test
	void workCannotAdvanceWithoutTheExactFeeOrConsumeOtherReservations() {
		var source = state(1000); var job = new CentrifugeJob(UUID.randomUUID(), plan(4, 10, 1, 2), 4, 0, 77, null);
		var started = source.replace(0, job, 1000); source.validateSuccessor(started);
		var progress = job.advance(3, 1000).job();
		assertThrows(IllegalArgumentException.class, () -> started.validateSuccessor(started.replace(0, progress, 1000)));
		assertThrows(IllegalArgumentException.class, () -> started.validateSuccessor(started.replace(0, progress, 975)));
		started.validateSuccessor(started.replace(0, progress, 976));
		var pending = new LedgerCheckpoint.Pending(UUID.randomUUID(), 1, LedgerTransaction.State.RESERVED, Map.of(COMB, amount(6)), Map.of(ITEM, amount(1)));
		var ledger = new LedgerCheckpoint(1, Map.of(COMB, amount(7)), List.of(pending));
		var assigned = CentrifugeWorkTransaction.assign(source, ledger, policy(1), 0, plan(4, 10, 1, 2), 4, 33);
		assertEquals(1, assigned.state().jobs().get(0).operations());
		assertEquals(amount(6), assigned.ledger().balances().get(COMB));
		assertEquals(List.of(pending), assigned.ledger().transactions());
	}

	@Test
	void heterogeneousLanesKeepSeparateCyclesMultipliersAndPrices() {
		var fast = CentrifugeWorkTransaction.assign(state(1000), ledger(amount(10)), policy(1), 0, plan(2, 4, 3, 5), 2, 1);
		var slow = CentrifugeWorkTransaction.assign(fast.state(), fast.ledger(), policy(1), 1, plan(8, 10, 1, 1), 8, 2);
		var fastPaid = CentrifugeWorkTransaction.advance(slow.state(), slow.ledger(), 0, 4, true, true);
		var slowPartial = CentrifugeWorkTransaction.advance(fastPaid.state(), fastPaid.ledger(), 1, 4, true, true);
		assertTrue(slowPartial.state().jobs().get(0).paid()); assertFalse(slowPartial.state().jobs().get(1).paid());
		assertEquals(928, slowPartial.state().energy());
		var fastFrozen = CentrifugeWorkTransaction.freeze(slowPartial.state(), slowPartial.ledger(), 0);
		var fastSettled = CentrifugeWorkTransaction.settle(fastFrozen.state(), fastFrozen.ledger(), 0, 1);
		assertEquals(amount(12), fastSettled.ledger().balances().get(ITEM));
		assertEquals(4, fastSettled.state().jobs().get(1).progress());
		var slowPaid = CentrifugeWorkTransaction.advance(fastSettled.state(), fastSettled.ledger(), 1, 6, true, true);
		var slowFrozen = CentrifugeWorkTransaction.freeze(slowPaid.state(), slowPaid.ledger(), 1);
		var all = CentrifugeWorkTransaction.settle(slowFrozen.state(), slowFrozen.ledger(), 1, 1);
		assertTrue(all.state().drained()); assertEquals(880, all.state().energy());
		assertEquals(amount(28), all.ledger().balances().get(ITEM)); assertEquals(amount(350), all.ledger().balances().get(FLUID));
	}
}
