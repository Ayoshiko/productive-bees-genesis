package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.zip.GZIPOutputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;

/** 工作线程只接收编码后的字节；原子替换成功前不触碰已有权威文件。 */
final class CheckpointFiles {
	private CheckpointFiles() { }
	static byte[] encode(CompoundTag data) throws IOException {
		var root = new CompoundTag(); root.put("data", data); NbtUtils.addCurrentDataVersion(root);
		var bytes = new ByteArrayOutputStream();
		try (var output = new DataOutputStream(bytes)) { NbtIo.write(root, output); }
		return bytes.toByteArray();
	}
	static void write(Path file, byte[] encoded) throws IOException {
		Path target = file.toAbsolutePath().normalize();
		Files.createDirectories(target.getParent());
		Path temporary = target.resolveSibling(target.getFileName() + ".pbg-pending");
		try (var output = new GZIPOutputStream(Files.newOutputStream(temporary))) { output.write(encoded); }
		try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
		Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
	}
}
