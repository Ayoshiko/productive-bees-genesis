package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.*;
import com.ayoshiko.productivebeesgenesis.apiculture.policy.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

final class CheckpointTestData {
	static final ProductKey RAW = key("raw");
	static final ProductKey PRODUCT = key("product");
	static final NetworkCheckpointCodec CODEC = new NetworkCheckpointCodec(key -> {
		if (!key.id().getNamespace().equals("test")) throw new IllegalArgumentException("Missing content");
	});
	static ProductKey key(String id) { return new ProductKey(ProductKey.Kind.ITEM, ResourceLocation.parse("test:" + id), new CompoundTag()); }
	static NetworkIdentity identity() {
		return new NetworkIdentity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 2,
				new MemberCapabilitySnapshot.Origin("minecraft:overworld", 1, 64, 1));
	}
	static ProductPolicyRegistry policy(long revision) {
		return new ProductPolicyRegistry(new ProductPolicySnapshot(revision, List.of(
				new AllowedProductDescriptor(RAW, "test:adapter", "test:recipe"),
				new AllowedProductDescriptor(PRODUCT, "test:adapter", "test:recipe")), List.of()));
	}
	static NetworkCheckpoint rich(NetworkIdentity identity, long revision) {
		var pending = new LedgerCheckpoint.Pending(UUID.randomUUID(), 3, LedgerTransaction.State.PAID,
				Map.of(RAW, ProductAmount.of(7)), Map.of(PRODUCT, ProductAmount.of(2)));
		// 每次夹具都会生成新的事务身份，账本版本必须随之推进。
		var ledger = new LedgerCheckpoint(Math.addExact(8, revision), Map.of(RAW, ProductAmount.of(BigInteger.ONE.shiftLeft(256)), PRODUCT, ProductAmount.of(5)), List.of(pending));
		var transfer = new TransferStaging.View(UUID.randomUUID(), "test-endpoint", PRODUCT, TransferStaging.Direction.EXPORT,
				19, ProductAmount.of(19), TransferStaging.Phase.UNKNOWN, "unknown external outcome");
		var work = new WorkCapacity(new WorkKey(WorkKey.Kind.CENTRIFUGE_RECIPE, "test:recipe", 3, "default"), 128, 30, 40, 500, 2.0, 0.5, Map.of("stack", 3));
		var member = new MemberCapabilitySnapshot(UUID.randomUUID(), 5, "test:machine", new MemberCapabilitySnapshot.Origin("minecraft:overworld", 2, 64, 1),
				MemberCapabilitySnapshot.Availability.OFFLINE, 0, 2, List.of(work));
		var lane = new VirtualLaneState(member.memberId(), 5, 1, work, 13);
		var reserves = new ReservePolicy(ReservePolicy.Scope.LOCAL_PROCESSING,
				new ReservePolicy.Layer(ReserveLimit.NONE, Map.of(new ProductMatcher(ProductMatcher.Mode.EXACT, PRODUCT), ReserveLimit.ALL)), ReservePolicy.Layer.NONE);
		var goal = new ProcessingRule("goal", 9, true, 100, 3, 12, new ProcessingRule.Goal(PRODUCT, ProductAmount.of(10), ProductAmount.of(20)), reserves);
		var disabled = new ProcessingRule("disabled", 2, false, 0, 1, 1, new ProcessingRule.Tag(ProductKey.Kind.ITEM, ResourceLocation.parse("test:tag")), reserves);
		var scheduler = new SchedulerCheckpoint(List.of(goal, disabled), ProcessingRuleScheduler.Mode.FAIR, Map.of("goal", true), "goal", 2);
		return new NetworkCheckpoint(identity, revision, 3, ledger, List.of(transfer), Set.of(new ProductPolicyRegistry.Discovery("test:dynamic", PRODUCT)),
				List.of(member), List.of(lane), scheduler);
	}
}
