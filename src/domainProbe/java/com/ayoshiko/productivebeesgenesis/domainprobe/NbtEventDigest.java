package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.persistence.read.NbtReadBatch;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.read.NbtReadEvent;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import net.minecraft.nbt.Tag;

/** 独立复编码接收到的事件并计算摘要；仅用于探针，不创建临时整域 NBT／byte[]。 */
final class NbtEventDigest {
	private final MessageDigest digest;
	private final DataOutputStream output;
	private final byte[] scratch = new byte[8 * 1024];
	NbtEventDigest() throws NoSuchAlgorithmException {
		digest = MessageDigest.getInstance("SHA-256"); output = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest));
	}
	void accept(NbtReadBatch batch) throws IOException {
		for (var event : batch.events()) switch (event) {
			case NbtReadEvent.Start start -> {
				if (start.name() != null) { output.writeByte(start.type()); output.writeUTF(start.name()); }
				if (start.type() == Tag.TAG_LIST) output.writeByte(start.elementType());
				if (start.type() != Tag.TAG_COMPOUND) output.writeInt(start.length());
			}
			case NbtReadEvent.End end -> { if (end.type() == Tag.TAG_COMPOUND) output.writeByte(Tag.TAG_END); }
			case NbtReadEvent.Scalar scalar -> {
				if (scalar.name() != null) { output.writeByte(scalar.value().getId()); output.writeUTF(scalar.name()); }
				scalar.value().write(output);
			}
			case NbtReadEvent.ArrayChunk chunk -> {
				var view = chunk.bytes();
				while (view.hasRemaining()) { int count = Math.min(scratch.length, view.remaining()); view.get(scratch, 0, count); output.write(scratch, 0, count); }
			}
		}
	}
	String finish() { return HexFormat.of().formatHex(digest.digest()); }
}
