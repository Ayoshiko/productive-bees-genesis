package com.ayoshiko.productivebeesgenesis.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class GenerationMemoizedSupplierTest {

	@Test
	void reusesOneValueAndResolvesAgainAfterReload() {
		var generation = new AtomicLong();
		var calls = new AtomicInteger();
		var supplier = new GenerationMemoizedSupplier<>(calls::incrementAndGet, generation::get);
		for (int i = 0; i < 1000; i++) assertEquals(1, supplier.get());
		assertEquals(1, calls.get());
		generation.incrementAndGet();
		assertEquals(2, supplier.get());
		assertEquals(2, calls.get());
	}

	@Test
	void nullFailureAndMidResolutionReloadAreNotCached() {
		var generation = new AtomicLong();
		var calls = new AtomicInteger();
		var supplier = new GenerationMemoizedSupplier<>(() -> {
			int call = calls.incrementAndGet();
			if (call == 1) return null;
			if (call == 2) throw new IllegalStateException("fixture");
			if (call == 3) generation.incrementAndGet();
			return call;
		}, generation::get);
		assertNull(supplier.get());
		assertThrows(IllegalStateException.class, supplier::get);
		assertEquals(3, supplier.get());
		assertEquals(4, supplier.get());
		assertEquals(4, supplier.get());
		assertEquals(4, calls.get());
	}
}
