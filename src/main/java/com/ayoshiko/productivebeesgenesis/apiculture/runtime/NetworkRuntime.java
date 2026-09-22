package com.ayoshiko.productivebeesgenesis.apiculture.runtime;

import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreBlockEntity;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.OwnedMachineRecord;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiculture.production.NetworkBeeService;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import java.util.Iterator;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/** 核心的可丢弃执行索引；进度与资产只存在于 checkpoint，恢复仅重建身份队列。 */
public final class NetworkRuntime {
	public enum Status { STOPPED, PREPARING, RUNNING, ENERGY, FLOWER, ENVIRONMENT, STALE_PLAN, UNAVAILABLE, UNSUPPORTED, SETTLING, BACKLOG, FAILED, NO_INPUT, RESERVED, RULES_UNSUPPORTED }
	private record Work(UUID member, int slot) { }
	private final FairDueQueue<Work> tasks = new FairDueQueue<>();
	private Iterator<OwnedMachineRecord> discovery;
	private long scanTick, scanEpoch = -1;
	private int memberCount = -1;
	private boolean scanNext;
	private Status status = Status.STOPPED;
	private final CentrifugeRuntimeStep centrifuges = new CentrifugeRuntimeStep();
	public Status status() { return status; }
	/** 已提交的单蜂更换只更新一个到期项；新蜜蜂最早从下一真实 tick 工作。 */
	public void beeChanged(UUID member, int slot, boolean inserted, long now) {
		if (slot < 0 || slot >= 3) throw new IllegalArgumentException("Invalid basic bee slot");
		var work = new Work(member, slot);
		tasks.remove(work);
		if (inserted) tasks.offer(work, Math.incrementExact(now));
	}
	void failed() { status = Status.FAILED; }
	long step(NetworkCoreBlockEntity core, long now) {
		var level = (ServerLevel) core.getLevel(); var data = core.ownership().readyAuthority(); var topology = core.topology();
		if (data == null || topology == null || !topology.valid()) { status = Status.UNAVAILABLE; return now + 20; }
		var current = data.checkpoint();
		if (discovery == null && (now >= scanTick || scanEpoch != topology.epoch() || memberCount != current.ownedMachines().activeCount())) {
			discovery = current.ownedMachines().activeValues().iterator(); scanEpoch = topology.epoch(); memberCount = current.ownedMachines().activeCount(); scanTick = now + 200;
		}
		if (discovery != null && (scanNext || tasks.nextTick() > now)) {
			scanNext = false;
			if (discovery.hasNext()) {
				var record = discovery.next();
				if (record.phase() == OwnedMachineRecord.Phase.OWNED) tasks.offer(new Work(record.claim().member(), -1), now);
			} else discovery = null;
			return nextTick(now);
		}
		scanNext = true; boolean overdue = tasks.nextTick() < now;
		var work = tasks.poll(now); if (work == null) return nextTick(now);
		var record = current.ownedMachines().get(work.member());
		if (record == null || record.phase() != OwnedMachineRecord.Phase.OWNED) { centrifuges.forget(work.member()); return nextTick(now); }
		boolean running = core.productionRunning() && ModConfig.SERVER.beeNetwork.enabled.get();
		var directory = NetworkPersistence.directory(level.getServer());
		try {
			if (work.slot() == -1) activate(level, data, directory, record, running, now);
			else {
				if (record.claim().machine().equals("productivebeesgenesis:mek_centrifuge")) {
					status = centrifuges.run(level, data, directory, work.member(), running);
					tasks.offer(work, status == Status.PREPARING || status == Status.UNSUPPORTED ? now : now + (status == Status.RUNNING || status == Status.SETTLING ? 1 : 20));
					return nextTick(now);
				}
				status = BeeRuntimeStep.run(level, data, directory, work.member(), work.slot(), running);
				if (record.bees() != null && record.bees().bees().stream().anyMatch(bee -> bee.slot() == work.slot())) {
					boolean progressing = status == Status.RUNNING || status == Status.SETTLING;
					tasks.offer(work, now + (progressing ? 1 : 20));
					if (overdue && progressing) status = Status.BACKLOG;
				}
			}
		} catch (RuntimeException error) { tasks.offer(work, now + 200); throw error; }
		return nextTick(now);
	}
	private void activate(ServerLevel level, NetworkSavedData data, NetworkDirectory directory, OwnedMachineRecord record, boolean running, long now) {
		var member = record.claim().member();
		if (record.claim().machine().equals("productivebeesgenesis:mek_centrifuge")) { tasks.offer(new Work(member, 0), now); return; }
		if (!record.claim().machine().equals("productivebeesgenesis:mek_apiary")) { status = Status.UNSUPPORTED; return; }
		if (record.bees() == null) {
			if (!running) { status = Status.STOPPED; return; }
			try {
				if (!new NetworkBeeService(data, directory).activate(level, member, data.checkpoint().revision(), 0)) {
					status = Status.UNAVAILABLE; tasks.offer(new Work(member, -1), now + 20); return;
				}
			} catch (IllegalArgumentException unsupported) { status = Status.UNSUPPORTED; return; }
		}
		for (var bee : data.checkpoint().ownedMachines().get(member).bees().bees()) tasks.offer(new Work(member, bee.slot()), now);
		status = running ? Status.PREPARING : Status.STOPPED;
	}
	private long nextTick(long now) { return discovery != null ? now : Math.min(scanTick, tasks.nextTick()); }
}
