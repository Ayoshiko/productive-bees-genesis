package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.production.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.read.CheckpointReadService;
import com.google.gson.JsonObject;
import java.nio.file.*;
import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.server.level.ServerLevel;

/** 两个独立 JVM 之间验证真实蜂记录的付费边界；只写开发目录，不改权威域。 */
public final class BeeRestartProbe {
	private static final List<String> STAGES = List.of("migrated", "partial", "pending", "frozen", "credited", "complete");
	private static BeeWorkExecutor.Context context() {
		return new BeeWorkExecutor.Context(true, true, true, 0, 0, new BeeWorkConditions.Environment(false, false, false, false));
	}
	public static void write(NetworkCheckpoint initial) {
		try {
			var current = initial; var member = initial.ownedMachines().values().iterator().next().claim().member();
			store(current, STAGES.get(0));
			current = work(current, member, 0, 3, 0); store(current, STAGES.get(1));
			current = work(work(current, member, 0, 7, 0), member, 1, 10, 0); store(current, STAGES.get(2));
			current = work(work(current, member, 0, 0, 1), member, 1, 0, 1); store(current, STAGES.get(3));
			current = settle(settle(current, member, 0), member, 1); store(current, STAGES.get(4));
			current = settle(settle(work(work(current, member, 0, 0, 1), member, 1, 0, 1), member, 0), member, 1); store(current, STAGES.get(5));
		} catch (Exception error) { throw new IllegalStateException("Cannot write bee restart fixtures", error); }
	}
	private static void store(NetworkCheckpoint checkpoint, String name) throws Exception {
		CheckpointFiles.write(Path.of("results", "bee-restart", name + ".dat"), new CheckpointPayload.Network(checkpoint, SharedConstants.getCurrentVersion().getDataVersion().getVersion()));
	}
	public static void read(ServerLevel level, Path folder, JsonObject report) throws Exception {
		var codec = NetworkCheckpointCodec.forRegistries(level.registryAccess()); int completed = 0;
		try (var reader = new CheckpointReadService()) {
			for (String name : STAGES) {
				NetworkCheckpoint checkpoint;
				try (var decoder = codec.decoder(reader.tryOpen(folder.resolve(name + ".dat")).orElseThrow())) {
					long deadline = System.nanoTime() + 10_000_000_000L;
					while (decoder.progress().state() == CheckpointDecoder.State.READING || decoder.progress().state() == CheckpointDecoder.State.VALIDATING) {
						require(System.nanoTime() < deadline, "Bee decode timeout"); decoder.step(16, 1_000_000); Thread.yield();
					}
					require(decoder.progress().state() == CheckpointDecoder.State.COMPLETE, decoder.progress().failure()); checkpoint = decoder.checkpoint();
				}
				var owned = checkpoint.ownedMachines().values().iterator().next(); var member = owned.claim().member(); var original = owned.bees();
				long expectedEnergy = completed == 0 ? 10000 : completed == 1 ? 9970 : 9800;
				require(original.energy() == expectedEnergy, "Bee restart restored old energy");
				for (var bee : original.bees()) {
					require(bee.originalSlot().copy().getCompound("entity_data").hasUUID("UUID"), "Bee identity missing");
					checkpoint = workIfPending(checkpoint, member, bee.slot()); checkpoint = settle(checkpoint, member, bee.slot());
				}
				var after = checkpoint.ownedMachines().get(member);
				require(after.bees().energy() == expectedEnergy && after.bees().drained(), "Paid recovery charged again or left work behind");
				long expectedOutput = completed < 2 ? 0 : 10;
				require(checkpoint.ledger().balances().getOrDefault(original.bee(0).plan().output(), ProductAmount.ZERO).equals(ProductAmount.of(expectedOutput)), "Bee restart quantity mismatch at " + name);
				require(after.returnImage().copy().getLong("energy") == expectedEnergy, "Return image used pre-production FE");
				completed++;
			}
		}
		report.addProperty("beeCheckpointCrossJvmPaidBoundaries", completed);
	}
	private static NetworkCheckpoint workIfPending(NetworkCheckpoint checkpoint, UUID member, int slot) {
		return checkpoint.ownedMachines().get(member).bees().bee(slot).pendingCycles() == 0 ? checkpoint : work(checkpoint, member, slot, 0, 8);
	}
	private static NetworkCheckpoint work(NetworkCheckpoint checkpoint, UUID member, int slot, int ticks, int budget) {
		var record = checkpoint.ownedMachines().get(member); var bee = record.bees().bee(slot);
		var result = BeeWorkExecutor.advance(record.bees(), slot, bee.revision(), context(), ticks, budget);
		require(result.status() == BeeWorkExecutor.Status.READY, "Bee fixture advance failed");
		return checkpoint.withOwnership(record.withBees(result.candidate()));
	}
	private static NetworkCheckpoint settle(NetworkCheckpoint checkpoint, UUID member, int slot) {
		long revision = checkpoint.ownedMachines().get(member).bees().bee(slot).revision();
		var after = checkpoint.settleBee(member, slot, revision);
		require(after == after.settleBee(member, slot, revision), "Repeated bee receipt duplicated outputs"); return after;
	}
	private static void require(boolean value, String reason) { if (!value) throw new IllegalStateException(reason); }
	private BeeRestartProbe() { }
}
