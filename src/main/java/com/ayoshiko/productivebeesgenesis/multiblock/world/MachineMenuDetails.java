package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.multiblock.production.CombinedMachineWork;
import com.ayoshiko.productivebeesgenesis.multiblock.runtime.MachineDirectory;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;

/** 固定六蜂位／三进程的只读显示；沿当前工作根采样，不重算配方或生产能力。 */
public final class MachineMenuDetails {
	public enum Field { AVAILABLE, ENERGY_CAPACITY, BEE_SLOTS, LANES, ITEM_USED, ITEM_SLOTS, ITEM_COUNT,
		FLUID_USED, FLUID_TANKS, FLUID_AMOUNT, FLUID_CAPACITY, PENDING_BEES, PAID_JOBS,
		POS_X, POS_Y, POS_Z, SIZE_X, SIZE_Y, SIZE_Z }
	private static final int BEE_START = Field.values().length, JOB_START = BEE_START + 6 * 3;
	private final ContainerData data = new SimpleContainerData((JOB_START + 3 * 4) * 4);
	private long nextSample;
	ContainerData data() { return data; }
	public long value(Field field) { return number(field.ordinal()); }
	public long beeProgress(int slot) { return number(BEE_START + slot * 3); }
	public long beeCycle(int slot) { return number(BEE_START + slot * 3 + 1); }
	public long beeWaiting(int slot) { return number(BEE_START + slot * 3 + 2); }
	public long jobProgress(int lane) { return number(JOB_START + lane * 4); }
	public long jobCycle(int lane) { return number(JOB_START + lane * 4 + 1); }
	public long jobOperations(int lane) { return number(JOB_START + lane * 4 + 2); }
	public long jobPaid(int lane) { return number(JOB_START + lane * 4 + 3); }
	void unavailable() { put(Field.AVAILABLE, 0); }
	void capture(CombinedMachineWork work, MachineDirectory.Binding binding, long now) {
		if (now < nextSample && value(Field.AVAILABLE) != 0) return;
		nextSample = now + 10;
		put(Field.ENERGY_CAPACITY, work.energyCapacity()); put(Field.BEE_SLOTS, work.beeSlots()); put(Field.LANES, work.lanes());
		var buffer = work.buffer(); long items = 0, fluids = 0; int itemUsed = 0, fluidUsed = 0;
		for (var cell : buffer.items()) { items += cell.count(); if (cell.key() != null) itemUsed++; }
		for (var cell : buffer.fluids()) { fluids += cell.count(); if (cell.key() != null) fluidUsed++; }
		put(Field.ITEM_USED, itemUsed); put(Field.ITEM_SLOTS, buffer.items().size()); put(Field.ITEM_COUNT, items);
		put(Field.FLUID_USED, fluidUsed); put(Field.FLUID_TANKS, buffer.fluids().size()); put(Field.FLUID_AMOUNT, fluids);
		put(Field.FLUID_CAPACITY, (long) buffer.tankCapacity() * buffer.fluids().size());
		for (int i = BEE_START; i < JOB_START + 3 * 4; i++) number(i, 0);
		int waiting = 0, paid = 0;
		for (var bee : work.bees()) {
			int start = BEE_START + bee.slot() * 3;
			number(start, bee.progress()); number(start + 1, bee.plan().cycleTicks()); number(start + 2, bee.drained() ? 0 : 1);
			if (!bee.drained()) waiting++;
		}
		for (var entry : work.centrifuges().entrySet()) {
			var job = entry.getValue().job(); int start = JOB_START + entry.getKey() * 4;
			number(start, job.progress()); number(start + 1, job.plan().cycleTicks()); number(start + 2, job.operations()); number(start + 3, job.paid() ? 1 : 0);
			if (job.paid()) paid++;
		}
		put(Field.PENDING_BEES, waiting); put(Field.PAID_JOBS, paid);
		var pos = binding.handle().controller(); var region = binding.region();
		put(Field.POS_X, pos.getX()); put(Field.POS_Y, pos.getY()); put(Field.POS_Z, pos.getZ());
		put(Field.SIZE_X, region.max().getX() - region.min().getX() + 1);
		put(Field.SIZE_Y, region.max().getY() - region.min().getY() + 1);
		put(Field.SIZE_Z, region.max().getZ() - region.min().getZ() + 1);
		put(Field.AVAILABLE, 1);
	}
	private void put(Field field, long value) { number(field.ordinal(), value); }
	private long number(int field) { long value = 0; for (int i = 0; i < 4; i++) value |= (data.get(field * 4 + i) & 65535L) << (i * 16); return value; }
	private void number(int field, long value) { for (int i = 0; i < 4; i++) data.set(field * 4 + i, (int) (value >>> (i * 16)) & 65535); }
}
