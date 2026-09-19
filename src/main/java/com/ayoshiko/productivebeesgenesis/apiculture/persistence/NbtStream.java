package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import java.io.DataOutput;
import java.io.IOException;
import net.minecraft.nbt.Tag;

/** 直接输出标准 NBT；集合调用方逐项推进，不建立整棵临时标签树。 */
final class NbtStream {
	private final DataOutput output;
	NbtStream(DataOutput output) { this.output = output; }
	private void header(int type, String name) throws IOException { output.writeByte(type); output.writeUTF(name); }
	void root(int version) throws IOException { compound(""); integer("DataVersion", version); compound("data"); }
	void compound(String name) throws IOException { header(Tag.TAG_COMPOUND, name); }
	void end() throws IOException { output.writeByte(Tag.TAG_END); }
	void list(String name, int size) throws IOException {
		header(Tag.TAG_LIST, name); output.writeByte(Tag.TAG_COMPOUND); output.writeInt(size);
	}
	void tag(String name, Tag value) throws IOException { header(value.getId(), name); value.write(output); }
	void string(String name, String value) throws IOException { header(Tag.TAG_STRING, name); output.writeUTF(value); }
	void integer(String name, int value) throws IOException { header(Tag.TAG_INT, name); output.writeInt(value); }
	void number(String name, long value) throws IOException { header(Tag.TAG_LONG, name); output.writeLong(value); }
	void decimal(String name, double value) throws IOException { header(Tag.TAG_DOUBLE, name); output.writeDouble(value); }
	void bool(String name, boolean value) throws IOException { header(Tag.TAG_BYTE, name); output.writeBoolean(value); }
}
