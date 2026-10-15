package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.*;
import com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.*;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.ayoshiko.productivebeesgenesis.apiculture.policy.SchedulerCheckpoint;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalScope;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class FactoryCentrifugeCheckpointTest {
	private static final UUID MEMBER = new UUID(41, 3);
	private static final ProductKey INPUT = new ProductKey(ProductKey.Kind.ITEM, net.minecraft.resources.ResourceLocation.parse("test:comb"), new CompoundTag());
	private static final ProductKey OUTPUT = new ProductKey(ProductKey.Kind.ITEM, net.minecraft.resources.ResourceLocation.parse("test:iron"), new CompoundTag());
	private static final NetworkCheckpointCodec CODEC = new NetworkCheckpointCodec(key -> {});
	@BeforeAll static void version() { SharedConstants.tryDetectVersion(); }
	private static NetworkCheckpoint fixture(String machine) {
		int lanes = CentrifugeWorkLayout.lanes(machine);
		var tag = new CompoundTag(); var extra = new CompoundTag();
		tag.putString("machine", machine); tag.put("upgrades", new CompoundTag());
		tag.putLong("energy", 10000); tag.putLong("energyCapacity", 10000);
		extra.putInt("factoryProcesses", lanes); extra.putIntArray("nativeProgress", new int[lanes]);
		extra.putIntArray("productivebeesgenesis_pb_progress", new int[lanes]);
		var pending = new ListTag(); var held = new CompoundTag(); held.putInt("process", lanes - 1);
		held.putString("retained", "已生成结果只保留一份"); pending.add(held);
		extra.put("productivebeesgenesis_pb_committed_pending", pending); tag.put("extra", extra);
		var assets = new AssetImage(tag); var identity = CheckpointTestData.identity();
		var claim = new MemberClaim(identity.networkId(), MEMBER, UUID.randomUUID(), new MemberCapabilitySnapshot.Origin("minecraft:overworld", 2, 64, 1), machine);
		var sealed = new OwnedMachineRecord(claim, OwnedMachineRecord.Phase.SEALED, assets, assets.fingerprint(), "");
		var owned = sealed.phase(OwnedMachineRecord.Phase.OWNED);
		return new NetworkCheckpoint(identity, 0, 0, new LedgerCheckpoint(0, Map.of(INPUT, ProductAmount.of(100)), List.of()),
				List.of(), Set.of(), List.of(), List.of(), SchedulerCheckpoint.EMPTY)
				.withOwnership(sealed).withOwnership(owned).withOwnership(owned.withCentrifuge(new CentrifugeWorkState(MEMBER, 0, lanes, 10000, 10000, Map.of())));
	}
	@Test void everyLaneKeepsFeesFrozenResultsAndSealedAssetsAcrossUpgradeAndRestart() {
		var policy = new ProductPolicyRegistry(new ProductPolicySnapshot(0,
				List.of(new AllowedProductDescriptor(INPUT, "test", "test:comb"), new AllowedProductDescriptor(OUTPUT, "test", "test:comb")), List.of()));
		var plan = new CentrifugeRecipePlan("test:comb", 0, 0, INPUT, 4, 2, 3, 1, 0, List.of(new CentrifugeRecipePlan.Output(OUTPUT, 2, 2, 1)));
		for (var machine : CentrifugeWorkLayout.MACHINES.subList(1, CentrifugeWorkLayout.MACHINES.size())) {
			var root = fixture(machine); var original = root.ownedMachines().get(MEMBER).returnImage(); int lanes = CentrifugeWorkLayout.lanes(machine);
			for (int lane = 0; lane < lanes; lane++) root = root.applyCentrifuge(MEMBER, CentrifugeWorkTransaction.assign(
					root.ownedMachines().get(MEMBER).centrifuge(), root.ledger(), policy, lane, plan, 2, lane));
			for (int lane = 0; lane < lanes; lane++) root = root.applyCentrifuge(MEMBER, CentrifugeWorkTransaction.advance(
					root.ownedMachines().get(MEMBER).centrifuge(), root.ledger(), lane, 1, true, true));
			root = CODEC.decode(NetworkCheckpointCodec.encode(root)); var before = root.ownedMachines().get(MEMBER);
			var upgraded = MemberUpgradeChange.speed(before, 1);
			assertEquals(before.centrifuge().jobs(), upgraded.candidate().centrifuge().jobs());
			assertEquals(original.copy().get("extra"), upgraded.candidate().assets().copy().get("extra"));
			root = root.exchangeUpgrade(upgraded);
			assertEquals(10000 - lanes * 6, root.ownedMachines().get(MEMBER).centrifuge().energy());
			for (int lane = 0; lane < lanes; lane++) {
				root = root.applyCentrifuge(MEMBER, CentrifugeWorkTransaction.advance(root.ownedMachines().get(MEMBER).centrifuge(), root.ledger(), lane, 3, true, true));
				root = root.applyCentrifuge(MEMBER, CentrifugeWorkTransaction.freeze(root.ownedMachines().get(MEMBER).centrifuge(), root.ledger(), lane));
				root = CODEC.decode(NetworkCheckpointCodec.encode(root));
				var result = CentrifugeWorkTransaction.settle(root.ownedMachines().get(MEMBER).centrifuge(), root.ledger(), lane, 0);
				root = root.applyCentrifuge(MEMBER, result); assertSame(root, root.applyCentrifuge(MEMBER, result));
			}
			assertEquals(ProductAmount.of(100 - 2L * lanes), root.ledger().balances().get(INPUT));
			assertEquals(ProductAmount.of(4L * lanes), root.ledger().balances().get(OUTPUT));
			var record = root.ownedMachines().get(MEMBER);
			assertEquals(10000 - 24L * lanes, record.centrifuge().energy());
			assertEquals(original.copy().get("extra"), record.returnImage().copy().get("extra"));
			assertTrue(TerminalScope.CENTRIFUGE.accepts(machine)); assertFalse(TerminalScope.APIARY.accepts(machine));
			assertSame(record, root.ownedMachines().workEntry(WorkKey.Kind.CENTRIFUGE_RECIPE, null, false));
			var receipt = record.phase(OwnedMachineRecord.Phase.RETURNING).phase(OwnedMachineRecord.Phase.RETURNED);
			assertTrue(receipt.assets().isEmpty());
		}
	}
	@Test void factoryIdentityLayoutAndLegacyPartialWorkCannotBeForged() {
		var root = fixture("productivebeesgenesis:ultimate_mek_centrifuge_factory"); var record = root.ownedMachines().get(MEMBER);
		var foreign = new MemberClaim(record.claim().network(), MEMBER, record.claim().transfer(), record.claim().origin(), "productivebeesgenesis:basic_mek_centrifuge_factory");
		assertThrows(IllegalArgumentException.class, () -> new OwnedMachineRecord(foreign, record.phase(), record.assets(), record.fingerprint(), "", null, record.centrifuge()));
		for (var field : List.of("nativeProgress", "productivebeesgenesis_pb_progress")) {
			var image = record.returnImage().copy(); var progress = new int[9]; progress[8] = 1; image.getCompound("extra").putIntArray(field, progress);
			assertThrows(IllegalArgumentException.class, () -> CentrifugeAssetProjection.validateMigration(new AssetImage(image), record.centrifuge()));
		}
		var malformed = NetworkCheckpointCodec.encode(root); malformed.getList("ownership", 10).getCompound(0).getCompound("centrifuge").putInt("lanes", 7);
		assertThrows(IllegalArgumentException.class, () -> CODEC.decode(malformed));
	}
}
