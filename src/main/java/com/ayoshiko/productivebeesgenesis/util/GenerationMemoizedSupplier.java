package com.ayoshiko.productivebeesgenesis.util;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** 固定配置的惰性值按代际复用；每个供应商只持有一个值，不建立全局缓存。 */
public final class GenerationMemoizedSupplier<T> implements Supplier<T> {

	private final Supplier<T> source;
	private final LongSupplier generation;
	private volatile Value<T> cached;

	public GenerationMemoizedSupplier(Supplier<T> source, LongSupplier generation) {
		this.source = source;
		this.generation = generation;
	}

	@Override
	public T get() {
		long version = generation.getAsLong();
		Value<T> current = cached;
		if (current != null && current.generation() == version) return current.value();
		return refresh();
	}

	private synchronized T refresh() {
		long version = generation.getAsLong();
		Value<T> current = cached;
		if (current != null && current.generation() == version) return current.value();
		cached = null;
		T value = source.get();
		// 注册阶段空值和跨重载的结果不进入缓存；异常直接传播，下一次可重新解析。
		if (value != null && generation.getAsLong() == version) cached = new Value<>(version, value);
		return value;
	}

	private record Value<T>(long generation, T value) {
	}
}
