package com.ayoshiko.productivebeesgenesis.apiculture.storage;

import java.io.*;
import java.util.Base64;
import net.minecraft.nbt.*;

/** 仅用于图标的有界组件快照；超限省略显示，不改变完整资产键。 */
public final class ProductIconPreview {
	public static final int MAX_BYTES = 512, MAX_TEXT = 684;
	private ProductIconPreview() { }
	static String capture(CompoundTag components) {
		if (!bounded(components, 0, new int[1])) return "";
		var bytes = new ByteArrayOutputStream(MAX_BYTES);
		try (var output = new DataOutputStream(new OutputStream() {
			@Override public void write(int value) throws IOException {
				if (bytes.size() >= MAX_BYTES) throw new Limit();
				bytes.write(value);
			}
		})) {
			NbtIo.write(components, output);
			return Base64.getEncoder().encodeToString(bytes.toByteArray());
		} catch (Limit overflow) { return ""; }
		catch (IOException failure) { throw new UncheckedIOException(failure); }
	}
	private static boolean bounded(Tag tag, int depth, int[] nodes) {
		if (depth > 8 || ++nodes[0] > 64) return false;
		if (tag instanceof StringTag text) return text.getAsString().length() <= 256;
		if (tag instanceof CompoundTag compound) {
			if (compound.size() > 64) return false;
			for (String key : compound.getAllKeys())
				if (key.length() > 128 || !bounded(compound.get(key), depth + 1, nodes)) return false;
		} else if (tag instanceof CollectionTag<?> collection) {
			if (collection.size() > 64) return false;
			for (Tag child : collection) if (!bounded(child, depth + 1, nodes)) return false;
		}
		return true;
	}
	public static CompoundTag decode(String encoded) {
		if (encoded.isEmpty() || encoded.length() > MAX_TEXT) throw new IllegalArgumentException("Missing or oversized icon");
		byte[] bytes = Base64.getDecoder().decode(encoded);
		if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Oversized icon");
		try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
			CompoundTag tag = NbtIo.read(input, NbtAccounter.create(16 * 1024));
			if (tag == null || input.available() != 0 || !bounded(tag, 0, new int[1]))
				throw new IllegalArgumentException("Invalid icon components");
			return tag;
		} catch (IOException failure) { throw new IllegalArgumentException("Invalid icon NBT", failure); }
	}
	private static final class Limit extends IOException { }
}
