package com.ayoshiko.productivebeesgenesis.apiculture.centrifuge;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 私有候选：只有宿主将 state 与 ledger 一同发布，投入、进度或产物才生效。 */
public final class CentrifugeWorkTransaction {
	private final CentrifugeWorkState expectedState, state;
	private final LedgerCheckpoint expectedLedger, ledger;
	private final int executedTicks;
	private final long expectedPolicy;
	private CentrifugeWorkTransaction(CentrifugeWorkState source, LedgerCheckpoint balances,
			CentrifugeWorkState next, LedgerCheckpoint result, int ticks, long expectedPolicy) {
		expectedState = source; expectedLedger = balances; state = next; ledger = result; executedTicks = ticks; this.expectedPolicy = expectedPolicy;
	}
	public boolean matches(CentrifugeWorkState current, LedgerCheckpoint balances) { return expectedState == current && expectedLedger == balances; }
	public boolean acceptsPolicy(long revision) { return expectedPolicy < 0 || expectedPolicy == revision; }
	public CentrifugeWorkState state() { return state; }
	public LedgerCheckpoint ledger() { return ledger; }
	public int executedTicks() { return executedTicks; }

	/** 输入预留采用所有权移动：余额减去的量只存在于新作业，不受普通提取或其它 lane 使用。 */
	public static CentrifugeWorkTransaction assign(CentrifugeWorkState state, LedgerCheckpoint ledger,
			ProductPolicyRegistry policy, int lane, CentrifugeRecipePlan plan, int requested, long seed) {
		if (lane < 0 || lane >= state.laneCount() || requested <= 0) throw new IllegalArgumentException("Invalid lane assignment");
		if (state.jobs().containsKey(lane) || plan.recipeRevision() != policy.snapshot().revision()) return null;
		for (var output : plan.outputs()) if (!policy.evaluate(output.key()).allowed()) return null;
		var candidate = restore(policy, ledger);
		int operations = (int) Math.min(Math.min(requested, plan.maxParallel()), candidate.available(plan.input()).longSaturated());
		operations = CentrifugeEnergyPricing.affordableOperations(plan.unitEnergyPerTick(), operations, state.energy());
		if (operations == 0) return null;
		// 拒绝溢出能耗，不把饱和值当作真实报价。
		plan.energyPerTick(operations);
		var job = new CentrifugeJob(UUID.randomUUID(), plan, operations, 0, seed, null);
		var reservation = candidate.prepare(Map.of(plan.input(), job.heldInputs()), Map.of(), plan.recipeRevision());
		if (reservation == null || !candidate.commit(reservation)) return null;
		return new CentrifugeWorkTransaction(state, ledger, state.replace(lane, job, state.energy()), candidate.checkpoint(), 0, plan.recipeRevision());
	}
	public static CentrifugeWorkTransaction advance(CentrifugeWorkState state, LedgerCheckpoint ledger,
			int lane, int ticks, boolean loaded, boolean enabled) {
		if (ticks < 0) throw new IllegalArgumentException("Negative work budget");
		var job = state.jobs().get(lane);
		if (job == null || !loaded || !enabled) return null;
		var result = job.advance(ticks, state.energy());
		if (result.executedTicks() == 0) return null;
		return new CentrifugeWorkTransaction(state, ledger,
				state.replace(lane, result.job(), state.energy() - result.energyUsed()), ledger, result.executedTicks(), -1);
	}
	public static CentrifugeWorkTransaction freeze(CentrifugeWorkState state, LedgerCheckpoint ledger, int lane) {
		var job = state.jobs().get(lane);
		if (job == null || !job.paid() || job.sampled()) return null;
		return new CentrifugeWorkTransaction(state, ledger, state.replace(lane, job.freeze(), state.energy()), ledger, 0, -1);
	}
	/** 完成投入消费与结果入账同属一个候选；全零随机结果也必须释放作业，不能永远占 lane。 */
	public static CentrifugeWorkTransaction settle(CentrifugeWorkState state, LedgerCheckpoint ledger, int lane, long policyRevision) {
		var job = state.jobs().get(lane);
		if (job == null || !job.sampled()) return null;
		return release(state, ledger, lane, policyRevision, job, job.frozen());
	}
	/** 只有尚未付过任何工作 tick 的预约可以撤销；已付费作业须继续完成或保留托管。 */
	public static CentrifugeWorkTransaction cancel(CentrifugeWorkState state, LedgerCheckpoint ledger, int lane, long policyRevision) {
		var job = state.jobs().get(lane);
		if (job == null || job.progress() != 0) return null;
		return release(state, ledger, lane, policyRevision, job, Map.of(job.plan().input(), job.heldInputs()));
	}
	private static CentrifugeWorkTransaction release(CentrifugeWorkState state, LedgerCheckpoint ledger, int lane,
			long policyRevision, CentrifugeJob job, Map<ProductKey, ProductAmount> amounts) {
		if (policyRevision < job.plan().recipeRevision()) throw new IllegalArgumentException("Future centrifuge policy");
		var next = ledger;
		if (!amounts.isEmpty()) {
			var policy = new ProductPolicyRegistry(new ProductPolicySnapshot(policyRevision, List.of(), List.of()));
			var candidate = restore(policy, ledger);
			var receipt = candidate.importPaidOutput(new LedgerCheckpoint.Pending(job.id(), job.plan().recipeRevision(),
					LedgerTransaction.State.PAID, Map.of(), amounts));
			if (!candidate.commit(receipt)) throw new IllegalStateException("Centrifuge settlement failed");
			next = candidate.checkpoint();
		}
		return new CentrifugeWorkTransaction(state, ledger, state.replace(lane, null, state.energy()), next, 0, policyRevision);
	}
	private static ProductLedger restore(ProductPolicyRegistry policy, LedgerCheckpoint ledger) {
		return ProductLedger.restore(policy, Math.addExact(ledger.transactions().size(), 1), ledger);
	}
}
