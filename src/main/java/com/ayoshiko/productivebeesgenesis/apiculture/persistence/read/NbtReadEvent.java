package com.ayoshiko.productivebeesgenesis.apiculture.persistence.read;

import java.nio.ByteBuffer;
import java.util.Objects;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/** 无注册表的语法事件。列表元素的 name 为 null；重复字段原样保留，交给领域校验拒绝。 */
public sealed interface NbtReadEvent {
	int accountedBytes();

	record Start(String name, int type, int elementType, int length) implements NbtReadEvent {
		@Override public int accountedBytes() { return 96 + nameBytes(name); }
	}
	record End(int type) implements NbtReadEvent {
		@Override public int accountedBytes() { return 32; }
	}
	record Scalar(String name, Tag value) implements NbtReadEvent {
		public Scalar {
			Objects.requireNonNull(value);
			if (!(value instanceof NumericTag) && !(value instanceof StringTag)) throw new IllegalArgumentException("Expected immutable scalar");
		}
		@Override public int accountedBytes() {
			return 128 + nameBytes(name) + (value instanceof StringTag text ? nameBytes(text.getAsString()) : 0);
		}
	}
	/** 大数组按原始大端字节分片；不暴露可修改数组，也不按文件声明长度一次分配。 */
	final class ArrayChunk implements NbtReadEvent {
		private final byte[] bytes;
		private ArrayChunk(byte[] bytes) { this.bytes = bytes; }
		static ArrayChunk take(byte[] bytes) { return new ArrayChunk(bytes); }
		public ByteBuffer bytes() { return ByteBuffer.wrap(bytes).asReadOnlyBuffer(); }
		@Override public int accountedBytes() { return 64 + bytes.length; }
	}
	private static int nameBytes(String value) { return value == null ? 0 : 2 * value.length(); }
}
