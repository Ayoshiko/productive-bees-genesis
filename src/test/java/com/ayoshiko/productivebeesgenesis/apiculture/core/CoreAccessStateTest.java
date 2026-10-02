package com.ayoshiko.productivebeesgenesis.apiculture.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;

class CoreAccessStateTest {
	private final UUID owner = UUID.randomUUID(), controller = UUID.randomUUID(), guest = UUID.randomUUID();

	@Test void upgradePermissionRequiresGuestAndRevocationDoesNotRegrantIt() {
		var state = new CoreAccessState();
		assertEquals(CoreAccessState.Change.INVALID, state.changeUpgrades(owner, guest, true));
		state.change(owner, guest, true); var ordinary = state.sessionToken();
		assertFalse(state.allowsUpgrades(guest));
		assertEquals(CoreAccessState.Change.CHANGED, state.changeUpgrades(owner, guest, true));
		assertTrue(state.allowsUpgrades(guest)); assertNotSame(ordinary, state.sessionToken());
		var granted = state.sessionToken();
		assertEquals(CoreAccessState.Change.UNCHANGED, state.changeUpgrades(owner, guest, true));
		assertSame(granted, state.sessionToken());
		state.changeUpgrades(owner, guest, false);
		assertTrue(state.allows(guest)); assertFalse(state.allowsUpgrades(guest)); assertNotSame(granted, state.sessionToken());
		state.changeUpgrades(owner, guest, true); state.change(owner, guest, false); state.change(owner, guest, true);
		assertFalse(state.allowsUpgrades(guest)); assertTrue(state.upgradeGuests().isEmpty());
	}
	@Test void legacyAccessNeverAcquiresUpgradePermissionAndNewFormatRestoresIt() {
		var state = new CoreAccessState(); state.change(owner, guest, true);
		var old = (CompoundTag) state.save(owner, controller); old.putInt("version", 1); old.remove("upgrades");
		var legacy = CoreAccessState.read(old, owner, controller);
		assertTrue(legacy.valid()); assertTrue(legacy.allows(guest)); assertFalse(legacy.allowsUpgrades(guest));
		state.changeUpgrades(owner, guest, true);
		var saved = state.save(owner, controller); var restored = CoreAccessState.read(saved, owner, controller);
		assertTrue(restored.valid()); assertTrue(restored.allowsUpgrades(guest));
		assertEquals(saved, restored.save(owner, controller)); assertNotSame(state.sessionToken(), restored.sessionToken());
	}
	@Test void corruptUpgradeGrantsDenyTheWholeAccessTableAndPreserveData() {
		var state = new CoreAccessState(); state.change(owner, guest, true); state.changeUpgrades(owner, guest, true);
		for (int fault = 0; fault < 4; fault++) {
			var saved = (CompoundTag) state.save(owner, controller);
			if (fault == 0) saved.remove("upgrades");
			if (fault == 1) saved.putString("upgrades", "invalid");
			if (fault == 2) saved.getList("upgrades", Tag.TAG_INT_ARRAY).add(NbtUtils.createUUID(guest));
			if (fault == 3) saved.getList("upgrades", Tag.TAG_INT_ARRAY).set(0, NbtUtils.createUUID(UUID.randomUUID()));
			var restored = CoreAccessState.read(saved, owner, controller);
			assertFalse(restored.valid()); assertFalse(restored.allows(guest)); assertFalse(restored.allowsUpgrades(guest));
			assertEquals(saved, restored.save(owner, controller));
		}
	}

	@Test void defaultsAndNoOpDoNotInvalidateSessions() {
		var state = new CoreAccessState(); var token = state.sessionToken();
		assertFalse(state.allows(guest)); assertFalse(state.allows(null));
		assertNull(state.save(owner, controller));
		assertEquals(CoreAccessState.Change.UNCHANGED, state.change(owner, guest, false));
		assertSame(token, state.sessionToken());
		assertEquals(CoreAccessState.Change.INVALID, state.change(owner, owner, true));
		assertEquals(CoreAccessState.Change.INVALID, state.change(owner, new UUID(0, 0), true));
		assertSame(token, state.sessionToken());
	}

