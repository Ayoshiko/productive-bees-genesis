package com.ayoshiko.productivebeesgenesis.resource;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** 空气以原始单位记账；压力只约束接收方，不把账户虚构成压力容器。 */
public final class ResourceAirTransfer {
	private ResourceAirTransfer() {}

	/** 留出一个 float 压力步长，且不越过原生 int 库存边界。 */
	public static int room(int air, int volume, float dangerPressure, float criticalPressure) {
		if (volume <= 0 || air < -(long) volume || !Float.isFinite(dangerPressure)
				|| !Float.isFinite(criticalPressure) || dangerPressure <= 0 || criticalPressure <= 0) return 0;
		float pressure = Math.nextDown(Math.min(dangerPressure, criticalPressure));
		long ceiling = Math.min(Integer.MAX_VALUE, (long) Math.floor((double) volume * pressure));
		// 原生计算还会将库存与体积转换为 float；大容积可能再次舍入到危险压力。
		if ((int) ceiling / (float) volume >= Math.min(dangerPressure, criticalPressure)) {
			long low = 0, high = ceiling;
			while (low < high) {
				long middle = (low + high + 1) >>> 1;
				if ((int) middle / (float) volume < Math.min(dangerPressure, criticalPressure)) low = middle;
				else high = middle - 1;
			}
			ceiling = low;
		}
		return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, ceiling - air));
	}

	/**
	 * addAir 没有接收量返回值。只有同步增量完全吻合时才确认提交；部分、延迟或异常接收
	 * 均交由外层账户冻结整份请求，不能把读回零当作拒收重试。
	 */
	public static long deliver(int before, long amount, IntSupplier read, IntConsumer add) {
		if (amount <= 0 || amount > Integer.MAX_VALUE || (long) before + amount > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("Invalid native air offer");
		}
		if (read.getAsInt() != before) throw new IllegalStateException("Air receiver changed before delivery");
		add.accept((int) amount);
		if ((long) read.getAsInt() - before != amount) {
			throw new IllegalStateException("Native air delivery outcome is uncertain");
		}
		return amount;
	}
}
