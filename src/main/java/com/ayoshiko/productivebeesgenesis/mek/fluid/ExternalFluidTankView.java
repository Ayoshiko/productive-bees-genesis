package com.ayoshiko.productivebeesgenesis.mek.fluid;

import com.ayoshiko.productivebeesgenesis.logistics.RotatingContainerView;
import mekanism.api.fluid.IExtendedFluidTank;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多流体输出槽的外部能力视图。
 * <p>
 * 仅暴露当前非空槽，减少通用 {@code IFluidHandler} 在 probe/drain 中对预分配空槽的扫描；
 * 空仓时保留一个空槽哨兵，避免 capability 被长期缓存为不存在。活跃槽仍按游戏刻轮转，
 * 保证多种流体都有机会成为首个抽取目标。
 */
final class ExternalFluidTankView {

	private final List<IExtendedFluidTank> source;
	private volatile RotatingContainerView<IExtendedFluidTank> rotatingView =
			new RotatingContainerView<>(List.of());
	private final AtomicLong invalidationVersion = new AtomicLong();
	private volatile long refreshedVersion = Long.MIN_VALUE;

	ExternalFluidTankView(List<IExtendedFluidTank> source) {
		this.source = source;
	}

	void invalidate() {
		invalidationVersion.incrementAndGet();
	}

	List<IExtendedFluidTank> forTick(long gameTime) {
		refreshIfNeeded();
		return rotatingView.forTick(gameTime);
	}

	private void refreshIfNeeded() {
		long requestedVersion = invalidationVersion.get();
		if (requestedVersion == refreshedVersion) return;
		synchronized (this) {
			requestedVersion = invalidationVersion.get();
			if (requestedVersion == refreshedVersion) return;
			List<IExtendedFluidTank> refreshed = new ArrayList<>(source.size());
			for (IExtendedFluidTank tank : source) {
				if (tank != null && !tank.isEmpty()) {
					refreshed.add(tank);
				}
			}
			if (refreshed.isEmpty() && !source.isEmpty()) {
				refreshed.add(source.get(0));
			}
			CopyOnWriteArrayList<IExtendedFluidTank> published = new CopyOnWriteArrayList<>(refreshed);
			rotatingView = new RotatingContainerView<>(published);
			refreshedVersion = requestedVersion;
		}
	}
}
