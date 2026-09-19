package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.persistence.read.NbtReadEvent;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.nbt.*;

/** 领域容器只保留固定字段；只有单个组件／数值实体化为原生 NBT。 */
final class CheckpointDecodeFrames {
	sealed interface Frame permits Domain, Records, Raw {
		String name();
		int type();
		void add(String name, Object value);
		Object finish();
	}
	record Domain(String name, CheckpointSchema.Node node) implements Frame {
		@Override public int type() { return Tag.TAG_COMPOUND; }
		@Override public void add(String name, Object value) { node.put(name, value); }
		@Override public Object finish() { return node.finish(); }
	}
	static final class Records implements Frame {
		private final String name;
		final CheckpointSchema.Kind element;
		private final int length;
		private final CheckpointSchema.ListSink sink;
		private int received;
		Records(String name, CheckpointSchema.Kind element, int length, CheckpointSchema.ListSink sink) {
			this.name = name; this.element = element; this.length = length; this.sink = sink;
		}
		@Override public String name() { return name; }
		@Override public int type() { return Tag.TAG_LIST; }
		@Override public void add(String name, Object value) {
			if (name != null || received >= length) throw new IllegalArgumentException("Invalid record list element");
			sink.add().accept(value); received++;
		}
		@Override public Object finish() {
			if (received != length) throw new IllegalArgumentException("Incomplete record list");
			return sink.finish().get();
		}
	}
	static final class Raw implements Frame {
		private final NbtReadEvent.Start start;
		private final Tag tag;
		private final Set<String> seen;
		private final ArrayList<ByteBuffer> chunks;
		private long bytes;
		Raw(NbtReadEvent.Start start) {
			this.start = start;
			tag = start.type() == Tag.TAG_COMPOUND ? new CompoundTag() : start.type() == Tag.TAG_LIST ? new ListTag() : null;
			seen = start.type() == Tag.TAG_COMPOUND ? ConcurrentHashMap.newKeySet() : null;
			chunks = tag == null ? new ArrayList<>() : null;
		}
		@Override public String name() { return start.name(); }
		@Override public int type() { return start.type(); }
		void begin(String name, int type) {
			if (seen != null) {
				if (name == null || !seen.add(name)) throw new IllegalArgumentException("Duplicate component field");
			} else if (tag instanceof ListTag list) {
				if (name != null || type != start.elementType() || list.size() >= start.length()) throw new IllegalArgumentException("Invalid component list");
			} else throw new IllegalArgumentException("Child in primitive array");
		}
		void chunk(NbtReadEvent.ArrayChunk chunk) {
			if (chunks == null) throw new IllegalArgumentException("Array bytes in container");
			var buffer = chunk.bytes(); bytes += buffer.remaining();
			long expected = (long) start.length() * width();
			if (bytes > expected) throw new IllegalArgumentException("Too many array bytes");
			chunks.add(buffer);
		}
		private int width() { return start.type() == Tag.TAG_INT_ARRAY ? 4 : start.type() == Tag.TAG_LONG_ARRAY ? 8 : 1; }
		@Override public void add(String name, Object value) {
			if (tag instanceof CompoundTag compound) compound.put(name, (Tag) value);
			else if (tag instanceof ListTag list) list.add((Tag) value);
			else throw new IllegalArgumentException("Value in primitive array");
		}
		@Override public Object finish() {
			if (tag instanceof ListTag list && list.size() != start.length()) throw new IllegalArgumentException("Incomplete component list");
			if (tag != null) return tag;
			if (bytes != (long) start.length() * width()) throw new IllegalArgumentException("Incomplete primitive array");
			// 只按已收到且核实的长度分配；整个单组件 codec 的不可抢占成本单独测量。
			if (start.type() == Tag.TAG_BYTE_ARRAY) {
				byte[] result = new byte[start.length()]; int offset = 0;
				for (var chunk : chunks) { int count = chunk.remaining(); chunk.get(result, offset, count); offset += count; }
				return new ByteArrayTag(result);
			}
			if (start.type() == Tag.TAG_INT_ARRAY) {
				int[] result = new int[start.length()]; int offset = 0;
				for (var chunk : chunks) { if (chunk.remaining() % 4 != 0) throw new IllegalArgumentException("Unaligned int array"); while (chunk.hasRemaining()) result[offset++] = chunk.getInt(); }
				return new IntArrayTag(result);
			}
			long[] result = new long[start.length()]; int offset = 0;
			for (var chunk : chunks) { if (chunk.remaining() % 8 != 0) throw new IllegalArgumentException("Unaligned long array"); while (chunk.hasRemaining()) result[offset++] = chunk.getLong(); }
			return new LongArrayTag(result);
		}
	}
	private CheckpointDecodeFrames() { }
}
