package com.ayoshiko.productivebeesgenesis.apiculture.ownership;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.SnapshotRecords;
import java.util.*;

/** 封存记录及占用位置的不可变根；一次交接更新只复制两条树路径。 */
public final class OwnedMachines {
	private static final Comparator<Origin> POSITIONS = Comparator.comparing(Origin::dimension).thenComparingInt(Origin::x).thenComparingInt(Origin::y).thenComparingInt(Origin::z);
	public static final OwnedMachines EMPTY = new Builder().finish();
	private final Map<UUID, OwnedMachineRecord> records;
	private final Map<Origin, UUID> positions;
	private final Map<UUID, UUID> transfers;
	private final Map<String, Map<Origin, UUID>> byMachine;
	private final Object token = new Object();
	private final Object previous;
	private final Object queryToken;
	private final Object capabilityToken;
	private OwnedMachines(Map<UUID, OwnedMachineRecord> records, Map<Origin, UUID> positions, Map<UUID, UUID> transfers, Map<String, Map<Origin, UUID>> byMachine, Object previous, Object queryToken, Object capabilityToken) { this.records = records; this.positions = positions; this.transfers = transfers; this.byMachine = byMachine; this.previous = previous; this.queryToken = queryToken; this.capabilityToken = capabilityToken; }
	public boolean hasTransfer(UUID transfer) { return transfers.containsKey(transfer); }
	public OwnedMachineRecord get(UUID member) { return records.get(member); }
	public OwnedMachineRecord at(Origin origin) { var member = positions.get(origin); return member == null ? null : records.get(member); }
	public Collection<OwnedMachineRecord> values() { return records.values(); }
	/** 调度发现只遍历当前保管成员，不反复扫描已交还的历史记录。 */
	public Iterable<OwnedMachineRecord> activeValues() { return activeValues(null); }
	/** 类型位置索引与资产根一起派生；翻页不扫描其它机器类型。 */
	public Iterable<OwnedMachineRecord> activeValues(String machine) {
		var selected = machine == null ? positions : byMachine.getOrDefault(machine, Map.of());
		return () -> new Iterator<>() {
			private final Iterator<UUID> ids = selected.values().iterator();
			@Override public boolean hasNext() { return ids.hasNext(); }
			@Override public OwnedMachineRecord next() { return records.get(ids.next()); }
		};
	}
	public int size() { return records.size(); }
	/** 在当前类型位置索引正反向续查；不保留旧记录根，也不枚举之前的成员。 */
	public OwnedMachineRecord activeEntry(String machine, Origin cursor, boolean reverse) {
		var selected = machine == null ? positions : byMachine.getOrDefault(machine, Map.of());
		if (selected.isEmpty()) return null;
		var entry = reverse ? SnapshotRecords.previousEntry(selected, cursor) : SnapshotRecords.nextEntry(selected, cursor);
		return entry == null ? null : records.get(entry.getValue());
	}
	public int activeCount() { return positions.size(); }
	public Object queryToken() { return queryToken; }
	public Object capabilityToken() { return capabilityToken; }
	public boolean follows(OwnedMachines old) { return this == old || previous == old.token || old.size() == 0 && size() == 0; }
	/** 升级只接受从当前记录签发的专用凭据，不放宽通用生产后继的资产约束。 */
	public OwnedMachines exchangeUpgrade(MemberUpgradeChange change) {
		var member = change.candidate().claim().member();
		if (!change.matches(records.get(member))) throw new IllegalArgumentException("Stale member upgrade exchange");
		var next = SnapshotRecords.fork(records, UUID::compareTo);
		next.put(member, change.candidate());
		return new OwnedMachines(next.snapshot(), positions, transfers, byMachine, token, queryToken, new Object());
	}
	/** 付款证明绑定原蜂状态；允许执行器在空周期切换时间，不放宽通用 put。 */
	public OwnedMachines applyBeeWork(UUID member, com.ayoshiko.productivebeesgenesis.apiculture.production.BeeWorkExecutor.Result result) {
		var old = Objects.requireNonNull(records.get(member), "Missing bee owner");
		if (old.phase() != OwnedMachineRecord.Phase.OWNED || old.bees() == null
				|| result.status() != com.ayoshiko.productivebeesgenesis.apiculture.production.BeeWorkExecutor.Status.READY
				|| !result.matches(old.bees())) throw new IllegalArgumentException("Stale bee work");
		var next = SnapshotRecords.fork(records, UUID::compareTo);
		next.put(member, new OwnedMachineRecord(old.claim(), old.phase(), old.assets(), old.fingerprint(), "", result.candidate()));
		return new OwnedMachines(next.snapshot(), positions, transfers, byMachine, token, queryToken, capabilityToken);
	}
	/** 有限蜂笼交接只替换对应记录；原位置和机器交接收据沿用原根。 */
	public OwnedMachines exchangeBee(com.ayoshiko.productivebeesgenesis.apiculture.production.BeeRosterChange change) {
		var member = change.bee().member();
		var old = Objects.requireNonNull(records.get(member), "Missing bee owner");
		var next = SnapshotRecords.fork(records, UUID::compareTo);
		next.put(member, old.exchangeBee(change));
		return new OwnedMachines(next.snapshot(), positions, transfers, byMachine, token, new Object(), capabilityToken);
	}
	public OwnedMachines put(OwnedMachineRecord record) {
		var next = SnapshotRecords.fork(records, UUID::compareTo); var locations = SnapshotRecords.fork(positions, POSITIONS);
		var intents = SnapshotRecords.fork(transfers, UUID::compareTo);
		var old = records.get(record.claim().member());
		if (old == null) {
			if (record.phase() != OwnedMachineRecord.Phase.SEALED) throw new IllegalArgumentException("Ownership must begin sealed");
		} else {
			if (old.equals(record)) return this;
			old.validateSuccessor(record);
		}
		if (old != null && old.phase() != OwnedMachineRecord.Phase.RETURNED) locations.remove(old.claim().origin());
		add(next, locations, intents, record, false);
		Map<String, Map<Origin, UUID>> groups = byMachine;
		boolean wasActive = old != null && old.phase() != OwnedMachineRecord.Phase.RETURNED;
		boolean active = record.phase() != OwnedMachineRecord.Phase.RETURNED;
		if (wasActive != active || wasActive && (!old.claim().origin().equals(record.claim().origin())
				|| !old.claim().machine().equals(record.claim().machine()))) {
			var changed = SnapshotRecords.fork(byMachine, String::compareTo);
			if (wasActive) index(changed, old, false);
			if (active) index(changed, record, true);
			groups = changed.snapshot();
		}
		boolean rosterChanged = old == null || old.bees() == null != (record.bees() == null)
				|| old.bees() != null && record.bees() != null && old.bees().rosterVersion() != record.bees().rosterVersion();
		return new OwnedMachines(next.snapshot(), locations.snapshot(), intents.snapshot(), groups, token,
				groups != byMachine || rosterChanged ? new Object() : queryToken,
				groups != byMachine || old == null || old.phase() != record.phase() || old.bees() == null != (record.bees() == null) ? new Object() : capabilityToken);
	}
	private static void index(SnapshotRecords<String, Map<Origin, UUID>> groups, OwnedMachineRecord record, boolean insert) {
		var machine = record.claim().machine();
		var existing = groups.get(machine);
		var group = SnapshotRecords.fork(existing == null ? Map.of() : existing, POSITIONS);
		if (insert) group.put(record.claim().origin(), record.claim().member()); else group.remove(record.claim().origin());
		var snapshot = group.snapshot();
		if (snapshot.isEmpty()) groups.remove(machine); else groups.put(machine, snapshot);
	}
	private static void add(SnapshotRecords<UUID, OwnedMachineRecord> values, SnapshotRecords<Origin, UUID> positions, SnapshotRecords<UUID, UUID> transfers, OwnedMachineRecord record, boolean restoring) {
		var claim = record.claim();
		if (restoring && values.get(claim.member()) != null) throw new IllegalArgumentException("Duplicate owned member");
		var previous = transfers.get(claim.transfer());
		if (previous != null && !previous.equals(claim.member())) throw new IllegalArgumentException("Duplicate ownership transfer");
		if (record.phase() != OwnedMachineRecord.Phase.RETURNED) {
			if (positions.get(claim.origin()) != null) throw new IllegalArgumentException("Duplicate occupied member position");
			positions.put(claim.origin(), claim.member());
		}
		values.put(claim.member(), record); transfers.put(claim.transfer(), claim.member());
	}
	public static final class Builder {
		private final SnapshotRecords<UUID, OwnedMachineRecord> records = new SnapshotRecords<>(UUID::compareTo);
		private final SnapshotRecords<Origin, UUID> positions = new SnapshotRecords<>(POSITIONS);
		private final SnapshotRecords<UUID, UUID> transfers = new SnapshotRecords<>(UUID::compareTo);
		private final SnapshotRecords<String, Map<Origin, UUID>> groups = new SnapshotRecords<>(String::compareTo);
		public void add(OwnedMachineRecord value) {
			OwnedMachines.add(records, positions, transfers, value, true);
			if (value.phase() != OwnedMachineRecord.Phase.RETURNED) index(groups, value, true);
		}
		public OwnedMachines finish() {
			// 索引构建计入逐条恢复预算；最终发布只冻结根，不再扫描全部成员。
			return new OwnedMachines(records.snapshot(), positions.snapshot(), transfers.snapshot(), groups.snapshot(), null, new Object(), new Object());
		}
	}
	@Override public boolean equals(Object other) { return this == other || other instanceof OwnedMachines machines && records.equals(machines.records); }
	@Override public int hashCode() { return records.hashCode(); }
}
