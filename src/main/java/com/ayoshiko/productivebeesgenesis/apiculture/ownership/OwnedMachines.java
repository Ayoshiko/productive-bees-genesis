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
	private final Object token = new Object();
	private final Object previous;
	private OwnedMachines(Map<UUID, OwnedMachineRecord> records, Map<Origin, UUID> positions, Map<UUID, UUID> transfers, Object previous) { this.records = records; this.positions = positions; this.transfers = transfers; this.previous = previous; }
	public boolean hasTransfer(UUID transfer) { return transfers.containsKey(transfer); }
	public OwnedMachineRecord get(UUID member) { return records.get(member); }
	public OwnedMachineRecord at(Origin origin) { var member = positions.get(origin); return member == null ? null : records.get(member); }
	public Collection<OwnedMachineRecord> values() { return records.values(); }
	/** 调度发现只遍历当前保管成员，不反复扫描已交还的历史记录。 */
	public Iterable<OwnedMachineRecord> activeValues() {
		return () -> new Iterator<>() {
			private final Iterator<UUID> ids = positions.values().iterator();
			@Override public boolean hasNext() { return ids.hasNext(); }
			@Override public OwnedMachineRecord next() { return records.get(ids.next()); }
		};
	}
	public int size() { return records.size(); }
	public int activeCount() { return positions.size(); }
	public boolean follows(OwnedMachines old) { return this == old || previous == old.token || old.size() == 0 && size() == 0; }
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
		add(next, locations, intents, record, false); return new OwnedMachines(next.snapshot(), locations.snapshot(), intents.snapshot(), token);
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
		public void add(OwnedMachineRecord value) { OwnedMachines.add(records, positions, transfers, value, true); }
		public OwnedMachines finish() { return new OwnedMachines(records.snapshot(), positions.snapshot(), transfers.snapshot(), null); }
	}
	@Override public boolean equals(Object other) { return this == other || other instanceof OwnedMachines machines && records.equals(machines.records); }
	@Override public int hashCode() { return records.hashCode(); }
}