	@Test void revokeAndRegrantNeverResurrectOldMenuToken() {
		var state = new CoreAccessState();
		assertEquals(CoreAccessState.Change.CHANGED, state.change(owner, guest, true));
		var granted = state.sessionToken(); assertTrue(state.allows(guest));
		assertEquals(CoreAccessState.Change.UNCHANGED, state.change(owner, guest, true));
		assertSame(granted, state.sessionToken());
		state.change(owner, guest, false); var revoked = state.sessionToken();
		assertFalse(state.allows(guest)); assertNotSame(granted, revoked);
		state.change(owner, guest, true);
		assertTrue(state.allows(guest)); assertNotSame(granted, state.sessionToken());
		assertNotSame(revoked, state.sessionToken());
	}

	@Test void boundedTablePreservesExistingAccessOnOverflow() {
		var state = new CoreAccessState();
		for (int i = 1; i <= CoreAccessState.MAX_GUESTS; i++) state.change(owner, new UUID(1, i), true);
		var before = state.save(owner, controller); var token = state.sessionToken();
		assertEquals(CoreAccessState.Change.FULL, state.change(owner, guest, true));
		assertEquals(before, state.save(owner, controller)); assertSame(token, state.sessionToken());
		state.change(owner, new UUID(1, 1), false);
		assertEquals(CoreAccessState.Change.CHANGED, state.change(owner, guest, true));
		assertThrows(UnsupportedOperationException.class, () -> state.guests().clear());
	}

	@Test void roundTripBindsIdentityAndCreatesFreshSession() {
		var state = new CoreAccessState(); state.change(owner, guest, true);
		var saved = state.save(owner, controller);
		var restored = CoreAccessState.read(saved, owner, controller);
		assertTrue(restored.valid()); assertTrue(restored.allows(guest));
		assertEquals(saved, restored.save(owner, controller));
		assertNotSame(state.sessionToken(), restored.sessionToken());
		assertFalse(CoreAccessState.read(saved, UUID.randomUUID(), controller).allows(guest));
		assertFalse(CoreAccessState.read(saved, owner, UUID.randomUUID()).allows(guest));
		assertTrue(CoreAccessState.read(null, owner, controller).valid());
	}

	@Test void corruptRootAndUnknownVersionStayQuarantinedAndPreserved() {
		var state = new CoreAccessState(); state.change(owner, guest, true);
		var unknown = (CompoundTag) state.save(owner, controller); unknown.putInt("version", 3);
		for (Tag raw : new Tag[]{unknown, StringTag.valueOf("broken")}) {
			var restored = CoreAccessState.read(raw, owner, controller);
			assertFalse(restored.valid()); assertFalse(restored.allows(guest));
			assertFalse(restored.failure().isEmpty());
			assertEquals(CoreAccessState.Change.INVALID, restored.change(owner, guest, true));
			assertEquals(raw, restored.save(owner, controller));
		}
		var quarantined = CoreAccessState.read(unknown, owner, controller);
		unknown.putInt("version", 99);
		assertEquals(3, ((CompoundTag) quarantined.save(owner, controller)).getInt("version"));
	}

	@Test void duplicateWrongTypeAndMalformedUuidDoNotPartiallyGrant() {
		var state = new CoreAccessState(); state.change(owner, guest, true);
		for (Tag bad : new Tag[]{NbtUtils.createUUID(guest), new IntArrayTag(new int[]{1, 2}), StringTag.valueOf("wrong")}) {
			var tag = (CompoundTag) state.save(owner, controller);
			var list = tag.getList("guests", Tag.TAG_INT_ARRAY);
			if (bad instanceof StringTag) { list = new ListTag(); tag.put("guests", list); }
			list.add(bad);
			var restored = CoreAccessState.read(tag, owner, controller);
			assertFalse(restored.valid()); assertFalse(restored.allows(guest));
			assertEquals(tag, restored.save(owner, controller));
		}
	}
}
