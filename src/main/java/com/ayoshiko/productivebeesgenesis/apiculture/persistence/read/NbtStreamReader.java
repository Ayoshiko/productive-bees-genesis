package com.ayoshiko.productivebeesgenesis.apiculture.persistence.read;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import net.minecraft.nbt.*;

/** 迭代读取标准 NBT；只保留容器栈，数组与嵌套记录都不物化整域。 */
final class NbtStreamReader {
	static final int ARRAY_CHUNK_BYTES = 8 * 1024;
	static final int MAX_DEPTH = 512;
	@FunctionalInterface interface Sink { void accept(NbtReadEvent event) throws IOException; }
	private static final class Frame {
		final int type;
		final int elementType;
		int remaining;
		Frame(int type, int elementType, int remaining) { this.type = type; this.elementType = elementType; this.remaining = remaining; }
	}
	private final DataInputStream input;
	private final Sink sink;
	private final ArrayDeque<Frame> stack = new ArrayDeque<>();
	NbtStreamReader(DataInputStream input, Sink sink) { this.input = input; this.sink = sink; }
	void read() throws IOException {
		if (input.readUnsignedByte() != Tag.TAG_COMPOUND) throw new IOException("Expected checkpoint root compound");
		value(Tag.TAG_COMPOUND, input.readUTF());
		while (!stack.isEmpty()) {
			var frame = stack.peek();
			if (frame.type == Tag.TAG_COMPOUND) {
				int type = input.readUnsignedByte();
				if (type == Tag.TAG_END) end();
				else { validType(type); value(type, input.readUTF()); }
			} else if (frame.remaining == 0) end();
			else { frame.remaining--; value(frame.elementType, null); }
		}
		// 读到压缩流 EOF 才能验证 GZIP 尾部 CRC；根结束本身不代表文件完整。
		if (input.read() != -1) throw new IOException("Trailing data after checkpoint root");
	}
	private void value(int type, String name) throws IOException {
		switch (type) {
			case Tag.TAG_COMPOUND -> start(name, type, Tag.TAG_END, -1);
			case Tag.TAG_LIST -> {
				int element = input.readUnsignedByte(); int length = length();
				if (element != Tag.TAG_END) validType(element);
				else if (length != 0) throw new IOException("Nonempty list without element type");
				start(name, type, element, length);
			}
			case Tag.TAG_BYTE_ARRAY, Tag.TAG_INT_ARRAY, Tag.TAG_LONG_ARRAY -> array(name, type);
			default -> sink.accept(new NbtReadEvent.Scalar(name, switch (type) {
				case Tag.TAG_BYTE -> ByteTag.valueOf(input.readByte());
				case Tag.TAG_SHORT -> ShortTag.valueOf(input.readShort());
				case Tag.TAG_INT -> IntTag.valueOf(input.readInt());
				case Tag.TAG_LONG -> LongTag.valueOf(input.readLong());
				case Tag.TAG_FLOAT -> FloatTag.valueOf(input.readFloat());
				case Tag.TAG_DOUBLE -> DoubleTag.valueOf(input.readDouble());
				case Tag.TAG_STRING -> StringTag.valueOf(input.readUTF());
				default -> throw new IOException("Invalid NBT type: " + type);
			}));
		}
	}
	private void start(String name, int type, int element, int length) throws IOException {
		if (stack.size() >= MAX_DEPTH) throw new IOException("NBT depth exceeds " + MAX_DEPTH);
		stack.push(new Frame(type, element, length)); sink.accept(new NbtReadEvent.Start(name, type, element, length));
	}
	private void end() throws IOException { sink.accept(new NbtReadEvent.End(stack.pop().type)); }
	private void array(String name, int type) throws IOException {
		int length = length(); int width = type == Tag.TAG_BYTE_ARRAY ? 1 : type == Tag.TAG_INT_ARRAY ? 4 : 8;
		sink.accept(new NbtReadEvent.Start(name, type, Tag.TAG_END, length));
		long remaining = (long) length * width;
		while (remaining > 0) {
			byte[] bytes = new byte[(int) Math.min(remaining, ARRAY_CHUNK_BYTES)];
			input.readFully(bytes); sink.accept(NbtReadEvent.ArrayChunk.take(bytes)); remaining -= bytes.length;
		}
		sink.accept(new NbtReadEvent.End(type));
	}
	private int length() throws IOException {
		int length = input.readInt();
		if (length < 0) throw new IOException("Negative NBT collection length");
		return length;
	}
	private static void validType(int type) throws IOException {
		if (type < Tag.TAG_BYTE || type > Tag.TAG_LONG_ARRAY) throw new IOException("Invalid NBT type: " + type);
	}
}
