package com.ayoshiko.productivebeesgenesis.apiculture.runtime;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberWorkLayout;
import com.ayoshiko.productivebeesgenesis.apiculture.capacity.WorkKey;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreBlockEntity;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.OwnedMachineRecord;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkDirectory;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkPersistence;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkSavedData;
import com.ayoshiko.productivebeesgenesis.apiculture.production.NetworkBeeService;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/** 核心的可丢弃执行索引；进度与资产只存在于 checkpoint，恢复仅重建身份队列。 */
public final class NetworkRuntime {
	public enum Status { STOPPED, PREPARING, RUNNING, ENERGY, FLOWER, ENVIRONMENT, STALE_PLAN, UNAVAILABLE, UNSUPPORTED, SETTLING, BACKLOG, FAILED, NO_INPUT, RESERVED, RULES_UNSUPPORTED }
	private final FairDueQueue<MemberWorkTask> tasks = new FairDueQueue<>();
	private final Map<UUID, Integer> registrations = new HashMap<>();
	private Iterator<OwnedMachineRecord> discovery;
	private long scanTick, scanEpoch = -1;
	private int memberCount = -1;
	private boolean scanNext;
	private Status status = Status.STOPPED;
	private final CentrifugeRuntimeStep centrifuges = new CentrifugeRuntimeStep();
	public Status status() { return status; }

	/** 已提交的单蜂更换只更新一个到期项；范围由权威交换校验，调度器不另设三槽限制。 */
	public void beeChanged(UUID member, int slot, boolean inserted, long now) {
		var work = new MemberWorkTask.Produce(member, new MemberWorkLayout.Slot(WorkKey.Kind.BEE_CYCLE, slot));
		tasks.remove(work);
		if (inserted) tasks.offer(work, Math.incrementExact(now));
	}
	void failed() { status = Status.FAILED; }

	long step(NetworkCoreBlockEntity core, long now) {
		var level = (ServerLevel) core.getLevel(); var data = core.ownership().readyAuthority(); var topology = core.topology();
		if (data == null || topology == null || !topology.valid()) { centrifuges.clear(); status = Status.UNAVAILABLE; return now + 20; }
		var current = data.checkpoint();
		if (discovery == null && (now >= scanTick || scanEpoch != topology.epoch() || memberCount != current.ownedMachines().activeCount())) {
			discovery = current.ownedMachines().activeValues().iterator(); scanEpoch = topology.epoch();
			memberCount = current.ownedMachines().activeCount(); scanTick = now + 200;
		}
		if (discovery != null && (scanNext || tasks.nextTick() > now)) {
			scanNext = false;
			if (discovery.hasNext()) {
				var record = discovery.next();
				if (record.phase() == OwnedMachineRecord.Phase.OWNED)
					tasks.offer(new MemberWorkTask.Activate(record.claim().member()), now);
			} else discovery = null;
			return nextTick(now);
		}
		scanNext = true; boolean overdue = tasks.nextTick() < now;
		var task = tasks.poll(now); if (task == null) return nextTick(now);
		var record = current.ownedMachines().get(task.member());
		if (record == null || record.phase() != OwnedMachineRecord.Phase.OWNED) {
			centrifuges.forget(task.member()); registrations.remove(task.member()); return nextTick(now);
		}
		boolean running = core.productionRunning() && ModConfig.SERVER.beeNetwork.enabled.get();
		var directory = NetworkPersistence.directory(level.getServer());
		try {
			if (task instanceof MemberWorkTask.Activate) activate(level, data, directory, record, running, now);
			else if (task instanceof MemberWorkTask.Register) register(record, now);
			else produce(level, data, directory, record, (MemberWorkTask.Produce) task, running, overdue, now);
		} catch (RuntimeException error) { tasks.offer(task, now + 200); throw error; }
		return nextTick(now);
	}

	private void produce(ServerLevel level, NetworkSavedData data, NetworkDirectory directory, OwnedMachineRecord record,
			MemberWorkTask.Produce work, boolean running, boolean overdue, long now) {
		var slot = work.slot();
		if (!record.workLayout().contains(slot)) return;
		if (slot.kind() == WorkKey.Kind.CENTRIFUGE_RECIPE) {
			status = centrifuges.run(level, data, directory, work.member(), slot.index(), running);
			tasks.offer(work, status == Status.PREPARING || status == Status.UNSUPPORTED
					? now : now + (status == Status.RUNNING || status == Status.SETTLING ? 1 : 20));
			return;
		}
		if (record.combined() != null) {
			if (record.combined().bees().stream().noneMatch(bee -> bee.slot() == slot.index())) return;
			status = com.ayoshiko.productivebeesgenesis.multiblock.world.CombinedNetworkService.bee(level, data, directory, record, slot.index(), running);
		} else {
			if (record.bees() == null || record.bees().bees().stream().noneMatch(bee -> bee.slot() == slot.index())) return;
			status = BeeRuntimeStep.run(level, data, directory, work.member(), slot.index(), running);
		}
		boolean progressing = status == Status.RUNNING || status == Status.SETTLING;
		tasks.offer(work, now + (progressing ? 1 : 20));
		if (overdue && progressing) status = Status.BACKLOG;
	}

	private void activate(ServerLevel level, NetworkSavedData data, NetworkDirectory directory,
			OwnedMachineRecord record, boolean running, long now) {
		var member = record.claim().member(); var layout = record.workLayout();
		if (layout.totalLanes() == 0) { status = Status.UNSUPPORTED; return; }
		if (record.claim().machine().equals(com.ayoshiko.productivebeesgenesis.multiblock.production.CombinedAssetProjection.MACHINE)) {
			if (record.combined() == null && !com.ayoshiko.productivebeesgenesis.multiblock.world.CombinedNetworkService.activate(level, data, directory, record)) {
				status = Status.ENERGY; tasks.offer(new MemberWorkTask.Activate(member), now + 20); return;
			}
		} else if (layout.beeSlots() > 0 && record.bees() == null) {
			if (!running) { status = Status.STOPPED; return; }
			try {
				if (!new NetworkBeeService(data, directory).activate(level, member, data.checkpoint().revision(), 0)) {
					status = Status.UNAVAILABLE; tasks.offer(new MemberWorkTask.Activate(member), now + 20); return;
				}
			} catch (IllegalArgumentException unsupported) { status = Status.UNSUPPORTED; return; }
		}
		registrations.putIfAbsent(member, 0);
		tasks.offer(new MemberWorkTask.Register(member), now);
		status = running ? Status.PREPARING : Status.STOPPED;
	}

	/** 每个共享服务步只登记一个工作位，不能在高等级成员激活时一次展开全部任务。 */
	private void register(OwnedMachineRecord record, long now) {
		var member = record.claim().member(); var layout = record.workLayout();
		int cursor = registrations.getOrDefault(member, 0);
		if (cursor >= layout.totalLanes()) { registrations.remove(member); return; }
		var slot = layout.slot(cursor);
		if (slot.kind() == WorkKey.Kind.CENTRIFUGE_RECIPE || record.combined() != null && record.combined().bees().stream().anyMatch(bee -> bee.slot() == slot.index()) || record.bees() != null
				&& record.bees().bees().stream().anyMatch(bee -> bee.slot() == slot.index()))
			tasks.offer(new MemberWorkTask.Produce(member, slot), now);
		if (cursor + 1 < layout.totalLanes()) {
			registrations.put(member, cursor + 1); tasks.offer(new MemberWorkTask.Register(member), now);
		} else registrations.remove(member);
	}
	private long nextTick(long now) { return discovery != null ? now : Math.min(scanTick, tasks.nextTick()); }
}
