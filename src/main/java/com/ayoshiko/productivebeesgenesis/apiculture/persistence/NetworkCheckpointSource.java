package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot;
import com.ayoshiko.productivebeesgenesis.apiculture.capacity.VirtualLaneState;
import com.ayoshiko.productivebeesgenesis.apiculture.policy.ProcessingRuleScheduler;
import com.ayoshiko.productivebeesgenesis.apiculture.policy.SchedulerCheckpoint;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.LedgerCheckpoint;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductLedger;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductPolicyRegistry;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.TransferStaging;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 同一主线程边界冻结所有 P1 域；不调用适配器、不枚举记录、不执行生产或文件 IO。 */
public final class NetworkCheckpointSource {
	private final Thread owner = Thread.currentThread();
	private final NetworkIdentity identity;
	private final ProductLedger ledger;
	private final ProductPolicyRegistry policy;
	private final TransferStaging staging;
	private final ProcessingRuleScheduler scheduler;
	private final NetworkMemberRecords members;
	public NetworkCheckpointSource(NetworkIdentity identity, ProductLedger ledger, ProductPolicyRegistry policy,
			TransferStaging staging, ProcessingRuleScheduler scheduler) {
		this.identity = Objects.requireNonNull(identity); this.ledger = Objects.requireNonNull(ledger);
		this.policy = Objects.requireNonNull(policy); this.staging = Objects.requireNonNull(staging);
		this.scheduler = Objects.requireNonNull(scheduler); members = new NetworkMemberRecords(identity.origin().dimension());
		validateSources();
	}
	public NetworkCheckpoint capture(long revision) {
		checkThread(); validateSources();
		if (revision < 0) throw new IllegalArgumentException("Negative checkpoint revision");
		return NetworkCheckpoint.capture(this, revision);
	}
	public void putMember(MemberCapabilitySnapshot member) { checkThread(); members.putMember(member); }
	public void removeMember(UUID member) { checkThread(); members.removeMember(member); }
	public void putLane(VirtualLaneState lane) { checkThread(); members.putLane(lane); }
	public void removeLane(UUID member, int index) { checkThread(); members.removeLane(member, index); }
	private void validateSources() {
		ledger.validateCheckpointPolicy(policy); staging.validateCheckpointLedger(ledger); scheduler.validateCheckpointLedger(ledger);
		policy.validateCheckpoint();
	}
	NetworkIdentity identity() { return identity; }
	long policyRevision() { return policy.snapshot().revision(); }
	LedgerCheckpoint ledger() { return ledger.checkpoint(); }
	List<TransferStaging.View> transfers() { return staging.snapshot(); }
	Set<ProductPolicyRegistry.Discovery> discoveries() { return policy.discoveries(); }
	List<MemberCapabilitySnapshot> members() { return members.members(); }
	List<VirtualLaneState> lanes() { return members.lanes(); }
	SchedulerCheckpoint scheduler() { return scheduler.checkpoint(); }
	private void checkThread() {
		if (Thread.currentThread() != owner) throw new IllegalStateException("Checkpoint source belongs to its server thread");
	}
}
