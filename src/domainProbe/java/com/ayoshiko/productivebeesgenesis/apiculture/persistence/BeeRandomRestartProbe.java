package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.production.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.read.CheckpointReadService;
import com.google.gson.JsonObject;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;

/** 从真实铁蜂记录派生隔离领域夹具；不放开实物升级准入，不改运行中的权威域。 */
public final class BeeRandomRestartProbe {
	private static final int CYCLES = 129;
	private static final List<String> STAGES = List.of("partial", "pending", "sampled", "credited", "complete");
	private static BeeWorkExecutor.Context context() {
		return new BeeWorkExecutor.Context(true, true, true, 0, 0, new BeeWorkConditions.Environment(false, false, false, false));
	}
	public static void write(NetworkCheckpoint initial) throws Exception {
		var tag = NetworkCheckpointCodec.encode(initial);
		var rawBees = tag.getList("ownership", 10).getCompound(0).getCompound("bees").getList("bees", 10);
		for (var raw : rawBees) {
			var bee = (CompoundTag) raw;
			require(bee.getInt("progress") == 0 && bee.getLong("pending") == 0, "Random fixture requires idle initial bees");
			bee.getCompound("plan").putFloat("multiplier", 2.5f);
			bee.getCompound("plan").putInt("ticks", 5); bee.getCompound("plan").putLong("cost", 1);
		}
		var current = new NetworkCheckpointCodec(key -> { }).decode(tag);
		var member = current.ownedMachines().values().iterator().next().claim().member();
		current = work(current, member, 0, 2, 0); current = work(current, member, 1, 2, 0); store(current, "partial");
		current = work(current, member, 0, CYCLES * 5 - 2, 0);
		current = work(current, member, 1, CYCLES * 5 - 2, 0); store(current, "pending");
		for (int slot = 0; slot < 2; slot++) current = work(current, member, slot, 0, 17);
		store(current, "sampled");
		for (int slot = 0; slot < 2; slot++) current = settle(current, member, slot);
		store(current, "credited");
		current = finish(current, member); store(current, "complete");
		var seven = NetworkCheckpointCodec.encode(current); seven.putInt("schema", 7);
		var sevenBees = seven.getList("ownership", 10).getCompound(0).getCompound("bees"); sevenBees.putInt("samplingVersion", 1);
		for (var raw : sevenBees.getList("bees", 10)) ((CompoundTag) raw).getCompound("plan").remove("sourceOutput");
		var sevenRoot = new CompoundTag(); sevenRoot.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion()); sevenRoot.put("data", seven);
		NbtIo.writeCompressed(sevenRoot, Path.of("results", "bee-restart", "random", "legacy-seven.dat"));
		// schema 6 文件由当前正式 writer 的等价旧形状生成，只包含原有倍率 1 工作。
		var legacy = NetworkCheckpointCodec.encode(initial); legacy.putInt("schema", 6);
		var state = legacy.getList("ownership", 10).getCompound(0).getCompound("bees");
		state.remove("samplingVersion");
		for (var raw : state.getList("bees", 10)) {
			var bee = (CompoundTag) raw; bee.remove("seed"); bee.remove("cursor"); bee.getCompound("plan").remove("multiplier"); bee.getCompound("plan").remove("sourceOutput");
		}
		var root = new CompoundTag(); root.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion()); root.put("data", legacy);
		NbtIo.writeCompressed(root, Path.of("results", "bee-restart", "random", "legacy.dat"));
		var metadata = new JsonObject(); metadata.addProperty("producerPid", ProcessHandle.current().pid());
		Files.writeString(Path.of("results", "bee-restart", "random", "writer.json"), metadata.toString());
	}
	private static void store(NetworkCheckpoint current, String stage) throws Exception {
		CheckpointFiles.write(Path.of("results", "bee-restart", "random", stage + ".dat"),
				new CheckpointPayload.Network(current, SharedConstants.getCurrentVersion().getDataVersion().getVersion()));
	}
	public static void read(ServerLevel level, Path source, JsonObject report) throws Exception {
		readProductivity(level, source, report);
		readBlock(level, source, report);
		var folder = source.resolve("random"); var codec = NetworkCheckpointCodec.forRegistries(level.registryAccess());
		long producer = com.google.gson.JsonParser.parseString(Files.readString(folder.resolve("writer.json"))).getAsJsonObject().get("producerPid").getAsLong();
		require(producer != ProcessHandle.current().pid(), "Random reader reused the writer JVM");
		report.addProperty("beeRandomProducerPid", producer);
		report.addProperty("beeRandomReaderPid", ProcessHandle.current().pid());
		int verified = 0; BigInteger expected = null;
		for (String name : STAGES) {
			var path = folder.resolve(name + ".dat"); var bytes = Files.readAllBytes(path);
			var current = decode(codec, path); var record = current.ownedMachines().values().iterator().next();
			var member = record.claim().member(); var bees = record.bees(); long energy = bees.energy();
			BigInteger oracle = BigInteger.ZERO;
			for (var bee : bees.bees()) {
				require(bee.plan().productionMultiplier() == 2.5f, "Restored old bee multiplier");
				long cursor = switch (name) { case "partial", "pending" -> 0; case "sampled", "credited" -> 17; default -> CYCLES; };
				require(bee.random().cursor() == cursor, "Restored wrong random cursor at " + name);
				var random = new SplittableRandom(bee.random().seed()); long rolls = 0;
				for (int i = 0; i < CYCLES; i++) rolls += 2 + (random.nextDouble() < 0.5 ? 1 : 0);
				// 此夹具为单件铁蜜脾，原生基因 0／3 分别得到每轮 1／4 件。
				require(bee.plan().count() == 1 && (bee.plan().productivity() == 0 || bee.plan().productivity() == 3), "Unexpected random oracle fixture");
				oracle = oracle.add(BigInteger.valueOf(rolls * (1 + bee.plan().productivity())));
			}
			if (expected == null) expected = oracle; else require(expected.equals(oracle), "Seed changed across paid boundaries");
			if (name.equals("partial")) {
				for (int slot = 0; slot < 2; slot++) current = work(current, member, slot, CYCLES * 5 - 2, 0);
				energy -= 2 * (CYCLES * 5 - 2);
			}
			current = finish(current, member);
			require(current.ownedMachines().get(member).bees().energy() == energy, "Random recovery charged paid cycles again");
			var key = bees.bee(0).plan().output();
			require(current.ledger().balances().get(key).exact().equals(oracle), "Random recovery differed from independent JDK oracle at " + name);
			require(Arrays.equals(bytes, Files.readAllBytes(path)), "Reader changed source checkpoint");
			verified++;
		}
		var legacy = decode(codec, folder.resolve("legacy.dat"));
		for (var record : legacy.ownedMachines().values()) for (var bee : record.bees().bees())
			require(bee.plan().productionMultiplier() == 1 && bee.random().equals(BeeCycleRandom.initial(bee.id())), "Legacy bee stream migration changed");
		var seven = decode(codec, folder.resolve("legacy-seven.dat"));
		for (var record : seven.ownedMachines().values()) for (var bee : record.bees().bees())
			require(bee.plan().productionMultiplier() == 2.5f && bee.random().cursor() == CYCLES && bee.plan().sourceOutput().equals(bee.plan().output()), "Schema seven lost output or random state");
		require(seven.equals(decode(codec, folder.resolve("complete.dat"))), "Schema seven migration changed completed assets");
		report.addProperty("beeRandomLegacySchemaSeven", true);
		report.addProperty("beeRandomCrossJvmBoundaries", verified);
		report.addProperty("beeRandomPartitionReplayAndOracle", true);
		report.addProperty("beeRandomLegacySchemaSix", true);
	}
	public static void readProductivity(ServerLevel level, Path source, JsonObject report) throws Exception {
		Path path = source.resolveSibling("apiary-productivity.dat"), metadataPath = source.resolveSibling("apiary-productivity.json");
		if (!Files.exists(path)) return;
		var metadata = com.google.gson.JsonParser.parseString(Files.readString(metadataPath)).getAsJsonObject();
		require(metadata.get("writerPid").getAsLong() != ProcessHandle.current().pid(), "Productivity restart reused writer");
		var bytes = Files.readAllBytes(path); var codec = NetworkCheckpointCodec.forRegistries(level.registryAccess());
		var checkpoint = codec.decode(NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap()).getCompound("data"));
		try (var reader = new CheckpointReadService(); var decoder = codec.decoder(reader.tryOpen(path).orElseThrow())) {
			long deadline = System.nanoTime() + 10_000_000_000L;
			while (decoder.progress().state() == CheckpointDecoder.State.READING || decoder.progress().state() == CheckpointDecoder.State.VALIDATING) {
				require(System.nanoTime() < deadline, "Productivity decoder timeout"); decoder.step(8, 1_000_000); Thread.yield();
			}
			require(decoder.progress().state() == CheckpointDecoder.State.COMPLETE && checkpoint.equals(decoder.checkpoint()), "Productivity decoders disagree");
		}
		var member = UUID.fromString(metadata.get("member").getAsString()); var record = checkpoint.ownedMachines().get(member); var bee = record.bees().bee(0);
		require(bee.pendingCycles() == 1 && bee.plan().productionMultiplier() == 2.5f
				&& com.ayoshiko.productivebeesgenesis.apiculture.compat.PbApiaryUpgradeCounts.read(record.assets().copy().getCompound("extra")).get(com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType.PRODUCTIVITY_2) == 2, "Saved old cycle/current upgrades changed");
		var result = BeeWorkExecutor.advance(record.bees(), 0, bee.revision(), new BeeWorkExecutor.Context(true, false, false, 0, 0, null), 0, 1, checkpoint.energy().stored());
		var next = checkpoint.applyBeeWork(member, result); var frozen = next.ownedMachines().get(member).bees().bee(0);
		require(frozen.frozen().exact().longValueExact() == productivityExpected(bee) && next.energy().equals(checkpoint.energy()), "Restored productivity work rerolled or recharged");
		require(Arrays.equals(bytes, Files.readAllBytes(path)), "Productivity reader changed original file");
		report.addProperty("apiaryProductivityRestart", true);
		report.addProperty("apiaryProductivityWriterPid", metadata.get("writerPid").getAsLong());
	}
	private static void readBlock(ServerLevel level, Path source, JsonObject report) throws Exception {
		var folder = source.getParent(); var metadataFile = folder.resolve("apiary-block.json");
		if (!Files.exists(metadataFile)) return;
		var metadata = com.google.gson.JsonParser.parseString(Files.readString(metadataFile)).getAsJsonObject();
		long writer = metadata.get("writerPid").getAsLong(); require(writer != ProcessHandle.current().pid(), "Block reader reused writer JVM");
		var member = UUID.fromString(metadata.get("member").getAsString()); var codec = NetworkCheckpointCodec.forRegistries(level.registryAccess());
		for (String phase : List.of("install", "remove")) {
			var path = folder.resolve("apiary-block-" + phase + ".dat"); var bytes = Files.readAllBytes(path); var before = decode(codec, path);
			var record = before.ownedMachines().get(member); var bee = record.bees().bee(0); boolean installed = phase.equals("install");
			var counts = com.ayoshiko.productivebeesgenesis.apiculture.compat.PbApiaryUpgradeCounts.read(record.assets().copy().getCompound("extra"));
			require(counts.getOrDefault(com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType.BLOCK, 0) == (installed ? 1 : 0), "Block upgrade image changed");
			require(bee.pendingCycles() == 1 && bee.frozen().isZero() && bee.plan().output().equals(bee.plan().sourceOutput()) == installed, "Paid block key changed on restart");
			var result = BeeWorkExecutor.advance(record.bees(), 0, bee.revision(), new BeeWorkExecutor.Context(true, false, false, 0, 0, null), 0, 1, before.energy().stored());
			var sampled = before.applyBeeWork(member, result); var frozen = sampled.ownedMachines().get(member).bees().bee(0);
			require(frozen.frozen().equals(ProductAmount.of(1)) && frozen.random().cursor() == bee.random().cursor() + 1 && sampled.energy().equals(before.energy()), "Block recovery recharged or resampled");
			var next = sampled.settleBee(member, 0, frozen.revision()); var expected = new HashMap<>(before.ledger().balances()); expected.merge(bee.plan().output(), ProductAmount.of(1), ProductAmount::add);
			require(next.ledger().balances().equals(expected) && next.settleBee(member, 0, frozen.revision()) == next, "Restored block settled to another key or twice");
			require(Arrays.equals(bytes, Files.readAllBytes(path)), "Block recovery rewrote source file");
		}
		report.addProperty("apiaryBlockRestart", true); report.addProperty("apiaryBlockWriterPid", writer);
	}
	private static long productivityExpected(BeeRecord bee) {
		var random = new SplittableRandom(bee.random().seed());
		for (long i = 0; i < bee.random().cursor(); i++) random.nextDouble();
		require(bee.plan().count() == 1 && bee.plan().productivity() == 0, "Unexpected productivity restart oracle bee");
		return 2 + (random.nextDouble() < 0.5 ? 1 : 0);
	}
	private static NetworkCheckpoint decode(NetworkCheckpointCodec codec, Path file) throws Exception {
		var full = codec.decode(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompound("data"));
		try (var reader = new CheckpointReadService(); var decoder = codec.decoder(reader.tryOpen(file).orElseThrow())) {
			long deadline = System.nanoTime() + 10_000_000_000L;
			while (decoder.progress().state() == CheckpointDecoder.State.READING || decoder.progress().state() == CheckpointDecoder.State.VALIDATING) {
				require(System.nanoTime() < deadline, "Random checkpoint read timeout"); decoder.step(8, 1_000_000); Thread.yield();
			}
			require(decoder.progress().state() == CheckpointDecoder.State.COMPLETE, decoder.progress().failure());
			require(full.equals(decoder.checkpoint()), "Random checkpoint decoders differ"); return decoder.checkpoint();
		}
	}
	private static NetworkCheckpoint finish(NetworkCheckpoint current, UUID member) {
		for (int slot = 0; slot < 2; slot++) {
			while (current.ownedMachines().get(member).bees().bee(slot).pendingCycles() > 0) {
				var before = current.ownedMachines().get(member).bees().bee(slot).random().cursor();
				current = work(current, member, slot, 0, 7);
				require(current.ownedMachines().get(member).bees().bee(slot).random().cursor() - before <= 7, "Exceeded sampling budget");
				current = settle(current, member, slot);
			}
			current = settle(current, member, slot);
		}
		return current;
	}
	private static NetworkCheckpoint work(NetworkCheckpoint current, UUID member, int slot, int ticks, int budget) {
		var state = current.ownedMachines().get(member).bees(); var bee = state.bee(slot);
		var result = BeeWorkExecutor.advance(state, slot, bee.revision(), context(), ticks, budget);
		var retry = BeeWorkExecutor.advance(state, slot, bee.revision(), context(), ticks, budget);
		require(result.status() == BeeWorkExecutor.Status.READY && result.candidate().equals(retry.candidate()), "Discarded candidate changed random result");
		var next = current.applyBeeWork(member, result);
		require(next != current && next.applyBeeWork(member, result) == next, "Random proof replay changed authority"); return next;
	}
	private static NetworkCheckpoint settle(NetworkCheckpoint current, UUID member, int slot) {
		long revision = current.ownedMachines().get(member).bees().bee(slot).revision();
		var next = current.settleBee(member, slot, revision);
		require(next.settleBee(member, slot, revision) == next, "Random result settled twice"); return next;
	}
	private static void require(boolean value, String reason) { if (!value) throw new IllegalStateException(reason); }
	private BeeRandomRestartProbe() { }
}
