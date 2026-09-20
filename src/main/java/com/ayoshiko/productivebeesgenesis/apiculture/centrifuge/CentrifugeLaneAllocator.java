package com.ayoshiko.productivebeesgenesis.apiculture.centrifuge;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.OwnedMachineRecord;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkCheckpoint;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductLedger;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductPolicyRegistry;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 有预算的逐 lane 轮转；候选来自已验证的能力快照，不复制或平均异构机器能力。 */
public final class CentrifugeLaneAllocator {
	public record Candidate(UUID member, int lane, long stateRevision, CentrifugeRecipePlan plan) {
		public Candidate {
			Objects.requireNonNull(member); Objects.requireNonNull(plan);
			if (lane < 0 || stateRevision < 0) throw new IllegalArgumentException("Invalid centrifuge candidate");
		}
	}
	public static final class Selection {
		private final NetworkCheckpoint expected;
		private final Candidate candidate;
		private final int operations;
		private Selection(NetworkCheckpoint expected, Candidate candidate, int operations) {
			this.expected = expected; this.candidate = candidate; this.operations = operations;
		}
		public Candidate candidate() { return candidate; }
		public int operations() { return operations; }
		public boolean matches(NetworkCheckpoint checkpoint) { return expected == checkpoint; }
	}
	public record Scan(Selection selection, int nextCursor, int inspected) { }

	/** 预览不签发作业 UUID、不扣料／电；调用者保存游标，候选替换时重置游标。 */
	public static Scan select(NetworkCheckpoint checkpoint, ProductPolicyRegistry policy, List<Candidate> candidates,
			int cursor, int budget, int operationLimit) {
		if (cursor < 0 || budget < 0 || operationLimit < 0) throw new IllegalArgumentException("Negative allocation budget");
		int size = candidates.size();
		if (size == 0) return new Scan(null, 0, 0);
		int next = cursor % size;
		if (budget == 0 || operationLimit == 0 || policy.snapshot().revision() != checkpoint.policyRevision()) return new Scan(null, next, 0);
		ProductLedger ledger = null;
		int limit = Math.min(budget, size);
		for (int inspected = 1; inspected <= limit; inspected++) {
			var candidate = candidates.get(next); next = next == size - 1 ? 0 : next + 1;
			var owner = checkpoint.ownedMachines().get(candidate.member());
			if (owner == null || owner.phase() != OwnedMachineRecord.Phase.OWNED || owner.centrifuge() == null) continue;
			var state = owner.centrifuge(); var plan = candidate.plan();
			if (state.revision() != candidate.stateRevision() || candidate.lane() >= state.laneCount()
					|| state.jobs().containsKey(candidate.lane()) || plan.recipeRevision() != checkpoint.policyRevision()) continue;
			if (!policy.evaluate(plan.input()).allowed() || plan.outputs().stream().anyMatch(output -> !policy.evaluate(output.key()).allowed())) continue;
			if (ledger == null) ledger = ProductLedger.restore(policy, Math.addExact(checkpoint.ledger().transactions().size(), 1), checkpoint.ledger());
			int operations = (int) Math.min(Math.min(operationLimit, plan.maxParallel()), ledger.available(plan.input()).longSaturated());
			operations = CentrifugeEnergyPricing.affordableOperations(plan.unitEnergyPerTick(), operations, state.networkPowered() ? checkpoint.energy().stored() : state.energy());
			if (operations > 0) return new Scan(new Selection(checkpoint, candidate, operations), next, inspected);
		}
		return new Scan(null, next, limit);
	}
	public static NetworkCheckpoint commit(NetworkCheckpoint checkpoint, ProductPolicyRegistry policy, Selection selection, long seed) {
		if (selection == null || !selection.matches(checkpoint)) return checkpoint;
		var candidate = selection.candidate(); var state = checkpoint.ownedMachines().get(candidate.member()).centrifuge();
		var transaction = CentrifugeWorkTransaction.assign(state, checkpoint.ledger(), policy, candidate.lane(), candidate.plan(), selection.operations(), seed, state.networkPowered() ? checkpoint.energy().stored() : state.energy());
		return transaction == null ? checkpoint : checkpoint.applyCentrifuge(candidate.member(), transaction);
	}
	private CentrifugeLaneAllocator() { }
}
