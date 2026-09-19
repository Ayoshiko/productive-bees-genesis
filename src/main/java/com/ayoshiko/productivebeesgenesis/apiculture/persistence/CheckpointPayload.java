package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import java.io.DataOutput;
import java.io.IOException;

/** 封闭的后台输入类型；不能用捕获可变服务器对象的任意回调替代。 */
sealed interface CheckpointPayload {
	long revision();
	void write(DataOutput output) throws IOException;
	record Network(NetworkCheckpoint checkpoint, int dataVersion) implements CheckpointPayload {
		@Override public long revision() { return checkpoint.revision(); }
		@Override public void write(DataOutput output) throws IOException { NetworkCheckpointStream.write(checkpoint, dataVersion, output); }
	}
	record Directory(NetworkDirectoryData.Snapshot checkpoint, int dataVersion) implements CheckpointPayload {
		@Override public long revision() { return checkpoint.revision(); }
		@Override public void write(DataOutput output) throws IOException {
			var stream = new NbtStream(output); stream.root(dataVersion);
			stream.integer("schema", 2); stream.number("revision", revision());
			stream.list("networks", checkpoint.identities().size());
			for (var identity : checkpoint.identities().values()) NetworkCheckpointCodec.identity(identity).write(output);
			stream.list("claims", checkpoint.claims().size());
			for (var claim : checkpoint.claims().values()) OwnershipRecordCodec.claim(claim).write(output);
			stream.end(); stream.end();
		}
	}
}
