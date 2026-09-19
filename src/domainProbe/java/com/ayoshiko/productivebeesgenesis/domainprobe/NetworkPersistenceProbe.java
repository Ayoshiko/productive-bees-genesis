package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.*;
import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiculture.policy.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.google.gson.JsonObject;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.DimensionDataStorage;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

final class NetworkPersistenceProbe {
	private static NetworkCheckpoint shutdownCheckpoint;
	private static NetworkSavedData managedDomain;
	private static NetworkOpenHandle managedOpening;
	private static NetworkCheckpoint firstCheckpoint;
	private static int saveRequestedTick;
	private NetworkPersistenceProbe() { }
	static void verify(MinecraftServer server, JsonObject report) throws Exception {
		var registries = server.registryAccess();
		var raw = ProductKeyCodec.item(new ItemStack(Items.GOLD_INGOT), registries);
		var output = ProductKeyCodec.item(new ItemStack(Items.GOLD_NUGGET), registries);
		var policySnapshot = new ProductPolicySnapshot(3, List.of(
				new AllowedProductDescriptor(raw, "probe", "gold"), new AllowedProductDescriptor(output, "probe", "nugget")),
				List.of(new DynamicProductRule("probe:discovery", output.kind(), output.id(), true, output::equals)));
		var policy = new ProductPolicyRegistry(policySnapshot);
		require(policy.recordVerifiedProduction("probe:discovery", output, 3), "Discovery was not accepted");
		var ledger = new ProductLedger(policy, 8); var amount = ProductAmount.of(BigInteger.ONE.shiftLeft(256));
		ledger.insert(raw, amount, ProductLedger.Action.EXECUTE);
		var pending = ledger.prepare(Map.of(raw, ProductAmount.of(7)), Map.of(output, ProductAmount.of(63)), 3); ledger.markPaid(pending);
		var staging = new TransferStaging(ledger, 8, 1000);
		var uncertain = staging.transfer("fault-provider", raw, 50, TransferStaging.Direction.EXPORT, offered -> { throw new IllegalStateException("Injected unknown result"); });
		var identity = new NetworkIdentity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0, new Origin("minecraft:overworld", 1, 64, 1));
		var reserve = new ReservePolicy(ReservePolicy.Scope.LOCAL_PROCESSING, ReservePolicy.Layer.NONE, ReservePolicy.Layer.NONE);
		var rule = new ProcessingRule("probe:goal", 1, true, 0, 3, 1,
				new ProcessingRule.Goal(output, ProductAmount.of(1), ProductAmount.of(64)), reserve);
		var savedScheduler = new SchedulerCheckpoint(List.of(rule), ProcessingRuleScheduler.Mode.FAIR, Map.of(rule.id(), true), rule.id(), 1);
		var scheduler = ProcessingRuleScheduler.restore(ledger, new ProcessingRuleIndex(List.of(rule), List.of(), Map.of()), savedScheduler);
		var source = new NetworkCheckpointSource(identity, ledger, policy, staging, scheduler);
		var work = new WorkCapacity(new WorkKey(WorkKey.Kind.CENTRIFUGE_RECIPE, "probe:gold", 3, "default"), 1, 30, 40, 500, 1, 0, Map.of());
		var member = new MemberCapabilitySnapshot(UUID.randomUUID(), 1, "probe:member", new Origin("minecraft:overworld", 2, 64, 1),
				MemberCapabilitySnapshot.Availability.OFFLINE, 0, 1, List.of(work));
		source.putMember(member); source.putLane(new VirtualLaneState(member.memberId(), 1, 0, work, 7));
		var checkpoint = source.capture(1);
		// 冻结后继续结算：保存的余额和 PAID 明细必须仍属于冻结前的同一边界。
		require(ledger.commit(pending), "Live work did not settle after freeze");
		require(checkpoint.ledger().transactions().getFirst().state() == LedgerTransaction.State.PAID
				&& !checkpoint.ledger().balances().containsKey(output), "Frozen checkpoint observed later settlement");
		staging.resolve(uncertain, 30); policy.replace(new ProductPolicySnapshot(4, List.of(), List.of()));
		scheduler.replace(new ProcessingRuleIndex(List.of(), List.of(), Map.of()));
		source.removeLane(member.memberId(), 0); source.removeMember(member.memberId());
		var later = source.capture(2);
		require(later.transfers().isEmpty() && later.discoveries().isEmpty() && later.members().isEmpty() && later.lanes().isEmpty()
				&& later.scheduler().rules().isEmpty(), "Live metadata did not advance");
		require(checkpoint.transfers().size() == 1 && checkpoint.discoveries().size() == 1 && checkpoint.members().size() == 1
				&& checkpoint.lanes().getFirst().progress() == 7 && checkpoint.scheduler().equals(savedScheduler), "Frozen metadata observed later mutations");
		managedOpening = NetworkPersistence.directory(server).create(identity); firstCheckpoint = checkpoint;
		saveRequestedTick = server.getTickCount(); shutdownCheckpoint = later;
		Path folder = Path.of("p2-persistence"); Files.createDirectories(folder);
		try (var writer = Executors.newSingleThreadExecutor()) {
			var nativeStorage = new DimensionDataStorage(folder.toFile(), DataFixers.getDataFixer(), registries);
			try (var directory = new NetworkDirectory(folder, registries, writer, nativeStorage)) {
			var domain = ProbeOpenAwait.await(directory, directory.create(identity)); domain.publish(checkpoint); nativeStorage.save(); directory.flush();
			require(domain.persistedRevision() == 1 && !domain.isDirty(), "No durable-save receipt");
			try (var fresh = new NetworkDirectory(folder, registries, writer, new DimensionDataStorage(folder.toFile(), DataFixers.getDataFixer(), registries))) {
			var restored = ProbeOpenAwait.await(fresh, fresh.loadExisting(identity)); require(checkpoint.equals(restored.checkpoint()), "Checkpoint round-trip lost a domain");
			var recoveredPolicy = new ProductPolicyRegistry(policySnapshot);
			recoveredPolicy.restoreDiscoveries(3, restored.checkpoint().discoveries());
			var recoveredLedger = ProductLedger.restore(recoveredPolicy, 8, restored.checkpoint().ledger());
			var recoveredStaging = TransferStaging.restore(recoveredLedger, 8, 1000, restored.checkpoint().transfers());
			require(!recoveredStaging.settle(recoveredStaging.pending(uncertain.view().id())), "Unknown transfer escaped quarantine");
			var job = recoveredLedger.pending(pending.id()); require(recoveredLedger.commit(job) && recoveredLedger.commit(job), "Paid work did not settle idempotently");
			require(recoveredLedger.available(output).equals(ProductAmount.of(63)), "Paid output duplicated");
			require(recoveredLedger.available(raw).equals(amount.subtract(ProductAmount.of(57))), "Reservation or staging duplicated");
			var codec = NetworkCheckpointCodec.forRegistries(registries);
			var broken = NetworkCheckpointCodec.encode(checkpoint);
			broken.getList("balances", 10).getCompound(0).getCompound("key").putString("id", "missing:gone");
			boolean rejected = false; try { codec.decode(broken); } catch (IllegalArgumentException error) { rejected = true; }
			require(rejected, "Missing registry item was accepted");
			var unknown = new ProductKey(ProductKey.Kind.ITEM, ResourceLocation.parse("minecraft:gold_ingot"), new CompoundTag());
			CompoundTag unknownComponents = unknown.components(); unknownComponents.putString("missing:component", "lost");
			rejected = false; try { ProductKeyCodec.validatePersisted(new ProductKey(unknown.kind(), unknown.id(), unknownComponents), registries); }
			catch (IllegalArgumentException | IllegalStateException error) { rejected = true; }
			require(rejected, "Unknown component was silently stripped");
			}
			}
		}
		report.addProperty("networkCheckpointRoundTrip", true); report.addProperty("pendingWorkAndUnknownTransferRecovery", true);
		report.addProperty("strictRegistryAndComponentRead", true); report.addProperty("acknowledgedNativeSavedData", true);
		report.addProperty("frozenCheckpointIsolatedFromLiveSettlement", true);
		report.addProperty("fullDomainCaptureIsolatedFromMetadataChanges", true);
		CheckpointReadProbe.start(folder.resolve("productivebeesgenesis_network_" + identity.networkId() + ".dat"), checkpoint, registries);
	}
	static boolean advance(MinecraftServer server, JsonObject report) throws Exception {
		if (managedDomain == null) {
			require(server.getTickCount() - saveRequestedTick < 200, "Managed create did not finish");
			if (managedOpening.pending()) return false;
			managedDomain = managedOpening.ready(); managedDomain.publish(firstCheckpoint);
			server.overworld().getDataStorage().save(); managedDomain.publish(shutdownCheckpoint); server.overworld().getDataStorage().save();
			report.addProperty("tickDrivenCreateAfterBothReceipts", true);
		}
		var status = NetworkPersistence.directory(server).saveStatus();
		require(status.activeSnapshots() <= 1 && status.reservedBufferBytes() <= 32 * 1024, "Unbounded checkpoint buffering");
		require(managedDomain.lastFailure().isEmpty(), "Managed save failed: " + managedDomain.lastFailure());
		require(server.getTickCount() - saveRequestedTick < 200, "Managed save/read did not finish within 200 real ticks");
		if (managedDomain.persistedRevision() >= 2 && !report.has("tickDrivenStreamSave")) {
			report.addProperty("tickDrivenStreamSave", true); report.addProperty("ticksUntilSaveReceipt", server.getTickCount() - saveRequestedTick);
			report.addProperty("maxActiveSaveSnapshots", 1); report.addProperty("streamBufferBudgetBytes", 32 * 1024);
		}
		boolean readComplete = CheckpointReadProbe.advance(report);
		if (managedDomain.persistedRevision() < 2 || !readComplete) return false;
		var previous = shutdownCheckpoint;
		shutdownCheckpoint = new NetworkCheckpoint(previous.identity(), 3, previous.policyRevision(), previous.ledger(), previous.transfers(),
				previous.discoveries(), previous.members(), previous.lanes(), previous.scheduler());
		managedDomain.publish(shutdownCheckpoint); // 不再触发世界保存，停服必须主动收尾最后一版。
		return true;
	}
	static void verifyShutdown(MinecraftServer server, JsonObject report) throws Exception {
		require(shutdownCheckpoint != null, "Shutdown fixture was not initialized");
		Path file = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data")
				.resolve("productivebeesgenesis_network_" + shutdownCheckpoint.identity().networkId() + ".dat");
		var root = net.minecraft.nbt.NbtIo.readCompressed(file, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
		var restored = NetworkCheckpointCodec.forRegistries(server.registryAccess()).decode(root.getCompound("data"));
		require(shutdownCheckpoint.equals(restored), "Normal shutdown did not persist the latest network checkpoint");
		report.addProperty("normalShutdownCheckpointSaved", true); shutdownCheckpoint = null; managedDomain = null;
	}
}
