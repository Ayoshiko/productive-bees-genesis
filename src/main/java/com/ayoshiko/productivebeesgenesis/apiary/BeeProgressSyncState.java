package com.ayoshiko.productivebeesgenesis.apiary;

import java.util.function.LongSupplier;
import net.minecraft.nbt.CompoundTag;

/** 每个打开的容器独立采样，仅降低显示进度的同步频率，不修改生产计时。 */
final class BeeProgressSyncState {

	private static final int INTERVAL = 5;
	private final BeeSlot slot;
	private final LongSupplier gameTime;
	private long lastSampleTick = Long.MIN_VALUE;
	private CompoundTag beeData;
	private BeeState state;
	private boolean nectar;
	private int duration;
	private int observedTicks;
	private int ticks;
	private float progress;

	BeeProgressSyncState(BeeSlot slot, LongSupplier gameTime) {
		this.slot = slot;
		this.gameTime = gameTime;
	}

	float progress() {
		refresh();
		return progress;
	}

	int ticks() {
		refresh();
		return ticks;
	}

	private void refresh() {
		long now = gameTime.getAsLong();
		int currentTicks = slot.getTicksInHive();
		if (lastSampleTick == Long.MIN_VALUE || now < lastSampleTick || now - lastSampleTick >= INTERVAL
				|| beeData != slot.getBeeData() || state != slot.getState()
				|| nectar != slot.hasNectar() || duration != slot.getMinOccupationTicks()
				|| currentTicks < observedTicks) {
			lastSampleTick = now;
			beeData = slot.getBeeData();
			state = slot.getState();
			nectar = slot.hasNectar();
			duration = slot.getMinOccupationTicks();
			ticks = currentTicks;
			progress = slot.getProgress();
		}
		observedTicks = currentTicks;
	}
}
