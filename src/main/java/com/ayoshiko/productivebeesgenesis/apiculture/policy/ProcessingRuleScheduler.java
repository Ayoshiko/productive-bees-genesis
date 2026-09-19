package com.ayoshiko.productivebeesgenesis.apiculture.policy;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.CapacityPoolIndex;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.LedgerTransaction;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductLedger;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.SnapshotRecords;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** 仅选择并预约物料，不推进时间、不占用虚拟 lane；D15／D16 负责工作段执行。 */
public final class ProcessingRuleScheduler {
	public enum Mode { FAIR, STRICT_PRIORITY }
	private record Candidate(ProductKey key, ProcessingRuleIndex.Binding binding) { }
	public static final class Selection {
		private final Object epoch;
		private final Candidate candidate;
		private final CapacityPoolIndex capacity;
		private final long ledgerRevision;
		private final long policyRevision;
		private final long operations;
		private LedgerTransaction claimed;
		private Selection(Object epoch, Candidate candidate, CapacityPoolIndex capacity, long ledgerRevision, long policyRevision, long operations) {
			this.epoch = epoch; this.candidate = candidate; this.capacity = capacity; this.ledgerRevision = ledgerRevision;
			this.policyRevision = policyRevision; this.operations = operations;
		}
		public ProcessingRule rule() { return candidate.binding().rule(); }
		public ProcessingRecipe recipe() { return candidate.binding().recipe(); }
		public ProductKey input() { return candidate.key(); }
		public long operations() { return operations; }
		public ProductAmount inputAmount() { return recipe().inputPerOperation().multiply(operations); }
	}
	private final Thread owner = Thread.currentThread();
	private final ProductLedger ledger;
	private final Mode mode;
	private ProcessingRuleIndex index;
	private Object epoch = new Object();
	private final SnapshotRecords<String, Boolean> watermarks = new SnapshotRecords<>(Comparator.naturalOrder());
	private int cursor;
	private int used;
	private boolean claiming;
	public ProcessingRuleScheduler(ProductLedger ledger, ProcessingRuleIndex index, Mode mode) {
		this.ledger = Objects.requireNonNull(ledger); this.index = Objects.requireNonNull(index); this.mode = Objects.requireNonNull(mode);
	}
	public void replace(ProcessingRuleIndex next) {
		checkThread(); index = Objects.requireNonNull(next); epoch = new Object(); watermarks.clear(); cursor = 0; used = 0;
	}
	public SchedulerCheckpoint checkpoint() {
		checkThread();
		return SchedulerCheckpoint.capture(index, mode, watermarks, cursor, used);
	}
	public void validateCheckpointLedger(ProductLedger expected) {
		checkThread();
		if (ledger != expected) throw new IllegalArgumentException("Scheduler belongs to a different ledger");
	}
	public static ProcessingRuleScheduler restore(ProductLedger ledger, ProcessingRuleIndex rebuilt, SchedulerCheckpoint checkpoint) {
		if (!rebuilt.configuredRules().equals(checkpoint.rules())) throw new IllegalArgumentException("Rebuilt rules differ from checkpoint");
		var scheduler = new ProcessingRuleScheduler(ledger, rebuilt, checkpoint.mode());
		checkpoint.watermarks().forEach(scheduler.watermarks::put);
		if (!checkpoint.cursorRule().isEmpty()) {
			for (int i = 0; i < rebuilt.rules().size(); i++) if (rebuilt.rules().get(i).id().equals(checkpoint.cursorRule())) scheduler.cursor = i;
		}
		scheduler.used = checkpoint.used();
		return scheduler;
	}
	public Selection select(long policyRevision, CapacityPoolIndex capacity,
			Map<ProductKey, ProductAmount> inFlight) {
		checkThread();
		ProductLedger.Snapshot stock = ledger.snapshot();
		inFlight = Map.copyOf(inFlight);
		Map<String, List<Candidate>> candidates = new ConcurrentHashMap<>();
		for (var key : stock.balances().keySet().stream().sorted(Comparator.comparing(ProductKey::orderingKey)).toList()) {
			for (var binding : index.candidates(key)) {
				if (capacity.forWork(binding.recipe().work()).pools().isEmpty()) continue;
				candidates.computeIfAbsent(binding.rule().id(), ignored -> new ArrayList<>()).add(new Candidate(key, binding));
			}
		}
		List<ProcessingRule> rules = index.rules();
		for (int offset = 0; offset < rules.size(); offset++) {
			int position = mode == Mode.STRICT_PRIORITY ? offset : (cursor + offset) % rules.size();
			var rule = rules.get(position);
			if (!demanding(rule, stock, inFlight)) continue;
			var eligible = candidates.getOrDefault(rule.id(), List.of());
			if (eligible.isEmpty()) continue;
			var keys = eligible.stream().map(Candidate::key).collect(Collectors.toSet());
			var plan = GroupAllowanceAllocator.allocate(stock, rule.reserves(), keys);
			for (var candidate : eligible) {
				ProductAmount limit = plan.allowances().getOrDefault(candidate.key(), ProductAmount.ZERO);
				long operations = Math.min(rule.batchLimit(), limit.divide(candidate.binding().recipe().inputPerOperation()).longSaturated());
				if (operations > 0) return new Selection(epoch, candidate, capacity, stock.revision(), policyRevision, operations);
			}
		}
		return null;
	}
	public LedgerTransaction claim(Selection selection, CapacityPoolIndex currentCapacity,
			Map<ProductKey, ProductAmount> preparedOutputs) {
		checkThread();
		claiming = true;
		try { return claimInternal(selection, currentCapacity, preparedOutputs); }
		finally { claiming = false; }
	}
	private LedgerTransaction claimInternal(Selection selection, CapacityPoolIndex currentCapacity,
			Map<ProductKey, ProductAmount> preparedOutputs) {
		if (selection == null || selection.epoch != epoch) return null;
		if (selection.claimed != null) return selection.claimed;
		if (selection.capacity != currentCapacity || preparedOutputs.isEmpty()
				|| !selection.recipe().possibleOutputs().containsAll(preparedOutputs.keySet())
				|| preparedOutputs.values().stream().allMatch(ProductAmount::isZero)) return null;
		var transaction = ledger.prepareAtRevision(Map.of(selection.input(), selection.inputAmount()), preparedOutputs,
				selection.ledgerRevision, selection.policyRevision);
		if (transaction == null) return null;
		selection.claimed = transaction;
		if (mode == Mode.FAIR) {
			int position = index.rules().indexOf(selection.rule());
			used = position == cursor ? used + 1 : 1;
			cursor = position;
			if (used >= selection.rule().weight()) { cursor = (cursor + 1) % index.rules().size(); used = 0; }
		}
		return transaction;
	}
	private boolean demanding(ProcessingRule rule, ProductLedger.Snapshot stock, Map<ProductKey, ProductAmount> inFlight) {
		if (!(rule.selector() instanceof ProcessingRule.Goal goal)) return true;
		var current = stock.balances().getOrDefault(goal.product(), ProductAmount.ZERO).add(inFlight.getOrDefault(goal.product(), ProductAmount.ZERO));
		var next = new WatermarkState(Boolean.TRUE.equals(watermarks.get(rule.id()))).update(current, goal.lower(), goal.upper());
		watermarks.put(rule.id(), next.replenishing());
		return next.replenishing();
	}
	private void checkThread() {
		if (Thread.currentThread() != owner) throw new IllegalStateException("Processing scheduler belongs to its server thread");
		if (claiming) throw new IllegalStateException("Reentrant processing claim");
	}
}
