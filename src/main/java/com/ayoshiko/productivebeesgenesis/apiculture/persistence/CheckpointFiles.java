package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.zip.GZIPOutputStream;

/** 单任务占用固定流缓冲；同步写流自然背压，原子替换成功前不触碰已有权威文件。 */
final class CheckpointFiles {
	static final int BUFFER_BYTES = 16 * 1024;
	static final int RESERVED_BYTES = BUFFER_BYTES * 2;
	private CheckpointFiles() { }
	static void write(Path file, CheckpointPayload payload) throws IOException {
		Path target = file.toAbsolutePath().normalize();
		Files.createDirectories(target.getParent());
		Path temporary = target.resolveSibling(target.getFileName() + ".pbg-pending");
		try (var fileOutput = Files.newOutputStream(temporary);
				var compressed = new GZIPOutputStream(fileOutput, BUFFER_BYTES);
				var buffered = new BufferedOutputStream(compressed, BUFFER_BYTES);
				var output = new DataOutputStream(buffered)) {
			payload.write(new CheckpointDataOutput(output));
		}
		try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
		Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
	}
}
