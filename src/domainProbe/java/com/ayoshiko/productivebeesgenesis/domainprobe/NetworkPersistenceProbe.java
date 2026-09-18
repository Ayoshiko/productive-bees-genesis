package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiculture.policy.SchedulerCheckpoint;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.google.gson.JsonObject;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
	private NetworkPersistenceProbe() { }
	static void verify(MinecraftServer server, JsonObject report) throws Exception {
		var registries = server.registryAccess();
		var raw = ProductKeyCodec.item(new ItemStack(Items.GOLD_INGOT), registries);
		var output = ProductKeyCodec.item(new ItemStack(Items.GOLD_NUGGET), registries);
		var policy = new ProductPolicyRegistry(new ProductPolicySnapshot(3, List.of(
				new AllowedProductDescriptor(raw, "probe", "gold"), new AllowedProductDescriptor(output, "probe", "nugget")), List.of()));
		var ledger = new ProductLedger(policy, 8); var amount = ProductAmount.of(BigInteger.ONE.shiftLeft(256));
		ledger.insert(raw, amount, ProductLedger.Action.EXECUTE);
		var pending = ledger.prepare(Map.of(raw, ProductAmount.of(7)), Map.of(output, ProductAmount.of(63)), 3); ledger.markPaid(pending);
		var staging = new TransferStaging(ledger, 8, 1000);
		var uncertain = staging.transfer("fault-provider", raw, 50, TransferStaging.Direction.EXPORT, offered -> { throw new IllegalStateException("Injected unknown result"); });
		var identity = new NetworkIdentity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0, new Origin("minecraft:overworld", 1, 64, 1));
		var checkpoint = new NetworkCheckpoint(identity, 1, 3, ledger.checkpoint(), staging.snapshot(), Set.of(), List.of(), List.of(), SchedulerCheckpoint.EMPTY);
		var managedDomain = NetworkPersistence.directory(server).create(identity);
		managedDomain.publish(checkpoint);
		shutdownCheckpoint = checkpoint;
		Path folder = Path.of("p2-persistence"); Files.createDirectories(folder);
		try (var writer = Executors.newSingleThreadExecutor()) {
			var nativeStorage = new DimensionDataStorage(folder.toFile(), DataFixers.getDataFixer(), registries);
			var directory = new NetworkDirectory(folder, registries, writer, nativeStorage);
			var domain = directory.create(identity); domain.publish(checkpoint); nativeStorage.save(); directory.flush();
			require(domain.persistedRevision() == 1 && !domain.isDirty(), "No durable-save receipt");
			var fresh = new NetworkDirectory(folder, registries, writer, new DimensionDataStorage(folder.toFile(), DataFixers.getDataFixer(), registries));
			var restored = fresh.loadExisting(identity); require(checkpoint.equals(restored.checkpoint()), "Checkpoint round-trip lost a domain");
			var recoveredLedger = ProductLedger.restore(policy, 8, restored.checkpoint().ledger());
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
		report.addProperty("networkCheckpointRoundTrip", true); report.addProperty("pendingWorkAndUnknownTransferRecovery", true);
		report.addProperty("strictRegistryAndComponentRead", true); report.addProperty("acknowledgedNativeSavedData", true);
	}
	static void verifyShutdown(MinecraftServer server, JsonObject report) throws Exception {
		require(shutdownCheckpoint != null, "Shutdown fixture was not initialized");
		Path file = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data")
				.resolve("productivebeesgenesis_network_" + shutdownCheckpoint.identity().networkId() + ".dat");
		var root = net.minecraft.nbt.NbtIo.readCompressed(file, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
		var restored = NetworkCheckpointCodec.forRegistries(server.registryAccess()).decode(root.getCompound("data"));
		require(shutdownCheckpoint.equals(restored), "Normal shutdown did not persist the latest network checkpoint");
		report.addProperty("normalShutdownCheckpointSaved", true); shutdownCheckpoint = null;
	}
}
