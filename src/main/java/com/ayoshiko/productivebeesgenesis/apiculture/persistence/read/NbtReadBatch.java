package com.ayoshiko.productivebeesgenesis.apiculture.persistence.read;

import java.util.List;

/** 一个消费批次；调用方可按事件预算分多 tick 消费，不能累计保留已处理批次。 */
public record NbtReadBatch(List<NbtReadEvent> events, int accountedBytes) {
	public NbtReadBatch {
		events = List.copyOf(events);
		int actual = 0;
		for (var event : events) actual = Math.addExact(actual, event.accountedBytes());
		if (events.isEmpty() || actual != accountedBytes) throw new IllegalArgumentException("Invalid read batch accounting");
	}
}
