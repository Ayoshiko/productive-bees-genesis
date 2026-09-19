package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import java.io.DataOutput;
import java.io.IOException;
import java.io.UTFDataFormatException;

/** DataOutputStream 的 UTF 临时数组随字符串增长；这里直接写入已有的定长流缓冲。 */
final class CheckpointDataOutput implements DataOutput {
	private final DataOutput output;
	CheckpointDataOutput(DataOutput output) { this.output = output; }
	@Override public void writeUTF(String value) throws IOException {
		int length = 0;
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i); length += c >= 1 && c <= 127 ? 1 : c <= 2047 ? 2 : 3;
			if (length > 65535) throw new UTFDataFormatException("NBT string exceeds modified UTF limit");
		}
		output.writeShort(length);
		for (int i = 0; i < value.length(); i++) {
			int c = value.charAt(i);
			if (c >= 1 && c <= 127) output.writeByte(c);
			else if (c <= 2047) { output.writeByte(192 | c >> 6); output.writeByte(128 | c & 63); }
			else { output.writeByte(224 | c >> 12); output.writeByte(128 | c >> 6 & 63); output.writeByte(128 | c & 63); }
		}
	}
	@Override public void write(int value) throws IOException { output.write(value); }
	@Override public void write(byte[] bytes) throws IOException { output.write(bytes); }
	@Override public void write(byte[] bytes, int offset, int length) throws IOException { output.write(bytes, offset, length); }
	@Override public void writeBoolean(boolean value) throws IOException { output.writeBoolean(value); }
	@Override public void writeByte(int value) throws IOException { output.writeByte(value); }
	@Override public void writeShort(int value) throws IOException { output.writeShort(value); }
	@Override public void writeChar(int value) throws IOException { output.writeChar(value); }
	@Override public void writeInt(int value) throws IOException { output.writeInt(value); }
	@Override public void writeLong(long value) throws IOException { output.writeLong(value); }
	@Override public void writeFloat(float value) throws IOException { output.writeFloat(value); }
	@Override public void writeDouble(double value) throws IOException { output.writeDouble(value); }
	@Override public void writeBytes(String value) throws IOException { output.writeBytes(value); }
	@Override public void writeChars(String value) throws IOException { output.writeChars(value); }
}
