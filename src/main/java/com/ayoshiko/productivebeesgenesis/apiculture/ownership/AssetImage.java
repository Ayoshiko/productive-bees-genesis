package com.ayoshiko.productivebeesgenesis.apiculture.ownership;

import java.io.*;
import java.security.*;
import java.util.HexFormat;
import net.minecraft.nbt.*;

/** 单台物理机的封存映像；按字段排序流式指纹，不额外构建整份 SNBT 字符串。 */
public final class AssetImage {
	public static final AssetImage EMPTY = new AssetImage(new CompoundTag());
	private final CompoundTag tag;
	private final String fingerprint;
	public AssetImage(CompoundTag tag) {
		this.tag = tag.copy();
		try {
			var digest = MessageDigest.getInstance("SHA-256");
			canonical(this.tag, new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest)), 0);
			fingerprint = HexFormat.of().formatHex(digest.digest());
		} catch (IOException | NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
	}
	public CompoundTag copy() { return tag.copy(); }
	public boolean isEmpty() { return tag.isEmpty(); }
	public String fingerprint() { return fingerprint; }
	private static void canonical(Tag tag, DataOutput output, int depth) throws IOException {
		if (depth > 512) throw new IllegalArgumentException("Asset nesting exceeds NBT limit"); output.writeByte(tag.getId());
		if (tag instanceof CompoundTag compound) {
			var keys = compound.getAllKeys().stream().sorted().toList(); output.writeInt(keys.size());
			for (String key : keys) { output.writeUTF(key); canonical(compound.get(key), output, depth + 1); }
		} else if (tag instanceof ListTag list) {
			output.writeInt(list.size()); for (Tag value : list) canonical(value, output, depth + 1);
		} else tag.write(output);
	}
	@Override public boolean equals(Object other) { return this == other || other instanceof AssetImage image && fingerprint.equals(image.fingerprint) && tag.equals(image.tag); }
	@Override public int hashCode() { return fingerprint.hashCode(); }
}
