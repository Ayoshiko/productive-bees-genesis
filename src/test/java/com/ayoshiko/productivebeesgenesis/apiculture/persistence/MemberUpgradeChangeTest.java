package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin;
import com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.*;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.NativeUpgradeCounts;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mekanism.api.SerializationConstants;
import mekanism.api.Upgrade;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MemberUpgradeChangeTest {
	private static final ProductKey INPUT = key("input"), OUTPUT = key("output");
	@BeforeAll static void version() { SharedConstants.tryDetectVersion(); }
	private static ProductKey key(String name) { return new ProductKey(ProductKey.Kind.ITEM, ResourceLocation.parse("test:" + name), new CompoundTag()); }
	private static NetworkCheckpoint fixture() {
		var checkpoint = NetworkCheckpoint.empty(CheckpointTestData.identity());
		for (int x = 2; x <= 3; x++) {
			var claim = new MemberClaim(checkpoint.identity().networkId(), UUID.randomUUID(), UUID.randomUUID(),
					new Origin("minecraft:overworld", x, 64, 1), "productivebeesgenesis:mek_centrifuge");
			var tag = new CompoundTag(); tag.putLong("energy", 1000); tag.putLong("energyCapacity", 1000);
			var component = new CompoundTag(); component.putString("unchanged", "input/output slot metadata");
			tag.put("upgrades", component); tag.put("extra", new CompoundTag());
			var image = new AssetImage(tag); var sealed = new OwnedMachineRecord(claim, OwnedMachineRecord.Phase.SEALED, image, image.fingerprint(), "");
			var owned = sealed.phase(OwnedMachineRecord.Phase.OWNED);
			checkpoint = checkpoint.withOwnership(sealed).withOwnership(owned).withOwnership(owned.withCentrifuge(
					new CentrifugeWorkState(claim.member(), 0, 1, 1000, 1000, Map.of())));
		}
		var ledger = new LedgerCheckpoint(0, Map.of(INPUT, ProductAmount.of(10)), List.of());
		return new NetworkCheckpoint(checkpoint.identity(), checkpoint.revision(), 0, ledger, checkpoint.transfers(), checkpoint.discoveries(),
				checkpoint.members(), checkpoint.lanes(), checkpoint.scheduler()).restoredOwnership(checkpoint.ownedMachines());
	}
	private static UUID first(NetworkCheckpoint checkpoint) { return checkpoint.ownedMachines().activeValues().iterator().next().claim().member(); }
	private static int count(OwnedMachineRecord record) { return NativeUpgradeCounts.read(record.assets().copy().getCompound("upgrades")).getOrDefault(Upgrade.SPEED, 0); }
	@Test void isolatedCandidateChangesOneMemberAndCannotReplayOrBypassTheProof() {
		var before = fixture(); var member = first(before); var old = before.ownedMachines().get(member);
		var change = MemberUpgradeChange.speed(old, 2);
		assertEquals(0, count(old)); assertEquals(2, change.installed());
		assertThrows(IllegalArgumentException.class, () -> before.withOwnership(change.candidate()));
		var after = before.exchangeUpgrade(change);
		assertSame(before.ledger(), after.ledger()); assertSame(before.energy(), after.energy()); assertSame(before.scheduler(), after.scheduler());
		assertEquals(old.centrifuge().revision() + 1, after.ownedMachines().get(member).centrifuge().revision());
		assertEquals(old.claim(), change.candidate().claim());
		for (var record : before.ownedMachines().values()) if (!record.claim().member().equals(member)) assertSame(record, after.ownedMachines().get(record.claim().member()));
		assertThrows(IllegalArgumentException.class, () -> after.exchangeUpgrade(change));
		var back = after.exchangeUpgrade(MemberUpgradeChange.speed(after.ownedMachines().get(member), -2));
		assertEquals(old.assets(), back.ownedMachines().get(member).assets());
		assertThrows(IllegalArgumentException.class, () -> back.exchangeUpgrade(change));
	}
	@Test void currentUpgradeCountAndRevisionSurviveStrictRestoreAndReturn() {
		var before = fixture(); var member = first(before);
		var after = before.exchangeUpgrade(MemberUpgradeChange.speed(before.ownedMachines().get(member), 3));
		var restored = CheckpointTestData.CODEC.decode(NetworkCheckpointCodec.encode(after));
		assertEquals(after, restored);
		var record = restored.ownedMachines().get(member); assertEquals(3, count(record));
		var returned = record.phase(OwnedMachineRecord.Phase.RETURNING);
		assertEquals(3, NativeUpgradeCounts.read(returned.assets().copy().getCompound("upgrades")).get(Upgrade.SPEED));
		assertEquals("input/output slot metadata", returned.assets().copy().getCompound("upgrades").getString("unchanged"));
		assertTrue(returned.phase(OwnedMachineRecord.Phase.RETURNED).assets().isEmpty());
	}
	@Test void heldPartialAndFrozenWorkKeepTheOriginalPriceSeedAndOutputs() {
		var policy = new ProductPolicyRegistry(new ProductPolicySnapshot(0, List.of(new AllowedProductDescriptor(OUTPUT, "test", "test:recipe")), List.of()));
		for (int progress : new int[] {0, 2, 5}) {
			var before = fixture(); var member = first(before); var state = before.ownedMachines().get(member).centrifuge();
			var plan = new CentrifugeRecipePlan("test:recipe", 0, 0, INPUT, 5, 2, 7, 1, 0,
					List.of(new CentrifugeRecipePlan.Output(OUTPUT, 3, 3, 1)));
			before = before.applyCentrifuge(member, CentrifugeWorkTransaction.assign(state, before.ledger(), policy, 0, plan, 2, 42));
			if (progress > 0) before = before.applyCentrifuge(member, CentrifugeWorkTransaction.advance(before.ownedMachines().get(member).centrifuge(), before.ledger(), 0, progress, true, true));
			if (progress == 5) before = before.applyCentrifuge(member, CentrifugeWorkTransaction.freeze(before.ownedMachines().get(member).centrifuge(), before.ledger(), 0));
			var record = before.ownedMachines().get(member); var job = record.centrifuge().jobs().get(0);
			var after = before.exchangeUpgrade(MemberUpgradeChange.speed(record, 1));
			assertSame(job, after.ownedMachines().get(member).centrifuge().jobs().get(0));
			assertSame(before.ledger(), after.ledger()); assertEquals(record.centrifuge().energy(), after.ownedMachines().get(member).centrifuge().energy());
			if (progress < 5) {
				after = after.applyCentrifuge(member, CentrifugeWorkTransaction.advance(after.ownedMachines().get(member).centrifuge(), after.ledger(), 0, 100, true, true));
				after = after.applyCentrifuge(member, CentrifugeWorkTransaction.freeze(after.ownedMachines().get(member).centrifuge(), after.ledger(), 0));
			}
			after = after.applyCentrifuge(member, CentrifugeWorkTransaction.settle(after.ownedMachines().get(member).centrifuge(), after.ledger(), 0, 0));
			assertEquals(930, after.ownedMachines().get(member).centrifuge().energy());
			assertEquals(ProductAmount.of(8), after.ledger().balances().get(INPUT)); assertEquals(ProductAmount.of(6), after.ledger().balances().get(OUTPUT));
			assertTrue(after.ownedMachines().get(member).centrifuge().drained());
		}
	}
	@Test void malformedCountsAndInvalidDeltasCannotModifyAuthority() {
		var before = fixture(); var record = before.ownedMachines().get(first(before));
		for (int delta : new int[] {0, -1, 65, Upgrade.SPEED.getMax() + 1}) assertThrows(IllegalArgumentException.class, () -> MemberUpgradeChange.speed(record, delta));
		var component = new CompoundTag(); var list = new ListTag();
		list.add(Upgrade.SPEED.getTag(1)); list.add(Upgrade.SPEED.getTag(2)); component.put(SerializationConstants.UPGRADES, list);
		assertThrows(IllegalArgumentException.class, () -> NativeUpgradeCounts.read(component));
		for (int ordinal : new int[] {-1, Upgrade.values().length}) {
			var entry = Upgrade.SPEED.getTag(1); entry.putInt(SerializationConstants.TYPE, ordinal);
			var invalid = new ListTag(); invalid.add(entry); component.put(SerializationConstants.UPGRADES, invalid);
			assertThrows(IllegalArgumentException.class, () -> NativeUpgradeCounts.read(component));
		}
		var entry = Upgrade.SPEED.getTag(1); entry.putString(SerializationConstants.AMOUNT, "1");
		var wrongType = new ListTag(); wrongType.add(entry); component.put(SerializationConstants.UPGRADES, wrongType);
		assertThrows(IllegalArgumentException.class, () -> NativeUpgradeCounts.read(component));
		assertEquals(0, count(record)); assertSame(record, before.ownedMachines().get(record.claim().member()));
	}
}
